// Shape-layer records: primitives ↔ anchors, paths, bounds, rendering and hit-testing.
#include "shape_layer.h"

#include <algorithm>
#include <cmath>
#include <cstring>
#include <limits>

#include "pixel_plane.h"

namespace croptop::shape {
namespace {

using vector::Path;
using vector::Point;
using vector::Verb;

constexpr double kPi = 3.14159265358979323846;
constexpr double kMaxCoordinate = 1e7;
constexpr std::size_t kMaxAnchors = 100000;

Point add(Point a, Point b) { return {a.x + b.x, a.y + b.y}; }
Point sub(Point a, Point b) { return {a.x - b.x, a.y - b.y}; }
double dist(Point a, Point b) { return std::hypot(a.x - b.x, a.y - b.y); }

bool finite(Point p) { return std::isfinite(p.x) && std::isfinite(p.y) && std::abs(p.x) <= kMaxCoordinate && std::abs(p.y) <= kMaxCoordinate; }

/** A path's verbs as editable anchors (cubic controls become relative handles; closing duplicates merge). */
std::vector<Subpath> anchorsFromPath(const Path& path, std::int64_t& next_id) {
    std::vector<Subpath> out;
    std::size_t pi = 0;
    const auto& pts = path.points();
    for (const auto verb : path.verbs()) {
        switch (verb) {
            case Verb::Move:
                out.push_back({});
                out.back().anchors.push_back({next_id++, pts[pi++], std::nullopt, std::nullopt, AnchorMode::Corner});
                break;
            case Verb::Line:
                if (out.empty()) out.push_back({});
                out.back().anchors.push_back({next_id++, pts[pi++], std::nullopt, std::nullopt, AnchorMode::Corner});
                break;
            case Verb::Quad: {  // elevate to cubic
                auto& prev = out.back().anchors.back();
                const Point c = pts[pi], p = pts[pi + 1];
                pi += 2;
                const Point c1{prev.point.x + 2.0 / 3.0 * (c.x - prev.point.x), prev.point.y + 2.0 / 3.0 * (c.y - prev.point.y)};
                const Point c2{p.x + 2.0 / 3.0 * (c.x - p.x), p.y + 2.0 / 3.0 * (c.y - p.y)};
                prev.outgoing = sub(c1, prev.point);
                out.back().anchors.push_back({next_id++, p, sub(c2, p), std::nullopt, AnchorMode::Corner});
                break;
            }
            case Verb::Cubic: {
                auto& prev = out.back().anchors.back();
                const Point c1 = pts[pi], c2 = pts[pi + 1], p = pts[pi + 2];
                pi += 3;
                prev.outgoing = sub(c1, prev.point);
                out.back().anchors.push_back({next_id++, p, sub(c2, p), std::nullopt, AnchorMode::Corner});
                break;
            }
            case Verb::Close: {
                auto& s = out.back();
                s.closed = true;
                if (s.anchors.size() > 1 && dist(s.anchors.front().point, s.anchors.back().point) < 1e-9) {
                    s.anchors.front().incoming = s.anchors.back().incoming;
                    s.anchors.pop_back();
                    --next_id;
                }
                break;
            }
        }
    }
    // Anchors whose two handles are collinear and opposite are smooth.
    for (auto& s : out)
        for (auto& a : s.anchors)
            if (a.incoming && a.outgoing) {
                const double cross = a.incoming->x * a.outgoing->y - a.incoming->y * a.outgoing->x;
                const double dot = a.incoming->x * a.outgoing->x + a.incoming->y * a.outgoing->y;
                const double li = std::hypot(a.incoming->x, a.incoming->y), lo = std::hypot(a.outgoing->x, a.outgoing->y);
                if (li > 1e-9 && lo > 1e-9 && std::abs(cross) < 1e-6 * li * lo && dot < 0)
                    a.mode = std::abs(li - lo) < 1e-6 * std::max(li, lo) ? AnchorMode::Symmetric : AnchorMode::Smooth;
            }
    return out;
}

std::array<double, 9> rotationAbout(double cx, double cy, double degrees) {
    const double r = degrees * kPi / 180.0, c = std::cos(r), s = std::sin(r);
    return {c, -s, cx - c * cx + s * cy, s, c, cy - s * cx - c * cy, 0, 0, 1};
}

/** Distance from p to segment ab. */
double segmentDistance(Point p, Point a, Point b) {
    const Point ab = sub(b, a);
    const double len2 = ab.x * ab.x + ab.y * ab.y;
    double t = len2 > 0 ? ((p.x - a.x) * ab.x + (p.y - a.y) * ab.y) / len2 : 0.0;
    t = std::clamp(t, 0.0, 1.0);
    return dist(p, {a.x + t * ab.x, a.y + t * ab.y});
}

/** The path of one segment (anchor i → next) of a subpath. */
Path segmentPath(const Subpath& s, std::size_t i) {
    const auto& a = s.anchors[i];
    const auto& b = s.anchors[(i + 1) % s.anchors.size()];
    Path p;
    p.moveTo(a.point);
    if (a.outgoing || b.incoming) {
        p.cubicTo(add(a.point, a.outgoing.value_or(Point{})), add(b.point, b.incoming.value_or(Point{})), b.point);
    } else {
        p.lineTo(b.point);
    }
    return p;
}

double strokeGrowth(const Stroke& stroke) {
    if (!stroke.enabled) return 0;
    const auto& st = stroke.style;
    const double half = st.align == vector::Align::Center ? st.width / 2 : st.align == vector::Align::Outside ? st.width : 0.0;
    const double join = st.join == vector::Join::Miter ? std::max(1.5, st.miter_limit) : 1.5;
    double grow = half * join;
    if (stroke.arrow_start || stroke.arrow_end) grow = std::max(grow, stroke.arrow_width * st.width / 2);
    return grow;
}

bool anyClosedOrFillable(const ShapeRecord& r) {
    for (const auto& s : r.subpaths)
        if (s.anchors.size() >= 3 || (s.anchors.size() >= 2 && (s.anchors.front().outgoing || s.anchors.back().incoming))) return true;
    return false;
}

/** Composites `color` × coverage (A16) onto the target, straight-alpha source-over. */
bool composite(TileStore& target, const TileStore& coverage, const Color& color, std::string& error) {
    const auto bpp = target.bytes_per_pixel();
    for (const auto c : coverage.tileCoordinates()) {
        const auto cov = coverage.readTile(c);
        if (!cov) continue;
        const auto existing = target.readTile(c);
        TileBuffer tile = existing ? *existing : TileBuffer(target.tile_byte_size(), 0);
        bool changed = false;
        for (std::size_t i = 0; i < static_cast<std::size_t>(kTileEdge) * kTileEdge; ++i) {
            std::uint16_t raw = 0;
            std::memcpy(&raw, cov->data() + i * 2, 2);
            if (raw == 0) continue;
            const float a = color[3] * (raw / 65535.0F);
            if (a <= 0) continue;
            float dst[4];
            paint::detail::readPixel(target.format(), tile.data() + i * bpp, dst);
            const float da = paint::detail::unitClamp(dst[3]);
            const float oa = a + da * (1 - a);
            float out[4];
            for (int k = 0; k < 3; ++k) out[k] = oa > 0 ? (color[static_cast<std::size_t>(k)] * a + dst[k] * da * (1 - a)) / oa : 0.0F;
            out[3] = oa;
            paint::detail::writePixel(target.format(), tile.data() + i * bpp, out);
            changed = true;
        }
        if (changed && !target.writeTile(c, tile)) {
            error = "could not store shape pixels";
            return false;
        }
    }
    return true;
}

}  // namespace

std::vector<Subpath> primitiveAnchors(const Primitive& pr, std::int64_t first_id) {
    std::int64_t next = first_id;
    const double cx = pr.x + pr.width / 2, cy = pr.y + pr.height / 2;
    Path path;
    switch (pr.kind) {
        case PrimitiveKind::Line: {
            Subpath s;
            s.anchors.push_back({next++, {pr.x, pr.y}, std::nullopt, std::nullopt, AnchorMode::Corner});
            s.anchors.push_back({next++, {pr.x + pr.width, pr.y + pr.height}, std::nullopt, std::nullopt, AnchorMode::Corner});
            return {s};
        }
        case PrimitiveKind::Rectangle: path = vector::rectangle(pr.x, pr.y, pr.width, pr.height); break;
        case PrimitiveKind::RoundedRectangle: path = vector::rectangle(pr.x, pr.y, pr.width, pr.height, pr.radii); break;
        case PrimitiveKind::Ellipse: path = vector::ellipse(cx, cy, pr.width / 2, pr.height / 2); break;
        case PrimitiveKind::Polygon:
        case PrimitiveKind::Star: {
            // A unit polygon stretched to the box (rotation applied after, about the centre).
            const auto unit = vector::polygon(0, 0, 1, std::clamp(pr.sides, 3, 64), pr.kind == PrimitiveKind::Star ? std::clamp(pr.inner_ratio, 0.05, 0.95) : 1.0, 0);
            path = unit.transformed({pr.width / 2, 0, cx, 0, pr.height / 2, cy, 0, 0, 1});
            break;
        }
    }
    if (pr.rotation != 0 && std::isfinite(pr.rotation)) path = path.transformed(rotationAbout(cx, cy, pr.rotation));
    return anchorsFromPath(path, next);
}

ShapeRecord fromPrimitive(std::int64_t id, const Primitive& primitive, const Fill& fill, const Stroke& stroke, std::int64_t first_anchor_id) {
    ShapeRecord r;
    r.id = id;
    r.subpaths = primitiveAnchors(primitive, first_anchor_id);
    r.fill = fill;
    r.stroke = stroke;
    r.primitive = primitive;
    return r;
}

vector::Path toPath(const ShapeRecord& record) {
    Path p;
    for (const auto& s : record.subpaths) {
        if (s.anchors.empty()) continue;
        p.moveTo(s.anchors.front().point);
        const std::size_t segments = s.closed ? s.anchors.size() : s.anchors.size() - 1;
        for (std::size_t i = 0; i < segments; ++i) {
            const auto& a = s.anchors[i];
            const auto& b = s.anchors[(i + 1) % s.anchors.size()];
            if (a.outgoing || b.incoming) {
                p.cubicTo(add(a.point, a.outgoing.value_or(Point{})), add(b.point, b.incoming.value_or(Point{})), b.point);
            } else if (!(s.closed && i + 1 == s.anchors.size())) {
                p.lineTo(b.point);
            }
        }
        if (s.closed) p.close();
    }
    return p;
}

Bounds bounds(const ShapeRecord& record, bool include_stroke) {
    Bounds b;
    b.x0 = b.y0 = std::numeric_limits<double>::max();
    b.x1 = b.y1 = -std::numeric_limits<double>::max();
    for (const auto& line : vector::flatten(toPath(record), 0.1))
        for (const auto& q : line.points) {
            b.x0 = std::min(b.x0, q.x);
            b.y0 = std::min(b.y0, q.y);
            b.x1 = std::max(b.x1, q.x);
            b.y1 = std::max(b.y1, q.y);
            b.empty = false;
        }
    if (b.empty) return Bounds{};
    if (include_stroke) {
        const double g = strokeGrowth(record.stroke);
        b.x0 -= g;
        b.y0 -= g;
        b.x1 += g;
        b.y1 += g;
    }
    return b;
}

bool validate(const ShapeRecord& r, std::string& error) {
    std::size_t anchors = 0;
    for (const auto& s : r.subpaths) {
        anchors += s.anchors.size();
        for (const auto& a : s.anchors)
            if (!finite(a.point) || (a.incoming && !finite(*a.incoming)) || (a.outgoing && !finite(*a.outgoing))) {
                error = "shape geometry must be finite and within ±1e7 px";
                return false;
            }
    }
    if (anchors > kMaxAnchors) {
        error = "shape has too many anchors";
        return false;
    }
    for (const auto& c : {r.fill.color, r.stroke.color})
        for (const float v : c)
            if (!std::isfinite(v) || v < 0 || v > 1) {
                error = "shape colours must be 0–1";
                return false;
            }
    const auto& st = r.stroke.style;
    if (!std::isfinite(st.width) || st.width < 0 || st.width > 10000 || !std::isfinite(st.miter_limit) || st.miter_limit < 1 || st.miter_limit > 100 ||
        !std::isfinite(st.dash_offset) || st.dash.size() > 32 || !std::isfinite(r.stroke.arrow_length) || !std::isfinite(r.stroke.arrow_width) ||
        r.stroke.arrow_length < 1 || r.stroke.arrow_length > 32 || r.stroke.arrow_width < 1 || r.stroke.arrow_width > 32) {
        error = "shape stroke settings are out of range";
        return false;
    }
    for (const double d : st.dash)
        if (!std::isfinite(d) || d <= 0) {
            error = "dash lengths must be positive";
            return false;
        }
    return true;
}

bool render(std::span<const ShapeRecord> records, TileStore& target, std::string& error) {
    if (!paint::detail::isRgba(target.format())) {
        error = "shape layers render into RGBA layers";
        return false;
    }
    for (const auto& r : records) {
        if (!validate(r, error)) return false;
        const auto path = toPath(r);
        if (path.empty()) continue;
        if (r.fill.enabled && r.fill.color[3] > 0 && anyClosedOrFillable(r)) {
            const auto coverage = vector::rasterize(path, target.width(), target.height(), r.fill_rule, 0.1);
            if (!composite(target, coverage, r.fill.color, error)) return false;
        }
        if (r.stroke.enabled && r.stroke.color[3] > 0 && r.stroke.style.width > 0) {
            const bool arrows = (r.stroke.arrow_start || r.stroke.arrow_end) && r.subpaths.size() == 1 && r.subpaths[0].anchors.size() == 2 &&
                                !r.subpaths[0].closed && !r.subpaths[0].anchors[0].outgoing && !r.subpaths[0].anchors[1].incoming;
            const auto coverage = arrows ? vector::rasterize(vector::arrowLine(r.subpaths[0].anchors[0].point, r.subpaths[0].anchors[1].point, r.stroke.style.width,
                                                                               r.stroke.arrow_start, r.stroke.arrow_end, r.stroke.arrow_length, r.stroke.arrow_width),
                                                             target.width(), target.height(), vector::FillRule::NonZero, 0.1)
                                         : vector::rasterizeStroke(path, r.stroke.style, target.width(), target.height(), 0.1);
            if (!composite(target, coverage, r.stroke.color, error)) return false;
        }
    }
    return true;
}

Hit hitTest(std::span<const ShapeRecord> records, vector::Point p, double tolerance) {
    Hit none;
    if (!finite(p) || !(tolerance >= 0)) return none;
    // Pass 1: handle ends and anchors (topmost record first, nearest within it).
    for (std::size_t ri = records.size(); ri-- > 0;) {
        Hit best;
        best.distance = std::numeric_limits<double>::max();
        const auto& r = records[ri];
        for (std::size_t si = 0; si < r.subpaths.size(); ++si)
            for (std::size_t ai = 0; ai < r.subpaths[si].anchors.size(); ++ai) {
                const auto& a = r.subpaths[si].anchors[ai];
                const auto consider = [&](HitPart part, Point q) {
                    const double d = dist(p, q);
                    if (d <= tolerance && d < best.distance) best = {part, ri, si, ai, d};
                };
                if (a.incoming) consider(HitPart::IncomingHandle, add(a.point, *a.incoming));
                if (a.outgoing) consider(HitPart::OutgoingHandle, add(a.point, *a.outgoing));
                consider(HitPart::Anchor, a.point);
            }
        if (best.part != HitPart::None) return best;
    }
    // Pass 2: segments (the stroke's half width counts as on the path).
    for (std::size_t ri = records.size(); ri-- > 0;) {
        const auto& r = records[ri];
        const double reach = tolerance + (r.stroke.enabled ? r.stroke.style.width / 2 : 0.0);
        Hit best;
        best.distance = std::numeric_limits<double>::max();
        for (std::size_t si = 0; si < r.subpaths.size(); ++si) {
            const auto& s = r.subpaths[si];
            if (s.anchors.size() < 2) continue;
            const std::size_t segments = s.closed ? s.anchors.size() : s.anchors.size() - 1;
            for (std::size_t i = 0; i < segments; ++i)
                for (const auto& line : vector::flatten(segmentPath(s, i), 0.1))
                    for (std::size_t k = 0; k + 1 < line.points.size(); ++k) {
                        const double d = segmentDistance(p, line.points[k], line.points[k + 1]);
                        if (d <= reach && d < best.distance) best = {HitPart::Segment, ri, si, i, d};
                    }
        }
        if (best.part != HitPart::None) return best;
    }
    // Pass 3: filled interiors by the record's fill rule.
    for (std::size_t ri = records.size(); ri-- > 0;) {
        const auto& r = records[ri];
        if (!r.fill.enabled || r.fill.color[3] <= 0) continue;
        int winding = 0, crossings = 0;
        for (const auto& line : vector::flatten(toPath(r), 0.1)) {
            const auto& q = line.points;
            for (std::size_t k = 0; k < q.size(); ++k) {
                const Point a = q[k], b = q[(k + 1) % q.size()];  // implicitly closed for filling
                if ((a.y <= p.y) != (b.y <= p.y)) {
                    const double x = a.x + (p.y - a.y) * (b.x - a.x) / (b.y - a.y);
                    if (x > p.x) {
                        winding += b.y > a.y ? 1 : -1;
                        ++crossings;
                    }
                }
            }
        }
        const bool inside = r.fill_rule == vector::FillRule::EvenOdd ? crossings % 2 == 1 : winding != 0;
        if (inside) return {HitPart::Interior, ri, 0, 0, 0};
    }
    return none;
}

}  // namespace croptop::shape
