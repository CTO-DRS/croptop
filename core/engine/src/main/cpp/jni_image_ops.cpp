// JNI surface for Liquify sessions, Red Eye, histograms and Refine Edge (class app.croptop.core.engine.NativeImageOpsBridge).
// Store-returning calls work on a copy-on-write clone, so the source layer stays intact for history; the caller owns every returned handle.
#if defined(__ANDROID__)
#include <jni.h>

#include <algorithm>
#include <memory>
#include <stdexcept>
#include <string>
#include <vector>

#include "heal.h"
#include "histogram.h"
#if defined(CROPTOP_HAS_GPU_DEVELOP)
#include "gpu_service.h"
#include "histogram_gpu.h"
#endif
#include "lens_correction.h"
#include "liquify.h"
#include "lut.h"
#include "native_store_handle.h"
#include "red_eye.h"
#include "refine_edge.h"

namespace {

thread_local std::string g_ops_error;

using Store = croptop::jni::NativeTileStore;

const Store& storeOf(jlong handle, const char* what) {
    const auto* store = reinterpret_cast<const Store*>(handle);
    if (store == nullptr) throw std::invalid_argument(std::string(what) + " handle is null");
    return *store;
}

const Store* optionalStore(jlong handle) { return reinterpret_cast<const Store*>(handle); }

template <typename Fn>
jlong guarded(const char* unknown, Fn&& fn) {
    g_ops_error.clear();
    try {
        return fn();
    } catch (const std::exception& e) {
        g_ops_error = e.what();
    } catch (...) {
        g_ops_error = unknown;
    }
    return 0;
}

}  // namespace

extern "C" JNIEXPORT jstring JNICALL Java_app_croptop_core_engine_NativeImageOpsBridge_nativeLastError(JNIEnv* env, jobject) {
    return env->NewStringUTF(g_ops_error.c_str());
}

// ── Liquify ────────────────────────────────────────────────────────────────────────────────────────────────────────────────────

extern "C" JNIEXPORT jlong JNICALL Java_app_croptop_core_engine_NativeImageOpsBridge_nativeLiquifyCreate(JNIEnv*, jobject, jint width, jint height, jint spacing) {
    return guarded("liquify failure", [&]() -> jlong {
        if (width <= 0 || height <= 0 || spacing < 1 || spacing > 64) throw std::invalid_argument("invalid liquify mesh size");
        return reinterpret_cast<jlong>(new croptop::paint::LiquifyMesh(static_cast<std::uint32_t>(width), static_cast<std::uint32_t>(height), static_cast<std::uint32_t>(spacing)));
    });
}

extern "C" JNIEXPORT void JNICALL Java_app_croptop_core_engine_NativeImageOpsBridge_nativeLiquifyDestroy(JNIEnv*, jobject, jlong mesh) {
    delete reinterpret_cast<croptop::paint::LiquifyMesh*>(mesh);
}

/** tool: LiquifyTool ordinal (0 forward warp … 7 push left). Returns 1 on success, 0 on failure (nativeLastError). */
extern "C" JNIEXPORT jlong JNICALL Java_app_croptop_core_engine_NativeImageOpsBridge_nativeLiquifyApply(JNIEnv*, jobject, jlong mesh, jint tool, jdouble x, jdouble y, jdouble size,
                                                                                                        jfloat density, jfloat pressure, jfloat rate, jdouble dx, jdouble dy) {
    return guarded("liquify failure", [&]() -> jlong {
        auto* m = reinterpret_cast<croptop::paint::LiquifyMesh*>(mesh);
        if (m == nullptr) throw std::invalid_argument("liquify session handle is null");
        if (tool < 0 || tool > static_cast<int>(croptop::paint::LiquifyTool::PushLeft)) throw std::invalid_argument("unknown liquify tool");
        croptop::paint::LiquifyBrush brush;
        brush.x = x;
        brush.y = y;
        brush.size = size;
        brush.density = density;
        brush.pressure = pressure;
        brush.rate = rate;
        brush.dx = dx;
        brush.dy = dy;
        std::string error;
        if (!m->apply(static_cast<croptop::paint::LiquifyTool>(tool), brush, error)) throw std::invalid_argument(error);
        return 1;
    });
}

