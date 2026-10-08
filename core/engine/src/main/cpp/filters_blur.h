#pragma once

// Blur and sharpen filters, CPU reference/fallback for the GPU kernels.
// All filters work on premultiplied RGBA (so transparent pixels never darken edges), clamp at the layer
// edges (replicate), and blend the result into the layer by the optional A16 mask's coverage; pixels
// outside the mask are untouched. RGBA8/16 are filtered in their stored (sRGB-encoded) values, like
// Photoshop's default; RGBA32F as stored.
//   gaussianBlur: σ = radius (Photoshop's Radius), approximated by three box passes (≤ 3% error), O(1) per
//                 pixel at any radius; the vertical passes stream through small row rings, so memory is a
//                 few MB per worker even on 24 MP.
//   boxBlur:      mean over a (2r+1)² square.
//   unsharpMask:  c + amount·(c − gaussian(c)) where |c − blur| (0–255 scale, per channel) > threshold.
//   highPass:     c − gaussian(c) + 0.5 (alpha kept).
//   motionBlur:   mean along a line of `distance` px through each pixel at `angle` degrees (counter-clockwise
//                 from +x, y up as in Photoshop).
//   radialBlur:   Spin — mean along the arc through each pixel around the centre, spanning amount·0.72°
//                 (100 → 72°); Zoom — mean along the ray towards the centre over amount·0.5 % of the pixel's
//                 distance from it (100 → 50 %). Samples every ~2 px along the path, at most 32.

#include <cstdint>
#include <string>

#include "tile_store.h"

namespace croptop::filters {

bool gaussianBlur(TileStore& layer, float radius, const TileStore* mask, std::string& error, unsigned worker_threads = 0);
bool boxBlur(TileStore& layer, std::uint32_t radius, const TileStore* mask, std::string& error, unsigned worker_threads = 0);
/** amount 0–5 (0–500 %), radius 0.1–1000 px, threshold 0–255 levels. */
bool unsharpMask(TileStore& layer, float amount, float radius, float threshold, const TileStore* mask, std::string& error,
                 unsigned worker_threads = 0);
bool highPass(TileStore& layer, float radius, const TileStore* mask, std::string& error, unsigned worker_threads = 0);
/** angle −360…360°, distance 1–2000 px. */
bool motionBlur(TileStore& layer, float angle, float distance, const TileStore* mask, std::string& error,
                unsigned worker_threads = 0);

enum class RadialMode : std::uint8_t { Spin, Zoom };
/** amount 1–100; centre in document px. */
bool radialBlur(TileStore& layer, RadialMode mode, float amount, double centre_x, double centre_y, const TileStore* mask,
                std::string& error, unsigned worker_threads = 0);

/** Surface blur: radius 1–100 px, threshold 2–255 levels (edge-preserving; ≤ 9 × 9 taps at any radius). */
bool surfaceBlur(TileStore& layer, std::uint32_t radius, float threshold, const TileStore* mask, std::string& error,
                 unsigned worker_threads = 0);

struct TiltShiftSpec final {
    double centre_x = 0.0;  // a point on the focus line, document px
    double centre_y = 0.0;
    float angle = 0.0F;       // focus line direction, degrees counter-clockwise from +x
    float focus = 100.0F;     // half-width of the sharp band, px
    float transition = 200.0F;  // distance over which the blur ramps to full, px
    float blur = 15.0F;       // full blur radius (σ), px
};
/** Tilt-shift: σ(p) = blur × clamp((d − focus) / transition, 0, 1), d = distance to the focus line. */
bool tiltShift(TileStore& layer, const TiltShiftSpec& spec, const TileStore* mask, std::string& error, unsigned worker_threads = 0);

enum class SharpenRemove : std::uint8_t { Gaussian, Lens };
struct SmartSharpenSpec final {
    float amount = 1.0F;        // 0–5
    float radius = 1.0F;        // 0.1–64 px
    float reduce_noise = 0.1F;  // 0–1
    SharpenRemove remove = SharpenRemove::Gaussian;
};
/**
 * Smart sharpen: c + amount·(c − blur) with a Gaussian (or, for Lens, a box ≈ defocus disc) blur; detail
 * below a noise floor (reduce_noise × 8 %) fades quadratically, and results stay within the 3×3
 * neighbourhood's range ±10 % (halo control).
 */
bool smartSharpen(TileStore& layer, const SmartSharpenSpec& spec, const TileStore* mask, std::string& error,
                  unsigned worker_threads = 0);

}  // namespace croptop::filters
