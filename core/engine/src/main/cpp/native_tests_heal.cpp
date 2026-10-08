// Tests for clone / heal / spot heal / patch pixel math.
#include "heal.h"
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

Rgba at(const TileStore& s, std::uint32_t x, std::uint32_t y) {
    Rgba c{};
    const auto t = s.readTile({static_cast<std::int32_t>(x / 256), static_cast<std::int32_t>(y / 256)});
    if (t) std::memcpy(c.data(), t->data() + (static_cast<std::size_t>(y % 256) * 256 + x % 256) * 4, 4);
    return c;
}

std::uint8_t noise(std::uint32_t x, std::uint32_t y) {
    std::uint32_t h = x * 374761393U + y * 668265263U;
    h = (h ^ (h >> 13)) * 1274126177U;
    return static_cast<std::uint8_t>((h ^ (h >> 16)) & 0xFFU);
}

/** Left half: dark texture (60 ± 20); right half: flat bright tone 200. */
TileStore twoTone() {
    return paint(512, 256, [](std::uint32_t x, std::uint32_t y) {
        if (x < 256) {
            const auto v = static_cast<std::uint8_t>(40 + noise(x, y) * 40 / 255);
            return Rgba{v, v, v, 255};
        }
        return Rgba{200, 200, 200, 255};
    });
}

struct Stats {
    double mean = 0;
    double stddev = 0;
};

Stats statsInside(const TileStore& s, const TileStore& mask, int channel = 0) {
    double sum = 0, sq = 0;
    std::size_t n = 0;
    for (std::uint32_t y = 0; y < s.height(); ++y)
        for (std::uint32_t x = 0; x < s.width(); ++x) {
            if (sel::coverageAt(mask, x, y) != 65535U) continue;
            const double v = at(s, x, y)[static_cast<std::size_t>(channel)];
            sum += v;
            sq += v * v;
            ++n;
        }
    const double mean = sum / static_cast<double>(n);
    return {mean, std::sqrt(std::max(0.0, sq / static_cast<double>(n) - mean * mean))};
}

TEST(Heal, CloneStampCopiesThroughTheMaskAndLeavesTheRestAlone) {
    auto image = twoTone();
    const auto original = image;
    const auto dab = sel::rasterizeEllipse(512, 256, {340, 80, 420, 160}, true);
    std::string error;
    ASSERT_TRUE(pt::cloneStamp(image, original, -256, 0, dab, error)) << error;
    EXPECT_EQ(at(image, 380, 120), at(original, 124, 120)) << "hard-covered pixels copy the source";
    EXPECT_EQ(at(image, 300, 120), at(original, 300, 120)) << "outside the dab is untouched";
    EXPECT_EQ(at(image, 10, 10), at(original, 10, 10));
}

TEST(Heal, CloneStampSoftEdgesBlendByCoverage) {
    auto image = paint(256, 256, [](std::uint32_t, std::uint32_t) { return Rgba{200, 200, 200, 255}; });
    const auto source = paint(256, 256, [](std::uint32_t, std::uint32_t) { return Rgba{0, 0, 0, 255}; });
    // Half coverage everywhere in the rect.
    TileStore half(PixelFormat::A16, 256, 256);
    croptop::TileBuffer t(half.tile_byte_size(), 0);
    for (int y = 100; y < 110; ++y)
        for (int x = 100; x < 110; ++x) {
            const std::uint16_t v = 32768;
            std::memcpy(t.data() + (static_cast<std::size_t>(y) * 256 + x) * 2, &v, 2);
        }
    ASSERT_TRUE(half.writeTile({0, 0}, t));
    std::string error;
    ASSERT_TRUE(pt::cloneStamp(image, source, 0, 0, half, error)) << error;
    EXPECT_NEAR(at(image, 105, 105)[0], 100, 1);
}

