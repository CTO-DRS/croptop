#include "layer_pixel_ops.h"

#include <gtest/gtest.h>

#include <cstddef>
#include <cstdint>
#include <cstring>
#include <memory>
#include <string>

namespace {

void writeRgba8(croptop::TileStore& store, std::uint32_t x, std::uint32_t y, std::uint8_t r, std::uint8_t g,
                std::uint8_t b, std::uint8_t a) {
    const croptop::TileCoordinate coordinate{static_cast<std::int32_t>(x / croptop::kTileEdge),
                                              static_cast<std::int32_t>(y / croptop::kTileEdge)};
    auto existing = store.readTile(coordinate);
    croptop::TileBuffer tile = existing == nullptr ? croptop::TileBuffer(store.tile_byte_size(), 0U) : *existing;
    const auto index = (static_cast<std::size_t>(y % croptop::kTileEdge) * croptop::kTileEdge + x % croptop::kTileEdge) * 4U;
    tile[index] = r;
    tile[index + 1U] = g;
    tile[index + 2U] = b;
    tile[index + 3U] = a;
    EXPECT_TRUE(store.writeTile(coordinate, tile));
}

void writeMask(croptop::TileStore& store, std::uint32_t x, std::uint32_t y, std::uint16_t coverage) {
    const croptop::TileCoordinate coordinate{static_cast<std::int32_t>(x / croptop::kTileEdge),
                                              static_cast<std::int32_t>(y / croptop::kTileEdge)};
    auto existing = store.readTile(coordinate);
    croptop::TileBuffer tile = existing == nullptr ? croptop::TileBuffer(store.tile_byte_size(), 0U) : *existing;
    const auto index = static_cast<std::size_t>(y % croptop::kTileEdge) * croptop::kTileEdge + x % croptop::kTileEdge;
    std::memcpy(tile.data() + index * sizeof(coverage), &coverage, sizeof(coverage));
    EXPECT_TRUE(store.writeTile(coordinate, tile));
}

std::uint8_t alphaAt(const croptop::TileStore& store, std::uint32_t x, std::uint32_t y) {
    const auto tile = store.readTile({static_cast<std::int32_t>(x / croptop::kTileEdge),
                                      static_cast<std::int32_t>(y / croptop::kTileEdge)});
    if (tile == nullptr) return 0;
    const auto index = (static_cast<std::size_t>(y % croptop::kTileEdge) * croptop::kTileEdge + x % croptop::kTileEdge) * 4U;
    return (*tile)[index + 3U];
}

std::uint8_t channelAt(const croptop::TileStore& store, std::uint32_t x, std::uint32_t y, std::size_t channel) {
    const auto tile = store.readTile({static_cast<std::int32_t>(x / croptop::kTileEdge),
                                      static_cast<std::int32_t>(y / croptop::kTileEdge)});
    if (tile == nullptr) return 0;
    const auto index = (static_cast<std::size_t>(y % croptop::kTileEdge) * croptop::kTileEdge + x % croptop::kTileEdge) * 4U;
    return (*tile)[index + channel];
}

}  // namespace

TEST(LayerPixelOps, FullCutMovesPixelsAndKeepsOutsideTransparent) {
    using namespace croptop;
    auto pool = std::make_shared<TilePool>();
    TileStore source(PixelFormat::RGBA8, 3, 1, pool);
    writeRgba8(source, 0, 0, 10, 20, 30, 255);
    writeRgba8(source, 1, 0, 40, 50, 60, 128);
    TileStore cut = source;
    TileStore copy = source.emptyLike(PixelFormat::RGBA8);
    cut.clear();
    std::string error;
    ASSERT_TRUE(layer_pixels::split(source, cut, copy, nullptr, error)) << error;
    EXPECT_EQ(alphaAt(cut, 0, 0), 0);
    EXPECT_EQ(alphaAt(copy, 0, 0), 255);
    EXPECT_EQ(alphaAt(cut, 1, 0), 0);
    EXPECT_EQ(alphaAt(copy, 1, 0), 128);
    EXPECT_EQ(alphaAt(copy, 2, 0), 0);
}

