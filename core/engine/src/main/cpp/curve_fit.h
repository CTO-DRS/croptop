#pragma once

// Turns flattened polygons back into smooth cubic bezier paths (path ops). Clipper2 booleans and offsets
// return polygons; refit() rebuilds curves from them with Schneider's least-squares cubic fit so a united circle stays a few
// cubics instead of hundreds of line segments. Vertices whose turn exceeds `corner_degrees` stay sharp corners; straight
// stretches stay lines. The fitted curve stays within `tolerance` px of every input vertex.

#include <cstdint>

#include "vector_path.h"

namespace croptop::vector {

struct RefitOptions final {
    double tolerance = 0.25;         // px, maximum distance of the input vertices from the fitted curve
    double corner_degrees = 35.0;    // turns sharper than this are corners
    double line_tolerance = 0.05;    // px: a chunk within this of its chord becomes a line
};

/** Re-fits every subpath of `polygons` (as produced by `combine`/`simplify`/`offset`; curves in the input are flattened first). */
Path refit(const Path& polygons, const RefitOptions& options = {});

}  // namespace croptop::vector
