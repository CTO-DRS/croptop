#include "layer_styles_codec.h"

#include <cmath>

namespace croptop::styles {
namespace {

void putRgba(std::vector<float>& v, const Rgba& c) { v.insert(v.end(), c.begin(), c.end()); }

void putShadow(std::vector<float>& v, const Shadow& s) {
    v.push_back(s.enabled);
    putRgba(v, s.color);
    for (const float x : {static_cast<float>(s.mode), s.opacity, s.angle, s.distance, s.spread, s.size}) v.push_back(x);
}
void putGlow(std::vector<float>& v, const Glow& g) {
    v.push_back(g.enabled);
    putRgba(v, g.color);
    for (const float x : {static_cast<float>(g.mode), g.opacity, g.spread, g.size, g.from_center ? 1.0F : 0.0F}) v.push_back(x);
}

struct Reader {
    std::span<const float> v;
    std::size_t i = 0;
    bool ok = true;
    std::string* error;
    float next() {
        if (i >= v.size()) {
            ok = false;
            return 0.0F;
        }
        return v[i++];
    }
    bool flag() {
        const float x = next();
        if (x != 0.0F && x != 1.0F) ok = false;
        return x == 1.0F;
    }
    int ordinal(int max) {
        const float x = next();
        const int o = static_cast<int>(x);
        if (static_cast<float>(o) != x || o < 0 || o > max) {
            ok = false;
            return 0;
        }
        return o;
    }
    Rgba rgba() {
        Rgba c{};
        for (auto& x : c) x = next();
        return c;
    }
    BlendMode mode() { return static_cast<BlendMode>(ordinal(static_cast<int>(BlendMode::Luminosity))); }
};

Shadow readShadow(Reader& r) {
    Shadow s;
    s.enabled = r.flag();
    s.color = r.rgba();
    s.mode = r.mode();
    s.opacity = r.next();
    s.angle = r.next();
    s.distance = r.next();
    s.spread = r.next();
    s.size = r.next();
    return s;
}
Glow readGlow(Reader& r) {
    Glow g;
    g.enabled = r.flag();
    g.color = r.rgba();
    g.mode = r.mode();
    g.opacity = r.next();
    g.spread = r.next();
    g.size = r.next();
    g.from_center = r.flag();
    return g;
}

}  // namespace

std::vector<float> encodeStyles(const LayerStyles& s) {
    std::vector<float> v;
    putShadow(v, s.drop_shadow);
    putGlow(v, s.outer_glow);
    const auto& b = s.bevel;
    v.push_back(b.enabled);
    for (const float x : {static_cast<float>(b.style), static_cast<float>(b.technique), b.depth, b.up ? 1.0F : 0.0F, b.size, b.soften, b.angle, b.altitude}) v.push_back(x);
    putRgba(v, b.highlight_color);
    v.push_back(static_cast<float>(b.highlight_mode));
    v.push_back(b.highlight_opacity);
    putRgba(v, b.shadow_color);
    v.push_back(static_cast<float>(b.shadow_mode));
    v.push_back(b.shadow_opacity);
    putShadow(v, s.inner_shadow);
    putGlow(v, s.inner_glow);
    const auto& t = s.satin;
    v.push_back(t.enabled);
    putRgba(v, t.color);
    for (const float x : {static_cast<float>(t.mode), t.opacity, t.angle, t.distance, t.size, t.invert ? 1.0F : 0.0F}) v.push_back(x);
    const auto& c = s.color_overlay;
    v.push_back(c.enabled);
    putRgba(v, c.color);
    v.push_back(static_cast<float>(c.mode));
    v.push_back(c.opacity);
    const auto& g = s.gradient_overlay;
    v.push_back(g.enabled);
    for (const float x : {static_cast<float>(g.mode), g.opacity, g.angle, g.scale, g.reverse ? 1.0F : 0.0F, static_cast<float>(g.stops.size())}) v.push_back(x);
    for (const auto& stop : g.stops) {
        v.push_back(stop.position);
        putRgba(v, stop.color);
    }
    const auto& p = s.pattern_overlay;
    v.push_back(p.enabled);
    for (const float x : {static_cast<float>(p.mode), p.opacity, p.scale}) v.push_back(x);
    const auto& k = s.stroke;
    v.push_back(k.enabled);
    putRgba(v, k.color);
    for (const float x : {static_cast<float>(k.mode), k.opacity, k.size, static_cast<float>(k.position)}) v.push_back(x);
    return v;
}

std::optional<LayerStyles> decodeStyles(std::span<const float> values, const TileStore* pattern, std::string& error) {
    for (const float x : values) {
        if (!std::isfinite(x)) {
            error = "layer style values must be finite";
            return std::nullopt;
        }
    }
    Reader r{values, 0, true, &error};
    LayerStyles s;
    s.drop_shadow = readShadow(r);
    s.outer_glow = readGlow(r);
    auto& b = s.bevel;
    b.enabled = r.flag();
    b.style = static_cast<BevelStyle>(r.ordinal(static_cast<int>(BevelStyle::PillowEmboss)));
    b.technique = static_cast<BevelTechnique>(r.ordinal(static_cast<int>(BevelTechnique::ChiselHard)));
    b.depth = r.next();
    b.up = r.flag();
    b.size = r.next();
    b.soften = r.next();
    b.angle = r.next();
    b.altitude = r.next();
    b.highlight_color = r.rgba();
    b.highlight_mode = r.mode();
    b.highlight_opacity = r.next();
    b.shadow_color = r.rgba();
    b.shadow_mode = r.mode();
    b.shadow_opacity = r.next();
    s.inner_shadow = readShadow(r);
    s.inner_glow = readGlow(r);
    auto& t = s.satin;
    t.enabled = r.flag();
    t.color = r.rgba();
    t.mode = r.mode();
    t.opacity = r.next();
    t.angle = r.next();
    t.distance = r.next();
    t.size = r.next();
    t.invert = r.flag();
    auto& c = s.color_overlay;
    c.enabled = r.flag();
    c.color = r.rgba();
    c.mode = r.mode();
    c.opacity = r.next();
    auto& g = s.gradient_overlay;
    g.enabled = r.flag();
    g.mode = r.mode();
    g.opacity = r.next();
    g.angle = r.next();
    g.scale = r.next();
    g.reverse = r.flag();
    const int stops = r.ordinal(static_cast<int>(kMaxGradientStops));
    if (r.ok && stops < 1) r.ok = false;
    g.stops.clear();
    for (int i = 0; r.ok && i < stops; ++i) {
        const float position = r.next();
        g.stops.push_back({position, r.rgba()});
    }
    auto& p = s.pattern_overlay;
    p.enabled = r.flag();
    p.mode = r.mode();
    p.opacity = r.next();
    p.scale = r.next();
    p.pattern = p.enabled ? pattern : nullptr;
    auto& k = s.stroke;
    k.enabled = r.flag();
    k.color = r.rgba();
    k.mode = r.mode();
    k.opacity = r.next();
    k.size = r.next();
    k.position = static_cast<StrokePosition>(r.ordinal(static_cast<int>(StrokePosition::Center)));
    if (!r.ok || r.i != values.size()) {
        error = "layer style array is malformed or has the wrong length";
        return std::nullopt;
    }
    if (p.enabled && pattern == nullptr) {
        error = "the pattern overlay needs a pattern store";
        return std::nullopt;
    }
    return s;
}

}  // namespace croptop::styles
