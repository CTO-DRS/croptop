#include "gpu_compute.h"

// Vulkan structs are initialised with their sType only; the remaining members are intentionally zero.
#pragma GCC diagnostic ignored "-Wmissing-field-initializers"

#include <vulkan/vulkan.h>

#include <algorithm>
#include <cstring>
#include <map>
#include <mutex>
#include <tuple>
#include <vector>

#include "gpu_quirks.h"

namespace croptop::gpu {
namespace {

std::string vkName(VkResult r) { return "VkResult " + std::to_string(static_cast<int>(r)); }

}  // namespace

struct Compute::Impl {
    VkInstance instance = VK_NULL_HANDLE;
    VkPhysicalDevice physical = VK_NULL_HANDLE;
    VkDevice device = VK_NULL_HANDLE;
    VkQueue queue = VK_NULL_HANDLE;
    std::uint32_t family = 0;
    VkCommandPool pool = VK_NULL_HANDLE;
    VkPhysicalDeviceMemoryProperties memory{};
    std::string name;
    bool software = false;
    bool lost = false;
    std::mutex mutex;

    struct Pipeline {
        VkShaderModule shader = VK_NULL_HANDLE;
        VkDescriptorSetLayout set_layout = VK_NULL_HANDLE;
        VkPipelineLayout layout = VK_NULL_HANDLE;
        VkPipeline pipeline = VK_NULL_HANDLE;
    };
    struct Key {
        const std::uint32_t* code;
        std::size_t words, buffers, push;
        bool operator<(const Key& o) const {
            return std::tie(code, words, buffers, push) < std::tie(o.code, o.words, o.buffers, o.push);
        }
    };
    std::map<Key, Pipeline> pipelines;

    ~Impl() {
        if (device != VK_NULL_HANDLE) {
            vkDeviceWaitIdle(device);
            for (auto& [key, p] : pipelines) {
                vkDestroyPipeline(device, p.pipeline, nullptr);
                vkDestroyPipelineLayout(device, p.layout, nullptr);
                vkDestroyDescriptorSetLayout(device, p.set_layout, nullptr);
                vkDestroyShaderModule(device, p.shader, nullptr);
            }
            if (pool != VK_NULL_HANDLE) vkDestroyCommandPool(device, pool, nullptr);
            vkDestroyDevice(device, nullptr);
        }
        if (instance != VK_NULL_HANDLE) vkDestroyInstance(instance, nullptr);
    }

    std::uint32_t memoryType(std::uint32_t bits, VkMemoryPropertyFlags wanted) const {
        for (std::uint32_t i = 0; i < memory.memoryTypeCount; ++i)
            if ((bits & (1U << i)) != 0U && (memory.memoryTypes[i].propertyFlags & wanted) == wanted) return i;
        return UINT32_MAX;
    }

