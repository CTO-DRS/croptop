// JNI surface for Upright / manual perspective (class app.croptop.core.engine.NativeUprightBridge): analyze a layer for the
// straightening transform and render it with the result. Results travel as a DoubleArray (layout below), so a preview can re-render with a
// cheaper filter without re-detecting lines.
#if defined(__ANDROID__)
#include <jni.h>

#include <algorithm>
#include <memory>
#include <stdexcept>
#include <string>
#include <vector>

#include "develop_upright_bridge.h"
#include "native_store_handle.h"

namespace {

namespace bridge = croptop::geometry::develop_bridge;
using Store = croptop::jni::NativeTileStore;

thread_local std::string g_upright_error;
thread_local std::string g_upright_note;

// Result layout: [0..8] source→output matrix (row-major), [9..12] crop left, top, right, bottom, [13] width, [14] height, [15] focal length px,
// [16] vertical found, [17] horizontal found, [18] tilt degrees, [19] segments used.
constexpr jsize kResultDoubles = 20;

std::vector<jdouble> pack(const bridge::Result& r) {
    std::vector<jdouble> v;
    v.reserve(kResultDoubles);
    for (const double m : r.source_to_output) v.push_back(m);
    v.push_back(r.crop_bounds.left);
    v.push_back(r.crop_bounds.top);
    v.push_back(r.crop_bounds.right);
    v.push_back(r.crop_bounds.bottom);
    v.push_back(r.width);
    v.push_back(r.height);
    v.push_back(r.focal_length_px);
    v.push_back(r.vertical_found ? 1.0 : 0.0);
    v.push_back(r.horizontal_found ? 1.0 : 0.0);
    v.push_back(r.tilt_degrees);
    v.push_back(static_cast<double>(r.segments_used));
    return v;
}

bridge::Result unpack(const jdouble* v) {
    bridge::Result r;
    for (int i = 0; i < 9; ++i) r.source_to_output[static_cast<std::size_t>(i)] = v[i];
    r.crop_bounds = {v[9], v[10], v[11], v[12]};
    r.width = static_cast<std::uint32_t>(v[13]);
    r.height = static_cast<std::uint32_t>(v[14]);
    r.focal_length_px = v[15];
    r.vertical_found = v[16] != 0.0;
    r.horizontal_found = v[17] != 0.0;
    r.tilt_degrees = v[18];
    r.segments_used = static_cast<std::size_t>(v[19]);
    return r;
}

}  // namespace

extern "C" JNIEXPORT jstring JNICALL Java_app_croptop_core_engine_NativeUprightBridge_nativeLastError(JNIEnv* env, jobject) {
    return env->NewStringUTF(g_upright_error.c_str());
}

/** Human-readable notes/warnings of the last successful analysis ("a | b"). */
extern "C" JNIEXPORT jstring JNICALL Java_app_croptop_core_engine_NativeUprightBridge_nativeLastNote(JNIEnv* env, jobject) {
    return env->NewStringUTF(g_upright_note.c_str());
}

/**
 * mode: 0 level, 1 vertical, 2 full, 3 auto, 4 guided, 5 manual. `guides` is x1,y1,x2,y2 per line (guided mode); `manual` is vertical, horizontal,
 * rotate, aspect, scale, xOffset, yOffset, focalPx. `exif_focal_35mm` ≤ 0 means unknown. Returns the packed result or null.
 */
extern "C" JNIEXPORT jdoubleArray JNICALL Java_app_croptop_core_engine_NativeUprightBridge_nativeAnalyze(JNIEnv* env, jobject, jlong store_handle, jint mode, jdouble exif_focal_35mm,
                                                                                                          jdoubleArray guides, jdoubleArray manual, jboolean auto_crop) {
    g_upright_error.clear();
    g_upright_note.clear();
    try {
        const auto* store = reinterpret_cast<const Store*>(store_handle);
        if (store == nullptr) throw std::invalid_argument("layer store handle is null");
        if (mode < 0 || mode > 5) throw std::invalid_argument("unknown upright mode");
        bridge::Request request;
        request.mode = static_cast<bridge::Mode>(mode);
        if (exif_focal_35mm > 0.0) request.exif_focal_length_35mm = exif_focal_35mm;
        request.auto_crop = auto_crop == JNI_TRUE;
        if (guides != nullptr) {
            const jsize n = env->GetArrayLength(guides);
            if (n < 0 || n % 4 != 0 || n > 4 * 256) throw std::invalid_argument("guides must be 1-256 lines of four numbers");
            std::vector<jdouble> g(static_cast<std::size_t>(n));
            if (n > 0) env->GetDoubleArrayRegion(guides, 0, n, g.data());
            for (jsize i = 0; i < n; i += 4) request.guides.push_back({g[static_cast<std::size_t>(i)], g[static_cast<std::size_t>(i + 1)], g[static_cast<std::size_t>(i + 2)], g[static_cast<std::size_t>(i + 3)]});
        }
        if (manual != nullptr) {
            if (env->GetArrayLength(manual) != 8) throw std::invalid_argument("manual upright takes 8 numbers");
            jdouble m[8];
            env->GetDoubleArrayRegion(manual, 0, 8, m);
            request.manual = {m[0], m[1], m[2], m[3], m[4], m[5], m[6], m[7]};
        }
        bridge::Error error;
        const auto result = bridge::analyze(store->pixels, request, error);
        if (!result) throw std::invalid_argument(error.message.empty() ? "upright analysis failed" : error.message);
        std::string notes = result->note;
        for (const auto& w : result->warnings) notes += (notes.empty() ? "" : " | ") + w.message;
        g_upright_note = notes;
        const auto packed = pack(*result);
        auto array = env->NewDoubleArray(kResultDoubles);
        if (array != nullptr) env->SetDoubleArrayRegion(array, 0, kResultDoubles, packed.data());
        return array;
    } catch (const std::exception& e) {
        g_upright_error = e.what();
    } catch (...) {
        g_upright_error = "unknown upright failure";
    }
    return nullptr;
}

/** filter: resample::Filter ordinal (0 nearest, 1 bilinear, 2 bicubic, 3 lanczos3). Returns a new cropped, corrected store handle or 0. */
extern "C" JNIEXPORT jlong JNICALL Java_app_croptop_core_engine_NativeUprightBridge_nativeRender(JNIEnv* env, jobject, jlong store_handle, jdoubleArray result, jint filter, jint worker_threads) {
    g_upright_error.clear();
    try {
        const auto* store = reinterpret_cast<const Store*>(store_handle);
        if (store == nullptr || result == nullptr || env->GetArrayLength(result) != kResultDoubles) throw std::invalid_argument("invalid upright render request");
        if (filter < 0 || filter > 3) throw std::invalid_argument("unknown resampling filter");
        jdouble v[kResultDoubles];
        env->GetDoubleArrayRegion(result, 0, kResultDoubles, v);
        bridge::Error error;
        auto out = bridge::render(store->pixels, unpack(v), static_cast<croptop::resample::Filter>(filter), error, static_cast<unsigned>(std::max(0, worker_threads)));
        if (!out) throw std::invalid_argument(error.message.empty() ? "upright render failed" : error.message);
        return reinterpret_cast<jlong>(new Store(std::move(*out)));
    } catch (const std::exception& e) {
        g_upright_error = e.what();
    } catch (...) {
        g_upright_error = "unknown upright failure";
    }
    return 0;
}
#endif  // __ANDROID__
