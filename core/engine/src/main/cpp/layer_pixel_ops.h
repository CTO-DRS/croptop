#pragma once

#include <string>

#include "tile_store.h"

namespace croptop::layer_pixels {

/**
 * Split an RGBA pixel layer by an optional A16 selection mask. `copy` receives the selected
 * coverage and `cut` retains the complementary source alpha. For partial coverage, both outputs
 * retain the same straight RGB while their alpha values receive `sourceAlpha * coverage` and
 * `sourceAlpha * (1 - coverage)`, respectively.
 * RGBA8, RGBA16, and RGBA32F inputs are supported. Both outputs must be independent stores with
 * matching dimensions/format and allocator setup.
 * A null mask means full coverage. Failure may leave outputs partially written; callers must
 * discard both outputs and retain the source unchanged.
 */
bool split(const TileStore& source, TileStore& cut, TileStore& copy, const TileStore* mask, std::string& error);

}  // namespace croptop::layer_pixels
