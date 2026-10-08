// Develop detail and effects.
#include "develop_detail.h"

#include <algorithm>
#include <array>
#include <atomic>
#include <cmath>
#include <vector>

#include "develop_internal.h"
#include "parallel_util.h"
#include "pixel_plane.h"

namespace croptop::develop {
namespace {

using detail::gaussian;
using detail::Neighbourhood;

constexpr float kGamma = 1.0F / 2.2F;

// Gamma 2.2 encode/decode dominate the per-pixel cost, so values in [0, 1] use tables (encode is indexed by
// √v to follow its steep start; interpolation error ≤ 3e-5 encoded, ≤ 1e-7 decoded); others stay exact.
constexpr int kGammaTable = 4096;

struct GammaTables {
    std::array<float, kGammaTable + 1> encode_by_sqrt{}, decode{};
    GammaTables() {
        for (int i = 0; i <= kGammaTable; ++i) {
            const double t = static_cast<double>(i) / kGammaTable;
            encode_by_sqrt[static_cast<std::size_t>(i)] = static_cast<float>(std::pow(t * t, 1.0 / 2.2));
            decode[static_cast<std::size_t>(i)] = static_cast<float>(std::pow(t, 2.2));
        }
    }
};

const GammaTables& gammaTables() {
    static const GammaTables tables;
    return tables;
}

inline float lookup(const std::array<float, kGammaTable + 1>& table, float t) {
    const float x = t * kGammaTable;
    const int i = std::min(static_cast<int>(x), kGammaTable - 1);
    const float f = x - static_cast<float>(i);
    return table[static_cast<std::size_t>(i)] + (table[static_cast<std::size_t>(i + 1)] - table[static_cast<std::size_t>(i)]) * f;
}

float encode(float v) {
    if (v >= 0.0F && v <= 1.0F) return lookup(gammaTables().encode_by_sqrt, std::sqrt(v));
    return std::isfinite(v) ? std::copysign(std::pow(std::abs(v), kGamma), v) : 0.0F;
}
float decode(float v) {
    if (v >= 0.0F && v <= 1.0F) return lookup(gammaTables().decode, v);
    return std::isfinite(v) ? std::copysign(std::pow(std::abs(v), 2.2F), v) : 0.0F;
}

float smoothstep(float e0, float e1, float x) {
    const float t = std::clamp((x - e0) / (e1 - e0), 0.0F, 1.0F);
    return t * t * (3.0F - 2.0F * t);
}

/** Box means over a small plane (clamped window, mean over the pixels actually inside), separable. */
std::vector<float> boxMean(const std::vector<float>& p, int w, int h, int r) {
    std::vector<double> rows(p.size()), prefix(static_cast<std::size_t>(std::max(w, h)) + 1);
    std::vector<int> xcount(static_cast<std::size_t>(w));
    for (int x = 0; x < w; ++x) xcount[static_cast<std::size_t>(x)] = std::min(w, x + r + 1) - std::max(0, x - r);
    for (int y = 0; y < h; ++y) {
        const float* in = p.data() + static_cast<std::size_t>(y) * w;
        for (int x = 0; x < w; ++x) prefix[static_cast<std::size_t>(x + 1)] = prefix[static_cast<std::size_t>(x)] + in[x];
        double* out = rows.data() + static_cast<std::size_t>(y) * w;
        for (int x = 0; x < w; ++x) out[x] = prefix[static_cast<std::size_t>(std::min(w, x + r + 1))] - prefix[static_cast<std::size_t>(std::max(0, x - r))];
    }
    // Vertical pass over a full 2-D prefix: contiguous rows instead of strided columns (same sums, same result).
    std::vector<double> column_prefix((static_cast<std::size_t>(h) + 1) * static_cast<std::size_t>(w), 0.0);
    for (int y = 0; y < h; ++y) {
        const double* above = column_prefix.data() + static_cast<std::size_t>(y) * w;
        const double* row = rows.data() + static_cast<std::size_t>(y) * w;
        double* below = column_prefix.data() + static_cast<std::size_t>(y + 1) * w;
        for (int x = 0; x < w; ++x) below[x] = above[x] + row[x];
    }
    std::vector<float> out(p.size());
    for (int y = 0; y < h; ++y) {
        const int y0 = std::max(0, y - r), y1 = std::min(h, y + r + 1);
        const double* top = column_prefix.data() + static_cast<std::size_t>(y0) * w;
        const double* bottom = column_prefix.data() + static_cast<std::size_t>(y1) * w;
        for (int x = 0; x < w; ++x) {
            out[static_cast<std::size_t>(y) * w + x] = static_cast<float>((bottom[x] - top[x]) / (static_cast<double>(xcount[static_cast<std::size_t>(x)]) * (y1 - y0)));
        }
    }
    return out;
}

/** Guide statistics of a guided filter, shared by every input filtered with the same guide and radius. */
struct GuideStats {
    std::vector<float> mean, var;
};

GuideStats guideStats(const std::vector<float>& guide, int w, int h, int r) {
    std::vector<float> gg(guide.size());
    for (std::size_t i = 0; i < guide.size(); ++i) gg[i] = guide[i] * guide[i];
    GuideStats st{boxMean(guide, w, h, r), boxMean(gg, w, h, r)};
    for (std::size_t i = 0; i < guide.size(); ++i) st.var[i] -= st.mean[i] * st.mean[i];
    return st;
}

/** Guided filter of `input` with `guide` (He et al.), radius r, regulariser eps. */
std::vector<float> guided(const GuideStats& st, const std::vector<float>& guide, const std::vector<float>& input, int w, int h, int r, float eps) {
    std::vector<float> gp(guide.size());
    for (std::size_t i = 0; i < guide.size(); ++i) gp[i] = guide[i] * input[i];
    const auto mp = boxMean(input, w, h, r), mgp = boxMean(gp, w, h, r);
    std::vector<float> a(guide.size()), b(guide.size());
    for (std::size_t i = 0; i < guide.size(); ++i) {
        const float cov = mgp[i] - st.mean[i] * mp[i];
        a[i] = cov / (st.var[i] + eps);
        b[i] = mp[i] - a[i] * st.mean[i];
    }
    const auto ma = boxMean(a, w, h, r), mb = boxMean(b, w, h, r);
    std::vector<float> out(guide.size());
    for (std::size_t i = 0; i < guide.size(); ++i) out[i] = ma[i] * guide[i] + mb[i];
    return out;
}

/** Deterministic white noise in [−0.5, 0.5) from absolute pixel coordinates (seamless across tiles). */
float hashNoise(std::int64_t x, std::int64_t y, std::uint32_t seed) {
    std::uint64_t z = static_cast<std::uint64_t>(x) * 0x9E3779B97F4A7C15ULL ^ (static_cast<std::uint64_t>(y) + 0x632BE59BD9B4E019ULL) * 0xBF58476D1CE4E5B9ULL ^
                      (static_cast<std::uint64_t>(seed) << 32U);
    z = (z ^ (z >> 30U)) * 0xBF58476D1CE4E5B9ULL;
    z = (z ^ (z >> 27U)) * 0x94D049BB133111EBULL;
    z ^= z >> 31U;
    return static_cast<float>(z >> 40U) / static_cast<float>(1ULL << 24U) - 0.5F;
}

bool in(float v, float lo, float hi) { return std::isfinite(v) && v >= lo && v <= hi; }

}  // namespace

bool DetailSettings::isIdentity() const {
    return sharpening.amount == 0 && noise.luminance == 0 && noise.color == 0 && vignette.amount == 0 && grain.amount == 0;
}

std::optional<TileStore> applyDetail(const TileStore& linear, const DetailSettings& s, std::string& error) {
    if (linear.format() != PixelFormat::RGBA16 && linear.format() != PixelFormat::RGBA32F) {
        error = "develop detail needs a linear RGBA16 or RGBA32F layer";
        return std::nullopt;
    }
    const auto& sh = s.sharpening;
    const auto& nr = s.noise;
    const auto& vg = s.vignette;
    const auto& gr = s.grain;
    const bool valid = in(sh.amount, 0, 150) && in(sh.radius, 0.5F, 3.0F) && in(sh.detail, 0, 100) && in(sh.masking, 0, 100) &&
                       in(nr.luminance, 0, 100) && in(nr.luminance_detail, 0, 100) && in(nr.color, 0, 100) && in(nr.color_detail, 0, 100) &&
                       in(vg.amount, -100, 100) && in(vg.midpoint, 0, 100) && in(vg.roundness, -100, 100) && in(vg.feather, 0, 100) &&
                       in(vg.highlights, 0, 100) && in(gr.amount, 0, 100) && in(gr.size, 0, 100) && in(gr.roughness, 0, 100) &&
                       std::all_of(s.luminance.begin(), s.luminance.end(), [](float v) { return std::isfinite(v) && v >= 0.0F && v <= 1.0F; }) &&
                       std::abs(s.luminance[0] + s.luminance[1] + s.luminance[2] - 1.0F) <= 1e-3F &&
                       s.luminance[1] > 0.01F;
    if (!valid) {
        error = "develop detail settings are out of range";
        return std::nullopt;
    }
    if (s.isIdentity()) return linear;

    const std::int64_t W = linear.width(), H = linear.height();
    // Vignette frame (post-crop); an empty frame means the whole layer.
    std::int64_t fl = vg.left, ft = vg.top, fr = vg.right, fb = vg.bottom;
    if (fr <= fl || fb <= ft) {
        fl = 0;
        ft = 0;
        fr = W;
        fb = H;
    }
    // The post-crop frame is supplied by callers; clamp it before arithmetic so
    // extreme signed coordinates cannot overflow the center/extent calculations.
    fl = std::clamp<std::int64_t>(fl, 0, W);
    ft = std::clamp<std::int64_t>(ft, 0, H);
    fr = std::clamp<std::int64_t>(fr, 0, W);
    fb = std::clamp<std::int64_t>(fb, 0, H);
    if (fr <= fl || fb <= ft) {
        fl = 0;
        ft = 0;
        fr = W;
        fb = H;
    }
    const float cx = 0.5F * static_cast<float>(fl + fr), cy = 0.5F * static_cast<float>(ft + fb);
    const float hw = 0.5F * static_cast<float>(fr - fl), hh = 0.5F * static_cast<float>(fb - ft), hmin = std::min(hw, hh);
    const float wr = s.luminance[0], wg = s.luminance[1], wb = s.luminance[2];
    // Grain field: coarse and fine blurs of white noise, normalised to unit deviation (analytic, so tiles agree).
    const float sigma_coarse = 0.6F + gr.size / 100.0F * 1.8F, sigma_fine = 0.5F;
    // Tile border from the active stages' dependency chain (a guided filter of radius r reads 2r; the
    // sharpening blur 3σ plus 1 px of gradient; grain 3σ), so the tile core matches an unbounded image.
    const int color_radius = 2 + static_cast<int>(nr.color / 100.0F * 6.0F);
    const int luma_radius = 2 + static_cast<int>(nr.luminance / 100.0F * 2.0F);
    const int sharpen_reach = sh.amount > 0.0F ? static_cast<int>(std::ceil(3.0F * sh.radius)) + 2 : 0;
    const int border = std::max({2, nr.color > 0.0F ? 2 * color_radius : 0, (nr.luminance > 0.0F ? 2 * luma_radius : 0) + sharpen_reach,
                                  gr.amount > 0.0F ? static_cast<int>(std::ceil(3.0F * sigma_coarse)) + 1 : 0});
    const auto noiseScale = [](float sigma) { return std::sqrt(12.0F * 4.0F * 3.14159265F * sigma * sigma); };

    TileStore out(linear.format(), linear.width(), linear.height());
    const auto columns = static_cast<std::size_t>((W + kTileEdge - 1) / kTileEdge), rows = static_cast<std::size_t>((H + kTileEdge - 1) / kTileEdge);
    const unsigned workers = parallel::workerCount(s.worker_threads);
    std::atomic<std::size_t> done{0};
    std::atomic<bool> cancelled{false}, failed{false}, reported_final{false};
    // Small images (e.g. 512 px previews) have fewer tiles than workers: split tiles into quarters so every
    // core has work. Quarter units write disjoint regions of shared tile buffers, stored at the end.
    const int split = columns * rows < static_cast<std::size_t>(workers) * 2U ? 2 : 1;
    const int unit = static_cast<int>(kTileEdge) / split;
    const std::size_t units = columns * rows * static_cast<std::size_t>(split * split);
    std::vector<TileBuffer> shared(split > 1 ? columns * rows : 0);
    for (std::size_t t = 0; t < shared.size(); ++t) {
        if (linear.readTile({static_cast<std::int32_t>(t % columns), static_cast<std::int32_t>(t / columns)}) != nullptr) shared[t].assign(out.tile_byte_size(), 0);
    }
    parallel::parallelFor(units, workers, [&](std::size_t index, std::size_t worker) {
        if (cancelled.load()) return;
        const std::size_t tile_index = index / static_cast<std::size_t>(split * split), quarter = index % static_cast<std::size_t>(split * split);
        const auto tx = static_cast<std::int64_t>(tile_index % columns), ty = static_cast<std::int64_t>(tile_index / columns);
        const int lx0 = static_cast<int>(quarter % static_cast<std::size_t>(split)) * unit, ly0 = static_cast<int>(quarter / static_cast<std::size_t>(split)) * unit;
        const auto reportProgress = [&] {
            const auto finished = done.fetch_add(1) + 1;
            if (worker == 0 && s.progress) {
                if (finished == units) reported_final.store(true);
                if (!s.progress(static_cast<float>(finished) / static_cast<float>(units))) cancelled.store(true);
            }
        };
        if (linear.readTile({static_cast<std::int32_t>(tx), static_cast<std::int32_t>(ty)}) == nullptr || tx * kTileEdge + lx0 >= W || ty * kTileEdge + ly0 >= H) {
            reportProgress();
            return;
        }
        const Neighbourhood around(linear, tx, ty);
        const int bw = unit + 2 * border;
        const auto n = static_cast<std::size_t>(bw) * bw;
        const std::int64_t ox = tx * kTileEdge + lx0 - border, oy = ty * kTileEdge + ly0 - border;
        std::vector<float> luma(n), c1(n), c2(n), alpha(n), scratch;
        for (int y = 0; y < bw; ++y)
            for (int x = 0; x < bw; ++x) {
                const auto i = static_cast<std::size_t>(y) * bw + x;
                float px[4];
                around.read(ox + x, oy + y, px);
                const float r = encode(px[0]), g = encode(px[1]), b = encode(px[2]);
                luma[i] = wr * r + wg * g + wb * b;
                c1[i] = r - luma[i];
                c2[i] = b - luma[i];
                alpha[i] = px[3];
            }
        if (nr.color > 0.0F) {
            const int radius = color_radius;
            const float eps = std::pow(0.002F + (1.0F - nr.color_detail / 100.0F) * 0.03F, 2.0F);
            const auto stats = guideStats(luma, bw, bw, radius);
            const auto f1 = guided(stats, luma, c1, bw, bw, radius, eps), f2 = guided(stats, luma, c2, bw, bw, radius, eps);
            const float mix = std::min(1.0F, nr.color / 40.0F);
            for (std::size_t i = 0; i < n; ++i) {
                c1[i] += (f1[i] - c1[i]) * mix;
                c2[i] += (f2[i] - c2[i]) * mix;
            }
        }
        if (nr.luminance > 0.0F) {
            const int radius = luma_radius;
            const float eps = std::pow(nr.luminance / 100.0F * 0.05F, 2.0F);
            const auto smooth = guided(guideStats(luma, bw, bw, radius), luma, luma, bw, bw, radius, eps);
            const float keep = nr.luminance_detail / 100.0F * 0.6F;
            for (std::size_t i = 0; i < n; ++i) luma[i] = smooth[i] + (luma[i] - smooth[i]) * keep;
        }
        std::vector<float> sharpened = luma;
        if (sh.amount > 0.0F) {
            std::vector<float> blurred = luma;
            gaussian(blurred, bw, bw, sh.radius, scratch);
            const float limit = 0.01F + (sh.detail / 100.0F) * (sh.detail / 100.0F) * 0.3F;  // halo suppression
            const float mask_edge = sh.masking / 100.0F * 0.03F;
            for (int y = 1; y < bw - 1; ++y)
                for (int x = 1; x < bw - 1; ++x) {
                    const auto i = static_cast<std::size_t>(y) * bw + x;
                    float mask = 1.0F;
                    if (sh.masking > 0.0F) {
                        const float gx = 0.5F * (blurred[i + 1] - blurred[i - 1]), gy = 0.5F * (blurred[i + static_cast<std::size_t>(bw)] - blurred[i - static_cast<std::size_t>(bw)]);
                        mask = smoothstep(mask_edge, 2.0F * mask_edge + 0.005F, std::hypot(gx, gy));
                    }
                    const float d = luma[i] - blurred[i];
                    sharpened[i] = luma[i] + sh.amount / 100.0F * limit * std::tanh(d / limit) * mask;
                }
        }
        std::vector<float> grain;
        if (gr.amount > 0.0F) {
            std::vector<float> coarse(n), fine;
            for (int y = 0; y < bw; ++y)
                for (int x = 0; x < bw; ++x) coarse[static_cast<std::size_t>(y) * bw + x] = hashNoise(ox + x, oy + y, gr.seed);
            fine = coarse;
            gaussian(coarse, bw, bw, sigma_coarse, scratch);
            gaussian(fine, bw, bw, sigma_fine, scratch);
            grain.resize(n);
            const float r = gr.roughness / 100.0F, kc = noiseScale(sigma_coarse), kf = noiseScale(sigma_fine);
            for (std::size_t i = 0; i < n; ++i) grain[i] = (1.0F - r) * coarse[i] * kc + r * fine[i] * kf;
        }
        TileBuffer own = split > 1 ? TileBuffer{} : TileBuffer(out.tile_byte_size(), 0);
        TileBuffer& tile = split > 1 ? shared[tile_index] : own;
        const auto bpp = out.bytes_per_pixel();
        for (int uy = 0; uy < unit && ty * kTileEdge + ly0 + uy < H; ++uy) {
            for (int ux = 0; ux < unit && tx * kTileEdge + lx0 + ux < W; ++ux) {
                const int x = lx0 + ux, y = ly0 + uy;  // position inside the tile
                const auto i = static_cast<std::size_t>(uy + border) * bw + static_cast<std::size_t>(ux + border);
                const float p = sharpened[i];
                const float r = p + c1[i], b = p + c2[i], g = (p - wr * r - wb * b) / wg;
                float px[4] = {decode(r), decode(g), decode(b), alpha[i]};
                if (vg.amount != 0.0F) {
                    const float nx = (static_cast<float>(tx * kTileEdge + x) + 0.5F - cx) / hw, ny = (static_cast<float>(ty * kTileEdge + y) + 0.5F - cy) / hh;
                    const float ellipse = std::hypot(nx, ny);
                    float d = ellipse;
                    if (vg.roundness > 0.0F) {
                        const float circle = std::hypot(nx * hw, ny * hh) / hmin;
                        d += (circle - ellipse) * vg.roundness / 100.0F;
                    } else if (vg.roundness < 0.0F) {
                        const float rect = std::pow(std::pow(std::abs(nx), 6.0F) + std::pow(std::abs(ny), 6.0F), 1.0F / 6.0F);
                        d += (rect - ellipse) * -vg.roundness / 100.0F;
                    }
                    const float inner = vg.midpoint / 100.0F * 0.9F;
                    const float falloff = smoothstep(inner, inner + 0.05F + vg.feather / 100.0F * 0.9F, d);
                    float stops = vg.amount / 100.0F * 2.0F * falloff;
                    if (stops < 0.0F && vg.highlights > 0.0F) {
                        const float y_lin = wr * px[0] + wg * px[1] + wb * px[2];
                        stops *= 1.0F - vg.highlights / 100.0F * smoothstep(0.4F, 1.0F, y_lin);
                    }
                    const float k = std::exp2(stops);
                    for (int c = 0; c < 3; ++c) px[c] *= k;
                }
                if (!grain.empty()) {
                    const float pl = encode(wr * px[0] + wg * px[1] + wb * px[2]);
                    const float midtones = std::clamp(4.0F * pl * (1.0F - pl), 0.0F, 1.0F);
                    const float delta = gr.amount / 100.0F * 0.06F * grain[i] * (0.25F + 0.75F * midtones);
                    for (int c = 0; c < 3; ++c) px[c] = decode(encode(px[c]) + delta);
                }
                if (out.format() == PixelFormat::RGBA16) {
                    for (int c = 0; c < 3; ++c) px[c] = std::max(0.0F, px[c]);
                }
                paint::detail::writePixel(out.format(), tile.data() + (static_cast<std::size_t>(y) * kTileEdge + static_cast<std::size_t>(x)) * bpp, px);
            }
        }
        if (split == 1 && !out.writeTile({static_cast<std::int32_t>(tx), static_cast<std::int32_t>(ty)}, tile)) {
            failed.store(true);
            cancelled.store(true);
            return;
        }
        reportProgress();
    });
    // Only the calling thread reports during the loop, and it may get no unit at all on small images, so
    // always report completion (which also gives the caller its cancellation point).
    if (!cancelled.load() && !reported_final.load() && s.progress && !s.progress(1.0F)) cancelled.store(true);
    for (std::size_t t = 0; t < shared.size() && !cancelled.load(); ++t) {
        if (shared[t].empty()) continue;
        if (!out.writeTile({static_cast<std::int32_t>(t % columns), static_cast<std::int32_t>(t / columns)}, shared[t])) {
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
