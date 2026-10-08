// JNI lifecycle for Little CMS profiles and the reusable color readout transform.
#if defined(__ANDROID__)
#include <jni.h>

#include <array>
#include <cstdint>
#include <limits>
#include <memory>
#include <stdexcept>
#include <string>
#include <vector>

#include "color_management.h"
#include "native_store_handle.h"

namespace {

thread_local std::string g_color_error;

using Profile = croptop::cms::Profile;
using Readout = croptop::cms::ColorReadout;
using NativeTileStore = croptop::jni::NativeTileStore;

const NativeTileStore& storeOf(jlong handle) {
    const auto* store = reinterpret_cast<const NativeTileStore*>(handle);
    if (store == nullptr) throw std::invalid_argument("source TileStore handle is null");
    return *store;
}

Profile* profileOf(jlong handle) {
    auto* profile = reinterpret_cast<Profile*>(handle);
    if (profile == nullptr) throw std::invalid_argument("ICC profile handle is null");
    return profile;
}

jlong ownProfile(Profile profile) {
    if (profile.handle() == nullptr) throw std::runtime_error("Little CMS could not allocate the profile");
    return reinterpret_cast<jlong>(new Profile(std::move(profile)));
}

template <typename Fn>
jlong guarded(const char* fallback, Fn&& fn) {
    g_color_error.clear();
    try {
        return fn();
    } catch (const std::exception& error) {
        g_color_error = error.what();
    } catch (...) {
        g_color_error = fallback;
    }
    return 0;
}

std::vector<std::uint8_t> bytesOf(JNIEnv* env, jbyteArray input) {
    if (input == nullptr) throw std::invalid_argument("ICC profile bytes are null");
    const jsize size = env->GetArrayLength(input);
    if (size <= 0 || size > (64 << 20)) throw std::invalid_argument("ICC profile size must be between 1 byte and 64 MiB");
    std::vector<std::uint8_t> bytes(static_cast<std::size_t>(size));
    env->GetByteArrayRegion(input, 0, size, reinterpret_cast<jbyte*>(bytes.data()));
    if (env->ExceptionCheck()) throw std::runtime_error("Could not read ICC profile bytes");
    return bytes;
}

}  // namespace

extern "C" JNIEXPORT jstring JNICALL Java_app_croptop_core_engine_NativeColorManagementBridge_nativeLastError(JNIEnv* env, jobject) {
    return env->NewStringUTF(g_color_error.c_str());
}

extern "C" JNIEXPORT jlong JNICALL Java_app_croptop_core_engine_NativeColorManagementBridge_nativeCreateBuiltinProfile(JNIEnv*, jobject, jint space, jboolean linear) {
    return guarded("built-in profile failure", [&]() -> jlong {
        if (space < 0 || space > static_cast<int>(croptop::cms::WorkingSpace::Rec2020)) throw std::invalid_argument("unknown built-in color space");
        auto value = static_cast<croptop::cms::WorkingSpace>(space);
        if (linear == JNI_TRUE) {
            if (value == croptop::cms::WorkingSpace::SRGB) value = croptop::cms::WorkingSpace::LinearSRGB;
            else if (value == croptop::cms::WorkingSpace::DisplayP3) value = croptop::cms::WorkingSpace::LinearP3;
            else throw std::invalid_argument("linear profiles are available only for sRGB and Display P3");
        }
        return ownProfile(Profile::builtin(value));
    });
}

extern "C" JNIEXPORT jlong JNICALL Java_app_croptop_core_engine_NativeColorManagementBridge_nativeCreateIccProfile(JNIEnv* env, jobject, jbyteArray input) {
    return guarded("ICC profile parse failure", [&]() -> jlong {
        const auto bytes = bytesOf(env, input);
        std::string error;
        auto profile = Profile::fromIcc(bytes, error);
        if (!profile) throw std::invalid_argument(error.empty() ? "Invalid ICC profile" : error);
        return ownProfile(std::move(*profile));
    });
}

extern "C" JNIEXPORT jlong JNICALL Java_app_croptop_core_engine_NativeColorManagementBridge_nativeCreateGenericCmykProfile(JNIEnv*, jobject, jfloat ink) {
    return guarded("CMYK profile failure", [&]() -> jlong { return ownProfile(Profile::genericCmyk(ink)); });
}

extern "C" JNIEXPORT void JNICALL Java_app_croptop_core_engine_NativeColorManagementBridge_nativeDestroyProfile(JNIEnv*, jobject, jlong handle) {
    delete reinterpret_cast<Profile*>(handle);
}

extern "C" JNIEXPORT jstring JNICALL Java_app_croptop_core_engine_NativeColorManagementBridge_nativeProfileDescription(JNIEnv* env, jobject, jlong handle) {
    try {
        g_color_error.clear();
        return env->NewStringUTF(profileOf(handle)->description().c_str());
    } catch (const std::exception& error) {
        g_color_error = error.what();
        return env->NewStringUTF("");
    }
}

