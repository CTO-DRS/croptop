// GPU develop colour against its CPU reference. Needs a Vulkan device; software rasterisers count, so this
// runs on CI hosts with lavapipe. Skips when there is no device at all.
#include "develop_color_gpu.h"

#include <gtest/gtest.h>

#include <cmath>
#include <cstring>
#include <memory>
#include <random>
#include <string>

namespace {

using croptop::PixelFormat;
using croptop::TileStore;
namespace dev = croptop::develop;

constexpr std::uint32_t kW = 300, kH = 140;

TileStore randomLayer(PixelFormat format, bool skip_a_tile) {
    TileStore store(format, kW, kH);
    std::mt19937 rng(7);
    std::uniform_real_distribution<float> dist(0.0F, 1.0F);
    const auto bpp = store.bytes_per_pixel();
    for (std::uint32_t ty = 0; ty * croptop::kTileEdge < kH; ++ty) {
        for (std::uint32_t tx = 0; tx * croptop::kTileEdge < kW; ++tx) {
            if (skip_a_tile && tx == 1 && ty == 0) continue;  // sparse layers stay sparse
            croptop::TileBuffer tile(store.tile_byte_size(), 0);
            for (std::size_t p = 0; p < static_cast<std::size_t>(croptop::kTileEdge) * croptop::kTileEdge; ++p) {
                float v[4] = {dist(rng), dist(rng) * 0.8F, dist(rng) * 0.6F, 1.0F};
                for (int c = 0; c < 4; ++c) {
                    if (format == PixelFormat::RGBA16) {
                        const auto q = static_cast<std::uint16_t>(v[c] * 65535.0F);
                        std::memcpy(tile.data() + p * bpp + c * 2, &q, 2);
                    } else {
                        const float f = c < 3 ? v[c] * 2.0F : 1.0F;  // some HDR values
                        std::memcpy(tile.data() + p * bpp + c * 4, &f, 4);
                    }
                }
            }
            EXPECT_TRUE(store.writeTile({static_cast<std::int32_t>(tx), static_cast<std::int32_t>(ty)}, tile));
        }
    }
    return store;
}

float channel(const TileStore& s, std::uint32_t x, std::uint32_t y, int c) {
    const auto tile = s.readTile({static_cast<std::int32_t>(x / croptop::kTileEdge), static_cast<std::int32_t>(y / croptop::kTileEdge)});
    if (!tile) return -1.0F;
    const auto bpp = s.bytes_per_pixel();
    const auto* p = tile->data() + (static_cast<std::size_t>(y % croptop::kTileEdge) * croptop::kTileEdge + x % croptop::kTileEdge) * bpp;
    if (s.format() == PixelFormat::RGBA16) {
        std::uint16_t q;
        std::memcpy(&q, p + c * 2, 2);
        return q / 65535.0F;
    }
    float f;
    std::memcpy(&f, p + c * 4, 4);
    return f;
}

dev::ColorSettings busySettings() {
    dev::ColorSettings s;
    s.calibration.red_hue = 30;
    s.calibration.green_saturation = -25;
    s.calibration.shadow_tint = 40;
    s.look = dev::Look::Vivid;
    s.mixer.hue = {10, -20, 0, 15, 0, -30, 5, 0};
    s.mixer.saturation = {20, 0, -30, 10, 0, 25, 0, -10};
    s.mixer.luminance = {0, 10, -10, 0, 20, 0, 0, -15};
    s.grading.shadows = {220, 40, -10};
    s.grading.highlights = {40, 30, 10};
    s.grading.global = {0, 0, 5};
    s.grading.blending = 60;
    s.grading.balance = 15;
    return s;
}

std::unique_ptr<croptop::gpu::Compute> device(std::string& note) {
    croptop::gpu::CreateOptions options;
    options.allow_software = true;
    return croptop::gpu::Compute::create(options, note);
}

double worstDifference(const TileStore& a, const TileStore& b) {
    double worst = 0;
    for (std::uint32_t y = 0; y < kH; ++y)
        for (std::uint32_t x = 0; x < kW; ++x)
            for (int c = 0; c < 4; ++c) worst = std::max(worst, static_cast<double>(std::abs(channel(a, x, y, c) - channel(b, x, y, c))));
    return worst;
}

}  // namespace

TEST(DevelopColorGpu, MatchesTheCpuReferenceOnFloatAndSixteenBit) {
    std::string note;
    const auto gpu = device(note);
    if (!gpu) GTEST_SKIP() << "no Vulkan device: " << note;
    for (const auto format : {PixelFormat::RGBA32F, PixelFormat::RGBA16}) {
        const auto layer = randomLayer(format, false);
        std::string error;
        const auto cpu = dev::applyColor(layer, busySettings(), error);
        ASSERT_TRUE(cpu.has_value()) << error;
        const auto out = dev::applyColorGpu(*gpu, layer, busySettings(), error);
        ASSERT_TRUE(out.has_value()) << error << " on " << gpu->deviceName();
        EXPECT_LT(worstDifference(*cpu, *out), format == PixelFormat::RGBA16 ? 2.0 / 65535.0 * 40 : 2e-3) << gpu->deviceName();
    }
}

TEST(DevelopColorGpu, MonochromeLookAndSparseTilesStaySparse) {
    std::string note;
    const auto gpu = device(note);
    if (!gpu) GTEST_SKIP() << "no Vulkan device: " << note;
    const auto layer = randomLayer(PixelFormat::RGBA32F, true);
    dev::ColorSettings s;
    s.look = dev::Look::Monochrome;
    std::string error;
    const auto cpu = dev::applyColor(layer, s, error);
    const auto out = dev::applyColorGpu(*gpu, layer, s, error);
    ASSERT_TRUE(cpu && out) << error;
    EXPECT_EQ(out->readTile({1, 0}), nullptr);
    EXPECT_EQ(out->tileCoordinates().size(), layer.tileCoordinates().size());
    for (std::uint32_t x = 0; x < kW; x += 17)
        for (std::uint32_t y = 0; y < kH; y += 13) {
            if (x / 256U == 1U && y / 256U == 0U) continue;
            EXPECT_NEAR(channel(*out, x, y, 0), channel(*cpu, x, y, 0), 2e-3);
            EXPECT_NEAR(channel(*out, x, y, 0), channel(*out, x, y, 2), 2e-3);  // mono: r == b
        }
}

TEST(DevelopColorGpu, IdentityReturnsTheInputAndBadSettingsAreRejected) {
    std::string note;
    const auto gpu = device(note);
    if (!gpu) GTEST_SKIP() << "no Vulkan device: " << note;
    const auto layer = randomLayer(PixelFormat::RGBA32F, false);
    std::string error;
    const auto same = dev::applyColorGpu(*gpu, layer, {}, error);
    ASSERT_TRUE(same.has_value());
    EXPECT_EQ(worstDifference(layer, *same), 0.0);
    dev::ColorSettings bad;
    bad.grading.balance = 500;
    EXPECT_FALSE(dev::applyColorGpu(*gpu, layer, bad, error).has_value());
    TileStore eight(PixelFormat::RGBA8, 16, 16);
    EXPECT_FALSE(dev::applyColorGpu(*gpu, eight, busySettings(), error).has_value());
}

TEST(DevelopColorGpu, HardwareOnlyCreationFailsCleanlyWithoutAHardwareDevice) {
    std::string error;
    const auto gpu = croptop::gpu::Compute::create({}, error);
    if (gpu) {
        EXPECT_FALSE(gpu->isSoftware());
    } else {
        EXPECT_FALSE(error.empty());
    }
}
