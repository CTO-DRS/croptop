#include <jni.h>

#include <memory>
#include <string>

#include "layer_pixel_ops.h"
#include "native_store_handle.h"

namespace {
using croptop::jni::NativeTileStore;
thread_local std::string g_last_error;
}  // namespace

extern "C" JNIEXPORT jlongArray JNICALL
Java_app_croptop_core_engine_NativeEngine_nativeLayerViaCut(JNIEnv* env, jobject, jlong source_handle,
                                                             jlong selection_handle) {
    g_last_error.clear();
    const auto* source = reinterpret_cast<const NativeTileStore*>(source_handle);
    const auto* selection = reinterpret_cast<const NativeTileStore*>(selection_handle);
    if (source == nullptr) {
        g_last_error = "Source layer storage is unavailable";
        return nullptr;
    }
    std::unique_ptr<NativeTileStore> cut;
    std::unique_ptr<NativeTileStore> copy;
    try {
        cut = std::make_unique<NativeTileStore>(source->pixels);
        copy = std::make_unique<NativeTileStore>(source->pixels.emptyLike(source->pixels.format()));
        if (selection_handle != 0 && selection == nullptr) {
            g_last_error = "Selection storage is unavailable";
            return nullptr;
        }
        if (!croptop::layer_pixels::split(source->pixels, cut->pixels, copy->pixels,
                                          selection_handle == 0 ? nullptr : &selection->pixels, g_last_error)) {
            return nullptr;
        }
        const jlong handles[3] = {reinterpret_cast<jlong>(cut.get()), reinterpret_cast<jlong>(copy.get()),
                                  cut->pixels.tile_count() == 0U ? 1L : 0L};
        jlongArray result = env->NewLongArray(3);
        if (result == nullptr) {
            g_last_error = "Could not allocate Layer via Cut result";
            return nullptr;
        }
        env->SetLongArrayRegion(result, 0, 3, handles);
        if (env->ExceptionCheck()) {
            g_last_error = "Could not return Layer via Cut stores";
            return nullptr;
        }
        cut.release();
        copy.release();
        return result;
    } catch (const std::exception& exception) {
        g_last_error = exception.what();
        return nullptr;
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_app_croptop_core_engine_NativeEngine_nativeLayerViaCutLastError(JNIEnv* env, jobject) {
    return env->NewStringUTF(g_last_error.c_str());
}
