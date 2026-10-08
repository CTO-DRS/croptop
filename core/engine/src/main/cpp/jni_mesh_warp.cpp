// JNI surface for Mesh Warp (class app.croptop.core.engine.NativeMeshWarpBridge): warps a layer store through a
// control lattice and returns the result as a new store handle (destroy with NativeSelectionBridge.destroyStore).
#if defined(__ANDROID__)
#include <jni.h>

#include <algorithm>
#include <stdexcept>
#include <string>
#include <vector>

#include "mesh_warp.h"
#include "native_store_handle.h"

namespace {
thread_local std::string g_warp_error;
}

extern "C" JNIEXPORT jstring JNICALL Java_app_croptop_core_engine_NativeMeshWarpBridge_nativeLastError(JNIEnv* env, jobject) {
    return env->NewStringUTF(g_warp_error.c_str());
}

/**
 * `nodes` holds columns × rows destination points as x, y pairs, row-major (2 · columns · rows floats). Returns the warped
 * store handle, or 0 (see nativeLastError).
 */
extern "C" JNIEXPORT jlong JNICALL Java_app_croptop_core_engine_NativeMeshWarpBridge_nativeApply(JNIEnv* env, jobject, jlong store_handle, jint columns,
                                                                                                  jint rows, jfloatArray nodes, jint spacing,
                                                                                                  jint worker_threads) {
    g_warp_error.clear();
    try {
        const auto* store = reinterpret_cast<const croptop::jni::NativeTileStore*>(store_handle);
        if (store == nullptr) throw std::invalid_argument("layer store handle is null");
        if (columns < 2 || rows < 2 || columns > 64 || rows > 64) throw std::invalid_argument("the mesh needs 2-64 columns and rows");
        const auto expected = static_cast<jsize>(columns) * rows * 2;
        if (nodes == nullptr || env->GetArrayLength(nodes) != expected) throw std::invalid_argument("the node array has the wrong length");
        std::vector<jfloat> values(static_cast<std::size_t>(expected));
        env->GetFloatArrayRegion(nodes, 0, expected, values.data());
        croptop::warp::Mesh mesh;
        mesh.columns = static_cast<std::uint32_t>(columns);
        mesh.rows = static_cast<std::uint32_t>(rows);
        for (std::size_t i = 0; i < values.size(); i += 2) mesh.nodes.push_back({values[i], values[i + 1]});
        croptop::warp::Options options;
        options.spacing = static_cast<std::uint32_t>(std::clamp(spacing, 1, 64));
        options.worker_threads = static_cast<unsigned>(std::max(0, worker_threads));
        std::string error;
        auto warped = croptop::warp::apply(store->pixels, mesh, options, error);
        if (!warped) throw std::invalid_argument(error);
        return reinterpret_cast<jlong>(new croptop::jni::NativeTileStore(std::move(*warped)));
    } catch (const std::exception& e) {
        g_warp_error = e.what();
    } catch (...) {
        g_warp_error = "unknown mesh warp failure";
    }
    return 0;
}
#endif  // __ANDROID__