extern "C" JNIEXPORT jint JNICALL Java_app_croptop_core_engine_NativeColorManagementBridge_nativeProfileModel(JNIEnv*, jobject, jlong handle) {
    try {
        g_color_error.clear();
        return static_cast<jint>(profileOf(handle)->model());
    } catch (const std::exception& error) {
        g_color_error = error.what();
        return static_cast<jint>(croptop::cms::ColorModel::Other);
    }
}

extern "C" JNIEXPORT jbyteArray JNICALL Java_app_croptop_core_engine_NativeColorManagementBridge_nativeProfileIccBytes(JNIEnv* env, jobject, jlong handle) {
    try {
        g_color_error.clear();
        const auto bytes = profileOf(handle)->iccBytes();
        if (bytes.size() > static_cast<std::size_t>(std::numeric_limits<jsize>::max())) throw std::length_error("ICC profile is too large for JNI");
        auto result = env->NewByteArray(static_cast<jsize>(bytes.size()));
        if (result != nullptr && !bytes.empty()) env->SetByteArrayRegion(result, 0, static_cast<jsize>(bytes.size()), reinterpret_cast<const jbyte*>(bytes.data()));
        return result;
    } catch (const std::exception& error) {
        g_color_error = error.what();
        return nullptr;
    }
}

extern "C" JNIEXPORT jlong JNICALL Java_app_croptop_core_engine_NativeColorManagementBridge_nativeConvertTileStore(
    JNIEnv* env, jobject, jlong source_handle, jbyteArray source_icc, jbyteArray target_icc, jint output_format, jint intent,
    jboolean black_point_compensation, jint worker_threads) {
    return guarded("profile conversion failure", [&]() -> jlong {
        if (output_format < 0 || output_format > static_cast<jint>(croptop::PixelFormat::RGBA32F)) {
            throw std::invalid_argument("profile conversion requires RGBA8, RGBA16 or RGBA32F output");
        }
        if (intent < 0 || intent > static_cast<jint>(croptop::cms::Intent::AbsoluteColorimetric)) {
            throw std::invalid_argument("unknown profile rendering intent");
        }
        if (worker_threads < 0 || worker_threads > 32) throw std::invalid_argument("worker count must be between 0 and 32");

        const auto& source = storeOf(source_handle);
        if (source.pixels.format() != croptop::PixelFormat::RGBA8 &&
            source.pixels.format() != croptop::PixelFormat::RGBA16 &&
            source.pixels.format() != croptop::PixelFormat::RGBA32F) {
            throw std::invalid_argument("profile conversion requires an RGBA source TileStore");
        }
        const auto source_bytes = bytesOf(env, source_icc);
        const auto target_bytes = bytesOf(env, target_icc);
        std::string error;
        auto from = Profile::fromIcc(source_bytes, error);
        if (!from) throw std::invalid_argument(error.empty() ? "Invalid source ICC profile" : error);
        auto to = Profile::fromIcc(target_bytes, error);
        if (!to) throw std::invalid_argument(error.empty() ? "Invalid target ICC profile" : error);
        if (from->model() != croptop::cms::ColorModel::RGB || to->model() != croptop::cms::ColorModel::RGB) {
            throw std::invalid_argument("profile conversion requires RGB source and target profiles");
        }

        croptop::cms::ConvertOptions options;
        options.intent = static_cast<croptop::cms::Intent>(intent);
        options.black_point_compensation = black_point_compensation == JNI_TRUE;
        options.worker_threads = static_cast<unsigned>(worker_threads);
        auto converted = croptop::cms::convert(source.pixels, *from, *to,
                                               static_cast<croptop::PixelFormat>(output_format), options, error);
        if (!converted) throw std::runtime_error(error.empty() ? "Profile conversion failed" : error);
        return reinterpret_cast<jlong>(new NativeTileStore(std::move(*converted)));
    });
}

