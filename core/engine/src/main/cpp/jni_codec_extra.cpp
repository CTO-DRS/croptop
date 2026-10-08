// JNI surface for TIFF / PSD import and export (class app.croptop.core.engine.NativeCodecExtraBridge).
// Mirrors nativeDecodeToTileStore: probe the header, create the document's store, decode into it.
#if defined(__ANDROID__)
#include <jni.h>

#include <algorithm>
#include <array>
#include <limits>
#include <optional>
#include <stdexcept>
#include <string>
#include <vector>

#include "codec_extra.h"
#include "native_store_handle.h"

namespace {

thread_local std::string g_codec_extra_error;
thread_local std::vector<std::uint8_t> g_codec_extra_icc;

constexpr std::size_t kMaxIccProfileBytes = 16U << 20U;

/** Bounded view of the first `size` bytes of a Java byte array (copied: the array stays unpinned). */
std::vector<std::uint8_t> readBytes(JNIEnv* env, jbyteArray array, jint size) {
    if (array == nullptr || size <= 0) throw std::invalid_argument("encoded image is empty");
    if (size > env->GetArrayLength(array)) throw std::invalid_argument("encoded image length exceeds its byte array");
    std::vector<std::uint8_t> bytes(static_cast<std::size_t>(size));
    env->GetByteArrayRegion(array, 0, size, reinterpret_cast<jbyte*>(bytes.data()));
    return bytes;
}

jint formatCode(croptop::PixelFormat f) { return f == croptop::PixelFormat::RGBA32F ? 32 : f == croptop::PixelFormat::RGBA16 ? 16 : 8; }

}  // namespace

extern "C" JNIEXPORT jstring JNICALL Java_app_croptop_core_engine_NativeCodecExtraBridge_nativeLastError(JNIEnv* env, jobject) {
    return env->NewStringUTF(g_codec_extra_error.c_str());
}

extern "C" JNIEXPORT jbyteArray JNICALL Java_app_croptop_core_engine_NativeCodecExtraBridge_nativeDecodedIccProfile(JNIEnv* env, jobject) {
    if (g_codec_extra_icc.empty()) return nullptr;
    if (g_codec_extra_icc.size() > kMaxIccProfileBytes || g_codec_extra_icc.size() > static_cast<std::size_t>(std::numeric_limits<jsize>::max())) {
        g_codec_extra_error = "decoded ICC profile exceeds the 16 MiB limit";
        return nullptr;
    }
    auto result = env->NewByteArray(static_cast<jsize>(g_codec_extra_icc.size()));
    if (result == nullptr) return nullptr;
    env->SetByteArrayRegion(result, 0, static_cast<jsize>(g_codec_extra_icc.size()), reinterpret_cast<const jbyte*>(g_codec_extra_icc.data()));
    return env->ExceptionCheck() ? nullptr : result;
}

/** [kind (1 TIFF, 2 PSD), width, height, bit depth (8, 16, 32 = float)], or null (see nativeLastError). */
extern "C" JNIEXPORT jintArray JNICALL Java_app_croptop_core_engine_NativeCodecExtraBridge_nativeProbe(JNIEnv* env, jobject, jbyteArray encoded, jint size) {
    g_codec_extra_error.clear();
    try {
        // Headers are small; a TIFF's first directory may sit anywhere, so probe the whole buffer.
        const auto bytes = readBytes(env, encoded, size);
        std::string error;
        const auto p = croptop::codec::probe(bytes, error);
        if (!p) throw std::invalid_argument(error);
        const std::array<jint, 4> values{static_cast<jint>(p->format), static_cast<jint>(p->width), static_cast<jint>(p->height), formatCode(p->pixel_format)};
        auto result = env->NewIntArray(4);
        if (result != nullptr) env->SetIntArrayRegion(result, 0, 4, values.data());
        return result;
    } catch (const std::exception& e) {
        g_codec_extra_error = e.what();
    } catch (...) {
        g_codec_extra_error = "unknown TIFF/PSD probe failure";
    }
    return nullptr;
}

