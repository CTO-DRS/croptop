#include "job_system.h"

#include <algorithm>
#include <cmath>
#include <utility>

namespace croptop {

namespace {
thread_local JobPool* current_worker_pool = nullptr;
thread_local std::size_t current_worker_index = 0;
}  // namespace

JobContext::JobContext(JobPool* owner, std::uint64_t job_id, std::shared_ptr<std::atomic_bool> cancelled)
    : owner_(owner), job_id_(job_id), cancelled_(std::move(cancelled)) {}
bool JobContext::cancellationRequested() const noexcept { return cancelled_->load(std::memory_order_relaxed); }
void JobContext::reportProgress(float progress) const {
    if (owner_ != nullptr) owner_->reportProgress(job_id_, progress);
}

JobHandle::JobHandle(std::shared_ptr<std::atomic_bool> cancelled, std::uint64_t job_id)
    : cancelled_(std::move(cancelled)), job_id_(job_id) {}
void JobHandle::cancel() const noexcept {
    if (cancelled_) cancelled_->store(true, std::memory_order_relaxed);
}
bool JobHandle::cancellationRequested() const noexcept {
    return !cancelled_ || cancelled_->load(std::memory_order_relaxed);
}
bool JobHandle::valid() const noexcept { return static_cast<bool>(cancelled_); }
std::uint64_t JobHandle::jobId() const noexcept { return job_id_; }

bool JobPool::LowerPriorityFirst::operator()(const Work& a, const Work& b) const noexcept {
    if (a.priority != b.priority) return a.priority > b.priority;
    return a.sequence > b.sequence;
}

JobPriority JobPool::effectivePriority(const Work& work, std::chrono::steady_clock::time_point now) noexcept {
    constexpr auto kPriorityAgingInterval = std::chrono::milliseconds(100);
    const auto age = now - work.enqueued_at;
    const auto promotions = std::chrono::duration_cast<std::chrono::milliseconds>(age) / kPriorityAgingInterval;
    const auto priority = static_cast<std::int64_t>(work.priority);
    return static_cast<JobPriority>(std::max<std::int64_t>(0, priority - promotions));
}

JobPool::JobPool(std::size_t worker_count, std::size_t pending_capacity, std::size_t progress_capacity)
    : pending_capacity_(pending_capacity), progress_capacity_(progress_capacity), progress_events_(progress_capacity) {
    if (worker_count == 0) worker_count = std::min(32U, std::max(1U, std::thread::hardware_concurrency()));
    local_queues_.reserve(worker_count);
    for (std::size_t i = 0; i < worker_count; ++i) local_queues_.push_back(std::make_unique<LocalQueue>());
    workers_.reserve(worker_count);
    for (std::size_t i = 0; i < worker_count; ++i) workers_.emplace_back([this, i] { run(i); });
}

JobPool::~JobPool() {
    {
        const std::lock_guard submit_lock(submit_mutex_);
        {
            const std::lock_guard lock(mutex_);
            stopping_ = true;
            while (!pending_.empty()) {
                pending_.top().cancelled->store(true, std::memory_order_relaxed);
                pending_.pop();
                pending_count_.fetch_sub(1U, std::memory_order_relaxed);
            }
            for (auto& weak : active_) if (auto state = weak.lock()) state->store(true, std::memory_order_relaxed);
        }
        for (auto& queue : local_queues_) {
            const std::lock_guard lock(queue->mutex);
            for (auto& work : queue->pending) work.cancelled->store(true, std::memory_order_relaxed);
            pending_count_.fetch_sub(queue->pending.size(), std::memory_order_relaxed);
            queue->pending.clear();
        }
    }
    ready_.notify_all();
    for (auto& worker : workers_) if (worker.joinable()) worker.join();
}

std::optional<JobHandle> JobPool::submit(JobPriority priority, Task task) {
    if (!task) return std::nullopt;
    auto cancelled = std::make_shared<std::atomic_bool>(false);
    std::uint64_t job_id = 0;
    {
        const std::lock_guard submit_lock(submit_mutex_);
        {
            const std::lock_guard lock(mutex_);
            if (stopping_) return std::nullopt;
        }

        auto prune_cancelled = [this](std::vector<Work>& removed) {
            const auto previous_size = removed.size();
            removed.erase(std::remove_if(removed.begin(), removed.end(), [](const Work& work) {
                return work.cancelled->load(std::memory_order_relaxed);
            }), removed.end());
            pending_count_.fetch_sub(previous_size - removed.size(), std::memory_order_relaxed);
        };
        std::vector<Work> global;
        {
            const std::lock_guard lock(mutex_);
            while (!pending_.empty()) {
                global.push_back(pending_.top());
                pending_.pop();
            }
            prune_cancelled(global);
            for (auto& work : global) pending_.push(std::move(work));
        }
        for (auto& queue : local_queues_) {
            const std::lock_guard lock(queue->mutex);
            prune_cancelled(queue->pending);
        }

        while (pending_count_.load(std::memory_order_relaxed) >= pending_capacity_) {
            bool local_victim = false;
            std::size_t victim_queue = 0;
            std::uint64_t victim_id = 0;
            JobPriority victim_priority = JobPriority::Interactive;
            bool found_victim = false;
            const auto now = std::chrono::steady_clock::now();
            auto consider = [&](const Work& candidate, bool local, std::size_t queue_index) {
                const auto candidate_priority = effectivePriority(candidate, now);
                if (!found_victim || candidate_priority > victim_priority ||
                    (candidate_priority == victim_priority && candidate.sequence > victim_id)) {
                    found_victim = true;
                    local_victim = local;
                    victim_queue = queue_index;
                    victim_priority = candidate_priority;
                    victim_id = candidate.sequence;
                }
            };
            {
                const std::lock_guard lock(mutex_);
                auto queued = pending_;
                while (!queued.empty()) {
                    consider(queued.top(), false, 0);
                    queued.pop();
                }
            }
            for (std::size_t i = 0; i < local_queues_.size(); ++i) {
                const std::lock_guard lock(local_queues_[i]->mutex);
                for (const auto& work : local_queues_[i]->pending) consider(work, true, i);
            }
            if (!found_victim || victim_priority <= priority) return std::nullopt;

            bool removed = false;
            if (local_victim) {
                const std::lock_guard lock(local_queues_[victim_queue]->mutex);
                auto& queued = local_queues_[victim_queue]->pending;
                const auto found = std::find_if(queued.begin(), queued.end(), [victim_id](const Work& work) {
                    return work.sequence == victim_id;
                });
                if (found != queued.end()) {
                    found->cancelled->store(true, std::memory_order_relaxed);
                    queued.erase(found);
                    removed = true;
                }
            } else {
                const std::lock_guard lock(mutex_);
                std::vector<Work> queued;
                while (!pending_.empty()) {
                    auto work = pending_.top();
                    pending_.pop();
                    if (work.sequence == victim_id) {
                        work.cancelled->store(true, std::memory_order_relaxed);
                        removed = true;
                    } else {
                        queued.push_back(std::move(work));
                    }
                }
                for (auto& work : queued) pending_.push(std::move(work));
            }
            if (removed) pending_count_.fetch_sub(1U, std::memory_order_relaxed);
        }

        job_id = next_sequence_++;
        Work work{priority, job_id, std::chrono::steady_clock::now(), std::move(task), cancelled};
        if (current_worker_pool == this && current_worker_index < local_queues_.size()) {
            const std::lock_guard lock(local_queues_[current_worker_index]->mutex);
            local_queues_[current_worker_index]->pending.push_back(std::move(work));
        } else {
            const std::lock_guard lock(mutex_);
            pending_.push(std::move(work));
        }
        pending_count_.fetch_add(1U, std::memory_order_relaxed);
    }
    ready_.notify_all();
    return JobHandle(std::move(cancelled), job_id);
}

std::size_t JobPool::workerCount() const noexcept { return workers_.size(); }
std::size_t JobPool::pendingCount() const { return pending_count_.load(std::memory_order_relaxed); }

void JobPool::reportProgress(std::uint64_t job_id, float progress) {
    if (progress_capacity_ == 0 || !std::isfinite(progress)) return;
    const auto normalized = std::clamp(progress, 0.0F, 1.0F);
    const std::lock_guard lock(progress_mutex_);
    if (progress_count_ == progress_capacity_) {
        progress_events_[progress_head_] = ProgressEvent{job_id, normalized};
        progress_head_ = (progress_head_ + 1U) % progress_capacity_;
    } else {
        const auto tail = (progress_head_ + progress_count_) % progress_capacity_;
        progress_events_[tail] = ProgressEvent{job_id, normalized};
        ++progress_count_;
    }
}

std::vector<JobPool::ProgressEvent> JobPool::drainProgress() {
    const std::lock_guard lock(progress_mutex_);
    std::vector<ProgressEvent> result;
    result.reserve(progress_count_);
    for (std::size_t i = 0; i < progress_count_; ++i) {
        result.push_back(progress_events_[(progress_head_ + i) % progress_capacity_]);
    }
    progress_head_ = 0;
    progress_count_ = 0;
    return result;
}

std::optional<JobPool::Work> JobPool::takeWork(std::size_t worker_index) {
    const auto now = std::chrono::steady_clock::now();
    std::optional<Work> global_work;
    {
        const std::lock_guard lock(mutex_);
        if (!pending_.empty()) {
            std::vector<Work> queued;
            queued.reserve(pending_.size());
            while (!pending_.empty()) {
                queued.push_back(pending_.top());
                pending_.pop();
            }
            const auto best = std::min_element(queued.begin(), queued.end(), [now](const Work& a, const Work& b) {
                const auto priority_a = effectivePriority(a, now);
                const auto priority_b = effectivePriority(b, now);
                if (priority_a != priority_b) return priority_a < priority_b;
                return a.sequence < b.sequence;
            });
            global_work = std::move(*best);
            queued.erase(best);
            for (auto& pending : queued) pending_.push(std::move(pending));
        }
    }

    auto take_local = [this, now](std::size_t index) -> std::optional<std::pair<std::size_t, Work>> {
        const std::lock_guard lock(local_queues_[index]->mutex);
        auto& pending = local_queues_[index]->pending;
        if (pending.empty()) return std::nullopt;
        const auto best = std::min_element(pending.begin(), pending.end(), [now](const Work& a, const Work& b) {
            const auto priority_a = effectivePriority(a, now);
            const auto priority_b = effectivePriority(b, now);
            if (priority_a != priority_b) return priority_a < priority_b;
            return a.sequence < b.sequence;
        });
        Work work = std::move(*best);
        pending.erase(best);
        return std::pair<std::size_t, Work>{index, std::move(work)};
    };

    std::optional<std::pair<std::size_t, Work>> local_work = take_local(worker_index);
    if (!local_work.has_value()) {
        for (std::size_t offset = 1; offset < local_queues_.size(); ++offset) {
            const auto victim = (worker_index + offset) % local_queues_.size();
            local_work = take_local(victim);
            if (local_work.has_value()) break;
        }
    }

    if (!global_work.has_value() && !local_work.has_value()) return std::nullopt;
    if (!global_work.has_value()) {
        pending_count_.fetch_sub(1U, std::memory_order_relaxed);
        return std::move(local_work->second);
    }
    if (!local_work.has_value()) {
        pending_count_.fetch_sub(1U, std::memory_order_relaxed);
        return global_work;
    }

    const auto global_priority = effectivePriority(*global_work, now);
    const auto local_priority = effectivePriority(local_work->second, now);
    if (global_priority < local_priority ||
        (global_priority == local_priority && global_work->sequence < local_work->second.sequence)) {
        const std::lock_guard pool_lock(mutex_);
        const std::lock_guard lock(local_queues_[local_work->first]->mutex);
        if (stopping_) {
            local_work->second.cancelled->store(true, std::memory_order_relaxed);
            pending_count_.fetch_sub(2U, std::memory_order_relaxed);
        } else {
            local_queues_[local_work->first]->pending.push_back(std::move(local_work->second));
            pending_count_.fetch_sub(1U, std::memory_order_relaxed);
        }
        return global_work;
    }

    {
        const std::lock_guard lock(mutex_);
        if (stopping_) {
            global_work->cancelled->store(true, std::memory_order_relaxed);
            pending_count_.fetch_sub(2U, std::memory_order_relaxed);
        } else {
            pending_.push(std::move(*global_work));
            pending_count_.fetch_sub(1U, std::memory_order_relaxed);
        }
    }
    return std::move(local_work->second);
}

void JobPool::run(std::size_t worker_index) {
    current_worker_pool = this;
    current_worker_index = worker_index;
    for (;;) {
        std::optional<Work> next;
        {
            std::unique_lock lock(mutex_);
            ready_.wait(lock, [this] {
                return stopping_ || pending_count_.load(std::memory_order_relaxed) != 0;
            });
            if (stopping_) {
                current_worker_pool = nullptr;
                return;
            }
        }
        next = takeWork(worker_index);
        if (!next) continue;
        auto& work = *next;
        bool run_task = false;
        {
            const std::lock_guard lock(mutex_);
            if (stopping_) {
                work.cancelled->store(true, std::memory_order_relaxed);
            } else {
                active_.erase(std::remove_if(active_.begin(), active_.end(), [](const auto& state) {
                    return state.expired();
                }), active_.end());
                active_.push_back(work.cancelled);
                run_task = true;
            }
        }
        if (run_task && !work.cancelled->load(std::memory_order_relaxed)) {
            try { work.task(JobContext(this, work.sequence, work.cancelled)); } catch (...) { /* Keep worker threads alive. */ }
        }
        {
            const std::lock_guard lock(mutex_);
            active_.erase(std::remove_if(active_.begin(), active_.end(), [&work](const auto& state) {
                const auto active = state.lock();
                return !active || active == work.cancelled;
            }), active_.end());
        }
    }
}

}  // namespace croptop
