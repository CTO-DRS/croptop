// Small diagnostic surface; pixel work stays out of JNI strings/calls.
#include <jni.h>
#include <android/log.h>
#include <android/bitmap.h>
#include <android/native_window_jni.h>

#include <cstdint>
#include <algorithm>
#include <array>
#include <atomic>
#include <cmath>
#include <cstring>
#include <exception>
#include <memory>
#include <mutex>
#include <new>
#include <span>
#include <string>
#include <unordered_map>
#include <vector>

#include "tile_store.h"
#include "pixel_format_conversion.h"
#include "layer_style_attachment.h"
#include "render_graph.h"
#include "scratch_store.h"
#include "image_decoder.h"
#include "gif_decoder.h"
#include "webp_codec.h"
#include "croptop_format.h"
#include "resample.h"
#include "vulkan_canvas.h"
#include "native_store_handle.h"
#include "jni_parallel.h"

using croptop::jni::NativeMemoryBudget;
using croptop::jni::NativeTileStore;

static_assert(static_cast<int>(croptop::BlendMode::Luminosity) == 26,
              "Keep the JNI blend-mode range aligned with the model's 27 modes");

namespace {
std::mutex render_cancellation_mutex;
std::unordered_map<jlong, std::shared_ptr<std::atomic_bool>> render_cancellations;
std::atomic<jlong> next_render_cancellation_id{1};

std::shared_ptr<std::atomic_bool> findRenderCancellation(jlong id) {
    if (id == 0) return {};
    const std::lock_guard lock(render_cancellation_mutex);
    const auto found = render_cancellations.find(id);
    return found == render_cancellations.end() ? nullptr : found->second;
}

struct RenderCancelled final {};

class VulkanBlendRuntime final {
public:
    ~VulkanBlendRuntime() {
        pipeline_.destroy();
        if (device_ != VK_NULL_HANDLE) vkDestroyDevice(device_, nullptr);
        if (instance_ != VK_NULL_HANDLE) vkDestroyInstance(instance_, nullptr);
    }

    bool blend(std::span<const float> backdrop, std::span<const float> sources,
               std::span<const croptop::GpuBlendLayer> layers, std::span<float> output,
               std::uint32_t width, std::uint32_t height, bool linear_light,
               std::uint32_t origin_x, std::uint32_t origin_y, std::uint32_t coordinate_scale,
               std::uint32_t precision_bits) {
        const std::lock_guard lock(mutex_);
        if (!ready_ && (unavailable_ || !initialize())) return false;
        std::vector<croptop::VulkanBlendLayer> parameters;
        parameters.reserve(layers.size());
        for (const auto& layer : layers) {
            parameters.push_back({static_cast<std::uint32_t>(layer.mode), layer.opacity, layer.dissolve_seed, 0});
        }
        const bool success = pipeline_.blendStack(backdrop, sources, parameters, output, width, height,
                                                  linear_light, origin_x, origin_y, coordinate_scale,
                                                  precision_bits);
        return success;
    }

private:
    bool initialize() {
        unavailable_ = true;
        VkApplicationInfo app_info{};
        app_info.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO;
        app_info.pApplicationName = "CropTop document tile compositor";
        app_info.apiVersion = VK_API_VERSION_1_1;
        VkInstanceCreateInfo instance_info{};
        instance_info.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;
        instance_info.pApplicationInfo = &app_info;
        if (vkCreateInstance(&instance_info, nullptr, &instance_) != VK_SUCCESS) return false;
        std::uint32_t device_count = 0;
        if (vkEnumeratePhysicalDevices(instance_, &device_count, nullptr) != VK_SUCCESS || device_count == 0) return false;
        std::vector<VkPhysicalDevice> devices(device_count);
        if (vkEnumeratePhysicalDevices(instance_, &device_count, devices.data()) != VK_SUCCESS) return false;
        for (const auto physical_device : devices) {
            std::uint32_t family_count = 0;
            vkGetPhysicalDeviceQueueFamilyProperties(physical_device, &family_count, nullptr);
            std::vector<VkQueueFamilyProperties> families(family_count);
            vkGetPhysicalDeviceQueueFamilyProperties(physical_device, &family_count, families.data());
            const auto family = std::find_if(families.begin(), families.end(), [](const auto& properties) {
                return properties.queueCount > 0 && (properties.queueFlags & VK_QUEUE_COMPUTE_BIT) != 0;
            });
            if (family == families.end()) continue;
            const auto family_index = static_cast<std::uint32_t>(std::distance(families.begin(), family));
            constexpr float priority = 1.0F;
            VkDeviceQueueCreateInfo queue_info{};
            queue_info.sType = VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO;
            queue_info.queueFamilyIndex = family_index;
            queue_info.queueCount = 1;
            queue_info.pQueuePriorities = &priority;
            VkDeviceCreateInfo device_info{};
            device_info.sType = VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO;
            device_info.queueCreateInfoCount = 1;
            device_info.pQueueCreateInfos = &queue_info;
            if (vkCreateDevice(physical_device, &device_info, nullptr, &device_) != VK_SUCCESS) continue;
            VkQueue queue = VK_NULL_HANDLE;
            vkGetDeviceQueue(device_, family_index, 0, &queue);
            if (pipeline_.initialize(physical_device, device_, queue, family_index)) {
                ready_ = true;
                unavailable_ = false;
                return true;
            }
            pipeline_.destroy();
            vkDestroyDevice(device_, nullptr);
            device_ = VK_NULL_HANDLE;
        }
        return false;
    }

    std::mutex mutex_;
    VkInstance instance_ = VK_NULL_HANDLE;
    VkDevice device_ = VK_NULL_HANDLE;
    croptop::VulkanBlendPipeline pipeline_;
    bool ready_ = false;
    bool unavailable_ = false;
};

VulkanBlendRuntime& documentBlendRuntime() {
    static VulkanBlendRuntime runtime;
    return runtime;
}

struct CompositeRenderSession final {
    std::mutex request_mutex;
    std::atomic<std::uint32_t> gpu_composited_tiles{0};
    croptop::RenderGraph graph;
    bool has_layers = false;

    CompositeRenderSession() {
        graph.setGpuBlendStack([this](std::span<const float> backdrop, std::span<const float> sources,
                                      std::span<const croptop::GpuBlendLayer> layers, std::span<float> output,
                                      std::uint32_t width, std::uint32_t height, bool linear_light,
                                      std::uint32_t origin_x, std::uint32_t origin_y,
                                      std::uint32_t coordinate_scale, std::uint32_t precision_bits) {
            const bool success = documentBlendRuntime().blend(backdrop, sources, layers, output, width, height,
                                                              linear_light, origin_x, origin_y, coordinate_scale,
                                                              precision_bits);
            if (success) gpu_composited_tiles.fetch_add(1, std::memory_order_relaxed);
            return success;
        });
    }
};

std::mutex composite_render_sessions_mutex;
std::unordered_map<jlong, std::shared_ptr<CompositeRenderSession>> composite_render_sessions;
std::atomic<jlong> next_composite_render_session{1};

std::shared_ptr<CompositeRenderSession> findCompositeRenderSession(jlong id) {
    if (id == 0) return nullptr;
    const std::lock_guard lock(composite_render_sessions_mutex);
    const auto found = composite_render_sessions.find(id);
    return found == composite_render_sessions.end() ? nullptr : found->second;
}

jlong createCompositeRenderSession() {
    const auto id = next_composite_render_session.fetch_add(1, std::memory_order_relaxed);
    auto session = std::make_shared<CompositeRenderSession>();
    const std::lock_guard lock(composite_render_sessions_mutex);
    composite_render_sessions.emplace(id, std::move(session));
    return id;
}

void destroyCompositeRenderSession(jlong id) {
    if (id == 0) return;
    const std::lock_guard lock(composite_render_sessions_mutex);
    composite_render_sessions.erase(id);
}
}  // namespace

#if defined(__aarch64__)
#include <sys/auxv.h>
#include <asm/hwcap.h>
#ifndef HWCAP_ASIMD
#define HWCAP_ASIMD (1UL << 1)
#endif
#endif

#if defined(__aarch64__)
static constexpr const char* kAbi = "arm64-v8a";
#elif defined(__arm__)
static constexpr const char* kAbi = "armeabi-v7a";
#elif defined(__x86_64__)
static constexpr const char* kAbi = "x86_64";
#elif defined(__i386__)
static constexpr const char* kAbi = "x86";
#else
static constexpr const char* kAbi = "unknown";
#endif

static const char* simdFeatures() {
#if defined(__aarch64__)
    return (getauxval(AT_HWCAP) & HWCAP_ASIMD) != 0 ? "neon" : "none";
#elif defined(__arm__) && defined(__ARM_NEON)
    return "neon";
#elif defined(__x86_64__)
    return "sse2";
#else
    return "none";
#endif
}

