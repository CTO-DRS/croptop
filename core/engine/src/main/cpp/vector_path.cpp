#include "vector_path.h"

#include <algorithm>
#include <cmath>
#include <cstring>

namespace croptop::vector {
namespace {

constexpr double kPi = 3.14159265358979323846;
constexpr double kKappa = 0.5522847498307936;  // cubic circle approximation
constexpr int kSubScanlines = 16;

Point operator+(Point a, Point b) { return {a.x + b.x, a.y + b.y}; }
Point operator-(Point a, Point b) { return {a.x - b.x, a.y - b.y}; }
Point operator*(Point a, double s) { return {a.x * s, a.y * s}; }
double length(Point a) { return std::hypot(a.x, a.y); }
double cross(Point a, Point b) { return a.x * b.y - a.y * b.x; }
Point lerp(Point a, Point b, double t) { return {a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t}; }

double distanceToLine(Point p, Point a, Point b) {
    const Point d = b - a;
    const double l = length(d);
    if (l < 1e-12) return length(p - a);
    return std::fabs(cross(p - a, d)) / l;
}

void flattenCubic(Point p0, Point p1, Point p2, Point p3, double tolerance, int depth, std::vector<Point>& out) {
    if (depth >= 12 || std::max(distanceToLine(p1, p0, p3), distanceToLine(p2, p0, p3)) <= tolerance) {
        out.push_back(p3);
        return;
    }
    const Point a = lerp(p0, p1, 0.5), b = lerp(p1, p2, 0.5), c = lerp(p2, p3, 0.5);
    const Point d = lerp(a, b, 0.5), e = lerp(b, c, 0.5), m = lerp(d, e, 0.5);
    flattenCubic(p0, a, d, m, tolerance, depth + 1, out);
    flattenCubic(m, e, c, p3, tolerance, depth + 1, out);
}

double signedArea(const std::vector<Point>& poly) {
    double a = 0;
    for (std::size_t i = 0; i < poly.size(); ++i) a += cross(poly[i], poly[(i + 1) % poly.size()]);
    return a / 2;
}

/** Adds a closed polygon to `out`, oriented with positive signed area (so nonzero fills union). */
void addPiece(Path& out, std::vector<Point> poly) {
    if (poly.size() < 3) return;
    if (signedArea(poly) < 0) std::reverse(poly.begin(), poly.end());
    out.moveTo(poly[0]);
    for (std::size_t i = 1; i < poly.size(); ++i) out.lineTo(poly[i]);
    out.close();
}

std::vector<Point> circle(Point c, double r, double tolerance) {
    const int n = std::clamp(static_cast<int>(std::ceil(kPi / std::acos(std::max(-1.0, 1.0 - tolerance / std::max(r, 1e-9))))), 8, 256);
    std::vector<Point> out;
    for (int i = 0; i < n; ++i) out.push_back({c.x + r * std::cos(2 * kPi * i / n), c.y + r * std::sin(2 * kPi * i / n)});
    return out;
}

/** Stroke pieces for one polyline (segments, joins, caps). */
void strokePolyline(const std::vector<Point>& raw, bool closed, const StrokeStyle& s, double tolerance, Path& out) {
    // Drop repeated points.
    std::vector<Point> p;
    for (const auto& q : raw)
        if (p.empty() || length(q - p.back()) > 1e-9) p.push_back(q);
    if (closed && p.size() > 1 && length(p.front() - p.back()) <= 1e-9) p.pop_back();
    const double hw = s.width / 2;
    if (p.size() == 1) {  // a dot: only round or square caps draw it
        if (s.cap == Cap::Round) addPiece(out, circle(p[0], hw, tolerance));
        if (s.cap == Cap::Square) addPiece(out, {{p[0].x - hw, p[0].y - hw}, {p[0].x + hw, p[0].y - hw}, {p[0].x + hw, p[0].y + hw}, {p[0].x - hw, p[0].y + hw}});
        return;
    }
    if (p.size() < 2) return;
    const std::size_t segments = closed ? p.size() : p.size() - 1;
    const auto normalOf = [&](std::size_t i) {
        const Point d = p[(i + 1) % p.size()] - p[i];
        const double l = length(d);
        return Point{-d.y / l * hw, d.x / l * hw};
    };
    for (std::size_t i = 0; i < segments; ++i) {
        const Point a = p[i], b = p[(i + 1) % p.size()], n = normalOf(i);
        addPiece(out, {a + n, b + n, b - n, a - n});
    }
    // Joins.
    const std::size_t first_join = closed ? 0 : 1, last_join = closed ? p.size() : p.size() - 1;
    for (std::size_t j = first_join; j < last_join; ++j) {
        const std::size_t prev = (j + p.size() - 1) % p.size(), next = (j + 1) % p.size();
        const Point v = p[j];
        const Point d0 = v - p[prev], d1 = p[next] - v;
        const double turn = cross(d0, d1);
        if (std::fabs(turn) < 1e-12 && d0.x * d1.x + d0.y * d1.y > 0) continue;  // straight
        const Point n0 = normalOf(prev), n1 = normalOf(j);
        // The outer side is opposite the turn.
        const Point o0 = turn > 0 ? v - n0 : v + n0, o1 = turn > 0 ? v - n1 : v + n1;
        if (s.join == Join::Round) {
            addPiece(out, circle(v, hw, tolerance));
        } else if (s.join == Join::Miter) {
            const Point t0 = d0 * (1.0 / length(d0)), t1 = d1 * (1.0 / length(d1));
            const double cos_theta = std::clamp(t0.x * t1.x + t0.y * t1.y, -1.0, 1.0);
            const double miter = 1.0 / std::sqrt(std::max(1e-12, (1 + cos_theta) / 2));  // 1/sin(half the interior angle)
            if (miter <= s.miter_limit) {
                Point bisector = (o0 - v) + (o1 - v);
                const double bl = length(bisector);
                if (bl > 1e-12) {
                    bisector = bisector * (hw * miter / bl);
                    addPiece(out, {v, o0, v + bisector, o1});
                    continue;
                }
            }
            addPiece(out, {v, o0, o1});
        } else {
            addPiece(out, {v, o0, o1});
        }
    }
    // Caps.
    if (!closed) {
        for (const bool start : {true, false}) {
            const Point e = start ? p.front() : p.back();
            const Point towards = start ? p[1] : p[p.size() - 2];
            const Point d = (e - towards) * (1.0 / length(e - towards));  // outward
            const Point n{-d.y * hw, d.x * hw};
            if (s.cap == Cap::Round) addPiece(out, circle(e, hw, tolerance));
            else if (s.cap == Cap::Square) addPiece(out, {e + n, e + n + d * hw, e - n + d * hw, e - n});
        }
    }
}

struct Box {
    double x0, y0, x1, y1;
};

constexpr std::size_t kMaxDashes = 200000;

/**
 * Splits a polyline into dashes by arc length. Only the parts of segments inside `clip` produce geometry; the
 * pattern advances arithmetically across everything outside it, so dashing a path that runs far off the
 * canvas stays cheap.
 */
std::vector<Polyline> dashed(const Polyline& line, const std::vector<double>& dash_array, double offset, const Box& clip) {
    // Like SVG, an odd-length array repeats to become even, so a full cycle returns to the same on/off state.
    std::vector<double> pattern = dash_array;
    if (pattern.size() % 2 == 1) pattern.insert(pattern.end(), dash_array.begin(), dash_array.end());
    double total = 0;
    for (auto& d : pattern) total += (d = std::isfinite(d) ? std::max(0.0, d) : 0.0);
    if (total <= 1e-9) return {line};
    std::vector<Point> pts = line.points;
    if (line.closed && !pts.empty()) pts.push_back(pts.front());
    std::vector<Polyline> out;
    std::size_t index = 0;
    double phase = std::fmod(std::fmod(std::isfinite(offset) ? offset : 0.0, total) + total, total);
    while (phase >= pattern[index] && pattern[index] > 0) {
        phase -= pattern[index];
        index = (index + 1) % pattern.size();
    }
    double remaining = pattern[index] - phase;
    bool on = index % 2 == 0;
    Polyline current;
    const auto step = [&]() {
        index = (index + 1) % pattern.size();
        remaining = pattern[index];
        on = !on;
    };
    const auto endDash = [&]() {
        if (on && current.points.size() > 1) out.push_back(current);
        current.points.clear();
    };
    // Advances the pattern by an invisible length without geometry.
    const auto skip = [&](double amount) {
        endDash();
        if (amount <= remaining) {
            remaining -= amount;
            return;
        }
        amount -= remaining;
        step();
        amount = std::fmod(amount, total);  // whole cycles leave the state unchanged
        while (amount > remaining) {
            amount -= remaining;
            step();
        }
        remaining -= amount;
    };
    // Walks a visible piece, emitting dashes.
    const auto walk = [&](Point a, Point b) {
        double seg = length(b - a);
        if (on && current.points.empty()) current.points.push_back(a);
        while (seg > remaining && out.size() <= kMaxDashes) {
            const Point cut = lerp(a, b, remaining / seg);
            if (on) {
                current.points.push_back(cut);
                out.push_back(current);
                current.points.clear();
            } else {
                current.points = {cut};
            }
            seg -= remaining;
            a = cut;
            step();
        }
        remaining -= seg;
        if (on) current.points.push_back(b);
    };
    for (std::size_t i = 0; i + 1 < pts.size() && out.size() <= kMaxDashes; ++i) {
        const Point a = pts[i], b = pts[i + 1];
        const double seg = length(b - a);
        if (!std::isfinite(seg) || seg <= 0.0) continue;
        // Liang–Barsky: the visible parameter range [t0, t1] of a→b inside the clip box.
        double t0 = 0.0, t1 = 1.0;
        const double dx = b.x - a.x, dy = b.y - a.y;
        const std::array<std::pair<double, double>, 4> tests{{{-dx, a.x - clip.x0}, {dx, clip.x1 - a.x}, {-dy, a.y - clip.y0}, {dy, clip.y1 - a.y}}};
        bool visible = true;
        for (const auto& [pk, qk] : tests) {
            if (pk == 0.0) {
                if (qk < 0.0) visible = false;
                continue;
            }
            const double r = qk / pk;
            if (pk < 0.0) t0 = std::max(t0, r);
            else t1 = std::min(t1, r);
        }
        if (!visible || t0 >= t1) {
            skip(seg);
            continue;
        }
        if (t0 > 0.0) skip(seg * t0);
        walk(lerp(a, b, t0), lerp(a, b, t1));
        if (t1 < 1.0) skip(seg * (1.0 - t1));
    }
    endDash();
    return out;
}

struct Edge {
    double x0, y0, x1, y1;
    int winding;
};

}  // namespace

Path& Path::moveTo(Point p) {
    verbs_.push_back(Verb::Move);
    points_.push_back(p);
    return *this;
}
Path& Path::lineTo(Point p) {
    if (verbs_.empty()) return moveTo(p);
    verbs_.push_back(Verb::Line);
    points_.push_back(p);
    return *this;
}
Path& Path::quadTo(Point c, Point p) {
    if (verbs_.empty()) moveTo(c);
    verbs_.push_back(Verb::Quad);
    points_.push_back(c);
    points_.push_back(p);
    return *this;
}
Path& Path::cubicTo(Point c1, Point c2, Point p) {
    if (verbs_.empty()) moveTo(c1);
    verbs_.push_back(Verb::Cubic);
    points_.push_back(c1);
    points_.push_back(c2);
    points_.push_back(p);
    return *this;
}
Path& Path::close() {
    if (!verbs_.empty() && verbs_.back() != Verb::Close) verbs_.push_back(Verb::Close);
    return *this;
}
Path& Path::append(const Path& other) {
    verbs_.insert(verbs_.end(), other.verbs_.begin(), other.verbs_.end());
    points_.insert(points_.end(), other.points_.begin(), other.points_.end());
    return *this;
}
Path Path::transformed(const std::array<double, 9>& m) const {
    Path out = *this;
    for (auto& p : out.points_) {
        const double w = m[6] * p.x + m[7] * p.y + m[8];
        const double iw = std::fabs(w) < 1e-12 ? 0.0 : 1.0 / w;
        p = {(m[0] * p.x + m[1] * p.y + m[2]) * iw, (m[3] * p.x + m[4] * p.y + m[5]) * iw};
    }
    return out;
}

std::vector<Polyline> flatten(const Path& path, double tolerance) {
    tolerance = std::clamp(tolerance, 0.001, 100.0);
    std::vector<Polyline> out;
    Polyline current;
    std::size_t pi = 0;
    Point last{};
    const auto finish = [&](bool closed) {
        if (!current.points.empty()) {
            current.closed = closed;
            out.push_back(std::move(current));
        }
        current = {};
    };
    for (const auto verb : path.verbs()) {
        switch (verb) {
            case Verb::Move:
                finish(false);
                last = path.points()[pi++];
                current.points.push_back(last);
                break;
            case Verb::Line:
                last = path.points()[pi++];
                current.points.push_back(last);
                break;
            case Verb::Quad: {
                const Point c = path.points()[pi], p = path.points()[pi + 1];
                pi += 2;
                flattenCubic(last, last + (c - last) * (2.0 / 3.0), p + (c - p) * (2.0 / 3.0), p, tolerance, 0, current.points);
                last = p;
                break;
            }
            case Verb::Cubic: {
                const Point c1 = path.points()[pi], c2 = path.points()[pi + 1], p = path.points()[pi + 2];
                pi += 3;
                flattenCubic(last, c1, c2, p, tolerance, 0, current.points);
                last = p;
                break;
            }
            case Verb::Close: {
                const Point start = current.points.empty() ? last : current.points.front();
                finish(true);
                last = start;
                break;
            }
        }
    }
    finish(false);
    return out;
}

Path rectangle(double x, double y, double w, double h, CornerRadii r) {
    Path p;
    if (!(w > 0) || !(h > 0)) return p;
    const double limit = std::min(w, h) / 2;
    const double tl = std::clamp(r.top_left, 0.0, limit), tr = std::clamp(r.top_right, 0.0, limit), br = std::clamp(r.bottom_right, 0.0, limit),
                 bl = std::clamp(r.bottom_left, 0.0, limit);
    p.moveTo({x + tl, y});
    p.lineTo({x + w - tr, y});
    if (tr > 0) p.cubicTo({x + w - tr + tr * kKappa, y}, {x + w, y + tr - tr * kKappa}, {x + w, y + tr});
    p.lineTo({x + w, y + h - br});
    if (br > 0) p.cubicTo({x + w, y + h - br + br * kKappa}, {x + w - br + br * kKappa, y + h}, {x + w - br, y + h});
    p.lineTo({x + bl, y + h});
    if (bl > 0) p.cubicTo({x + bl - bl * kKappa, y + h}, {x, y + h - bl + bl * kKappa}, {x, y + h - bl});
    p.lineTo({x, y + tl});
    if (tl > 0) p.cubicTo({x, y + tl - tl * kKappa}, {x + tl - tl * kKappa, y}, {x + tl, y});
    return p.close();
}

Path ellipse(double cx, double cy, double rx, double ry) {
    Path p;
    if (!(rx > 0) || !(ry > 0)) return p;
    const double kx = rx * kKappa, ky = ry * kKappa;
    p.moveTo({cx + rx, cy});
    p.cubicTo({cx + rx, cy + ky}, {cx + kx, cy + ry}, {cx, cy + ry});
    p.cubicTo({cx - kx, cy + ry}, {cx - rx, cy + ky}, {cx - rx, cy});
    p.cubicTo({cx - rx, cy - ky}, {cx - kx, cy - ry}, {cx, cy - ry});
    p.cubicTo({cx + kx, cy - ry}, {cx + rx, cy - ky}, {cx + rx, cy});
    return p.close();
}

Path polygon(double cx, double cy, double radius, int sides, double inner_ratio, double rotation) {
    Path p;
    sides = std::clamp(sides, 3, 1000);
    const bool star = inner_ratio < 1.0;
    const int n = star ? sides * 2 : sides;
    for (int i = 0; i < n; ++i) {
        const double r = (star && i % 2 == 1) ? radius * std::clamp(inner_ratio, 0.01, 1.0) : radius;
        const double a = (rotation - 90.0) * kPi / 180.0 + 2 * kPi * i / n;
        const Point q{cx + r * std::cos(a), cy + r * std::sin(a)};
        i == 0 ? p.moveTo(q) : p.lineTo(q);
    }
    return p.close();
}

Path arrowLine(Point from, Point to, double width, bool arrow_start, bool arrow_end, double head_length, double head_width) {
    Path out;
    const Point d = to - from;
    const double l = length(d);
    if (l < 1e-9 || !(width > 0)) return out;
    const Point u = d * (1.0 / l), n{-u.y, u.x};
    const double hl = std::min(head_length * width, l / (arrow_start && arrow_end ? 2.0 : 1.0)), hw = std::max(head_width * width, width) / 2;
    const Point a = arrow_start ? from + u * hl : from, b = arrow_end ? to - u * hl : to;
    addPiece(out, {a + n * (width / 2), b + n * (width / 2), b - n * (width / 2), a - n * (width / 2)});
    if (arrow_end) addPiece(out, {to, b + n * hw, b - n * hw});
    if (arrow_start) addPiece(out, {from, a - n * hw, a + n * hw});
    return out;
}

namespace {

Path strokeWithin(const Path& path, const StrokeStyle& style, double tolerance, const Box& clip) {
    Path out;
    if (!(style.width > 0) || !std::isfinite(style.width)) return out;
    for (const auto& line : flatten(path, tolerance)) {
        if (style.dash.empty()) {
            strokePolyline(line.points, line.closed, style, tolerance, out);
        } else {
            for (const auto& dash : dashed(line, style.dash, style.dash_offset, clip)) strokePolyline(dash.points, false, style, tolerance, out);
        }
    }
    return out;
}

}  // namespace

Path strokeOutline(const Path& path, const StrokeStyle& style, double tolerance) {
    return strokeWithin(path, style, tolerance, {-1e300, -1e300, 1e300, 1e300});
}

TileStore rasterize(const Path& path, std::uint32_t width, std::uint32_t height, FillRule rule, double tolerance) {
    TileStore out(PixelFormat::A16, width, height);
    std::vector<Edge> edges;
    double min_y = 1e300, max_y = -1e300, min_x = 1e300, max_x = -1e300;
    for (const auto& line : flatten(path, tolerance)) {
        const auto& p = line.points;
        for (std::size_t i = 0; i < p.size(); ++i) {
            const Point a = p[i], b = p[(i + 1) % p.size()];  // fills close every subpath
            if (i + 1 == p.size() && p.size() < 3) break;
            if (!std::isfinite(a.x) || !std::isfinite(a.y) || !std::isfinite(b.x) || !std::isfinite(b.y) || a.y == b.y) continue;
            // Edges above, below or right of the canvas never change coverage inside it; edges left of it only
            // contribute winding, so they collapse to a vertical edge at x = −1.
            // Keep edges exactly on the right boundary: they still pair with the left crossings
            // that define coverage up to the last pixel. Only edges strictly outside are inert.
            if (std::max(a.y, b.y) <= 0.0 || std::min(a.y, b.y) >= height || std::min(a.x, b.x) > width) continue;
            Edge e = a.y < b.y ? Edge{a.x, a.y, b.x, b.y, 1} : Edge{b.x, b.y, a.x, a.y, -1};
            if (std::max(e.x0, e.x1) < 0.0) e.x0 = e.x1 = -1.0;
            // Clip the vertical extent to the canvas (keeping the x of the clipped ends on the edge).
            const auto xAt = [&](double y) { return e.x0 + (y - e.y0) / (e.y1 - e.y0) * (e.x1 - e.x0); };
            if (e.y0 < -1.0) e.x0 = xAt(-1.0), e.y0 = -1.0;
            if (e.y1 > height + 1.0) e.x1 = xAt(height + 1.0), e.y1 = height + 1.0;
            edges.push_back(e);
            min_y = std::min(min_y, std::min(a.y, b.y)), max_y = std::max(max_y, std::max(a.y, b.y));
            min_x = std::min(min_x, std::min(a.x, b.x)), max_x = std::max(max_x, std::max(a.x, b.x));
        }
    }
    if (edges.empty()) return out;
    std::sort(edges.begin(), edges.end(), [](const Edge& a, const Edge& b) { return a.y0 < b.y0; });
    const int row0 = std::max(0, static_cast<int>(std::floor(min_y))), row1 = std::min(static_cast<int>(height), static_cast<int>(std::ceil(max_y)));
    const int col0 = std::max(0, static_cast<int>(std::floor(min_x))), col1 = std::min(static_cast<int>(width), static_cast<int>(std::ceil(max_x)) + 1);
    if (row0 >= row1 || col0 >= col1) return out;
    const int span = col1 - col0;
    std::vector<double> cover(static_cast<std::size_t>(span) + 2), diff(static_cast<std::size_t>(span) + 2);
    std::vector<std::pair<double, int>> crossings;
    std::vector<std::size_t> active;
    std::size_t next_edge = 0;
    constexpr double kWeight = 1.0 / kSubScanlines;
    for (int ty = row0 / static_cast<int>(kTileEdge); ty * static_cast<int>(kTileEdge) < row1; ++ty) {
        const int tx0 = col0 / static_cast<int>(kTileEdge), tx1 = (col1 - 1) / static_cast<int>(kTileEdge);
        std::vector<TileBuffer> tiles(static_cast<std::size_t>(tx1 - tx0 + 1));
        std::vector<bool> used(tiles.size(), false);
        for (int row = std::max(row0, ty * static_cast<int>(kTileEdge)); row < std::min(row1, (ty + 1) * static_cast<int>(kTileEdge)); ++row) {
            std::fill(cover.begin(), cover.end(), 0.0);
            std::fill(diff.begin(), diff.end(), 0.0);
            for (int sub = 0; sub < kSubScanlines; ++sub) {
                const double y = row + (sub + 0.5) * kWeight;
                while (next_edge < edges.size() && edges[next_edge].y0 <= y) active.push_back(next_edge++);
                crossings.clear();
                std::size_t keep = 0;
                for (const auto e : active) {
                    const auto& edge = edges[e];
                    if (edge.y1 <= y) continue;  // finished
                    active[keep++] = e;
                    if (edge.y0 > y) continue;
                    const double t = (y - edge.y0) / (edge.y1 - edge.y0);
                    crossings.emplace_back(edge.x0 + t * (edge.x1 - edge.x0), edge.winding);
                }
                active.resize(keep);
                std::sort(crossings.begin(), crossings.end());
                int winding = 0;
                for (std::size_t i = 0; i + 1 < crossings.size(); ++i) {
                    winding += crossings[i].second;
                    const bool inside = rule == FillRule::NonZero ? winding != 0 : (winding & 1) != 0;
                    if (!inside) continue;
                    const double a = std::clamp(crossings[i].first - col0, 0.0, static_cast<double>(span));
                    const double b = std::clamp(crossings[i + 1].first - col0, 0.0, static_cast<double>(span));
                    if (b <= a) continue;
                    const auto ia = static_cast<std::size_t>(a), ib = static_cast<std::size_t>(b);
                    if (ia == ib) {
                        cover[ia] += (b - a) * kWeight;
                    } else {
                        cover[ia] += (static_cast<double>(ia) + 1 - a) * kWeight;
                        diff[ia + 1] += kWeight;
                        diff[ib] -= kWeight;
                        cover[ib] += (b - static_cast<double>(ib)) * kWeight;
                    }
                }
            }
            double running = 0;
            for (int x = 0; x < span; ++x) {
                running += diff[static_cast<std::size_t>(x)];
                const double c = std::clamp(cover[static_cast<std::size_t>(x)] + running, 0.0, 1.0);
                if (c <= 0.0) continue;
                const int gx = col0 + x;
                const auto t = static_cast<std::size_t>(gx / static_cast<int>(kTileEdge) - tx0);
                if (!used[t]) {
                    tiles[t].assign(out.tile_byte_size(), 0);
                    used[t] = true;
                }
                const auto v = static_cast<std::uint16_t>(c * 65535.0 + 0.5);
                std::memcpy(tiles[t].data() + (static_cast<std::size_t>(row % static_cast<int>(kTileEdge)) * kTileEdge + static_cast<std::size_t>(gx % static_cast<int>(kTileEdge))) * 2U, &v, 2U);
            }
        }
        for (std::size_t t = 0; t < tiles.size(); ++t)
            if (used[t]) (void)out.writeTile({tx0 + static_cast<int>(t), ty}, tiles[t]);
    }
    return out;
}

TileStore rasterizeStroke(const Path& path, const StrokeStyle& style, std::uint32_t width, std::uint32_t height, double tolerance) {
    const double pad = std::isfinite(style.width) ? std::fabs(style.width) * std::max(1.0, style.miter_limit) + 2.0 : 2.0;
    const Box clip{-pad, -pad, width + pad, height + pad};
    if (style.align == Align::Center) return rasterize(strokeWithin(path, style, tolerance, clip), width, height, FillRule::NonZero, tolerance);
    StrokeStyle doubled = style;
    doubled.width *= 2;
    auto stroke = rasterize(strokeWithin(path, doubled, tolerance, {clip.x0 - pad, clip.y0 - pad, clip.x1 + pad, clip.y1 + pad}), width, height, FillRule::NonZero, tolerance);
    const auto fill = rasterize(path, width, height, FillRule::NonZero, tolerance);
    for (const auto c : stroke.tileCoordinates()) {
        auto tile = *stroke.readTile(c);
        const auto f = fill.readTile(c);
        for (std::size_t i = 0; i < kTileEdge * kTileEdge; ++i) {
            std::uint16_t s = 0, m = 0;
            std::memcpy(&s, tile.data() + i * 2U, 2U);
            if (f) std::memcpy(&m, f->data() + i * 2U, 2U);
            const double keep = style.align == Align::Inside ? m / 65535.0 : 1.0 - m / 65535.0;
            const auto v = static_cast<std::uint16_t>(s * keep + 0.5);
            std::memcpy(tile.data() + i * 2U, &v, 2U);
        }
        (void)stroke.writeTile(c, tile);
    }
    return stroke;
}

}  // namespace croptop::vector
