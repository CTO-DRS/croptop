// Runs the compositor's Vulkan blend shader against the CPU blend reference for every blend mode on the host's Vulkan
// device (Mesa lavapipe on CI hosts), using the pipeline's own parity smoke test. Skips without a Vulkan device.
#include "vulkan_blend_pipeline.h"

#include <gtest/gtest.h>

TEST(VulkanBlend, EveryBlendModeMatchesTheCpuReference) {
    const int result = croptop::runVulkanBlendSmokeTest();
    if (result == 0) GTEST_SKIP() << "no Vulkan compute device";
    EXPECT_EQ(result, 2) << "GPU blend differs from the CPU reference";
}
