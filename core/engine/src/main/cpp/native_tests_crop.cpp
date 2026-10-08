// Tests for crop geometry.
#include "crop_geometry.h"
#include "resample.h"
#include "tile_store.h"

#include <gtest/gtest.h>

#include <cmath>
#include <cstring>
#include <string>

namespace {

namespace cg = croptop::crop;

cg::Point map(const cg::Matrix& m, cg::Point p) {
    cg::Point out;
    EXPECT_TRUE(cg::apply(m, p, out));
    return out;
}

std::array<double, 9> inverse(const cg::Matrix& m) {
    const auto a = m[0], b = m[1], c = m[2], d = m[3], e = m[4], f = m[5], g = m[6], h = m[7], i = m[8];
    const auto det = a * (e * i - f * h) - b * (d * i - f * g) + c * (d * h - e * g);
    return {(e * i - f * h) / det, (c * h - b * i) / det, (b * f - c * e) / det, (f * g - d * i) / det, (a * i - c * g) / det,
            (c * d - a * f) / det, (d * h - e * g) / det, (b * g - a * h) / det, (a * e - b * d) / det};
}

TEST(CropGeometry, PerspectiveCropMapsQuadCornersOntoTheRectangle) {
    std::string error;
    const std::array<cg::Point, 4> quad = {{{120.5, 80.25}, {910.0, 140.0}, {860.0, 700.0}, {90.0, 640.0}}};
    const auto m = cg::perspectiveCrop(quad, 800, 600, error);
    ASSERT_TRUE(m) << error;
    const std::array<cg::Point, 4> corners = {{{0, 0}, {800, 0}, {800, 600}, {0, 600}}};
    for (std::size_t i = 0; i < 4; ++i) {
        const auto p = map(*m, quad[i]);
        EXPECT_NEAR(p.x, corners[i].x, 1e-6);
        EXPECT_NEAR(p.y, corners[i].y, 1e-6);
    }
    // An axis-aligned quad gives a pure scale + translation.
    const auto rect = *cg::perspectiveCrop({{{10, 20}, {110, 20}, {110, 70}, {10, 70}}}, 200, 100, error);
    EXPECT_NEAR(rect[0], 2.0, 1e-9);
    EXPECT_NEAR(rect[4], 2.0, 1e-9);
    EXPECT_NEAR(rect[6], 0.0, 1e-12);
    EXPECT_NEAR(rect[7], 0.0, 1e-12);
    const auto size = cg::perspectiveCropSize(quad);
    EXPECT_NEAR(size[0], (std::hypot(789.5, 59.75) + std::hypot(770.0, 60.0)) / 2, 1.0);
    // Rejections: bow-tie (non-convex), collinear, non-finite, bad size.
    EXPECT_FALSE(cg::perspectiveCrop({{{0, 0}, {100, 100}, {100, 0}, {0, 100}}}, 10, 10, error));
    EXPECT_FALSE(cg::perspectiveCrop({{{0, 0}, {50, 0}, {100, 0}, {0, 100}}}, 10, 10, error));
    EXPECT_FALSE(cg::perspectiveCrop({{{0, 0}, {NAN, 0}, {100, 100}, {0, 100}}}, 10, 10, error));
    EXPECT_FALSE(cg::perspectiveCrop(quad, 0.5, 10, error));
}

TEST(CropGeometry, StraightenCropsToTheLargestInscribedRectangle) {
    std::string error;
    const auto none = *cg::straighten(600, 400, 0.0, error);
    EXPECT_EQ(none.width, 600u);
    EXPECT_EQ(none.height, 400u);
    for (const double degrees : {3.0, -7.5, 30.0, 90.0}) {
        const auto s = *cg::straighten(600, 400, degrees, error);
        EXPECT_NEAR(double(s.width) / s.height, 1.5, 0.01) << "aspect ratio is kept";
        // Every output corner maps back inside the source image…
        const auto back = inverse(s.matrix);
        for (const auto& corner : {cg::Point{0, 0}, {double(s.width), 0}, {0, double(s.height)}, {double(s.width), double(s.height)}}) {
            const auto p = map(back, corner);
            EXPECT_GE(p.x, -1e-6) << degrees;
            EXPECT_LE(p.x, 600 + 1e-6) << degrees;
            EXPECT_GE(p.y, -1e-6) << degrees;
            EXPECT_LE(p.y, 400 + 1e-6) << degrees;
        }
        // …and the crop is maximal: grown 1% about its center, some corner leaves the image.
        const double ox = s.width / 2.0, oy = s.height / 2.0;
        bool outside = false;
        for (const double dx : {-1.0, 1.0}) {
            for (const double dy : {-1.0, 1.0}) {
                const auto p = map(back, {ox + dx * ox * 1.01, oy + dy * oy * 1.01});
                outside = outside || p.x < -1e-6 || p.y < -1e-6 || p.x > 600 + 1e-6 || p.y > 400 + 1e-6;
            }
        }
        EXPECT_TRUE(outside) << degrees;
    }
    EXPECT_EQ(cg::straighten(600, 400, 90.0, error)->height, 266u) << "400 × (400/600)";
    EXPECT_FALSE(cg::straighten(0, 10, 1.0, error));
    EXPECT_FALSE(cg::straighten(10, 10, NAN, error));
}

TEST(CropGeometry, StraightenedWarpHasNoTransparentCorners) {
    croptop::TileStore image(croptop::PixelFormat::RGBA8, 300, 200);
    croptop::TileBuffer tile(image.tile_byte_size(), 0);
    for (std::size_t i = 0; i < tile.size(); i += 4) {
        tile[i] = 90;
        tile[i + 1] = 140;
        tile[i + 2] = 200;
        tile[i + 3] = 255;
    }
    for (int tx = 0; tx < 2; ++tx) ASSERT_TRUE(image.writeTile({tx, 0}, tile));
    std::string error;
    const auto s = *cg::straighten(300, 200, 12.0, error);
    const auto out = *croptop::resample::warp(image, s.matrix, s.width, s.height, croptop::resample::Filter::Bilinear, error);
    for (const auto [x, y] : {std::pair{0u, 0u}, {s.width - 1, 0u}, {0u, s.height - 1}, {s.width - 1, s.height - 1}}) {
        const auto t = out.readTile({static_cast<std::int32_t>(x / 256), static_cast<std::int32_t>(y / 256)});
        ASSERT_NE(t, nullptr);
        EXPECT_GE((*t)[(static_cast<std::size_t>(y % 256) * 256 + x % 256) * 4 + 3], 250) << x << "," << y;
    }
}

}  // namespace
