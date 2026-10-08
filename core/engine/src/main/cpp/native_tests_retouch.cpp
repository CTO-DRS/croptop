// Tests for toning/focus brushes and red eye.
#include "native_tests_stress_gate.h"
#include "red_eye.h"
#include "retouch.h"
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

using croptop::PixelFormat;
using croptop::TileStore;
namespace pt = croptop::paint;
namespace sel = croptop::selection;
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

TileStore flat(Rgba c, std::uint32_t w = 128, std::uint32_t h = 128) {
    return paint(w, h, [c](std::uint32_t, std::uint32_t) { return c; });
}

Rgba at(const TileStore& s, std::uint32_t x, std::uint32_t y) {
    Rgba c{};
    const auto t = s.readTile({static_cast<std::int32_t>(x / 256), static_cast<std::int32_t>(y / 256)});
    if (t) std::memcpy(c.data(), t->data() + (static_cast<std::size_t>(y % 256) * 256 + x % 256) * 4, 4);
    return c;
}

TileStore dab(std::uint32_t w = 128, std::uint32_t h = 128) { return sel::rasterizeEllipse(w, h, {32, 32, 96, 96}, true); }

double luma(Rgba c) { return 0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2]; }

TEST(Retouch, DodgeBrightensAndBurnDarkensTheCoveredArea) {
    for (const bool burn : {false, true}) {
        auto image = flat({120, 100, 80, 255});
        std::string error;
        pt::DodgeBurnSpec spec;
        spec.burn = burn;
        spec.exposure = 1.0F;
        ASSERT_TRUE(pt::dodgeBurn(image, dab(), spec, error)) << error;
        const auto centre = at(image, 64, 64);
        if (burn) {
            EXPECT_LT(luma(centre), 100.0);
        } else {
            EXPECT_GT(luma(centre), 110.0);
        }
        EXPECT_EQ(at(image, 5, 5), (Rgba{120, 100, 80, 255})) << "outside the dab is untouched";
        EXPECT_EQ(centre[3], 255);
    }
}

TEST(Retouch, DodgeRangeTargetsItsTones) {
    // Highlights dodge barely moves shadows; shadows dodge barely moves highlights.
    auto dark = flat({30, 30, 30, 255});
    auto bright = flat({220, 220, 220, 255});
    std::string error;
    pt::DodgeBurnSpec spec;
    spec.exposure = 1.0F;
    spec.range = pt::ToneRange::Highlights;
    ASSERT_TRUE(pt::dodgeBurn(dark, dab(), spec, error));
    EXPECT_LE(at(dark, 64, 64)[0], 35);
    spec.range = pt::ToneRange::Shadows;
    auto dark2 = flat({30, 30, 30, 255});
    ASSERT_TRUE(pt::dodgeBurn(dark2, dab(), spec, error));
    EXPECT_GT(at(dark2, 64, 64)[0], 45);
    ASSERT_TRUE(pt::dodgeBurn(bright, dab(), spec, error));
    EXPECT_LE(at(bright, 64, 64)[0], 222);
}

TEST(Retouch, ProtectTonesKeepsHueWhileBurning) {
    const Rgba orange{220, 140, 60, 255};
    std::string error;
    pt::DodgeBurnSpec spec;
    spec.burn = true;
    spec.exposure = 1.0F;
    auto protectedImage = flat(orange);
    ASSERT_TRUE(pt::dodgeBurn(protectedImage, dab(), spec, error));
    spec.protect_tones = false;
    auto plain = flat(orange);
    ASSERT_TRUE(pt::dodgeBurn(plain, dab(), spec, error));
    const auto a = at(protectedImage, 64, 64), b = at(plain, 64, 64);
    // Channel ratios: protect tones keeps R/G of the original; plain gamma per channel oversaturates.
    const double original = 220.0 / 140.0;
    EXPECT_NEAR(static_cast<double>(a[0]) / a[1], original, 0.05);
    EXPECT_GT(static_cast<double>(b[0]) / b[1], original + 0.1);
}

TEST(Retouch, ProtectTonesDodgeDoesNotClip) {
    auto image = flat({250, 120, 40, 255});
    std::string error;
    pt::DodgeBurnSpec spec;
    spec.exposure = 1.0F;
    ASSERT_TRUE(pt::dodgeBurn(image, dab(), spec, error));
    const auto c = at(image, 64, 64);
    EXPECT_LE(c[0], 255);
    EXPECT_NEAR(static_cast<double>(c[0]) / c[1], 250.0 / 120.0, 0.08) << "hue is kept at the clip limit";
}

