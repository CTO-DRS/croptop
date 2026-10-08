// Deterministic latest-wins preview scheduling regressions.
#include "adjustment_preview_scheduler.h"
#include "selection_mask.h"

#include <gtest/gtest.h>

#include <atomic>
#include <chrono>
#include <condition_variable>
#include <cstring>
#include <mutex>
#include <thread>
#include <vector>

namespace {

using croptop::PixelFormat;
using croptop::TileBuffer;
using croptop::TileStore;
using croptop::adjust::preview::CancellationToken;
using croptop::adjust::preview::LatestWinsScheduler;

TileStore onePixel(std::uint16_t value) {
    TileStore pixels(PixelFormat::A16, 1, 1);
    TileBuffer tile(pixels.tile_byte_size(), 0);
    std::memcpy(tile.data(), &value, sizeof(value));
    EXPECT_TRUE(pixels.writeTile({0, 0}, tile));
    return pixels;
}

template <typename Predicate>
bool waitFor(std::condition_variable& changed, std::unique_lock<std::mutex>& lock, Predicate predicate) {
    return changed.wait_for(lock, std::chrono::seconds(3), predicate);
}

TEST(AdjustmentPreviewScheduler, NewestPendingRequestSupersedesIntermediateUpdates) {
    LatestWinsScheduler scheduler;
    std::mutex mutex;
    std::condition_variable changed;
    bool first_started = false;
    bool release_first = false;
    bool first_completed = false;
    int intermediate_runs = 0;
    int newest_runs = 0;
    std::vector<LatestWinsScheduler::Generation> completed;
    std::uint16_t delivered_coverage = 0;

    const auto first_generation = scheduler.submit(
        [&](const CancellationToken& token) {
            std::unique_lock lock(mutex);
            first_started = true;
            changed.notify_all();
            while (!release_first && !token.isCancelled()) changed.wait_for(lock, std::chrono::milliseconds(2));
            lock.unlock();
            return std::optional<TileStore>{onePixel(100)};
        },
        [&](auto generation, std::optional<TileStore>) {
            std::lock_guard lock(mutex);
            first_completed = true;
            completed.push_back(generation);
            changed.notify_all();
        });
    ASSERT_NE(first_generation, 0U);
    {
        std::unique_lock lock(mutex);
        ASSERT_TRUE(waitFor(changed, lock, [&] { return first_started; }));
    }

    const auto intermediate_generation = scheduler.submit(
        [&](const CancellationToken&) {
            ++intermediate_runs;
            return std::optional<TileStore>{onePixel(200)};
        },
        [&](auto generation, std::optional<TileStore>) {
            std::lock_guard lock(mutex);
            completed.push_back(generation);
            changed.notify_all();
        });
    const auto newest_generation = scheduler.submit(
        [&](const CancellationToken&) {
            ++newest_runs;
            return std::optional<TileStore>{onePixel(300)};
        },
        [&](auto generation, std::optional<TileStore> result) {
            std::lock_guard lock(mutex);
            completed.push_back(generation);
            if (result) delivered_coverage = croptop::selection::coverageAt(*result, 0, 0);
            changed.notify_all();
        });
    ASSERT_NE(intermediate_generation, 0U);
    ASSERT_GT(newest_generation, intermediate_generation);
    {
        std::lock_guard lock(mutex);
        release_first = true;
    }
    changed.notify_all();
    {
        std::unique_lock lock(mutex);
        ASSERT_TRUE(waitFor(changed, lock, [&] { return !completed.empty(); }));
        EXPECT_EQ(completed, (std::vector<LatestWinsScheduler::Generation>{newest_generation}));
        EXPECT_EQ(delivered_coverage, 300);
        EXPECT_FALSE(first_completed);
    }
    EXPECT_EQ(intermediate_runs, 0);
    EXPECT_EQ(newest_runs, 1);
    scheduler.shutdown();
}

TEST(AdjustmentPreviewScheduler, CancelInvalidatesRunningAndDropsPendingWork) {
    LatestWinsScheduler scheduler;
    std::mutex mutex;
    std::condition_variable changed;
    bool running_started = false;
    bool running_exited = false;
    int pending_runs = 0;
    std::vector<LatestWinsScheduler::Generation> completed;

    ASSERT_NE(scheduler.submit(
        [&](const CancellationToken& token) {
            std::unique_lock lock(mutex);
            running_started = true;
            changed.notify_all();
            while (!token.isCancelled()) changed.wait_for(lock, std::chrono::milliseconds(2));
            running_exited = true;
            changed.notify_all();
            return std::optional<TileStore>{onePixel(111)};
        },
        [&](auto generation, std::optional<TileStore>) {
            std::lock_guard lock(mutex);
            completed.push_back(generation);
            changed.notify_all();
        }), 0U);
    {
        std::unique_lock lock(mutex);
        ASSERT_TRUE(waitFor(changed, lock, [&] { return running_started; }));
    }
    scheduler.submit(
        [&](const CancellationToken&) {
            ++pending_runs;
            return std::optional<TileStore>{onePixel(222)};
        },
        [&](auto generation, std::optional<TileStore>) {
            std::lock_guard lock(mutex);
            completed.push_back(generation);
            changed.notify_all();
        });
    scheduler.cancel();

    const auto fence_generation = scheduler.submit(
        [&](const CancellationToken&) { return std::optional<TileStore>{onePixel(333)}; },
        [&](auto generation, std::optional<TileStore>) {
            std::lock_guard lock(mutex);
            completed.push_back(generation);
            changed.notify_all();
        });
    ASSERT_NE(fence_generation, 0U);
    {
        std::unique_lock lock(mutex);
        ASSERT_TRUE(waitFor(changed, lock, [&] { return !completed.empty(); }));
        EXPECT_TRUE(running_exited);
        EXPECT_EQ(completed, (std::vector<LatestWinsScheduler::Generation>{fence_generation}));
    }
    EXPECT_EQ(pending_runs, 0);
    scheduler.shutdown();
}

TEST(AdjustmentPreviewScheduler, ShutdownCancelsRunningWorkAndRejectsFurtherRequests) {
    LatestWinsScheduler scheduler;
    std::mutex mutex;
    std::condition_variable changed;
    bool started = false;
    bool exited = false;
    int completions = 0;
    int pending_runs = 0;

    ASSERT_NE(scheduler.submit(
        [&](const CancellationToken& token) {
            std::unique_lock lock(mutex);
            started = true;
            changed.notify_all();
            while (!token.isCancelled()) changed.wait_for(lock, std::chrono::milliseconds(2));
            exited = true;
            changed.notify_all();
            return std::optional<TileStore>{onePixel(444)};
        },
        [&](auto, std::optional<TileStore>) { ++completions; }), 0U);
    {
        std::unique_lock lock(mutex);
        ASSERT_TRUE(waitFor(changed, lock, [&] { return started; }));
    }
    scheduler.submit(
        [&](const CancellationToken&) {
            ++pending_runs;
            return std::optional<TileStore>{onePixel(555)};
        },
        [&](auto, std::optional<TileStore>) { ++completions; });

    scheduler.shutdown();
    EXPECT_EQ(scheduler.submit([](const CancellationToken&) { return std::optional<TileStore>{onePixel(666)}; },
                               [](auto, std::optional<TileStore>) {}), 0U);
    EXPECT_TRUE(exited);
    EXPECT_EQ(pending_runs, 0);
    EXPECT_EQ(completions, 0);
    EXPECT_EQ(scheduler.latestGeneration(), 3U);
}

TEST(AdjustmentPreviewScheduler, SubmitWaitsForAnInFlightCompletionCallback) {
    LatestWinsScheduler scheduler;
    std::mutex mutex;
    std::condition_variable changed;
    bool callback_started = false;
    bool release_callback = false;
    bool submit_calling = false;
    std::atomic_bool submit_returned{false};
    LatestWinsScheduler::Generation submitted_generation = 0;
    bool newest_completed = false;

    const auto first_generation = scheduler.submit(
        [&](const CancellationToken&) { return std::optional<TileStore>{onePixel(777)}; },
        [&](auto, std::optional<TileStore>) {
            std::unique_lock lock(mutex);
            callback_started = true;
            changed.notify_all();
            changed.wait(lock, [&] { return release_callback; });
        });
    ASSERT_NE(first_generation, 0U);
    bool did_start = false;
    {
        std::unique_lock lock(mutex);
        did_start = waitFor(changed, lock, [&] { return callback_started; });
    }
    EXPECT_TRUE(did_start);

    std::thread submitter([&] {
        {
            std::lock_guard lock(mutex);
            submit_calling = true;
            changed.notify_all();
        }
        submitted_generation = scheduler.submit(
            [&](const CancellationToken&) { return std::optional<TileStore>{onePixel(888)}; },
            [&](auto, std::optional<TileStore>) {
                std::lock_guard lock(mutex);
                newest_completed = true;
                changed.notify_all();
            });
        submit_returned.store(true, std::memory_order_release);
    });
    {
        std::unique_lock lock(mutex);
        EXPECT_TRUE(waitFor(changed, lock, [&] { return submit_calling; }));
    }
    std::this_thread::sleep_for(std::chrono::milliseconds(10));
    EXPECT_FALSE(submit_returned.load(std::memory_order_acquire));
    {
        std::lock_guard lock(mutex);
        release_callback = true;
    }
    changed.notify_all();
    submitter.join();
    EXPECT_NE(submitted_generation, 0U);
    EXPECT_TRUE(submit_returned.load(std::memory_order_acquire));
    {
        std::unique_lock lock(mutex);
        EXPECT_TRUE(waitFor(changed, lock, [&] { return newest_completed; }));
    }
    scheduler.shutdown();
}

}  // namespace
