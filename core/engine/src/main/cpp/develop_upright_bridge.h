#pragma once

// Adapter from Develop/Editor geometry requests to the upright engine.
// Coordinates are document pixel-edge coordinates, x right and y down. Input matrices map
// source points to transformed points; `source_to_output` additionally subtracts crop.left/top
// and is ready for resample::warp. EXIF focal metadata is 35 mm-equivalent millimetres.

#include <cstdint>
#include <optional>
#include <string>
#include <vector>

#include "crop_geometry.h"
#include "resample.h"
#include "upright.h"

namespace croptop::geometry::develop_bridge {

enum class Mode : std::uint8_t { Level, Vertical, Full, Auto, Guided, Manual };
enum class ErrorCode : std::uint8_t { InvalidInput, Unsupported, DetectionFailed, TransformFailed };
enum class WarningCode : std::uint8_t { DefaultFocalLength, NoUsableLines, PartialCorrection };

struct Error final {
    ErrorCode code = ErrorCode::InvalidInput;
    std::string message;
};

struct Warning final {
    WarningCode code = WarningCode::NoUsableLines;
    std::string message;
};

struct Request final {
    Mode mode = Mode::Auto;
    std::optional<double> exif_focal_length_35mm;
    std::vector<Segment> guides;  // Guided mode; endpoints in source document pixel-edge coordinates.
    ManualTransform manual;
    bool auto_crop = true;
    bool lens_distortion_correction = false;
    bool chromatic_aberration_correction = false;
};

struct Result final {
    Matrix3 source_to_output{1, 0, 0, 0, 1, 0, 0, 0, 1};
    Rect crop_bounds{0, 0, 0, 0};  // Rectangle in transformed source coordinates.
    std::uint32_t width = 0;
    std::uint32_t height = 0;
    double focal_length_px = 0;
    bool vertical_found = false;
    bool horizontal_found = false;
    double tilt_degrees = 0;
    std::size_t segments_used = 0;
    std::string note;
    std::vector<Warning> warnings;
};

/** Calculate an upright/manual transform from already available line segments. */
std::optional<Result> solve(std::uint32_t width, std::uint32_t height, const std::vector<Segment>& detected_segments,
                            const Request& request, Error& error);

/** Detect lines from the image unless the request is Guided or Manual, then solve. */
std::optional<Result> analyze(const TileStore& source, const Request& request, Error& error);

/** Apply the computed source-to-output transform and crop with the requested resampling filter. */
std::optional<TileStore> render(const TileStore& source, const Result& result, resample::Filter filter,
                                Error& error, unsigned worker_threads = 0);

}  // namespace croptop::geometry::develop_bridge
