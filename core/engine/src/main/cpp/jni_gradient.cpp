// Native bridge for the transactional Gradient tool command.
#if defined(__ANDROID__)
#include <jni.h>

#include <cmath>
#include <exception>
#include <string>
#include <vector>

#include "gradient_fill.h"
#include "native_store_handle.h"

extern "C" JNIEXPORT jstring JNICALL
Java_app_croptop_core_engine_NativeGradientBridge_nativeRender(JNIEnv* env, jobject, jlong handle, jfloat start_x,
                                                               jfloat start_y, jfloat end_x, jfloat end_y,
                                                               jfloatArray colors, jfloatArray opacities, jint shape,
                                                               jboolean reverse, jboolean dither, jfloat opacity,
                                                               jlong mask_handle) {
    try {
        auto* target = reinterpret_cast<croptop::jni::NativeTileStore*>(handle);
        if (target == nullptr) return env->NewStringUTF("Gradient target is unavailable");
        const auto* mask_store = reinterpret_cast<croptop::jni::NativeTileStore*>(mask_handle);
        const croptop::TileStore* mask = nullptr;
        if (mask_handle != 0) {
            if (mask_store == nullptr || mask_store->pixels.format() != croptop::PixelFormat::A16 ||
                mask_store->pixels.width() != target->pixels.width() || mask_store->pixels.height() != target->pixels.height()) {
                return env->NewStringUTF("Gradient selection mask is incompatible with the target");
            }
            mask = &mask_store->pixels;
        }
        if (colors == nullptr || opacities == nullptr) return env->NewStringUTF("Gradient stops are missing");
        const auto color_count = env->GetArrayLength(colors);
        const auto opacity_count = env->GetArrayLength(opacities);
        if (color_count < 8 || color_count % 4 != 0 || opacity_count < 4 || opacity_count % 2 != 0 ||
            color_count / 4 != opacity_count / 2) {
            return env->NewStringUTF("Gradient stops have invalid dimensions");
        }
        if (shape < 0 || shape > 4) return env->NewStringUTF("Gradient shape is unsupported");
        if (!std::isfinite(start_x) || !std::isfinite(start_y) || !std::isfinite(end_x) || !std::isfinite(end_y) ||
            !std::isfinite(opacity) || opacity < 0.0F || opacity > 1.0F) {
            return env->NewStringUTF("Gradient geometry or opacity is invalid");
        }
        std::vector<jfloat> color_values(static_cast<std::size_t>(color_count));
        std::vector<jfloat> opacity_values(static_cast<std::size_t>(opacity_count));
        env->GetFloatArrayRegion(colors, 0, color_count, color_values.data());
        env->GetFloatArrayRegion(opacities, 0, opacity_count, opacity_values.data());
        if (env->ExceptionCheck()) return nullptr;

        croptop::paint::GradientSpec spec;
        spec.start = {start_x, start_y};
        spec.end = {end_x, end_y};
        spec.shape = static_cast<croptop::paint::GradientShape>(shape);
        spec.reverse = reverse == JNI_TRUE;
        spec.dither = dither == JNI_TRUE;
        spec.opacity = opacity;
        spec.colors.reserve(static_cast<std::size_t>(color_count / 4));
        spec.opacities.reserve(static_cast<std::size_t>(opacity_count / 2));
        for (jsize index = 0; index < color_count; index += 4) {
            spec.colors.push_back({color_values[static_cast<std::size_t>(index)], color_values[static_cast<std::size_t>(index + 1)],
                                   color_values[static_cast<std::size_t>(index + 2)], color_values[static_cast<std::size_t>(index + 3)]});
        }
        for (jsize index = 0; index < opacity_count; index += 2) {
            spec.opacities.push_back({opacity_values[static_cast<std::size_t>(index)], opacity_values[static_cast<std::size_t>(index + 1)]});
        }
        std::string error;
        if (!croptop::paint::renderGradient(target->pixels, spec, mask, error)) {
            return env->NewStringUTF(error.empty() ? "Gradient could not be rendered" : error.c_str());
        }
        return nullptr;
    } catch (const std::exception& failure) {
        return env->NewStringUTF(failure.what());
    } catch (...) {
        return env->NewStringUTF("Unknown native gradient failure");
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_app_croptop_core_engine_NativeGradientBridge_nativeBucket(JNIEnv* env, jobject, jlong target_handle,
                                                               jlong sample_handle, jfloat x, jfloat y, jfloat red,
                                                               jfloat green, jfloat blue, jfloat alpha, jint tolerance,
                                                               jboolean contiguous, jfloat opacity, jlong mask_handle) {
    try {
        auto* target = reinterpret_cast<croptop::jni::NativeTileStore*>(target_handle);
        auto* sample = reinterpret_cast<croptop::jni::NativeTileStore*>(sample_handle);
        if (target == nullptr || sample == nullptr) return env->NewStringUTF("Paint Bucket pixels are unavailable");
        const auto* mask_store = reinterpret_cast<croptop::jni::NativeTileStore*>(mask_handle);
        const croptop::TileStore* mask = nullptr;
        if (mask_handle != 0) {
            if (mask_store == nullptr || mask_store->pixels.format() != croptop::PixelFormat::A16 ||
                mask_store->pixels.width() != target->pixels.width() || mask_store->pixels.height() != target->pixels.height()) {
                return env->NewStringUTF("Paint Bucket selection mask is incompatible with the target");
            }
            mask = &mask_store->pixels;
        }
        if (!std::isfinite(x) || !std::isfinite(y) || !std::isfinite(red) || !std::isfinite(green) ||
            !std::isfinite(blue) || !std::isfinite(alpha) || !std::isfinite(opacity) || x < 0.0F || y < 0.0F ||
            red < 0.0F || red > 1.0F || green < 0.0F || green > 1.0F || blue < 0.0F || blue > 1.0F ||
            alpha < 0.0F || alpha > 1.0F || opacity < 0.0F || opacity > 1.0F || tolerance < 0 || tolerance > 255) {
            return env->NewStringUTF("Paint Bucket arguments are out of range");
        }
        std::string error;
        if (!croptop::paint::bucketFill(target->pixels, sample->pixels, static_cast<std::int64_t>(x),
                                        static_cast<std::int64_t>(y), {red, green, blue, alpha}, tolerance, contiguous == JNI_TRUE,
                                        mask, opacity, error)) {
            return env->NewStringUTF(error.empty() ? "Paint Bucket could not fill this region" : error.c_str());
        }
        return nullptr;
    } catch (const std::exception& failure) {
        return env->NewStringUTF(failure.what());
    } catch (...) {
        return env->NewStringUTF("Unknown Paint Bucket failure");
    }
}
#endif
