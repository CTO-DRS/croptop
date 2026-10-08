#pragma once

// Geometric path booleans and offsetting on vector::Path ("path ops"), through Clipper2. Paths are
// flattened first (curves within `tolerance` px), so results are polygons: corner anchors only, closed, and to be filled
// with FillRule::NonZero (outer contours wind opposite to holes). Open subpaths are treated as closed for filling.

#include <optional>
#include <string>

#include "vector_path.h"

namespace croptop::vector {

enum class BoolOp : std::uint8_t {
    Unite,      // subject ∪ clip
    Subtract,   // subject − clip ("Subtract Front Shape")
    Intersect,  // subject ∩ clip
    Exclude,    // subject xor clip ("Exclude Overlapping Shapes")
};

/** Combines two paths, each interpreted with its own fill rule. nullopt with `error` for non-finite coordinates. */
std::optional<Path> combine(const Path& subject, FillRule subject_rule, const Path& clip, FillRule clip_rule, BoolOp op,
                            std::string& error, double tolerance = 0.1);

/** Merges every contour of `path` under `rule` into a clean outline (overlaps resolved, holes kept). */
std::optional<Path> simplify(const Path& path, FillRule rule, std::string& error, double tolerance = 0.1);

/** Grows (delta > 0) or shrinks (delta < 0) the filled area by `delta` px, joining corners like a stroke would. */
std::optional<Path> offset(const Path& path, FillRule rule, double delta, Join join, double miter_limit, std::string& error,
                           double tolerance = 0.1);

/** Net signed area of all contours (outer positive, holes negative under NonZero output); for tests and sizing. */
double netArea(const Path& path, double tolerance = 0.1);

}  // namespace croptop::vector
