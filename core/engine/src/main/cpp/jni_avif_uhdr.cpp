// JNI surface for AVIF and Ultra HDR (class app.croptop.core.engine.NativeAvifUltraHdrBridge): AVIF probe/decode/encode
// and Ultra HDR JPEG export. Streams follow NativeCodecExtraBridge (chunked writes to a java.io.OutputStream).
#if defined(__ANDROID__)
#include <jni.h>

#include <algorithm>
#include <array>
#include <optional>
#include <stdexcept>
#include <string>
#include <vector>

#include "avif_codec.h"
#include "native_store_handle.h"
#include "ultra_hdr.h"

namespace {

thread_local std::string g_error;
thread_local std::vector<std::uint8_t> g_icc;

std::vector<std::uint8_t> readBytes(JNIEnv* env, jbyteArray array, jint size, const char* what) {
    if (array == nullptr || size <= 0) throw std::invalid_argument(std::string(what) + " is empty");
    if (size > env->GetArrayLength(array)) throw std::invalid_argument(std::string(what) + " length exceeds its byte array");
    std::vector<std::uint8_t> bytes(static_cast<std::size_t>(size));
    env->GetByteArrayRegion(array, 0, size, reinterpret_cast<jbyte*>(bytes.data()));
    return bytes;
}

std::vector<std::uint8_t> optionalBytes(JNIEnv* env, jbyteArray array, jsize limit, const char* what) {
    std::vector<std::uint8_t> out;
    if (array == nullptr) return out;
    const jsize n = env->GetArrayLength(array);
    if (n < 0 || n > limit) throw std::invalid_argument(std::string(what) + " is too large");
    out.resize(static_cast<std::size_t>(n));
    if (n > 0) env->GetByteArrayRegion(array, 0, n, reinterpret_cast<jbyte*>(out.data()));
    return out;
}

/** Writes `data` to the OutputStream in 1 MiB chunks; false when the stream threw (the exception stays pending). */
bool stream(JNIEnv* env, jobject output, const std::vector<std::uint8_t>& data) {
    const auto output_class = env->GetObjectClass(output);
    const auto write_method = env->GetMethodID(output_class, "write", "([BII)V");
    env->DeleteLocalRef(output_class);
    if (write_method == nullptr || env->ExceptionCheck()) throw std::runtime_error("output stream has no write method");
    constexpr std::size_t kChunk = 1U << 20;
    auto buffer = env->NewByteArray(static_cast<jsize>(kChunk));
    if (buffer == nullptr) throw std::runtime_error("could not allocate the output buffer");
    for (std::size_t at = 0; at < data.size(); at += kChunk) {
        const auto n = static_cast<jsize>(std::min(kChunk, data.size() - at));
        env->SetByteArrayRegion(buffer, 0, n, reinterpret_cast<const jbyte*>(data.data() + at));
        env->CallVoidMethod(output, write_method, buffer, 0, n);
        if (env->ExceptionCheck()) {
            env->DeleteLocalRef(buffer);
            return false;
        }
    }
    env->DeleteLocalRef(buffer);
    return true;
}

}  // namespace

extern "C" JNIEXPORT jstring JNICALL Java_app_croptop_core_engine_NativeAvifUltraHdrBridge_nativeLastError(JNIEnv* env, jobject) {
    return env->NewStringUTF(g_error.c_str());
}

extern "C" JNIEXPORT jboolean JNICALL Java_app_croptop_core_engine_NativeAvifUltraHdrBridge_nativeIsAvif(JNIEnv* env, jobject, jbyteArray bytes, jint size) {
    try {
        const auto head = readBytes(env, bytes, std::min<jint>(size, 64), "file");
        return croptop::avif::isAvif(head) ? JNI_TRUE : JNI_FALSE;
    } catch (...) {
        return JNI_FALSE;
    }
}

extern "C" JNIEXPORT jboolean JNICALL Java_app_croptop_core_engine_NativeAvifUltraHdrBridge_nativeIsUltraHdr(JNIEnv* env, jobject, jbyteArray bytes, jint size) {
    try {
        const auto data = readBytes(env, bytes, size, "file");
        return croptop::ultrahdr::isUltraHdr(data) ? JNI_TRUE : JNI_FALSE;
    } catch (...) {
        return JNI_FALSE;
    }
}

/** [width, height, bit depth, has alpha (0/1)], or null (see nativeLastError). */
extern "C" JNIEXPORT jintArray JNICALL Java_app_croptop_core_engine_NativeAvifUltraHdrBridge_nativeAvifProbe(JNIEnv* env, jobject, jbyteArray bytes, jint size) {
    g_error.clear();
    try {
        const auto data = readBytes(env, bytes, size, "AVIF file");
        std::string error;
        const auto info = croptop::avif::probe(data, error);
        if (!info) throw std::invalid_argument(error);
        const std::array<jint, 4> values{static_cast<jint>(info->width), static_cast<jint>(info->height), info->bit_depth, info->has_alpha ? 1 : 0};
        auto result = env->NewIntArray(4);
        if (result != nullptr) env->SetIntArrayRegion(result, 0, 4, values.data());
        return result;
    } catch (const std::exception& e) {
        g_error = e.what();
    } catch (...) {
        g_error = "unknown AVIF probe failure";
    }
    return nullptr;
}

