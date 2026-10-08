// Tests for Ultra HDR export.
#include "tile_store.h"
#include "ultra_hdr.h"

#include <gtest/gtest.h>

#include <cmath>
#include <cstdint>
#include <cstring>
#include <string>
#include <vector>

namespace {

using croptop::PixelFormat;
using croptop::TileStore;
namespace uh = croptop::ultrahdr;

constexpr std::uint32_t kW = 320;
constexpr std::uint32_t kH = 200;

// Scene-linear gradient: SDR-range left half, up to 4× SDR white on the right (a highlight the gain map must carry).
TileStore hdrGradient() {
    TileStore store(PixelFormat::RGBA32F, kW, kH);
    for (std::uint32_t ty = 0; ty * croptop::kTileEdge < kH; ++ty) {
        for (std::uint32_t tx = 0; tx * croptop::kTileEdge < kW; ++tx) {
            croptop::TileBuffer tile(store.tile_byte_size(), 0);
            for (std::uint32_t y = 0; y < croptop::kTileEdge; ++y) {
                for (std::uint32_t x = 0; x < croptop::kTileEdge; ++x) {
                    const auto X = tx * croptop::kTileEdge + x, Y = ty * croptop::kTileEdge + y;
                    if (X >= kW || Y >= kH) continue;
                    const float v[4] = {4.0F * X / (kW - 1), 0.5F * Y / (kH - 1), 0.25F, 1.0F};
                    std::memcpy(tile.data() + (static_cast<std::size_t>(y) * croptop::kTileEdge + x) * 16U, v, 16U);
                }
            }
            EXPECT_TRUE(store.writeTile({static_cast<std::int32_t>(tx), static_cast<std::int32_t>(ty)}, tile));
        }
    }
    return store;
}

TileStore sdrBase() {
    TileStore store(PixelFormat::RGBA8, kW, kH);
    for (std::uint32_t ty = 0; ty * croptop::kTileEdge < kH; ++ty) {
        for (std::uint32_t tx = 0; tx * croptop::kTileEdge < kW; ++tx) {
            croptop::TileBuffer tile(store.tile_byte_size(), 0);
            for (std::uint32_t y = 0; y < croptop::kTileEdge; ++y) {
                for (std::uint32_t x = 0; x < croptop::kTileEdge; ++x) {
                    const auto X = tx * croptop::kTileEdge + x, Y = ty * croptop::kTileEdge + y;
                    if (X >= kW || Y >= kH) continue;
                    auto* p = tile.data() + (static_cast<std::size_t>(y) * croptop::kTileEdge + x) * 4U;
                    p[0] = static_cast<std::uint8_t>(std::min(255U, X * 255U / (kW - 1) + 40U));
                    p[1] = static_cast<std::uint8_t>(Y * 255U / (kH - 1));
                    p[2] = 120;
                    p[3] = 255;
                }
            }
            EXPECT_TRUE(store.writeTile({static_cast<std::int32_t>(tx), static_cast<std::int32_t>(ty)}, tile));
        }
    }
    return store;
}

}  // namespace

TEST(UltraHdr, HdrOnlyInputProducesAGainMapJpeg) {
    std::string error;
    const auto bytes = uh::encode(hdrGradient(), nullptr, {}, error);
    ASSERT_TRUE(bytes.has_value()) << error;
    ASSERT_GT(bytes->size(), 1000U);
    EXPECT_EQ((*bytes)[0], 0xFF);
    EXPECT_EQ((*bytes)[1], 0xD8);
    EXPECT_TRUE(uh::isUltraHdr(*bytes));
}

TEST(UltraHdr, SuppliedSdrBaseAndMultiChannelMapAreAccepted) {
    std::string error;
    uh::EncodeOptions options;
    options.multi_channel_gain_map = true;
    options.target_peak_nits = 4000.0F;
    const auto sdr = sdrBase();
    const auto bytes = uh::encode(hdrGradient(), &sdr, options, error);
    ASSERT_TRUE(bytes.has_value()) << error;
    EXPECT_TRUE(uh::isUltraHdr(*bytes));
}

TEST(UltraHdr, PlainJpegAndJunkAreNotUltraHdr) {
    const std::vector<std::uint8_t> junk(64, 0x11);
    EXPECT_FALSE(uh::isUltraHdr(junk));
    EXPECT_FALSE(uh::isUltraHdr({}));
    const std::vector<std::uint8_t> soi_only{0xFF, 0xD8, 0xFF, 0xD9};
    EXPECT_FALSE(uh::isUltraHdr(soi_only));
}

TEST(UltraHdr, RejectsBadInputs) {
    std::string error;
    TileStore eight(PixelFormat::RGBA8, kW, kH);
    EXPECT_FALSE(uh::encode(eight, nullptr, {}, error).has_value());
    TileStore tiny(PixelFormat::RGBA32F, 4, 4);
    EXPECT_FALSE(uh::encode(tiny, nullptr, {}, error).has_value());
    const auto hdr = hdrGradient();
    TileStore wrong(PixelFormat::RGBA8, kW + 8, kH);
    EXPECT_FALSE(uh::encode(hdr, &wrong, {}, error).has_value());
    uh::EncodeOptions bad;
    bad.base_quality = 0;
    EXPECT_FALSE(uh::encode(hdr, nullptr, bad, error).has_value());
    bad = {};
    bad.target_peak_nits = 100.0F;
    EXPECT_FALSE(uh::encode(hdr, nullptr, bad, error).has_value());
}

TEST(UltraHdr, DecodeRecoversHeadroomAboveSdrWhite) {
    std::string error;
    const auto bytes = uh::encode(hdrGradient(), nullptr, {}, error);
    ASSERT_TRUE(bytes.has_value()) << error;
    const auto decoded = uh::decode(*bytes, error);
    ASSERT_TRUE(decoded.has_value()) << error;
    ASSERT_EQ(decoded->format(), PixelFormat::RGBA32F);
    ASSERT_EQ(decoded->width(), kW);
    const auto sample = [&](std::uint32_t x, std::uint32_t y) {
        const auto tile = decoded->readTile({static_cast<std::int32_t>(x / croptop::kTileEdge), static_cast<std::int32_t>(y / croptop::kTileEdge)});
        float v[4] = {};
        if (tile) std::memcpy(v, tile->data() + (static_cast<std::size_t>(y % croptop::kTileEdge) * croptop::kTileEdge + x % croptop::kTileEdge) * 16U, 16U);
        return v[0];
    };
    // Source red ramps 0..4 across the width: the left stays in SDR range, the right keeps clear headroom above 1.0.
    EXPECT_LT(sample(20, 100), 1.0F);
    EXPECT_GT(sample(kW - 10, 100), 2.0F);
    EXPECT_GT(sample(kW - 10, 100), sample(kW / 2, 100));
}

TEST(UltraHdr, DecodeRejectsNonUltraHdrAndLimits) {
    std::string error;
    const std::vector<std::uint8_t> junk(64, 0x22);
    EXPECT_FALSE(uh::decode(junk, error).has_value());
    const auto bytes = uh::encode(hdrGradient(), nullptr, {}, error);
    ASSERT_TRUE(bytes.has_value()) << error;
    EXPECT_FALSE(uh::decode(*bytes, error, 1000).has_value());
}
