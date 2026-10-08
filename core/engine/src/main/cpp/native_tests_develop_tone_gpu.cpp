// GPU develop tone against its CPU reference; runs on any Vulkan device including Mesa lavapipe.
#include "develop_tone_gpu.h"

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

constexpr std::uint32_t kW = 400, kH = 260;

// A soft scene: a bright ramp with a dark patch and mild noise, in linear light, some values above 1.
TileStore scene(PixelFormat format) {
    TileStore store(format, kW, kH);
    std::mt19937 rng(11);
    std::uniform_real_distribution<float> noise(-0.01F, 0.01F);
    const auto bpp = store.bytes_per_pixel();
    for (std::uint32_t ty = 0; ty * croptop::kTileEdge < kH; ++ty) {
        for (std::uint32_t tx = 0; tx * croptop::kTileEdge < kW; ++tx) {
            croptop::TileBuffer tile(store.tile_byte_size(), 0);
            for (std::uint32_t y = 0; y < croptop::kTileEdge; ++y) {
                for (std::uint32_t x = 0; x < croptop::kTileEdge; ++x) {
                    const auto X = tx * croptop::kTileEdge + x, Y = ty * croptop::kTileEdge + y;
                    if (X >= kW || Y >= kH) continue;
                    const float base = 0.02F + 0.9F * static_cast<float>(X) / kW * (1.0F + static_cast<float>(Y) / kH);
                    const float dark = (X > 120 && X < 200 && Y > 80 && Y < 160) ? 0.15F : 1.0F;
                    const float v[4] = {base * dark + noise(rng), base * dark * 0.8F + noise(rng), base * dark * 0.6F + noise(rng), 1.0F};
                    auto* p = tile.data() + (static_cast<std::size_t>(y) * croptop::kTileEdge + x) * bpp;
                    for (int c = 0; c < 4; ++c) {
                        const float f = std::max(0.0F, v[c]);
                        if (format == PixelFormat::RGBA16) {
                            const auto q = static_cast<std::uint16_t>(std::min(1.0F, f) * 65535.0F);
                            std::memcpy(p + c * 2, &q, 2);
                        } else {
                            std::memcpy(p + c * 4, &f, 4);
                        }
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

double worstRelative(const TileStore& a, const TileStore& b) {
    double worst = 0;
    for (std::uint32_t y = 0; y < kH; y += 2)
        for (std::uint32_t x = 0; x < kW; x += 2)
            for (int c = 0; c < 3; ++c) {
                const double va = channel(a, x, y, c), vb = channel(b, x, y, c);
                worst = std::max(worst, std::abs(va - vb) / std::max(1.0, std::abs(va)));
            }
    return worst;
}

std::unique_ptr<croptop::gpu::Compute> device(std::string& note) {
    croptop::gpu::CreateOptions options;
    options.allow_software = true;
    return croptop::gpu::Compute::create(options, note);
}

dev::ToneSettings full() {
    dev::ToneSettings t;
    t.exposure = 0.4F;
    t.contrast = 25;
    t.highlights = -40;
    t.shadows = 35;
    t.whites = 15;
    t.blacks = -10;
    t.clarity = 30;
    t.vibrance = 20;
    t.saturation = -10;
    t.parametric.lights = 20;
    t.parametric.shadows = -15;
    t.point_curve.points = {{0.0F, 0.0F}, {0.3F, 0.25F}, {0.7F, 0.78F}, {1.0F, 1.0F}};
    return t;
}

}  // namespace

TEST(DevelopToneGpu, MatchesTheCpuReferenceWithLocalToneClarityAndCurves) {
    std::string note;
    const auto gpu = device(note);
    if (!gpu) GTEST_SKIP() << "no Vulkan device: " << note;
    for (const auto format : {PixelFormat::RGBA32F, PixelFormat::RGBA16}) {
        const auto layer = scene(format);
        std::string error;
        const auto cpu = dev::applyTone(layer, full(), error);
        ASSERT_TRUE(cpu.has_value()) << error;
        const auto out = dev::applyToneGpu(*gpu, layer, full(), error);
        ASSERT_TRUE(out.has_value()) << error << " on " << gpu->deviceName();
        // The point curve is sampled from a 1025-entry table on the GPU; measured on lavapipe the worst error is about 2e-5 (a wrong exposure gives 0.4-0.9).
        EXPECT_LT(worstRelative(*cpu, *out), 0.001) << gpu->deviceName();
    }
}

TEST(DevelopToneGpu, DehazeBothWaysMatchesTheCpu) {
    std::string note;
    const auto gpu = device(note);
    if (!gpu) GTEST_SKIP() << "no Vulkan device: " << note;
    const auto layer = scene(PixelFormat::RGBA32F);
    for (const float dehaze : {50.0F, -60.0F}) {
        dev::ToneSettings t;
        t.dehaze = dehaze;
        t.exposure = -0.2F;
        std::string error;
        const auto cpu = dev::applyTone(layer, t, error);
        const auto out = dev::applyToneGpu(*gpu, layer, t, error);
        ASSERT_TRUE(cpu && out) << error;
        EXPECT_LT(worstRelative(*cpu, *out), 0.001) << "dehaze " << dehaze;
    }
}

TEST(DevelopToneGpu, TextureIdentityAndBadSettingsAreHandled) {
    std::string note;
    const auto gpu = device(note);
    if (!gpu) GTEST_SKIP() << "no Vulkan device: " << note;
    const auto layer = scene(PixelFormat::RGBA32F);
    std::string error;
    dev::ToneSettings texture;
    texture.texture = 30;
    EXPECT_FALSE(dev::applyToneGpu(*gpu, layer, texture, error).has_value());
    EXPECT_NE(error.find("texture"), std::string::npos);
    const auto same = dev::applyToneGpu(*gpu, layer, {}, error);
    ASSERT_TRUE(same.has_value());
    EXPECT_EQ(worstRelative(layer, *same), 0.0);
    dev::ToneSettings bad;
    bad.exposure = 9;
    EXPECT_FALSE(dev::applyToneGpu(*gpu, layer, bad, error).has_value());
}