TEST(Heal, SoftCoverageOntoTransparentPixelsPreservesSourceColour) {
    auto image = paint(256, 256, [](std::uint32_t, std::uint32_t) { return Rgba{0, 0, 0, 0}; });
    const auto source = paint(256, 256, [](std::uint32_t, std::uint32_t) { return Rgba{0, 0, 255, 255}; });
    TileStore half(PixelFormat::A16, 256, 256);
    croptop::TileBuffer tile(half.tile_byte_size(), 0);
    for (int y = 100; y < 110; ++y)
        for (int x = 100; x < 110; ++x) {
            const std::uint16_t value = 32768;
            std::memcpy(tile.data() + (static_cast<std::size_t>(y) * 256 + x) * 2, &value, 2);
        }
    ASSERT_TRUE(half.writeTile({0, 0}, tile));
    std::string error;
    ASSERT_TRUE(pt::cloneStamp(image, source, 0, 0, half, error)) << error;
    const auto pixel = at(image, 105, 105);
    EXPECT_EQ(pixel[0], 0);
    EXPECT_EQ(pixel[1], 0);
    EXPECT_EQ(pixel[2], 255);
    EXPECT_NEAR(pixel[3], 128, 1);
}

TEST(Heal, CloneStampFractionalOffsetSamplesBilinearly) {
    auto image = paint(256, 64, [](std::uint32_t, std::uint32_t) { return Rgba{0, 0, 0, 255}; });
    const auto ramp = paint(256, 64, [](std::uint32_t x, std::uint32_t) {
        return Rgba{static_cast<std::uint8_t>(x), 0, 0, 255};
    });
    const auto dab = sel::rasterizeRect(256, 64, {100, 20, 120, 40}, false);
    std::string error;
    ASSERT_TRUE(pt::cloneStamp(image, ramp, 10.5, 0, dab, error)) << error;
    EXPECT_NEAR(at(image, 105, 30)[0], 115.5, 0.6);
}

TEST(Heal, HealingKeepsSourceTextureButMatchesDestinationTone) {
    auto image = twoTone();
    const auto original = image;
    const auto dab = sel::rasterizeEllipse(512, 256, {330, 70, 430, 170}, false);
    std::string error;
    ASSERT_TRUE(pt::healingBlend(image, original, -256, 0, dab, error)) << error;
    const auto healed = statsInside(image, dab);
    const auto texture = statsInside(original, sel::rasterizeEllipse(512, 256, {74, 70, 174, 170}, false));
    std::printf("[heal] tone %.1f (target 200), texture σ %.1f (source σ %.1f)\n", healed.mean, healed.stddev,
                texture.stddev);
    EXPECT_NEAR(healed.mean, 200.0, 4.0) << "tone comes from the destination surroundings";
    EXPECT_NEAR(healed.stddev, texture.stddev, texture.stddev * 0.25) << "texture comes from the source";
    // Seam: just inside the edge matches just outside to within the texture's amplitude.
    for (const auto& [ix, ox] : std::array<std::pair<std::uint32_t, std::uint32_t>, 2>{{{332, 328}, {428, 432}}}) {
        EXPECT_NEAR(at(image, ix, 120)[0], at(image, ox, 120)[0], 25.0);
    }
    EXPECT_EQ(at(image, 300, 20), at(original, 300, 20)) << "outside the dab is untouched";
}

TEST(Heal, HealingAFlatAreaFromItselfIsTheIdentity) {
    auto image = paint(300, 300, [](std::uint32_t, std::uint32_t) { return Rgba{90, 140, 30, 255}; });
    const auto original = image;
    const auto dab = sel::rasterizeEllipse(300, 300, {100, 100, 200, 200}, true);
    std::string error;
    ASSERT_TRUE(pt::healingBlend(image, original, 40, -30, dab, error)) << error;
    for (std::uint32_t y = 90; y < 210; y += 7)
        for (std::uint32_t x = 90; x < 210; x += 7) {
            const auto a = at(image, x, y), b = at(original, x, y);
            for (int c = 0; c < 4; ++c) EXPECT_NEAR(a[c], b[c], 1) << x << "," << y;
        }
}

