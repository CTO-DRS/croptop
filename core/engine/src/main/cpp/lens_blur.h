#pragma once

// Lens Blur (the part that needs no AI): depth-of-field blur driven by any depth map
// (a layer mask or channel today, the AI depth output later), with Photoshop's controls.
//   Depth → signed circle of confusion: coc = radius · (d − focal) / max(focal, 1 − focal), d = 0 near … 1
//   far (Invert flips it). Negative = in front of the focal plane.
//   Rendering is layered: pixels are split into CoC bins (tent membership, so there is no banding), each
//   bin is blurred with the iris-shaped kernel of its radius (row-span prefix sums; large radii run on a
//   reduced copy and are upsampled), and the bins are composited far → near in premultiplied linear light.
//   Near out-of-focus content therefore spreads over sharp content behind it, while far blur never bleeds
//   onto sharp content in front. Colour and alpha are renormalised by the composited coverage, so opaque
//   layers stay opaque and no dark halos appear where occluded background is missing.
//   Specular highlights: bright pixels (above the threshold) are boosted in linear light before blurring,
//   which gives bokeh balls their punch. Noise is added last.
//   Cost grows with the radius (each tile reads a border of about the radius): 12 MP takes roughly 0.5–1.4 s
//   on 8 desktop cores, so interactive previews should run on a screen-size proxy with the radius scaled.

#include <cstdint>
#include <functional>
#include <optional>
#include <string>

#include "tile_store.h"

namespace croptop::lensblur {

struct Iris final {
    int blades = 6;               // 0 = circle, else 3–8
    float rotation_degrees = 0;   // blade rotation
    float curvature = 0;          // 0 = straight blades … 1 = round
};

struct LensBlurSpec final {
    float radius = 15;               // largest blur radius, 0–100 px
    float focal_distance = 0;        // depth value that stays sharp, 0–1
    bool invert_depth = false;
    Iris iris;
    float specular_brightness = 0;   // 0–1 (boost up to 16× in linear light)
    float specular_threshold = 0.9F; // 0–1 display-referred luminance where the boost starts
    float noise = 0;                 // 0–1 (up to ±10 % display-referred)
    bool monochromatic_noise = true;
    std::uint32_t seed = 1;
    unsigned worker_threads = 0;
    std::function<bool(float)> progress;
};

/**
 * Blurs an RGBA layer. `depth` is an A8/A16 mask or an RGBA layer (its luminance) of the layer's size; null
 * means every pixel is at the far end (a plain bokeh blur of `radius` when `focal_distance` is 0).
 */
std::optional<TileStore> lensBlur(const TileStore& layer, const TileStore* depth, const LensBlurSpec& spec, std::string& error);

/** An A16 mask selecting depths in [near, far] (0–1), falling off smoothly over `feather` on both sides. */
std::optional<TileStore> depthRangeMask(const TileStore& depth, float near, float far, float feather, std::string& error);

}  // namespace croptop::lensblur
