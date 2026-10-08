// Tests for develop detail and effects.
#include "develop_detail.h"

#include <gtest/gtest.h>

#include <cmath>
#include <chrono>
#include <cstring>
#include <functional>
#include <limits>
#include <cstdio>
#include <string>
#include <vector>

namespace {

using croptop::PixelFormat;
using croptop::TileStore;
namespace dev = croptop::develop;
using Rgb = std::array<float, 3>;

TileStore paint(std::uint32_t w, std::uint32_t h, const std::function<Rgb(std::uint32_t, std::uint32_t)>& f) {
    TileStore s(PixelFormat::RGBA32F, w, h);
    for (std::uint32_t ty = 0; ty * 256 < h; ++ty)
        for (std::uint32_t tx = 0; tx * 256 < w; ++tx) {
            croptop::TileBuffer t(s.tile_byte_size(), 0);
            for (std::uint32_t y = 0; y < 256; ++y)
                for (std::uint32_t x = 0; x < 256; ++x) {
                    const auto gx = tx * 256 + x, gy = ty * 256 + y;
                    if (gx >= w || gy >= h) continue;
                    const auto c = f(gx, gy);
                    const float px[4] = {c[0], c[1], c[2], 1.0F};
                    std::memcpy(t.data() + (static_cast<std::size_t>(y) * 256 + x) * 16, px, 16);
                }
            EXPECT_TRUE(s.writeTile({static_cast<std::int32_t>(tx), static_cast<std::int32_t>(ty)}, t));
        }
    return s;
}

std::array<float, 4> at(const TileStore& s, std::uint32_t x, std::uint32_t y) {
    std::array<float, 4> out{};
    const auto t = s.readTile({static_cast<std::int32_t>(x / 256), static_cast<std::int32_t>(y / 256)});
    if (t) std::memcpy(out.data(), t->data() + (static_cast<std::size_t>(y % 256) * 256 + x % 256) * 16, 16);
    return out;
}

TileStore run(const TileStore& in, const std::function<void(dev::DetailSettings&)>& set) {
    dev::DetailSettings s;
    set(s);
    std::string error;
    auto out = dev::applyDetail(in, s, error);
    EXPECT_TRUE(out) << error;
    return out ? std::move(*out) : in;
}

float noise(std::uint32_t x, std::uint32_t y, std::uint32_t k) {
    std::uint32_t h = x * 73856093U ^ y * 19349663U ^ k * 83492791U;
    h ^= h >> 13;
    h *= 0x5bd1e995U;
    h ^= h >> 15;
    return static_cast<float>(h & 0xFFFF) / 65535.0F - 0.5F;
}

/** Standard deviation of a channel over a rectangle. */
float deviation(const TileStore& s, std::uint32_t x0, std::uint32_t y0, std::uint32_t x1, std::uint32_t y1, int c) {
    double sum = 0, sq = 0;
    int n = 0;
    for (std::uint32_t y = y0; y < y1; ++y)
        for (std::uint32_t x = x0; x < x1; ++x) {
            const double v = at(s, x, y)[static_cast<std::size_t>(c)];
            sum += v;
            sq += v * v;
            ++n;
        }
    const double mean = sum / n;
    return static_cast<float>(std::sqrt(std::max(0.0, sq / n - mean * mean)));
}

TEST(DevelopDetail, SharpeningOvershootsEdgesWithoutFringesAndMaskingSparesFlatNoise) {
    // Grey step (left 0.1, right 0.4) above a flat noisy grey area.
    const auto scene = paint(300, 300, [](std::uint32_t x, std::uint32_t y) {
        if (y < 150) {
            const float v = x < 150 ? 0.1F : 0.4F;
            return Rgb{v, v, v};
        }
        const float v = 0.2F + 0.01F * noise(x, y, 1);
        return Rgb{v, v, v};
    });
    std::string error;
    EXPECT_EQ(at(*dev::applyDetail(scene, {}, error), 10, 10), at(scene, 10, 10)) << "identity";
    const auto sharp = run(scene, [](auto& s) {
        s.sharpening.amount = 100.0F;
        s.sharpening.radius = 1.0F;
        s.sharpening.detail = 50.0F;
    });
    EXPECT_GT(at(sharp, 150, 75)[0], 0.4F * 1.03F) << "bright side overshoots";
    EXPECT_LT(at(sharp, 149, 75)[0], 0.1F * 0.97F) << "dark side undershoots";
    EXPECT_NEAR(at(sharp, 150, 75)[0], at(sharp, 150, 75)[2], 1e-4F) << "no colour fringes";
    EXPECT_NEAR(at(sharp, 50, 75)[0], 0.1F, 1e-4F) << "flat areas unchanged";
    const float noise_before = deviation(scene, 40, 180, 260, 280, 1);
    EXPECT_GT(deviation(sharp, 40, 180, 260, 280, 1), noise_before * 1.2F) << "unmasked sharpening amplifies noise";
    const auto masked = run(scene, [](auto& s) {
        s.sharpening.amount = 100.0F;
        s.sharpening.masking = 80.0F;
    });
    EXPECT_LT(deviation(masked, 40, 180, 260, 280, 1), noise_before * 1.05F) << "masking spares flat noise";
    EXPECT_GT(at(masked, 150, 75)[0], 0.4F * 1.02F) << "…but still sharpens the edge";
}

TEST(DevelopDetail, NoiseReductionSmoothsLuminanceAndColourButKeepsEdges) {
    const auto noisy = paint(300, 300, [](std::uint32_t x, std::uint32_t y) {
        const float base = x < 150 ? 0.08F : 0.3F;
        const float l = 0.02F * noise(x, y, 2), cr = 0.02F * noise(x, y, 3), cb = 0.02F * noise(x, y, 4);
        return Rgb{base + l + cr, base + l, base + l + cb};
    });
    const auto lum = run(noisy, [](auto& s) {
        s.noise.luminance = 60.0F;
        s.noise.luminance_detail = 30.0F;
    });
    EXPECT_LT(deviation(lum, 170, 20, 280, 280, 1), deviation(noisy, 170, 20, 280, 280, 1) * 0.6F);
    // The step stays sharp: pixels two away from the edge keep their side's level.
    EXPECT_NEAR(at(lum, 147, 150)[1], 0.08F, 0.02F);
    EXPECT_NEAR(at(lum, 152, 150)[1], 0.3F, 0.03F);
    const auto colour = run(noisy, [](auto& s) { s.noise.color = 80.0F; });
    const auto chroma = [](const TileStore& s) {
        double sum = 0;
        int n = 0;
        for (std::uint32_t y = 20; y < 280; y += 3)
            for (std::uint32_t x = 170; x < 280; x += 3) {
                const auto p = at(s, x, y);
                sum += std::abs(p[0] - p[1]) + std::abs(p[2] - p[1]);
                ++n;
            }
        return sum / n;
    };
    EXPECT_LT(chroma(colour), chroma(noisy) * 0.6);
}

TEST(DevelopDetail, VignetteDarkensCornersAndProtectsHighlights) {
    const auto flat = paint(400, 300, [](std::uint32_t x, std::uint32_t) {
        const float v = x < 20 ? 0.95F : 0.2F;  // a bright strip on the left edge
        return Rgb{v, v, v};
    });
    const auto dark = run(flat, [](auto& s) { s.vignette.amount = -100.0F; });
    EXPECT_NEAR(at(dark, 200, 150)[0], 0.2F, 1e-3F) << "centre unchanged";
    EXPECT_LT(at(dark, 395, 295)[0], 0.2F * 0.4F) << "corner darkened (up to 2 stops)";
    const auto protect = run(flat, [](auto& s) {
        s.vignette.amount = -100.0F;
        s.vignette.highlights = 100.0F;
    });
    EXPECT_GT(at(protect, 5, 295)[0], at(dark, 5, 295)[0] * 1.5F) << "bright corner protected";
    const auto light = run(flat, [](auto& s) { s.vignette.amount = 60.0F; });
    EXPECT_GT(at(light, 395, 295)[0], 0.2F);
    // Post-crop frame: the vignette centres on the crop, not the layer.
    const auto framed = run(flat, [](auto& s) {
        s.vignette.amount = -100.0F;
        s.vignette.left = 200;
        s.vignette.top = 0;
        s.vignette.right = 400;
        s.vignette.bottom = 300;
    });
    EXPECT_NEAR(at(framed, 300, 150)[0], 0.2F, 1e-3F);
    EXPECT_LT(at(framed, 205, 5)[0], 0.2F * 0.5F);

    const auto extreme_frame = run(flat, [](auto& s) {
        s.vignette.amount = -100.0F;
        s.vignette.roundness = 100.0F;
        s.vignette.left = std::numeric_limits<std::int64_t>::min();
        s.vignette.top = std::numeric_limits<std::int64_t>::min();
        s.vignette.right = std::numeric_limits<std::int64_t>::max();
        s.vignette.bottom = std::numeric_limits<std::int64_t>::max();
    });
    const auto ordinary_frame = run(flat, [](auto& s) {
        s.vignette.amount = -100.0F;
        s.vignette.roundness = 100.0F;
    });
    EXPECT_EQ(at(extreme_frame, 395, 295), at(ordinary_frame, 395, 295)) << "crop extents clamp before geometry arithmetic";
}

TEST(DevelopDetail, GrainIsDeterministicAndSeamless) {
    const auto grey = paint(512, 300, [](std::uint32_t, std::uint32_t) { return Rgb{0.18F, 0.18F, 0.18F}; });
    const auto grainy = run(grey, [](auto& s) {
        s.grain.amount = 60.0F;
        s.grain.size = 30.0F;
    });
    const auto again = run(grey, [](auto& s) {
        s.grain.amount = 60.0F;
        s.grain.size = 30.0F;
    });
    EXPECT_EQ(at(grainy, 300, 100), at(again, 300, 100));
    const float spread = deviation(grainy, 10, 10, 500, 290, 1);
    EXPECT_GT(spread, 0.004F);
    EXPECT_NEAR(at(grainy, 300, 100)[0], at(grainy, 300, 100)[2], 1e-5F) << "monochrome grain";
    // Across the tile boundary at x = 256 neighbouring pixels differ no more than elsewhere.
    double across = 0, within = 0;
    for (std::uint32_t y = 10; y < 290; ++y) {
        across += std::abs(at(grainy, 256, y)[1] - at(grainy, 255, y)[1]);
        across += std::abs(at(grainy, 128, y)[1] - at(grainy, 127, y)[1]);  // quarter-tile work-unit seam
        within += std::abs(at(grainy, 300, y)[1] - at(grainy, 299, y)[1]);
        within += std::abs(at(grainy, 200, y)[1] - at(grainy, 199, y)[1]);
    }
    EXPECT_LT(across, within * 1.5);
    const auto seeded = run(grey, [](auto& s) {
        s.grain.amount = 60.0F;
        s.grain.seed = 7;
    });
    EXPECT_NE(at(seeded, 300, 100), at(grainy, 300, 100));
}

TEST(DevelopDetail, Validation) {
    const auto grey = paint(64, 64, [](std::uint32_t, std::uint32_t) { return Rgb{0.2F, 0.2F, 0.2F}; });
    std::string error;
    dev::DetailSettings s;
    s.sharpening.amount = 151.0F;
    EXPECT_FALSE(dev::applyDetail(grey, s, error));
    s = {};
    s.grain.amount = 10.0F;
    s.luminance = {0.3F, 0.0F, 0.7F};
    EXPECT_FALSE(dev::applyDetail(grey, s, error));
    s = {};
    s.luminance = {0.5F, 0.75F, 0.5F};
    EXPECT_FALSE(dev::applyDetail(grey, s, error)) << "luminance weights must be normalized and bounded";
    s = {};
    s.vignette.amount = -50.0F;
    s.progress = [](float) { return false; };
    EXPECT_FALSE(dev::applyDetail(grey, s, error));
    EXPECT_EQ(error, "cancelled");
    EXPECT_FALSE(dev::applyDetail(TileStore(PixelFormat::RGBA8, 4, 4), {}, error));
}

TEST(DevelopDetail, SparseLayerStillReportsProgressForEveryTile) {
    const TileStore sparse(PixelFormat::RGBA32F, 512, 256);
    dev::DetailSettings settings;
    settings.grain.amount = 20.0F;
    settings.worker_threads = 1;
    std::vector<float> progress;
    settings.progress = [&](float value) {
        progress.push_back(value);
        return true;
    };
    std::string error;
    const auto out = dev::applyDetail(sparse, settings, error);
    ASSERT_TRUE(out) << error;
    EXPECT_EQ(out->tile_count(), 0U);
    ASSERT_EQ(progress.size(), 2U);
    EXPECT_FLOAT_EQ(progress[0], 0.5F);
    EXPECT_FLOAT_EQ(progress[1], 1.0F);
}

#ifdef NDEBUG
TEST(DevelopDetail, Throughput24MPRgba16) {
    constexpr std::uint32_t w = 6000, h = 4000;
    TileStore input(PixelFormat::RGBA16, w, h);
    for (std::uint32_t ty = 0; ty * 256U < h; ++ty) {
        for (std::uint32_t tx = 0; tx * 256U < w; ++tx) {
            croptop::TileBuffer tile(input.tile_byte_size(), 0);
            for (std::uint32_t y = 0; y < 256; ++y) {
                for (std::uint32_t x = 0; x < 256; ++x) {
                    const auto gx = tx * 256U + x, gy = ty * 256U + y;
                    if (gx >= w || gy >= h) continue;
                    const std::uint16_t px[4] = {12000, static_cast<std::uint16_t>(10000U + (gx + gy) % 3000U), 8000, 65535};
                    std::memcpy(tile.data() + (static_cast<std::size_t>(y) * 256U + x) * 8U, px, sizeof(px));
                }
            }
            ASSERT_TRUE(input.writeTile({static_cast<std::int32_t>(tx), static_cast<std::int32_t>(ty)}, tile));
        }
    }
    dev::DetailSettings settings;
    settings.sharpening = {55.0F, 1.2F, 50.0F, 30.0F};
    settings.noise = {20.0F, 50.0F, 20.0F, 50.0F};
    settings.vignette.amount = -12.0F;
    settings.grain = {12.0F, 30.0F, 50.0F, 42};
    std::string error;
    const auto start = std::chrono::steady_clock::now();
    const auto out = dev::applyDetail(input, settings, error);
    const double ms = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - start).count();
    ASSERT_TRUE(out) << error;
    ASSERT_EQ(out->width(), w);
    std::printf("[develop] 24 MP RGBA16 detail (sharpen+NR+vignette+grain): %.0f ms\n", ms);
}
#endif

}  // namespace
