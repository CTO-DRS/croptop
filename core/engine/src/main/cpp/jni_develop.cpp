// JNI surface for the develop pipeline (class app.croptop.core.engine.NativeDevelopBridge): renders a scene-linear
// layer through DevelopSettings (flat encoding in develop_settings_codec.h) into a new store handle, on the GPU when a hardware
// compute device exists (tone and colour; each stage falls back to the CPU on its own).
#if defined(__ANDROID__)
#include <jni.h>

#include <algorithm>
#include <atomic>
#include <memory>
#include <stdexcept>
#include <string>
#include <vector>

#include "develop_pipeline.h"
#include "develop_settings_codec.h"
#include "gpu_service.h"
#include "native_store_handle.h"

namespace {
// Preview generation clock: a render started with generation G stops at its next progress boundary once the clock moves past G.
std::shared_ptr<std::atomic<std::uint64_t>> generationClock() {
    static const auto clock = std::make_shared<std::atomic<std::uint64_t>>(0);
    return clock;
}
thread_local std::string g_develop_error;
thread_local std::string g_develop_info;
}  // namespace

extern "C" JNIEXPORT jstring JNICALL Java_app_croptop_core_engine_NativeDevelopBridge_nativeLastError(JNIEnv* env, jobject) {
    return env->NewStringUTF(g_develop_error.c_str());
}

/** "width=..;height=..;toneGpu=0|1;colorGpu=0|1;elapsedMs=..;warnings=a|b" for the last successful render on this thread. */
extern "C" JNIEXPORT jstring JNICALL Java_app_croptop_core_engine_NativeDevelopBridge_nativeLastInfo(JNIEnv* env, jobject) {
    return env->NewStringUTF(g_develop_info.c_str());
}

/** Human-readable reason the GPU is unavailable ("" when a device exists or GPU use was not requested yet). */
extern "C" JNIEXPORT jstring JNICALL Java_app_croptop_core_engine_NativeDevelopBridge_nativeGpuStatus(JNIEnv* env, jobject) {
    auto& service = croptop::gpu::sharedGpuService();
    const auto device = service.acquire();
    const std::string text = device ? "device=" + device->deviceName() : "unavailable: " + service.lastError();
    return env->NewStringUTF(text.c_str());
}

/** Advances the clock (cancelling every render started with an older generation) and returns the new generation. */
extern "C" JNIEXPORT jlong JNICALL Java_app_croptop_core_engine_NativeDevelopBridge_nativeNewGeneration(JNIEnv*, jobject) {
    return static_cast<jlong>(generationClock()->fetch_add(1) + 1U);
}

/**
 * mode: 0 full resolution, 1 preview (longest side previewMax). tier: 0 custom, 1 interactive, 2 settled. Returns a new store
 * handle the caller owns (NativeSelectionBridge.destroyStore), or 0 (see nativeLastError).
 */
extern "C" JNIEXPORT jlong JNICALL Java_app_croptop_core_engine_NativeDevelopBridge_nativeRender(JNIEnv* env, jobject, jlong store_handle, jfloatArray settings, jint mode,
                                                                                                jint tier, jint preview_max, jint worker_threads, jboolean use_gpu,
                                                                                                jint grain_seed, jlong generation) {
    g_develop_error.clear();
    g_develop_info.clear();
    try {
        const auto* store = reinterpret_cast<const croptop::jni::NativeTileStore*>(store_handle);
        if (store == nullptr || settings == nullptr) throw std::invalid_argument("store or settings is null");
        if ((mode != 0 && mode != 1) || tier < 0 || tier > 2 || preview_max < 16 || preview_max > 8192) throw std::invalid_argument("invalid render request");
        const jsize n = env->GetArrayLength(settings);
        if (n < 0 || n > static_cast<jsize>(croptop::develop::kSettingsMaxFloats)) throw std::invalid_argument("settings array has an invalid length");
        std::vector<jfloat> values(static_cast<std::size_t>(n));
        env->GetFloatArrayRegion(settings, 0, n, values.data());
        std::string error;
        const auto decoded = croptop::develop::decodeSettings(values, error);
        if (!decoded) throw std::invalid_argument(error);
        croptop::develop::pipeline::Request request;
        request.mode = mode == 0 ? croptop::develop::pipeline::Mode::FullResolution : croptop::develop::pipeline::Mode::Preview;
        request.preview_tier = static_cast<croptop::develop::pipeline::PreviewTier>(tier);
        request.preview_max_dimension = static_cast<std::uint32_t>(preview_max);
        request.worker_threads = static_cast<unsigned>(std::max(0, worker_threads));
        request.grain_seed = static_cast<std::uint32_t>(grain_seed);
        if (generation > 0) {  // 0 = not cancellable
            request.preview_generation = static_cast<std::uint64_t>(generation);
            request.latest_preview_generation = generationClock();
        }
        std::shared_ptr<croptop::gpu::Compute> device;
        if (use_gpu == JNI_TRUE) {
            device = croptop::gpu::sharedGpuService().acquire();
            request.gpu = device.get();
        }
        auto result = croptop::develop::pipeline::render(store->pixels, *decoded, request, error);
        if (!result) throw std::invalid_argument(error);
        if (generation > 0 && result->preview_generation != generationClock()->load()) throw std::invalid_argument("cancelled");
        std::string warnings;
        for (const auto& w : result->warnings) warnings += (warnings.empty() ? "" : "|") + w;
        g_develop_info = "width=" + std::to_string(result->width) + ";height=" + std::to_string(result->height) + ";toneGpu=" + (result->tone_on_gpu ? "1" : "0") +
                         ";colorGpu=" + (result->color_on_gpu ? "1" : "0") + ";elapsedMs=" + std::to_string(result->elapsed.count() / 1000000) +
                         ";warnings=" + warnings;
        return reinterpret_cast<jlong>(new croptop::jni::NativeTileStore(std::move(result->pixels)));
    } catch (const std::exception& e) {
        g_develop_error = e.what();
    } catch (...) {
        g_develop_error = "unknown develop failure";
    }
    return 0;
}
#endif  // __ANDROID__