TEST(LayerPixelOps, SelectionCutAppliesComplementaryCoverageToSourceAlpha) {
    using namespace croptop;
    auto pool = std::make_shared<TilePool>();
    TileStore source(PixelFormat::RGBA8, 2, 1, pool);
    writeRgba8(source, 0, 0, 80, 40, 20, 200);
    writeRgba8(source, 1, 0, 90, 50, 30, 200);
    TileStore mask(PixelFormat::A16, 2, 1, pool);
    writeMask(mask, 0, 0, 0xFFFFU);
    writeMask(mask, 1, 0, 0x8000U);
    TileStore cut = source;
    TileStore copy = source.emptyLike(PixelFormat::RGBA8);
    std::string error;
    ASSERT_TRUE(layer_pixels::split(source, cut, copy, &mask, error)) << error;
    EXPECT_EQ(alphaAt(copy, 0, 0), 200);
    EXPECT_EQ(alphaAt(cut, 0, 0), 0);
    EXPECT_EQ(channelAt(copy, 0, 0, 0), 80);
    EXPECT_EQ(channelAt(cut, 0, 0, 0), 0);
    EXPECT_EQ(alphaAt(cut, 1, 0), 100);
    EXPECT_EQ(alphaAt(copy, 1, 0), 100);
    EXPECT_EQ(channelAt(copy, 1, 0, 0), 90);
    EXPECT_EQ(channelAt(cut, 1, 0, 0), 90);
}

TEST(LayerPixelOps, RejectsMismatchedMaskWithoutChangingOutputs) {
    using namespace croptop;
    auto pool = std::make_shared<TilePool>();
    TileStore source(PixelFormat::RGBA8, 1, 1, pool);
    writeRgba8(source, 0, 0, 1, 2, 3, 255);
    TileStore cut = source;
    TileStore copy = source.emptyLike(PixelFormat::RGBA8);
    TileStore wrong_mask(PixelFormat::A16, 2, 1, pool);
    std::string error;
    EXPECT_FALSE(layer_pixels::split(source, cut, copy, &wrong_mask, error));
    EXPECT_FALSE(error.empty());
    EXPECT_EQ(alphaAt(cut, 0, 0), 255);
    EXPECT_EQ(alphaAt(copy, 0, 0), 0);
}

TEST(LayerPixelOps, SupportsRgba16CoverageWithoutDroppingPrecision) {
    using namespace croptop;
    auto pool = std::make_shared<TilePool>();
    TileStore source(PixelFormat::RGBA16, 1, 1, pool);
    TileBuffer source_tile(source.tile_byte_size(), 0U);
    const std::uint16_t values[4] = {12000U, 24000U, 36000U, 50000U};
    std::memcpy(source_tile.data(), values, sizeof(values));
    ASSERT_TRUE(source.writeTile({0, 0}, source_tile));
    TileStore mask(PixelFormat::A16, 1, 1, pool);
    writeMask(mask, 0, 0, 32768U);
    TileStore cut = source;
    TileStore copy = source.emptyLike(PixelFormat::RGBA16);
    std::string error;
    ASSERT_TRUE(layer_pixels::split(source, cut, copy, &mask, error)) << error;
    auto copy_tile = copy.readTile({0, 0});
    auto cut_tile = cut.readTile({0, 0});
    ASSERT_NE(copy_tile, nullptr);
    ASSERT_NE(cut_tile, nullptr);
    std::uint16_t copy_alpha = 0;
    std::uint16_t cut_alpha = 0;
    std::memcpy(&copy_alpha, copy_tile->data() + 6U, sizeof(copy_alpha));
    std::memcpy(&cut_alpha, cut_tile->data() + 6U, sizeof(cut_alpha));
    EXPECT_NE(copy_alpha, 0U);
    EXPECT_NE(cut_alpha, 0U);
    EXPECT_NEAR(copy_alpha, 25000U, 1U);
    EXPECT_NEAR(cut_alpha, 25000U, 1U);
    for (std::size_t channel = 0; channel < 3; ++channel) {
        std::uint16_t copied = 0;
        std::uint16_t cut_color = 0;
        std::memcpy(&copied, copy_tile->data() + channel * sizeof(copied), sizeof(copied));
        std::memcpy(&cut_color, cut_tile->data() + channel * sizeof(cut_color), sizeof(cut_color));
        EXPECT_EQ(copied, values[channel]);
        EXPECT_EQ(cut_color, values[channel]);
    }
}
