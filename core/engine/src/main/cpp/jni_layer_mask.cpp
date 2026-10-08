// JNI surface for layer masks (class app.croptop.core.engine.NativeLayerMaskBridge): create, invert,
// clone, attach to a layer store (so the render passes composite it), clipping, apply into pixels, mask → selection.
// Needs the NativeTileStore `mask` / `clipped` members (native_store_handle.h) and the RenderLayer fields
// (render_graph.h).
#if defined(__ANDROID__)
#include <jni.h>

#include <algorithm>
#include <memory>
#include <stdexcept>
#include <string>
#include <vector>

#include "layer_mask.h"
#include "layer_mask_ops.h"
#include "native_store_handle.h"

namespace {

using croptop::jni::NativeTileStore;
namespace mask = croptop::layermask;

/** A layer mask as the engine holds it: pixels + empty-tile coverage. Immutable once created (clone for history). */
struct NativeLayerMask final {
    mask::MaskData data;
};

thread_local std::string g_mask_error;

mask::MaskProperties propertiesOf(jboolean enabled, jfloat density, jfloat feather) {
    mask::MaskProperties p;
    p.enabled = enabled == JNI_TRUE;
    p.density = density;
    p.feather = feather;
    return p;
}

NativeLayerMask* maskOf(jlong handle) {
    auto* m = reinterpret_cast<NativeLayerMask*>(handle);
    if (m == nullptr) throw std::invalid_argument("layer mask handle is null");
    return m;
}

NativeTileStore* storeOf(jlong handle) {
    auto* s = reinterpret_cast<NativeTileStore*>(handle);
    if (s == nullptr) throw std::invalid_argument("layer store handle is null");
    return s;
}

template <typename Body>
jlong guarded(Body&& body) {
    g_mask_error.clear();
    try {
        return body();
    } catch (const std::exception& e) {
        g_mask_error = e.what();
    } catch (...) {
        g_mask_error = "unknown layer mask failure";
    }
    return 0;
}

}  // namespace

extern "C" JNIEXPORT jstring JNICALL Java_app_croptop_core_engine_NativeLayerMaskBridge_nativeLastError(JNIEnv* env, jobject) {
    return env->NewStringUTF(g_mask_error.c_str());
}

/** kind: 0 Reveal All, 1 Hide All, 2 Reveal Selection, 3 Hide Selection. Returns a mask handle, or 0. */
extern "C" JNIEXPORT jlong JNICALL Java_app_croptop_core_engine_NativeLayerMaskBridge_nativeCreate(JNIEnv*, jobject, jlong like_store, jint kind,
                                                                                                   jlong selection_store) {
    return guarded([&]() -> jlong {
        if (kind < 0 || kind > 3) throw std::invalid_argument("unknown mask kind");
        const auto* selection = reinterpret_cast<const NativeTileStore*>(selection_store);
        std::string error;
        auto created = mask::createMask(storeOf(like_store)->pixels, static_cast<mask::NewMask>(kind), selection == nullptr ? nullptr : &selection->pixels, error);
        if (!created) throw std::invalid_argument(error);
        return reinterpret_cast<jlong>(new NativeLayerMask{std::move(*created)});
    });
}

/** Wraps an A16 store (for example one a mask brush painted into) as a mask with the given empty-tile coverage. */
extern "C" JNIEXPORT jlong JNICALL Java_app_croptop_core_engine_NativeLayerMaskBridge_nativeFromStore(JNIEnv*, jobject, jlong a16_store, jfloat empty) {
    return guarded([&]() -> jlong {
        const auto* store = storeOf(a16_store);
        if (store->pixels.format() != croptop::PixelFormat::A16) throw std::invalid_argument("a mask store must be A16");
        if (!(empty >= 0.0F && empty <= 1.0F)) throw std::invalid_argument("empty coverage must be 0..1");
        return reinterpret_cast<jlong>(new NativeLayerMask{mask::MaskData{store->pixels, empty}});
    });
}

/** Copies the unprocessed mask data attached to a layer store into a caller-owned mask handle. */
extern "C" JNIEXPORT jlong JNICALL Java_app_croptop_core_engine_NativeLayerMaskBridge_nativeFromLayerStore(JNIEnv*, jobject,
                                                                                                              jlong layer_store) {
    return guarded([&]() -> jlong {
        const auto* store = storeOf(layer_store);
        if (!store->mask) throw std::invalid_argument("layer has no attached mask");
        mask::MaskData data{store->mask->maskPixels(), store->mask->emptyCoverage()};
        return reinterpret_cast<jlong>(new NativeLayerMask{std::move(data)});
    });
}

