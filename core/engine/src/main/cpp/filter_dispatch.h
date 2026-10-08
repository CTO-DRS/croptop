#pragma once

// One entry point for every pixel filter in filters_blur / filters_noise / filters_distort, addressed by a
// stable numeric id and a flat float parameter list, so a single JNI call (`NativeFiltersBridge`) exposes the whole Filter menu.
// Ids are ABI: never renumber, only append. Parameters per id (all floats; integers must be whole numbers ≥ 0):
//
//   0 GaussianBlur radius              1 BoxBlur radius                  2 UnsharpMask amount, radius, threshold
//   3 HighPass radius                  4 MotionBlur angle, distance      5 RadialBlur mode(0 spin,1 zoom), amount, cx, cy
//   6 SurfaceBlur radius, threshold    7 TiltShift cx, cy, angle, focus, transition, blur
//   8 SmartSharpen amount, radius, reduceNoise, remove(0 gaussian,1 lens)
//   9 Twirl angle   10 Pinch amount   11 Spherize amount   12 Wave wavelength, amplitude, horizontal(0/1), vertical(0/1)
//  13 Ripple amount, size(0..2)       14 PolarCoordinates mode(0 rect→polar,1 polar→rect)
//  15 Displace hScale, vScale, tile(0/1)   [needs `map`]    16 Emboss angle, height, amount    17 FindEdges (none)
//  18 GlowingEdges width, brightness, smoothness            19 OilPaint stylization, cleanliness
//  20 DiffuseGlow graininess, glow, clear   21 Vignette amount, midpoint, feather
//  22 Clouds fgR,fgG,fgB,fgA, bgR,bgG,bgB,bgA              23 ChromaticAberration redShift, blueShift
//  24 AddNoise amount, distribution(0 uniform,1 gaussian), monochromatic(0/1)   25 FilmGrain intensity, size
//  26 Median radius   27 DustAndScratches radius, threshold   28 ReduceNoise strength, preserveDetails
//  29 Mosaic cell     30 Crystallize cell                     31 ColorHalftone maxRadius, angleR, angleG, angleB
//  32 LensFlare centerX, centerY (0–1), brightness (0.1–3), lensType (0–3)
//
// Ids 20, 22, 24, 25, 30 use `seed`. Every filter takes an optional A16 selection/layer mask and works in place.

#include <cstdint>
#include <span>
#include <string>

#include "tile_store.h"

namespace croptop::filters {

inline constexpr int kFilterCount = 33;

/** Number of parameters `id` expects, or -1 for an unknown id. */
int filterParameterCount(int id);

/** In place. `map` is only used by Displace. false with `error` for an unknown id, a wrong parameter count, non-finite or non-integral values, or a filter failure. */
bool applyFilter(TileStore& layer, int id, std::span<const float> params, const TileStore* map, const TileStore* mask, std::uint32_t seed,
                 unsigned worker_threads, std::string& error);

}  // namespace croptop::filters
