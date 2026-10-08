// Tests for per-mask adjustment blend behavior.
#include "develop_mask_blend.h"

#include <gtest/gtest.h>

#include <algorithm>
#include <array>
#include <cmath>
#include <cstring>
#include <limits>
#include <string>

#include "pixel_plane.h"

namespace {
using croptop::PixelFormat;
using croptop::TileBuffer;
using croptop::TileStore;
namespace blend = croptop::develop::mask_blend;

std::array<float, 4> rgba8(const TileStore& store, std::uint32_t x = 0, std::uint32_t y = 0) {
    std::array<float, 4> value{};
    const auto tile = store.readTile({static_cast<std::int32_t>(x / croptop::kTileEdge), static_cast<std::int32_t>(y / croptop::kTileEdge)});
    if (!tile) return value;
    const auto offset = (static_cast<std::size_t>(y % croptop::kTileEdge) * croptop::kTileEdge + x % croptop::kTileEdge) * store.bytes_per_pixel();
    croptop::paint::detail::readPixel(store.format(), tile->data() + offset, value.data());
    return value;
}

void setRgba8(TileStore& store, std::uint8_t r, std::uint8_t g, std::uint8_t b, std::uint8_t a) {
    TileBuffer tile(store.tile_byte_size(), 0);
    tile[0] = r; tile[1] = g; tile[2] = b; tile[3] = a;
    EXPECT_TRUE(store.writeTile({0, 0}, tile));
}

void setMask(TileStore& store, std::uint16_t coverage) {
    TileBuffer tile(store.tile_byte_size(), 0);
    std::memcpy(tile.data(), &coverage, sizeof(coverage));
    EXPECT_TRUE(store.writeTile({0, 0}, tile));
}

TEST(DevelopMaskBlend, MaskAndOpacityEndpointsPreserveAlpha) {
    TileStore base(PixelFormat::RGBA8, 1, 1), adjusted(PixelFormat::RGBA8, 1, 1), mask(PixelFormat::A16, 1, 1);
    setRgba8(base, 51, 102, 153, 91); setRgba8(adjusted, 204, 51, 26, 220);
    std::string error;
    setMask(mask, 0);
    auto none = blend::apply(base, adjusted, mask, 1.0F, error);
    ASSERT_TRUE(none) << error;
    EXPECT_EQ(rgba8(*none), rgba8(base));
    setMask(mask, 65535);
    auto zero_opacity = blend::apply(base, adjusted, mask, 0.0F, error);
    ASSERT_TRUE(zero_opacity) << error;
    EXPECT_EQ(rgba8(*zero_opacity), rgba8(base));
    auto full = blend::apply(base, adjusted, mask, 1.0F, error);
    ASSERT_TRUE(full) << error;
    const auto full_pixel = rgba8(*full);
    EXPECT_NEAR(full_pixel[0], 204.0F / 255.0F, 0.004F);
    EXPECT_NEAR(full_pixel[1], 51.0F / 255.0F, 0.004F);
    EXPECT_NEAR(full_pixel[2], 26.0F / 255.0F, 0.004F);
    EXPECT_FLOAT_EQ(full_pixel[3], 91.0F / 255.0F) << "alpha comes from the base, not adjusted output";
}

TEST(DevelopMaskBlend, FractionalCoverageAndOpacityMultiply) {
    TileStore base(PixelFormat::RGBA8, 1, 1), adjusted(PixelFormat::RGBA8, 1, 1), mask(PixelFormat::A16, 1, 1);
    setRgba8(base, 0, 0, 0, 127); setRgba8(adjusted, 255, 128, 64, 255);
    setMask(mask, 32768);
    std::string error;
    const auto result = blend::apply(base, adjusted, mask, 0.5F, error);
    ASSERT_TRUE(result) << error;
    const auto p = rgba8(*result);
    EXPECT_NEAR(p[0], 0.25F, 0.005F);
    EXPECT_NEAR(p[1], 0.125F, 0.005F);
    EXPECT_NEAR(p[2], 0.0625F, 0.005F);
    EXPECT_FLOAT_EQ(p[3], 127.0F / 255.0F);
}

TEST(DevelopMaskBlend, MaskOnSparseTransparentSourceDoesNotAllocateOutputTile) {
    TileStore base(PixelFormat::RGBA8, 1, 1), adjusted(PixelFormat::RGBA8, 1, 1), mask(PixelFormat::A16, 1, 1);
    setRgba8(adjusted, 255, 64, 32, 255);
    setMask(mask, 65535);
    std::string error;

    const auto result = blend::apply(base, adjusted, mask, 1.0F, error);

    ASSERT_TRUE(result) << error;
    EXPECT_TRUE(result->tileCoordinates().empty());
    EXPECT_TRUE(base.tileCoordinates().empty()) << "the immutable sparse input remains unchanged";
}

TEST(DevelopMaskBlend, RejectsMismatchesAndBadOpacityWithoutMutatingInputs) {
    TileStore base(PixelFormat::RGBA8, 1, 1), adjusted(PixelFormat::RGBA8, 1, 1), mask(PixelFormat::A16, 1, 1);
    setRgba8(base, 10, 20, 30, 40); setRgba8(adjusted, 200, 150, 100, 90); setMask(mask, 65535);
    const auto before = rgba8(base);
    std::string error;
    EXPECT_FALSE(blend::apply(base, adjusted, TileStore(PixelFormat::A16, 2, 1), 1.0F, error));
    EXPECT_FALSE(blend::apply(base, adjusted, mask, std::numeric_limits<float>::quiet_NaN(), error));
    EXPECT_FALSE(blend::apply(base, adjusted, mask, 1.01F, error));
    EXPECT_EQ(rgba8(base), before);
    auto output = blend::apply(base, adjusted, mask, 1.0F, error);
    ASSERT_TRUE(output) << error;
    TileBuffer changed(output->tile_byte_size(), 0);
    changed[0] = 0; changed[1] = 0; changed[2] = 0; changed[3] = 0;
    ASSERT_TRUE(output->writeTile({0, 0}, changed));
    EXPECT_EQ(rgba8(base), before) << "COW output changes cannot mutate the base input";
}

TEST(DevelopMaskBlend, PreservesFloatAlphaBitwiseAndRejectsNonFiniteSelectedPixels) {
    TileStore base(PixelFormat::RGBA32F, 1, 1), adjusted(PixelFormat::RGBA32F, 1, 1), mask(PixelFormat::A16, 1, 1);
    TileBuffer a(base.tile_byte_size(), 0), b(adjusted.tile_byte_size(), 0);
    const float original[4] = {0.1F, 0.2F, 0.3F, 1.25F};
    const float changed[4] = {0.9F, 0.8F, 0.7F, 0.5F};
    std::memcpy(a.data(), original, sizeof(original)); std::memcpy(b.data(), changed, sizeof(changed));
    ASSERT_TRUE(base.writeTile({0, 0}, a)); ASSERT_TRUE(adjusted.writeTile({0, 0}, b)); setMask(mask, 65535);
    std::string error;
    auto output = blend::apply(base, adjusted, mask, 1.0F, error);
    ASSERT_TRUE(output) << error;
    const auto out = output->readTile({0, 0});
    float output_alpha = 0;
    std::memcpy(&output_alpha, out->data() + 3U * sizeof(float), sizeof(float));
    EXPECT_FLOAT_EQ(output_alpha, original[3]);
    std::fill(b.begin(), b.end(), 0);
    const float malformed[4] = {NAN, 0, 0, 1};
    std::memcpy(b.data(), malformed, sizeof(malformed));
    ASSERT_TRUE(adjusted.writeTile({0, 0}, b));
    EXPECT_FALSE(blend::apply(base, adjusted, mask, 1.0F, error));
    EXPECT_FALSE(error.empty());
}

}  // namespace
