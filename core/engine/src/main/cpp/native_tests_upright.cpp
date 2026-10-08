// Tests for Geometry / Upright.
#include "upright.h"
#include "resample.h"
#include "tile_store.h"

#include <gtest/gtest.h>

#include <array>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <string>

namespace {

namespace geo = croptop::geometry;
using croptop::PixelFormat;
using croptop::TileStore;

/** A building-like grid: black lines every 50 px on white, 900 × 700. */
TileStore grid(std::uint32_t w = 900, std::uint32_t h = 700) {
    TileStore s(PixelFormat::RGBA8, w, h);
    for (std::uint32_t ty = 0; ty * 256 < h; ++ty)
        for (std::uint32_t tx = 0; tx * 256 < w; ++tx) {
            croptop::TileBuffer t(s.tile_byte_size(), 0);
            for (std::uint32_t y = 0; y < 256; ++y)
                for (std::uint32_t x = 0; x < 256; ++x) {
                    const auto gx = tx * 256 + x, gy = ty * 256 + y;
                    const bool line = (gx % 50 < 3 && gx > 60 && gx < w - 60) || (gy % 50 < 3 && gy > 60 && gy < h - 60);
                    const std::uint8_t v = line ? 0 : 255;
                    std::uint8_t* p = t.data() + (static_cast<std::size_t>(y) * 256 + x) * 4;
                    p[0] = p[1] = p[2] = v;
                    p[3] = 255;
                }
            EXPECT_TRUE(s.writeTile({static_cast<std::int32_t>(tx), static_cast<std::int32_t>(ty)}, t));
        }
    return s;
}

geo::Matrix3 multiply(const geo::Matrix3& a, const geo::Matrix3& b) {
    geo::Matrix3 r{};
    for (int i = 0; i < 3; ++i)
        for (int j = 0; j < 3; ++j)
            for (int k = 0; k < 3; ++k) r[static_cast<std::size_t>(i * 3 + j)] += a[static_cast<std::size_t>(i * 3 + k)] * b[static_cast<std::size_t>(k * 3 + j)];
    return r;
}

TileStore distort(const TileStore& image, const geo::Matrix3& m) {
    std::string error;
    auto out = croptop::resample::warp(image, m, image.width(), image.height(), croptop::resample::Filter::Bilinear, error);
    EXPECT_TRUE(out) << error;
    return std::move(*out);
}

/** A photo taken by a camera tilted by (ax, ay) degrees about x and y: K·R·K⁻¹ with the principal point kept at
 * the image centre (f = 1.2 × the longer side, as upright assumes). */
geo::Matrix3 cameraTilt(std::uint32_t w, std::uint32_t h, double ax, double ay) {
    const double f = 1.2 * std::max(w, h), cx = w / 2.0, cy = h / 2.0, a = ax * 3.14159265358979323846 / 180.0, b = ay * 3.14159265358979323846 / 180.0;
    const geo::Matrix3 rx{1, 0, 0, 0, std::cos(a), -std::sin(a), 0, std::sin(a), std::cos(a)};
    const geo::Matrix3 ry{std::cos(b), 0, std::sin(b), 0, 1, 0, -std::sin(b), 0, std::cos(b)};
    const geo::Matrix3 k{f, 0, cx, 0, f, cy, 0, 0, 1}, kinv{1 / f, 0, -cx / f, 0, 1 / f, -cy / f, 0, 0, 1};
    return multiply(k, multiply(multiply(ry, rx), kinv));
}

/** How projective a homography still is (0 for affine), scaled to pixels at the image's size. */
double projectiveness(const geo::Matrix3& m, double size) { return (std::fabs(m[6]) + std::fabs(m[7])) * size / std::fabs(m[8]); }

TEST(Upright, DetectsTheGridLines) {
    std::string error;
    const auto segments = geo::detectSegments(grid(), error);
    ASSERT_TRUE(error.empty()) << error;
    int vertical = 0, horizontal = 0;
    for (const auto& s : segments) {
        // Crossing lines split each grid line into ~47 px pieces between intersections.
        if (std::fabs(s.x2 - s.x1) < 2.0 && s.length() > 40) ++vertical;
        if (std::fabs(s.y2 - s.y1) < 2.0 && s.length() > 40) ++horizontal;
    }
    std::printf("[upright] %zu segments (%d long vertical, %d long horizontal)\n", segments.size(), vertical, horizontal);
    EXPECT_GE(vertical, 10);
    EXPECT_GE(horizontal, 8);
}

TEST(Upright, LevelUndoesARotation) {
    for (const double degrees : {4.0, -6.0}) {
        geo::ManualTransform rotate;
        rotate.rotate = degrees;
        const auto m0 = geo::manualTransform(900, 700, rotate);
        const auto rotated = distort(grid(), m0);
        std::string error;
        geo::UprightSpec spec;
        spec.mode = geo::UprightMode::Level;
        const auto result = geo::upright(rotated, spec, error);
        ASSERT_TRUE(result) << error;
        const auto combined = multiply(result->matrix, m0);
        const double residual = std::atan2(combined[3], combined[0]) * 180.0 / 3.14159265358979323846;
        std::printf("[upright] level of %.1f° leaves %.3f°\n", degrees, residual);
        EXPECT_NEAR(residual, 0.0, 0.3);
    }
}

TEST(Upright, VerticalMakesConvergingVerticalsParallel) {
    const auto m0 = cameraTilt(900, 700, 15.0, 0.0);  // keystoned verticals
    const auto keystoned = distort(grid(), m0);
    std::string error;
    geo::UprightSpec spec;
    spec.mode = geo::UprightMode::Vertical;
    const auto result = geo::upright(keystoned, spec, error);
    ASSERT_TRUE(result) << error;
    EXPECT_TRUE(result->vertical_found);
    EXPECT_TRUE(result->note.empty()) << result->note;
    // The correction cancels the tilt: vertical lines map to vertical lines again.
    const auto combined = multiply(result->matrix, m0);
    const auto map = [&](double x, double y) {
        const double w = combined[6] * x + combined[7] * y + combined[8];
        return std::array<double, 2>{(combined[0] * x + combined[1] * y + combined[2]) / w, (combined[3] * x + combined[4] * y + combined[5]) / w};
    };
    for (const double x : {150.0, 450.0, 750.0}) {
        const auto top = map(x, 100), bottom = map(x, 600);
        EXPECT_NEAR(top[0], bottom[0], 3.0) << "line at x = " << x;
    }
    std::printf("[upright] vertical: tilt %.1f° from %zu segments\n", result->tilt_degrees, result->segments);
    EXPECT_NEAR(result->tilt_degrees, 15.0, 0.5);
}

TEST(Upright, FullAlsoMakesHorizontalsParallel) {
    const auto m0 = cameraTilt(900, 700, 12.0, 12.0);
    const auto skewed = distort(grid(), m0);
    std::string error;
    geo::UprightSpec spec;
    spec.mode = geo::UprightMode::Full;
    const auto result = geo::upright(skewed, spec, error);
    ASSERT_TRUE(result) << error;
    EXPECT_TRUE(result->vertical_found);
    EXPECT_TRUE(result->horizontal_found);
    const auto combined = multiply(result->matrix, m0);
    EXPECT_LT(projectiveness(combined, 900), 0.02) << "no perspective left";
}

TEST(Upright, AutoFallsBackToLevelForExtremeTilts) {
    const auto keystoned = distort(grid(), cameraTilt(900, 700, 32.0, 0.0));
    std::string error;
    const auto result = geo::upright(keystoned, {}, error);
    ASSERT_TRUE(result) << error;
    EXPECT_FALSE(result->note.empty());
    EXPECT_LT(result->tilt_degrees, 10.0);
    const auto blank = geo::uprightFromSegments(900, 700, {}, {});
    EXPECT_EQ(blank.matrix, (geo::Matrix3{1, 0, 0, 0, 1, 0, 0, 0, 1})) << "nothing detected, nothing changes";
}

TEST(Upright, GuidedUsesTheUserLines) {
    const auto m0 = cameraTilt(900, 700, 15.0, 0.0);
    const auto map = [&](double x, double y) {
        const double w = m0[6] * x + m0[7] * y + m0[8];
        return std::array<double, 2>{(m0[0] * x + m0[1] * y + m0[2]) / w, (m0[3] * x + m0[4] * y + m0[5]) / w};
    };
    geo::UprightSpec spec;
    spec.mode = geo::UprightMode::Guided;
    for (const double x : {200.0, 700.0}) {
        const auto a = map(x, 100), b = map(x, 600);
        spec.guides.push_back({a[0], a[1], b[0], b[1]});
    }
    const auto result = geo::uprightFromSegments(900, 700, {}, spec);
    EXPECT_TRUE(result.vertical_found);
    EXPECT_NEAR(result.tilt_degrees, 15.0, 0.5);
}

TEST(Upright, FocalLengthFromExif) {
    // A 43.27 mm-equivalent lens maps the frame diagonal onto the image diagonal.
    EXPECT_NEAR(geo::focalLengthPixels(43.266615305567875, 3000, 4000), 5000.0, 1e-6);
    EXPECT_NEAR(geo::focalLengthPixels(24.0, 6000, 4000), 24.0 / 43.266615305567875 * std::hypot(6000.0, 4000.0), 1e-6);
    EXPECT_EQ(geo::focalLengthPixels(0.0, 100, 100), 0.0) << "missing EXIF → default";
    EXPECT_EQ(geo::focalLengthPixels(NAN, 100, 100), 0.0);
}

TEST(Upright, ManualTransformAndCrop) {
    const geo::ManualTransform none;
    EXPECT_EQ(geo::manualTransform(900, 700, none), (geo::Matrix3{1, 0, 0, 0, 1, 0, 0, 0, 1}));
    const auto full = geo::suggestedCrop(900, 700, geo::manualTransform(900, 700, none));
    EXPECT_NEAR(full.left, 0.0, 1e-6);
    EXPECT_NEAR(full.right, 900.0, 1e-6);
    geo::ManualTransform rotate;
    rotate.rotate = 10.0;
    const auto crop = geo::suggestedCrop(900, 700, geo::manualTransform(900, 700, rotate));
    EXPECT_GT(crop.left, 50.0) << "a rotated image leaves less room";
    EXPECT_NEAR((crop.right - crop.left) / (crop.bottom - crop.top), 900.0 / 700.0, 1e-6);
    geo::ManualTransform keystone;
    keystone.vertical = 50.0;
    const auto m = geo::manualTransform(900, 700, keystone);
    // Positive vertical tilts the top away: the top edge becomes narrower than the bottom (or vice versa) — perspective.
    const auto width_at = [&](double y) {
        const auto x = [&](double px) {
            const double w = m[6] * px + m[7] * y + m[8];
            return (m[0] * px + m[1] * y + m[2]) / w;
        };
        return x(900) - x(0);
    };
    EXPECT_GT(std::fabs(width_at(0) - width_at(700)), 50.0);
}

}  // namespace
