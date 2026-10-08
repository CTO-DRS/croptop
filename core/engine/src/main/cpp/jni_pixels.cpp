// JNI for moving pixels in and out of the engine from outside (model output, AI features): a coarse model mask
// becomes a full-resolution selection mask by guided upsampling, and model RGBA output is written into a layer (scaled to the
// target region, optionally through a coverage mask). Integer formats take the bytes as encoded sRGB; float layers are linear.
#include <jni.h>

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <memory>
#include <stdexcept>
#include <string>
#include <vector>

#include "mask_upsample.h"
#include "native_store_handle.h"
#include "pixel_plane.h"

namespace {

thread_local std::string g_pixel_error;

float srgbToLinear(float v) { return v <= 0.04045F ? v / 12.92F : std::pow((v + 0.055F) / 1.055F, 2.4F); }

/** Bilinear sample of an interleaved 8-bit image (channels per pixel), output 0–1. */
void sample(const std::uint8_t* data, int w, int h, int channels, double x, double y, float* out) {
    const double fx = std::clamp(x - 0.5, 0.0, static_cast<double>(w - 1));
    const double fy = std::clamp(y - 0.5, 0.0, static_cast<double>(h - 1));
    const int x0 = static_cast<int>(fx), y0 = static_cast<int>(fy);
    const int x1 = std::min(x0 + 1, w - 1), y1 = std::min(y0 + 1, h - 1);
    const float tx = static_cast<float>(fx - x0), ty = static_cast<float>(fy - y0);
    for (int c = 0; c < channels; ++c) {
        const auto at = [&](int xx, int yy) { return data[(static_cast<std::size_t>(yy) * w + xx) * channels + c] / 255.0F; };
        const float top = at(x0, y0) * (1 - tx) + at(x1, y0) * tx;
        const float bottom = at(x0, y1) * (1 - tx) + at(x1, y1) * tx;
        out[c] = top * (1 - ty) + bottom * ty;
    }
}

}  // namespace

extern "C" JNIEXPORT jstring JNICALL Java_app_croptop_core_engine_NativePixelBridge_lastError(JNIEnv* env, jobject) {
    return env->NewStringUTF(g_pixel_error.c_str());
}

/** A16 selection mask at the guide's size from an 8-bit coarse mask (`lowWidth` × `lowHeight`), edges following the guide. */
extern "C" JNIEXPORT jlong JNICALL Java_app_croptop_core_engine_NativePixelBridge_guidedUpsample(JNIEnv* env, jobject, jlong guide_handle, jint low_width,
                                                                                               jint low_height, jbyteArray alpha, jint radius,
                                                                                               jfloat epsilon) {
    g_pixel_error.clear();
    try {
        const auto* guide = reinterpret_cast<const croptop::jni::NativeTileStore*>(guide_handle);
        if (guide == nullptr || alpha == nullptr || low_width <= 0 || low_height <= 0) throw std::invalid_argument("guided upsample arguments are missing");
        if (env->GetArrayLength(alpha) != low_width * low_height) throw std::invalid_argument("coarse mask size does not match its bytes");
        if (static_cast<std::uint32_t>(low_width) > guide->pixels.width() || static_cast<std::uint32_t>(low_height) > guide->pixels.height()) {
            throw std::invalid_argument("the coarse mask must not be larger than the image");
        }
        std::vector<jbyte> bytes(static_cast<std::size_t>(low_width) * low_height);
        env->GetByteArrayRegion(alpha, 0, static_cast<jsize>(bytes.size()), bytes.data());
        croptop::TileStore low(croptop::PixelFormat::A16, static_cast<std::uint32_t>(low_width), static_cast<std::uint32_t>(low_height));
        for (std::uint32_t ty = 0; ty * croptop::kTileEdge < low.height(); ++ty) {
            for (std::uint32_t tx = 0; tx * croptop::kTileEdge < low.width(); ++tx) {
                croptop::TileBuffer tile(low.tile_byte_size(), 0);
                for (std::uint32_t y = 0; y < croptop::kTileEdge; ++y) {
                    for (std::uint32_t x = 0; x < croptop::kTileEdge; ++x) {
                        const auto gx = tx * croptop::kTileEdge + x, gy = ty * croptop::kTileEdge + y;
                        if (gx >= low.width() || gy >= low.height()) continue;
                        const auto v = static_cast<std::uint16_t>(static_cast<std::uint8_t>(bytes[static_cast<std::size_t>(gy) * low.width() + gx]) * 257U);
                        std::memcpy(tile.data() + (static_cast<std::size_t>(y) * croptop::kTileEdge + x) * 2U, &v, 2U);
                    }
                }
                if (!low.writeTile({static_cast<std::int32_t>(tx), static_cast<std::int32_t>(ty)}, tile)) throw std::runtime_error("could not store the coarse mask");
            }
        }
        croptop::selection::GuidedUpsampleSpec spec;
        spec.radius = static_cast<std::uint32_t>(std::clamp<jint>(radius, 0, 64));
        spec.epsilon = epsilon > 0.0F ? epsilon : spec.epsilon;
        auto out = croptop::selection::guidedUpsample(guide->pixels, low, spec, g_pixel_error);
        if (!out) return 0;
        return reinterpret_cast<jlong>(new croptop::jni::NativeTileStore(std::move(*out)));
    } catch (const std::exception& e) {
        g_pixel_error = e.what();
    }
    return 0;
}

