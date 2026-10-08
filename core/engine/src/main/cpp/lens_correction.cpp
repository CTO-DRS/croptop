#include "lens_correction.h"

#include <cmath>

#include "filters_internal.h"

namespace croptop::filters {

using namespace detail;

bool lensCorrection(TileStore& layer, const LensCorrectionSpec& spec, const TileStore* mask, std::string& error, unsigned worker_threads) {
    const auto in = [](float v, float lo, float hi) { return std::isfinite(v) && v >= lo && v <= hi; };
    if (!in(spec.distortion, -100, 100) || !in(spec.red_cyan, -100, 100) || !in(spec.blue_yellow, -100, 100) ||
        !in(spec.vignette_amount, -100, 100) || !in(spec.vignette_midpoint, 0, 100) || !in(spec.vertical_perspective, -100, 100) ||
        !in(spec.horizontal_perspective, -100, 100) || !in(spec.angle, -180, 180) || !in(spec.scale, 0.5F, 1.5F)) {
        error = "lens correction values are out of range";
        return false;
    }
    const double w = layer.width(), h = layer.height(), cx = w / 2.0, cy = h / 2.0;
    const double d = std::hypot(cx, cy);
    const double corner = 1.0;  // r at the corners in half-diagonal units
    const double angle = spec.angle * 3.14159265358979323846 / 180.0;
    const double ca = std::cos(angle), sa = std::sin(angle);
    const double a = spec.horizontal_perspective / 200.0, b = spec.vertical_perspective / 200.0;
    const double k = -spec.distortion / 200.0;
    const double red_scale = 1.0 + spec.red_cyan / 20000.0, blue_scale = 1.0 + spec.blue_yellow / 20000.0;
    const double midpoint = spec.vignette_midpoint / 100.0;
    const bool chromatic = spec.red_cyan != 0.0F || spec.blue_yellow != 0.0F;
    return pointwise(
        layer, mask, -1,
        [&](Taps& taps, int x, int y) {
            // Output pixel in normalised coordinates, then steps 1–4 towards the source.
            double u = (x + 0.5 - cx) / d / spec.scale, v = (y + 0.5 - cy) / d / spec.scale;
            // y is down in pixels; a counter-clockwise (y up) correction rotates the sampling point clockwise.
            const double ru = ca * u - sa * v, rv = sa * u + ca * v;
            u = ru, v = rv;
            const double denominator = 1.0 + a * u + b * v;
            if (denominator <= 1e-6) return Pixel{};
            u /= denominator, v /= denominator;
            const double r2 = u * u + v * v;
            const double radial = 1.0 + k * r2;
            u *= radial, v *= radial;
            const auto sample = [&](double scale) -> Pixel {
                const double sx = cx + u * scale * d, sy = cy + v * scale * d;
                if (!spec.edge_extend && (sx < 0.0 || sy < 0.0 || sx > w || sy > h)) return Pixel{};
                return taps.layer.bilinear(sx, sy);
            };
            Pixel out = sample(1.0);
            if (chromatic) {
                // Swap in red and blue from their own radial scales (premultiplied, so carry their alpha ratio).
                const auto red = unpremultiply(sample(red_scale)), blue = unpremultiply(sample(blue_scale));
                auto straight = unpremultiply(out);
                straight[0] = red[0];
                straight[2] = blue[2];
                for (std::size_t c = 0; c < 3; ++c) out[c] = straight[c] * out[3];
            }
            if (spec.vignette_amount != 0.0F) {
                const double r = std::hypot((x + 0.5 - cx) / d, (y + 0.5 - cy) / d) / corner;
                const double t = midpoint >= 1.0 ? 0.0 : std::clamp((r - midpoint) / (1.0 - midpoint), 0.0, 1.0);
                const auto gain = static_cast<float>(1.0 + spec.vignette_amount / 100.0 * t * t);
                for (std::size_t c = 0; c < 3; ++c) out[c] *= gain;
            }
            return out;
        },
        error, worker_threads);
}

}  // namespace croptop::filters
