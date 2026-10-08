// Batched native adjustment application for the NativeDocument preview transaction.
#include <jni.h>

#include <algorithm>
#include <array>
#include <cmath>
#include <cstddef>
#include <string>
#include <vector>

#include "adjustments.h"
#include "native_store_handle.h"

namespace {

jstring error(JNIEnv* env, const char* message) { return env->NewStringUTF(message); }

bool finite(const std::vector<float>& values) {
    for (const auto value : values) if (!std::isfinite(value)) return false;
    return true;
}

bool readCurves(const std::vector<float>& values, std::size_t& cursor, croptop::adjust::Curves& curves) {
    std::array<croptop::adjust::Curve*, 4> output{&curves.rgb, &curves.red, &curves.green, &curves.blue};
    for (auto* curve : output) {
        if (cursor >= values.size()) return false;
        const auto count = values[cursor++];
        if (count < 2.0F || count > 64.0F || std::floor(count) != count) return false;
        const auto point_count = static_cast<std::size_t>(count);
        if (values.size() - cursor < point_count * 2U) return false;
        curve->points.clear();
        curve->points.reserve(point_count);
        float previous_x = -1.0F;
        for (std::size_t i = 0; i < point_count; ++i) {
            const auto x = values[cursor++];
            const auto y = values[cursor++];
            if (x < 0.0F || x > 1.0F || y < 0.0F || y > 1.0F || x <= previous_x) return false;
            curve->points.emplace_back(x, y);
            previous_x = x;
        }
    }
    return cursor == values.size();
}

}  // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_app_croptop_core_engine_NativeEngine_nativeApplyAdjustment(
    JNIEnv* env, jobject, jlong handle, jstring spec_id, jfloatArray encoded_values, jlong selection_handle) {
    auto* store = reinterpret_cast<croptop::jni::NativeTileStore*>(handle);
    auto* mask = reinterpret_cast<croptop::jni::NativeTileStore*>(selection_handle);
    if (store == nullptr || spec_id == nullptr || encoded_values == nullptr) return error(env, "Invalid adjustment arguments");
    const char* spec = env->GetStringUTFChars(spec_id, nullptr);
    if (spec == nullptr) return error(env, "Could not read adjustment id");
    const auto count = env->GetArrayLength(encoded_values);
    if (count < 0 || count > 520) {
        env->ReleaseStringUTFChars(spec_id, spec);
        return error(env, "Adjustment parameter block is out of range");
    }
    std::vector<float> values(static_cast<std::size_t>(count));
    env->GetFloatArrayRegion(encoded_values, 0, count, values.data());
    if (env->ExceptionCheck()) {
        env->ReleaseStringUTFChars(spec_id, spec);
        return error(env, "Could not read adjustment parameters");
    }
    if (!finite(values)) {
        env->ReleaseStringUTFChars(spec_id, spec);
        return error(env, "Adjustment parameters must be finite");
    }

    croptop::adjust::Adjustment adjustment;
    const std::string id(spec);
    env->ReleaseStringUTFChars(spec_id, spec);
    if (id == "shadowsHighlights" && values.size() == 2U) {
        std::string message;
        if (!croptop::adjust::shadowsHighlights(
                store->pixels, croptop::adjust::ShadowsHighlights{values[0], values[1]}, mask ? &mask->pixels : nullptr, 1.0F, message)) {
            return error(env, message.empty() ? "Native adjustment failed" : message.c_str());
        }
        return nullptr;
    }
    if (id == "brightnessContrast" && values.size() == 3U) {
        adjustment = croptop::adjust::BrightnessContrast{values[0], values[1], values[2] != 0.0F};
    } else if (id == "exposure" && values.size() == 3U) {
        adjustment = croptop::adjust::Exposure{values[0], values[1], values[2]};
    } else if (id == "vibrance" && values.size() == 2U) {
        adjustment = croptop::adjust::Vibrance{values[0], values[1]};
    } else if (id == "invert" && values.empty()) {
        adjustment = croptop::adjust::Invert{};
    } else if (id == "posterize" && values.size() == 1U) {
        adjustment = croptop::adjust::Posterize{values[0]};
    } else if (id == "threshold" && values.size() == 1U) {
        adjustment = croptop::adjust::Threshold{values[0]};
    } else if (id == "levels" && values.size() == 5U) {
        adjustment = croptop::adjust::Levels{values[0], values[1], values[2], values[3], values[4]};
    } else if (id == "curves") {
        croptop::adjust::Curves curves;
        std::size_t cursor = 0;
        if (!readCurves(values, cursor, curves)) return error(env, "Invalid curve channels");
        adjustment = std::move(curves);
    } else if (id == "hueSaturation" && values.size() == 46U) {
        croptop::adjust::HueSaturation hue_sat;
        hue_sat.master = {values[0], values[1], values[2]};
        hue_sat.colorize = values[3] != 0.0F;
        std::size_t cursor = 4;
        for (auto& channel : hue_sat.channels) {
            channel = {values[cursor], values[cursor + 1], values[cursor + 2]};
            cursor += 3;
        }
        for (auto& range : hue_sat.ranges) {
            range = {values[cursor], values[cursor + 1], values[cursor + 2], values[cursor + 3]};
            cursor += 4;
        }
        adjustment = std::move(hue_sat);
    } else if (id == "colorBalance" && values.size() == 10U) {
        croptop::adjust::ColorBalance balance;
        balance.shadows = {values[0], values[1], values[2]};
        balance.midtones = {values[3], values[4], values[5]};
        balance.highlights = {values[6], values[7], values[8]};
        balance.preserve_luminosity = values[9] != 0.0F;
        adjustment = balance;
    } else if (id == "blackWhite" && values.size() == 10U) {
        adjustment = croptop::adjust::BlackWhite{
            values[0], values[1], values[2], values[3], values[4], values[5], values[6] != 0.0F, {values[7], values[8], values[9]}};
    } else if (id == "photoFilter" && values.size() == 6U) {
        croptop::adjust::PhotoFilter filter;
        const auto preset = static_cast<int>(values[0]);
        if (preset == 0) filter.color = {236.0F / 255.0F, 138.0F / 255.0F, 0.0F};
        else if (preset == 1) filter.color = {0.0F, 109.0F / 255.0F, 1.0F};
        else if (preset == 2) filter.color = {172.0F / 255.0F, 122.0F / 255.0F, 51.0F / 255.0F};
        else filter.color = {values[1], values[2], values[3]};
        filter.density = values[4];
        filter.preserve_luminosity = values[5] != 0.0F;
        adjustment = filter;
    } else if (id == "channelMixer" && values.size() == 6U) {
        croptop::adjust::ChannelMixer mixer;
        const auto selected_output = static_cast<std::size_t>(values[0]);
        if (selected_output > 2U) return error(env, "Invalid Channel Mixer output");
        const auto output = values[5] != 0.0F ? 0U : selected_output;
        mixer.matrix[output] = {values[1], values[2], values[3]};
        mixer.constant[output] = values[4];
        mixer.monochrome = values[5] != 0.0F;
        adjustment = mixer;
    } else if (id == "gradientMap") {
        if (values.size() < 13U || values[0] < 0.0F || values[0] > 1.0F || std::floor(values[0]) != values[0] ||
            values[1] < 0.0F || values[1] > 1.0F || std::floor(values[1]) != values[1]) {
            return error(env, "Invalid Gradient Map options");
        }
        const auto count_stops = values[2];
        if (count_stops < 2.0F || count_stops > 64.0F || std::floor(count_stops) != count_stops ||
            values.size() != 3U + static_cast<std::size_t>(count_stops) * 5U) {
            return error(env, "Invalid Gradient Map stops");
        }
        croptop::adjust::GradientMap map;
        map.reverse = values[0] != 0.0F;
        map.dither = values[1] != 0.0F;
        map.stops.clear();
        std::size_t cursor = 3;
        for (std::size_t i = 0; i < static_cast<std::size_t>(count_stops); ++i) {
            croptop::adjust::GradientStop stop{values[cursor], {values[cursor + 1], values[cursor + 2], values[cursor + 3], values[cursor + 4]}};
            if (stop.position < 0.0F || stop.position > 1.0F ||
                std::any_of(stop.color.begin(), stop.color.end(), [](float c) { return c < 0.0F || c > 1.0F; })) {
                return error(env, "Gradient Map stops must use normalized values");
            }
            map.stops.push_back(stop);
            cursor += 5;
        }
        adjustment = std::move(map);
    } else if (id == "selectiveColor" && values.size() == 6U) {
        if (values[0] < 0.0F || values[0] > 8.0F || std::floor(values[0]) != values[0]) {
            return error(env, "Invalid Selective Color range");
        }
        croptop::adjust::SelectiveColor selected;
        selected.absolute = values[5] != 0.0F;
        selected.cmyk[static_cast<std::size_t>(values[0])] = {values[1], values[2], values[3], values[4]};
        adjustment = selected;
    } else {
        return error(env, "This adjustment is not supported by the native engine");
    }

    std::string message;
    if (!croptop::adjust::apply(store->pixels, adjustment, mask ? &mask->pixels : nullptr, 1.0F, message)) {
        return error(env, message.empty() ? "Native adjustment failed" : message.c_str());
    }
    return nullptr;
}
