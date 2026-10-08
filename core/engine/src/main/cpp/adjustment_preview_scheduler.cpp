#include "adjustment_preview_scheduler.h"

namespace croptop::adjust::preview {

LatestWinsScheduler::LatestWinsScheduler() : worker_([this] { run(); }) {}

LatestWinsScheduler::~LatestWinsScheduler() { shutdown(); }

LatestWinsScheduler::Generation LatestWinsScheduler::submit(Work work, Completion completion) {
    if (!work || !completion) return 0;
    Generation generation = 0;
    {
        const std::lock_guard publication_lock(publication_mutex_);
        const std::lock_guard lock(mutex_);
        if (stopped_) return 0;
        if (running_cancelled_) running_cancelled_->store(true, std::memory_order_release);
        ++latest_generation_;
        if (latest_generation_ == 0) ++latest_generation_;  // Keep zero as the rejection sentinel.
        generation = latest_generation_;
        pending_ = Job{generation, std::move(work), std::move(completion), std::make_shared<std::atomic_bool>(false)};
    }
    wake_.notify_one();
    return generation;
}

void LatestWinsScheduler::cancel() {
    {
        const std::lock_guard publication_lock(publication_mutex_);
        const std::lock_guard lock(mutex_);
        if (stopped_) return;
        ++latest_generation_;
        if (latest_generation_ == 0) ++latest_generation_;
        pending_.reset();
        if (running_cancelled_) running_cancelled_->store(true, std::memory_order_release);
    }
    wake_.notify_one();
}

void LatestWinsScheduler::shutdown() {
    const std::lock_guard shutdown_lock(shutdown_mutex_);
    {
        const std::lock_guard publication_lock(publication_mutex_);
        const std::lock_guard lock(mutex_);
        if (!stopped_) {
            stopped_ = true;
            ++latest_generation_;
            if (latest_generation_ == 0) ++latest_generation_;
            pending_.reset();
            if (running_cancelled_) running_cancelled_->store(true, std::memory_order_release);
        }
    }
    wake_.notify_all();
    if (worker_.joinable()) worker_.join();
}

LatestWinsScheduler::Generation LatestWinsScheduler::latestGeneration() const {
    const std::lock_guard lock(mutex_);
    return latest_generation_;
}

void LatestWinsScheduler::run() {
    for (;;) {
        Job job;
        {
            std::unique_lock lock(mutex_);
            wake_.wait(lock, [this] { return stopped_ || pending_.has_value(); });
            if (stopped_) return;
            job = std::move(*pending_);
            pending_.reset();
            running_cancelled_ = job.cancelled;
        }

        std::optional<TileStore> result;
        try {
            result = job.work(CancellationToken(job.cancelled));
        } catch (...) {
            // A failed preview is represented as an empty result; it must not terminate the worker.
            result.reset();
        }

        std::unique_lock publication_lock(publication_mutex_);
        bool current = false;
        {
            const std::lock_guard lock(mutex_);
            current = !stopped_ && job.generation == latest_generation_ &&
                      !job.cancelled->load(std::memory_order_acquire);
            if (running_cancelled_ == job.cancelled) running_cancelled_.reset();
        }
        if (current) {
            try {
                job.completion(job.generation, std::move(result));
            } catch (...) {
                // Client errors cannot take down scheduling for subsequent slider updates.
            }
        }
        publication_lock.unlock();
    }
}

}  // namespace croptop::adjust::preview
