#ifndef VK_USE_PLATFORM_ANDROID_KHR
#define VK_USE_PLATFORM_ANDROID_KHR
#endif
#include "vulkan_canvas.h"

#include "gpu_quirks.h"

#include <android/log.h>
#include <android/native_window.h>

#include <algorithm>
#include <array>
#include <cmath>
#include <cstring>
#include <limits>
#include <vector>

namespace croptop {
namespace {

constexpr float kPi = 3.14159265358979323846f;
constexpr float kDocumentWidth = 512.0f;
constexpr float kDocumentHeight = 320.0f;

bool ok(VkResult result) { return result == VK_SUCCESS; }

bool logVkFailure(const char* operation, VkResult result) {
    if (result == VK_SUCCESS) return false;
    __android_log_print(ANDROID_LOG_ERROR, "CropTopVulkan", "%s failed with VkResult %d",
                        operation, static_cast<int>(result));
    return true;
}

bool hasDeviceExtension(VkPhysicalDevice device, const char* name) {
    std::uint32_t count = 0;
    if (!ok(vkEnumerateDeviceExtensionProperties(device, nullptr, &count, nullptr))) return false;
    std::vector<VkExtensionProperties> extensions(count);
    if (!ok(vkEnumerateDeviceExtensionProperties(device, nullptr, &count, extensions.data()))) return false;
    return std::any_of(extensions.begin(), extensions.end(), [name](const VkExtensionProperties& extension) {
        return std::strcmp(extension.extensionName, name) == 0;
    });
}

bool hasInstanceExtension(const std::vector<VkExtensionProperties>& extensions, const char* name) {
    return std::any_of(extensions.begin(), extensions.end(), [name](const VkExtensionProperties& extension) {
        return std::strcmp(extension.extensionName, name) == 0;
    });
}

VkClearColorValue rgba(float r, float g, float b, float a = 1.0f) {
    VkClearColorValue value{};
    value.float32[0] = r;
    value.float32[1] = g;
    value.float32[2] = b;
    value.float32[3] = a;
    return value;
}

#ifndef NDEBUG
VKAPI_ATTR VkBool32 VKAPI_CALL reportVulkanMessage(VkDebugUtilsMessageSeverityFlagBitsEXT severity,
        VkDebugUtilsMessageTypeFlagsEXT, const VkDebugUtilsMessengerCallbackDataEXT* data, void*) {
    const int priority = (severity & VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT) != 0
        ? ANDROID_LOG_ERROR : ANDROID_LOG_WARN;
    __android_log_print(priority, "CropTopVulkan", "%s", data->pMessage);
    return VK_FALSE;
}
#endif

}  // namespace

VulkanCanvas::VulkanCanvas(ANativeWindow* window) : window_(window) {
    if (window_ != nullptr) {
        ANativeWindow_acquire(window_);
        valid_ = initialize();
    }
}

VulkanCanvas::~VulkanCanvas() {
    teardown();
    if (window_ != nullptr) ANativeWindow_release(window_);
}

void VulkanCanvas::teardown() {
    if (device_ != VK_NULL_HANDLE) {
        vkDeviceWaitIdle(device_);  // may report VK_ERROR_DEVICE_LOST; destruction below is still valid
        blend_pipeline_.destroy();
        destroyFrameBuffer();
        destroySwapchain();
        if (frame_fence_ != VK_NULL_HANDLE) vkDestroyFence(device_, frame_fence_, nullptr);
        if (image_available_ != VK_NULL_HANDLE) vkDestroySemaphore(device_, image_available_, nullptr);
        if (command_pool_ != VK_NULL_HANDLE) vkDestroyCommandPool(device_, command_pool_, nullptr);
        vkDestroyDevice(device_, nullptr);
    }
    frame_fence_ = VK_NULL_HANDLE;
    image_available_ = VK_NULL_HANDLE;
    command_pool_ = VK_NULL_HANDLE;
    device_ = VK_NULL_HANDLE;
    queue_ = VK_NULL_HANDLE;
    compute_queue_ = VK_NULL_HANDLE;
    physical_device_ = VK_NULL_HANDLE;
    if (surface_ != VK_NULL_HANDLE) vkDestroySurfaceKHR(instance_, surface_, nullptr);
    surface_ = VK_NULL_HANDLE;
    if (debug_messenger_ != VK_NULL_HANDLE) {
        const auto destroy_messenger = reinterpret_cast<PFN_vkDestroyDebugUtilsMessengerEXT>(
            vkGetInstanceProcAddr(instance_, "vkDestroyDebugUtilsMessengerEXT"));
        if (destroy_messenger != nullptr) destroy_messenger(instance_, debug_messenger_, nullptr);
    }
    debug_messenger_ = VK_NULL_HANDLE;
    if (instance_ != VK_NULL_HANDLE) vkDestroyInstance(instance_, nullptr);
    instance_ = VK_NULL_HANDLE;
}

bool VulkanCanvas::recover(const char* reason) {
    constexpr std::uint32_t kMaxRecoveries = 3;
    if (window_ == nullptr || consecutive_recoveries_ >= kMaxRecoveries) {
        __android_log_print(ANDROID_LOG_ERROR, "CropTopVulkan", "Giving up after %u recoveries in a row (%s)", consecutive_recoveries_, reason);
        valid_ = false;
        return false;
    }
    ++consecutive_recoveries_;
    ++recovery_count_;
    __android_log_print(ANDROID_LOG_WARN, "CropTopVulkan", "Rebuilding the Vulkan stack after %s (recovery %u)", reason, recovery_count_);
    valid_ = false;
    teardown();
    valid_ = initialize();
    if (!valid_) __android_log_print(ANDROID_LOG_ERROR, "CropTopVulkan", "Vulkan recovery failed");
    return valid_;
}

bool VulkanCanvas::initialize() {
    std::uint32_t loader_api_version = VK_API_VERSION_1_0;
    const auto enumerate_instance_version = reinterpret_cast<PFN_vkEnumerateInstanceVersion>(
        vkGetInstanceProcAddr(VK_NULL_HANDLE, "vkEnumerateInstanceVersion"));
    if (enumerate_instance_version != nullptr) {
        const VkResult version_result = enumerate_instance_version(&loader_api_version);
        if (logVkFailure("vkEnumerateInstanceVersion", version_result)) {
            loader_api_version = VK_API_VERSION_1_0;
        }
    }
    const auto requested_api_version = std::min(loader_api_version, VK_API_VERSION_1_1);
    __android_log_print(ANDROID_LOG_INFO, "CropTopVulkan", "Loader API %u.%u.%u; requesting %u.%u.%u",
        VK_VERSION_MAJOR(loader_api_version), VK_VERSION_MINOR(loader_api_version), VK_VERSION_PATCH(loader_api_version),
        VK_VERSION_MAJOR(requested_api_version), VK_VERSION_MINOR(requested_api_version),
        VK_VERSION_PATCH(requested_api_version));
    const VkApplicationInfo app_info{VK_STRUCTURE_TYPE_APPLICATION_INFO, nullptr, "CropTop", 1, "CropTop", 1,
                                     requested_api_version};
    std::vector<const char*> instance_extensions{VK_KHR_SURFACE_EXTENSION_NAME,
                                                  VK_KHR_ANDROID_SURFACE_EXTENSION_NAME};
    std::vector<const char*> instance_layers;
    std::uint32_t extension_count = 0;
    VkResult result = vkEnumerateInstanceExtensionProperties(nullptr, &extension_count, nullptr);
    if (logVkFailure("vkEnumerateInstanceExtensionProperties(count)", result)) return false;
    std::vector<VkExtensionProperties> available_extensions(extension_count);
    result = vkEnumerateInstanceExtensionProperties(nullptr, &extension_count, available_extensions.data());
    if (logVkFailure("vkEnumerateInstanceExtensionProperties(properties)", result)) return false;
    const bool surface_available = hasInstanceExtension(available_extensions, VK_KHR_SURFACE_EXTENSION_NAME);
    const bool android_surface_available = hasInstanceExtension(available_extensions, VK_KHR_ANDROID_SURFACE_EXTENSION_NAME);
    __android_log_print(ANDROID_LOG_INFO, "CropTopVulkan", "Instance extensions: count=%u surface=%d android_surface=%d",
                        extension_count, surface_available, android_surface_available);
    if (!surface_available || !android_surface_available) {
        __android_log_print(ANDROID_LOG_ERROR, "CropTopVulkan", "Required Android surface extensions are unavailable");
        return false;
    }
    VkDebugUtilsMessengerCreateInfoEXT debug_info{};
    bool debug_utils_available = false;
#ifndef NDEBUG
    std::uint32_t layer_count = 0;
    if (ok(vkEnumerateInstanceLayerProperties(&layer_count, nullptr))) {
        std::vector<VkLayerProperties> layers(layer_count);
        if (ok(vkEnumerateInstanceLayerProperties(&layer_count, layers.data()))) {
            const bool validation_available = std::any_of(layers.begin(), layers.end(), [](const VkLayerProperties& layer) {
                return std::strcmp(layer.layerName, "VK_LAYER_KHRONOS_validation") == 0;
            });
            if (validation_available) instance_layers.push_back("VK_LAYER_KHRONOS_validation");
        }
    }
    debug_utils_available = hasInstanceExtension(available_extensions, VK_EXT_DEBUG_UTILS_EXTENSION_NAME);
    if (debug_utils_available) {
        instance_extensions.push_back(VK_EXT_DEBUG_UTILS_EXTENSION_NAME);
        debug_info.sType = VK_STRUCTURE_TYPE_DEBUG_UTILS_MESSENGER_CREATE_INFO_EXT;
        debug_info.messageSeverity = VK_DEBUG_UTILS_MESSAGE_SEVERITY_WARNING_BIT_EXT |
                                     VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT;
        debug_info.messageType = VK_DEBUG_UTILS_MESSAGE_TYPE_GENERAL_BIT_EXT |
                                 VK_DEBUG_UTILS_MESSAGE_TYPE_VALIDATION_BIT_EXT |
                                 VK_DEBUG_UTILS_MESSAGE_TYPE_PERFORMANCE_BIT_EXT;
        debug_info.pfnUserCallback = reportVulkanMessage;
    }
#endif
    VkInstanceCreateInfo instance_info{};
    instance_info.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;
    instance_info.pNext = debug_utils_available ? &debug_info : nullptr;
    instance_info.pApplicationInfo = &app_info;
    instance_info.enabledLayerCount = static_cast<std::uint32_t>(instance_layers.size());
    instance_info.ppEnabledLayerNames = instance_layers.data();
    instance_info.enabledExtensionCount = static_cast<std::uint32_t>(instance_extensions.size());
    instance_info.ppEnabledExtensionNames = instance_extensions.data();
    result = vkCreateInstance(&instance_info, nullptr, &instance_);
    if (logVkFailure("vkCreateInstance", result)) {
        // Retry with the minimum Android surface extensions and Vulkan 1.0. Optional layers,
        // debug-utils, and newer API negotiation must not prevent a basic canvas from opening.
        __android_log_print(ANDROID_LOG_WARN, "CropTopVulkan", "Retrying instance creation with Vulkan 1.0 and no optional layers");
        const char* required_extensions[] = {VK_KHR_SURFACE_EXTENSION_NAME,
                                              VK_KHR_ANDROID_SURFACE_EXTENSION_NAME};
        VkApplicationInfo fallback_app_info = app_info;
        fallback_app_info.apiVersion = VK_API_VERSION_1_0;
        VkInstanceCreateInfo fallback_info{};
        fallback_info.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;
        fallback_info.pApplicationInfo = &fallback_app_info;
        fallback_info.enabledExtensionCount = 2;
        fallback_info.ppEnabledExtensionNames = required_extensions;
        instance_ = VK_NULL_HANDLE;
        result = vkCreateInstance(&fallback_info, nullptr, &instance_);
        if (logVkFailure("vkCreateInstance(minimal Vulkan 1.0)", result)) {
            VkApplicationInfo bare_app_info{};
            bare_app_info.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO;
            bare_app_info.pApplicationName = "CropTop Vulkan diagnostic";
            bare_app_info.apiVersion = VK_API_VERSION_1_0;
            VkInstanceCreateInfo bare_info{};
            bare_info.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;
            bare_info.pApplicationInfo = &bare_app_info;
            VkInstance bare_instance = VK_NULL_HANDLE;
            const VkResult bare_result = vkCreateInstance(&bare_info, nullptr, &bare_instance);
            if (logVkFailure("vkCreateInstance(no extensions)", bare_result)) return false;
            __android_log_print(ANDROID_LOG_ERROR, "CropTopVulkan",
                                "Bare Vulkan instance creation succeeded; Android surface extension setup failed");
            vkDestroyInstance(bare_instance, nullptr);
            return false;
        }
        debug_utils_available = false;
    }
#ifndef NDEBUG
    if (debug_utils_available) {
        const auto create_messenger = reinterpret_cast<PFN_vkCreateDebugUtilsMessengerEXT>(
            vkGetInstanceProcAddr(instance_, "vkCreateDebugUtilsMessengerEXT"));
        if (create_messenger != nullptr) create_messenger(instance_, &debug_info, nullptr, &debug_messenger_);
    }
#endif

    const VkAndroidSurfaceCreateInfoKHR surface_info{VK_STRUCTURE_TYPE_ANDROID_SURFACE_CREATE_INFO_KHR, nullptr,
                                                      0, window_};
    result = vkCreateAndroidSurfaceKHR(instance_, &surface_info, nullptr, &surface_);
    if (logVkFailure("vkCreateAndroidSurfaceKHR", result)) return false;

    std::uint32_t device_count = 0;
    result = vkEnumeratePhysicalDevices(instance_, &device_count, nullptr);
    if (logVkFailure("vkEnumeratePhysicalDevices(count)", result)) return false;
    if (device_count == 0) {
        __android_log_print(ANDROID_LOG_ERROR, "CropTopVulkan", "No Vulkan physical devices available");
        return false;
    }
    std::vector<VkPhysicalDevice> devices(device_count);
    result = vkEnumeratePhysicalDevices(instance_, &device_count, devices.data());
    if (logVkFailure("vkEnumeratePhysicalDevices(devices)", result)) return false;
    for (const auto candidate : devices) {
        if (!hasDeviceExtension(candidate, VK_KHR_SWAPCHAIN_EXTENSION_NAME)) continue;
        std::uint32_t family_count = 0;
        vkGetPhysicalDeviceQueueFamilyProperties(candidate, &family_count, nullptr);
        std::vector<VkQueueFamilyProperties> families(family_count);
        vkGetPhysicalDeviceQueueFamilyProperties(candidate, &family_count, families.data());
        for (std::uint32_t family = 0; family < family_count; ++family) {
            VkBool32 can_present = VK_FALSE;
            if (families[family].queueCount > 0 && (families[family].queueFlags & VK_QUEUE_GRAPHICS_BIT) != 0 &&
                ok(vkGetPhysicalDeviceSurfaceSupportKHR(candidate, family, surface_, &can_present)) && can_present) {
                physical_device_ = candidate;
                queue_family_ = family;
                break;
            }
        }
        if (physical_device_ != VK_NULL_HANDLE) break;
    }
    if (physical_device_ == VK_NULL_HANDLE) {
        __android_log_print(ANDROID_LOG_ERROR, "CropTopVulkan", "No graphics queue supports presenting to this Android surface");
        return false;
    }

    VkPhysicalDeviceProperties properties{};
    vkGetPhysicalDeviceProperties(physical_device_, &properties);
    std::strncpy(gpu_name_, properties.deviceName, sizeof(gpu_name_) - 1);
    vulkan_api_version_ = properties.apiVersion;
    {
        gpu::GpuInfo info;
        info.vendor_id = properties.vendorID;
        info.device_id = properties.deviceID;
        info.driver_version = properties.driverVersion;
        info.api_version = properties.apiVersion;
        info.name = properties.deviceName;
        switch (properties.deviceType) {
            case VK_PHYSICAL_DEVICE_TYPE_INTEGRATED_GPU: info.type = gpu::DeviceType::Integrated; break;
            case VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU: info.type = gpu::DeviceType::Discrete; break;
            case VK_PHYSICAL_DEVICE_TYPE_VIRTUAL_GPU: info.type = gpu::DeviceType::Virtual; break;
            case VK_PHYSICAL_DEVICE_TYPE_CPU: info.type = gpu::DeviceType::Cpu; break;
            default: break;
        }
        quirks_ = gpu::quirksFor(info);
        if (quirks_.blocked) {
            __android_log_print(ANDROID_LOG_WARN, "CropTopVulkan", "GPU %s is on the blocklist; using the CPU renderer", properties.deviceName);
            return false;
        }
    }
    std::uint32_t selected_family_count = 0;
    vkGetPhysicalDeviceQueueFamilyProperties(physical_device_, &selected_family_count, nullptr);
    std::vector<VkQueueFamilyProperties> selected_families(selected_family_count);
    vkGetPhysicalDeviceQueueFamilyProperties(physical_device_, &selected_family_count, selected_families.data());
    const auto compute_family = std::find_if(selected_families.begin(), selected_families.end(),
        [](const VkQueueFamilyProperties& family) {
            return family.queueCount > 0 && (family.queueFlags & VK_QUEUE_COMPUTE_BIT) != 0;
        });
    if (compute_family == selected_families.end()) {
        __android_log_print(ANDROID_LOG_ERROR, "CropTopVulkan", "Selected device has no compute-capable queue");
        return false;
    }
    compute_queue_family_ = static_cast<std::uint32_t>(std::distance(selected_families.begin(), compute_family));
    constexpr float queue_priority = 1.0f;
    std::vector<VkDeviceQueueCreateInfo> queue_infos;
    const auto add_queue_info = [&queue_infos, &queue_priority](std::uint32_t family) {
        VkDeviceQueueCreateInfo queue_info{};
        queue_info.sType = VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO;
        queue_info.queueFamilyIndex = family;
        queue_info.queueCount = 1;
        queue_info.pQueuePriorities = &queue_priority;
        queue_infos.push_back(queue_info);
    };
    add_queue_info(queue_family_);
    if (compute_queue_family_ != queue_family_) add_queue_info(compute_queue_family_);
    const char* device_extensions[] = {VK_KHR_SWAPCHAIN_EXTENSION_NAME};
    VkDeviceCreateInfo device_info{};
    device_info.sType = VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO;
    device_info.queueCreateInfoCount = static_cast<std::uint32_t>(queue_infos.size());
    device_info.pQueueCreateInfos = queue_infos.data();
    device_info.enabledExtensionCount = 1;
    device_info.ppEnabledExtensionNames = device_extensions;
    result = vkCreateDevice(physical_device_, &device_info, nullptr, &device_);
    if (logVkFailure("vkCreateDevice", result)) return false;
    vkGetDeviceQueue(device_, queue_family_, 0, &queue_);
    vkGetDeviceQueue(device_, compute_queue_family_, 0, &compute_queue_);

    if (!blend_pipeline_.initialize(physical_device_, device_, compute_queue_, compute_queue_family_)) {
        __android_log_print(ANDROID_LOG_ERROR, "CropTopVulkan", "Could not create Vulkan blend compute pipeline");
        return false;
    }
    __android_log_print(ANDROID_LOG_INFO, "CropTopVulkan", "RGBA32F blend compute pipeline is ready");

    const VkCommandPoolCreateInfo pool_info{VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO, nullptr,
                                             VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT, queue_family_};
    result = vkCreateCommandPool(device_, &pool_info, nullptr, &command_pool_);
    if (logVkFailure("vkCreateCommandPool", result)) return false;
    VkSemaphoreCreateInfo semaphore_info{};
    semaphore_info.sType = VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO;
    result = vkCreateSemaphore(device_, &semaphore_info, nullptr, &image_available_);
    if (logVkFailure("vkCreateSemaphore(imageAvailable)", result)) return false;
    const VkFenceCreateInfo fence_info{VK_STRUCTURE_TYPE_FENCE_CREATE_INFO, nullptr, VK_FENCE_CREATE_SIGNALED_BIT};
    result = vkCreateFence(device_, &fence_info, nullptr, &frame_fence_);
    if (logVkFailure("vkCreateFence", result)) return false;
    width_ = static_cast<std::uint32_t>(std::max(ANativeWindow_getWidth(window_), 1));
    height_ = static_cast<std::uint32_t>(std::max(ANativeWindow_getHeight(window_), 1));
    if (!has_viewport_) {
        pan_x_ = (static_cast<float>(width_) - kDocumentWidth) / 2.0f;
        pan_y_ = (static_cast<float>(height_) - kDocumentHeight) / 2.0f;
    }
    return createSwapchain();
}

void VulkanCanvas::destroySwapchain() {
    if (device_ == VK_NULL_HANDLE) return;
    if (!commands_.empty() && command_pool_ != VK_NULL_HANDLE) {
        vkFreeCommandBuffers(device_, command_pool_, static_cast<std::uint32_t>(commands_.size()), commands_.data());
        commands_.clear();
    }
    for (const auto framebuffer : framebuffers_) vkDestroyFramebuffer(device_, framebuffer, nullptr);
    framebuffers_.clear();
    for (const auto semaphore : render_finished_) vkDestroySemaphore(device_, semaphore, nullptr);
    render_finished_.clear();
    for (const auto view : views_) vkDestroyImageView(device_, view, nullptr);
    views_.clear();
    images_.clear();
    if (render_pass_ != VK_NULL_HANDLE) vkDestroyRenderPass(device_, render_pass_, nullptr);
    render_pass_ = VK_NULL_HANDLE;
    if (swapchain_ != VK_NULL_HANDLE) vkDestroySwapchainKHR(device_, swapchain_, nullptr);
    swapchain_ = VK_NULL_HANDLE;
}

bool VulkanCanvas::createSwapchain() {
    const auto retire_lost_surface = [this](VkResult value, const char* operation) {
        if (value != VK_ERROR_SURFACE_LOST_KHR) return false;
        __android_log_print(ANDROID_LOG_WARN, "CropTopVulkan",
            "%s reported VK_ERROR_SURFACE_LOST_KHR; abort swapchain creation for the retired Surface", operation);
        valid_ = false;
        return true;
    };
    VkSurfaceCapabilitiesKHR capabilities{};
    VkResult result = vkGetPhysicalDeviceSurfaceCapabilitiesKHR(physical_device_, surface_, &capabilities);
    if (retire_lost_surface(result, "vkGetPhysicalDeviceSurfaceCapabilitiesKHR")) return false;
    if (logVkFailure("vkGetPhysicalDeviceSurfaceCapabilitiesKHR", result)) return false;
    std::uint32_t format_count = 0;
    result = vkGetPhysicalDeviceSurfaceFormatsKHR(physical_device_, surface_, &format_count, nullptr);
    if (retire_lost_surface(result, "vkGetPhysicalDeviceSurfaceFormatsKHR(count)")) return false;
    if (logVkFailure("vkGetPhysicalDeviceSurfaceFormatsKHR(count)", result)) return false;
    if (format_count == 0) {
        __android_log_print(ANDROID_LOG_ERROR, "CropTopVulkan", "Surface reported no supported formats");
        return false;
    }
    std::vector<VkSurfaceFormatKHR> formats(format_count);
    result = vkGetPhysicalDeviceSurfaceFormatsKHR(physical_device_, surface_, &format_count, formats.data());
    if (retire_lost_surface(result, "vkGetPhysicalDeviceSurfaceFormatsKHR(formats)")) return false;
    if (logVkFailure("vkGetPhysicalDeviceSurfaceFormatsKHR(formats)", result)) return false;
    VkSurfaceFormatKHR selected_format = formats.front();
    for (const auto format : formats) {
        if (format.format == VK_FORMAT_B8G8R8A8_UNORM || format.format == VK_FORMAT_R8G8B8A8_UNORM) {
            selected_format = format;
            break;
        }
    }

    std::uint32_t mode_count = 0;
    result = vkGetPhysicalDeviceSurfacePresentModesKHR(physical_device_, surface_, &mode_count, nullptr);
    if (retire_lost_surface(result, "vkGetPhysicalDeviceSurfacePresentModesKHR(count)")) return false;
    if (logVkFailure("vkGetPhysicalDeviceSurfacePresentModesKHR(count)", result)) return false;
    std::vector<VkPresentModeKHR> modes(mode_count);
    result = vkGetPhysicalDeviceSurfacePresentModesKHR(physical_device_, surface_, &mode_count, modes.data());
    if (retire_lost_surface(result, "vkGetPhysicalDeviceSurfacePresentModesKHR(modes)")) return false;
    if (logVkFailure("vkGetPhysicalDeviceSurfacePresentModesKHR(modes)", result)) return false;
    VkPresentModeKHR present_mode = VK_PRESENT_MODE_FIFO_KHR;
    if (std::find(modes.begin(), modes.end(), VK_PRESENT_MODE_MAILBOX_KHR) != modes.end()) present_mode = VK_PRESENT_MODE_MAILBOX_KHR;

    extent_ = capabilities.currentExtent;
    if (extent_.width == std::numeric_limits<std::uint32_t>::max()) {
        extent_.width = std::clamp(width_, capabilities.minImageExtent.width, capabilities.maxImageExtent.width);
        extent_.height = std::clamp(height_, capabilities.minImageExtent.height, capabilities.maxImageExtent.height);
    }
    std::uint32_t image_count = capabilities.minImageCount + 1;
    if (capabilities.maxImageCount != 0) image_count = std::min(image_count, capabilities.maxImageCount);
    VkCompositeAlphaFlagBitsKHR alpha = VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR;
    constexpr std::array<VkCompositeAlphaFlagBitsKHR, 4> alpha_modes{
        VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR, VK_COMPOSITE_ALPHA_PRE_MULTIPLIED_BIT_KHR,
        VK_COMPOSITE_ALPHA_POST_MULTIPLIED_BIT_KHR, VK_COMPOSITE_ALPHA_INHERIT_BIT_KHR};
    for (const auto candidate : alpha_modes) {
        if ((capabilities.supportedCompositeAlpha & candidate) != 0) { alpha = candidate; break; }
    }
    transfer_dst_supported_ = (capabilities.supportedUsageFlags & VK_IMAGE_USAGE_TRANSFER_DST_BIT) != 0;
    const VkImageUsageFlags image_usage = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT |
        (transfer_dst_supported_ ? VK_IMAGE_USAGE_TRANSFER_DST_BIT : 0U);
    const VkSwapchainCreateInfoKHR swapchain_info{VK_STRUCTURE_TYPE_SWAPCHAIN_CREATE_INFO_KHR, nullptr, 0,
        surface_, image_count, selected_format.format, selected_format.colorSpace, extent_, 1,
        image_usage, VK_SHARING_MODE_EXCLUSIVE, 0, nullptr,
        capabilities.currentTransform, alpha, present_mode, VK_TRUE, VK_NULL_HANDLE};
    result = vkCreateSwapchainKHR(device_, &swapchain_info, nullptr, &swapchain_);
    if (retire_lost_surface(result, "vkCreateSwapchainKHR")) return false;
    if (logVkFailure("vkCreateSwapchainKHR", result)) return false;
    format_ = selected_format.format;
    result = vkGetSwapchainImagesKHR(device_, swapchain_, &image_count, nullptr);
    if (logVkFailure("vkGetSwapchainImagesKHR(count)", result)) return false;
    images_.resize(image_count);
    result = vkGetSwapchainImagesKHR(device_, swapchain_, &image_count, images_.data());
    if (logVkFailure("vkGetSwapchainImagesKHR(images)", result)) return false;

    const VkAttachmentDescription attachment{0, format_, VK_SAMPLE_COUNT_1_BIT, VK_ATTACHMENT_LOAD_OP_CLEAR,
        VK_ATTACHMENT_STORE_OP_STORE, VK_ATTACHMENT_LOAD_OP_DONT_CARE, VK_ATTACHMENT_STORE_OP_DONT_CARE,
        VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_PRESENT_SRC_KHR};
    const VkAttachmentReference color_ref{0, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL};
    const VkSubpassDescription subpass{0, VK_PIPELINE_BIND_POINT_GRAPHICS, 0, nullptr, 1, &color_ref,
        nullptr, nullptr, 0, nullptr};
    const VkSubpassDependency dependency{VK_SUBPASS_EXTERNAL, 0, VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT,
        VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT, 0, VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT, 0};
    const VkRenderPassCreateInfo render_info{VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO, nullptr, 0, 1,
        &attachment, 1, &subpass, 1, &dependency};
    result = vkCreateRenderPass(device_, &render_info, nullptr, &render_pass_);
    if (logVkFailure("vkCreateRenderPass", result)) return false;

    views_.reserve(images_.size());
    framebuffers_.reserve(images_.size());
    for (const auto image : images_) {
        const VkImageViewCreateInfo view_info{VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO, nullptr, 0, image,
            VK_IMAGE_VIEW_TYPE_2D, format_, {}, {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1}};
        VkImageView view = VK_NULL_HANDLE;
        result = vkCreateImageView(device_, &view_info, nullptr, &view);
        if (logVkFailure("vkCreateImageView", result)) return false;
        views_.push_back(view);
        const VkFramebufferCreateInfo framebuffer_info{VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO, nullptr, 0,
            render_pass_, 1, &view, extent_.width, extent_.height, 1};
        VkFramebuffer framebuffer = VK_NULL_HANDLE;
        result = vkCreateFramebuffer(device_, &framebuffer_info, nullptr, &framebuffer);
        if (logVkFailure("vkCreateFramebuffer", result)) return false;
        framebuffers_.push_back(framebuffer);
    }
    VkSemaphoreCreateInfo swapchain_semaphore_info{};
    swapchain_semaphore_info.sType = VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO;
    render_finished_.reserve(images_.size());
    for (std::size_t i = 0; i < images_.size(); ++i) {
        VkSemaphore semaphore = VK_NULL_HANDLE;
        result = vkCreateSemaphore(device_, &swapchain_semaphore_info, nullptr, &semaphore);
        if (logVkFailure("vkCreateSemaphore(renderFinished)", result)) return false;
        render_finished_.push_back(semaphore);
    }
    commands_.resize(images_.size());
    const VkCommandBufferAllocateInfo command_info{VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO, nullptr,
        command_pool_, VK_COMMAND_BUFFER_LEVEL_PRIMARY, static_cast<std::uint32_t>(commands_.size())};
    result = vkAllocateCommandBuffers(device_, &command_info, commands_.data());
    if (logVkFailure("vkAllocateCommandBuffers", result)) return false;

    checker_dark_.clear();
    checker_light_.clear();
    constexpr std::uint32_t cell_size = 48;
    const std::size_t reserve_count = static_cast<std::size_t>((extent_.width + cell_size - 1) / cell_size) *
                                      ((extent_.height + cell_size - 1) / cell_size) / 2 + 1;
    checker_dark_.reserve(reserve_count);
    checker_light_.reserve(reserve_count);
    for (std::uint32_t y = 0, row = 0; y < extent_.height; y += cell_size, ++row) {
        for (std::uint32_t x = 0, column = 0; x < extent_.width; x += cell_size, ++column) {
            const VkClearRect rect{{{static_cast<std::int32_t>(x), static_cast<std::int32_t>(y)},
                                    {std::min(cell_size, extent_.width - x), std::min(cell_size, extent_.height - y)}}, 0, 1};
            ((row + column) & 1U ? checker_light_ : checker_dark_).push_back(rect);
        }
    }
    return true;
}

bool VulkanCanvas::resize(std::uint32_t width, std::uint32_t height) {
    if (!valid_ || width == 0 || height == 0) return false;
    width_ = width;
    height_ = height;
    if (!has_viewport_) {
        pan_x_ = (static_cast<float>(width_) - kDocumentWidth * zoom_) / 2.0f;
        pan_y_ = (static_cast<float>(height_) - kDocumentHeight * zoom_) / 2.0f;
    }
    vkDeviceWaitIdle(device_);
    destroySwapchain();
    const bool created = createSwapchain();
    if (!created) {
        __android_log_print(valid_ ? ANDROID_LOG_ERROR : ANDROID_LOG_WARN, "CropTopVulkan",
            "Swapchain recreation failed at %ux%u%s", width, height,
            valid_ ? "" : " after the Surface was lost");
    }
    return created;
}

bool VulkanCanvas::setViewport(float zoom, float pan_x, float pan_y, float rotation_degrees, bool flip_horizontal) {
    if (!std::isfinite(zoom) || zoom < 0.01f || zoom > 64.0f || !std::isfinite(pan_x) ||
        !std::isfinite(pan_y) || !std::isfinite(rotation_degrees)) return false;
    zoom_ = zoom;
    pan_x_ = pan_x;
    pan_y_ = pan_y;
    rotation_degrees_ = rotation_degrees;
    flip_horizontal_ = flip_horizontal;
    has_viewport_ = true;
    return true;
}

void VulkanCanvas::drawRect(VkCommandBuffer command, VkClearColorValue color,
                            float left, float top, float right, float bottom) {
    const float sx = flip_horizontal_ ? -zoom_ : zoom_;
    const float angle = rotation_degrees_ * kPi / 180.0f;
    const float cosine = std::cos(angle);
    const float sine = std::sin(angle);
    const std::array<std::array<float, 2>, 4> corners{{{left, top}, {right, top}, {left, bottom}, {right, bottom}}};
    float min_x = static_cast<float>(extent_.width);
    float min_y = static_cast<float>(extent_.height);
    float max_x = 0.0f;
    float max_y = 0.0f;
    for (const auto& corner : corners) {
        const float x = corner[0] * sx;
        const float y = corner[1] * zoom_;
        const float view_x = x * cosine - y * sine + pan_x_;
        const float view_y = x * sine + y * cosine + pan_y_;
        min_x = std::min(min_x, view_x);
        min_y = std::min(min_y, view_y);
        max_x = std::max(max_x, view_x);
        max_y = std::max(max_y, view_y);
    }
    const auto x0 = static_cast<std::int32_t>(std::clamp(std::floor(min_x), 0.0f, static_cast<float>(extent_.width)));
    const auto y0 = static_cast<std::int32_t>(std::clamp(std::floor(min_y), 0.0f, static_cast<float>(extent_.height)));
    const auto x1 = static_cast<std::int32_t>(std::clamp(std::ceil(max_x), 0.0f, static_cast<float>(extent_.width)));
    const auto y1 = static_cast<std::int32_t>(std::clamp(std::ceil(max_y), 0.0f, static_cast<float>(extent_.height)));
    if (x1 <= x0 || y1 <= y0) return;
    VkClearAttachment attachment{};
    attachment.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    attachment.colorAttachment = 0;
    attachment.clearValue.color = color;
    const VkClearRect rect{{{x0, y0}, {static_cast<std::uint32_t>(x1 - x0), static_cast<std::uint32_t>(y1 - y0)}}, 0, 1};
    vkCmdClearAttachments(command, 1, &attachment, 1, &rect);
}

bool VulkanCanvas::draw() {
    if (!valid_ || swapchain_ == VK_NULL_HANDLE) {
        __android_log_print(ANDROID_LOG_ERROR, "CropTopVulkan", "Canvas has no valid swapchain (valid=%d)", valid_);
        return false;
    }
    const auto lost = [](VkResult r) { return r == VK_ERROR_DEVICE_LOST || r == VK_ERROR_SURFACE_LOST_KHR; };
    VkResult result = vkWaitForFences(device_, 1, &frame_fence_, VK_TRUE, UINT64_MAX);
    if (lost(result)) return recover("device lost while waiting for the frame fence");
    if (logVkFailure("vkWaitForFences", result)) return false;
    std::uint32_t image_index = 0;
    const VkResult acquired = vkAcquireNextImageKHR(device_, swapchain_, UINT64_MAX, image_available_,
                                                     VK_NULL_HANDLE, &image_index);
    if (acquired == VK_ERROR_OUT_OF_DATE_KHR) {
        __android_log_print(ANDROID_LOG_WARN, "CropTopVulkan", "Acquire returned OUT_OF_DATE; recreating swapchain");
        return resize(width_, height_);
    }
    if (lost(acquired)) return recover("device or surface lost at acquire");
    if (acquired != VK_SUCCESS && acquired != VK_SUBOPTIMAL_KHR) {
        logVkFailure("vkAcquireNextImageKHR", acquired);
        return false;
    }
    result = vkResetFences(device_, 1, &frame_fence_);
    if (logVkFailure("vkResetFences", result)) return false;
    const VkCommandBuffer command = commands_[image_index];
    result = vkResetCommandBuffer(command, 0);
    if (logVkFailure("vkResetCommandBuffer", result)) return false;
    VkCommandBufferBeginInfo begin{};
    begin.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
    result = vkBeginCommandBuffer(command, &begin);
    if (logVkFailure("vkBeginCommandBuffer", result)) return false;
    VkClearValue background{};
    background.color.float32[0] = 0.10f;
    background.color.float32[1] = 0.11f;
    background.color.float32[2] = 0.13f;
    background.color.float32[3] = 1.0f;
    const VkRenderPassBeginInfo render_begin{VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO, nullptr, render_pass_,
        framebuffers_[image_index], {{0, 0}, extent_}, 1, &background};
    vkCmdBeginRenderPass(command, &render_begin, VK_SUBPASS_CONTENTS_INLINE);

    const auto clear_cells = [command](VkClearColorValue color, const std::vector<VkClearRect>& rects) {
        if (rects.empty()) return;
        VkClearAttachment attachment{};
        attachment.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
        attachment.colorAttachment = 0;
        attachment.clearValue.color = color;
        vkCmdClearAttachments(command, 1, &attachment, static_cast<std::uint32_t>(rects.size()), rects.data());
    };
    clear_cells(rgba(0.22f, 0.23f, 0.25f), checker_dark_);
    clear_cells(rgba(0.31f, 0.32f, 0.34f), checker_light_);

    const std::array<VkClearColorValue, 12> chart_colors{{
        rgba(0.80f, 0.08f, 0.09f), rgba(0.91f, 0.42f, 0.06f), rgba(0.95f, 0.78f, 0.08f),
        rgba(0.23f, 0.62f, 0.19f), rgba(0.02f, 0.55f, 0.60f), rgba(0.08f, 0.27f, 0.82f),
        rgba(0.47f, 0.16f, 0.73f), rgba(0.82f, 0.16f, 0.49f), rgba(0.91f, 0.91f, 0.91f),
        rgba(0.62f, 0.62f, 0.62f), rgba(0.32f, 0.32f, 0.32f), rgba(0.06f, 0.06f, 0.06f)}};
    constexpr float chart_left = 76.0f;
    constexpr float chart_top = 40.0f;
    constexpr float swatch_width = 60.0f;
    constexpr float swatch_height = 120.0f;
    for (std::size_t i = 0; i < chart_colors.size(); ++i) {
        const float left = chart_left + static_cast<float>(i % 6) * swatch_width;
        const float top = chart_top + static_cast<float>(i / 6) * swatch_height;
        drawRect(command, chart_colors[i], left, top, left + swatch_width - 2.0f, top + swatch_height - 2.0f);
    }

    vkCmdEndRenderPass(command);
    result = vkEndCommandBuffer(command);
    if (logVkFailure("vkEndCommandBuffer", result)) return false;
    const VkPipelineStageFlags wait_stage = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
    const VkSemaphore render_finished = render_finished_[image_index];
    const VkSubmitInfo submit{VK_STRUCTURE_TYPE_SUBMIT_INFO, nullptr, 1, &image_available_, &wait_stage,
        1, &command, 1, &render_finished};
    result = vkQueueSubmit(queue_, 1, &submit, frame_fence_);
    if (lost(result)) return recover("device lost at queue submit");
    if (logVkFailure("vkQueueSubmit", result)) return false;
    const VkPresentInfoKHR present{VK_STRUCTURE_TYPE_PRESENT_INFO_KHR, nullptr, 1, &render_finished,
        1, &swapchain_, &image_index, nullptr};
    const VkResult presented = vkQueuePresentKHR(queue_, &present);
    if (lost(presented)) return recover("device or surface lost at present");
    if (presented == VK_SUCCESS || presented == VK_SUBOPTIMAL_KHR) consecutive_recoveries_ = 0;
    if (presented != VK_SUCCESS && presented != VK_SUBOPTIMAL_KHR && presented != VK_ERROR_OUT_OF_DATE_KHR) {
        logVkFailure("vkQueuePresentKHR", presented);
        return false;
    }
    return presented == VK_SUCCESS || presented == VK_SUBOPTIMAL_KHR || presented == VK_ERROR_OUT_OF_DATE_KHR;
}

std::uint32_t VulkanCanvas::hostMemoryType(std::uint32_t mask) const {
    VkPhysicalDeviceMemoryProperties properties{};
    vkGetPhysicalDeviceMemoryProperties(physical_device_, &properties);
    constexpr VkMemoryPropertyFlags required = VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT;
    for (std::uint32_t i = 0; i < properties.memoryTypeCount; ++i) {
        if ((mask & (1U << i)) != 0 && (properties.memoryTypes[i].propertyFlags & required) == required) return i;
    }
    return UINT32_MAX;
}

void VulkanCanvas::destroyFrameBuffer() {
    if (frame_mapped_ != nullptr && device_ != VK_NULL_HANDLE && frame_memory_ != VK_NULL_HANDLE) {
        vkUnmapMemory(device_, frame_memory_);
    }
    frame_mapped_ = nullptr;
    if (frame_buffer_ != VK_NULL_HANDLE && device_ != VK_NULL_HANDLE) vkDestroyBuffer(device_, frame_buffer_, nullptr);
    if (frame_memory_ != VK_NULL_HANDLE && device_ != VK_NULL_HANDLE) vkFreeMemory(device_, frame_memory_, nullptr);
    frame_buffer_ = VK_NULL_HANDLE;
    frame_memory_ = VK_NULL_HANDLE;
    frame_buffer_size_ = 0;
}

bool VulkanCanvas::ensureFrameBuffer(VkDeviceSize bytes) {
    if (frame_buffer_ != VK_NULL_HANDLE && frame_buffer_size_ >= bytes) return true;
    destroyFrameBuffer();
    const VkBufferCreateInfo buffer_info{VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO, nullptr, 0, bytes,
        VK_BUFFER_USAGE_TRANSFER_SRC_BIT, VK_SHARING_MODE_EXCLUSIVE, 0, nullptr};
    VkResult result = vkCreateBuffer(device_, &buffer_info, nullptr, &frame_buffer_);
    if (logVkFailure("vkCreateBuffer(frame)", result)) return false;
    VkMemoryRequirements requirements{};
    vkGetBufferMemoryRequirements(device_, frame_buffer_, &requirements);
    const std::uint32_t memory_type = hostMemoryType(requirements.memoryTypeBits);
    if (memory_type == UINT32_MAX) {
        __android_log_print(ANDROID_LOG_ERROR, "CropTopVulkan", "No host-coherent memory type for frame upload");
        destroyFrameBuffer();
        return false;
    }
    const VkMemoryAllocateInfo allocation{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO, nullptr, requirements.size, memory_type};
    result = vkAllocateMemory(device_, &allocation, nullptr, &frame_memory_);
    if (logVkFailure("vkAllocateMemory(frame)", result)) {
        destroyFrameBuffer();
        return false;
    }
    result = vkBindBufferMemory(device_, frame_buffer_, frame_memory_, 0);
    if (logVkFailure("vkBindBufferMemory(frame)", result)) {
        destroyFrameBuffer();
        return false;
    }
    result = vkMapMemory(device_, frame_memory_, 0, requirements.size, 0, &frame_mapped_);
    if (logVkFailure("vkMapMemory(frame)", result)) {
        destroyFrameBuffer();
        return false;
    }
    frame_buffer_size_ = requirements.size;
    return true;
}

bool VulkanCanvas::drawFrame(std::span<const std::uint8_t> rgba, std::uint32_t width, std::uint32_t height) {
    if (!valid_ || !transfer_dst_supported_ || swapchain_ == VK_NULL_HANDLE || width == 0 || height == 0 ||
        static_cast<std::uint64_t>(width) * height * 4U != rgba.size()) return false;
    if ((width != extent_.width || height != extent_.height) && !resize(width, height)) return false;
    if (format_ != VK_FORMAT_B8G8R8A8_UNORM && format_ != VK_FORMAT_B8G8R8A8_SRGB &&
        format_ != VK_FORMAT_R8G8B8A8_UNORM && format_ != VK_FORMAT_R8G8B8A8_SRGB) return false;
    const auto retire_lost_surface = [this](VkResult r, const char* operation) {
        if (r != VK_ERROR_SURFACE_LOST_KHR) return false;
        __android_log_print(ANDROID_LOG_WARN, "CropTopVulkan",
            "%s reported VK_ERROR_SURFACE_LOST_KHR; retire this renderer and wait for a new Android Surface", operation);
        valid_ = false;
        return true;
    };
    VkResult result = vkWaitForFences(device_, 1, &frame_fence_, VK_TRUE, UINT64_MAX);
    if (result == VK_ERROR_DEVICE_LOST) return recover("device lost while waiting for document frame");
    if (retire_lost_surface(result, "vkWaitForFences(document frame)")) return false;
    if (logVkFailure("vkWaitForFences(document frame)", result)) return false;
    const VkDeviceSize bytes = static_cast<VkDeviceSize>(rgba.size());
    if (!ensureFrameBuffer(bytes)) return false;
    auto* target = static_cast<std::uint8_t*>(frame_mapped_);
    const bool bgra = format_ == VK_FORMAT_B8G8R8A8_UNORM || format_ == VK_FORMAT_B8G8R8A8_SRGB;
    if (!bgra) {
        std::memcpy(target, rgba.data(), rgba.size());
    } else {
        for (std::size_t i = 0; i < rgba.size(); i += 4) {
            target[i] = rgba[i + 2];
            target[i + 1] = rgba[i + 1];
            target[i + 2] = rgba[i];
            target[i + 3] = rgba[i + 3];
        }
    }

    std::uint32_t image_index = 0;
    const VkResult acquired = vkAcquireNextImageKHR(device_, swapchain_, UINT64_MAX, image_available_, VK_NULL_HANDLE, &image_index);
    if (acquired == VK_ERROR_OUT_OF_DATE_KHR) {
        resize(width_, height_);
        return false;
    }
    if (acquired == VK_ERROR_DEVICE_LOST) return recover("device lost at document frame acquire");
    if (retire_lost_surface(acquired, "vkAcquireNextImageKHR(document frame)")) return false;
    if (acquired != VK_SUCCESS && acquired != VK_SUBOPTIMAL_KHR) {
        logVkFailure("vkAcquireNextImageKHR(document frame)", acquired);
        return false;
    }
    result = vkResetFences(device_, 1, &frame_fence_);
    if (logVkFailure("vkResetFences(document frame)", result)) return false;
    const VkCommandBuffer command = commands_[image_index];
    result = vkResetCommandBuffer(command, 0);
    if (logVkFailure("vkResetCommandBuffer(document frame)", result)) return false;
    VkCommandBufferBeginInfo begin{};
    begin.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
    result = vkBeginCommandBuffer(command, &begin);
    if (logVkFailure("vkBeginCommandBuffer(document frame)", result)) return false;

    const VkImage image = images_[image_index];
    VkImageMemoryBarrier to_transfer{};
    to_transfer.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
    to_transfer.srcAccessMask = 0;
    to_transfer.dstAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
    to_transfer.oldLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    to_transfer.newLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
    to_transfer.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    to_transfer.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    to_transfer.image = image;
    to_transfer.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
    vkCmdPipelineBarrier(command, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, 0,
                         0, nullptr, 0, nullptr, 1, &to_transfer);
    const VkBufferImageCopy copy{0, width, height, {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1}, {0, 0, 0}, {width, height, 1}};
    vkCmdCopyBufferToImage(command, frame_buffer_, image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &copy);
    VkImageMemoryBarrier to_present{};
    to_present.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
    to_present.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
    to_present.dstAccessMask = 0;
    to_present.oldLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
    to_present.newLayout = VK_IMAGE_LAYOUT_PRESENT_SRC_KHR;
    to_present.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    to_present.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    to_present.image = image;
    to_present.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
    vkCmdPipelineBarrier(command, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, 0,
                         0, nullptr, 0, nullptr, 1, &to_present);
    result = vkEndCommandBuffer(command);
    if (logVkFailure("vkEndCommandBuffer(document frame)", result)) return false;
    const VkPipelineStageFlags wait_stage = VK_PIPELINE_STAGE_TRANSFER_BIT;
    const VkSemaphore render_finished = render_finished_[image_index];
    const VkSubmitInfo submit{VK_STRUCTURE_TYPE_SUBMIT_INFO, nullptr, 1, &image_available_, &wait_stage,
        1, &command, 1, &render_finished};
    result = vkQueueSubmit(queue_, 1, &submit, frame_fence_);
    if (result == VK_ERROR_DEVICE_LOST) return recover("device lost submitting document frame");
    if (retire_lost_surface(result, "vkQueueSubmit(document frame)")) return false;
    if (logVkFailure("vkQueueSubmit(document frame)", result)) return false;
    const VkPresentInfoKHR present{VK_STRUCTURE_TYPE_PRESENT_INFO_KHR, nullptr, 1, &render_finished,
        1, &swapchain_, &image_index, nullptr};
    const VkResult presented = vkQueuePresentKHR(queue_, &present);
    if (presented == VK_ERROR_DEVICE_LOST) return recover("device lost presenting document frame");
    if (retire_lost_surface(presented, "vkQueuePresentKHR(document frame)")) return false;
    if (presented == VK_SUCCESS || presented == VK_SUBOPTIMAL_KHR) consecutive_recoveries_ = 0;
    if (presented == VK_ERROR_OUT_OF_DATE_KHR) {
        resize(width_, height_);
        return false;
    }
    if (presented != VK_SUCCESS && presented != VK_SUBOPTIMAL_KHR && presented != VK_ERROR_OUT_OF_DATE_KHR) {
        logVkFailure("vkQueuePresentKHR(document frame)", presented);
        return false;
    }
    return true;
}

}  // namespace croptop