    bool buildPipeline(std::span<const std::uint32_t> spirv, std::size_t buffers, std::size_t push, Pipeline& out, std::string& error) {
        VkShaderModuleCreateInfo module_info{VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO};
        module_info.codeSize = spirv.size_bytes();
        module_info.pCode = spirv.data();
        if (auto r = vkCreateShaderModule(device, &module_info, nullptr, &out.shader); r != VK_SUCCESS) {
            error = "shader module: " + vkName(r);
            return false;
        }
        std::vector<VkDescriptorSetLayoutBinding> bindings(buffers);
        for (std::size_t i = 0; i < buffers; ++i) {
            bindings[i] = {static_cast<std::uint32_t>(i), VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, 1, VK_SHADER_STAGE_COMPUTE_BIT, nullptr};
        }
        VkDescriptorSetLayoutCreateInfo set_info{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO};
        set_info.bindingCount = static_cast<std::uint32_t>(bindings.size());
        set_info.pBindings = bindings.data();
        if (auto r = vkCreateDescriptorSetLayout(device, &set_info, nullptr, &out.set_layout); r != VK_SUCCESS) {
            error = "descriptor layout: " + vkName(r);
            return false;
        }
        VkPushConstantRange range{VK_SHADER_STAGE_COMPUTE_BIT, 0, static_cast<std::uint32_t>(push)};
        VkPipelineLayoutCreateInfo layout_info{VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO};
        layout_info.setLayoutCount = 1;
        layout_info.pSetLayouts = &out.set_layout;
        layout_info.pushConstantRangeCount = push > 0 ? 1U : 0U;
        layout_info.pPushConstantRanges = &range;
        if (auto r = vkCreatePipelineLayout(device, &layout_info, nullptr, &out.layout); r != VK_SUCCESS) {
            error = "pipeline layout: " + vkName(r);
            return false;
        }
        VkComputePipelineCreateInfo pipeline_info{VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO};
        pipeline_info.stage = {VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO, nullptr, 0, VK_SHADER_STAGE_COMPUTE_BIT, out.shader, "main", nullptr};
        pipeline_info.layout = out.layout;
        if (auto r = vkCreateComputePipelines(device, VK_NULL_HANDLE, 1, &pipeline_info, nullptr, &out.pipeline); r != VK_SUCCESS) {
            error = "compute pipeline: " + vkName(r);
            return false;
        }
        return true;
    }
};

Compute::Compute(std::unique_ptr<Impl> impl) : impl_(std::move(impl)) {}
Compute::~Compute() = default;
const std::string& Compute::deviceName() const { return impl_->name; }
bool Compute::isSoftware() const { return impl_->software; }

std::unique_ptr<Compute> Compute::create(const CreateOptions& options, std::string& error) {
    auto impl = std::make_unique<Impl>();
    VkApplicationInfo app{VK_STRUCTURE_TYPE_APPLICATION_INFO};
    app.pApplicationName = "croptop-compute";
    app.apiVersion = VK_API_VERSION_1_1;
    VkInstanceCreateInfo instance_info{VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO};
    instance_info.pApplicationInfo = &app;
    if (auto r = vkCreateInstance(&instance_info, nullptr, &impl->instance); r != VK_SUCCESS) {
        error = "no Vulkan instance: " + vkName(r);
        return nullptr;
    }
    std::uint32_t count = 0;
    vkEnumeratePhysicalDevices(impl->instance, &count, nullptr);
    std::vector<VkPhysicalDevice> devices(count);
    vkEnumeratePhysicalDevices(impl->instance, &count, devices.data());
    int best_score = -1;
    for (const auto device : devices) {
        VkPhysicalDeviceProperties props{};
        vkGetPhysicalDeviceProperties(device, &props);
        GpuInfo info;
        info.vendor_id = props.vendorID;
        info.device_id = props.deviceID;
        info.driver_version = props.driverVersion;
        info.api_version = props.apiVersion;
        info.name = props.deviceName;
        info.type = props.deviceType == VK_PHYSICAL_DEVICE_TYPE_CPU ? DeviceType::Cpu
                    : props.deviceType == VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU ? DeviceType::Discrete
                    : props.deviceType == VK_PHYSICAL_DEVICE_TYPE_INTEGRATED_GPU ? DeviceType::Integrated
                                                                                 : DeviceType::Other;
        const auto quirks = quirksFor(info);
        if (quirks.blocked || (quirks.software_renderer && !options.allow_software)) continue;
        std::uint32_t families = 0;
        vkGetPhysicalDeviceQueueFamilyProperties(device, &families, nullptr);
        std::vector<VkQueueFamilyProperties> props_list(families);
        vkGetPhysicalDeviceQueueFamilyProperties(device, &families, props_list.data());
        for (std::uint32_t f = 0; f < families; ++f) {
            if (props_list[f].queueCount == 0 || (props_list[f].queueFlags & VK_QUEUE_COMPUTE_BIT) == 0) continue;
            const int score = (info.type == DeviceType::Discrete ? 3 : info.type == DeviceType::Integrated ? 2 : 1);
            if (score > best_score) {
                best_score = score;
                impl->physical = device;
                impl->family = f;
                impl->name = props.deviceName;
                impl->software = quirks.software_renderer;
            }
            break;
        }
    }
    if (impl->physical == VK_NULL_HANDLE) {
        error = options.allow_software ? "no Vulkan device with a compute queue" : "no hardware Vulkan device with a compute queue";
        return nullptr;
    }
    vkGetPhysicalDeviceMemoryProperties(impl->physical, &impl->memory);
    const float priority = 1.0F;
    VkDeviceQueueCreateInfo queue_info{VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO};
    queue_info.queueFamilyIndex = impl->family;
    queue_info.queueCount = 1;
    queue_info.pQueuePriorities = &priority;
    VkDeviceCreateInfo device_info{VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO};
    device_info.queueCreateInfoCount = 1;
    device_info.pQueueCreateInfos = &queue_info;
    if (auto r = vkCreateDevice(impl->physical, &device_info, nullptr, &impl->device); r != VK_SUCCESS) {
        error = "could not create the Vulkan device: " + vkName(r);
        return nullptr;
    }
    vkGetDeviceQueue(impl->device, impl->family, 0, &impl->queue);
    VkCommandPoolCreateInfo pool_info{VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO};
    pool_info.queueFamilyIndex = impl->family;
    pool_info.flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
    if (auto r = vkCreateCommandPool(impl->device, &pool_info, nullptr, &impl->pool); r != VK_SUCCESS) {
        error = "could not create the command pool: " + vkName(r);
        return nullptr;
    }
    return std::unique_ptr<Compute>(new Compute(std::move(impl)));
}

bool Compute::dispatch(std::span<const std::uint32_t> spirv, std::span<const Binding> bindings, std::span<const std::uint8_t> push_constants,
                       std::uint32_t gx, std::uint32_t gy, std::uint32_t gz, std::string& error) {
    auto& d = *impl_;
    const std::lock_guard lock(d.mutex);
    if (d.lost) {
        error = "the Vulkan device was lost";
        return false;
    }
    if (spirv.empty() || bindings.empty() || gx == 0U || gy == 0U || gz == 0U) {
        error = "invalid dispatch";
        return false;
    }
    const Impl::Key key{spirv.data(), spirv.size(), bindings.size(), push_constants.size()};
    auto found = d.pipelines.find(key);
    if (found == d.pipelines.end()) {
        Impl::Pipeline built;
        if (!d.buildPipeline(spirv, bindings.size(), push_constants.size(), built, error)) {
            vkDestroyPipeline(d.device, built.pipeline, nullptr);
            vkDestroyPipelineLayout(d.device, built.layout, nullptr);
            vkDestroyDescriptorSetLayout(d.device, built.set_layout, nullptr);
            vkDestroyShaderModule(d.device, built.shader, nullptr);
            return false;
        }
        found = d.pipelines.emplace(key, built).first;
    }
    const auto& pipeline = found->second;

    struct Buf {
        VkBuffer handle = VK_NULL_HANDLE;
        VkDeviceMemory memory = VK_NULL_HANDLE;
        void* mapped = nullptr;
    };
    std::vector<Buf> buffers(bindings.size());
    VkDescriptorPool descriptor_pool = VK_NULL_HANDLE;
    VkCommandBuffer command = VK_NULL_HANDLE;
    VkFence fence = VK_NULL_HANDLE;
    const auto cleanup = [&] {
        if (fence != VK_NULL_HANDLE) vkDestroyFence(d.device, fence, nullptr);
        if (command != VK_NULL_HANDLE) vkFreeCommandBuffers(d.device, d.pool, 1, &command);
        if (descriptor_pool != VK_NULL_HANDLE) vkDestroyDescriptorPool(d.device, descriptor_pool, nullptr);
        for (auto& b : buffers) {
            if (b.mapped != nullptr) vkUnmapMemory(d.device, b.memory);
            if (b.handle != VK_NULL_HANDLE) vkDestroyBuffer(d.device, b.handle, nullptr);
            if (b.memory != VK_NULL_HANDLE) vkFreeMemory(d.device, b.memory, nullptr);
        }
    };
    const auto fail = [&](const std::string& what, VkResult r) {
        error = what + ": " + vkName(r);
        if (r == VK_ERROR_DEVICE_LOST) d.lost = true;
        cleanup();
        return false;
    };
    for (std::size_t i = 0; i < bindings.size(); ++i) {
        if (bindings[i].bytes == 0U) {
            error = "empty buffer binding";
            cleanup();
            return false;
        }
        VkBufferCreateInfo info{VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO};
        info.size = bindings[i].bytes;
        info.usage = VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
        info.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
        if (auto r = vkCreateBuffer(d.device, &info, nullptr, &buffers[i].handle); r != VK_SUCCESS) return fail("buffer", r);
        VkMemoryRequirements req{};
        vkGetBufferMemoryRequirements(d.device, buffers[i].handle, &req);
        const auto type = d.memoryType(req.memoryTypeBits, VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
        if (type == UINT32_MAX) {
            error = "no host-visible memory type";
            cleanup();
            return false;
        }
        VkMemoryAllocateInfo alloc{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};
        alloc.allocationSize = req.size;
        alloc.memoryTypeIndex = type;
        if (auto r = vkAllocateMemory(d.device, &alloc, nullptr, &buffers[i].memory); r != VK_SUCCESS) return fail("memory", r);
        if (auto r = vkBindBufferMemory(d.device, buffers[i].handle, buffers[i].memory, 0); r != VK_SUCCESS) return fail("bind", r);
        if (auto r = vkMapMemory(d.device, buffers[i].memory, 0, VK_WHOLE_SIZE, 0, &buffers[i].mapped); r != VK_SUCCESS) return fail("map", r);
        if (bindings[i].input != nullptr) std::memcpy(buffers[i].mapped, bindings[i].input, bindings[i].bytes);
        else std::memset(buffers[i].mapped, 0, bindings[i].bytes);
    }
    VkDescriptorPoolSize pool_size{VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, static_cast<std::uint32_t>(bindings.size())};
    VkDescriptorPoolCreateInfo pool_info{VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO};
    pool_info.maxSets = 1;
    pool_info.poolSizeCount = 1;
    pool_info.pPoolSizes = &pool_size;
    if (auto r = vkCreateDescriptorPool(d.device, &pool_info, nullptr, &descriptor_pool); r != VK_SUCCESS) return fail("descriptor pool", r);
    VkDescriptorSetAllocateInfo set_alloc{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO};
    set_alloc.descriptorPool = descriptor_pool;
    set_alloc.descriptorSetCount = 1;
    set_alloc.pSetLayouts = &pipeline.set_layout;
    VkDescriptorSet set = VK_NULL_HANDLE;
    if (auto r = vkAllocateDescriptorSets(d.device, &set_alloc, &set); r != VK_SUCCESS) return fail("descriptor set", r);
    std::vector<VkDescriptorBufferInfo> infos(bindings.size());
    std::vector<VkWriteDescriptorSet> writes(bindings.size());
    for (std::size_t i = 0; i < bindings.size(); ++i) {
        infos[i] = {buffers[i].handle, 0, VK_WHOLE_SIZE};
        writes[i] = {VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET, nullptr, set, static_cast<std::uint32_t>(i), 0, 1, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, nullptr, &infos[i], nullptr};
    }
    vkUpdateDescriptorSets(d.device, static_cast<std::uint32_t>(writes.size()), writes.data(), 0, nullptr);

    VkCommandBufferAllocateInfo cb_alloc{VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO};
    cb_alloc.commandPool = d.pool;
    cb_alloc.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
    cb_alloc.commandBufferCount = 1;
    if (auto r = vkAllocateCommandBuffers(d.device, &cb_alloc, &command); r != VK_SUCCESS) return fail("command buffer", r);
    VkCommandBufferBeginInfo begin{VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};
    begin.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    vkBeginCommandBuffer(command, &begin);
    vkCmdBindPipeline(command, VK_PIPELINE_BIND_POINT_COMPUTE, pipeline.pipeline);
    vkCmdBindDescriptorSets(command, VK_PIPELINE_BIND_POINT_COMPUTE, pipeline.layout, 0, 1, &set, 0, nullptr);
    if (!push_constants.empty()) {
        vkCmdPushConstants(command, pipeline.layout, VK_SHADER_STAGE_COMPUTE_BIT, 0, static_cast<std::uint32_t>(push_constants.size()), push_constants.data());
    }
    vkCmdDispatch(command, gx, gy, gz);
    VkMemoryBarrier barrier{VK_STRUCTURE_TYPE_MEMORY_BARRIER, nullptr, VK_ACCESS_SHADER_WRITE_BIT, VK_ACCESS_HOST_READ_BIT};
    vkCmdPipelineBarrier(command, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_HOST_BIT, 0, 1, &barrier, 0, nullptr, 0, nullptr);
    if (auto r = vkEndCommandBuffer(command); r != VK_SUCCESS) return fail("record", r);
    VkFenceCreateInfo fence_info{VK_STRUCTURE_TYPE_FENCE_CREATE_INFO};
    if (auto r = vkCreateFence(d.device, &fence_info, nullptr, &fence); r != VK_SUCCESS) return fail("fence", r);
    VkSubmitInfo submit{VK_STRUCTURE_TYPE_SUBMIT_INFO};
    submit.commandBufferCount = 1;
    submit.pCommandBuffers = &command;
    if (auto r = vkQueueSubmit(d.queue, 1, &submit, fence); r != VK_SUCCESS) return fail("submit", r);
    if (auto r = vkWaitForFences(d.device, 1, &fence, VK_TRUE, 30ULL * 1000000000ULL); r != VK_SUCCESS) return fail("wait", r);
    for (std::size_t i = 0; i < bindings.size(); ++i)
        if (bindings[i].output != nullptr) std::memcpy(bindings[i].output, buffers[i].mapped, bindings[i].bytes);
    cleanup();
    return true;
}

}  // namespace croptop::gpu
