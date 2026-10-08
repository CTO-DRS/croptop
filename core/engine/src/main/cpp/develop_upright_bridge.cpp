#include "develop_upright_bridge.h"

#include <algorithm>
#include <cmath>
#include <limits>

#include "pixel_plane.h"

namespace croptop::geometry::develop_bridge {
namespace {

using Matrix = Matrix3;

void setError(Error& error, ErrorCode code, const char* message) {
    error.code = code;
    error.message = message;
}

bool finite(double value) { return std::isfinite(value); }

bool validDimensions(std::uint32_t width, std::uint32_t height) {
    return width >= 8U && height >= 8U && width <= 65535U && height <= 65535U;
}

Matrix multiply(const Matrix& a, const Matrix& b) {
    Matrix out{};
    for (std::size_t row = 0; row < 3; ++row)
        for (std::size_t column = 0; column < 3; ++column)
            for (std::size_t k = 0; k < 3; ++k)
                out[row * 3 + column] += a[row * 3 + k] * b[k * 3 + column];
    return out;
}

bool validSegments(const std::vector<Segment>& segments, std::uint32_t width, std::uint32_t height) {
    for (const auto& segment : segments) {
        if (!finite(segment.x1) || !finite(segment.y1) || !finite(segment.x2) || !finite(segment.y2)) return false;
        if (segment.x1 < 0 || segment.y1 < 0 || segment.x2 < 0 || segment.y2 < 0 ||
            segment.x1 > width || segment.x2 > width || segment.y1 > height || segment.y2 > height ||
            !(segment.length() > 1e-6)) return false;
    }
    return true;
}

bool validManual(const ManualTransform& manual) {
    return finite(manual.vertical) && manual.vertical >= -100 && manual.vertical <= 100 &&
           finite(manual.horizontal) && manual.horizontal >= -100 && manual.horizontal <= 100 &&
           finite(manual.rotate) && manual.rotate >= -45 && manual.rotate <= 45 &&
           finite(manual.aspect) && manual.aspect >= -100 && manual.aspect <= 100 &&
           finite(manual.scale) && manual.scale >= 10 && manual.scale <= 400 &&
           finite(manual.x_offset) && manual.x_offset >= -100 && manual.x_offset <= 100 &&
           finite(manual.y_offset) && manual.y_offset >= -100 && manual.y_offset <= 100;
}

bool validMatrix(const Matrix& matrix) {
    return std::all_of(matrix.begin(), matrix.end(), finite);
}

std::optional<Result> packageResult(std::uint32_t width, std::uint32_t height, const Request& request,
                                    const UprightResult& upright, double focal_px, Error& error) {
    Result result;
    result.focal_length_px = focal_px;
    result.vertical_found = upright.vertical_found;
    result.horizontal_found = upright.horizontal_found;
    result.tilt_degrees = upright.tilt_degrees;
    result.segments_used = upright.segments;
    result.note = upright.note;

    if (!validMatrix(upright.matrix) || !finite(upright.tilt_degrees)) {
        setError(error, ErrorCode::TransformFailed, "upright produced a non-finite transform");
        return std::nullopt;
    }

    if (request.auto_crop) {
        result.crop_bounds = suggestedCrop(width, height, upright.matrix);
    } else {
        result.crop_bounds = {0, 0, static_cast<double>(width), static_cast<double>(height)};
    }
    const auto& crop = result.crop_bounds;
    if (!finite(crop.left) || !finite(crop.top) || !finite(crop.right) || !finite(crop.bottom) ||
        crop.right <= crop.left || crop.bottom <= crop.top) {
        setError(error, ErrorCode::TransformFailed, "upright transform has no valid crop rectangle");
        return std::nullopt;
    }

    const double crop_width = std::floor(crop.right - crop.left + 1e-9);
    const double crop_height = std::floor(crop.bottom - crop.top + 1e-9);
    if (!(crop_width >= 1 && crop_height >= 1) || crop_width > 65535 || crop_height > 65535) {
        setError(error, ErrorCode::TransformFailed, "upright crop dimensions are outside the supported range");
        return std::nullopt;
    }
    result.width = static_cast<std::uint32_t>(crop_width);
    result.height = static_cast<std::uint32_t>(crop_height);
    const Matrix translate_crop{1, 0, -crop.left, 0, 1, -crop.top, 0, 0, 1};
    result.source_to_output = multiply(translate_crop, upright.matrix);
    if (!validMatrix(result.source_to_output)) {
        setError(error, ErrorCode::TransformFailed, "crop composition produced a non-finite transform");
        return std::nullopt;
    }
    if (!request.exif_focal_length_35mm) {
        result.warnings.push_back({WarningCode::DefaultFocalLength, "EXIF 35 mm-equivalent focal length is unavailable; using the engine default"});
    }
    if (request.mode != Mode::Manual && !result.vertical_found && !result.horizontal_found) {
        result.warnings.push_back({WarningCode::NoUsableLines, "no usable line families were found; no automatic correction was applied"});
    } else if (request.mode == Mode::Full && result.vertical_found && !result.horizontal_found) {
        result.warnings.push_back({WarningCode::PartialCorrection, "vertical correction was applied, but no horizontal family was found"});
    }
    error = {};
    return result;
}

bool validateRequest(std::uint32_t width, std::uint32_t height, const Request& request, double& focal_px, Error& error) {
    if (!validDimensions(width, height)) {
        setError(error, ErrorCode::InvalidInput, "geometry requires dimensions from 8 through 65535 pixels");
        return false;
    }
    if (static_cast<unsigned>(request.mode) > static_cast<unsigned>(Mode::Manual)) {
        setError(error, ErrorCode::InvalidInput, "unknown upright mode");
        return false;
    }
    if (request.lens_distortion_correction || request.chromatic_aberration_correction) {
        setError(error, ErrorCode::Unsupported, "lens distortion and chromatic-aberration correction are not part of the upright geometry engine");
        return false;
    }
    if (request.exif_focal_length_35mm) {
        if (!finite(*request.exif_focal_length_35mm) ||
            (focal_px = focalLengthPixels(*request.exif_focal_length_35mm, width, height)) <= 0.0) {
            setError(error, ErrorCode::InvalidInput, "EXIF focal length must be a finite 35 mm-equivalent value from 4 through 2000 mm");
            return false;
        }
    } else {
        focal_px = 0.0;
    }
    if (request.mode == Mode::Manual && !validManual(request.manual)) {
        setError(error, ErrorCode::InvalidInput, "manual geometry value is outside its supported slider range");
        return false;
    }
    if (request.mode == Mode::Guided && !validSegments(request.guides, width, height)) {
        setError(error, ErrorCode::InvalidInput, "guided lines must be finite, non-degenerate, and inside the source image");
        return false;
    }
    return true;
}

UprightMode mapMode(Mode mode) {
    switch (mode) {
        case Mode::Level: return UprightMode::Level;
        case Mode::Vertical: return UprightMode::Vertical;
        case Mode::Full: return UprightMode::Full;
        case Mode::Auto: return UprightMode::Auto;
        case Mode::Guided: return UprightMode::Guided;
        case Mode::Manual: return UprightMode::Level;
    }
    return UprightMode::Auto;
}

}  // namespace

std::optional<Result> solve(std::uint32_t width, std::uint32_t height, const std::vector<Segment>& detected_segments,
                            const Request& request, Error& error) {
    error = {};
    double focal_px = 0;
    if (!validateRequest(width, height, request, focal_px, error)) return std::nullopt;
    if (request.mode != Mode::Guided && request.mode != Mode::Manual &&
        !validSegments(detected_segments, width, height)) {
        setError(error, ErrorCode::InvalidInput, "detected lines must be finite, non-degenerate, and inside the source image");
        return std::nullopt;
    }

    UprightResult upright_result;
    if (request.mode == Mode::Manual) {
        auto manual = request.manual;
        manual.focal_length_px = focal_px;
        upright_result.matrix = manualTransform(width, height, manual);
    } else {
        UprightSpec spec;
        spec.mode = mapMode(request.mode);
        spec.focal_length_px = focal_px;
        if (request.mode == Mode::Guided) spec.guides = request.guides;
        upright_result = uprightFromSegments(width, height, detected_segments, spec);
    }
    return packageResult(width, height, request, upright_result, focal_px, error);
}

std::optional<Result> analyze(const TileStore& source, const Request& request, Error& error) {
    error = {};
    double focal_px = 0;
    if (!validDimensions(source.width(), source.height())) {
        setError(error, ErrorCode::InvalidInput, "geometry requires dimensions from 8 through 65535 pixels");
        return std::nullopt;
    }
    if (!paint::detail::isRgba(source.format())) {
        setError(error, ErrorCode::Unsupported, "upright line detection requires an RGBA source image");
        return std::nullopt;
    }
    if (!validateRequest(source.width(), source.height(), request, focal_px, error)) return std::nullopt;
    if (request.mode == Mode::Manual || request.mode == Mode::Guided) {
        return solve(source.width(), source.height(), {}, request, error);
    }
    std::string detection_error;
    const auto segments = detectSegments(source, detection_error);
    if (!detection_error.empty()) {
        setError(error, ErrorCode::DetectionFailed, detection_error.c_str());
        return std::nullopt;
    }
    return solve(source.width(), source.height(), segments, request, error);
}

std::optional<TileStore> render(const TileStore& source, const Result& result, resample::Filter filter,
                                Error& error, unsigned worker_threads) {
    error = {};
    if (!paint::detail::isRgba(source.format())) {
        setError(error, ErrorCode::Unsupported, "geometry render requires an RGBA source image");
        return std::nullopt;
    }
    if (source.width() < 1U || source.height() < 1U || result.width < 1U || result.height < 1U ||
        result.width > 65535U || result.height > 65535U || !validMatrix(result.source_to_output)) {
        setError(error, ErrorCode::InvalidInput, "geometry render received invalid dimensions or matrix");
        return std::nullopt;
    }
    auto pixels = resample::warp(source, result.source_to_output, result.width, result.height, filter, error.message, worker_threads);
    if (!pixels) {
        error.code = ErrorCode::TransformFailed;
        return std::nullopt;
    }
    return pixels;
}

}  // namespace croptop::geometry::develop_bridge
