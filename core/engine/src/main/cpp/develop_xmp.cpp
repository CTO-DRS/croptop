// XMP sidecars for develop settings.
#include "develop_xmp.h"

#include <algorithm>
#include <charconv>
#include <cmath>
#include <functional>
#include <map>
#include <vector>

namespace croptop::develop {
namespace {

constexpr std::string_view kCrsUri = "http://ns.adobe.com/camera-raw-settings/1.0/";
constexpr std::string_view kOurUri = "http://ns.croptop.app/develop/1.0/";
constexpr std::size_t kMaxXmp = 16U << 20U;
// Parsing stays linear on hostile input: attribute values, property bodies and the number of property
// elements are bounded, and a malformed tag skips to the next '>' instead of being rescanned.
constexpr std::size_t kMaxValue = 64U << 10U;
constexpr std::size_t kMaxBody = 64U << 10U;
constexpr int kMaxPropertyElements = 4096;

/** One scalar control: its Camera Raw name, range and the decimals Lightroom stores. */
struct Field {
    std::string name;
    float* value;
    float lo, hi;
    int decimals;
    float crs_scale = 1.0F;  // ours × scale = crs (e.g. split points 0–1 ↔ 0–100)
};

std::vector<Field> fieldsOf(DevelopRecipe& r) {
    auto& t = r.tone;
    auto& c = r.color;
    auto& d = r.detail;
    std::vector<Field> f{
        {"Exposure2012", &t.exposure, -5, 5, 2},
        {"Contrast2012", &t.contrast, -100, 100, 0},
        {"Highlights2012", &t.highlights, -100, 100, 0},
        {"Shadows2012", &t.shadows, -100, 100, 0},
        {"Whites2012", &t.whites, -100, 100, 0},
        {"Blacks2012", &t.blacks, -100, 100, 0},
        {"Texture", &t.texture, -100, 100, 0},
        {"Clarity2012", &t.clarity, -100, 100, 0},
        {"Dehaze", &t.dehaze, -100, 100, 0},
        {"Vibrance", &t.vibrance, -100, 100, 0},
        {"Saturation", &t.saturation, -100, 100, 0},
        {"ParametricShadows", &t.parametric.shadows, -100, 100, 0},
        {"ParametricDarks", &t.parametric.darks, -100, 100, 0},
        {"ParametricLights", &t.parametric.lights, -100, 100, 0},
        {"ParametricHighlights", &t.parametric.highlights, -100, 100, 0},
        {"ParametricShadowSplit", &t.parametric.split_shadows, 0.05F, 0.9F, 0, 100.0F},
        {"ParametricMidtoneSplit", &t.parametric.split_midtones, 0.1F, 0.95F, 0, 100.0F},
        {"ParametricHighlightSplit", &t.parametric.split_highlights, 0.15F, 0.98F, 0, 100.0F},
        {"ShadowTint", &c.calibration.shadow_tint, -100, 100, 0},
        {"RedHue", &c.calibration.red_hue, -100, 100, 0},
        {"RedSaturation", &c.calibration.red_saturation, -100, 100, 0},
        {"GreenHue", &c.calibration.green_hue, -100, 100, 0},
        {"GreenSaturation", &c.calibration.green_saturation, -100, 100, 0},
        {"BlueHue", &c.calibration.blue_hue, -100, 100, 0},
        {"BlueSaturation", &c.calibration.blue_saturation, -100, 100, 0},
        {"ColorGradeBlending", &c.grading.blending, 0, 100, 0},
        {"SplitToningBalance", &c.grading.balance, -100, 100, 0},
        {"Sharpness", &d.sharpening.amount, 0, 150, 0},
        {"SharpenRadius", &d.sharpening.radius, 0.5F, 3.0F, 1},
        {"SharpenDetail", &d.sharpening.detail, 0, 100, 0},
        {"SharpenEdgeMasking", &d.sharpening.masking, 0, 100, 0},
        {"LuminanceSmoothing", &d.noise.luminance, 0, 100, 0},
        {"LuminanceNoiseReductionDetail", &d.noise.luminance_detail, 0, 100, 0},
        {"ColorNoiseReduction", &d.noise.color, 0, 100, 0},
        {"ColorNoiseReductionDetail", &d.noise.color_detail, 0, 100, 0},
        {"PostCropVignetteAmount", &d.vignette.amount, -100, 100, 0},
        {"PostCropVignetteMidpoint", &d.vignette.midpoint, 0, 100, 0},
        {"PostCropVignetteRoundness", &d.vignette.roundness, -100, 100, 0},
        {"PostCropVignetteFeather", &d.vignette.feather, 0, 100, 0},
        {"PostCropVignetteHighlightContrast", &d.vignette.highlights, 0, 100, 0},
        {"GrainAmount", &d.grain.amount, 0, 100, 0},
        {"GrainSize", &d.grain.size, 0, 100, 0},
        {"GrainFrequency", &d.grain.roughness, 0, 100, 0},
    };
    static const char* kRanges[8] = {"Red", "Orange", "Yellow", "Green", "Aqua", "Blue", "Purple", "Magenta"};
    for (int i = 0; i < 8; ++i) {
        const auto k = static_cast<std::size_t>(i);
        f.push_back({std::string("HueAdjustment") + kRanges[i], &c.mixer.hue[k], -100, 100, 0});
        f.push_back({std::string("SaturationAdjustment") + kRanges[i], &c.mixer.saturation[k], -100, 100, 0});
        f.push_back({std::string("LuminanceAdjustment") + kRanges[i], &c.mixer.luminance[k], -100, 100, 0});
    }
    const std::pair<const char*, GradeWheel*> wheels[4] = {{"Shadow", &c.grading.shadows}, {"Midtone", &c.grading.midtones},
                                                           {"Highlight", &c.grading.highlights}, {"Global", &c.grading.global}};
    for (const auto& [name, wheel] : wheels) {
        f.push_back({std::string("ColorGrade") + name + "Hue", &wheel->hue, 0, 360, 0});
        f.push_back({std::string("ColorGrade") + name + "Sat", &wheel->saturation, 0, 100, 0});
        f.push_back({std::string("ColorGrade") + name + "Lum", &wheel->luminance, -100, 100, 0});
    }
    return f;
}

// ── Locale-independent numbers ─────────────────────────────────────────────────────────────────

std::string formatNumber(float v, int decimals) {
    char buffer[64];
    if (decimals < 0) {
        const auto r = std::to_chars(buffer, buffer + sizeof(buffer), v);  // shortest exact round trip
        return std::string(buffer, r.ptr);
    }
    const double scaled = std::round(static_cast<double>(v) * std::pow(10.0, decimals)) / std::pow(10.0, decimals);
    const auto r = std::to_chars(buffer, buffer + sizeof(buffer), scaled, std::chars_format::fixed, decimals);
    std::string s(buffer, r.ptr);
    if (s == "-0" || s == "-0.0" || s == "-0.00") s.erase(0, 1);
    return s;
}

/** Parses "[+|-]digits[.digits][e[+|-]digits]"; rejects anything else (and non-finite results). */
std::optional<float> parseNumber(std::string_view s) {
    while (!s.empty() && (s.front() == ' ' || s.front() == '\t' || s.front() == '\n' || s.front() == '\r')) s.remove_prefix(1);
    while (!s.empty() && (s.back() == ' ' || s.back() == '\t' || s.back() == '\n' || s.back() == '\r')) s.remove_suffix(1);
    if (s.empty() || s.size() > 64) return std::nullopt;
    std::size_t i = 0;
    double sign = 1;
    if (s[i] == '+' || s[i] == '-') sign = s[i++] == '-' ? -1 : 1;
    double value = 0;
    bool digits = false;
    while (i < s.size() && s[i] >= '0' && s[i] <= '9') {
        value = value * 10 + (s[i++] - '0');
        digits = true;
    }
    if (i < s.size() && s[i] == '.') {
        ++i;
        double scale = 0.1;
        while (i < s.size() && s[i] >= '0' && s[i] <= '9') {
            value += (s[i++] - '0') * scale;
            scale *= 0.1;
            digits = true;
        }
    }
    if (!digits) return std::nullopt;
    if (i < s.size() && (s[i] == 'e' || s[i] == 'E')) {
        ++i;
        int esign = 1, exponent = 0;
        if (i < s.size() && (s[i] == '+' || s[i] == '-')) esign = s[i++] == '-' ? -1 : 1;
        bool edigits = false;
        while (i < s.size() && s[i] >= '0' && s[i] <= '9' && exponent < 1000) {
            exponent = exponent * 10 + (s[i++] - '0');
            edigits = true;
        }
        if (!edigits) return std::nullopt;
        value *= std::pow(10.0, esign * exponent);
    }
    if (i != s.size()) return std::nullopt;
    const auto result = static_cast<float>(sign * value);
    return std::isfinite(result) ? std::optional<float>(result) : std::nullopt;
}

std::string escape(std::string_view s) {
    std::string out;
    for (const char ch : s) {
        switch (ch) {
            case '&': out += "&amp;"; break;
            case '<': out += "&lt;"; break;
            case '>': out += "&gt;"; break;
            case '"': out += "&quot;"; break;
            default: out += ch;
        }
    }
    return out;
}

std::string unescape(std::string_view s) {
    std::string out;
    for (std::size_t i = 0; i < s.size(); ++i) {
        if (s[i] != '&') {
            out += s[i];
            continue;
        }
        const auto end = s.find(';', i);
        if (end == std::string_view::npos || end - i > 10) {
            out += s[i];
            continue;
        }
        const auto entity = s.substr(i + 1, end - i - 1);
        if (entity == "amp") out += '&';
        else if (entity == "lt") out += '<';
        else if (entity == "gt") out += '>';
        else if (entity == "quot") out += '"';
        else if (entity == "apos") out += '\'';
        else if (!entity.empty() && entity[0] == '#') {
            unsigned code = 0;
            const bool hex = entity.size() > 1 && (entity[1] == 'x' || entity[1] == 'X');
            const auto digits = entity.substr(hex ? 2 : 1);
            const auto r = std::from_chars(digits.data(), digits.data() + digits.size(), code, hex ? 16 : 10);
            if (r.ec == std::errc() && r.ptr == digits.data() + digits.size() && code > 0 && code < 128) out += static_cast<char>(code);
        } else {
            out.append(s.substr(i, end - i + 1));
        }
        i = end;
    }
    return out;
}

// ── A small XMP reader: namespace-aware properties from attributes, simple elements and rdf:Seq lists ──

struct Properties {
    std::map<std::string, std::string, std::less<>> crs, ours;
    std::map<std::string, std::vector<std::string>, std::less<>> crs_lists, ours_lists;
};

bool isNameChar(char c) { return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == ':' || c == '_' || c == '-' || c == '.'; }

std::optional<Properties> parse(std::string_view doc) {
    std::map<std::string, std::string, std::less<>> prefixes;  // prefix → URI (document-global: enough for sidecars)
    struct Tag {
        std::string name;
        std::vector<std::pair<std::string, std::string>> attributes;
        bool closing = false, self_closing = false;
        std::size_t end = 0;
    };
    const auto readTag = [&](std::size_t at) -> std::optional<Tag> {
        Tag t;
        std::size_t i = at + 1;
        if (i < doc.size() && doc[i] == '/') {
            t.closing = true;
            ++i;
        }
        const std::size_t name_start = i;
        while (i < doc.size() && isNameChar(doc[i])) ++i;
        t.name = std::string(doc.substr(name_start, i - name_start));
        if (t.name.empty()) return std::nullopt;
        while (i < doc.size()) {
            while (i < doc.size() && (doc[i] == ' ' || doc[i] == '\t' || doc[i] == '\n' || doc[i] == '\r')) ++i;
            if (i >= doc.size()) return std::nullopt;
            if (doc[i] == '>') {
                t.end = i + 1;
                return t;
            }
            if (doc[i] == '/' && i + 1 < doc.size() && doc[i + 1] == '>') {
                t.self_closing = true;
                t.end = i + 2;
                return t;
            }
            const std::size_t a = i;
            while (i < doc.size() && isNameChar(doc[i])) ++i;
            if (i == a) return std::nullopt;
            std::string key(doc.substr(a, i - a));
            while (i < doc.size() && (doc[i] == ' ' || doc[i] == '\t' || doc[i] == '\n' || doc[i] == '\r')) ++i;
            if (i >= doc.size() || doc[i] != '=') return std::nullopt;
            ++i;
            while (i < doc.size() && (doc[i] == ' ' || doc[i] == '\t' || doc[i] == '\n' || doc[i] == '\r')) ++i;
            if (i >= doc.size() || (doc[i] != '"' && doc[i] != '\'')) return std::nullopt;
            const char quote = doc[i++];
            const auto close = doc.substr(i, kMaxValue).find(quote);
            if (close == std::string_view::npos) return std::nullopt;
            t.attributes.emplace_back(std::move(key), unescape(doc.substr(i, close)));
            i += close + 1;
        }
        return std::nullopt;
    };
    const auto split = [](const std::string& qname) {
        const auto colon = qname.find(':');
        return colon == std::string::npos ? std::pair<std::string, std::string>{"", qname}
                                          : std::pair<std::string, std::string>{qname.substr(0, colon), qname.substr(colon + 1)};
    };

    // Pass 1: namespace declarations.
    bool found = false;
    for (std::size_t i = doc.find('<'); i != std::string_view::npos; i = doc.find('<', i + 1)) {
        if (doc.compare(i, 4, "<!--") == 0 || doc.compare(i, 2, "<?") == 0 || doc.compare(i, 2, "<!") == 0) continue;
        const auto tag = readTag(i);
        if (!tag) {
            const auto next = doc.find('>', i);
            if (next == std::string_view::npos) break;
            i = next;
            continue;
        }
        i = tag->end - 1;
        if (tag->name == "x:xmpmeta" || tag->name == "rdf:RDF" || tag->name.ends_with(":xmpmeta")) found = true;
        for (const auto& [key, value] : tag->attributes) {
            if (key.rfind("xmlns:", 0) == 0) prefixes[key.substr(6)] = value;
        }
    }
    if (!found) return std::nullopt;
    const auto kind = [&](const std::string& prefix) -> int {
        const auto it = prefixes.find(prefix);
        if (it == prefixes.end()) return 0;
        return it->second == kCrsUri ? 1 : it->second == kOurUri ? 2 : 0;
    };

    // Pass 2: properties.
    Properties props;
    int property_elements = 0;
    std::size_t i = 0;
    while ((i = doc.find('<', i)) != std::string_view::npos) {
        if (doc.compare(i, 4, "<!--") == 0) {
            const auto end = doc.find("-->", i);
            if (end == std::string_view::npos) break;
            i = end + 3;
            continue;
        }
        if (doc.compare(i, 2, "<?") == 0 || doc.compare(i, 2, "<!") == 0) {
            ++i;
            continue;
        }
        const auto tag = readTag(i);
        if (!tag) {
            const auto next = doc.find('>', i);
            if (next == std::string_view::npos) break;
            i = next + 1;
            continue;
        }
        if (!tag->closing) {
            for (const auto& [key, value] : tag->attributes) {
                const auto [prefix, local] = split(key);
                if (const int k = kind(prefix)) (k == 1 ? props.crs : props.ours)[local] = value;
            }
            const auto [prefix, local] = split(tag->name);
            const int k = kind(prefix);
            if (k != 0 && !tag->self_closing && ++property_elements <= kMaxPropertyElements) {
                const std::string closing = "</" + tag->name;
                const auto window = doc.substr(tag->end, kMaxBody);
                const auto found = window.find(closing);
                const auto end = found == std::string_view::npos ? std::string_view::npos : tag->end + found;
                if (end != std::string_view::npos) {
                    const auto body = doc.substr(tag->end, end - tag->end);
                    if (body.find('<') == std::string_view::npos) {
                        (k == 1 ? props.crs : props.ours)[local] = unescape(body);
                    } else {
                        // rdf:Seq / rdf:Bag of rdf:li values.
                        std::vector<std::string> items;
                        for (std::size_t p = body.find("<rdf:li"); p != std::string_view::npos; p = body.find("<rdf:li", p + 1)) {
                            const auto open_end = body.find('>', p);
                            const auto close = body.find("</rdf:li>", p);
                            if (open_end == std::string_view::npos || close == std::string_view::npos || close < open_end) break;
                            items.push_back(unescape(body.substr(open_end + 1, close - open_end - 1)));
                            if (items.size() > 4096) break;
                        }
                        (k == 1 ? props.crs_lists : props.ours_lists)[local] = std::move(items);
                    }
                    i = end;
                    continue;
                }
            }
        }
        i = tag->end;
    }
    return props;
}

std::vector<std::pair<float, float>> parseCurve(const std::vector<std::string>& items, float scale) {
    std::vector<std::pair<float, float>> points;
    for (const auto& item : items) {
        const auto comma = item.find(',');
        if (comma == std::string::npos) continue;
        const auto x = parseNumber(std::string_view(item).substr(0, comma)), y = parseNumber(std::string_view(item).substr(comma + 1));
        if (!x || !y) continue;
        points.emplace_back(std::clamp(*x / scale, 0.0F, 1.0F), std::clamp(*y / scale, 0.0F, 1.0F));
    }
    std::sort(points.begin(), points.end());
    points.erase(std::unique(points.begin(), points.end(), [](const auto& a, const auto& b) { return a.first == b.first; }), points.end());
    return points;
}

}  // namespace

std::string writeXmp(const DevelopRecipe& recipe_in, std::string_view creator_tool) {
    DevelopRecipe recipe = recipe_in;  // fieldsOf needs mutable pointers; nothing is modified
    const auto fields = fieldsOf(recipe);
    std::string crs_attrs, our_attrs;
    for (const auto& f : fields) {
        const float v = std::clamp(*f.value, f.lo, f.hi);
        crs_attrs += "\n   crs:" + f.name + "=\"" + formatNumber(v * f.crs_scale, f.decimals) + "\"";
        our_attrs += "\n   ctd:" + f.name + "=\"" + formatNumber(v, -1) + "\"";
    }
    our_attrs += "\n   ctd:Look=\"" + std::to_string(static_cast<int>(recipe.color.look)) + "\"";
    our_attrs += "\n   ctd:GrainSeed=\"" + std::to_string(recipe.detail.grain.seed) + "\"";
    if (recipe.white_balance) {
        our_attrs += "\n   ctd:WhiteBalance=\"" + formatNumber((*recipe.white_balance)[0], -1) + "," + formatNumber((*recipe.white_balance)[1], -1) + "," +
                     formatNumber((*recipe.white_balance)[2], -1) + "\"";
    }
    std::string crs_curve, our_curve;
    for (const auto& [x, y] : recipe.tone.point_curve.points) {
        crs_curve += "      <rdf:li>" + formatNumber(std::clamp(x, 0.0F, 1.0F) * 255.0F, 0) + ", " + formatNumber(std::clamp(y, 0.0F, 1.0F) * 255.0F, 0) + "</rdf:li>\n";
        our_curve += "      <rdf:li>" + formatNumber(x, -1) + ", " + formatNumber(y, -1) + "</rdf:li>\n";
    }
    std::string out;
    out += "<?xpacket begin=\"\xEF\xBB\xBF\" id=\"W5M0MpCehiHzreSzNTczkc9d\"?>\n";
    out += "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\">\n <rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">\n";
    out += "  <rdf:Description rdf:about=\"\"\n   xmlns:xmp=\"http://ns.adobe.com/xap/1.0/\"\n   xmlns:crs=\"";
    out += kCrsUri;
    out += "\"\n   xmlns:ctd=\"";
    out += kOurUri;
    out += "\"\n   xmp:CreatorTool=\"" + escape(creator_tool) + "\"\n   crs:Version=\"15.0\"\n   crs:ProcessVersion=\"11.0\"\n   crs:HasSettings=\"True\"";
    out += crs_attrs + our_attrs + ">\n";
    out += "   <crs:ToneCurvePV2012>\n    <rdf:Seq>\n" + crs_curve + "    </rdf:Seq>\n   </crs:ToneCurvePV2012>\n";
    out += "   <ctd:PointCurve>\n    <rdf:Seq>\n" + our_curve + "    </rdf:Seq>\n   </ctd:PointCurve>\n";
    out += "  </rdf:Description>\n </rdf:RDF>\n</x:xmpmeta>\n<?xpacket end=\"w\"?>\n";
    return out;
}

namespace {

/** Fills `recipe` from the properties; `present` (optional) collects the controls that were set. */
void readRecipe(const Properties& props, DevelopRecipe& recipe, std::vector<std::string>* present) {
    const auto mark = [&](std::string name) {
        if (present != nullptr) present->push_back(std::move(name));
    };
    for (auto& f : fieldsOf(recipe)) {
        std::optional<float> value;
        if (const auto it = props.ours.find(f.name); it != props.ours.end()) value = parseNumber(it->second);
        if (!value) {
            if (const auto it = props.crs.find(f.name); it != props.crs.end()) {
                if (const auto v = parseNumber(it->second)) value = *v / f.crs_scale;
            }
        }
        if (value) {
            *f.value = std::clamp(*value, f.lo, f.hi);
            mark(f.name);
        }
    }
    if (const auto it = props.ours.find("Look"); it != props.ours.end()) {
        if (const auto v = parseNumber(it->second); v && *v >= 0 && *v <= static_cast<float>(Look::Monochrome) && std::floor(*v) == *v) {
            recipe.color.look = static_cast<Look>(static_cast<int>(*v));
            mark("Look");
        }
    }
    if (const auto it = props.ours.find("GrainSeed"); it != props.ours.end()) {
        if (const auto v = parseNumber(it->second); v && *v >= 0 && *v <= 4294967295.0F) {
            recipe.detail.grain.seed = static_cast<std::uint32_t>(*v);
            mark("GrainSeed");
        }
    }
    if (const auto it = props.ours.find("WhiteBalance"); it != props.ours.end()) {
        std::array<float, 3> wb{};
        std::size_t start = 0, count = 0;
        const std::string& s = it->second;
        for (std::size_t k = 0; k <= s.size() && count < 3; ++k) {
            if (k == s.size() || s[k] == ',') {
                if (const auto v = parseNumber(std::string_view(s).substr(start, k - start)); v && *v > 0.0F && *v <= 64.0F) wb[count] = *v;
                else break;
                ++count;
                start = k + 1;
            }
        }
        if (count == 3) {
            recipe.white_balance = wb;
            mark("WhiteBalance");
        }
    }
    std::vector<std::pair<float, float>> curve;
    if (const auto it = props.ours_lists.find("PointCurve"); it != props.ours_lists.end()) curve = parseCurve(it->second, 1.0F);
    if (curve.size() < 2) {
        if (const auto it = props.crs_lists.find("ToneCurvePV2012"); it != props.crs_lists.end()) curve = parseCurve(it->second, 255.0F);
    }
    if (curve.size() >= 2) {
        recipe.tone.point_curve.points = curve;
        mark("PointCurve");
    }
}

/** A language-alternative or plain text property (x-default / first item). */
std::string textOf(const Properties& props, std::string_view name) {
    if (const auto it = props.crs_lists.find(name); it != props.crs_lists.end() && !it->second.empty()) return it->second.front().substr(0, 256);
    if (const auto it = props.crs.find(name); it != props.crs.end()) return it->second.substr(0, 256);
    return {};
}

}  // namespace

std::optional<DevelopRecipe> readXmp(std::string_view xmp, std::string& error) {
    error.clear();
    if (xmp.size() > kMaxXmp) {
        error = "XMP document is too large";
        return std::nullopt;
    }
    const auto props = parse(xmp);
    if (!props) {
        error = "no XMP document found";
        return std::nullopt;
    }
    DevelopRecipe recipe;
    readRecipe(*props, recipe, nullptr);
    return recipe;
}

std::optional<DevelopPreset> readXmpPreset(std::string_view xmp, std::string& error) {
    error.clear();
    if (xmp.size() > kMaxXmp) {
        error = "XMP document is too large";
        return std::nullopt;
    }
    const auto props = parse(xmp);
    if (!props) {
        error = "no XMP document found";
        return std::nullopt;
    }
    DevelopPreset preset;
    readRecipe(*props, preset.values, &preset.controls);
    if (preset.controls.empty()) {
        error = "the preset sets no controls CropTop supports";
        return std::nullopt;
    }
    preset.name = textOf(*props, "Name");
    preset.group = textOf(*props, "Group");
    return preset;
}

DevelopRecipe applyPreset(const DevelopRecipe& base, const DevelopPreset& preset, float amount) {
    amount = std::isfinite(amount) ? std::clamp(amount, 0.0F, 2.0F) : 1.0F;
    DevelopRecipe out = base;
    if (amount == 0.0F) return out;
    const auto has = [&](std::string_view name) { return std::find(preset.controls.begin(), preset.controls.end(), name) != preset.controls.end(); };
    auto target = preset.values;
    auto from = base;
    auto to = fieldsOf(out);
    const auto a = fieldsOf(from), b = fieldsOf(target);
    for (std::size_t i = 0; i < to.size(); ++i) {
        if (!has(to[i].name)) continue;
        *to[i].value = std::clamp(*a[i].value + amount * (*b[i].value - *a[i].value), to[i].lo, to[i].hi);
    }
    if (has("Look")) out.color.look = preset.values.color.look;
    if (has("GrainSeed")) out.detail.grain.seed = preset.values.detail.grain.seed;
    if (has("WhiteBalance") && preset.values.white_balance) {
        if (base.white_balance) {
            std::array<float, 3> wb{};
            for (std::size_t c = 0; c < 3; ++c)  // blend multipliers in log space
                wb[c] = std::clamp(std::exp(std::log((*base.white_balance)[c]) + amount * (std::log((*preset.values.white_balance)[c]) - std::log((*base.white_balance)[c]))),
                                   1e-3F, 64.0F);
            out.white_balance = wb;
        } else {
            out.white_balance = preset.values.white_balance;
        }
    }
    if (has("PointCurve")) {
        if (amount == 1.0F) {
            out.tone.point_curve = preset.values.tone.point_curve;
        } else {
            // Blend the two curves on 17 samples.
            std::vector<std::pair<float, float>> points;
            for (int k = 0; k <= 16; ++k) {
                const float x = static_cast<float>(k) / 16.0F;
                const float y0 = base.tone.point_curve.evaluate(x), y1 = preset.values.tone.point_curve.evaluate(x);
                points.emplace_back(x, std::clamp(y0 + amount * (y1 - y0), 0.0F, 1.0F));
            }
            out.tone.point_curve.points = std::move(points);
        }
    }
    return out;
}

}  // namespace croptop::develop