extern "C" JNIEXPORT jlong JNICALL
Java_app_croptop_core_engine_NativeEngine_nativeCreateRenderCancellation(JNIEnv*, jobject) {
    const auto id = next_render_cancellation_id.fetch_add(1, std::memory_order_relaxed);
    if (id <= 0) return 0;
    try {
        const auto state = std::make_shared<std::atomic_bool>(false);
        const std::lock_guard lock(render_cancellation_mutex);
        render_cancellations.emplace(id, state);
        return id;
    } catch (...) {
        return 0;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_app_croptop_core_engine_NativeEngine_nativeCancelRender(JNIEnv*, jobject, jlong id) {
    if (const auto state = findRenderCancellation(id)) state->store(true, std::memory_order_release);
}

extern "C" JNIEXPORT void JNICALL
Java_app_croptop_core_engine_NativeEngine_nativeDestroyRenderCancellation(JNIEnv*, jobject, jlong id) {
    if (id == 0) return;
    const std::lock_guard lock(render_cancellation_mutex);
    render_cancellations.erase(id);
}

extern "C" JNIEXPORT jstring JNICALL
Java_app_croptop_core_engine_NativeEngine_nativeInfo(JNIEnv* env, jobject /* this */) {
#if defined(CROPTOP_HAS_VULKAN)
    constexpr const char* vulkan = "true";
#else
    constexpr const char* vulkan = "false";
#endif
    const std::string info = std::string("{\"version\":\"") + CROPTOP_VERSION +
                             "\",\"abi\":\"" + kAbi + "\",\"simd\":\"" +
                             simdFeatures() + "\",\"buildType\":\"" +
                             CROPTOP_BUILD_TYPE + "\",\"vulkan\":" + vulkan + ",\"deps\":{\"zstd\":\"" +
                             CROPTOP_ZSTD_VERSION + "\",\"lcms\":\"" + CROPTOP_LCMS_VERSION +
                             "\",\"jpegTurbo\":\"" + CROPTOP_JPEG_TURBO_VERSION +
                             "\",\"png\":\"" + CROPTOP_PNG_VERSION + "\",\"webp\":\"" +
                             CROPTOP_WEBP_VERSION + "\",\"tiff\":\"" + CROPTOP_TIFF_VERSION + "\"}}";
    return env->NewStringUTF(info.c_str());
}

extern "C" JNIEXPORT jlong JNICALL
Java_app_croptop_core_engine_NativeEngine_nativeCreateMemoryBudget(JNIEnv* env, jobject, jstring scratch_path,
        jlong budget_bytes) {
    if (scratch_path == nullptr || budget_bytes <= 0) return 0;
    const char* path = env->GetStringUTFChars(scratch_path, nullptr);
    if (path == nullptr) return 0;
    try {
        auto* budget = new NativeMemoryBudget(path, static_cast<std::size_t>(budget_bytes));
        env->ReleaseStringUTFChars(scratch_path, path);
        return reinterpret_cast<jlong>(budget);
    } catch (const std::exception& exception) {
        env->ReleaseStringUTFChars(scratch_path, path);
        __android_log_print(ANDROID_LOG_ERROR, "CropTopEngine", "Could not create memory budget: %s", exception.what());
        return 0;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_app_croptop_core_engine_NativeEngine_nativeDestroyMemoryBudget(JNIEnv*, jobject, jlong handle) {
    delete reinterpret_cast<NativeMemoryBudget*>(handle);
}

extern "C" JNIEXPORT void JNICALL
Java_app_croptop_core_engine_NativeEngine_nativeSetMemoryBudget(JNIEnv*, jobject, jlong handle, jlong budget_bytes) {
    auto* budget = reinterpret_cast<NativeMemoryBudget*>(handle);
    if (budget != nullptr && budget_bytes > 0) budget->manager->setBudget(static_cast<std::size_t>(budget_bytes));
}

extern "C" JNIEXPORT void JNICALL
Java_app_croptop_core_engine_NativeEngine_nativeTrimMemoryBudget(JNIEnv*, jobject, jlong handle, jlong target_bytes) {
    auto* budget = reinterpret_cast<NativeMemoryBudget*>(handle);
    if (budget != nullptr && target_bytes >= 0) budget->manager->trimTo(static_cast<std::size_t>(target_bytes));
}

extern "C" JNIEXPORT jlong JNICALL
Java_app_croptop_core_engine_NativeEngine_nativeMemoryBudgetBytes(JNIEnv*, jobject, jlong handle) {
    const auto* budget = reinterpret_cast<const NativeMemoryBudget*>(handle);
    return budget == nullptr ? 0 : static_cast<jlong>(budget->manager->budgetBytes());
}

extern "C" JNIEXPORT jlong JNICALL
Java_app_croptop_core_engine_NativeEngine_nativeMemoryResidentBytes(JNIEnv*, jobject, jlong handle) {
    const auto* budget = reinterpret_cast<const NativeMemoryBudget*>(handle);
    return budget == nullptr ? 0 : static_cast<jlong>(budget->manager->residentBytes());
}

extern "C" JNIEXPORT jint JNICALL
Java_app_croptop_core_engine_NativeEngine_nativeVulkanBlendSmoke(JNIEnv*, jobject) {
    return static_cast<jint>(croptop::runVulkanBlendSmokeTest());
}

extern "C" JNIEXPORT jlong JNICALL
Java_app_croptop_core_engine_NativeEngine_nativeCreateTileStore(JNIEnv*, jobject, jint width, jint height, jint format,
        jlong memory_budget_handle) {
    if (width <= 0 || height <= 0 || format < 0 || format > 4) return 0;
    auto* budget = reinterpret_cast<NativeMemoryBudget*>(memory_budget_handle);
    if (budget == nullptr) return 0;
    try {
        const auto pixel_format = static_cast<croptop::PixelFormat>(format);
        return reinterpret_cast<jlong>(new NativeTileStore(pixel_format,
            static_cast<std::uint32_t>(width), static_cast<std::uint32_t>(height), budget->tile_pool, budget->manager));
    } catch (const std::exception& exception) {
        __android_log_print(ANDROID_LOG_ERROR, "CropTopEngine", "Could not create native tile store: %s", exception.what());
        return 0;
    }
}

extern "C" JNIEXPORT jlong JNICALL
Java_app_croptop_core_engine_NativeEngine_nativePromoteTileStore(JNIEnv*, jobject, jlong handle, jint output_format,
                                                                 jint output_width, jint output_height) {
    const auto* source = reinterpret_cast<const NativeTileStore*>(handle);
    if (source == nullptr || output_format < 0 || output_format > 2 || output_width <= 0 || output_height <= 0) return 0;
    try {
        std::string error;
        auto converted = croptop::promotePixelStore(source->pixels, static_cast<croptop::PixelFormat>(output_format), error,
                                                    static_cast<std::uint32_t>(output_width),
                                                    static_cast<std::uint32_t>(output_height));
        if (!converted) {
            __android_log_print(ANDROID_LOG_ERROR, "CropTopEngine", "Could not promote archive layer: %s", error.c_str());
            return 0;
        }
        return reinterpret_cast<jlong>(new NativeTileStore(std::move(*converted)));
    } catch (const std::exception& exception) {
        __android_log_print(ANDROID_LOG_ERROR, "CropTopEngine", "Could not promote archive layer: %s", exception.what());
        return 0;
    }
}

extern "C" JNIEXPORT jlong JNICALL
Java_app_croptop_core_engine_NativeEngine_nativeCloneTileStore(JNIEnv*, jobject, jlong handle) {
    const auto* source = reinterpret_cast<const NativeTileStore*>(handle);
    if (source == nullptr) return 0;
    try {
        return reinterpret_cast<jlong>(new NativeTileStore(*source));
    } catch (const std::exception& exception) {
        __android_log_print(ANDROID_LOG_ERROR, "CropTopEngine", "Could not clone native tile store: %s", exception.what());
        return 0;
    }
}

extern "C" JNIEXPORT jlong JNICALL
Java_app_croptop_core_engine_NativeEngine_nativeResizeTileStore(JNIEnv*, jobject, jlong handle, jint width, jint height) {
    const auto* source = reinterpret_cast<const NativeTileStore*>(handle);
    if (source == nullptr || width <= 0 || height <= 0) return 0;
    try {
        std::string error;
        auto resized = croptop::resample::resize(source->pixels, static_cast<std::uint32_t>(width),
                                                 static_cast<std::uint32_t>(height), croptop::resample::Filter::Bilinear, error);
        if (!resized) {
            __android_log_print(ANDROID_LOG_ERROR, "CropTopEngine", "Could not resize preview store: %s", error.c_str());
            return 0;
        }
        return reinterpret_cast<jlong>(new NativeTileStore(std::move(*resized)));
    } catch (const std::exception& exception) {
        __android_log_print(ANDROID_LOG_ERROR, "CropTopEngine", "Could not resize preview store: %s", exception.what());
        return 0;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_app_croptop_core_engine_NativeEngine_nativeDestroyTileStore(JNIEnv*, jobject, jlong handle) {
    delete reinterpret_cast<NativeTileStore*>(handle);
}

extern "C" JNIEXPORT jlong JNICALL
Java_app_croptop_core_engine_NativeEngine_nativeTileStoreResidentBytes(JNIEnv*, jobject, jlong handle) {
    const auto* store = reinterpret_cast<const NativeTileStore*>(handle);
    return store == nullptr ? -1 : static_cast<jlong>(store->pixels.resident_bytes());
}

extern "C" JNIEXPORT void JNICALL
Java_app_croptop_core_engine_NativeEngine_nativeTrimTileStore(JNIEnv*, jobject, jlong handle) {
    auto* store = reinterpret_cast<NativeTileStore*>(handle);
    if (store != nullptr) store->pixels.trimCaches();
}

extern "C" JNIEXPORT jboolean JNICALL
Java_app_croptop_core_engine_NativeEngine_nativeTranslateTileStore(JNIEnv*, jobject, jlong handle, jfloat dx, jfloat dy) {
    auto* store = reinterpret_cast<NativeTileStore*>(handle);
    if (store == nullptr || !std::isfinite(dx) || !std::isfinite(dy)) return JNI_FALSE;
    try {
        return store->pixels.translate(dx, dy) ? JNI_TRUE : JNI_FALSE;
    } catch (const std::exception& exception) {
        __android_log_print(ANDROID_LOG_ERROR, "CropTopEngine", "Could not translate layer pixels: %s", exception.what());
        return JNI_FALSE;
    }
}

extern "C" JNIEXPORT jlong JNICALL
Java_app_croptop_core_engine_NativeEngine_nativeCreateCompositeRenderSession(JNIEnv*, jobject) {
    try {
        return createCompositeRenderSession();
    } catch (const std::exception& exception) {
        __android_log_print(ANDROID_LOG_ERROR, "CropTopEngine", "Could not create composite render session: %s", exception.what());
        return 0;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_app_croptop_core_engine_NativeEngine_nativeDestroyCompositeRenderSession(JNIEnv*, jobject, jlong id) {
    destroyCompositeRenderSession(id);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_app_croptop_core_engine_NativeEngine_nativeRenderCompositePreview(
    JNIEnv* env, jobject, jlongArray store_handles, jlongArray layer_ids, jintArray blend_modes,
    jintArray opacities, jintArray fill_opacities, jbooleanArray visibility, jfloatArray background_color,
    jint mip_level, jint origin_x, jint origin_y, jobject bitmap, jlong render_session_id, jlong cancellation_id) {
    if (store_handles == nullptr || layer_ids == nullptr || blend_modes == nullptr || opacities == nullptr ||
        fill_opacities == nullptr || visibility == nullptr || background_color == nullptr || bitmap == nullptr || mip_level < 0 || mip_level > 31 ||
        env->GetArrayLength(background_color) != 4 || origin_x < 0 || origin_y < 0) {
        return JNI_FALSE;
    }
    const auto count = env->GetArrayLength(store_handles);
    if (count <= 0 || count > 500 || env->GetArrayLength(layer_ids) != count ||
        env->GetArrayLength(blend_modes) != count || env->GetArrayLength(opacities) != count ||
        env->GetArrayLength(fill_opacities) != count || env->GetArrayLength(visibility) != count) {
        return JNI_FALSE;
    }

    std::vector<jlong> handles(static_cast<std::size_t>(count));
    std::vector<jlong> ids(static_cast<std::size_t>(count));
    std::vector<jint> modes(static_cast<std::size_t>(count));
    std::vector<jint> opacity(static_cast<std::size_t>(count));
    std::vector<jint> fill_opacity(static_cast<std::size_t>(count));
    std::vector<jboolean> visible(static_cast<std::size_t>(count));
    env->GetLongArrayRegion(store_handles, 0, count, handles.data());
    env->GetLongArrayRegion(layer_ids, 0, count, ids.data());
    env->GetIntArrayRegion(blend_modes, 0, count, modes.data());
    env->GetIntArrayRegion(opacities, 0, count, opacity.data());
    env->GetIntArrayRegion(fill_opacities, 0, count, fill_opacity.data());
    env->GetBooleanArrayRegion(visibility, 0, count, visible.data());
    if (env->ExceptionCheck()) return JNI_FALSE;

    AndroidBitmapInfo bitmap_info{};
    if (AndroidBitmap_getInfo(env, bitmap, &bitmap_info) != ANDROID_BITMAP_RESULT_SUCCESS ||
        bitmap_info.format != ANDROID_BITMAP_FORMAT_RGBA_8888 || bitmap_info.width == 0 || bitmap_info.height == 0) {
        return JNI_FALSE;
    }

    try {
        const auto cancellation = findRenderCancellation(cancellation_id);
        auto session = findCompositeRenderSession(render_session_id);
        if (render_session_id != 0 && session == nullptr) return JNI_FALSE;
        if (session == nullptr) session = std::make_shared<CompositeRenderSession>();
        const std::lock_guard session_lock(session->request_mutex);
        session->gpu_composited_tiles.store(0, std::memory_order_relaxed);
        std::vector<std::shared_ptr<const croptop::TileStore>> stores;
        std::vector<croptop::RenderLayer> layers;
        std::vector<std::shared_ptr<const croptop::jni::StyleAttachment::Effects>> effects;
        stores.reserve(static_cast<std::size_t>(count));
        layers.reserve(static_cast<std::size_t>(count));
        for (jsize index = 0; index < count; ++index) {
            const auto* source = reinterpret_cast<const NativeTileStore*>(handles[static_cast<std::size_t>(index)]);
            if (source == nullptr || ids[static_cast<std::size_t>(index)] == 0 ||
                modes[static_cast<std::size_t>(index)] < 0 || modes[static_cast<std::size_t>(index)] > 26 ||
                opacity[static_cast<std::size_t>(index)] < 0 || opacity[static_cast<std::size_t>(index)] > 255 ||
                fill_opacity[static_cast<std::size_t>(index)] < 0 || fill_opacity[static_cast<std::size_t>(index)] > 255) {
                return JNI_FALSE;
            }
            auto snapshot = std::make_shared<croptop::TileStore>(source->pixels);
            stores.push_back(snapshot);
            croptop::RenderLayer layer{
                static_cast<std::uint64_t>(ids[static_cast<std::size_t>(index)]),
                snapshot.get(),
                static_cast<croptop::BlendMode>(modes[static_cast<std::size_t>(index)]),
                static_cast<std::uint8_t>(opacity[static_cast<std::size_t>(index)]),
                static_cast<std::uint8_t>(fill_opacity[static_cast<std::size_t>(index)]),
                visible[static_cast<std::size_t>(index)] == JNI_TRUE,
            };
            layer.pixels_owner = snapshot;
            layer.mask = source->mask;
            layer.clipped = source->clipped;
            layer.blend_if = source->blend_if;
            layer.adjustment = source->adjustment;
            croptop::jni::appendStyledLayer(layers, std::move(layer), *source, effects);
        }

        auto& graph = session->graph;
        const auto cache_hits_before = graph.cachedOutputHits();
        const bool layers_set = session->has_layers
            ? graph.setLayersRetainingCache(std::move(layers), croptop::BlendSpace::LinearLight)
            : graph.setLayers(std::move(layers), croptop::BlendSpace::LinearLight);
        if (!layers_set) return JNI_FALSE;
        session->has_layers = true;
        std::array<jfloat, 4> background{};
        env->GetFloatArrayRegion(background_color, 0, 4, background.data());
        if (env->ExceptionCheck() || !graph.setBackground({background[0], background[1], background[2], background[3]})) return JNI_FALSE;
        const auto& pixels = *stores.front();
        const auto level = static_cast<std::uint32_t>(mip_level);
        const auto width = pixels.mip_width(level);
        const auto height = pixels.mip_height(level);
        if (bitmap_info.width == 0 || bitmap_info.height == 0 ||
            static_cast<std::uint64_t>(origin_x) + bitmap_info.width > width ||
            static_cast<std::uint64_t>(origin_y) + bitmap_info.height > height) return JNI_FALSE;

        void* locked_pixels = nullptr;
        if (AndroidBitmap_lockPixels(env, bitmap, &locked_pixels) != ANDROID_BITMAP_RESULT_SUCCESS || locked_pixels == nullptr) {
            return JNI_FALSE;
        }
        struct BitmapUnlocker final {
            JNIEnv* env;
            jobject bitmap;
            ~BitmapUnlocker() { AndroidBitmap_unlockPixels(env, bitmap); }
        } unlocker{env, bitmap};

        const auto format = pixels.format();
        const auto bytes_per_pixel = pixels.bytes_per_pixel();
        const auto first_tile_x = static_cast<std::uint32_t>(origin_x) / croptop::kTileEdge;
        const auto first_tile_y = static_cast<std::uint32_t>(origin_y) / croptop::kTileEdge;
        const auto last_x = static_cast<std::uint32_t>(origin_x) + bitmap_info.width - 1U;
        const auto last_y = static_cast<std::uint32_t>(origin_y) + bitmap_info.height - 1U;
        const auto tile_columns = 1U + last_x / croptop::kTileEdge - first_tile_x;
        const auto tile_rows = 1U + last_y / croptop::kTileEdge - first_tile_y;
        auto* output = static_cast<std::uint8_t*>(locked_pixels);
        const auto tile_count = static_cast<std::size_t>(tile_columns) * tile_rows;
        croptop::jni::parallelRenderBatches(
            tile_count, 64U, croptop::JobPriority::Visible,
            [&](std::size_t tile_index) {
                if (cancellation != nullptr && cancellation->load(std::memory_order_acquire)) throw RenderCancelled{};
                const auto tile_x = first_tile_x + static_cast<std::uint32_t>(tile_index % tile_columns);
                const auto tile_y = first_tile_y + static_cast<std::uint32_t>(tile_index / tile_columns);
                const croptop::TileCoordinate coordinate{static_cast<std::int32_t>(tile_x), static_cast<std::int32_t>(tile_y)};
                const auto composed = graph.renderTile(coordinate, level);
                if (composed == nullptr) throw std::runtime_error("could not compose a preview tile");
                const auto start_x = tile_x * croptop::kTileEdge;
                const auto start_y = tile_y * croptop::kTileEdge;
                const auto region_left = static_cast<std::uint32_t>(origin_x);
                const auto region_top = static_cast<std::uint32_t>(origin_y);
                const auto region_right = region_left + bitmap_info.width;
                const auto region_bottom = region_top + bitmap_info.height;
                const auto copy_left = std::max(start_x, region_left);
                const auto copy_top = std::max(start_y, region_top);
                const auto copy_right = std::min(start_x + croptop::kTileEdge, region_right);
                const auto copy_bottom = std::min(start_y + croptop::kTileEdge, region_bottom);
                for (std::uint32_t absolute_y = copy_top; absolute_y < copy_bottom; ++absolute_y) {
                    for (std::uint32_t absolute_x = copy_left; absolute_x < copy_right; ++absolute_x) {
                        const auto x = absolute_x - start_x;
                        const auto y = absolute_y - start_y;
                        const auto source_pixel = (static_cast<std::size_t>(y) * croptop::kTileEdge + x) * bytes_per_pixel;
                        std::array<std::uint8_t, 4> rgba{};
                        if (format == croptop::PixelFormat::RGBA8) {
                            std::memcpy(rgba.data(), composed->data() + source_pixel, rgba.size());
                        } else if (format == croptop::PixelFormat::RGBA16) {
                            for (std::size_t channel = 0; channel < 4; ++channel) {
                                std::uint16_t value = 0;
                                std::memcpy(&value, composed->data() + source_pixel + channel * sizeof(value), sizeof(value));
                                rgba[channel] = static_cast<std::uint8_t>((static_cast<std::uint32_t>(value) + 128U) / 257U);
                            }
                        } else {
                            for (std::size_t channel = 0; channel < 4; ++channel) {
                                float value = 0.0F;
                                std::memcpy(&value, composed->data() + source_pixel + channel * sizeof(value), sizeof(value));
                                value = std::isfinite(value) ? std::clamp(value, 0.0F, 1.0F) : 0.0F;
                                rgba[channel] = static_cast<std::uint8_t>(std::lround(value * 255.0F));
                            }
                        }
                        const auto destination_pixel = static_cast<std::size_t>(absolute_y - region_top) * bitmap_info.stride +
                                                       (static_cast<std::size_t>(absolute_x - region_left) * 4U);
                        std::memcpy(output + destination_pixel, rgba.data(), rgba.size());
                    }
                }
            });
        const auto gpu_tile_count = session->gpu_composited_tiles.load(std::memory_order_relaxed);
        if (gpu_tile_count > 0) {
            __android_log_print(ANDROID_LOG_INFO, "CropTopVulkan", "Composited %u document tiles on Vulkan",
                                gpu_tile_count);
        }
        const auto cache_hits = graph.cachedOutputHits() - cache_hits_before;
        if (cache_hits > 0) {
            __android_log_print(ANDROID_LOG_INFO, "CropTopVulkan", "Reused %llu cached document tiles",
                                static_cast<unsigned long long>(cache_hits));
        }
        return JNI_TRUE;
    } catch (const RenderCancelled&) {
        return JNI_FALSE;
    } catch (const std::exception& exception) {
        __android_log_print(ANDROID_LOG_ERROR, "CropTopEngine", "Could not render layer preview: %s", exception.what());
        return JNI_FALSE;
    }
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_app_croptop_core_engine_NativeEngine_nativeSamplePixels(
    JNIEnv* env, jobject, jlongArray store_handles, jlongArray layer_ids, jintArray blend_modes, jintArray opacities,
    jintArray fill_opacities, jbooleanArray visibility, jfloatArray background_color, jint mode, jlong primary_layer_id, jint x, jint y, jint size) {
    if (store_handles == nullptr || layer_ids == nullptr || blend_modes == nullptr || opacities == nullptr ||
        fill_opacities == nullptr || visibility == nullptr || background_color == nullptr || env->GetArrayLength(background_color) != 4 ||
        mode < 0 || mode > 2 || x < 0 || y < 0 ||
        (size != 1 && size != 3 && size != 5 && size != 11 && size != 31 && size != 51 && size != 101)) {
        return nullptr;
    }
    const auto count = env->GetArrayLength(store_handles);
    if (count <= 0 || count > 500 || env->GetArrayLength(layer_ids) != count || env->GetArrayLength(blend_modes) != count ||
        env->GetArrayLength(opacities) != count || env->GetArrayLength(fill_opacities) != count ||
        env->GetArrayLength(visibility) != count) {
        return nullptr;
    }
    std::vector<jlong> handles(static_cast<std::size_t>(count));
    std::vector<jlong> ids(static_cast<std::size_t>(count));
    std::vector<jint> modes(static_cast<std::size_t>(count));
    std::vector<jint> opacity(static_cast<std::size_t>(count));
    std::vector<jint> fill_opacity(static_cast<std::size_t>(count));
    std::vector<jboolean> visible(static_cast<std::size_t>(count));
    env->GetLongArrayRegion(store_handles, 0, count, handles.data());
    env->GetLongArrayRegion(layer_ids, 0, count, ids.data());
    env->GetIntArrayRegion(blend_modes, 0, count, modes.data());
    env->GetIntArrayRegion(opacities, 0, count, opacity.data());
    env->GetIntArrayRegion(fill_opacities, 0, count, fill_opacity.data());
    env->GetBooleanArrayRegion(visibility, 0, count, visible.data());
    if (env->ExceptionCheck()) return nullptr;

    try {
        std::vector<std::unique_ptr<NativeTileStore>> stores;
        std::vector<croptop::RenderLayer> layers;
        std::vector<std::shared_ptr<const croptop::jni::StyleAttachment::Effects>> effects;
        stores.reserve(static_cast<std::size_t>(count));
        layers.reserve(static_cast<std::size_t>(count));
        for (jsize index = 0; index < count; ++index) {
            if (mode == 1 && ids[static_cast<std::size_t>(index)] != primary_layer_id) continue;
            const auto* source = reinterpret_cast<const NativeTileStore*>(handles[static_cast<std::size_t>(index)]);
            if (source == nullptr || modes[static_cast<std::size_t>(index)] < 0 ||
                modes[static_cast<std::size_t>(index)] > 26 || opacity[static_cast<std::size_t>(index)] < 0 ||
                opacity[static_cast<std::size_t>(index)] > 255 || fill_opacity[static_cast<std::size_t>(index)] < 0 ||
                fill_opacity[static_cast<std::size_t>(index)] > 255) {
                return nullptr;
            }
            stores.push_back(std::make_unique<NativeTileStore>(source->pixels));
            layers.push_back({
                static_cast<std::uint64_t>(ids[static_cast<std::size_t>(index)]),
                &stores.back()->pixels,
                mode == 1 ? croptop::BlendMode::Normal : static_cast<croptop::BlendMode>(modes[static_cast<std::size_t>(index)]),
                static_cast<std::uint8_t>(mode == 1 ? 255 : opacity[static_cast<std::size_t>(index)]),
                static_cast<std::uint8_t>(mode == 1 ? 255 : fill_opacity[static_cast<std::size_t>(index)]),
                mode == 1 || visible[static_cast<std::size_t>(index)] == JNI_TRUE,
            });
            if (mode != 1) {  // sampling the active layer alone ignores masks, clipping and styles
                auto layer = std::move(layers.back());
                layers.pop_back();
                layer.mask = source->mask;
                layer.clipped = source->clipped;
                layer.blend_if = source->blend_if;
                layer.adjustment = source->adjustment;
                croptop::jni::appendStyledLayer(layers, std::move(layer), *source, effects);
            }
        }

        std::array<float, 4> result{};
        if (layers.empty()) {
            std::array<jfloat, 4> background{};
            env->GetFloatArrayRegion(background_color, 0, 4, background.data());
            if (env->ExceptionCheck()) return nullptr;
            if (mode != 1) result = {background[0], background[1], background[2], background[3]};
            const auto output = env->NewFloatArray(4);
            if (output != nullptr) env->SetFloatArrayRegion(output, 0, 4, result.data());
            return output;
        }
        std::array<jfloat, 4> background{};
        env->GetFloatArrayRegion(background_color, 0, 4, background.data());
        if (env->ExceptionCheck()) return nullptr;
        if (mode == 1) background = {};
        const auto& pixels = stores.front()->pixels;
        if (static_cast<std::uint32_t>(x) >= pixels.width() || static_cast<std::uint32_t>(y) >= pixels.height()) return nullptr;
        const auto half = size / 2;
        const auto left = std::max(0, x - half);
        const auto top = std::max(0, y - half);
        const auto right = std::min(static_cast<jint>(pixels.width()) - 1, x + half);
        const auto bottom = std::min(static_cast<jint>(pixels.height()) - 1, y + half);
        const auto format = pixels.format();

        // Pointer-rate sampling often targets a simple paint layer. Avoid cloning every TileStore wrapper and
        // rendering a full 256x256 graph tile for each request when one plain RGBA8 layer is sufficient.
        // Any feature that can change compositing falls through to RenderGraph below.
        const auto& only_layer = layers.front();
        const bool simple_rgba8_layer = layers.size() == 1 && format == croptop::PixelFormat::RGBA8 &&
            only_layer.blend_mode == croptop::BlendMode::Normal && only_layer.visible &&
            only_layer.mask == nullptr && !only_layer.clipped && only_layer.adjustment == nullptr && !only_layer.blend_if.active() &&
            only_layer.opacity == 255 && only_layer.fill_opacity == 255 && effects.empty();
        if (simple_rgba8_layer) {
            const auto first_tile_x = left / static_cast<jint>(croptop::kTileEdge);
            const auto first_tile_y = top / static_cast<jint>(croptop::kTileEdge);
            std::array<std::shared_ptr<const croptop::TileBuffer>, 4> source_tiles{};
            std::array<bool, 4> source_loaded{};
            croptop::BlendPixel8 backdrop{};
            for (std::size_t channel = 0; channel < 4; ++channel) {
                backdrop[channel] = static_cast<std::uint8_t>(std::lround(std::clamp(background[channel], 0.0F, 1.0F) * 255.0F));
            }
            std::array<double, 3> premultiplied{};
            double alpha_sum = 0.0;
            std::uint32_t sample_count = 0;
            for (auto sample_y = top; sample_y <= bottom; ++sample_y) {
                for (auto sample_x = left; sample_x <= right; ++sample_x) {
                    const auto tile_x = sample_x / static_cast<jint>(croptop::kTileEdge);
                    const auto tile_y = sample_y / static_cast<jint>(croptop::kTileEdge);
                    const auto cache_index = static_cast<std::size_t>((tile_y - first_tile_y) * 2 + (tile_x - first_tile_x));
                    if (cache_index >= source_tiles.size()) return nullptr;
                    if (!source_loaded[cache_index]) {
                        source_tiles[cache_index] = pixels.readTile({tile_x, tile_y});
                        source_loaded[cache_index] = true;
                    }
                    croptop::BlendPixel8 composed{};
                    if (source_tiles[cache_index] != nullptr) {
                        const auto offset = (static_cast<std::size_t>(sample_y % static_cast<jint>(croptop::kTileEdge)) *
                                                 croptop::kTileEdge + static_cast<std::size_t>(sample_x % static_cast<jint>(croptop::kTileEdge))) * 4U;
                        croptop::BlendPixel8 source{};
                        std::memcpy(source.data(), source_tiles[cache_index]->data() + offset, source.size());
                        composed = croptop::blendRgba8(backdrop, source, croptop::BlendMode::Normal, 255,
                            static_cast<std::uint32_t>(sample_x), static_cast<std::uint32_t>(sample_y), 0,
                            croptop::BlendSpace::LinearLight);
                    } else {
                        composed = backdrop;
                    }
                    std::array<double, 4> color{};
                    for (std::size_t channel = 0; channel < 3; ++channel) {
                        const auto encoded = static_cast<double>(composed[channel]) / 255.0;
                        color[channel] = encoded <= 0.04045 ? encoded / 12.92 : std::pow((encoded + 0.055) / 1.055, 2.4);
                        premultiplied[channel] += color[channel] * (static_cast<double>(composed[3]) / 255.0);
                    }
                    const auto alpha = static_cast<double>(composed[3]) / 255.0;
                    alpha_sum += alpha;
                    ++sample_count;
                }
            }
            result[3] = static_cast<float>(alpha_sum / sample_count);
            for (std::size_t channel = 0; channel < 3; ++channel) {
                const auto linear = alpha_sum > 0.0 ? std::clamp(premultiplied[channel] / alpha_sum, 0.0, 1.0) : 0.0;
                result[channel] = static_cast<float>(linear <= 0.0031308 ? linear * 12.92 : 1.055 * std::pow(linear, 1.0 / 2.4) - 0.055);
            }
            const auto output = env->NewFloatArray(4);
            if (output != nullptr) env->SetFloatArrayRegion(output, 0, 4, result.data());
            return output;
        }

        croptop::RenderGraph graph;
        if (!graph.setLayers(std::move(layers), croptop::BlendSpace::LinearLight)) return nullptr;
        if (!graph.setBackground({background[0], background[1], background[2], background[3]})) return nullptr;
        const auto pixel_bytes = pixels.bytes_per_pixel();
        const auto first_tile_x = left / static_cast<jint>(croptop::kTileEdge);
        const auto first_tile_y = top / static_cast<jint>(croptop::kTileEdge);
        std::array<std::shared_ptr<const croptop::TileBuffer>, 4> sampled_tiles{};
        std::array<bool, 4> tile_loaded{};
        double alpha_sum = 0.0;
        std::array<double, 3> premultiplied{};
        std::uint32_t sample_count = 0;
        for (auto sample_y = top; sample_y <= bottom; ++sample_y) {
            for (auto sample_x = left; sample_x <= right; ++sample_x) {
                const auto tile_x = sample_x / static_cast<jint>(croptop::kTileEdge);
                const auto tile_y = sample_y / static_cast<jint>(croptop::kTileEdge);
                const auto cache_index = static_cast<std::size_t>((tile_y - first_tile_y) * 2 + (tile_x - first_tile_x));
                if (cache_index >= sampled_tiles.size()) return nullptr;
                if (!tile_loaded[cache_index]) {
                    sampled_tiles[cache_index] = graph.renderTile({tile_x, tile_y});
                    tile_loaded[cache_index] = true;
                }
                const auto& tile = sampled_tiles[cache_index];
                const auto offset = (static_cast<std::size_t>(sample_y % static_cast<jint>(croptop::kTileEdge)) *
                                         croptop::kTileEdge +
                                     static_cast<std::size_t>(sample_x % static_cast<jint>(croptop::kTileEdge))) *
                                    pixel_bytes;
                std::array<double, 4> color{};
                if (tile != nullptr) {
                    if (format == croptop::PixelFormat::RGBA8) {
                        for (std::size_t channel = 0; channel < 3; ++channel) {
                            const auto encoded = static_cast<double>((*tile)[offset + channel]) / 255.0;
                            color[channel] = encoded <= 0.04045 ? encoded / 12.92
                                : std::pow((encoded + 0.055) / 1.055, 2.4);
                        }
                        color[3] = static_cast<double>((*tile)[offset + 3]) / 255.0;
                    } else if (format == croptop::PixelFormat::RGBA16) {
                        for (std::size_t channel = 0; channel < 3; ++channel) {
                            std::uint16_t component = 0;
                            std::memcpy(&component, tile->data() + offset + channel * sizeof(component), sizeof(component));
                            const auto encoded = static_cast<double>(component) / 65535.0;
                            color[channel] = encoded <= 0.04045 ? encoded / 12.92
                                : std::pow((encoded + 0.055) / 1.055, 2.4);
                        }
                        std::uint16_t alpha = 0;
                        std::memcpy(&alpha, tile->data() + offset + 3U * sizeof(alpha), sizeof(alpha));
                        color[3] = static_cast<double>(alpha) / 65535.0;
                    } else {
                        for (std::size_t channel = 0; channel < 4; ++channel) {
                            float component = 0.0F;
                            std::memcpy(&component, tile->data() + offset + channel * sizeof(component), sizeof(component));
                            color[channel] = std::isfinite(component) ? std::clamp(static_cast<double>(component), 0.0, 1.0) : 0.0;
                        }
                    }
                }
                for (std::size_t channel = 0; channel < 3; ++channel) premultiplied[channel] += color[channel] * color[3];
                alpha_sum += color[3];
                ++sample_count;
            }
        }
        if (sample_count != 0U) {
            result[3] = static_cast<float>(alpha_sum / sample_count);
            for (std::size_t channel = 0; channel < 3; ++channel) {
                const auto linear = alpha_sum > 0.0 ? std::clamp(premultiplied[channel] / alpha_sum, 0.0, 1.0) : 0.0;
                const auto encoded = linear <= 0.0031308 ? linear * 12.92
                    : 1.055 * std::pow(linear, 1.0 / 2.4) - 0.055;
                result[channel] = static_cast<float>(encoded);
            }
        }
        const auto output = env->NewFloatArray(4);
        if (output != nullptr) env->SetFloatArrayRegion(output, 0, 4, result.data());
        return output;
    } catch (const std::exception& exception) {
        __android_log_print(ANDROID_LOG_ERROR, "CropTopEngine", "Could not sample document pixels: %s", exception.what());
        return nullptr;
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_app_croptop_core_engine_NativeEngine_nativeDecodeToTileStore(JNIEnv* env, jobject, jbyteArray encoded,
                                                                  jint encoded_size, jlong store_handle, jint codec,
                                                                  jint exif_orientation) {
    auto* store = reinterpret_cast<NativeTileStore*>(store_handle);
    if (encoded == nullptr || store == nullptr || codec < 0 || codec > 4 || encoded_size <= 0 ||
        exif_orientation < 1 || exif_orientation > 8) {
        return env->NewStringUTF("invalid native decoder arguments");
    }
    const auto array_length = env->GetArrayLength(encoded);
    if (encoded_size > array_length) return env->NewStringUTF("encoded image length exceeds its byte array");
    jboolean copied = JNI_FALSE;
    auto* bytes = env->GetByteArrayElements(encoded, &copied);
    if (bytes == nullptr) return env->NewStringUTF("could not access encoded image bytes");

    std::string error;
    try {
        const auto input = std::span(reinterpret_cast<const std::uint8_t*>(bytes), static_cast<std::size_t>(encoded_size));
        croptop::DecodeResult result;
        if (codec == 0) result = croptop::decodeJpegToTiles(input, store->pixels, 32768, 500'000'000, exif_orientation);
        else if (codec == 1) result = croptop::decodePngToTiles(input, store->pixels, 32768, 500'000'000, exif_orientation);
        else if (codec == 2) result = croptop::decodeBmpToTiles(input, store->pixels);
        else if (codec == 3) result = croptop::decodeWebpToTiles(input, store->pixels, 16383, 500'000'000,
                                                                  croptop::kStillImageMaxWorkingBytes,
                                                                  static_cast<std::uint8_t>(exif_orientation));
        else result = croptop::decodeGifToTiles(input, store->pixels);
        if (!result.success) error = result.error;
    } catch (const std::exception& failure) {
        error = failure.what();
    } catch (...) {
        error = "unknown native image decoder failure";
    }
    env->ReleaseByteArrayElements(encoded, bytes, JNI_ABORT);
    return error.empty() ? nullptr : env->NewStringUTF(error.c_str());
}

extern "C" JNIEXPORT jlong JNICALL
Java_app_croptop_core_engine_NativeEngine_nativeEncodePngToStream(JNIEnv* env, jobject, jlong store_handle,
                                                                  jobject output_stream, jint compression_level,
                                                                  jbyteArray icc_profile, jbyteArray exif_tiff, jfloatArray background_color) {
    auto* store = reinterpret_cast<NativeTileStore*>(store_handle);
    if (store == nullptr || output_stream == nullptr || compression_level < 0 || compression_level > 9 || background_color == nullptr ||
        env->GetArrayLength(background_color) != 4) return -1;
    const auto output_class = env->GetObjectClass(output_stream);
    if (output_class == nullptr) return -1;
    const auto write_method = env->GetMethodID(output_class, "write", "([BII)V");
    env->DeleteLocalRef(output_class);
    if (write_method == nullptr || env->ExceptionCheck()) return -1;
    jbyte* icc_bytes = icc_profile == nullptr ? nullptr : env->GetByteArrayElements(icc_profile, nullptr);
    if (icc_profile != nullptr && icc_bytes == nullptr) return -1;
    jbyte* exif_bytes = exif_tiff == nullptr ? nullptr : env->GetByteArrayElements(exif_tiff, nullptr);
    if (exif_tiff != nullptr && exif_bytes == nullptr) {
        if (icc_bytes != nullptr) env->ReleaseByteArrayElements(icc_profile, icc_bytes, JNI_ABORT);
        return -1;
    }
    const auto icc_length = icc_profile == nullptr ? 0 : env->GetArrayLength(icc_profile);
    const auto exif_length = exif_tiff == nullptr ? 0 : env->GetArrayLength(exif_tiff);
    std::array<jfloat, 4> background{};
    env->GetFloatArrayRegion(background_color, 0, 4, background.data());
    if (env->ExceptionCheck()) {
        if (icc_bytes != nullptr) env->ReleaseByteArrayElements(icc_profile, icc_bytes, JNI_ABORT);
        if (exif_bytes != nullptr) env->ReleaseByteArrayElements(exif_tiff, exif_bytes, JNI_ABORT);
        return -1;
    }
    constexpr jsize kChunkCapacity = 64 * 1024;
    const auto chunk = env->NewByteArray(kChunkCapacity);
    if (chunk == nullptr) {
        if (icc_bytes != nullptr) env->ReleaseByteArrayElements(icc_profile, icc_bytes, JNI_ABORT);
        if (exif_bytes != nullptr) env->ReleaseByteArrayElements(exif_tiff, exif_bytes, JNI_ABORT);
        return -1;
    }
    jlong bytes_written = 0;
    const croptop::EncodedBytesSink sink = [&](std::span<const std::uint8_t> bytes) {
        std::size_t offset = 0;
        while (offset < bytes.size()) {
            const auto count = std::min<std::size_t>(kChunkCapacity, bytes.size() - offset);
            env->SetByteArrayRegion(chunk, 0, static_cast<jsize>(count),
                                    reinterpret_cast<const jbyte*>(bytes.data() + offset));
            if (env->ExceptionCheck()) return false;
            env->CallVoidMethod(output_stream, write_method, chunk, 0, static_cast<jint>(count));
            if (env->ExceptionCheck()) return false;
            bytes_written += static_cast<jlong>(count);
            offset += count;
        }
        return true;
    };
    const croptop::PngEncodeMetadata metadata{
        icc_bytes == nullptr ? std::span<const std::uint8_t>{}
                             : std::span(reinterpret_cast<const std::uint8_t*>(icc_bytes),
                                         static_cast<std::size_t>(icc_length)),
        exif_bytes == nullptr ? std::span<const std::uint8_t>{}
                              : std::span(reinterpret_cast<const std::uint8_t*>(exif_bytes),
                                          static_cast<std::size_t>(exif_length)),
        {background[0], background[1], background[2], background[3]},
    };
    croptop::EncodeResult result{};
    try {
        result = croptop::encodePngToSink(store->pixels, sink, compression_level, metadata);
    } catch (...) {
        env->DeleteLocalRef(chunk);
        if (icc_bytes != nullptr) env->ReleaseByteArrayElements(icc_profile, icc_bytes, JNI_ABORT);
        if (exif_bytes != nullptr) env->ReleaseByteArrayElements(exif_tiff, exif_bytes, JNI_ABORT);
        return -1;
    }
    env->DeleteLocalRef(chunk);
    if (icc_bytes != nullptr) env->ReleaseByteArrayElements(icc_profile, icc_bytes, JNI_ABORT);
    if (exif_bytes != nullptr) env->ReleaseByteArrayElements(exif_tiff, exif_bytes, JNI_ABORT);
    if (env->ExceptionCheck()) return -1;
    return result.success ? bytes_written : -1;
}

extern "C" JNIEXPORT jlong JNICALL
Java_app_croptop_core_engine_NativeEngine_nativeEncodeJpegToStream(JNIEnv* env, jobject, jlong store_handle,
                                                                   jobject output_stream, jint quality,
                                                                   jbyteArray icc_profile, jbyteArray exif_tiff, jfloatArray background_color) {
    auto* store = reinterpret_cast<NativeTileStore*>(store_handle);
    if (store == nullptr || output_stream == nullptr || quality < 1 || quality > 100 || background_color == nullptr ||
        env->GetArrayLength(background_color) != 4) return -1;
    const auto output_class = env->GetObjectClass(output_stream);
    if (output_class == nullptr) return -1;
    const auto write_method = env->GetMethodID(output_class, "write", "([BII)V");
    env->DeleteLocalRef(output_class);
    if (write_method == nullptr || env->ExceptionCheck()) return -1;
    jbyte* icc_bytes = icc_profile == nullptr ? nullptr : env->GetByteArrayElements(icc_profile, nullptr);
    if (icc_profile != nullptr && icc_bytes == nullptr) return -1;
    jbyte* exif_bytes = exif_tiff == nullptr ? nullptr : env->GetByteArrayElements(exif_tiff, nullptr);
    if (exif_tiff != nullptr && exif_bytes == nullptr) {
        if (icc_bytes != nullptr) env->ReleaseByteArrayElements(icc_profile, icc_bytes, JNI_ABORT);
        return -1;
    }
    const auto icc_length = icc_profile == nullptr ? 0 : env->GetArrayLength(icc_profile);
    const auto exif_length = exif_tiff == nullptr ? 0 : env->GetArrayLength(exif_tiff);
    std::array<jfloat, 4> background{};
    env->GetFloatArrayRegion(background_color, 0, 4, background.data());
    if (env->ExceptionCheck()) {
        if (icc_bytes != nullptr) env->ReleaseByteArrayElements(icc_profile, icc_bytes, JNI_ABORT);
        if (exif_bytes != nullptr) env->ReleaseByteArrayElements(exif_tiff, exif_bytes, JNI_ABORT);
        return -1;
    }
    constexpr jsize kChunkCapacity = 64 * 1024;
    const auto chunk = env->NewByteArray(kChunkCapacity);
    if (chunk == nullptr) {
        if (icc_bytes != nullptr) env->ReleaseByteArrayElements(icc_profile, icc_bytes, JNI_ABORT);
        if (exif_bytes != nullptr) env->ReleaseByteArrayElements(exif_tiff, exif_bytes, JNI_ABORT);
        return -1;
    }
    jlong bytes_written = 0;
    const croptop::EncodedBytesSink sink = [&](std::span<const std::uint8_t> bytes) {
        std::size_t offset = 0;
        while (offset < bytes.size()) {
            const auto count = std::min<std::size_t>(kChunkCapacity, bytes.size() - offset);
            env->SetByteArrayRegion(chunk, 0, static_cast<jsize>(count),
                                    reinterpret_cast<const jbyte*>(bytes.data() + offset));
            if (env->ExceptionCheck()) return false;
            env->CallVoidMethod(output_stream, write_method, chunk, 0, static_cast<jint>(count));
            if (env->ExceptionCheck()) return false;
            bytes_written += static_cast<jlong>(count);
            offset += count;
        }
        return true;
    };
    const croptop::JpegEncodeMetadata metadata{
        icc_bytes == nullptr ? std::span<const std::uint8_t>{}
                             : std::span(reinterpret_cast<const std::uint8_t*>(icc_bytes),
                                         static_cast<std::size_t>(icc_length)),
        exif_bytes == nullptr ? std::span<const std::uint8_t>{}
                              : std::span(reinterpret_cast<const std::uint8_t*>(exif_bytes),
                                          static_cast<std::size_t>(exif_length)),
        {background[0], background[1], background[2], background[3]},
    };
    croptop::EncodeResult result{};
    try {
        result = croptop::encodeJpegToSink(store->pixels, sink, quality, metadata);
    } catch (...) {
        env->DeleteLocalRef(chunk);
        if (icc_bytes != nullptr) env->ReleaseByteArrayElements(icc_profile, icc_bytes, JNI_ABORT);
        if (exif_bytes != nullptr) env->ReleaseByteArrayElements(exif_tiff, exif_bytes, JNI_ABORT);
        return -1;
    }
    env->DeleteLocalRef(chunk);
    if (icc_bytes != nullptr) env->ReleaseByteArrayElements(icc_profile, icc_bytes, JNI_ABORT);
    if (exif_bytes != nullptr) env->ReleaseByteArrayElements(exif_tiff, exif_bytes, JNI_ABORT);
    if (env->ExceptionCheck()) return -1;
    return result.success ? bytes_written : -1;
}

extern "C" JNIEXPORT jlong JNICALL
Java_app_croptop_core_engine_NativeEngine_nativeEncodeWebpToStream(JNIEnv* env, jobject, jlong store_handle,
                                                                   jobject output_stream, jint quality,
                                                                   jlong max_working_bytes,
                                                                   jbyteArray icc_profile, jbyteArray exif_tiff, jfloatArray background_color) {
    auto* store = reinterpret_cast<NativeTileStore*>(store_handle);
    if (store == nullptr || output_stream == nullptr || quality < 1 || quality > 100 || max_working_bytes <= 0 || background_color == nullptr ||
        env->GetArrayLength(background_color) != 4) return -1;
    const auto output_class = env->GetObjectClass(output_stream);
    if (output_class == nullptr) return -1;
    const auto write_method = env->GetMethodID(output_class, "write", "([BII)V");
    env->DeleteLocalRef(output_class);
    if (write_method == nullptr || env->ExceptionCheck()) return -1;
    jbyte* icc_bytes = icc_profile == nullptr ? nullptr : env->GetByteArrayElements(icc_profile, nullptr);
    if (icc_profile != nullptr && icc_bytes == nullptr) return -1;
    jbyte* exif_bytes = exif_tiff == nullptr ? nullptr : env->GetByteArrayElements(exif_tiff, nullptr);
    if (exif_tiff != nullptr && exif_bytes == nullptr) {
        if (icc_bytes != nullptr) env->ReleaseByteArrayElements(icc_profile, icc_bytes, JNI_ABORT);
        return -1;
    }
    const auto icc_length = icc_profile == nullptr ? 0 : env->GetArrayLength(icc_profile);
    const auto exif_length = exif_tiff == nullptr ? 0 : env->GetArrayLength(exif_tiff);
    std::array<jfloat, 4> background{};
    env->GetFloatArrayRegion(background_color, 0, 4, background.data());
    if (env->ExceptionCheck()) {
        if (icc_bytes != nullptr) env->ReleaseByteArrayElements(icc_profile, icc_bytes, JNI_ABORT);
        if (exif_bytes != nullptr) env->ReleaseByteArrayElements(exif_tiff, exif_bytes, JNI_ABORT);
        return -1;
    }
    constexpr jsize kChunkCapacity = 64 * 1024;
    const auto chunk = env->NewByteArray(kChunkCapacity);
    if (chunk == nullptr) {
        if (icc_bytes != nullptr) env->ReleaseByteArrayElements(icc_profile, icc_bytes, JNI_ABORT);
        if (exif_bytes != nullptr) env->ReleaseByteArrayElements(exif_tiff, exif_bytes, JNI_ABORT);
        return -1;
    }
    jlong bytes_written = 0;
    const croptop::EncodedBytesSink sink = [&](std::span<const std::uint8_t> bytes) {
        std::size_t offset = 0;
        while (offset < bytes.size()) {
            const auto count = std::min<std::size_t>(kChunkCapacity, bytes.size() - offset);
            env->SetByteArrayRegion(chunk, 0, static_cast<jsize>(count),
                                    reinterpret_cast<const jbyte*>(bytes.data() + offset));
            if (env->ExceptionCheck()) return false;
            env->CallVoidMethod(output_stream, write_method, chunk, 0, static_cast<jint>(count));
            if (env->ExceptionCheck()) return false;
            bytes_written += static_cast<jlong>(count);
            offset += count;
        }
        return true;
    };
    croptop::WebpEncodeOptions options;
    options.quality = static_cast<float>(quality);
    options.lossless = false;
    options.method = 4;
    options.max_working_bytes = static_cast<std::uint64_t>(max_working_bytes);
    options.canvas_background = {background[0], background[1], background[2], background[3]};
    if (icc_bytes != nullptr) {
        options.icc_profile = std::span(reinterpret_cast<const std::uint8_t*>(icc_bytes),
                                        static_cast<std::size_t>(icc_length));
    }
    if (exif_bytes != nullptr) {
        options.exif_tiff = std::span(reinterpret_cast<const std::uint8_t*>(exif_bytes),
                                      static_cast<std::size_t>(exif_length));
    }
    croptop::EncodeResult result{};
    try {
        result = croptop::encodeWebpToSink(store->pixels, sink, options);
    } catch (...) {
        env->DeleteLocalRef(chunk);
        if (icc_bytes != nullptr) env->ReleaseByteArrayElements(icc_profile, icc_bytes, JNI_ABORT);
        if (exif_bytes != nullptr) env->ReleaseByteArrayElements(exif_tiff, exif_bytes, JNI_ABORT);
        return -1;
    }
    env->DeleteLocalRef(chunk);
    if (icc_bytes != nullptr) env->ReleaseByteArrayElements(icc_profile, icc_bytes, JNI_ABORT);
    if (exif_bytes != nullptr) env->ReleaseByteArrayElements(exif_tiff, exif_bytes, JNI_ABORT);
    if (env->ExceptionCheck()) return -1;
    return result.success ? bytes_written : -1;
}

extern "C" JNIEXPORT jlong JNICALL
Java_app_croptop_core_engine_NativeEngine_nativeSaveCroptop(JNIEnv* env, jobject, jlongArray store_handles,
                                                           jobjectArray layer_uuids, jbyteArray manifest_json,
                                                           jbyteArray working_icc, jobject output_stream,
                                                           jint zstd_level, jint worker_threads, jobjectArray embedded_ids,
                                                           jobjectArray embedded_names, jobjectArray embedded_bytes) {
    if (store_handles == nullptr || layer_uuids == nullptr || manifest_json == nullptr || output_stream == nullptr ||
        zstd_level < -7 || zstd_level > 19 || worker_threads < 0 || worker_threads > 8) {
        return -1;
    }
    const auto count = env->GetArrayLength(store_handles);
    // Up to 500 document layers plus one A16 archive payload for each attached layer mask.
    if (count <= 0 || count > 1000 || env->GetArrayLength(layer_uuids) != count) return -1;
    const auto output_class = env->GetObjectClass(output_stream);
    if (output_class == nullptr) return -1;
    const auto write_method = env->GetMethodID(output_class, "write", "([BII)V");
    env->DeleteLocalRef(output_class);
    if (write_method == nullptr || env->ExceptionCheck()) return -1;

    std::vector<jlong> handles(static_cast<std::size_t>(count));
    env->GetLongArrayRegion(store_handles, 0, count, handles.data());
    if (env->ExceptionCheck()) return -1;
    std::vector<croptop::CroptopLayerPixels> layers;
    layers.reserve(static_cast<std::size_t>(count));
    for (jsize index = 0; index < count; ++index) {
        auto* store = reinterpret_cast<NativeTileStore*>(handles[static_cast<std::size_t>(index)]);
        auto uuid_string = static_cast<jstring>(env->GetObjectArrayElement(layer_uuids, index));
        if (store == nullptr || uuid_string == nullptr) {
            if (uuid_string != nullptr) env->DeleteLocalRef(uuid_string);
            return -1;
        }
        const char* utf = env->GetStringUTFChars(uuid_string, nullptr);
        if (utf == nullptr) {
            env->DeleteLocalRef(uuid_string);
            return -1;
        }
        layers.push_back({utf, &store->pixels});
        env->ReleaseStringUTFChars(uuid_string, utf);
        env->DeleteLocalRef(uuid_string);
    }

    auto* manifest_bytes = env->GetByteArrayElements(manifest_json, nullptr);
    if (manifest_bytes == nullptr) return -1;
    const auto manifest_length = env->GetArrayLength(manifest_json);
    jbyte* icc_bytes = working_icc == nullptr ? nullptr : env->GetByteArrayElements(working_icc, nullptr);
    if (working_icc != nullptr && icc_bytes == nullptr) {
        env->ReleaseByteArrayElements(manifest_json, manifest_bytes, JNI_ABORT);
        return -1;
    }
    const auto icc_length = working_icc == nullptr ? 0 : env->GetArrayLength(working_icc);
    constexpr jsize kChunkCapacity = 64 * 1024;
    const auto chunk = env->NewByteArray(kChunkCapacity);
    if (chunk == nullptr) {
        env->ReleaseByteArrayElements(manifest_json, manifest_bytes, JNI_ABORT);
        if (icc_bytes != nullptr) env->ReleaseByteArrayElements(working_icc, icc_bytes, JNI_ABORT);
        return -1;
    }
    jlong bytes_written = 0;
    const croptop::EncodedBytesSink sink = [&](std::span<const std::uint8_t> bytes) {
        std::size_t offset = 0;
        while (offset < bytes.size()) {
            const auto size = std::min<std::size_t>(kChunkCapacity, bytes.size() - offset);
            env->SetByteArrayRegion(chunk, 0, static_cast<jsize>(size),
                                    reinterpret_cast<const jbyte*>(bytes.data() + offset));
            if (env->ExceptionCheck()) return false;
            env->CallVoidMethod(output_stream, write_method, chunk, 0, static_cast<jint>(size));
            if (env->ExceptionCheck()) return false;
            bytes_written += static_cast<jlong>(size);
            offset += size;
        }
        return true;
    };
    croptop::CroptopSaveInput input;
    input.manifest_json = std::span(reinterpret_cast<const std::uint8_t*>(manifest_bytes),
                                    static_cast<std::size_t>(manifest_length));
    input.layers = std::move(layers);
    // Embedded files (a RAW smart object's original.raw and settings.xmp): copied out of the JVM so they outlive the sink calls.
    std::vector<std::vector<std::uint8_t>> embedded_storage;
    if (embedded_ids != nullptr && embedded_names != nullptr && embedded_bytes != nullptr) {
        const auto embedded_count = env->GetArrayLength(embedded_ids);
        if (embedded_count < 0 || embedded_count > 64 || env->GetArrayLength(embedded_names) != embedded_count ||
            env->GetArrayLength(embedded_bytes) != embedded_count) {
            env->DeleteLocalRef(chunk);
            env->ReleaseByteArrayElements(manifest_json, manifest_bytes, JNI_ABORT);
            if (icc_bytes != nullptr) env->ReleaseByteArrayElements(working_icc, icc_bytes, JNI_ABORT);
            return -1;
        }
        embedded_storage.reserve(static_cast<std::size_t>(embedded_count));
        std::vector<std::pair<std::string, std::string>> labels;
        bool embedded_ok = true;
        for (jsize i = 0; i < embedded_count && embedded_ok; ++i) {
            auto id = static_cast<jstring>(env->GetObjectArrayElement(embedded_ids, i));
            auto name = static_cast<jstring>(env->GetObjectArrayElement(embedded_names, i));
            auto data = static_cast<jbyteArray>(env->GetObjectArrayElement(embedded_bytes, i));
            if (id == nullptr || name == nullptr || data == nullptr) {
                embedded_ok = false;
            } else {
                const char* id_utf = env->GetStringUTFChars(id, nullptr);
                const char* name_utf = env->GetStringUTFChars(name, nullptr);
                if (id_utf == nullptr || name_utf == nullptr) {
                    embedded_ok = false;
                } else {
                    labels.emplace_back(id_utf, name_utf);
                    const auto length = env->GetArrayLength(data);
                    embedded_storage.emplace_back(static_cast<std::size_t>(length));
                    env->GetByteArrayRegion(data, 0, length, reinterpret_cast<jbyte*>(embedded_storage.back().data()));
                    if (env->ExceptionCheck()) embedded_ok = false;
                }
                if (id_utf != nullptr) env->ReleaseStringUTFChars(id, id_utf);
                if (name_utf != nullptr) env->ReleaseStringUTFChars(name, name_utf);
            }
            if (id != nullptr) env->DeleteLocalRef(id);
            if (name != nullptr) env->DeleteLocalRef(name);
            if (data != nullptr) env->DeleteLocalRef(data);
        }
        if (!embedded_ok) {
            env->DeleteLocalRef(chunk);
            env->ReleaseByteArrayElements(manifest_json, manifest_bytes, JNI_ABORT);
            if (icc_bytes != nullptr) env->ReleaseByteArrayElements(working_icc, icc_bytes, JNI_ABORT);
            return -1;
        }
        for (std::size_t i = 0; i < labels.size(); ++i) {
            input.embedded.push_back({labels[i].first, labels[i].second, std::span<const std::uint8_t>(embedded_storage[i])});
        }
    }
    if (icc_bytes != nullptr) {
        input.working_icc = std::span(reinterpret_cast<const std::uint8_t*>(icc_bytes),
                                      static_cast<std::size_t>(icc_length));
    }
    input.zstd_level = zstd_level;
    input.worker_threads = static_cast<unsigned>(worker_threads);
    croptop::EncodeResult result{};
    try {
        result = croptop::saveCroptop(input, sink);
    } catch (...) {
        result.success = false;
    }
    env->DeleteLocalRef(chunk);
    env->ReleaseByteArrayElements(manifest_json, manifest_bytes, JNI_ABORT);
    if (icc_bytes != nullptr) env->ReleaseByteArrayElements(working_icc, icc_bytes, JNI_ABORT);
    if (env->ExceptionCheck()) return -1;
    return result.success ? bytes_written : -1;
}

extern "C" JNIEXPORT jlong JNICALL
Java_app_croptop_core_engine_NativeCanvasView_nativeCreate(JNIEnv* env, jobject /* this */, jobject surface) {
    ANativeWindow* window = ANativeWindow_fromSurface(env, surface);
    if (window == nullptr) return 0;
    auto* canvas = new (std::nothrow) croptop::VulkanCanvas(window);
    ANativeWindow_release(window);
    if (canvas == nullptr || !canvas->valid()) {
        delete canvas;
        return 0;
    }
    return reinterpret_cast<jlong>(canvas);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_app_croptop_core_engine_NativeCanvasView_nativeResize(JNIEnv*, jobject, jlong handle, jint width, jint height) {
    auto* canvas = reinterpret_cast<croptop::VulkanCanvas*>(handle);
    return canvas != nullptr && canvas->resize(static_cast<std::uint32_t>(width), static_cast<std::uint32_t>(height))
        ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_app_croptop_core_engine_NativeCanvasView_nativeSetViewport(JNIEnv*, jobject, jlong handle, jfloat zoom,
        jfloat pan_x, jfloat pan_y, jfloat rotation_degrees, jboolean flip_horizontal) {
    auto* canvas = reinterpret_cast<croptop::VulkanCanvas*>(handle);
    return canvas != nullptr && canvas->setViewport(zoom, pan_x, pan_y, rotation_degrees,
        flip_horizontal == JNI_TRUE) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_app_croptop_core_engine_NativeCanvasView_nativeDraw(JNIEnv*, jobject, jlong handle) {
    auto* canvas = reinterpret_cast<croptop::VulkanCanvas*>(handle);
    return canvas != nullptr && canvas->draw() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_app_croptop_core_engine_NativeCanvasView_nativeGpuName(JNIEnv* env, jobject, jlong handle) {
    auto* canvas = reinterpret_cast<croptop::VulkanCanvas*>(handle);
    return env->NewStringUTF(canvas == nullptr ? "unavailable" : canvas->gpuName());
}

extern "C" JNIEXPORT void JNICALL
Java_app_croptop_core_engine_NativeCanvasView_nativeDestroy(JNIEnv*, jobject, jlong handle) {
    delete reinterpret_cast<croptop::VulkanCanvas*>(handle);
}

extern "C" JNIEXPORT jlong JNICALL
Java_app_croptop_core_engine_NativeVulkanFrameBridge_nativeCreate(JNIEnv* env, jobject, jobject surface) {
    ANativeWindow* window = ANativeWindow_fromSurface(env, surface);
    if (window == nullptr) return 0;
    auto* canvas = new (std::nothrow) croptop::VulkanCanvas(window);
    ANativeWindow_release(window);
    if (canvas == nullptr || !canvas->valid()) {
        delete canvas;
        return 0;
    }
    return reinterpret_cast<jlong>(canvas);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_app_croptop_core_engine_NativeVulkanFrameBridge_nativeDrawFrame(JNIEnv* env, jobject, jlong handle,
        jobject direct_buffer, jint width, jint height) {
    auto* canvas = reinterpret_cast<croptop::VulkanCanvas*>(handle);
    if (canvas == nullptr || direct_buffer == nullptr || width <= 0 || height <= 0) return JNI_FALSE;
    auto* pixels = static_cast<const std::uint8_t*>(env->GetDirectBufferAddress(direct_buffer));
    const jlong capacity = env->GetDirectBufferCapacity(direct_buffer);
    const auto required = static_cast<std::uint64_t>(width) * static_cast<std::uint64_t>(height) * 4U;
    if (pixels == nullptr || capacity < 0 || required > static_cast<std::uint64_t>(capacity)) return JNI_FALSE;
    return canvas->drawFrame(std::span<const std::uint8_t>(pixels, static_cast<std::size_t>(required)),
        static_cast<std::uint32_t>(width), static_cast<std::uint32_t>(height)) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_app_croptop_core_engine_NativeVulkanFrameBridge_nativeDestroy(JNIEnv*, jobject, jlong handle) {
    delete reinterpret_cast<croptop::VulkanCanvas*>(handle);
}

extern "C" JNIEXPORT jstring JNICALL
Java_app_croptop_core_engine_NativeVulkanFrameBridge_nativeGpuName(JNIEnv* env, jobject, jlong handle) {
    auto* canvas = reinterpret_cast<croptop::VulkanCanvas*>(handle);
    return env->NewStringUTF(canvas == nullptr ? "unavailable" : canvas->gpuName());
}

extern "C" JNIEXPORT jint JNICALL
Java_app_croptop_core_engine_NativeVulkanFrameBridge_nativeVulkanApiVersion(JNIEnv*, jobject, jlong handle) {
    auto* canvas = reinterpret_cast<croptop::VulkanCanvas*>(handle);
    return canvas == nullptr ? 0 : static_cast<jint>(canvas->vulkanApiVersion());
}
