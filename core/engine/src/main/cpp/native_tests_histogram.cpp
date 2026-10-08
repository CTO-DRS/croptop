// Tests for histogram and statistics (CPU reference).
#include "histogram.h"
#include "selection_mask.h"
#include "tile_store.h"

#include <gtest/gtest.h>

#include <array>
#include <cstring>
#include <functional>
#include <string>

namespace {

namespace cl = croptop::color;
using croptop::PixelFormat;
using croptop::TileStore;
using Rgba = std::array<std::uint8_t, 4>;

TileStore paint(std::uint32_t w, std::uint32_t h, const std::function<Rgba(std::uint32_t, std::uint32_t)>& f) {
    TileStore s(PixelFormat::RGBA8, w, h);
    for (std::uint32_t ty = 0; ty * 256 < h; ++ty)
        for (std::uint32_t tx = 0; tx * 256 < w; ++tx) {
            croptop::TileBuffer t(s.tile_byte_size(), 0);
            for (std::uint32_t y = 0; y < 256; ++y)
                for (std::uint32_t x = 0; x < 256; ++x) {
                    const auto gx = tx * 256 + x, gy = ty * 256 + y;
                    if (gx >= w || gy >= h) continue;
                    const auto c = f(gx, gy);
                    std::memcpy(t.data() + (static_cast<std::size_t>(y) * 256 + x) * 4, c.data(), 4);
                }
            EXPECT_TRUE(s.writeTile({static_cast<std::int32_t>(tx), static_cast<std::int32_t>(ty)}, t));
        }
    return s;
}

TEST(Histogram, CountsEveryOpaquePixelOnce) {
    const auto image = paint(300, 200, [](std::uint32_t x, std::uint32_t) { return Rgba{static_cast<std::uint8_t>(x % 256), 10, 200, 255}; });
    std::string error;
    const auto h = cl::computeHistogram(image, nullptr, error);
    ASSERT_TRUE(h) << error;
    EXPECT_DOUBLE_EQ(h->total, 300.0 * 200.0);
    EXPECT_DOUBLE_EQ(h->bins[1][10], 300.0 * 200.0);
    EXPECT_DOUBLE_EQ(h->bins[0][5], 2.0 * 200.0) << "x = 5 and x = 261";
    EXPECT_DOUBLE_EQ(h->bins[0][100], 200.0);
    const auto s = cl::statistics(*h, cl::HistogramChannel::Blue);
    EXPECT_DOUBLE_EQ(s.mean, 200.0);
    EXPECT_DOUBLE_EQ(s.stddev, 0.0);
    EXPECT_DOUBLE_EQ(s.median, 200.0);
}

TEST(Histogram, LuminosityCompositeAndPercentiles) {
    const auto image = paint(256, 256, [](std::uint32_t x, std::uint32_t) { return Rgba{static_cast<std::uint8_t>(x), static_cast<std::uint8_t>(x), static_cast<std::uint8_t>(x), 255}; });
    std::string error;
    const auto h = cl::computeHistogram(image, nullptr, error);
    ASSERT_TRUE(h) << error;
    const auto l = cl::statistics(*h, cl::HistogramChannel::Luminosity);
    EXPECT_NEAR(l.mean, 127.5, 0.6);
    EXPECT_NEAR(l.median, 127, 1);
    EXPECT_NEAR(cl::percentile(*h, cl::HistogramChannel::Luminosity, 0.005), 1, 1);
    EXPECT_NEAR(cl::percentile(*h, cl::HistogramChannel::Luminosity, 0.995), 254, 1);
    const auto rgb = h->channel(cl::HistogramChannel::Rgb);
    EXPECT_DOUBLE_EQ(rgb[42], 3.0 * 256.0);
    EXPECT_DOUBLE_EQ(cl::statistics(*h, cl::HistogramChannel::Rgb).pixels, 256.0 * 256.0);
}

TEST(Histogram, TransparencyAndMaskWeight) {
    const auto image = paint(256, 256, [](std::uint32_t x, std::uint32_t) {
        return x < 128 ? Rgba{50, 50, 50, 0} : Rgba{200, 200, 200, 255};
    });
    std::string error;
    auto h = cl::computeHistogram(image, nullptr, error);
    ASSERT_TRUE(h) << error;
    EXPECT_DOUBLE_EQ(h->bins[0][50], 0.0) << "transparent pixels do not count";
    EXPECT_DOUBLE_EQ(h->total, 128.0 * 256.0);
    const auto mask = croptop::selection::rasterizeRect(256, 256, {128, 0, 192, 256}, false);
    h = cl::computeHistogram(image, &mask, error);
    ASSERT_TRUE(h) << error;
    EXPECT_DOUBLE_EQ(h->total, 64.0 * 256.0);
}

TEST(Histogram, MipLevelsApproximateAndHighBitDepthBins) {
    const auto image = paint(1024, 1024, [](std::uint32_t x, std::uint32_t y) { return Rgba{static_cast<std::uint8_t>((x / 4) % 256), static_cast<std::uint8_t>(y % 256), 0, 255}; });
    std::string error;
    const auto full = cl::computeHistogram(image, nullptr, error);
    const auto quarter = cl::computeHistogram(image, nullptr, error, 2);
    ASSERT_TRUE(full && quarter) << error;
    EXPECT_NEAR(quarter->total, full->total / 16.0, 1.0);
    EXPECT_NEAR(cl::statistics(*quarter, cl::HistogramChannel::Red).mean, cl::statistics(*full, cl::HistogramChannel::Red).mean, 1.0);
    TileStore wide(PixelFormat::RGBA16, 16, 16);
    croptop::TileBuffer t(wide.tile_byte_size(), 0);
    for (std::size_t i = 0; i < 256U * 256U; ++i) {
        const std::array<std::uint16_t, 4> px{32768, 0, 65535, 65535};
        std::memcpy(t.data() + i * 8, px.data(), 8);
    }
    ASSERT_TRUE(wide.writeTile({0, 0}, t));
    const auto h16 = cl::computeHistogram(wide, nullptr, error);
    ASSERT_TRUE(h16) << error;
    EXPECT_DOUBLE_EQ(h16->bins[0][128], 256.0);
    EXPECT_DOUBLE_EQ(h16->bins[2][255], 256.0);
    TileStore alpha(PixelFormat::A16, 4, 4);
    EXPECT_FALSE(cl::computeHistogram(alpha, nullptr, error));
}

}  // namespace