TEST(Heal, HealingContinuesASmoothGradientAcrossTheDab) {
    auto image = paint(400, 200, [](std::uint32_t x, std::uint32_t y) {
        return Rgba{static_cast<std::uint8_t>(x / 2), static_cast<std::uint8_t>(y), 100, 255};
    });
    const auto reference = image;
    const auto flat = paint(400, 200, [](std::uint32_t, std::uint32_t) { return Rgba{10, 250, 10, 255}; });
    const auto dab = sel::rasterizeEllipse(400, 200, {150, 50, 250, 150}, false);
    std::string error;
    ASSERT_TRUE(pt::healingBlend(image, flat, 0, 0, dab, error)) << error;
    // A flat source contributes no gradient, so the result is the harmonic fill = the linear ramp.
    for (std::uint32_t x = 160; x < 240; x += 10) EXPECT_NEAR(at(image, x, 100)[0], at(reference, x, 100)[0], 2) << x;
    EXPECT_NEAR(at(image, 200, 80)[1], 80, 2);
}

TEST(Heal, SpotHealRemovesABlemishFromTexture) {
    const auto clean = paint(256, 256, [](std::uint32_t x, std::uint32_t) {
        return (x / 5) % 2 == 0 ? Rgba{180, 150, 120, 255} : Rgba{160, 130, 100, 255};
    });
    auto image = clean;
    const auto spot = sel::rasterizeEllipse(256, 256, {110, 110, 130, 130}, true);
    std::string error;
    auto blemish = paint(256, 256, [](std::uint32_t, std::uint32_t) { return Rgba{0, 0, 0, 255}; });
    ASSERT_TRUE(pt::cloneStamp(image, blemish, 0, 0, spot, error)) << error;
    ASSERT_TRUE(pt::spotHeal(image, spot, error)) << error;
    const auto healed = statsInside(image, spot);
    EXPECT_GT(healed.mean, 150.0) << "the dark spot is gone";
    EXPECT_EQ(at(image, 20, 20), at(clean, 20, 20));
}

TEST(Heal, SpotHealTypesAllRemoveTheBlemishAndMatchTheTone) {
    // Noisy texture around 150 with a gentle ramp, and a dark blemish.
    const auto clean = paint(300, 300, [](std::uint32_t x, std::uint32_t y) {
        const auto v = static_cast<std::uint8_t>(120 + x / 10 + noise(x, y) * 30 / 255);
        return Rgba{v, static_cast<std::uint8_t>(v - 20), static_cast<std::uint8_t>(v - 40), 255};
    });
    const auto spot = sel::rasterizeEllipse(300, 300, {130, 130, 160, 160}, true);
    const auto blemish = paint(300, 300, [](std::uint32_t, std::uint32_t) { return Rgba{10, 5, 5, 255}; });
    const auto reference = statsInside(clean, spot);
    for (const auto type : {pt::SpotHealType::ContentAware, pt::SpotHealType::ProximityMatch, pt::SpotHealType::CreateTexture}) {
        auto image = clean;
        std::string error;
        ASSERT_TRUE(pt::cloneStamp(image, blemish, 0, 0, spot, error)) << error;
        ASSERT_TRUE(pt::spotHeal(image, spot, error, type)) << error;
        const auto healed = statsInside(image, spot);
        std::printf("[heal] spot type %d: mean %.1f σ %.1f (clean %.1f σ %.1f)\n", static_cast<int>(type), healed.mean,
                    healed.stddev, reference.mean, reference.stddev);
        EXPECT_NEAR(healed.mean, reference.mean, 6.0) << static_cast<int>(type);
        EXPECT_GT(healed.stddev, reference.stddev * 0.5) << "texture, not a flat patch";
        EXPECT_EQ(at(image, 20, 20), at(clean, 20, 20));
    }
}

