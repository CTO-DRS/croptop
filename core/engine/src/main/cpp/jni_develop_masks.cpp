// JNI surface for Develop local-adjustment masks (class app.croptop.core.engine.NativeDevelopMasksBridge): linear and radial gradients, luminance
// and colour ranges, brush dabs, mask algebra and the masked blend that applies a local adjustment. Every call returns a new A16 (or image) store.
#if defined(__ANDROID__)
#include <jni.h>

#include <cmath>
#include <memory>
#include <stdexcept>
#include <string>

#include "develop_mask_blend.h"
#include "develop_mask_ops.h"
#include "develop_masks.h"
#include "native_store_handle.h"

namespace {

using Store = croptop::jni::NativeTileStore;
thread_local std::string g_mask_error;

const Store& storeOf(jlong handle, const char* what) {
    const auto* s = reinterpret_cast<const Store*>(handle);
    if (s == nullptr) throw std::invalid_argument(std::string(what) + " handle is null");
    return *s;
}

template <typename Fn>
jlong guarded(Fn&& fn) {
    g_mask_error.clear();
    try {
        return fn();
    } catch (const std::exception& e) {
        g_mask_error = e.what();
    } catch (...) {
        g_mask_error = "unknown mask failure";
    }
    return 0;
}

void checkSize(jint width, jint height) {
    if (width <= 0 || height <= 0 || width > 65536 || height > 65536) throw std::invalid_argument("invalid mask size");
}

}  // namespace

extern "C" JNIEXPORT jstring JNICALL Java_app_croptop_core_engine_NativeDevelopMasksBridge_nativeLastError(JNIEnv* env, jobject) {
    return env->NewStringUTF(g_mask_error.c_str());
}

extern "C" JNIEXPORT jlong JNICALL Java_app_croptop_core_engine_NativeDevelopMasksBridge_nativeLinearGradient(JNIEnv*, jobject, jint width, jint height, jdouble x1, jdouble y1, jdouble x2, jdouble y2) {
    return guarded([&]() -> jlong {
        checkSize(width, height);
        if (!std::isfinite(x1 + y1 + x2 + y2)) throw std::invalid_argument("gradient points must be finite");
        return reinterpret_cast<jlong>(new Store(croptop::develop::linearGradient(static_cast<std::uint32_t>(width), static_cast<std::uint32_t>(height), {x1, y1}, {x2, y2})));
    });
}

extern "C" JNIEXPORT jlong JNICALL Java_app_croptop_core_engine_NativeDevelopMasksBridge_nativeRadial(JNIEnv*, jobject, jint width, jint height, jdouble cx, jdouble cy, jdouble rx, jdouble ry, jdouble rotation,
                                                                                                      jdouble feather, jboolean invert) {
    return guarded([&]() -> jlong {
        checkSize(width, height);
        croptop::develop::RadialSpec spec;
        spec.centre = {cx, cy};
        spec.radius_x = rx;
        spec.radius_y = ry;
        spec.rotation = rotation;
        spec.feather = feather;
        spec.invert = invert == JNI_TRUE;
        croptop::TileStore out(croptop::PixelFormat::A16, static_cast<std::uint32_t>(width), static_cast<std::uint32_t>(height));
        std::string error;
        if (!croptop::develop::radial(static_cast<std::uint32_t>(width), static_cast<std::uint32_t>(height), spec, out, error)) throw std::invalid_argument(error);
        return reinterpret_cast<jlong>(new Store(std::move(out)));
    });
}

extern "C" JNIEXPORT jlong JNICALL Java_app_croptop_core_engine_NativeDevelopMasksBridge_nativeLuminanceRange(JNIEnv*, jobject, jlong image_handle, jfloat low, jfloat high, jfloat smoothness) {
    return guarded([&]() -> jlong {
        const auto& image = storeOf(image_handle, "image").pixels;
        croptop::TileStore out(croptop::PixelFormat::A16, image.width(), image.height());
        std::string error;
        if (!croptop::develop::luminanceRange(image, low, high, smoothness, out, error)) throw std::invalid_argument(error);
        return reinterpret_cast<jlong>(new Store(std::move(out)));
    });
}

/** target r, g, b in the image's stored domain (0-1); tolerance and feather as in develop_mask_ops.h. */
extern "C" JNIEXPORT jlong JNICALL Java_app_croptop_core_engine_NativeDevelopMasksBridge_nativeColorRange(JNIEnv*, jobject, jlong image_handle, jfloat r, jfloat g, jfloat b, jfloat tolerance, jfloat feather) {
    return guarded([&]() -> jlong {
        const auto& image = storeOf(image_handle, "image").pixels;
        croptop::develop::mask_ops::ColorRange range;
        range.target = {r, g, b};
        range.tolerance = tolerance;
        range.feather = feather;
        croptop::TileStore out(croptop::PixelFormat::A16, image.width(), image.height());
        std::string error;
        if (!croptop::develop::mask_ops::colorRange(image, range, out, error)) throw std::invalid_argument(error);
        return reinterpret_cast<jlong>(new Store(std::move(out)));
    });
}

/** A new mask with one brush dab (or erase dab) added to a clone of `mask_handle`. */
extern "C" JNIEXPORT jlong JNICALL Java_app_croptop_core_engine_NativeDevelopMasksBridge_nativeBrushDab(JNIEnv*, jobject, jlong mask_handle, jdouble cx, jdouble cy, jdouble size, jdouble feather, jdouble flow,
                                                                                                        jboolean erase) {
    return guarded([&]() -> jlong {
        auto result = std::make_unique<Store>(storeOf(mask_handle, "mask"));
        croptop::develop::Dab dab;
        dab.centre = {cx, cy};
        dab.size = size;
        dab.feather = feather;
        dab.flow = flow;
        dab.erase = erase == JNI_TRUE;
        std::string error;
        if (!croptop::develop::brushDab(result->pixels, dab, error)) throw std::invalid_argument(error);
        return reinterpret_cast<jlong>(result.release());
    });
}

/** op: 0 replace, 1 add, 2 subtract, 3 intersect. */
extern "C" JNIEXPORT jlong JNICALL Java_app_croptop_core_engine_NativeDevelopMasksBridge_nativeCombine(JNIEnv*, jobject, jlong base_handle, jlong operand_handle, jint op) {
    return guarded([&]() -> jlong {
        if (op < 0 || op > 3) throw std::invalid_argument("unknown mask operation");
        auto out = croptop::develop::mask_ops::combine(storeOf(base_handle, "base mask").pixels, storeOf(operand_handle, "operand mask").pixels, static_cast<croptop::selection::Op>(op));
        if (!out) throw std::invalid_argument("the masks must be A16 stores of the same size");
        return reinterpret_cast<jlong>(new Store(std::move(*out)));
    });
}

/** Mixes `adjusted` over `base` by mask coverage × opacity (the local adjustment); tiles outside the mask are shared with `base`. */
extern "C" JNIEXPORT jlong JNICALL Java_app_croptop_core_engine_NativeDevelopMasksBridge_nativeApplyMasked(JNIEnv*, jobject, jlong base_handle, jlong adjusted_handle, jlong mask_handle, jfloat opacity) {
    return guarded([&]() -> jlong {
        std::string error;
        auto out = croptop::develop::mask_blend::apply(storeOf(base_handle, "base").pixels, storeOf(adjusted_handle, "adjusted").pixels, storeOf(mask_handle, "mask").pixels, opacity, error);
        if (!out) throw std::invalid_argument(error);
        return reinterpret_cast<jlong>(new Store(std::move(*out)));
    });
}
#endif  // __ANDROID__
