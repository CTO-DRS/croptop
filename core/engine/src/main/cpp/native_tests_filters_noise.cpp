// Tests for the noise and pixelate CPU filters.
#include "filters_noise.h"
#include "native_tests_stress_gate.h"
#include "selection_mask.h"
#include "tile_store.h"

#include <gtest/gtest.h>

#include <array>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <functional>
#include <set>
#include <string>

namespace {

namespace fl = croptop::filters;
using croptop::PixelFormat;
using croptop::TileStore;
using Rgba = std::array<std::uint8_t, 4>;

TileStore paint(std::uint32_t w, std::uint32_t h, const std::function<Rgba(std::uint32_t, std::uint32_t)>& f) {
    TileStore s(PixelFormat::RGBA8, w, h);
    for (std::uint32_t ty = 0; ty * 256 < h; ++ty) {
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
    }
    return s;
}

Rgba at(const TileStore& s, std::uint32_t x, std::uint32_t y) {
    Rgba c{};
    const auto t = s.readTile({static_cast<std::int32_t>(x / 256), static_cast<std::int32_t>(y / 256)});
    if (t) std::memcpy(c.data(), t->data() + (static_cast<std::size_t>(y % 256) * 256 + x % 256) * 4, 4);
    return c;
}

TileStore flat(Rgba c, std::uint32_t w = 300, std::uint32_t h = 300) {
    return paint(w, h, [c](std::uint32_t, std::uint32_t) { return c; });
}

struct Stats {
    double mean = 0, stddev = 0;
};
Stats stats(const TileStore& s, int channel, std::uint32_t x0, std::uint32_t y0, std::uint32_t x1, std::uint32_t y1) {
    double sum = 0, sq = 0;
    int n = 0;
    for (std::uint32_t y = y0; y < y1; ++y)
        for (std::uint32_t x = x0; x < x1; ++x) {
            const double v = at(s, x, y)[static_cast<std::size_t>(channel)];
            sum += v;
            sq += v * v;
            ++n;
        }
    const double m = sum / n;
    return {m, std::sqrt(std::max(0.0, sq / n - m * m))};
}

TEST(FiltersNoise, AddNoiseHasTheRequestedSpreadAndIsDeterministic) {
    auto uniform = flat({128, 128, 128, 255});
    std::string error;
    ASSERT_TRUE(fl::addNoise(uniform, 1.0F, fl::NoiseDistribution::Uniform, false, 7, nullptr, error)) << error;
    const auto u = stats(uniform, 0, 0, 0, 300, 300);
    // U(−a, a) with a = 0.25 → σ = a/√3 ≈ 36.8 levels.
    EXPECT_NEAR(u.mean, 128, 1.5);
    EXPECT_NEAR(u.stddev, 0.25 * 255 / std::sqrt(3.0), 2.0);
    auto again = flat({128, 128, 128, 255});
    ASSERT_TRUE(fl::addNoise(again, 1.0F, fl::NoiseDistribution::Uniform, false, 7, nullptr, error));
    EXPECT_EQ(at(again, 123, 45), at(uniform, 123, 45)) << "same seed, same noise";
    auto mono = flat({128, 128, 128, 255});
    ASSERT_TRUE(fl::addNoise(mono, 1.0F, fl::NoiseDistribution::Gaussian, true, 7, nullptr, error));
    const auto p = at(mono, 10, 10);
    EXPECT_EQ(p[0], p[1]);
    EXPECT_EQ(p[1], p[2]);
    EXPECT_NEAR(stats(mono, 0, 0, 0, 300, 300).stddev, 0.125 * 255, 2.5) << "Gaussian σ = a/2";
}

TEST(FiltersNoise, FilmGrainTouchesLuminanceMostInMidtones) {
    auto mid = flat({128, 128, 128, 255});
    auto dark = flat({8, 8, 8, 255});
    std::string error;
    ASSERT_TRUE(fl::filmGrain(mid, 1.0F, 2.0F, 3, nullptr, error)) << error;
    ASSERT_TRUE(fl::filmGrain(dark, 1.0F, 2.0F, 3, nullptr, error));
    EXPECT_GT(stats(mid, 0, 0, 0, 300, 300).stddev, 2.0 * stats(dark, 0, 0, 0, 300, 300).stddev);
    const auto p = at(mid, 50, 50);
    EXPECT_EQ(p[0], p[2]) << "grain is neutral";
}

TEST(FiltersNoise, MedianRemovesImpulsesAndKeepsEdges) {
    auto image = paint(300, 300, [](std::uint32_t x, std::uint32_t y) {
        if ((x * 7 + y * 13) % 37 == 0) return Rgba{255, 255, 255, 255};  // salt
        return x < 150 ? Rgba{40, 40, 40, 255} : Rgba{200, 200, 200, 255};
    });
    std::string error;
    ASSERT_TRUE(fl::median(image, 2, nullptr, error)) << error;
    EXPECT_EQ(stats(image, 0, 10, 10, 140, 290).stddev, 0.0) << "impulses gone";
    EXPECT_EQ(at(image, 149, 100)[0], 40) << "edge kept";
    EXPECT_EQ(at(image, 150, 100)[0], 200);
}

TEST(FiltersNoise, MedianOfARampIsTheRamp) {
    TileStore image(PixelFormat::RGBA16, 256, 64);
    croptop::TileBuffer t(image.tile_byte_size(), 0);
    for (std::size_t i = 0; i < 256U * 256U; ++i) {
        const std::array<std::uint16_t, 4> px{static_cast<std::uint16_t>((i % 256) * 200), 0, 0, 65535};
        std::memcpy(t.data() + i * 8, px.data(), 8);
    }
    ASSERT_TRUE(image.writeTile({0, 0}, t));
    std::string error;
    ASSERT_TRUE(fl::median(image, 3, nullptr, error)) << error;
    std::uint16_t v = 0;
    std::memcpy(&v, image.readTile({0, 0})->data() + (30U * 256U + 100U) * 8, 2);
    EXPECT_NEAR(v, 100 * 200, 150) << "16-bit values are refined inside the 256-level bins";
}

TEST(FiltersNoise, DustAndScratchesOnlyReplacesOutliers) {
    auto image = paint(200, 200, [](std::uint32_t x, std::uint32_t y) {
        if (x == 100 && y == 100) return Rgba{255, 255, 255, 255};
        return Rgba{static_cast<std::uint8_t>(100 + (x + y) % 3), 100, 100, 255};
    });
    const auto original = image;
    std::string error;
    ASSERT_TRUE(fl::dustAndScratches(image, 2, 20.0F, nullptr, error)) << error;
    EXPECT_LT(at(image, 100, 100)[0], 110) << "the speck is removed";
    EXPECT_EQ(at(image, 50, 51), at(original, 50, 51)) << "fine texture under the threshold is kept";
}

TEST(FiltersNoise, ReduceNoiseSmoothsFlatAreasButKeepsEdges) {
    auto image = paint(256, 128, [](std::uint32_t x, std::uint32_t y) {
        const int base = x < 128 ? 60 : 200;
        const int n = static_cast<int>((x * 2654435761U + y * 40503U) % 11) - 5;
        const auto v = static_cast<std::uint8_t>(base + n);
        return Rgba{v, v, v, 255};
    });
    const double before = stats(image, 0, 10, 10, 110, 118).stddev;
    std::string error;
    ASSERT_TRUE(fl::reduceNoise(image, 5.0F, 0.0F, nullptr, error)) << error;
    EXPECT_LT(stats(image, 0, 10, 10, 110, 118).stddev, before * 0.5);
    EXPECT_NEAR(at(image, 127, 64)[0], 60, 8);
    EXPECT_NEAR(at(image, 128, 64)[0], 200, 8);
}

TEST(FiltersNoise, MosaicAveragesAlignedCells) {
    auto image = paint(300, 300, [](std::uint32_t x, std::uint32_t) { return Rgba{static_cast<std::uint8_t>(x % 10 < 5 ? 0 : 100), 0, 0, 255}; });
    std::string error;
    ASSERT_TRUE(fl::mosaic(image, 10, nullptr, error)) << error;
    EXPECT_EQ(at(image, 3, 3)[0], 50);
    EXPECT_EQ(at(image, 257, 263)[0], 50) << "cells straddling tiles agree";
    EXPECT_EQ(at(image, 250, 263), at(image, 259, 263));
}

TEST(FiltersNoise, CrystallizeMakesFlatCells) {
    auto image = paint(300, 300, [](std::uint32_t x, std::uint32_t y) {
        return Rgba{static_cast<std::uint8_t>(x), static_cast<std::uint8_t>(y), 0, 255};
    });
    std::string error;
    ASSERT_TRUE(fl::crystallize(image, 20, 1, nullptr, error)) << error;
    std::set<std::uint32_t> colours;
    for (std::uint32_t y = 0; y < 300; ++y)
        for (std::uint32_t x = 0; x < 300; ++x) {
            const auto p = at(image, x, y);
            colours.insert(static_cast<std::uint32_t>(p[0]) << 8 | p[1]);
        }
    EXPECT_LT(colours.size(), 300U) << "about one colour per 20 px cell (225 cells)";
    EXPECT_GT(colours.size(), 150U);
}

TEST(FiltersNoise, HalftoneDotsFollowTheValue) {
    auto dark = flat({30, 30, 30, 255});
    auto light = flat({225, 225, 225, 255});
    std::string error;
    ASSERT_TRUE(fl::colorHalftone(dark, 8, {108, 162, 90}, nullptr, error)) << error;
    ASSERT_TRUE(fl::colorHalftone(light, 8, {108, 162, 90}, nullptr, error));
    // Coverage (mean) tracks the value; pixels are binary apart from anti-aliased dot edges.
    EXPECT_NEAR(stats(dark, 0, 0, 0, 300, 300).mean, 30, 12);
    EXPECT_NEAR(stats(light, 0, 0, 0, 300, 300).mean, 225, 20);
    EXPECT_GT(stats(dark, 0, 0, 0, 300, 300).stddev, 60) << "dots, not a flat tone";
}

TEST(FiltersNoise, MaskAndValidation) {
    auto image = flat({128, 128, 128, 255});
    const auto original = image;
    const auto mask = croptop::selection::rasterizeRect(300, 300, {0, 0, 100, 100}, false);
    std::string error;
    ASSERT_TRUE(fl::addNoise(image, 2.0F, fl::NoiseDistribution::Uniform, false, 1, &mask, error));
    EXPECT_EQ(at(image, 200, 200), at(original, 200, 200));
    EXPECT_FALSE(fl::addNoise(image, 5.0F, fl::NoiseDistribution::Uniform, false, 1, nullptr, error));
    EXPECT_FALSE(fl::median(image, 0, nullptr, error));
    EXPECT_FALSE(fl::mosaic(image, 1, nullptr, error));
    EXPECT_FALSE(fl::crystallize(image, 2, 0, nullptr, error));
    EXPECT_FALSE(fl::colorHalftone(image, 2, {0, 0, 0}, nullptr, error));
    EXPECT_FALSE(fl::reduceNoise(image, 11.0F, 0.0F, nullptr, error));
}

TEST(FiltersNoise, TimingOn24Megapixels) {
    CROPTOP_REQUIRE_STRESS();
    TileStore image(PixelFormat::RGBA8, 6000, 4000);
    croptop::TileBuffer t(image.tile_byte_size(), 0);
    for (std::size_t i = 0; i < t.size(); ++i) t[i] = static_cast<std::uint8_t>(i * 13U);
    for (std::int32_t ty = 0; ty * 256 < 4000; ++ty)
        for (std::int32_t tx = 0; tx * 256 < 6000; ++tx) ASSERT_TRUE(image.writeTile({tx, ty}, t));
    const auto time = [&](const char* name, const std::function<bool(TileStore&, std::string&)>& f) {
        TileStore copy(image);
        std::string error;
        const auto start = std::chrono::steady_clock::now();
        ASSERT_TRUE(f(copy, error)) << error;
        std::printf("[filters] %s on 24 MP: %.0f ms\n", name, std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - start).count());
    };
    time("add noise", [](TileStore& c, std::string& e) { return fl::addNoise(c, 1.0F, fl::NoiseDistribution::Gaussian, false, 1, nullptr, e); });
    time("median r3", [](TileStore& c, std::string& e) { return fl::median(c, 3, nullptr, e); });
    time("reduce noise", [](TileStore& c, std::string& e) { return fl::reduceNoise(c, 5.0F, 0.2F, nullptr, e); });
    time("mosaic 20", [](TileStore& c, std::string& e) { return fl::mosaic(c, 20, nullptr, e); });
    time("crystallize 20", [](TileStore& c, std::string& e) { return fl::crystallize(c, 20, 1, nullptr, e); });
    time("halftone 8", [](TileStore& c, std::string& e) { return fl::colorHalftone(c, 8, {108, 162, 90}, nullptr, e); });
}

}  // namespace