TEST(Retouch, SpongeSaturatesAndDesaturates) {
    const Rgba muted{150, 120, 110, 255};
    std::string error;
    pt::SpongeSpec spec;
    spec.flow = 1.0F;
    spec.vibrance = false;
    auto grey = flat(muted);
    ASSERT_TRUE(pt::sponge(grey, dab(), spec, error));
    const auto g = at(grey, 64, 64);
    EXPECT_LE(std::max({g[0], g[1], g[2]}) - std::min({g[0], g[1], g[2]}), 1) << "full desaturation is neutral";
    EXPECT_NEAR(luma(g), luma(muted), 1.5) << "luma is kept";
    spec.saturate = true;
    auto vivid = flat(muted);
    ASSERT_TRUE(pt::sponge(vivid, dab(), spec, error));
    const auto v = at(vivid, 64, 64);
    EXPECT_GT(v[0] - v[2], muted[0] - muted[2] + 20);
}

TEST(Retouch, VibranceBoostsMutedColoursMoreAndNeverClips) {
    std::string error;
    pt::SpongeSpec spec;
    spec.saturate = true;
    spec.flow = 1.0F;
    auto muted = flat({140, 120, 110, 255});
    auto strong = flat({240, 40, 20, 255});
    ASSERT_TRUE(pt::sponge(muted, dab(), spec, error));
    ASSERT_TRUE(pt::sponge(strong, dab(), spec, error));
    const auto m = at(muted, 64, 64), s = at(strong, 64, 64);
    EXPECT_GT((m[0] - m[2]) / 30.0, 1.5) << "muted colour gains a lot";
    EXPECT_LT((s[0] - s[2]) / 220.0, 1.15) << "strong colour gains little, stopping at the clip limit";
    EXPECT_GT(s[2], 0) << "no channel is pushed past 0";
}

TEST(Retouch, BlurSoftensAnEdgeAndSharpenStrengthensIt) {
    const auto edge = [](std::uint32_t x, std::uint32_t) { return x < 64 ? Rgba{60, 60, 60, 255} : Rgba{180, 180, 180, 255}; };
    std::string error;
    pt::FocusSpec spec;
    spec.strength = 1.0F;
    auto blurred = paint(128, 128, edge);
    ASSERT_TRUE(pt::blurSharpen(blurred, dab(), spec, error));
    EXPECT_GT(at(blurred, 63, 64)[0], 60);
    EXPECT_LT(at(blurred, 64, 64)[0], 180);
    spec.sharpen = true;
    spec.protect_detail = false;
    auto sharpened = paint(128, 128, edge);
    ASSERT_TRUE(pt::blurSharpen(sharpened, dab(), spec, error));
    EXPECT_LT(at(sharpened, 63, 64)[0], 60) << "overshoot without protect detail";
    EXPECT_GT(at(sharpened, 64, 64)[0], 180);
    spec.protect_detail = true;
    auto protectedImage = paint(128, 128, edge);
    ASSERT_TRUE(pt::blurSharpen(protectedImage, dab(), spec, error));
    EXPECT_EQ(at(protectedImage, 63, 64)[0], 60) << "no halo with protect detail";
    EXPECT_EQ(at(protectedImage, 64, 64)[0], 180);
}

TEST(Retouch, BlurDoesNotDarkenAgainstTransparency) {
    auto image = paint(128, 128, [](std::uint32_t x, std::uint32_t) {
        return x < 64 ? Rgba{0, 0, 0, 0} : Rgba{200, 50, 50, 255};
    });
    std::string error;
    pt::FocusSpec spec;
    spec.strength = 1.0F;
    ASSERT_TRUE(pt::blurSharpen(image, dab(), spec, error));
    const auto c = at(image, 64, 64);
    EXPECT_LT(c[3], 255) << "alpha softens";
    EXPECT_NEAR(c[0], 200, 1) << "colour stays unpremultiplied-correct";
}

TEST(Retouch, SmudgeDragsColourFromThePreviousDab) {
    auto image = paint(128, 128, [](std::uint32_t x, std::uint32_t) { return x < 50 ? Rgba{220, 20, 20, 255} : Rgba{20, 20, 220, 255}; });
    std::string error;
    pt::SmudgeSpec spec;
    spec.offset_x = 30;  // moving right: pixels pull colour from 30 px to the left
    spec.strength = 1.0F;
    const auto footprint = sel::rasterizeRect(128, 128, {60, 40, 80, 60}, false);
    ASSERT_TRUE(pt::smudge(image, footprint, spec, error)) << error;
    EXPECT_EQ(at(image, 70, 50)[0], 220) << "x=70 pulled red from x=40";
    EXPECT_EQ(at(image, 79, 50)[0], 220) << "x=79 pulled red from x=49";
    EXPECT_EQ(at(image, 85, 50)[2], 220) << "outside the footprint is untouched";
}

