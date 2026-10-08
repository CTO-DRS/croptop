#pragma once

// Process-wide owner of the headless compute device. The develop pipeline asks `GpuService::acquire()` for a
// device per job: it gets the shared one, or null when there is none (no hardware GPU, blocklisted driver, or a recent failure), and
// then runs the CPU reference. A device that reports loss is dropped and re-created after a back-off, so one driver reset does not
// turn the GPU off for the rest of the session and a crashing driver is not hammered.

#include <chrono>
#include <functional>
#include <cstdint>
#include <memory>
#include <mutex>
#include <string>

#include "gpu_compute.h"

namespace croptop::gpu {

class GpuService {
public:
    using Clock = std::chrono::steady_clock;

    explicit GpuService(CreateOptions options = {}, std::chrono::milliseconds retry_after = std::chrono::seconds(30),
                        std::function<Clock::time_point()> now = [] { return Clock::now(); });

    /** The shared device, creating it on first use; null while unavailable. Cheap once created. */
    std::shared_ptr<Compute> acquire();

    /** Call after a dispatch failed: drops the device so the next acquire() re-creates it once the back-off has passed. */
    void reportFailure(const std::string& reason);

    /** Why the device is unavailable ("" while it is healthy). */
    std::string lastError() const;
    std::uint32_t creations() const;

private:
    CreateOptions options_;
    std::chrono::milliseconds retry_after_;
    std::function<Clock::time_point()> now_;
    mutable std::mutex mutex_;
    std::shared_ptr<Compute> device_;
    Clock::time_point retry_at_{};
    bool has_retry_ = false;
    std::string last_error_;
    std::uint32_t creations_ = 0;
};

/** The application-wide instance (hardware GPUs only). */
GpuService& sharedGpuService();

}  // namespace croptop::gpu
