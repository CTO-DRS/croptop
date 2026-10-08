#pragma once

// Small threading helpers shared modules (no third-party dependencies).

#include <algorithm>
#include <atomic>
#include <cstddef>
#include <exception>
#include <mutex>
#include <thread>
#include <utility>
#include <vector>

namespace croptop::parallel {

/** std::jthread stand-in: NDK 27's libc++ does not provide it. Joins on destruction. */
class JoiningThread final {
public:
    JoiningThread() = default;
    template <typename Function, typename... Args>
    explicit JoiningThread(Function&& function, Args&&... args)
        : thread_(std::forward<Function>(function), std::forward<Args>(args)...) {}
    JoiningThread(JoiningThread&&) noexcept = default;
    JoiningThread& operator=(JoiningThread&& other) noexcept {
        join();
        thread_ = std::move(other.thread_);
        return *this;
    }
    ~JoiningThread() { join(); }
    bool joinable() const { return thread_.joinable(); }
    void join() {
        if (thread_.joinable()) thread_.join();
    }

private:
    std::thread thread_;
};

inline unsigned workerCount(unsigned requested) {
    const auto hardware = std::max(1U, std::thread::hardware_concurrency());
    return std::clamp(requested == 0U ? hardware : requested, 1U, 8U);
}

/**
 * Runs `work(index, worker)` for index in [0, count) on up to `workers` threads (worker 0 is the caller).
 * If any call throws, scheduling stops, every thread is joined, and the first exception is rethrown on
 * the calling thread (a throw on a spawned std::thread would otherwise call std::terminate).
 */
template <typename Work>
inline void parallelFor(std::size_t count, unsigned workers, Work&& work) {
    std::atomic<std::size_t> next{0};
    std::atomic<bool> stop{false};
    std::mutex failure_mutex;
    std::exception_ptr failure;
    const auto run = [&](std::size_t worker) {
        try {
            for (auto index = next.fetch_add(1); index < count && !stop.load(); index = next.fetch_add(1)) {
                work(index, worker);
            }
        } catch (...) {
            const std::lock_guard lock(failure_mutex);
            if (!failure) failure = std::current_exception();
            stop.store(true);
        }
    };
    {
        std::vector<JoiningThread> threads;
        const auto thread_count = std::min<std::size_t>(workers, count);
        try {
            for (std::size_t t = 1; t < thread_count; ++t) threads.emplace_back(run, t);
        } catch (...) {
            // Could not start a thread: finish with the ones that did start (joined below), then report.
            stop.store(true);
            const std::lock_guard lock(failure_mutex);
            if (!failure) failure = std::current_exception();
        }
        run(0);
    }  // Joins every worker before `failure` is inspected.
    if (failure) std::rethrow_exception(failure);
}

}  // namespace croptop::parallel