extern "C" JNIEXPORT void JNICALL Java_app_croptop_core_engine_NativeImageOpsBridge_nativeLiquifyReconstructAll(JNIEnv*, jobject, jlong mesh, jfloat amount) {
    if (auto* m = reinterpret_cast<croptop::paint::LiquifyMesh*>(mesh)) m->reconstructAll(std::clamp(static_cast<float>(amount), 0.0F, 1.0F));
}

/** `mask_handle` 0 clears the freeze mask. */
extern "C" JNIEXPORT jlong JNICALL Java_app_croptop_core_engine_NativeImageOpsBridge_nativeLiquifySetFreeze(JNIEnv*, jobject, jlong mesh, jlong mask_handle) {
    return guarded("liquify failure", [&]() -> jlong {
        auto* m = reinterpret_cast<croptop::paint::LiquifyMesh*>(mesh);
        if (m == nullptr) throw std::invalid_argument("liquify session handle is null");
        const auto* mask = optionalStore(mask_handle);
        std::string error;
        if (!m->setFreezeMask(mask != nullptr ? &mask->pixels : nullptr, error)) throw std::invalid_argument(error);
        return 1;
    });
}

extern "C" JNIEXPORT jlong JNICALL Java_app_croptop_core_engine_NativeImageOpsBridge_nativeLiquifyRender(JNIEnv*, jobject, jlong mesh, jlong store_handle, jint worker_threads, jlong selection_handle) {
    return guarded("liquify failure", [&]() -> jlong {
        const auto* m = reinterpret_cast<const croptop::paint::LiquifyMesh*>(mesh);
        if (m == nullptr) throw std::invalid_argument("liquify session handle is null");
        std::string error;
        const auto* selection = optionalStore(selection_handle);
        auto rendered = m->render(storeOf(store_handle, "layer store").pixels, error, static_cast<unsigned>(std::max(0, worker_threads)),
                                  selection != nullptr ? &selection->pixels : nullptr);
        if (!rendered) throw std::invalid_argument(error);
        return reinterpret_cast<jlong>(new Store(std::move(*rendered)));
    });
}

/** Interleaved (dx, dy) per node, for the GPU preview texture; also `columns`, `rows` and `spacing` are returned in the first three elements. */
extern "C" JNIEXPORT jfloatArray JNICALL Java_app_croptop_core_engine_NativeImageOpsBridge_nativeLiquifyField(JNIEnv* env, jobject, jlong mesh) {
    const auto* m = reinterpret_cast<const croptop::paint::LiquifyMesh*>(mesh);
    if (m == nullptr) return nullptr;
    const auto& field = m->field();
    std::vector<jfloat> out;
    out.reserve(field.size() + 3U);
    out.push_back(static_cast<jfloat>(m->columns()));
    out.push_back(static_cast<jfloat>(m->rows()));
    out.push_back(static_cast<jfloat>(m->spacing()));
    out.insert(out.end(), field.begin(), field.end());
    auto array = env->NewFloatArray(static_cast<jsize>(out.size()));
    if (array != nullptr) env->SetFloatArrayRegion(array, 0, static_cast<jsize>(out.size()), out.data());
    return array;
}

// ── Red Eye ────────────────────────────────────────────────────────────────────────────────────────────────────────────────────

extern "C" JNIEXPORT jlong JNICALL Java_app_croptop_core_engine_NativeImageOpsBridge_nativeRedEye(JNIEnv*, jobject, jlong store_handle, jlong region_handle, jfloat pupil_size, jfloat darken) {
    return guarded("red eye failure", [&]() -> jlong {
        auto result = std::make_unique<Store>(storeOf(store_handle, "layer store"));
        croptop::paint::RedEyeSpec spec;
        spec.pupil_size = pupil_size;
        spec.darken = darken;
        std::string error;
        if (!croptop::paint::redEye(result->pixels, storeOf(region_handle, "region").pixels, spec, error)) throw std::invalid_argument(error);
        return reinterpret_cast<jlong>(result.release());
    });
}

