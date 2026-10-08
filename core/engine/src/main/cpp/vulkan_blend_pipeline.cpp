#include "vulkan_blend_pipeline.h"

#include "blend_comp_spv.h"
#include "blend_modes.h"

#include <algorithm>
#include <array>
#include <cstring>
#include <cmath>
#include <limits>
#include <vector>

namespace croptop {
namespace {

bool succeeded(VkResult result) { return result == VK_SUCCESS; }

float srgbToLinearReference(float value) {
    return value <= 0.04045F ? value / 12.92F : std::pow((value + 0.055F) / 1.055F, 2.4F);
}

float linearToSrgbReference(float value) {
    return value <= 0.0031308F ? value * 12.92F : 1.055F * std::pow(value, 1.0F / 2.4F) - 0.055F;
}

struct PushConstants {
    std::uint32_t width;
    std::uint32_t height;
    std::uint32_t layer_count;
    std::uint32_t linear_light;
    std::uint32_t origin_x;
    std::uint32_t origin_y;
    std::uint32_t coordinate_scale;
    std::uint32_t precision_bits;
};
static_assert(sizeof(PushConstants) == 32);
static_assert(sizeof(VulkanBlendLayer) == 16);

}  // namespace

VulkanBlendPipeline::~VulkanBlendPipeline() {
    destroy();
}

void VulkanBlendPipeline::destroy() {
    if (device_ == VK_NULL_HANDLE) return;
    vkDeviceWaitIdle(device_);
    destroyBuffer(output_);
    destroyBuffer(layer_parameters_);
    destroyBuffer(source_);
    destroyBuffer(backdrop_);
    if (submit_fence_ != VK_NULL_HANDLE) vkDestroyFence(device_, submit_fence_, nullptr);
    if (descriptor_pool_ != VK_NULL_HANDLE) vkDestroyDescriptorPool(device_, descriptor_pool_, nullptr);
    if (pipeline_ != VK_NULL_HANDLE) vkDestroyPipeline(device_, pipeline_, nullptr);
    if (pipeline_layout_ != VK_NULL_HANDLE) vkDestroyPipelineLayout(device_, pipeline_layout_, nullptr);
    if (descriptor_layout_ != VK_NULL_HANDLE) vkDestroyDescriptorSetLayout(device_, descriptor_layout_, nullptr);
    if (command_pool_ != VK_NULL_HANDLE) vkDestroyCommandPool(device_, command_pool_, nullptr);
    physical_device_ = VK_NULL_HANDLE;
    device_ = VK_NULL_HANDLE;
    queue_ = VK_NULL_HANDLE;
    command_pool_ = VK_NULL_HANDLE;
    descriptor_pool_ = VK_NULL_HANDLE;
    descriptor_set_ = VK_NULL_HANDLE;
    command_buffer_ = VK_NULL_HANDLE;
    submit_fence_ = VK_NULL_HANDLE;
    descriptor_layout_ = VK_NULL_HANDLE;
    pipeline_layout_ = VK_NULL_HANDLE;
    pipeline_ = VK_NULL_HANDLE;
}

bool VulkanBlendPipeline::initialize(VkPhysicalDevice physical_device, VkDevice device, VkQueue queue,
                                     std::uint32_t queue_family) {
    if (physical_device == VK_NULL_HANDLE || device == VK_NULL_HANDLE || queue == VK_NULL_HANDLE) return false;
    physical_device_ = physical_device;
    device_ = device;
    queue_ = queue;

    std::array<VkDescriptorSetLayoutBinding, 4> bindings{};
    for (std::uint32_t index = 0; index < bindings.size(); ++index) {
        bindings[index].binding = index;
        bindings[index].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
        bindings[index].descriptorCount = 1;
        bindings[index].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
    }
    VkDescriptorSetLayoutCreateInfo descriptor_info{};
    descriptor_info.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO;
    descriptor_info.bindingCount = static_cast<std::uint32_t>(bindings.size());
    descriptor_info.pBindings = bindings.data();
    if (!succeeded(vkCreateDescriptorSetLayout(device_, &descriptor_info, nullptr, &descriptor_layout_))) return false;

    VkPushConstantRange push_range{};
    push_range.stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
    push_range.offset = 0;
    push_range.size = sizeof(PushConstants);
    VkPipelineLayoutCreateInfo layout_info{};
    layout_info.sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO;
    layout_info.setLayoutCount = 1;
    layout_info.pSetLayouts = &descriptor_layout_;
    layout_info.pushConstantRangeCount = 1;
    layout_info.pPushConstantRanges = &push_range;
    if (!succeeded(vkCreatePipelineLayout(device_, &layout_info, nullptr, &pipeline_layout_))) return false;

    VkShaderModuleCreateInfo shader_info{};
    shader_info.sType = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO;
    shader_info.codeSize = kBlendShaderSpirvBytes;
    shader_info.pCode = kBlendShaderSpirv;
    VkShaderModule shader = VK_NULL_HANDLE;
    if (!succeeded(vkCreateShaderModule(device_, &shader_info, nullptr, &shader))) return false;
    VkPipelineShaderStageCreateInfo stage{};
    stage.sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
    stage.stage = VK_SHADER_STAGE_COMPUTE_BIT;
    stage.module = shader;
    stage.pName = "main";
    VkComputePipelineCreateInfo pipeline_info{};
    pipeline_info.sType = VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO;
    pipeline_info.stage = stage;
    pipeline_info.layout = pipeline_layout_;
    const VkResult pipeline_result = vkCreateComputePipelines(device_, VK_NULL_HANDLE, 1, &pipeline_info,
                                                               nullptr, &pipeline_);
    vkDestroyShaderModule(device_, shader, nullptr);
    if (!succeeded(pipeline_result)) return false;

    VkCommandPoolCreateInfo pool_info{};
    pool_info.sType = VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO;
    pool_info.flags = VK_COMMAND_POOL_CREATE_TRANSIENT_BIT | VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
    pool_info.queueFamilyIndex = queue_family;
    if (!succeeded(vkCreateCommandPool(device_, &pool_info, nullptr, &command_pool_))) return false;

    const VkDescriptorPoolSize pool_size{VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, 4};
    VkDescriptorPoolCreateInfo descriptor_pool_info{};
    descriptor_pool_info.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO;
    descriptor_pool_info.maxSets = 1;
    descriptor_pool_info.poolSizeCount = 1;
    descriptor_pool_info.pPoolSizes = &pool_size;
    if (!succeeded(vkCreateDescriptorPool(device_, &descriptor_pool_info, nullptr, &descriptor_pool_))) return false;
    VkDescriptorSetAllocateInfo set_info{};
    set_info.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO;
    set_info.descriptorPool = descriptor_pool_;
    set_info.descriptorSetCount = 1;
    set_info.pSetLayouts = &descriptor_layout_;
    if (!succeeded(vkAllocateDescriptorSets(device_, &set_info, &descriptor_set_))) return false;

    VkCommandBufferAllocateInfo command_allocate{};
    command_allocate.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO;
    command_allocate.commandPool = command_pool_;
    command_allocate.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
    command_allocate.commandBufferCount = 1;
    if (!succeeded(vkAllocateCommandBuffers(device_, &command_allocate, &command_buffer_))) return false;
    VkFenceCreateInfo fence_info{};
    fence_info.sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO;
    fence_info.flags = VK_FENCE_CREATE_SIGNALED_BIT;
    return succeeded(vkCreateFence(device_, &fence_info, nullptr, &submit_fence_));
}

std::uint32_t VulkanBlendPipeline::memoryType(std::uint32_t mask, VkMemoryPropertyFlags required) const {
    VkPhysicalDeviceMemoryProperties properties{};
    vkGetPhysicalDeviceMemoryProperties(physical_device_, &properties);
    for (std::uint32_t index = 0; index < properties.memoryTypeCount; ++index) {
        if ((mask & (1U << index)) != 0 &&
            (properties.memoryTypes[index].propertyFlags & required) == required) return index;
    }
    return std::numeric_limits<std::uint32_t>::max();
}

bool VulkanBlendPipeline::ensureBuffer(Buffer& buffer, VkDeviceSize size) {
    if (buffer.capacity >= size) return true;
    destroyBuffer(buffer);
    VkBufferCreateInfo buffer_info{};
    buffer_info.sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO;
    buffer_info.size = size;
    buffer_info.usage = VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
    buffer_info.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    if (!succeeded(vkCreateBuffer(device_, &buffer_info, nullptr, &buffer.handle))) return false;
    VkMemoryRequirements requirements{};
    vkGetBufferMemoryRequirements(device_, buffer.handle, &requirements);
    const auto type = memoryType(requirements.memoryTypeBits,
        VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
    if (type == std::numeric_limits<std::uint32_t>::max()) {
        destroyBuffer(buffer);
        return false;
    }
    VkMemoryAllocateInfo allocate_info{};
    allocate_info.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
    allocate_info.allocationSize = requirements.size;
    allocate_info.memoryTypeIndex = type;
    if (!succeeded(vkAllocateMemory(device_, &allocate_info, nullptr, &buffer.memory)) ||
        !succeeded(vkBindBufferMemory(device_, buffer.handle, buffer.memory, 0)) ||
        !succeeded(vkMapMemory(device_, buffer.memory, 0, requirements.size, 0, &buffer.mapped))) {
        destroyBuffer(buffer);
        return false;
    }
    buffer.capacity = size;
    return true;
}

void VulkanBlendPipeline::destroyBuffer(Buffer& buffer) {
    if (device_ == VK_NULL_HANDLE) return;
    if (buffer.mapped != nullptr && buffer.memory != VK_NULL_HANDLE) vkUnmapMemory(device_, buffer.memory);
    if (buffer.handle != VK_NULL_HANDLE) vkDestroyBuffer(device_, buffer.handle, nullptr);
    if (buffer.memory != VK_NULL_HANDLE) vkFreeMemory(device_, buffer.memory, nullptr);
    buffer = {};
}

bool VulkanBlendPipeline::blend(std::span<const float> backdrop, std::span<const float> source,
                                std::span<float> output, std::uint32_t width, std::uint32_t height,
                                std::uint32_t mode, float opacity, bool linear_light,
                                std::uint32_t dissolve_seed) {
    const std::array layer{VulkanBlendLayer{mode, opacity, dissolve_seed, 0}};
    return blendStack(backdrop, source, layer, output, width, height, linear_light);
}

bool VulkanBlendPipeline::blendStack(std::span<const float> backdrop, std::span<const float> sources,
                                     std::span<const VulkanBlendLayer> layers, std::span<float> output,
                                     std::uint32_t width, std::uint32_t height, bool linear_light,
                                     std::uint32_t origin_x, std::uint32_t origin_y,
                                     std::uint32_t coordinate_scale, std::uint32_t precision_bits) {
    const std::uint64_t pixel_count = static_cast<std::uint64_t>(width) * height;
    constexpr auto kFloatsPerPixel = std::size_t{4};
    const auto max_float_count = std::numeric_limits<std::size_t>::max() / sizeof(float);
    if (!valid() || command_buffer_ == VK_NULL_HANDLE || descriptor_set_ == VK_NULL_HANDLE ||
        submit_fence_ == VK_NULL_HANDLE || width == 0 || height == 0 || layers.empty() ||
        pixel_count > std::numeric_limits<std::uint32_t>::max() || coordinate_scale == 0 ||
        (precision_bits != 8U && precision_bits != 16U && precision_bits != 32U) ||
        pixel_count > std::numeric_limits<std::size_t>::max() / kFloatsPerPixel ||
        pixel_count > std::numeric_limits<VkDeviceSize>::max() / (kFloatsPerPixel * sizeof(float))) {
        return false;
    }
    const auto float_count = static_cast<std::size_t>(pixel_count * kFloatsPerPixel);
    if (layers.size() > max_float_count / float_count ||
        layers.size() > std::numeric_limits<std::uint32_t>::max()) return false;
    const auto output_float_count = float_count * layers.size();
    if (std::any_of(layers.begin(), layers.end(), [](const VulkanBlendLayer& layer) {
            return layer.mode > 26 || !std::isfinite(layer.opacity);
        })) return false;
    VkPhysicalDeviceProperties properties{};
    vkGetPhysicalDeviceProperties(physical_device_, &properties);
    if ((width - 1U) / 16U + 1U > properties.limits.maxComputeWorkGroupCount[0] ||
        (height - 1U) / 16U + 1U > properties.limits.maxComputeWorkGroupCount[1]) return false;
    const auto byte_count = static_cast<VkDeviceSize>(float_count * sizeof(float));
    const auto output_byte_count = static_cast<VkDeviceSize>(output_float_count * sizeof(float));
    const auto source_float_count = float_count * layers.size();
    if (source_float_count > std::numeric_limits<VkDeviceSize>::max() / sizeof(float)) return false;
    const auto source_byte_count = static_cast<VkDeviceSize>(source_float_count * sizeof(float));
    const auto parameter_byte_count = static_cast<VkDeviceSize>(layers.size_bytes());
    if (byte_count > properties.limits.maxStorageBufferRange ||
        output_byte_count > properties.limits.maxStorageBufferRange ||
        source_byte_count > properties.limits.maxStorageBufferRange ||
        parameter_byte_count > properties.limits.maxStorageBufferRange) return false;
    if (backdrop.size() != float_count || sources.size() != source_float_count || output.size() != output_float_count ||
        !ensureBuffer(backdrop_, byte_count) || !ensureBuffer(source_, source_byte_count) ||
        !ensureBuffer(output_, output_byte_count) ||
        !ensureBuffer(layer_parameters_, parameter_byte_count)) return false;
    // Dispatch is synchronous: the prior call waited for its submission before returning.
    std::memcpy(backdrop_.mapped, backdrop.data(), static_cast<std::size_t>(byte_count));
    std::memcpy(source_.mapped, sources.data(), static_cast<std::size_t>(source_byte_count));
    std::memcpy(layer_parameters_.mapped, layers.data(), static_cast<std::size_t>(parameter_byte_count));

    const std::array<VkDescriptorBufferInfo, 4> buffer_infos{{
        {backdrop_.handle, 0, byte_count}, {source_.handle, 0, source_byte_count},
        {output_.handle, 0, output_byte_count}, {layer_parameters_.handle, 0, parameter_byte_count}}};
    std::array<VkWriteDescriptorSet, 4> writes{};
    for (std::uint32_t index = 0; index < writes.size(); ++index) {
        writes[index] = {};
        writes[index].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
        writes[index].dstSet = descriptor_set_;
        writes[index].dstBinding = index;
        writes[index].descriptorCount = 1;
        writes[index].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
        writes[index].pBufferInfo = &buffer_infos[index];
    }
    vkUpdateDescriptorSets(device_, static_cast<std::uint32_t>(writes.size()), writes.data(), 0, nullptr);

    if (!succeeded(vkResetCommandBuffer(command_buffer_, 0))) return false;
    VkCommandBufferBeginInfo begin{};
    begin.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
    begin.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    bool success = succeeded(vkBeginCommandBuffer(command_buffer_, &begin));
    if (success) {
        vkCmdBindPipeline(command_buffer_, VK_PIPELINE_BIND_POINT_COMPUTE, pipeline_);
        vkCmdBindDescriptorSets(command_buffer_, VK_PIPELINE_BIND_POINT_COMPUTE, pipeline_layout_, 0, 1,
                                &descriptor_set_, 0, nullptr);
        const PushConstants push{width, height, static_cast<std::uint32_t>(layers.size()),
                                 linear_light ? 1U : 0U, origin_x, origin_y, coordinate_scale, precision_bits};
        vkCmdPushConstants(command_buffer_, pipeline_layout_, VK_SHADER_STAGE_COMPUTE_BIT, 0, sizeof(push), &push);
        vkCmdDispatch(command_buffer_, (width + 15U) / 16U, (height + 15U) / 16U, 1);
        success = succeeded(vkEndCommandBuffer(command_buffer_));
    }
    if (success) {
        success = succeeded(vkResetFences(device_, 1, &submit_fence_));
    }
    if (success) {
        VkSubmitInfo submit{};
        submit.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
        submit.commandBufferCount = 1;
        submit.pCommandBuffers = &command_buffer_;
        if (!succeeded(vkQueueSubmit(queue_, 1, &submit, submit_fence_))) {
            vkDestroyFence(device_, submit_fence_, nullptr);
            submit_fence_ = VK_NULL_HANDLE;
            VkFenceCreateInfo recovery_fence_info{};
            recovery_fence_info.sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO;
            recovery_fence_info.flags = VK_FENCE_CREATE_SIGNALED_BIT;
            vkCreateFence(device_, &recovery_fence_info, nullptr, &submit_fence_);
            success = false;
        } else {
            success = succeeded(vkWaitForFences(device_, 1, &submit_fence_, VK_TRUE, UINT64_MAX));
        }
    }
    if (success) std::memcpy(output.data(), output_.mapped, static_cast<std::size_t>(output_byte_count));
    return success;
}

bool VulkanBlendPipeline::smokeTest() {
    constexpr std::uint32_t width = 64;
    constexpr std::uint32_t height = 64;
    constexpr std::array<BlendPixelF, 4> backdrop_samples{{
        {0.2F, 0.4F, 0.6F, 0.75F}, {0.0F, 0.5F, 1.0F, 0.0F},
        {0.95F, 0.04F, 0.25F, 1.0F}, {0.5F, 0.5F, 0.5F, 0.3F}}};
    constexpr std::array<BlendPixelF, 4> source_samples{{
        {0.8F, 0.3F, 0.1F, 0.5F}, {0.6F, 0.2F, 0.1F, 0.8F},
        {0.0F, 1.0F, 0.7F, 0.65F}, {0.0F, 0.0F, 0.0F, 0.0F}}};
    constexpr float opacity = 0.63F;
    constexpr std::uint32_t seed = 347U;
    constexpr std::size_t pixel_count = static_cast<std::size_t>(width) * height;
    std::vector<float> backdrop(pixel_count * 4);
    std::vector<float> source(pixel_count * 4);
    std::vector<float> output(pixel_count * 4);
    for (std::size_t pixel = 0; pixel < pixel_count; ++pixel) {
        const auto& b = backdrop_samples[(pixel * 7 + pixel / width) % backdrop_samples.size()];
        const auto& s = source_samples[(pixel * 3 + pixel / width) % source_samples.size()];
        std::copy(b.begin(), b.end(), backdrop.begin() + static_cast<std::ptrdiff_t>(pixel * 4));
        std::copy(s.begin(), s.end(), source.begin() + static_cast<std::ptrdiff_t>(pixel * 4));
    }
    for (std::uint32_t mode = 0; mode <= static_cast<std::uint32_t>(BlendMode::Luminosity); ++mode) {
        if (!blend(backdrop, source, output, width, height, mode, opacity, false, seed)) return false;
        for (std::size_t pixel = 0; pixel < pixel_count; ++pixel) {
            const std::size_t offset = pixel * 4;
            const BlendPixelF b{backdrop[offset], backdrop[offset + 1], backdrop[offset + 2], backdrop[offset + 3]};
            const BlendPixelF s{source[offset], source[offset + 1], source[offset + 2], source[offset + 3]};
            const auto expected = blendRgbaF(b, s, static_cast<BlendMode>(mode), opacity,
                                             static_cast<std::uint32_t>(pixel % width),
                                             static_cast<std::uint32_t>(pixel / width), seed);
            for (std::size_t channel = 0; channel < 4; ++channel) {
                if (!std::isfinite(output[offset + channel]) ||
                    std::abs(output[offset + channel] - expected[channel]) > 0.002F) return false;
            }
        }
    }

    const std::array<VulkanBlendLayer, 3> stack_layers{{
        {static_cast<std::uint32_t>(BlendMode::Multiply), 0.67F, 19U, 0},
        {static_cast<std::uint32_t>(BlendMode::Screen), 0.45F, 23U, 0},
        {static_cast<std::uint32_t>(BlendMode::Dissolve), 0.72F, 347U, 0}}};
    std::vector<float> stack_sources(pixel_count * 4 * stack_layers.size());
    std::vector<float> stack_output(pixel_count * 4 * stack_layers.size());
    for (std::size_t layer = 0; layer < stack_layers.size(); ++layer) {
        for (std::size_t pixel = 0; pixel < pixel_count; ++pixel) {
            const auto& sample = source_samples[(pixel * (layer + 3) + layer + pixel / width) % source_samples.size()];
            std::copy(sample.begin(), sample.end(),
                      stack_sources.begin() + static_cast<std::ptrdiff_t>((layer * pixel_count + pixel) * 4));
        }
    }
    constexpr std::uint32_t stack_origin_x = 512U;
    constexpr std::uint32_t stack_origin_y = 768U;
    constexpr std::uint32_t stack_coordinate_scale = 2U;
    if (!blendStack(backdrop, stack_sources, stack_layers, stack_output, width, height, false,
                    stack_origin_x, stack_origin_y, stack_coordinate_scale)) return false;
    for (std::size_t pixel = 0; pixel < pixel_count; ++pixel) {
        const std::size_t offset = pixel * 4;
        BlendPixelF expected{backdrop[offset], backdrop[offset + 1], backdrop[offset + 2], backdrop[offset + 3]};
        for (std::size_t layer = 0; layer < stack_layers.size(); ++layer) {
            const auto source_offset = (layer * pixel_count + pixel) * 4;
            const BlendPixelF layer_source{stack_sources[source_offset], stack_sources[source_offset + 1],
                                           stack_sources[source_offset + 2], stack_sources[source_offset + 3]};
            const auto& parameters = stack_layers[layer];
            expected = blendRgbaF(expected, layer_source, static_cast<BlendMode>(parameters.mode),
                                  parameters.opacity,
                                  stack_origin_x + static_cast<std::uint32_t>(pixel % width) * stack_coordinate_scale,
                                  stack_origin_y + static_cast<std::uint32_t>(pixel / width) * stack_coordinate_scale,
                                  parameters.dissolve_seed);
            for (std::size_t channel = 0; channel < 4; ++channel) {
                const auto stack_offset = layer * pixel_count * 4U + offset + channel;
                if (!std::isfinite(stack_output[stack_offset]) ||
                    std::abs(stack_output[stack_offset] - expected[channel]) > 0.002F) return false;
            }
        }
    }
    for (const std::uint32_t precision_bits : {8U, 16U}) {
        std::fill(stack_output.begin(), stack_output.end(), 0.0F);
        if (!blendStack(backdrop, stack_sources, stack_layers, stack_output, width, height, false,
                        stack_origin_x, stack_origin_y, stack_coordinate_scale, precision_bits)) return false;
        const float quantization_scale = precision_bits == 8U ? 255.0F : 65535.0F;
        for (std::size_t pixel = 0; pixel < pixel_count; ++pixel) {
            const std::size_t offset = pixel * 4;
            BlendPixelF expected{backdrop[offset], backdrop[offset + 1], backdrop[offset + 2], backdrop[offset + 3]};
            for (std::size_t layer = 0; layer < stack_layers.size(); ++layer) {
                const auto source_offset = (layer * pixel_count + pixel) * 4;
                const BlendPixelF layer_source{stack_sources[source_offset], stack_sources[source_offset + 1],
                                               stack_sources[source_offset + 2], stack_sources[source_offset + 3]};
                const auto& parameters = stack_layers[layer];
                expected = blendRgbaF(expected, layer_source, static_cast<BlendMode>(parameters.mode),
                                      parameters.opacity,
                                      stack_origin_x + static_cast<std::uint32_t>(pixel % width) * stack_coordinate_scale,
                                      stack_origin_y + static_cast<std::uint32_t>(pixel / width) * stack_coordinate_scale,
                                      parameters.dissolve_seed);
                for (auto& channel : expected) {
                    channel = std::round(std::clamp(channel, 0.0F, 1.0F) * quantization_scale) /
                              quantization_scale;
                }
                for (std::size_t channel = 0; channel < 4; ++channel) {
                    const auto stack_offset = layer * pixel_count * 4U + offset + channel;
                    if (!std::isfinite(stack_output[stack_offset]) ||
                        std::abs(stack_output[stack_offset] - expected[channel]) > 0.002F) return false;
                }
            }
        }
    }

    std::array<float, 4> linear_backdrop{};
    std::array<float, 4> linear_source{};
    std::array<float, 4> linear_output{};
    for (std::size_t channel = 0; channel < 3; ++channel) {
        linear_backdrop[channel] = srgbToLinearReference(backdrop[channel]);
        linear_source[channel] = srgbToLinearReference(source[channel]);
    }
    linear_backdrop[3] = backdrop[3];
    linear_source[3] = source[3];
    if (!blend(std::span(backdrop).first<4>(), std::span(source).first<4>(), linear_output,
               1, 1, static_cast<std::uint32_t>(BlendMode::Multiply), opacity, true, seed)) return false;
    auto expected_linear = blendRgbaF(linear_backdrop, linear_source, BlendMode::Multiply, opacity);
    for (std::size_t channel = 0; channel < 3; ++channel) {
        expected_linear[channel] = linearToSrgbReference(expected_linear[channel]);
    }
    for (std::size_t channel = 0; channel < 4; ++channel) {
        if (!std::isfinite(linear_output[channel]) ||
            std::abs(linear_output[channel] - expected_linear[channel]) > 0.002F) return false;
    }
    constexpr std::array<float, 4> hdr_backdrop{2.0F, 0.5F, 0.0F, 1.0F};
    constexpr std::array<float, 4> hdr_source{4.0F, 0.5F, 0.0F, 0.5F};
    std::array<float, 4> hdr_output{};
    if (!blend(hdr_backdrop, hdr_source, hdr_output, 1, 1,
               static_cast<std::uint32_t>(BlendMode::Normal), 0.5F, false, seed)) return false;
    const auto expected_hdr = blendRgbaF(hdr_backdrop, hdr_source, BlendMode::Normal, 0.5F);
    for (std::size_t channel = 0; channel < 4; ++channel) {
        if (!std::isfinite(hdr_output[channel]) || std::abs(hdr_output[channel] - expected_hdr[channel]) > 0.002F) {
            return false;
        }
    }
    return true;
}

int runVulkanBlendSmokeTest() {
    VkApplicationInfo app_info{};
    app_info.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO;
    app_info.pApplicationName = "CropTop blend pipeline test";
    app_info.apiVersion = VK_API_VERSION_1_1;
    VkInstanceCreateInfo instance_info{};
    instance_info.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;
    instance_info.pApplicationInfo = &app_info;
    VkInstance instance = VK_NULL_HANDLE;
    if (!succeeded(vkCreateInstance(&instance_info, nullptr, &instance))) return 0;

    std::uint32_t device_count = 0;
    bool passed = false;
    bool compute_available = false;
    if (succeeded(vkEnumeratePhysicalDevices(instance, &device_count, nullptr)) && device_count > 0) {
        std::vector<VkPhysicalDevice> devices(device_count);
        if (succeeded(vkEnumeratePhysicalDevices(instance, &device_count, devices.data()))) {
            for (const VkPhysicalDevice physical_device : devices) {
                std::uint32_t family_count = 0;
                vkGetPhysicalDeviceQueueFamilyProperties(physical_device, &family_count, nullptr);
                std::vector<VkQueueFamilyProperties> families(family_count);
                vkGetPhysicalDeviceQueueFamilyProperties(physical_device, &family_count, families.data());
                for (std::uint32_t family = 0; family < family_count; ++family) {
                    if (families[family].queueCount == 0 ||
                        (families[family].queueFlags & VK_QUEUE_COMPUTE_BIT) == 0) continue;
                    compute_available = true;
                    constexpr float priority = 1.0F;
                    VkDeviceQueueCreateInfo queue_info{};
                    queue_info.sType = VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO;
                    queue_info.queueFamilyIndex = family;
                    queue_info.queueCount = 1;
                    queue_info.pQueuePriorities = &priority;
                    VkDeviceCreateInfo device_info{};
                    device_info.sType = VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO;
                    device_info.queueCreateInfoCount = 1;
                    device_info.pQueueCreateInfos = &queue_info;
                    VkDevice device = VK_NULL_HANDLE;
                    if (!succeeded(vkCreateDevice(physical_device, &device_info, nullptr, &device))) continue;
                    VkQueue queue = VK_NULL_HANDLE;
                    vkGetDeviceQueue(device, family, 0, &queue);
                    {
                        VulkanBlendPipeline pipeline;
                        passed = pipeline.initialize(physical_device, device, queue, family) && pipeline.smokeTest();
                        pipeline.destroy();
                    }
                    vkDeviceWaitIdle(device);
                    vkDestroyDevice(device, nullptr);
                    if (passed) break;
                }
                if (passed) break;
            }
        }
    }
    vkDestroyInstance(instance, nullptr);
    return passed ? 2 : (compute_available ? 1 : 0);
}

}  // namespace croptop