/** Decodes to a new store handle (RGBA8, or RGBA16 for 10/12-bit files) the caller owns; 0 on failure. The ICC profile is kept for nativeTakeIcc. */
extern "C" JNIEXPORT jlong JNICALL Java_app_croptop_core_engine_NativeAvifUltraHdrBridge_nativeAvifDecode(JNIEnv* env, jobject, jbyteArray bytes, jint size) {
    g_error.clear();
    g_icc.clear();
    try {
        const auto data = readBytes(env, bytes, size, "AVIF file");
        std::string error;
        auto decoded = croptop::avif::decode(data, error);
        if (!decoded) throw std::invalid_argument(error);
        g_icc = std::move(decoded->icc_profile);
        return reinterpret_cast<jlong>(new croptop::jni::NativeTileStore(std::move(decoded->pixels)));
    } catch (const std::exception& e) {
        g_error = e.what();
    } catch (...) {
        g_error = "unknown AVIF decode failure";
    }
    return 0;
}

extern "C" JNIEXPORT jbyteArray JNICALL Java_app_croptop_core_engine_NativeAvifUltraHdrBridge_nativeTakeIcc(JNIEnv* env, jobject) {
    auto result = env->NewByteArray(static_cast<jsize>(g_icc.size()));
    if (result != nullptr && !g_icc.empty()) env->SetByteArrayRegion(result, 0, static_cast<jsize>(g_icc.size()), reinterpret_cast<const jbyte*>(g_icc.data()));
    g_icc.clear();
    return result;
}

/** Decodes an Ultra HDR JPEG to a new linear RGBA32F store (1.0 = SDR white) the caller owns; 0 on failure. */
extern "C" JNIEXPORT jlong JNICALL Java_app_croptop_core_engine_NativeAvifUltraHdrBridge_nativeUltraHdrDecode(JNIEnv* env, jobject, jbyteArray bytes, jint size) {
    g_error.clear();
    try {
        const auto data = readBytes(env, bytes, size, "Ultra HDR file");
        std::string error;
        auto decoded = croptop::ultrahdr::decode(data, error);
        if (!decoded) throw std::invalid_argument(error);
        return reinterpret_cast<jlong>(new croptop::jni::NativeTileStore(std::move(*decoded)));
    } catch (const std::exception& e) {
        g_error = e.what();
    } catch (...) {
        g_error = "unknown Ultra HDR decode failure";
    }
    return 0;
}

/** Encodes the store to AVIF and streams it; returns the byte count, or -1 (see nativeLastError; a Java IOException stays pending). */
extern "C" JNIEXPORT jlong JNICALL Java_app_croptop_core_engine_NativeAvifUltraHdrBridge_nativeAvifEncode(JNIEnv* env, jobject, jlong store_handle, jint quality, jint speed,
                                                                                                          jint bit_depth, jbyteArray icc_profile, jint worker_threads,
                                                                                                          jobject output_stream) {
    g_error.clear();
    try {
        const auto* store = reinterpret_cast<const croptop::jni::NativeTileStore*>(store_handle);
        if (store == nullptr || output_stream == nullptr) throw std::invalid_argument("store or output stream is null");
        croptop::avif::EncodeOptions options;
        options.quality = quality;
        options.speed = speed;
        options.bit_depth = bit_depth;
        options.worker_threads = static_cast<unsigned>(std::max(0, worker_threads));
        options.icc_profile = optionalBytes(env, icc_profile, 16 << 20, "ICC profile");
        std::string error;
        const auto encoded = croptop::avif::encode(store->pixels, options, error);
        if (!encoded) throw std::invalid_argument(error);
        if (!stream(env, output_stream, *encoded)) return -1;
        return static_cast<jlong>(encoded->size());
    } catch (const std::exception& e) {
        g_error = e.what();
    } catch (...) {
        g_error = "unknown AVIF encode failure";
    }
    return -1;
}

/** Ultra HDR export of a linear RGBA32F store (`sdr_handle` 0 = let libultrahdr tone-map the base). Same return contract as nativeAvifEncode. */
extern "C" JNIEXPORT jlong JNICALL Java_app_croptop_core_engine_NativeAvifUltraHdrBridge_nativeUltraHdrEncode(JNIEnv* env, jobject, jlong hdr_handle, jlong sdr_handle,
                                                                                                             jint base_quality, jint gain_map_quality, jboolean multi_channel,
                                                                                                             jfloat peak_nits, jbyteArray exif, jobject output_stream) {
    g_error.clear();
    try {
        const auto* hdr = reinterpret_cast<const croptop::jni::NativeTileStore*>(hdr_handle);
        const auto* sdr = reinterpret_cast<const croptop::jni::NativeTileStore*>(sdr_handle);
        if (hdr == nullptr || output_stream == nullptr) throw std::invalid_argument("store or output stream is null");
        croptop::ultrahdr::EncodeOptions options;
        options.base_quality = base_quality;
        options.gain_map_quality = gain_map_quality;
        options.multi_channel_gain_map = multi_channel == JNI_TRUE;
        options.target_peak_nits = peak_nits;
        options.exif = optionalBytes(env, exif, 1 << 20, "EXIF block");
        std::string error;
        const auto encoded = croptop::ultrahdr::encode(hdr->pixels, sdr != nullptr ? &sdr->pixels : nullptr, options, error);
        if (!encoded) throw std::invalid_argument(error);
        if (!stream(env, output_stream, *encoded)) return -1;
        return static_cast<jlong>(encoded->size());
    } catch (const std::exception& e) {
        g_error = e.what();
    } catch (...) {
        g_error = "unknown Ultra HDR encode failure";
    }
    return -1;
}
#endif  // __ANDROID__
