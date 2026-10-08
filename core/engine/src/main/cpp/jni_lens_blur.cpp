// Handoff for Lens Blur. JNI entry points for NativeLensBlurBridge.
#if defined(__ANDROID__)
#include <jni.h>

#include <memory>
#include <stdexcept>
#include <string>
#include <cstring>
#include <algorithm>

#include "lens_blur.h"
#include "native_store_handle.h"
#include "pixel_plane.h"
#include "selection_mask.h"

namespace {

using croptop::jni::NativeTileStore;
using croptop::lensblur::LensBlurSpec;

thread_local std::string g_lens_blur_error;

template <typename Result, typename Fn>
Result guarded(Result failure, Fn&& fn) {
    g_lens_blur_error.clear();
    try {
        return fn();
    } catch (const std::exception& e) {
        g_lens_blur_error = e.what();
    } catch (...) {
        g_lens_blur_error = "unknown native Lens Blur error";
    }
    return failure;
}

const NativeTileStore& store(jlong handle, const char* label) {
    const auto* value = reinterpret_cast<const NativeTileStore*>(handle);
    if (value == nullptr) throw std::invalid_argument(std::string(label) + " store handle is null");
    return *value;
}

}  // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_app_croptop_core_engine_NativeLensBlurBridge_nativeLastError(JNIEnv* env, jobject) {
    return env->NewStringUTF(g_lens_blur_error.c_str());
}

extern "C" JNIEXPORT jlong JNICALL Java_app_croptop_core_engine_NativeLensBlurBridge_nativeLensBlur(
    JNIEnv* env, jobject, jlong layer_handle, jlong depth_handle, jfloat radius, jfloat focal_distance, jboolean invert_depth,
    jint iris_blades, jfloat iris_rotation, jfloat iris_curvature, jfloat specular_brightness, jfloat specular_threshold,
    jfloat noise, jboolean monochromatic_noise, jint seed, jint worker_threads, jobject progress_callback) {
    return guarded<jlong>(0, [&] {
        if (worker_threads < 0 || worker_threads > 32) throw std::invalid_argument("worker count must be between 0 and 32");
        if (progress_callback == nullptr) throw std::invalid_argument("Lens Blur progress callback is null");
        jclass callback_class = env->GetObjectClass(progress_callback);
        if (callback_class == nullptr) throw std::runtime_error("could not inspect Lens Blur progress callback");
        const jmethodID on_progress = env->GetMethodID(callback_class, "test", "(D)Z");
        env->DeleteLocalRef(callback_class);
        if (on_progress == nullptr) throw std::runtime_error("Lens Blur progress callback has no test method");
        const auto& layer = store(layer_handle, "Lens Blur source");
        const auto* depth = depth_handle == 0 ? nullptr : &store(depth_handle, "Depth map");
        LensBlurSpec spec;
        spec.radius = radius;
        spec.focal_distance = focal_distance;
        spec.invert_depth = invert_depth == JNI_TRUE;
        spec.iris.blades = iris_blades;
        spec.iris.rotation_degrees = iris_rotation;
        spec.iris.curvature = iris_curvature;
        spec.specular_brightness = specular_brightness;
        spec.specular_threshold = specular_threshold;
        spec.noise = noise;
        spec.monochromatic_noise = monochromatic_noise == JNI_TRUE;
        spec.seed = static_cast<std::uint32_t>(seed);
        spec.worker_threads = static_cast<unsigned>(worker_threads);
        spec.progress = [env, progress_callback, on_progress](float fraction) {
            const jboolean keep_going = env->CallBooleanMethod(progress_callback, on_progress, static_cast<jdouble>(fraction));
            return !env->ExceptionCheck() && keep_going == JNI_TRUE;
        };
        std::string error;
        auto result = croptop::lensblur::lensBlur(layer.pixels, depth != nullptr ? &depth->pixels : nullptr, spec, error);
        if (!result) throw std::invalid_argument(error.empty() ? "Lens Blur failed" : error);
        return reinterpret_cast<jlong>(new NativeTileStore(std::move(*result)));
    });
}

