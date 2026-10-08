#pragma once

// Vector engine geometry + rasterisation core, without a clipping library.
//   Path: move/line/quad/cubic/close; flatten() subdivides curves until they are within `tolerance` px of
//     their chords.
//   Shapes: rectangles with per-corner ("live") radii, ellipses, regular polygons and stars, and lines with
//     arrowheads.
//   Stroke: every segment quad, join (miter within the limit, else bevel; round; bevel) and cap (butt, round,
//     square) is emitted as its own positively-oriented polygon, so a nonzero fill of the pieces is their exact
//     union; dashes split the flattened path by arc length; Inside/Outside alignment (closed paths) strokes at
//     twice the width and keeps the part inside/outside the fill.
//   Rasterise: 16 sub-scanlines per pixel row, exact horizontal span coverage from each sub-scanline's winding
//     numbers (nonzero or even-odd) → anti-aliased A16 coverage, canvas-sized and sparse.
// Path booleans and offsetting are geometric, in vector_boolean.h (Clipper2).

#include <array>
#include <cstdint>
#include <vector>

#include "tile_store.h"

namespace croptop::vector {

struct Point final {
    double x = 0.0, y = 0.0;
};

enum class Verb : std::uint8_t { Move, Line, Quad, Cubic, Close };

class Path final {
public:
    Path& moveTo(Point p);
    Path& lineTo(Point p);
    Path& quadTo(Point c, Point p);
    Path& cubicTo(Point c1, Point c2, Point p);
    Path& close();
    /** Appends another path's subpaths. */
    Path& append(const Path& other);
    /** Points mapped through a row-major 3×3 matrix (projective maps apply to control points). */
    Path transformed(const std::array<double, 9>& m) const;
    bool empty() const { return verbs_.empty(); }
    const std::vector<Verb>& verbs() const { return verbs_; }
    const std::vector<Point>& points() const { return points_; }

private:
    std::vector<Verb> verbs_;
    std::vector<Point> points_;
};

struct Polyline final {
    std::vector<Point> points;
    bool closed = false;
};
std::vector<Polyline> flatten(const Path& path, double tolerance = 0.1);

struct CornerRadii final {
    double top_left = 0, top_right = 0, bottom_right = 0, bottom_left = 0;
};
Path rectangle(double x, double y, double width, double height, CornerRadii radii = {});
Path ellipse(double cx, double cy, double rx, double ry);
/** Regular polygon (inner_ratio = 1) or star (inner vertices at inner_ratio × radius); rotation in degrees. */
Path polygon(double cx, double cy, double radius, int sides, double inner_ratio = 1.0, double rotation = 0.0);
/** A filled outline of a line with optional arrowheads (length and width in multiples of the line width). */
Path arrowLine(Point from, Point to, double width, bool arrow_start, bool arrow_end, double head_length = 4.0, double head_width = 3.0);

enum class Join : std::uint8_t { Miter, Round, Bevel };
enum class Cap : std::uint8_t { Butt, Round, Square };
enum class Align : std::uint8_t { Center, Inside, Outside };
struct StrokeStyle final {
    double width = 1.0;
    Join join = Join::Miter;
    Cap cap = Cap::Butt;
    double miter_limit = 4.0;
    std::vector<double> dash;  // on/off lengths in px (empty = solid)
    double dash_offset = 0.0;
    Align align = Align::Center;
};
/** Centred stroke as fill geometry (nonzero); alignment is applied by rasterizeStroke. */
Path strokeOutline(const Path& path, const StrokeStyle& style, double tolerance = 0.1);

enum class FillRule : std::uint8_t { NonZero, EvenOdd };
TileStore rasterize(const Path& path, std::uint32_t width, std::uint32_t height, FillRule rule = FillRule::NonZero, double tolerance = 0.1);
TileStore rasterizeStroke(const Path& path, const StrokeStyle& style, std::uint32_t width, std::uint32_t height, double tolerance = 0.1);

}  // namespace croptop::vector
