// Tests for the id-based filter entry point.
#include "filter_dispatch.h"

#include <gtest/gtest.h>

#include <cmath>
#include <cstring>
#include <limits>
#include <random>
#include <string>
#include <vector>

using namespace croptop;

namespace {

TileStore layer() {
    TileStore s(PixelFormat::RGBA8, 160, 120);
    std::mt19937 rng(4);
    TileBuffer t(s.tile_byte_size(), 0);
    for (std::uint32_t y = 0; y < 120; ++y)
        for (std::uint32_t x = 0; x < 160; ++x) {
            auto* p = t.data() + (static_cast<std::size_t>(y) * kTileEdge + x) * 4;
            p[0] = static_cast<std::uint8_t>((x * 3 + rng() % 30) & 255);
            p[1] = static_cast<std::uint8_t>((y * 4 + rng() % 30) & 255);
            p[2] = static_cast<std::uint8_t>(((x ^ y) * 5) & 255);
            p[3] = 255;
        }
    EXPECT_TRUE(s.writeTile({0, 0}, t));
    return s;
}

std::vector<std::uint8_t> bytes(const TileStore& s) {
    const auto tile = s.readTile({0, 0});
    return tile ? std::vector<std::uint8_t>(tile->begin(), tile->end()) : std::vector<std::uint8_t>{};
}

// A plausible parameter set for every id (counts must match filterParameterCount).
std::vector<float> params(int id) {
    switch (id) {
        case 0: return {3};
        case 1: return {3};
        case 2: return {1.5F, 2, 0};
        case 3: return {4};
        case 4: return {30, 12};
        case 5: return {1, 20, 80, 60};
        case 6: return {4, 20};
        case 7: return {80, 60, 10, 20, 30, 6};
        case 8: return {1.2F, 1.5F, 0.1F, 0};
        case 9: return {90};
        case 10: return {0.6F};
        case 11: return {0.7F};
        case 12: return {30, 10, 1, 1};
        case 13: return {40, 1};
        case 14: return {0};
        case 15: return {8, 8, 0};
        case 16: return {135, 3, 1};
        case 17: return {};
        case 18: return {3, 8, 5};
        case 19: return {4, 4};
        case 20: return {4, 8, 6};
        case 21: return {-0.7F, 0.5F, 0.5F};
        case 22: return {0, 0, 0, 1, 1, 1, 1, 1};
        case 23: return {5, -5};
        case 24: return {2, 1, 0};
        case 25: return {0.4F, 4};
        case 26: return {2};
        case 27: return {2, 20};
        case 28: return {5, 0.5F};
        case 29: return {8};
        case 30: return {12};
        case 31: return {8, 108, 162, 90};
        case 32: return {0.3F, 0.4F, 1.0F, 1};
        default: return {};
    }
}

}  // namespace

TEST(FilterDispatch, EveryIdRunsAndChangesThePixels) {
    const auto source = layer();
    TileStore map = layer();
    for (int id = 0; id < filters::kFilterCount; ++id) {
        ASSERT_EQ(filters::filterParameterCount(id), static_cast<int>(params(id).size())) << id;
        TileStore work(source);
        std::string error;
        ASSERT_TRUE(filters::applyFilter(work, id, params(id), &map, nullptr, 7, 2, error)) << "filter " << id << ": " << error;
        EXPECT_NE(bytes(work), bytes(source)) << "filter " << id << " changed nothing";
    }
}

TEST(FilterDispatch, SelectionMaskLimitsTheFilter) {
    const auto source = layer();
    TileStore mask(PixelFormat::A16, 160, 120);  // empty: nothing selected
    TileStore work(source);
    std::string error;
    ASSERT_TRUE(filters::applyFilter(work, 0, params(0), nullptr, &mask, 0, 1, error)) << error;
    EXPECT_EQ(bytes(work), bytes(source));
}

TEST(FilterDispatch, BadCallsAreRejected) {
    TileStore work = layer();
    std::string error;
    const float nan = std::numeric_limits<float>::quiet_NaN();
    EXPECT_FALSE(filters::applyFilter(work, 99, {}, nullptr, nullptr, 0, 1, error));
    EXPECT_FALSE(filters::applyFilter(work, -1, {}, nullptr, nullptr, 0, 1, error));
    EXPECT_FALSE(filters::applyFilter(work, 0, std::vector<float>{1, 2}, nullptr, nullptr, 0, 1, error));   // wrong count
    EXPECT_FALSE(filters::applyFilter(work, 0, std::vector<float>{nan}, nullptr, nullptr, 0, 1, error));
    EXPECT_FALSE(filters::applyFilter(work, 1, std::vector<float>{2.5F}, nullptr, nullptr, 0, 1, error));   // radius must be whole
    EXPECT_FALSE(filters::applyFilter(work, 12, std::vector<float>{30, 10, 2, 1}, nullptr, nullptr, 0, 1, error));  // flag out of range
    EXPECT_FALSE(filters::applyFilter(work, 15, params(15), nullptr, nullptr, 0, 1, error));  // displace without a map
    EXPECT_NE(error.find("map"), std::string::npos);
}

TEST(FilterDispatch, ParameterTableMatchesTheKotlinBridge) {
    int total = 0;
    for (int id = 0; id < filters::kFilterCount; ++id) total += filters::filterParameterCount(id);
    EXPECT_EQ(total, 81);  // NativeFiltersBridgeTableTest pins the same number
}
