// Native bridge for the transactional solid Fill command.
#if defined(__ANDROID__)
#include <jni.h>

#include <array>
#include <cmath>
#include <exception>
#include <optional>
#include <string>
#include <vector>

#include "content_aware_sampling.h"
#include "fill_command.h"
#include "native_store_handle.h"

extern "C" JNIEXPORT jstring JNICALL
Java_app_croptop_core_engine_NativeFillBridge_nativeSolid(JNIEnv* env, jobject, jlong handle, jfloatArray color,
                                                           jfloat opacity, jint blend_mode, jboolean preserve_transparency,
                                                           jlong mask_handle) {
    try {
        auto* target = reinterpret_cast<croptop::jni::NativeTileStore*>(handle);
        if (target == nullptr) return env->NewStringUTF("Fill target is unavailable");
        const auto* mask_store = reinterpret_cast<croptop::jni::NativeTileStore*>(mask_handle);
        const croptop::TileStore* mask = nullptr;
        if (mask_handle != 0) {
            if (mask_store == nullptr || mask_store->pixels.format() != croptop::PixelFormat::A16 ||
                mask_store->pixels.width() != target->pixels.width() || mask_store->pixels.height() != target->pixels.height()) {
                return env->NewStringUTF("Fill selection mask is incompatible with the target");
            }
            mask = &mask_store->pixels;
        }
        if (color == nullptr || env->GetArrayLength(color) != 4) return env->NewStringUTF("Fill color must contain RGBA values");
        if (!std::isfinite(opacity) || opacity < 0.0F || opacity > 1.0F) return env->NewStringUTF("Fill opacity is out of range");
        if (blend_mode < 0 || blend_mode > 26) return env->NewStringUTF("Fill blend mode is unsupported");
        std::array<jfloat, 4> rgba{};
        env->GetFloatArrayRegion(color, 0, 4, rgba.data());
        if (env->ExceptionCheck()) return nullptr;
        for (const auto channel : rgba) {
            if (!std::isfinite(channel) || channel < 0.0F || channel > 1.0F) return env->NewStringUTF("Fill color is out of range");
        }
        croptop::paint::FillSpec spec;
        spec.source = croptop::paint::FillSource::Color;
        spec.color = {rgba[0], rgba[1], rgba[2], rgba[3]};
        spec.opacity = opacity;
        spec.mode = static_cast<croptop::BlendMode>(blend_mode);
        spec.preserve_transparency = preserve_transparency == JNI_TRUE;
        std::string error;
        if (!croptop::paint::fill(target->pixels, spec, mask, error)) {
            return env->NewStringUTF(error.empty() ? "Fill could not be applied" : error.c_str());
        }
        return nullptr;
    } catch (const std::exception& failure) {
        return env->NewStringUTF(failure.what());
    } catch (...) {
        return env->NewStringUTF("Unknown native fill failure");
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_app_croptop_core_engine_NativeFillBridge_nativeContentAware(JNIEnv* env, jobject, jlong handle, jfloat opacity,
                                                                  jint blend_mode, jboolean preserve_transparency,
                                                                  jlong mask_handle, jobject progress_callback, jint rotation,
                                                                  jboolean mirror, jboolean color_adaptation, jint sampling_mode,
                                                                  jfloatArray sampling_strokes) {
    try {
        auto* target = reinterpret_cast<croptop::jni::NativeTileStore*>(handle);
        if (target == nullptr) return env->NewStringUTF("Fill target is unavailable");
        const auto* mask_store = reinterpret_cast<croptop::jni::NativeTileStore*>(mask_handle);
        if (mask_store == nullptr || mask_store->pixels.format() != croptop::PixelFormat::A16 ||
            mask_store->pixels.width() != target->pixels.width() || mask_store->pixels.height() != target->pixels.height()) {
            return env->NewStringUTF("Content-Aware Fill needs a matching selection mask");
        }
        if (!std::isfinite(opacity) || opacity < 0.0F || opacity > 1.0F) return env->NewStringUTF("Fill opacity is out of range");
        if (blend_mode < 0 || blend_mode > 26) return env->NewStringUTF("Fill blend mode is unsupported");

        if (rotation < 0 || rotation > 4 || sampling_mode < 0 || sampling_mode > 2) return env->NewStringUTF("Content-Aware Fill options are invalid");
        // Strokes: [count, then per stroke: add(0/1), radius, pointCount, x0, y0, x1, y1, ...].
        std::vector<croptop::paint::SamplingStroke> strokes;
        if (sampling_strokes != nullptr && env->GetArrayLength(sampling_strokes) > 0) {
            const auto length = static_cast<std::size_t>(env->GetArrayLength(sampling_strokes));
            std::vector<jfloat> raw(length);
            env->GetFloatArrayRegion(sampling_strokes, 0, static_cast<jsize>(length), raw.data());
            if (env->ExceptionCheck()) return env->NewStringUTF("Sampling strokes could not be read");
            std::size_t at = 0;
            const auto next = [&]() -> double { return at < raw.size() ? static_cast<double>(raw[at++]) : std::nan(""); };
            const double count = next();
            if (!(count >= 0.0 && count <= 64.0)) return env->NewStringUTF("Sampling strokes are malformed");
            for (int i = 0; i < static_cast<int>(count); ++i) {
                croptop::paint::SamplingStroke stroke;
                stroke.add = next() > 0.5;
                stroke.radius = next();
                const double points = next();
                if (!(points >= 1.0 && points <= 4096.0)) return env->NewStringUTF("Sampling strokes are malformed");
                for (int p = 0; p < static_cast<int>(points); ++p) {
                    const double x = next();
                    const double y = next();
                    stroke.points.push_back({x, y});
                }
                strokes.push_back(std::move(stroke));
            }
            if (at != raw.size()) return env->NewStringUTF("Sampling strokes are malformed");
        }
        // sampling_mode: 0 = automatic (everything outside the hole, minus subtract strokes), 1 = only painted areas.
        std::optional<croptop::TileStore> sampling;
        if (sampling_mode == 1 || !strokes.empty()) {
            std::string sampling_error;
            sampling = croptop::paint::buildSamplingMask(
                target->pixels, sampling_mode == 1 ? croptop::paint::SamplingBase::None : croptop::paint::SamplingBase::All,
                strokes, sampling_error);
            if (!sampling) return env->NewStringUTF(sampling_error.empty() ? "Sampling area could not be built" : sampling_error.c_str());
        }

        croptop::paint::FillSpec spec;
        spec.rotation_adaptation = static_cast<croptop::paint::RotationAdaptation>(rotation);
        spec.mirror_adaptation = mirror == JNI_TRUE;
        spec.color_adaptation = color_adaptation == JNI_TRUE;
        spec.sampling = sampling ? &*sampling : nullptr;
        spec.source = croptop::paint::FillSource::ContentAware;
        spec.opacity = opacity;
        spec.mode = static_cast<croptop::BlendMode>(blend_mode);
        spec.preserve_transparency = preserve_transparency == JNI_TRUE;
        jmethodID on_progress = nullptr;
        if (progress_callback != nullptr) {
            jclass callback_class = env->GetObjectClass(progress_callback);
            if (callback_class == nullptr) return env->NewStringUTF("Content-Aware Fill progress callback is unavailable");
            on_progress = env->GetMethodID(callback_class, "onProgress", "(F)Z");
            env->DeleteLocalRef(callback_class);
            if (on_progress == nullptr) return env->NewStringUTF("Content-Aware Fill progress callback has an invalid signature");
        }
        spec.progress = [env, progress_callback, on_progress](float fraction) {
            if (progress_callback == nullptr) return true;
            const auto keep_going = env->CallBooleanMethod(progress_callback, on_progress, static_cast<jfloat>(fraction));
            if (env->ExceptionCheck()) {
                env->ExceptionClear();
                return false;
            }
            return keep_going == JNI_TRUE;
        };
        std::string error;
        if (!croptop::paint::fill(target->pixels, spec, &mask_store->pixels, error)) {
            return env->NewStringUTF(error.empty() ? "Content-Aware Fill could not be applied" : error.c_str());
        }
        return nullptr;
    } catch (const std::exception& failure) {
        return env->NewStringUTF(failure.what());
    } catch (...) {
        return env->NewStringUTF("Unknown Content-Aware Fill failure");
    }
}

/**
 * Applies brush strokes to an A16 mask: returns a new store handle (the caller owns it) holding `base` (0 = empty) with the strokes
 * applied. `strokes` is [count, then per stroke: add (0/1), radius, pointCount, x0, y0, ...]. 0 on failure (see nativeStrokeMaskError).
 */
static std::string g_stroke_mask_error;

extern "C" JNIEXPORT jstring JNICALL Java_app_croptop_core_engine_NativeFillBridge_nativeStrokeMaskError(JNIEnv* env, jobject) {
    return env->NewStringUTF(g_stroke_mask_error.c_str());
}

extern "C" JNIEXPORT jlong JNICALL Java_app_croptop_core_engine_NativeFillBridge_nativeStrokeMask(JNIEnv* env, jobject, jlong like_handle,
                                                                                                  jlong base_handle, jfloatArray strokes) {
    g_stroke_mask_error.clear();
    try {
        const auto* like = reinterpret_cast<croptop::jni::NativeTileStore*>(like_handle);
        if (like == nullptr) {
            g_stroke_mask_error = "Mask template is unavailable";
            return 0;
        }
        std::vector<croptop::paint::SamplingStroke> list;
        if (strokes != nullptr && env->GetArrayLength(strokes) > 0) {
            const auto length = static_cast<std::size_t>(env->GetArrayLength(strokes));
            std::vector<jfloat> raw(length);
            env->GetFloatArrayRegion(strokes, 0, static_cast<jsize>(length), raw.data());
            if (env->ExceptionCheck()) {
                g_stroke_mask_error = "Strokes could not be read";
                return 0;
            }
            std::size_t at = 0;
            const auto next = [&]() -> double { return at < raw.size() ? static_cast<double>(raw[at++]) : std::nan(""); };
            const double count = next();
            if (!(count >= 0.0 && count <= 64.0)) {
                g_stroke_mask_error = "Strokes are malformed";
                return 0;
            }
            for (int i = 0; i < static_cast<int>(count); ++i) {
                croptop::paint::SamplingStroke stroke;
                stroke.add = next() > 0.5;
                stroke.radius = next();
                const double points = next();
                if (!(points >= 1.0 && points <= 4096.0)) {
                    g_stroke_mask_error = "Strokes are malformed";
                    return 0;
                }
                for (int p = 0; p < static_cast<int>(points); ++p) {
                    const double x = next();
                    const double y = next();
                    stroke.points.push_back({x, y});
                }
                list.push_back(std::move(stroke));
            }
            if (at != raw.size()) {
                g_stroke_mask_error = "Strokes are malformed";
                return 0;
            }
        }
        const auto* base = reinterpret_cast<croptop::jni::NativeTileStore*>(base_handle);
        const auto start = base != nullptr ? base->pixels : croptop::selection::emptyMask(like->pixels);
        auto result = croptop::paint::applyStrokesToMask(like->pixels, start, list, g_stroke_mask_error);
        if (!result) return 0;
        return reinterpret_cast<jlong>(new croptop::jni::NativeTileStore(std::move(*result)));
    } catch (const std::exception& failure) {
        g_stroke_mask_error = failure.what();
        return 0;
    }
}
#endif
