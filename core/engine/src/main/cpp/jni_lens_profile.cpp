// JNI surface for lens-profile correction (class app.croptop.core.engine.NativeLensProfileBridge): load lensfun XML into a database
// session, match a camera and lens by name, and correct distortion, lateral chromatic aberration and vignetting for one shot.
#if defined(__ANDROID__)
#include <jni.h>

#include <algorithm>
#include <memory>
#include <stdexcept>
#include <string>
#include <vector>

#include "lens_profile.h"
#include "native_store_handle.h"

namespace {

using Store = croptop::jni::NativeTileStore;
using Database = croptop::lens::Database;
thread_local std::string g_lens_error;

Database& dbOf(jlong handle) {
    auto* d = reinterpret_cast<Database*>(handle);
    if (d == nullptr) throw std::invalid_argument("lens database handle is null");
    return *d;
}

std::string text(JNIEnv* env, jstring s) {
    if (s == nullptr) return {};
    const char* chars = env->GetStringUTFChars(s, nullptr);
    if (chars == nullptr) throw std::runtime_error("could not read a string argument");
    std::string out(chars);
    env->ReleaseStringUTFChars(s, chars);
    return out;
}

template <typename Fn>
jlong guarded(Fn&& fn) {
    g_lens_error.clear();
    try {
        return fn();
    } catch (const std::exception& e) {
        g_lens_error = e.what();
    } catch (...) {
        g_lens_error = "unknown lens profile failure";
    }
    return 0;
}

}  // namespace

extern "C" JNIEXPORT jstring JNICALL Java_app_croptop_core_engine_NativeLensProfileBridge_nativeLastError(JNIEnv* env, jobject) {
    return env->NewStringUTF(g_lens_error.c_str());
}

extern "C" JNIEXPORT jlong JNICALL Java_app_croptop_core_engine_NativeLensProfileBridge_nativeCreate(JNIEnv*, jobject) { return reinterpret_cast<jlong>(new Database()); }

extern "C" JNIEXPORT void JNICALL Java_app_croptop_core_engine_NativeLensProfileBridge_nativeDestroy(JNIEnv*, jobject, jlong db) { delete reinterpret_cast<Database*>(db); }

/** Adds one lensfun XML file; returns 1, or 0 with nativeLastError. Call once per file of the database. */
extern "C" JNIEXPORT jlong JNICALL Java_app_croptop_core_engine_NativeLensProfileBridge_nativeAddXml(JNIEnv* env, jobject, jlong db, jbyteArray xml) {
    return guarded([&]() -> jlong {
        if (xml == nullptr) throw std::invalid_argument("XML is null");
        const jsize n = env->GetArrayLength(xml);
        if (n <= 0 || n > (32 << 20)) throw std::invalid_argument("lensfun XML has an invalid size");
        std::string data(static_cast<std::size_t>(n), '\0');
        env->GetByteArrayRegion(xml, 0, n, reinterpret_cast<jbyte*>(data.data()));
        std::string error;
        if (!dbOf(db).addXml(data, error)) throw std::invalid_argument(error.empty() ? "could not read the lensfun XML" : error);
        return 1;
    });
}

extern "C" JNIEXPORT jint JNICALL Java_app_croptop_core_engine_NativeLensProfileBridge_nativeLensCount(JNIEnv*, jobject, jlong db) {
    try {
        return static_cast<jint>(dbOf(db).lenses.size());
    } catch (...) {
        return 0;
    }
}

/** Index of the best lens match, or -1. */
extern "C" JNIEXPORT jint JNICALL Java_app_croptop_core_engine_NativeLensProfileBridge_nativeFindLens(JNIEnv* env, jobject, jlong db, jstring maker, jstring model) {
    try {
        auto& d = dbOf(db);
        const auto* lens = d.findLens(text(env, maker), text(env, model));
        return lens == nullptr ? -1 : static_cast<jint>(lens - d.lenses.data());
    } catch (...) {
        return -1;
    }
}

/** Crop factor of the matching camera body, or 0 when it is not in the database. */
extern "C" JNIEXPORT jfloat JNICALL Java_app_croptop_core_engine_NativeLensProfileBridge_nativeCameraCrop(JNIEnv* env, jobject, jlong db, jstring maker, jstring model) {
    try {
        const auto* camera = dbOf(db).findCamera(text(env, maker), text(env, model));
        return camera == nullptr ? 0.0F : camera->crop_factor;
    } catch (...) {
        return 0.0F;
    }
}

/** "maker|model|mount|cropFactor|distortionEntries|tcaEntries|vignettingEntries" for a lens index, or "" when out of range. */
extern "C" JNIEXPORT jstring JNICALL Java_app_croptop_core_engine_NativeLensProfileBridge_nativeLensInfo(JNIEnv* env, jobject, jlong db, jint index) {
    try {
        auto& d = dbOf(db);
        if (index < 0 || static_cast<std::size_t>(index) >= d.lenses.size()) return env->NewStringUTF("");
        const auto& l = d.lenses[static_cast<std::size_t>(index)];
        const std::string info = l.maker + "|" + l.model + "|" + l.mount + "|" + std::to_string(l.crop_factor) + "|" + std::to_string(l.distortion.size()) + "|" +
                                 std::to_string(l.tca.size()) + "|" + std::to_string(l.vignetting.size());
        return env->NewStringUTF(info.c_str());
    } catch (...) {
        return env->NewStringUTF("");
    }
}

/** Corrects a clone of the layer for lens `lens_index` at the given focal length (mm) and aperture; `flags` bit 0 distortion, 1 TCA, 2 vignetting. */
extern "C" JNIEXPORT jlong JNICALL Java_app_croptop_core_engine_NativeLensProfileBridge_nativeApply(JNIEnv*, jobject, jlong db, jint lens_index, jlong store_handle, jfloat focal_mm, jfloat aperture,
                                                                                                   jfloat image_crop_factor, jint flags, jint worker_threads) {
    return guarded([&]() -> jlong {
        auto& d = dbOf(db);
        if (lens_index < 0 || static_cast<std::size_t>(lens_index) >= d.lenses.size()) throw std::invalid_argument("no such lens");
        const auto* layer = reinterpret_cast<const Store*>(store_handle);
        if (layer == nullptr) throw std::invalid_argument("layer store handle is null");
        if (!(focal_mm > 0.0F) || !(aperture > 0.0F) || !(image_crop_factor > 0.0F)) throw std::invalid_argument("focal length, aperture and crop factor must be positive");
        const auto correction = croptop::lens::interpolate(d.lenses[static_cast<std::size_t>(lens_index)], focal_mm, aperture);
        croptop::lens::ApplyOptions options;
        options.image_crop_factor = image_crop_factor;
        options.distortion = (flags & 1) != 0;
        options.tca = (flags & 2) != 0;
        options.vignetting = (flags & 4) != 0;
        options.worker_threads = static_cast<unsigned>(std::max(0, worker_threads));
        std::string error;
        auto out = croptop::lens::apply(layer->pixels, correction, options, error);
        if (!out) throw std::invalid_argument(error);
        return reinterpret_cast<jlong>(new Store(std::move(*out)));
    });
}
#endif  // __ANDROID__
