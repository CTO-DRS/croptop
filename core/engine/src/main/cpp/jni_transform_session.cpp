// JNI surface for layer transform sessions (class app.croptop.core.engine.NativeTransformBridge).
// Session and store handles are owned by the Kotlin caller; calls on one session must be serialised.
#if defined(__ANDROID__)
#include <jni.h>

#include <array>
#include <stdexcept>
#include <string>

#include "native_store_handle.h"
#include "transform_session.h"

namespace {

using croptop::jni::NativeTileStore;
using croptop::xform::LayerTransform;

thread_local std::string g_transform_error;

template <typename Result, typename Fn>
Result guarded(Result failure, Fn&& fn) {
    g_transform_error.clear();
    try {
        return fn();
    } catch (const std::exception& e) {
        g_transform_error = e.what();
    } catch (...) {
        g_transform_error = "unknown native transform error";
    }
    return failure;
}

LayerTransform& session(jlong handle) {
    auto* s = reinterpret_cast<LayerTransform*>(handle);
    if (s == nullptr) throw std::invalid_argument("transform session handle is null");
    return *s;
}

croptop::xform::Matrix matrix(JNIEnv* env, jfloatArray values) {
    if (values == nullptr || env->GetArrayLength(values) != 9) throw std::invalid_argument("a transform matrix has 9 values");
    std::array<float, 9> f{};
    env->GetFloatArrayRegion(values, 0, 9, f.data());
    croptop::xform::Matrix m{};
    for (std::size_t i = 0; i < 9; ++i) m[i] = f[i];
    return m;
}

}  // namespace

extern "C" JNIEXPORT jstring JNICALL Java_app_croptop_core_engine_NativeTransformBridge_nativeLastError(JNIEnv* env, jobject) {
    return env->NewStringUTF(g_transform_error.c_str());
}

/** Starts a session on a layer store; `selection_handle` 0 = the whole layer. Returns 0 on failure. */
extern "C" JNIEXPORT jlong JNICALL Java_app_croptop_core_engine_NativeTransformBridge_nativeBegin(JNIEnv*, jobject, jlong layer_handle, jlong selection_handle) {
    return guarded<jlong>(0, [&] {
        const auto* layer = reinterpret_cast<const NativeTileStore*>(layer_handle);
        if (layer == nullptr) throw std::invalid_argument("layer handle is null");
        const auto* selection = reinterpret_cast<const NativeTileStore*>(selection_handle);
        std::string error;
        auto s = LayerTransform::begin(layer->pixels, selection != nullptr ? &selection->pixels : nullptr, error);
        if (!s) throw std::invalid_argument(error);
        return reinterpret_cast<jlong>(s.release());
    });
}

/** Proxy preview: a new store handle (caller destroys it), or 0 on failure. */
extern "C" JNIEXPORT jlong JNICALL Java_app_croptop_core_engine_NativeTransformBridge_nativePreview(JNIEnv* env, jobject, jlong handle, jfloatArray values,
                                                                                                    jfloat scale) {
    return guarded<jlong>(0, [&] {
        std::string error;
        auto preview = session(handle).preview(matrix(env, values), scale, error);
        if (!preview) throw std::invalid_argument(error);
        return reinterpret_cast<jlong>(new NativeTileStore(std::move(*preview)));
    });
}

/**
 * Full-resolution result: [new store handle, dirty left, top, right, bottom], or null on failure. Filter:
 * 0 nearest, 1 bilinear, 2 bicubic, 3 Lanczos3.
 */
extern "C" JNIEXPORT jlongArray JNICALL Java_app_croptop_core_engine_NativeTransformBridge_nativeCommit(JNIEnv* env, jobject, jlong handle, jfloatArray values,
                                                                                                        jint filter) {
    return guarded<jlongArray>(nullptr, [&]() -> jlongArray {
        if (filter < 0 || filter > 3) throw std::invalid_argument("resample filter is out of range");
        std::string error;
        auto committed = session(handle).commit(matrix(env, values), error, static_cast<croptop::resample::Filter>(filter));
        if (!committed) throw std::invalid_argument(error);
        const auto dirty = committed->dirty;
        auto* store = new NativeTileStore(std::move(committed->pixels));
        const std::array<jlong, 5> out{reinterpret_cast<jlong>(store), dirty.left, dirty.top, dirty.right, dirty.bottom};
        auto result = env->NewLongArray(5);
        if (result == nullptr) {
            delete store;
            throw std::runtime_error("could not allocate the commit result");
        }
        env->SetLongArrayRegion(result, 0, 5, out.data());
        if (env->ExceptionCheck()) {
            delete store;
            return nullptr;
        }
        return result;
    });
}

extern "C" JNIEXPORT void JNICALL Java_app_croptop_core_engine_NativeTransformBridge_nativeDestroy(JNIEnv*, jobject, jlong handle) {
    g_transform_error.clear();
    delete reinterpret_cast<LayerTransform*>(handle);
}
#endif
