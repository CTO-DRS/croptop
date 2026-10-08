// . GPU histogram parity against the CPU reference on hardware or lavapipe.
#include "histogram_gpu.h"
#include "pixel_plane.h"
#include "selection_mask.h"

#include <gtest/gtest.h>

#include <array>
#include <cstdint>
#include <cstring>
#include <memory>
#include <string>

namespace {

using croptop::PixelFormat;
using croptop::TileStore;
namespace color = croptop::color;

std::unique_ptr<croptop::gpu::Compute> compute(std::string& error) {
    croptop::gpu::CreateOptions options;
    options.allow_software = true;
    return croptop::gpu::Compute::create(options, error);
}

TileStore sampleImage() {
    TileStore image(PixelFormat::RGBA8, 300, 270);
    for (std::uint32_t tile_y = 0; tile_y < 2; ++tile_y) {
        for (std::uint32_t tile_x = 0; tile_x < 2; ++tile_x) {
            croptop::TileBuffer tile(image.tile_byte_size(), 0);
            for (std::uint32_t y = 0; y < croptop::kTileEdge; ++y) {
                for (std::uint32_t x = 0; x < croptop::kTileEdge; ++x) {
                    const auto px = tile_x * croptop::kTileEdge + x;
                    const auto py = tile_y * croptop::kTileEdge + y;
                    if (px >= image.width() || py >= image.height()) continue;
                    const std::array<std::uint8_t, 4> rgba{
                        static_cast<std::uint8_t>((px * 7U + py) % 256U),
                        static_cast<std::uint8_t>((px + py * 3U) % 256U),
                        static_cast<std::uint8_t>((px * 11U + py * 13U) % 256U),
                        static_cast<std::uint8_t>((px + py) % 256U),
                    };
                    const auto offset = (static_cast<std::size_t>(y) * croptop::kTileEdge + x) * rgba.size();
                    std::memcpy(tile.data() + offset, rgba.data(), rgba.size());
                }
            }
            EXPECT_TRUE(image.writeTile({static_cast<std::int32_t>(tile_x), static_cast<std::int32_t>(tile_y)}, tile));
        }
    }
    return image;
}

void expectClose(const color::Histogram& expected, const color::Histogram& actual, const char* stage) {
    SCOPED_TRACE(stage);
    EXPECT_NEAR(expected.total, actual.total, 1.0);
    for (std::size_t channel = 0; channel < expected.bins.size(); ++channel) {
        for (std::size_t bin = 0; bin < expected.bins[channel].size(); ++bin) {
            EXPECT_NEAR(expected.bins[channel][bin], actual.bins[channel][bin], 1.0)
                << "channel " << channel << ", bin " << bin;
        }
    }
}

TEST(HistogramGpu, MatchesCpuForEdgeTilesMasksAndMipLevels) {
    std::string error;
    const auto gpu = compute(error);
    if (!gpu) GTEST_SKIP() << "no Vulkan compute device: " << error;
    const auto image = sampleImage();
    auto mask = croptop::selection::rasterizeRect(image.width(), image.height(), {23, 17, 281, 249}, true);
    const auto cpu = color::computeHistogram(image, &mask, error);
    ASSERT_TRUE(cpu) << error;
    const auto accelerated = color::computeHistogramGpu(*gpu, image, &mask, error);
    ASSERT_TRUE(accelerated) << error << " on " << gpu->deviceName();
    expectClose(*cpu, *accelerated, "base level");

    const auto cpu_mip = color::computeHistogram(image, &mask, error, 2);
    ASSERT_TRUE(cpu_mip) << error;
    const auto gpu_mip = color::computeHistogramGpu(*gpu, image, &mask, error, 2);
    ASSERT_TRUE(gpu_mip) << error << " on " << gpu->deviceName();
    expectClose(*cpu_mip, *gpu_mip, "mip level 2");
}

TEST(HistogramGpu, PreservesHighBitDepthAndFullTileWeight) {
    std::string error;
    const auto gpu = compute(error);
    if (!gpu) GTEST_SKIP() << "no Vulkan compute device: " << error;

    // A fully opaque 256x256 tile drives one bin to 65,536 * 65,535 fixed-point
    // units, close to UINT32_MAX. This checks the kernel's per-tile accumulator bound.
    TileStore image(PixelFormat::RGBA16, croptop::kTileEdge, croptop::kTileEdge);
    croptop::TileBuffer tile(image.tile_byte_size(), 0);
    const std::array<float, 4> white{1.0F, 1.0F, 1.0F, 1.0F};
    for (std::size_t offset = 0; offset < tile.size(); offset += image.bytes_per_pixel()) {
        croptop::paint::detail::writePixel(PixelFormat::RGBA16, tile.data() + offset, white.data());
    }
    ASSERT_TRUE(image.writeTile({0, 0}, tile));

    const auto cpu = color::computeHistogram(image, nullptr, error);
    ASSERT_TRUE(cpu) << error;
    const auto accelerated = color::computeHistogramGpu(*gpu, image, nullptr, error);
    ASSERT_TRUE(accelerated) << error << " on " << gpu->deviceName();
    expectClose(*cpu, *accelerated, "RGBA16 base level");
    EXPECT_NEAR(65536.0, accelerated->total, 1.0);
    EXPECT_NEAR(65536.0, accelerated->bins[0][255], 1.0);
}

TEST(HistogramGpu, MatchesFloatPixelsAndSparseTiles) {
    std::string error;
    const auto gpu = compute(error);
    if (!gpu) GTEST_SKIP() << "no Vulkan compute device: " << error;

    TileStore image(PixelFormat::RGBA32F, 300, 270);
    croptop::TileBuffer tile(image.tile_byte_size(), 0);
    const std::array<float, 4> pixel{0.125F, 0.5F, 1.4F, 0.375F};
    for (std::size_t offset = 0; offset < tile.size(); offset += image.bytes_per_pixel()) {
        croptop::paint::detail::writePixel(PixelFormat::RGBA32F, tile.data() + offset, pixel.data());
    }
    ASSERT_TRUE(image.writeTile({1, 0}, tile));  // Only the in-bounds 44x256 region contributes.

    const auto cpu = color::computeHistogram(image, nullptr, error);
    ASSERT_TRUE(cpu) << error;
    const auto accelerated = color::computeHistogramGpu(*gpu, image, nullptr, error);
    ASSERT_TRUE(accelerated) << error << " on " << gpu->deviceName();
    expectClose(*cpu, *accelerated, "RGBA32F base level");
    EXPECT_NEAR(44.0 * 256.0 * pixel[3], accelerated->total, 1.0);
}

}  // namespace