/**
 * Decodes into the NativeTileStore behind `store_handle` (created at the probed size and depth). Returns null
 * on success, else the error message. Non-fatal notes are joined into `nativeLastError()` after success.
 */
extern "C" JNIEXPORT jstring JNICALL Java_app_croptop_core_engine_NativeCodecExtraBridge_nativeDecode(JNIEnv* env, jobject, jbyteArray encoded, jint size,
                                                                                                     jlong store_handle) {
    g_codec_extra_error.clear();
    g_codec_extra_icc.clear();
    std::string error;
    try {
        auto* store = reinterpret_cast<croptop::jni::NativeTileStore*>(store_handle);
        if (store == nullptr) throw std::invalid_argument("destination store handle is null");
        const auto bytes = readBytes(env, encoded, size);
        std::vector<std::string> warnings;
        std::vector<std::uint8_t> icc_profile;
        const auto result = croptop::codec::decodeToTiles(bytes, store->pixels, &warnings, &icc_profile);
        if (!result.success) {
            error = result.error.empty() ? "TIFF/PSD decoding failed" : result.error;
        } else {
            if (icc_profile.size() > kMaxIccProfileBytes) throw std::invalid_argument("decoded ICC profile exceeds the 16 MiB limit");
            g_codec_extra_icc = std::move(icc_profile);
            for (const auto& w : warnings) g_codec_extra_error += (g_codec_extra_error.empty() ? "" : "\n") + w;
        }
    } catch (const std::exception& e) {
        error = e.what();
    } catch (...) {
        error = "unknown TIFF/PSD decoder failure";
    }
    return error.empty() ? nullptr : env->NewStringUTF(error.c_str());
}

/**
 * Encodes the store behind `store_handle` (kind 1 TIFF, 2 flattened PSD) and streams it to `output_stream`
 * (java.io.OutputStream) in 1 MiB chunks. Returns the byte count, or -1 (see nativeLastError).
 */
extern "C" JNIEXPORT jlong JNICALL Java_app_croptop_core_engine_NativeCodecExtraBridge_nativeEncode(JNIEnv* env, jobject, jlong store_handle, jint kind,
                                                                                                   jbyteArray icc_profile, jboolean deflate, jobject output_stream) {
    g_codec_extra_error.clear();
    try {
        const auto* store = reinterpret_cast<const croptop::jni::NativeTileStore*>(store_handle);
        if (store == nullptr || output_stream == nullptr) throw std::invalid_argument("store or output stream is null");
        std::vector<std::uint8_t> icc;
        if (icc_profile != nullptr) {
            const jsize n = env->GetArrayLength(icc_profile);
            if (n < 0 || n > (16 << 20)) throw std::invalid_argument("ICC profile is too large");
            icc.resize(static_cast<std::size_t>(n));
            if (n > 0) env->GetByteArrayRegion(icc_profile, 0, n, reinterpret_cast<jbyte*>(icc.data()));
        }
        std::string error;
        std::optional<std::vector<std::uint8_t>> encoded;
        if (kind == 1) encoded = croptop::codec::encodeTiff(store->pixels, icc, deflate == JNI_TRUE, error);
        else if (kind == 2) encoded = croptop::codec::encodePsd(store->pixels, icc, error);
        else throw std::invalid_argument("export kind must be 1 (TIFF) or 2 (PSD)");
        if (!encoded) throw std::invalid_argument(error);
        const auto output_class = env->GetObjectClass(output_stream);
        const auto write_method = env->GetMethodID(output_class, "write", "([BII)V");
        env->DeleteLocalRef(output_class);
        if (write_method == nullptr || env->ExceptionCheck()) throw std::runtime_error("output stream has no write method");
        constexpr std::size_t kChunk = 1U << 20;
        auto buffer = env->NewByteArray(static_cast<jsize>(kChunk));
        if (buffer == nullptr) throw std::runtime_error("could not allocate the output buffer");
        for (std::size_t at = 0; at < encoded->size(); at += kChunk) {
            const auto n = static_cast<jsize>(std::min(kChunk, encoded->size() - at));
            env->SetByteArrayRegion(buffer, 0, n, reinterpret_cast<const jbyte*>(encoded->data() + at));
            env->CallVoidMethod(output_stream, write_method, buffer, 0, n);
            if (env->ExceptionCheck()) {
                env->DeleteLocalRef(buffer);
                return -1;  // the Java IOException propagates to the caller
            }
        }
        env->DeleteLocalRef(buffer);
        return static_cast<jlong>(encoded->size());
    } catch (const std::exception& e) {
        g_codec_extra_error = e.what();
    } catch (...) {
        g_codec_extra_error = "unknown TIFF/PSD encoder failure";
    }
    return -1;
}

