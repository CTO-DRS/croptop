#pragma once

#include <android/native_window.h>
#ifndef VK_USE_PLATFORM_ANDROID_KHR
#define VK_USE_PLATFORM_ANDROID_KHR
#endif
#include <vulkan/vulkan.h>

#include "vulkan_blend_pipeline.h"
#include "gpu_quirks.h"

#include <cstdint>
#include <span>
#include <vector>

namespace croptop {

class VulkanCanvas {
public:
    explicit VulkanCanvas(ANativeWindow* window);
    ~VulkanCanvas();

    VulkanCanvas(const VulkanCanvas&) = delete;
    VulkanCanvas& operator=(const VulkanCanvas&) = delete;

    bool valid() const { return valid_; }
    bool resize(std::uint32_t width, std::uint32_t height);
    bool setViewport(float zoom, float pan_x, float pan_y, float rotation_degrees, bool flip_horizontal);
    bool draw();
    /** Present a tightly packed Android RGBA_8888 frame through the swapchain transfer path. */
    bool drawFrame(std::span<const std::uint8_t> rgba, std::uint32_t width, std::uint32_t height);
    const char* gpuName() const { return gpu_name_; }
    std::uint32_t vulkanApiVersion() const { return vulkan_api_version_; }
    /** Driver quirks that matched the selected device (valid after construction succeeded). */
    const gpu::Quirks& quirks() const { return quirks_; }
    /** How many times the device was rebuilt after VK_ERROR_DEVICE_LOST / SURFACE_LOST since construction (diagnostics). */
    std::uint32_t recoveryCount() const { return recovery_count_; }

private:
    bool initialize();
    /** Destroys every Vulkan object (device, surface, instance) but keeps the window and the viewport state. */
    void teardown();
    /**
     * Rebuilds the whole stack after the device or surface was lost (driver reset, GPU hang, app-window churn). The viewport
     * (zoom, pan, rotation, flip) survives; document pixels live outside this class. Gives up after kMaxRecoveries in a row.
     */
    bool recover(const char* reason);
    bool createSwapchain();
    void destroySwapchain();
    bool ensureFrameBuffer(VkDeviceSize bytes);
    void destroyFrameBuffer();
    std::uint32_t hostMemoryType(std::uint32_t mask) const;
    void drawRect(VkCommandBuffer command, VkClearColorValue color,
                  float left, float top, float right, float bottom);

    ANativeWindow* window_ = nullptr;
    gpu::Quirks quirks_;
    VkInstance instance_ = VK_NULL_HANDLE;
    VkDebugUtilsMessengerEXT debug_messenger_ = VK_NULL_HANDLE;
    VkSurfaceKHR surface_ = VK_NULL_HANDLE;
    VkPhysicalDevice physical_device_ = VK_NULL_HANDLE;
    VkDevice device_ = VK_NULL_HANDLE;
    VkQueue queue_ = VK_NULL_HANDLE;
    VkQueue compute_queue_ = VK_NULL_HANDLE;
    VulkanBlendPipeline blend_pipeline_;
    std::uint32_t queue_family_ = 0;
    std::uint32_t compute_queue_family_ = 0;
    VkSwapchainKHR swapchain_ = VK_NULL_HANDLE;
    VkFormat format_ = VK_FORMAT_UNDEFINED;
    VkExtent2D extent_{};
    VkRenderPass render_pass_ = VK_NULL_HANDLE;
    std::vector<VkImage> images_;
    std::vector<VkImageView> views_;
    std::vector<VkFramebuffer> framebuffers_;
    VkCommandPool command_pool_ = VK_NULL_HANDLE;
    std::vector<VkCommandBuffer> commands_;
    std::vector<VkClearRect> checker_dark_;
    std::vector<VkClearRect> checker_light_;
    VkSemaphore image_available_ = VK_NULL_HANDLE;
    std::vector<VkSemaphore> render_finished_;
    VkFence frame_fence_ = VK_NULL_HANDLE;
    VkBuffer frame_buffer_ = VK_NULL_HANDLE;
    VkDeviceMemory frame_memory_ = VK_NULL_HANDLE;
    void* frame_mapped_ = nullptr;
    VkDeviceSize frame_buffer_size_ = 0;
    bool transfer_dst_supported_ = false;
    std::uint32_t width_ = 0;
    std::uint32_t height_ = 0;
    float zoom_ = 1.0f;
    float pan_x_ = 0.0f;
    float pan_y_ = 0.0f;
    float rotation_degrees_ = 0.0f;
    bool flip_horizontal_ = false;
    bool has_viewport_ = false;
    bool valid_ = false;
    std::uint32_t recovery_count_ = 0;
    std::uint32_t consecutive_recoveries_ = 0;
    char gpu_name_[VK_MAX_PHYSICAL_DEVICE_NAME_SIZE]{};
    std::uint32_t vulkan_api_version_ = 0;
};

}  // namespace croptop
