// JNI surface for camera raw files (class app.croptop.core.engine.NativeRawBridge): probe, embedded
// preview, and open as an upright sRGB layer. LibRaw itself stays behind raw_decode.h / raw_open.h.
#if defined(__ANDROID__)
#include <jni.h>

#include <algorithm>
#include <array>
#include <atomic>
#include <cstdio>
#include <memory>
#include <stdexcept>
#include <string>
#include <vector>

#include "native_store_handle.h"
#include "develop_settings_codec.h"
#include "raw_decode.h"
#include "raw_open.h"
#include "lens_profile.h"

namespace {

thread_local std::string g_raw_error;
thread_local std::vector<std::string> g_raw_info;  // key, value, key, value, ... of the last probe/open

struct CancelToken final {
    std::atomic<bool> cancelled{false};
    std::atomic<float> progress{0.0F};
};

struct UtfChars final {
    JNIEnv* env;
    jstring string;
    const char* chars;
    ~UtfChars() { env->ReleaseStringUTFChars(string, chars); }
};

std::vector<std::uint8_t> readBytes(JNIEnv* env, jbyteArray array, jint size) {
    if (array == nullptr || size <= 0) throw std::invalid_argument("raw file is empty");
    if (size > env->GetArrayLength(array)) throw std::invalid_argument("raw file length exceeds its byte array");
    std::vector<std::uint8_t> bytes(static_cast<std::size_t>(size));
    env->GetByteArrayRegion(array, 0, size, reinterpret_cast<jbyte*>(bytes.data()));
    return bytes;
}

std::string number(double value) {
    char text[48];
    std::snprintf(text, sizeof(text), "%.6g", value);
    return text;
}

std::array<float, 11> toneValues(JNIEnv* env, jfloatArray tone) {
    if (tone == nullptr || env->GetArrayLength(tone) != 11) throw std::invalid_argument("tone needs 11 values");
    std::array<jfloat, 11> values{};
    env->GetFloatArrayRegion(tone, 0, static_cast<jsize>(values.size()), values.data());
    return values;
}

std::vector<float> settingsValues(JNIEnv* env, jfloatArray settings) {
    if (settings == nullptr) throw std::invalid_argument("Develop settings are null");
    const auto length = env->GetArrayLength(settings);
    if (length < static_cast<jsize>(croptop::develop::kSettingsFixedFloats) ||
        length > static_cast<jsize>(croptop::develop::kSettingsMaxFloats))
        throw std::invalid_argument("Develop settings array has an invalid length");
    std::vector<float> values(static_cast<std::size_t>(length));
    env->GetFloatArrayRegion(settings, 0, length, values.data());
    return values;
}

void setTone(croptop::raw::OpenOptions& options, const std::array<float, 11>& v) {
    auto& b = options.settings.basic;
    b.exposure = v[0]; b.contrast = v[1]; b.highlights = v[2]; b.shadows = v[3]; b.whites = v[4]; b.blacks = v[5];
    b.texture = v[6]; b.clarity = v[7]; b.dehaze = v[8]; b.vibrance = v[9]; b.saturation = v[10];
}

std::array<float, 11> toneValues(const croptop::develop::pipeline::BasicTone& b) {
    return {b.exposure, b.contrast, b.highlights, b.shadows, b.whites, b.blacks, b.texture, b.clarity, b.dehaze,
            b.vibrance, b.saturation};
}

void addInfo(const std::string& key, const std::string& value) {
    g_raw_info.push_back(key);
    g_raw_info.push_back(value);
}

void addMetadata(const croptop::raw::RawMetadata& m) {
    addInfo("make", m.make);
    addInfo("model", m.model);
    addInfo("normalizedMake", m.normalized_make);
    addInfo("normalizedModel", m.normalized_model);
    addInfo("lens", m.lens);
    addInfo("software", m.software);
    addInfo("iso", number(m.iso));
    addInfo("shutterSeconds", number(m.shutter_seconds));
    addInfo("aperture", number(m.aperture));
    addInfo("focalLengthMm", number(m.focal_length_mm));
    addInfo("timestamp", std::to_string(m.timestamp));
    addInfo("orientation", std::to_string(m.orientation));
    addInfo("isDng", m.is_dng ? "true" : "false");
    addInfo("rawCount", std::to_string(m.raw_count));
}

jobjectArray infoArray(JNIEnv* env) {
    const auto string_class = env->FindClass("java/lang/String");
    auto result = env->NewObjectArray(static_cast<jsize>(g_raw_info.size()), string_class, nullptr);
    env->DeleteLocalRef(string_class);
    if (result == nullptr) return nullptr;
    for (std::size_t i = 0; i < g_raw_info.size(); ++i) {
        const auto text = env->NewStringUTF(g_raw_info[i].c_str());
        env->SetObjectArrayElement(result, static_cast<jsize>(i), text);
        env->DeleteLocalRef(text);
    }
    return result;
}

}  // namespace

