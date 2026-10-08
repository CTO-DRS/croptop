#include "gpu_service.h"

namespace croptop::gpu {

GpuService::GpuService(CreateOptions options, std::chrono::milliseconds retry_after, std::function<Clock::time_point()> now)
    : options_(options), retry_after_(retry_after), now_(std::move(now)) {}

std::shared_ptr<Compute> GpuService::acquire() {
    const std::lock_guard lock(mutex_);
    if (device_) return device_;
    if (has_retry_ && now_() < retry_at_) return nullptr;
    std::string error;
    auto created = Compute::create(options_, error);
    if (!created) {
        last_error_ = error;
        has_retry_ = true;
        retry_at_ = now_() + retry_after_;
        return nullptr;
    }
    ++creations_;
    last_error_.clear();
    has_retry_ = false;
    device_ = std::move(created);
    return device_;
}

void GpuService::reportFailure(const std::string& reason) {
    const std::lock_guard lock(mutex_);
    device_.reset();  // jobs still holding the shared_ptr finish on the old device; new ones wait for the back-off
    last_error_ = reason;
    has_retry_ = true;
    retry_at_ = now_() + retry_after_;
}

std::string GpuService::lastError() const {
    const std::lock_guard lock(mutex_);
    return last_error_;
}

std::uint32_t GpuService::creations() const {
    const std::lock_guard lock(mutex_);
    return creations_;
}

GpuService& sharedGpuService() {
    static GpuService service;
    return service;
}

}  // namespace croptop::gpu