TEST(Retouch, FingerPaintingMixesTheColour) {
    auto image = flat({20, 20, 220, 255});
    std::string error;
    pt::SmudgeSpec spec;
    spec.strength = 0.5F;
    spec.finger_color = std::array<float, 4>{1.0F, 1.0F, 1.0F, 1.0F};
    const auto footprint = sel::rasterizeRect(128, 128, {60, 40, 80, 60}, false);
    ASSERT_TRUE(pt::smudge(image, footprint, spec, error)) << error;
    EXPECT_NEAR(at(image, 70, 50)[0], 138, 1);
}

TEST(Retouch, RejectsBadParameters) {
    auto image = flat({1, 2, 3, 255});
    std::string error;
    pt::DodgeBurnSpec tone;
    tone.exposure = 1.5F;
    EXPECT_FALSE(pt::dodgeBurn(image, dab(), tone, error));
    pt::SpongeSpec sponge;
    sponge.flow = std::nanf("");
    EXPECT_FALSE(pt::sponge(image, dab(), sponge, error));
    pt::SmudgeSpec smudge;
    smudge.offset_x = INFINITY;
    EXPECT_FALSE(pt::smudge(image, dab(), smudge, error));
    EXPECT_FALSE(pt::blurSharpen(image, sel::rasterizeRect(10, 10, {0, 0, 5, 5}, false), {}, error));
    pt::RedEyeSpec eye;
    eye.darken = -0.1F;
    EXPECT_FALSE(pt::redEye(image, dab(), eye, error));
}

TEST(RedEye, NeutralisesThePupilButKeepsSkinAndCatchlight) {
    auto image = paint(128, 128, [](std::uint32_t x, std::uint32_t y) {
        const double dx = x - 64.0, dy = y - 64.0;
        const double r = std::sqrt(dx * dx + dy * dy);
        if (r < 2.5) return Rgba{250, 250, 250, 255};  // catchlight
        if (r < 12) return Rgba{210, 35, 40, 255};      // red pupil
        if (r < 20) return Rgba{90, 70, 50, 255};       // brown iris
        return Rgba{205, 150, 130, 255};                // skin
    });
    const auto original = image;
    const auto region = sel::rasterizeRect(128, 128, {30, 30, 98, 98}, false);
    std::string error;
    ASSERT_TRUE(pt::redEye(image, region, {}, error)) << error;
    const auto pupil = at(image, 70, 64);
    EXPECT_LT(pupil[0], 45) << "pupil is no longer red";
    EXPECT_LE(std::abs(pupil[0] - pupil[1]), 3) << "and neutral";
    EXPECT_EQ(at(image, 64, 64), at(original, 64, 64)) << "catchlight kept";
    EXPECT_EQ(at(image, 64, 90), at(original, 64, 90)) << "skin kept";
    EXPECT_EQ(at(image, 64, 80), at(original, 64, 80)) << "iris kept";
    EXPECT_EQ(at(image, 5, 5), at(original, 5, 5));
}

TEST(RedEye, DoesNothingWithoutRed) {
    auto image = flat({60, 80, 120, 255});
    const auto original = image;
    std::string error;
    ASSERT_TRUE(pt::redEye(image, dab(), {}, error));
    EXPECT_EQ(at(image, 64, 64), at(original, 64, 64));
}

TEST(Retouch, PerDabTimingAt200Px) {
    CROPTOP_REQUIRE_STRESS();
    TileStore image(PixelFormat::RGBA16, 6000, 4000);
    for (std::int32_t ty = 7; ty <= 8; ++ty)
        for (std::int32_t tx = 12; tx <= 13; ++tx) {
            croptop::TileBuffer t(image.tile_byte_size(), 0);
            for (std::size_t i = 0; i < t.size(); ++i) t[i] = static_cast<std::uint8_t>(i * 7U);
            ASSERT_TRUE(image.writeTile({tx, ty}, t));
        }
    const auto footprint = sel::rasterizeEllipse(6000, 4000, {3100, 1850, 3300, 2050}, true);
    std::string error;
    const auto time = [&](const char* name, const std::function<bool()>& f) {
        const auto start = std::chrono::steady_clock::now();
        for (int i = 0; i < 5; ++i) ASSERT_TRUE(f()) << error;
        std::printf("[retouch] %s 200 px dab on 24 MP 16-bit: %.2f ms\n", name,
                    std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - start).count() / 5);
    };
    time("dodge", [&] { return pt::dodgeBurn(image, footprint, {}, error); });
    time("sponge", [&] { return pt::sponge(image, footprint, {}, error); });
    time("sharpen", [&] { return pt::blurSharpen(image, footprint, {true, 0.5F, true}, error); });
    time("smudge", [&] { return pt::smudge(image, footprint, {10, 0, 0.5F, std::nullopt}, error); });
}

