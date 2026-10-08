// Tests for gradients and the paint bucket.
#include "gradient_fill.h"
#include "selection_mask.h"
#include "tile_store.h"

#include <gtest/gtest.h>

#include <array>
#include <cmath>
#include <cstring>
#include <set>
#include <string>

namespace {

using croptop::PixelFormat;
using croptop::TileStore;
namespace pt = croptop::paint;

std::array<double, 4> px(const TileStore& s, std::uint32_t x, std::uint32_t y) {
    const auto t = s.readTile({static_cast<std::int32_t>(x / 256), static_cast<std::int32_t>(y / 256)});
    std::array<double, 4> v{};
    if (!t) return v;
    const auto i = (static_cast<std::size_t>(y % 256) * 256 + x % 256) * s.bytes_per_pixel();
    for (std::size_t c = 0; c < 4; ++c) {
        if (s.format() == PixelFormat::RGBA8) {
            v[c] = (*t)[i + c] / 255.0;
        } else if (s.format() == PixelFormat::RGBA16) {
            std::uint16_t q;
            std::memcpy(&q, t->data() + i + c * 2, 2);
            v[c] = q / 65535.0;
        } else {
            float f;
            std::memcpy(&f, t->data() + i + c * 4, 4);
            v[c] = f;
        }
    }
    return v;
}

pt::GradientSpec blackToWhite(pt::GradientShape shape, pt::Point s, pt::Point e) {
    pt::GradientSpec spec;
    spec.shape = shape;
    spec.start = s;
    spec.end = e;
    spec.colors = {{0.0, 0, 0, 0}, {1.0, 1, 1, 1}};
    spec.dither = false;
    return spec;
}

TEST(Gradient, SixteenBitLinearGradientHasNoBanding) {
    TileStore layer(PixelFormat::RGBA16, 4000, 2);
    auto spec = blackToWhite(pt::GradientShape::Linear, {0, 1}, {4000, 1});
    spec.dither = true;
    std::string error;
    ASSERT_TRUE(pt::renderGradient(layer, spec, nullptr, error)) << error;
    std::set<std::uint16_t> levels;
    double worst_step = 0;
    double previous = px(layer, 0, 0)[0];
    for (std::uint32_t x = 0; x < 4000; ++x) {
        const auto v = px(layer, x, 0)[0];
        levels.insert(static_cast<std::uint16_t>(std::lround(v * 65535)));
        worst_step = std::max(worst_step, std::fabs(v - previous) * 65535);
        previous = v;
        ASSERT_NEAR(v, (x + 0.5) / 4000, 3.0 / 65535) << x;
    }
    EXPECT_GT(levels.size(), 3900u) << "one distinct level per pixel, far beyond 8-bit's 256";
    EXPECT_LE(worst_step, 65535.0 / 4000 + 3) << "no step larger than the gradient slope plus dither";
}

TEST(Gradient, DitheringPreservesOpaqueAlpha) {
    std::string error;
    for (const auto format : {PixelFormat::RGBA8, PixelFormat::RGBA16}) {
        TileStore layer(format, 512, 1);
        auto opaque = blackToWhite(pt::GradientShape::Linear, {0, 0}, {512, 0});
        ASSERT_TRUE(pt::renderGradient(layer, opaque, nullptr, error)) << error;
        for (std::uint32_t x = 0; x < 512; ++x) {
            EXPECT_EQ(px(layer, x, 0)[3], 1.0) << "alpha must stay fully opaque for " << x;
        }
    }
}

TEST(Gradient, ShapesFollowTheirDefinitions) {
    std::string error;
    const auto value = [&](pt::GradientShape shape, pt::Point s, pt::Point e, std::uint32_t x, std::uint32_t y) {
        TileStore layer(PixelFormat::RGBA16, 400, 400);
        EXPECT_TRUE(pt::renderGradient(layer, blackToWhite(shape, s, e), nullptr, error)) << error;
        return px(layer, x, y)[0];
    };
    const double tol = 2.0 / 65535;
    // Linear: projection onto start→end, clamped outside.
    EXPECT_NEAR(value(pt::GradientShape::Linear, {100, 0}, {300, 0}, 199, 50), 0.4975, tol);
    EXPECT_NEAR(value(pt::GradientShape::Linear, {100, 0}, {300, 0}, 10, 50), 0.0, tol);
    EXPECT_NEAR(value(pt::GradientShape::Linear, {100, 0}, {300, 0}, 390, 50), 1.0, tol);
    // Radial: distance / length.
    EXPECT_NEAR(value(pt::GradientShape::Radial, {200, 200}, {300, 200}, 229, 239), std::hypot(29.5, 39.5) / 100, tol);
    // Reflected: symmetric about the start.
    EXPECT_NEAR(value(pt::GradientShape::Reflected, {200, 0}, {300, 0}, 149, 9), 0.505, tol);
    EXPECT_NEAR(value(pt::GradientShape::Reflected, {200, 0}, {300, 0}, 250, 9), 0.505, tol);
    // Diamond: L1 distance in the gradient frame.
    EXPECT_NEAR(value(pt::GradientShape::Diamond, {200, 200}, {300, 200}, 219, 229), (19.5 + 29.5) / 100, tol);
    // Angle: a quarter turn clockwise on screen (y down) from +x is +y.
    EXPECT_NEAR(value(pt::GradientShape::Angle, {200, 200}, {300, 200}, 199, 299), 0.25, 0.005);
    EXPECT_NEAR(value(pt::GradientShape::Angle, {200, 200}, {300, 200}, 100, 199), 0.5, 0.005);
}

TEST(Gradient, StopsInterpolationOpacityAndComposition) {
    std::string error;
    TileStore layer(PixelFormat::RGBA8, 256, 1);
    auto spec = blackToWhite(pt::GradientShape::Linear, {0, 0}, {256, 0});
    ASSERT_TRUE(pt::renderGradient(layer, spec, nullptr, error));
    EXPECT_NEAR(px(layer, 127, 0)[0], 0.5, 1.0 / 255) << "perceptual midpoint is 50% encoded";
    spec.interpolation = pt::Interpolation::LinearLight;
    ASSERT_TRUE(pt::renderGradient(layer, spec, nullptr, error));
    EXPECT_NEAR(px(layer, 127, 0)[0], 0.735, 2.0 / 255) << "linear-light midpoint encodes to ~188";

    // Three colour stops and an opacity ramp over an opaque red layer.
    TileStore red(PixelFormat::RGBA8, 300, 1);
    croptop::TileBuffer tile(red.tile_byte_size(), 0);
    for (std::size_t i = 0; i < tile.size(); i += 4) {
        tile[i] = 255;
        tile[i + 3] = 255;
    }
    ASSERT_TRUE(red.writeTile({0, 0}, tile));
    ASSERT_TRUE(red.writeTile({1, 0}, tile));
    pt::GradientSpec stops;
    stops.start = {0, 0};
    stops.end = {300, 0};
    stops.colors = {{0.5, 0, 0, 1}, {0.0, 0, 1, 0}, {1.0, 1, 1, 1}};  // Unsorted on purpose.
    stops.opacities = {{0.0, 0.0F}, {1.0, 1.0F}};
    stops.dither = false;
    ASSERT_TRUE(pt::renderGradient(red, stops, nullptr, error));
    const auto left = px(red, 0, 0);
    EXPECT_NEAR(left[0], 1.0, 1.0 / 255) << "zero opacity leaves red";
    const auto middle = px(red, 149, 0);  // t ≈ 0.5: blue at ~50% over red.
    EXPECT_NEAR(middle[0], 0.5, 2.0 / 255);
    EXPECT_NEAR(middle[2], 0.5, 2.0 / 255);
    EXPECT_NEAR(px(red, 299, 0)[1], 1.0, 2.0 / 255);

    // Float layers store linear light.
    TileStore hdr(PixelFormat::RGBA32F, 256, 1);
    ASSERT_TRUE(pt::renderGradient(hdr, blackToWhite(pt::GradientShape::Linear, {0, 0}, {256, 0}), nullptr, error));
    EXPECT_NEAR(px(hdr, 127, 0)[0], 0.214, 0.01) << "encoded 0.5 stored as linear ~0.214";

    EXPECT_FALSE(pt::renderGradient(layer, blackToWhite(pt::GradientShape::Linear, {5, 5}, {5, 5}), nullptr, error));
    pt::GradientSpec empty = spec;
    empty.colors.clear();
    EXPECT_FALSE(pt::renderGradient(layer, empty, nullptr, error));
    TileStore alpha_only(PixelFormat::A16, 10, 10);
    EXPECT_FALSE(pt::renderGradient(alpha_only, spec, nullptr, error));
}

TEST(Gradient, SelectionLimitsAndWeightsThePaint) {
    std::string error;
    TileStore layer(PixelFormat::RGBA8, 600, 300);
    const auto mask = croptop::selection::rasterizeRect(600, 300, {100.5, 0, 300, 300}, true);
    pt::GradientSpec white;
    white.start = {0, 0};
    white.end = {600, 0};
    white.colors = {{0.0, 1, 1, 1}};
    white.dither = false;
    ASSERT_TRUE(pt::renderGradient(layer, white, &mask, error)) << error;
    EXPECT_EQ(px(layer, 50, 10)[3], 0.0) << "outside the selection";
    EXPECT_NEAR(px(layer, 100, 10)[3], 0.5, 1.0 / 255) << "half-covered edge pixel";
    EXPECT_EQ(px(layer, 200, 10)[3], 1.0);
    EXPECT_EQ(layer.readTile({2, 0}), nullptr) << "unselected tiles stay sparse";
    TileStore wrong(PixelFormat::A16, 10, 10);
    EXPECT_FALSE(pt::renderGradient(layer, white, &wrong, error));
}

TEST(PaintBucket, FillsTheContiguousMatchingRegionInsideTheSelection) {
    // Left half blue, right half yellow, with a blue island on the right.
    TileStore layer(PixelFormat::RGBA8, 300, 100);
    for (int tx = 0; tx < 2; ++tx) {
        croptop::TileBuffer tile(layer.tile_byte_size(), 0);
        for (std::uint32_t y = 0; y < 100; ++y) {
            for (std::uint32_t x = 0; x < 256; ++x) {
                const auto gx = tx * 256 + x;
                if (gx >= 300) continue;
                const bool blue = gx < 150 || (gx >= 250 && gx < 270 && y >= 40 && y < 60);
                auto* p = tile.data() + (static_cast<std::size_t>(y) * 256 + x) * 4;
                p[0] = blue ? 20 : 230;
                p[1] = blue ? 40 : 220;
                p[2] = blue ? 200 : 30;
                p[3] = 255;
            }
        }
        ASSERT_TRUE(layer.writeTile({tx, 0}, tile));
    }
    std::string error;
    auto contiguous = layer;
    ASSERT_TRUE(pt::bucketFill(contiguous, layer, 10, 10, {1, 0, 0, 1}, 10, true, nullptr, 1.0F, error)) << error;
    EXPECT_NEAR(px(contiguous, 100, 50)[0], 1.0, 1e-9);
    EXPECT_NEAR(px(contiguous, 260, 50)[2], 200.0 / 255, 1e-9) << "island is not connected";
    EXPECT_NEAR(px(contiguous, 200, 50)[0], 230.0 / 255, 1e-9) << "yellow untouched";

    auto global = layer;
    ASSERT_TRUE(pt::bucketFill(global, layer, 10, 10, {1, 0, 0, 1}, 10, false, nullptr, 0.5F, error));
    EXPECT_NEAR(px(global, 260, 50)[0], (1.0 + 20.0 / 255) / 2, 1.0 / 255) << "island filled at 50%";

    auto limited = layer;
    const auto mask = croptop::selection::rasterizeRect(300, 100, {0, 0, 50, 100}, false);
    ASSERT_TRUE(pt::bucketFill(limited, layer, 10, 10, {1, 0, 0, 1}, 10, true, &mask, 1.0F, error));
    EXPECT_NEAR(px(limited, 10, 10)[0], 1.0, 1e-9);
    EXPECT_NEAR(px(limited, 100, 10)[0], 20.0 / 255, 1e-9) << "outside the selection";
    EXPECT_FALSE(pt::bucketFill(limited, layer, 10, 10, {1, 0, 0, 1}, 10, true, &mask, 2.0F, error));
}

}  // namespace
