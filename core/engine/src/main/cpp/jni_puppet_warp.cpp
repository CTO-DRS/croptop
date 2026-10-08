// JNI surface for Puppet Warp sessions (class app.croptop.core.engine.NativeImageOpsBridge is left alone; this one is
// app.croptop.core.engine.NativePuppetWarpBridge): build a mesh over a layer, add and drag pins, solve, read the deformed vertices for a live
// preview and render the final result.
#if defined(__ANDROID__)
#include <jni.h>

#include <algorithm>
#include <memory>
#include <stdexcept>
#include <string>
#include <vector>

#include "native_store_handle.h"
#include "puppet_warp.h"

namespace {

using Store = croptop::jni::NativeTileStore;
using Mesh = croptop::puppet::PuppetMesh;
thread_local std::string g_puppet_error;

Mesh& meshOf(jlong handle) {
    auto* m = reinterpret_cast<Mesh*>(handle);
    if (m == nullptr) throw std::invalid_argument("puppet warp session handle is null");
    return *m;
}

template <typename Fn>
jlong guarded(Fn&& fn) {
    g_puppet_error.clear();
    try {
        return fn();
    } catch (const std::exception& e) {
        g_puppet_error = e.what();
    } catch (...) {
        g_puppet_error = "unknown puppet warp failure";
    }
    return 0;
}

}  // namespace

extern "C" JNIEXPORT jstring JNICALL Java_app_croptop_core_engine_NativePuppetWarpBridge_nativeLastError(JNIEnv* env, jobject) {
    return env->NewStringUTF(g_puppet_error.c_str());
}

/** density: 0 fewer, 1 normal, 2 more. Returns the session handle or 0. The mesh keeps its own copy of the layer. */
extern "C" JNIEXPORT jlong JNICALL Java_app_croptop_core_engine_NativePuppetWarpBridge_nativeBuild(JNIEnv*, jobject, jlong layer_handle, jint density, jfloat expansion) {
    return guarded([&]() -> jlong {
        const auto* layer = reinterpret_cast<const Store*>(layer_handle);
        if (layer == nullptr) throw std::invalid_argument("layer store handle is null");
        if (density < 0 || density > 2 || !(expansion >= -32.0F && expansion <= 64.0F)) throw std::invalid_argument("invalid mesh settings");
        croptop::puppet::MeshSpec spec;
        spec.density = static_cast<croptop::puppet::Density>(density);
        spec.expansion = expansion;
        std::string error;
        auto mesh = Mesh::build(layer->pixels, spec, error);
        if (!mesh) throw std::invalid_argument(error);
        return reinterpret_cast<jlong>(mesh.release());
    });
}

extern "C" JNIEXPORT void JNICALL Java_app_croptop_core_engine_NativePuppetWarpBridge_nativeDestroy(JNIEnv*, jobject, jlong mesh) { delete reinterpret_cast<Mesh*>(mesh); }

/** Adds a pin at (x, y) in layer pixels; returns its index, or -1 when it is off the mesh. */
extern "C" JNIEXPORT jint JNICALL Java_app_croptop_core_engine_NativePuppetWarpBridge_nativeAddPin(JNIEnv*, jobject, jlong mesh, jdouble x, jdouble y) {
    try {
        return meshOf(mesh).addPin({x, y});
    } catch (...) {
        return -1;
    }
}

extern "C" JNIEXPORT jboolean JNICALL Java_app_croptop_core_engine_NativePuppetWarpBridge_nativeRemovePin(JNIEnv*, jobject, jlong mesh, jint index) {
    try {
        return meshOf(mesh).removePin(index) ? JNI_TRUE : JNI_FALSE;
    } catch (...) {
        return JNI_FALSE;
    }
}

extern "C" JNIEXPORT jint JNICALL Java_app_croptop_core_engine_NativePuppetWarpBridge_nativePinCount(JNIEnv*, jobject, jlong mesh) {
    try {
        return static_cast<jint>(meshOf(mesh).pinCount());
    } catch (...) {
        return 0;
    }
}

