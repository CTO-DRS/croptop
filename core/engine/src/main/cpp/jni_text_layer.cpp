// Text layers: lays out and rasterises a text block into a new document-sized store (the text layer's
// render cache) with font fallback across the given font files (first = primary). Fonts are loaded once per path.
// Returns [store handle, left, top, right, bottom] or null (see nativeTextLastError).
#include <jni.h>

#include <algorithm>
#include <array>
#include <cmath>
#include <fstream>
#include <iterator>
#include <map>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <string>
#include <vector>

#include "native_store_handle.h"
#include "text_layout.h"
#include "truetype_font.h"

namespace {

using croptop::text::TrueTypeFont;
thread_local std::string g_text_error;
std::mutex g_font_mutex;
std::map<std::string, std::shared_ptr<const TrueTypeFont>> g_fonts;

std::shared_ptr<const TrueTypeFont> loadFont(const std::string& path) {
    const std::lock_guard lock(g_font_mutex);
    if (const auto it = g_fonts.find(path); it != g_fonts.end()) return it->second;
    std::ifstream in(path, std::ios::binary);
    if (!in) return nullptr;
    std::vector<std::uint8_t> bytes((std::istreambuf_iterator<char>(in)), std::istreambuf_iterator<char>());
    if (bytes.empty() || bytes.size() > (64U << 20)) return nullptr;
    std::string error;
    auto font = TrueTypeFont::load(bytes, error);
    if (font) g_fonts.emplace(path, font);
    return font;
}

using Axes = std::vector<std::pair<std::string, float>>;
std::map<std::string, std::shared_ptr<const TrueTypeFont>> g_varied;

/** `font` at [axes] (variable fonts only), cached by file and axis values so repeated renders share the instance. */
std::shared_ptr<const TrueTypeFont> variedFont(const std::string& path, const std::shared_ptr<const TrueTypeFont>& font, const Axes& axes) {
    if (axes.empty() || font->variationAxes().empty()) return font;
    std::string key = path;
    for (const auto& [tag, value] : axes) key += "|" + tag + "=" + std::to_string(value);
    const std::lock_guard lock(g_font_mutex);
    if (const auto it = g_varied.find(key); it != g_varied.end()) return it->second;
    if (g_varied.size() >= 64) g_varied.clear();  // a few instances per document is typical; bound the rest
    auto varied = font->withVariation(axes);
    g_varied.emplace(key, varied);
    return varied;
}

std::string jstr(JNIEnv* env, jstring s) {
    if (s == nullptr) return {};
    const char* chars = env->GetStringUTFChars(s, nullptr);
    std::string out = chars != nullptr ? chars : "";
    if (chars != nullptr) env->ReleaseStringUTFChars(s, chars);
    return out;
}

}  // namespace

extern "C" JNIEXPORT jstring JNICALL Java_app_croptop_core_engine_NativeEngine_nativeTextLastError(JNIEnv* env, jobject) {
    return env->NewStringUTF(g_text_error.c_str());
}

extern "C" JNIEXPORT jlongArray JNICALL Java_app_croptop_core_engine_NativeEngine_nativeRenderTextLayer(
    JNIEnv* env, jobject, jlong template_handle, jobjectArray font_paths, jstring text, jfloat size_px, jfloat leading_px, jfloat tracking,
    jint align, jfloat box_width, jfloat x, jfloat y, jfloat red, jfloat green, jfloat blue, jfloat alpha, jobjectArray axis_tags,
    jfloatArray axis_values) {
    g_text_error.clear();
    try {
        const auto* templ = reinterpret_cast<const croptop::jni::NativeTileStore*>(template_handle);
        if (templ == nullptr || font_paths == nullptr) throw std::invalid_argument("text layer arguments are missing");
        if (!std::isfinite(size_px) || size_px < 1 || size_px > 4000) throw std::invalid_argument("font size must be 1–4000 px");
        if (align < 0 || align > 4) throw std::invalid_argument("unknown text alignment");
        Axes axes;
        if (axis_tags != nullptr && axis_values != nullptr) {
            const jsize axis_count = std::min(env->GetArrayLength(axis_tags), env->GetArrayLength(axis_values));
            std::vector<jfloat> values(static_cast<std::size_t>(std::max<jsize>(axis_count, 0)));
            if (axis_count > 0) env->GetFloatArrayRegion(axis_values, 0, axis_count, values.data());
            for (jsize i = 0; i < axis_count && i < 16; ++i) {
                auto tag = jstr(env, static_cast<jstring>(env->GetObjectArrayElement(axis_tags, i)));
                if (tag.size() == 4 && std::isfinite(values[static_cast<std::size_t>(i)])) axes.emplace_back(std::move(tag), values[static_cast<std::size_t>(i)]);
            }
        }
        std::vector<std::shared_ptr<const TrueTypeFont>> fonts;
        const jsize n = env->GetArrayLength(font_paths);
        for (jsize i = 0; i < n && i < 16; ++i) {
            auto path = jstr(env, static_cast<jstring>(env->GetObjectArrayElement(font_paths, i)));
            if (auto font = loadFont(path)) fonts.push_back(variedFont(path, font, axes));
        }
        if (fonts.empty()) throw std::runtime_error("no usable font was found");
        const auto utf8 = jstr(env, text);
        croptop::text::FallbackShaper shaper(fonts);
        croptop::text::ParagraphStyle style;
        style.size_px = size_px;
        style.leading_px = std::isfinite(leading_px) ? std::max(0.0F, leading_px) : 0.0F;
        style.tracking = std::isfinite(tracking) ? tracking : 0.0F;
        style.align = static_cast<croptop::text::Align>(align);
        const float width = std::isfinite(box_width) && box_width > 0 ? box_width : 0.0F;
        const auto laid = croptop::text::layout(utf8, style, width, shaper);
        auto out = std::make_unique<croptop::jni::NativeTileStore>(templ->pixels.emptyLike(templ->pixels.format()));
        const std::array<float, 4> color{std::clamp(red, 0.0F, 1.0F), std::clamp(green, 0.0F, 1.0F), std::clamp(blue, 0.0F, 1.0F), std::clamp(alpha, 0.0F, 1.0F)};
        std::string error;
        if (!utf8.empty() && !croptop::text::renderText(out->pixels, laid, fonts, color, x, y, error)) throw std::runtime_error(error.empty() ? "could not render the text" : error);
        float ink = width;
        for (const auto& line : laid.lines) ink = std::max(ink, line.width);
        const std::array<jlong, 5> result{reinterpret_cast<jlong>(out.get()), static_cast<jlong>(std::floor(x)), static_cast<jlong>(std::floor(y)),
                                          static_cast<jlong>(std::ceil(x + ink)), static_cast<jlong>(std::ceil(y + laid.height))};
        auto array = env->NewLongArray(5);
        if (array == nullptr) throw std::runtime_error("could not return the text layer");
        env->SetLongArrayRegion(array, 0, 5, result.data());
        out.release();
        return array;
    } catch (const std::exception& e) {
        g_text_error = e.what();
    }
    return nullptr;
}
