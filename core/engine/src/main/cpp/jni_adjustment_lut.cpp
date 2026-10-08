// Optional GPU-preview LUT bridge; CPU apply/commit remains in NativeEngine.
#include <jni.h>

#include <exception>
#include <limits>
#include <string>
#include <vector>

#include "adjustment_lut_bridge.h"

namespace {
thread_local std::string last_error;
jstring toJava(JNIEnv* env, const std::string& text) { return env->NewStringUTF(text.c_str()); }
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_app_croptop_core_engine_NativeAdjustmentLutBridge_nativeBakeAdjustmentLut(
    JNIEnv* env, jobject, jstring spec_id, jfloatArray encoded_values, jint lut_size) {
    last_error.clear();
    if (spec_id == nullptr || encoded_values == nullptr) { last_error="Invalid adjustment LUT arguments"; return nullptr; }
    const char* utf=env->GetStringUTFChars(spec_id,nullptr);
    if(utf==nullptr){last_error="Could not read adjustment id";return nullptr;}
    const jsize count=env->GetArrayLength(encoded_values);
    if(count<0||count>520){env->ReleaseStringUTFChars(spec_id,utf);last_error="Adjustment block is out of range";return nullptr;}
    std::vector<float> block(static_cast<std::size_t>(count));
    if (count > 0) env->GetFloatArrayRegion(encoded_values,0,count,block.data());
    const std::string id(utf);
    env->ReleaseStringUTFChars(spec_id,utf);
    if(env->ExceptionCheck()){env->ExceptionClear();last_error="Could not read adjustment values";return nullptr;}
    try {
        auto lut=croptop::adjust::bridge::bakeBlock(id,block,static_cast<std::uint32_t>(lut_size),last_error);
        if(!lut)return nullptr;
        const auto data=lut->toTexture();
        if(data.size()>static_cast<std::size_t>(std::numeric_limits<jsize>::max())){last_error="Baked LUT is too large for JNI";return nullptr;}
        auto result=env->NewFloatArray(static_cast<jsize>(data.size()));
        if(result==nullptr){last_error="Could not allocate LUT result";return nullptr;}
        env->SetFloatArrayRegion(result,0,static_cast<jsize>(data.size()),data.data());
        if(env->ExceptionCheck()){env->ExceptionClear();last_error="Could not copy LUT result";return nullptr;}
        return result;
    } catch(const std::exception& e){last_error=e.what();return nullptr;}
    catch(...){last_error="Unknown native LUT bake failure";return nullptr;}
}

extern "C" JNIEXPORT jstring JNICALL
Java_app_croptop_core_engine_NativeAdjustmentLutBridge_nativeAdjustmentLutLastError(JNIEnv* env,jobject){
    return toJava(env,last_error);
}
