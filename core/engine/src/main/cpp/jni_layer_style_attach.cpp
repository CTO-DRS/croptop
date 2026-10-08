// Attaches layer styles to a layer store (NativeLayerStylesBridge.nativeAttach); see layer_style_attachment.h.
#include <jni.h>

#include <memory>
#include <span>
#include <string>
#include <vector>

#include "layer_style_attachment.h"
#include "layer_styles_codec.h"
#include "native_store_handle.h"
#include "psd_effects_writer.h"

/** `settings` null detaches. Returns an error message, or null on success. */
extern "C" JNIEXPORT jstring JNICALL Java_app_croptop_core_engine_NativeLayerStylesBridge_nativeAttach(JNIEnv* env, jobject, jlong store_handle,
                                                                                                    jfloatArray settings, jlong pattern_handle) {
    auto* store = reinterpret_cast<croptop::jni::NativeTileStore*>(store_handle);
    if (store == nullptr) return env->NewStringUTF("Invalid layer store");
    if (settings == nullptr) {
        store->styles = nullptr;
        return nullptr;
    }
    try {
        const auto length = env->GetArrayLength(settings);
        std::vector<float> values(static_cast<std::size_t>(length));
        if (length > 0) env->GetFloatArrayRegion(settings, 0, length, values.data());
        if (env->ExceptionCheck()) return nullptr;
        std::string error;
        const auto* pattern = reinterpret_cast<const croptop::jni::NativeTileStore*>(pattern_handle);
        auto decoded = croptop::styles::decodeStyles(std::span<const float>(values), pattern != nullptr ? &pattern->pixels : nullptr, error);
        if (!decoded) return env->NewStringUTF(error.empty() ? "Invalid layer style settings" : error.c_str());
        std::optional<croptop::TileStore> owned;
        if (pattern != nullptr) owned.emplace(pattern->pixels);
        store->styles = std::make_shared<croptop::jni::StyleAttachment>(std::move(*decoded), std::move(owned));
        return nullptr;
    } catch (const std::exception& e) {
        return env->NewStringUTF(e.what());
    }
}

extern "C" JNIEXPORT jboolean JNICALL Java_app_croptop_core_engine_NativeLayerStylesBridge_nativeHasStyles(JNIEnv*, jobject, jlong store_handle) {
    const auto* store = reinterpret_cast<const croptop::jni::NativeTileStore*>(store_handle);
    return store != nullptr && store->styles != nullptr ? JNI_TRUE : JNI_FALSE;
}

/** The Photoshop `lfx2` block for codec-layout [settings] (pattern overlay omitted), or null. */
extern "C" JNIEXPORT jbyteArray JNICALL Java_app_croptop_core_engine_NativeLayerStylesBridge_nativeEncodePsdEffects(JNIEnv* env, jobject,
                                                                                                                jfloatArray settings) {
    if (settings == nullptr) return nullptr;
    try {
        const auto length = env->GetArrayLength(settings);
        std::vector<float> values(static_cast<std::size_t>(length));
        if (length > 0) env->GetFloatArrayRegion(settings, 0, length, values.data());
        if (env->ExceptionCheck()) return nullptr;
        std::string error;
        auto decoded = croptop::styles::decodeStyles(std::span<const float>(values), nullptr, error);
        if (!decoded) return nullptr;
        const auto bytes = croptop::psd::encodeEffects(*decoded);
        auto out = env->NewByteArray(static_cast<jsize>(bytes.size()));
        if (out != nullptr && !bytes.empty()) env->SetByteArrayRegion(out, 0, static_cast<jsize>(bytes.size()), reinterpret_cast<const jbyte*>(bytes.data()));
        return out;
    } catch (const std::exception&) {
        return nullptr;
    }
}