TEST(Heal, SpotHealNearTheCanvasEdge) {
    for (const auto type : {pt::SpotHealType::ProximityMatch, pt::SpotHealType::CreateTexture}) {
        auto image = twoTone();
        const auto spot = sel::rasterizeEllipse(512, 256, {0, 0, 24, 24}, true);
        std::string error;
        EXPECT_TRUE(pt::spotHeal(image, spot, error, type)) << error;
        const auto huge = sel::rasterizeRect(512, 256, {0, 0, 512, 256}, false);
        EXPECT_FALSE(pt::spotHeal(image, huge, error, type)) << "nothing left to sample from";
    }
}

TEST(Heal, PatchNormalHealsTheRegionFromTheOffsetArea) {
    auto image = twoTone();
    const auto original = image;
    const auto region = sel::rasterizeRect(512, 256, {300, 60, 380, 140}, false);
    std::string error;
    ASSERT_TRUE(pt::patch(image, region, -200, 0, pt::PatchMode::Normal, error)) << error;
    const auto result = statsInside(image, region);
    EXPECT_NEAR(result.mean, 200.0, 4.0);
    EXPECT_GT(result.stddev, 5.0) << "patched area picks up the source texture";
}

TEST(Heal, PatchContentAwareSamplesOnlyTheSourceArea) {
    auto image = paint(512, 256, [](std::uint32_t x, std::uint32_t) {
        if (x < 256) return (x / 4) % 2 == 0 ? Rgba{220, 30, 30, 255} : Rgba{30, 30, 220, 255};
        return Rgba{30, 200, 30, 255};
    });
    const auto region = sel::rasterizeRect(512, 256, {330, 80, 390, 140}, false);
    std::string error;
    ASSERT_TRUE(pt::patch(image, region, -250, 0, pt::PatchMode::ContentAware, error)) << error;
    // Colour adaptation pulls the fill's tone towards the flat green surroundings (red 30), so the stripes'
    // troughs clip at 0; their contrast can only have come from the striped source area (flat fill: σ 0).
    const auto red = statsInside(image, region, 0);
    std::printf("[heal] content-aware patch red σ %.1f\n", red.stddev);
    EXPECT_GT(red.stddev, 25.0) << "the fill came from the striped source area, not the flat surroundings";
}

TEST(Heal, WorksOnHighBitDepthLayers) {
    for (const auto format : {PixelFormat::RGBA16, PixelFormat::RGBA32F}) {
        TileStore image(format, 256, 256);
        croptop::TileBuffer t(image.tile_byte_size(), 0);
        for (std::size_t i = 0; i < 256U * 256U; ++i) {
            for (int c = 0; c < 4; ++c) {
                if (format == PixelFormat::RGBA16) {
                    const std::uint16_t v = 40000;
                    std::memcpy(t.data() + i * 8 + static_cast<std::size_t>(c) * 2, &v, 2);
                } else {
                    const float v = 0.6F;
                    std::memcpy(t.data() + i * 16 + static_cast<std::size_t>(c) * 4, &v, 4);
                }
            }
        }
        ASSERT_TRUE(image.writeTile({0, 0}, t));
        const auto original = image;
        const auto dab = sel::rasterizeEllipse(256, 256, {80, 80, 160, 160}, true);
        std::string error;
        ASSERT_TRUE(pt::healingBlend(image, original, 30, 30, dab, error)) << error;
        const auto tile = image.readTile({0, 0});
        const std::size_t i = 120U * 256U + 120U;
        if (format == PixelFormat::RGBA16) {
            std::uint16_t v = 0;
            std::memcpy(&v, tile->data() + i * 8, 2);
            EXPECT_NEAR(v, 40000, 40);
        } else {
            float v = 0;
            std::memcpy(&v, tile->data() + i * 16, 4);
            EXPECT_NEAR(v, 0.6F, 1e-3F);
        }
    }
}

