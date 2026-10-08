// Handoff helper. Creates selection outputs with the document TileStore allocator.
#include <jni.h>

#include <cstdint>
#include <exception>
#include <optional>
#include <stdexcept>
#include <string>
#include <vector>

#include "native_store_handle.h"
#include "selection_mask.h"
#include "selection_modify.h"

namespace {

using croptop::TileStore;
using croptop::jni::NativeTileStore;

constexpr jsize kMaxPolygonPoints = 1'000'000;

thread_local std::string g_last_error;

template <typename Function>
jlong invoke(Function&& function) {
    g_last_error.clear();
    try {
        return function();
    } catch (const std::exception& exception) {
        g_last_error = exception.what();
    } catch (...) {
        g_last_error = "unknown native selection allocation error";
    }
    return 0;
}

TileStore& requireStore(jlong handle) {
    auto* store = reinterpret_cast<NativeTileStore*>(handle);
    if (store == nullptr) throw std::invalid_argument("document pixel store handle is null");
    return store->pixels;
}

std::vector<croptop::selection::Point> readPoints(JNIEnv* env, jdoubleArray packed) {
    if (packed == nullptr) throw std::invalid_argument("polygon coordinates are null");
    const auto length = env->GetArrayLength(packed);
    if ((length & 1) != 0) throw std::invalid_argument("polygon coordinates must be x,y pairs");
    if (length / 2 > kMaxPolygonPoints) throw std::invalid_argument("polygon exceeds the 1000000 point limit");
    std::vector<jdouble> values(static_cast<std::size_t>(length));
    if (length > 0) env->GetDoubleArrayRegion(packed, 0, length, values.data());
    if (env->ExceptionCheck()) return {};
    std::vector<croptop::selection::Point> points;
    points.reserve(values.size() / 2U);
    for (std::size_t index = 0; index < values.size(); index += 2U) {
        points.push_back({values[index], values[index + 1U]});
    }
    return points;
}

jlong makeStore(TileStore pixels) {
    return reinterpret_cast<jlong>(new NativeTileStore(std::move(pixels)));
}

}  // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_app_croptop_core_engine_NativeSelectionBudgetBridge_nativeLastError(JNIEnv* env, jobject) {
    return env->NewStringUTF(g_last_error.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_app_croptop_core_engine_NativeSelectionBudgetBridge_destroyStore(JNIEnv*, jobject, jlong handle) {
    g_last_error.clear();
    delete reinterpret_cast<NativeTileStore*>(handle);
}

extern "C" JNIEXPORT jlong JNICALL
Java_app_croptop_core_engine_NativeSelectionBudgetBridge_emptyA16MaskLike(JNIEnv*, jobject, jlong allocation_handle) {
    return invoke([&] { return makeStore(croptop::selection::emptyMask(requireStore(allocation_handle))); });
}

extern "C" JNIEXPORT jlong JNICALL
Java_app_croptop_core_engine_NativeSelectionBudgetBridge_rasterizeRectLike(JNIEnv*, jobject, jlong allocation_handle,
                                                                           jdouble left, jdouble top, jdouble right,
                                                                           jdouble bottom, jboolean antialias) {
    return invoke([&] {
        return makeStore(croptop::selection::rasterizeRect(requireStore(allocation_handle), {left, top, right, bottom},
                                                            antialias == JNI_TRUE));
    });
}

extern "C" JNIEXPORT jlong JNICALL
Java_app_croptop_core_engine_NativeSelectionBudgetBridge_rasterizeEllipseLike(JNIEnv*, jobject, jlong allocation_handle,
                                                                              jdouble left, jdouble top, jdouble right,
                                                                              jdouble bottom, jboolean antialias) {
    return invoke([&] {
        return makeStore(croptop::selection::rasterizeEllipse(requireStore(allocation_handle), {left, top, right, bottom},
                                                               antialias == JNI_TRUE));
    });
}

extern "C" JNIEXPORT jlong JNICALL
Java_app_croptop_core_engine_NativeSelectionBudgetBridge_rasterizePolygonLike(JNIEnv* env, jobject, jlong allocation_handle,
                                                                             jdoubleArray xy, jboolean antialias) {
    return invoke([&] {
        const auto points = readPoints(env, xy);
        if (env->ExceptionCheck()) return static_cast<jlong>(0);
        return makeStore(croptop::selection::rasterizePolygon(requireStore(allocation_handle), points,
                                                               antialias == JNI_TRUE));
    });
}

extern "C" JNIEXPORT jlong JNICALL
Java_app_croptop_core_engine_NativeSelectionBudgetBridge_combineLike(JNIEnv*, jobject, jlong allocation_handle,
                                                                      jlong selection_handle, jlong shape_handle,
                                                                      jint operation) {
    return invoke([&] {
        if (operation < 0 || operation > static_cast<jint>(croptop::selection::Op::Intersect))
            throw std::invalid_argument("selection operation is invalid");
        std::string error;
        auto result = croptop::selection::combine(requireStore(allocation_handle), requireStore(selection_handle),
            requireStore(shape_handle), static_cast<croptop::selection::Op>(operation), &error);
        if (!result) throw std::runtime_error(error.empty() ? "Could not combine selection masks" : error);
        return makeStore(std::move(*result));
    });
}

extern "C" JNIEXPORT jlong JNICALL
Java_app_croptop_core_engine_NativeSelectionBudgetBridge_invertLike(JNIEnv*, jobject, jlong allocation_handle,
                                                                     jlong selection_handle) {
    return invoke([&] {
        std::string error;
        auto result = croptop::selection::invert(requireStore(allocation_handle), requireStore(selection_handle), &error);
        if (!result) throw std::runtime_error(error.empty() ? "Could not invert selection mask" : error);
        return makeStore(std::move(*result));
    });
}

extern "C" JNIEXPORT jlong JNICALL
Java_app_croptop_core_engine_NativeSelectionBudgetBridge_modifyLike(JNIEnv*, jobject, jlong allocation_handle,
                                                                     jlong selection_handle, jint operation,
                                                                     jfloat radius) {
    return invoke([&] {
        std::string error;
        const auto& allocation = requireStore(allocation_handle);
        const auto& selection = requireStore(selection_handle);
        std::optional<TileStore> result;
        switch (operation) {
            case 0: result = croptop::selection::feather(allocation, selection, radius, error); break;
            case 1: result = croptop::selection::expand(allocation, selection, radius, error); break;
            case 2: result = croptop::selection::contract(allocation, selection, radius, error); break;
            case 3: result = croptop::selection::border(allocation, selection, radius, error); break;
            case 4: result = croptop::selection::smooth(allocation, selection, radius, error); break;
            default: throw std::invalid_argument("selection modify operation is invalid");
        }
        if (!result) throw std::runtime_error(error.empty() ? "Could not modify selection mask" : error);
        return makeStore(std::move(*result));
    });
}
