// JNI surface for smart-object sessions (class app.croptop.core.engine.NativeSmartObjectBridge): a source layer with a transform chain,
// non-destructive smart filters (each with blend mode, opacity and an enabled flag) and an optional filter mask; render() is also "Rasterize".
#if defined(__ANDROID__)
#include <jni.h>

#include <algorithm>
#include <cmath>
#include <memory>
#include <stdexcept>
#include <string>
#include <vector>

#include "native_store_handle.h"
#include "smart_object.h"

namespace {

using Store = croptop::jni::NativeTileStore;
using Object = croptop::smart::SmartObject;
thread_local std::string g_smart_error;

Object& objectOf(jlong handle) {
    auto* o = reinterpret_cast<Object*>(handle);
    if (o == nullptr) throw std::invalid_argument("smart object handle is null");
    return *o;
}

template <typename Fn>
jlong guarded(Fn&& fn) {
    g_smart_error.clear();
    try {
        return fn();
    } catch (const std::exception& e) {
        g_smart_error = e.what();
    } catch (...) {
        g_smart_error = "unknown smart object failure";
    }
    return 0;
}

}  // namespace

extern "C" JNIEXPORT jstring JNICALL Java_app_croptop_core_engine_NativeSmartObjectBridge_nativeLastError(JNIEnv* env, jobject) {
    return env->NewStringUTF(g_smart_error.c_str());
}

/** Wraps a copy of the layer (placed 1:1 at the origin). Returns the session handle or 0. */
extern "C" JNIEXPORT jlong JNICALL Java_app_croptop_core_engine_NativeSmartObjectBridge_nativeConvert(JNIEnv*, jobject, jlong layer_handle) {
    return guarded([&]() -> jlong {
        const auto* layer = reinterpret_cast<const Store*>(layer_handle);
        if (layer == nullptr) throw std::invalid_argument("layer store handle is null");
        return reinterpret_cast<jlong>(new Object(croptop::smart::convertToSmartObject(layer->pixels)));
    });
}

extern "C" JNIEXPORT void JNICALL Java_app_croptop_core_engine_NativeSmartObjectBridge_nativeDestroy(JNIEnv*, jobject, jlong object) { delete reinterpret_cast<Object*>(object); }

/** `matrices` holds n row-major 3×3 source→destination matrices, applied first to last (n may be 0). */
extern "C" JNIEXPORT jlong JNICALL Java_app_croptop_core_engine_NativeSmartObjectBridge_nativeSetTransforms(JNIEnv* env, jobject, jlong object, jdoubleArray matrices) {
    return guarded([&]() -> jlong {
        auto& o = objectOf(object);
        const jsize n = matrices == nullptr ? 0 : env->GetArrayLength(matrices);
        if (n < 0 || n % 9 != 0 || n > 9 * 256) throw std::invalid_argument("transforms must be 0-256 matrices of nine numbers");
        std::vector<jdouble> values(static_cast<std::size_t>(n));
        if (n > 0) env->GetDoubleArrayRegion(matrices, 0, n, values.data());
        for (const double v : values) {
            if (!std::isfinite(v)) throw std::invalid_argument("transform values must be finite");
        }
        o.transforms.clear();
        for (jsize i = 0; i < n; i += 9) {
            croptop::smart::Matrix m{};
            for (int k = 0; k < 9; ++k) m[static_cast<std::size_t>(k)] = values[static_cast<std::size_t>(i + k)];
            o.transforms.push_back(m);
        }
        return 1;
    });
}

/** kind: 0 Gaussian blur (radius), 1 Unsharp mask (amount, radius, threshold). Returns the filter index. */
extern "C" JNIEXPORT jlong JNICALL Java_app_croptop_core_engine_NativeSmartObjectBridge_nativeAddFilter(JNIEnv* env, jobject, jlong object, jint kind, jfloatArray params, jint blend_mode, jfloat opacity) {
    return guarded([&]() -> jlong {
        auto& o = objectOf(object);
        if (o.filters.size() >= 64) throw std::invalid_argument("too many smart filters");
        if (blend_mode < 0 || blend_mode > static_cast<int>(croptop::BlendMode::Luminosity) || !(opacity >= 0.0F && opacity <= 1.0F)) throw std::invalid_argument("invalid filter blend settings");
        const jsize n = params == nullptr ? 0 : env->GetArrayLength(params);
        jfloat p[3] = {0, 0, 0};
        if (n < 0 || n > 3) throw std::invalid_argument("too many filter parameters");
        if (n > 0) env->GetFloatArrayRegion(params, 0, n, p);
        croptop::smart::SmartFilter filter;
        if (kind == 0 && n == 1) filter = croptop::smart::gaussianBlurFilter(p[0]);
        else if (kind == 1 && n == 3) filter = croptop::smart::unsharpMaskFilter(p[0], p[1], p[2]);
        else throw std::invalid_argument("unknown smart filter or wrong parameter count");
        filter.mode = static_cast<croptop::BlendMode>(blend_mode);
        filter.opacity = opacity;
        o.filters.push_back(std::move(filter));
        return static_cast<jlong>(o.filters.size() - 1U);
    });  // the filter index, or 0 on failure (a failure leaves nativeLastError non-empty)
}