TEST(Heal, RejectsMismatchedInputsAndIgnoresEmptyMasks) {
    auto image = twoTone();
    const auto original = image;
    std::string error;
    TileStore wrong_size(PixelFormat::A16, 10, 10);
    EXPECT_FALSE(pt::healingBlend(image, original, 0, 0, wrong_size, error));
    const auto dab = sel::rasterizeRect(512, 256, {10, 10, 20, 20}, false);
    TileStore other_format(PixelFormat::RGBA16, 512, 256);
    EXPECT_FALSE(pt::cloneStamp(image, other_format, 0, 0, dab, error));
    EXPECT_FALSE(pt::cloneStamp(image, original, std::nan(""), 0, dab, error));
    TileStore empty(PixelFormat::A16, 512, 256);
    EXPECT_TRUE(pt::healingBlend(image, original, 5, 5, empty, error));
    EXPECT_TRUE(pt::spotHeal(image, empty, error));
    EXPECT_EQ(at(image, 100, 100), at(original, 100, 100));
}

TEST(Heal, SourceOutsideTheCanvasReadsTransparent) {
    auto image = twoTone();
    const auto original = image;
    const auto dab = sel::rasterizeRect(512, 256, {0, 0, 20, 20}, false);
    std::string error;
    ASSERT_TRUE(pt::cloneStamp(image, original, -100, -100, dab, error)) << error;
    EXPECT_EQ(at(image, 5, 5), (Rgba{0, 0, 0, 0}));
}

TEST(Heal, ExtremeFiniteOffsetsReadTransparentAndPreserveUncoveredPixels) {
    for (const double offset : {1e100, -1e100}) {
        auto image = twoTone();
        const auto original = image;
        const auto dab = sel::rasterizeRect(512, 256, {0, 0, 20, 20}, false);
        std::string error;
        ASSERT_TRUE(pt::cloneStamp(image, original, offset, offset, dab, error)) << error;
        EXPECT_EQ(at(image, 5, 5), (Rgba{0, 0, 0, 0}));
        EXPECT_EQ(at(image, 100, 100), at(original, 100, 100));
    }
}

TEST(Heal, PerDabTimingAt200PxOn24Megapixels) {
    CROPTOP_REQUIRE_STRESS();
    constexpr std::uint32_t kW = 6000, kH = 4000;
    // Only the tiles near the dab carry pixels; that is all a dab reads.
    TileStore image(PixelFormat::RGBA8, kW, kH);
    for (std::int32_t ty = 6; ty <= 9; ++ty)
        for (std::int32_t tx = 10; tx <= 14; ++tx) {
            croptop::TileBuffer t(image.tile_byte_size(), 0);
            for (std::size_t i = 0; i < 256U * 256U; ++i) {
                const auto v = noise(static_cast<std::uint32_t>(i % 256 + static_cast<std::size_t>(tx) * 256),
                                     static_cast<std::uint32_t>(i / 256 + static_cast<std::size_t>(ty) * 256));
                t[i * 4] = v;
                t[i * 4 + 1] = static_cast<std::uint8_t>(v / 2);
                t[i * 4 + 2] = 90;
                t[i * 4 + 3] = 255;
            }
            ASSERT_TRUE(image.writeTile({tx, ty}, t));
        }
    const auto source = image;
    const auto dab = sel::rasterizeEllipse(kW, kH, {3200, 1900, 3400, 2100}, true);
    std::string error;
    const auto start = std::chrono::steady_clock::now();
    constexpr int kDabs = 5;
    for (int i = 0; i < kDabs; ++i) ASSERT_TRUE(pt::healingBlend(image, source, -300, 60, dab, error)) << error;
    const auto heal_ms = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - start).count() / kDabs;
    const auto clone_start = std::chrono::steady_clock::now();
    for (int i = 0; i < kDabs; ++i) ASSERT_TRUE(pt::cloneStamp(image, source, -300, 60, dab, error)) << error;
    const auto clone_ms =
        std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - clone_start).count() / kDabs;
    std::printf("[heal] 200 px dab on 24 MP: healing %.2f ms, clone %.2f ms\n", heal_ms, clone_ms);
}

}  // namespace