/**
 * Writes `rgba` (srcWidth × srcHeight, straight alpha, 8-bit) over the region (left, top, width, height) of the layer store,
 * scaled bilinearly. `coverage` (optional, srcWidth × srcHeight bytes) is how much of the new pixel replaces the old one;
 * without it the new pixel replaces the old one outright. Returns false with lastError on failure.
 */
extern "C" JNIEXPORT jboolean JNICALL Java_app_croptop_core_engine_NativePixelBridge_writeRegion(JNIEnv* env, jobject, jlong store_handle, jint left, jint top,
                                                                                                jint width, jint height, jint src_width, jint src_height,
                                                                                                jbyteArray rgba, jbyteArray coverage) {
    g_pixel_error.clear();
    try {
        auto* store = reinterpret_cast<croptop::jni::NativeTileStore*>(store_handle);
        if (store == nullptr || rgba == nullptr || width <= 0 || height <= 0 || src_width <= 0 || src_height <= 0) throw std::invalid_argument("pixel write arguments are missing");
        if (!croptop::paint::detail::isRgba(store->pixels.format())) throw std::invalid_argument("pixels can only be written into an RGBA layer");
        const auto expected = static_cast<std::size_t>(src_width) * src_height;
        if (static_cast<std::size_t>(env->GetArrayLength(rgba)) != expected * 4U) throw std::invalid_argument("pixel data does not match its size");
        std::vector<jbyte> pixels(expected * 4U);
        env->GetByteArrayRegion(rgba, 0, static_cast<jsize>(pixels.size()), pixels.data());
        std::vector<jbyte> cover;
        if (coverage != nullptr) {
            if (static_cast<std::size_t>(env->GetArrayLength(coverage)) != expected) throw std::invalid_argument("coverage does not match the pixel data");
            cover.resize(expected);
            env->GetByteArrayRegion(coverage, 0, static_cast<jsize>(cover.size()), cover.data());
        }
        const auto& format = store->pixels.format();
        const bool linear = format == croptop::PixelFormat::RGBA32F;
        const std::int64_t x0 = std::max<std::int64_t>(left, 0), y0 = std::max<std::int64_t>(top, 0);
        const std::int64_t x1 = std::min<std::int64_t>(static_cast<std::int64_t>(left) + width, store->pixels.width());
        const std::int64_t y1 = std::min<std::int64_t>(static_cast<std::int64_t>(top) + height, store->pixels.height());
        if (x0 >= x1 || y0 >= y1) return JNI_TRUE;  // entirely outside the layer
        const auto bpp = store->pixels.bytes_per_pixel();
        for (std::int64_t ty = y0 / croptop::kTileEdge; ty * static_cast<std::int64_t>(croptop::kTileEdge) < y1; ++ty) {
            for (std::int64_t tx = x0 / croptop::kTileEdge; tx * static_cast<std::int64_t>(croptop::kTileEdge) < x1; ++tx) {
                const croptop::TileCoordinate coordinate{static_cast<std::int32_t>(tx), static_cast<std::int32_t>(ty)};
                const auto existing = store->pixels.readTile(coordinate);
                croptop::TileBuffer tile = existing ? *existing : croptop::TileBuffer(store->pixels.tile_byte_size(), 0);
                for (std::int64_t ly = 0; ly < static_cast<std::int64_t>(croptop::kTileEdge); ++ly) {
                    const std::int64_t gy = ty * croptop::kTileEdge + ly;
                    if (gy < y0 || gy >= y1) continue;
                    for (std::int64_t lx = 0; lx < static_cast<std::int64_t>(croptop::kTileEdge); ++lx) {
                        const std::int64_t gx = tx * croptop::kTileEdge + lx;
                        if (gx < x0 || gx >= x1) continue;
                        const double u = (static_cast<double>(gx - left) + 0.5) * src_width / width;
                        const double v = (static_cast<double>(gy - top) + 0.5) * src_height / height;
                        float incoming[4];
                        sample(reinterpret_cast<const std::uint8_t*>(pixels.data()), src_width, src_height, 4, u, v, incoming);
                        float weight = 1.0F;
                        if (!cover.empty()) {
                            sample(reinterpret_cast<const std::uint8_t*>(cover.data()), src_width, src_height, 1, u, v, &weight);
                        }
                        if (weight <= 0.0F) continue;
                        if (linear) {
                            for (int c = 0; c < 3; ++c) incoming[c] = srgbToLinear(incoming[c]);
                        }
                        auto* p = tile.data() + (static_cast<std::size_t>(ly) * croptop::kTileEdge + lx) * bpp;
                        float current[4];
                        croptop::paint::detail::readPixel(format, p, current);
                        float mixed[4];
                        // Premultiplied mix so a transparent old pixel does not tint the new colour.
                        const float ca = current[3], ia = incoming[3];
                        mixed[3] = ca + weight * (ia - ca);
                        for (int c = 0; c < 3; ++c) {
                            const float premultiplied = current[c] * ca + weight * (incoming[c] * ia - current[c] * ca);
                            mixed[c] = mixed[3] > 1e-6F ? premultiplied / mixed[3] : incoming[c];
                        }
                        croptop::paint::detail::writePixel(format, p, mixed);
                    }
                }
                if (!store->pixels.writeTile(coordinate, tile)) throw std::runtime_error("could not store the pixel tile");
            }
        }
        return JNI_TRUE;
    } catch (const std::exception& e) {
        g_pixel_error = e.what();
    }
    return JNI_FALSE;
}