namespace {

croptop::BlendMode blendFromKey(const std::string& key) {
    static const std::pair<const char*, croptop::BlendMode> kKeys[] = {
        {"norm", croptop::BlendMode::Normal},      {"diss", croptop::BlendMode::Dissolve},    {"dark", croptop::BlendMode::Darken},
        {"mul ", croptop::BlendMode::Multiply},    {"idiv", croptop::BlendMode::ColorBurn},   {"lbrn", croptop::BlendMode::LinearBurn},
        {"dkCl", croptop::BlendMode::DarkerColor}, {"lite", croptop::BlendMode::Lighten},     {"scrn", croptop::BlendMode::Screen},
        {"div ", croptop::BlendMode::ColorDodge},  {"lddg", croptop::BlendMode::LinearDodge}, {"lgCl", croptop::BlendMode::LighterColor},
        {"over", croptop::BlendMode::Overlay},     {"sLit", croptop::BlendMode::SoftLight},   {"hLit", croptop::BlendMode::HardLight},
        {"vLit", croptop::BlendMode::VividLight},  {"lLit", croptop::BlendMode::LinearLight}, {"pLit", croptop::BlendMode::PinLight},
        {"hMix", croptop::BlendMode::HardMix},     {"diff", croptop::BlendMode::Difference},  {"smud", croptop::BlendMode::Exclusion},
        {"fsub", croptop::BlendMode::Subtract},    {"fdiv", croptop::BlendMode::Divide},      {"hue ", croptop::BlendMode::Hue},
        {"sat ", croptop::BlendMode::Saturation},  {"colr", croptop::BlendMode::Color},       {"lum ", croptop::BlendMode::Luminosity}};
    for (const auto& [k, mode] : kKeys)
        if (key == k) return mode;
    return croptop::BlendMode::Normal;
}

std::string jstring8(JNIEnv* env, jobjectArray array, jsize i) {
    auto s = static_cast<jstring>(env->GetObjectArrayElement(array, i));
    if (s == nullptr) return {};
    const char* chars = env->GetStringUTFChars(s, nullptr);
    std::string out = chars != nullptr ? chars : "";
    if (chars != nullptr) env->ReleaseStringUTFChars(s, chars);
    env->DeleteLocalRef(s);
    return out;
}

}  // namespace

/**
 * Layered PSD export. Records are in file order (bottom to top): kind 0 = pixel layer (store handle, its attached
 * mask and clipping are exported), 1 = group start (divider), 2 = group end (folder: name, blend key, open flag).
 * `composite` is the merged image. Streams to `output_stream`; returns the byte count or -1 (see nativeLastError).
 */
