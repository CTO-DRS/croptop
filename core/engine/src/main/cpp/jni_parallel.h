#pragma once

#include "job_system.h"

#include <algorithm>
#include <atomic>
#include <chrono>
#include <condition_variable>
#include <cstddef>
#include <exception>
#include <functional>
#include <mutex>
#include <utility>
#include <vector>

namespace croptop::jni {

/** Shared CPU pool for independent native render/export tiles. */
inline JobPool& renderJobPool() {
    static JobPool pool(0, 256, 0);
    return pool;
}

/** Keeps concurrent callers from submitting large tile batches at the same instant. */
inline std::mutex& renderBatchMutex() {
    static std::mutex mutex;
    return mutex;
}

/**
 * Runs bounded batches over the shared worker pool. Each batch is fully joined before `after_batch` runs,
 * which lets render callers release the tile cache between batches and keep export memory bounded.
 */
template <typename Work, typename AfterBatch>
void parallelRenderBatches(JobPool& pool, std::mutex& serialization_mutex, std::size_t count, std::size_t batch_size,
                           JobPriority priority, Work&& work, AfterBatch&& after_batch) {
    if (count == 0U) return;
    if (count == 1U) {
        std::invoke(work, 0U);
        std::invoke(after_batch);
        return;
    }
    // Limit each batch to one tile per worker so a visible batch can be scheduled
    // between background tiles instead of waiting for workers to drain a large range.
    const auto batch_limit = std::max<std::size_t>(1U, std::min(batch_size, pool.workerCount()));

    struct BatchState final {
        std::atomic<std::size_t> completed{0};
        std::atomic_bool failed{false};
        std::mutex failure_mutex;
        std::exception_ptr failure;
        std::mutex completion_mutex;
        std::condition_variable completion;
    };

    for (std::size_t begin = 0; begin < count; begin += batch_limit) {
        // Yield the shared pool between bounded batches so a newly queued Visible render can run
        // before a long Background flatten continues with its next group of tiles.
        std::unique_lock batch_lock(serialization_mutex);
        const auto length = std::min(batch_limit, count - begin);
        BatchState state;
        const auto task_count = std::min(length, pool.workerCount());
        std::vector<std::atomic_bool> started(task_count);
        std::vector<JobHandle> handles;
        handles.reserve(task_count);
        std::size_t submitted = 0;
        for (; submitted < task_count; ++submitted) {
            const auto lane = submitted;
            const auto handle = pool.submit(priority, [&state, &started, &work, begin, lane](const JobContext& context) {
                started[lane].store(true, std::memory_order_release);
                state.completion.notify_one();
                try {
                    if (!state.failed.load(std::memory_order_relaxed) && !context.cancellationRequested()) {
                        std::invoke(work, begin + lane);
                    }
                } catch (...) {
                    {
                        const std::lock_guard failure_lock(state.failure_mutex);
                        if (state.failure == nullptr) state.failure = std::current_exception();
                    }
                    state.failed.store(true, std::memory_order_relaxed);
                }
                state.completed.fetch_add(1U, std::memory_order_release);
                state.completion.notify_one();
            });
            if (!handle) break;
            handles.push_back(*handle);
        }
        // Let another caller enqueue Visible work while this bounded batch runs.
        batch_lock.unlock();

        if (submitted == 0U) {
            // Back-pressure may reject every worker task; preserve completion semantics on the caller.
            for (std::size_t index = 0; index < length; ++index) std::invoke(work, begin + index);
        } else {
            std::unique_lock completion_lock(state.completion_mutex);
            const auto all_lanes_resolved = [&] {
                for (std::size_t lane = 0; lane < submitted; ++lane) {
                    if (!started[lane].load(std::memory_order_acquire) && !handles[lane].cancellationRequested()) return false;
                }
                return true;
            };
            while (!all_lanes_resolved()) state.completion.wait_for(completion_lock, std::chrono::milliseconds(1));
            const auto cancelled_before_start = [&] {
                for (std::size_t lane = 0; lane < submitted; ++lane) {
                    if (!started[lane].load(std::memory_order_acquire)) return true;
                }
                return false;
            };
            if (submitted < length || cancelled_before_start()) {
                // A higher-priority submission may evict a queued worker. Run only
                // lanes that never started; each running lane owns exactly one tile.
                try {
                    for (std::size_t lane = 0; lane < length && !state.failed.load(std::memory_order_relaxed); ++lane) {
                        if (lane >= submitted || !started[lane].load(std::memory_order_acquire)) {
                            std::invoke(work, begin + lane);
                        }
                    }
                } catch (...) {
                    {
                        const std::lock_guard failure_lock(state.failure_mutex);
                        if (state.failure == nullptr) state.failure = std::current_exception();
                    }
                    state.failed.store(true, std::memory_order_relaxed);
                }
            }
            auto started_count = std::size_t{0};
            for (std::size_t lane = 0; lane < submitted; ++lane) {
                if (started[lane].load(std::memory_order_acquire)) ++started_count;
            }
            state.completion.wait(completion_lock, [&] {
                return state.completed.load(std::memory_order_acquire) == started_count;
            });
            if (state.failure != nullptr) std::rethrow_exception(state.failure);
            // Each started worker and the caller own disjoint lanes.
        }
        std::invoke(after_batch);
    }
}

template <typename Work>
void parallelRenderBatches(JobPool& pool, std::mutex& serialization_mutex, std::size_t count, std::size_t batch_size,
                           JobPriority priority, Work&& work) {
    parallelRenderBatches(pool, serialization_mutex, count, batch_size, priority, std::forward<Work>(work), [] {});
}

template <typename Work, typename AfterBatch>
void parallelRenderBatches(std::size_t count, std::size_t batch_size, JobPriority priority, Work&& work,
                           AfterBatch&& after_batch) {
    if (count == 0U) return;
    if (count == 1U) {
        std::invoke(work, 0U);
        std::invoke(after_batch);
        return;
    }
    parallelRenderBatches(renderJobPool(), renderBatchMutex(), count, batch_size, priority, std::forward<Work>(work),
                          std::forward<AfterBatch>(after_batch));
}

template <typename Work>
void parallelRenderBatches(std::size_t count, std::size_t batch_size, JobPriority priority, Work&& work) {
    parallelRenderBatches(renderJobPool(), renderBatchMutex(), count, batch_size, priority,
                          std::forward<Work>(work), [] {});
}

}  // namespace croptop::jni