extern "C" JNIEXPORT jstring JNICALL Java_app_croptop_core_engine_NativeRawBridge_nativeLastError(JNIEnv* env, jobject) {
    return env->NewStringUTF(g_raw_error.c_str());
}

/** Key/value pairs of the last successful nativeOpen (metadata, sensor size, warnings). */
extern "C" JNIEXPORT jobjectArray JNICALL Java_app_croptop_core_engine_NativeRawBridge_nativeLastInfo(JNIEnv* env, jobject) {
    return infoArray(env);
}

/** Key/value pairs (metadata, sizes, decodable) or null (see nativeLastError). */
extern "C" JNIEXPORT jobjectArray JNICALL Java_app_croptop_core_engine_NativeRawBridge_nativeProbe(JNIEnv* env, jobject, jbyteArray encoded, jint size) {
    g_raw_error.clear();
    g_raw_info.clear();
    try {
        const auto bytes = readBytes(env, encoded, size);
        std::string error;
        const auto probe = croptop::raw::probe(bytes, error);
        if (!probe) throw std::invalid_argument(error);
        addMetadata(probe->metadata);
        addInfo("width", std::to_string(probe->width));
        addInfo("height", std::to_string(probe->height));
        addInfo("rawWidth", std::to_string(probe->raw_width));
        addInfo("rawHeight", std::to_string(probe->raw_height));
        addInfo("xtrans", probe->xtrans ? "true" : "false");
        addInfo("decodable", probe->decodable ? "true" : "false");
        addInfo("unsupportedReason", probe->unsupported_reason);
        return infoArray(env);
    } catch (const std::exception& e) {
        g_raw_error = e.what();
    } catch (...) {
        g_raw_error = "unknown raw probe failure";
    }
    return nullptr;
}

/** Create/read interoperable Camera Raw XMP for the BasicTone controls currently exposed by NativeRawBridge. */
extern "C" JNIEXPORT jstring JNICALL Java_app_croptop_core_engine_NativeRawBridge_nativeWriteSidecar(JNIEnv* env, jobject,
                                                                                                      jfloatArray tone) {
    g_raw_error.clear();
    try {
        croptop::raw::OpenOptions options;
        setTone(options, toneValues(env, tone));
        const auto xmp = croptop::raw::sidecarXmp(options);
        return env->NewStringUTF(xmp.c_str());
    } catch (const std::exception& e) {
        g_raw_error = e.what();
    } catch (...) {
        g_raw_error = "unknown XMP sidecar write failure";
    }
    return nullptr;
}

extern "C" JNIEXPORT jfloatArray JNICALL Java_app_croptop_core_engine_NativeRawBridge_nativeReadSidecar(JNIEnv* env, jobject,
                                                                                                         jstring encoded) {
    g_raw_error.clear();
    if (encoded == nullptr) {
        g_raw_error = "XMP sidecar is null";
        return nullptr;
    }
    const char* chars = env->GetStringUTFChars(encoded, nullptr);
    if (chars == nullptr) return nullptr;
    const UtfChars utf{env, encoded, chars};
    (void)utf;
    try {
        std::string error;
        const auto options = croptop::raw::optionsFromXmp(chars, error);
        if (!options) throw std::invalid_argument(error.empty() ? "XMP has no supported Develop settings" : error);
        const auto values = toneValues(options->settings.basic);
        auto result = env->NewFloatArray(static_cast<jsize>(values.size()));
        if (result == nullptr) throw std::runtime_error("could not allocate XMP settings result");
        env->SetFloatArrayRegion(result, 0, static_cast<jsize>(values.size()), values.data());
        return result;
    } catch (const std::exception& e) {
        g_raw_error = e.what();
    } catch (...) {
        g_raw_error = "unknown XMP sidecar read failure";
    }
    return nullptr;
}

