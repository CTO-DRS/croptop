#pragma once

// Apply a rendered per-mask adjustment to the document pixels.

#include <optional>
#include <string>

#include "tile_store.h"

namespace croptop::develop::mask_blend {

/**
 * Mixes the adjusted result over the original RGB channels with factor = mask coverage × opacity.
 * The original alpha channel is preserved byte-for-byte; adjusted alpha is ignored. Integer formats
 * interpolate their normalized stored channel values, while RGBA32F remains in its float channel
 * domain. Inputs are immutable: the returned store is a COW copy of `base`, and tiles outside the
 * nonzero mask are shared. `base` and `adjusted` may refer to the same store; no input is mutated.
 */
std::optional<TileStore> apply(const TileStore& base, const TileStore& adjusted, const TileStore& mask, float opacity,
                               std::string& error);

}  // namespace croptop::develop::mask_blend
