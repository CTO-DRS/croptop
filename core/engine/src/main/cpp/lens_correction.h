#pragma once

// Lens Correction filter, manual controls (lensfun profiles come with).
// One inverse map and one resample per pixel. Coordinates are normalised to the half-diagonal D around the
// layer centre (u, v in about −0.7…0.7 at the edges). For each output pixel:
//   1. scale:        u /= scale (0.5–1.5)
//   2. angle:        rotate by −angle (degrees, counter-clockwise positive, y up)
//   3. perspective:  (u, v) /= 1 + a·u + b·v, a = horizontal/200, b = vertical/200 (each −100…100)
//   4. distortion:   (u, v) ·= 1 + k·r², k = −distortion/200 (−100…100; + removes barrel distortion)
//   5. chromatic aberration: red and blue are sampled at (u, v)·(1 + s/20000) with s = red_cyan / blue_yellow
//                    (−100…100, ±0.5 % at 100), green at (u, v)
//   6. vignette:     colour ·= 1 + amount/100 · t², t = clamp((r/r_corner − m)/(1 − m), 0, 1), m = midpoint/100
// Points that land outside the layer are transparent, or clamp to its edge with `edge_extend`.

#include <string>

#include "tile_store.h"

namespace croptop::filters {

struct LensCorrectionSpec final {
    float distortion = 0.0F;
    float red_cyan = 0.0F;
    float blue_yellow = 0.0F;
    float vignette_amount = 0.0F;
    float vignette_midpoint = 50.0F;
    float vertical_perspective = 0.0F;
    float horizontal_perspective = 0.0F;
    float angle = 0.0F;
    float scale = 1.0F;
    bool edge_extend = false;
};

bool lensCorrection(TileStore& layer, const LensCorrectionSpec& spec, const TileStore* mask, std::string& error,
                    unsigned worker_threads = 0);

}  // namespace croptop::filters
