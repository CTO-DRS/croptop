#pragma once

// Select ▸ Transform Selection: applies a 3×3 affine or projective matrix
// (row-major, maps document points (x, y, 1) like core:model Matrix3) to an A16 selection mask.
// Each output pixel center is mapped back through the inverse and the source coverage is sampled
// bilinearly (pixel centers at +0.5), so transformed edges stay anti-aliased. The result is clipped to
// the canvas. Rejected: singular matrices, and projective matrices that send part of the selection's
// bounds through the horizon (w <= 0).

#include <array>
#include <optional>
#include <string>

#include "tile_store.h"

namespace croptop::selection {

std::optional<TileStore> transform(const TileStore& selection, const std::array<double, 9>& matrix, std::string& error,
                                   unsigned worker_threads = 0);

}  // namespace croptop::selection
