// JNI for the model-free Object Selection / Select Subject (NativeSelectionBridge). Returns a new A16
// mask store handle, or 0 (see nativeObjectSelectLastError).
#include <jni.h>

#include <memory>
#include <string>

#include "native_store_handle.h"
#include "object_select.h"

namespace {
thread_local std::string g_object_error;
}

extern "C" JNIEXPORT jlong JNICALL Java_app_croptop_core_engine_NativeSelectionBridge_selectObject(JNIEnv*, jobject, jlong source_handle, jdouble left,
                                                                                                  jdouble top, jdouble right, jdouble bottom,
                                                                                                  jboolean subject) {
    g_object_error.clear();
    try {
        const auto* source = reinterpret_cast<const croptop::jni::NativeTileStore*>(source_handle);
        if (source == nullptr) {
            g_object_error = "layer storage is unavailable";
            return 0;
        }
        auto mask = subject == JNI_TRUE
            ? croptop::selection::selectSubject(source->pixels, source->pixels, g_object_error)
            : croptop::selection::selectObject(source->pixels, source->pixels, {left, top, right, bottom}, g_object_error);
        if (!mask) return 0;
        return reinterpret_cast<jlong>(new croptop::jni::NativeTileStore(std::move(*mask)));
    } catch (const std::exception& e) {
        g_object_error = e.what();
    }
    return 0;
}

extern "C" JNIEXPORT jstring JNICALL Java_app_croptop_core_engine_NativeSelectionBridge_objectSelectLastError(JNIEnv* env, jobject) {
    return env->NewStringUTF(g_object_error.c_str());
}
