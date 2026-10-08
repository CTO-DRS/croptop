// JNI surface for the pixel filters (class app.croptop.core.engine.NativeFiltersBridge): one call applies any filter
// from filter_dispatch.h to a copy-on-write clone of a layer store and returns the clone, so the source stays untouched for history.
#if defined(__ANDROID__)
#include <jni.h>

#include <algorithm>
#include <stdexcept>
#include <string>
#include <vector>

#include "filter_dispatch.h"
#include "native_store_handle.h"

namespace {
thread_local std::string g_filter_error;
}

extern "C" JNIEXPORT jstring JNICALL Java_app_croptop_core_engine_NativeFiltersBridge_nativeLastError(JNIEnv* env, jobject) {
    return env->NewStringUTF(g_filter_error.c_str());
}

extern "C" JNIEXPORT jint JNICALL Java_app_croptop_core_engine_NativeFiltersBridge_nativeParameterCount(JNIEnv*, jobject, jint id) {
    return croptop::filters::filterParameterCount(id);
}

/**
 * Applies filter `id` with `params` (layout in filter_dispatch.h) to a clone of `store_handle`; `mask_handle` (an A16 store, 0 = none)
 * limits the effect and `map_handle` is Displace's map (0 otherwise). Returns the new store handle the caller owns, or 0 (nativeLastError).
 */
extern "C" JNIEXPORT jlong JNICALL Java_app_croptop_core_engine_NativeFiltersBridge_nativeApply(JNIEnv* env, jobject, jlong store_handle, jint id, jfloatArray params,
                                                                                               jlong mask_handle, jlong map_handle, jint seed, jint worker_threads) {
    g_filter_error.clear();
    try {
        const auto* source = reinterpret_cast<const croptop::jni::NativeTileStore*>(store_handle);
        if (source == nullptr) throw std::invalid_argument("layer store handle is null");
        const auto* mask = reinterpret_cast<const croptop::jni::NativeTileStore*>(mask_handle);
        const auto* map = reinterpret_cast<const croptop::jni::NativeTileStore*>(map_handle);
        std::vector<jfloat> values;
        if (params != nullptr) {
            const jsize n = env->GetArrayLength(params);
            if (n < 0 || n > 64) throw std::invalid_argument("too many filter parameters");
            values.resize(static_cast<std::size_t>(n));
            if (n > 0) env->GetFloatArrayRegion(params, 0, n, values.data());
        }
        auto result = std::make_unique<croptop::jni::NativeTileStore>(*source);  // COW clone: shares tiles until the filter writes
        std::string error;
        if (!croptop::filters::applyFilter(result->pixels, id, values, map != nullptr ? &map->pixels : nullptr, mask != nullptr ? &mask->pixels : nullptr,
                                           static_cast<std::uint32_t>(seed), static_cast<unsigned>(std::max(0, worker_threads)), error)) {
            throw std::invalid_argument(error.empty() ? "filter failed" : error);
        }
        return reinterpret_cast<jlong>(result.release());
    } catch (const std::exception& e) {
        g_filter_error = e.what();
    } catch (...) {
        g_filter_error = "unknown filter failure";
    }
    return 0;
}
#endif  // __ANDROID__