extern "C" JNIEXPORT jlong JNICALL Java_app_croptop_core_engine_NativeCodecExtraBridge_nativeEncodePsdLayered(
    JNIEnv* env, jobject, jlongArray stores, jintArray kinds, jobjectArray names, jobjectArray blend_keys, jintArray opacities, jintArray fills,
    jbooleanArray flags, jobjectArray extra_keys, jobjectArray extra_data, jlong composite_handle, jbyteArray icc_profile, jobject output_stream) {
    g_codec_extra_error.clear();
    try {
        const auto* composite = reinterpret_cast<const croptop::jni::NativeTileStore*>(composite_handle);
        if (stores == nullptr || kinds == nullptr || names == nullptr || blend_keys == nullptr || opacities == nullptr || fills == nullptr ||
            flags == nullptr || composite == nullptr || output_stream == nullptr) {
            throw std::invalid_argument("layered PSD arguments are missing");
        }
        const jsize n = env->GetArrayLength(stores);
        if (n <= 0 || n > 10000 || env->GetArrayLength(kinds) != n || env->GetArrayLength(names) != n || env->GetArrayLength(blend_keys) != n ||
            env->GetArrayLength(opacities) != n || env->GetArrayLength(fills) != n || env->GetArrayLength(flags) != n * 4 ||
            (extra_keys != nullptr && env->GetArrayLength(extra_keys) != n) || (extra_data != nullptr && env->GetArrayLength(extra_data) != n)) {
            throw std::invalid_argument("layered PSD arrays must have one entry per record");
        }
        std::vector<jlong> handles(static_cast<std::size_t>(n));
        std::vector<jint> kind(static_cast<std::size_t>(n)), opacity(static_cast<std::size_t>(n)), fill(static_cast<std::size_t>(n));
        std::vector<jboolean> flag(static_cast<std::size_t>(n) * 4);  // visible, clipped, pass-through, group open
        env->GetLongArrayRegion(stores, 0, n, handles.data());
        env->GetIntArrayRegion(kinds, 0, n, kind.data());
        env->GetIntArrayRegion(opacities, 0, n, opacity.data());
        env->GetIntArrayRegion(fills, 0, n, fill.data());
        env->GetBooleanArrayRegion(flags, 0, n * 4, flag.data());
        std::vector<croptop::psd::Layer> layers;
        layers.reserve(static_cast<std::size_t>(n));
        for (jsize i = 0; i < n; ++i) {
            const auto k = static_cast<std::size_t>(i);
            croptop::psd::Layer layer;
            layer.name = jstring8(env, names, i);
            layer.mode = blendFromKey(jstring8(env, blend_keys, i));
            layer.opacity = static_cast<std::uint8_t>(std::clamp<jint>(opacity[k], 0, 255));
            layer.fill = static_cast<std::uint8_t>(std::clamp<jint>(fill[k], 0, 255));
            layer.visible = flag[k * 4] == JNI_TRUE;
            layer.clipped = flag[k * 4 + 1] == JNI_TRUE;
            layer.pass_through = flag[k * 4 + 2] == JNI_TRUE;
            layer.group_open = flag[k * 4 + 3] == JNI_TRUE;
            if (kind[k] == 1) {
                layer.kind = croptop::psd::LayerKind::GroupDivider;
                layer.name = "</Layer group>";
            } else if (kind[k] == 2) {
                layer.kind = croptop::psd::LayerKind::GroupFolder;
            } else if (kind[k] == 3) {
                // Adjustment layer: no pixels, its settings travel in the extra block; the store (if any) supplies the mask.
                const auto* store = reinterpret_cast<const croptop::jni::NativeTileStore*>(handles[k]);
                if (store != nullptr && store->mask != nullptr) {
                    layer.mask.emplace(croptop::psd::Mask{store->mask->maskPixels(),
                                                          static_cast<std::uint8_t>(store->mask->emptyCoverage() >= 0.5F ? 255 : 0), false, true});
                }
            } else {
                const auto* store = reinterpret_cast<const croptop::jni::NativeTileStore*>(handles[k]);
                if (store == nullptr) throw std::invalid_argument("a pixel layer has no store");
                layer.pixels.emplace(store->pixels);
                {
                    std::size_t at = 0;
                    const auto put = [&](const croptop::BlendIfRange& r) {
                        for (const auto value : {r.black_lo, r.black_hi, r.white_lo, r.white_hi}) layer.blend_ranges[at++] = value;
                    };
                    put(store->blend_if.self);
                    put(store->blend_if.under);
                    for (std::size_t c = 0; c < 3; ++c) {
                        put(store->blend_if.channel_self[c]);
                        put(store->blend_if.channel_under[c]);
                    }
                }
                layer.right = static_cast<std::int32_t>(store->pixels.width());
                layer.bottom = static_cast<std::int32_t>(store->pixels.height());
                if (store->mask != nullptr) {
                    layer.mask.emplace(croptop::psd::Mask{store->mask->maskPixels(),
                                                          static_cast<std::uint8_t>(store->mask->emptyCoverage() >= 0.5F ? 255 : 0), false, true});
                }
            }
            if (extra_keys != nullptr && extra_data != nullptr) {
                auto key = static_cast<jstring>(env->GetObjectArrayElement(extra_keys, i));
                auto data = static_cast<jbyteArray>(env->GetObjectArrayElement(extra_data, i));
                if (key != nullptr && data != nullptr) {
                    croptop::psd::AdditionalInfo info;
                    info.key = jstring8(env, extra_keys, i);
                    if (info.key.size() != 4) throw std::invalid_argument("extra block keys have four characters");
                    const jsize m = env->GetArrayLength(data);
                    if (m < 0 || m > (1 << 20)) throw std::invalid_argument("extra block is too large");
                    info.data.resize(static_cast<std::size_t>(m));
                    if (m > 0) env->GetByteArrayRegion(data, 0, m, reinterpret_cast<jbyte*>(info.data.data()));
                    layer.extra.push_back(std::move(info));
                }
                if (key != nullptr) env->DeleteLocalRef(key);
                if (data != nullptr) env->DeleteLocalRef(data);
            }
            layers.push_back(std::move(layer));
        }
        std::vector<std::uint8_t> icc;
        if (icc_profile != nullptr) {
            const jsize m = env->GetArrayLength(icc_profile);
            if (m < 0 || m > (16 << 20)) throw std::invalid_argument("ICC profile is too large");
            icc.resize(static_cast<std::size_t>(m));
            if (m > 0) env->GetByteArrayRegion(icc_profile, 0, m, reinterpret_cast<jbyte*>(icc.data()));
        }
        std::string error;
        auto encoded = croptop::codec::encodePsdLayered(std::move(layers), composite->pixels, icc, error);
        if (!encoded) throw std::invalid_argument(error);
        const auto output_class = env->GetObjectClass(output_stream);
        const auto write_method = env->GetMethodID(output_class, "write", "([BII)V");
        env->DeleteLocalRef(output_class);
        if (write_method == nullptr || env->ExceptionCheck()) throw std::runtime_error("output stream has no write method");
        constexpr std::size_t kChunk = 1U << 20;
        auto buffer = env->NewByteArray(static_cast<jsize>(kChunk));
        if (buffer == nullptr) throw std::runtime_error("could not allocate the output buffer");
        for (std::size_t at = 0; at < encoded->size(); at += kChunk) {
            const auto len = static_cast<jsize>(std::min(kChunk, encoded->size() - at));
            env->SetByteArrayRegion(buffer, 0, len, reinterpret_cast<const jbyte*>(encoded->data() + at));
            env->CallVoidMethod(output_stream, write_method, buffer, 0, len);
            if (env->ExceptionCheck()) {
                env->DeleteLocalRef(buffer);
                return -1;
            }
        }
        env->DeleteLocalRef(buffer);
        return static_cast<jlong>(encoded->size());
    } catch (const std::exception& e) {
        g_codec_extra_error = e.what();
    }
    return -1;
}
#endif
