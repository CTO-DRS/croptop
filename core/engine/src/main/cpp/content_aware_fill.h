#pragma once

// Content-Aware Fill: multi-scale PatchMatch image completion (Wexler et al.
// style) over the hole's neighbourhood.
//
// The hole is the A16 `hole` mask (coverage > 50% is unknown); sources are 7×7 patches lying entirely
// in known pixels that the optional A16 `sampling` mask allows (default: everything outside the hole).
// Work happens on the hole's bounds plus a sampling margin (`margin` px, default half the hole's larger
// side, at least 64), across a pyramid down to a ~16 px hole. The coarsest hole is seeded by onion-peel diffusion; each
// level alternates PatchMatch (propagation + random search, SSD with early exit) and voting (mean of the
// overlapping patches; the final pass takes each pixel from its best-matching patch so grain stays sharp),
// and upsamples its nearest-neighbour field to the next level. Colours are averaged in the stored
// encoding (RGBA, so transparent areas fill with transparency). The result replaces hole pixels,
// blended by the hole mask's coverage; everything else is untouched. Colour adaptation (default on)
// solves a Poisson equation per level so the fill keeps its own texture gradients while matching the
// hole's surroundings (smooth gradients continue across the hole instead of stepping).

#include <cstdint>
#include <functional>
#include <optional>
#include <string>

#include "tile_store.h"

namespace croptop::paint {

/** Photoshop's rotation adaptation: patches may be rotated within ±15° (Low), ±45° (Medium), ±90° (High) or
 * freely (Full, 15° steps); None matches unrotated patches only (fastest). */
enum class RotationAdaptation : std::uint8_t { None, Low, Medium, High, Full };

struct ContentAwareFillSpec final {
    /** Extra context around the hole in px; 0 = half the hole's larger side (at least 64). */
    std::uint32_t margin = 0;
    /** PatchMatch iterations at the coarsest level; finer levels use fewer. */
    int iterations = 6;
    std::uint32_t seed = 1;
    /** Poisson colour adaptation: keeps the copied texture but matches the hole's surroundings. */
    bool color_adaptation = true;
    RotationAdaptation rotation_adaptation = RotationAdaptation::None;
    /** Also match horizontally mirrored patches (Photoshop's Mirror option; combine with rotation for vertical). */
    bool mirror_adaptation = false;
    unsigned worker_threads = 0;
    /** Progress in [0, 1]; return false to cancel (the call then returns nullopt, "cancelled"). */
    std::function<bool(float)> progress;
    std::uint64_t max_working_bytes = 1024ULL * 1024ULL * 1024ULL;
};

std::optional<TileStore> contentAwareFill(const TileStore& image, const TileStore& hole, const TileStore* sampling,
                                          const ContentAwareFillSpec& spec, std::string& error);

}  // namespace croptop::paint
