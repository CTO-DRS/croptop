#pragma once

// Multi-image merges (HDR merge, focus stack): translation alignment with
// Ward's median-threshold bitmaps (exposure-invariant, pyramid search; the gray is lightly smoothed and
// the threshold percentile is chosen per pair so wide brackets with clipped halves still align), HDR merge
// of bracketed exposures into scene-linear radiance, and focus stacking by local sharpness.
//   HDR: integer layers are sRGB-decoded, 32F layers are taken as linear; radiance = Σ w·(pixel ·
//        e_ref / exposure) / Σ w (e_ref: the middle exposure, which sets the output scale) with a hat weight that distrusts clipped and near-black samples. Output is RGBA32F radiance
//        (tone-map it with develop::applyTone).
//   Focus: per pixel, each frame is weighted by its local Laplacian energy (sharpness), the weights are
//        smoothed so seams stay soft, and the frames are blended. Output keeps the frames' format.

#include <functional>
#include <optional>
#include <span>
#include <string>

#include "tile_store.h"

namespace croptop::stack {

struct Offset final {
    int dx = 0, dy = 0;  // shift that aligns a frame to the reference (sample the frame at x − dx, y − dy)
};

/** Translation that best aligns `frame` to `reference` (MTB, pyramid up to ±2^levels px). */
Offset alignMtb(const TileStore& reference, const TileStore& frame, int levels = 6);

struct HdrFrame final {
    const TileStore* image = nullptr;
    float exposure = 1.0F;  // relative exposure (e.g. 2^EV, or shutter time × ISO); > 0
};

struct MergeOptions final {
    bool align = true;
    unsigned worker_threads = 0;
    std::function<bool(float)> progress;
};

std::optional<TileStore> mergeHdr(std::span<const HdrFrame> frames, const MergeOptions& options, std::string& error);

struct FocusOptions final {
    bool align = true;
    float smoothing = 4.0F;  // weight-map blur (px): larger = softer transitions
    unsigned worker_threads = 0;
    std::function<bool(float)> progress;
};

std::optional<TileStore> focusStack(std::span<const TileStore* const> frames, const FocusOptions& options, std::string& error);

}  // namespace croptop::stack