/** Coverage (A16 store → 0–255 bytes, row by row) of the rectangle (left, top, width, height); 0 outside the store. Null on failure. */
extern "C" JNIEXPORT jbyteArray JNICALL Java_app_croptop_core_engine_NativePixelBridge_readCoverage(JNIEnv* env, jobject, jlong store_handle, jint left, jint top,
                                                                                                   jint width, jint height) {
    g_pixel_error.clear();
    try {
        const auto* store = reinterpret_cast<const croptop::jni::NativeTileStore*>(store_handle);
        if (store == nullptr || width <= 0 || height <= 0 || static_cast<std::int64_t>(width) * height > (1 << 24)) throw std::invalid_argument("coverage read arguments are invalid");
        if (store->pixels.format() != croptop::PixelFormat::A16) throw std::invalid_argument("coverage can only be read from an A16 mask");
        const auto coverage = croptop::paint::detail::readCoverage(store->pixels, left, top, width, height);
        std::vector<jbyte> bytes(coverage.size());
        for (std::size_t i = 0; i < coverage.size(); ++i) bytes[i] = static_cast<jbyte>((static_cast<std::uint32_t>(coverage[i]) * 255U + 32767U) / 65535U);
        auto out = env->NewByteArray(static_cast<jsize>(bytes.size()));
        if (out == nullptr) return nullptr;
        env->SetByteArrayRegion(out, 0, static_cast<jsize>(bytes.size()), bytes.data());
        return out;
    } catch (const std::exception& e) {
        g_pixel_error = e.what();
    }
    return nullptr;
}
