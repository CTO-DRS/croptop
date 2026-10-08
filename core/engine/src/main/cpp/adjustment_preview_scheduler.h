#pragma once

// Single-worker, latest-wins scheduling primitive for asynchronous adjustment previews.
// The caller owns debounce timing, immutable document snapshots, UI dispatch, and final document/session
// freshness checks. Work must periodically inspect its cancellation token; shutdown joins that work.

#include <atomic>
#include <condition_variable>
#include <cstdint>
#include <functional>
#include <memory>
#include <mutex>
#include <optional>
#include <thread>
#include <utility>

#include "tile_store.h"

namespace croptop::adjust::preview {

class CancellationToken final {
public:
    bool isCancelled() const noexcept { return cancelled_ && cancelled_->load(std::memory_order_acquire); }

private:
    friend class LatestWinsScheduler;
    explicit CancellationToken(std::shared_ptr<std::atomic_bool> cancelled) : cancelled_(std::move(cancelled)) {}
    std::shared_ptr<std::atomic_bool> cancelled_;
};

/**
 * Runs at most one preview and retains at most one pending request. A newly submitted request
 * cancels running work and replaces pending work. A result is delivered only if its generation is
 * still current at the scheduler's publication check. Work and completion callbacks run on the
 * worker thread; callbacks should hand off quickly to the UI thread and validate the generation/session
 * before publishing there. Submit/cancel wait for an in-progress completion callback to return, which
 * makes their return a barrier against older callback delivery. Do not call scheduler methods from a callback.
 *
 * Cancellation is cooperative. shutdown() invalidates pending/running work, signals its token and
 * waits for the worker to exit, so work must not block indefinitely without checking cancellation.
 * No timer is included: the caller submits immediately or after its own debounce/settle policy.
 */
class LatestWinsScheduler final {
public:
    using Generation = std::uint64_t;
    using Work = std::function<std::optional<TileStore>(const CancellationToken&)>;
    using Completion = std::function<void(Generation, std::optional<TileStore>)>;

    LatestWinsScheduler();
    ~LatestWinsScheduler();
    LatestWinsScheduler(const LatestWinsScheduler&) = delete;
    LatestWinsScheduler& operator=(const LatestWinsScheduler&) = delete;

    /** Returns a nonzero generation, or 0 when shutdown or passed an empty callback. */
    Generation submit(Work work, Completion completion);
    /** Cancels running work, drops pending work, and advances the freshness generation. */
    void cancel();
    /** Idempotently stops accepting work, invalidates results, and joins the worker. */
    void shutdown();
    Generation latestGeneration() const;

private:
    struct Job final {
        Generation generation = 0;
        Work work;
        Completion completion;
        std::shared_ptr<std::atomic_bool> cancelled;
    };

    void run();

    mutable std::mutex mutex_;
    std::mutex publication_mutex_;
    std::mutex shutdown_mutex_;
    std::condition_variable wake_;
    std::optional<Job> pending_;
    std::shared_ptr<std::atomic_bool> running_cancelled_;
    Generation latest_generation_ = 0;
    bool stopped_ = false;
    std::thread worker_;
};

}  // namespace croptop::adjust::preview