/** A display/soft-proof 3D LUT (size³ × RGB floats, red fastest) from the document profile to the display, optionally through a proof profile. */
extern "C" JNIEXPORT jfloatArray JNICALL Java_app_croptop_core_engine_NativeColorManagementBridge_nativeBakeDisplayLut(
    JNIEnv* env, jobject, jlong document, jlong display, jint size, jlong proof, jint intent, jboolean black_point_compensation, jint proof_intent,
    jboolean gamut_warning, jfloat warning_r, jfloat warning_g, jfloat warning_b) {
    g_color_error.clear();
    try {
        if (intent < 0 || intent > static_cast<jint>(croptop::cms::Intent::AbsoluteColorimetric) || proof_intent < 0 ||
            proof_intent > static_cast<jint>(croptop::cms::Intent::AbsoluteColorimetric)) {
            throw std::invalid_argument("unknown rendering intent");
        }
        croptop::cms::DisplayOptions options;
        options.intent = static_cast<croptop::cms::Intent>(intent);
        options.black_point_compensation = black_point_compensation == JNI_TRUE;
        options.proof = proof != 0 ? profileOf(proof) : nullptr;
        options.proof_intent = static_cast<croptop::cms::Intent>(proof_intent);
        options.gamut_warning = gamut_warning == JNI_TRUE;
        options.gamut_warning_rgb = {warning_r, warning_g, warning_b};
        std::string error;
        auto lut = croptop::cms::bakeDisplayLut(*profileOf(document), *profileOf(display), static_cast<std::uint32_t>(size), options, error);
        if (!lut) throw std::runtime_error(error.empty() ? "Could not build the display transform" : error);
        const auto count = static_cast<jsize>(lut->table_3d.size());
        jfloatArray out = env->NewFloatArray(count);
        if (out == nullptr) throw std::runtime_error("out of memory");
        env->SetFloatArrayRegion(out, 0, count, lut->table_3d.data());
        return out;
    } catch (const std::exception& error) {
        g_color_error = error.what();
    } catch (...) {
        g_color_error = "display transform failure";
    }
    return nullptr;
}

/** Runs packed ARGB viewport pixels through a baked LUT in place (alpha untouched). */
extern "C" JNIEXPORT jboolean JNICALL Java_app_croptop_core_engine_NativeColorManagementBridge_nativeApplyLutArgb(
    JNIEnv* env, jobject, jintArray pixels, jint count, jfloatArray table, jint size) {
    g_color_error.clear();
    try {
        if (pixels == nullptr || table == nullptr || count < 0 || size < 2 || env->GetArrayLength(pixels) < count ||
            static_cast<std::int64_t>(env->GetArrayLength(table)) != static_cast<std::int64_t>(size) * size * size * 3) {
            throw std::invalid_argument("LUT application arguments do not match");
        }
        croptop::color::Lut lut;
        lut.size_3d = static_cast<std::uint32_t>(size);
        lut.table_3d.resize(static_cast<std::size_t>(size) * size * size * 3U);
        env->GetFloatArrayRegion(table, 0, static_cast<jsize>(lut.table_3d.size()), lut.table_3d.data());
        auto* data = static_cast<jint*>(env->GetPrimitiveArrayCritical(pixels, nullptr));
        if (data == nullptr) throw std::runtime_error("could not pin the frame");
        croptop::cms::applyLutToArgb(reinterpret_cast<std::uint32_t*>(data), static_cast<std::size_t>(count), lut);
        env->ReleasePrimitiveArrayCritical(pixels, data, 0);
        return JNI_TRUE;
    } catch (const std::exception& error) {
        g_color_error = error.what();
    } catch (...) {
        g_color_error = "LUT application failure";
    }
    return JNI_FALSE;
}

extern "C" JNIEXPORT jlong JNICALL Java_app_croptop_core_engine_NativeColorManagementBridge_nativeCreateReadout(JNIEnv*, jobject, jlong document, jlong cmyk) {
    return guarded("color readout failure", [&]() -> jlong {
        std::string error;
        auto readout = Readout::create(*profileOf(document), *profileOf(cmyk), error);
        if (!readout) throw std::invalid_argument(error.empty() ? "Could not create color readout" : error);
        return reinterpret_cast<jlong>(new Readout(std::move(*readout)));
    });
}

extern "C" JNIEXPORT jfloatArray JNICALL Java_app_croptop_core_engine_NativeColorManagementBridge_nativeReadout(JNIEnv* env, jobject, jlong handle, jfloatArray input) {
    try {
        g_color_error.clear();
        if (reinterpret_cast<Readout*>(handle) == nullptr || input == nullptr || env->GetArrayLength(input) != 3) {
            throw std::invalid_argument("color readout needs a live session and three RGB channels");
        }
        std::array<jfloat, 3> rgb{};
        env->GetFloatArrayRegion(input, 0, 3, rgb.data());
        if (env->ExceptionCheck()) return nullptr;
        const auto value = reinterpret_cast<Readout*>(handle)->read(rgb);
        const std::array<jfloat, 8> output{
            value.lab[0], value.lab[1], value.lab[2], value.cmyk[0], value.cmyk[1], value.cmyk[2], value.cmyk[3], value.out_of_gamut ? 1.0F : 0.0F};
        auto result = env->NewFloatArray(static_cast<jsize>(output.size()));
        if (result != nullptr) env->SetFloatArrayRegion(result, 0, static_cast<jsize>(output.size()), output.data());
        return result;
    } catch (const std::exception& error) {
        g_color_error = error.what();
        return nullptr;
    }
}

extern "C" JNIEXPORT void JNICALL Java_app_croptop_core_engine_NativeColorManagementBridge_nativeDestroyReadout(JNIEnv*, jobject, jlong handle) {
    delete reinterpret_cast<Readout*>(handle);
}
#endif