// ── Histogram ──────────────────────────────────────────────────────────────────────────────────────────────────────────────────

/** Red, green, blue, luminosity bins (4 × 256 doubles) followed by the total weight; null on failure. */
extern "C" JNIEXPORT jdoubleArray JNICALL Java_app_croptop_core_engine_NativeImageOpsBridge_nativeHistogram(JNIEnv* env, jobject, jlong store_handle, jlong mask_handle, jint mip_level,
                                                                                                           jint worker_threads) {
    g_ops_error.clear();
    try {
        const auto* mask = optionalStore(mask_handle);
        std::string error;
        const auto& layer = storeOf(store_handle, "layer store").pixels;
        const auto* mask_pixels = mask != nullptr ? &mask->pixels : nullptr;
        const auto level = static_cast<std::uint32_t>(std::max(0, mip_level));
        auto histogram = std::optional<croptop::color::Histogram>{};
#if defined(CROPTOP_HAS_GPU_DEVELOP)
        if (level <= 16U && static_cast<std::uint64_t>(layer.mip_width(level)) * layer.mip_height(level) >= 256U * 256U) {
            if (auto gpu = croptop::gpu::sharedGpuService().acquire()) {
                histogram = croptop::color::computeHistogramGpu(*gpu, layer, mask_pixels, error, level);
                if (!histogram) croptop::gpu::sharedGpuService().reportFailure(error);
            }
        }
#endif
        if (!histogram) {
            histogram = croptop::color::computeHistogram(layer, mask_pixels, error, level, static_cast<unsigned>(std::max(0, worker_threads)));
        }
        if (!histogram) throw std::invalid_argument(error);
        std::vector<jdouble> out;
        out.reserve(4U * 256U + 1U);
        for (const auto& channel : histogram->bins) out.insert(out.end(), channel.begin(), channel.end());
        out.push_back(histogram->total);
        auto array = env->NewDoubleArray(static_cast<jsize>(out.size()));
        if (array != nullptr) env->SetDoubleArrayRegion(array, 0, static_cast<jsize>(out.size()), out.data());
        return array;
    } catch (const std::exception& e) {
        g_ops_error = e.what();
    } catch (...) {
        g_ops_error = "histogram failure";
    }
    return nullptr;
}

// ── Refine Edge ────────────────────────────────────────────────────────────────────────────────────────────────────────────────

/** params: radius, smartRadius(0/1), smooth, feather, contrast, shiftEdge. Returns a new A16 mask handle. */
extern "C" JNIEXPORT jlong JNICALL Java_app_croptop_core_engine_NativeImageOpsBridge_nativeRefineEdge(JNIEnv* env, jobject, jlong guide_handle, jlong mask_handle, jfloatArray params) {
    return guarded("refine edge failure", [&]() -> jlong {
        if (params == nullptr || env->GetArrayLength(params) != 6) throw std::invalid_argument("refine edge takes 6 parameters");
        jfloat p[6];
        env->GetFloatArrayRegion(params, 0, 6, p);
        croptop::selection::RefineEdgeSpec spec;
        spec.radius = p[0];
        spec.smart_radius = p[1] != 0.0F;
        spec.smooth = p[2];
        spec.feather = p[3];
        spec.contrast = p[4];
        spec.shift_edge = p[5];
        std::string error;
        auto refined = croptop::selection::refine(storeOf(guide_handle, "guide").pixels, storeOf(mask_handle, "mask").pixels, spec, error);
        if (!refined) throw std::invalid_argument(error);
        return reinterpret_cast<jlong>(new Store(std::move(*refined)));
    });
}

