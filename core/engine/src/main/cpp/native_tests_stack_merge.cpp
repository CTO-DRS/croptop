// Tests for MTB alignment, HDR merge and focus stacking.
#include "stack_merge.h"

#include <gtest/gtest.h>

#include <cmath>
#include <cstdint>
#include <functional>
#include <vector>

#include "pixel_plane.h"

namespace {

using croptop::PixelFormat;
using croptop::TileStore;
namespace stack = croptop::stack;

constexpr int kW = 300, kH = 200;

float hash(int x, int y) {
    std::uint32_t h = static_cast<std::uint32_t>(x) * 374761393U + static_cast<std::uint32_t>(y) * 668265263U;
    h = (h ^ (h >> 13)) * 1274126177U;
    return static_cast<float>((h ^ (h >> 16)) & 0xFFFFU) / 65535.0F;
}

/** Scene radiance (linear): smooth structure, blobs and speckle, spanning ~0.002…9 across x. */
float scene(int x, int y) {
    const float ramp = std::exp2(-8.0F + 10.5F * static_cast<float>(x) / kW);
    const float structure = 0.6F + 0.4F * std::sin(0.11F * static_cast<float>(x)) * std::cos(0.13F * static_cast<float>(y));
    const float blob = ((x / 17 + y / 13) % 3 == 0) ? 1.6F : 1.0F;
    const float speckle = 0.6F + 0.8F * hash(x / 4, y / 4);  // natural-image detail that anchors the alignment
    return ramp * structure * blob * speckle;
}

float srgb(float v) { return v <= 0.0031308F ? v * 12.92F : 1.055F * std::pow(v, 1.0F / 2.4F) - 0.055F; }

TileStore fill(PixelFormat format, const std::function<std::array<float, 4>(int, int)>& pixel) {
    TileStore s(format, kW, kH);
    for (std::int32_t ty = 0; ty * 256 < kH; ++ty)
        for (std::int32_t tx = 0; tx * 256 < kW; ++tx) {
            croptop::TileBuffer t(s.tile_byte_size(), 0);
            for (int y = 0; y < 256; ++y)
                for (int x = 0; x < 256; ++x) {
                    const int gx = tx * 256 + x, gy = ty * 256 + y;
                    if (gx >= kW || gy >= kH) continue;
                    const auto v = pixel(gx, gy);
                    croptop::paint::detail::writePixel(format, t.data() + (static_cast<std::size_t>(y) * 256 + x) * s.bytes_per_pixel(), v.data());
                }
            EXPECT_TRUE(s.writeTile({tx, ty}, t));
        }
    return s;
}

std::array<float, 4> at(const TileStore& s, int x, int y) {
    const auto tile = s.readTile({x / 256, y / 256});
    std::array<float, 4> v{};
    if (tile) croptop::paint::detail::readPixel(s.format(), tile->data() + (static_cast<std::size_t>(y % 256) * 256 + x % 256) * s.bytes_per_pixel(), v.data());
    return v;
}

/** A bracketed exposure of the scene, shifted by (dx, dy): the frame shows scene(x + dx, y + dy). */
TileStore exposure(float e, PixelFormat format, int dx = 0, int dy = 0) {
    return fill(format, [=](int x, int y) {
        const int sx = std::clamp(x + dx, 0, kW - 1), sy = std::clamp(y + dy, 0, kH - 1);
        const float v = scene(sx, sy) * e;
        return std::array<float, 4>{srgb(std::min(1.0F, v)), srgb(std::min(1.0F, v * 0.9F)), srgb(std::min(1.0F, v * 0.8F)), 1.0F};
    });
}

TEST(StackMerge, MtbRecoversShiftAcrossExposures) {
    const auto reference = exposure(1.0F, PixelFormat::RGBA8);
    // The frame shows scene(x + 7, y − 4), so reference(x, y) = frame(x − 7, y + 4): offset (7, −4).
    const auto frame = exposure(4.0F, PixelFormat::RGBA16, 7, -4);
    const auto offset = stack::alignMtb(reference, frame);
    EXPECT_EQ(offset.dx, 7);
    EXPECT_EQ(offset.dy, -4);
    const auto same = stack::alignMtb(reference, exposure(0.25F, PixelFormat::RGBA8));
    EXPECT_EQ(same.dx, 0);
    EXPECT_EQ(same.dy, 0);
}

void expectRadiance(const TileStore& merged, int margin) {
    ASSERT_EQ(merged.format(), PixelFormat::RGBA32F);
    double worst = 0;
    int checked = 0;
    for (int y = margin; y < kH - margin; y += 3)
        for (int x = margin; x < kW - margin; x += 3) {
            const float truth = scene(x, y);
            const auto v = at(merged, x, y);
            // 8-bit quantisation limits the precision of the darkest recoverable values.
            const double rel = std::abs(v[0] - truth) / truth;
            worst = std::max(worst, rel);
            EXPECT_LT(rel, 0.06) << "at (" << x << ", " << y << ") truth " << truth << " got " << v[0];
            EXPECT_NEAR(v[1] / v[0], 0.9, 0.05);
            EXPECT_NEAR(v[3], 1.0, 1e-5);
            ++checked;
            if (rel >= 0.06) return;
        }
    EXPECT_GT(checked, 1000);
}

TEST(StackMerge, HdrMergeRecoversSceneRadiance) {
    const auto dark = exposure(1.0F / 16.0F, PixelFormat::RGBA8), mid = exposure(1.0F, PixelFormat::RGBA16), bright = exposure(16.0F, PixelFormat::RGBA8);
    // Frames in any order; the middle exposure sets the output scale (radiance × 1).
    const std::vector<stack::HdrFrame> frames{{&bright, 16.0F}, {&dark, 1.0F / 16.0F}, {&mid, 1.0F}};
    std::string error;
    stack::MergeOptions options;
    options.align = false;
    const auto merged = stack::mergeHdr(frames, options, error);
    ASSERT_TRUE(merged) << error;
    expectRadiance(*merged, 0);
    // No single frame covers the range: the mid exposure clips the right side and crushes the left.
    EXPECT_LT(srgb(std::min(1.0F, scene(kW - 1, 0))), 1.01F);
    EXPECT_GT(scene(kW - 1, 0), 3.0F);
}

TEST(StackMerge, HdrMergeAlignsHandheldFrames) {
    const auto dark = exposure(1.0F / 16.0F, PixelFormat::RGBA8, -5, 3), mid = exposure(1.0F, PixelFormat::RGBA8), bright = exposure(16.0F, PixelFormat::RGBA8, 6, 2);
    const std::vector<stack::HdrFrame> frames{{&dark, 1.0F / 16.0F}, {&mid, 1.0F}, {&bright, 16.0F}};
    std::string error;
    int calls = 0;
    float last = 0;
    stack::MergeOptions options;
    options.worker_threads = 3;
    options.progress = [&](float p) {
        ++calls;
        EXPECT_GE(p, last);
        last = p;
        return true;
    };
    const auto merged = stack::mergeHdr(frames, options, error);
    ASSERT_TRUE(merged) << error;
    EXPECT_GT(calls, 0);
    EXPECT_FLOAT_EQ(last, 1.0F);
    expectRadiance(*merged, 8);
}

TEST(StackMerge, HdrMergeSingleLinearFrameAndErrors) {
    const auto linear = fill(PixelFormat::RGBA32F, [](int x, int y) { return std::array<float, 4>{scene(x, y), scene(x, y), scene(x, y), 1.0F}; });
    std::string error;
    const std::vector<stack::HdrFrame> one{{&linear, 2.0F}};
    const auto merged = stack::mergeHdr(one, {}, error);
    ASSERT_TRUE(merged) << error;
    // Clipped samples fall back to the only frame: radiance passes through unchanged.
    EXPECT_NEAR(at(*merged, 150, 100)[0], scene(150, 100), 1e-4);
    EXPECT_NEAR(at(*merged, kW - 1, 0)[0], scene(kW - 1, 0), 1e-3);

    EXPECT_FALSE(stack::mergeHdr({}, {}, error));
    const std::vector<stack::HdrFrame> bad{{&linear, 0.0F}};
    EXPECT_FALSE(stack::mergeHdr(bad, {}, error));
    TileStore small(PixelFormat::RGBA8, 10, 10);
    const std::vector<stack::HdrFrame> mixed{{&linear, 1.0F}, {&small, 1.0F}};
    EXPECT_FALSE(stack::mergeHdr(mixed, {}, error));
    stack::MergeOptions cancel;
    cancel.progress = [](float) { return false; };
    EXPECT_FALSE(stack::mergeHdr(one, cancel, error));
    EXPECT_EQ(error, "cancelled");
}

/** In-focus subject: broad shapes (shared by every frame, whatever the focus) plus fine detail. */
float sharpValue(int x, int y) {
    x = std::clamp(x, 0, kW - 1);
    y = std::clamp(y, 0, kH - 1);
    return 0.35F + 0.2F * std::sin(0.09F * static_cast<float>(x)) * std::cos(0.07F * static_cast<float>(y)) + 0.3F * hash(x, y);
}

/** The subject; `blurred(x, y)` chooses a 7×7 box-blurred (defocused) version at that pixel. */
TileStore texture(const std::function<bool(int, int)>& blurred, int dx = 0, int dy = 0) {
    const auto sharp = sharpValue;
    return fill(PixelFormat::RGBA8, [=](int x, int y) {
        const int sx = x + dx, sy = y + dy;
        float v = 0;
        if (blurred(sx, sy)) {
            for (int j = -3; j <= 3; ++j)
                for (int i = -3; i <= 3; ++i) v += sharp(sx + i, sy + j);
            v /= 49.0F;
        } else {
            v = sharp(sx, sy);
        }
        return std::array<float, 4>{v, v, v, 1.0F};
    });
}

double sharpError(const TileStore& s, int x0, int x1, int margin) {
    double err = 0;
    int n = 0;
    for (int y = margin; y < kH - margin; ++y)
        for (int x = x0; x < x1; ++x) {
            const float truth = sharpValue(x, y);
            err += std::abs(at(s, x, y)[0] - truth);
            ++n;
        }
    return err / n;
}

TEST(StackMerge, FocusStackKeepsTheSharpestRegions) {
    // Near focus: left half sharp. Far focus: right half sharp. Middle frame: all soft.
    const auto near = texture([](int x, int) { return x >= kW / 2; });
    const auto far = texture([](int x, int) { return x < kW / 2; });
    const auto soft = texture([](int, int) { return true; });
    std::string error;
    stack::FocusOptions options;
    options.align = false;
    const std::vector<const TileStore*> frames{&near, &soft, &far};
    const auto stacked = stack::focusStack(frames, options, error);
    ASSERT_TRUE(stacked) << error;
    EXPECT_EQ(stacked->format(), PixelFormat::RGBA8);
    const double left = sharpError(*stacked, 4, kW / 2 - 12, 4), right = sharpError(*stacked, kW / 2 + 12, kW - 4, 4);
    const double soft_err = sharpError(soft, 4, kW - 4, 4);
    EXPECT_LT(left, 0.02) << "soft frame error " << soft_err;
    EXPECT_LT(right, 0.02);
    EXPECT_GT(soft_err, 0.05);
}

TEST(StackMerge, FocusStackAlignsAndValidates) {
    const auto near = texture([](int x, int) { return x >= kW / 2; }, 4, -3);
    const auto soft = texture([](int, int) { return true; });
    const auto far = texture([](int x, int) { return x < kW / 2; }, -2, 5);
    std::string error;
    const std::vector<const TileStore*> frames{&near, &soft, &far};
    stack::FocusOptions options;
    options.smoothing = 6.0F;
    const auto stacked = stack::focusStack(frames, options, error);
    ASSERT_TRUE(stacked) << error;
    // Aligned to the (soft) reference; the sharp content of each frame lands on the reference grid.
    EXPECT_LT(sharpError(*stacked, 12, kW / 2 - 20, 12), 0.03);
    EXPECT_LT(sharpError(*stacked, kW / 2 + 20, kW - 12, 12), 0.03);

    options.smoothing = 40.0F;
    EXPECT_FALSE(stack::focusStack(frames, options, error));
    const auto linear = fill(PixelFormat::RGBA32F, [](int, int) { return std::array<float, 4>{0.5F, 0.5F, 0.5F, 1.0F}; });
    const std::vector<const TileStore*> mixed{&near, &linear};
    EXPECT_FALSE(stack::focusStack(mixed, {}, error));
    EXPECT_FALSE(stack::focusStack({}, {}, error));
}

}  // namespace