/** Moves pin `index` to (x, y) and gives it an overlap depth. Returns 1 or 0 (out-of-range index). */
extern "C" JNIEXPORT jlong JNICALL Java_app_croptop_core_engine_NativePuppetWarpBridge_nativeMovePin(JNIEnv*, jobject, jlong mesh, jint index, jdouble x, jdouble y, jint depth) {
    return guarded([&]() -> jlong {
        auto& m = meshOf(mesh);
        if (index < 0 || static_cast<std::size_t>(index) >= m.pinCount()) throw std::invalid_argument("no such pin");
        auto& pin = m.pin(index);
        pin.target = {x, y};
        pin.depth = depth;
        return 1;
    });
}

/** mode: 0 rigid, 1 normal, 2 distort. Returns the rounds run, or -1. */
extern "C" JNIEXPORT jint JNICALL Java_app_croptop_core_engine_NativePuppetWarpBridge_nativeSolve(JNIEnv*, jobject, jlong mesh, jint mode, jint max_iterations, jdouble tolerance) {
    g_puppet_error.clear();
    try {
        if (mode < 0 || mode > 2 || max_iterations < 1 || max_iterations > 1000 || !(tolerance > 0.0)) throw std::invalid_argument("invalid solve settings");
        return meshOf(mesh).solve(static_cast<croptop::puppet::Mode>(mode), max_iterations, tolerance);
    } catch (const std::exception& e) {
        g_puppet_error = e.what();
    } catch (...) {
        g_puppet_error = "unknown puppet warp failure";
    }
    return -1;
}

/** [x0,y0, x1,y1, ...] of the deformed vertices, then the triangle indices are read with nativeTriangles. */
extern "C" JNIEXPORT jfloatArray JNICALL Java_app_croptop_core_engine_NativePuppetWarpBridge_nativeDeformedVertices(JNIEnv* env, jobject, jlong mesh) {
    try {
        const auto& v = meshOf(mesh).deformedVertices();
        std::vector<jfloat> out;
        out.reserve(v.size() * 2U);
        for (const auto& p : v) {
            out.push_back(static_cast<jfloat>(p.x));
            out.push_back(static_cast<jfloat>(p.y));
        }
        auto array = env->NewFloatArray(static_cast<jsize>(out.size()));
        if (array != nullptr && !out.empty()) env->SetFloatArrayRegion(array, 0, static_cast<jsize>(out.size()), out.data());
        return array;
    } catch (...) {
        return nullptr;
    }
}

extern "C" JNIEXPORT jfloatArray JNICALL Java_app_croptop_core_engine_NativePuppetWarpBridge_nativeRestVertices(JNIEnv* env, jobject, jlong mesh) {
    try {
        const auto& v = meshOf(mesh).restVertices();
        std::vector<jfloat> out;
        out.reserve(v.size() * 2U);
        for (const auto& p : v) {
            out.push_back(static_cast<jfloat>(p.x));
            out.push_back(static_cast<jfloat>(p.y));
        }
        auto array = env->NewFloatArray(static_cast<jsize>(out.size()));
        if (array != nullptr && !out.empty()) env->SetFloatArrayRegion(array, 0, static_cast<jsize>(out.size()), out.data());
        return array;
    } catch (...) {
        return nullptr;
    }
}

/** Triangle vertex indices, three per triangle. */
extern "C" JNIEXPORT jintArray JNICALL Java_app_croptop_core_engine_NativePuppetWarpBridge_nativeTriangles(JNIEnv* env, jobject, jlong mesh) {
    try {
        const auto& t = meshOf(mesh).triangles();
        std::vector<jint> out;
        out.reserve(t.size() * 3U);
        for (const auto& tri : t)
            for (const auto i : tri) out.push_back(static_cast<jint>(i));
        auto array = env->NewIntArray(static_cast<jsize>(out.size()));
        if (array != nullptr && !out.empty()) env->SetIntArrayRegion(array, 0, static_cast<jsize>(out.size()), out.data());
        return array;
    } catch (...) {
        return nullptr;
    }
}

extern "C" JNIEXPORT jlong JNICALL Java_app_croptop_core_engine_NativePuppetWarpBridge_nativeRender(JNIEnv*, jobject, jlong mesh, jint worker_threads) {
    return guarded([&]() -> jlong {
        std::string error;
        auto out = meshOf(mesh).render(static_cast<unsigned>(std::max(0, worker_threads)), error);
        if (!out) throw std::invalid_argument(error);
        return reinterpret_cast<jlong>(new Store(std::move(*out)));
    });
}
#endif  // __ANDROID__
