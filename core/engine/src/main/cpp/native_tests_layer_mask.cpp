// Tests for layer-mask coverage and clipping.
#include "layer_mask.h"

#include <gtest/gtest.h>

#include <cmath>
#include <cstring>
#include <functional>
#include <thread>
#include <vector>

#include "pixel_plane.h"
#include "selection_modify.h"

namespace {

using croptop::PixelFormat;
using croptop::TileBuffer;
using croptop::TileStore;
namespace lm = croptop::layermask;

TileStore maskOf(int w, int h, const std::function<float(int, int)>& value) {
    TileStore s(PixelFormat::A16, static_cast<std::uint32_t>(w), static_cast<std::uint32_t>(h));
    for (std::int32_t ty = 0; ty * 256 < h; ++ty)
        for (std::int32_t tx = 0; tx * 256 < w; ++tx) {
            TileBuffer t(s.tile_byte_size(), 0);
            bool any = false;
            for (int y = 0; y < 256; ++y)
                for (int x = 0; x < 256; ++x) {
                    const int gx = tx * 256 + x, gy = ty * 256 + y;
                    if (gx >= w || gy >= h) continue;
                    const auto v = static_cast<std::uint16_t>(std::clamp(value(gx, gy), 0.0F, 1.0F) * 65535.0F + 0.5F);
                    any = any || v != 0;
                    std::memcpy(t.data() + (static_cast<std::size_t>(y) * 256 + x) * 2, &v, 2);
                }
            if (any) {
                EXPECT_TRUE(s.writeTile({tx, ty}, t));
            }
        }
    return s;
}

float covAt(lm::MaskCache& cache, int x, int y) {
    const auto t = cache.tile({x / 256, y / 256});
    std::uint16_t v = 0;
    std::memcpy(&v, t->data() + (static_cast<std::size_t>(y % 256) * 256 + static_cast<std::size_t>(x % 256)) * 2, 2);
    return v / 65535.0F;
}

float storeAt(const TileStore& s, int x, int y) {
    const auto t = s.readTile({x / 256, y / 256});
    if (!t) return 0.0F;
    std::uint16_t v = 0;
    std::memcpy(&v, t->data() + (static_cast<std::size_t>(y % 256) * 256 + static_cast<std::size_t>(x % 256)) * 2, 2);
    return v / 65535.0F;
}

bool inCircle(int x, int y) { return (x - 300) * (x - 300) + (y - 200) * (y - 200) < 120 * 120; }

TEST(LayerMask, DensityInvertAndDisable) {
    const auto mask = maskOf(600, 400, [](int x, int) { return x < 300 ? 1.0F : 0.25F; });
    std::string error;
    auto cache = lm::MaskCache::create(mask, {}, 0.0F, error);
    ASSERT_NE(cache, nullptr) << error;
    const auto initial_revision = cache->contentRevision();
    EXPECT_NEAR(covAt(*cache, 10, 10), 1.0F, 1e-4F);
    EXPECT_NEAR(covAt(*cache, 500, 10), 0.25F, 1e-4F);
    ASSERT_TRUE(cache->setProperties({true, false, 0.5F, 0}, error));
    EXPECT_NE(cache->contentRevision(), initial_revision);
    EXPECT_NEAR(covAt(*cache, 500, 10), 1.0F - 0.5F * 0.75F, 1e-4F) << "density 50 % halves the masking";
    ASSERT_TRUE(cache->setProperties({true, true, 1.0F, 0}, error));
    EXPECT_NEAR(covAt(*cache, 10, 10), 0.0F, 1e-4F);
    EXPECT_NEAR(covAt(*cache, 500, 10), 0.75F, 1e-4F);
    ASSERT_TRUE(cache->setProperties({false, true, 1.0F, 0}, error));
    EXPECT_NEAR(covAt(*cache, 500, 10), 1.0F, 1e-6F) << "disabled masks reveal everything";
    const auto before_rejection = cache->contentRevision();
    EXPECT_FALSE(cache->setProperties({true, false, 1.5F, 0}, error));
    EXPECT_FALSE(cache->setProperties({true, false, 1.0F, 2000}, error));
    EXPECT_EQ(cache->contentRevision(), before_rejection);
    TileStore rgba(PixelFormat::RGBA8, 10, 10);
    EXPECT_EQ(lm::MaskCache::create(rgba, {}, 0, error), nullptr);
}

TEST(LayerMask, FeatherMatchesSelectFeatherIncludingLargeRadii) {
    const auto mask = maskOf(600, 400, [](int x, int y) { return inCircle(x, y) ? 1.0F : 0.0F; });
    std::string error;
    for (const float feather : {6.0F, 40.0F, 160.0F}) {
        auto cache = lm::MaskCache::create(mask, {true, false, 1.0F, feather}, 0.0F, error);
        ASSERT_NE(cache, nullptr) << error;
        const auto reference = croptop::selection::feather(mask, feather, error);
        ASSERT_TRUE(reference) << error;
        double worst = 0;
        for (int y = 0; y < 400; y += 7)
            for (int x = 0; x < 600; x += 7) worst = std::max(worst, static_cast<double>(std::abs(covAt(*cache, x, y) - storeAt(*reference, x, y))));
        // Different blur implementations (exact / three-box, reduced copy for large radii): close, not identical.
        EXPECT_LT(worst, feather > 100 ? 0.04 : 0.03) << "feather " << feather;
    }
}

TEST(LayerMask, OutsideValueFeathersInFromTheCanvasEdge) {
    const auto mask = maskOf(300, 200, [](int, int) { return 1.0F; });
    std::string error;
    auto black = lm::MaskCache::create(mask, {true, false, 1.0F, 20}, 0.0F, error);
    auto white = lm::MaskCache::create(mask, {true, false, 1.0F, 20}, 1.0F, error);
    ASSERT_TRUE(black && white);
    EXPECT_NEAR(covAt(*black, 0, 100), 0.5F, 0.08F) << "black outside darkens the edge";
    EXPECT_NEAR(covAt(*white, 0, 100), 1.0F, 1e-3F) << "white outside keeps the edge solid";
    EXPECT_NEAR(covAt(*black, 150, 100), 1.0F, 1e-3F);
}

TEST(LayerMask, EditsRecomputeOnlyReachableTiles) {
    auto mask = maskOf(2048, 1024, [](int x, int y) { return inCircle(x, y) ? 1.0F : 0.0F; });
    std::string error;
    auto cache = lm::MaskCache::create(mask, {true, false, 1.0F, 12}, 0.0F, error);
    ASSERT_NE(cache, nullptr) << error;
    const auto initial_revision = cache->contentRevision();
    for (int ty = 0; ty < 4; ++ty)
        for (int tx = 0; tx < 8; ++tx) cache->tile({tx, ty});
    EXPECT_EQ(cache->computedTiles(), 32U);
    // Paint a small dab in the middle of tile (5, 2).
    const auto edited = maskOf(2048, 1024, [](int x, int y) {
        if ((x - 1400) * (x - 1400) + (y - 640) * (y - 640) < 20 * 20) return 1.0F;
        return inCircle(x, y) ? 1.0F : 0.0F;
    });
    ASSERT_TRUE(cache->update(edited, {1380, 620, 1420, 660}, error)) << error;
    const auto edited_revision = cache->contentRevision();
    EXPECT_NE(edited_revision, initial_revision);
    for (int ty = 0; ty < 4; ++ty)
        for (int tx = 0; tx < 8; ++tx) cache->tile({tx, ty});
    EXPECT_EQ(cache->computedTiles(), 33U) << "only the edited tile is recomputed (the dab and its feather stay inside it)";
    // Incremental result equals a fresh cache of the edited mask.
    auto fresh = lm::MaskCache::create(edited, {true, false, 1.0F, 12}, 0.0F, error);
    for (int y = 0; y < 1024; y += 5)
        for (int x = 0; x < 2048; x += 5) ASSERT_EQ(covAt(*cache, x, y), covAt(*fresh, x, y)) << x << ", " << y;
    // An edit near a tile corner reaches the neighbours within the feather footprint.
    ASSERT_TRUE(cache->update(edited, {1530, 505, 1534, 509}, error));
    for (int ty = 0; ty < 4; ++ty)
        for (int tx = 0; tx < 8; ++tx) cache->tile({tx, ty});
    EXPECT_EQ(cache->computedTiles(), 37U) << "the four tiles around the corner at (1536, 512)";
    const auto before_wrong_update = cache->contentRevision();
    TileStore wrong(PixelFormat::A16, 10, 10);
    EXPECT_FALSE(cache->update(wrong, {0, 0, 1, 1}, error));
    EXPECT_EQ(cache->contentRevision(), before_wrong_update);
}

TEST(LayerMask, ConcurrentRenderThreadsShareTheCache) {
    const auto mask = maskOf(1024, 1024, [](int x, int y) { return inCircle(x, y) ? 1.0F : 0.0F; });
    std::string error;
    auto cache = lm::MaskCache::create(mask, {true, false, 1.0F, 30}, 0.0F, error);
    ASSERT_NE(cache, nullptr) << error;
    std::vector<std::thread> threads;
    for (int t = 0; t < 4; ++t)
        threads.emplace_back([&cache] {
            for (int ty = 0; ty < 4; ++ty)
                for (int tx = 0; tx < 4; ++tx) EXPECT_NE(cache->tile({tx, ty}), nullptr);
        });
    for (auto& t : threads) t.join();
    EXPECT_EQ(cache->cachedTiles(), 16U);
    EXPECT_GE(cache->computedTiles(), 16U);
}

TEST(LayerMask, CoverageAndClippingMultiplyAlpha) {
    TileBuffer layer(256 * 256 * 4, 0), base(256 * 256 * 4, 0), coverage(256 * 256 * 2, 0);
    const std::uint8_t px[4] = {200, 100, 50, 255};
    for (std::size_t i = 0; i < 256 * 256; ++i) std::memcpy(layer.data() + i * 4, px, 4);
    const std::uint16_t half = 32768;
    std::memcpy(coverage.data(), &half, 2);  // pixel 0 half covered
    std::memset(coverage.data() + 2, 0xFF, coverage.size() - 2);
    lm::applyCoverage(PixelFormat::RGBA8, layer, coverage);
    EXPECT_EQ(layer[3], 128);
    EXPECT_EQ(layer[0], 200) << "straight colour untouched";
    EXPECT_EQ(layer[7], 255);
    base[4 + 3] = 64;  // base alpha 0.25 at pixel 1, transparent elsewhere
    lm::clipToBase(PixelFormat::RGBA8, layer, PixelFormat::RGBA8, &base);
    EXPECT_EQ(layer[3], 0);
    EXPECT_EQ(layer[7], 64);
    lm::clipToBase(PixelFormat::RGBA8, layer, PixelFormat::RGBA8, nullptr);
    EXPECT_EQ(layer[7], 0) << "no base: clipped away";
}

}  // namespace
