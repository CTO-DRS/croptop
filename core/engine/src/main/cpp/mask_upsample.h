#pragma once

// Guided mask upsampling: a segmentation model returns a soft mask at a fixed low
// resolution (e.g. 512²); this turns it into a full-resolution A16 mask that follows the image's edges.
//
// Fast guided filter (He & Sun): luminance of the full image is area-averaged to the mask's size, the
// linear model mask ≈ a·I + b is solved per window there, and a, b are bilinearly upsampled and applied to
// the full-resolution luminance. Cost is one full-resolution pass plus low-resolution work, so a 24 MP image
// needs only the luminance plane (4 bytes/pixel) in memory. The result is meant to feed refine() afterwards.

#include <cstdint>
#include <optional>
#include <string>

#include "tile_store.h"

namespace croptop::selection {

struct GuidedUpsampleSpec final {
    std::uint32_t radius = 4;   // window radius at the mask's resolution, 1–64; 0 resizes the mask without the guide
    float epsilon = 1e-3F;      // edge threshold on luminance variance (0–1 scale), > 0
};

/** `guide` is RGBA8/16/32F at full size; `low_mask` is A16 of any smaller-or-equal size. Returns A16 of the guide's size. */
std::optional<TileStore> guidedUpsample(const TileStore& guide, const TileStore& low_mask, const GuidedUpsampleSpec& spec,
                                        std::string& error);

}  // namespace croptop::selection
