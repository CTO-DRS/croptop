// Tests for the blur/sharpen CPU filters.
#include "filters_blur.h"
#include "native_tests_stress_gate.h"
#include "selection_mask.h"
#include "tile_store.h"

#include <gtest/gtest.h>

#include <array>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <functional>
#include <string>

namespace {

namespace fl = croptop::filters;
using croptop::PixelFormat;
using croptop::TileStore;
using Rgba = std::array<std::uint8_t, 4>;

TileStore paint(std::uint32_t w, std::uint32_t h, const std::function<Rgba(std::uint32_t, std::uint32_t)>& f) {
    TileStore s(PixelFormat::RGBA8, w, h);
    for (std::uint32_t ty = 0; ty * 256 < h; ++ty) {
        for (std::uint32_t tx = 0; tx * 256 < w; ++tx) {
            croptop::TileBuffer t(s.tile_byte_size(), 0);
            for (std::uint32_t y = 0; y < 256; ++y)
                for (std::uint32_t x = 0; x < 256; ++x) {
                    const auto gx = tx * 256 + x, gy = ty * 256 + y;
                    if (gx >= w || gy >= h) continue;
                    const auto c = f(gx, gy);
                    std::memcpy(t.data() + (static_cast<std::size_t>(y) * 256 + x) * 4, c.data(), 4);
                }
            EXPECT_TRUE(s.writeTile({static_cast<std::int32_t>(tx), static_cast<std::int32_t>(ty)}, t));
        }
    }
    return s;
}

Rgba at(const TileStore& s, std::uint32_t x, std::uint32_t y) {
    Rgba c{};
    const auto t = s.readTile({static_cast<std::int32_t>(x / 256), static_cast<std::int32_t>(y / 256)});
    if (t) std::memcpy(c.data(), t->data() + (static_cast<std::size_t>(y % 256) * 256 + x % 256) * 4, 4);
    return c;
}

TileStore dotImage(std::uint32_t w, std::uint32_t h, std::uint32_t cx, std::uint32_t cy) {
    return paint(w, h, [=](std::uint32_t x, std::uint32_t y) {
        return x == cx && y == cy ? Rgba{255, 255, 255, 255} : Rgba{0, 0, 0, 255};
    });
}

TEST(FiltersBlur, GaussianMatchesTheAnalyticProfile) {
    // A vertical step edge blurred with σ = 8 follows the error function; compare at a few offsets.
    auto image = paint(600, 300, [](std::uint32_t x, std::uint32_t) { return x < 300 ? Rgba{0, 0, 0, 255} : Rgba{255, 255, 255, 255}; });
    std::string error;
    ASSERT_TRUE(fl::gaussianBlur(image, 8.0F, nullptr, error)) << error;
    for (const int offset : {-16, -8, -3, 0, 3, 8, 16}) {
        const double x = 300 + offset + 0.5;
        const double expected = 255.0 * 0.5 * (1.0 + std::erf((x - 300.0) / (8.0 * std::sqrt(2.0))));
        EXPECT_NEAR(at(image, static_cast<std::uint32_t>(300 + offset), 150)[0], expected, 255 * 0.03) << offset;
    }
    EXPECT_EQ(at(image, 5, 5), (Rgba{0, 0, 0, 255})) << "far from the edge nothing changes";
    EXPECT_EQ(at(image, 590, 290), (Rgba{255, 255, 255, 255})) << "edges replicate (no dark border)";
}

TEST(FiltersBlur, StreamingChunksJoinSeamlessly) {
    // A horizontal step exactly on a row-chunk boundary (1024) must blur like any other edge.
    auto image = paint(300, 1600, [](std::uint32_t, std::uint32_t y) { return y < 1024 ? Rgba{0, 0, 0, 255} : Rgba{255, 255, 255, 255}; });
    std::string error;
    ASSERT_TRUE(fl::gaussianBlur(image, 6.0F, nullptr, error)) << error;
    for (int offset = -14; offset <= 14; ++offset) {
        const double y = 1024 + offset + 0.5;
        const double expected = 255.0 * 0.5 * (1.0 + std::erf((y - 1024.0) / (6.0 * std::sqrt(2.0))));
        EXPECT_NEAR(at(image, 150, static_cast<std::uint32_t>(1024 + offset))[0], expected, 255 * 0.03) << offset;
    }
}

TEST(FiltersBlur, GaussianConservesEnergyAndIsIsotropic) {
    auto image = dotImage(300, 300, 150, 150);
    TileStore wide(PixelFormat::RGBA32F, 300, 300);
    {
        croptop::TileBuffer t(wide.tile_byte_size(), 0);
        for (std::size_t i = 0; i < 256U * 256U; ++i) {
            const float a = 1.0F;
            std::memcpy(t.data() + i * 16 + 12, &a, 4);
        }
        const float one = 1.0F;
        std::memcpy(t.data() + (150U * 256U + 150U) * 16, &one, 4);
        ASSERT_TRUE(wide.writeTile({0, 0}, t));
        croptop::TileBuffer blank(wide.tile_byte_size(), 0);
        for (std::size_t i = 0; i < 256U * 256U; ++i) {
            const float a = 1.0F;
            std::memcpy(blank.data() + i * 16 + 12, &a, 4);
        }
        ASSERT_TRUE(wide.writeTile({1, 0}, blank));
        ASSERT_TRUE(wide.writeTile({0, 1}, blank));
        ASSERT_TRUE(wide.writeTile({1, 1}, blank));
    }
    std::string error;
    ASSERT_TRUE(fl::gaussianBlur(wide, 5.0F, nullptr, error)) << error;
    const auto value = [&](int x, int y) {
        float v = 0;
        std::memcpy(&v, wide.readTile({x / 256, y / 256})->data() + ((y % 256) * 256 + x % 256) * 16, 4);
        return v;
    };
    double total = 0;
    for (int y = 120; y < 180; ++y)
        for (int x = 120; x < 180; ++x) total += value(x, y);
    EXPECT_NEAR(total, 1.0, 1e-3);
    EXPECT_NEAR(value(160, 150), value(150, 160), 1e-6F);
    EXPECT_NEAR(value(157, 157), value(143, 143), 1e-6F);
    (void)image;
}

TEST(FiltersBlur, TransparencyDoesNotDarkenEdges) {
    auto image = paint(256, 256, [](std::uint32_t x, std::uint32_t) { return x < 128 ? Rgba{0, 0, 0, 0} : Rgba{250, 60, 20, 255}; });
    std::string error;
    ASSERT_TRUE(fl::gaussianBlur(image, 6.0F, nullptr, error)) << error;
    for (std::uint32_t x = 115; x < 140; ++x) {
        const auto c = at(image, x, 100);
        if (c[3] > 10) {
            EXPECT_NEAR(c[0], 250, 3) << x;
        }
    }
    EXPECT_GT(at(image, 125, 100)[3], 0) << "alpha spreads";
}

TEST(FiltersBlur, MaskLimitsTheEffect) {
    auto image = paint(512, 256, [](std::uint32_t x, std::uint32_t y) { return ((x / 4 + y / 4) % 2) ? Rgba{255, 255, 255, 255} : Rgba{0, 0, 0, 255}; });
    const auto original = image;
    const auto mask = croptop::selection::rasterizeRect(512, 256, {300, 50, 400, 150}, false);
    std::string error;
    ASSERT_TRUE(fl::gaussianBlur(image, 4.0F, &mask, error)) << error;
    EXPECT_NEAR(at(image, 350, 100)[0], 128, 12) << "checkerboard inside the mask averages out";
    EXPECT_EQ(at(image, 100, 100), at(original, 100, 100));
    EXPECT_EQ(at(image, 350, 200), at(original, 350, 200));
}

TEST(FiltersBlur, BoxBlurIsAnExactMean) {
    auto image = dotImage(64, 64, 30, 30);
    std::string error;
    ASSERT_TRUE(fl::boxBlur(image, 2, nullptr, error)) << error;
    EXPECT_NEAR(at(image, 30, 30)[0], 255.0 / 25.0, 0.6);
    EXPECT_NEAR(at(image, 32, 28)[0], 255.0 / 25.0, 0.6);
    EXPECT_EQ(at(image, 33, 30)[0], 0);
}

TEST(FiltersBlur, UnsharpMaskAndHighPass) {
    const auto edge = [](std::uint32_t x, std::uint32_t) { return x < 128 ? Rgba{80, 80, 80, 255} : Rgba{160, 160, 160, 255}; };
    auto sharp = paint(256, 64, edge);
    std::string error;
    ASSERT_TRUE(fl::unsharpMask(sharp, 1.0F, 3.0F, 0.0F, nullptr, error)) << error;
    EXPECT_LT(at(sharp, 126, 30)[0], 80) << "dark side undershoots";
    EXPECT_GT(at(sharp, 129, 30)[0], 160) << "bright side overshoots";
    EXPECT_EQ(at(sharp, 20, 30)[0], 80) << "flat areas are unchanged";
    auto thresholded = paint(256, 64, edge);
    ASSERT_TRUE(fl::unsharpMask(thresholded, 1.0F, 3.0F, 255.0F, nullptr, error));
    EXPECT_EQ(at(thresholded, 126, 30)[0], 80) << "differences below the threshold are left alone";
    auto pass = paint(256, 64, edge);
    ASSERT_TRUE(fl::highPass(pass, 3.0F, nullptr, error));
    EXPECT_NEAR(at(pass, 20, 30)[0], 128, 1) << "flat areas become mid grey";
    EXPECT_LT(at(pass, 126, 30)[0], 128);
    EXPECT_GT(at(pass, 129, 30)[0], 128);
}

TEST(FiltersBlur, MotionBlurSmearsAlongTheAngleOnly) {
    auto image = dotImage(128, 128, 64, 64);
    std::string error;
    ASSERT_TRUE(fl::motionBlur(image, 0.0F, 20.0F, nullptr, error)) << error;
    EXPECT_GT(at(image, 72, 64)[0], 5) << "smeared horizontally";
    EXPECT_EQ(at(image, 64, 72)[0], 0) << "not vertically";
    auto vertical = dotImage(128, 128, 64, 64);
    ASSERT_TRUE(fl::motionBlur(vertical, 90.0F, 20.0F, nullptr, error));
    EXPECT_GT(at(vertical, 64, 56)[0], 5);
    EXPECT_EQ(at(vertical, 72, 64)[0], 0);
}

TEST(FiltersBlur, RadialSpinAndZoom) {
    // A dot to the right of the centre: spin smears it vertically (along the circle), zoom horizontally.
    auto spin = dotImage(200, 200, 160, 100);
    std::string error;
    ASSERT_TRUE(fl::radialBlur(spin, fl::RadialMode::Spin, 20.0F, 100.0, 100.0, nullptr, error)) << error;
    EXPECT_GT(at(spin, 160, 106)[0] + at(spin, 159, 106)[0], 3);
    EXPECT_EQ(at(spin, 150, 100)[0], 0);
    auto zoom = dotImage(200, 200, 160, 100);
    ASSERT_TRUE(fl::radialBlur(zoom, fl::RadialMode::Zoom, 40.0F, 100.0, 100.0, nullptr, error)) << error;
    EXPECT_GT(at(zoom, 163, 100)[0], 3) << "pixels outward sample the dot on their way to the centre";
    EXPECT_EQ(at(zoom, 160, 108)[0], 0);
}

std::uint8_t noise(std::uint32_t x, std::uint32_t y) {
    std::uint32_t h = x * 374761393U + y * 668265263U;
    h = (h ^ (h >> 13)) * 1274126177U;
    return static_cast<std::uint8_t>((h ^ (h >> 16)) & 0xFFU);
}

TEST(FiltersBlur, SurfaceBlurSmoothsNoiseButKeepsEdges) {
    auto image = paint(256, 128, [](std::uint32_t x, std::uint32_t y) {
        const int base = x < 128 ? 60 : 200;
        const auto v = static_cast<std::uint8_t>(base + noise(x, y) % 16 - 8);
        return Rgba{v, v, v, 255};
    });
    std::string error;
    ASSERT_TRUE(fl::surfaceBlur(image, 6, 30.0F, nullptr, error)) << error;
    double spread = 0;
    for (std::uint32_t x = 20; x < 100; ++x) spread += std::abs(at(image, x, 64)[0] - 60);
    EXPECT_LT(spread / 80.0, 2.5) << "noise smoothed";
    EXPECT_NEAR(at(image, 126, 64)[0], 60, 8) << "edge kept on the dark side";
    EXPECT_NEAR(at(image, 129, 64)[0], 200, 8) << "and on the bright side";
}

TEST(FiltersBlur, TiltShiftKeepsTheFocusBandSharp) {
    // Period-16 stripes: σ 4.5 keeps ~20 % contrast, σ 8 under 1 %.
    const auto stripes = [](std::uint32_t x, std::uint32_t) { return (x / 8) % 2 ? Rgba{255, 255, 255, 255} : Rgba{0, 0, 0, 255}; };
    auto image = paint(256, 512, stripes);
    fl::TiltShiftSpec spec;
    spec.centre_x = 128;
    spec.centre_y = 256;
    spec.angle = 0.0F;  // horizontal focus band
    spec.focus = 40.0F;
    spec.transition = 60.0F;
    spec.blur = 8.0F;
    std::string error;
    ASSERT_TRUE(fl::tiltShift(image, spec, nullptr, error)) << error;
    const auto contrast = [&](std::uint32_t y) {
        int lo = 255, hi = 0;
        for (std::uint32_t x = 100; x < 140; ++x) {
            lo = std::min<int>(lo, at(image, x, y)[0]);
            hi = std::max<int>(hi, at(image, x, y)[0]);
        }
        return hi - lo;
    };
    EXPECT_EQ(contrast(256), 255) << "inside the focus band";
    EXPECT_EQ(contrast(290), 255);
    EXPECT_LT(contrast(20), 10) << "fully blurred far away";
    EXPECT_GT(contrast(330), 25) << "the blur ramps up through the transition";
    EXPECT_LT(contrast(330), 200);
}

TEST(FiltersBlur, SmartSharpenControlsHalosAndNoise) {
    const auto edge = [](std::uint32_t x, std::uint32_t) { return x < 128 ? Rgba{80, 80, 80, 255} : Rgba{160, 160, 160, 255}; };
    std::string error;
    for (const auto mode : {fl::SharpenRemove::Gaussian, fl::SharpenRemove::Lens}) {
        auto image = paint(256, 64, edge);
        fl::SmartSharpenSpec spec;
        spec.amount = 2.0F;
        spec.radius = 3.0F;
        spec.remove = mode;
        ASSERT_TRUE(fl::smartSharpen(image, spec, nullptr, error)) << error;
        const int dark = at(image, 127, 30)[0], bright = at(image, 128, 30)[0];
        EXPECT_LT(dark, 80);
        EXPECT_GE(dark, 72) << "halo held to 10 % of the local range";
        EXPECT_GT(bright, 160);
        EXPECT_LE(bright, 168);
        EXPECT_EQ(at(image, 30, 30)[0], 80);
    }
    // Faint noise is left alone with noise reduction on, amplified with it off.
    auto grain = paint(128, 128, [](std::uint32_t x, std::uint32_t y) {
        const auto v = static_cast<std::uint8_t>(128 + noise(x, y) % 5 - 2);
        return Rgba{v, v, v, 255};
    });
    auto grain_raw = grain;
    fl::SmartSharpenSpec spec;
    spec.amount = 3.0F;
    spec.reduce_noise = 1.0F;
    ASSERT_TRUE(fl::smartSharpen(grain, spec, nullptr, error));
    spec.reduce_noise = 0.0F;
    ASSERT_TRUE(fl::smartSharpen(grain_raw, spec, nullptr, error));
    double reduced = 0, raw = 0;
    for (std::uint32_t x = 10; x < 110; ++x) {
        reduced += std::abs(at(grain, x, 60)[0] - 128);
        raw += std::abs(at(grain_raw, x, 60)[0] - 128);
    }
    EXPECT_LT(reduced, raw * 0.8);
}

TEST(FiltersBlur, RejectsBadInput) {
    auto image = dotImage(64, 64, 3, 3);
    std::string error;
    EXPECT_FALSE(fl::gaussianBlur(image, -1.0F, nullptr, error));
    EXPECT_FALSE(fl::gaussianBlur(image, NAN, nullptr, error));
    EXPECT_FALSE(fl::unsharpMask(image, 9.0F, 2.0F, 0.0F, nullptr, error));
    EXPECT_FALSE(fl::motionBlur(image, 0.0F, 0.0F, nullptr, error));
    EXPECT_FALSE(fl::radialBlur(image, fl::RadialMode::Zoom, 0.0F, 0, 0, nullptr, error));
    TileStore mask(PixelFormat::A16, 5, 5);
    EXPECT_FALSE(fl::gaussianBlur(image, 2.0F, &mask, error));
    TileStore alpha(PixelFormat::A8, 64, 64);
    EXPECT_FALSE(fl::gaussianBlur(alpha, 2.0F, nullptr, error));
    EXPECT_TRUE(fl::gaussianBlur(image, 0.1F, nullptr, error)) << "tiny radius is a no-op";
    EXPECT_FALSE(fl::surfaceBlur(image, 0, 10.0F, nullptr, error));
    fl::TiltShiftSpec tilt;
    tilt.transition = 0.0F;
    EXPECT_FALSE(fl::tiltShift(image, tilt, nullptr, error));
    fl::SmartSharpenSpec sharpen;
    sharpen.radius = 100.0F;
    EXPECT_FALSE(fl::smartSharpen(image, sharpen, nullptr, error));
}

TEST(FiltersBlur, GaussianTimingOn24Megapixels16Bit) {
    CROPTOP_REQUIRE_STRESS();
    TileStore image(PixelFormat::RGBA16, 6000, 4000);
    croptop::TileBuffer t(image.tile_byte_size(), 0);
    for (std::size_t i = 0; i < t.size(); ++i) t[i] = static_cast<std::uint8_t>(i * 13U);
    for (std::int32_t ty = 0; ty * 256 < 4000; ++ty)
        for (std::int32_t tx = 0; tx * 256 < 6000; ++tx) ASSERT_TRUE(image.writeTile({tx, ty}, t));
    std::string error;
    const auto start = std::chrono::steady_clock::now();
    ASSERT_TRUE(fl::gaussianBlur(image, 50.0F, nullptr, error)) << error;
    std::printf("[filters] 50 px gaussian on 24 MP RGBA16: %.0f ms\n",
                std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - start).count());
}

}  // namespace
