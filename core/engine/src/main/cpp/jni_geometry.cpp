// Budget-preserving exact canvas geometry operations for NativeEngine.
#if defined(__ANDROID__)
#include <jni.h>

#include <array>
#include <algorithm>
#include <cmath>
#include <cstdint>
#include <exception>
#include <optional>
#include <stdexcept>
#include <string>
#include <utility>

#include "crop_geometry.h"
#include "native_store_handle.h"
#include "resample.h"

namespace {

using croptop::jni::NativeTileStore;
thread_local std::string g_geometry_error;

}  // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_app_croptop_core_engine_NativeEngine_nativeGeometryLastError(JNIEnv* env, jobject) {
    return env->NewStringUTF(g_geometry_error.c_str());
}

extern "C" JNIEXPORT jlong JNICALL
Java_app_croptop_core_engine_NativeEngine_nativeGeometryTransformTileStore(
    JNIEnv*, jobject, jlong store_handle, jint operation, jint width, jint height, jint dx, jint dy) {
    g_geometry_error.clear();
    try {
        auto* source = reinterpret_cast<NativeTileStore*>(store_handle);
        if (source == nullptr) throw std::invalid_argument("tile store handle is null");
        if (width <= 0 || height <= 0 || width > 65535 || height > 65535) {
            throw std::invalid_argument("native canvas dimensions must be between 1 and 65535 pixels");
        }
        if ((operation == 1 || operation == 2) &&
            (static_cast<std::uint32_t>(width) != source->pixels.width() ||
             static_cast<std::uint32_t>(height) != source->pixels.height())) {
            throw std::invalid_argument("flip output dimensions must match the source");
        }
        if (operation == 3) {
            const auto turns = ((dx % 4) + 4) % 4;
            const auto expected_width = turns % 2 == 0 ? source->pixels.width() : source->pixels.height();
            const auto expected_height = turns % 2 == 0 ? source->pixels.height() : source->pixels.width();
            if (static_cast<std::uint32_t>(width) != expected_width || static_cast<std::uint32_t>(height) != expected_height) {
                throw std::invalid_argument("rotate output dimensions do not match the quarter-turn count");
            }
        }
        std::optional<croptop::TileStore> result;
        switch (operation) {
            case 0: {
                std::string error;
                auto resized = croptop::resample::canvasSize(source->pixels, static_cast<std::uint32_t>(width),
                                                              static_cast<std::uint32_t>(height), dx, dy, error);
                if (!resized) throw std::runtime_error(error.empty() ? "canvas resize failed" : error);
                result.emplace(std::move(*resized));
                break;
            }
            case 1: result.emplace(croptop::resample::flip(source->pixels, true)); break;
            case 2: result.emplace(croptop::resample::flip(source->pixels, false)); break;
            case 3: result.emplace(croptop::resample::rotate90(source->pixels, dx)); break;
            case 4: {
                if (dx < 0 || dx > 3) throw std::invalid_argument("unknown image resampling filter");
                std::string error;
                auto resized = croptop::resample::resize(
                    source->pixels, static_cast<std::uint32_t>(width), static_cast<std::uint32_t>(height),
                    static_cast<croptop::resample::Filter>(dx), error);
                if (!resized) throw std::runtime_error(error.empty() ? "image resampling failed" : error);
                result.emplace(std::move(*resized));
                break;
            }
            default: throw std::invalid_argument("unknown canvas geometry operation");
        }
        return reinterpret_cast<jlong>(new NativeTileStore(std::move(*result)));
    } catch (const std::exception& error) {
        g_geometry_error = error.what();
    } catch (...) {
        g_geometry_error = "unknown canvas geometry failure";
    }
    return 0;
}

extern "C" JNIEXPORT jdoubleArray JNICALL
Java_app_croptop_core_engine_NativeEngine_nativeGeometryStraightenCrop(
    JNIEnv* env, jobject, jint left, jint top, jint width, jint height, jdouble angle_degrees) {
    g_geometry_error.clear();
    try {
        if (width <= 0 || height <= 0 || width > 65535 || height > 65535 || !std::isfinite(angle_degrees)) {
            throw std::invalid_argument("crop needs a finite angle and dimensions between 1 and 65535 pixels");
        }
        std::string error;
        const auto angle = std::remainder(angle_degrees, 360.0);
        auto geometry = croptop::crop::straighten(static_cast<std::uint32_t>(width), static_cast<std::uint32_t>(height), angle, error);
        if (!geometry) throw std::invalid_argument(error.empty() ? "could not calculate crop geometry" : error);
        auto matrix = geometry->matrix;
        matrix[2] -= matrix[0] * static_cast<double>(left) + matrix[1] * static_cast<double>(top);
        matrix[5] -= matrix[3] * static_cast<double>(left) + matrix[4] * static_cast<double>(top);
        std::array<jdouble, 11> packed{};
        packed[0] = geometry->width;
        packed[1] = geometry->height;
        for (std::size_t i = 0; i < matrix.size(); ++i) packed[i + 2] = matrix[i];
        auto result = env->NewDoubleArray(static_cast<jsize>(packed.size()));
        if (result == nullptr) return nullptr;
        env->SetDoubleArrayRegion(result, 0, static_cast<jsize>(packed.size()), packed.data());
        if (env->ExceptionCheck()) {
            env->DeleteLocalRef(result);
            return nullptr;
        }
        return result;
    } catch (const std::exception& error) {
        g_geometry_error = error.what();
    } catch (...) {
        g_geometry_error = "unknown crop geometry failure";
    }
    return nullptr;
}

