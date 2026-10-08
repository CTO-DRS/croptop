#include "selection_mask.h"
#include "native_tests_stress_gate.h"
#include "selection_quick.h"

#include <gtest/gtest.h>

#include <algorithm>
#include <chrono>
#include <cstdio>
#include <limits>
#include <string>
#include <vector>

namespace {

using croptop::PixelFormat;
using croptop::TileStore;
namespace sel = croptop::selection;

TEST(QuickSelectionSafety, RejectedPointBatchDoesNotMutateStroke) {
    const TileStore image(PixelFormat::RGBA8, 64, 64);
    std::string error;
    auto selector = sel::QuickSelector::begin(image, sel::emptyMask(64, 64), {8.0F, false, true}, error);
    ASSERT_NE(selector, nullptr) << error;

    const std::vector<float> invalid_batch = {32.0F, 32.0F, std::numeric_limits<float>::quiet_NaN(), 5.0F};
    EXPECT_FALSE(selector->add(invalid_batch, error));
    EXPECT_EQ(error, "points must be finite");
    EXPECT_TRUE(sel::isEmpty(selector->current()));
    const auto after_rejection = selector->finish(error);
    ASSERT_TRUE(after_rejection) << error;
    EXPECT_TRUE(sel::isEmpty(*after_rejection));

    // The session remains usable, and the rejected valid prefix is not replayed later.
    const std::vector<float> valid_batch = {10.0F, 10.0F};
    ASSERT_TRUE(selector->add(valid_batch, error)) << error;
    auto fresh = sel::QuickSelector::begin(image, sel::emptyMask(64, 64), {8.0F, false, true}, error);
    ASSERT_NE(fresh, nullptr) << error;
    ASSERT_TRUE(fresh->add(valid_batch, error)) << error;
    const auto actual = selector->finish(error);
    const auto expected = fresh->finish(error);
    ASSERT_TRUE(actual) << error;
    ASSERT_TRUE(expected) << error;
    for (std::int64_t y = 0; y < 64; ++y) {
        for (std::int64_t x = 0; x < 64; ++x) {
            EXPECT_EQ(sel::coverageAt(*actual, x, y), sel::coverageAt(*expected, x, y)) << "at " << x << ',' << y;
        }
    }
}

TEST(QuickSelectionSafety, FiniteExtremeCoordinatesAreSafeNoOps) {
    const TileStore image(PixelFormat::RGBA8, 64, 64);
    std::string error;
    auto selector = sel::QuickSelector::begin(image, sel::emptyMask(64, 64), {8.0F, false, false}, error);
    ASSERT_NE(selector, nullptr) << error;

    const auto extreme = std::numeric_limits<float>::max();
    const std::vector<float> positive = {extreme, 10.0F};
    const std::vector<float> negative = {-extreme, 10.0F};
    ASSERT_TRUE(selector->add(positive, error)) << error;
    ASSERT_TRUE(selector->add(negative, error)) << error;
    EXPECT_TRUE(sel::isEmpty(selector->current()));
}


/** Acceptance (host stand-in): one brush sample on a 24 MP photo-like layer (CROPTOP_STRESS=1). */
TEST(QuickSelectionSafety, BrushSampleTimingOn24Megapixels) {
    CROPTOP_REQUIRE_STRESS();
    const std::uint32_t w = 6000, h = 4000;
    croptop::TileStore image(croptop::PixelFormat::RGBA8, w, h);
    for (std::uint32_t ty = 0; ty * 256 < h; ++ty) {
        for (std::uint32_t tx = 0; tx * 256 < w; ++tx) {
            croptop::TileBuffer tile(image.tile_byte_size(), 0);
            for (std::uint32_t y = 0; y < 256; ++y) {
                for (std::uint32_t x = 0; x < 256; ++x) {
                    const auto gx = tx * 256 + x;
                    const auto gy = ty * 256 + y;
                    if (gx >= w || gy >= h) continue;
                    const std::uint8_t left = gx < w / 2 ? 200 : 40;
                    auto* px = tile.data() + (static_cast<std::size_t>(y) * 256 + x) * 4;
                    px[0] = static_cast<std::uint8_t>(left + (gx * 7 + gy * 3) % 9);
                    px[1] = static_cast<std::uint8_t>(left / 2 + (gx * 5 + gy) % 9);
                    px[2] = 90;
                    px[3] = 255;
                }
            }
            ASSERT_TRUE(image.writeTile({static_cast<std::int32_t>(tx), static_cast<std::int32_t>(ty)}, tile));
        }
    }
    std::string error;
    auto selector = sel::QuickSelector::begin(image, sel::emptyMask(w, h), {60.0F, false, false}, error);
    ASSERT_TRUE(selector) << error;
    double worst = 0;
    for (int i = 0; i < 6; ++i) {
        const float point[2] = {1000.0F + 40.0F * static_cast<float>(i), 2000.0F};
        const auto start = std::chrono::steady_clock::now();
        ASSERT_TRUE(selector->add(point, error)) << error;
        worst = std::max(worst, std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - start).count());
    }
    std::printf("[quick select 24MP] worst brush sample %.1f ms\n", worst);
    EXPECT_LT(worst, 50.0) << "host Release timing; the reference device is the acceptance target";
}

}  // namespace
