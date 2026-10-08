#pragma once

// Native side of persistent vector paths and shape layers. Value types mirror core:model's VectorPaths.kt (PathAnchor, VectorSubpath, ShapeToolOptions):
// anchors carry *relative* in/out handles, a record owns ordered subpaths plus fill/stroke style and the
// optional primitive it came from. The path geometry is the source of truth; primitives only seed it (and
// keep live handles editable). Rendering fills a rebuildable RGBA cache; manifest, history and JNI stay
// in Kotlin.

#include <array>
#include <cstdint>
#include <optional>
#include <span>
#include <string>
#include <vector>

#include "tile_store.h"
#include "vector_path.h"

namespace croptop::shape {

enum class AnchorMode : std::uint8_t { Corner, Smooth, Symmetric };

struct Anchor final {
    std::int64_t id = 0;
    vector::Point point;
    std::optional<vector::Point> incoming, outgoing;  // relative to `point`
    AnchorMode mode = AnchorMode::Corner;
};

struct Subpath final {
    std::vector<Anchor> anchors;
    bool closed = false;
};

enum class PrimitiveKind : std::uint8_t { Rectangle, RoundedRectangle, Ellipse, Polygon, Star, Line };

struct Primitive final {
    PrimitiveKind kind = PrimitiveKind::Rectangle;
    double x = 0, y = 0, width = 0, height = 0;  // bounding box (Line: from (x, y) to (x + width, y + height))
    vector::CornerRadii radii;                     // RoundedRectangle
    int sides = 5;                                 // Polygon / Star (3–64)
    double inner_ratio = 0.5;                      // Star
    double rotation = 0;                           // degrees, about the box centre
};

using Color = std::array<float, 4>;  // straight-alpha sRGB, 0–1

struct Fill final {
    bool enabled = true;
    Color color{0, 0, 0, 0};
};

struct Stroke final {
    bool enabled = true;
    Color color{0, 0, 0, 1};
    vector::StrokeStyle style;
    bool arrow_start = false, arrow_end = false;  // Line primitives
    double arrow_length = 4, arrow_width = 3;     // in multiples of the stroke width
};

struct ShapeRecord final {
    std::int64_t id = 0;
    std::vector<Subpath> subpaths;
    vector::FillRule fill_rule = vector::FillRule::NonZero;
    Fill fill;
    Stroke stroke;
    std::optional<Primitive> primitive;  // present while the geometry still equals it
};

/**
 * Editable anchors for a primitive (curves as cubic handles: rounded corners and ellipses use the
 * 4/3·tan(θ/4) circle approximation). Anchor ids are `first_id`, `first_id + 1`, …
 */
std::vector<Subpath> primitiveAnchors(const Primitive& primitive, std::int64_t first_id = 1);

/** A new record from a primitive: geometry from `primitiveAnchors`, `primitive` retained. */
ShapeRecord fromPrimitive(std::int64_t id, const Primitive& primitive, const Fill& fill, const Stroke& stroke, std::int64_t first_anchor_id = 1);

/** The record's geometry as a path (segments are cubic where either end has a handle, else straight). */
vector::Path toPath(const ShapeRecord& record);

struct Bounds final {
    double x0 = 0, y0 = 0, x1 = 0, y1 = 0;
    bool empty = true;
};
/** Geometry bounds (control points excluded: tight on curves), optionally grown by the stroke. */
Bounds bounds(const ShapeRecord& record, bool include_stroke);

/** Validates a record (finite geometry, sane sizes); false with `error`. */
bool validate(const ShapeRecord& record, std::string& error);

/** Draws the records in order (fill, then stroke; straight-alpha source-over) onto an RGBA layer. */
bool render(std::span<const ShapeRecord> records, TileStore& target, std::string& error);

enum class HitPart : std::uint8_t { None, IncomingHandle, OutgoingHandle, Anchor, Segment, Interior };

struct Hit final {
    HitPart part = HitPart::None;
    std::size_t record = 0, subpath = 0, anchor = 0;  // Segment: from `anchor` to the next one
    double distance = 0;
};

/**
 * Topmost hit at `p` within `tolerance` px. Priority: handle ends, anchors, segments (distance to the
 * flattened curve, or to the stroke edge), then filled interiors (by the record's fill rule). Later
 * records are on top.
 */
Hit hitTest(std::span<const ShapeRecord> records, vector::Point p, double tolerance);

}  // namespace croptop::shape
