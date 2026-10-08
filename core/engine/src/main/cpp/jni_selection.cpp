// JNI bridge for native selection primitives; document/history wiring remains in Kotlin.
#include <jni.h>

#include <algorithm>
#include <array>
#include <cstdint>
#include <exception>
#include <limits>
#include <memory>
#include <stdexcept>
#include <string>
#include <utility>
#include <vector>

#include "native_store_handle.h"
#include "selection_color.h"
#include "selection_mask.h"
#include "selection_modify.h"
#include "selection_quick.h"
#include "selection_transform.h"

namespace {

using croptop::PixelFormat;
using croptop::TileStore;
using croptop::jni::NativeTileStore;
using croptop::selection::QuickSelectSpec;
using croptop::selection::QuickSelector;

thread_local std::string g_last_error;

struct NativeQuickSession final {
    explicit NativeQuickSession(std::unique_ptr<QuickSelector> value) : selector(std::move(value)) {}
    std::unique_ptr<QuickSelector> selector;
};

template <typename Result, typename Function>
Result invoke(Result failure, Function&& function) {
    g_last_error.clear();
    try {
        return function();
    } catch (const std::exception& exception) {
        g_last_error = exception.what();
    } catch (...) {
        g_last_error = "unknown native selection error";
    }
    return failure;
}

NativeTileStore& requireStore(jlong handle) {
    auto* store = reinterpret_cast<NativeTileStore*>(handle);
    if (store == nullptr) throw std::invalid_argument("tile store handle is null");
    return *store;
}

TileStore& requireMask(jlong handle) {
    auto& store = requireStore(handle).pixels;
    if (store.format() != PixelFormat::A16) throw std::invalid_argument("selection handle must contain an A16 mask");
    return store;
}

NativeQuickSession& requireSession(jlong handle) {
    auto* session = reinterpret_cast<NativeQuickSession*>(handle);
    if (session == nullptr || session->selector == nullptr) throw std::invalid_argument("Quick Selection session is invalid");
    return *session;
}

jlong makeStore(TileStore&& pixels) {
    return reinterpret_cast<jlong>(new NativeTileStore(std::move(pixels)));
}

std::vector<double> readDoubles(JNIEnv* env, jdoubleArray array) {
    if (array == nullptr) throw std::invalid_argument("double array is null");
    const auto length = env->GetArrayLength(array);
    std::vector<double> values(static_cast<std::size_t>(length));
    if (length > 0) env->GetDoubleArrayRegion(array, 0, length, values.data());
    return values;
}

std::vector<float> readFloats(JNIEnv* env, jfloatArray array) {
    if (array == nullptr) throw std::invalid_argument("float array is null");
    const auto length = env->GetArrayLength(array);
    std::vector<float> values(static_cast<std::size_t>(length));
    if (length > 0) env->GetFloatArrayRegion(array, 0, length, values.data());
    return values;
}

std::vector<jint> readInts(JNIEnv* env, jintArray array) {
    if (array == nullptr) throw std::invalid_argument("integer array is null");
    const auto length = env->GetArrayLength(array);
    std::vector<jint> values(static_cast<std::size_t>(length));
    if (length > 0) env->GetIntArrayRegion(array, 0, length, values.data());
    return values;
}

jintArray toIntArray(JNIEnv* env, const std::vector<jint>& values) {
    auto result = env->NewIntArray(static_cast<jsize>(values.size()));
    if (result != nullptr && !values.empty()) env->SetIntArrayRegion(result, 0, static_cast<jsize>(values.size()), values.data());
    return result;
}

}  // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_app_croptop_core_engine_NativeSelectionBridge_nativeLastError(JNIEnv* env, jobject) {
    return env->NewStringUTF(g_last_error.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_app_croptop_core_engine_NativeSelectionBridge_destroyStore(JNIEnv*, jobject, jlong store_handle) {
    g_last_error.clear();
    delete reinterpret_cast<NativeTileStore*>(store_handle);
}

extern "C" JNIEXPORT jlong JNICALL
Java_app_croptop_core_engine_NativeSelectionBridge_rasterizeRect(JNIEnv*, jobject, jint width, jint height, jdouble left,
                                                                  jdouble top, jdouble right, jdouble bottom,
                                                                  jboolean antialias) {
    return invoke<jlong>(0, [&] {
        if (width <= 0 || height <= 0) throw std::invalid_argument("selection dimensions must be positive");
        return makeStore(croptop::selection::rasterizeRect(static_cast<std::uint32_t>(width), static_cast<std::uint32_t>(height),
                                                            {left, top, right, bottom}, antialias == JNI_TRUE));
    });
}

extern "C" JNIEXPORT jlong JNICALL
Java_app_croptop_core_engine_NativeSelectionBridge_rasterizeEllipse(JNIEnv*, jobject, jint width, jint height, jdouble left,
                                                                     jdouble top, jdouble right, jdouble bottom,
                                                                     jboolean antialias) {
    return invoke<jlong>(0, [&] {
        if (width <= 0 || height <= 0) throw std::invalid_argument("selection dimensions must be positive");
        return makeStore(croptop::selection::rasterizeEllipse(static_cast<std::uint32_t>(width), static_cast<std::uint32_t>(height),
                                                               {left, top, right, bottom}, antialias == JNI_TRUE));
    });
}

extern "C" JNIEXPORT jlong JNICALL
Java_app_croptop_core_engine_NativeSelectionBridge_rasterizePolygon(JNIEnv* env, jobject, jint width, jint height,
                                                                     jdoubleArray xy, jboolean antialias) {
    return invoke<jlong>(0, [&] {
        if (width <= 0 || height <= 0) throw std::invalid_argument("selection dimensions must be positive");
        const auto values = readDoubles(env, xy);
        if (values.size() % 2U != 0U) throw std::invalid_argument("polygon coordinates must be x,y pairs");
        std::vector<croptop::selection::Point> points;
        points.reserve(values.size() / 2U);
        for (std::size_t i = 0; i < values.size(); i += 2U) points.push_back({values[i], values[i + 1U]});
        return makeStore(croptop::selection::rasterizePolygon(static_cast<std::uint32_t>(width),
                                                               static_cast<std::uint32_t>(height), points,
                                                               antialias == JNI_TRUE));
    });
}

extern "C" JNIEXPORT jlong JNICALL
Java_app_croptop_core_engine_NativeSelectionBridge_combine(JNIEnv*, jobject, jlong selection_handle, jlong shape_handle,
                                                            jint operation) {
    return invoke<jlong>(0, [&] {
        if (operation < 0 || operation > 3) throw std::invalid_argument("unknown selection combine operation");
        std::string error;
        const auto result = croptop::selection::combine(requireMask(selection_handle), requireMask(shape_handle),
                                                         static_cast<croptop::selection::Op>(operation), &error);
        if (!result) throw std::runtime_error(error.empty() ? "selection combine failed" : error);
        return makeStore(TileStore(std::move(*result)));
    });
}

extern "C" JNIEXPORT jlong JNICALL
Java_app_croptop_core_engine_NativeSelectionBridge_invert(JNIEnv*, jobject, jlong selection_handle) {
    return invoke<jlong>(0, [&] {
        std::string error;
        auto result = croptop::selection::invert(requireMask(selection_handle), &error);
        if (!result) throw std::runtime_error(error.empty() ? "could not invert selection mask" : error);
        return makeStore(std::move(*result));
    });
}

extern "C" JNIEXPORT jlong JNICALL
Java_app_croptop_core_engine_NativeSelectionBridge_modify(JNIEnv*, jobject, jlong selection_handle, jint operation,
                                                           jfloat radius) {
    return invoke<jlong>(0, [&] {
        auto& mask = requireMask(selection_handle);
        std::string error;
        std::optional<TileStore> result;
        switch (operation) {
            case 0: result = croptop::selection::feather(mask, radius, error); break;
            case 1: result = croptop::selection::expand(mask, radius, error); break;
            case 2: result = croptop::selection::contract(mask, radius, error); break;
            case 3: result = croptop::selection::border(mask, radius, error); break;
            case 4: result = croptop::selection::smooth(mask, radius, error); break;
            default: throw std::invalid_argument("unknown selection modify operation");
        }
        if (!result) throw std::runtime_error(error.empty() ? "selection modification failed" : error);
        return makeStore(std::move(*result));
    });
}

extern "C" JNIEXPORT jlong JNICALL
Java_app_croptop_core_engine_NativeSelectionBridge_magicWand(JNIEnv*, jobject, jlong source_handle, jlong x, jlong y,
                                                              jint tolerance, jboolean contiguous) {
    return invoke<jlong>(0, [&] {
        auto& source = requireStore(source_handle).pixels;
        croptop::selection::MagicWandSpec spec;
        spec.x = x;
        spec.y = y;
        spec.tolerance = tolerance;
        spec.contiguous = contiguous == JNI_TRUE;
        std::string error;
        auto result = croptop::selection::magicWand(source, spec, error);
        if (!result) throw std::runtime_error(error.empty() ? "Magic Wand failed" : error);
        return makeStore(std::move(*result));
    });
}

extern "C" JNIEXPORT jlong JNICALL
Java_app_croptop_core_engine_NativeSelectionBridge_colorRange(JNIEnv* env, jobject, jlong source_handle, jint target,
                                                               jfloatArray packed_samples, jint fuzziness,
                                                               jboolean invert, jint worker_threads) {
    return invoke<jlong>(0, [&] {
        if (target < 0 || target > static_cast<jint>(croptop::selection::ColorRangeTarget::SkinTones)) {
            throw std::invalid_argument("unknown color range target");
        }
        if (worker_threads < 0) throw std::invalid_argument("worker thread count cannot be negative");
        const auto values = readFloats(env, packed_samples);
        if (values.size() % 3U != 0U) throw std::invalid_argument("color samples must be RGB triples");
        croptop::selection::ColorRangeSpec spec;
        spec.target = static_cast<croptop::selection::ColorRangeTarget>(target);
        spec.fuzziness = fuzziness;
        spec.invert = invert == JNI_TRUE;
        spec.worker_threads = static_cast<unsigned>(worker_threads);
        spec.samples.reserve(values.size() / 3U);
        for (std::size_t i = 0; i < values.size(); i += 3U) spec.samples.push_back({values[i], values[i + 1U], values[i + 2U]});
        std::string error;
        auto result = croptop::selection::colorRange(requireStore(source_handle).pixels, spec, error);
        if (!result) throw std::runtime_error(error.empty() ? "Color Range failed" : error);
        return makeStore(std::move(*result));
    });
}

extern "C" JNIEXPORT jlong JNICALL
Java_app_croptop_core_engine_NativeSelectionBridge_quickBegin(JNIEnv*, jobject, jlong source_handle, jlong before_handle,
                                                                jfloat diameter, jboolean subtract, jboolean enhance_edge) {
    return invoke<jlong>(0, [&] {
        QuickSelectSpec spec;
        spec.diameter = diameter;
        spec.subtract = subtract == JNI_TRUE;
        spec.enhance_edge = enhance_edge == JNI_TRUE;
        std::string error;
        auto selector = QuickSelector::begin(requireStore(source_handle).pixels, requireMask(before_handle), spec, error);
        if (!selector) throw std::runtime_error(error.empty() ? "could not begin Quick Selection" : error);
        return reinterpret_cast<jlong>(new NativeQuickSession(std::move(selector)));
    });
}

extern "C" JNIEXPORT jboolean JNICALL
Java_app_croptop_core_engine_NativeSelectionBridge_quickAdd(JNIEnv* env, jobject, jlong session_handle, jfloatArray xy) {
    return invoke<jboolean>(JNI_FALSE, [&] {
        const auto points = readFloats(env, xy);
        std::string error;
        if (!requireSession(session_handle).selector->add(points, error)) {
            if (!error.empty()) g_last_error = error;
            return static_cast<jboolean>(JNI_FALSE);
        }
        return static_cast<jboolean>(JNI_TRUE);
    });
}

extern "C" JNIEXPORT jlong JNICALL
Java_app_croptop_core_engine_NativeSelectionBridge_quickCurrent(JNIEnv*, jobject, jlong session_handle) {
    return invoke<jlong>(0, [&] {
        const auto& current = requireSession(session_handle).selector->current();
        return reinterpret_cast<jlong>(new NativeTileStore(current));
    });
}

extern "C" JNIEXPORT jlong JNICALL
Java_app_croptop_core_engine_NativeSelectionBridge_quickFinish(JNIEnv*, jobject, jlong session_handle) {
    return invoke<jlong>(0, [&] {
        std::string error;
        auto result = requireSession(session_handle).selector->finish(error);
        if (!result) throw std::runtime_error(error.empty() ? "Quick Selection finish failed" : error);
        return makeStore(std::move(*result));
    });
}

extern "C" JNIEXPORT void JNICALL
Java_app_croptop_core_engine_NativeSelectionBridge_quickDestroy(JNIEnv*, jobject, jlong session_handle) {
    g_last_error.clear();
    delete reinterpret_cast<NativeQuickSession*>(session_handle);
}

extern "C" JNIEXPORT jlong JNICALL
Java_app_croptop_core_engine_NativeSelectionBridge_transform(JNIEnv* env, jobject, jlong selection_handle,
                                                              jdoubleArray matrix, jint worker_threads) {
    return invoke<jlong>(0, [&] {
        if (worker_threads < 0) throw std::invalid_argument("worker thread count cannot be negative");
        const auto values = readDoubles(env, matrix);
        if (values.size() != 9U) throw std::invalid_argument("selection transform matrix must contain nine values");
        std::array<double, 9> transform_matrix{};
        std::copy(values.begin(), values.end(), transform_matrix.begin());
        std::string error;
        auto result = croptop::selection::transform(requireMask(selection_handle), transform_matrix, error,
                                                      static_cast<unsigned>(worker_threads));
        if (!result) throw std::runtime_error(error.empty() ? "selection transform failed" : error);
        return makeStore(std::move(*result));
    });
}

extern "C" JNIEXPORT jintArray JNICALL
Java_app_croptop_core_engine_NativeSelectionBridge_bounds(JNIEnv* env, jobject, jlong selection_handle) {
    return invoke<jintArray>(nullptr, [&] {
        const auto result = croptop::selection::bounds(requireMask(selection_handle));
        if (!result) return env->NewIntArray(0);
        return toIntArray(env, {result->left, result->top, result->right, result->bottom});
    });
}

extern "C" JNIEXPORT jintArray JNICALL
Java_app_croptop_core_engine_NativeSelectionBridge_contour(JNIEnv* env, jobject, jlong selection_handle,
                                                            jintArray packed_region, jint mip_level, jint threshold,
                                                            jint max_segments) {
    return invoke<jintArray>(nullptr, [&] {
        const auto values = readInts(env, packed_region);
        if (values.size() != 4U) throw std::invalid_argument("contour region must be [left,top,right,bottom]");
        if (mip_level < 0 || threshold < 0 || threshold > 65535 || max_segments < 0) {
            throw std::invalid_argument("invalid contour options");
        }
        if (max_segments > 200'000) throw std::invalid_argument("contour segment limit exceeds 200000");
        const croptop::PixelRect region{values[0], values[1], values[2], values[3]};
        const auto result = croptop::selection::contour(requireMask(selection_handle), region,
                                                         static_cast<std::uint32_t>(mip_level),
                                                         static_cast<std::uint16_t>(threshold),
                                                         static_cast<std::size_t>(max_segments));
        std::vector<jint> packed;
        packed.reserve(2U + result.segments.size() * 4U);
        packed.push_back(result.truncated ? 1 : 0);
        packed.push_back(static_cast<jint>(result.segments.size()));
        for (const auto& segment : result.segments) {
            packed.push_back(segment.x0);
            packed.push_back(segment.y0);
            packed.push_back(segment.x1);
            packed.push_back(segment.y1);
        }
        return toIntArray(env, packed);
    });
}
