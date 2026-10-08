// Host tests for the Quick Selection brush.
#include "selection_mask.h"
#include "selection_quick.h"
#include "tile_store.h"

#include <gtest/gtest.h>

#include <algorithm>
#include <array>
#include <cmath>
#include <cstring>
#include <random>
#include <string>
#include <vector>

namespace {

using croptop::PixelFormat;
using croptop::TileStore;
namespace sel = croptop::selection;

/** Two noisy regions split at x = split: dark blue on the left, light yellow on the right. */
TileStore twoRegions(std::uint32_t width, std::uint32_t height, std::uint32_t split) {
    TileStore store(PixelFormat::RGBA8, width, height);
    std::mt19937 random(11);
    for (std::uint32_t ty = 0; ty * croptop::kTileEdge < height; ++ty) {
        for (std::uint32_t tx = 0; tx * croptop::kTileEdge < width; ++tx) {
            croptop::TileBuffer tile(store.tile_byte_size(), 0);
            for (std::uint32_t y = 0; y < croptop::kTileEdge; ++y) {
                for (std::uint32_t x = 0; x < croptop::kTileEdge; ++x) {
                    const auto gx = tx * croptop::kTileEdge + x;
                    if (gx >= width || ty * croptop::kTileEdge + y >= height) continue;
                    const int noise = static_cast<int>(random() % 11) - 5;
                    const std::array<int, 3> base = gx < split ? std::array<int, 3>{30, 50, 140} : std::array<int, 3>{230, 210, 90};
                    auto* p = tile.data() + (static_cast<std::size_t>(y) * croptop::kTileEdge + x) * 4;
                    for (int c = 0; c < 3; ++c) p[c] = static_cast<std::uint8_t>(std::clamp(base[c] + noise, 0, 255));
                    p[3] = 255;
                }
            }
            EXPECT_TRUE(store.writeTile({static_cast<std::int32_t>(tx), static_cast<std::int32_t>(ty)}, tile));
        }
    }
    return store;
}

TEST(QuickSelection, GrowsThroughSimilarNoisyPixelsAndStopsAtTheEdge) {
    const auto image = twoRegions(400, 300, 200);
    std::string error;
    auto selector = sel::QuickSelector::begin(image, sel::emptyMask(400, 300), {20.0F, false, false}, error);
    ASSERT_NE(selector, nullptr) << error;
    // Dab 20 px from the edge; its region of interest (4 radii + 8 px = 48 px) spans the edge.
    const std::vector<float> dab = {180.0F, 150.0F};
    ASSERT_TRUE(selector->add(dab, error)) << error;
    const auto& mask = selector->current();
    EXPECT_EQ(sel::coverageAt(mask, 180, 150), 65535);
    EXPECT_EQ(sel::coverageAt(mask, 140, 150), 65535) << "grows beyond the brush through similar pixels";
    EXPECT_EQ(sel::coverageAt(mask, 199, 120), 65535) << "reaches the edge";
    EXPECT_EQ(sel::coverageAt(mask, 200, 150), 0) << "never crosses into the yellow region";
    const auto bounds = *sel::bounds(mask);
    EXPECT_LE(bounds.right, 200);
    // Growth is limited to the region of interest around the dab.
    EXPECT_EQ(sel::coverageAt(mask, 180, 150 - 60), 0);
}

TEST(QuickSelection, SecondDabExtendsTheModelAndSubtractRemoves) {
    const auto image = twoRegions(400, 300, 200);
    std::string error;
    auto adder = sel::QuickSelector::begin(image, sel::emptyMask(400, 300), {20.0F, false, false}, error);
    const std::vector<float> both = {150.0F, 150.0F, 250.0F, 150.0F};
    ASSERT_TRUE(adder->add(both, error)) << error;
    EXPECT_EQ(sel::coverageAt(adder->current(), 150, 150), 65535);
    EXPECT_EQ(sel::coverageAt(adder->current(), 250, 150), 65535);

    const auto full = sel::fullMask(400, 300);
    auto subtractor = sel::QuickSelector::begin(image, full, {20.0F, true, false}, error);
    const std::vector<float> dab = {300.0F, 150.0F};
    ASSERT_TRUE(subtractor->add(dab, error)) << error;
    EXPECT_EQ(sel::coverageAt(subtractor->current(), 300, 150), 0);
    EXPECT_EQ(sel::coverageAt(subtractor->current(), 100, 150), 65535) << "the blue side is untouched";

    // Enhance edge softens only the stroke boundary.
    auto soft = sel::QuickSelector::begin(image, sel::emptyMask(400, 300), {20.0F, false, true}, error);
    const std::vector<float> near_edge = {220.0F, 150.0F};
    ASSERT_TRUE(soft->add(near_edge, error));
    const auto finished = soft->finish(error);
    ASSERT_TRUE(finished) << error;
    EXPECT_EQ(sel::coverageAt(soft->current(), 199, 150), 0);
    const auto edge = sel::coverageAt(*finished, 199, 150);
    EXPECT_GT(edge, 0) << "the softened stroke bleeds one pixel past its hard edge";
    EXPECT_LT(edge, 65535);
}

TEST(QuickSelection, ValidatesInput) {
    const auto image = twoRegions(64, 64, 32);
    std::string error;
    EXPECT_EQ(sel::QuickSelector::begin(image, sel::emptyMask(64, 64), {0.5F, false, false}, error), nullptr);
    EXPECT_EQ(sel::QuickSelector::begin(image, sel::emptyMask(65, 64), {10.0F, false, false}, error), nullptr);
    EXPECT_EQ(sel::QuickSelector::begin(sel::emptyMask(64, 64), sel::emptyMask(64, 64), {10.0F, false, false}, error), nullptr);
    auto selector = sel::QuickSelector::begin(image, sel::emptyMask(64, 64), {10.0F, false, false}, error);
    const std::vector<float> odd = {1.0F, 2.0F, 3.0F};
    EXPECT_FALSE(selector->add(odd, error));
    const std::vector<float> outside = {-500.0F, -500.0F};
    EXPECT_TRUE(selector->add(outside, error));
    EXPECT_TRUE(sel::isEmpty(selector->current()));
}

}  // namespace
