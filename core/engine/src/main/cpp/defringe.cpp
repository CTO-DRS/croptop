// Manual defringe.
#include "defringe.h"

#include <algorithm>
#include <atomic>
#include <cmath>
#include <vector>

#include "develop_internal.h"
#include "parallel_util.h"
#include "pixel_plane.h"

namespace croptop::lens {
namespace {

constexpr int kBorder = 2;
constexpr float kPi = 3.14159265358979323846F;

float srgbToLinear(float v) { return v <= 0.04045F ? v / 12.92F : std::pow((v + 0.055F) / 1.055F, 2.4F); }
float linearToSrgb(float v) { return v <= 0.0031308F ? v * 12.92F : 1.055F * std::pow(v, 1.0F / 2.4F) - 0.055F; }

std::array<float, 3> toOklab(float r, float g, float b) {
    const float l = std::cbrt(0.4122214708F * r + 0.5363325363F * g + 0.0514459929F * b);
    const float m = std::cbrt(0.2119034982F * r + 0.6806995451F * g + 0.1073969566F * b);
    const float s = std::cbrt(0.0883024619F * r + 0.2817188376F * g + 0.6299787005F * b);
    return {0.2104542553F * l + 0.7936177850F * m - 0.0040720468F * s, 1.9779984951F * l - 2.4285922050F * m + 0.4505937099F * s,
            0.0259040371F * l + 0.7827717662F * m - 0.8086757660F * s};
}

std::array<float, 3> fromOklab(float L, float a, float b) {
    const float l = L + 0.3963377774F * a + 0.2158037573F * b, m = L - 0.1055613458F * a - 0.0638541728F * b,
                s = L - 0.0894841775F * a - 1.2914855480F * b;
    const float l3 = l * l * l, m3 = m * m * m, s3 = s * s * s;
    return {4.0767416621F * l3 - 3.3077115913F * m3 + 0.2309699292F * s3, -1.2684380046F * l3 + 2.6097574011F * m3 - 0.3413193965F * s3,
            -0.0041960863F * l3 - 0.7034186147F * m3 + 1.7076147010F * s3};
}

float smoothstep(float e0, float e1, float x) {
    const float t = std::clamp((x - e0) / (e1 - e0), 0.0F, 1.0F);
    return t * t * (3.0F - 2.0F * t);
}

/** 1 inside [lo, hi] (degrees, may wrap), feathered over 10° outside. */
float bandWeight(float hue, float lo, float hi) {
    const auto wrap = [](float d) {
        d = std::fmod(d, 360.0F);
        return d < 0 ? d + 360.0F : d;
    };
    const float width = wrap(hi - lo), offset = wrap(hue - lo);
    if (offset <= width) return 1.0F;
    const float outside = std::min(offset - width, 360.0F - offset);
    return 1.0F - smoothstep(0.0F, 10.0F, outside);
}

bool in(float v, float lo, float hi) { return std::isfinite(v) && v >= lo && v <= hi; }

}  // namespace

std::optional<TileStore> defringe(const TileStore& layer, const DefringeSpec& s, std::string& error) {
    if (!paint::detail::isRgba(layer.format())) {
        error = "defringe needs an RGBA layer";
        return std::nullopt;
    }
    if (!in(s.purple_amount, 0, 20) || !in(s.green_amount, 0, 20) || !in(s.purple_hue_low, 0, 360) || !in(s.purple_hue_high, 0, 360) ||
        !in(s.green_hue_low, 0, 360) || !in(s.green_hue_high, 0, 360) || !in(s.edge_threshold, 0.005F, 1.0F)) {
        error = "defringe settings are out of range";
        return std::nullopt;
    }
    if (s.purple_amount == 0 && s.green_amount == 0) return layer;
    const bool encoded = layer.format() != PixelFormat::RGBA32F;
    TileStore out(layer.format(), layer.width(), layer.height());
    const auto coordinates = layer.tileCoordinates();
    std::atomic<bool> failed{false};
    parallel::parallelFor(coordinates.size(), parallel::workerCount(s.worker_threads), [&](std::size_t index, std::size_t) {
        if (failed.load()) return;
        const auto coordinate = coordinates[index];
        const develop::detail::Neighbourhood around(layer, coordinate.x, coordinate.y);
        const int bw = static_cast<int>(kTileEdge) + 2 * kBorder;
        const std::int64_t ox = static_cast<std::int64_t>(coordinate.x) * kTileEdge - kBorder, oy = static_cast<std::int64_t>(coordinate.y) * kTileEdge - kBorder;
        std::vector<std::array<float, 3>> lab(static_cast<std::size_t>(bw) * bw);
        std::vector<float> alpha(lab.size());
        for (int y = 0; y < bw; ++y)
            for (int x = 0; x < bw; ++x) {
                float px[4];
                around.read(ox + x, oy + y, px);
                if (encoded)
                    for (int c = 0; c < 3; ++c) px[c] = srgbToLinear(std::clamp(px[c], 0.0F, 1.0F));
                const auto i = static_cast<std::size_t>(y) * bw + x;
                lab[i] = toOklab(px[0], px[1], px[2]);
                alpha[i] = px[3];
            }
        TileBuffer tile(out.tile_byte_size(), 0);
        const auto bpp = out.bytes_per_pixel();
        for (int y = 0; y < static_cast<int>(kTileEdge); ++y)
            for (int x = 0; x < static_cast<int>(kTileEdge); ++x) {
                const auto i = static_cast<std::size_t>(y + kBorder) * bw + static_cast<std::size_t>(x + kBorder);
                auto [L, a, b] = lab[i];
                const float chroma = std::hypot(a, b);
                if (chroma > 1e-4F) {
                    float lo = 1e9F, hi = -1e9F;  // local lightness range over 5×5
                    for (int dy = -kBorder; dy <= kBorder; ++dy)
                        for (int dx = -kBorder; dx <= kBorder; ++dx) {
                            const float l = lab[i + static_cast<std::size_t>(dy * bw + dx)][0];
                            lo = std::min(lo, l);
                            hi = std::max(hi, l);
                        }
                    const float edge = smoothstep(s.edge_threshold, 2.0F * s.edge_threshold, hi - lo);
                    if (edge > 0.0F) {
                        const float hue = std::atan2(b, a) * 180.0F / kPi;
                        const float remove = std::min(1.0F, edge * (s.purple_amount / 20.0F * bandWeight(hue, s.purple_hue_low, s.purple_hue_high) +
                                                                    s.green_amount / 20.0F * bandWeight(hue, s.green_hue_low, s.green_hue_high)));
                        a *= 1.0F - remove;
                        b *= 1.0F - remove;
                    }
                }
                auto rgb = fromOklab(L, a, b);
                float px[4] = {rgb[0], rgb[1], rgb[2], alpha[i]};
                if (encoded)
                    for (int c = 0; c < 3; ++c) px[c] = linearToSrgb(std::max(0.0F, px[c]));
                paint::detail::writePixel(out.format(), tile.data() + (static_cast<std::size_t>(y) * kTileEdge + static_cast<std::size_t>(x)) * bpp, px);
            }
        if (!out.writeTile(coordinate, tile)) failed.store(true);
    });
    if (failed.load()) {
        error = "could not store defringed pixels";
        return std::nullopt;
    }
    return out;
}

}  // namespace croptop::lens
