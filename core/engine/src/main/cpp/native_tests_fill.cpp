// Tests for Edit ▸ Fill.
#include "fill_command.h"
#include "selection_mask.h"
#include "tile_store.h"

#include <gtest/gtest.h>

#include <array>
#include <cmath>
#include <cstring>
#include <functional>
#include <string>

namespace {

using croptop::BlendMode;
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

pt::FillSpec colorFill(float r, float g, float b, float a = 1.0F) {
    pt::FillSpec spec;
    spec.color = {r, g, b, a};
    return spec;
}

TEST(Fill, FillsTheSelectionWithAColour) {
    auto layer = paint(300, 300, [](std::uint32_t, std::uint32_t) { return Rgba{10, 20, 30, 255}; });
    const auto selection = sel::rasterizeRect(300, 300, {50, 50, 280, 280}, false);
    std::string error;
    ASSERT_TRUE(pt::fill(layer, colorFill(1.0F, 0.5F, 0.0F), &selection, error)) << error;
    EXPECT_EQ(at(layer, 100, 100), (Rgba{255, 128, 0, 255}));
    EXPECT_EQ(at(layer, 270, 270), (Rgba{255, 128, 0, 255})) << "across tile boundaries";
    EXPECT_EQ(at(layer, 10, 10), (Rgba{10, 20, 30, 255}));
}

TEST(Fill, WithoutASelectionFillsTheWholeLayerIncludingEmptyTiles) {
    TileStore layer(PixelFormat::RGBA8, 600, 300);
    std::string error;
    ASSERT_TRUE(pt::fill(layer, colorFill(0.0F, 1.0F, 0.0F), nullptr, error)) << error;
    EXPECT_EQ(at(layer, 0, 0), (Rgba{0, 255, 0, 255}));
    EXPECT_EQ(at(layer, 599, 299), (Rgba{0, 255, 0, 255}));
}

TEST(Fill, OpacityCoverageAndBlendModeApply) {
    auto layer = paint(64, 64, [](std::uint32_t, std::uint32_t) { return Rgba{200, 200, 200, 255}; });
    std::string error;
    auto spec = colorFill(0.0F, 0.0F, 0.0F);
    spec.opacity = 0.5F;
    ASSERT_TRUE(pt::fill(layer, spec, nullptr, error));
    EXPECT_NEAR(at(layer, 5, 5)[0], 100, 1);
    auto multiply = paint(64, 64, [](std::uint32_t, std::uint32_t) { return Rgba{200, 100, 50, 255}; });
    spec = colorFill(0.5F, 0.5F, 0.5F);
    spec.mode = BlendMode::Multiply;
    ASSERT_TRUE(pt::fill(multiply, spec, nullptr, error));
    EXPECT_NEAR(at(multiply, 5, 5)[0], 100, 1);
    EXPECT_NEAR(at(multiply, 5, 5)[1], 50, 1);
    // Half coverage selection halves the effect.
    auto soft = paint(64, 64, [](std::uint32_t, std::uint32_t) { return Rgba{200, 200, 200, 255}; });
    TileStore half(PixelFormat::A16, 64, 64);
    croptop::TileBuffer t(half.tile_byte_size(), 0);
    for (std::size_t i = 0; i < 256U * 256U; ++i) {
        const std::uint16_t v = 32768;
        std::memcpy(t.data() + i * 2, &v, 2);
    }
    ASSERT_TRUE(half.writeTile({0, 0}, t));
    ASSERT_TRUE(pt::fill(soft, colorFill(0.0F, 0.0F, 0.0F), &half, error));
    EXPECT_NEAR(at(soft, 5, 5)[0], 100, 1);
}

TEST(Fill, PreserveTransparencyOnlyRecoloursExistingPixels) {
    auto layer = paint(64, 64, [](std::uint32_t x, std::uint32_t) {
        return x < 32 ? Rgba{0, 0, 0, 0} : Rgba{50, 60, 70, 128};
    });
    auto spec = colorFill(1.0F, 0.0F, 0.0F);
    spec.preserve_transparency = true;
    std::string error;
    ASSERT_TRUE(pt::fill(layer, spec, nullptr, error));
    EXPECT_EQ(at(layer, 5, 5), (Rgba{0, 0, 0, 0}));
    EXPECT_EQ(at(layer, 40, 5), (Rgba{255, 0, 0, 128}));
    TileStore empty(PixelFormat::RGBA8, 64, 64);
    ASSERT_TRUE(pt::fill(empty, spec, nullptr, error));
    EXPECT_TRUE(empty.tileCoordinates().empty()) << "no pixels, nothing to recolour";
}

TEST(Fill, PatternTilesFromItsOrigin) {
    const auto pattern = paint(3, 2, [](std::uint32_t x, std::uint32_t y) {
        return Rgba{static_cast<std::uint8_t>(x * 100), static_cast<std::uint8_t>(y * 100), 7, 255};
    });
    TileStore layer(PixelFormat::RGBA8, 300, 300);
    pt::FillSpec spec;
    spec.source = pt::FillSource::Pattern;
    spec.pattern = &pattern;
    spec.pattern_x = 1;
    spec.pattern_y = -1;
    std::string error;
    ASSERT_TRUE(pt::fill(layer, spec, nullptr, error)) << error;
    // (x − 1) mod 3, (y + 1) mod 2.
    EXPECT_EQ(at(layer, 1, 0), (Rgba{0, 100, 7, 255}));
    EXPECT_EQ(at(layer, 0, 0), (Rgba{200, 100, 7, 255}));
    EXPECT_EQ(at(layer, 260, 257), (Rgba{100, 0, 7, 255}));
}

TEST(Fill, ContentAwareFillsTheSelection) {
    auto layer = paint(256, 256, [](std::uint32_t x, std::uint32_t) {
        return (x / 6) % 2 == 0 ? Rgba{200, 60, 40, 255} : Rgba{30, 90, 180, 255};
    });
    const auto original = layer;
    const auto hole = sel::rasterizeRect(256, 256, {100, 100, 140, 140}, false);
    auto blot = colorFill(0.0F, 0.0F, 0.0F);
    std::string error;
    ASSERT_TRUE(pt::fill(layer, blot, &hole, error));
    pt::FillSpec spec;
    spec.source = pt::FillSource::ContentAware;
    ASSERT_TRUE(pt::fill(layer, spec, &hole, error)) << error;
    int matching = 0;
    for (std::uint32_t x = 100; x < 140; ++x) matching += at(layer, x, 120) == at(original, x, 120) ? 1 : 0;
    EXPECT_GT(matching, 30) << "stripes continue through the filled area";
    EXPECT_FALSE(pt::fill(layer, spec, nullptr, error)) << "content-aware needs a selection";
}

TEST(Fill, RejectsBadInput) {
    TileStore layer(PixelFormat::RGBA8, 64, 64);
    std::string error;
    auto spec = colorFill(0.0F, 0.0F, 0.0F);
    spec.opacity = 2.0F;
    EXPECT_FALSE(pt::fill(layer, spec, nullptr, error));
    spec = colorFill(std::nanf(""), 0.0F, 0.0F);
    EXPECT_FALSE(pt::fill(layer, spec, nullptr, error));
    spec = {};
    spec.source = pt::FillSource::Pattern;
    EXPECT_FALSE(pt::fill(layer, spec, nullptr, error));
    TileStore mask(PixelFormat::A16, 10, 10);
    EXPECT_FALSE(pt::fill(layer, colorFill(0, 0, 0), &mask, error));
    TileStore alpha(PixelFormat::A8, 64, 64);
    EXPECT_FALSE(pt::fill(alpha, colorFill(0, 0, 0), nullptr, error));
}

TEST(Fill, HighBitDepthKeepsPrecision) {
    TileStore layer(PixelFormat::RGBA16, 64, 64);
    std::string error;
    ASSERT_TRUE(pt::fill(layer, colorFill(0.123456F, 0.5F, 1.0F), nullptr, error));
    std::uint16_t r = 0;
    std::memcpy(&r, layer.readTile({0, 0})->data(), 2);
    EXPECT_NEAR(r, 0.123456 * 65535, 1.0);
}

}  // namespace