/** The mask's pixels as a new store handle (copy-on-write), e.g. as a brush target; destroy with destroyStore. */
extern "C" JNIEXPORT jlong JNICALL Java_app_croptop_core_engine_NativeLayerMaskBridge_nativeStore(JNIEnv*, jobject, jlong mask_handle) {
    return guarded([&]() -> jlong { return reinterpret_cast<jlong>(new NativeTileStore(maskOf(mask_handle)->data.pixels)); });
}

extern "C" JNIEXPORT jfloat JNICALL Java_app_croptop_core_engine_NativeLayerMaskBridge_nativeEmpty(JNIEnv*, jobject, jlong mask_handle) {
    auto* m = reinterpret_cast<NativeLayerMask*>(mask_handle);
    return m == nullptr ? 0.0F : m->data.empty;
}

extern "C" JNIEXPORT jlong JNICALL Java_app_croptop_core_engine_NativeLayerMaskBridge_nativeClone(JNIEnv*, jobject, jlong mask_handle) {
    return guarded([&]() -> jlong { return reinterpret_cast<jlong>(new NativeLayerMask{maskOf(mask_handle)->data}); });
}

extern "C" JNIEXPORT jlong JNICALL Java_app_croptop_core_engine_NativeLayerMaskBridge_nativeInvert(JNIEnv*, jobject, jlong mask_handle) {
    return guarded([&]() -> jlong { return reinterpret_cast<jlong>(new NativeLayerMask{mask::invertMask(maskOf(mask_handle)->data)}); });
}

extern "C" JNIEXPORT void JNICALL Java_app_croptop_core_engine_NativeLayerMaskBridge_nativeDestroy(JNIEnv*, jobject, jlong mask_handle) {
    delete reinterpret_cast<NativeLayerMask*>(mask_handle);
}

/**
 * Makes the render passes composite `mask_handle` under this layer's store handle. A fresh cache is created on every
 * call (properties change, mask edited), so history snapshots that cloned the store keep the cache they had.
 */
