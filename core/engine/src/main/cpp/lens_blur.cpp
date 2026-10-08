// Lens Blur (depth-driven part).
#include "lens_blur.h"

#include <algorithm>
#include <array>
#include <atomic>
#include <cmath>
#include <cstring>
#include <memory>
#include <vector>

#include "develop_internal.h"
#include "parallel_util.h"
#include "pixel_plane.h"

namespace croptop::lensblur {
namespace {

constexpr float kPi = 3.14159265358979323846F;
constexpr int kEdge = static_cast<int>(kTileEdge);
constexpr float kMaxLowResRadius = 12.0F;  // larger bin radii run on a 2^k-reduced copy

float srgbToLinear(float v) { return v <= 0.04045F ? v / 12.92F : std::pow((v + 0.055F) / 1.055F, 2.4F); }
float linearToSrgb(float v) { return v <= 0.0031308F ? v * 12.92F : 1.055F * std::pow(v, 1.0F / 2.4F) - 0.055F; }

float decode(float encoded) {
    static const std::vector<float> table = [] {
        std::vector<float> t(65536);
        for (std::size_t i = 0; i < t.size(); ++i) t[i] = srgbToLinear(static_cast<float>(i) / 65535.0F);
        return t;
    }();
    return table[static_cast<std::size_t>(paint::detail::unitClamp(encoded) * 65535.0F + 0.5F)];
}

float encode(float linear) {
    static const std::vector<float> table = [] {
        std::vector<float> t(4097);
        for (std::size_t i = 0; i < t.size(); ++i) t[i] = linearToSrgb(static_cast<float>(i) / 4096.0F);
        return t;
    }();
    const float x = paint::detail::unitClamp(linear) * 4096.0F;
    const auto i = std::min<std::size_t>(4095, static_cast<std::size_t>(x));
    const float f = x - static_cast<float>(i);
    return table[i] + (table[i + 1] - table[i]) * f;
}

float smoothstep(float e0, float e1, float x) {
    const float t = std::clamp((x - e0) / (e1 - e0), 0.0F, 1.0F);
    return t * t * (3.0F - 2.0F * t);
}

bool in(float v, float lo, float hi) { return std::isfinite(v) && v >= lo && v <= hi; }

/** Iris radius (relative, ≤ 1) in direction θ. */
float irisRadius(const Iris& iris, float theta) {
    if (iris.blades < 3) return 1.0F;
    const float sector = 2.0F * kPi / static_cast<float>(iris.blades);
    float a = std::fmod(theta - iris.rotation_degrees * kPi / 180.0F, sector);
    if (a < 0) a += sector;
    const float polygon = std::cos(kPi / static_cast<float>(iris.blades)) / std::cos(a - sector / 2.0F);
    return polygon + (1.0F - polygon) * iris.curvature;
}

struct Span final {
    int dy;
    float left, right;  // offsets from the centre (px)
};

/** Row spans of the iris of radius ρ (row centres inside the shape). */
std::vector<Span> kernelSpans(const Iris& iris, float rho, double& area) {
    std::vector<Span> spans;
    area = 0;
    const int rows = static_cast<int>(std::ceil(rho));
    const auto inside = [&](float x, float y) { return std::hypot(x, y) <= rho * irisRadius(iris, std::atan2(y, x)); };
    for (int dy = -rows; dy <= rows; ++dy) {
        const float y = static_cast<float>(dy);
        if (!inside(0, y)) {
            // A rotated polygon's row may not contain x = 0 only when |y| is at the very top: skip it.
            continue;
        }
        const auto extent = [&](float direction) {
            float lo = 0, hi = rho + 1;
            for (int it = 0; it < 24; ++it) {
                const float mid = 0.5F * (lo + hi);
                (inside(direction * mid, y) ? lo : hi) = mid;
            }
            return lo;
        };
        Span s{dy, -extent(-1.0F) - 0.5F, extent(1.0F) + 0.5F};  // ±0.5: pixel footprint around the boundary
        area += static_cast<double>(s.right - s.left);
        spans.push_back(s);
    }
    if (spans.empty() || area <= 0) {
        spans = {{0, -0.5F, 0.5F}};
        area = 1;
    }
    return spans;
}

struct Bins final {
    float step = 1;  // CoC between bin centres
    int half = 0;    // bins −half … +half
};

Bins binsFor(float radius) {
    Bins b;
    if (radius < 0.5F) return b;
    b.half = radius <= 8.0F ? static_cast<int>(std::ceil(radius)) : 8;
    b.step = radius / static_cast<float>(b.half);
    return b;
}

/** Depth (0–1) around one tile (3×3 tiles cached, coordinates clamped): masks by value, RGBA by luminance. */
class DepthReader final {
public:
    DepthReader(const TileStore& depth, std::int64_t tx, std::int64_t ty) : depth_(depth), tx_(tx), ty_(ty) {
        for (int dy = -1; dy <= 1; ++dy)
            for (int dx = -1; dx <= 1; ++dx)
                tiles_[static_cast<std::size_t>((dy + 1) * 3 + dx + 1)] = depth.readTile({static_cast<std::int32_t>(tx + dx), static_cast<std::int32_t>(ty + dy)});
    }
    float at(std::int64_t x, std::int64_t y) const {
        x = std::clamp<std::int64_t>(x, 0, depth_.width() - 1);
        y = std::clamp<std::int64_t>(y, 0, depth_.height() - 1);
        const auto& tile = tiles_[static_cast<std::size_t>((y / kEdge - ty_ + 1) * 3 + (x / kEdge - tx_ + 1))];
        if (tile == nullptr) return 0.0F;
        const auto local = static_cast<std::size_t>(y % kEdge) * kTileEdge + static_cast<std::size_t>(x % kEdge);
        switch (depth_.format()) {
            case PixelFormat::A8: return (*tile)[local] / 255.0F;
            case PixelFormat::A16: {
                std::uint16_t v = 0;
                std::memcpy(&v, tile->data() + local * 2U, 2U);
                return v / 65535.0F;
            }
            default: {
                float px[4];
                paint::detail::readPixel(depth_.format(), tile->data() + local * depth_.bytes_per_pixel(), px);
                return paint::detail::unitClamp(0.2126F * paint::detail::unitClamp(px[0]) + 0.7152F * paint::detail::unitClamp(px[1]) + 0.0722F * paint::detail::unitClamp(px[2]));
            }
        }
    }

private:
    const TileStore& depth_;
    std::int64_t tx_, ty_;
    std::array<std::shared_ptr<const TileBuffer>, 9> tiles_;
};

}  // namespace

std::optional<TileStore> lensBlur(const TileStore& layer, const TileStore* depth, const LensBlurSpec& spec, std::string& error) {
    if (!paint::detail::isRgba(layer.format())) {
        error = "lens blur needs an RGBA layer";
        return std::nullopt;
    }
    if (depth != nullptr && (depth->width() != layer.width() || depth->height() != layer.height() ||
                             !(paint::detail::isRgba(depth->format()) || depth->format() == PixelFormat::A16 || depth->format() == PixelFormat::A8))) {
        error = "the depth map must be a mask or RGBA layer of the layer's size";
        return std::nullopt;
    }
    if (!in(spec.radius, 0, 100) || !in(spec.focal_distance, 0, 1) || !in(spec.iris.rotation_degrees, -360, 360) || !in(spec.iris.curvature, 0, 1) ||
        !(spec.iris.blades == 0 || (spec.iris.blades >= 3 && spec.iris.blades <= 8)) || !in(spec.specular_brightness, 0, 1) ||
        !in(spec.specular_threshold, 0, 1) || !in(spec.noise, 0, 1)) {
        error = "lens blur settings are out of range";
        return std::nullopt;
    }
    const bool linear_input = layer.format() == PixelFormat::RGBA32F;
    const auto bins = binsFor(spec.radius);
    // Region border: the largest bin's border (radius + two low-res pixels, aligned to its reduction).
    const auto reduction = [](float r) {
        int s = 1;
        while (r / static_cast<float>(s) > kMaxLowResRadius) s *= 2;
        return s;
    };
    const auto borderFor = [&](float r) {
        const int s = reduction(r);
        return s * (static_cast<int>(std::ceil((r + static_cast<float>(s)) / static_cast<float>(s))) + 1);
    };
    const int border = bins.half > 0 ? borderFor(spec.radius) : 0;
    const int side = kEdge + 2 * border;
    const float focus_scale = 1.0F / std::max(spec.focal_distance, 1.0F - spec.focal_distance);

    TileStore out = layer.emptyLike(layer.format());
    const int columns = static_cast<int>((layer.width() + kTileEdge - 1) / kTileEdge), rows = static_cast<int>((layer.height() + kTileEdge - 1) / kTileEdge);
    const auto tiles = static_cast<std::size_t>(columns) * static_cast<std::size_t>(rows);
    std::atomic<bool> stop{false}, failed{false}, reported_final{false};
    std::atomic<std::size_t> done{0};
    parallel::parallelFor(tiles, parallel::workerCount(spec.worker_threads), [&](std::size_t index, std::size_t worker) {
        if (stop.load()) return;
        const int tx = static_cast<int>(index % static_cast<std::size_t>(columns)), ty = static_cast<int>(index / static_cast<std::size_t>(columns));
        const std::int64_t ox = static_cast<std::int64_t>(tx) * kEdge - border, oy = static_cast<std::int64_t>(ty) * kEdge - border;
        // 1. Region: premultiplied linear colour, alpha and CoC bin membership.
        const develop::detail::Neighbourhood around(layer, tx, ty);
        std::optional<DepthReader> depth_around;
        if (depth != nullptr) depth_around.emplace(*depth, tx, ty);
        const auto count = static_cast<std::size_t>(side) * static_cast<std::size_t>(side);
        std::vector<std::array<float, 4>> colour(count);
        std::vector<std::int16_t> bin(count);
        std::vector<float> frac(count);
        std::vector<std::uint8_t> used(static_cast<std::size_t>(2 * bins.half + 2), 0);
        for (int y = 0; y < side; ++y)
            for (int x = 0; x < side; ++x) {
                float px[4];
                around.read(ox + x, oy + y, px);
                const float alpha = paint::detail::unitClamp(px[3]);
                float lin[3];
                float luminance = 0;
                for (int c = 0; c < 3; ++c) {
                    if (linear_input) {
                        lin[c] = std::isfinite(px[c]) ? std::max(0.0F, px[c]) : 0.0F;
                    } else {
                        lin[c] = decode(px[c]);
                    }
                }
                if (spec.specular_brightness > 0) {
                    luminance = linear_input ? encode(0.2126F * lin[0] + 0.7152F * lin[1] + 0.0722F * lin[2])
                                             : 0.2126F * paint::detail::unitClamp(px[0]) + 0.7152F * paint::detail::unitClamp(px[1]) + 0.0722F * paint::detail::unitClamp(px[2]);
                    const float t = spec.specular_threshold;
                    const float boost = 1.0F + 15.0F * spec.specular_brightness * smoothstep(t, t + 0.5F * (1.0F - t) + 1e-3F, luminance);
                    for (auto& v : lin) v *= boost;
                }
                const auto i = static_cast<std::size_t>(y) * side + static_cast<std::size_t>(x);
                colour[i] = {lin[0] * alpha, lin[1] * alpha, lin[2] * alpha, alpha};
                float d = depth_around ? depth_around->at(ox + x, oy + y) : 1.0F;
                if (spec.invert_depth) d = 1.0F - d;
                const float coc = std::clamp(spec.radius * (d - spec.focal_distance) * focus_scale, -spec.radius, spec.radius);
                if (bins.half == 0) {
                    bin[i] = 0;
                    frac[i] = 0;
                } else {
                    const float t = coc / bins.step;
                    const int j = std::clamp(static_cast<int>(std::floor(t)), -bins.half, bins.half);
                    bin[i] = static_cast<std::int16_t>(j);
                    frac[i] = j == bins.half ? 0.0F : std::clamp(t - static_cast<float>(j), 0.0F, 1.0F);
                    used[static_cast<std::size_t>(j + bins.half)] = 1;
                    if (frac[i] > 0) used[static_cast<std::size_t>(j + 1 + bins.half)] = 1;
                }
            }
        // 2. Bins far → near: blur each and composite "over" the accumulation.
        const std::size_t pixels = static_cast<std::size_t>(kEdge) * kEdge;
        std::vector<std::array<float, 5>> acc(pixels, {0, 0, 0, 0, 0});  // premultiplied rgb, alpha, coverage
        std::vector<std::array<float, 5>> low, blurred;
        std::vector<float> prefix;
        for (int j = bins.half; j >= -bins.half; --j) {
            if (bins.half > 0 && !used[static_cast<std::size_t>(j + bins.half)]) continue;
            const float r = std::abs(static_cast<float>(j)) * bins.step;
            const auto membership = [&](std::size_t i) {
                if (bin[i] == j) return 1.0F - frac[i];
                if (bin[i] + 1 == j) return frac[i];
                return 0.0F;
            };
            const auto compose = [&](std::size_t p, const std::array<float, 5>& v) {
                auto& a = acc[p];
                const float keep = 1.0F - v[3], keep_cover = 1.0F - v[4];
                for (int c = 0; c < 4; ++c) a[static_cast<std::size_t>(c)] = v[static_cast<std::size_t>(c)] + a[static_cast<std::size_t>(c)] * keep;
                a[4] = v[4] + a[4] * keep_cover;
            };
            if (r < 0.5F) {
                for (int y = 0; y < kEdge; ++y)
                    for (int x = 0; x < kEdge; ++x) {
                        const auto i = static_cast<std::size_t>(y + border) * side + static_cast<std::size_t>(x + border);
                        const float m = membership(i);
                        if (m <= 0) continue;
                        const auto& c = colour[i];
                        compose(static_cast<std::size_t>(y) * kEdge + static_cast<std::size_t>(x), {c[0] * m, c[1] * m, c[2] * m, c[3] * m, m});
                    }
                continue;
            }
            const int s = reduction(r);
            const int b = borderFor(r);
            const float rho = r / static_cast<float>(s);
            const int l = (kEdge + 2 * b) / s;
            const int offset = border - b;  // this bin's sub-region inside the region
            low.assign(static_cast<std::size_t>(l) * l, {0, 0, 0, 0, 0});
            const float inv = 1.0F / static_cast<float>(s * s);
            bool any = false;
            for (int y = 0; y < kEdge + 2 * b; ++y)
                for (int x = 0; x < kEdge + 2 * b; ++x) {
                    const auto i = static_cast<std::size_t>(y + offset) * side + static_cast<std::size_t>(x + offset);
                    const float m = membership(i);
                    if (m <= 0) continue;
                    any = true;
                    auto& cell = low[static_cast<std::size_t>(y / s) * l + static_cast<std::size_t>(x / s)];
                    const auto& c = colour[i];
                    const float w = m * inv;
                    for (int k = 0; k < 4; ++k) cell[static_cast<std::size_t>(k)] += c[static_cast<std::size_t>(k)] * w;
                    cell[4] += w;
                }
            if (!any) continue;
            // Row prefix sums (continuous: pixel i covers [i, i + 1)).
            prefix.assign(static_cast<std::size_t>(l) * static_cast<std::size_t>(l + 1) * 5, 0.0F);
            for (int y = 0; y < l; ++y)
                for (int x = 0; x < l; ++x)
                    for (int k = 0; k < 5; ++k) {
                        const auto row = static_cast<std::size_t>(y) * static_cast<std::size_t>(l + 1);
                        prefix[(row + static_cast<std::size_t>(x) + 1) * 5 + static_cast<std::size_t>(k)] =
                            prefix[(row + static_cast<std::size_t>(x)) * 5 + static_cast<std::size_t>(k)] + low[static_cast<std::size_t>(y) * l + static_cast<std::size_t>(x)][static_cast<std::size_t>(k)];
                    }
            double area = 0;
            const auto spans = kernelSpans(spec.iris, rho, area);
            // Span ends relative to u: integer part and fraction are the same for every u. The border
            // guarantees b / s ≥ ρ + 2, so every tap stays inside the low-res region (no clamping).
            struct Tap final {
                int row, lo, hi;  // row offset, integer starts
                float flo, fhi;  // fractions
            };
            std::vector<Tap> taps;
            taps.reserve(spans.size());
            for (const auto& sp : spans) {
                const double a = 0.5 + sp.left, z = 0.5 + sp.right;
                const double ia = std::floor(a), iz = std::floor(z);
                taps.push_back({sp.dy, static_cast<int>(ia), static_cast<int>(iz), static_cast<float>(a - ia), static_cast<float>(z - iz)});
            }
            // Blurred low-res pixels covering the tile (plus one for bilinear upsampling).
            const int u0 = b / s - 1, u1 = (b + kEdge) / s + 1;
            const int bw = u1 - u0 + 1;
            blurred.assign(static_cast<std::size_t>(bw) * static_cast<std::size_t>(bw), {0, 0, 0, 0, 0});
            const float norm = static_cast<float>(1.0 / area);
            const std::size_t stride = static_cast<std::size_t>(l + 1) * 5;
            for (int v = u0; v <= u1; ++v)
                for (int u = u0; u <= u1; ++u) {
                    float sum[5] = {0, 0, 0, 0, 0};
                    for (const auto& tap : taps) {
                        const float* row = prefix.data() + static_cast<std::size_t>(v + tap.row) * stride;
                        const float* pz = row + static_cast<std::size_t>(u + tap.hi) * 5;
                        const float* pa = row + static_cast<std::size_t>(u + tap.lo) * 5;
                        for (int k = 0; k < 5; ++k) sum[k] += (pz[k] + (pz[k + 5] - pz[k]) * tap.fhi) - (pa[k] + (pa[k + 5] - pa[k]) * tap.flo);
                    }
                    auto& o = blurred[static_cast<std::size_t>(v - u0) * bw + static_cast<std::size_t>(u - u0)];
                    for (int k = 0; k < 5; ++k) o[static_cast<std::size_t>(k)] = sum[k] * norm;
                }
            for (int y = 0; y < kEdge; ++y)
                for (int x = 0; x < kEdge; ++x) {
                    std::array<float, 5> v{};
                    if (s == 1) {
                        v = blurred[static_cast<std::size_t>(y + b - u0) * bw + static_cast<std::size_t>(x + b - u0)];
                    } else {
                        const float fu = (static_cast<float>(x + b) + 0.5F) / static_cast<float>(s) - 0.5F - static_cast<float>(u0);
                        const float fv = (static_cast<float>(y + b) + 0.5F) / static_cast<float>(s) - 0.5F - static_cast<float>(u0);
                        const int iu = std::clamp(static_cast<int>(std::floor(fu)), 0, bw - 2), iv = std::clamp(static_cast<int>(std::floor(fv)), 0, bw - 2);
                        const float au = std::clamp(fu - static_cast<float>(iu), 0.0F, 1.0F), av = std::clamp(fv - static_cast<float>(iv), 0.0F, 1.0F);
                        const auto& p00 = blurred[static_cast<std::size_t>(iv) * bw + static_cast<std::size_t>(iu)];
                        const auto& p10 = blurred[static_cast<std::size_t>(iv) * bw + static_cast<std::size_t>(iu + 1)];
                        const auto& p01 = blurred[static_cast<std::size_t>(iv + 1) * bw + static_cast<std::size_t>(iu)];
                        const auto& p11 = blurred[static_cast<std::size_t>(iv + 1) * bw + static_cast<std::size_t>(iu + 1)];
                        for (std::size_t k = 0; k < 5; ++k) v[k] = (p00[k] * (1 - au) + p10[k] * au) * (1 - av) + (p01[k] * (1 - au) + p11[k] * au) * av;
                    }
                    if (v[4] <= 0) continue;
                    compose(static_cast<std::size_t>(y) * kEdge + static_cast<std::size_t>(x), v);
                }
        }
        // 3. Normalise, add noise, encode.
        TileBuffer tile(out.tile_byte_size(), 0);
        const auto bpp = out.bytes_per_pixel();
        bool any = false;
        for (int y = 0; y < kEdge; ++y) {
            const std::int64_t gy = static_cast<std::int64_t>(ty) * kEdge + y;
            if (gy >= layer.height()) break;
            for (int x = 0; x < kEdge; ++x) {
                const std::int64_t gx = static_cast<std::int64_t>(tx) * kEdge + x;
                if (gx >= layer.width()) break;
                const auto& a = acc[static_cast<std::size_t>(y) * kEdge + static_cast<std::size_t>(x)];
                if (a[4] <= 1e-6F || a[3] <= 1e-6F) continue;
                const float alpha = std::min(1.0F, a[3] / a[4]);
                float value[4] = {a[0] / a[3], a[1] / a[3], a[2] / a[3], alpha};
                if (!linear_input)
                    for (int c = 0; c < 3; ++c) value[c] = encode(value[c]);
                if (spec.noise > 0) {
                    const auto hash = [&](std::uint32_t channel) {
                        std::uint32_t h = static_cast<std::uint32_t>(gx) * 0x9E3779B1U ^ static_cast<std::uint32_t>(gy) * 0x85EBCA77U ^ spec.seed * 0xC2B2AE3DU ^ channel * 0x27D4EB2FU;
                        h = (h ^ (h >> 15)) * 0x2C1B3C6DU;
                        h = (h ^ (h >> 12)) * 0x297A2D39U;
                        h ^= h >> 15;
                        return static_cast<float>(h & 0xFFFFU) / 65535.0F;
                    };
                    for (std::uint32_t c = 0; c < 3; ++c) {
                        const std::uint32_t ch = spec.monochromatic_noise ? 0U : c;
                        const float n = (hash(ch * 2U) + hash(ch * 2U + 1U) - 1.0F) * 0.1F * spec.noise;  // triangular ±0.1
                        value[c] = linear_input ? std::max(0.0F, value[c] + n) : value[c] + n;
                    }
                }
                paint::detail::writePixel(out.format(), tile.data() + (static_cast<std::size_t>(y) * kTileEdge + static_cast<std::size_t>(x)) * bpp, value);
                any = true;
            }
        }
        if (any && !out.writeTile({tx, ty}, tile)) {
            failed.store(true);
            stop.store(true);
            return;
        }
        const auto finished = done.fetch_add(1) + 1;
        if (worker == 0 && spec.progress) {
            if (finished == tiles) reported_final.store(true);
            if (!spec.progress(static_cast<float>(finished) / static_cast<float>(tiles))) stop.store(true);
        }
    });
    if (!stop.load() && !reported_final.load() && spec.progress && !spec.progress(1.0F)) stop.store(true);
    if (stop.load()) {
        error = failed.load() ? "could not store blurred pixels" : "cancelled";
        return std::nullopt;
    }
    return out;
}

std::optional<TileStore> depthRangeMask(const TileStore& depth, float near, float far, float feather, std::string& error) {
    if (!(paint::detail::isRgba(depth.format()) || depth.format() == PixelFormat::A16 || depth.format() == PixelFormat::A8)) {
        error = "the depth map must be a mask or RGBA layer";
        return std::nullopt;
    }
    if (!in(near, 0, 1) || !in(far, 0, 1) || near > far || !in(feather, 0, 1)) {
        error = "depth range must satisfy 0 ≤ near ≤ far ≤ 1 with feather 0–1";
        return std::nullopt;
    }
    TileStore mask = depth.emptyLike(PixelFormat::A16);
    const int columns = static_cast<int>((depth.width() + kTileEdge - 1) / kTileEdge), rows = static_cast<int>((depth.height() + kTileEdge - 1) / kTileEdge);
    std::atomic<bool> failed{false};
    parallel::parallelFor(static_cast<std::size_t>(columns) * static_cast<std::size_t>(rows), parallel::workerCount(0), [&](std::size_t index, std::size_t) {
        const int tx = static_cast<int>(index % static_cast<std::size_t>(columns)), ty = static_cast<int>(index / static_cast<std::size_t>(columns));
        const DepthReader around(depth, tx, ty);
        TileBuffer tile(mask.tile_byte_size(), 0);
        bool any = false;
        for (int y = 0; y < kEdge; ++y)
            for (int x = 0; x < kEdge; ++x) {
                const std::int64_t gx = static_cast<std::int64_t>(tx) * kEdge + x, gy = static_cast<std::int64_t>(ty) * kEdge + y;
                if (gx >= depth.width() || gy >= depth.height()) continue;
                const float d = around.at(gx, gy);
                float w = d >= near && d <= far ? 1.0F : 0.0F;
                if (feather > 0 && w == 0.0F) w = d < near ? 1.0F - smoothstep(0, feather, near - d) : 1.0F - smoothstep(0, feather, d - far);
                const auto v = static_cast<std::uint16_t>(w * 65535.0F + 0.5F);
                if (v == 0) continue;
                std::memcpy(tile.data() + (static_cast<std::size_t>(y) * kTileEdge + static_cast<std::size_t>(x)) * 2, &v, 2);
                any = true;
            }
        if (any && !mask.writeTile({tx, ty}, tile)) failed.store(true);
    });
    if (failed.load()) {
        error = "could not store the depth mask";
        return std::nullopt;
    }
    return mask;
}

}  // namespace croptop::lensblur
