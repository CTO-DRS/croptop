// Tests for AVIF import/export.
#include "avif_codec.h"
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
namespace avif = croptop::avif;

constexpr std::uint32_t kW = 300;  // crosses a tile edge
constexpr std::uint32_t kH = 140;

TileStore gradient(PixelFormat format) {
    TileStore store(format, kW, kH);
    const auto bpp = store.bytes_per_pixel();
    for (std::uint32_t ty = 0; ty * croptop::kTileEdge < kH; ++ty) {
        for (std::uint32_t tx = 0; tx * croptop::kTileEdge < kW; ++tx) {
            croptop::TileBuffer tile(store.tile_byte_size(), 0);
            for (std::uint32_t y = 0; y < croptop::kTileEdge; ++y) {
                for (std::uint32_t x = 0; x < croptop::kTileEdge; ++x) {
                    const auto X = tx * croptop::kTileEdge + x, Y = ty * croptop::kTileEdge + y;
                    if (X >= kW || Y >= kH) continue;
                    auto* p = tile.data() + (static_cast<std::size_t>(y) * croptop::kTileEdge + x) * bpp;
                    const double v[4] = {X / double(kW - 1), Y / double(kH - 1), 0.5, 0.25 + 0.75 * (X % 64) / 63.0};
                    for (int c = 0; c < 4; ++c) {
                        if (format == PixelFormat::RGBA8) p[c] = static_cast<std::uint8_t>(std::lround(v[c] * 255));
                        else if (format == PixelFormat::RGBA16) { const auto q = static_cast<std::uint16_t>(std::lround(v[c] * 65535)); std::memcpy(p + c * 2, &q, 2); }
                        else { const auto f = static_cast<float>(v[c]); std::memcpy(p + c * 4, &f, 4); }
                    }
                }
            }
            EXPECT_TRUE(store.writeTile({static_cast<std::int32_t>(tx), static_cast<std::int32_t>(ty)}, tile));
        }
    }
    return store;
}

double channel(const TileStore& s, std::uint32_t x, std::uint32_t y, int c) {
    const auto tile = s.readTile({static_cast<std::int32_t>(x / croptop::kTileEdge), static_cast<std::int32_t>(y / croptop::kTileEdge)});
    if (!tile) return 0.0;
    const auto bpp = s.bytes_per_pixel();
    const auto* p = tile->data() + (static_cast<std::size_t>(y % croptop::kTileEdge) * croptop::kTileEdge + x % croptop::kTileEdge) * bpp;
    if (s.format() == PixelFormat::RGBA8) return p[c] / 255.0;
    if (s.format() == PixelFormat::RGBA16) { std::uint16_t v; std::memcpy(&v, p + c * 2, 2); return v / 65535.0; }
    float f; std::memcpy(&f, p + c * 4, 4); return f;
}

double meanError(const TileStore& a, const TileStore& b) {
    double sum = 0;
    for (std::uint32_t y = 0; y < kH; y += 3)
        for (std::uint32_t x = 0; x < kW; x += 3)
            for (int c = 0; c < 4; ++c) sum += std::abs(channel(a, x, y, c) - channel(b, x, y, c));
    return sum / (((kH + 2) / 3) * ((kW + 2) / 3) * 4.0);
}

}  // namespace

TEST(Avif, LosslessRoundTripKeepsEightBitPixelsExact) {
    const auto source = gradient(PixelFormat::RGBA8);
    avif::EncodeOptions options;
    options.quality = 100;
    options.speed = 9;
    std::string error;
    const auto bytes = avif::encode(source, options, error);
    ASSERT_TRUE(bytes.has_value()) << error;
    EXPECT_TRUE(avif::isAvif(*bytes));
    const auto info = avif::probe(*bytes, error);
    ASSERT_TRUE(info.has_value()) << error;
    EXPECT_EQ(info->width, kW);
    EXPECT_EQ(info->height, kH);
    const auto decoded = avif::decode(*bytes, error);
    ASSERT_TRUE(decoded.has_value()) << error;
    ASSERT_EQ(decoded->pixels.format(), PixelFormat::RGBA8);
    EXPECT_TRUE(decoded->has_alpha);
    EXPECT_EQ(meanError(source, decoded->pixels), 0.0);
}

TEST(Avif, LossyStaysCloseAndIsMuchSmaller) {
    const auto source = gradient(PixelFormat::RGBA8);
    avif::EncodeOptions options;
    options.quality = 75;
    options.speed = 9;
    std::string error;
    const auto bytes = avif::encode(source, options, error);
    ASSERT_TRUE(bytes.has_value()) << error;
    EXPECT_LT(bytes->size(), static_cast<std::size_t>(kW) * kH);
    const auto decoded = avif::decode(*bytes, error);
    ASSERT_TRUE(decoded.has_value()) << error;
    EXPECT_LT(meanError(source, decoded->pixels), 0.03);
}

TEST(Avif, TenBitExportDecodesToSixteenBit) {
    const auto source = gradient(PixelFormat::RGBA16);
    avif::EncodeOptions options;
    options.quality = 90;
    options.speed = 9;
    options.bit_depth = 10;
    std::string error;
    const auto bytes = avif::encode(source, options, error);
    ASSERT_TRUE(bytes.has_value()) << error;
    const auto decoded = avif::decode(*bytes, error);
    ASSERT_TRUE(decoded.has_value()) << error;
    EXPECT_EQ(decoded->pixels.format(), PixelFormat::RGBA16);
    EXPECT_EQ(decoded->source_bit_depth, 10);
    EXPECT_LT(meanError(source, decoded->pixels), 0.03);
}

TEST(Avif, FloatSourceAndIccProfileRoundTrip) {
    const auto source = gradient(PixelFormat::RGBA32F);
    avif::EncodeOptions options;
    options.speed = 9;
    options.icc_profile = {1, 2, 3, 4, 5, 6, 7, 8};
    std::string error;
    const auto bytes = avif::encode(source, options, error);
    ASSERT_TRUE(bytes.has_value()) << error;
    const auto decoded = avif::decode(*bytes, error);
    ASSERT_TRUE(decoded.has_value()) << error;
    EXPECT_EQ(decoded->icc_profile, options.icc_profile);
}

TEST(Avif, RejectsBadOptionsAndBadFiles) {
    const auto source = gradient(PixelFormat::RGBA8);
    std::string error;
    avif::EncodeOptions bad;
    bad.bit_depth = 9;
    EXPECT_FALSE(avif::encode(source, bad, error).has_value());
    bad = {};
    bad.quality = 101;
    EXPECT_FALSE(avif::encode(source, bad, error).has_value());
    TileStore mask(PixelFormat::A16, 16, 16);
    EXPECT_FALSE(avif::encode(mask, {}, error).has_value());
    const std::vector<std::uint8_t> junk(64, 0x42);
    EXPECT_FALSE(avif::isAvif(junk));
    EXPECT_FALSE(avif::decode(junk, error).has_value());
    avif::EncodeOptions ok;
    ok.speed = 10;
    auto bytes = avif::encode(source, ok, error);
    ASSERT_TRUE(bytes.has_value()) << error;
    bytes->resize(bytes->size() / 2);
    EXPECT_FALSE(avif::decode(*bytes, error).has_value());
    ok.quality = 50;
    const auto small = avif::encode(source, ok, error);
    ASSERT_TRUE(small.has_value());
    EXPECT_FALSE(avif::decode(*small, error, 100).has_value());  // pixel limit
}
