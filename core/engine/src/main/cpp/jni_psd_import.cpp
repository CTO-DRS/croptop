// Layered PSD/PSB opening over psd::importLayers. nativeImportPsd parses the file into a holder;
// nativeImportPsdJson describes the layer tree (stores referenced by index); nativeImportPsdStores hands out one
// NativeTileStore handle per referenced store (caller-owned); nativeImportPsdDestroy frees the holder.
#include <jni.h>

#include <limits>
#include <memory>
#include <stdexcept>
#include <string>
#include <vector>

#include "native_store_handle.h"
#include "psd_import.h"

namespace {

thread_local std::string g_psd_error;

struct Holder final {
    croptop::psd::ImportedDocument document;
    std::string json;
    std::vector<const croptop::TileStore*> stores;
};

}  // namespace

extern "C" JNIEXPORT jstring JNICALL Java_app_croptop_core_engine_NativeEngine_nativeImportPsdLastError(JNIEnv* env, jobject) {
    return env->NewStringUTF(g_psd_error.c_str());
}

extern "C" JNIEXPORT jlong JNICALL Java_app_croptop_core_engine_NativeEngine_nativeImportPsd(JNIEnv* env, jobject, jbyteArray encoded, jint size,
                                                                                              jlong template_handle) {
    g_psd_error.clear();
    try {
        const auto* templ = reinterpret_cast<const croptop::jni::NativeTileStore*>(template_handle);
        if (encoded == nullptr || templ == nullptr || size <= 0 || size > env->GetArrayLength(encoded)) throw std::invalid_argument("invalid PSD import arguments");
        std::vector<std::uint8_t> bytes(static_cast<std::size_t>(size));
        env->GetByteArrayRegion(encoded, 0, size, reinterpret_cast<jbyte*>(bytes.data()));
        // Masks are created like an A16 store of the document (same tile pool and budget as the layers).
        const auto mask_template = templ->pixels.emptyLike(croptop::PixelFormat::A16);
        auto document = croptop::psd::importLayers(bytes, templ->pixels, &mask_template, g_psd_error);
        if (!document) return 0;
        auto holder = std::make_unique<Holder>();
        holder->document = std::move(*document);
        holder->json = croptop::psd::toJson(holder->document, holder->stores);
        return reinterpret_cast<jlong>(holder.release());
    } catch (const std::exception& e) {
        g_psd_error = e.what();
    }
    return 0;
}

extern "C" JNIEXPORT jstring JNICALL Java_app_croptop_core_engine_NativeEngine_nativeImportPsdJson(JNIEnv* env, jobject, jlong holder) {
    const auto* h = reinterpret_cast<const Holder*>(holder);
    return h == nullptr ? nullptr : env->NewStringUTF(h->json.c_str());
}

extern "C" JNIEXPORT jlongArray JNICALL Java_app_croptop_core_engine_NativeEngine_nativeImportPsdStores(JNIEnv* env, jobject, jlong holder) {
    const auto* h = reinterpret_cast<const Holder*>(holder);
    if (h == nullptr) return nullptr;
    std::vector<jlong> handles;
    handles.reserve(h->stores.size());
    try {
        for (const auto* store : h->stores) handles.push_back(reinterpret_cast<jlong>(new croptop::jni::NativeTileStore(*store)));
    } catch (const std::exception& e) {
        for (const auto handle : handles) delete reinterpret_cast<croptop::jni::NativeTileStore*>(handle);
        g_psd_error = e.what();
        return nullptr;
    }
    auto out = env->NewLongArray(static_cast<jsize>(handles.size()));
    if (out != nullptr && !handles.empty()) env->SetLongArrayRegion(out, 0, static_cast<jsize>(handles.size()), handles.data());
    return out;
}

extern "C" JNIEXPORT void JNICALL Java_app_croptop_core_engine_NativeEngine_nativeImportPsdDestroy(JNIEnv*, jobject, jlong holder) {
    delete reinterpret_cast<Holder*>(holder);
}

// Exposes the profile retained by the layered import holder without extending holder lifetime.
// The Kotlin bridge treats an empty array as an untagged PSD and null as an error.
extern "C" JNIEXPORT jbyteArray JNICALL Java_app_croptop_core_engine_NativeCodecExtraBridge_nativePsdIccProfile(JNIEnv* env, jobject, jlong holder) {
    g_psd_error.clear();
    const auto* h = reinterpret_cast<const Holder*>(holder);
    if (h == nullptr) {
        g_psd_error = "PSD import holder is null";
        return nullptr;
    }
    const auto& profile = h->document.icc_profile;
    constexpr std::size_t kMaxIccProfileBytes = 16U << 20U;
    if (profile.size() > kMaxIccProfileBytes || profile.size() > static_cast<std::size_t>(std::numeric_limits<jsize>::max())) {
        g_psd_error = "PSD ICC profile exceeds the 16 MiB limit";
        return nullptr;
    }
    auto result = env->NewByteArray(static_cast<jsize>(profile.size()));
    if (result == nullptr) {
        if (!env->ExceptionCheck()) g_psd_error = "Could not allocate PSD ICC profile result";
        return nullptr;
    }
    if (!profile.empty()) {
        env->SetByteArrayRegion(result, 0, static_cast<jsize>(profile.size()), reinterpret_cast<const jbyte*>(profile.data()));
        if (env->ExceptionCheck()) return nullptr;
    }
    return result;
}

extern "C" JNIEXPORT jstring JNICALL Java_app_croptop_core_engine_NativeCodecExtraBridge_nativePsdIccLastError(JNIEnv* env, jobject) {
    return env->NewStringUTF(g_psd_error.c_str());
}
