// Layered PSD/PSB opening into a document layer tree.
#include "psd_import.h"

#include <cmath>

#include "layer_styles_codec.h"
#include "psd_descriptor.h"
#include "psd_layer_data.h"

#include <algorithm>
#include <span>
#include <optional>
#include <cstdlib>
#include <cstring>
#include <cstdio>
#include <map>

#include "pixel_plane.h"
#include "psd_reader.h"

namespace croptop::psd {
namespace {

std::string blendKey(BlendMode mode) {
    static const std::map<BlendMode, std::string> kKeys{
        {BlendMode::Normal, "norm"},      {BlendMode::Dissolve, "diss"},     {BlendMode::Darken, "dark"},
        {BlendMode::Multiply, "mul "},    {BlendMode::ColorBurn, "idiv"},    {BlendMode::LinearBurn, "lbrn"},
        {BlendMode::DarkerColor, "dkCl"}, {BlendMode::Lighten, "lite"},      {BlendMode::Screen, "scrn"},
        {BlendMode::ColorDodge, "div "},  {BlendMode::LinearDodge, "lddg"},  {BlendMode::LighterColor, "lgCl"},
        {BlendMode::Overlay, "over"},     {BlendMode::SoftLight, "sLit"},    {BlendMode::HardLight, "hLit"},
        {BlendMode::VividLight, "vLit"},  {BlendMode::LinearLight, "lLit"},  {BlendMode::PinLight, "pLit"},
        {BlendMode::HardMix, "hMix"},     {BlendMode::Difference, "diff"},   {BlendMode::Exclusion, "smud"},
        {BlendMode::Subtract, "fsub"},    {BlendMode::Divide, "fdiv"},       {BlendMode::Hue, "hue "},
        {BlendMode::Saturation, "sat "},  {BlendMode::Color, "colr"},        {BlendMode::Luminosity, "lum "}};
    const auto it = kKeys.find(mode);
    return it == kKeys.end() ? "norm" : it->second;
}

bool hasKey(const Layer& layer, std::initializer_list<const char*> keys) {
    for (const auto& info : layer.extra)
        for (const char* k : keys)
            if (info.key == k) return true;
    return false;
}

double beDouble(const std::vector<std::uint8_t>& d, std::size_t at) {
    std::uint64_t bits = 0;
    for (std::size_t i = 0; i < 8; ++i) bits = (bits << 8) | d[at + i];
    double value = 0;
    std::memcpy(&value, &bits, sizeof value);
    return std::isfinite(value) ? value : 0.0;
}

/** First number after `key` in EngineData text (e.g. "/FontSize 36.0"). */
std::optional<double> engineNumber(const std::string& engine, const std::string& key, std::size_t from = 0) {
    const auto at = engine.find(key, from);
    if (at == std::string::npos) return std::nullopt;
    const char* begin = engine.c_str() + at + key.size();
    char* end = nullptr;
    const double value = std::strtod(begin, &end);
    if (end == begin || !std::isfinite(value)) return std::nullopt;
    return value;
}

/** The first `/Name (…)` string in EngineData (UTF-16BE with a BOM, backslash escapes), as ASCII. */
std::string engineFirstName(const std::string& engine) {
    const auto at = engine.find("/Name (");
    if (at == std::string::npos) return {};
    std::string bytes;
    for (std::size_t i = at + 7; i < engine.size() && bytes.size() < 512; ++i) {
        char c = engine[i];
        if (c == '\\' && i + 1 < engine.size()) c = engine[++i];
        else if (c == ')') break;
        bytes += c;
    }
    std::string out;
    const bool utf16 = bytes.size() >= 2 && static_cast<unsigned char>(bytes[0]) == 0xFE && static_cast<unsigned char>(bytes[1]) == 0xFF;
    for (std::size_t i = utf16 ? 2 : 0; i < bytes.size(); i += utf16 ? 2 : 1) {
        const unsigned char c = static_cast<unsigned char>(bytes[utf16 && i + 1 < bytes.size() ? i + 1 : i]);
        if (c >= 0x20 && c < 0x7F) out += static_cast<char>(c);
    }
    return out;
}

ImportedText textOf(const Layer& layer) {
    ImportedText text;
    for (const auto& info : layer.extra) {
        if (info.key != "TySh" || info.data.size() < 2 + 48 + 2 + 4) continue;
        const auto& d = info.data;
        const double xx = beDouble(d, 2);
        const double xy = beDouble(d, 2 + 8);
        text.x = beDouble(d, 2 + 32);
        text.y = beDouble(d, 2 + 40);
        std::string error;
        const auto desc = parseDescriptor(std::span<const std::uint8_t>(d).subspan(2 + 48 + 2 + 4), error);
        if (!desc) return text;
        text.text = desc->text_or("Txt ");
        if (text.text.empty()) return text;
        std::string engine;
        if (const auto* data = desc->find("EngineData"); data != nullptr) engine.assign(data->raw.begin(), data->raw.end());
        // Similarity transform: x' = xx·x + yx·y + tx, y' = xy·x + yy·y + ty.
        const double scale_raw = std::hypot(xx, xy);
        const double scale = scale_raw > 1e-6 ? scale_raw : 1.0;
        text.rotation = scale_raw > 1e-6 ? std::atan2(xy, xx) * 180.0 / 3.14159265358979323846 : 0.0;
        text.size = engineNumber(engine, "/FontSize ").value_or(12.0) * scale;
        text.align = static_cast<int>(engineNumber(engine, "/Justification ").value_or(0.0));
        if (const auto fill = engine.find("/FillColor"); fill != std::string::npos) {
            if (const auto values = engine.find("/Values [", fill); values != std::string::npos && values - fill < 200) {
                // [ alpha r g b ]
                std::size_t from = values + 9;
                double v[4] = {1, 0, 0, 0};
                for (double& component : v) {
                    const char* begin = engine.c_str() + from;
                    char* end = nullptr;
                    component = std::strtod(begin, &end);
                    if (end == begin) break;
                    from = static_cast<std::size_t>(end - engine.c_str());
                }
                for (int i = 0; i < 3; ++i) text.color[i] = static_cast<float>(std::clamp(v[i + 1], 0.0, 1.0));
            }
        }
        text.font = engineFirstName(engine);
        // Paragraph (box) text: EngineData carries "/BoxBounds [ 0.0 0.0 width height ]"; point text has none.
        if (const auto box = engineNumber(engine, "/BoxBounds [ 0.0 0.0 "); box && *box > 0) text.box_width = *box * scale;
        // Photoshop CR line breaks become newlines.
        std::replace(text.text.begin(), text.text.end(), '\r', '\n');
        text.present = true;
        return text;
    }
    return text;
}

std::string adjustmentKey(const Layer& layer) {
    static const char* kAdjustments[] = {"levl", "curv", "hue2", "hue ", "blnc", "brit", "CgEd", "nvrt", "post", "thrs", "grdm", "selc", "mixr",
                                         "expA", "vibA", "blwh", "phfl", "clrL", "SoCo", "GdFl", "PtFl"};
    for (const auto& info : layer.extra)
        for (const char* k : kAdjustments)
            if (info.key == k) return info.key;
    return {};
}

/** A cleared copy of `like` (same format, pool and memory budget) filled from `source`, converting the format. */
TileStore storeLike(const TileStore& like, const TileStore& source) {
    TileStore out(like);
    out.clear();
    const auto sb = source.bytes_per_pixel(), db = out.bytes_per_pixel();
    for (const auto c : source.tileCoordinates()) {
        const auto tile = source.readTile(c);
        if (!tile) continue;
        if (source.format() == out.format()) {
            out.writeTile(c, *tile);
            continue;
        }
        TileBuffer converted(out.tile_byte_size(), 0);
        if (out.format() == PixelFormat::A16 || source.format() == PixelFormat::A16) {
            // Masks: only A16 → A16 is meaningful; anything else is copied as-is by value range.
            continue;
        }
        for (std::size_t i = 0; i < static_cast<std::size_t>(kTileEdge) * kTileEdge; ++i) {
            float px[4];
            paint::detail::readPixel(source.format(), tile->data() + i * sb, px);
            paint::detail::writePixel(out.format(), converted.data() + i * db, px);
        }
        out.writeTile(c, converted);
    }
    return out;
}

ImportedLayer convert(const Layer& layer, const TileStore& pixel_template, const TileStore* mask_template) {
    ImportedLayer out;
    out.name = layer.name;
    out.blend_key = layer.kind == LayerKind::GroupFolder && layer.pass_through ? "pass" : blendKey(layer.mode);
    out.opacity = layer.opacity;
    out.fill = layer.fill;
    out.visible = layer.visible;
    out.clipped = layer.clipped;
    out.lock_transparency = layer.lock_transparency;
    out.blend_ranges = layer.blend_ranges;
    out.group_open = layer.group_open;
    if (layer.kind == LayerKind::GroupFolder) {
        out.kind = ImportKind::Group;
    } else if (const auto key = adjustmentKey(layer); !key.empty() && !layer.pixels) {
        out.kind = ImportKind::Adjustment;
        out.adjustment_key = key;
        for (const auto& info : layer.extra) {
            if (info.key == key && info.data.size() <= 64U * 1024U) {
                out.adjustment_data = info.data;
                break;
            }
        }
    } else if (hasKey(layer, {"TySh", "tySh"})) {
        out.kind = ImportKind::Text;
        out.text = textOf(layer);
    } else if (hasKey(layer, {"SoLd", "PlLd", "SoLE"})) {
        out.kind = ImportKind::SmartObject;
    }
    if (layer.pixels && out.kind != ImportKind::Group && out.kind != ImportKind::Adjustment) out.pixels.emplace(storeLike(pixel_template, *layer.pixels));
    if (out.kind != ImportKind::Group && out.kind != ImportKind::Adjustment) {
        std::vector<std::string> ignored;
        if (const auto effects = effectsOf(layer, GlobalLight{}, ignored)) out.styles = styles::encodeStyles(*effects);
    }
    if (layer.mask) {
        out.mask.emplace(mask_template != nullptr ? storeLike(*mask_template, layer.mask->coverage) : layer.mask->coverage);
        out.mask_default = layer.mask->default_color;
        out.mask_disabled = layer.mask->disabled;
    }
    return out;
}

void appendEscaped(std::string& out, const std::string& s) {
    out += '"';
    for (const unsigned char c : s) {
        if (c == '"' || c == '\\') {
            out += '\\';
            out += static_cast<char>(c);
        } else if (c < 0x20) {
            char buffer[8];
            std::snprintf(buffer, sizeof(buffer), "\\u%04x", c);
            out += buffer;
        } else {
            out += static_cast<char>(c);
        }
    }
    out += '"';
}

void appendLayer(std::string& out, const ImportedLayer& layer, std::vector<const TileStore*>& handles) {
    static const char* kKinds[] = {"pixel", "group", "text", "smartObject", "adjustment"};
    out += "{\"kind\":\"";
    out += kKinds[static_cast<int>(layer.kind)];
    out += "\",\"name\":";
    appendEscaped(out, layer.name);
    out += ",\"blend\":";
    appendEscaped(out, layer.blend_key);
    out += ",\"opacity\":" + std::to_string(layer.opacity) + ",\"fill\":" + std::to_string(layer.fill);
    out += std::string(",\"visible\":") + (layer.visible ? "true" : "false") + ",\"clipped\":" + (layer.clipped ? "true" : "false");
    out += ",\"blendIf\":[";
    for (std::size_t i = 0; i < layer.blend_ranges.size(); ++i) out += (i == 0 ? "" : ",") + std::to_string(layer.blend_ranges[i]);
    out += "]";
    out += std::string(",\"lockTransparency\":") + (layer.lock_transparency ? "true" : "false") + ",\"open\":" + (layer.group_open ? "true" : "false");
    int pixels = -1, mask = -1;
    if (layer.pixels) {
        pixels = static_cast<int>(handles.size());
        handles.push_back(&*layer.pixels);
    }
    if (layer.mask) {
        mask = static_cast<int>(handles.size());
        handles.push_back(&*layer.mask);
    }
    out += ",\"pixels\":" + std::to_string(pixels) + ",\"mask\":" + std::to_string(mask) + ",\"maskDefault\":" + std::to_string(layer.mask_default);
    out += std::string(",\"maskDisabled\":") + (layer.mask_disabled ? "true" : "false");
    out += ",\"adjustment\":";
    appendEscaped(out, layer.adjustment_key);
    out += ",\"adjustmentData\":\"";
    static const char* kHex = "0123456789abcdef";
    for (const auto byte : layer.adjustment_data) {
        out += kHex[byte >> 4];
        out += kHex[byte & 15];
    }
    out += '"';
    out += ",\"styles\":[";
    for (std::size_t i = 0; i < layer.styles.size(); ++i) {
        if (i > 0) out += ',';
        out += std::isfinite(layer.styles[i]) ? std::to_string(layer.styles[i]) : std::string("0");
    }
    out += ']';
    if (layer.text.present) {
        out += ",\"text\":{\"text\":";
        appendEscaped(out, layer.text.text);
        out += ",\"font\":";
        appendEscaped(out, layer.text.font);
        out += ",\"size\":" + std::to_string(layer.text.size) + ",\"x\":" + std::to_string(layer.text.x) + ",\"y\":" + std::to_string(layer.text.y);
        out += ",\"align\":" + std::to_string(layer.text.align) + ",\"boxWidth\":" + std::to_string(layer.text.box_width);
        out += ",\"rotation\":" + std::to_string(layer.text.rotation);
        out += ",\"color\":[" + std::to_string(layer.text.color[0]) + "," + std::to_string(layer.text.color[1]) + "," + std::to_string(layer.text.color[2]) + "]}";
    }
    out += ",\"children\":[";
    for (std::size_t i = 0; i < layer.children.size(); ++i) {
        if (i > 0) out += ',';
        appendLayer(out, layer.children[i], handles);
    }
    out += "]}";
}

}  // namespace

std::optional<ImportedDocument> importLayers(std::span<const std::uint8_t> file, const TileStore& pixel_template, const TileStore* mask_template, std::string& error) {
    if (!paint::detail::isRgba(pixel_template.format())) {
        error = "the pixel template must be an RGBA store";
        return std::nullopt;
    }
    if (mask_template != nullptr && mask_template->format() != PixelFormat::A16) {
        error = "the mask template must be an A16 store";
        return std::nullopt;
    }
    auto document = read(file, error);
    if (!document) return std::nullopt;
    if (document->width != pixel_template.width() || document->height != pixel_template.height() ||
        (mask_template != nullptr && (mask_template->width() != document->width || mask_template->height() != document->height))) {
        error = "the templates must match the document size";
        return std::nullopt;
    }
    ImportedDocument out;
    out.width = document->width;
    out.height = document->height;
    out.depth = document->depth;
    out.icc_profile = document->icc_profile;
    out.warnings = document->warnings;
    if (document->layers.empty()) {
        if (!document->composite) {
            error = "the document has neither layers nor a merged image";
            return std::nullopt;
        }
        ImportedLayer background;
        background.name = "Background";
        background.pixels.emplace(storeLike(pixel_template, *document->composite));
        out.layers.push_back(std::move(background));
        return out;
    }
    // File order is bottom to top; a GroupDivider opens a group that the matching GroupFolder closes (and names).
    // Build bottom-to-top lists per open group, then reverse into panel order.
    std::vector<std::vector<ImportedLayer>> stack(1);
    for (const auto& layer : document->layers) {
        if (layer.kind == LayerKind::GroupDivider) {
            stack.emplace_back();
            continue;
        }
        auto converted = convert(layer, pixel_template, mask_template);
        if (layer.kind == LayerKind::GroupFolder) {
            if (stack.size() > 1) {
                converted.children = std::move(stack.back());
                std::reverse(converted.children.begin(), converted.children.end());
                stack.pop_back();
            } else {
                out.warnings.push_back("a group without an opening marker was imported empty");
            }
        }
        stack.back().push_back(std::move(converted));
    }
    while (stack.size() > 1) {  // unterminated groups: keep their layers at the enclosing level
        out.warnings.push_back("an unterminated layer group was flattened into its parent");
        auto orphans = std::move(stack.back());
        stack.pop_back();
        for (auto& l : orphans) stack.back().push_back(std::move(l));
    }
    out.layers = std::move(stack.back());
    std::reverse(out.layers.begin(), out.layers.end());
    return out;
}

std::string toJson(const ImportedDocument& document, std::vector<const TileStore*>& handles) {
    std::string out = "{\"width\":" + std::to_string(document.width) + ",\"height\":" + std::to_string(document.height) +
                      ",\"depth\":" + std::to_string(document.depth) + ",\"layers\":[";
    for (std::size_t i = 0; i < document.layers.size(); ++i) {
        if (i > 0) out += ',';
        appendLayer(out, document.layers[i], handles);
    }
    out += "],\"warnings\":[";
    for (std::size_t i = 0; i < document.warnings.size(); ++i) {
        if (i > 0) out += ',';
        appendEscaped(out, document.warnings[i]);
    }
    out += "]}";
    return out;
}

}  // namespace croptop::psd
