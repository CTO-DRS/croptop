// Develop tone operations.
#include "develop_tone.h"

#include <algorithm>
#include <atomic>
#include <cmath>
#include <cstring>
#include <memory>
#include <vector>

#include "parallel_util.h"
#include "develop_internal.h"
#include "pixel_plane.h"

namespace croptop::develop {
namespace {

using detail::gaussian;
using detail::Neighbourhood;

constexpr float kMidGrey = -2.4739312F;  // log2(0.18)
constexpr float kFloor = 1e-6F;           // luminance floor before log2
constexpr int kLowResLongSide = 1024;

float smoothstep(float e0, float e1, float x) {
    const float t = std::clamp((x - e0) / (e1 - e0), 0.0F, 1.0F);
    return t * t * (3.0F - 2.0F * t);
}

/** A low-resolution float plane with integral-image box means. */
struct Plane {
    int w = 0, h = 0;
    std::vector<float> v;
    float& at(int x, int y) { return v[static_cast<std::size_t>(y) * w + x]; }
    float at(int x, int y) const { return v[static_cast<std::size_t>(y) * w + x]; }
    /** Bilinear sample at plane coordinates (pixel centres at integer + 0.5). */
    float sample(float x, float y) const {
        const float fx = std::clamp(x - 0.5F, 0.0F, static_cast<float>(w - 1));
        const float fy = std::clamp(y - 0.5F, 0.0F, static_cast<float>(h - 1));
        const int x0 = static_cast<int>(fx), y0 = static_cast<int>(fy);
        const int x1 = std::min(w - 1, x0 + 1), y1 = std::min(h - 1, y0 + 1);
        const float ax = fx - static_cast<float>(x0), ay = fy - static_cast<float>(y0);
        return (1 - ay) * ((1 - ax) * at(x0, y0) + ax * at(x1, y0)) + ay * ((1 - ax) * at(x0, y1) + ax * at(x1, y1));
    }
};

/** Box means (clamped window, mean over the pixels inside), separable and row/column parallel. */
Plane boxMean(const Plane& p, int r) {
    const unsigned workers = parallel::workerCount(0);
    std::vector<double> rows(p.v.size());
    parallel::parallelFor(static_cast<std::size_t>(p.h), workers, [&](std::size_t y, std::size_t) {
        std::vector<double> prefix(static_cast<std::size_t>(p.w) + 1, 0.0);
        const float* in = p.v.data() + y * static_cast<std::size_t>(p.w);
        for (int x = 0; x < p.w; ++x) prefix[static_cast<std::size_t>(x + 1)] = prefix[static_cast<std::size_t>(x)] + in[x];
        double* out = rows.data() + y * static_cast<std::size_t>(p.w);
        for (int x = 0; x < p.w; ++x) out[x] = prefix[static_cast<std::size_t>(std::min(p.w, x + r + 1))] - prefix[static_cast<std::size_t>(std::max(0, x - r))];
    });
    // Vertical pass over a full 2-D prefix (contiguous rows: one sequential build, then rows in parallel).
    const auto w = static_cast<std::size_t>(p.w);
    std::vector<double> prefix((static_cast<std::size_t>(p.h) + 1) * w, 0.0);
    for (std::size_t y = 0; y < static_cast<std::size_t>(p.h); ++y) {
        const double* above = prefix.data() + y * w;
        const double* row = rows.data() + y * w;
        double* below = prefix.data() + (y + 1) * w;
        for (std::size_t x = 0; x < w; ++x) below[x] = above[x] + row[x];
    }
    Plane out{p.w, p.h, std::vector<float>(p.v.size())};
    parallel::parallelFor(static_cast<std::size_t>(p.h), workers, [&](std::size_t ys, std::size_t) {
        const int y = static_cast<int>(ys);
        const int y0 = std::max(0, y - r), y1 = std::min(p.h, y + r + 1);
        const double* top = prefix.data() + static_cast<std::size_t>(y0) * w;
        const double* bottom = prefix.data() + static_cast<std::size_t>(y1) * w;
        for (int x = 0; x < p.w; ++x) {
            const double xcount = std::min(p.w, x + r + 1) - std::max(0, x - r);
            out.at(x, y) = static_cast<float>((bottom[x] - top[x]) / (xcount * (y1 - y0)));
        }
    });
    return out;
}

Plane multiply(const Plane& a, const Plane& b) {
    Plane out{a.w, a.h, std::vector<float>(a.v.size())};
    for (std::size_t i = 0; i < a.v.size(); ++i) out.v[i] = a.v[i] * b.v[i];
    return out;
}

/** Guided-filter coefficient planes (mean a, mean b): output = A·guide + B. */
struct Guided {
    Plane a, b;
};

Guided guidedCoefficients(const Plane& guide, const Plane& input, int r, float eps) {
    // Self-guided filters (local tone, clarity) have input == guide: two of the four box means are then identical.
    const bool self_guided = &guide == &input;
    const Plane mean_g = boxMean(guide, r);
    const Plane mean_p = self_guided ? mean_g : boxMean(input, r);
    const Plane corr_gg = boxMean(multiply(guide, guide), r);
    const Plane corr_gp = self_guided ? corr_gg : boxMean(multiply(guide, input), r);
    Plane a{guide.w, guide.h, std::vector<float>(guide.v.size())}, b = a;
    for (std::size_t i = 0; i < guide.v.size(); ++i) {
        const float var = corr_gg.v[i] - mean_g.v[i] * mean_g.v[i];
        const float cov = corr_gp.v[i] - mean_g.v[i] * mean_p.v[i];
        a.v[i] = cov / (var + eps);
        b.v[i] = mean_p.v[i] - a.v[i] * mean_g.v[i];
    }
    return {boxMean(a, r), boxMean(b, r)};
}

Plane minFilter(const Plane& p, int r) {
    Plane tmp = p, out = p;
    for (int y = 0; y < p.h; ++y)
        for (int x = 0; x < p.w; ++x) {
            float m = p.at(x, y);
            for (int k = std::max(0, x - r); k <= std::min(p.w - 1, x + r); ++k) m = std::min(m, p.at(k, y));
            tmp.at(x, y) = m;
        }
    for (int y = 0; y < p.h; ++y)
        for (int x = 0; x < p.w; ++x) {
            float m = tmp.at(x, y);
            for (int k = std::max(0, y - r); k <= std::min(p.h - 1, y + r); ++k) m = std::min(m, tmp.at(x, k));
            out.at(x, y) = m;
        }
    return out;
}

float contrastCurve(float l, float contrast) {
    if (contrast == 0.0F) return l;
    const float s = std::exp2(contrast / 100.0F);
    const float x = (l - kMidGrey) / 4.0F, ax = std::abs(x);
    const float y = ax <= 1.0F ? x * s / (1.0F + (s - 1.0F) * ax) : std::copysign(1.0F + (ax - 1.0F) / s, x);
    return kMidGrey + 4.0F * y;
}

/** Parametric tone curve on display-encoded v (0–1): four smooth regional bumps, kept monotonic via a LUT. */
std::vector<float> parametricLut(const ParametricCurve& c) {
    constexpr int kSize = 1024;
    std::vector<float> lut(kSize + 1);
    const float s1 = std::clamp(c.split_shadows, 0.05F, 0.9F), s2 = std::clamp(c.split_midtones, s1 + 0.02F, 0.95F);
    const float s3 = std::clamp(c.split_highlights, s2 + 0.02F, 0.98F);
    const std::array<std::array<float, 3>, 4> regions{{{c.shadows, 0.0F, s1}, {c.darks, s1, s2}, {c.lights, s2, s3}, {c.highlights, s3, 1.0F}}};
    for (int i = 0; i <= kSize; ++i) {
        const float v = static_cast<float>(i) / kSize;
        float out = v;
        for (const auto& [amount, lo, hi] : regions) {
            if (amount == 0.0F) continue;
            const float centre = 0.5F * (lo + hi), half = hi - lo;  // windows overlap their neighbours
            const float t = (v - centre) / half;
            if (std::abs(t) >= 1.0F) continue;
            const float window = std::cos(t * 1.5707963F);
            out += amount / 100.0F * 0.2F * window * window * 4.0F * v * (1.0F - v);
        }
        lut[static_cast<std::size_t>(i)] = std::clamp(out, 0.0F, 1.0F);
        if (i > 0) lut[static_cast<std::size_t>(i)] = std::max(lut[static_cast<std::size_t>(i)], lut[static_cast<std::size_t>(i - 1)]);
    }
    return lut;
}

float lutAt(const std::vector<float>& lut, float v) {
    const float x = std::clamp(v, 0.0F, 1.0F) * static_cast<float>(lut.size() - 1);
    const auto i = std::min(static_cast<std::size_t>(x), lut.size() - 2);
    const float f = x - static_cast<float>(i);
    return lut[i] + (lut[i + 1] - lut[i]) * f;
}

}  // namespace

bool ToneSettings::isIdentity() const {
    const bool curve_identity = point_curve.points.size() == 2 && point_curve.points[0] == std::pair{0.0F, 0.0F} &&
                                point_curve.points[1] == std::pair{1.0F, 1.0F};
    return dehaze == 0 && exposure == 0 && contrast == 0 && highlights == 0 && shadows == 0 && whites == 0 && blacks == 0 && texture == 0 &&
           clarity == 0 && vibrance == 0 && saturation == 0 && parametric.isIdentity() && curve_identity;
}

bool validateToneSettings(const ToneSettings& t, std::string& error) {
    const float sliders[] = {t.dehaze, t.contrast, t.highlights, t.shadows, t.whites, t.blacks, t.texture, t.clarity, t.vibrance, t.saturation};
    for (const float v : sliders) {
        if (!std::isfinite(v) || v < -100.0F || v > 100.0F) {
            error = "develop sliders must be within −100…100";
            return false;
        }
    }
    if (!std::isfinite(t.exposure) || std::abs(t.exposure) > 5.0F) {
        error = "exposure must be within ±5 stops";
        return false;
    }
    for (const float v : t.luminance) {
        if (!std::isfinite(v) || v < 0.0F) {
            error = "luminance weights must be finite and non-negative";
            return false;
        }
    }
    return true;
}

std::optional<TonePlan> makeTonePlan(const TileStore& linear, const ToneSettings& t, std::string& error) {
    const std::int64_t W = linear.width(), H = linear.height();
    const auto lum = [&](const float* rgb) { return t.luminance[0] * rgb[0] + t.luminance[1] * rgb[1] + t.luminance[2] * rgb[2]; };
    const unsigned workers = parallel::workerCount(t.worker_threads);
    (void)H;
    // ── Pass 1: low-resolution linear RGB (block means). ──
    const int block = std::max<int>(1, static_cast<int>((std::max(W, H) + kLowResLongSide - 1) / kLowResLongSide));
    const int lw = static_cast<int>((W + block - 1) / block), lh = static_cast<int>((H + block - 1) / block);
    Plane low_r{lw, lh, std::vector<float>(static_cast<std::size_t>(lw) * lh)}, low_g = low_r, low_b = low_r;
    parallel::parallelFor(static_cast<std::size_t>(lh), workers, [&](std::size_t row, std::size_t) {
        const auto by = static_cast<std::int64_t>(row);
        std::vector<double> sum(static_cast<std::size_t>(lw) * 3, 0.0);
        std::vector<int> count(static_cast<std::size_t>(lw), 0);
        std::shared_ptr<const TileBuffer> tile;
        std::int64_t cached = -1;
        for (std::int64_t y = by * block; y < std::min(H, (by + 1) * block); ++y) {
            for (std::int64_t x = 0; x < W; ++x) {
                const std::int64_t key = (y / kTileEdge) * 100000 + x / kTileEdge;
                if (key != cached) {
                    tile = linear.readTile({static_cast<std::int32_t>(x / kTileEdge), static_cast<std::int32_t>(y / kTileEdge)});
                    cached = key;
                }
                float px[4] = {0, 0, 0, 0};
                if (tile) paint::detail::readPixel(linear.format(), tile->data() + (static_cast<std::size_t>(y % kTileEdge) * kTileEdge + static_cast<std::size_t>(x % kTileEdge)) * linear.bytes_per_pixel(), px);
                const auto bx = static_cast<std::size_t>(x / block);
                for (int c = 0; c < 3; ++c) sum[bx * 3 + static_cast<std::size_t>(c)] += std::max(0.0F, px[c]);
                ++count[bx];
            }
        }
        for (int bx = 0; bx < lw; ++bx) {
            const double n = std::max(1, count[static_cast<std::size_t>(bx)]);
            low_r.at(bx, static_cast<int>(by)) = static_cast<float>(sum[static_cast<std::size_t>(bx) * 3] / n);
            low_g.at(bx, static_cast<int>(by)) = static_cast<float>(sum[static_cast<std::size_t>(bx) * 3 + 1] / n);
            low_b.at(bx, static_cast<int>(by)) = static_cast<float>(sum[static_cast<std::size_t>(bx) * 3 + 2] / n);
        }
    });
    if (t.progress && !t.progress(0.2F)) {
        error = "cancelled";
        return std::nullopt;
    }

    // ── Dehaze (dark channel prior) on the low-resolution image; refined by a fast guided filter. ──
    std::array<float, 3> air{1, 1, 1};
    Guided haze;
    const auto perceptual = [](float y) { return std::pow(std::max(y, 0.0F), 1.0F / 2.2F); };
    Plane low_guide{lw, lh, std::vector<float>(low_r.v.size())};
    for (std::size_t i = 0; i < low_r.v.size(); ++i) {
        const float rgb[3] = {low_r.v[i], low_g.v[i], low_b.v[i]};
        low_guide.v[i] = perceptual(lum(rgb));
    }
    const int dark_radius = std::max(2, lw / 140);
    if (t.dehaze > 0.0F) {
        Plane dark{lw, lh, std::vector<float>(low_r.v.size())};
        for (std::size_t i = 0; i < dark.v.size(); ++i) dark.v[i] = std::min({low_r.v[i], low_g.v[i], low_b.v[i]});
        dark = minFilter(dark, dark_radius);
        // Atmospheric light: mean colour of the brightest 0.1 % of the dark channel.
        std::vector<std::size_t> order(dark.v.size());
        for (std::size_t i = 0; i < order.size(); ++i) order[i] = i;
        const std::size_t top = std::max<std::size_t>(1, order.size() / 1000);
        std::nth_element(order.begin(), order.begin() + static_cast<std::ptrdiff_t>(top - 1), order.end(),
                         [&](std::size_t a, std::size_t b) { return dark.v[a] > dark.v[b]; });
        double acc[3] = {0, 0, 0};
        for (std::size_t k = 0; k < top; ++k) {
            acc[0] += low_r.v[order[k]];
            acc[1] += low_g.v[order[k]];
            acc[2] += low_b.v[order[k]];
        }
        for (int c = 0; c < 3; ++c) air[static_cast<std::size_t>(c)] = std::max(1e-3F, static_cast<float>(acc[c] / static_cast<double>(top)));
        Plane transmission{lw, lh, std::vector<float>(low_r.v.size())};
        for (std::size_t i = 0; i < transmission.v.size(); ++i) {
            transmission.v[i] = std::min({low_r.v[i] / air[0], low_g.v[i] / air[1], low_b.v[i] / air[2]});
        }
        transmission = minFilter(transmission, dark_radius);
        const float omega = 0.95F * t.dehaze / 100.0F;
        for (auto& v : transmission.v) v = 1.0F - omega * v;
        haze = guidedCoefficients(low_guide, transmission, std::max(4, lw / 50), 1e-3F);
    }

    // Low-resolution log luminance after dehaze and exposure: the guide for the local tone operators.
    Plane low_log{lw, lh, std::vector<float>(low_r.v.size())};
    for (int y = 0; y < lh; ++y)
        for (int x = 0; x < lw; ++x) {
            const auto i = static_cast<std::size_t>(y) * lw + x;
            float rgb[3] = {low_r.v[i], low_g.v[i], low_b.v[i]};
            if (t.dehaze > 0.0F) {
                const float tr = std::max(0.1F, haze.a.at(x, y) * low_guide.v[i] + haze.b.at(x, y));
                for (int c = 0; c < 3; ++c) rgb[c] = std::max(0.0F, (rgb[c] - air[static_cast<std::size_t>(c)]) / tr + air[static_cast<std::size_t>(c)]);
            }
            low_log.v[i] = std::log2(std::max(kFloor, lum(rgb))) + t.exposure;
        }
    const bool local_tone = t.highlights != 0.0F || t.shadows != 0.0F;
    const bool clarity = t.clarity != 0.0F;
    Guided base, detail;
    if (local_tone) base = guidedCoefficients(low_log, low_log, std::max(3, lw * 3 / 100), 0.25F);
    if (clarity) detail = guidedCoefficients(low_log, low_log, std::max(2, lw * 3 / 200), 1.0F);
    if (t.progress && !t.progress(0.35F)) {
        error = "cancelled";
        return std::nullopt;
    }

    const auto parametric = t.parametric.isIdentity() ? std::vector<float>{} : parametricLut(t.parametric);
    const bool point_curve = !(t.point_curve.points.size() == 2 && t.point_curve.points[0] == std::pair{0.0F, 0.0F} &&
                               t.point_curve.points[1] == std::pair{1.0F, 1.0F});
    const bool texture = t.texture != 0.0F;


    TonePlan plan;
    plan.block = block;
    plan.low_width = lw;
    plan.low_height = lh;
    plan.air = air;
    plan.local_tone = local_tone;
    plan.clarity = clarity;
    plan.texture = texture;
    plan.dehaze_positive = t.dehaze > 0.0F;
    plan.haze_a = std::move(haze.a.v);
    plan.haze_b = std::move(haze.b.v);
    plan.base_a = std::move(base.a.v);
    plan.base_b = std::move(base.b.v);
    plan.detail_a = std::move(detail.a.v);
    plan.detail_b = std::move(detail.b.v);
    plan.parametric = parametric;
    plan.point_curve = point_curve;
    if (point_curve) {
        plan.point_lut.resize(1025);
        for (std::size_t i = 0; i < plan.point_lut.size(); ++i) plan.point_lut[i] = t.point_curve.evaluate(static_cast<float>(i) / 1024.0F);
    }
    return plan;
}

std::optional<TileStore> applyTone(const TileStore& linear, const ToneSettings& t, std::string& error) {
    if (linear.format() != PixelFormat::RGBA16 && linear.format() != PixelFormat::RGBA32F) {
        error = "develop tone needs a linear RGBA16 or RGBA32F layer";
        return std::nullopt;
    }
    if (!validateToneSettings(t, error)) return std::nullopt;
    if (t.isIdentity()) return linear;

    const std::int64_t W = linear.width(), H = linear.height();
    const auto lum = [&](const float* rgb) { return t.luminance[0] * rgb[0] + t.luminance[1] * rgb[1] + t.luminance[2] * rgb[2]; };
    const unsigned workers = parallel::workerCount(t.worker_threads);
    std::atomic<bool> cancelled{false};
    const auto maybe_plan = makeTonePlan(linear, t, error);
    if (!maybe_plan) return std::nullopt;
    const TonePlan& plan = *maybe_plan;
    const int block = plan.block, lw = plan.low_width, lh = plan.low_height;
    const auto air = plan.air;
    const auto perceptual = [](float y) { return std::pow(std::max(y, 0.0F), 1.0F / 2.2F); };
    Guided haze{Plane{lw, lh, plan.haze_a}, Plane{lw, lh, plan.haze_b}};
    Guided base{Plane{lw, lh, plan.base_a}, Plane{lw, lh, plan.base_b}};
    Guided detail{Plane{lw, lh, plan.detail_a}, Plane{lw, lh, plan.detail_b}};
    const bool local_tone = plan.local_tone, clarity = plan.clarity, texture = plan.texture, point_curve = plan.point_curve;
    const auto& parametric = plan.parametric;
    const int border = texture ? 10 : 0;  // the widest texture kernel (σ 3, exact) reaches 9 px
    const float scale = 1.0F / static_cast<float>(block);

    // ── Pass 2: full resolution, per tile. ──
    TileStore out(linear.format(), linear.width(), linear.height());
    const auto columns = static_cast<std::size_t>((W + kTileEdge - 1) / kTileEdge);
    const auto rows = static_cast<std::size_t>((H + kTileEdge - 1) / kTileEdge);
    std::atomic<std::size_t> done{0};
    std::atomic<bool> failed{false}, reported_final{false};
    // Small images (previews) have fewer tiles than workers: split tiles into quarters so every core works.
    const int split = columns * rows < static_cast<std::size_t>(workers) * 2U ? 2 : 1;
    const int unit = static_cast<int>(kTileEdge) / split;
    const std::size_t units = columns * rows * static_cast<std::size_t>(split * split);
    std::vector<TileBuffer> shared(split > 1 ? columns * rows : 0);
    for (std::size_t k = 0; k < shared.size(); ++k) {
        if (t.dehaze > 0.0F || linear.readTile({static_cast<std::int32_t>(k % columns), static_cast<std::int32_t>(k / columns)}) != nullptr) shared[k].assign(out.tile_byte_size(), 0);
    }
    parallel::parallelFor(units, workers, [&](std::size_t index, std::size_t worker) {
        if (cancelled.load()) return;
        const std::size_t tile_index = index / static_cast<std::size_t>(split * split), quarter = index % static_cast<std::size_t>(split * split);
        const auto tx = static_cast<std::int64_t>(tile_index % columns), ty = static_cast<std::int64_t>(tile_index / columns);
        const int lx0 = static_cast<int>(quarter % static_cast<std::size_t>(split)) * unit, ly0 = static_cast<int>(quarter / static_cast<std::size_t>(split)) * unit;
        const auto source = linear.readTile({static_cast<std::int32_t>(tx), static_cast<std::int32_t>(ty)});
        if ((source == nullptr && t.dehaze <= 0.0F) || tx * kTileEdge + lx0 >= W || ty * kTileEdge + ly0 >= H) return;  // transparent black stays transparent black
        const Neighbourhood around(linear, tx, ty);
        const int bw = unit + 2 * border;
        std::vector<float> rgba(static_cast<std::size_t>(bw) * bw * 4), log_l(static_cast<std::size_t>(bw) * bw);
        const std::int64_t ox = tx * kTileEdge + lx0 - border, oy = ty * kTileEdge + ly0 - border;
        for (int y = 0; y < bw; ++y)
            for (int x = 0; x < bw; ++x) {
                const auto i = static_cast<std::size_t>(y) * bw + x;
                float* px = rgba.data() + i * 4;
                around.read(ox + x, oy + y, px);
                for (int c = 0; c < 3; ++c) px[c] = std::max(0.0F, px[c]);
                if (t.dehaze > 0.0F) {
                    const float gx = (static_cast<float>(ox + x) + 0.5F) * scale, gy = (static_cast<float>(oy + y) + 0.5F) * scale;
                    const float tr = std::max(0.1F, haze.a.sample(gx, gy) * perceptual(lum(px)) + haze.b.sample(gx, gy));
                    for (int c = 0; c < 3; ++c) px[c] = std::max(0.0F, (px[c] - air[static_cast<std::size_t>(c)]) / tr + air[static_cast<std::size_t>(c)]);
                } else if (t.dehaze < 0.0F) {
                    // Negative dehaze adds an even grey veil (lower contrast and saturation), up to 50 %.
                    const float amount = -t.dehaze / 100.0F * 0.5F;
                    for (int c = 0; c < 3; ++c) px[c] += (0.35F - px[c]) * amount;
                }
                const float gain = std::exp2(t.exposure);
                for (int c = 0; c < 3; ++c) px[c] *= gain;
                log_l[i] = std::log2(std::max(kFloor, lum(px)));
            }
        std::vector<float> band;
        if (texture) {
            std::vector<float> fine = log_l, coarse = log_l, scratch;
            gaussian(fine, bw, bw, 1.0F, scratch);  // band-pass ≈ 4–20 px periods: skin/foliage texture,
            gaussian(coarse, bw, bw, 3.0F, scratch);  // below clarity's scale and above pixel noise
            band.resize(fine.size());
            for (std::size_t i = 0; i < band.size(); ++i) band[i] = fine[i] - coarse[i];
        }
        TileBuffer own = split > 1 ? TileBuffer{} : TileBuffer(out.tile_byte_size(), 0);
        TileBuffer& tile = split > 1 ? shared[tile_index] : own;
        const auto bpp = out.bytes_per_pixel();
        for (int uy = 0; uy < unit && ty * kTileEdge + ly0 + uy < H; ++uy) {
            for (int ux = 0; ux < unit && tx * kTileEdge + lx0 + ux < W; ++ux) {
                const int x = lx0 + ux, y = ly0 + uy;  // position inside the tile
                const auto i = static_cast<std::size_t>(uy + border) * bw + static_cast<std::size_t>(ux + border);
                float* px = rgba.data() + i * 4;
                const float l0 = log_l[i];
                float l = l0;
                const float gx = (static_cast<float>(tx * kTileEdge + x) + 0.5F) * scale, gy = (static_cast<float>(ty * kTileEdge + y) + 0.5F) * scale;
                if (local_tone) {
                    const float b = base.a.sample(gx, gy) * l0 + base.b.sample(gx, gy) - kMidGrey;
                    const float w_shadow = 1.0F / (1.0F + std::exp(1.5F * (b + 1.5F)));
                    const float w_highlight = 1.0F / (1.0F + std::exp(-1.5F * (b - 1.0F)));
                    l += 1.5F * (t.shadows / 100.0F * w_shadow + t.highlights / 100.0F * w_highlight);
                }
                if (clarity) {
                    const float d = l0 - (detail.a.sample(gx, gy) * l0 + detail.b.sample(gx, gy));
                    const float midtones = std::exp(-((l0 - kMidGrey) / 2.5F) * ((l0 - kMidGrey) / 2.5F));
                    l += 0.7F * t.clarity / 100.0F * d * midtones;
                }
                if (texture) l += t.texture / 100.0F * band[i];
                l = contrastCurve(l, t.contrast);
                l += t.whites / 100.0F * smoothstep(kMidGrey + 0.5F, kMidGrey + 2.5F, l);  // linear 0.25 … 1.0
                l += t.blacks / 100.0F * (1.0F - smoothstep(kMidGrey - 6.0F, kMidGrey - 3.0F, l));
                if (l != l0) {
                    const float k = std::exp2(l - l0);
                    for (int c = 0; c < 3; ++c) px[c] *= k;
                }
                if (!parametric.empty() || point_curve) {
                    const float y_lin = lum(px);
                    if (y_lin > 0.0F && y_lin < 1.0F) {
                        float v = std::pow(y_lin, 1.0F / 2.2F);
                        if (!parametric.empty()) v = lutAt(parametric, v);
                        if (point_curve) v = t.point_curve.evaluate(v);
                        const float k = std::pow(std::max(v, 0.0F), 2.2F) / y_lin;
                        for (int c = 0; c < 3; ++c) px[c] *= k;
                    }
                }
                if (t.vibrance != 0.0F || t.saturation != 0.0F) {
                    const float y_lin = lum(px);
                    const float hi = std::max({px[0], px[1], px[2]}), lo = std::min({px[0], px[1], px[2]});
                    const float sat = hi > 0.0F ? (hi - lo) / hi : 0.0F;
                    const float factor = std::max(0.0F, 1.0F + t.saturation / 100.0F + t.vibrance / 100.0F * (1.0F - sat));
                    for (int c = 0; c < 3; ++c) px[c] = std::max(0.0F, y_lin + (px[c] - y_lin) * factor);
                }
                paint::detail::writePixel(out.format(), tile.data() + (static_cast<std::size_t>(y) * kTileEdge + static_cast<std::size_t>(x)) * bpp, px);
            }
        }
        if (split == 1 && !out.writeTile({static_cast<std::int32_t>(tx), static_cast<std::int32_t>(ty)}, tile)) {
            failed.store(true);
            cancelled.store(true);
            return;
        }
        const auto finished = done.fetch_add(1) + 1;
        if (worker == 0 && t.progress) {
            if (finished == units) reported_final.store(true);
            if (!t.progress(0.35F + 0.65F * static_cast<float>(finished) / static_cast<float>(units))) cancelled.store(true);
        }
    });
    // The calling thread may get no unit on small images: always report completion (a cancellation point).
    if (!cancelled.load() && !reported_final.load() && t.progress && !t.progress(1.0F)) cancelled.store(true);
    for (std::size_t k = 0; k < shared.size() && !cancelled.load(); ++k) {
        if (shared[k].empty()) continue;
        if (!out.writeTile({static_cast<std::int32_t>(k % columns), static_cast<std::int32_t>(k / columns)}, shared[k])) {
            failed.store(true);
            cancelled.store(true);
        }
    }
    if (cancelled.load()) {
        error = failed.load() ? "could not store developed pixels" : "cancelled";
        return std::nullopt;
    }
    return out;
}

}  // namespace croptop::develop
