// Host tests for color-based selection tools.
#include "selection_color.h"
#include "selection_mask.h"
#include "tile_store.h"

#include <gtest/gtest.h>

#include <algorithm>
#include <array>
#include <cmath>
#include <cstring>
#include <string>
#include <vector>

namespace {

using croptop::PixelFormat;
using croptop::TileStore;

bool sameTiles(const TileStore& a, const TileStore& b) {
    if (a.width() != b.width() || a.height() != b.height() || a.format() != b.format()) return false;
    const croptop::TileBuffer zero(a.tile_byte_size(), 0);
    for (std::uint32_t y = 0; y * croptop::kTileEdge < a.height(); ++y) {
        for (std::uint32_t x = 0; x * croptop::kTileEdge < a.width(); ++x) {
            const croptop::TileCoordinate coordinate{static_cast<std::int32_t>(x), static_cast<std::int32_t>(y)};
            const auto left = a.readTile(coordinate);
            const auto right = b.readTile(coordinate);
            if ((left ? *left : zero) != (right ? *right : zero)) return false;
        }
    }
    return true;
}

// ---- Magic Wand and Color Range -----------------------------------------------------

/** RGBA8 store filled by `color(x, y)` (r, g, b, a bytes). */
template <typename Color>
TileStore paint(std::uint32_t width, std::uint32_t height, Color&& color) {
    TileStore store(PixelFormat::RGBA8, width, height);
    for (std::uint32_t ty = 0; ty * croptop::kTileEdge < height; ++ty) {
        for (std::uint32_t tx = 0; tx * croptop::kTileEdge < width; ++tx) {
            croptop::TileBuffer tile(store.tile_byte_size(), 0);
            for (std::uint32_t y = 0; y < croptop::kTileEdge; ++y) {
                for (std::uint32_t x = 0; x < croptop::kTileEdge; ++x) {
                    const auto gx = tx * croptop::kTileEdge + x;
                    const auto gy = ty * croptop::kTileEdge + y;
                    if (gx >= width || gy >= height) continue;
                    const std::array<std::uint8_t, 4> c = color(gx, gy);
                    std::memcpy(tile.data() + (static_cast<std::size_t>(y) * croptop::kTileEdge + x) * 4, c.data(), 4);
                }
            }
            EXPECT_TRUE(store.writeTile({static_cast<std::int32_t>(tx), static_cast<std::int32_t>(ty)}, tile));
        }
    }
    return store;
}

TEST(MagicWand, FloodFillsFourConnectedRegionsWithinTolerance) {
    // Two red squares on blue, touching only diagonally at (100,100)/(99,99); a near-red speck elsewhere.
    const auto image = paint(300, 280, [](std::uint32_t x, std::uint32_t y) -> std::array<std::uint8_t, 4> {
        if (x < 100 && y < 100) return {200, 10, 10, 255};
        if (x >= 100 && x < 200 && y >= 100 && y < 200) return {200, 10, 10, 255};
        if (x == 250 && y == 250) return {210, 10, 10, 255};  // 10 away in R.
        return {10, 10, 200, 255};
    });
    std::string error;
    const auto contiguous = croptop::selection::magicWand(image, {5, 5, 20, true}, error);
    ASSERT_TRUE(contiguous) << error;
    EXPECT_EQ(croptop::selection::coverageAt(*contiguous, 50, 50), 65535);
    EXPECT_EQ(croptop::selection::coverageAt(*contiguous, 150, 150), 0) << "diagonal contact is not 4-connected";
    EXPECT_EQ(croptop::selection::coverageAt(*contiguous, 250, 250), 0);
    const auto bounds = *croptop::selection::bounds(*contiguous);
    EXPECT_EQ(bounds.right, 100);
    EXPECT_EQ(bounds.bottom, 100);

    const auto global = *croptop::selection::magicWand(image, {5, 5, 20, false}, error);
    EXPECT_EQ(croptop::selection::coverageAt(global, 150, 150), 65535);
    EXPECT_EQ(croptop::selection::coverageAt(global, 250, 250), 65535);
    const auto strict = *croptop::selection::magicWand(image, {5, 5, 9, false}, error);
    EXPECT_EQ(croptop::selection::coverageAt(strict, 250, 250), 0) << "difference 10 exceeds tolerance 9";
    const auto exact = *croptop::selection::magicWand(image, {5, 5, 10, false}, error);
    EXPECT_EQ(croptop::selection::coverageAt(exact, 250, 250), 65535) << "difference 10 is within tolerance 10";

    EXPECT_TRUE(croptop::selection::isEmpty(*croptop::selection::magicWand(image, {-1, 5, 20, true}, error)));
    EXPECT_FALSE(croptop::selection::magicWand(image, {5, 5, 256, true}, error));
    EXPECT_FALSE(croptop::selection::magicWand(TileStore(PixelFormat::A16, 10, 10), {1, 1, 5, true}, error));
}

TEST(MagicWand, SpiralRegionAndLargeUniformFill) {
    // A one-pixel-wide spiral path forces many spans and direction changes.
    const auto spiral = paint(120, 120, [](std::uint32_t x, std::uint32_t y) -> std::array<std::uint8_t, 4> {
        const auto ring = std::min({x, y, 119 - x, 119 - y});
        const bool wall = ring % 4 == 1 && !(x == ring + 1 && y == ring);  // Gaps connect rings.
        return wall ? std::array<std::uint8_t, 4>{0, 0, 0, 255} : std::array<std::uint8_t, 4>{255, 255, 255, 255};
    });
    std::string error;
    const auto filled = *croptop::selection::magicWand(spiral, {0, 0, 0, true}, error);
    const auto global = *croptop::selection::magicWand(spiral, {0, 0, 0, false}, error);
    EXPECT_TRUE(sameTiles(filled, global)) << "all white pixels are connected through the gaps";
    const auto uniform = paint(1500, 1100, [](std::uint32_t, std::uint32_t) -> std::array<std::uint8_t, 4> {
        return {7, 7, 7, 255};
    });
    EXPECT_TRUE(sameTiles(*croptop::selection::magicWand(uniform, {700, 500, 0, true}, error),
                          croptop::selection::fullMask(1500, 1100)));
}

/** Independent CIELAB reference for the tests. */
std::array<double, 3> labOf(double r, double g, double b) {
    const auto lin = [](double v) { return v <= 0.04045 ? v / 12.92 : std::pow((v + 0.055) / 1.055, 2.4); };
    r = lin(r);
    g = lin(g);
    b = lin(b);
    const double x = (0.4124564 * r + 0.3575761 * g + 0.1804375 * b) / 0.95047;
    const double y = 0.2126729 * r + 0.7151522 * g + 0.0721750 * b;
    const double z = (0.0193339 * r + 0.1191920 * g + 0.9503041 * b) / 1.08883;
    const auto f = [](double t) { return t > 0.008856451679 ? std::cbrt(t) : t / 0.1284185493 + 4.0 / 29.0; };
    return {116 * f(y) - 16, 500 * (f(x) - f(y)), 200 * (f(y) - f(z))};
}

TEST(ColorRange, SampledColorsFallOffWithDeltaE) {
    // Columns of greys from 0 to 255, one per x: ΔE to the black sample equals L*.
    const auto image = paint(256, 4, [](std::uint32_t x, std::uint32_t) -> std::array<std::uint8_t, 4> {
        const auto v = static_cast<std::uint8_t>(x);
        return {v, v, v, 255};
    });
    std::string error;
    croptop::selection::ColorRangeSpec spec;
    spec.samples = {{0.0F, 0.0F, 0.0F}};
    spec.fuzziness = 40;
    const auto mask = croptop::selection::colorRange(image, spec, error);
    ASSERT_TRUE(mask) << error;
    for (std::uint32_t x = 0; x < 256; x += 3) {
        const double delta_e = labOf(x / 255.0, x / 255.0, x / 255.0)[0];
        const double expected = delta_e <= 10 ? 1.0 : std::max(0.0, (20 - delta_e) / 10);
        EXPECT_NEAR(croptop::selection::coverageAt(*mask, x, 1) / 65535.0, expected, 1e-3) << x;
    }
    spec.invert = true;
    const auto inverted = *croptop::selection::colorRange(image, spec, error);
    EXPECT_EQ(croptop::selection::coverageAt(inverted, 0, 0), 0);
    EXPECT_EQ(croptop::selection::coverageAt(inverted, 255, 0), 65535);
    spec.samples.clear();
    EXPECT_FALSE(croptop::selection::colorRange(image, spec, error));
}

TEST(ColorRange, PresetsAndAlpha) {
    const std::vector<std::array<std::uint8_t, 4>> palette = {
        {255, 0, 0, 255},     // 0 red
        {0, 200, 0, 255},     // 1 green
        {30, 60, 230, 255},   // 2 blue
        {128, 128, 128, 255}, // 3 grey
        {250, 250, 250, 255}, // 4 near white
        {8, 8, 8, 255},       // 5 near black
        {224, 172, 105, 255}, // 6 skin tone
        {255, 0, 0, 128},     // 7 half-transparent red
        {119, 119, 119, 255}, // 8 mid grey (L* ~ 50)
    };
    const auto image = paint(static_cast<std::uint32_t>(palette.size()), 1, [&](std::uint32_t x, std::uint32_t) {
        return palette[x];
    });
    std::string error;
    const auto coverage = [&](croptop::selection::ColorRangeTarget target, std::uint32_t x) {
        croptop::selection::ColorRangeSpec spec;
        spec.target = target;
        return croptop::selection::coverageAt(*croptop::selection::colorRange(image, spec, error), x, 0) / 65535.0;
    };
    using T = croptop::selection::ColorRangeTarget;
    EXPECT_EQ(coverage(T::Reds, 0), 1.0);
    EXPECT_EQ(coverage(T::Reds, 1), 0.0);
    EXPECT_EQ(coverage(T::Reds, 3), 0.0) << "grey has no hue";
    EXPECT_EQ(coverage(T::Greens, 1), 1.0);
    EXPECT_GT(coverage(T::Blues, 2), 0.95);
    EXPECT_EQ(coverage(T::Highlights, 4), 1.0);
    EXPECT_EQ(coverage(T::Shadows, 4), 0.0);
    EXPECT_EQ(coverage(T::Shadows, 5), 1.0);
    EXPECT_EQ(coverage(T::Midtones, 8), 1.0);
    EXPECT_GT(coverage(T::SkinTones, 6), 0.9);
    EXPECT_EQ(coverage(T::SkinTones, 2), 0.0);
    EXPECT_NEAR(coverage(T::Reds, 7), 128.0 / 255.0, 1e-3) << "coverage scales with alpha";
}

}  // namespace