extern "C" JNIEXPORT jstring JNICALL Java_app_croptop_core_engine_NativeRawBridge_nativeWriteDevelopSidecar(JNIEnv* env, jobject,
                                                                                                             jfloatArray encoded) {
    g_raw_error.clear();
    try {
        const auto values = settingsValues(env, encoded);
        std::string error;
        auto settings = croptop::develop::decodeSettings(values, error);
        if (!settings) throw std::invalid_argument(error);
        croptop::raw::OpenOptions options;
        options.settings = *settings;
        const auto xmp = croptop::raw::sidecarXmp(options);
        return env->NewStringUTF(xmp.c_str());
    } catch (const std::exception& e) {
        g_raw_error = e.what();
    } catch (...) {
        g_raw_error = "unknown Develop XMP sidecar write failure";
    }
    return nullptr;
}

extern "C" JNIEXPORT jfloatArray JNICALL Java_app_croptop_core_engine_NativeRawBridge_nativeReadDevelopSidecar(JNIEnv* env, jobject,
                                                                                                                 jstring encoded) {
    g_raw_error.clear();
    if (encoded == nullptr) {
        g_raw_error = "XMP sidecar is null";
        return nullptr;
    }
    const char* chars = env->GetStringUTFChars(encoded, nullptr);
    if (chars == nullptr) return nullptr;
    const UtfChars utf{env, encoded, chars};
    (void)utf;
    try {
        std::string error;
        const auto options = croptop::raw::optionsFromXmp(chars, error);
        if (!options) throw std::invalid_argument(error.empty() ? "XMP has no supported Develop settings" : error);
        const auto values = croptop::develop::encodeSettings(options->settings);
        auto result = env->NewFloatArray(static_cast<jsize>(values.size()));
        if (result == nullptr) throw std::runtime_error("could not allocate Develop XMP settings result");
        env->SetFloatArrayRegion(result, 0, static_cast<jsize>(values.size()), values.data());
        return result;
    } catch (const std::exception& e) {
        g_raw_error = e.what();
    } catch (...) {
        g_raw_error = "unknown Develop XMP sidecar read failure";
    }
    return nullptr;
}

/** The largest embedded JPEG whose longer edge is at least minLongEdge, or null (see nativeLastError). */
extern "C" JNIEXPORT jbyteArray JNICALL Java_app_croptop_core_engine_NativeRawBridge_nativePreview(JNIEnv* env, jobject, jbyteArray encoded, jint size,
                                                                                                  jint min_long_edge) {
    g_raw_error.clear();
    try {
        const auto bytes = readBytes(env, encoded, size);
        std::string error;
        const auto preview = croptop::raw::extractPreview(bytes, error, static_cast<std::uint32_t>(std::max(0, min_long_edge)));
        if (!preview) throw std::invalid_argument(error);
        auto result = env->NewByteArray(static_cast<jsize>(preview->jpeg.size()));
        if (result == nullptr) throw std::runtime_error("could not allocate the preview array");
        env->SetByteArrayRegion(result, 0, static_cast<jsize>(preview->jpeg.size()), reinterpret_cast<const jbyte*>(preview->jpeg.data()));
        return result;
    } catch (const std::exception& e) {
        g_raw_error = e.what();
    } catch (...) {
        g_raw_error = "unknown raw preview failure";
    }
    return nullptr;
}