TEST(Retouch, BackgroundEraserErasesTheSampledColourAndKeepsTheRest) {
    // A red field with a blue stripe through the middle; the eraser samples red.
    auto image = paint(128, 128, [](std::uint32_t x, std::uint32_t) {
        return x >= 60 && x < 68 ? Rgba{20, 30, 230, 255} : Rgba{220, 30, 30, 255};
    });
    pt::BackgroundEraseSpec spec;
    spec.sample = {220 / 255.0F, 30 / 255.0F, 30 / 255.0F};
    spec.tolerance = 0.2F;
    spec.contiguous = false;
    spec.hotspot_x = 40;
    spec.hotspot_y = 64;
    std::string error;
    ASSERT_TRUE(pt::backgroundErase(image, dab(), spec, error)) << error;
    EXPECT_EQ(at(image, 40, 64)[3], 0) << "red under the brush is gone";
    EXPECT_EQ(at(image, 80, 64)[3], 0) << "so is red on the other side of the stripe (discontiguous)";
    EXPECT_EQ(at(image, 63, 64)[3], 255) << "the blue stripe is not red: it stays";
    EXPECT_EQ(at(image, 20, 20)[3], 255) << "outside the brush footprint nothing changes";
    EXPECT_EQ(at(image, 40, 64)[0], 220) << "colour channels are left alone, only alpha changes";
}

TEST(Retouch, BackgroundEraserContiguousStopsAtADifferentColour) {
    auto image = paint(128, 128, [](std::uint32_t x, std::uint32_t) {
        return x >= 60 && x < 68 ? Rgba{20, 30, 230, 255} : Rgba{220, 30, 30, 255};
    });
    pt::BackgroundEraseSpec spec;
    spec.sample = {220 / 255.0F, 30 / 255.0F, 30 / 255.0F};
    spec.tolerance = 0.2F;
    spec.contiguous = true;
    spec.hotspot_x = 48;
    spec.hotspot_y = 64;
    std::string error;
    ASSERT_TRUE(pt::backgroundErase(image, dab(), spec, error)) << error;
    EXPECT_EQ(at(image, 48, 64)[3], 0) << "the red the hotspot sits on";
    EXPECT_EQ(at(image, 90, 64)[3], 255) << "the red behind the blue stripe is not connected to the hotspot";
}

TEST(Retouch, BackgroundEraserProtectsAColourAndFadesAtTheToleranceEdge) {
    auto image = paint(128, 128, [](std::uint32_t x, std::uint32_t) {
        if (x < 56) return Rgba{200, 40, 40, 255};      // the sampled red
        if (x < 72) return Rgba{210, 60, 60, 255};      // a slightly different red (inside tolerance)
        return Rgba{235, 120, 100, 255};                // a salmon, further away
    });
    pt::BackgroundEraseSpec spec;
    spec.sample = {200 / 255.0F, 40 / 255.0F, 40 / 255.0F};
    spec.tolerance = 0.1F;
    spec.contiguous = false;
    spec.hotspot_x = 64;
    spec.hotspot_y = 64;
    std::string error;
    auto plain = image;
    ASSERT_TRUE(pt::backgroundErase(plain, dab(), spec, error)) << error;
    EXPECT_EQ(at(plain, 40, 64)[3], 0);
    EXPECT_EQ(at(plain, 64, 64)[3], 0) << "a close red is within tolerance";
    EXPECT_EQ(at(plain, 90, 64)[3], 255) << "salmon is beyond the fade";
    spec.protect = std::array<float, 3>{210 / 255.0F, 60 / 255.0F, 60 / 255.0F};
    auto protectedImage = image;
    ASSERT_TRUE(pt::backgroundErase(protectedImage, dab(), spec, error)) << error;
    EXPECT_EQ(at(protectedImage, 64, 64)[3], 255) << "the protected colour survives";
    EXPECT_EQ(at(protectedImage, 40, 64)[3], 0) << "the sampled colour still goes";
    spec.tolerance = 2.0F;
    EXPECT_FALSE(pt::backgroundErase(image, dab(), spec, error));
}

}  // namespace
