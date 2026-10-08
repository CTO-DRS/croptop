#pragma once

// Select and Mask / Refine Edge.
//
// refine() improves an A16 selection along its boundary using the image as a guide:
//   1. Edge band: pixels within `radius` of the selection boundary (exact Euclidean distance to the
//      50% contour). With `smart_radius`, the band narrows where the image has a strong edge
//      (width × (0.25 + 0.75 × (1 − normalized luminance gradient))), keeping crisp outlines crisp.
//   2. Inside the band, colour-projection matting: foreground colour is sampled from the selection's
//      interior at least 3 px from its edge, background colour from outside the band; their local means F
//      and B (window 2 × radius) define a colour line
//      and each band pixel's alpha is its projection α = (I − B)·(F − B)/|F − B|² (kept as-is where F and
//      B are too similar). A small guided filter (He et al., window 2, ε = 1e-4) on luminance then cleans
//      noise while keeping thin structure (hair, fur). Outside the band the original mask is kept.
//   3. smooth (0–100): Gaussian-like blur with σ = smooth / 20 px; feather (0–250 px): σ = feather / 2;
//      contrast (0–100 %): steepens transitions around 50% (×1 at 0, ×10 at 100%);
//      shift_edge (−100…100 %): gamma curve m^γ with γ = 1/(1 + s/50) for s > 0 (grow), 1 − s/50 for
//      s < 0 (shrink); 0 and 1 stay fixed.
// decontaminate() replaces colours in the refined edge band with an estimate of the foreground colour
// F = (I − (1 − α)·B) / α, where B and a fallback F are local means of confidently-background and
// confidently-foreground pixels; `amount` (0–1) blends original → decontaminated.
//
// For interactive preview, callers refine a proxy (resample::resize of guide and mask to the fit-zoom
// size) and refine at full resolution on commit.

#include <cstdint>
#include <optional>
#include <string>

#include "tile_store.h"

namespace croptop::selection {

struct RefineEdgeSpec final {
    float radius = 10.0F;       // 0–250 px
    bool smart_radius = false;
    float smooth = 0.0F;        // 0–100
    float feather = 0.0F;       // 0–250 px
    float contrast = 0.0F;      // 0–100 %
    float shift_edge = 0.0F;    // −100…100 %
    /** Peak working memory for the processed region. */
    std::uint64_t max_working_bytes = 768ULL * 1024ULL * 1024ULL;
};

/** `guide` is RGBA8/16/32F of the mask's size; `mask` is A16. Returns the refined A16 mask. */
std::optional<TileStore> refine(const TileStore& guide, const TileStore& mask, const RefineEdgeSpec& spec,
                                std::string& error);

/** Decontaminated copy of `image` (same format) for the soft pixels of `refined_mask`; amount 0–1. */
std::optional<TileStore> decontaminate(const TileStore& image, const TileStore& refined_mask, float amount,
                                       std::string& error);

}  // namespace croptop::selection