extern "C" JNIEXPORT jlong JNICALL Java_app_croptop_core_engine_NativeSmartObjectBridge_nativeConfigureFilter(JNIEnv*, jobject, jlong object, jint index, jboolean enabled, jint blend_mode, jfloat opacity) {
    return guarded([&]() -> jlong {
        auto& o = objectOf(object);
        if (index < 0 || static_cast<std::size_t>(index) >= o.filters.size()) throw std::invalid_argument("no such smart filter");
        if (blend_mode < 0 || blend_mode > static_cast<int>(croptop::BlendMode::Luminosity) || !(opacity >= 0.0F && opacity <= 1.0F)) throw std::invalid_argument("invalid filter blend settings");
        auto& f = o.filters[static_cast<std::size_t>(index)];
        f.enabled = enabled == JNI_TRUE;
        f.mode = static_cast<croptop::BlendMode>(blend_mode);
        f.opacity = opacity;
        return 1;
    });
}

extern "C" JNIEXPORT jlong JNICALL Java_app_croptop_core_engine_NativeSmartObjectBridge_nativeRemoveFilter(JNIEnv*, jobject, jlong object, jint index) {
    return guarded([&]() -> jlong {
        auto& o = objectOf(object);
        if (index < 0 || static_cast<std::size_t>(index) >= o.filters.size()) throw std::invalid_argument("no such smart filter");
        o.filters.erase(o.filters.begin() + index);
        return 1;
    });
}

extern "C" JNIEXPORT jint JNICALL Java_app_croptop_core_engine_NativeSmartObjectBridge_nativeFilterCount(JNIEnv*, jobject, jlong object) {
    try {
        return static_cast<jint>(objectOf(object).filters.size());
    } catch (...) {
        return 0;
    }
}

/** `mask_handle` is an A16 store of the document size; 0 clears the filter mask. */
extern "C" JNIEXPORT jlong JNICALL Java_app_croptop_core_engine_NativeSmartObjectBridge_nativeSetFilterMask(JNIEnv*, jobject, jlong object, jlong mask_handle, jboolean enabled) {
    return guarded([&]() -> jlong {
        auto& o = objectOf(object);
        const auto* mask = reinterpret_cast<const Store*>(mask_handle);
        if (mask == nullptr) o.filter_mask.reset();
        else {
            o.filter_mask.reset();
            o.filter_mask.emplace(mask->pixels);
        }
        o.filter_mask_enabled = enabled == JNI_TRUE;
        return 1;
    });
}

/** Renders (rasterizes) into a document of the given size; quality: resample::Filter ordinal 0-3. Returns a new store handle or 0. */
extern "C" JNIEXPORT jlong JNICALL Java_app_croptop_core_engine_NativeSmartObjectBridge_nativeRender(JNIEnv*, jobject, jlong object, jint width, jint height, jint quality, jint worker_threads) {
    return guarded([&]() -> jlong {
        if (width <= 0 || height <= 0 || quality < 0 || quality > 3) throw std::invalid_argument("invalid render request");
        croptop::smart::RenderOptions options;
        options.quality = static_cast<croptop::resample::Filter>(quality);
        options.worker_threads = static_cast<unsigned>(std::max(0, worker_threads));
        std::string error;
        auto out = croptop::smart::render(objectOf(object), static_cast<std::uint32_t>(width), static_cast<std::uint32_t>(height), options, error);
        if (!out) throw std::invalid_argument(error);
        return reinterpret_cast<jlong>(new Store(std::move(*out)));
    });
}

extern "C" JNIEXPORT jlong JNICALL Java_app_croptop_core_engine_NativeSmartObjectBridge_nativeBlendFilter(JNIEnv*, jobject, jlong input_handle,
                                                                                                            jlong filtered_handle, jint blend_mode,
                                                                                                            jfloat opacity, jint worker_threads) {
    return guarded([&]() -> jlong {
        const auto* input = reinterpret_cast<const Store*>(input_handle);
        const auto* filtered = reinterpret_cast<const Store*>(filtered_handle);
        if (input == nullptr || filtered == nullptr) throw std::invalid_argument("smart filter stores are null");
        if (blend_mode < 0 || blend_mode >= static_cast<int>(croptop::BlendMode::Luminosity) + 1) throw std::invalid_argument("unknown smart filter blend mode");
        std::string error;
        auto output = croptop::smart::blendFilterResult(input->pixels, filtered->pixels, static_cast<croptop::BlendMode>(blend_mode), opacity,
                                                        static_cast<unsigned>(std::max(0, worker_threads)), error);
        if (!output) throw std::invalid_argument(error);
        return reinterpret_cast<jlong>(new Store(std::move(*output)));
    });
}

extern "C" JNIEXPORT jlong JNICALL Java_app_croptop_core_engine_NativeSmartObjectBridge_nativeBlendThroughMask(JNIEnv*, jobject, jlong base_handle,
                                                                                                                jlong filtered_handle, jlong mask_handle,
                                                                                                                jint worker_threads) {
    return guarded([&]() -> jlong {
        const auto* base = reinterpret_cast<const Store*>(base_handle);
        const auto* filtered = reinterpret_cast<const Store*>(filtered_handle);
        const auto* mask = reinterpret_cast<const Store*>(mask_handle);
        if (base == nullptr || filtered == nullptr || mask == nullptr) throw std::invalid_argument("smart filter mask stores are null");
        std::string error;
        auto output = croptop::smart::blendThroughMask(base->pixels, filtered->pixels, mask->pixels, static_cast<unsigned>(std::max(0, worker_threads)), error);
        if (!output) throw std::invalid_argument(error);
        return reinterpret_cast<jlong>(new Store(std::move(*output)));
    });
}
#endif  // __ANDROID__
