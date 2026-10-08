#pragma once

#include <vulkan/vulkan.h>

#include <cstdint>
#include <span>

namespace croptop {

struct VulkanBlendLayer final {
    std::uint32_t mode = 0;
    float opacity = 1.0F;
    std::uint32_t dissolve_seed = 0;
    std::uint32_t reserved = 0;
};

/** Vulkan compute path for RGBA32F tiles. Instances are owned by the render thread. */
class VulkanBlendPipeline final {
public:
    VulkanBlendPipeline() = default;
    ~VulkanBlendPipeline();
    VulkanBlendPipeline(const VulkanBlendPipeline&) = delete;
    VulkanBlendPipeline& operator=(const VulkanBlendPipeline&) = delete;

    bool initialize(VkPhysicalDevice physical_device, VkDevice device, VkQueue queue,
                    std::uint32_t queue_family);
    void destroy();
    bool blend(std::span<const float> backdrop, std::span<const float> source,
               std::span<float> output, std::uint32_t width, std::uint32_t height,
               std::uint32_t mode, float opacity, bool linear_light, std::uint32_t dissolve_seed);
    /** Composes layer-major RGBA32F tiles; output receives every cumulative prefix in one dispatch/readback. */
    bool blendStack(std::span<const float> backdrop, std::span<const float> sources,
                    std::span<const VulkanBlendLayer> layers, std::span<float> output,
                    std::uint32_t width, std::uint32_t height, bool linear_light,
                    std::uint32_t origin_x = 0, std::uint32_t origin_y = 0,
                    std::uint32_t coordinate_scale = 1, std::uint32_t precision_bits = 32);
    bool valid() const { return pipeline_ != VK_NULL_HANDLE; }
    bool smokeTest();

private:
    struct Buffer {
        VkBuffer handle = VK_NULL_HANDLE;
        VkDeviceMemory memory = VK_NULL_HANDLE;
        VkDeviceSize capacity = 0;
        void* mapped = nullptr;
    };

    bool ensureBuffer(Buffer& buffer, VkDeviceSize size);
    void destroyBuffer(Buffer& buffer);
    std::uint32_t memoryType(std::uint32_t mask, VkMemoryPropertyFlags required) const;

    VkPhysicalDevice physical_device_ = VK_NULL_HANDLE;
    VkDevice device_ = VK_NULL_HANDLE;
    VkQueue queue_ = VK_NULL_HANDLE;
    VkCommandPool command_pool_ = VK_NULL_HANDLE;
    VkDescriptorPool descriptor_pool_ = VK_NULL_HANDLE;
    VkDescriptorSet descriptor_set_ = VK_NULL_HANDLE;
    VkCommandBuffer command_buffer_ = VK_NULL_HANDLE;
    VkFence submit_fence_ = VK_NULL_HANDLE;
    VkDescriptorSetLayout descriptor_layout_ = VK_NULL_HANDLE;
    VkPipelineLayout pipeline_layout_ = VK_NULL_HANDLE;
    VkPipeline pipeline_ = VK_NULL_HANDLE;
    Buffer backdrop_;
    Buffer source_;
    Buffer output_;
    Buffer layer_parameters_;
};

/** Returns 0 when Vulkan compute is unavailable, 1 on pipeline/parity failure, 2 on success. */
int runVulkanBlendSmokeTest();

}  // namespace croptop
