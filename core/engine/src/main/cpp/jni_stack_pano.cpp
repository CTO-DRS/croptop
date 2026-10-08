// JNI surface for multi-image merges (class app.croptop.core.engine.NativeStackBridge): frame alignment, HDR merge, focus stacking and
// panorama stitching. Every call takes an array of layer store handles and returns a new store handle the caller owns. A progress callback is
// not exposed; a merge of a few frames takes seconds, so the UI should run it as a cancellable background job and simply discard late results.
#if defined(__ANDROID__)
#include <jni.h>

#include <algorithm>
#include <stdexcept>
#include <string>
#include <vector>

#include "native_store_handle.h"
#include "panorama.h"
#include "stack_merge.h"

namespace {

using Store = croptop::jni::NativeTileStore;
thread_local std::string g_stack_error;
thread_local std::string g_stack_info;

std::vector<const croptop::TileStore*> stores(JNIEnv* env, jlongArray handles, std::size_t min, std::size_t max) {
    if (handles == nullptr) throw std::invalid_argument("frame list is null");
    const jsize n = env->GetArrayLength(handles);
    if (n < 0 || static_cast<std::size_t>(n) < min || static_cast<std::size_t>(n) > max) {
        throw std::invalid_argument("expected " + std::to_string(min) + " to " + std::to_string(max) + " frames");
    }
    std::vector<jlong> raw(static_cast<std::size_t>(n));
    env->GetLongArrayRegion(handles, 0, n, raw.data());
    std::vector<const croptop::TileStore*> out;
    for (const auto h : raw) {
        const auto* s = reinterpret_cast<const Store*>(h);
        if (s == nullptr) throw std::invalid_argument("a frame handle is null");
        out.push_back(&s->pixels);
    }
    return out;
}

template <typename Fn>
jlong guarded(Fn&& fn) {
    g_stack_error.clear();
    g_stack_info.clear();
    try {
        return fn();
    } catch (const std::exception& e) {
        g_stack_error = e.what();
    } catch (...) {
        g_stack_error = "unknown merge failure";
    }
    return 0;
}

}  // namespace

extern "C" JNIEXPORT jstring JNICALL Java_app_croptop_core_engine_NativeStackBridge_nativeLastError(JNIEnv* env, jobject) {
    return env->NewStringUTF(g_stack_error.c_str());
}

/** Details of the last successful call, "key=value;..." (panorama: projection, placed, dropped, gains). */
extern "C" JNIEXPORT jstring JNICALL Java_app_croptop_core_engine_NativeStackBridge_nativeLastInfo(JNIEnv* env, jobject) {
    return env->NewStringUTF(g_stack_info.c_str());
}

/** [dx, dy] that aligns `frame` to `reference` (median-threshold-bitmap pyramid), or null. */
extern "C" JNIEXPORT jintArray JNICALL Java_app_croptop_core_engine_NativeStackBridge_nativeAlign(JNIEnv* env, jobject, jlong reference, jlong frame, jint levels) {
    g_stack_error.clear();
    try {
        const auto* r = reinterpret_cast<const Store*>(reference);
        const auto* f = reinterpret_cast<const Store*>(frame);
        if (r == nullptr || f == nullptr || levels < 1 || levels > 10) throw std::invalid_argument("invalid alignment request");
        const auto offset = croptop::stack::alignMtb(r->pixels, f->pixels, levels);
        const jint values[2] = {offset.dx, offset.dy};
        auto array = env->NewIntArray(2);
        if (array != nullptr) env->SetIntArrayRegion(array, 0, 2, values);
        return array;
    } catch (const std::exception& e) {
        g_stack_error = e.what();
    } catch (...) {
        g_stack_error = "unknown alignment failure";
    }
    return nullptr;
}

/** Merge to HDR (linear RGBA32F); `exposures` are the relative exposures per frame (> 0). */
extern "C" JNIEXPORT jlong JNICALL Java_app_croptop_core_engine_NativeStackBridge_nativeMergeHdr(JNIEnv* env, jobject, jlongArray handles, jfloatArray exposures, jboolean align, jint worker_threads) {
    return guarded([&]() -> jlong {
        const auto frames = stores(env, handles, 2, 32);
        if (exposures == nullptr || env->GetArrayLength(exposures) != static_cast<jsize>(frames.size())) throw std::invalid_argument("one exposure per frame is required");
        std::vector<jfloat> e(frames.size());
        env->GetFloatArrayRegion(exposures, 0, static_cast<jsize>(frames.size()), e.data());
        std::vector<croptop::stack::HdrFrame> hdr;
        for (std::size_t i = 0; i < frames.size(); ++i) {
            if (!(e[i] > 0.0F)) throw std::invalid_argument("exposures must be positive");
            hdr.push_back({frames[i], e[i]});
        }
        croptop::stack::MergeOptions options;
        options.align = align == JNI_TRUE;
        options.worker_threads = static_cast<unsigned>(std::max(0, worker_threads));
        std::string error;
        auto out = croptop::stack::mergeHdr(hdr, options, error);
        if (!out) throw std::invalid_argument(error);
        return reinterpret_cast<jlong>(new Store(std::move(*out)));
    });
}

extern "C" JNIEXPORT jlong JNICALL Java_app_croptop_core_engine_NativeStackBridge_nativeFocusStack(JNIEnv* env, jobject, jlongArray handles, jboolean align, jfloat smoothing, jint worker_threads) {
    return guarded([&]() -> jlong {
        const auto frames = stores(env, handles, 2, 64);
        croptop::stack::FocusOptions options;
        options.align = align == JNI_TRUE;
        options.smoothing = smoothing;
        options.worker_threads = static_cast<unsigned>(std::max(0, worker_threads));
        std::string error;
        auto out = croptop::stack::focusStack(frames, options, error);
        if (!out) throw std::invalid_argument(error);
        return reinterpret_cast<jlong>(new Store(std::move(*out)));
    });
}

/** projection: 0 auto, 1 perspective, 2 cylindrical, 3 reposition. Info: projection, placed, dropped, gains, anchor. */
extern "C" JNIEXPORT jlong JNICALL Java_app_croptop_core_engine_NativeStackBridge_nativeStitch(JNIEnv* env, jobject, jlongArray handles, jint projection, jboolean blend, jboolean exposure_compensation,
                                                                                              jint worker_threads) {
    return guarded([&]() -> jlong {
        const auto images = stores(env, handles, 2, 32);
        if (projection < 0 || projection > 3) throw std::invalid_argument("unknown panorama projection");
        croptop::panorama::StitchOptions options;
        options.projection = static_cast<croptop::panorama::Projection>(projection);
        options.blend = blend == JNI_TRUE;
        options.exposure_compensation = exposure_compensation == JNI_TRUE;
        options.worker_threads = static_cast<unsigned>(std::max(0, worker_threads));
        std::string error;
        auto result = croptop::panorama::stitch(images, options, error);
        if (!result) throw std::invalid_argument(error);
        const auto join = [](const std::vector<std::size_t>& v) {
            std::string s;
            for (const auto i : v) s += (s.empty() ? "" : ",") + std::to_string(i);
            return s;
        };
        std::string gains;
        for (const float g : result->gains) gains += (gains.empty() ? "" : ",") + std::to_string(g);
        g_stack_info = "projection=" + std::to_string(static_cast<int>(result->projection)) + ";placed=" + join(result->placed) + ";dropped=" + join(result->dropped) +
                       ";gains=" + gains + ";anchor=" + std::to_string(result->anchor);
        return reinterpret_cast<jlong>(new Store(std::move(result->image)));
    });
}
#endif  // __ANDROID__