extern "C" JNIEXPORT jlong JNICALL Java_app_croptop_core_engine_NativeRawBridge_nativeNewCancelToken(JNIEnv*, jobject) {
    return reinterpret_cast<jlong>(new CancelToken());
}
extern "C" JNIEXPORT void JNICALL Java_app_croptop_core_engine_NativeRawBridge_nativeCancel(JNIEnv*, jobject, jlong token) {
    if (token != 0) reinterpret_cast<CancelToken*>(token)->cancelled = true;
}
extern "C" JNIEXPORT jfloat JNICALL Java_app_croptop_core_engine_NativeRawBridge_nativeProgress(JNIEnv*, jobject, jlong token) {
    return token == 0 ? 0.0F : reinterpret_cast<CancelToken*>(token)->progress.load();
}
extern "C" JNIEXPORT void JNICALL Java_app_croptop_core_engine_NativeRawBridge_nativeFreeCancelToken(JNIEnv*, jobject, jlong token) {
    delete reinterpret_cast<CancelToken*>(token);
}

/**
 * Opens the raw as an upright sRGB layer. `tone` is the 11 Develop BasicTone values (exposure, contrast, highlights,
 * shadows, whites, blacks, texture, clarity, dehaze, vibrance, saturation) or null for neutral. Returns
 * [store handle, width, height, bit depth (8 or 16)] or null (see nativeLastError); nativeLastInfo describes the file.
 * The caller owns the store (NativeSelectionBridge.destroyStore).
 */
extern "C" JNIEXPORT jlongArray JNICALL Java_app_croptop_core_engine_NativeRawBridge_nativeOpen(JNIEnv* env, jobject, jbyteArray encoded, jint size,
                                                                                                jboolean sixteen_bit, jboolean apply_orientation,
                                                                                                jfloatArray develop_values, jint worker_threads, jlong cancel_token, jlong lens_database) {
    g_raw_error.clear();
    g_raw_info.clear();
    try {
        const auto bytes = readBytes(env, encoded, size);
        croptop::raw::OpenOptions options;
        options.sixteen_bit = sixteen_bit == JNI_TRUE;
        options.apply_orientation = apply_orientation == JNI_TRUE;
        options.worker_threads = static_cast<unsigned>(std::max(0, worker_threads));
        if (lens_database != 0) {
            const auto* database = reinterpret_cast<const croptop::lens::Database*>(lens_database);
            options.lens_database = std::shared_ptr<const croptop::lens::Database>(database, [](const croptop::lens::Database*) {});
        }
        if (develop_values != nullptr) {
            const auto length = env->GetArrayLength(develop_values);
            if (length == 11) {
                setTone(options, toneValues(env, develop_values));
            } else {
                const auto values = settingsValues(env, develop_values);
                std::string settings_error;
                auto settings = croptop::develop::decodeSettings(values, settings_error);
                if (!settings) throw std::invalid_argument(settings_error);
                options.settings = std::move(*settings);
            }
        }
        auto* token = reinterpret_cast<CancelToken*>(cancel_token);
        if (token != nullptr) {
            options.progress = [token](float fraction) {
                token->progress = fraction;
                return !token->cancelled.load();
            };
        }
        std::string error;
        auto opened = croptop::raw::openFlattened(bytes, options, error);
        if (!opened) throw std::invalid_argument(error);
        addMetadata(opened->metadata);
        addInfo("sensorWidth", std::to_string(opened->sensor_width));
        addInfo("sensorHeight", std::to_string(opened->sensor_height));
        std::string warnings;
        for (const auto& w : opened->warnings) warnings += (warnings.empty() ? "" : "\n") + w;
        addInfo("warnings", warnings);
        const auto width = opened->pixels.width(), height = opened->pixels.height();
        const jint depth = options.sixteen_bit ? 16 : 8;
        auto store = std::make_unique<croptop::jni::NativeTileStore>(std::move(opened->pixels));
        const std::array<jlong, 4> values{reinterpret_cast<jlong>(store.get()), static_cast<jlong>(width), static_cast<jlong>(height), depth};
        auto result = env->NewLongArray(4);
        if (result == nullptr) throw std::runtime_error("could not allocate the result array");
        env->SetLongArrayRegion(result, 0, 4, values.data());
        store.release();
        return result;
    } catch (const std::exception& e) {
        g_raw_error = e.what();
    } catch (...) {
        g_raw_error = "unknown raw open failure";
    }
    return nullptr;
}

