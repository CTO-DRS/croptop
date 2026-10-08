#pragma once

// Noise and pixelate filters, CPU reference/fallback. Like filters_blur.h: RGBA8/16/32F
// layers, premultiplied where colours are averaged, edge replicate, results blended by the optional A16 mask.
// Random filters are deterministic for a given seed (a hash of position, channel and seed), so previews,
// tiles and the final render agree.
//   addNoise:        adds U(−a, a) (Uniform) or N(0, a/2) (Gaussian) with a = amount (0–4 → 0–400 % of full scale
//                    /4, Photoshop's Amount/400); `monochromatic` uses one value for R, G and B.
//   filmGrain:       luminance-only value noise of cell `size` px (bilinear between cells), scaled by `intensity`
//                    and weighted towards midtones (0.25 + 3L(1 − L)).
//   median:          per-channel median of the (2r+1)² square (sliding 256-bin histograms; 16-bit/float values
//                    are binned to 256 levels and refined linearly inside the bin).
//   dustAndScratches: the median where it differs from the pixel by more than `threshold` levels (0–255).
//   reduceNoise:     bilateral smoothing (radius 3, range 0.02·strength) of colour; `preserve_details` (0–1) keeps
//                    that share of the removed high-frequency detail.
//   mosaic:          each `cell` × `cell` block (aligned to the document origin) becomes its mean.
//   crystallize:     Voronoi cells around one jittered seed per `cell` grid square; each cell takes the colour
//                    under its seed.
//   colorHalftone:   per channel, a dot grid of pitch 2·max_radius rotated by that channel's angle; the dot's
//                    area follows the channel's value at the dot centre (1 px anti-aliased edge).

#include <array>
#include <cstdint>
#include <string>

#include "tile_store.h"

namespace croptop::filters {

enum class NoiseDistribution : std::uint8_t { Uniform, Gaussian };

bool addNoise(TileStore& layer, float amount, NoiseDistribution distribution, bool monochromatic, std::uint32_t seed,
              const TileStore* mask, std::string& error, unsigned worker_threads = 0);
/** intensity 0–1, size 1–64 px. */
bool filmGrain(TileStore& layer, float intensity, float size, std::uint32_t seed, const TileStore* mask, std::string& error,
               unsigned worker_threads = 0);
/** radius 1–100 px. */
bool median(TileStore& layer, std::uint32_t radius, const TileStore* mask, std::string& error, unsigned worker_threads = 0);
bool dustAndScratches(TileStore& layer, std::uint32_t radius, float threshold, const TileStore* mask, std::string& error,
                      unsigned worker_threads = 0);
/** strength 0–10, preserve_details 0–1. */
bool reduceNoise(TileStore& layer, float strength, float preserve_details, const TileStore* mask, std::string& error,
                 unsigned worker_threads = 0);
/** cell 2–200 px. */
bool mosaic(TileStore& layer, std::uint32_t cell, const TileStore* mask, std::string& error, unsigned worker_threads = 0);
/** cell 3–300 px. */
bool crystallize(TileStore& layer, std::uint32_t cell, std::uint32_t seed, const TileStore* mask, std::string& error,
                 unsigned worker_threads = 0);
/** max_radius 4–127 px; angles in degrees for R, G, B (Photoshop defaults 108, 162, 90). */
bool colorHalftone(TileStore& layer, std::uint32_t max_radius, std::array<float, 3> angles, const TileStore* mask,
                   std::string& error, unsigned worker_threads = 0);

}  // namespace croptop::filters
