#pragma once

#include <atomic>
#include <condition_variable>
#include <chrono>
#include <cstddef>
#include <cstdint>
#include <functional>
#include <memory>
#include <mutex>
#include <optional>
#include <queue>
#include <thread>
#include <vector>

namespace croptop {

class JobPool;

enum class JobPriority : std::uint8_t { Interactive, Visible, Background, Idle };

class JobContext final {
public:
    bool cancellationRequested() const noexcept;
    /** Adds a bounded progress event; non-finite values are ignored and others clamped to [0, 1]. */
    void reportProgress(float progress) const;

private:
    friend class JobPool;
    JobContext(JobPool* owner, std::uint64_t job_id, std::shared_ptr<std::atomic_bool> cancelled);
    JobPool* owner_;
    std::uint64_t job_id_;
    std::shared_ptr<std::atomic_bool> cancelled_;
};

class JobHandle final {
public:
    JobHandle() = default;
    void cancel() const noexcept;
    bool cancellationRequested() const noexcept;
    bool valid() const noexcept;
    std::uint64_t jobId() const noexcept;

private:
    friend class JobPool;
    JobHandle(std::shared_ptr<std::atomic_bool> cancelled, std::uint64_t job_id);
    std::shared_ptr<std::atomic_bool> cancelled_;
    std::uint64_t job_id_ = 0;
};

/** Bounded priority executor. Tasks must poll JobContext to stop promptly. */
class JobPool final {
public:
    using Task = std::function<void(const JobContext&)>;

    struct ProgressEvent final {
        std::uint64_t job_id;
        float progress;
    };

    explicit JobPool(std::size_t worker_count = 0, std::size_t pending_capacity = 256,
                     std::size_t progress_capacity = 256);
    ~JobPool();
    JobPool(const JobPool&) = delete;
    JobPool& operator=(const JobPool&) = delete;

    /** A higher-priority task evicts queued lower-priority work at capacity; otherwise returns nullopt. */
    std::optional<JobHandle> submit(JobPriority priority, Task task);
    std::size_t workerCount() const noexcept;
    std::size_t pendingCount() const;
    /** Returns queued progress events in report order, clearing the ring. */
    std::vector<ProgressEvent> drainProgress();

private:
    struct Work {
        JobPriority priority;
        std::uint64_t sequence;
        std::chrono::steady_clock::time_point enqueued_at;
        Task task;
        std::shared_ptr<std::atomic_bool> cancelled;
    };
    struct LocalQueue {
        std::mutex mutex;
        std::vector<Work> pending;
    };
    friend class JobContext;
    void reportProgress(std::uint64_t job_id, float progress);
    static JobPriority effectivePriority(const Work& work, std::chrono::steady_clock::time_point now) noexcept;
    struct LowerPriorityFirst {
        bool operator()(const Work& a, const Work& b) const noexcept;
    };
    std::optional<Work> takeWork(std::size_t worker_index);
    void run(std::size_t worker_index);

    const std::size_t pending_capacity_;
    const std::size_t progress_capacity_;
    mutable std::mutex submit_mutex_;
    mutable std::mutex mutex_;
    mutable std::mutex progress_mutex_;
    std::vector<ProgressEvent> progress_events_;
    std::size_t progress_head_ = 0;
    std::size_t progress_count_ = 0;
    std::condition_variable ready_;
    std::priority_queue<Work, std::vector<Work>, LowerPriorityFirst> pending_;
    std::vector<std::unique_ptr<LocalQueue>> local_queues_;
    std::vector<std::thread> workers_;
    std::vector<std::weak_ptr<std::atomic_bool>> active_;
    std::atomic<std::size_t> pending_count_{0};
    std::uint64_t next_sequence_ = 0;
    bool stopping_ = false;
};

}  // namespace croptop