/** A Develop preview session: decode + demosaic once, then render bounded previews for changing settings. Returns a handle or 0. */
extern "C" JNIEXPORT jlong JNICALL Java_app_croptop_core_engine_NativeRawBridge_nativeSessionOpen(JNIEnv* env, jobject, jbyteArray encoded, jint size,
                                                                                                  jint worker_threads, jlong lens_database) {
    g_raw_error.clear();
    g_raw_info.clear();
    try {
        const auto bytes = readBytes(env, encoded, size);
        croptop::raw::OpenOptions options;
        options.worker_threads = static_cast<unsigned>(std::max(0, worker_threads));
        if (lens_database != 0) {
            const auto* database = reinterpret_cast<const croptop::lens::Database*>(lens_database);
            options.lens_database = std::shared_ptr<const croptop::lens::Database>(database, [](const croptop::lens::Database*) {});
        }
        std::string error;
        auto session = croptop::raw::DevelopSession::open(bytes, options, error);
        if (!session) throw std::invalid_argument(error);
        addMetadata(session->metadata());
        addInfo("width", std::to_string(session->width()));
        addInfo("height", std::to_string(session->height()));
        std::string warnings;
        for (const auto& w : session->openWarnings()) warnings += (warnings.empty() ? "" : "\n") + w;
        addInfo("warnings", warnings);
        return reinterpret_cast<jlong>(session.release());
    } catch (const std::exception& e) {
        g_raw_error = e.what();
    } catch (...) {
        g_raw_error = "unknown Develop session failure";
    }
    return 0;
}

/** [width, height, ARGB pixels...] of the preview for the flat settings, or null (see nativeLastError). Warnings are in nativeLastInfo. */
extern "C" JNIEXPORT jintArray JNICALL Java_app_croptop_core_engine_NativeRawBridge_nativeSessionRender(JNIEnv* env, jobject, jlong handle,
                                                                                                         jfloatArray settings, jint max_dimension,
                                                                                                         jint worker_threads) {
    g_raw_error.clear();
    g_raw_info.clear();
    auto* session = reinterpret_cast<croptop::raw::DevelopSession*>(handle);
    if (session == nullptr) {
        g_raw_error = "Develop session is closed";
        return nullptr;
    }
    try {
        const auto values = settingsValues(env, settings);
        std::string error;
        auto decoded = croptop::develop::decodeSettings(values, error);
        if (!decoded) throw std::invalid_argument(error);
        std::vector<std::string> warnings;
        auto preview = session->render(*decoded, static_cast<std::uint32_t>(std::max(0, max_dimension)), static_cast<unsigned>(std::max(0, worker_threads)),
                                       error, &warnings);
        if (!preview) throw std::invalid_argument(error);
        std::string joined;
        for (const auto& w : warnings) joined += (joined.empty() ? "" : "\n") + w;
        addInfo("warnings", joined);
        const auto width = preview->width(), height = preview->height();
        std::vector<jint> out(2U + static_cast<std::size_t>(width) * height);
        out[0] = static_cast<jint>(width);
        out[1] = static_cast<jint>(height);
        for (std::uint32_t ty = 0; ty * croptop::kTileEdge < height; ++ty) {
            for (std::uint32_t tx = 0; tx * croptop::kTileEdge < width; ++tx) {
                const auto tile = preview->readTile({static_cast<std::int32_t>(tx), static_cast<std::int32_t>(ty)});
                for (std::uint32_t y = 0; y < croptop::kTileEdge && ty * croptop::kTileEdge + y < height; ++y) {
                    for (std::uint32_t x = 0; x < croptop::kTileEdge && tx * croptop::kTileEdge + x < width; ++x) {
                        std::uint32_t r = 0, g = 0, b = 0;
                        if (tile != nullptr) {
                            const auto* px = tile->data() + (static_cast<std::size_t>(y) * croptop::kTileEdge + x) * 4U;
                            r = px[0];
                            g = px[1];
                            b = px[2];
                        }
                        out[2U + static_cast<std::size_t>(ty * croptop::kTileEdge + y) * width + tx * croptop::kTileEdge + x] =
                            static_cast<jint>(0xFF000000U | (r << 16U) | (g << 8U) | b);
                    }
                }
            }
        }
        auto result = env->NewIntArray(static_cast<jsize>(out.size()));
        if (result == nullptr) throw std::runtime_error("could not allocate the preview");
        env->SetIntArrayRegion(result, 0, static_cast<jsize>(out.size()), out.data());
        return result;
    } catch (const std::exception& e) {
        g_raw_error = e.what();
    } catch (...) {
        g_raw_error = "unknown Develop preview failure";
    }
    return nullptr;
}

