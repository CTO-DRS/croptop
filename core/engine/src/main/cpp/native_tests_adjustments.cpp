// Tests for the CPU adjustment kernels.
#include "adjustments.h"
#include "selection_mask.h"
#include "tile_store.h"

#include <gtest/gtest.h>

#include <array>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <random>
#include <string>

#include "pixel_plane.h"

namespace {

namespace aj = croptop::adjust;
using aj::Rgb;
using croptop::PixelFormat;
using croptop::TileStore;

void expectRgb(const Rgb& a, const Rgb& b, float tolerance) {
    for (int c = 0; c < 3; ++c) EXPECT_NEAR(a[static_cast<std::size_t>(c)], b[static_cast<std::size_t>(c)], tolerance) << "channel " << c;
}

float luma(const Rgb& c) { return 0.2126F * c[0] + 0.7152F * c[1] + 0.0722F * c[2]; }

TEST(Adjustments, DefaultsAreTheIdentity) {
    const std::vector<aj::Adjustment> identities{aj::BrightnessContrast{}, aj::Levels{}, aj::Curves{}, aj::Exposure{}, aj::Vibrance{},
                                                 aj::HueSaturation{}, aj::ColorBalance{}, aj::ChannelMixer{}, aj::SelectiveColor{},
                                                 aj::PhotoFilter{{1, 1, 1}, 25, false}};
    std::mt19937 random(1);
    std::uniform_real_distribution<float> unit(0, 1);
    for (std::size_t i = 0; i < identities.size(); ++i) {
        for (int k = 0; k < 50; ++k) {
            const Rgb c{unit(random), unit(random), unit(random)};
            SCOPED_TRACE(i);
            expectRgb(aj::applyPixel(identities[i], c), c, 2e-4F);
        }
    }
}

TEST(Adjustments, CurveMatchesTheModelsMonotoneCubic) {
    aj::Curve s;
    s.points = {{0, 0}, {0.25F, 0.15F}, {0.75F, 0.85F}, {1, 1}};
    EXPECT_FLOAT_EQ(s.evaluate(0.25F), 0.15F);
    EXPECT_FLOAT_EQ(s.evaluate(0.75F), 0.85F);
    EXPECT_NEAR(s.evaluate(0.5F), 0.5F, 1e-6F) << "symmetric S-curve";
    float previous = -1;
    for (int i = 0; i <= 100; ++i) {
        const float y = s.evaluate(i / 100.0F);
        EXPECT_GE(y, previous) << "monotone, never overshoots";
        previous = y;
    }
    aj::Curves curves;
    curves.red.points = {{0, 0}, {1, 0.5F}};
    curves.rgb.points = {{0, 0}, {1, 0.5F}};
    {
        SCOPED_TRACE("channel curve, then composite");
        expectRgb(aj::applyPixel(curves, {1, 1, 1}), {0.25F, 0.5F, 0.5F}, 1e-6F);
    }
}

TEST(Adjustments, LevelsExposureAndBrightnessContrast) {
    aj::Levels levels{0.2F, 0.8F, 2.0F, 0.1F, 0.9F};
    expectRgb(aj::applyPixel(levels, {0.5F, 0.2F, 0.8F}), {0.1F + std::sqrt(0.5F) * 0.8F, 0.1F, 0.9F}, 1e-6F);
    aj::Exposure exposure{1.0F, 0, 1};
    const auto lin = [](float v) { return v <= 0.04045F ? v / 12.92F : std::pow((v + 0.055F) / 1.055F, 2.4F); };
    const auto brighter = aj::applyPixel(exposure, {0.3F, 0.3F, 0.3F});
    EXPECT_NEAR(lin(brighter[0]), 2 * lin(0.3F), 1e-5F) << "+1 EV doubles linear light";
    aj::BrightnessContrast bright{100, 0, false};
    EXPECT_GT(aj::applyPixel(bright, {0.5F, 0.5F, 0.5F})[0], 0.55F);
    EXPECT_EQ(aj::applyPixel(bright, {0, 0, 0})[0], 0.0F) << "black stays black";
    aj::BrightnessContrast contrast{0, 100, false};
    EXPECT_LT(aj::applyPixel(contrast, {0.25F, 0.25F, 0.25F})[0], 0.25F);
    EXPECT_GT(aj::applyPixel(contrast, {0.75F, 0.75F, 0.75F})[0], 0.75F);
    aj::BrightnessContrast legacy{51, 0, true};
    EXPECT_NEAR(aj::applyPixel(legacy, {0.5F, 0.5F, 0.5F})[0], 0.7F, 1e-6F);
}

TEST(Adjustments, HueSaturationTargetsItsRanges) {
    aj::HueSaturation rotate;
    rotate.master.hue = 120;
    expectRgb(aj::applyPixel(rotate, {1, 0, 0}), {0, 1, 0}, 1e-5F);
    aj::HueSaturation reds;
    reds.channels[0].saturation = -100;
    expectRgb(aj::applyPixel(reds, {0.9F, 0.1F, 0.1F}), {0.5F, 0.5F, 0.5F}, 1e-5F);
    {
        SCOPED_TRACE("blue untouched");
        expectRgb(aj::applyPixel(reds, {0.1F, 0.1F, 0.9F}), {0.1F, 0.1F, 0.9F}, 1e-5F);
    }
    aj::HueSaturation colorize;
    colorize.colorize = true;
    colorize.master = {240, 100, 0};
    const auto blue = aj::applyPixel(colorize, {0.5F, 0.5F, 0.5F});
    EXPECT_GT(blue[2], blue[0] + 0.5F);
}

TEST(Adjustments, ColorBalanceAndPhotoFilterPreserveLuminosity) {
    aj::ColorBalance balance;
    balance.shadows.cyan_red = 100;
    const auto dark = aj::applyPixel(balance, {0.2F, 0.2F, 0.2F}), bright = aj::applyPixel(balance, {0.9F, 0.9F, 0.9F});
    EXPECT_GT(dark[0] - dark[1], bright[0] - bright[1]) << "shadows move most";
    EXPECT_NEAR(luma(dark), 0.2F, 2e-3F) << "luminosity preserved";
    aj::PhotoFilter warm;
    warm.density = 100;
    const auto warmed = aj::applyPixel(warm, {0.5F, 0.5F, 0.5F});
    EXPECT_GT(warmed[0], warmed[2]);
    EXPECT_NEAR(luma(warmed), 0.5F, 2e-3F);
}

TEST(Adjustments, BlackWhiteUsesSectorWeights) {
    aj::BlackWhite bw;
    {
        SCOPED_TRACE("reds 40 %");
        expectRgb(aj::applyPixel(bw, {1, 0, 0}), {0.4F, 0.4F, 0.4F}, 1e-6F);
    }
    {
        SCOPED_TRACE("yellows 60 %");
        expectRgb(aj::applyPixel(bw, {1, 1, 0}), {0.6F, 0.6F, 0.6F}, 1e-6F);
    }
    {
        SCOPED_TRACE("blues 20 %");
        expectRgb(aj::applyPixel(bw, {0, 0, 1}), {0.2F, 0.2F, 0.2F}, 1e-6F);
    }
    expectRgb(aj::applyPixel(bw, {1, 1, 1}), {1, 1, 1}, 1e-6F);
    bw.tint = true;
    const auto tinted = aj::applyPixel(bw, {1, 1, 0});
    EXPECT_GT(tinted[0], tinted[2]) << "warm tint";
}

TEST(Adjustments, SimpleOperators) {
    expectRgb(aj::applyPixel(aj::Invert{}, {0.2F, 0.5F, 1}), {0.8F, 0.5F, 0}, 1e-6F);
    expectRgb(aj::applyPixel(aj::Posterize{4}, {0.1F, 0.3F, 0.99F}), {0, 1.0F / 3, 1}, 1e-6F);
    expectRgb(aj::applyPixel(aj::Threshold{128}, {0.6F, 0.6F, 0.6F}), {1, 1, 1}, 0);
    expectRgb(aj::applyPixel(aj::Threshold{128}, {0.4F, 0.4F, 0.4F}), {0, 0, 0}, 0);
    aj::ChannelMixer swap;
    swap.matrix = {{{0, 0, 100}, {0, 100, 0}, {100, 0, 0}}};
    expectRgb(aj::applyPixel(swap, {0.1F, 0.2F, 0.3F}), {0.3F, 0.2F, 0.1F}, 1e-6F);
    swap.monochrome = true;
    expectRgb(aj::applyPixel(swap, {0.1F, 0.2F, 0.3F}), {0.3F, 0.3F, 0.3F}, 1e-6F);
    aj::GradientMap map;
    EXPECT_NEAR(aj::applyPixel(map, {0.5F, 0.5F, 0.5F})[0], 0.5F, 1e-6F);
    map.reverse = true;
    EXPECT_NEAR(aj::applyPixel(map, {0.2F, 0.2F, 0.2F})[0], 0.8F, 1e-6F);
}

TEST(Adjustments, SelectiveColorOnlyTouchesItsRange) {
    aj::SelectiveColor selective;
    selective.cmyk[0] = {100, 0, 0, 0};  // reds: +100 % cyan (relative)
    const auto red = aj::applyPixel(selective, {0.9F, 0.2F, 0.2F});
    EXPECT_LT(red[0], 0.9F) << "cyan ink removes red";
    {
        SCOPED_TRACE("blue untouched");
        expectRgb(aj::applyPixel(selective, {0.2F, 0.2F, 0.9F}), {0.2F, 0.2F, 0.9F}, 1e-6F);
    }
    selective.absolute = true;
    EXPECT_LT(aj::applyPixel(selective, {0.9F, 0.2F, 0.2F})[0], red[0]) << "absolute is stronger on light inks";
}

TEST(Adjustments, BakedLutsMatchDirectEvaluation) {
    const std::vector<aj::Adjustment> stack{aj::Levels{0.05F, 0.95F, 1.3F, 0, 1}, aj::Vibrance{30, 10}, aj::ColorBalance{{10, 0, -10}, {}, {}, true}};
    const auto lut = aj::bakeLut(stack, 65);
    std::mt19937 random(4);
    std::uniform_real_distribution<float> unit(0, 1);
    float worst = 0;
    for (int i = 0; i < 2000; ++i) {
        Rgb c{unit(random), unit(random), unit(random)};
        Rgb direct = c;
        for (const auto& a : stack) {
            direct = aj::applyPixel(a, direct);
            for (auto& v : direct) v = std::clamp(v, 0.0F, 1.0F);
        }
        const auto baked = lut.apply(c);
        for (int k = 0; k < 3; ++k) worst = std::max(worst, std::fabs(baked[static_cast<std::size_t>(k)] - direct[static_cast<std::size_t>(k)]));
    }
    std::printf("[adjustments] baked 65³ LUT worst error %.4f (%.2f levels of 255)\n", worst, worst * 255);
    EXPECT_LT(worst * 255, 2.5F);
}

TEST(Adjustments, ApplyToLayersWithMaskOpacityAndShadowsHighlights) {
    TileStore layer(PixelFormat::RGBA8, 300, 200);
    for (std::int32_t tx = 0; tx < 2; ++tx) {
        croptop::TileBuffer t(layer.tile_byte_size(), 0);
        for (std::size_t i = 0; i < 256U * 256U; ++i) {
            const auto x = static_cast<std::uint32_t>(i % 256 + static_cast<std::size_t>(tx) * 256);
            const std::uint8_t v = x < 150 ? 40 : 220;
            t[i * 4] = t[i * 4 + 1] = t[i * 4 + 2] = v;
            t[i * 4 + 3] = 255;
        }
        ASSERT_TRUE(layer.writeTile({tx, 0}, t));
    }
    const auto px = [&](const TileStore& s, std::uint32_t x, std::uint32_t y) {
        const auto t = s.readTile({static_cast<std::int32_t>(x / 256), static_cast<std::int32_t>(y / 256)});
        return static_cast<int>(t->data()[(static_cast<std::size_t>(y % 256) * 256 + x % 256) * 4]);
    };
    std::string error;
    auto inverted = layer;
    const auto mask = croptop::selection::rasterizeRect(300, 200, {0, 0, 100, 200}, false);
    ASSERT_TRUE(aj::apply(inverted, aj::Invert{}, &mask, 0.5F, error)) << error;
    EXPECT_EQ(px(inverted, 50, 50), (40 + 215) / 2 + 1) << "half opacity";
    EXPECT_EQ(px(inverted, 120, 50), 40) << "outside the mask";
    auto lifted = layer;
    ASSERT_TRUE(aj::shadowsHighlights(lifted, {80, 0}, nullptr, 1.0F, error)) << error;
    EXPECT_GT(px(lifted, 40, 100), 60) << "shadows lifted";
    EXPECT_NEAR(px(lifted, 280, 100), 220, 2) << "highlights kept";
    EXPECT_FALSE(aj::apply(lifted, aj::Invert{}, nullptr, 2.0F, error));
}

}  // namespace