extern "C" JNIEXPORT jdoubleArray JNICALL
Java_app_croptop_core_engine_NativeEngine_nativeGeometryPerspectiveCrop(
    JNIEnv* env, jobject, jint source_width, jint source_height, jdoubleArray quad_values) {
    g_geometry_error.clear();
    try {
        if (quad_values == nullptr || source_width <= 0 || source_height <= 0 || source_width > 65535 || source_height > 65535) {
            throw std::invalid_argument("perspective crop requires a valid canvas and four corners");
        }
        if (env->GetArrayLength(quad_values) != 8) throw std::invalid_argument("perspective crop needs exactly four x/y corners");
        std::array<jdouble, 8> values{};
        env->GetDoubleArrayRegion(quad_values, 0, static_cast<jsize>(values.size()), values.data());
        if (env->ExceptionCheck()) return nullptr;
        std::array<croptop::crop::Point, 4> quad{};
        for (std::size_t i = 0; i < quad.size(); ++i) {
            const auto x = values[i * 2];
            const auto y = values[i * 2 + 1];
            if (!std::isfinite(x) || !std::isfinite(y) || x < 0.0 || y < 0.0 || x > source_width || y > source_height) {
                throw std::invalid_argument("perspective crop corners must be finite and inside the canvas");
            }
            quad[i] = {x, y};
        }
        const auto edge_length = [](const croptop::crop::Point& a, const croptop::crop::Point& b) {
            return std::hypot(b.x - a.x, b.y - a.y);
        };
        const auto output_width = std::lround((edge_length(quad[0], quad[1]) + edge_length(quad[3], quad[2])) * 0.5);
        const auto output_height = std::lround((edge_length(quad[0], quad[3]) + edge_length(quad[1], quad[2])) * 0.5);
        if (output_width < 1 || output_height < 1 || output_width > 65535 || output_height > 65535) {
            throw std::invalid_argument("perspective crop output dimensions exceed 65535 pixels per side");
        }
        const auto output = croptop::crop::perspectiveCropSize(quad);
        std::string error;
        auto matrix = croptop::crop::perspectiveCrop(quad, output[0], output[1], error);
        if (!matrix) throw std::invalid_argument(error.empty() ? "could not calculate perspective crop" : error);
        std::array<jdouble, 11> packed{};
        packed[0] = output[0];
        packed[1] = output[1];
        for (std::size_t i = 0; i < matrix->size(); ++i) packed[i + 2] = (*matrix)[i];
        auto result = env->NewDoubleArray(static_cast<jsize>(packed.size()));
        if (result == nullptr) return nullptr;
        env->SetDoubleArrayRegion(result, 0, static_cast<jsize>(packed.size()), packed.data());
        if (env->ExceptionCheck()) {
            env->DeleteLocalRef(result);
            return nullptr;
        }
        return result;
    } catch (const std::exception& error) {
        g_geometry_error = error.what();
    } catch (...) {
        g_geometry_error = "unknown perspective crop geometry failure";
    }
    return nullptr;
}

extern "C" JNIEXPORT jlong JNICALL
Java_app_croptop_core_engine_NativeEngine_nativeGeometryWarpTileStore(
    JNIEnv* env, jobject, jlong store_handle, jint width, jint height, jdoubleArray matrix_values, jint filter) {
    g_geometry_error.clear();
    try {
        auto* source = reinterpret_cast<NativeTileStore*>(store_handle);
        if (source == nullptr || matrix_values == nullptr) throw std::invalid_argument("crop store and matrix are required");
        if (width <= 0 || height <= 0 || width > 65535 || height > 65535) {
            throw std::invalid_argument("crop output dimensions must be between 1 and 65535 pixels");
        }
        if (env->GetArrayLength(matrix_values) != 9) throw std::invalid_argument("crop matrix must contain nine values");
        if (filter < 0 || filter > 3) throw std::invalid_argument("unknown crop resampling filter");
        croptop::crop::Matrix matrix{};
        env->GetDoubleArrayRegion(matrix_values, 0, 9, matrix.data());
        if (env->ExceptionCheck()) return 0;
        if (!std::all_of(matrix.begin(), matrix.end(), [](double value) { return std::isfinite(value); })) {
            throw std::invalid_argument("crop matrix must be finite");
        }
        std::string error;
        auto warped = croptop::resample::warp(source->pixels, matrix, static_cast<std::uint32_t>(width),
                                               static_cast<std::uint32_t>(height),
                                               static_cast<croptop::resample::Filter>(filter), error);
        if (!warped) throw std::runtime_error(error.empty() ? "crop resampling failed" : error);
        return reinterpret_cast<jlong>(new NativeTileStore(std::move(*warped)));
    } catch (const std::exception& error) {
        g_geometry_error = error.what();
    } catch (...) {
        g_geometry_error = "unknown crop resampling failure";
    }
    return 0;
}
#endif
