// Viewport mask display: applied to an already composed RGBA_8888 preview bitmap.
//   mode 0, solo: the bitmap shows the A16 mask as opaque grayscale (Alt-click a mask thumbnail).
//   mode 1, overlay: the colour (straight RGBA) is laid over unselected areas, weighted by 1 − coverage (Quick
//   Mask's rubylith).
// The mask is read at the mip level whose size matches the bitmap. Returns false on any mismatch.
#include <android/bitmap.h>
#include <jni.h>

#include <algorithm>
#include <cstdint>
#include <cstring>

#include "native_store_handle.h"

extern "C" JNIEXPORT jboolean JNICALL Java_app_croptop_core_engine_NativeEngine_nativeApplyMaskOverlay(JNIEnv* env, jobject, jobject bitmap, jlong mask_handle,
                                                                                                       jint mode, jfloat red, jfloat green, jfloat blue,
                                                                                                       jfloat alpha) {
    const auto* mask = reinterpret_cast<const croptop::jni::NativeTileStore*>(mask_handle);
    if (bitmap == nullptr || mask == nullptr || mask->pixels.format() != croptop::PixelFormat::A16 || mode < 0 || mode > 3) return JNI_FALSE;
    AndroidBitmapInfo info{};
    if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS || info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) return JNI_FALSE;
    std::uint32_t level = 0;
    while (level < 31 && mask->pixels.mip_width(level) > info.width) ++level;
    if (mask->pixels.mip_width(level) != info.width || mask->pixels.mip_height(level) != info.height) return JNI_FALSE;
    void* pixels = nullptr;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS || pixels == nullptr) return JNI_FALSE;
    const auto clamp = [](float v) { return std::clamp(v, 0.0F, 1.0F); };
    const float r = clamp(red), g = clamp(green), b = clamp(blue), a = clamp(alpha);
    const auto edge = croptop::kTileEdge;
    for (std::uint32_t ty = 0; ty * edge < info.height; ++ty)
        for (std::uint32_t tx = 0; tx * edge < info.width; ++tx) {
            const auto tile = mask->pixels.readMipTile(level, {static_cast<std::int32_t>(tx), static_cast<std::int32_t>(ty)});
            for (std::uint32_t y = 0; y < edge && ty * edge + y < info.height; ++y) {
                auto* row = static_cast<std::uint8_t*>(pixels) + static_cast<std::size_t>(ty * edge + y) * info.stride;
                for (std::uint32_t x = 0; x < edge && tx * edge + x < info.width; ++x) {
                    std::uint16_t raw = 0;
                    if (tile) std::memcpy(&raw, tile->data() + (static_cast<std::size_t>(y) * edge + x) * 2, 2);
                    const float coverage = raw / 65535.0F;
                    auto* p = row + static_cast<std::size_t>(tx * edge + x) * 4;
                    if (mode == 0) {
                        const auto v = static_cast<std::uint8_t>(coverage * 255.0F + 0.5F);
                        p[0] = p[1] = p[2] = v;
                        p[3] = 255;
                    } else {
                        const float w = (mode == 1 ? a : 1.0F) * (1.0F - coverage);
                        if (w <= 0) continue;
                        const float overlay_r = mode == 2 ? 0.0F : (mode == 3 ? 1.0F : r);
                        const float overlay_g = mode == 2 ? 0.0F : (mode == 3 ? 1.0F : g);
                        const float overlay_b = mode == 2 ? 0.0F : (mode == 3 ? 1.0F : b);
                        // Bitmap pixels are premultiplied; blend the colour over them source-over.
                        const float cr = overlay_r * w, cg = overlay_g * w, cb = overlay_b * w;
                        p[0] = static_cast<std::uint8_t>(std::min(255.0F, cr * 255.0F + p[0] * (1 - w)));
                        p[1] = static_cast<std::uint8_t>(std::min(255.0F, cg * 255.0F + p[1] * (1 - w)));
                        p[2] = static_cast<std::uint8_t>(std::min(255.0F, cb * 255.0F + p[2] * (1 - w)));
                        p[3] = static_cast<std::uint8_t>(std::min(255.0F, w * 255.0F + p[3] * (1 - w)));
                    }
                }
            }
        }
    AndroidBitmap_unlockPixels(env, bitmap);
    return JNI_TRUE;
}
