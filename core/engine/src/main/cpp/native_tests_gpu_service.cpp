// Tests for the shared compute-device owner.
#include "gpu_service.h"

#include <gtest/gtest.h>

using namespace croptop::gpu;
using namespace std::chrono_literals;

namespace {
struct FakeClock {
    GpuService::Clock::time_point t{};
    GpuService::Clock::time_point operator()() const { return t; }
};
}  // namespace

TEST(GpuService, HardwareOnlyServiceEitherHasADeviceOrExplainsWhy) {
    GpuService service;
    const auto device = service.acquire();
    if (device) {
        EXPECT_FALSE(device->isSoftware());
        EXPECT_TRUE(service.lastError().empty());
    } else {
        EXPECT_FALSE(service.lastError().empty());
    }
}

TEST(GpuService, FailureDropsTheDeviceAndRetriesOnlyAfterTheBackOff) {
    CreateOptions options;
    options.allow_software = true;
    auto clock = std::make_shared<FakeClock>();
    GpuService service(options, 30s, [clock] { return (*clock)(); });
    const auto first = service.acquire();
    if (!first) GTEST_SKIP() << "no Vulkan device: " << service.lastError();
    EXPECT_EQ(service.acquire().get(), first.get());
    EXPECT_EQ(service.creations(), 1U);
    service.reportFailure("device lost");
    EXPECT_EQ(service.acquire(), nullptr);  // inside the back-off
    EXPECT_EQ(service.lastError(), "device lost");
    clock->t += 31s;
    const auto second = service.acquire();
    ASSERT_NE(second, nullptr);
    EXPECT_NE(second.get(), first.get());
    EXPECT_EQ(service.creations(), 2U);
    EXPECT_TRUE(service.lastError().empty());
}

TEST(GpuService, SharedInstanceIsStable) {
    EXPECT_EQ(&sharedGpuService(), &sharedGpuService());
}
