#include "image_metrics.h"
#include "blend_modes.h"
#include "image_decoder.h"
#include "job_system.h"
#include "render_graph.h"
#include "scratch_store.h"
#include "tile_store.h"

#include <gtest/gtest.h>
#include <jpeglib.h>
#include <png.h>

#include <algorithm>
#include <array>
#include <atomic>
#include <barrier>
#include <chrono>
#include <condition_variable>
#include <cmath>
#include <cstdlib>
#include <cstring>
#include <filesystem>
#include <fstream>
#include <limits>
#include <mutex>
#include <random>
#include <stdexcept>
#include <tuple>
#include <thread>
#include <vector>

namespace {

TEST(JobPool, SchedulesHigherPriorityBeforeOlderQueuedWork) {
    croptop::JobPool pool(1, 4);
    std::mutex mutex;
    std::condition_variable changed;
    bool release = false;
    ASSERT_TRUE(pool.submit(croptop::JobPriority::Interactive, [&](const croptop::JobContext&) {
        std::unique_lock lock(mutex);
        changed.wait(lock, [&] { return release; });
    }));
    while (pool.pendingCount() != 0) std::this_thread::yield();
    std::vector<int> order;
    ASSERT_TRUE(pool.submit(croptop::JobPriority::Background, [&](const croptop::JobContext&) {
        std::lock_guard lock(mutex);
        order.push_back(1);
        changed.notify_all();
    }));
    ASSERT_TRUE(pool.submit(croptop::JobPriority::Visible, [&](const croptop::JobContext&) {
        std::lock_guard lock(mutex);
        order.push_back(2);
        changed.notify_all();
    }));
    {
        std::lock_guard lock(mutex);
        release = true;
    }
    changed.notify_all();
    {
        std::unique_lock lock(mutex);
        ASSERT_TRUE(changed.wait_for(lock, std::chrono::seconds(2), [&] { return order.size() == 2; }));
    }
    EXPECT_EQ(order, (std::vector<int>{2, 1}));
}

TEST(JobPool, PromotesAgedBackgroundWorkAheadOfContinuousInteractiveArrivals) {
    croptop::JobPool pool(1, 16);
    std::mutex mutex;
    std::condition_variable changed;
    bool release = false;
    ASSERT_TRUE(pool.submit(croptop::JobPriority::Interactive, [&](const croptop::JobContext&) {
        std::unique_lock lock(mutex);
        changed.wait(lock, [&] { return release; });
    }));
    while (pool.pendingCount() != 0) std::this_thread::yield();

    std::vector<int> order;
    ASSERT_TRUE(pool.submit(croptop::JobPriority::Background, [&](const croptop::JobContext&) {
        std::lock_guard lock(mutex);
        order.push_back(0);
        changed.notify_all();
    }));
    std::this_thread::sleep_for(std::chrono::milliseconds(350));
    for (int index = 1; index <= 8; ++index) {
        ASSERT_TRUE(pool.submit(croptop::JobPriority::Interactive, [&, index](const croptop::JobContext&) {
            std::lock_guard lock(mutex);
            order.push_back(index);
            changed.notify_all();
        }));
    }
    {
        std::lock_guard lock(mutex);
        release = true;
    }
    changed.notify_all();
    {
        std::unique_lock lock(mutex);
        ASSERT_TRUE(changed.wait_for(lock, std::chrono::seconds(2), [&] { return order.size() == 9; }));
    }
    ASSERT_FALSE(order.empty());
    EXPECT_EQ(order.front(), 0);
}

TEST(JobPool, RejectsEqualPriorityAtCapacityAndReusesCancelledSlot) {
    croptop::JobPool pool(1, 1);
    std::mutex mutex;
    std::condition_variable changed;
    bool release = false;
    ASSERT_TRUE(pool.submit(croptop::JobPriority::Interactive, [&](const croptop::JobContext&) {
        std::unique_lock lock(mutex);
        changed.wait(lock, [&] { return release; });
    }));
    while (pool.pendingCount() != 0) std::this_thread::yield();
    std::atomic_int ran = 0;
    auto queued = pool.submit(croptop::JobPriority::Visible, [&](const croptop::JobContext&) { ++ran; });
    ASSERT_TRUE(queued);
    EXPECT_FALSE(pool.submit(croptop::JobPriority::Visible, [&](const croptop::JobContext&) { ++ran; }));
    queued->cancel();
    EXPECT_TRUE(pool.submit(croptop::JobPriority::Visible, [&](const croptop::JobContext&) { ++ran; }));
    {
        std::lock_guard lock(mutex);
        release = true;
    }
    changed.notify_all();
    for (int i = 0; i < 2000 && ran.load() != 1; ++i) std::this_thread::sleep_for(std::chrono::milliseconds(1));
    EXPECT_EQ(ran.load(), 1);
}

TEST(JobPool, VisibleWorkDisplacesQueuedBackgroundAtCapacity) {
    croptop::JobPool pool(1, 1);
    std::mutex mutex;
    std::condition_variable changed;
    bool release = false;
    ASSERT_TRUE(pool.submit(croptop::JobPriority::Interactive, [&](const croptop::JobContext&) {
        std::unique_lock lock(mutex);
        changed.wait(lock, [&] { return release; });
    }));
    while (pool.pendingCount() != 0) std::this_thread::yield();
    std::atomic_int background_ran = 0;
    std::atomic_int visible_ran = 0;
    auto background = pool.submit(croptop::JobPriority::Background, [&](const croptop::JobContext&) { ++background_ran; });
    ASSERT_TRUE(background);
    ASSERT_TRUE(pool.submit(croptop::JobPriority::Visible, [&](const croptop::JobContext&) { ++visible_ran; }));
    EXPECT_TRUE(background->cancellationRequested());
    {
        std::lock_guard lock(mutex);
        release = true;
    }
    changed.notify_all();
    for (int i = 0; i < 2000 && visible_ran.load() != 1; ++i) std::this_thread::sleep_for(std::chrono::milliseconds(1));
    EXPECT_EQ(background_ran.load(), 0);
    EXPECT_EQ(visible_ran.load(), 1);
}

TEST(JobPool, AgedBackgroundWorkIsNotEvictedByNewInteractiveWorkAtCapacity) {
    croptop::JobPool pool(1, 1);
    std::mutex mutex;
    std::condition_variable changed;
    bool release = false;
    ASSERT_TRUE(pool.submit(croptop::JobPriority::Interactive, [&](const croptop::JobContext&) {
        std::unique_lock lock(mutex);
        changed.wait(lock, [&] { return release; });
    }));
    while (pool.pendingCount() != 0) std::this_thread::yield();

    std::atomic_int background_ran = 0;
    std::atomic_int interactive_ran = 0;
    auto background = pool.submit(croptop::JobPriority::Background, [&](const croptop::JobContext&) { ++background_ran; });
    ASSERT_TRUE(background);
    std::this_thread::sleep_for(std::chrono::milliseconds(350));
    EXPECT_FALSE(pool.submit(croptop::JobPriority::Interactive, [&](const croptop::JobContext&) { ++interactive_ran; }));
    EXPECT_FALSE(background->cancellationRequested());

    {
        std::lock_guard lock(mutex);
        release = true;
    }
    changed.notify_all();
    for (int attempt = 0; attempt < 2000 && background_ran.load() != 1; ++attempt) {
        std::this_thread::sleep_for(std::chrono::milliseconds(1));
    }
    EXPECT_EQ(background_ran.load(), 1);
    EXPECT_EQ(interactive_ran.load(), 0);
}

TEST(JobPool, AgedLocalWorkRunsDuringContinuousGlobalInteractiveTraffic) {
    croptop::JobPool pool(2, 1024);
    std::mutex mutex;
    std::condition_variable changed;
    bool blocker_entered = false;
    bool release_blocker = false;
    bool parent_waiting = false;
    std::atomic_bool local_ran = false;
    std::atomic_bool parent_finished = false;
    std::atomic_bool blocker_finished = false;

    ASSERT_TRUE(pool.submit(croptop::JobPriority::Interactive, [&](const croptop::JobContext&) {
        {
            std::unique_lock lock(mutex);
            blocker_entered = true;
            changed.notify_all();
            changed.wait(lock, [&] { return release_blocker; });
        }
        blocker_finished.store(true, std::memory_order_release);
    }));
    {
        std::unique_lock lock(mutex);
        ASSERT_TRUE(changed.wait_for(lock, std::chrono::seconds(2), [&] { return blocker_entered; }));
    }
    ASSERT_TRUE(pool.submit(croptop::JobPriority::Interactive, [&](const croptop::JobContext&) {
        const auto local = pool.submit(croptop::JobPriority::Background, [&](const croptop::JobContext&) {
            {
                std::lock_guard lock(mutex);
                local_ran.store(true, std::memory_order_release);
                changed.notify_all();
            }
        });
        {
            std::lock_guard lock(mutex);
            parent_waiting = local.has_value();
        }
        changed.notify_all();
        if (!local) return;
        {
            std::unique_lock lock(mutex);
            changed.wait_for(lock, std::chrono::seconds(3), [&] { return local_ran.load(std::memory_order_acquire); });
        }
        parent_finished.store(true, std::memory_order_release);
    }));
    {
        std::unique_lock lock(mutex);
        ASSERT_TRUE(changed.wait_for(lock, std::chrono::seconds(2), [&] { return parent_waiting; }));
    }

    std::atomic_bool keep_submitting = true;
    std::thread producer([&] {
        while (keep_submitting.load(std::memory_order_acquire) && !local_ran.load(std::memory_order_acquire)) {
            (void)pool.submit(croptop::JobPriority::Interactive, [](const croptop::JobContext&) {
                std::this_thread::sleep_for(std::chrono::milliseconds(3));
            });
            std::this_thread::sleep_for(std::chrono::milliseconds(1));
        }
    });
    for (int attempt = 0; attempt < 2000 && pool.pendingCount() < 32; ++attempt) {
        std::this_thread::sleep_for(std::chrono::milliseconds(1));
    }
    EXPECT_GE(pool.pendingCount(), 32U);
    std::this_thread::sleep_for(std::chrono::milliseconds(350));
    {
        std::lock_guard lock(mutex);
        release_blocker = true;
    }
    changed.notify_all();
    {
        std::unique_lock lock(mutex);
        EXPECT_TRUE(changed.wait_for(lock, std::chrono::seconds(2), [&] {
            return local_ran.load(std::memory_order_acquire);
        }));
    }
    keep_submitting.store(false, std::memory_order_release);
    producer.join();
    for (int attempt = 0; attempt < 2000 &&
         (!parent_finished.load(std::memory_order_acquire) || !blocker_finished.load(std::memory_order_acquire)); ++attempt) {
        std::this_thread::sleep_for(std::chrono::milliseconds(1));
    }
    EXPECT_TRUE(local_ran.load(std::memory_order_acquire));
    EXPECT_TRUE(parent_finished.load(std::memory_order_acquire));
    EXPECT_TRUE(blocker_finished.load(std::memory_order_acquire));
}

TEST(JobPool, IdleWorkerStealsNestedTasksWhileSubmittingWorkerWaits) {
    std::mutex mutex;
    std::condition_variable changed;
    std::size_t completed = 0;
    std::atomic_bool parent_completed{false};
    croptop::JobPool pool(2, 8);

    ASSERT_TRUE(pool.submit(croptop::JobPriority::Interactive, [&](const croptop::JobContext&) {
        for (int i = 0; i < 4; ++i) {
            const auto child = pool.submit(croptop::JobPriority::Visible, [&](const croptop::JobContext&) {
                {
                    std::lock_guard lock(mutex);
                    ++completed;
                }
                changed.notify_all();
            });
            if (!child) return;
        }

        std::unique_lock lock(mutex);
        const bool children_stolen = changed.wait_for(lock, std::chrono::seconds(3), [&] {
            return completed == 4;
        });
        parent_completed.store(children_stolen, std::memory_order_release);
    }));

    for (int i = 0; i < 3500 && !parent_completed.load(std::memory_order_acquire); ++i) {
        std::this_thread::sleep_for(std::chrono::milliseconds(1));
    }
    EXPECT_TRUE(parent_completed.load(std::memory_order_acquire));
    EXPECT_EQ(completed, 4U);
}

TEST(BlendModes, SourceOverHandlesTransparentPixelsAndLayerOpacity) {
    EXPECT_EQ(croptop::blendRgba8({12, 34, 56, 0}, {210, 180, 90, 255}, croptop::BlendMode::Normal),
              (croptop::BlendPixel8{210, 180, 90, 255}));
    EXPECT_EQ(croptop::blendRgba8({12, 34, 56, 255}, {210, 180, 90, 0}, croptop::BlendMode::Normal),
              (croptop::BlendPixel8{12, 34, 56, 255}));
    EXPECT_EQ(croptop::blendRgba8({0, 0, 0, 0}, {255, 128, 0, 255}, croptop::BlendMode::Normal, 0),
              (croptop::BlendPixel8{0, 0, 0, 0}));
    EXPECT_EQ(croptop::blendRgba8({0, 0, 0, 255}, {255, 0, 0, 128}, croptop::BlendMode::Normal),
              (croptop::BlendPixel8{128, 0, 0, 255}));
}

TEST(BlendModes, EveryPhotoshopModeProducesBoundedOpaqueResult) {
    constexpr std::array modes{
        croptop::BlendMode::Normal, croptop::BlendMode::Dissolve, croptop::BlendMode::Darken,
        croptop::BlendMode::Multiply, croptop::BlendMode::ColorBurn, croptop::BlendMode::LinearBurn,
        croptop::BlendMode::DarkerColor, croptop::BlendMode::Lighten, croptop::BlendMode::Screen,
        croptop::BlendMode::ColorDodge, croptop::BlendMode::LinearDodge, croptop::BlendMode::LighterColor,
        croptop::BlendMode::Overlay, croptop::BlendMode::SoftLight, croptop::BlendMode::HardLight,
        croptop::BlendMode::VividLight, croptop::BlendMode::LinearLight, croptop::BlendMode::PinLight,
        croptop::BlendMode::HardMix, croptop::BlendMode::Difference, croptop::BlendMode::Exclusion,
        croptop::BlendMode::Subtract, croptop::BlendMode::Divide, croptop::BlendMode::Hue,
        croptop::BlendMode::Saturation, croptop::BlendMode::Color, croptop::BlendMode::Luminosity,
    };
    for (const auto mode : modes) {
        const auto result = croptop::blendRgba8({70, 145, 210, 255}, {190, 80, 45, 255}, mode);
        EXPECT_EQ(result[3], 255) << static_cast<int>(mode);
    }
}

TEST(BlendModes, SeparableReferenceFormulasMatchExactEightBitSamples) {
    const croptop::BlendPixel8 backdrop{64, 128, 192, 255};
    const croptop::BlendPixel8 source{128, 64, 32, 255};
    EXPECT_EQ(croptop::blendRgba8(backdrop, source, croptop::BlendMode::Multiply),
              (croptop::BlendPixel8{32, 32, 24, 255}));
    EXPECT_EQ(croptop::blendRgba8(backdrop, source, croptop::BlendMode::Screen),
              (croptop::BlendPixel8{160, 160, 200, 255}));
    EXPECT_EQ(croptop::blendRgba8(backdrop, source, croptop::BlendMode::Difference),
              (croptop::BlendPixel8{64, 64, 160, 255}));
    EXPECT_EQ(croptop::blendRgba8(backdrop, source, croptop::BlendMode::LinearDodge),
              (croptop::BlendPixel8{192, 192, 224, 255}));
    EXPECT_EQ(croptop::blendRgba8(backdrop, source, croptop::BlendMode::Subtract),
              (croptop::BlendPixel8{0, 64, 160, 255}));
}

TEST(BlendModes, EightBitReferenceSupportsGammaCompatibleAndLinearLightMath) {
    const croptop::BlendPixel8 mid_gray{128, 128, 128, 255};
    const auto gamma = croptop::blendRgba8(mid_gray, mid_gray, croptop::BlendMode::Multiply);
    const auto linear = croptop::blendRgba8(mid_gray, mid_gray, croptop::BlendMode::Multiply,
        255, 0, 0, 0, croptop::BlendSpace::LinearLight);
    EXPECT_EQ(gamma[0], 64);
    EXPECT_EQ(linear[0], 61);
    EXPECT_LT(linear[0], gamma[0]);
    EXPECT_EQ(linear[3], 255);
}

TEST(BlendModes, FloatReferencePreservesSelectedBlendSpaceAndSanitizesNonFiniteInput) {
    const auto gamma = croptop::blendRgbaF({0.5F, 0.5F, 0.5F, 1.0F},
                                          {0.5F, 0.5F, 0.5F, 1.0F}, croptop::BlendMode::Multiply);
    const auto linear = croptop::blendRgbaF({0.214041F, 0.214041F, 0.214041F, 1.0F},
                                           {0.214041F, 0.214041F, 0.214041F, 1.0F}, croptop::BlendMode::Multiply);
    EXPECT_NEAR(gamma[0], 0.25F, 1e-6F);
    EXPECT_NEAR(linear[0], 0.045814F, 1e-5F);
    const auto sanitized = croptop::blendRgbaF(
        {std::numeric_limits<float>::quiet_NaN(), 0.0F, 0.0F, 1.0F},
        {1.0F, 0.0F, 0.0F, 1.0F}, croptop::BlendMode::Normal);
    EXPECT_TRUE(std::isfinite(sanitized[0]));
    EXPECT_GE(sanitized[0], 0.0F);
    EXPECT_LE(sanitized[0], 1.0F);
}

TEST(BlendModes, FloatNormalBlendPreservesExtendedRangeHighlights) {
    const auto result = croptop::blendRgbaF({2.0F, 0.5F, 0.0F, 1.0F},
                                            {4.0F, 0.5F, 0.0F, 0.5F},
                                            croptop::BlendMode::Normal, 0.5F);
    EXPECT_NEAR(result[0], 2.5F, 1e-6F);
    EXPECT_NEAR(result[1], 0.5F, 1e-6F);
    EXPECT_NEAR(result[2], 0.0F, 1e-6F);
    EXPECT_EQ(result[3], 1.0F);
}

TEST(BlendModes, AllModesRemainFiniteAtBlackWhiteAndAlphaEdges) {
    constexpr std::array modes{
        croptop::BlendMode::Normal, croptop::BlendMode::Dissolve, croptop::BlendMode::Darken,
        croptop::BlendMode::Multiply, croptop::BlendMode::ColorBurn, croptop::BlendMode::LinearBurn,
        croptop::BlendMode::DarkerColor, croptop::BlendMode::Lighten, croptop::BlendMode::Screen,
        croptop::BlendMode::ColorDodge, croptop::BlendMode::LinearDodge, croptop::BlendMode::LighterColor,
        croptop::BlendMode::Overlay, croptop::BlendMode::SoftLight, croptop::BlendMode::HardLight,
        croptop::BlendMode::VividLight, croptop::BlendMode::LinearLight, croptop::BlendMode::PinLight,
        croptop::BlendMode::HardMix, croptop::BlendMode::Difference, croptop::BlendMode::Exclusion,
        croptop::BlendMode::Subtract, croptop::BlendMode::Divide, croptop::BlendMode::Hue,
        croptop::BlendMode::Saturation, croptop::BlendMode::Color, croptop::BlendMode::Luminosity,
    };
    constexpr std::array values{0.0F, 0.001F, 0.5F, 0.999F, 1.0F};
    constexpr std::array alphas{0.0F, 0.5F, 1.0F};
    for (const auto mode : modes) {
        for (const auto backdrop : values) {
            for (const auto source : values) {
                for (const auto alpha : alphas) {
                    const auto result = croptop::blendRgbaF(
                        {backdrop, 1.0F - backdrop, 0.5F, 1.0F},
                        {source, 0.5F, 1.0F - source, alpha}, mode, 0.75F);
                    for (const auto channel : result) {
                        EXPECT_TRUE(std::isfinite(channel)) << static_cast<int>(mode);
                        EXPECT_GE(channel, 0.0F);
                        EXPECT_LE(channel, 1.0F);
                    }
                }
            }
        }
    }
}

TEST(BlendModes, DissolveUsesStablePositionDependentCoverage) {
    const croptop::BlendPixel8 backdrop{0, 0, 0, 0};
    const croptop::BlendPixel8 source{255, 80, 20, 128};
    const auto selected = croptop::blendRgba8(backdrop, source, croptop::BlendMode::Dissolve, 255, 122, 0, 7);
    const auto rejected = croptop::blendRgba8(backdrop, source, croptop::BlendMode::Dissolve, 255, 124, 0, 7);
    EXPECT_EQ(selected, (croptop::BlendPixel8{255, 80, 20, 255}));
    EXPECT_EQ(rejected, backdrop);
    EXPECT_EQ(croptop::blendRgba8(backdrop, source, croptop::BlendMode::Dissolve, 255, 122, 0, 7), selected);
    EXPECT_EQ(croptop::blendRgbaF({0, 0, 0, 0}, {1, 0.3F, 0.1F, 0.5F},
                                 croptop::BlendMode::Dissolve, 1.0F, 122, 0, 7),
              (croptop::BlendPixelF{1, 0.3F, 0.1F, 1}));
}

TEST(CompositorReference, CompositesVisibleLayerTilesBottomToTop) {
    const std::array<croptop::BlendPixel8, 2> base{{{20, 40, 60, 255}, {100, 120, 140, 255}}};
    const std::array<croptop::BlendPixel8, 2> top{{{200, 100, 50, 255}, {80, 60, 40, 0}}};
    const std::array<croptop::Rgba8LayerTile, 2> layers{{
        {base, croptop::BlendMode::Normal, 255, 255, true},
        {top, croptop::BlendMode::Multiply, 255, 255, true},
    }};
    std::array<croptop::BlendPixel8, 2> output{};
    ASSERT_TRUE(croptop::compositeRgba8Tile(layers, output));
    EXPECT_EQ(output[0], (croptop::BlendPixel8{16, 16, 12, 255}));
    EXPECT_EQ(output[1], base[1]);
}

TEST(CompositorReference, RejectsMismatchedVisibleTileWithoutChangingOutput) {
    const std::array<croptop::BlendPixel8, 1> source{{{1, 2, 3, 255}}};
    const std::array<croptop::Rgba8LayerTile, 1> layers{{
        {source, croptop::BlendMode::Normal, 255, 255, true},
    }};
    std::array<croptop::BlendPixel8, 2> output{{{9, 8, 7, 6}, {5, 4, 3, 2}}};
    EXPECT_FALSE(croptop::compositeRgba8Tile(layers, output));
    EXPECT_EQ(output[0], (croptop::BlendPixel8{9, 8, 7, 6}));
    EXPECT_EQ(output[1], (croptop::BlendPixel8{5, 4, 3, 2}));
}

TEST(RenderGraph, CachesLayerOutputsAndRecomputesDirtyTiles) {
    croptop::TileStore base(croptop::PixelFormat::RGBA8, 1, 1);
    croptop::TileStore top(croptop::PixelFormat::RGBA8, 1, 1);
    croptop::TileBuffer base_pixels(base.tile_byte_size(), 0);
    croptop::TileBuffer top_pixels(top.tile_byte_size(), 0);
    base_pixels[0] = 20; base_pixels[1] = 40; base_pixels[2] = 60; base_pixels[3] = 255;
    top_pixels[0] = 200; top_pixels[1] = 100; top_pixels[2] = 50; top_pixels[3] = 255;
    ASSERT_TRUE(base.writeTile({0, 0}, base_pixels));
    ASSERT_TRUE(top.writeTile({0, 0}, top_pixels));

    croptop::RenderGraph graph;
    ASSERT_TRUE(graph.setLayers({
        {1, &base, croptop::BlendMode::Normal, 255, 255, true},
        {2, &top, croptop::BlendMode::Multiply, 255, 255, true},
    }, croptop::BlendSpace::GammaEncoded));
    const auto first = graph.renderTile({0, 0});
    ASSERT_NE(first, nullptr);
    EXPECT_EQ((*first)[0], 16);
    EXPECT_EQ((*first)[1], 16);
    EXPECT_EQ((*first)[2], 12);
    EXPECT_EQ(graph.cachedTileCount(), 2U);
    EXPECT_EQ(graph.renderTile({0, 0}), first);

    top_pixels[0] = 64;
    ASSERT_TRUE(top.writeTile({0, 0}, top_pixels));
    graph.invalidate({0, 0, 1, 1});
    const auto updated = graph.renderTile({0, 0});
    ASSERT_NE(updated, nullptr);
    EXPECT_NE(updated, first);
    EXPECT_EQ((*updated)[0], 5);
}

TEST(RenderGraph, ReusesUnchangedLowerPrefixWhenUpperLayerChanges) {
    croptop::TileStore base(croptop::PixelFormat::RGBA8, 1, 1);
    croptop::TileStore top(croptop::PixelFormat::RGBA8, 1, 1);
    croptop::TileBuffer base_pixels(base.tile_byte_size(), 0);
    croptop::TileBuffer top_pixels(top.tile_byte_size(), 0);
    base_pixels[0] = 90; base_pixels[3] = 255;
    top_pixels[2] = 80; top_pixels[3] = 128;
    ASSERT_TRUE(base.writeTile({0, 0}, base_pixels));
    ASSERT_TRUE(top.writeTile({0, 0}, top_pixels));

    croptop::RenderGraph graph;
    std::vector<croptop::RenderLayer> layers{
        {1, &base, croptop::BlendMode::Normal, 255, 255, true},
        {2, &top, croptop::BlendMode::Normal, 255, 255, true},
    };
    ASSERT_TRUE(graph.setLayers(layers, croptop::BlendSpace::GammaEncoded));
    ASSERT_NE(graph.renderTile({0, 0}), nullptr);
    ASSERT_EQ(graph.cachedTileCount(), 2U);
    const auto hits_before = graph.cachedOutputHits();

    top_pixels[2] = 140;
    ASSERT_TRUE(top.writeTile({0, 0}, top_pixels));
    ASSERT_TRUE(graph.setLayersRetainingCache(layers, croptop::BlendSpace::GammaEncoded));
    const auto updated = graph.renderTile({0, 0});
    ASSERT_NE(updated, nullptr);
    EXPECT_EQ((*updated)[0], 45);
    EXPECT_EQ((*updated)[2], 70);
    EXPECT_GT(graph.cachedOutputHits(), hits_before);
}

TEST(RenderGraph, RetainsCrossRequestTilesAndRevalidatesContentAndLayerProperties) {
    croptop::TileStore pixels(croptop::PixelFormat::RGBA8, 1, 1);
    croptop::TileBuffer tile(pixels.tile_byte_size(), 0);
    tile[0] = 40;
    tile[3] = 255;
    ASSERT_TRUE(pixels.writeTile({0, 0}, tile));

    croptop::RenderGraph graph;
    croptop::RenderLayer layer{1, &pixels, croptop::BlendMode::Normal, 255, 255, true};
    ASSERT_TRUE(graph.setLayers({layer}, croptop::BlendSpace::GammaEncoded));
    const auto original = graph.renderTile({0, 0});
    ASSERT_NE(original, nullptr);
    EXPECT_EQ((*original)[0], 40);
    EXPECT_EQ(graph.cachedOutputHits(), 0U);

    ASSERT_TRUE(graph.setLayersRetainingCache({layer}, croptop::BlendSpace::GammaEncoded));
    EXPECT_EQ(graph.renderTile({0, 0}), original);
    EXPECT_EQ(graph.cachedOutputHits(), 1U);

    tile[0] = 90;
    ASSERT_TRUE(pixels.writeTile({0, 0}, tile));
    ASSERT_TRUE(graph.setLayersRetainingCache({layer}, croptop::BlendSpace::GammaEncoded));
    const auto updated_pixels = graph.renderTile({0, 0});
    ASSERT_NE(updated_pixels, nullptr);
    EXPECT_NE(updated_pixels, original);
    EXPECT_EQ((*updated_pixels)[0], 90);

    layer.visible = false;
    ASSERT_TRUE(graph.setLayersRetainingCache({layer}, croptop::BlendSpace::GammaEncoded));
    const auto updated_properties = graph.renderTile({0, 0});
    ASSERT_NE(updated_properties, nullptr);
    EXPECT_EQ((*updated_properties)[3], 0);
}

TEST(RenderGraph, RetainedCacheRevisionIncludesBlendSpace) {
    croptop::TileStore base(croptop::PixelFormat::RGBA8, 1, 1);
    croptop::TileStore overlay(croptop::PixelFormat::RGBA8, 1, 1);
    croptop::TileBuffer base_pixel(base.tile_byte_size(), 0);
    croptop::TileBuffer overlay_pixel(overlay.tile_byte_size(), 0);
    base_pixel[0] = 128; base_pixel[3] = 255;
    overlay_pixel[2] = 255; overlay_pixel[3] = 128;
    ASSERT_TRUE(base.writeTile({0, 0}, base_pixel));
    ASSERT_TRUE(overlay.writeTile({0, 0}, overlay_pixel));

    croptop::RenderGraph graph;
    ASSERT_TRUE(graph.setLayers({{1, &base}, {2, &overlay}}, croptop::BlendSpace::GammaEncoded));
    const auto gamma = graph.renderTile({0, 0});
    ASSERT_NE(gamma, nullptr);
    ASSERT_TRUE(graph.setLayersRetainingCache({{1, &base}, {2, &overlay}}, croptop::BlendSpace::LinearLight));
    const auto linear = graph.renderTile({0, 0});
    ASSERT_NE(linear, nullptr);
    EXPECT_NE(linear, gamma);
    EXPECT_NE((*linear)[0], (*gamma)[0]);
}

TEST(RenderGraph, LayerBindingRetainsOwnedPixelSnapshotUntilReplacement) {
    auto pixels = std::make_shared<croptop::TileStore>(croptop::PixelFormat::RGBA8, 1, 1);
    croptop::TileBuffer pixel(pixels->tile_byte_size(), 0);
    pixel[0] = 73; pixel[3] = 255;
    ASSERT_TRUE(pixels->writeTile({0, 0}, pixel));
    std::weak_ptr<const croptop::TileStore> weak_pixels = pixels;

    croptop::RenderGraph graph;
    croptop::RenderLayer layer{1, pixels.get()};
    layer.pixels_owner = pixels;
    ASSERT_TRUE(graph.setLayers({layer}));
    pixels.reset();
    layer.pixels_owner.reset();
    EXPECT_FALSE(weak_pixels.expired());
    const auto output = graph.renderTile({0, 0});
    ASSERT_NE(output, nullptr);
    EXPECT_EQ((*output)[0], 73);

    ASSERT_TRUE(graph.setLayers({}));
    EXPECT_TRUE(weak_pixels.expired());
}

TEST(RenderGraph, BoundsRetainedOutputCacheAcrossManyTiles) {
    croptop::TileStore pixels(croptop::PixelFormat::RGBA8, 65U * croptop::kTileEdge, croptop::kTileEdge);
    croptop::RenderGraph graph;
    ASSERT_TRUE(graph.setLayers({{1, &pixels, croptop::BlendMode::Normal, 255, 255, true}}));

    for (std::int32_t tile_x = 0; tile_x < 65; ++tile_x) {
        ASSERT_NE(graph.renderTile({tile_x, 0}), nullptr);
        EXPECT_LE(graph.cachedTileCount(), 64U);
    }
    EXPECT_EQ(graph.cachedTileCount(), 64U);
    for (std::int32_t tile_x = 0; tile_x < 65; ++tile_x) {
        ASSERT_NE(graph.renderTile({tile_x, 0}), nullptr);
        EXPECT_LE(graph.cachedTileCount(), 64U);
    }
    EXPECT_EQ(graph.cachedTileCount(), 64U);
}

TEST(RenderGraph, TileStoreMutationChangesCachedOutputWithoutExplicitInvalidation) {
    croptop::TileStore pixels(croptop::PixelFormat::RGBA8, 512, 256);
    croptop::TileBuffer tile(pixels.tile_byte_size(), 0);
    tile[0] = 40; tile[3] = 255;
    ASSERT_TRUE(pixels.writeTile({0, 0}, tile));
    tile[0] = 90;
    ASSERT_TRUE(pixels.writeTile({1, 0}, tile));

    croptop::RenderGraph graph;
    ASSERT_TRUE(graph.setLayers({{1, &pixels, croptop::BlendMode::Normal, 255, 255, true}},
                                croptop::BlendSpace::GammaEncoded));
    const auto first = graph.renderTile({0, 0});
    ASSERT_NE(first, nullptr);
    EXPECT_EQ((*first)[0], 40);
    const auto unaffected = graph.renderTile({1, 0});
    ASSERT_NE(unaffected, nullptr);
    EXPECT_EQ((*unaffected)[0], 90);

    tile[0] = 180;
    ASSERT_TRUE(pixels.writeTile({0, 0}, tile));
    const auto updated = graph.renderTile({0, 0});
    ASSERT_NE(updated, nullptr);
    EXPECT_NE(updated, first);
    EXPECT_EQ((*updated)[0], 180);
    EXPECT_EQ(graph.renderTile({1, 0}), unaffected);
    EXPECT_EQ(graph.cachedTileCount(), 2U);
}

TEST(RenderGraph, UsesGpuStackForVisibleUnmaskedRgba8TileAndPreservesDocumentOrigin) {
    croptop::TileStore base(croptop::PixelFormat::RGBA8, 300, 4);
    croptop::TileStore top(croptop::PixelFormat::RGBA8, 300, 4);
    croptop::TileBuffer base_pixels(base.tile_byte_size(), 0);
    croptop::TileBuffer top_pixels(top.tile_byte_size(), 0);
    base_pixels[0] = 20; base_pixels[1] = 40; base_pixels[2] = 60; base_pixels[3] = 255;
    top_pixels[0] = 200; top_pixels[1] = 100; top_pixels[2] = 50; top_pixels[3] = 255;
    ASSERT_TRUE(base.writeTile({1, 0}, base_pixels));
    ASSERT_TRUE(top.writeTile({1, 0}, top_pixels));

    croptop::RenderGraph graph;
    ASSERT_TRUE(graph.setLayers({
        {1, &base, croptop::BlendMode::Normal, 255, 255, true},
        {2, &top, croptop::BlendMode::Multiply, 128, 255, true},
    }, croptop::BlendSpace::LinearLight));
    int invocations = 0;
    graph.setGpuBlendStack([&](std::span<const float> backdrop, std::span<const float> sources,
                               std::span<const croptop::GpuBlendLayer> layers, std::span<float> output,
                               std::uint32_t width, std::uint32_t height, bool linear_light,
                               std::uint32_t origin_x, std::uint32_t origin_y,
                               std::uint32_t coordinate_scale, std::uint32_t precision_bits) {
        ++invocations;
        EXPECT_EQ(backdrop.size(), static_cast<std::size_t>(256 * 256 * 4));
        EXPECT_EQ(sources.size(), static_cast<std::size_t>(layers.size() * 256 * 256 * 4));
        EXPECT_EQ(output.size(), static_cast<std::size_t>(layers.size() * 256 * 256 * 4));
        EXPECT_EQ(layers.size(), invocations == 1 ? 2U : 1U);
        EXPECT_EQ(width, 256U);
        EXPECT_EQ(height, 256U);
        EXPECT_TRUE(linear_light);
        EXPECT_EQ(origin_x, 256U);
        EXPECT_EQ(origin_y, 0U);
        EXPECT_EQ(coordinate_scale, 1U);
        EXPECT_EQ(precision_bits, 8U);
        if (invocations == 1) {
            EXPECT_EQ(layers[0].mode, croptop::BlendMode::Normal);
            EXPECT_EQ(layers[1].mode, croptop::BlendMode::Multiply);
            EXPECT_NEAR(layers[1].opacity, 128.0F / 255.0F, 0.0001F);
            EXPECT_FLOAT_EQ(sources[0], 20.0F / 255.0F);
            EXPECT_FLOAT_EQ(sources[4U * 256U * 256U], 200.0F / 255.0F);
        } else {
            EXPECT_EQ(layers[0].mode, croptop::BlendMode::Multiply);
            EXPECT_FLOAT_EQ(sources[0], 100.0F / 255.0F);
        }
        for (std::size_t offset = 0; offset < output.size(); offset += 4U) {
            output[offset] = 0.25F;
            output[offset + 1U] = 0.5F;
            output[offset + 2U] = 0.75F;
            output[offset + 3U] = 1.0F;
        }
        return true;
    });
    const auto result = graph.renderTile({1, 0});
    ASSERT_NE(result, nullptr);
    EXPECT_EQ(invocations, 1);
    EXPECT_EQ((*result)[0], 64U);
    EXPECT_EQ((*result)[1], 128U);
    EXPECT_EQ((*result)[2], 191U);
    EXPECT_EQ((*result)[3], 255U);

    const auto hits_before = graph.cachedOutputHits();
    top_pixels[0] = 100;
    ASSERT_TRUE(top.writeTile({1, 0}, top_pixels));
    const auto updated = graph.renderTile({1, 0});
    ASSERT_NE(updated, nullptr);
    EXPECT_EQ(invocations, 2);
    EXPECT_GT(graph.cachedOutputHits(), hits_before);
    EXPECT_EQ(graph.cachedTileCount(), 2U);
}

TEST(RenderGraph, VirtualCanvasBackgroundComposesWithoutMaterializingLayerPixels) {
    croptop::TileStore pixels(croptop::PixelFormat::RGBA8, 512, 512);
    croptop::RenderGraph graph;
    ASSERT_TRUE(graph.setLayers({{1, &pixels, croptop::BlendMode::Normal, 255, 255, true}}));
    ASSERT_TRUE(graph.setBackground({1.0F, 1.0F, 1.0F, 1.0F}));

    const auto white = graph.renderTile({1, 1});
    ASSERT_NE(white, nullptr);
    EXPECT_EQ((*white)[0], 255);
    EXPECT_EQ((*white)[1], 255);
    EXPECT_EQ((*white)[2], 255);
    EXPECT_EQ((*white)[3], 255);
    EXPECT_EQ(pixels.tile_count(), 0U);

    ASSERT_TRUE(graph.setBackground({0.0F, 0.0F, 0.0F, 0.0F}));
    const auto transparent = graph.renderTile({0, 0});
    ASSERT_NE(transparent, nullptr);
    EXPECT_EQ((*transparent)[3], 0);
    EXPECT_EQ(pixels.tile_count(), 0U);
    EXPECT_FALSE(graph.setBackground({std::numeric_limits<float>::quiet_NaN(), 0.0F, 0.0F, 1.0F}));
}

TEST(RenderGraph, ComposesAtMipLevelAndInvalidatesTheMatchingReducedTile) {
    croptop::TileStore base(croptop::PixelFormat::RGBA8, 256, 256);
    croptop::TileStore top(croptop::PixelFormat::RGBA8, 256, 256);
    croptop::TileBuffer base_pixels(base.tile_byte_size(), 0);
    croptop::TileBuffer top_pixels(top.tile_byte_size(), 0);
    for (std::size_t pixel = 0; pixel < base_pixels.size(); pixel += 4U) {
        base_pixels[pixel] = 220;
        base_pixels[pixel + 1U] = 10;
        base_pixels[pixel + 2U] = 20;
        base_pixels[pixel + 3U] = 255;
        top_pixels[pixel] = 5;
        top_pixels[pixel + 1U] = 30;
        top_pixels[pixel + 2U] = 230;
        top_pixels[pixel + 3U] = 255;
    }
    ASSERT_TRUE(base.writeTile({0, 0}, base_pixels));
    ASSERT_TRUE(top.writeTile({0, 0}, top_pixels));

    croptop::RenderGraph graph;
    ASSERT_TRUE(graph.setLayers({
        {1, &base, croptop::BlendMode::Normal, 255, 255, true},
        {2, &top, croptop::BlendMode::Normal, 255, 255, true},
    }));
    const auto full_resolution = graph.renderTile({0, 0});
    const auto reduced = graph.renderTile({0, 0}, 1);
    ASSERT_NE(full_resolution, nullptr);
    ASSERT_NE(reduced, nullptr);
    EXPECT_EQ((*full_resolution)[0], 5);
    EXPECT_EQ((*full_resolution)[2], 230);
    EXPECT_EQ((*reduced)[0], 5);
    EXPECT_EQ((*reduced)[2], 230);
    EXPECT_EQ(graph.cachedTileCount(), 4U);

    for (std::size_t pixel = 0; pixel < top_pixels.size(); pixel += 4U) {
        top_pixels[pixel] = 10;
        top_pixels[pixel + 1U] = 220;
        top_pixels[pixel + 2U] = 30;
    }
    ASSERT_TRUE(top.writeTile({0, 0}, top_pixels));
    graph.invalidate({0, 0, 1, 1});
    const auto refreshed = graph.renderTile({0, 0}, 1);
    ASSERT_NE(refreshed, nullptr);
    EXPECT_NE(refreshed, reduced);
    EXPECT_EQ((*refreshed)[0], 10);
    EXPECT_EQ((*refreshed)[1], 220);
    EXPECT_EQ((*refreshed)[2], 30);
}

TEST(RenderGraph, ComposesAndCachesRgba32fTilesWithoutQuantizingToEightBits) {
    croptop::TileStore base(croptop::PixelFormat::RGBA32F, 1, 1);
    croptop::TileStore top(croptop::PixelFormat::RGBA32F, 1, 1);
    croptop::TileBuffer base_pixels(base.tile_byte_size(), 0);
    croptop::TileBuffer top_pixels(top.tile_byte_size(), 0);
    const croptop::BlendPixelF red{1.0F, 0.0F, 0.0F, 1.0F};
    const croptop::BlendPixelF half_blue{0.0F, 0.0F, 1.0F, 0.5F};
    std::memcpy(base_pixels.data(), red.data(), sizeof(red));
    std::memcpy(top_pixels.data(), half_blue.data(), sizeof(half_blue));
    ASSERT_TRUE(base.writeTile({0, 0}, base_pixels));
    ASSERT_TRUE(top.writeTile({0, 0}, top_pixels));

    croptop::RenderGraph graph;
    ASSERT_TRUE(graph.setLayers({
        {1, &base, croptop::BlendMode::Normal, 255, 255, true},
        {2, &top, croptop::BlendMode::Normal, 255, 255, true},
    }));
    const auto output = graph.renderTile({0, 0});
    ASSERT_NE(output, nullptr);
    croptop::BlendPixelF pixel{};
    std::memcpy(pixel.data(), output->data(), sizeof(pixel));
    EXPECT_NEAR(pixel[0], 0.5F, 1e-6F);
    EXPECT_NEAR(pixel[1], 0.0F, 1e-6F);
    EXPECT_NEAR(pixel[2], 0.5F, 1e-6F);
    EXPECT_NEAR(pixel[3], 1.0F, 1e-6F);
    EXPECT_EQ(graph.renderTile({0, 0}), output);
    EXPECT_EQ(graph.cachedTileCount(), 2U);
}

TEST(RenderGraph, ComposesRgba16LayersAtSixteenBitPrecision) {
    croptop::TileStore base(croptop::PixelFormat::RGBA16, 1, 1);
    croptop::TileStore top(croptop::PixelFormat::RGBA16, 1, 1);
    croptop::TileBuffer base_pixels(base.tile_byte_size(), 0);
    croptop::TileBuffer top_pixels(top.tile_byte_size(), 0);
    const std::array<std::uint16_t, 4> red{65535, 0, 0, 65535};
    const std::array<std::uint16_t, 4> half_blue{0, 0, 65535, 32768};
    std::memcpy(base_pixels.data(), red.data(), sizeof(red));
    std::memcpy(top_pixels.data(), half_blue.data(), sizeof(half_blue));
    ASSERT_TRUE(base.writeTile({0, 0}, base_pixels));
    ASSERT_TRUE(top.writeTile({0, 0}, top_pixels));

    croptop::RenderGraph graph;
    ASSERT_TRUE(graph.setLayers({
        {1, &base, croptop::BlendMode::Normal, 255, 255, true},
        {2, &top, croptop::BlendMode::Normal, 255, 255, true},
    }));
    const auto output = graph.renderTile({0, 0});
    ASSERT_NE(output, nullptr);
    std::array<std::uint16_t, 4> pixel{};
    std::memcpy(pixel.data(), output->data(), sizeof(pixel));
    EXPECT_NEAR(pixel[0], 32767, 1);
    EXPECT_NEAR(pixel[1], 0, 1);
    EXPECT_NEAR(pixel[2], 32768, 1);
    EXPECT_EQ(pixel[3], 65535);
}

TEST(RenderGraph, ComposesDistinctTilesOnConcurrentWorkers) {
    croptop::TileStore pixels(croptop::PixelFormat::RGBA8, 512, 256);
    croptop::TileBuffer left(pixels.tile_byte_size(), 0);
    croptop::TileBuffer right(pixels.tile_byte_size(), 0);
    left[0] = 30; left[3] = 255;
    right[0] = 90; right[3] = 255;
    ASSERT_TRUE(pixels.writeTile({0, 0}, left));
    ASSERT_TRUE(pixels.writeTile({1, 0}, right));
    croptop::RenderGraph graph;
    ASSERT_TRUE(graph.setLayers({{1, &pixels}}, croptop::BlendSpace::GammaEncoded));

    std::barrier start(3);
    std::shared_ptr<const croptop::TileBuffer> first;
    std::shared_ptr<const croptop::TileBuffer> second;
    std::thread a([&] { start.arrive_and_wait(); first = graph.renderTile({0, 0}); });
    std::thread b([&] { start.arrive_and_wait(); second = graph.renderTile({1, 0}); });
    start.arrive_and_wait();
    a.join();
    b.join();
    ASSERT_NE(first, nullptr);
    ASSERT_NE(second, nullptr);
    EXPECT_EQ((*first)[0], 30);
    EXPECT_EQ((*second)[0], 90);
    EXPECT_EQ(graph.cachedTileCount(), 2U);

    left[0] = 120;
    ASSERT_TRUE(pixels.writeTile({0, 0}, left));
    graph.invalidate({0, 0, 256, 256});
    const auto updated = graph.renderTile({0, 0});
    ASSERT_NE(updated, nullptr);
    EXPECT_EQ((*updated)[0], 120);
    EXPECT_EQ((*second)[0], 90);
}

TEST(RenderGraph, ConcurrentTileReplacementAndInvalidationRemainRaceFree) {
    croptop::TileStore pixels(croptop::PixelFormat::RGBA8, 256, 256);
    croptop::TileBuffer contents(pixels.tile_byte_size(), 0);
    contents[3] = 255;
    ASSERT_TRUE(pixels.writeTile({0, 0}, contents));
    croptop::RenderGraph graph;
    ASSERT_TRUE(graph.setLayers({{1, &pixels}}, croptop::BlendSpace::GammaEncoded));

    std::barrier start(2);
    std::atomic_bool writer_done = false;
    std::atomic_bool invalid_tile_size = false;
    std::thread writer([&] {
        start.arrive_and_wait();
        for (std::uint8_t value = 1; value <= 24; ++value) {
            croptop::TileBuffer next(pixels.tile_byte_size(), 0);
            next[0] = value;
            next[3] = 255;
            (void)pixels.writeTile({0, 0}, next);
            graph.invalidate({0, 0, 256, 256});
        }
        writer_done.store(true, std::memory_order_release);
    });
    std::thread renderer([&] {
        start.arrive_and_wait();
        while (!writer_done.load(std::memory_order_acquire)) {
            const auto tile = graph.renderTile({0, 0});
            if (tile != nullptr && tile->size() != pixels.tile_byte_size()) {
                invalid_tile_size.store(true, std::memory_order_relaxed);
            }
        }
    });
    writer.join();
    renderer.join();
    EXPECT_FALSE(invalid_tile_size.load());

    const auto final = graph.renderTile({0, 0});
    ASSERT_NE(final, nullptr);
    EXPECT_EQ((*final)[0], 24);
}

TEST(RenderGraph, RejectsInvalidLayerSetAndClearsPreviousGraph) {
    croptop::TileStore first(croptop::PixelFormat::RGBA8, 2, 2);
    croptop::TileStore wrong_size(croptop::PixelFormat::RGBA8, 3, 2);
    croptop::RenderGraph graph;
    ASSERT_TRUE(graph.setLayers({{1, &first}}));
    EXPECT_FALSE(graph.setLayers({{1, &first}, {2, &wrong_size}}));
    EXPECT_NE(graph.renderTile({0, 0}), nullptr);
}

std::vector<std::uint8_t> encodeTestPng(std::uint32_t width, std::uint32_t height,
                                       const std::vector<std::uint8_t>& rgba) {
    png_image image{};
    image.version = PNG_IMAGE_VERSION;
    image.width = width;
    image.height = height;
    image.format = PNG_FORMAT_RGBA;
    png_alloc_size_t length = 0;
    if (png_image_write_to_memory(&image, nullptr, &length, 0, rgba.data(), 0, nullptr) == 0) return {};
    std::vector<std::uint8_t> encoded(length);
    if (png_image_write_to_memory(&image, encoded.data(), &length, 0, rgba.data(), 0, nullptr) == 0) return {};
    encoded.resize(length);
    return encoded;
}

std::vector<std::uint8_t> encodeTestPng16(std::uint32_t width, std::uint32_t height,
                                         const std::vector<std::uint16_t>& rgba) {
    png_structp png = png_create_write_struct(PNG_LIBPNG_VER_STRING, nullptr, nullptr, nullptr);
    if (png == nullptr) return {};
    png_infop info = png_create_info_struct(png);
    if (info == nullptr) {
        png_destroy_write_struct(&png, nullptr);
        return {};
    }
    std::vector<std::uint8_t> encoded;
    if (setjmp(png_jmpbuf(png)) != 0) {
        png_destroy_write_struct(&png, &info);
        return {};
    }
    png_set_write_fn(
        png, &encoded,
        [](png_structp writer, png_bytep data, png_size_t length) {
            auto* bytes = static_cast<std::vector<std::uint8_t>*>(png_get_io_ptr(writer));
            bytes->insert(bytes->end(), data, data + length);
        },
        [](png_structp) {});
    png_set_IHDR(png, info, width, height, 16, PNG_COLOR_TYPE_RGBA, PNG_INTERLACE_NONE, PNG_COMPRESSION_TYPE_DEFAULT,
                 PNG_FILTER_TYPE_DEFAULT);
    png_write_info(png, info);
    png_set_swap(png);
    for (std::uint32_t y = 0; y < height; ++y) {
        auto* row = reinterpret_cast<png_bytep>(const_cast<std::uint16_t*>(rgba.data() + y * width * 4U));
        png_write_row(png, row);
    }
    png_write_end(png, info);
    png_destroy_write_struct(&png, &info);
    return encoded;
}

std::vector<std::uint8_t> encodeTestJpeg(std::uint32_t width, std::uint32_t height,
                                         const std::vector<std::uint8_t>& rgb) {
    jpeg_compress_struct compressor{};
    jpeg_error_mgr error{};
    compressor.err = jpeg_std_error(&error);
    jpeg_create_compress(&compressor);
    unsigned char* encoded = nullptr;
    unsigned long length = 0;
    jpeg_mem_dest(&compressor, &encoded, &length);
    compressor.image_width = width;
    compressor.image_height = height;
    compressor.input_components = 3;
    compressor.in_color_space = JCS_RGB;
    jpeg_set_defaults(&compressor);
    jpeg_set_quality(&compressor, 100, TRUE);
    jpeg_start_compress(&compressor, TRUE);
    while (compressor.next_scanline < compressor.image_height) {
        auto* row = const_cast<JSAMPLE*>(rgb.data() + compressor.next_scanline * width * 3U);
        JSAMPROW rows[] = {row};
        jpeg_write_scanlines(&compressor, rows, 1);
    }
    jpeg_finish_compress(&compressor);
    std::vector<std::uint8_t> result(encoded, encoded + length);
    std::free(encoded);
    jpeg_destroy_compress(&compressor);
    return result;
}

std::vector<std::uint8_t> makeTestBmp24() {
    std::vector<std::uint8_t> bmp(54U + 16U, 0);
    auto put16 = [&](std::size_t offset, std::uint16_t value) {
        bmp[offset] = static_cast<std::uint8_t>(value);
        bmp[offset + 1U] = static_cast<std::uint8_t>(value >> 8U);
    };
    auto put32 = [&](std::size_t offset, std::uint32_t value) {
        for (std::size_t i = 0; i < 4; ++i) bmp[offset + i] = static_cast<std::uint8_t>(value >> (i * 8U));
    };
    bmp[0] = 'B';
    bmp[1] = 'M';
    put32(2, static_cast<std::uint32_t>(bmp.size()));
    put32(10, 54);
    put32(14, 40);
    put32(18, 2);
    put32(22, 2);
    put16(26, 1);
    put16(28, 24);
    put32(34, 16);
    // Bottom-up BMP rows are BGR with two padding bytes per row.
    const std::array<std::uint8_t, 16> pixels{255, 0, 0, 255, 255, 255, 0, 0,
                                              0, 0, 255, 0, 255, 0, 0, 0};
    std::copy(pixels.begin(), pixels.end(), bmp.begin() + 54);
    return bmp;
}

std::vector<std::uint8_t> makeTestBmpRle8() {
    constexpr std::array<std::uint8_t, 14> rle{3, 1, 0, 0, 0, 3, 0, 1, 0, 0, 0, 0, 0, 1};
    std::vector<std::uint8_t> bmp(62U + rle.size(), 0);
    const auto put16 = [&](std::size_t offset, std::uint16_t value) {
        bmp[offset] = static_cast<std::uint8_t>(value);
        bmp[offset + 1U] = static_cast<std::uint8_t>(value >> 8U);
    };
    const auto put32 = [&](std::size_t offset, std::uint32_t value) {
        for (std::size_t i = 0; i < 4; ++i) bmp[offset + i] = static_cast<std::uint8_t>(value >> (i * 8U));
    };
    bmp[0] = 'B';
    bmp[1] = 'M';
    put32(2, static_cast<std::uint32_t>(bmp.size()));
    put32(10, 62);
    put32(14, 40);
    put32(18, 3);
    put32(22, 2);
    put16(26, 1);
    put16(28, 8);
    put32(30, 1);
    put32(34, static_cast<std::uint32_t>(rle.size()));
    put32(46, 2);
    bmp[58] = 0;
    bmp[59] = 0;
    bmp[60] = 255;
    std::copy(rle.begin(), rle.end(), bmp.begin() + 62);
    return bmp;
}

std::vector<std::uint8_t> makeTestBmpRle4() {
    constexpr std::array<std::uint8_t, 12> rle{3, 0x12, 0, 0, 0, 3, 0x02, 0x00, 0, 0, 0, 1};
    std::vector<std::uint8_t> bmp(66U + rle.size(), 0);
    const auto put16 = [&](std::size_t offset, std::uint16_t value) {
        bmp[offset] = static_cast<std::uint8_t>(value);
        bmp[offset + 1U] = static_cast<std::uint8_t>(value >> 8U);
    };
    const auto put32 = [&](std::size_t offset, std::uint32_t value) {
        for (std::size_t i = 0; i < 4; ++i) bmp[offset + i] = static_cast<std::uint8_t>(value >> (i * 8U));
    };
    bmp[0] = 'B';
    bmp[1] = 'M';
    put32(2, static_cast<std::uint32_t>(bmp.size()));
    put32(10, 66);
    put32(14, 40);
    put32(18, 3);
    put32(22, 2);
    put16(26, 1);
    put16(28, 4);
    put32(30, 2);
    put32(34, static_cast<std::uint32_t>(rle.size()));
    put32(46, 3);
    bmp[58] = 0;
    bmp[59] = 0;
    bmp[60] = 255;
    bmp[61] = 0;
    bmp[62] = 0;
    bmp[63] = 255;
    bmp[64] = 0;
    bmp[65] = 0;
    std::copy(rle.begin(), rle.end(), bmp.begin() + 66);
    return bmp;
}

std::vector<std::uint8_t> makeTestBmpRgb565() {
    std::vector<std::uint8_t> bmp(70, 0);
    const auto put16 = [&](std::size_t offset, std::uint16_t value) {
        bmp[offset] = static_cast<std::uint8_t>(value);
        bmp[offset + 1U] = static_cast<std::uint8_t>(value >> 8U);
    };
    const auto put32 = [&](std::size_t offset, std::uint32_t value) {
        for (std::size_t i = 0; i < 4; ++i) bmp[offset + i] = static_cast<std::uint8_t>(value >> (i * 8U));
    };
    bmp[0] = 'B';
    bmp[1] = 'M';
    put32(2, static_cast<std::uint32_t>(bmp.size()));
    put32(10, 66);
    put32(14, 40);
    put32(18, 2);
    put32(22, 1);
    put16(26, 1);
    put16(28, 16);
    put32(30, 3);
    put32(34, 4);
    put32(54, 0xf800);
    put32(58, 0x07e0);
    put32(62, 0x001f);
    put16(66, 0xf800);
    put16(68, 0x07e0);
    return bmp;
}

std::vector<std::uint8_t> makeTestBmpArgb8888() {
    std::vector<std::uint8_t> bmp(74, 0);
    const auto put16 = [&](std::size_t offset, std::uint16_t value) {
        bmp[offset] = static_cast<std::uint8_t>(value);
        bmp[offset + 1U] = static_cast<std::uint8_t>(value >> 8U);
    };
    const auto put32 = [&](std::size_t offset, std::uint32_t value) {
        for (std::size_t i = 0; i < 4; ++i) bmp[offset + i] = static_cast<std::uint8_t>(value >> (i * 8U));
    };
    bmp[0] = 'B';
    bmp[1] = 'M';
    put32(2, static_cast<std::uint32_t>(bmp.size()));
    put32(10, 70);
    put32(14, 56);
    put32(18, 1);
    put32(22, 1);
    put16(26, 1);
    put16(28, 32);
    put32(30, 6);
    put32(34, 4);
    put32(54, 0x00ff0000);
    put32(58, 0x0000ff00);
    put32(62, 0x000000ff);
    put32(66, 0xff000000);
    put32(70, 0x80402010);
    return bmp;
}

std::vector<std::uint8_t> makeTestBmpIndexed(std::uint16_t bits_per_pixel, const std::vector<std::uint8_t>& palette,
                                             const std::array<std::uint8_t, 4>& pixels, bool top_down = false) {
    const auto pixel_offset = 54U + palette.size();
    std::vector<std::uint8_t> bmp(pixel_offset + pixels.size(), 0);
    const auto put16 = [&](std::size_t offset, std::uint16_t value) {
        bmp[offset] = static_cast<std::uint8_t>(value);
        bmp[offset + 1U] = static_cast<std::uint8_t>(value >> 8U);
    };
    const auto put32 = [&](std::size_t offset, std::uint32_t value) {
        for (std::size_t i = 0; i < 4; ++i) bmp[offset + i] = static_cast<std::uint8_t>(value >> (i * 8U));
    };
    bmp[0] = 'B';
    bmp[1] = 'M';
    put32(2, static_cast<std::uint32_t>(bmp.size()));
    put32(10, static_cast<std::uint32_t>(pixel_offset));
    put32(14, 40);
    put32(18, 3);
    put32(22, top_down ? 0xffffffffU : 1U);
    put16(26, 1);
    put16(28, bits_per_pixel);
    put32(34, static_cast<std::uint32_t>(pixels.size()));
    put32(46, static_cast<std::uint32_t>(palette.size() / 4U));
    std::copy(palette.begin(), palette.end(), bmp.begin() + 54);
    std::copy(pixels.begin(), pixels.end(), bmp.begin() + static_cast<std::ptrdiff_t>(pixel_offset));
    return bmp;
}

void writeSolidPng(const std::filesystem::path& path, croptop::Rgba8 pixel) {
    png_image image{};
    image.version = PNG_IMAGE_VERSION;
    image.width = 2;
    image.height = 2;
    image.format = PNG_FORMAT_RGBA;
    const std::vector<croptop::Rgba8> pixels(4, pixel);
    ASSERT_NE(png_image_write_to_file(&image, path.c_str(), 0, pixels.data(), 0, nullptr), 0) << image.message;
}

TEST(ImageMetrics, IdenticalColorsHaveNoDifference) {
    constexpr croptop::Rgba8 color{125, 65, 210, 255};
    EXPECT_DOUBLE_EQ(croptop::deltaE2000(color, color), 0.0);
}

TEST(ImageDecoder, PngStreamsRgbaRowsWithoutChangingAlpha) {
    const std::vector<std::uint8_t> pixels{255, 0, 0, 128, 0, 255, 0, 255,
                                           0, 0, 255, 255, 10, 20, 30, 0};
    const auto encoded = encodeTestPng(2, 2, pixels);
    ASSERT_FALSE(encoded.empty());
    std::vector<std::uint8_t> decoded;
    std::uint32_t rows = 0;
    const auto result = croptop::decodePng(encoded, [&](std::uint32_t y, std::span<const std::uint8_t> rgba) {
        EXPECT_EQ(y, rows);
        EXPECT_EQ(rgba.size(), 8U);
        decoded.insert(decoded.end(), rgba.begin(), rgba.end());
        ++rows;
        return true;
    });
    ASSERT_TRUE(result.success) << result.error;
    EXPECT_EQ(result.raster.width, 2U);
    EXPECT_EQ(result.raster.height, 2U);
    EXPECT_EQ(rows, 2U);
    EXPECT_EQ(decoded, pixels);
}

TEST(ImageDecoder, PngPreservesSixteenBitSamples) {
    const std::vector<std::uint16_t> pixels{0x1234, 0x5678, 0x9abc, 0xdef0};
    const auto encoded = encodeTestPng16(1, 1, pixels);
    ASSERT_FALSE(encoded.empty());
    std::vector<std::uint8_t> decoded;
    const auto result = croptop::decodePng(encoded, [&](std::uint32_t y, std::span<const std::uint8_t> rgba) {
        EXPECT_EQ(y, 0U);
        decoded.assign(rgba.begin(), rgba.end());
        return true;
    });
    ASSERT_TRUE(result.success) << result.error;
    EXPECT_EQ(result.raster.bit_depth, 16U);
    ASSERT_EQ(decoded.size(), 8U);
    const auto* samples = reinterpret_cast<const std::uint16_t*>(decoded.data());
    EXPECT_EQ(std::vector<std::uint16_t>(samples, samples + 4), pixels);
}

TEST(ImageDecoder, DecodesRowsDirectlyIntoSparseTilesAtEightAndSixteenBit) {
    const auto rgba8 = std::vector<std::uint8_t>{1, 2, 3, 4, 5, 6, 7, 8,
                                                 9, 10, 11, 12, 13, 14, 15, 16};
    auto png8 = encodeTestPng(2, 2, rgba8);
    auto pool = std::make_shared<croptop::TilePool>();
    croptop::TileStore store8(croptop::PixelFormat::RGBA8, 2, 2, pool);
    const auto decoded8 = croptop::decodePngToTiles(png8, store8);
    ASSERT_TRUE(decoded8.success) << decoded8.error;
    const auto tile8 = store8.readTile({0, 0});
    ASSERT_NE(tile8, nullptr);
    EXPECT_TRUE(std::equal(rgba8.begin(), rgba8.begin() + 8, tile8->begin()));
    EXPECT_TRUE(std::equal(rgba8.begin() + 8, rgba8.end(), tile8->begin() + croptop::kTileEdge * 4U));

    const auto rgba16 = std::vector<std::uint16_t>{0x1234, 0x5678, 0x9abc, 0xdef0};
    auto png16 = encodeTestPng16(1, 1, rgba16);
    croptop::TileStore store16(croptop::PixelFormat::RGBA16, 1, 1, pool);
    const auto decoded16 = croptop::decodePngToTiles(png16, store16);
    ASSERT_TRUE(decoded16.success) << decoded16.error;
    const auto tile16 = store16.readTile({0, 0});
    ASSERT_NE(tile16, nullptr);
    std::vector<std::uint16_t> actual16(4);
    std::memcpy(actual16.data(), tile16->data(), sizeof(std::uint16_t) * actual16.size());
    EXPECT_EQ(actual16, rgba16);

    croptop::TileStore mismatched(croptop::PixelFormat::RGBA8, 1, 1, pool);
    EXPECT_FALSE(croptop::decodePngToTiles(png16, mismatched).success);
}

TEST(ImageDecoder, AppliesAllExifOrientationsWhileWritingPngRowsIntoTiles) {
    std::vector<std::uint8_t> source_pixels;
    for (std::uint8_t value = 1; value <= 6; ++value) {
        source_pixels.insert(source_pixels.end(), {value, 0, 0, 255});
    }
    const auto png = encodeTestPng(2, 3, source_pixels);
    const std::array<std::array<std::uint8_t, 6>, 8> expected{{
        {1, 2, 3, 4, 5, 6}, {2, 1, 4, 3, 6, 5}, {6, 5, 4, 3, 2, 1}, {5, 6, 3, 4, 1, 2},
        {1, 3, 5, 2, 4, 6}, {5, 3, 1, 6, 4, 2}, {6, 4, 2, 5, 3, 1}, {2, 4, 6, 1, 3, 5},
    }};
    auto pool = std::make_shared<croptop::TilePool>();
    for (std::uint8_t orientation = 1; orientation <= 8; ++orientation) {
        const bool swapped = orientation >= 5;
        croptop::TileStore destination(croptop::PixelFormat::RGBA8, swapped ? 3 : 2, swapped ? 2 : 3, pool);
        const auto result = croptop::decodePngToTiles(png, destination, 32768, 500'000'000, orientation);
        ASSERT_TRUE(result.success) << "orientation " << static_cast<int>(orientation) << ": " << result.error;
        const auto tile = destination.readTile({0, 0});
        ASSERT_NE(tile, nullptr);
        for (std::uint32_t y = 0; y < destination.height(); ++y) {
            for (std::uint32_t x = 0; x < destination.width(); ++x) {
                const auto source_index = static_cast<std::size_t>(y * destination.width() + x);
                const auto tile_offset = (static_cast<std::size_t>(y) * croptop::kTileEdge + x) * 4U;
                EXPECT_EQ((*tile)[tile_offset], expected[orientation - 1U][source_index])
                    << "orientation=" << static_cast<int>(orientation) << " x=" << x << " y=" << y;
            }
        }
    }
}

TEST(ImageDecoder, ExifOrientationCrossesPartialTileEdgesWithoutLosingPixels) {
    constexpr std::uint32_t source_width = 257;
    constexpr std::uint32_t source_height = 259;
    std::vector<std::uint8_t> source_pixels(static_cast<std::size_t>(source_width) * source_height * 4U);
    for (std::uint32_t y = 0; y < source_height; ++y) {
        for (std::uint32_t x = 0; x < source_width; ++x) {
            const auto offset = (static_cast<std::size_t>(y) * source_width + x) * 4U;
            source_pixels[offset] = static_cast<std::uint8_t>(x % 251U);
            source_pixels[offset + 1U] = static_cast<std::uint8_t>(y % 251U);
            source_pixels[offset + 2U] = static_cast<std::uint8_t>((x + y) % 251U);
            source_pixels[offset + 3U] = 255;
        }
    }
    const auto png = encodeTestPng(source_width, source_height, source_pixels);
    auto pool = std::make_shared<croptop::TilePool>();
    croptop::TileStore destination(croptop::PixelFormat::RGBA8, source_height, source_width, pool);
    const auto decoded = croptop::decodePngToTiles(png, destination, 32768, 500'000'000, 7);
    ASSERT_TRUE(decoded.success) << decoded.error;
    ASSERT_EQ(destination.tile_count(), 4U);
    const auto top_left = destination.readTile({0, 0});
    const auto bottom_right = destination.readTile({1, 1});
    ASSERT_NE(top_left, nullptr);
    ASSERT_NE(bottom_right, nullptr);
    EXPECT_EQ((*top_left)[0], 5U); // source (256, 258)
    EXPECT_EQ((*top_left)[1], 7U);
    constexpr std::size_t last_pixel = 2U * 4U; // destination (258, 256), tile-local (2, 0)
    EXPECT_EQ((*bottom_right)[last_pixel], 0U); // source (0, 0)
    EXPECT_EQ((*bottom_right)[last_pixel + 1U], 0U);
    EXPECT_FALSE(croptop::decodePngToTiles(png, destination, 32768, 500'000'000, 0).success);
}

TEST(ImageEncoder, PngRoundTripsSparseRgba8AndRgba16TileRows) {
    auto pool = std::make_shared<croptop::TilePool>();
    croptop::TileBuffer pixels8(croptop::kTileEdge * croptop::kTileEdge * 4U, 0);
    const std::array<std::uint8_t, 16> rgba8{255, 0, 0, 255, 0, 255, 0, 128,
                                            0, 0, 255, 255, 7, 8, 9, 10};
    std::copy(rgba8.begin(), rgba8.begin() + 8, pixels8.begin());
    std::copy(rgba8.begin() + 8, rgba8.end(), pixels8.begin() + croptop::kTileEdge * 4U);
    croptop::TileStore original8(croptop::PixelFormat::RGBA8, 2, 2, pool);
    ASSERT_TRUE(original8.writeTile({0, 0}, pixels8));
    const auto encoded8 = croptop::encodePngFromTiles(original8);
    ASSERT_TRUE(encoded8.success) << encoded8.error;
    std::vector<std::uint8_t> streamed_png;
    const croptop::EncodedBytesSink collecting_sink = [&streamed_png](std::span<const std::uint8_t> bytes) {
        streamed_png.insert(streamed_png.end(), bytes.begin(), bytes.end());
        return true;
    };
    const auto streamed8 = croptop::encodePngToSink(original8, collecting_sink);
    ASSERT_TRUE(streamed8.success) << streamed8.error;
    EXPECT_EQ(streamed_png, encoded8.encoded);
    const croptop::EncodedBytesSink rejecting_sink = [](std::span<const std::uint8_t>) { return false; };
    EXPECT_FALSE(croptop::encodePngToSink(original8, rejecting_sink).success);
    std::vector<std::uint8_t> icc_profile(132, 0);
    icc_profile[3] = 132;
    icc_profile[16] = 'R';
    icc_profile[17] = 'G';
    icc_profile[18] = 'B';
    icc_profile[20] = 'X';
    icc_profile[21] = 'Y';
    icc_profile[22] = 'Z';
    icc_profile[36] = 'a';
    icc_profile[37] = 'c';
    icc_profile[38] = 's';
    icc_profile[39] = 'p';
    std::vector<std::uint8_t> exif_tiff(26, 0);
    exif_tiff[0] = 'I';
    exif_tiff[1] = 'I';
    exif_tiff[2] = 42;
    exif_tiff[4] = 8;
    exif_tiff[8] = 1;
    exif_tiff[10] = 0x12;
    exif_tiff[11] = 0x01;
    exif_tiff[12] = 3;
    exif_tiff[14] = 1;
    exif_tiff[18] = 1;
    const croptop::PngEncodeMetadata metadata{icc_profile, exif_tiff};
    const auto metadata_png = croptop::encodePngFromTiles(original8, 6, metadata);
    ASSERT_TRUE(metadata_png.success) << metadata_png.error;
    const auto has_chunk = [&metadata_png](std::string_view chunk) {
        return std::search(metadata_png.encoded.begin(), metadata_png.encoded.end(), chunk.begin(), chunk.end()) !=
               metadata_png.encoded.end();
    };
    EXPECT_TRUE(has_chunk("iCCP"));
    const auto exif_chunk = std::search(metadata_png.encoded.begin(), metadata_png.encoded.end(), "eXIf", "eXIf" + 4);
    ASSERT_NE(exif_chunk, metadata_png.encoded.end());
    EXPECT_EQ(*(exif_chunk + 4 + 18), 1U);
    croptop::TileStore metadata_round_trip(croptop::PixelFormat::RGBA8, 2, 2, pool);
    EXPECT_TRUE(croptop::decodePngToTiles(metadata_png.encoded, metadata_round_trip).success);
    croptop::TileStore decoded8(croptop::PixelFormat::RGBA8, 2, 2, pool);
    const auto result8 = croptop::decodePngToTiles(encoded8.encoded, decoded8);
    ASSERT_TRUE(result8.success) << result8.error;
    const auto actual8 = decoded8.readTile({0, 0});
    ASSERT_NE(actual8, nullptr);
    EXPECT_TRUE(std::equal(rgba8.begin(), rgba8.begin() + 8, actual8->begin()));
    EXPECT_TRUE(std::equal(rgba8.begin() + 8, rgba8.end(), actual8->begin() + croptop::kTileEdge * 4U));

    croptop::TileBuffer pixels16(croptop::kTileEdge * croptop::kTileEdge * 8U, 0);
    const std::array<std::uint16_t, 8> rgba16{0x1234, 0x5678, 0x9abc, 0xdef0, 0x0102, 0x0304, 0xabcd, 0x9876};
    std::memcpy(pixels16.data(), rgba16.data(), 4U * sizeof(std::uint16_t));
    std::memcpy(pixels16.data() + croptop::kTileEdge * 8U, rgba16.data() + 4, 4U * sizeof(std::uint16_t));
    croptop::TileStore original16(croptop::PixelFormat::RGBA16, 2, 2, pool);
    ASSERT_TRUE(original16.writeTile({0, 0}, pixels16));
    const auto encoded16 = croptop::encodePngFromTiles(original16, 1);
    ASSERT_TRUE(encoded16.success) << encoded16.error;
    croptop::TileStore decoded16(croptop::PixelFormat::RGBA16, 2, 2, pool);
    const auto result16 = croptop::decodePngToTiles(encoded16.encoded, decoded16);
    ASSERT_TRUE(result16.success) << result16.error;
    const auto actual16 = decoded16.readTile({0, 0});
    ASSERT_NE(actual16, nullptr);
    std::array<std::uint16_t, 4> actual_top{};
    std::array<std::uint16_t, 4> actual_bottom{};
    std::memcpy(actual_top.data(), actual16->data(), actual_top.size() * sizeof(std::uint16_t));
    std::memcpy(actual_bottom.data(), actual16->data() + croptop::kTileEdge * 8U,
                actual_bottom.size() * sizeof(std::uint16_t));
    EXPECT_TRUE(std::equal(rgba16.begin(), rgba16.begin() + 4, actual_top.begin()));
    EXPECT_TRUE(std::equal(rgba16.begin() + 4, rgba16.end(), actual_bottom.begin()));

    croptop::TileStore empty(croptop::PixelFormat::RGBA8, 2, 2, pool);
    const auto transparent_png = croptop::encodePngFromTiles(empty);
    ASSERT_TRUE(transparent_png.success) << transparent_png.error;
    croptop::TileStore transparent(croptop::PixelFormat::RGBA8, 2, 2, pool);
    const auto transparent_result = croptop::decodePngToTiles(transparent_png.encoded, transparent);
    ASSERT_TRUE(transparent_result.success) << transparent_result.error;
    EXPECT_EQ(transparent.tile_count(), 0U);
    EXPECT_FALSE(croptop::encodePngFromTiles(empty, -1).success);
    croptop::TileStore unsupported(croptop::PixelFormat::RGBA32F, 2, 2, pool);
    EXPECT_FALSE(croptop::encodePngFromTiles(unsupported).success);
}

TEST(ImageEncoder, JpegStreamsTileRowsHonorsQualityAndRejectsSinkFailure) {
    auto pool = std::make_shared<croptop::TilePool>();
    croptop::TileBuffer pixels(croptop::kTileEdge * croptop::kTileEdge * 4U, 0);
    const std::array<std::uint8_t, 8> rgba{255, 0, 0, 255, 0, 0, 255, 0};
    std::copy(rgba.begin(), rgba.end(), pixels.begin());
    croptop::TileStore source(croptop::PixelFormat::RGBA8, 2, 1, pool);
    ASSERT_TRUE(source.writeTile({0, 0}, pixels));
    std::vector<std::uint8_t> encoded;
    const croptop::EncodedBytesSink sink = [&encoded](std::span<const std::uint8_t> bytes) {
        encoded.insert(encoded.end(), bytes.begin(), bytes.end());
        return true;
    };
    const auto result = croptop::encodeJpegToSink(source, sink, 88);
    ASSERT_TRUE(result.success) << result.error;
    ASSERT_GE(encoded.size(), 4U);
    EXPECT_EQ(encoded[0], 0xffU);
    EXPECT_EQ(encoded[1], 0xd8U);
    std::uint32_t decoded_rows = 0;
    const auto decoded = croptop::decodeJpeg(encoded, [&decoded_rows](std::uint32_t y, std::span<const std::uint8_t> row) {
        EXPECT_LT(y, 1U);
        EXPECT_EQ(row.size(), 8U);
        ++decoded_rows;
        return true;
    });
    ASSERT_TRUE(decoded.success) << decoded.error;
    EXPECT_EQ(decoded.raster.width, 2U);
    EXPECT_EQ(decoded.raster.height, 1U);
    EXPECT_EQ(decoded_rows, 1U);
    const std::array<std::uint8_t, 8> jpeg_exif{'I', 'I', 42, 0, 8, 0, 0, 0};
    const std::array<std::uint8_t, 4> jpeg_icc{'t', 'e', 's', 't'};
    const croptop::JpegEncodeMetadata metadata{jpeg_icc, jpeg_exif};
    std::vector<std::uint8_t> metadata_encoded;
    const croptop::EncodedBytesSink metadata_sink = [&metadata_encoded](std::span<const std::uint8_t> bytes) {
        metadata_encoded.insert(metadata_encoded.end(), bytes.begin(), bytes.end());
        return true;
    };
    ASSERT_TRUE(croptop::encodeJpegToSink(source, metadata_sink, 88, metadata).success);
    EXPECT_NE(std::search(metadata_encoded.begin(), metadata_encoded.end(), jpeg_exif.begin(), jpeg_exif.end()),
              metadata_encoded.end());
    const std::array<std::uint8_t, 14> icc_header{'I', 'C', 'C', '_', 'P', 'R', 'O', 'F', 'I', 'L', 'E', 0, 1, 1};
    EXPECT_NE(std::search(metadata_encoded.begin(), metadata_encoded.end(), icc_header.begin(), icc_header.end()),
              metadata_encoded.end());
    const croptop::EncodedBytesSink rejecting_sink = [](std::span<const std::uint8_t>) { return false; };
    EXPECT_FALSE(croptop::encodeJpegToSink(source, rejecting_sink).success);
    EXPECT_FALSE(croptop::encodeJpegToSink(source, sink, 0).success);
}

TEST(ImageDecoder, BmpNormalizesBottomUpBgrRowsIntoRgbaTiles) {
    const auto bmp = makeTestBmp24();
    auto pool = std::make_shared<croptop::TilePool>();
    croptop::TileStore store(croptop::PixelFormat::RGBA8, 2, 2, pool);
    const auto result = croptop::decodeBmpToTiles(bmp, store);
    ASSERT_TRUE(result.success) << result.error;
    const auto tile = store.readTile({0, 0});
    ASSERT_NE(tile, nullptr);
    const std::array<std::uint8_t, 8> top{255, 0, 0, 255, 0, 255, 0, 255};
    const std::array<std::uint8_t, 8> bottom{0, 0, 255, 255, 255, 255, 255, 255};
    EXPECT_TRUE(std::equal(top.begin(), top.end(), tile->begin()));
    EXPECT_TRUE(std::equal(bottom.begin(), bottom.end(), tile->begin() + croptop::kTileEdge * 4U));
    EXPECT_FALSE(croptop::decodeBmpToTiles(bmp, store, 1, 10).success);
}

TEST(ImageDecoder, BmpRle8ExpandsPaletteRunsIntoReverseTileBands) {
    const auto bmp = makeTestBmpRle8();
    auto pool = std::make_shared<croptop::TilePool>();
    croptop::TileStore store(croptop::PixelFormat::RGBA8, 3, 2, pool);
    const auto result = croptop::decodeBmpToTiles(bmp, store);
    ASSERT_TRUE(result.success) << result.error;
    const auto tile = store.readTile({0, 0});
    ASSERT_NE(tile, nullptr);
    const std::array<std::uint8_t, 12> top{0, 0, 0, 255, 255, 0, 0, 255, 0, 0, 0, 255};
    const std::array<std::uint8_t, 12> bottom{255, 0, 0, 255, 255, 0, 0, 255, 255, 0, 0, 255};
    EXPECT_TRUE(std::equal(top.begin(), top.end(), tile->begin()));
    EXPECT_TRUE(std::equal(bottom.begin(), bottom.end(), tile->begin() + croptop::kTileEdge * 4U));
    auto malformed = bmp;
    malformed.resize(malformed.size() - 3U);
    EXPECT_FALSE(croptop::decodeBmpToTiles(malformed, store).success);
}

TEST(ImageDecoder, BmpRle4ExpandsPackedNibblesAndWordAlignedAbsoluteRuns) {
    const auto bmp = makeTestBmpRle4();
    auto pool = std::make_shared<croptop::TilePool>();
    croptop::TileStore store(croptop::PixelFormat::RGBA8, 3, 2, pool);
    const auto result = croptop::decodeBmpToTiles(bmp, store);
    ASSERT_TRUE(result.success) << result.error;
    const auto tile = store.readTile({0, 0});
    ASSERT_NE(tile, nullptr);
    const std::array<std::uint8_t, 12> top{0, 0, 0, 255, 0, 255, 0, 255, 0, 0, 0, 255};
    const std::array<std::uint8_t, 12> bottom{255, 0, 0, 255, 0, 255, 0, 255, 255, 0, 0, 255};
    EXPECT_TRUE(std::equal(top.begin(), top.end(), tile->begin()));
    EXPECT_TRUE(std::equal(bottom.begin(), bottom.end(), tile->begin() + croptop::kTileEdge * 4U));
}

TEST(ImageDecoder, BmpRgb565BitfieldsNormalizeToRgba8Tiles) {
    const auto bmp = makeTestBmpRgb565();
    auto pool = std::make_shared<croptop::TilePool>();
    croptop::TileStore store(croptop::PixelFormat::RGBA8, 2, 1, pool);
    const auto result = croptop::decodeBmpToTiles(bmp, store);
    ASSERT_TRUE(result.success) << result.error;
    const auto tile = store.readTile({0, 0});
    ASSERT_NE(tile, nullptr);
    const std::array<std::uint8_t, 8> expected{255, 0, 0, 255, 0, 255, 0, 255};
    EXPECT_TRUE(std::equal(expected.begin(), expected.end(), tile->begin()));
    auto invalid_mask = bmp;
    invalid_mask[54] = 0x00;
    invalid_mask[55] = 0xf5;
    EXPECT_FALSE(croptop::decodeBmpToTiles(invalid_mask, store).success);
}

TEST(ImageDecoder, BmpAlphaBitfieldsPreserveMaskedAlpha) {
    const auto bmp = makeTestBmpArgb8888();
    auto pool = std::make_shared<croptop::TilePool>();
    croptop::TileStore store(croptop::PixelFormat::RGBA8, 1, 1, pool);
    const auto result = croptop::decodeBmpToTiles(bmp, store);
    ASSERT_TRUE(result.success) << result.error;
    const auto tile = store.readTile({0, 0});
    ASSERT_NE(tile, nullptr);
    const std::array<std::uint8_t, 4> expected{64, 32, 16, 128};
    EXPECT_TRUE(std::equal(expected.begin(), expected.end(), tile->begin()));
}

TEST(ImageDecoder, BmpIndexedOneFourAndEightBitRowsExpandPalettes) {
    const std::vector<std::uint8_t> palette_2{0, 0, 0, 0, 0, 0, 255, 0};
    const std::vector<std::uint8_t> palette_3{0, 0, 0, 0, 0, 0, 255, 0, 0, 255, 0, 0};
    const std::array<std::uint8_t, 4> red_black_red{0xa0, 0, 0, 0};
    const std::array<std::uint8_t, 4> packed_4bit{0x10, 0x10, 0, 0};
    const std::array<std::uint8_t, 4> indexed_8bit{1, 0, 1, 0};
    const std::array<std::uint8_t, 12> red_black_red_rgba{
        255, 0, 0, 255, 0, 0, 0, 255, 255, 0, 0, 255,
    };
    for (const auto& [bits, palette, pixels, top_down] : std::array{
             std::tuple{1U, palette_2, red_black_red, true},
             std::tuple{4U, palette_3, packed_4bit, false},
             std::tuple{8U, palette_2, indexed_8bit, false},
         }) {
        const auto bmp = makeTestBmpIndexed(static_cast<std::uint16_t>(bits), palette, pixels, top_down);
        auto pool = std::make_shared<croptop::TilePool>();
        croptop::TileStore store(croptop::PixelFormat::RGBA8, 3, 1, pool);
        const auto result = croptop::decodeBmpToTiles(bmp, store);
        ASSERT_TRUE(result.success) << result.error;
        const auto tile = store.readTile({0, 0});
        ASSERT_NE(tile, nullptr);
        EXPECT_TRUE(std::equal(red_black_red_rgba.begin(), red_black_red_rgba.end(), tile->begin())) << bits << "bpp";
    }
}

TEST(ImageDecoder, JpegStreamsOpaqueRgbaRows) {
    const std::vector<std::uint8_t> pixels(2U * 2U * 3U, 210);
    const auto encoded = encodeTestJpeg(2, 2, pixels);
    ASSERT_FALSE(encoded.empty());
    std::uint32_t rows = 0;
    const auto result = croptop::decodeJpeg(encoded, [&](std::uint32_t y, std::span<const std::uint8_t> rgba) {
        EXPECT_EQ(y, rows);
        EXPECT_EQ(rgba.size(), 8U);
        EXPECT_EQ(rgba[3], 255U);
        EXPECT_GT(rgba[0], 180U);
        ++rows;
        return true;
    });
    ASSERT_TRUE(result.success) << result.error;
    EXPECT_EQ(result.raster.width, 2U);
    EXPECT_EQ(result.raster.height, 2U);
    EXPECT_EQ(rows, 2U);
}

TEST(ImageDecoder, RejectsTruncatedInputDimensionBombsAndSinkFailures) {
    const std::vector<std::uint8_t> tiny_png{0x89, 'P', 'N', 'G'};
    EXPECT_FALSE(croptop::decodePng(tiny_png, [](std::uint32_t, std::span<const std::uint8_t>) { return true; }).success);
    const auto pixels = std::vector<std::uint8_t>(4U * 4U * 4U, 127);
    const auto png = encodeTestPng(4, 4, pixels);
    EXPECT_FALSE(croptop::decodePng(png, [](std::uint32_t, std::span<const std::uint8_t>) { return true; }, 2, 100).success);
    auto truncated_png = png;
    truncated_png.resize(truncated_png.size() - 2U);
    EXPECT_FALSE(croptop::decodePng(truncated_png,
                                   [](std::uint32_t, std::span<const std::uint8_t>) { return true; }).success);
    const auto jpeg = encodeTestJpeg(2, 2, std::vector<std::uint8_t>(12, 42));
    EXPECT_FALSE(croptop::decodeJpeg(jpeg, [](std::uint32_t, std::span<const std::uint8_t>) { return false; }).success);
    const std::vector<std::uint8_t> truncated_jpeg(jpeg.begin(), jpeg.begin() + 8);
    EXPECT_FALSE(croptop::decodeJpeg(truncated_jpeg,
                                    [](std::uint32_t, std::span<const std::uint8_t>) { return true; }).success);
}

TEST(ImageDecoder, RandomizedMalformedHeadersRemainBounded) {
    std::mt19937 random(0xa37e4U);
    const auto sink = [](std::uint32_t, std::span<const std::uint8_t> row) {
        return row.size() <= 512U * 4U * 2U;
    };
    for (std::size_t sample = 0; sample < 512; ++sample) {
        const auto length = static_cast<std::size_t>(random() % 2048U);
        std::vector<std::uint8_t> bytes(length);
        std::generate(bytes.begin(), bytes.end(), [&] { return static_cast<std::uint8_t>(random()); });
        EXPECT_FALSE(croptop::decodeJpeg(bytes, sink, 512, 512U * 512U).success);
        EXPECT_FALSE(croptop::decodePng(bytes, sink, 512, 512U * 512U).success);
        EXPECT_FALSE(croptop::decodeBmp(bytes, sink, 512, 512U * 512U).success);
    }
}

TEST(ImageMetrics, DeltaE2000IsSymmetric) {
    const double forward = croptop::deltaE2000({45, 125, 210, 255}, {48, 120, 205, 255});
    const double reverse = croptop::deltaE2000({48, 120, 205, 255}, {45, 125, 210, 255});
    EXPECT_NEAR(forward, reverse, 1e-10);
    EXPECT_GT(forward, 0.0);
}

TEST(GoldenImage, DetectsOneLevelColorShiftAndWritesPngDiff) {
    const auto root = std::filesystem::temp_directory_path() / "croptop-golden-smoke";
    std::filesystem::create_directories(root);
    const auto expected = root / "expected.png";
    const auto actual = root / "actual.png";
    const auto diff = root / "diff.png";
    writeSolidPng(expected, {128, 128, 128, 255});
    writeSolidPng(actual, {129, 128, 128, 255});

    const auto report = croptop::comparePng(expected, actual, diff, 0.0);
    EXPECT_EQ(report.width, 2U);
    EXPECT_EQ(report.height, 2U);
    EXPECT_EQ(report.pixels_over_threshold, 4U);
    EXPECT_GT(report.max_delta_e, 0.0);
    EXPECT_TRUE(std::filesystem::exists(diff));
}

TEST(JobPool, ProgressRingKeepsNewestEventsAndClampsFiniteValues) {
    croptop::JobPool pool(1, 4, 3);
    std::atomic<bool> completed{false};
    const auto handle = pool.submit(croptop::JobPriority::Background, [&](const croptop::JobContext& context) {
        context.reportProgress(0.1F);
        context.reportProgress(0.25F);
        context.reportProgress(0.5F);
        context.reportProgress(0.75F);
        context.reportProgress(1.5F);
        context.reportProgress(std::numeric_limits<float>::infinity());
        completed.store(true, std::memory_order_release);
    });
    ASSERT_TRUE(handle.has_value());
    while (!completed.load(std::memory_order_acquire)) std::this_thread::yield();

    const auto events = pool.drainProgress();
    ASSERT_EQ(events.size(), 3U);
    EXPECT_EQ(handle->jobId(), events[0].job_id);
    EXPECT_FLOAT_EQ(events[0].progress, 0.5F);
    EXPECT_FLOAT_EQ(events[1].progress, 0.75F);
    EXPECT_FLOAT_EQ(events[2].progress, 1.0F);
    EXPECT_TRUE(pool.drainProgress().empty());
}

TEST(JobPool, ProgressRingIsBoundedAcrossConcurrentWorkers) {
    constexpr std::size_t kCapacity = 128;
    croptop::JobPool pool(4, 8, kCapacity);
    std::atomic<std::size_t> completed{0};
    for (int task = 0; task < 4; ++task) {
        ASSERT_TRUE(pool.submit(croptop::JobPriority::Visible, [&](const croptop::JobContext& context) {
            for (int step = 0; step < 1000; ++step) context.reportProgress(static_cast<float>(step) / 999.0F);
            completed.fetch_add(1, std::memory_order_release);
        }));
    }
    while (completed.load(std::memory_order_acquire) != 4U) std::this_thread::yield();

    const auto events = pool.drainProgress();
    EXPECT_EQ(events.size(), kCapacity);
    for (const auto& event : events) {
        EXPECT_LT(event.job_id, 4U);
        EXPECT_GE(event.progress, 0.0F);
        EXPECT_LE(event.progress, 1.0F);
    }
}

TEST(TileStore, HundredMegapixelEmptyDocumentAllocatesNoPixelTiles) {
    const croptop::TileStore image(croptop::PixelFormat::RGBA8, 10'000, 10'000);

    EXPECT_EQ(image.tile_count(), 0U);
    EXPECT_EQ(image.resident_bytes(), 0U);
    EXPECT_LT(image.resident_bytes(), 5U * 1024U * 1024U);
}

TEST(TileStore, SupportsAllPlannedPixelFormats) {
    EXPECT_EQ(croptop::TileStore(croptop::PixelFormat::RGBA8, 1, 1).tile_byte_size(), 256U * 256U * 4U);
    EXPECT_EQ(croptop::TileStore(croptop::PixelFormat::RGBA16, 1, 1).tile_byte_size(), 256U * 256U * 8U);
    EXPECT_EQ(croptop::TileStore(croptop::PixelFormat::RGBA32F, 1, 1).tile_byte_size(), 256U * 256U * 16U);
    EXPECT_EQ(croptop::TileStore(croptop::PixelFormat::A8, 1, 1).tile_byte_size(), 256U * 256U);
    EXPECT_EQ(croptop::TileStore(croptop::PixelFormat::A16, 1, 1).tile_byte_size(), 256U * 256U * 2U);
}

TEST(TileStore, TranslationIsSparseClippedBilinearAndCopyOnWrite) {
    croptop::TileStore pixels(croptop::PixelFormat::RGBA8, 8, 8);
    croptop::TileBuffer tile(pixels.tile_byte_size(), 0);
    const auto source_pixel = (2U * croptop::kTileEdge + 3U) * 4U;
    tile[source_pixel] = 255;
    tile[source_pixel + 3U] = 255;
    ASSERT_TRUE(pixels.writeTile({0, 0}, tile));
    const croptop::TileStore snapshot(pixels);

    ASSERT_TRUE(pixels.translate(2.0F, 1.0F));
    EXPECT_EQ(pixels.tile_count(), 1U);
    const auto moved = pixels.readTile({0, 0});
    ASSERT_NE(moved, nullptr);
    const auto moved_pixel = (3U * croptop::kTileEdge + 5U) * 4U;
    EXPECT_EQ((*moved)[moved_pixel], 255U);
    EXPECT_EQ((*moved)[moved_pixel + 3U], 255U);
    EXPECT_EQ((*moved)[source_pixel + 3U], 0U);
    const auto original = snapshot.readTile({0, 0});
    ASSERT_NE(original, nullptr);
    EXPECT_EQ((*original)[source_pixel], 255U);

    ASSERT_TRUE(pixels.translate(-6.0F, 0.0F));
    EXPECT_EQ(pixels.tile_count(), 0U);
}

TEST(TileStore, FractionalTranslationInterpolatesPremultipliedAlphaWithoutColorFringes) {
    croptop::TileStore pixels(croptop::PixelFormat::RGBA8, 4, 4);
    croptop::TileBuffer tile(pixels.tile_byte_size(), 0);
    const auto index = (1U * croptop::kTileEdge + 1U) * 4U;
    tile[index] = 255;
    tile[index + 3U] = 255;
    ASSERT_TRUE(pixels.writeTile({0, 0}, tile));

    ASSERT_TRUE(pixels.translate(0.5F, 0.0F));
    const auto translated = pixels.readTile({0, 0});
    ASSERT_NE(translated, nullptr);
    const auto left = (1U * croptop::kTileEdge + 1U) * 4U;
    const auto right = (1U * croptop::kTileEdge + 2U) * 4U;
    EXPECT_EQ((*translated)[left], 255U);
    EXPECT_EQ((*translated)[left + 3U], 128U);
    EXPECT_EQ((*translated)[right], 255U);
    EXPECT_EQ((*translated)[right + 3U], 128U);
}

TEST(TileStore, SnapshotSharesTilesAndWritesAreCopyOnWrite) {
    auto pool = std::make_shared<croptop::TilePool>();
    croptop::TileStore original(croptop::PixelFormat::RGBA8, 512, 512, pool);
    const auto empty_revision = original.contentRevision();
    croptop::TileBuffer first(original.tile_byte_size(), 0);
    first[0] = 17;
    ASSERT_TRUE(original.writeTile({0, 0}, first));
    const auto first_revision = original.contentRevision();
    EXPECT_NE(first_revision, empty_revision);

    auto snapshot = original;
    EXPECT_EQ(snapshot.contentRevision(), first_revision);
    croptop::TileBuffer changed = first;
    changed[0] = 29;
    ASSERT_TRUE(original.writeTile({0, 0}, changed));
    EXPECT_NE(original.contentRevision(), first_revision);
    EXPECT_EQ(snapshot.contentRevision(), first_revision);

    ASSERT_NE(original.readTile({0, 0}), nullptr);
    ASSERT_NE(snapshot.readTile({0, 0}), nullptr);
    EXPECT_EQ(original.readTile({0, 0})->front(), 29);
    EXPECT_EQ(snapshot.readTile({0, 0})->front(), 17);
    EXPECT_EQ(pool->active_bytes(), 2U * original.tile_byte_size());
}

TEST(TileStore, CopyAssignmentKeepsConfigurationImmutableDuringConcurrentReads) {
    const auto pool = std::make_shared<croptop::TilePool>();
    croptop::TileStore source(croptop::PixelFormat::RGBA8, 512, 512, pool);
    croptop::TileStore destination(croptop::PixelFormat::RGBA8, 512, 512, pool);
    croptop::TileBuffer pixels(source.tile_byte_size(), 17U);
    ASSERT_TRUE(source.writeTile({0, 0}, pixels));

    std::atomic<bool> start{false};
    std::atomic<bool> failed{false};
    std::thread writer([&] {
        while (!start.load(std::memory_order_acquire)) std::this_thread::yield();
        for (int iteration = 0; iteration < 500; ++iteration) destination = source;
    });
    std::vector<std::thread> readers;
    for (int reader = 0; reader < 4; ++reader) {
        readers.emplace_back([&] {
            while (!start.load(std::memory_order_acquire)) std::this_thread::yield();
            for (int iteration = 0; iteration < 500; ++iteration) {
                croptop::TileStore snapshot(destination);
                const auto valid = snapshot.format() == croptop::PixelFormat::RGBA8 && snapshot.width() == 512U &&
                                   snapshot.height() == 512U && snapshot.bytes_per_pixel() == 4U &&
                                   snapshot.mip_width(1U) == 256U && snapshot.contentRevision() != 0;
                if (!valid) failed.store(true, std::memory_order_relaxed);
            }
        });
    }
    start.store(true, std::memory_order_release);
    writer.join();
    for (auto& reader : readers) reader.join();
    EXPECT_FALSE(failed.load());
    EXPECT_EQ(destination.tile_count(), 1U);

    croptop::TileStore incompatible(croptop::PixelFormat::RGBA8, 256, 512, pool);
    EXPECT_THROW(destination = incompatible, std::invalid_argument);
}

TEST(TileStore, EmptyAndErasedTilesReturnToPool) {
    auto pool = std::make_shared<croptop::TilePool>();
    croptop::TileStore image(croptop::PixelFormat::A8, 256, 256, pool);
    croptop::TileBuffer pixels(image.tile_byte_size(), 0);

    ASSERT_TRUE(image.writeTile({0, 0}, pixels));
    EXPECT_EQ(image.tile_count(), 0U);
    pixels.back() = 1;
    ASSERT_TRUE(image.writeTile({0, 0}, pixels));
    EXPECT_EQ(pool->active_bytes(), image.tile_byte_size());
    ASSERT_TRUE(image.eraseTile({0, 0}));
    EXPECT_EQ(image.resident_bytes(), 0U);
    EXPECT_EQ(pool->active_bytes(), 0U);
    EXPECT_EQ(pool->cached_bytes(), image.tile_byte_size());
}

TEST(TileStore, RejectsInvalidCoordinatesAndBufferSizes) {
    croptop::TileStore image(croptop::PixelFormat::RGBA8, 257, 256);
    croptop::TileBuffer pixels(image.tile_byte_size(), 1);

    EXPECT_FALSE(image.writeTile({-1, 0}, pixels));
    EXPECT_FALSE(image.writeTile({2, 0}, pixels));
    EXPECT_FALSE(image.writeTile({1, 0}, croptop::TileBuffer(1, 1)));
    EXPECT_EQ(image.readTile({1, 0}), nullptr);
}

TEST(TileStore, MipsBuildLazilyAverageInLinearLightAndInvalidateDirtyRegion) {
    croptop::TileStore image(croptop::PixelFormat::RGBA8, 2, 2);
    croptop::TileBuffer pixels(image.tile_byte_size(), 0);
    const auto pixel_offset = [](std::size_t x, std::size_t y) { return (y * croptop::kTileEdge + x) * 4U; };
    for (std::size_t y = 0; y < 2; ++y) {
        for (std::size_t x = 0; x < 2; ++x) pixels[pixel_offset(x, y) + 3U] = 255;
    }
    pixels[pixel_offset(0, 0)] = 255;
    pixels[pixel_offset(1, 0) + 1U] = 255;
    pixels[pixel_offset(0, 1) + 2U] = 255;
    pixels[pixel_offset(1, 1)] = 255;
    ASSERT_TRUE(image.writeTile({0, 0}, pixels));

    EXPECT_EQ(image.mip_resident_bytes(), 0U);
    EXPECT_EQ(image.mip_width(1), 1U);
    EXPECT_EQ(image.mip_height(1), 1U);
    const auto reduced = image.readMipTile(1, {0, 0});
    ASSERT_NE(reduced, nullptr);
    EXPECT_NEAR((*reduced)[0], 188, 1);
    EXPECT_NEAR((*reduced)[1], 137, 1);
    EXPECT_EQ((*reduced)[3], 255U);
    EXPECT_EQ(image.mip_resident_bytes(), image.tile_byte_size());

    std::fill(pixels.begin(), pixels.end(), 0);
    for (std::size_t y = 0; y < 2; ++y) {
        for (std::size_t x = 0; x < 2; ++x) pixels[pixel_offset(x, y) + 3U] = 255;
    }
    ASSERT_TRUE(image.writeTile({0, 0}, pixels));
    EXPECT_EQ(image.mip_resident_bytes(), 0U);
    const auto refreshed = image.readMipTile(1, {0, 0});
    ASSERT_NE(refreshed, nullptr);
    EXPECT_EQ((*refreshed)[0], 0U);
    EXPECT_EQ((*refreshed)[3], 255U);

    std::fill(pixels.begin(), pixels.end(), 0);
    for (std::size_t y = 0; y < 2; ++y) {
        for (std::size_t x = 0; x < 2; ++x) pixels[pixel_offset(x, y)] = 255;
    }
    pixels[pixel_offset(0, 0)] = 0;
    pixels[pixel_offset(0, 0) + 2U] = 255;
    pixels[pixel_offset(0, 0) + 3U] = 255;
    ASSERT_TRUE(image.writeTile({0, 0}, pixels));
    auto alpha_filtered = image.readMipTile(1, {0, 0});
    ASSERT_NE(alpha_filtered, nullptr);
    EXPECT_EQ((*alpha_filtered)[0], 0U);
    EXPECT_EQ((*alpha_filtered)[2], 255U);
    EXPECT_EQ((*alpha_filtered)[3], 64U);
    alpha_filtered.reset();
    image.trimCaches();
    EXPECT_EQ(image.mip_resident_bytes(), 0U);
}

TEST(DiskScratchStore, ZstdRoundTripChecksCorruptionAndRejectsUnsafeKeys) {
    const auto directory = std::filesystem::temp_directory_path() /
        ("croptop-scratch-" + std::to_string(std::chrono::steady_clock::now().time_since_epoch().count()));
    auto scratch = std::make_shared<croptop::DiskScratchStore>(directory);
    const std::vector<std::uint8_t> payload(4096, 42);
    ASSERT_TRUE(scratch->write("tile_1", payload));
    const auto restored = scratch->read("tile_1");
    ASSERT_TRUE(restored.has_value());
    EXPECT_EQ(*restored, payload);
    EXPECT_FALSE(scratch->write("../escape", payload));
    {
        std::ofstream corrupt(directory / "tile_1.zst", std::ios::binary | std::ios::trunc);
        corrupt << "bad!";
    }
    EXPECT_EQ(scratch->read("tile_1"), std::nullopt);
    EXPECT_TRUE(scratch->erase("tile_1"));
    std::filesystem::remove_all(directory);
}

TEST(MemoryBudgetManager, LruEntriesSpillAndRestoreWithinBudget) {
    const auto directory = std::filesystem::temp_directory_path() /
        ("croptop-budget-" + std::to_string(std::chrono::steady_clock::now().time_since_epoch().count()));
    auto scratch = std::make_shared<croptop::DiskScratchStore>(directory);
    croptop::MemoryBudgetManager budget(4, scratch);
    ASSERT_TRUE(budget.put("tile_a", {1, 2, 3, 4}));
    ASSERT_TRUE(budget.put("tile_b", {5, 6, 7, 8}));
    EXPECT_EQ(budget.residentBytes(), 4U);
    EXPECT_EQ(budget.entryCount(), 2U);
    auto restored_a = budget.get("tile_a");
    ASSERT_NE(restored_a, nullptr);
    EXPECT_EQ(*restored_a, (std::vector<std::uint8_t>{1, 2, 3, 4}));
    EXPECT_LE(budget.residentBytes(), budget.budgetBytes());
    restored_a.reset();

    budget.trimTo(0);
    EXPECT_EQ(budget.residentBytes(), 0U);
    auto restored_b = budget.get("tile_b");
    ASSERT_NE(restored_b, nullptr);
    EXPECT_EQ(*restored_b, (std::vector<std::uint8_t>{5, 6, 7, 8}));
    restored_b.reset();
    EXPECT_TRUE(budget.erase("tile_a"));
    EXPECT_TRUE(budget.erase("tile_b"));
    std::filesystem::remove_all(directory);
}

TEST(TileStore, BudgetedTilesSpillRestoreAndRemainCopyOnWriteAcrossSnapshots) {
    const auto directory = std::filesystem::temp_directory_path() /
        ("croptop-managed-tiles-" + std::to_string(std::chrono::steady_clock::now().time_since_epoch().count()));
    auto scratch = std::make_shared<croptop::DiskScratchStore>(directory);
    auto pool = std::make_shared<croptop::TilePool>();
    {
        auto budget = std::make_shared<croptop::MemoryBudgetManager>(256U * 256U * 4U, scratch);
        croptop::TileStore image(croptop::PixelFormat::RGBA8, 512, 256, pool, budget);
        croptop::TileBuffer first(image.tile_byte_size(), 0);
        croptop::TileBuffer second(image.tile_byte_size(), 0);
        first[0] = 11;
        second[0] = 22;
        ASSERT_TRUE(image.writeTile({0, 0}, first));
        auto snapshot = image;
        ASSERT_TRUE(image.writeTile({1, 0}, second));
        EXPECT_EQ(image.resident_bytes(), image.tile_byte_size());
        EXPECT_EQ(budget->residentBytes(), image.tile_byte_size());

        croptop::TileBuffer changed = first;
        changed[0] = 33;
        ASSERT_TRUE(image.writeTile({0, 0}, changed));
        EXPECT_EQ(image.readTile({0, 0})->front(), 33U);
        EXPECT_EQ(snapshot.readTile({0, 0})->front(), 11U);
        EXPECT_EQ(image.readTile({1, 0})->front(), 22U);
        EXPECT_LE(budget->residentBytes(), budget->budgetBytes());

        croptop::TileStore second_document(croptop::PixelFormat::RGBA8, 256, 256, pool, budget);
        croptop::TileBuffer third(image.tile_byte_size(), 0);
        third[0] = 44;
        ASSERT_TRUE(second_document.writeTile({0, 0}, third));
        EXPECT_EQ(budget->residentBytes(), image.tile_byte_size());
        EXPECT_EQ(image.readTile({0, 0})->front(), 33U);
        EXPECT_EQ(second_document.readTile({0, 0})->front(), 44U);
    }
    std::filesystem::remove_all(directory);
}

TEST(TileStore, SparseTwoHundredMegapixelLayerSpillsAndRestoresUnderOneGiB) {
    const auto directory = std::filesystem::temp_directory_path() /
        ("croptop-200mp-tiles-" + std::to_string(std::chrono::steady_clock::now().time_since_epoch().count()));
    auto scratch = std::make_shared<croptop::DiskScratchStore>(directory);
    auto pool = std::make_shared<croptop::TilePool>();
    constexpr std::uint32_t side = 14143;  // 200,014,449 document pixels.
    constexpr std::size_t budget_bytes = 64U * 1024U * 1024U;
    {
        auto budget = std::make_shared<croptop::MemoryBudgetManager>(budget_bytes, scratch);
        croptop::TileStore image(croptop::PixelFormat::RGBA8, side, side, pool, budget);
        const auto columns = 1U + (side - 1U) / croptop::kTileEdge;
        const auto rows = columns;
        const auto expected_tiles = static_cast<std::size_t>(columns) * rows;
        ASSERT_EQ(expected_tiles, 3136U);
        for (std::uint32_t y = 0; y < rows; ++y) {
            for (std::uint32_t x = 0; x < columns; ++x) {
                croptop::TileBuffer pixels(image.tile_byte_size(), 0);
                pixels[0] = static_cast<std::uint8_t>((y * columns + x) % 251U + 1U);
                ASSERT_TRUE(image.writeTile({static_cast<std::int32_t>(x), static_cast<std::int32_t>(y)}, pixels));
            }
        }
        EXPECT_LE(budget->residentBytes(), budget_bytes);
        EXPECT_LT(budget->residentBytes(), 1024U * 1024U * 1024U);
        EXPECT_EQ(image.resident_bytes(), budget->residentBytes());

        for (std::uint32_t y = 0; y < rows; ++y) {
            for (std::uint32_t x = 0; x < columns; ++x) {
                auto restored = image.readTile({static_cast<std::int32_t>(x), static_cast<std::int32_t>(y)});
                ASSERT_NE(restored, nullptr);
                EXPECT_EQ(restored->front(), static_cast<std::uint8_t>((y * columns + x) % 251U + 1U));
                restored.reset();
            }
        }
    }
    std::filesystem::remove_all(directory);
}

TEST(TileStore, DerivedMipsShareTheDocumentBudgetAndTrimWithoutLosingBasePixels) {
    const auto directory = std::filesystem::temp_directory_path() /
        ("croptop-budgeted-mips-" + std::to_string(std::chrono::steady_clock::now().time_since_epoch().count()));
    auto scratch = std::make_shared<croptop::DiskScratchStore>(directory);
    auto pool = std::make_shared<croptop::TilePool>();
    {
        const auto tile_bytes = 256U * 256U * 4U;
        auto budget = std::make_shared<croptop::MemoryBudgetManager>(2U * tile_bytes, scratch);
        croptop::TileStore image(croptop::PixelFormat::RGBA8, 256, 256, pool, budget);
        croptop::TileBuffer pixels(image.tile_byte_size(), 0);
        pixels[0] = 128;
        pixels[3] = 255;
        ASSERT_TRUE(image.writeTile({0, 0}, pixels));
        ASSERT_NE(image.readMipTile(1, {0, 0}), nullptr);
        EXPECT_EQ(image.mip_resident_bytes(), tile_bytes);
        EXPECT_EQ(budget->residentBytes(), 2U * tile_bytes);

        budget->setBudget(tile_bytes);
        EXPECT_LE(budget->residentBytes(), budget->budgetBytes());
        image.trimCaches();
        EXPECT_EQ(image.mip_resident_bytes(), 0U);
        EXPECT_LE(budget->residentBytes(), budget->budgetBytes());
        EXPECT_EQ(image.readTile({0, 0})->front(), 128U);
    }
    std::filesystem::remove_all(directory);
}

TEST(TileStore, RebuildsDerivedMipWhenSpilledScratchIsMissingOrCorrupt) {
    for (const bool corrupt : {false, true}) {
        const auto directory = std::filesystem::temp_directory_path() /
            ("croptop-missing-mip-" + std::to_string(std::chrono::steady_clock::now().time_since_epoch().count()) +
             (corrupt ? "-corrupt" : "-missing"));
        auto scratch = std::make_shared<croptop::DiskScratchStore>(directory);
        auto pool = std::make_shared<croptop::TilePool>();
        constexpr std::size_t tile_bytes = 256U * 256U * 4U;
        auto budget = std::make_shared<croptop::MemoryBudgetManager>(tile_bytes, scratch);
        {
            croptop::TileStore image(croptop::PixelFormat::RGBA8, 256, 256, pool, budget);
            croptop::TileBuffer pixels(image.tile_byte_size(), 0);
            pixels[0] = 128;
            pixels[3] = 255;
            ASSERT_TRUE(image.writeTile({0, 0}, pixels));
            auto original = image.readMipTile(1, {0, 0});
            ASSERT_NE(original, nullptr);
            const auto expected = *original;
            original.reset();

            // Spill both base and derived data, then damage only the reconstructible mip entry.
            budget->setBudget(0);
            std::filesystem::path mip_path;
            for (const auto& entry : std::filesystem::directory_iterator(directory)) {
                const auto filename = entry.path().filename().string();
                if (filename.find("_m1_0_0.zst") != std::string::npos) mip_path = entry.path();
            }
            ASSERT_FALSE(mip_path.empty());
            if (corrupt) {
                std::ofstream stream(mip_path, std::ios::binary | std::ios::trunc);
                ASSERT_TRUE(stream.good());
                stream << "invalid zstd payload";
                ASSERT_TRUE(stream.good());
            } else {
                ASSERT_TRUE(std::filesystem::remove(mip_path));
            }

            const auto rebuilt = image.readMipTile(1, {0, 0});
            ASSERT_NE(rebuilt, nullptr) << (corrupt ? "corrupt" : "missing") << " scratch mip";
            EXPECT_EQ(*rebuilt, expected);
            EXPECT_NE(image.readTile({0, 0}), nullptr);
        }
        std::filesystem::remove_all(directory);
    }
}

}  // namespace
