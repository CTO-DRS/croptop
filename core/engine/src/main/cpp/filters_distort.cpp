#include "filters_distort.h"

#include <algorithm>
#include <array>
#include <cmath>
#include <functional>
#include <vector>

#include "filters_blur.h"
#include "filters_internal.h"

namespace croptop::filters {
namespace {

using namespace detail;

constexpr double kPi = 3.14159265358979323846;
constexpr double kDegrees = kPi / 180.0;

struct Geometry {
    double cx, cy, radius;
};

Geometry geometry(const TileStore& layer) {
    return {layer.width() / 2.0, layer.height() / 2.0, std::min(layer.width(), layer.height()) / 2.0};
}

/** Inverse-map resampling: `map(x + 0.5, y + 0.5, qx, qy)` gives the source point for each output pixel. */
bool remap(TileStore& layer, const TileStore* mask, const std::function<void(double, double, double&, double&)>& map, std::string& error,
           unsigned worker_threads, const TileStore* aux = nullptr) {
    return pointwise(
        layer, mask, -1,
        [&](Taps& taps, int x, int y) {
            double qx = 0.0, qy = 0.0;
            map(x + 0.5, y + 0.5, qx, qy);
            if (!std::isfinite(qx) || !std::isfinite(qy)) return taps.layer.texel(x, y);
            return taps.layer.bilinear(qx, qy);
        },
        error, worker_threads, aux);
}

bool within(float v, float low, float high) { return std::isfinite(v) && v >= low && v <= high; }

Pixel premultiplied(Pixel p) {
    for (std::size_t c = 0; c < 3; ++c) p[c] *= p[3];
    return p;
}

float luma(const Pixel& p) { return 0.2126F * p[0] + 0.7152F * p[1] + 0.0722F * p[2]; }

std::uint32_t hash(std::uint32_t x, std::uint32_t y, std::uint32_t z) {
    std::uint32_t h = x * 0x8DA6B343U ^ y * 0xD8163841U ^ z * 0xCB1AB31FU;
    h ^= h >> 15;
    h *= 0x2C1B3C6DU;
    h ^= h >> 12;
    h *= 0x297A2D39U;
    h ^= h >> 15;
    return h;
}
float unit(std::uint32_t h) { return static_cast<float>(h >> 8) * (1.0F / 16777216.0F); }

/** Sobel gradient magnitude per channel (straight colour) at spacing d, read through `read`. */
template <typename Read>
Pixel sobel(Read&& read, int x, int y, int d) {
    Pixel magnitude{};
    std::array<Pixel, 9> n{};
    for (int j = -1; j <= 1; ++j)
        for (int i = -1; i <= 1; ++i) n[static_cast<std::size_t>((j + 1) * 3 + (i + 1))] = unpremultiply(read(x + i * d, y + j * d));
    for (std::size_t c = 0; c < 3; ++c) {
        const float gx = (n[2][c] + 2 * n[5][c] + n[8][c]) - (n[0][c] + 2 * n[3][c] + n[6][c]);
        const float gy = (n[6][c] + 2 * n[7][c] + n[8][c]) - (n[0][c] + 2 * n[1][c] + n[2][c]);
        magnitude[c] = std::sqrt(gx * gx + gy * gy) / 4.0F;
    }
    magnitude[3] = n[4][3];
    return magnitude;
}

}  // namespace

bool twirl(TileStore& layer, float angle, const TileStore* mask, std::string& error, unsigned worker_threads) {
    if (!within(angle, -999.0F, 999.0F)) {
        error = "angle must be −999…999°";
        return false;
    }
    const auto g = geometry(layer);
    return remap(
        layer, mask,
        [&](double px, double py, double& qx, double& qy) {
            const double dx = px - g.cx, dy = py - g.cy, r = std::hypot(dx, dy);
            if (r >= g.radius) {
                qx = px, qy = py;
                return;
            }
            const double t = 1.0 - r / g.radius;
            const double a = -angle * kDegrees * t * t, c = std::cos(a), s = std::sin(a);
            qx = g.cx + c * dx - s * dy;
            qy = g.cy + s * dx + c * dy;
        },
        error, worker_threads);
}

bool pinch(TileStore& layer, float amount, const TileStore* mask, std::string& error, unsigned worker_threads) {
    if (!within(amount, -100.0F, 100.0F)) {
        error = "amount must be −100…100";
        return false;
    }
    const auto g = geometry(layer);
    const double a = -amount / 200.0;
    return remap(
        layer, mask,
        [&](double px, double py, double& qx, double& qy) {
            const double dx = px - g.cx, dy = py - g.cy, r = std::hypot(dx, dy);
            const double f = r >= g.radius ? 1.0 : (1.0 - a) + a * r / g.radius;
            qx = g.cx + dx * f;
            qy = g.cy + dy * f;
        },
        error, worker_threads);
}

bool spherize(TileStore& layer, float amount, const TileStore* mask, std::string& error, unsigned worker_threads) {
    if (!within(amount, -100.0F, 100.0F)) {
        error = "amount must be −100…100";
        return false;
    }
    const auto g = geometry(layer);
    const double k = amount / 100.0;
    return remap(
        layer, mask,
        [&](double px, double py, double& qx, double& qy) {
            const double dx = px - g.cx, dy = py - g.cy, r = std::hypot(dx, dy);
            double f = 1.0;
            if (r < g.radius && r > 1e-9) {
                const double u = r / g.radius;
                // Bulge: q = R·(2/π)·asin(r/R) magnifies the centre (scale 2/π) and meets the identity at R;
                // pinch-in: q = R·sin(π/2 · r/R) shrinks it (scale π/2).
                const double shape = k >= 0.0 ? std::asin(u) / (u * kPi / 2.0) : std::sin(u * kPi / 2.0) / u;
                f = 1.0 + std::fabs(k) * (shape - 1.0);
            } else if (r <= 1e-9) {
                // Limits at the centre: 2/π (bulge) and π/2 (pinch-in).
                f = 1.0 + std::fabs(k) * ((k >= 0.0 ? 2.0 / kPi : kPi / 2.0) - 1.0);
            }
            qx = g.cx + dx * f;
            qy = g.cy + dy * f;
        },
        error, worker_threads);
}

bool wave(TileStore& layer, float wavelength, float amplitude, bool horizontal, bool vertical, const TileStore* mask,
          std::string& error, unsigned worker_threads) {
    if (!within(wavelength, 2.0F, 999.0F) || !within(amplitude, 0.0F, 999.0F)) {
        error = "wavelength must be 2–999 px and amplitude 0–999 px";
        return false;
    }
    const double k = 2.0 * kPi / wavelength;
    return remap(
        layer, mask,
        [&](double px, double py, double& qx, double& qy) {
            qx = px + (horizontal ? amplitude * std::sin(k * py) : 0.0);
            qy = py + (vertical ? amplitude * std::sin(k * px) : 0.0);
        },
        error, worker_threads);
}

bool ripple(TileStore& layer, float amount, RippleSize size, const TileStore* mask, std::string& error, unsigned worker_threads) {
    if (!within(amount, -999.0F, 999.0F)) {
        error = "amount must be −999…999";
        return false;
    }
    const float wavelength = size == RippleSize::Small ? 8.0F : size == RippleSize::Medium ? 16.0F : 32.0F;
    const float amplitude = std::fabs(amount) / 100.0F * wavelength / 4.0F;
    const double k = 2.0 * kPi / wavelength * (amount < 0.0F ? -1.0 : 1.0);
    return remap(
        layer, mask,
        [&](double px, double py, double& qx, double& qy) {
            qx = px + amplitude * std::sin(k * py);
            qy = py + amplitude * std::sin(k * px);
        },
        error, worker_threads);
}

bool polarCoordinates(TileStore& layer, PolarMode mode, const TileStore* mask, std::string& error, unsigned worker_threads) {
    const double w = layer.width(), h = layer.height(), cx = w / 2.0, cy = h / 2.0;
    return remap(
        layer, mask,
        [&](double px, double py, double& qx, double& qy) {
            if (mode == PolarMode::RectangularToPolar) {
                // Output is the polar image: angle (from the top, clockwise) picks the source column, the
                // elliptical radius picks the source row counted from the bottom.
                const double dx = (px - cx) / cx, dy = (py - cy) / cy;
                const double r = std::hypot(dx, dy);
                double phi = std::atan2(dx, -dy);
                if (phi < 0.0) phi += 2.0 * kPi;
                qx = phi / (2.0 * kPi) * w;
                qy = h - std::min(r, 1.0) * h;
            } else {
                const double phi = px / w * 2.0 * kPi, r = (h - py) / h;
                qx = cx + r * std::sin(phi) * cx;
                qy = cy - r * std::cos(phi) * cy;
            }
        },
        error, worker_threads);
}

bool displace(TileStore& layer, const TileStore& map, float horizontal_scale, float vertical_scale, bool tile_map,
              const TileStore* mask, std::string& error, unsigned worker_threads) {
    if (!isRgba(map.format()) || map.width() == 0U || map.height() == 0U || !within(horizontal_scale, -999.0F, 999.0F) ||
        !within(vertical_scale, -999.0F, 999.0F)) {
        error = "displace needs an RGBA map and scales of −999…999 %";
        return false;
    }
    const double mw = map.width(), mh = map.height(), lw = layer.width(), lh = layer.height();
    return pointwise(
        layer, mask, -1,
        [&](Taps& taps, int x, int y) {
            double mx = 0.0, my = 0.0;
            if (tile_map) {
                mx = std::fmod(x + 0.5, mw);
                my = std::fmod(y + 0.5, mh);
            } else {
                mx = (x + 0.5) / lw * mw;
                my = (y + 0.5) / lh * mh;
            }
            const auto m = unpremultiply(taps.aux.bilinear(mx, my));
            const double qx = x + 0.5 + (m[0] - 0.5) * 2.0 * horizontal_scale * 1.28;
            const double qy = y + 0.5 + (m[1] - 0.5) * 2.0 * vertical_scale * 1.28;
            return taps.layer.bilinear(qx, qy);
        },
        error, worker_threads, &map);
}

bool emboss(TileStore& layer, float angle, float height, float amount, const TileStore* mask, std::string& error, unsigned worker_threads) {
    if (!within(angle, -360.0F, 360.0F) || !within(height, 1.0F, 10.0F) || !within(amount, 0.01F, 5.0F)) {
        error = "emboss needs angle −360…360°, height 1–10 px and amount 0.01–5";
        return false;
    }
    const double ux = std::cos(angle * kDegrees) * height, uy = -std::sin(angle * kDegrees) * height;
    return pointwise(
        layer, mask, static_cast<int>(std::ceil(height)) + 2,
        [&](Taps& taps, int x, int y) {
            const auto lit = unpremultiply(taps.window.bilinear(x + 0.5 + ux, y + 0.5 + uy));
            const auto shade = unpremultiply(taps.window.bilinear(x + 0.5 - ux, y + 0.5 - uy));
            Pixel out = unpremultiply(taps.window.texel(x, y));
            for (std::size_t c = 0; c < 3; ++c) out[c] = 0.5F + amount * (lit[c] - shade[c]);
            return premultiplied(out);
        },
        error, worker_threads);
}

bool findEdges(TileStore& layer, const TileStore* mask, std::string& error, unsigned worker_threads) {
    return pointwise(
        layer, mask, 1,
        [&](Taps& taps, int x, int y) {
            auto m = sobel([&](int i, int j) { return taps.window.texel(i, j); }, x, y, 1);
            for (std::size_t c = 0; c < 3; ++c) m[c] = 1.0F - std::min(1.0F, m[c]);
            return premultiplied(m);
        },
        error, worker_threads);
}

bool glowingEdges(TileStore& layer, int width, float brightness, int smoothness, const TileStore* mask, std::string& error,
                  unsigned worker_threads) {
    if (width < 1 || width > 14 || !within(brightness, 0.0F, 20.0F) || smoothness < 1 || smoothness > 15) {
        error = "glowing edges needs width 1–14, brightness 0–20 and smoothness 1–15";
        return false;
    }
    if (!validate(layer, mask, error)) return false;
    try {
        TileStore smooth(layer);
        if (!boxBlur(smooth, static_cast<std::uint32_t>(smoothness / 2), nullptr, error, worker_threads)) return false;
        const int spacing = std::max(1, (width + 1) / 2);
        return pointwise(
            layer, mask, -1,
            [&](Taps& taps, int x, int y) {
                auto m = sobel([&](int i, int j) { return taps.aux.texel(i, j); }, x, y, spacing);
                for (std::size_t c = 0; c < 3; ++c) m[c] = std::min(1.0F, m[c] * brightness / 2.0F);
                return premultiplied(m);
            },
            error, worker_threads, &smooth);
    } catch (const std::exception&) {
        error = "could not allocate filter buffers";
        return false;
    }
}

bool oilPaint(TileStore& layer, float stylization, float cleanliness, const TileStore* mask, std::string& error, unsigned worker_threads) {
    if (!within(stylization, 0.1F, 10.0F) || !within(cleanliness, 0.0F, 10.0F)) {
        error = "stylization must be 0.1–10 and cleanliness 0–10";
        return false;
    }
    const int r = 1 + static_cast<int>(std::lround(cleanliness));
    const int levels = std::clamp(static_cast<int>(std::lround(24.0F - 2.0F * stylization)), 4, 24);
    return pointwise(
        layer, mask, r,
        [&](Taps& taps, int x, int y) {
            std::array<int, 24> count{};
            std::array<Pixel, 24> sum{};
            for (int dy = -r; dy <= r; ++dy)
                for (int dx = -r; dx <= r; ++dx) {
                    if (dx * dx + dy * dy > r * r) continue;
                    const auto p = taps.window.texel(x + dx, y + dy);
                    const auto straight = unpremultiply(p);
                    const auto level = static_cast<std::size_t>(std::clamp(static_cast<int>(luma(straight) * static_cast<float>(levels)), 0, levels - 1));
                    ++count[level];
                    for (std::size_t c = 0; c < 4; ++c) sum[level][c] += p[c];
                }
            std::size_t best = 0;
            for (std::size_t i = 1; i < static_cast<std::size_t>(levels); ++i)
                if (count[i] > count[best]) best = i;
            Pixel out{};
            for (std::size_t c = 0; c < 4; ++c) out[c] = sum[best][c] / static_cast<float>(std::max(1, count[best]));
            return out;
        },
        error, worker_threads);
}

bool diffuseGlow(TileStore& layer, float graininess, float glow, float clear, std::uint32_t seed, const TileStore* mask,
                 std::string& error, unsigned worker_threads) {
    if (!within(graininess, 0.0F, 10.0F) || !within(glow, 0.0F, 20.0F) || !within(clear, 0.0F, 20.0F)) {
        error = "graininess must be 0–10, glow 0–20 and clear 0–20";
        return false;
    }
    if (!validate(layer, mask, error)) return false;
    try {
        // Highlights only, blurred.
        TileStore highlights(layer);
        const float threshold = 1.0F - clear / 20.0F;
        if (!pointwise(
                highlights, nullptr, 0,
                [&](Taps& taps, int x, int y) {
                    const auto p = taps.window.texel(x, y);
                    const auto straight = unpremultiply(p);
                    const float l = luma(straight);
                    const float keep = std::clamp((l - threshold) / 0.1F + 0.5F, 0.0F, 1.0F);
                    Pixel out{};
                    for (std::size_t c = 0; c < 4; ++c) out[c] = p[c] * keep;
                    out[3] = p[3];
                    return out;
                },
                error, worker_threads)) {
            return false;
        }
        if (!gaussianBlur(highlights, 2.0F + glow, nullptr, error, worker_threads)) return false;
        const float strength = glow / 10.0F, grain = graininess / 10.0F * 0.2F;
        return pointwise(
            layer, mask, 0,
            [&](Taps& taps, int x, int y) {
                auto p = unpremultiply(taps.window.texel(x, y));
                const auto h = taps.aux.texel(x, y);
                const float n = grain * (2.0F * unit(hash(static_cast<std::uint32_t>(x), static_cast<std::uint32_t>(y), seed)) - 1.0F);
                for (std::size_t c = 0; c < 3; ++c) p[c] += strength * h[c] + n;
                return premultiplied(p);
            },
            error, worker_threads, &highlights);
    } catch (const std::exception&) {
        error = "could not allocate filter buffers";
        return false;
    }
}

bool vignette(TileStore& layer, float amount, float midpoint, float feather, const TileStore* mask, std::string& error,
              unsigned worker_threads) {
    if (!within(amount, -1.0F, 1.0F) || !within(midpoint, 0.0F, 1.0F) || !within(feather, 0.01F, 1.0F)) {
        error = "vignette needs amount −1…1, midpoint 0–1 and feather 0.01–1";
        return false;
    }
    const double cx = layer.width() / 2.0, cy = layer.height() / 2.0;
    return pointwise(
        layer, mask, 0,
        [&](Taps& taps, int x, int y) {
            auto p = unpremultiply(taps.window.texel(x, y));
            // Elliptical distance: 0 at the centre, 1 at the corners.
            const double d = std::hypot((x + 0.5 - cx) / cx, (y + 0.5 - cy) / cy) / std::sqrt(2.0);
            const double t = std::clamp((d - midpoint) / feather, 0.0, 1.0);
            const auto f = static_cast<float>(t * t * (3.0 - 2.0 * t));
            for (std::size_t c = 0; c < 3; ++c) p[c] = amount >= 0.0F ? p[c] * (1.0F - amount * f) : p[c] + (-amount) * f * (1.0F - p[c]);
            return premultiplied(p);
        },
        error, worker_threads);
}

bool clouds(TileStore& layer, std::array<float, 4> foreground, std::array<float, 4> background, std::uint32_t seed,
            const TileStore* mask, std::string& error, unsigned worker_threads) {
    for (const float v : foreground)
        if (!within(v, 0.0F, 1.0F)) {
            error = "colours must be 0–1";
            return false;
        }
    for (const float v : background)
        if (!within(v, 0.0F, 1.0F)) {
            error = "colours must be 0–1";
            return false;
        }
    const double base = std::max(8.0, std::max(layer.width(), layer.height()) / 4.0);
    const auto lattice = [&](int i, int j, std::uint32_t octave) {
        return unit(hash(static_cast<std::uint32_t>(i), static_cast<std::uint32_t>(j), seed * 16U + octave));
    };
    return pointwise(
        layer, mask, -1,
        [&](Taps&, int x, int y) {
            double value = 0.0, weight = 0.0, amplitude = 1.0, cell = base;
            for (std::uint32_t octave = 0; octave < 8; ++octave) {
                const double gx = (x + 0.5) / cell, gy = (y + 0.5) / cell;
                const int i = static_cast<int>(std::floor(gx)), j = static_cast<int>(std::floor(gy));
                double fx = gx - i, fy = gy - j;
                fx = fx * fx * (3.0 - 2.0 * fx);
                fy = fy * fy * (3.0 - 2.0 * fy);
                const double n = (lattice(i, j, octave) * (1 - fx) + lattice(i + 1, j, octave) * fx) * (1 - fy) +
                                 (lattice(i, j + 1, octave) * (1 - fx) + lattice(i + 1, j + 1, octave) * fx) * fy;
                value += amplitude * n;
                weight += amplitude;
                amplitude *= 0.5;
                cell = std::max(1.0, cell / 2.0);
            }
            const auto t = static_cast<float>(value / weight);
            Pixel out{};
            for (std::size_t c = 0; c < 4; ++c) out[c] = background[c] + t * (foreground[c] - background[c]);
            return premultiplied(out);
        },
        error, worker_threads);
}

namespace {

/** One element of a flare: where on the axis (0 = the light, 1 = the picture centre, 2 = its mirror), its size as a fraction of the diagonal, strength and tint. */
struct FlareElement {
    enum class Kind : std::uint8_t { Glow, Disc, Ring } kind;
    float t, radius, strength, r, g, b;
};

std::vector<FlareElement> flareElements(int lens_type) {
    using K = FlareElement::Kind;
    std::vector<FlareElement> e = {
        {K::Glow, 0.0F, 0.34F, 0.70F, 1.00F, 0.86F, 0.62F},   // the glow around the light
        {K::Glow, 0.0F, 0.07F, 1.00F, 1.00F, 1.00F, 0.95F},   // its hot core
        {K::Ring, 0.0F, 0.20F, 0.28F, 1.00F, 0.80F, 0.55F},   // halo
    };
    switch (lens_type) {
        case 0:  // zoom: several soft ghosts
            e.push_back({K::Disc, 0.45F, 0.05F, 0.26F, 0.55F, 0.80F, 1.00F});
            e.push_back({K::Disc, 0.75F, 0.09F, 0.18F, 1.00F, 0.70F, 0.45F});
            e.push_back({K::Ring, 1.20F, 0.13F, 0.22F, 0.60F, 1.00F, 0.70F});
            e.push_back({K::Disc, 1.55F, 0.04F, 0.30F, 1.00F, 0.55F, 0.80F});
            e.push_back({K::Glow, 1.90F, 0.10F, 0.22F, 0.70F, 0.75F, 1.00F});
            break;
        case 1:  // 35 mm prime: a few crisp ghosts close to the light
            e.push_back({K::Disc, 0.30F, 0.035F, 0.34F, 0.60F, 0.85F, 1.00F});
            e.push_back({K::Ring, 0.60F, 0.08F, 0.30F, 1.00F, 0.75F, 0.50F});
            e.push_back({K::Disc, 0.95F, 0.05F, 0.22F, 1.00F, 0.60F, 0.60F});
            break;
        case 2:  // 105 mm prime: larger, fainter ghosts further out
            e.push_back({K::Disc, 0.70F, 0.14F, 0.12F, 0.70F, 0.90F, 1.00F});
            e.push_back({K::Ring, 1.10F, 0.22F, 0.16F, 1.00F, 0.80F, 0.60F});
            e.push_back({K::Disc, 1.65F, 0.10F, 0.14F, 0.80F, 0.70F, 1.00F});
            break;
        default:  // movie prime: ghosts plus the anamorphic streak (handled in the pixel loop)
            e.push_back({K::Disc, 0.55F, 0.04F, 0.24F, 0.55F, 0.75F, 1.00F});
            e.push_back({K::Ring, 1.00F, 0.10F, 0.20F, 0.70F, 0.90F, 1.00F});
            e.push_back({K::Disc, 1.40F, 0.06F, 0.18F, 0.60F, 0.70F, 1.00F});
            break;
    }
    return e;
}

}  // namespace

bool lensFlare(TileStore& layer, float center_x, float center_y, float brightness, int lens_type, const TileStore* mask, std::string& error,
               unsigned worker_threads) {
    if (!within(center_x, 0.0F, 1.0F) || !within(center_y, 0.0F, 1.0F) || !within(brightness, 0.1F, 3.0F) || lens_type < 0 || lens_type > 3) {
        error = "lens flare needs a centre inside the picture, brightness 0.1–3 and lens type 0–3";
        return false;
    }
    const double w = layer.width(), h = layer.height();
    const double diagonal = std::hypot(w, h);
    const double lx = center_x * w, ly = center_y * h;
    const double ax = w / 2.0 - lx, ay = h / 2.0 - ly;  // from the light to the picture centre
    const auto elements = flareElements(lens_type);
    const bool streak = lens_type == 3;
    return pointwise(
        layer, mask, 0,
        [&](Taps& taps, int x, int y) {
            auto p = unpremultiply(taps.window.texel(x, y));
            double add[3] = {0.0, 0.0, 0.0};
            const double px = x + 0.5, py = y + 0.5;
            for (const auto& el : elements) {
                const double ex = lx + el.t * ax, ey = ly + el.t * ay;
                const double d = std::hypot(px - ex, py - ey) / (el.radius * diagonal);
                double f = 0.0;
                switch (el.kind) {
                    case FlareElement::Kind::Glow: {
                        const double q = std::max(0.0, 1.0 - d);
                        f = q * q;
                        break;
                    }
                    case FlareElement::Kind::Disc: {
                        const double t = std::clamp((1.0 - d) / 0.12, 0.0, 1.0);
                        f = t * t * (3.0 - 2.0 * t) * 0.8;
                        break;
                    }
                    case FlareElement::Kind::Ring: {
                        const double r = (d - 1.0) / 0.14;
                        f = std::exp(-r * r);
                        break;
                    }
                }
                f *= el.strength * brightness;
                add[0] += f * el.r;
                add[1] += f * el.g;
                add[2] += f * el.b;
            }
            if (streak) {
                // A thin horizontal line through the light that fades along its length.
                const double along = std::abs(px - lx) / (0.45 * w);
                const double across = (py - ly) / (0.004 * diagonal + 1.0);
                const double f = std::exp(-across * across) * std::max(0.0, 1.0 - along) * 0.45 * brightness;
                add[0] += f * 0.6;
                add[1] += f * 0.8;
                add[2] += f * 1.0;
            }
            // Screen blend keeps highlights from clipping harshly.
            for (std::size_t c = 0; c < 3; ++c) p[c] = static_cast<float>(1.0 - (1.0 - std::clamp<double>(p[c], 0.0, 1.0)) * (1.0 - std::clamp(add[c], 0.0, 1.0)));
            return premultiplied(p);
        },
        error, worker_threads);
}

bool chromaticAberration(TileStore& layer, float red_shift, float blue_shift, const TileStore* mask, std::string& error,
                         unsigned worker_threads) {
    if (!within(red_shift, -100.0F, 100.0F) || !within(blue_shift, -100.0F, 100.0F)) {
        error = "shifts must be −100…100 px";
        return false;
    }
    const double cx = layer.width() / 2.0, cy = layer.height() / 2.0, corner = std::hypot(cx, cy);
    return pointwise(
        layer, mask, -1,
        [&](Taps& taps, int x, int y) {
            const double dx = x + 0.5 - cx, dy = y + 0.5 - cy;
            const auto sample = [&](float shift) {
                const double f = 1.0 + shift / corner;
                return unpremultiply(taps.layer.bilinear(cx + dx * f, cy + dy * f));
            };
            auto out = unpremultiply(taps.layer.texel(x, y));
            out[0] = sample(red_shift)[0];
            out[2] = sample(blue_shift)[2];
            return premultiplied(out);
        },
        error, worker_threads);
}

}  // namespace croptop::filters