extern "C" JNIEXPORT void JNICALL Java_app_croptop_core_engine_NativeRawBridge_nativeSessionClose(JNIEnv*, jobject, jlong handle) {
    delete reinterpret_cast<croptop::raw::DevelopSession*>(handle);
}

/** Like nativeSessionRender but returns the red coverage overlay of `settings.masks[mask_index]` ([width, height, ARGB...]). */
extern "C" JNIEXPORT jintArray JNICALL Java_app_croptop_core_engine_NativeRawBridge_nativeSessionMaskOverlay(JNIEnv* env, jobject, jlong handle,
                                                                                                             jfloatArray settings, jint mask_index,
                                                                                                             jint max_dimension, jint worker_threads) {
    g_raw_error.clear();
    auto* session = reinterpret_cast<croptop::raw::DevelopSession*>(handle);
    if (session == nullptr) {
        g_raw_error = "Develop session is closed";
        return nullptr;
    }
    try {
        const auto values = settingsValues(env, settings);
        std::string error;
        auto decoded = croptop::develop::decodeSettings(values, error);
        if (!decoded) throw std::invalid_argument(error);
        auto overlay = session->maskOverlay(*decoded, static_cast<std::size_t>(std::max(0, mask_index)),
                                            static_cast<std::uint32_t>(std::max(0, max_dimension)), static_cast<unsigned>(std::max(0, worker_threads)), error);
        if (!overlay) throw std::invalid_argument(error);
        const auto width = overlay->width(), height = overlay->height();
        std::vector<jint> out(2U + static_cast<std::size_t>(width) * height, 0);
        out[0] = static_cast<jint>(width);
        out[1] = static_cast<jint>(height);
        for (const auto coordinate : overlay->tileCoordinates()) {
            const auto tile = overlay->readTile(coordinate);
            if (tile == nullptr) continue;
            for (std::uint32_t y = 0; y < croptop::kTileEdge; ++y) {
                const auto gy = static_cast<std::uint32_t>(coordinate.y) * croptop::kTileEdge + y;
                if (gy >= height) break;
                for (std::uint32_t x = 0; x < croptop::kTileEdge; ++x) {
                    const auto gx = static_cast<std::uint32_t>(coordinate.x) * croptop::kTileEdge + x;
                    if (gx >= width) break;
                    const auto* px = tile->data() + (static_cast<std::size_t>(y) * croptop::kTileEdge + x) * 4U;
                    out[2U + static_cast<std::size_t>(gy) * width + gx] =
                        static_cast<jint>((static_cast<std::uint32_t>(px[3]) << 24U) | (static_cast<std::uint32_t>(px[0]) << 16U) |
                                          (static_cast<std::uint32_t>(px[1]) << 8U) | px[2]);
                }
            }
        }
        auto result = env->NewIntArray(static_cast<jsize>(out.size()));
        if (result == nullptr) throw std::runtime_error("could not allocate the overlay");
        env->SetIntArrayRegion(result, 0, static_cast<jsize>(out.size()), out.data());
        return result;
    } catch (const std::exception& e) {
        g_raw_error = e.what();
    } catch (...) {
        g_raw_error = "unknown mask overlay failure";
    }
    return nullptr;
}
#endif  // __ANDROID__
