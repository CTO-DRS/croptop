#include "shape_render_adapter.h"
#include "jni_shape_layer.h"
#include "native_store_handle.h"
#include "scratch_store.h"

#include <cstdint>
#include <limits>
#include <memory>
#include <stdexcept>
#include <string>
#include <vector>

#if defined(__ANDROID__)
#include <jni.h>

namespace {
thread_local std::string g_shape_render_error;

std::vector<std::uint8_t> readBatch(JNIEnv* env, jbyteArray encoded) {
    if (encoded == nullptr) throw std::invalid_argument("shape batch byte array is null");
    const jsize size = env->GetArrayLength(encoded);
    if (size < 0 || static_cast<std::size_t>(size) > croptop::shape::bridge::kMaxBatchBytes) {
        throw std::invalid_argument("shape batch exceeds the 32 MiB limit");
    }
    std::vector<std::uint8_t> bytes(static_cast<std::size_t>(size));
    if (size > 0) env->GetByteArrayRegion(encoded, 0, size, reinterpret_cast<jbyte*>(bytes.data()));
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        throw std::runtime_error("could not read shape batch bytes");
    }
    return bytes;
}

template <typename Fn>
jlong guarded(Fn&& fn) {
    g_shape_render_error.clear();
    try {
        return fn();
    } catch (const std::exception& exception) {
        g_shape_render_error = exception.what();
    } catch (...) {
        g_shape_render_error = "unknown shape-layer rendering failure";
    }
    return 0;
}
}  // namespace

extern "C" JNIEXPORT jstring JNICALL Java_app_croptop_core_engine_NativeShapeRendererBridge_nativeLastError(JNIEnv* env, jobject) {
    return env->NewStringUTF(g_shape_render_error.c_str());
}

extern "C" JNIEXPORT jlong JNICALL Java_app_croptop_core_engine_NativeShapeRendererBridge_nativeRenderBatch(
    JNIEnv* env, jobject, jbyteArray encoded_batch, jint width, jint height, jlong budget_handle) {
    return guarded([&] {
        if (width <= 0 || height <= 0) throw std::invalid_argument("shape render dimensions must be positive");
        auto* budget = reinterpret_cast<croptop::jni::NativeMemoryBudget*>(budget_handle);
        if (budget == nullptr) throw std::invalid_argument("native memory budget is unavailable");
        auto bytes = readBatch(env, encoded_batch);
        auto store = std::make_unique<croptop::jni::NativeTileStore>(croptop::PixelFormat::RGBA8,
            static_cast<std::uint32_t>(width), static_cast<std::uint32_t>(height), budget->tile_pool, budget->manager);
        std::string error;
        if (!croptop::shape::bridge::renderBatchToTileStore(bytes, store->pixels, error)) {
            throw std::invalid_argument(error);
        }
        return reinterpret_cast<jlong>(store.release());
    });
}

#endif  // defined(__ANDROID__)
