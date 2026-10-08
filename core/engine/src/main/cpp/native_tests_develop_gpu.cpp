// GPU-with-fallback develop stages.
#include "develop_gpu.h"

#include <gtest/gtest.h>

#include <cmath>
#include <cstring>
#include <memory>
#include <string>

namespace {

using croptop::PixelFormat;
using croptop::TileStore;
namespace dev = croptop::develop;

TileStore layer() {
    TileStore store(PixelFormat::RGBA32F, 300, 200);
    for (std::uint32_t ty = 0; ty < 1; ++ty)
        for (std::uint32_t tx = 0; tx < 2; ++tx) {
            croptop::TileBuffer tile(store.tile_byte_size(), 0);
            for (std::uint32_t y = 0; y < croptop::kTileEdge; ++y)
                for (std::uint32_t x = 0; x < croptop::kTileEdge; ++x) {
                    const float X = static_cast<float>(tx * croptop::kTileEdge + x);
                    const float v[4] = {0.05F + X / 400.0F, 0.1F + static_cast<float>(y) / 500.0F, 0.2F, 1.0F};
                    std::memcpy(tile.data() + (static_cast<std::size_t>(y) * croptop::kTileEdge + x) * 16, v, 16);
                }
            EXPECT_TRUE(store.writeTile({static_cast<std::int32_t>(tx), 0}, tile));
        }
    return store;
}

float red(const TileStore& s, std::uint32_t x, std::uint32_t y) {
    const auto tile = s.readTile({static_cast<std::int32_t>(x / croptop::kTileEdge), 0});
    float f = -1.0F;
    if (tile) std::memcpy(&f, tile->data() + (static_cast<std::size_t>(y) * croptop::kTileEdge + x % croptop::kTileEdge) * 16, 4);
    return f;
}

}  // namespace

TEST(DevelopGpu, GpuAndCpuAgreeAndTheReportSaysWhereStagesRan) {
    croptop::gpu::CreateOptions options;
    options.allow_software = true;
    std::string note;
    const auto gpu = croptop::gpu::Compute::create(options, note);
    if (!gpu) GTEST_SKIP() << "no Vulkan device: " << note;
    dev::ToneSettings tone;
    tone.exposure = 0.3F;
    tone.contrast = 20;
    dev::ColorSettings color;
    color.look = dev::Look::Vivid;
    const auto source = layer();
    std::string error;
    dev::GpuStageReport report;
    const auto with_gpu = dev::applyToneAndColor(gpu.get(), source, tone, color, &report, error);
    ASSERT_TRUE(with_gpu.has_value()) << error;
    EXPECT_TRUE(report.tone_on_gpu);
    EXPECT_TRUE(report.color_on_gpu);
    EXPECT_TRUE(report.fallback_reason.empty()) << report.fallback_reason;
    const auto cpu_only = dev::applyToneAndColor(nullptr, source, tone, color, &report, error);
    ASSERT_TRUE(cpu_only.has_value()) << error;
    EXPECT_FALSE(report.tone_on_gpu);
    for (std::uint32_t x = 0; x < 300; x += 23) EXPECT_NEAR(red(*with_gpu, x, 40), red(*cpu_only, x, 40), 2e-3F);
}

TEST(DevelopGpu, TextureFallsBackToTheCpuWithoutFailing) {
    croptop::gpu::CreateOptions options;
    options.allow_software = true;
    std::string note;
    const auto gpu = croptop::gpu::Compute::create(options, note);
    if (!gpu) GTEST_SKIP() << "no Vulkan device: " << note;
    dev::ToneSettings tone;
    tone.texture = 40;
    dev::ColorSettings color;
    color.mixer.saturation[0] = 30;
    const auto source = layer();
    std::string error;
    dev::GpuStageReport report;
    const auto out = dev::applyToneAndColor(gpu.get(), source, tone, color, &report, error);
    ASSERT_TRUE(out.has_value()) << error;
    EXPECT_FALSE(report.tone_on_gpu);
    EXPECT_TRUE(report.color_on_gpu);
    EXPECT_NE(report.fallback_reason.find("texture"), std::string::npos);
    const auto reference = dev::applyToneAndColor(nullptr, source, tone, color, nullptr, error);
    ASSERT_TRUE(reference.has_value());
    for (std::uint32_t x = 0; x < 300; x += 31) EXPECT_NEAR(red(*out, x, 60), red(*reference, x, 60), 2e-3F);
}

TEST(DevelopGpu, InvalidSettingsStillFailCleanly) {
    dev::ToneSettings tone;
    tone.exposure = 20;
    std::string error;
    EXPECT_FALSE(dev::applyToneAndColor(nullptr, layer(), tone, {}, nullptr, error).has_value());
    EXPECT_FALSE(error.empty());
}

#include "develop_pipeline.h"

TEST(DevelopGpu, PipelineRenderUsesTheDeviceWhenGivenOneAndMatchesTheCpuRun) {
    croptop::gpu::CreateOptions options;
    options.allow_software = true;
    std::string note;
    const auto gpu = croptop::gpu::Compute::create(options, note);
    if (!gpu) GTEST_SKIP() << "no Vulkan device: " << note;
    croptop::develop::pipeline::Settings settings;
    settings.basic.exposure = 0.3F;
    settings.basic.contrast = 15;
    settings.basic.vibrance = 20;
    settings.profile = croptop::develop::pipeline::Profile::Vivid;
    settings.mixer[0].saturation = 25;
    const auto source = layer();
    std::string error;
    croptop::develop::pipeline::Request request;
    request.worker_threads = 1;
    const auto cpu = croptop::develop::pipeline::render(source, settings, request, error);
    ASSERT_TRUE(cpu.has_value()) << error;
    EXPECT_FALSE(cpu->tone_on_gpu);
    request.gpu = gpu.get();
    const auto accelerated = croptop::develop::pipeline::render(source, settings, request, error);
    ASSERT_TRUE(accelerated.has_value()) << error;
    EXPECT_TRUE(accelerated->tone_on_gpu);
    EXPECT_TRUE(accelerated->color_on_gpu);
    for (std::uint32_t x = 0; x < 300; x += 29) EXPECT_NEAR(red(accelerated->pixels, x, 50), red(cpu->pixels, x, 50), 3e-3F);
    // Texture is not on the GPU: that stage falls back and the render still succeeds.
    settings.basic.texture = 30;
    const auto mixed = croptop::develop::pipeline::render(source, settings, request, error);
    ASSERT_TRUE(mixed.has_value()) << error;
    EXPECT_FALSE(mixed->tone_on_gpu);
    EXPECT_TRUE(mixed->color_on_gpu);
}
