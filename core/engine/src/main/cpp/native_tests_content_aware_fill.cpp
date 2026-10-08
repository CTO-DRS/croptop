// Tests for Content-Aware Fill.
#include "content_aware_fill.h"
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
#include <stdexcept>
#include <string>

namespace {

using croptop::PixelFormat;
using croptop::TileStore;
namespace pt = croptop::paint;
namespace sel = croptop::selection;
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

/** PSNR (dB) of the RGB channels over the given rectangle. */
double psnr(const TileStore& a, const TileStore& b, std::uint32_t x0, std::uint32_t y0, std::uint32_t x1, std::uint32_t y1) {
    double mse = 0;
    std::size_t n = 0;
    for (std::uint32_t y = y0; y < y1; ++y)
        for (std::uint32_t x = x0; x < x1; ++x) {
            const auto p = at(a, x, y), q = at(b, x, y);
            for (int c = 0; c < 3; ++c) mse += (p[c] - q[c]) * (p[c] - q[c]);
            n += 3;
        }
    mse /= n;
    return mse == 0 ? 99.0 : 10 * std::log10(255.0 * 255.0 / mse);
}

TEST(ContentAwareFill, ContinuesPeriodicStripesAcrossTheHole) {
    const auto stripes = paint(240, 200, [](std::uint32_t x, std::uint32_t) {
        return (x / 6) % 2 == 0 ? Rgba{200, 60, 40, 255} : Rgba{30, 90, 180, 255};
    });
    const auto hole = sel::rasterizeRect(240, 200, {80, 60, 150, 130}, false);
    std::string error;
    const auto filled = pt::contentAwareFill(stripes, hole, nullptr, {}, error);
    ASSERT_TRUE(filled) << error;
    const double quality = psnr(*filled, stripes, 80, 60, 150, 130);
    std::printf("[content-aware fill] stripes PSNR %.1f dB\n", quality);
    EXPECT_GT(quality, 20.0);
    EXPECT_EQ(at(*filled, 10, 10), at(stripes, 10, 10)) << "outside the hole is untouched";
}

TEST(ContentAwareFill, SmoothGradientsFillWithoutVisibleSeams) {
    const auto gradient = paint(256, 160, [](std::uint32_t x, std::uint32_t y) {
        return Rgba{static_cast<std::uint8_t>(x), static_cast<std::uint8_t>(y), 128, 255};
    });
    const auto hole = sel::rasterizeEllipse(256, 160, {100, 50, 160, 110}, false);
    std::string error;
    const auto filled = *pt::contentAwareFill(gradient, hole, nullptr, {}, error);
    double worst = 0;
    double mean = 0;
    int n = 0;
    for (std::uint32_t y = 50; y < 110; ++y)
        for (std::uint32_t x = 100; x < 160; ++x) {
            if (sel::coverageAt(hole, x, y) == 0) continue;
            const auto p = at(filled, x, y), q = at(gradient, x, y);
            const double e = (std::abs(p[0] - q[0]) + std::abs(p[1] - q[1])) / 2.0;
            worst = std::max(worst, e);
            mean += e;
            ++n;
        }
    mean /= n;
    std::printf("[content-aware fill] gradient mean error %.2f, worst %.0f (of 255)\n", mean, worst);
    EXPECT_LT(mean, 12.0);
}

TEST(ContentAwareFill, RotationAdaptationMatchesRotatedTexture) {
    // Only vertical stripes (left) may be sampled; the hole sits in horizontal stripes (right).
    const auto wave = [](std::uint32_t v) {
        return static_cast<std::uint8_t>(std::lround(127.5 + 110.0 * std::sin(v * 2.0 * 3.14159265358979 / 10.0)));
    };
    const auto image = paint(240, 160, [&](std::uint32_t x, std::uint32_t y) {
        const auto g = x < 120 ? wave(x) : wave(y);
        return Rgba{g, g, g, 255};
    });
    const auto truth = paint(240, 160, [&](std::uint32_t, std::uint32_t y) {
        const auto g = wave(y);
        return Rgba{g, g, g, 255};
    });
    const auto hole = sel::rasterizeRect(240, 160, {160, 50, 210, 110}, false);
    const auto left = sel::rasterizeRect(240, 160, {0, 0, 120, 160}, false);
    std::string error;
    pt::ContentAwareFillSpec spec;
    spec.color_adaptation = false;
    const auto plain = *pt::contentAwareFill(image, hole, &left, spec, error);
    spec.rotation_adaptation = pt::RotationAdaptation::High;
    const auto rotated = *pt::contentAwareFill(image, hole, &left, spec, error);
    const double q_plain = psnr(plain, truth, 160, 50, 210, 110);
    const double q_rotated = psnr(rotated, truth, 160, 50, 210, 110);
    std::printf("[content-aware fill] rotated stripes PSNR none %.1f dB, high %.1f dB\n", q_plain, q_rotated);
    EXPECT_GT(q_rotated, q_plain + 6.0) << "±90° patches turn the vertical source into horizontal stripes";
    EXPECT_GT(q_rotated, 20.0);

    // None is the default and matches the unrotated path exactly (deterministic seed).
    spec.rotation_adaptation = pt::RotationAdaptation::None;
    const auto again = *pt::contentAwareFill(image, hole, &left, spec, error);
    EXPECT_EQ(psnr(again, plain, 0, 0, 240, 160), 99.0);

    // Full rotation still fills every pixel from allowed texture (no out-of-range reads under ASan).
    spec.rotation_adaptation = pt::RotationAdaptation::Full;
    const auto full = *pt::contentAwareFill(image, hole, &left, spec, error);
    EXPECT_GT(psnr(full, truth, 160, 50, 210, 110), q_plain);
}

TEST(ContentAwareFill, MirrorAdaptationMatchesFlippedTexture) {
    // Sampleable left half: a smooth but asymmetric wave; the hole sits in its mirror image. (A 16 px hole is
    // filled at one pyramid level.)
    const auto saw = [](std::uint32_t v) {
        // Asymmetric at two scales, so coarse pyramid levels can already tell the orientation apart.
        const double u = v * 2.0 * 3.14159265358979 / 8.0, w = v * 2.0 * 3.14159265358979 / 32.0;
        return static_cast<std::uint8_t>(std::lround(127.5 + 45.0 * std::sin(u) + 22.0 * std::sin(2.0 * u + 1.0) +
                                                     35.0 * std::sin(w) + 17.0 * std::sin(2.0 * w + 1.0)));
    };
    const auto image = paint(240, 120, [&](std::uint32_t x, std::uint32_t) {
        const auto g = x < 120 ? saw(x) : saw(1200 - x);
        return Rgba{g, g, g, 255};
    });
    const auto truth = paint(240, 120, [&](std::uint32_t x, std::uint32_t) {
        const auto g = saw(1200 - x);
        return Rgba{g, g, g, 255};
    });
    const auto hole = sel::rasterizeRect(240, 120, {175, 52, 191, 68}, false);
    const auto left = sel::rasterizeRect(240, 120, {0, 0, 120, 120}, false);
    std::string error;
    pt::ContentAwareFillSpec spec;
    spec.color_adaptation = false;
    const auto plain = *pt::contentAwareFill(image, hole, &left, spec, error);
    spec.mirror_adaptation = true;
    const auto mirrored = *pt::contentAwareFill(image, hole, &left, spec, error);
    const double q_plain = psnr(plain, truth, 175, 52, 191, 68);
    const double q_mirrored = psnr(mirrored, truth, 175, 52, 191, 68);
    std::printf("[content-aware fill] mirrored wave PSNR plain %.1f dB, mirror %.1f dB\n", q_plain, q_mirrored);
    EXPECT_GT(q_mirrored, q_plain + 6.0);
    EXPECT_GT(q_mirrored, 23.0);
    spec.rotation_adaptation = pt::RotationAdaptation::Full;  // 48 transforms still fit and stay in bounds
    EXPECT_GT(psnr(*pt::contentAwareFill(image, hole, &left, spec, error), truth, 175, 52, 191, 68), q_plain);
}

TEST(ContentAwareFill, SamplingMaskSoftEdgesCancellationAndValidation) {
    // Left red, right blue; the hole sits inside the red half but only blue may be sampled.
    const auto halves = paint(200, 120, [](std::uint32_t x, std::uint32_t) {
        return x < 100 ? Rgba{220, 30, 30, 255} : Rgba{30, 30, 220, 255};
    });
    const auto hole = sel::rasterizeRect(200, 120, {30, 40, 60, 80}, false);
    const auto blue_only = sel::rasterizeRect(200, 120, {100, 0, 200, 120}, false);
    std::string error;
    pt::ContentAwareFillSpec copy_only;
    copy_only.color_adaptation = false;
    const auto from_blue = *pt::contentAwareFill(halves, hole, &blue_only, copy_only, error);
    EXPECT_GT(at(from_blue, 45, 60)[2], 180) << "without adaptation the fill copies the allowed (blue) area";
    const auto adapted = *pt::contentAwareFill(halves, hole, &blue_only, {}, error);
    EXPECT_GT(at(adapted, 45, 60)[0], 150) << "colour adaptation pulls the blue texture to the red surroundings";
    const auto from_any = *pt::contentAwareFill(halves, hole, nullptr, copy_only, error);
    EXPECT_GT(at(from_any, 45, 60)[0], 180) << "without a sampling mask the red surroundings win";

    // A half-covered hole edge blends 50/50 with the original.
    const auto soft = sel::rasterizeRect(200, 120, {30, 40, 60.5, 80}, true);
    const auto blended = *pt::contentAwareFill(halves, soft, &blue_only, copy_only, error);
    EXPECT_NEAR(at(blended, 60, 60)[2], (30 + at(from_blue, 45, 60)[2]) / 2.0, 20.0);

    pt::ContentAwareFillSpec cancelling;
    cancelling.progress = [](float) { return false; };
    EXPECT_FALSE(pt::contentAwareFill(halves, hole, nullptr, cancelling, error));
    EXPECT_EQ(error, "cancelled");

    EXPECT_EQ(pt::contentAwareFill(halves, sel::emptyMask(200, 120), nullptr, {}, error)->tile_count(), halves.tile_count());
    EXPECT_FALSE(pt::contentAwareFill(halves, sel::emptyMask(201, 120), nullptr, {}, error));
    const auto everything = sel::fullMask(200, 120);
    EXPECT_FALSE(pt::contentAwareFill(halves, everything, nullptr, {}, error)) << "nothing left to sample";
}

TEST(ContentAwareFill, ProgressCallbackExceptionDuringColorAdaptationCancelsAndJoinsWorkers) {
    const auto image = paint(200, 120, [](std::uint32_t x, std::uint32_t y) {
        const auto channel = static_cast<std::uint8_t>((x * 7U + y * 11U) % 255U);
        return Rgba{channel, channel, channel, 255};
    });
    const auto hole = sel::rasterizeRect(200, 120, {70, 35, 110, 75}, false);
    pt::ContentAwareFillSpec spec;
    spec.iterations = 1;
    int progress_calls = 0;
    spec.progress = [&](float) {
        if (++progress_calls == 2) throw std::runtime_error("progress observer failed");
        return true;
    };
    std::string error;
    EXPECT_FALSE(pt::contentAwareFill(image, hole, nullptr, spec, error));
    EXPECT_EQ(error, "cancelled");
    EXPECT_GE(progress_calls, 2);
}


/** Acceptance (host stand-in): a 24 MP photo-like texture with a 5% hole (CROPTOP_STRESS=1). */
TEST(ContentAwareFill, TimingOn24MegapixelsWithA5PercentHole) {
    CROPTOP_REQUIRE_STRESS();
    const std::uint32_t w = 6000, h = 4000;
    const auto image = paint(w, h, [](std::uint32_t x, std::uint32_t y) {
        const auto n = static_cast<std::uint32_t>((x * 1103515245U) ^ (y * 12345U)) >> 24U;
        const auto base = static_cast<std::uint8_t>(80 + 60 * std::sin(x * 0.01) * std::cos(y * 0.013));
        return Rgba{static_cast<std::uint8_t>(base + n % 24), static_cast<std::uint8_t>(base / 2 + n % 24), static_cast<std::uint8_t>(60 + n % 24), 255};
    });
    const double side = std::sqrt(0.05 * w * h);  // 5% of the pixels
    const auto hole = sel::rasterizeRect(w, h, {w / 2.0 - side / 2, h / 2.0 - side / 2, w / 2.0 + side / 2, h / 2.0 + side / 2}, false);
    pt::ContentAwareFillSpec spec;
    std::string error;
    const auto start = std::chrono::steady_clock::now();
    const auto out = pt::contentAwareFill(image, hole, nullptr, spec, error);
    const double seconds = std::chrono::duration<double>(std::chrono::steady_clock::now() - start).count();
    ASSERT_TRUE(out) << error;
    std::printf("[content-aware 24MP, 5%% hole] %.2f s\n", seconds);
    EXPECT_LT(seconds, 3.0) << "host Release timing; the reference device is the acceptance target";
}

}  // namespace
