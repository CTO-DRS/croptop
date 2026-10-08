// JNI surface for layer styles (class app.croptop.core.engine.NativeLayerStylesBridge). Settings travel as one FloatArray
// (layout in layer_styles_codec.h); the optional pattern is an RGBA store handle.
#if defined(__ANDROID__)
#include <jni.h>

#include <cstring>
#include <memory>
#include <stdexcept>
#include <string>
#include <vector>

#include "layer_styles_codec.h"
#include "native_store_handle.h"

namespace {
thread_local std::string g_styles_error;
using Store = croptop::jni::NativeTileStore;

croptop::styles::LayerStyles decode(JNIEnv* env, jfloatArray settings, jlong pattern_handle) {
    if (settings == nullptr) throw std::invalid_argument("style settings are null");
    const jsize n = env->GetArrayLength(settings);
    if (n <= 0 || n > 4096) throw std::invalid_argument("style settings have an invalid length");
    std::vector<jfloat> values(static_cast<std::size_t>(n));
    env->GetFloatArrayRegion(settings, 0, n, values.data());
    const auto* pattern = reinterpret_cast<const Store*>(pattern_handle);
    std::string error;
    auto styles = croptop::styles::decodeStyles(values, pattern != nullptr ? &pattern->pixels : nullptr, error);
    if (!styles) throw std::invalid_argument(error);
    return *styles;
}
}  // namespace

extern "C" JNIEXPORT jstring JNICALL Java_app_croptop_core_engine_NativeLayerStylesBridge_nativeLastError(JNIEnv* env, jobject) {
    return env->NewStringUTF(g_styles_error.c_str());
}

/**
 * Effect layers for the compositor. Returns a long array [handle, mode, opacityBits, belowContent, ...] with 4 longs per effect (the store
 * handle is owned by the caller; opacity is the float's IEEE bits), or null (nativeLastError).
 */
extern "C" JNIEXPORT jlongArray JNICALL Java_app_croptop_core_engine_NativeLayerStylesBridge_nativeRender(JNIEnv* env, jobject, jlong layer_handle, jfloatArray settings, jlong pattern_handle) {
    g_styles_error.clear();
    try {
        const auto* layer = reinterpret_cast<const Store*>(layer_handle);
        if (layer == nullptr) throw std::invalid_argument("layer store handle is null");
        const auto styles = decode(env, settings, pattern_handle);
        std::string error;
        auto effects = croptop::styles::render(layer->pixels, styles, error);
        if (!effects) throw std::invalid_argument(error);
        std::vector<jlong> out;
        out.reserve(effects->size() * 4U);
        for (auto& effect : *effects) {
            std::uint32_t bits = 0;
            static_assert(sizeof(bits) == sizeof(effect.opacity));
            std::memcpy(&bits, &effect.opacity, sizeof(bits));
            out.push_back(reinterpret_cast<jlong>(new Store(std::move(effect.pixels))));
            out.push_back(static_cast<jlong>(effect.mode));
            out.push_back(static_cast<jlong>(bits));
            out.push_back(effect.below_content ? 1 : 0);
        }
        auto array = env->NewLongArray(static_cast<jsize>(out.size()));
        if (array != nullptr) env->SetLongArrayRegion(array, 0, static_cast<jsize>(out.size()), out.data());
        return array;
    } catch (const std::exception& e) {
        g_styles_error = e.what();
    } catch (...) {
        g_styles_error = "unknown layer style failure";
    }
    return nullptr;
}

/** The layer drawn at `fill_opacity` with its styles composited onto the backdrop; returns a new store handle or 0. */
extern "C" JNIEXPORT jlong JNICALL Java_app_croptop_core_engine_NativeLayerStylesBridge_nativeFlatten(JNIEnv* env, jobject, jlong layer_handle, jfloatArray settings, jlong pattern_handle,
                                                                                                      jlong backdrop_handle, jfloat fill_opacity) {
    g_styles_error.clear();
    try {
        const auto* layer = reinterpret_cast<const Store*>(layer_handle);
        const auto* backdrop = reinterpret_cast<const Store*>(backdrop_handle);
        if (layer == nullptr || backdrop == nullptr) throw std::invalid_argument("layer or backdrop handle is null");
        const auto styles = decode(env, settings, pattern_handle);
        std::string error;
        auto out = croptop::styles::flatten(layer->pixels, styles, backdrop->pixels, fill_opacity, error);
        if (!out) throw std::invalid_argument(error);
        return reinterpret_cast<jlong>(new Store(std::move(*out)));
    } catch (const std::exception& e) {
        g_styles_error = e.what();
    } catch (...) {
        g_styles_error = "unknown layer style failure";
    }
    return 0;
}
#endif  // __ANDROID__