extern "C" JNIEXPORT jboolean JNICALL Java_app_croptop_core_engine_NativeLayerMaskBridge_nativeAttach(JNIEnv*, jobject, jlong layer_store, jlong mask_handle,
                                                                                                      jboolean enabled, jfloat density, jfloat feather) {
    return guarded([&]() -> jlong {
               const auto& data = maskOf(mask_handle)->data;
               std::string error;
               std::shared_ptr<mask::MaskCache> cache = mask::MaskCache::create(data.pixels, propertiesOf(enabled, density, feather), data.empty, error, data.empty);
               if (!cache) throw std::invalid_argument(error);
               storeOf(layer_store)->mask = std::move(cache);
               return 1;
           }) == 1
               ? JNI_TRUE
               : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL Java_app_croptop_core_engine_NativeLayerMaskBridge_nativeDetach(JNIEnv*, jobject, jlong layer_store) {
    if (auto* s = reinterpret_cast<NativeTileStore*>(layer_store)) s->mask.reset();
}

extern "C" JNIEXPORT void JNICALL Java_app_croptop_core_engine_NativeLayerMaskBridge_nativeSetClipped(JNIEnv*, jobject, jlong layer_store, jboolean clipped) {
    if (auto* s = reinterpret_cast<NativeTileStore*>(layer_store)) s->clipped = clipped == JNI_TRUE;
}

/**
 * Blend If: v = [thisBlackLo, thisBlackHi, thisWhiteLo, thisWhiteHi, underBlackLo, underBlackHi, underWhiteLo, underWhiteHi] for the
 * gray channel, optionally followed by the same eight values for red, green and blue (32 in all), each 0–255.
 */
extern "C" JNIEXPORT void JNICALL Java_app_croptop_core_engine_NativeLayerMaskBridge_nativeSetBlendIf(JNIEnv* env, jobject, jlong layer_store, jintArray values) {
    auto* s = reinterpret_cast<NativeTileStore*>(layer_store);
    if (s == nullptr || values == nullptr) return;
    const auto length = env->GetArrayLength(values);
    if (length != 8 && length != 32) return;
    jint v[32] = {};
    env->GetIntArrayRegion(values, 0, length, v);
    if (env->ExceptionCheck()) return;
    const auto b = [](jint x) { return static_cast<std::uint8_t>(std::clamp<jint>(x, 0, 255)); };
    const auto range = [&](int at) { return croptop::BlendIfRange{b(v[at]), b(v[at + 1]), b(v[at + 2]), b(v[at + 3])}; };
    s->blend_if = {};
    s->blend_if.self = range(0);
    s->blend_if.under = range(4);
    if (length == 32) {
        for (int c = 0; c < 3; ++c) {
            s->blend_if.channel_self[static_cast<std::size_t>(c)] = range(8 + 8 * c);
            s->blend_if.channel_under[static_cast<std::size_t>(c)] = range(12 + 8 * c);
        }
    }
}

/** "Apply Layer Mask": the layer's pixels with the mask multiplied into alpha, as a new store handle (0 on failure). */
extern "C" JNIEXPORT jlong JNICALL Java_app_croptop_core_engine_NativeLayerMaskBridge_nativeApply(JNIEnv*, jobject, jlong layer_store, jlong mask_handle,
                                                                                                  jboolean enabled, jfloat density, jfloat feather) {
    return guarded([&]() -> jlong {
        std::string error;
        const auto* source = storeOf(layer_store);
        auto applied = mask::applyToPixels(source->pixels, maskOf(mask_handle)->data, propertiesOf(enabled, density, feather), error);
        if (!applied) throw std::invalid_argument(error);
        auto result = std::make_unique<NativeTileStore>(std::move(*applied));
        result->clipped = source->clipped;
        result->blend_if = source->blend_if;
        return reinterpret_cast<jlong>(result.release());
    });
}

/**
 * Grayscale preview of the mask, longer edge at most `max_px`: [width, height, 0xFFgggggg ARGB pixels...] as ints,
 * or null on failure (see nativeLastError).
 */
extern "C" JNIEXPORT jintArray JNICALL Java_app_croptop_core_engine_NativeLayerMaskBridge_nativeThumbnail(JNIEnv* env, jobject, jlong mask_handle,
                                                                                                          jint max_px) {
    g_mask_error.clear();
    try {
        std::string error;
        const auto thumb = mask::thumbnail(maskOf(mask_handle)->data, static_cast<std::uint32_t>(std::max(1, max_px)), error);
        if (!thumb) throw std::invalid_argument(error);
        const auto count = static_cast<jsize>(thumb->gray.size());
        std::vector<jint> values(static_cast<std::size_t>(count) + 2);
        values[0] = static_cast<jint>(thumb->width);
        values[1] = static_cast<jint>(thumb->height);
        for (std::size_t i = 0; i < thumb->gray.size(); ++i) {
            const auto g = static_cast<std::uint32_t>(thumb->gray[i]);
            values[i + 2] = static_cast<jint>(0xFF000000U | (g << 16) | (g << 8) | g);
        }
        auto result = env->NewIntArray(static_cast<jsize>(values.size()));
        if (result == nullptr) throw std::runtime_error("could not allocate the thumbnail array");
        env->SetIntArrayRegion(result, 0, static_cast<jsize>(values.size()), values.data());
        return result;
    } catch (const std::exception& e) {
        g_mask_error = e.what();
    } catch (...) {
        g_mask_error = "unknown layer mask failure";
    }
    return nullptr;
}

/** "Load Selection": the mask's effective coverage as a new A16 selection store handle (0 on failure). */
extern "C" JNIEXPORT jlong JNICALL Java_app_croptop_core_engine_NativeLayerMaskBridge_nativeToSelection(JNIEnv*, jobject, jlong mask_handle, jboolean enabled,
                                                                                                        jfloat density, jfloat feather) {
    return guarded([&]() -> jlong {
        std::string error;
        auto selection = mask::toSelection(maskOf(mask_handle)->data, propertiesOf(enabled, density, feather), error);
        if (!selection) throw std::invalid_argument(error);
        return reinterpret_cast<jlong>(new NativeTileStore(std::move(*selection)));
    });
}
#endif  // __ANDROID__