extern "C" JNIEXPORT jlong JNICALL Java_app_croptop_core_engine_NativeImageOpsBridge_nativeDecontaminate(JNIEnv*, jobject, jlong image_handle, jlong mask_handle, jfloat amount) {
    return guarded("decontaminate failure", [&]() -> jlong {
        std::string error;
        auto out = croptop::selection::decontaminate(storeOf(image_handle, "image").pixels, storeOf(mask_handle, "mask").pixels, amount, error);
        if (!out) throw std::invalid_argument(error);
        return reinterpret_cast<jlong>(new Store(std::move(*out)));
    });
}

// ── Clone / Heal / Spot Heal / Patch ───────────────────────────────────────────────────────────────────────────────────────────

/** op: 0 clone stamp, 1 healing blend, 2 patch (Normal), 3 patch (ContentAware). `region_handle` is the A16 brush/selection area. */
extern "C" JNIEXPORT jlong JNICALL Java_app_croptop_core_engine_NativeImageOpsBridge_nativeRetouchFromSource(JNIEnv*, jobject, jlong dst_handle, jlong src_handle, jlong region_handle,
                                                                                                             jint op, jdouble offset_x, jdouble offset_y, jint worker_threads) {
    return guarded("retouch failure", [&]() -> jlong {
        if (op < 0 || op > 3) throw std::invalid_argument("unknown retouch operation");
        auto result = std::make_unique<Store>(storeOf(dst_handle, "layer store"));
        const auto& region = storeOf(region_handle, "region").pixels;
        std::string error;
        bool ok = false;
        if (op == 0) ok = croptop::paint::cloneStamp(result->pixels, storeOf(src_handle, "source store").pixels, offset_x, offset_y, region, error);
        else if (op == 1) ok = croptop::paint::healingBlend(result->pixels, storeOf(src_handle, "source store").pixels, offset_x, offset_y, region, error, static_cast<unsigned>(std::max(0, worker_threads)));
        else ok = croptop::paint::patch(result->pixels, region, offset_x, offset_y, op == 2 ? croptop::paint::PatchMode::Normal : croptop::paint::PatchMode::ContentAware, error);
        if (!ok) throw std::invalid_argument(error);
        return reinterpret_cast<jlong>(result.release());
    });
}

/** type: 0 content-aware, 1 proximity match, 2 create texture. */
extern "C" JNIEXPORT jlong JNICALL Java_app_croptop_core_engine_NativeImageOpsBridge_nativeSpotHeal(JNIEnv*, jobject, jlong dst_handle, jlong mask_handle, jint type) {
    return guarded("spot heal failure", [&]() -> jlong {
        if (type < 0 || type > 2) throw std::invalid_argument("unknown spot heal type");
        auto result = std::make_unique<Store>(storeOf(dst_handle, "layer store"));
        std::string error;
        if (!croptop::paint::spotHeal(result->pixels, storeOf(mask_handle, "mask").pixels, error, static_cast<croptop::paint::SpotHealType>(type))) throw std::invalid_argument(error);
        return reinterpret_cast<jlong>(result.release());
    });
}

// ── Colour lookup tables ───────────────────────────────────────────────────────────────────────────────────────────────────────

namespace {
std::optional<croptop::color::Lut> parseLutBytes(JNIEnv* env, jbyteArray bytes, jstring file_name) {
    if (bytes == nullptr) throw std::invalid_argument("LUT file is empty");
    const jsize n = env->GetArrayLength(bytes);
    if (n <= 0 || n > (64 << 20)) throw std::invalid_argument("LUT file has an invalid size");
    std::string text(static_cast<std::size_t>(n), '\0');
    env->GetByteArrayRegion(bytes, 0, n, reinterpret_cast<jbyte*>(text.data()));
    std::string name;
    if (file_name != nullptr) {
        const char* chars = env->GetStringUTFChars(file_name, nullptr);
        if (chars != nullptr) {
            name = chars;
            env->ReleaseStringUTFChars(file_name, chars);
        }
    }
    std::string error;
    auto lut = croptop::color::parseLut(text, name, error);
    if (!lut) throw std::invalid_argument(error);
    return lut;
}
}  // namespace

