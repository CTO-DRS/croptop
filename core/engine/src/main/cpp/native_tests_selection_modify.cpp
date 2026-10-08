// Host checks for Select ▸ Modify pixel semantics and bounded working sets.
#include "selection_mask.h"
#include "selection_modify.h"

#include <gtest/gtest.h>

#include <cmath>
#include <cstring>
#include <limits>
#include <string>

namespace {

using croptop::PixelFormat;
using croptop::TileBuffer;
using croptop::TileStore;

TileStore pointMask(std::uint32_t width, std::uint32_t height, std::int32_t x, std::int32_t y,
                    std::uint16_t value = croptop::selection::kFullySelected) {
    TileStore mask(PixelFormat::A16, width, height);
    TileBuffer tile(mask.tile_byte_size(), 0);
    const auto offset = (static_cast<std::size_t>(y) * croptop::kTileEdge + static_cast<std::size_t>(x)) * 2U;
    std::memcpy(tile.data() + offset, &value, sizeof(value));
    EXPECT_TRUE(mask.writeTile({0, 0}, tile));
    return mask;
}

std::uint16_t at(const TileStore& mask, std::int64_t x, std::int64_t y) {
    return croptop::selection::coverageAt(mask, x, y);
}

TEST(SelectionModify, ExpandUsesEuclideanDistanceAndFractionalCoverage) {
    const auto mask = pointMask(9, 9, 4, 4);
    std::string error;
    const auto expanded = croptop::selection::expand(mask, 1.0F, error);
    ASSERT_TRUE(expanded) << error;
    EXPECT_EQ(at(*expanded, 4, 4), 65535);
    EXPECT_EQ(at(*expanded, 5, 4), 65535);
    EXPECT_NEAR(at(*expanded, 5, 5), std::lround((2.0 - std::sqrt(2.0)) * 65535.0), 1);
    EXPECT_EQ(at(*expanded, 6, 4), 0);

    const auto fractional = croptop::selection::expand(mask, 1.5F, error);
    ASSERT_TRUE(fractional) << error;
    EXPECT_EQ(at(*fractional, 5, 5), 65535);  // Radius 1.5 fully covers the diagonal center at sqrt(2).
}

TEST(SelectionModify, ContractAndBorderRespectCanvasEdges) {
    std::string error;
    const auto full = croptop::selection::fullMask(9, 7);
    const auto contracted_full = croptop::selection::contract(full, 2.0F, error);
    ASSERT_TRUE(contracted_full) << error;
    EXPECT_EQ(at(*contracted_full, 0, 0), 65535);
    EXPECT_EQ(at(*contracted_full, 8, 6), 65535);

    const auto square = croptop::selection::rasterizeRect(9, 9, {2, 2, 7, 7}, false);
    const auto contracted = croptop::selection::contract(square, 1.0F, error);
    ASSERT_TRUE(contracted) << error;
    EXPECT_EQ(at(*contracted, 2, 4), 0);
    EXPECT_EQ(at(*contracted, 4, 4), 65535);
    EXPECT_EQ(at(*contracted, 6, 4), 0);

    const auto band = croptop::selection::border(square, 2.0F, error);
    ASSERT_TRUE(band) << error;
    EXPECT_GT(at(*band, 2, 4), 0);
    EXPECT_EQ(at(*band, 4, 4), 0);
    const auto zero_band = croptop::selection::border(square, 0.0F, error);
    ASSERT_TRUE(zero_band) << error;
    EXPECT_TRUE(croptop::selection::isEmpty(*zero_band));
}

TEST(SelectionModify, FeatherIsSymmetricAndZeroRadiusIsIdentity) {
    const auto mask = pointMask(9, 9, 4, 4);
    std::string error;
    const auto identity = croptop::selection::feather(mask, 0.0F, error);
    ASSERT_TRUE(identity) << error;
    EXPECT_EQ(at(*identity, 4, 4), 65535);
    EXPECT_EQ(at(*identity, 3, 4), 0);

    const auto blurred = croptop::selection::feather(mask, 3.0F, error);
    ASSERT_TRUE(blurred) << error;
    EXPECT_GT(at(*blurred, 4, 4), at(*blurred, 3, 4));
    EXPECT_EQ(at(*blurred, 3, 4), at(*blurred, 5, 4));
    EXPECT_EQ(at(*blurred, 4, 3), at(*blurred, 4, 5));
    EXPECT_EQ(at(*blurred, 0, 0), 0);
}

TEST(SelectionModify, SmoothUsesMajorityAndKeepsTies) {
    std::string error;
    const auto isolated = pointMask(9, 9, 4, 4);
    const auto removed = croptop::selection::smooth(isolated, 1.0F, error);
    ASSERT_TRUE(removed) << error;
    EXPECT_TRUE(croptop::selection::isEmpty(*removed));

    const auto square = croptop::selection::rasterizeRect(9, 9, {3, 3, 6, 6}, false);
    const auto retained = croptop::selection::smooth(square, 1.0F, error);
    ASSERT_TRUE(retained) << error;
    EXPECT_EQ(at(*retained, 4, 3), 65535);
    EXPECT_EQ(at(*retained, 4, 4), 65535);
    EXPECT_EQ(at(*retained, 2, 2), 0);

    auto tie = pointMask(2, 2, 0, 0);
    TileBuffer top_row(tie.tile_byte_size(), 0);
    const std::uint16_t full = 65535;
    std::memcpy(top_row.data(), &full, sizeof(full));
    std::memcpy(top_row.data() + 2U, &full, sizeof(full));
    ASSERT_TRUE(tie.writeTile({0, 0}, top_row));
    const auto tied = croptop::selection::smooth(tie, 1.0F, error);
    ASSERT_TRUE(tied) << error;
    EXPECT_EQ(at(*tied, 0, 0), 65535);
    EXPECT_EQ(at(*tied, 0, 1), 0);
}

TEST(SelectionModify, RejectsInvalidInputsAndWorkingSetsAboveLimit) {
    std::string error;
    const TileStore wrong_format(PixelFormat::RGBA8, 9, 9);
    EXPECT_FALSE(croptop::selection::expand(wrong_format, 1.0F, error));
    const auto mask = pointMask(9, 9, 4, 4);
    EXPECT_FALSE(croptop::selection::feather(mask, std::numeric_limits<float>::quiet_NaN(), error));
    EXPECT_FALSE(croptop::selection::expand(mask, -0.25F, error));

    croptop::selection::ModifyLimits limits;
    limits.max_working_bytes = 1;
    const auto rejected = croptop::selection::expand(mask, 1.0F, error, limits);
    EXPECT_FALSE(rejected);
    EXPECT_NE(error.find("memory limit"), std::string::npos);
    EXPECT_EQ(at(mask, 4, 4), 65535);  // A rejected operation must leave the source intact.
}

}  // namespace
