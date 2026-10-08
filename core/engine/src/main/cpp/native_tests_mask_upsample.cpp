// Tests for guided mask upsampling.
#include "mask_upsample.h"
#include "tile_store.h"

#include <gtest/gtest.h>

#include <cmath>
#include <cstdint>
#include <cstring>
#include <string>
#include <vector>

namespace {

using croptop::PixelFormat;
using croptop::TileStore;
namespace sel = croptop::selection;

constexpr std::uint32_t kFull = 512;
constexpr std::uint32_t kLow = 64;

bool insideDisk(double x, double y) { return std::hypot(x - 256.0, y - 250.0) < 150.0; }

TileStore makeGuide() {
    TileStore guide(PixelFormat::RGBA8, kFull, kFull);
    for (std::uint32_t ty = 0; ty < kFull / croptop::kTileEdge; ++ty) {
        for (std::uint32_t tx = 0; tx < kFull / croptop::kTileEdge; ++tx) {
            croptop::TileBuffer tile(guide.tile_byte_size());
            for (std::uint32_t y = 0; y < croptop::kTileEdge; ++y) {
                for (std::uint32_t x = 0; x < croptop::kTileEdge; ++x) {
                    const bool in = insideDisk(tx * croptop::kTileEdge + x + 0.5, ty * croptop::kTileEdge + y + 0.5);
                    auto* p = tile.data() + (static_cast<std::size_t>(y) * croptop::kTileEdge + x) * 4U;
                    p[0] = in ? 230 : 20;
                    p[1] = in ? 180 : 30;
                    p[2] = in ? 90 : 60;
                    p[3] = 255;
                }
            }
            EXPECT_TRUE(guide.writeTile({static_cast<std::int32_t>(tx), static_cast<std::int32_t>(ty)}, tile));
        }
    }
    return guide;
}

// A model-like low-res mask: the disk sampled coarsely (blocky, up to half a low-res pixel off).
TileStore makeLowMask() {
    TileStore mask(PixelFormat::A16, kLow, kLow);
    croptop::TileBuffer tile(mask.tile_byte_size(), 0);
    for (std::uint32_t y = 0; y < kLow; ++y) {
        for (std::uint32_t x = 0; x < kLow; ++x) {
            const std::uint16_t v = insideDisk((x + 0.5) * (kFull / kLow), (y + 0.5) * (kFull / kLow)) ? 65535 : 0;
            std::memcpy(tile.data() + (static_cast<std::size_t>(y) * croptop::kTileEdge + x) * 2U, &v, 2U);
        }
    }
    EXPECT_TRUE(mask.writeTile({0, 0}, tile));
    return mask;
}

std::uint16_t at(const TileStore& s, std::uint32_t x, std::uint32_t y) {
    const auto tile = s.readTile({static_cast<std::int32_t>(x / croptop::kTileEdge), static_cast<std::int32_t>(y / croptop::kTileEdge)});
    if (tile == nullptr) return 0;
    std::uint16_t v = 0;
    std::memcpy(&v, tile->data() + (static_cast<std::size_t>(y % croptop::kTileEdge) * croptop::kTileEdge + x % croptop::kTileEdge) * 2U, 2U);
    return v;
}

}  // namespace

TEST(MaskUpsample, FollowsImageEdgeMoreCloselyThanBlockyInput) {
    const auto guide = makeGuide();
    const auto low = makeLowMask();
    std::string error;
    const auto out = sel::guidedUpsample(guide, low, {}, error);
    ASSERT_TRUE(out.has_value()) << error;
    ASSERT_EQ(out->width(), kFull);
    ASSERT_EQ(out->format(), PixelFormat::A16);
    double error_sum = 0.0;
    for (std::uint32_t y = 0; y < kFull; ++y) {
        for (std::uint32_t x = 0; x < kFull; ++x) {
            const double truth = insideDisk(x + 0.5, y + 0.5) ? 1.0 : 0.0;
            error_sum += std::abs(at(*out, x, y) / 65535.0 - truth);
        }
    }
    // Under 0.15 % of the image's pixels wrong (about 390 of 262144).
    EXPECT_LT(error_sum, 0.0015 * kFull * kFull) << error_sum;
    EXPECT_EQ(at(*out, 256, 250), 65535);
    EXPECT_EQ(at(*out, 5, 5), 0);
}

TEST(MaskUpsample, EmptyMaskStaysEmptyAndSparse) {
    const auto guide = makeGuide();
    TileStore low(PixelFormat::A16, kLow, kLow);
    std::string error;
    const auto out = sel::guidedUpsample(guide, low, {}, error);
    ASSERT_TRUE(out.has_value()) << error;
    EXPECT_TRUE(out->tileCoordinates().empty());
}

TEST(MaskUpsample, RejectsBadInputs) {
    const auto guide = makeGuide();
    std::string error;
    TileStore wrong(PixelFormat::A8, kLow, kLow);
    EXPECT_FALSE(sel::guidedUpsample(guide, wrong, {}, error).has_value());
    TileStore larger(PixelFormat::A16, kFull * 2, kFull * 2);
    EXPECT_FALSE(sel::guidedUpsample(guide, larger, {}, error).has_value());
    sel::GuidedUpsampleSpec spec;
    spec.radius = 65;
    EXPECT_FALSE(sel::guidedUpsample(guide, makeLowMask(), spec, error).has_value());
    spec.radius = 4;
    spec.epsilon = 0.0F;
    EXPECT_FALSE(sel::guidedUpsample(guide, makeLowMask(), spec, error).has_value());
}

TEST(MaskUpsample, RadiusZeroOnlyResizesTheMask) {
    // Same size in and out: radius 0 must give the mask back unchanged, whatever the guide's edges say.
    const auto guide = makeGuide();
    TileStore mask(PixelFormat::A16, kFull, kFull);
    croptop::TileBuffer tile(mask.tile_byte_size(), 0);
    for (std::uint32_t y = 0; y < std::min<std::uint32_t>(kFull, croptop::kTileEdge); ++y) {
        for (std::uint32_t x = 0; x < std::min<std::uint32_t>(kFull, croptop::kTileEdge); ++x) {
            const std::uint16_t v = static_cast<std::uint16_t>(((x * 37U + y * 11U) % 256U) * 257U);
            std::memcpy(tile.data() + (static_cast<std::size_t>(y) * croptop::kTileEdge + x) * 2U, &v, 2U);
        }
    }
    ASSERT_TRUE(mask.writeTile({0, 0}, tile));
    sel::GuidedUpsampleSpec spec;
    spec.radius = 0;
    std::string error;
    const auto out = sel::guidedUpsample(guide, mask, spec, error);
    ASSERT_TRUE(out.has_value()) << error;
    const auto read = out->readTile({0, 0});
    ASSERT_NE(read, nullptr);
    for (std::uint32_t y = 0; y < std::min<std::uint32_t>(kFull, croptop::kTileEdge); ++y) {
        for (std::uint32_t x = 0; x < std::min<std::uint32_t>(kFull, croptop::kTileEdge); ++x) {
            std::uint16_t got = 0;
            std::memcpy(&got, read->data() + (static_cast<std::size_t>(y) * croptop::kTileEdge + x) * 2U, 2U);
            ASSERT_EQ(got, static_cast<std::uint16_t>(((x * 37U + y * 11U) % 256U) * 257U)) << x << "," << y;
        }
    }
}