/** Parses a .cube/.3dl file and applies it to a clone of the layer, blended by `strength` (0-1) and the optional A16 mask. */
extern "C" JNIEXPORT jlong JNICALL Java_app_croptop_core_engine_NativeImageOpsBridge_nativeApplyLut(JNIEnv* env, jobject, jlong store_handle, jbyteArray bytes, jstring file_name,
                                                                                                    jfloat strength, jlong mask_handle, jint worker_threads) {
    return guarded("LUT failure", [&]() -> jlong {
        const auto lut = parseLutBytes(env, bytes, file_name);
        auto result = std::make_unique<Store>(storeOf(store_handle, "layer store"));
        const auto* mask = optionalStore(mask_handle);
        std::string error;
        if (!croptop::color::applyLut(result->pixels, *lut, strength, mask != nullptr ? &mask->pixels : nullptr, error, static_cast<unsigned>(std::max(0, worker_threads)))) throw std::invalid_argument(error);
        return reinterpret_cast<jlong>(result.release());
    });
}

/** [size, r, g, b, a, r, g, b, a, ...] for a GPU 3D texture (size³ RGBA32F texels), or null when the file has no 3D table. */
extern "C" JNIEXPORT jfloatArray JNICALL Java_app_croptop_core_engine_NativeImageOpsBridge_nativeLutTexture(JNIEnv* env, jobject, jbyteArray bytes, jstring file_name) {
    g_ops_error.clear();
    try {
        const auto lut = parseLutBytes(env, bytes, file_name);
        auto texels = lut->toTexture();
        if (texels.empty()) throw std::invalid_argument("the LUT has no 3D table");
        std::vector<jfloat> out;
        out.reserve(texels.size() + 1U);
        out.push_back(static_cast<jfloat>(lut->size_3d));
        out.insert(out.end(), texels.begin(), texels.end());
        auto array = env->NewFloatArray(static_cast<jsize>(out.size()));
        if (array != nullptr) env->SetFloatArrayRegion(array, 0, static_cast<jsize>(out.size()), out.data());
        return array;
    } catch (const std::exception& e) {
        g_ops_error = e.what();
    } catch (...) {
        g_ops_error = "LUT failure";
    }
    return nullptr;
}

// ── Lens Correction (manual) ───────────────────────────────────────────────────────────────────────────────────────────────────

/** params: distortion, redCyan, blueYellow, vignetteAmount, vignetteMidpoint, verticalPerspective, horizontalPerspective, angle, scale, edgeExtend(0/1). */
extern "C" JNIEXPORT jlong JNICALL Java_app_croptop_core_engine_NativeImageOpsBridge_nativeLensCorrection(JNIEnv* env, jobject, jlong store_handle, jfloatArray params, jlong mask_handle,
                                                                                                          jint worker_threads) {
    return guarded("lens correction failure", [&]() -> jlong {
        if (params == nullptr || env->GetArrayLength(params) != 10) throw std::invalid_argument("lens correction takes 10 parameters");
        jfloat p[10];
        env->GetFloatArrayRegion(params, 0, 10, p);
        croptop::filters::LensCorrectionSpec spec;
        spec.distortion = p[0];
        spec.red_cyan = p[1];
        spec.blue_yellow = p[2];
        spec.vignette_amount = p[3];
        spec.vignette_midpoint = p[4];
        spec.vertical_perspective = p[5];
        spec.horizontal_perspective = p[6];
        spec.angle = p[7];
        spec.scale = p[8];
        spec.edge_extend = p[9] != 0.0F;
        auto result = std::make_unique<Store>(storeOf(store_handle, "layer store"));
        const auto* mask = optionalStore(mask_handle);
        std::string error;
        if (!croptop::filters::lensCorrection(result->pixels, spec, mask != nullptr ? &mask->pixels : nullptr, error, static_cast<unsigned>(std::max(0, worker_threads)))) throw std::invalid_argument(error);
        return reinterpret_cast<jlong>(result.release());
    });
}
#endif  // __ANDROID__
