// Model-free Object Selection / Select Subject.
#include "object_select.h"

#include <algorithm>
#include <array>
#include <cmath>
#include <cstring>
#include <queue>
#include <vector>

#include "pixel_plane.h"

namespace croptop::selection {
namespace {

constexpr int kProxy = 512;
constexpr int kBins = 16;  // per channel → 4096 joint bins

struct Proxy final {
    int w = 0, h = 0;
    double x0 = 0, y0 = 0, scale = 1;  // proxy pixel (i, j) covers document [x0 + i·scale, …)
    std::vector<std::array<float, 3>> rgb;
    std::vector<float> alpha;
};

/** Box-averaged proxy of the document rectangle [x0, x1) × [y0, y1). */
Proxy buildProxy(const TileStore& image, int x0, int y0, int x1, int y1) {
    Proxy p;
    const int bw = x1 - x0, bh = y1 - y0;
    p.scale = std::max(1.0, static_cast<double>(std::max(bw, bh)) / kProxy);
    p.w = std::max(1, static_cast<int>(std::ceil(bw / p.scale)));
    p.h = std::max(1, static_cast<int>(std::ceil(bh / p.scale)));
    p.x0 = x0;
    p.y0 = y0;
    const auto n = static_cast<std::size_t>(p.w) * p.h;
    p.rgb.assign(n, {0, 0, 0});
    p.alpha.assign(n, 0);
    std::vector<float> count(n, 0);
    const auto bpp = image.bytes_per_pixel();
    for (int ty = y0 / static_cast<int>(kTileEdge); ty * static_cast<int>(kTileEdge) < y1; ++ty)
        for (int tx = x0 / static_cast<int>(kTileEdge); tx * static_cast<int>(kTileEdge) < x1; ++tx) {
            const auto tile = image.readTile({tx, ty});
            for (int ly = 0; ly < static_cast<int>(kTileEdge); ++ly) {
                const int y = ty * static_cast<int>(kTileEdge) + ly;
                if (y < y0 || y >= y1) continue;
                for (int lx = 0; lx < static_cast<int>(kTileEdge); ++lx) {
                    const int x = tx * static_cast<int>(kTileEdge) + lx;
                    if (x < x0 || x >= x1) continue;
                    float px[4] = {0, 0, 0, 0};
                    if (tile) paint::detail::readPixel(image.format(), tile->data() + (static_cast<std::size_t>(ly) * kTileEdge + lx) * bpp, px);
                    const int i = std::min(p.w - 1, static_cast<int>((x - x0) / p.scale));
                    const int j = std::min(p.h - 1, static_cast<int>((y - y0) / p.scale));
                    const auto k = static_cast<std::size_t>(j) * p.w + i;
                    for (int c = 0; c < 3; ++c) p.rgb[k][static_cast<std::size_t>(c)] += paint::detail::unitClamp(px[c]);
                    p.alpha[k] += paint::detail::unitClamp(px[3]);
                    count[k] += 1;
                }
            }
        }
    for (std::size_t k = 0; k < n; ++k)
        if (count[k] > 0) {
            for (auto& v : p.rgb[k]) v /= count[k];
            p.alpha[k] /= count[k];
        }
    return p;
}

int bin(const std::array<float, 3>& c) {
    const auto q = [](float v) { return std::min(kBins - 1, static_cast<int>(v * kBins)); };
    return (q(c[0]) * kBins + q(c[1])) * kBins + q(c[2]);
}

/** Smoothed histogram (each sample also votes into its 6 neighbouring bins at half weight), normalised. */
std::vector<float> histogram(const Proxy& p, const std::vector<float>& weight) {
    std::vector<float> h(kBins * kBins * kBins, 0);
    double total = 0;
    for (std::size_t k = 0; k < p.rgb.size(); ++k) {
        if (weight[k] <= 0) continue;
        const int b = bin(p.rgb[k]);
        const int r = b / (kBins * kBins), g = (b / kBins) % kBins, bl = b % kBins;
        h[static_cast<std::size_t>(b)] += weight[k];
        const std::array<std::array<int, 3>, 6> nb{{{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}}};
        for (const auto& d : nb) {
            const int rr = r + d[0], gg = g + d[1], bb = bl + d[2];
            if (rr < 0 || gg < 0 || bb < 0 || rr >= kBins || gg >= kBins || bb >= kBins) continue;
            h[static_cast<std::size_t>((rr * kBins + gg) * kBins + bb)] += 0.5F * weight[k];
        }
        total += weight[k] * 4.0;
    }
    if (total > 0)
        for (auto& v : h) v = static_cast<float>(v / total);
    return h;
}

void boxBlur(std::vector<float>& v, int w, int h, int r) {
    std::vector<float> tmp(v.size());
    for (int y = 0; y < h; ++y)
        for (int x = 0; x < w; ++x) {
            float s = 0;
            int n = 0;
            for (int k = std::max(0, x - r); k <= std::min(w - 1, x + r); ++k, ++n) s += v[static_cast<std::size_t>(y) * w + k];
            tmp[static_cast<std::size_t>(y) * w + x] = s / static_cast<float>(n);
        }
    for (int y = 0; y < h; ++y)
        for (int x = 0; x < w; ++x) {
            float s = 0;
            int n = 0;
            for (int k = std::max(0, y - r); k <= std::min(h - 1, y + r); ++k, ++n) s += tmp[static_cast<std::size_t>(k) * w + x];
            v[static_cast<std::size_t>(y) * w + x] = s / static_cast<float>(n);
        }
}

std::optional<TileStore> segment(const TileStore& allocation_like, const TileStore& image, ObjectRegion region,
                                 bool subject, std::string& error) {
    if (!paint::detail::isRgba(image.format())) {
        error = "Object selection needs an RGBA layer";
        return std::nullopt;
    }
    if (allocation_like.width() != image.width() || allocation_like.height() != image.height()) {
        error = "The allocation template must match the image dimensions";
        return std::nullopt;
    }
    const int W = static_cast<int>(image.width()), H = static_cast<int>(image.height());
    if (!(std::isfinite(region.left) && std::isfinite(region.top) && std::isfinite(region.right) && std::isfinite(region.bottom))) {
        error = "The selection region must be finite";
        return std::nullopt;
    }
    const double rl = std::clamp(std::min(region.left, region.right), 0.0, static_cast<double>(W));
    const double rr = std::clamp(std::max(region.left, region.right), 0.0, static_cast<double>(W));
    const double rt = std::clamp(std::min(region.top, region.bottom), 0.0, static_cast<double>(H));
    const double rb = std::clamp(std::max(region.top, region.bottom), 0.0, static_cast<double>(H));
    if (rr - rl < 4 || rb - rt < 4) {
        error = "Draw a larger region around the object";
        return std::nullopt;
    }
    // Context: the region plus a margin of 15 % of its size (clamped to the canvas) supplies background samples.
    const double mx = (rr - rl) * 0.15, my = (rb - rt) * 0.15;
    const int x0 = static_cast<int>(std::max(0.0, rl - mx)), y0 = static_cast<int>(std::max(0.0, rt - my));
    const int x1 = static_cast<int>(std::min<double>(W, std::ceil(rr + mx))), y1 = static_cast<int>(std::min<double>(H, std::ceil(rb + my)));
    const auto p = buildProxy(image, x0, y0, x1, y1);
    const auto n = static_cast<std::size_t>(p.w) * p.h;
    // Proxy coordinates of the region and its centre seed.
    const double il = (rl - p.x0) / p.scale, ir = (rr - p.x0) / p.scale, it = (rt - p.y0) / p.scale, ib = (rb - p.y0) / p.scale;
    const double cx = (il + ir) / 2, cy = (it + ib) / 2, ax = (ir - il) / 2, ay = (ib - it) / 2;
    std::vector<std::uint8_t> inside(n, 0);
    std::vector<float> fg_seed(n, 0), bg_seed(n, 0), prob(n, 0);
    const double ring = 0.08;  // border ring of the region, as a fraction of its half-size
    for (int j = 0; j < p.h; ++j)
        for (int i = 0; i < p.w; ++i) {
            const auto k = static_cast<std::size_t>(j) * p.w + i;
            const double u = (i + 0.5 - cx) / ax, v = (j + 0.5 - cy) / ay;  // −1…1 inside the region
            const bool in = std::abs(u) < 1 && std::abs(v) < 1;
            inside[k] = in ? 1 : 0;
            if (p.alpha[k] < 0.05F) continue;  // transparent pixels never belong to the object
            if (!in || std::max(std::abs(u), std::abs(v)) > 1 - ring) bg_seed[k] = 1;
            // Centre ellipse: half the region for a drawn box; a smaller, centre-weighted core for Select Subject.
            const double core = subject ? 0.35 : 0.5;
            if (u * u + v * v < core * core) fg_seed[k] = 1;
        }
    auto fg = histogram(p, fg_seed), bg = histogram(p, bg_seed);
    for (int round = 0; round < 4; ++round) {
        std::vector<float> fw(n, 0), bw(n, 0);
        for (std::size_t k = 0; k < n; ++k) {
            if (!inside[k] || p.alpha[k] < 0.05F) {
                prob[k] = 0;
                bw[k] = inside[k] ? 1.0F : 0.5F;
                continue;
            }
            const auto b = static_cast<std::size_t>(bin(p.rgb[k]));
            const float f = fg[b] + 1e-6F, g = bg[b] + 1e-6F;
            prob[k] = f / (f + g);
            fw[k] = prob[k] > 0.5F ? prob[k] : 0.0F;
            bw[k] = prob[k] <= 0.5F ? 1.0F - prob[k] : 0.0F;
            if (fg_seed[k] > 0) fw[k] = std::max(fw[k], 0.5F);  // the seed core stays foreground evidence
        }
        fg = histogram(p, fw);
        bg = histogram(p, bw);
    }
    boxBlur(prob, p.w, p.h, 2);
    // Keep the component(s) connected to the seed core; fill holes.
    std::vector<std::uint8_t> keep(n, 0);
    std::queue<std::size_t> q;
    for (std::size_t k = 0; k < n; ++k)
        if (fg_seed[k] > 0 && prob[k] > 0.5F) {
            keep[k] = 1;
            q.push(k);
        }
    if (q.empty()) {  // no confident core: fall back to the most probable pixel inside the region
        std::size_t best = 0;
        for (std::size_t k = 0; k < n; ++k)
            if (inside[k] && prob[k] > prob[best]) best = k;
        if (prob[best] <= 0.5F) {
            error = "Couldn't find a distinct object in that area";
            return std::nullopt;
        }
        keep[best] = 1;
        q.push(best);
    }
    while (!q.empty()) {
        const auto k = q.front();
        q.pop();
        const int i = static_cast<int>(k % static_cast<std::size_t>(p.w)), j = static_cast<int>(k / static_cast<std::size_t>(p.w));
        const std::array<std::array<int, 2>, 4> nb{{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}};
        for (const auto& d : nb) {
            const int ii = i + d[0], jj = j + d[1];
            if (ii < 0 || jj < 0 || ii >= p.w || jj >= p.h) continue;
            const auto kk = static_cast<std::size_t>(jj) * p.w + ii;
            if (!keep[kk] && prob[kk] > 0.5F) {
                keep[kk] = 1;
                q.push(kk);
            }
        }
    }
    // Holes: background pixels not reachable from the proxy border through non-kept pixels.
    std::vector<std::uint8_t> outside(n, 0);
    for (int i = 0; i < p.w; ++i)
        for (const int j : {0, p.h - 1}) {
            const auto k = static_cast<std::size_t>(j) * p.w + i;
            if (!keep[k] && !outside[k]) {
                outside[k] = 1;
                q.push(k);
            }
        }
    for (int j = 0; j < p.h; ++j)
        for (const int i : {0, p.w - 1}) {
            const auto k = static_cast<std::size_t>(j) * p.w + i;
            if (!keep[k] && !outside[k]) {
                outside[k] = 1;
                q.push(k);
            }
        }
    while (!q.empty()) {
        const auto k = q.front();
        q.pop();
        const int i = static_cast<int>(k % static_cast<std::size_t>(p.w)), j = static_cast<int>(k / static_cast<std::size_t>(p.w));
        const std::array<std::array<int, 2>, 4> nb{{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}};
        for (const auto& d : nb) {
            const int ii = i + d[0], jj = j + d[1];
            if (ii < 0 || jj < 0 || ii >= p.w || jj >= p.h) continue;
            const auto kk = static_cast<std::size_t>(jj) * p.w + ii;
            if (!keep[kk] && !outside[kk]) {
                outside[kk] = 1;
                q.push(kk);
            }
        }
    }
    std::vector<float> soft(n, 0);
    for (std::size_t k = 0; k < n; ++k) soft[k] = (keep[k] || !outside[k]) ? 1.0F : 0.0F;
    boxBlur(soft, p.w, p.h, 1);  // anti-aliased edge after upsampling
    // Upsample into an A16 mask of the document size (bilinear on the proxy, sparse tiles).
    TileStore mask = allocation_like.emptyLike(PixelFormat::A16);
    for (int ty = y0 / static_cast<int>(kTileEdge); ty * static_cast<int>(kTileEdge) < y1; ++ty)
        for (int tx = x0 / static_cast<int>(kTileEdge); tx * static_cast<int>(kTileEdge) < x1; ++tx) {
            TileBuffer tile(mask.tile_byte_size(), 0);
            bool any = false;
            for (int ly = 0; ly < static_cast<int>(kTileEdge); ++ly) {
                const int y = ty * static_cast<int>(kTileEdge) + ly;
                if (y < y0 || y >= y1) continue;
                for (int lx = 0; lx < static_cast<int>(kTileEdge); ++lx) {
                    const int x = tx * static_cast<int>(kTileEdge) + lx;
                    if (x < x0 || x >= x1) continue;
                    const double fx = std::clamp((x + 0.5 - p.x0) / p.scale - 0.5, 0.0, p.w - 1.0);
                    const double fy = std::clamp((y + 0.5 - p.y0) / p.scale - 0.5, 0.0, p.h - 1.0);
                    const int i0 = static_cast<int>(fx), j0 = static_cast<int>(fy);
                    const int i1 = std::min(p.w - 1, i0 + 1), j1 = std::min(p.h - 1, j0 + 1);
                    const float ax2 = static_cast<float>(fx - i0), ay2 = static_cast<float>(fy - j0);
                    const auto at = [&](int i, int j) { return soft[static_cast<std::size_t>(j) * p.w + i]; };
                    float v = (at(i0, j0) * (1 - ax2) + at(i1, j0) * ax2) * (1 - ay2) + (at(i0, j1) * (1 - ax2) + at(i1, j1) * ax2) * ay2;
                    v = std::clamp((v - 0.25F) / 0.5F, 0.0F, 1.0F);  // sharpen the edge to about one proxy pixel
                    if (x < rl || x >= rr || y < rt || y >= rb) v = 0;
                    const auto raw = static_cast<std::uint16_t>(v * 65535.0F + 0.5F);
                    if (raw == 0) continue;
                    std::memcpy(tile.data() + (static_cast<std::size_t>(ly) * kTileEdge + lx) * 2, &raw, 2);
                    any = true;
                }
            }
            if (any && !mask.writeTile({tx, ty}, tile)) {
                error = "could not store the selection";
                return std::nullopt;
            }
        }
    return mask;
}

}  // namespace

std::optional<TileStore> selectObject(const TileStore& image, ObjectRegion region, std::string& error) {
    return selectObject(image, image, region, error);
}

std::optional<TileStore> selectObject(const TileStore& allocation_like, const TileStore& image,
                                      ObjectRegion region, std::string& error) {
    return segment(allocation_like, image, region, false, error);
}

std::optional<TileStore> selectSubject(const TileStore& image, std::string& error) {
    return selectSubject(image, image, error);
}

std::optional<TileStore> selectSubject(const TileStore& allocation_like, const TileStore& image, std::string& error) {
    const double w = image.width(), h = image.height();
    return segment(allocation_like, image, {w * 0.04, h * 0.04, w * 0.96, h * 0.96}, true, error);
}

}  // namespace croptop::selection
