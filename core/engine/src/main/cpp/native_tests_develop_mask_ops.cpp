// Tests for develop local color-range masks and algebra.
#include "develop_mask_ops.h"

#include <gtest/gtest.h>

#include <array>
#include <cmath>
#include <cstring>
#include <limits>
#include <string>

namespace {
using croptop::PixelFormat;
using croptop::TileBuffer;
using croptop::TileStore;
namespace ops = croptop::develop::mask_ops;

std::uint16_t at(const TileStore& mask, std::uint32_t x, std::uint32_t y) {
    const auto tile = mask.readTile({static_cast<std::int32_t>(x / croptop::kTileEdge), static_cast<std::int32_t>(y / croptop::kTileEdge)});
    if (!tile) return 0;
    std::uint16_t value = 0;
    std::memcpy(&value, tile->data() + (static_cast<std::size_t>(y % croptop::kTileEdge) * croptop::kTileEdge + x % croptop::kTileEdge) * 2U, 2U);
    return value;
}

TEST(DevelopMaskOps, ColorRangeBuildsFeatheredAlphaWeightedMaskAcrossTiles) {
    TileStore image(PixelFormat::RGBA8, 300, 16);
    TileBuffer left(image.tile_byte_size(), 0), right(image.tile_byte_size(), 0);
    auto set = [](TileBuffer& tile, std::uint32_t x, std::uint8_t r, std::uint8_t g, std::uint8_t b, std::uint8_t a) {
        const auto offset = (static_cast<std::size_t>(4) * croptop::kTileEdge + x) * 4U;
        tile[offset] = r; tile[offset + 1] = g; tile[offset + 2] = b; tile[offset + 3] = a;
    };
    set(left, 255, 255, 0, 0, 255);
    set(right, 0, 230, 25, 0, 255);
    set(right, 1, 0, 255, 0, 255);
    set(right, 2, 255, 0, 0, 0);
    ASSERT_TRUE(image.writeTile({0, 0}, left));
    ASSERT_TRUE(image.writeTile({1, 0}, right));
    TileStore mask(PixelFormat::A16, 300, 16);
    ops::ColorRange range; range.target = {1, 0, 0}; range.tolerance = 0.04F; range.feather = 0.12F;
    std::string error;
    ASSERT_TRUE(ops::colorRange(image, range, mask, error)) << error;
    EXPECT_GT(at(mask, 255, 4), 65000);
    EXPECT_GT(at(mask, 256, 4), 0);
    EXPECT_LT(at(mask, 256, 4), 65535);
    EXPECT_EQ(at(mask, 257, 4), 0);
    EXPECT_EQ(at(mask, 258, 4), 0) << "transparent source pixels do not select";
}

TEST(DevelopMaskOps, ColorRangeRejectsMalformedParametersAndNonFiniteFloatPixels) {
    TileStore image(PixelFormat::RGBA32F, 4, 4);
    TileBuffer source(image.tile_byte_size(), 0);
    float pixel[4] = {NAN, 0.0F, 0.0F, 1.0F};
    std::memcpy(source.data(), pixel, sizeof(pixel));
    ASSERT_TRUE(image.writeTile({0, 0}, source));
    TileStore mask(PixelFormat::A16, 4, 4);
    TileBuffer prior(mask.tile_byte_size(), 0xff);
    ASSERT_TRUE(mask.writeTile({0, 0}, prior));
    ops::ColorRange range;
    std::string error;
    EXPECT_FALSE(ops::colorRange(image, range, mask, error));
    EXPECT_FALSE(error.empty());
    EXPECT_TRUE(mask.tileCoordinates().empty());
    image.clear();
    range.target[1] = std::numeric_limits<float>::infinity();
    EXPECT_FALSE(ops::colorRange(image, range, mask, error));
    range.target = {0, 0, 0}; range.tolerance = std::numeric_limits<float>::quiet_NaN();
    EXPECT_FALSE(ops::colorRange(image, range, mask, error));
}

TEST(DevelopMaskOps, CombineMatchesSelectionMaskAlgebra) {
    TileStore base(PixelFormat::A16, 3, 1), operand(PixelFormat::A16, 3, 1);
    TileBuffer a(base.tile_byte_size(), 0), b(operand.tile_byte_size(), 0);
    const std::array<std::uint16_t, 3> av{10000, 40000, 65535}, bv{30000, 20000, 10000};
    for (std::size_t i = 0; i < av.size(); ++i) {
        std::memcpy(a.data() + i * 2U, &av[i], 2U);
        std::memcpy(b.data() + i * 2U, &bv[i], 2U);
    }
    ASSERT_TRUE(base.writeTile({0, 0}, a)); ASSERT_TRUE(operand.writeTile({0, 0}, b));
    const auto add = ops::combine(base, operand, croptop::selection::Op::Add);
    const auto sub = ops::combine(base, operand, croptop::selection::Op::Subtract);
    const auto intersect = ops::combine(base, operand, croptop::selection::Op::Intersect);
    const auto replace = ops::combine(base, operand, croptop::selection::Op::Replace);
    ASSERT_TRUE(add); ASSERT_TRUE(sub); ASSERT_TRUE(intersect); ASSERT_TRUE(replace);
    EXPECT_EQ(at(*add, 0, 0), 30000);
    EXPECT_EQ(at(*sub, 0, 0), 10000);
    EXPECT_EQ(at(*intersect, 0, 0), 10000);
    EXPECT_EQ(at(*replace, 0, 0), 30000);
    EXPECT_FALSE(ops::combine(base, TileStore(PixelFormat::A16, 2, 1), croptop::selection::Op::Add));
}

}  // namespace
