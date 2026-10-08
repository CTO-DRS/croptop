// Handoff for. JNI surface for the native manual defringe operation.
#if defined(__ANDROID__)
#include <jni.h>

#include <algorithm>
#include <stdexcept>
#include <string>
#include <utility>

#include "defringe.h"
#include "develop_mask_blend.h"
#include "native_store_handle.h"

namespace {

using Store = croptop::jni::NativeTileStore;
thread_local std::string g_defringe_error;

const Store& store(jlong handle, const char* name) {
    const auto* value = reinterpret_cast<const Store*>(handle);
    if (value == nullptr) throw std::invalid_argument(std::string(name) + " store handle is null");
    return *value;
}

template <typename Fn>
jlong guarded(Fn&& fn) {
    g_defringe_error.clear();
    try {
        return fn();
    } catch (const std::exception& e) {
        g_defringe_error = e.what();
    } catch (...) {
        g_defringe_error = "unknown defringe failure";
    }
    return 0;
}

}  // namespace

extern "C" JNIEXPORT jstring JNICALL Java_app_croptop_core_engine_NativeDefringeBridge_nativeLastError(JNIEnv* env, jobject) {
    return env->NewStringUTF(g_defringe_error.c_str());
}

extern "C" JNIEXPORT jlong JNICALL Java_app_croptop_core_engine_NativeDefringeBridge_nativeApply(
    JNIEnv*, jobject, jlong source_handle, jlong mask_handle, jfloat purple_amount, jfloat purple_hue_low, jfloat purple_hue_high,
    jfloat green_amount, jfloat green_hue_low, jfloat green_hue_high, jfloat edge_threshold, jint worker_threads) {
    return guarded([&]() -> jlong {
        const auto& source = store(source_handle, "Defringe source");
        croptop::lens::DefringeSpec spec;
        spec.purple_amount = purple_amount;
        spec.purple_hue_low = purple_hue_low;
        spec.purple_hue_high = purple_hue_high;
        spec.green_amount = green_amount;
        spec.green_hue_low = green_hue_low;
        spec.green_hue_high = green_hue_high;
        spec.edge_threshold = edge_threshold;
        spec.worker_threads = static_cast<unsigned>(std::max(0, worker_threads));
        std::string error;
        auto adjusted = croptop::lens::defringe(source.pixels, spec, error);
        if (!adjusted) throw std::invalid_argument(error.empty() ? "Defringe failed" : error);
        if (mask_handle != 0) {
            const auto& mask = store(mask_handle, "Defringe selection");
            auto blended = croptop::develop::mask_blend::apply(source.pixels, *adjusted, mask.pixels, 1.0F, error);
            if (!blended) throw std::invalid_argument(error.empty() ? "Could not apply the Defringe selection" : error);
            adjusted.emplace(std::move(*blended));
        }
        return reinterpret_cast<jlong>(new Store(std::move(*adjusted)));
    });
}
#endif  // __ANDROID__