extern "C" JNIEXPORT jlong JNICALL Java_app_croptop_core_engine_NativeLensBlurBridge_nativeBlendSelection(
    JNIEnv*, jobject, jlong source_handle, jlong processed_handle, jlong selection_handle) {
    return guarded<jlong>(0, [&] {
        const auto& source = store(source_handle, "Lens Blur source");
        const auto& processed = store(processed_handle, "Lens Blur result");
        const auto& selection = store(selection_handle, "Selection");
        if (!croptop::paint::detail::isRgba(source.pixels.format()) || processed.pixels.format() != source.pixels.format() ||
            processed.pixels.width() != source.pixels.width() || processed.pixels.height() != source.pixels.height() ||
            selection.pixels.format() != croptop::PixelFormat::A16 || selection.pixels.width() != source.pixels.width() ||
            selection.pixels.height() != source.pixels.height()) {
            throw std::invalid_argument("selection, source and Lens Blur result dimensions or formats do not match");
        }
        croptop::TileStore mixed(source.pixels);
        const auto bounds = croptop::selection::bounds(selection.pixels);
        if (bounds.has_value()) {
            const auto edge = static_cast<std::int32_t>(croptop::kTileEdge);
            const auto first_x = bounds->left / edge, last_x = (bounds->right - 1) / edge;
            const auto first_y = bounds->top / edge, last_y = (bounds->bottom - 1) / edge;
            const auto bpp = source.pixels.bytes_per_pixel();
            for (auto ty = first_y; ty <= last_y; ++ty) {
                for (auto tx = first_x; tx <= last_x; ++tx) {
                    const croptop::TileCoordinate coordinate{tx, ty};
                    const auto mask_tile = selection.pixels.readTile(coordinate);
                    if (!mask_tile) continue;
                    const auto before_tile = source.pixels.readTile(coordinate);
                    const auto after_tile = processed.pixels.readTile(coordinate);
                    croptop::TileBuffer output = before_tile ? *before_tile : croptop::TileBuffer(source.pixels.tile_byte_size(), 0);
                    for (std::int32_t y = 0; y < edge; ++y) {
                        const auto gy = ty * edge + y;
                        if (gy < bounds->top || gy >= bounds->bottom || gy < 0 || gy >= static_cast<std::int32_t>(source.pixels.height())) continue;
                        for (std::int32_t x = 0; x < edge; ++x) {
                            const auto gx = tx * edge + x;
                            if (gx < bounds->left || gx >= bounds->right || gx < 0 || gx >= static_cast<std::int32_t>(source.pixels.width())) continue;
                            const auto local = static_cast<std::size_t>(y) * croptop::kTileEdge + static_cast<std::size_t>(x);
                            std::uint16_t raw = 0;
                            std::memcpy(&raw, mask_tile->data() + local * sizeof(raw), sizeof(raw));
                            if (raw == 0) continue;
                            const auto coverage = static_cast<float>(raw) / 65535.0F;
                            const auto offset = local * bpp;
                            float before[4]{0, 0, 0, 0};
                            float after[4]{0, 0, 0, 0};
                            if (before_tile) croptop::paint::detail::readPixel(source.pixels.format(), before_tile->data() + offset, before);
                            if (after_tile) croptop::paint::detail::readPixel(processed.pixels.format(), after_tile->data() + offset, after);
                            const auto source_alpha = croptop::paint::detail::unitClamp(before[3]);
                            const auto result_alpha = croptop::paint::detail::unitClamp(after[3]);
                            const auto mixed_alpha = source_alpha + coverage * (result_alpha - source_alpha);
                            for (std::size_t channel = 0; channel < 4; ++channel) {
                                if (channel == 3) {
                                    before[channel] = mixed_alpha;
                                } else {
                                    const auto source_premultiplied = before[channel] * source_alpha;
                                    const auto result_premultiplied = after[channel] * result_alpha;
                                    const auto mixed_premultiplied = source_premultiplied + coverage * (result_premultiplied - source_premultiplied);
                                    before[channel] = mixed_alpha > 1e-8F
                                        ? mixed_premultiplied / mixed_alpha
                                        : before[channel] + coverage * (after[channel] - before[channel]);
                                }
                            }
                            croptop::paint::detail::writePixel(source.pixels.format(), output.data() + offset, before);
                        }
                    }
                    const bool empty = std::all_of(output.begin(), output.end(), [](std::uint8_t value) { return value == 0; });
                    if (empty) mixed.eraseTile(coordinate);
                    else if (!mixed.writeTile(coordinate, output)) throw std::runtime_error("could not store selection-masked Lens Blur output");
                }
            }
        }
        return reinterpret_cast<jlong>(new NativeTileStore(std::move(mixed)));
    });
}

extern "C" JNIEXPORT jlong JNICALL Java_app_croptop_core_engine_NativeLensBlurBridge_nativeDepthRangeMask(
    JNIEnv*, jobject, jlong depth_handle, jfloat near_depth, jfloat far_depth, jfloat feather) {
    return guarded<jlong>(0, [&] {
        const auto& depth = store(depth_handle, "Depth map");
        std::string error;
        auto result = croptop::lensblur::depthRangeMask(depth.pixels, near_depth, far_depth, feather, error);
        if (!result) throw std::invalid_argument(error.empty() ? "Depth-range mask failed" : error);
        return reinterpret_cast<jlong>(new NativeTileStore(std::move(*result)));
    });
}
#endif
