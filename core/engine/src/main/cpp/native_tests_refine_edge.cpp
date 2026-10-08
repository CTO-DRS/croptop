// Tests for Select and Mask / Refine Edge.
#include "refine_edge.h"
#include "selection_mask.h"
#include "tile_store.h"

#include <gtest/gtest.h>

#include <algorithm>
#include <array>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <random>
#include <string>
#include <vector>

namespace {

using croptop::PixelFormat;
using croptop::TileStore;
namespace sel = croptop::selection;

constexpr std::uint32_t kSize = 256;
constexpr std::array<float, 3> kBackground{20 / 255.0F, 30 / 255.0F, 90 / 255.0F};
constexpr std::array<float, 3> kForeground{240 / 255.0F, 160 / 255.0F, 60 / 255.0F};

/** Ground-truth alpha (4×4 supersampled) of a disk with thin radiating strands ("hair"). */
std::vector<float> hairAlpha() {
    std::mt19937 random(5);
    std::vector<double> angles;
    for (int i = 0; i < 40; ++i) angles.push_back((random() % 3600) / 3600.0 * 2 * M_PI);
    std::vector<float> alpha(kSize * kSize);
    for (std::uint32_t y = 0; y < kSize; ++y) {
        for (std::uint32_t x = 0; x < kSize; ++x) {
            int hits = 0;
            for (int sy = 0; sy < 4; ++sy) {
                for (int sx = 0; sx < 4; ++sx) {
                    const double px = x + (sx + 0.5) / 4 - 128.0;
                    const double py = y + (sy + 0.5) / 4 - 128.0;
                    const double r = std::hypot(px, py);
                    bool in = r <= 60.0;
                    for (const auto a : angles) {
                        if (in || r > 100.0) break;
                        // Distance from the ray at angle a (strands are 1.5 px wide).
                        const double along = px * std::cos(a) + py * std::sin(a);
                        const double across = -px * std::sin(a) + py * std::cos(a);
                        in = along > 0 && std::fabs(across) <= 0.75;
                    }
                    hits += in ? 1 : 0;
                }
            }
            alpha[y * kSize + x] = hits / 16.0F;
        }
    }
    return alpha;
}

TileStore compositeImage(const std::vector<float>& alpha, int noise = 0) {
    std::mt19937 random(17);
    TileStore image(PixelFormat::RGBA8, kSize, kSize);
    croptop::TileBuffer tile(image.tile_byte_size(), 0);
    for (std::uint32_t y = 0; y < kSize; ++y) {
        for (std::uint32_t x = 0; x < kSize; ++x) {
            const auto a = alpha[y * kSize + x];
            auto* p = tile.data() + (static_cast<std::size_t>(y) * kSize + x) * 4;
            for (int c = 0; c < 3; ++c) {
                const int jitter = noise == 0 ? 0 : static_cast<int>(random() % (2 * noise + 1)) - noise;
                p[c] = static_cast<std::uint8_t>(std::clamp<long>(std::lround((a * kForeground[c] + (1 - a) * kBackground[c]) * 255) + jitter, 0, 255));
            }
            p[3] = 255;
        }
    }
    EXPECT_TRUE(image.writeTile({0, 0}, tile));
    return image;
}

std::vector<float> maskValues(const TileStore& mask) {
    std::vector<float> out(kSize * kSize);
    for (std::uint32_t y = 0; y < kSize; ++y)
        for (std::uint32_t x = 0; x < kSize; ++x) out[y * kSize + x] = sel::coverageAt(mask, x, y) / 65535.0F;
    return out;
}

struct Score {
    double sad;
    double iou;
};

Score score(const std::vector<float>& mask, const std::vector<float>& truth) {
    double sad = 0, inter = 0, uni = 0;
    for (std::size_t i = 0; i < mask.size(); ++i) {
        sad += std::fabs(mask[i] - truth[i]);
        const bool a = mask[i] >= 0.5F, b = truth[i] >= 0.5F;
        inter += (a && b) ? 1 : 0;
        uni += (a || b) ? 1 : 0;
    }
    return {sad, inter / uni};
}

TEST(RefineEdge, RecoversHairStrandsBetterThanTheCoarseMask) {
    const auto truth = hairAlpha();
    const auto image = compositeImage(truth);
    const auto coarse = sel::rasterizeEllipse(kSize, kSize, {68, 68, 188, 188}, true);  // The disk only.
    std::string error;
    sel::RefineEdgeSpec spec;
    spec.radius = 45.0F;
    const auto refined = sel::refine(image, coarse, spec, error);
    ASSERT_TRUE(refined) << error;
    const auto before = score(maskValues(coarse), truth);
    const auto after = score(maskValues(*refined), truth);
    std::printf("[refine-edge metrics] coarse SAD %.1f IoU %.4f | refined SAD %.1f IoU %.4f\n", before.sad, before.iou,
                after.sad, after.iou);
    EXPECT_LT(after.sad, before.sad * 0.1) << "refinement should recover most of the missing strand coverage";
    EXPECT_GT(after.iou, before.iou);
    // The disk interior and far background are untouched.
    EXPECT_EQ(sel::coverageAt(*refined, 128, 128), 65535);
    EXPECT_EQ(sel::coverageAt(*refined, 5, 5), 0);
}

TEST(RefineEdge, OutputControlsBehaveAsDocumented) {
    const auto truth = hairAlpha();
    const auto image = compositeImage(truth);
    const auto coarse = sel::rasterizeEllipse(kSize, kSize, {68, 68, 188, 188}, true);
    std::string error;
    const auto sum = [&](const TileStore& m) {
        double s = 0;
        for (const auto v : maskValues(m)) s += v;
        return s;
    };
    sel::RefineEdgeSpec identity;
    identity.radius = 0.0F;
    const auto same = *sel::refine(image, coarse, identity, error);
    EXPECT_EQ(maskValues(same), maskValues(coarse)) << "no radius and no output controls leave the mask unchanged";

    sel::RefineEdgeSpec grow = identity;
    grow.shift_edge = 60.0F;
    sel::RefineEdgeSpec shrink = identity;
    shrink.shift_edge = -60.0F;
    EXPECT_GT(sum(*sel::refine(image, coarse, grow, error)), sum(coarse));
    EXPECT_LT(sum(*sel::refine(image, coarse, shrink, error)), sum(coarse));

    sel::RefineEdgeSpec soft = identity;
    soft.feather = 8.0F;
    const auto feathered = maskValues(*sel::refine(image, coarse, soft, error));
    int partial_before = 0, partial_after = 0;
    for (const auto v : maskValues(coarse)) partial_before += (v > 0.02F && v < 0.98F);
    for (const auto v : feathered) partial_after += (v > 0.02F && v < 0.98F);
    EXPECT_GT(partial_after, partial_before * 3) << "feather widens the soft edge";

    sel::RefineEdgeSpec hard = soft;
    hard.contrast = 100.0F;
    int partial_hard = 0;
    for (const auto v : maskValues(*sel::refine(image, coarse, hard, error))) partial_hard += (v > 0.02F && v < 0.98F);
    EXPECT_LT(partial_hard, partial_after) << "contrast steepens the edge again";

    sel::RefineEdgeSpec bad;
    bad.radius = 300.0F;
    EXPECT_FALSE(sel::refine(image, coarse, bad, error));
    bad.radius = 10.0F;
    bad.shift_edge = NAN;
    EXPECT_FALSE(sel::refine(image, coarse, bad, error));
    EXPECT_FALSE(sel::refine(sel::emptyMask(kSize, kSize), coarse, identity, error)) << "guide must be RGBA";
    EXPECT_TRUE(sel::isEmpty(*sel::refine(image, sel::emptyMask(kSize, kSize), identity, error)));
}

TEST(RefineEdge, DecontaminateRecoversForegroundColour) {
    // A 50% blend of orange over blue, fully orange to the left and blue to the right.
    TileStore image(PixelFormat::RGBA8, 64, 64);
    croptop::TileBuffer tile(image.tile_byte_size(), 0);
    TileStore mask(PixelFormat::A16, 64, 64);
    croptop::TileBuffer mask_tile(mask.tile_byte_size(), 0);
    for (std::uint32_t y = 0; y < 64; ++y) {
        for (std::uint32_t x = 0; x < 64; ++x) {
            const float a = x < 30 ? 1.0F : x < 34 ? 0.5F : 0.0F;
            auto* p = tile.data() + (static_cast<std::size_t>(y) * 256 + x) * 4;
            for (int c = 0; c < 3; ++c) p[c] = static_cast<std::uint8_t>(std::lround((a * kForeground[c] + (1 - a) * kBackground[c]) * 255));
            p[3] = 255;
            const auto m = static_cast<std::uint16_t>(std::lround(a * 65535));
            std::memcpy(mask_tile.data() + (static_cast<std::size_t>(y) * 256 + x) * 2, &m, 2);
        }
    }
    ASSERT_TRUE(image.writeTile({0, 0}, tile));
    ASSERT_TRUE(mask.writeTile({0, 0}, mask_tile));
    std::string error;
    const auto clean = sel::decontaminate(image, mask, 1.0F, error);
    ASSERT_TRUE(clean) << error;
    const auto pixel = [](const TileStore& s, std::uint32_t x, std::uint32_t y) {
        const auto t = s.readTile({0, 0});
        return std::array<int, 4>{(*t)[(y * 256 + x) * 4], (*t)[(y * 256 + x) * 4 + 1], (*t)[(y * 256 + x) * 4 + 2], (*t)[(y * 256 + x) * 4 + 3]};
    };
    const auto soft = pixel(*clean, 31, 20);
    for (int c = 0; c < 3; ++c) EXPECT_NEAR(soft[c], kForeground[c] * 255, 3) << "channel " << c;
    EXPECT_EQ(soft[3], 255) << "alpha untouched";
    EXPECT_EQ(pixel(*clean, 10, 20), pixel(image, 10, 20)) << "opaque foreground untouched";
    EXPECT_EQ(pixel(*clean, 50, 20), pixel(image, 50, 20)) << "background untouched";
    const auto half = *sel::decontaminate(image, mask, 0.5F, error);
    EXPECT_NEAR(pixel(half, 31, 20)[0], (pixel(image, 31, 20)[0] + soft[0]) / 2.0, 2);
    EXPECT_FALSE(sel::decontaminate(image, mask, 1.5F, error));
}

TEST(RefineEdge, StillHelpsOnANoisyImage) {
    const auto truth = hairAlpha();
    const auto image = compositeImage(truth, 12);  // ±12/255 per channel.
    const auto coarse = sel::rasterizeEllipse(kSize, kSize, {68, 68, 188, 188}, true);
    std::string error;
    sel::RefineEdgeSpec spec;
    spec.radius = 45.0F;
    const auto before = score(maskValues(coarse), truth);
    const auto after = score(maskValues(*sel::refine(image, coarse, spec, error)), truth);
    std::printf("[refine-edge metrics, noise ±12] coarse SAD %.1f IoU %.4f | refined SAD %.1f IoU %.4f\n", before.sad,
                before.iou, after.sad, after.iou);
    EXPECT_LT(after.sad, before.sad * 0.5);
    EXPECT_GT(after.iou, before.iou);
}

}  // namespace
