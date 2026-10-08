#include "adjustments.h"

#include <algorithm>
#include <atomic>
#include <cmath>
#include <cstring>
#include <exception>

#include "filters_blur.h"
#include "parallel_util.h"
#include "pixel_plane.h"

namespace croptop::adjust {
namespace {

float luma(const Rgb& c) { return 0.2126F * c[0] + 0.7152F * c[1] + 0.0722F * c[2]; }
float clamp01(float v) { return std::clamp(v, 0.0F, 1.0F); }
float toLinear(float v) { return v <= 0.04045F ? v / 12.92F : std::pow((v + 0.055F) / 1.055F, 2.4F); }
float toEncoded(float v) { return v <= 0.0031308F ? v * 12.92F : 1.055F * std::pow(v, 1.0F / 2.4F) - 0.055F; }

struct Hsl { float h, s, l; };  // h in degrees

Hsl toHsl(const Rgb& c) {
    const float mx = std::max({c[0], c[1], c[2]}), mn = std::min({c[0], c[1], c[2]});
    const float l = (mx + mn) / 2.0F, d = mx - mn;
    if (d <= 1e-7F) return {0.0F, 0.0F, l};
    const float s = l > 0.5F ? d / (2.0F - mx - mn) : d / (mx + mn);
    float h = 0.0F;
    if (mx == c[0]) h = (c[1] - c[2]) / d + (c[1] < c[2] ? 6.0F : 0.0F);
    else if (mx == c[1]) h = (c[2] - c[0]) / d + 2.0F;
    else h = (c[0] - c[1]) / d + 4.0F;
    return {h * 60.0F, s, l};
}

Rgb fromHsl(Hsl v) {
    float hue = v.h;
    if (!std::isfinite(hue)) hue = 0.0F;
    if (hue < 0.0F || hue >= 360.0F) hue = std::fmod(std::fmod(hue, 360.0F) + 360.0F, 360.0F);
    const float h = hue / 360.0F, s = clamp01(v.s), l = clamp01(v.l);
    if (s <= 0.0F) return {l, l, l};
    const float q = l < 0.5F ? l * (1 + s) : l + s - l * s, p = 2 * l - q;
    const auto channel = [&](float t) {
        t = t < 0 ? t + 1 : t > 1 ? t - 1 : t;
        if (t < 1.0F / 6) return p + (q - p) * 6 * t;
        if (t < 0.5F) return q;
        if (t < 2.0F / 3) return p + (q - p) * (2.0F / 3 - t) * 6;
        return p;
    };
    return {channel(h + 1.0F / 3), channel(h), channel(h - 1.0F / 3)};
}

/** Rescales to `target` luma without clipping any channel. */
Rgb withLuma(Rgb c, float target) {
    const float l = luma(c);
    if (l <= 1e-6F) return {target, target, target};
    float k = target / l;
    const float peak = std::max({c[0], c[1], c[2]});
    if (k > 1.0F && peak * k > 1.0F) {
        // Scale as far as possible, then lift towards white to reach the target luma.
        const float kmax = peak > 0 ? 1.0F / peak : k;
        for (auto& v : c) v *= kmax;
        const float remaining = (target - luma(c)) / std::max(1e-6F, 1.0F - luma(c));
        for (auto& v : c) v += (1.0F - v) * clamp01(remaining);
        return c;
    }
    for (auto& v : c) v *= k;
    return c;
}

Rgb brightnessContrast(const BrightnessContrast& a, Rgb c) {
    for (auto& v : c) {
        if (a.legacy) {
            v = clamp01(v + a.brightness / 255.0F);
            v = (v - 0.5F) * (1.0F + a.contrast / 100.0F) + 0.5F;
        } else {
            const float mid = std::clamp(0.5F + a.brightness / 600.0F, 0.01F, 0.99F);
            v = std::pow(clamp01(v), std::log(mid) / std::log(0.5F));
            if (a.contrast >= 0) v += a.contrast / 100.0F * (v * v * (3 - 2 * v) - v);
            else v = 0.5F + (v - 0.5F) * (1.0F + a.contrast / 100.0F);
        }
    }
    return c;
}

}  // namespace

float Curve::evaluate(float x) const {
    // Port of core:model Curve.evaluate (monotone cubic, Fritsch–Carlson) for identical results.
    const auto& p = points;
    if (p.size() < 2) return x;
    if (x <= p.front().first) return p.front().second;
    if (x >= p.back().first) return p.back().second;
    const std::size_t n = p.size();
    std::vector<float> dx(n - 1), slope(n - 1), m(n);
    for (std::size_t i = 0; i + 1 < n; ++i) {
        dx[i] = p[i + 1].first - p[i].first;
        slope[i] = dx[i] != 0.0F ? (p[i + 1].second - p[i].second) / dx[i] : 0.0F;
    }
    m[0] = slope[0];
    m[n - 1] = slope[n - 2];
    for (std::size_t i = 1; i + 1 < n; ++i) m[i] = slope[i - 1] * slope[i] <= 0.0F ? 0.0F : (slope[i - 1] + slope[i]) / 2.0F;
    for (std::size_t i = 0; i + 1 < n; ++i) {
        if (slope[i] == 0.0F) {
            m[i] = 0.0F;
            m[i + 1] = 0.0F;
        } else {
            const float a = m[i] / slope[i], b = m[i + 1] / slope[i], h = a * a + b * b;
            if (h > 9.0F) {
                const float t = 3.0F / std::sqrt(h);
                m[i] = t * a * slope[i];
                m[i + 1] = t * b * slope[i];
            }
        }
    }
    std::size_t i = 0;
    while (i + 2 < n && !(x < p[i + 1].first)) ++i;
    const float t = (x - p[i].first) / dx[i], t2 = t * t, t3 = t2 * t;
    const float y = (2 * t3 - 3 * t2 + 1) * p[i].second + (t3 - 2 * t2 + t) * dx[i] * m[i] + (-2 * t3 + 3 * t2) * p[i + 1].second + (t3 - t2) * dx[i] * m[i + 1];
    return clamp01(y);
}

float HueRange::weight(float hue) const {
    // Port of core:model HueRange.weight.
    // Same result as ((to − from) % 360 + 360) % 360 for angles in [0, 360), without fmod (hot path).
    const auto fwd = [](float from, float to) {
        float d = to - from;
        while (d < 0.0F) d += 360.0F;
        while (d >= 360.0F) d -= 360.0F;
        return d;
    };
    const float h = fwd(outer_start, hue), a = fwd(outer_start, inner_start), b = fwd(outer_start, inner_end), e = fwd(outer_start, outer_end);
    if (h > e) return 0.0F;
    if (h < a) return a == 0.0F ? 1.0F : h / a;
    if (h <= b) return 1.0F;
    return e == b ? 1.0F : (e - h) / (e - b);
}

Rgb applyPixel(const Adjustment& adjustment, Rgb c, std::uint32_t px, std::uint32_t py) {
    struct Visitor {
        Rgb c;
        std::uint32_t px, py;
        Rgb operator()(const BrightnessContrast& a) const { return brightnessContrast(a, c); }
        Rgb operator()(const Levels& a) const {
            Rgb out = c;
            const float range = std::max(a.in_white - a.in_black, 1e-4F);
            for (auto& v : out) v = a.out_black + std::pow(clamp01((v - a.in_black) / range), 1.0F / std::max(a.gamma, 0.01F)) * (a.out_white - a.out_black);
            return out;
        }
        Rgb operator()(const Curves& a) const {
            return {a.rgb.evaluate(a.red.evaluate(clamp01(c[0]))), a.rgb.evaluate(a.green.evaluate(clamp01(c[1]))), a.rgb.evaluate(a.blue.evaluate(clamp01(c[2])))};
        }
        Rgb operator()(const Exposure& a) const {
            Rgb out = c;
            for (auto& v : out) {
                float lin = toLinear(clamp01(v)) * std::exp2(a.exposure) + a.offset;
                lin = std::pow(std::max(lin, 0.0F), 1.0F / std::max(a.gamma, 0.01F));
                v = toEncoded(clamp01(lin));
            }
            return out;
        }
        Rgb operator()(const Vibrance& a) const {
            // In HSL saturation, which stays in gamut by construction (no clip limiting, so it is smooth and
            // bakes into LUTs cleanly).
            Hsl v = toHsl({clamp01(c[0]), clamp01(c[1]), clamp01(c[2])});
            v.s = clamp01(v.s * (1.0F + a.saturation / 100.0F));
            v.s = clamp01(v.s + a.vibrance / 100.0F * v.s * (1.0F - v.s));
            return fromHsl(v);
        }
        Rgb operator()(const HueSaturation& a) const {
            Hsl v = toHsl(c);
            const auto lightness = [](float l, float delta) { return delta >= 0 ? l + (1 - l) * delta / 100.0F : l * (1 + delta / 100.0F); };
            if (a.colorize) return fromHsl({a.master.hue, std::clamp(a.master.saturation, 0.0F, 100.0F) / 100.0F, lightness(v.l, a.master.lightness)});
            float dh = a.master.hue, ds = a.master.saturation, dl = a.master.lightness;
            if (v.s > 0.0F) {
                for (std::size_t i = 0; i < 6; ++i) {
                    const float w = a.ranges[i].weight(v.h);
                    dh += w * a.channels[i].hue;
                    ds += w * a.channels[i].saturation;
                    dl += w * a.channels[i].lightness;
                }
            }
            v.h += dh;
            v.s = clamp01(v.s * (1.0F + ds / 100.0F));
            v.l = lightness(v.l, std::clamp(dl, -100.0F, 100.0F));
            return fromHsl(v);
        }
        Rgb operator()(const ColorBalance& a) const {
            const float l = clamp01(luma(c));
            const float ws = (1 - l) * (1 - l), wm = 4 * l * (1 - l), wh = l * l;
            Rgb out = c;
            const std::array<const BalanceShift*, 3> shifts{&a.shadows, &a.midtones, &a.highlights};
            const std::array<float, 3> weights{ws, wm, wh};
            for (std::size_t r = 0; r < 3; ++r) {
                out[0] += 0.25F * shifts[r]->cyan_red / 100.0F * weights[r];
                out[1] += 0.25F * shifts[r]->magenta_green / 100.0F * weights[r];
                out[2] += 0.25F * shifts[r]->yellow_blue / 100.0F * weights[r];
            }
            for (auto& v : out) v = clamp01(v);
            return a.preserve_luminosity ? withLuma(out, luma(c)) : out;
        }
        Rgb operator()(const BlackWhite& a) const {
            const Rgb x{clamp01(c[0]), clamp01(c[1]), clamp01(c[2])};
            std::array<int, 3> order{0, 1, 2};
            std::sort(order.begin(), order.end(), [&](int i, int j) { return x[static_cast<std::size_t>(i)] > x[static_cast<std::size_t>(j)]; });
            const float mx = x[static_cast<std::size_t>(order[0])], mid = x[static_cast<std::size_t>(order[1])], mn = x[static_cast<std::size_t>(order[2])];
            const std::array<float, 3> primary{a.reds, a.greens, a.blues};  // R, G, B
            const auto secondary = [&](int p, int q) {  // Y = R+G, C = G+B, M = R+B
                const int s = p + q;
                return s == 1 ? a.yellows : s == 3 ? a.cyans : a.magentas;
            };
            const float gray = clamp01(mn + (mx - mid) * primary[static_cast<std::size_t>(order[0])] / 100.0F + (mid - mn) * secondary(order[0], order[1]) / 100.0F);
            if (!a.tint) return {gray, gray, gray};
            const Hsl t = toHsl(a.tint_color);
            return fromHsl({t.h, t.s, gray});
        }
        Rgb operator()(const PhotoFilter& a) const {
            const float d = std::clamp(a.density, 0.0F, 100.0F) / 100.0F;
            Rgb out{c[0] + d * (c[0] * a.color[0] - c[0]), c[1] + d * (c[1] * a.color[1] - c[1]), c[2] + d * (c[2] * a.color[2] - c[2])};
            return a.preserve_luminosity ? withLuma(out, luma(c)) : out;
        }
        Rgb operator()(const Invert&) const { return {1 - c[0], 1 - c[1], 1 - c[2]}; }
        Rgb operator()(const Posterize& a) const {
            const float n = std::clamp(std::round(a.levels), 2.0F, 255.0F);
            Rgb out = c;
            for (auto& v : out) v = std::floor(std::min(clamp01(v) * n, n - 1)) / (n - 1);
            return out;
        }
        Rgb operator()(const Threshold& a) const {
            const float v = luma(c) >= std::clamp(a.level, 1.0F, 255.0F) / 255.0F ? 1.0F : 0.0F;
            return {v, v, v};
        }
        Rgb operator()(const ChannelMixer& a) const {
            Rgb out{};
            for (std::size_t o = 0; o < 3; ++o) {
                const auto& row = a.matrix[a.monochrome ? 0 : o];
                out[o] = (row[0] * c[0] + row[1] * c[1] + row[2] * c[2]) / 100.0F + a.constant[a.monochrome ? 0 : o] / 100.0F;
            }
            return out;
        }
        Rgb operator()(const GradientMap& a) const {
            float t = clamp01(luma(c));
            if (a.reverse) t = 1 - t;
            if (a.dither) {
                static constexpr std::array<float, 16> kBayer{0, 8, 2, 10, 12, 4, 14, 6, 3, 11, 1, 9, 15, 7, 13, 5};
                t = clamp01(t + (kBayer[(py % 4) * 4 + px % 4] / 16.0F - 0.5F) / 255.0F);
            }
            if (a.stops.empty()) return c;
            auto stops = a.stops;
            std::sort(stops.begin(), stops.end(), [](const GradientStop& x, const GradientStop& y) { return x.position < y.position; });
            if (t <= stops.front().position) return {stops.front().color[0], stops.front().color[1], stops.front().color[2]};
            if (t >= stops.back().position) return {stops.back().color[0], stops.back().color[1], stops.back().color[2]};
            std::size_t i = 0;
            while (i + 2 < stops.size() && stops[i + 1].position <= t) ++i;
            const auto& s0 = stops[i];
            const auto& s1 = stops[i + 1];
            const float f = s1.position == s0.position ? 0 : (t - s0.position) / (s1.position - s0.position);
            return {s0.color[0] + f * (s1.color[0] - s0.color[0]), s0.color[1] + f * (s1.color[1] - s0.color[1]), s0.color[2] + f * (s1.color[2] - s0.color[2])};
        }
        Rgb operator()(const SelectiveColor& a) const {
            const Rgb x{clamp01(c[0]), clamp01(c[1]), clamp01(c[2])};
            const float mx = std::max({x[0], x[1], x[2]}), mn = std::min({x[0], x[1], x[2]});
            const float mid = x[0] + x[1] + x[2] - mx - mn;
            std::array<float, 9> amount{};
            for (std::size_t k = 0; k < 3; ++k) {
                if (x[k] == mx) amount[k * 2] = std::max(amount[k * 2], mx - mid);  // reds 0, greens 2, blues 4
            }
            // Secondaries: yellows (B is min) 1, cyans (R is min) 3, magentas (G is min) 5.
            if (x[2] == mn) amount[1] = mid - mn;
            if (x[0] == mn) amount[3] = mid - mn;
            if (x[1] == mn) amount[5] = mid - mn;
            amount[6] = std::max(0.0F, 2 * (mn - 0.5F));
            amount[7] = std::max(0.0F, 1 - std::fabs(mx - 0.5F) - std::fabs(mn - 0.5F));
            amount[8] = std::max(0.0F, 2 * (0.5F - mx));
            Rgb ink{1 - x[0], 1 - x[1], 1 - x[2]};
            Rgb out_ink = ink;
            for (std::size_t r = 0; r < 9; ++r) {
                if (amount[r] <= 0.0F) continue;
                const float k = a.cmyk[r][3] / 100.0F;
                for (std::size_t ch = 0; ch < 3; ++ch) {
                    const float p = a.cmyk[r][ch] / 100.0F;
                    out_ink[ch] += a.absolute ? amount[r] * (p + k) : amount[r] * (p * ink[ch] + k * ink[ch]);
                }
            }
            return {1 - clamp01(out_ink[0]), 1 - clamp01(out_ink[1]), 1 - clamp01(out_ink[2])};
        }
    };
    return std::visit(Visitor{c, px, py}, adjustment);
}

namespace {

/** Per-pixel tile loop over the layer's existing tiles (adjustments never create pixels). */
/** `per_pixel(rgb, x, y, aux)` gets the matching pixel of `aux` (same size and tiling) when given. */
template <typename PerPixel>
bool forEachPixel(TileStore& layer, const TileStore* mask, float opacity, std::string& error, unsigned worker_threads, PerPixel&& per_pixel,
                  const TileStore* aux = nullptr) {
    if (!paint::detail::isRgba(layer.format())) {
        error = "adjustments apply to RGBA layers";
        return false;
    }
    if (mask != nullptr && (mask->format() != PixelFormat::A16 || mask->width() != layer.width() || mask->height() != layer.height())) {
        error = "the mask must be an A16 mask of the layer's size";
        return false;
    }
    if (!std::isfinite(opacity) || opacity < 0.0F || opacity > 1.0F) {
        error = "opacity must be 0–1";
        return false;
    }
    if (opacity == 0.0F) return true;
    const auto coordinates = layer.tileCoordinates();
    const auto bpp = layer.bytes_per_pixel();
    std::atomic<bool> failed{false};
    try {
        parallel::parallelFor(coordinates.size(), parallel::workerCount(worker_threads), [&](std::size_t item, std::size_t) {
            const auto coordinate = coordinates[item];
            const auto mask_tile = mask != nullptr ? mask->readTile(coordinate) : nullptr;
            if (mask != nullptr && mask_tile == nullptr) return;
            const auto source = layer.readTile(coordinate);
            if (source == nullptr) return;
            const auto aux_tile = aux != nullptr ? aux->readTile(coordinate) : nullptr;
            TileBuffer tile = *source;
            for (std::uint32_t ly = 0; ly < kTileEdge; ++ly) {
                for (std::uint32_t lx = 0; lx < kTileEdge; ++lx) {
                    const std::size_t i = static_cast<std::size_t>(ly) * kTileEdge + lx;
                    float amount = opacity;
                    if (mask_tile != nullptr) {
                        std::uint16_t raw = 0;
                        std::memcpy(&raw, mask_tile->data() + i * 2U, 2U);
                        if (raw == 0U) continue;
                        amount *= raw / 65535.0F;
                    }
                    auto* p = tile.data() + i * bpp;
                    std::array<float, 4> px{};
                    paint::detail::readPixel(layer.format(), p, px.data());
                    if (px[3] <= 0.0F) continue;
                    const auto gx = static_cast<std::uint32_t>(coordinate.x) * kTileEdge + lx, gy = static_cast<std::uint32_t>(coordinate.y) * kTileEdge + ly;
                    std::array<float, 4> aux_px{};
                    if (aux_tile != nullptr) paint::detail::readPixel(aux->format(), aux_tile->data() + i * aux->bytes_per_pixel(), aux_px.data());
                    const Rgb out = per_pixel(Rgb{px[0], px[1], px[2]}, gx, gy, aux_px);
                    for (std::size_t c = 0; c < 3; ++c) px[c] += amount * (out[c] - px[c]);
                    paint::detail::writePixel(layer.format(), p, px.data());
                }
            }
            if (!layer.writeTile(coordinate, tile)) failed.store(true);
        });
    } catch (const std::exception&) {
        error = "could not allocate adjustment buffers";
        return false;
    }
    if (failed.load()) error = "could not store adjusted pixels";
    return !failed.load();
}

}  // namespace

bool apply(TileStore& layer, const Adjustment& adjustment, const TileStore* mask, float opacity, std::string& error, unsigned worker_threads) {
    // Per-channel adjustments become exact lookup tables for 8/16-bit layers (every representable value).
    const bool separable = std::holds_alternative<BrightnessContrast>(adjustment) || std::holds_alternative<Levels>(adjustment) ||
                           std::holds_alternative<Curves>(adjustment) || std::holds_alternative<Exposure>(adjustment) ||
                           std::holds_alternative<Invert>(adjustment) || std::holds_alternative<Posterize>(adjustment);
    if (separable && (layer.format() == PixelFormat::RGBA8 || layer.format() == PixelFormat::RGBA16)) {
        const std::size_t levels = layer.format() == PixelFormat::RGBA8 ? 256U : 65536U;
        const float scale = 1.0F / static_cast<float>(levels - 1U);
        std::vector<Rgb> table(levels);
        for (std::size_t i = 0; i < levels; ++i) {
            const float v = static_cast<float>(i) * scale;
            table[i] = applyPixel(adjustment, {v, v, v});
        }
        const float quantize = static_cast<float>(levels - 1U);
        return forEachPixel(layer, mask, opacity, error, worker_threads, [&](const Rgb& c, std::uint32_t, std::uint32_t, const std::array<float, 4>&) {
            Rgb out{};
            for (std::size_t k = 0; k < 3; ++k) out[k] = table[static_cast<std::size_t>(clamp01(c[k]) * quantize + 0.5F)][k];
            return out;
        });
    }
    return forEachPixel(layer, mask, opacity, error, worker_threads,
                        [&](const Rgb& c, std::uint32_t x, std::uint32_t y, const std::array<float, 4>&) { return applyPixel(adjustment, c, x, y); });
}

color::Lut bakeLut(const std::vector<Adjustment>& stack, std::uint32_t size) {
    size = std::clamp<std::uint32_t>(size, 2, 129);
    color::Lut lut;
    lut.title = "baked adjustments";
    lut.size_3d = size;
    lut.table_3d.resize(static_cast<std::size_t>(size) * size * size * 3U);
    const float scale = 1.0F / static_cast<float>(size - 1);
    for (std::uint32_t b = 0; b < size; ++b)
        for (std::uint32_t g = 0; g < size; ++g)
            for (std::uint32_t r = 0; r < size; ++r) {
                Rgb c{r * scale, g * scale, b * scale};
                for (const auto& adjustment : stack) {
                    c = applyPixel(adjustment, c);
                    for (auto& v : c) v = clamp01(v);
                }
                const auto i = ((static_cast<std::size_t>(b) * size + g) * size + r) * 3U;
                std::copy(c.begin(), c.end(), lut.table_3d.begin() + static_cast<std::ptrdiff_t>(i));
            }
    return lut;
}

bool shadowsHighlights(TileStore& layer, const ShadowsHighlights& spec, const TileStore* mask, float opacity, std::string& error,
                       unsigned worker_threads) {
    if (!std::isfinite(spec.shadows) || !std::isfinite(spec.highlights) || spec.shadows < 0 || spec.shadows > 100 || spec.highlights < 0 ||
        spec.highlights > 100) {
        error = "shadows and highlights must be 0–100 %";
        return false;
    }
    if (!paint::detail::isRgba(layer.format())) {
        error = "adjustments apply to RGBA layers";
        return false;
    }
    try {
        // Blurred luminance (in every channel of a copy) drives the local tone curve.
        TileStore luminance(layer);
        if (!forEachPixel(luminance, nullptr, 1.0F, error, worker_threads, [](const Rgb& c, std::uint32_t, std::uint32_t, const std::array<float, 4>&) {
                const float l = clamp01(luma(c));
                return Rgb{l, l, l};
            })) {
            return false;
        }
        const float sigma = std::max(1.0F, 0.015F * static_cast<float>(std::max(layer.width(), layer.height())));
        if (!filters::gaussianBlur(luminance, std::min(sigma, 1000.0F), nullptr, error, worker_threads)) return false;
        const float s = spec.shadows / 100.0F, h = spec.highlights / 100.0F;
        return forEachPixel(
            layer, mask, opacity, error, worker_threads,
            [&](const Rgb& c, std::uint32_t, std::uint32_t, const std::array<float, 4>& blurred) {
                const float lb = clamp01(blurred[0]);
                const float gamma = std::max(0.1F, 1.0F - 0.6F * s * (1 - lb) * (1 - lb) + 0.6F * h * lb * lb);
                return Rgb{std::pow(clamp01(c[0]), gamma), std::pow(clamp01(c[1]), gamma), std::pow(clamp01(c[2]), gamma)};
            },
            &luminance);
    } catch (const std::exception&) {
        error = "could not allocate shadows/highlights buffers";
        return false;
    }
}

}  // namespace croptop::adjust
