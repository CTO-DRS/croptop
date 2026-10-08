// Develop/editor adapter contract tests for upright geometry.
#include "develop_upright_bridge.h"

#include <gtest/gtest.h>

#include <algorithm>
#include <array>
#include <chrono>
#include <cmath>
#include <cstdint>
#include <limits>
#include <string>
#include <vector>

namespace {
namespace bridge = croptop::geometry::develop_bridge;
namespace geo = croptop::geometry;
using croptop::PixelFormat;
using croptop::TileBuffer;
using croptop::TileStore;

constexpr double kPi = 3.14159265358979323846;

TileStore grid(std::uint32_t width = 900, std::uint32_t height = 700) {
    TileStore image(PixelFormat::RGBA8, width, height);
    for (std::uint32_t ty = 0; ty * croptop::kTileEdge < height; ++ty) {
        for (std::uint32_t tx = 0; tx * croptop::kTileEdge < width; ++tx) {
            TileBuffer tile(image.tile_byte_size(), 0);
            for (std::uint32_t y = 0; y < croptop::kTileEdge; ++y) {
                for (std::uint32_t x = 0; x < croptop::kTileEdge; ++x) {
                    const auto gx = tx * croptop::kTileEdge + x;
                    const auto gy = ty * croptop::kTileEdge + y;
                    if (gx >= width || gy >= height) continue;
                    const bool line = (gx % 50U < 3U && gx > 60U && gx < width - 60U) ||
                                      (gy % 50U < 3U && gy > 60U && gy < height - 60U);
                    const auto value = static_cast<std::uint8_t>(line ? 0 : 255);
                    auto* pixel = tile.data() + (static_cast<std::size_t>(y) * croptop::kTileEdge + x) * 4U;
                    pixel[0] = pixel[1] = pixel[2] = value;
                    pixel[3] = 255;
                }
            }
            EXPECT_TRUE(image.writeTile({static_cast<std::int32_t>(tx), static_cast<std::int32_t>(ty)}, tile));
        }
    }
    return image;
}

geo::Matrix3 multiply(const geo::Matrix3& a, const geo::Matrix3& b) {
    geo::Matrix3 out{};
    for (std::size_t row = 0; row < 3; ++row)
        for (std::size_t column = 0; column < 3; ++column)
            for (std::size_t k = 0; k < 3; ++k) out[row * 3 + column] += a[row * 3 + k] * b[k * 3 + column];
    return out;
}

geo::Matrix3 cameraTilt(std::uint32_t width, std::uint32_t height, double ax, double ay) {
    const double f = 1.2 * std::max(width, height), cx = width / 2.0, cy = height / 2.0;
    const double x = ax * kPi / 180.0, y = ay * kPi / 180.0;
    const geo::Matrix3 rx{1, 0, 0, 0, std::cos(x), -std::sin(x), 0, std::sin(x), std::cos(x)};
    const geo::Matrix3 ry{std::cos(y), 0, std::sin(y), 0, 1, 0, -std::sin(y), 0, std::cos(y)};
    const geo::Matrix3 k{f, 0, cx, 0, f, cy, 0, 0, 1};
    const geo::Matrix3 ki{1 / f, 0, -cx / f, 0, 1 / f, -cy / f, 0, 0, 1};
    return multiply(k, multiply(multiply(ry, rx), ki));
}

croptop::crop::Point map(const geo::Matrix3& m, double x, double y) {
    const double w = m[6] * x + m[7] * y + m[8];
    return {(m[0] * x + m[1] * y + m[2]) / w, (m[3] * x + m[4] * y + m[5]) / w};
}

std::vector<geo::Segment> transformedVerticalGuides(const geo::Matrix3& m) {
    std::vector<geo::Segment> lines;
    for (const double x : {200.0, 450.0, 700.0}) {
        const auto a = map(m, x, 250);
        const auto b = map(m, x, 450);
        lines.push_back({a.x, a.y, b.x, b.y});
    }
    return lines;
}

TEST(DevelopUprightBridge, MapsAutomaticModesAndManualInputs) {
    const std::vector<geo::Segment> mixed_lines{{200, 20, 235, 600}, {520, 10, 555, 610},
                                                {30, 160, 850, 195}, {20, 480, 860, 515}};
    bridge::Error error;
    for (const auto mode : {bridge::Mode::Level, bridge::Mode::Vertical, bridge::Mode::Full, bridge::Mode::Auto}) {
        bridge::Request request;
        request.mode = mode;
        const auto result = bridge::solve(900, 700, mixed_lines, request, error);
        ASSERT_TRUE(result) << error.message;
        EXPECT_GT(result->width, 0U);
        EXPECT_GT(result->height, 0U);
        EXPECT_TRUE(std::all_of(result->source_to_output.begin(), result->source_to_output.end(), [](double v) { return std::isfinite(v); }));
    }

    bridge::Request manual;
    manual.mode = bridge::Mode::Manual;
    manual.manual.rotate = 8.0;
    manual.manual.vertical = 15.0;
    manual.manual.scale = 110.0;
    manual.exif_focal_length_35mm = 50.0;
    const auto corrected = bridge::solve(900, 700, {}, manual, error);
    ASSERT_TRUE(corrected) << error.message;
    EXPECT_NEAR(corrected->focal_length_px, geo::focalLengthPixels(50, 900, 700), 1e-9);
    EXPECT_EQ(corrected->width, static_cast<std::uint32_t>(std::floor(corrected->crop_bounds.right - corrected->crop_bounds.left + 1e-9)));
    EXPECT_TRUE(corrected->warnings.empty());
}

TEST(DevelopUprightBridge, GuidedPerspectiveLinesAreCorrectedAndCropped) {
    const auto source = grid();
    const auto perspective = cameraTilt(source.width(), source.height(), 8, 0);
    bridge::Request request;
    request.mode = bridge::Mode::Guided;
    request.guides = transformedVerticalGuides(perspective);
    bridge::Error error;
    const auto result = bridge::solve(source.width(), source.height(), {}, request, error);
    ASSERT_TRUE(result) << error.message;
    EXPECT_TRUE(result->vertical_found);
    EXPECT_FALSE(result->horizontal_found);
    const auto corrected = multiply(result->source_to_output, perspective);
    for (const double x : {150.0, 450.0, 750.0}) {
        const auto a = map(corrected, x, 250);
        const auto b = map(corrected, x, 450);
        EXPECT_NEAR(a.x, b.x, 3.0);
    }
    EXPECT_GT(result->crop_bounds.left, 0.0);
    EXPECT_GT(result->crop_bounds.top, 0.0);
    EXPECT_LT(result->width, source.width());
    EXPECT_LT(result->height, source.height());
    EXPECT_NEAR(static_cast<double>(result->width) / result->height,
                static_cast<double>(source.width()) / source.height(), 0.01);

    const auto rendered = bridge::render(source, *result, croptop::resample::Filter::Bilinear, error);
    ASSERT_TRUE(rendered) << error.message;
    EXPECT_EQ(rendered->width(), result->width);
    EXPECT_EQ(rendered->height(), result->height);
}

TEST(DevelopUprightBridge, AnalyzeRotatedSyntheticImageAndReportDegenerateLines) {
    const auto original = grid();
    croptop::geometry::ManualTransform rotation;
    rotation.rotate = 6.0;
    const auto rotated_matrix = geo::manualTransform(original.width(), original.height(), rotation);
    std::string warp_error;
    auto rotated = croptop::resample::warp(original, rotated_matrix, original.width(), original.height(),
                                           croptop::resample::Filter::Bilinear, warp_error);
    ASSERT_TRUE(rotated) << warp_error;

    bridge::Request request;
    request.mode = bridge::Mode::Level;
    bridge::Error error;
    const auto analysis_start = std::chrono::steady_clock::now();
    const auto analyzed = bridge::analyze(*rotated, request, error);
    const auto analysis_us = std::chrono::duration_cast<std::chrono::microseconds>(std::chrono::steady_clock::now() - analysis_start).count();
    ASSERT_TRUE(analyzed) << error.message;
    EXPECT_GT(analyzed->segments_used, 0U);
    const auto recovered = multiply(analyzed->source_to_output, rotated_matrix);
    const auto line_start = map(recovered, 100, 350);
    const auto line_end = map(recovered, 800, 350);
    const double residual_angle = std::atan2(line_end.y - line_start.y, line_end.x - line_start.x) * 180.0 / kPi;
    EXPECT_NEAR(residual_angle, 0.0, 0.4);
    std::printf("[upright-bridge] rotated grid recovered residual %.3f deg from %zu segments in %lld us\n",
                residual_angle, analyzed->segments_used, static_cast<long long>(analysis_us));

    bridge::Request guided;
    guided.mode = bridge::Mode::Guided;
    guided.guides = {{20, 20, 20, 20}};
    EXPECT_FALSE(bridge::solve(900, 700, {}, guided, error));
    EXPECT_EQ(error.code, bridge::ErrorCode::InvalidInput);
    guided.guides = {{NAN, 0, 10, 10}};
    EXPECT_FALSE(bridge::solve(900, 700, {}, guided, error));
    EXPECT_EQ(error.code, bridge::ErrorCode::InvalidInput);
    guided.guides = {{-1, 10, 100, 500}};
    EXPECT_FALSE(bridge::solve(900, 700, {}, guided, error));
    EXPECT_EQ(error.code, bridge::ErrorCode::InvalidInput);
    bridge::Request auto_request;
    auto_request.mode = bridge::Mode::Auto;
    const auto no_lines = bridge::solve(900, 700, {}, auto_request, error);
    ASSERT_TRUE(no_lines) << error.message;
    const geo::Matrix3 identity{1, 0, 0, 0, 1, 0, 0, 0, 1};
    for (std::size_t i = 0; i < identity.size(); ++i) EXPECT_NEAR(no_lines->source_to_output[i], identity[i], 1e-9);
    ASSERT_FALSE(no_lines->warnings.empty());
    EXPECT_EQ(no_lines->warnings.back().code, bridge::WarningCode::NoUsableLines);
}

TEST(DevelopUprightBridge, CropCanBeDisabledAndUnsupportedInputsAreTyped) {
    bridge::Request request;
    request.mode = bridge::Mode::Manual;
    request.manual.rotate = 10.0;
    bridge::Error error;
    const auto auto_crop = bridge::solve(900, 700, {}, request, error);
    ASSERT_TRUE(auto_crop) << error.message;
    EXPECT_LT(auto_crop->width, 900U);
    request.auto_crop = false;
    const auto uncropped = bridge::solve(900, 700, {}, request, error);
    ASSERT_TRUE(uncropped) << error.message;
    EXPECT_EQ(uncropped->width, 900U);
    EXPECT_EQ(uncropped->height, 700U);
    EXPECT_EQ(uncropped->crop_bounds.left, 0.0);

    request.lens_distortion_correction = true;
    EXPECT_FALSE(bridge::solve(900, 700, {}, request, error));
    EXPECT_EQ(error.code, bridge::ErrorCode::Unsupported);
    request = {};
    request.mode = bridge::Mode::Manual;
    request.manual.horizontal = 101;
    EXPECT_FALSE(bridge::solve(900, 700, {}, request, error));
    EXPECT_EQ(error.code, bridge::ErrorCode::InvalidInput);
    request.manual.horizontal = 0;
    request.exif_focal_length_35mm = std::numeric_limits<double>::infinity();
    EXPECT_FALSE(bridge::solve(900, 700, {}, request, error));
    EXPECT_EQ(error.code, bridge::ErrorCode::InvalidInput);

    TileStore gray(PixelFormat::A8, 32, 32);
    EXPECT_FALSE(bridge::analyze(gray, {}, error));
    EXPECT_EQ(error.code, bridge::ErrorCode::Unsupported);
}

TEST(DevelopUprightBridge, RepeatedSolveIsDeterministicAndReportsLatency) {
    const std::vector<geo::Segment> guides{{200, 30, 230, 650}, {520, 20, 550, 640}};
    bridge::Request request;
    request.mode = bridge::Mode::Vertical;
    request.exif_focal_length_35mm = 70.0;
    bridge::Error error;
    const auto start = std::chrono::steady_clock::now();
    const auto first = bridge::solve(900, 700, guides, request, error);
    ASSERT_TRUE(first) << error.message;
    for (int iteration = 0; iteration < 100; ++iteration) {
        const auto repeated = bridge::solve(900, 700, guides, request, error);
        ASSERT_TRUE(repeated) << error.message;
        EXPECT_EQ(repeated->source_to_output, first->source_to_output);
        EXPECT_EQ(repeated->width, first->width);
        EXPECT_EQ(repeated->height, first->height);
    }
    const auto elapsed = std::chrono::duration_cast<std::chrono::microseconds>(std::chrono::steady_clock::now() - start).count();
    std::printf("[upright-bridge] 101 deterministic 900x700 solves: %lld us total, %.2f us/solve\n",
                static_cast<long long>(elapsed), static_cast<double>(elapsed) / 101.0);
}

}  // namespace
