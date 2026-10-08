#include "layer_styles.h"

#include <algorithm>
#include <atomic>
#include <cmath>
#include <exception>

#include "parallel_util.h"
#include "pixel_plane.h"
#include "scratch_store.h"
#include "selection_internal.h"

namespace croptop::styles {
namespace {

constexpr double kDegrees = 3.14159265358979323846 / 180.0;
using Plane = std::vector<float>;

struct Area {
    int x0 = 0, y0 = 0, w = 0, h = 0;
    std::size_t size() const { return static_cast<std::size_t>(w) * static_cast<std::size_t>(h); }
};

float clamp01(float v) { return std::clamp(v, 0.0F, 1.0F); }

/** Three-pass box blur approximating a Gaussian of `sigma`; values outside the area are `fill`. Rows and
 * columns run in parallel. */
void blur(Plane& p, const Area& a, float sigma, float fill) {
    if (sigma < 0.3F) return;
    const auto sizes = selection::detail::boxSizes(sigma);
    const auto workers = parallel::workerCount(0);
    for (const auto size : sizes) {
        const int r = static_cast<int>(size) / 2;
        const float inv = 1.0F / static_cast<float>(size);
        parallel::parallelFor(static_cast<std::size_t>(a.h), workers, [&](std::size_t yy, std::size_t) {
            const int y = static_cast<int>(yy);
            float* row = p.data() + static_cast<std::size_t>(y) * static_cast<std::size_t>(a.w);
            Plane line(static_cast<std::size_t>(a.w));
            const auto at = [&](int x) { return x < 0 || x >= a.w ? fill : row[x]; };
            double sum = 0;
            for (int k = -r; k <= r; ++k) sum += at(k);
            for (int x = 0; x < a.w; ++x) {
                line[static_cast<std::size_t>(x)] = static_cast<float>(sum) * inv;
                sum += at(x + r + 1) - at(x - r);
            }
            std::copy_n(line.data(), a.w, row);
        });
        parallel::parallelFor(static_cast<std::size_t>(a.w), workers, [&](std::size_t xx, std::size_t) {
            const int x = static_cast<int>(xx);
            Plane line(static_cast<std::size_t>(a.h));
            const auto at = [&](int y) { return y < 0 || y >= a.h ? fill : p[static_cast<std::size_t>(y) * static_cast<std::size_t>(a.w) + static_cast<std::size_t>(x)]; };
            double sum = 0;
            for (int k = -r; k <= r; ++k) sum += at(k);
            for (int y = 0; y < a.h; ++y) {
                line[static_cast<std::size_t>(y)] = static_cast<float>(sum) * inv;
                sum += at(y + r + 1) - at(y - r);
            }
            for (int y = 0; y < a.h; ++y) p[static_cast<std::size_t>(y) * static_cast<std::size_t>(a.w) + static_cast<std::size_t>(x)] = line[static_cast<std::size_t>(y)];
        });
    }
}

/** Bilinear shift by (dx, dy); samples from outside the area read `fill`. */
Plane shift(const Plane& p, const Area& a, double dx, double dy, float fill) {
    if (dx == 0.0 && dy == 0.0) return p;
    Plane out(p.size());
    const auto at = [&](int x, int y) { return x < 0 || y < 0 || x >= a.w || y >= a.h ? fill : p[static_cast<std::size_t>(y) * static_cast<std::size_t>(a.w) + static_cast<std::size_t>(x)]; };
    parallel::parallelFor(static_cast<std::size_t>(a.h), parallel::workerCount(0), [&](std::size_t yy, std::size_t) {
        const int y = static_cast<int>(yy);
        for (int x = 0; x < a.w; ++x) {
            const double sx = x - dx, sy = y - dy;
            const int ix = static_cast<int>(std::floor(sx)), iy = static_cast<int>(std::floor(sy));
            const auto tx = static_cast<float>(sx - ix), ty = static_cast<float>(sy - iy);
            out[static_cast<std::size_t>(y) * static_cast<std::size_t>(a.w) + static_cast<std::size_t>(x)] =
                (at(ix, iy) * (1 - tx) + at(ix + 1, iy) * tx) * (1 - ty) + (at(ix, iy + 1) * (1 - tx) + at(ix + 1, iy + 1) * tx) * ty;
        }
    });
    return out;
}

/** Euclidean distance (px) from every pixel to the nearest pixel where `plane` ≥ 0.5 (0 on those pixels). */
Plane distanceTo(const Plane& p, const Area& a, bool inside_shape) {
    std::vector<std::uint8_t> source(p.size());
    for (std::size_t i = 0; i < p.size(); ++i) source[i] = (p[i] >= 0.5F) == inside_shape ? 1U : 0U;
    selection::detail::Region region{0, 0, static_cast<std::uint32_t>(a.w), static_cast<std::uint32_t>(a.h)};
    auto d = selection::detail::distanceField(region, source);
    for (auto& v : d) v = std::sqrt(v);
    return d;
}

/** Grows the ≥ 50 % shape of `p` by `amount` px (anti-aliased), keeping existing coverage. */
void dilate(Plane& p, const Area& a, float amount) {
    if (amount <= 0.0F) return;
    const auto d = distanceTo(p, a, true);
    // Distances run between pixel centres, so the shape's edge is at d = 0.5: a pixel at d is covered up to
    // `amount` px beyond the edge when d ≤ amount (+ half a pixel of anti-aliasing each side).
    for (std::size_t i = 0; i < p.size(); ++i) p[i] = std::max(p[i], clamp01(amount + 1.0F - d[i]));
}

/** Area of the layer's content grown by `margin`, clipped to the canvas; empty when the layer is empty. */
std::optional<Area> contentArea(const TileStore& layer, int margin) {
    int x0 = static_cast<int>(layer.width()), y0 = static_cast<int>(layer.height()), x1 = 0, y1 = 0;
    for (const auto c : layer.tileCoordinates()) {
        x0 = std::min(x0, c.x * static_cast<int>(kTileEdge));
        y0 = std::min(y0, c.y * static_cast<int>(kTileEdge));
        x1 = std::max(x1, (c.x + 1) * static_cast<int>(kTileEdge));
        y1 = std::max(y1, (c.y + 1) * static_cast<int>(kTileEdge));
    }
    if (x1 <= x0 || y1 <= y0) return std::nullopt;
    Area a;
    a.x0 = std::max(0, x0 - margin);
    a.y0 = std::max(0, y0 - margin);
    a.w = std::min(static_cast<int>(layer.width()), x1 + margin) - a.x0;
    a.h = std::min(static_cast<int>(layer.height()), y1 + margin) - a.y0;
    return a;
}

EffectLayer makeLayer(const TileStore& layer, const Area& a, const std::vector<Rgba>& colors, const Plane& coverage, BlendMode mode,
                      float opacity, bool below, const char* name) {
    // Effects are derived tiles, so keep them under the document's same byte budget and scratch store.
    EffectLayer out{layer.emptyLike(layer.format()), mode, clamp01(opacity), below, name};
    const auto bpp = out.pixels.bytes_per_pixel();
    const int tx0 = a.x0 / static_cast<int>(kTileEdge), ty0 = a.y0 / static_cast<int>(kTileEdge);
    const int tx1 = (a.x0 + a.w - 1) / static_cast<int>(kTileEdge), ty1 = (a.y0 + a.h - 1) / static_cast<int>(kTileEdge);
    const auto columns = static_cast<std::size_t>(tx1 - tx0 + 1);
    std::atomic<bool> failed{false};
    parallel::parallelFor(columns * static_cast<std::size_t>(ty1 - ty0 + 1), parallel::workerCount(0), [&](std::size_t item, std::size_t) {
        const int tx = tx0 + static_cast<int>(item % columns), ty = ty0 + static_cast<int>(item / columns);
        {
            TileBuffer tile(out.pixels.tile_byte_size(), 0);
            bool any = false;
            for (int ly = 0; ly < static_cast<int>(kTileEdge); ++ly) {
                const int y = ty * static_cast<int>(kTileEdge) + ly - a.y0;
                if (y < 0 || y >= a.h) continue;
                for (int lx = 0; lx < static_cast<int>(kTileEdge); ++lx) {
                    const int x = tx * static_cast<int>(kTileEdge) + lx - a.x0;
                    if (x < 0 || x >= a.w) continue;
                    const auto i = static_cast<std::size_t>(y) * static_cast<std::size_t>(a.w) + static_cast<std::size_t>(x);
                    const float cov = clamp01(coverage[i]);
                    if (cov <= 0.0F) continue;
                    Rgba px = colors.size() == 1 ? colors[0] : colors[i];
                    px[3] *= cov;
                    paint::detail::writePixel(out.pixels.format(), tile.data() + (static_cast<std::size_t>(ly) * kTileEdge + static_cast<std::size_t>(lx)) * bpp, px.data());
                    any = true;
                }
            }
            if (any && !out.pixels.writeTile({tx, ty}, tile)) failed.store(true);
        }
    });
    if (failed.load()) throw std::runtime_error("could not store an effect layer");
    return out;
}

Rgba gradientAt(const std::vector<GradientStop>& unsorted, float t) {
    auto stops = unsorted;
    std::sort(stops.begin(), stops.end(), [](const GradientStop& a, const GradientStop& b) { return a.position < b.position; });
    if (stops.empty()) return {0, 0, 0, 0};
    if (t <= stops.front().position) return stops.front().color;
    if (t >= stops.back().position) return stops.back().color;
    std::size_t i = 0;
    while (i + 2 < stops.size() && stops[i + 1].position <= t) ++i;
    const float f = stops[i + 1].position == stops[i].position ? 0 : (t - stops[i].position) / (stops[i + 1].position - stops[i].position);
    Rgba c{};
    for (std::size_t k = 0; k < 4; ++k) c[k] = stops[i].color[k] + f * (stops[i + 1].color[k] - stops[i].color[k]);
    return c;
}

}  // namespace

std::optional<std::vector<EffectLayer>> render(const TileStore& layer, const LayerStyles& s, std::string& error, std::uint64_t max_working_bytes) {
    if (!paint::detail::isRgba(layer.format())) {
        error = "layer styles need an RGBA layer";
        return std::nullopt;
    }
    const auto sane = [](float v, float lo, float hi) { return std::isfinite(v) && v >= lo && v <= hi; };
    for (const auto* shadow : {&s.drop_shadow, &s.inner_shadow})
        if (shadow->enabled && (!sane(shadow->distance, 0, 30000) || !sane(shadow->size, 0, 250) || !sane(shadow->spread, 0, 100) || !sane(shadow->angle, -360, 360))) {
            error = "shadow distance 0–30000 px, size 0–250 px, spread 0–100 %";
            return std::nullopt;
        }
    for (const auto* glow : {&s.outer_glow, &s.inner_glow})
        if (glow->enabled && (!sane(glow->size, 0, 250) || !sane(glow->spread, 0, 100))) {
            error = "glow size 0–250 px, spread 0–100 %";
            return std::nullopt;
        }
    if ((s.stroke.enabled && !sane(s.stroke.size, 1, 250)) || (s.bevel.enabled && (!sane(s.bevel.size, 0, 250) || !sane(s.bevel.soften, 0, 16) || !sane(s.bevel.depth, 0.01F, 10) || !sane(s.bevel.altitude, 0, 90)))) {
        error = "stroke size 1–250 px; bevel size 0–250, soften 0–16, depth 0.01–10, altitude 0–90";
        return std::nullopt;
    }
    std::vector<EffectLayer> out;
    int margin = 2;
    if (s.drop_shadow.enabled) margin = std::max(margin, static_cast<int>(std::ceil(s.drop_shadow.distance + s.drop_shadow.size * 1.5F)) + 4);
    if (s.outer_glow.enabled) margin = std::max(margin, static_cast<int>(std::ceil(s.outer_glow.size * 1.5F)) + 4);
    if (s.stroke.enabled) margin = std::max(margin, static_cast<int>(std::ceil(s.stroke.size)) + 4);
    if (s.bevel.enabled) margin = std::max(margin, static_cast<int>(std::ceil(s.bevel.size + s.bevel.soften)) + 4);
    if (s.satin.enabled && (!sane(s.satin.distance, 0, 250) || !sane(s.satin.size, 0, 250) || !sane(s.satin.angle, -360, 360))) {
        error = "satin distance and size must be 0–250 px";
        return std::nullopt;
    }
    const auto area = contentArea(layer, margin);
    if (!area) return out;
    const Area a = *area;
    const auto working_bytes = static_cast<std::uint64_t>(a.size()) * 4U * 8U;
    if (working_bytes > max_working_bytes) {
        error = "the styled area exceeds the working memory limit";
        return std::nullopt;
    }
    const auto memory_budget = layer.memoryBudgetManager();
    if (memory_budget && !memory_budget->reserveWorkingBytes(static_cast<std::size_t>(working_bytes))) {
        error = "the styled area exceeds the document memory budget";
        return std::nullopt;
    }
    struct WorkingReservation final {
        std::shared_ptr<MemoryBudgetManager> budget;
        std::size_t bytes = 0;
        ~WorkingReservation() { if (budget) budget->releaseWorkingBytes(bytes); }
    } reservation{memory_budget, memory_budget ? static_cast<std::size_t>(working_bytes) : 0U};
    try {
        Plane alpha(a.size(), 0.0F);
        {
            const auto bpp = layer.bytes_per_pixel();
            for (int y = 0; y < a.h; ++y) {
                std::shared_ptr<const TileBuffer> tile;
                int loaded = -1;
                const int gy = a.y0 + y;
                for (int x = 0; x < a.w; ++x) {
                    const int gx = a.x0 + x;
                    if (gx / static_cast<int>(kTileEdge) != loaded) {
                        loaded = gx / static_cast<int>(kTileEdge);
                        tile = layer.readTile({loaded, gy / static_cast<int>(kTileEdge)});
                    }
                    if (!tile) continue;
                    std::array<float, 4> px{};
                    paint::detail::readPixel(layer.format(), tile->data() + (static_cast<std::size_t>(gy % kTileEdge) * kTileEdge + static_cast<std::size_t>(gx % kTileEdge)) * bpp, px.data());
                    alpha[static_cast<std::size_t>(y) * static_cast<std::size_t>(a.w) + static_cast<std::size_t>(x)] = clamp01(px[3]);
                }
            }
        }
        const auto offsetOf = [](const Shadow& sh) {
            return std::pair<double, double>{-sh.distance * std::cos(sh.angle * kDegrees), sh.distance * std::sin(sh.angle * kDegrees)};
        };
        // Below the content.
        if (s.drop_shadow.enabled) {
            const auto [dx, dy] = offsetOf(s.drop_shadow);
            auto m = shift(alpha, a, dx, dy, 0.0F);
            dilate(m, a, s.drop_shadow.spread / 100.0F * s.drop_shadow.size);
            blur(m, a, (1.0F - s.drop_shadow.spread / 100.0F) * s.drop_shadow.size / 2.0F, 0.0F);
            out.push_back(makeLayer(layer, a, {s.drop_shadow.color}, m, s.drop_shadow.mode, s.drop_shadow.opacity, true, "Drop Shadow"));
        }
        // Glows: full within spread, then a smooth fade to nothing at `size` px from the edge.
        const auto glowRamp = [](float d, const Glow& g) {
            const float hard = g.spread / 100.0F * g.size, soft = g.size - hard;
            if (soft < 0.5F) return clamp01(g.size + 1.0F - d);
            const float t = clamp01((d - 1.0F - hard) / soft);
            return 1.0F - t * t * (3.0F - 2.0F * t);
        };
        if (s.outer_glow.enabled) {
            const auto d = distanceTo(alpha, a, true);
            Plane m(a.size());
            for (std::size_t i = 0; i < a.size(); ++i) m[i] = std::max(alpha[i], glowRamp(d[i], s.outer_glow));
            out.push_back(makeLayer(layer, a, {s.outer_glow.color}, m, s.outer_glow.mode, s.outer_glow.opacity, true, "Outer Glow"));
        }
        // Bevel & emboss.
        if (s.bevel.enabled && s.bevel.size > 0.0F) {
            const auto& b = s.bevel;
            const auto d_in = distanceTo(alpha, a, false);   // inside pixels: distance to the outside
            const auto d_out = distanceTo(alpha, a, true);   // outside pixels: distance to the shape
            Plane height(a.size(), 0.0F), inner_mask(a.size(), 0.0F), outer_mask(a.size(), 0.0F);
            for (std::size_t i = 0; i < a.size(); ++i) {
                const float in = std::min(d_in[i], b.size) / b.size, outside = std::min(d_out[i], b.size) / b.size;
                switch (b.style) {
                    case BevelStyle::InnerBevel: height[i] = alpha[i] >= 0.5F ? in : 0.0F; break;
                    case BevelStyle::OuterBevel: height[i] = alpha[i] >= 0.5F ? 1.0F : 1.0F - outside; break;
                    case BevelStyle::Emboss: height[i] = alpha[i] >= 0.5F ? 0.5F + 0.5F * in : 0.5F - 0.5F * outside; break;
                    case BevelStyle::PillowEmboss: height[i] = alpha[i] >= 0.5F ? in : outside; break;
                }
                inner_mask[i] = alpha[i];
                outer_mask[i] = (1.0F - alpha[i]) * (d_out[i] <= b.size + 1.0F ? 1.0F : 0.0F);
            }
            if (b.technique == BevelTechnique::Smooth) blur(height, a, b.size / 4.0F, 0.0F);
            blur(height, a, b.soften / 2.0F, 0.0F);
            const double lx = std::cos(b.altitude * kDegrees) * std::cos(b.angle * kDegrees), ly = -std::cos(b.altitude * kDegrees) * std::sin(b.angle * kDegrees);
            const double lz = std::sin(b.altitude * kDegrees);
            Plane highlight(a.size(), 0.0F), shadow(a.size(), 0.0F);
            const double strength = b.depth * b.size * (b.up ? 1.0 : -1.0);
            parallel::parallelFor(static_cast<std::size_t>(a.h), parallel::workerCount(0), [&](std::size_t row, std::size_t) {
                const int y = static_cast<int>(row);
                for (int x = 0; x < a.w; ++x) {
                    const auto at = [&](int xx, int yy) {
                        xx = std::clamp(xx, 0, a.w - 1), yy = std::clamp(yy, 0, a.h - 1);
                        return height[static_cast<std::size_t>(yy) * static_cast<std::size_t>(a.w) + static_cast<std::size_t>(xx)];
                    };
                    const double gx = (at(x + 1, y) - at(x - 1, y)) * 0.5 * strength, gy = (at(x, y + 1) - at(x, y - 1)) * 0.5 * strength;
                    const double n = std::sqrt(gx * gx + gy * gy + 1.0);
                    const double shade = (-gx * lx - gy * ly + lz) / n;
                    const auto i = static_cast<std::size_t>(y) * static_cast<std::size_t>(a.w) + static_cast<std::size_t>(x);
                    const float mask = b.style == BevelStyle::InnerBevel ? inner_mask[i]
                                       : b.style == BevelStyle::OuterBevel ? outer_mask[i]
                                                                           : std::max(inner_mask[i], outer_mask[i]);
                    highlight[i] = mask * clamp01(static_cast<float>((shade - lz) / std::max(1e-6, 1.0 - lz)));
                    shadow[i] = mask * clamp01(static_cast<float>((lz - shade) / std::max(1e-6, lz)));
                }
            });
            const bool below = b.style == BevelStyle::OuterBevel;
            out.push_back(makeLayer(layer, a, {b.shadow_color}, shadow, b.shadow_mode, b.shadow_opacity, below, "Bevel Shadow"));
            out.push_back(makeLayer(layer, a, {b.highlight_color}, highlight, b.highlight_mode, b.highlight_opacity, below, "Bevel Highlight"));
        }
        // Interior effects, clipped to the content.
        if (s.inner_shadow.enabled) {
            const auto [dx, dy] = offsetOf(s.inner_shadow);
            Plane inverse(a.size());
            for (std::size_t i = 0; i < a.size(); ++i) inverse[i] = 1.0F - alpha[i];
            auto m = shift(inverse, a, dx, dy, 1.0F);
            dilate(m, a, s.inner_shadow.spread / 100.0F * s.inner_shadow.size);
            blur(m, a, (1.0F - s.inner_shadow.spread / 100.0F) * s.inner_shadow.size / 2.0F, 1.0F);
            for (std::size_t i = 0; i < a.size(); ++i) m[i] *= alpha[i];
            out.push_back(makeLayer(layer, a, {s.inner_shadow.color}, m, s.inner_shadow.mode, s.inner_shadow.opacity, false, "Inner Shadow"));
        }
        if (s.inner_glow.enabled) {
            const auto d = distanceTo(alpha, a, false);  // inside pixels: distance to the outside
            Plane m(a.size());
            for (std::size_t i = 0; i < a.size(); ++i) {
                const float edge = glowRamp(d[i], s.inner_glow);
                m[i] = (s.inner_glow.from_center ? 1.0F - edge : edge) * alpha[i];
            }
            out.push_back(makeLayer(layer, a, {s.inner_glow.color}, m, s.inner_glow.mode, s.inner_glow.opacity, false, "Inner Glow"));
        }
        if (s.satin.enabled) {
            const double dx = s.satin.distance * std::cos(s.satin.angle * kDegrees), dy = -s.satin.distance * std::sin(s.satin.angle * kDegrees);
            auto a1 = shift(alpha, a, dx, dy, 0.0F), a2 = shift(alpha, a, -dx, -dy, 0.0F);
            blur(a1, a, s.satin.size / 2.0F, 0.0F);
            blur(a2, a, s.satin.size / 2.0F, 0.0F);
            Plane m(a.size());
            for (std::size_t i = 0; i < a.size(); ++i) {
                const float d = std::fabs(a1[i] - a2[i]);
                m[i] = (s.satin.invert ? 1.0F - d : d) * alpha[i];
            }
            out.push_back(makeLayer(layer, a, {s.satin.color}, m, s.satin.mode, s.satin.opacity, false, "Satin"));
        }
        if (s.color_overlay.enabled) out.push_back(makeLayer(layer, a, {s.color_overlay.color}, alpha, s.color_overlay.mode, s.color_overlay.opacity, false, "Color Overlay"));
        if (s.gradient_overlay.enabled) {
            // Runs across the content's bounds along `angle` (90° = bottom to top), scaled about the centre.
            int bx0 = a.w, by0 = a.h, bx1 = -1, by1 = -1;
            for (int y = 0; y < a.h; ++y)
                for (int x = 0; x < a.w; ++x)
                    if (alpha[static_cast<std::size_t>(y) * static_cast<std::size_t>(a.w) + static_cast<std::size_t>(x)] > 0.0F) {
                        bx0 = std::min(bx0, x), by0 = std::min(by0, y), bx1 = std::max(bx1, x), by1 = std::max(by1, y);
                    }
            std::vector<Rgba> colors(a.size(), Rgba{});
            if (bx1 >= 0) {
                const double ux = std::cos(s.gradient_overlay.angle * kDegrees), uy = -std::sin(s.gradient_overlay.angle * kDegrees);
                const double cx = (bx0 + bx1 + 1) / 2.0, cy = (by0 + by1 + 1) / 2.0;
                const double half = 0.5 * (std::fabs(ux) * (bx1 - bx0 + 1) + std::fabs(uy) * (by1 - by0 + 1)) * std::clamp(s.gradient_overlay.scale, 0.1F, 1.5F);
                for (int y = 0; y < a.h; ++y)
                    for (int x = 0; x < a.w; ++x) {
                        float t = static_cast<float>(((x + 0.5 - cx) * ux + (y + 0.5 - cy) * uy) / (2.0 * std::max(half, 1e-6)) + 0.5);
                        if (s.gradient_overlay.reverse) t = 1.0F - t;
                        colors[static_cast<std::size_t>(y) * static_cast<std::size_t>(a.w) + static_cast<std::size_t>(x)] = gradientAt(s.gradient_overlay.stops, clamp01(t));
                    }
            }
            Plane coverage(a.size());
            for (std::size_t i = 0; i < a.size(); ++i) coverage[i] = alpha[i] * colors[i][3];
            for (auto& c : colors) c[3] = 1.0F;
            out.push_back(makeLayer(layer, a, colors, coverage, s.gradient_overlay.mode, s.gradient_overlay.opacity, false, "Gradient Overlay"));
        }
        if (s.pattern_overlay.enabled && s.pattern_overlay.pattern != nullptr && paint::detail::isRgba(s.pattern_overlay.pattern->format()) &&
            s.pattern_overlay.pattern->width() > 0 && s.pattern_overlay.pattern->height() > 0) {
            const auto& pat = *s.pattern_overlay.pattern;
            const double scale = std::clamp(s.pattern_overlay.scale, 0.01F, 10.0F);
            std::vector<Rgba> colors(a.size());
            Plane coverage(a.size());
            for (int y = 0; y < a.h; ++y)
                for (int x = 0; x < a.w; ++x) {
                    const auto px = static_cast<std::uint32_t>(std::fmod(std::floor((a.x0 + x) / scale), pat.width()));
                    const auto py = static_cast<std::uint32_t>(std::fmod(std::floor((a.y0 + y) / scale), pat.height()));
                    Rgba c{};
                    const auto t = pat.readTile({static_cast<std::int32_t>(px / kTileEdge), static_cast<std::int32_t>(py / kTileEdge)});
                    if (t) paint::detail::readPixel(pat.format(), t->data() + (static_cast<std::size_t>(py % kTileEdge) * kTileEdge + px % kTileEdge) * pat.bytes_per_pixel(), c.data());
                    const auto i = static_cast<std::size_t>(y) * static_cast<std::size_t>(a.w) + static_cast<std::size_t>(x);
                    coverage[i] = alpha[i] * c[3];
                    c[3] = 1.0F;
                    colors[i] = c;
                }
            out.push_back(makeLayer(layer, a, colors, coverage, s.pattern_overlay.mode, s.pattern_overlay.opacity, false, "Pattern Overlay"));
        }
        if (s.stroke.enabled) {
            const float size = s.stroke.size;
            const auto d_out = distanceTo(alpha, a, true), d_in = distanceTo(alpha, a, false);
            Plane m(a.size());
            for (std::size_t i = 0; i < a.size(); ++i) {
                const float width = s.stroke.position == StrokePosition::Center ? size / 2 : size;
                const float outside = clamp01(width + 1.0F - d_out[i]) * (1.0F - alpha[i]);
                const float inside = clamp01(width + 1.0F - d_in[i]) * alpha[i];
                m[i] = s.stroke.position == StrokePosition::Outside ? outside : s.stroke.position == StrokePosition::Inside ? inside : outside + inside;
            }
            out.push_back(makeLayer(layer, a, {s.stroke.color}, m, s.stroke.mode, s.stroke.opacity, false, "Stroke"));
        }
    } catch (const std::bad_alloc&) {
        error = "not enough memory for layer styles";
        return std::nullopt;
    } catch (const std::exception& e) {
        error = e.what();
        return std::nullopt;
    }
    return out;
}

std::optional<TileStore> flatten(const TileStore& layer, const LayerStyles& styles, const TileStore& backdrop, float fill_opacity, std::string& error) {
    if (!paint::detail::isRgba(backdrop.format()) || backdrop.width() != layer.width() || backdrop.height() != layer.height()) {
        error = "the backdrop must be an RGBA store of the layer's size";
        return std::nullopt;
    }
    auto effects = render(layer, styles, error);
    if (!effects) return std::nullopt;
    TileStore result(backdrop);
    const auto composite = [&](const TileStore& source, BlendMode mode, float opacity) {
        for (const auto c : source.tileCoordinates()) {
            const auto src = source.readTile(c);
            const auto existing = result.readTile(c);
            TileBuffer tile = existing ? *existing : TileBuffer(result.tile_byte_size(), 0);
            for (std::size_t i = 0; i < kTileEdge * kTileEdge; ++i) {
                BlendPixelF s{}, b{};
                paint::detail::readPixel(source.format(), src->data() + i * source.bytes_per_pixel(), s.data());
                if (s[3] <= 0.0F) continue;
                paint::detail::readPixel(result.format(), tile.data() + i * result.bytes_per_pixel(), b.data());
                const auto out = blendRgbaF(b, s, mode, opacity);
                paint::detail::writePixel(result.format(), tile.data() + i * result.bytes_per_pixel(), out.data());
            }
            if (!result.writeTile(c, tile)) throw std::runtime_error("could not store the flattened result");
        }
    };
    try {
        for (const auto& e : *effects)
            if (e.below_content) composite(e.pixels, e.mode, e.opacity);
        composite(layer, BlendMode::Normal, std::clamp(fill_opacity, 0.0F, 1.0F));
        for (const auto& e : *effects)
            if (!e.below_content) composite(e.pixels, e.mode, e.opacity);
    } catch (const std::exception& e) {
        error = e.what();
        return std::nullopt;
    }
    return result;
}

}  // namespace croptop::styles
