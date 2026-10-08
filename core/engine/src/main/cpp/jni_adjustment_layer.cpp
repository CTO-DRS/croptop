// Adjustment layers: bakes a NativeEngine value block into a 33³ LUT and attaches it to the layer's
// (empty, document-sized) store, which RenderGraph then applies to the composite below. Returns null on success,
// else the error. Spatial adjustments (shadows/highlights) have no LUT and are rejected.
#include <jni.h>

#include <memory>
#include <stdexcept>
#include <string>
#include <vector>

#include "adjustment_lut_bridge.h"
#include "native_store_handle.h"

extern "C" JNIEXPORT jstring JNICALL Java_app_croptop_core_engine_NativeEngine_nativeSetAdjustmentLayerLut(JNIEnv* env, jobject, jlong store_handle,
                                                                                                           jstring spec_id, jfloatArray values) {
    std::string error;
    try {
        auto* store = reinterpret_cast<croptop::jni::NativeTileStore*>(store_handle);
        if (store == nullptr || spec_id == nullptr || values == nullptr) throw std::invalid_argument("adjustment layer arguments are missing");
        const char* chars = env->GetStringUTFChars(spec_id, nullptr);
        if (chars == nullptr) throw std::runtime_error("could not read the adjustment id");
        const std::string spec(chars);
        env->ReleaseStringUTFChars(spec_id, chars);
        const jsize n = env->GetArrayLength(values);
        if (n < 0 || n > 4096) throw std::invalid_argument("adjustment values are out of range");
        std::vector<float> block(static_cast<std::size_t>(n));
        if (n > 0) env->GetFloatArrayRegion(values, 0, n, block.data());
        auto lut = croptop::adjust::bridge::bakeBlock(spec, block, 33, error);
        if (!lut) throw std::invalid_argument(error.empty() ? "this adjustment can't be used as an adjustment layer" : error);
        store->adjustment = std::make_shared<const croptop::color::Lut>(std::move(*lut));
        error.clear();
    } catch (const std::exception& e) {
        error = e.what();
    } catch (...) {
        error = "unknown adjustment layer failure";
    }
    return error.empty() ? nullptr : env->NewStringUTF(error.c_str());
}
