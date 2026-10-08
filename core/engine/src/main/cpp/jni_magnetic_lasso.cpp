// JNI for the Magnetic Lasso (class app.croptop.core.engine.NativeMagneticLassoBridge). The session copies the
// image (COW) at begin; calls on one session must be serialised by the caller. Failures report through lastError().
#include <jni.h>

#include <memory>
#include <stdexcept>
#include <string>
#include <vector>

#include "native_store_handle.h"
#include "selection_magnetic.h"

namespace {

using croptop::selection::MagneticLasso;
thread_local std::string g_magnetic_error;

MagneticLasso& lasso(jlong handle) {
    auto* l = reinterpret_cast<MagneticLasso*>(handle);
    if (l == nullptr) throw std::invalid_argument("magnetic lasso session is null");
    return *l;
}

jfloatArray points(JNIEnv* env, const std::vector<croptop::selection::Point>& pts) {
    std::vector<jfloat> xy;
    xy.reserve(pts.size() * 2);
    for (const auto& p : pts) {
        xy.push_back(static_cast<jfloat>(p.x));
        xy.push_back(static_cast<jfloat>(p.y));
    }
    auto out = env->NewFloatArray(static_cast<jsize>(xy.size()));
    if (out != nullptr && !xy.empty()) env->SetFloatArrayRegion(out, 0, static_cast<jsize>(xy.size()), xy.data());
    return out;
}

}  // namespace

extern "C" JNIEXPORT jstring JNICALL Java_app_croptop_core_engine_NativeMagneticLassoBridge_lastError(JNIEnv* env, jobject) {
    return env->NewStringUTF(g_magnetic_error.c_str());
}

extern "C" JNIEXPORT jlong JNICALL Java_app_croptop_core_engine_NativeMagneticLassoBridge_begin(JNIEnv*, jobject, jlong store_handle, jfloat x, jfloat y,
                                                                                               jfloat width, jfloat contrast, jfloat frequency) {
    g_magnetic_error.clear();
    try {
        const auto* store = reinterpret_cast<const croptop::jni::NativeTileStore*>(store_handle);
        if (store == nullptr) throw std::invalid_argument("layer storage is unavailable");
        croptop::selection::MagneticSpec spec;
        spec.width = width;
        spec.contrast = contrast;
        spec.frequency = frequency;
        auto session = MagneticLasso::begin(store->pixels, {x, y}, spec, g_magnetic_error);
        return session ? reinterpret_cast<jlong>(session.release()) : 0;
    } catch (const std::exception& e) {
        g_magnetic_error = e.what();
    }
    return 0;
}

/** The live wire from the last fastening point to the snapped cursor, as x,y pairs. */
extern "C" JNIEXPORT jfloatArray JNICALL Java_app_croptop_core_engine_NativeMagneticLassoBridge_update(JNIEnv* env, jobject, jlong handle, jfloat x, jfloat y) {
    g_magnetic_error.clear();
    try {
        return points(env, lasso(handle).update({x, y}));
    } catch (const std::exception& e) {
        g_magnetic_error = e.what();
    }
    return nullptr;
}

extern "C" JNIEXPORT jboolean JNICALL Java_app_croptop_core_engine_NativeMagneticLassoBridge_fasten(JNIEnv*, jobject, jlong handle, jfloat x, jfloat y) {
    g_magnetic_error.clear();
    try {
        lasso(handle).fasten({x, y});
        return JNI_TRUE;
    } catch (const std::exception& e) {
        g_magnetic_error = e.what();
    }
    return JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL Java_app_croptop_core_engine_NativeMagneticLassoBridge_undoFasten(JNIEnv*, jobject, jlong handle) {
    try {
        return lasso(handle).undoFasten() ? JNI_TRUE : JNI_FALSE;
    } catch (const std::exception& e) {
        g_magnetic_error = e.what();
    }
    return JNI_FALSE;
}

/** The committed outline (seed → last fastening point) as x,y pairs. */
extern "C" JNIEXPORT jfloatArray JNICALL Java_app_croptop_core_engine_NativeMagneticLassoBridge_committed(JNIEnv* env, jobject, jlong handle) {
    try {
        return points(env, lasso(handle).committed());
    } catch (const std::exception& e) {
        g_magnetic_error = e.what();
    }
    return nullptr;
}

/** Closes the outline back to the seed and returns a new A16 mask store handle (0 on failure). */
extern "C" JNIEXPORT jlong JNICALL Java_app_croptop_core_engine_NativeMagneticLassoBridge_close(JNIEnv*, jobject, jlong handle, jboolean antialias) {
    g_magnetic_error.clear();
    try {
        auto mask = lasso(handle).close(antialias == JNI_TRUE, g_magnetic_error);
        return mask ? reinterpret_cast<jlong>(new croptop::jni::NativeTileStore(std::move(*mask))) : 0;
    } catch (const std::exception& e) {
        g_magnetic_error = e.what();
    }
    return 0;
}

extern "C" JNIEXPORT void JNICALL Java_app_croptop_core_engine_NativeMagneticLassoBridge_destroy(JNIEnv*, jobject, jlong handle) {
    delete reinterpret_cast<MagneticLasso*>(handle);
}
