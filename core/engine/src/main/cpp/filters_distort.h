#pragma once

// Distort, stylize and render filters, CPU reference/fallback. Same conventions as
// filters_blur.h (RGBA8/16/32F, premultiplied resampling, edge replicate, optional A16 mask blend).
// Distortions are inverse maps: each output pixel p samples the layer bilinearly at q(p). The effect centre
// is the layer's centre and R = min(width, height) / 2 unless stated.
//   twirl:      q = c + rot(p − c, −angle·(1 − r/R)²) for r < R (angle −999…999°).
//   pinch:      q = c + (p − c)·((1 − a) + a·r/R), a = −amount/200 (amount −100…100; + pulls inwards).
//   spherize:   q = c + (p − c)·(1 + |k|·(g − 1)), k = amount/100, u = r/R < 1: bulge (k > 0)
//               g = asin(u)/(u·π/2) (centre magnified 1/(2/π)); pinch-in (k < 0) g = sin(u·π/2)/u. Both meet
//               the identity at R.
//   wave:       q = p + (A·sin(2π·y/λ), A·sin(2π·x/λ)) — `horizontal`/`vertical` pick the axes.
//   ripple:     wave with λ = 8/16/32 px (small/medium/large) and A = amount/100 · λ/4 (amount −999…999).
//   polar:      RectangularToPolar maps the full layer onto the ellipse inscribed in it (top = angle 0,
//               clockwise; the layer's bottom row is the centre); PolarToRectangular is its inverse.
//   displace:   q = p + (R − 0.5, G − 0.5)·2·scale·128/100 px from the map (RGBA, stretched to the layer or
//               tiled), scale −999…999 %.
//   emboss:     0.5 + amount·(c(p + h·u) − c(p − h·u)) per channel, u = the light direction (angle°, y up).
//   findEdges:  1 − |Sobel| per channel (white background, coloured edges).
//   glowingEdges: Sobel magnitude of the image box-smoothed by `smoothness`, widened by `width` and scaled by
//               brightness/10, on black.
//   oilPaint:   within radius r = 1 + cleanliness, the most common of 20 intensity levels wins and its mean
//               colour is taken (stylization scales the level count); cleanliness 0–10.
//   diffuseGlow: highlights above (1 − clear/20) are blurred (σ = 2 + glow) and added with glow/10 strength,
//               plus `graininess`/10 of seeded noise.
//   vignette:   c·(1 − amount·f) (amount > 0 darkens, < 0 lightens towards white), f = smoothstep(midpoint,
//               midpoint + feather, d) with d the elliptical distance from the centre (1 at the corners).
//   clouds:     8-octave value-noise fBm between `foreground` and `background` (replaces pixels, opaque).
//   chromaticAberration: red and blue sampled at c + (p − c)·(1 + shift/r_max) (shift in px at the corners).

#include <array>
#include <cstdint>
#include <string>

#include "tile_store.h"

namespace croptop::filters {

bool twirl(TileStore& layer, float angle, const TileStore* mask, std::string& error, unsigned worker_threads = 0);
bool pinch(TileStore& layer, float amount, const TileStore* mask, std::string& error, unsigned worker_threads = 0);
bool spherize(TileStore& layer, float amount, const TileStore* mask, std::string& error, unsigned worker_threads = 0);
/** wavelength 2–999 px, amplitude 0–999 px. */
bool wave(TileStore& layer, float wavelength, float amplitude, bool horizontal, bool vertical, const TileStore* mask,
          std::string& error, unsigned worker_threads = 0);
enum class RippleSize : std::uint8_t { Small, Medium, Large };
bool ripple(TileStore& layer, float amount, RippleSize size, const TileStore* mask, std::string& error, unsigned worker_threads = 0);
enum class PolarMode : std::uint8_t { RectangularToPolar, PolarToRectangular };
bool polarCoordinates(TileStore& layer, PolarMode mode, const TileStore* mask, std::string& error, unsigned worker_threads = 0);
bool displace(TileStore& layer, const TileStore& map, float horizontal_scale, float vertical_scale, bool tile_map,
              const TileStore* mask, std::string& error, unsigned worker_threads = 0);

/** angle −360…360°, height 1–10 px, amount 0.01–5. */
bool emboss(TileStore& layer, float angle, float height, float amount, const TileStore* mask, std::string& error,
            unsigned worker_threads = 0);
bool findEdges(TileStore& layer, const TileStore* mask, std::string& error, unsigned worker_threads = 0);
/** width 1–14, brightness 0–20, smoothness 1–15. */
bool glowingEdges(TileStore& layer, int width, float brightness, int smoothness, const TileStore* mask, std::string& error,
                  unsigned worker_threads = 0);
/** stylization 0.1–10, cleanliness 0–10. */
bool oilPaint(TileStore& layer, float stylization, float cleanliness, const TileStore* mask, std::string& error,
              unsigned worker_threads = 0);
/** graininess 0–10, glow 0–20, clear 0–20. */
bool diffuseGlow(TileStore& layer, float graininess, float glow, float clear, std::uint32_t seed, const TileStore* mask,
                 std::string& error, unsigned worker_threads = 0);

/** amount −1…1, midpoint 0–1, feather 0.01–1. */
bool vignette(TileStore& layer, float amount, float midpoint, float feather, const TileStore* mask, std::string& error,
              unsigned worker_threads = 0);
/** Colours are straight RGBA 0–1. */
bool clouds(TileStore& layer, std::array<float, 4> foreground, std::array<float, 4> background, std::uint32_t seed,
            const TileStore* mask, std::string& error, unsigned worker_threads = 0);
/**
 * Screen-blends a lens flare onto the layer: a glow at the light (`center_x`/`center_y`, 0–1 of the width/height) and ghosts, discs and
 * rings along the axis through the picture's centre. `brightness` 0.1–3; `lens_type` 0 zoom 50–300 mm, 1 prime 35 mm, 2 prime 105 mm,
 * 3 movie prime (adds a horizontal streak). Alpha is unchanged.
 */
bool lensFlare(TileStore& layer, float center_x, float center_y, float brightness, int lens_type, const TileStore* mask, std::string& error,
               unsigned worker_threads = 0);
/** shifts −100…100 px at the corners. */
bool chromaticAberration(TileStore& layer, float red_shift, float blue_shift, const TileStore* mask, std::string& error,
                         unsigned worker_threads = 0);

}  // namespace croptop::filters
