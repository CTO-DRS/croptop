// Schneider's algorithm ("An Algorithm for Automatically Fitting Digitized Curves", Graphics Gems, 1990).
#include "curve_fit.h"

#include <algorithm>
#include <array>
#include <cmath>
#include <vector>

namespace croptop::vector {
namespace {

struct V {
    double x = 0, y = 0;
};
V operator+(V a, V b) { return {a.x + b.x, a.y + b.y}; }
V operator-(V a, V b) { return {a.x - b.x, a.y - b.y}; }
V operator*(V a, double s) { return {a.x * s, a.y * s}; }
double dot(V a, V b) { return a.x * b.x + a.y * b.y; }
double length(V a) { return std::sqrt(dot(a, a)); }
V normalized(V a) {
    const double l = length(a);
    return l > 1e-12 ? V{a.x / l, a.y / l} : V{0, 0};
}

using Bez = std::array<V, 4>;

V bezAt(const Bez& b, double t) {
    const double s = 1 - t;
    return b[0] * (s * s * s) + b[1] * (3 * s * s * t) + b[2] * (3 * s * t * t) + b[3] * (t * t * t);
}
V bezD1(const Bez& b, double t) {
    const double s = 1 - t;
    return (b[1] - b[0]) * (3 * s * s) + (b[2] - b[1]) * (6 * s * t) + (b[3] - b[2]) * (3 * t * t);
}
V bezD2(const Bez& b, double t) {
    const double s = 1 - t;
    return (b[2] - b[1] * 2 + b[0]) * (6 * s) + (b[3] - b[2] * 2 + b[1]) * (6 * t);
}

std::vector<double> chordParams(const std::vector<V>& p, std::size_t first, std::size_t last) {
    std::vector<double> u(last - first + 1, 0.0);
    for (std::size_t i = first + 1; i <= last; ++i) u[i - first] = u[i - first - 1] + length(p[i] - p[i - 1]);
    const double total = u.back();
    for (auto& v : u) v = total > 0 ? v / total : 0.0;
    return u;
}

Bez generate(const std::vector<V>& p, std::size_t first, std::size_t last, const std::vector<double>& u, V t1, V t2) {
    const auto n = last - first + 1;
    Bez b{};
    b[0] = p[first];
    b[3] = p[last];
    double c00 = 0, c01 = 0, c11 = 0, x0 = 0, x1 = 0;
    for (std::size_t i = 0; i < n; ++i) {
        const double s = 1 - u[i];
        const double b1 = 3 * u[i] * s * s, b2 = 3 * u[i] * u[i] * s;
        const V a1 = t1 * b1, a2 = t2 * b2;
        c00 += dot(a1, a1);
        c01 += dot(a1, a2);
        c11 += dot(a2, a2);
        const V tmp = p[first + i] - (b[0] * ((s * s * s) + b1) + b[3] * (b2 + u[i] * u[i] * u[i]));
        x0 += dot(a1, tmp);
        x1 += dot(a2, tmp);
    }
    const double det = c00 * c11 - c01 * c01;
    double alpha1 = 0, alpha2 = 0;
    if (std::abs(det) > 1e-12) {
        alpha1 = (x0 * c11 - x1 * c01) / det;
        alpha2 = (c00 * x1 - c01 * x0) / det;
    }
    const double seg = length(p[last] - p[first]);
    const double eps = 1e-6 * seg;
    if (alpha1 < eps || alpha2 < eps) {  // degenerate: Wu/Barsky heuristic
        alpha1 = alpha2 = seg / 3.0;
    }
    b[1] = b[0] + t1 * alpha1;
    b[2] = b[3] + t2 * alpha2;
    return b;
}

double maxError(const std::vector<V>& p, std::size_t first, std::size_t last, const Bez& b, const std::vector<double>& u, std::size_t& split) {
    double worst = 0;
    split = (first + last) / 2;
    for (std::size_t i = first + 1; i < last; ++i) {
        const double d = length(bezAt(b, u[i - first]) - p[i]);
        if (d >= worst) {
            worst = d;
            split = i;
        }
    }
    return worst;
}

std::vector<double> reparam(const std::vector<V>& p, std::size_t first, std::size_t last, const std::vector<double>& u, const Bez& b) {
    std::vector<double> out(u.size());
    for (std::size_t i = 0; i < u.size(); ++i) {
        const V d = bezAt(b, u[i]) - p[first + i];
        const V d1 = bezD1(b, u[i]), d2 = bezD2(b, u[i]);
        const double num = dot(d, d1);
        const double den = dot(d1, d1) + dot(d, d2);
        out[i] = std::abs(den) > 1e-12 ? std::clamp(u[i] - num / den, 0.0, 1.0) : u[i];
    }
    (void)last;
    return out;
}

void fit(const std::vector<V>& p, std::size_t first, std::size_t last, V t1, V t2, double tol, std::vector<Bez>& out) {
    if (last - first == 1) {  // two points: heuristic handles
        const double d = length(p[last] - p[first]) / 3.0;
        out.push_back({p[first], p[first] + t1 * d, p[last] + t2 * d, p[last]});
        return;
    }
    auto u = chordParams(p, first, last);
    Bez b = generate(p, first, last, u, t1, t2);
    std::size_t split = 0;
    double err = maxError(p, first, last, b, u, split);
    if (err < tol) {
        out.push_back(b);
        return;
    }
    if (err < tol * 16.0) {
        for (int iteration = 0; iteration < 6; ++iteration) {
            u = reparam(p, first, last, u, b);
            b = generate(p, first, last, u, t1, t2);
            err = maxError(p, first, last, b, u, split);
            if (err < tol) {
                out.push_back(b);
                return;
            }
        }
    }
    split = std::clamp<std::size_t>(split, first + 1, last - 1);
    V center = normalized(p[split - 1] - p[split + 1]);
    if (center.x == 0 && center.y == 0) center = normalized(V{-(p[split + 1] - p[split - 1]).y, (p[split + 1] - p[split - 1]).x});
    fit(p, first, split, t1, center, tol, out);
    fit(p, split, last, center * -1.0, t2, tol, out);
}

bool nearlyStraight(const std::vector<V>& p, std::size_t first, std::size_t last, double tol) {
    const V a = p[first], c = p[last];
    const V d = c - a;
    const double len = length(d);
    for (std::size_t i = first + 1; i < last; ++i) {
        const double dist = len > 1e-12 ? std::abs(d.x * (p[i].y - a.y) - d.y * (p[i].x - a.x)) / len : length(p[i] - a);
        if (dist > tol) return false;
    }
    return true;
}

void emitChunk(const std::vector<V>& p, std::size_t first, std::size_t last, V t1, V t2, const RefitOptions& o, Path& out) {
    if (last - first == 1 || nearlyStraight(p, first, last, o.line_tolerance)) {
        out.lineTo({p[last].x, p[last].y});
        return;
    }
    std::vector<Bez> curves;
    fit(p, first, last, t1, t2, o.tolerance, curves);
    for (const auto& b : curves) out.cubicTo({b[1].x, b[1].y}, {b[2].x, b[2].y}, {b[3].x, b[3].y});
}

}  // namespace

Path refit(const Path& polygons, const RefitOptions& options) {
    Path out;
    for (const auto& poly : flatten(polygons, 0.05)) {
        std::vector<V> pts;
        for (const auto& q : poly.points) {
            const V v{q.x, q.y};
            if (pts.empty() || length(v - pts.back()) > 1e-9) pts.push_back(v);
        }
        if (poly.closed && pts.size() > 1 && length(pts.front() - pts.back()) < 1e-9) pts.pop_back();
        if (pts.size() < 3 || !poly.closed) {  // open or degenerate: keep as lines
            if (pts.empty()) continue;
            out.moveTo({pts[0].x, pts[0].y});
            for (std::size_t i = 1; i < pts.size(); ++i) out.lineTo({pts[i].x, pts[i].y});
            if (poly.closed) out.close();
            continue;
        }
        const std::size_t n = pts.size();
        const double cos_limit = std::cos(options.corner_degrees * M_PI / 180.0);
        std::vector<bool> corner(n, false);
        std::size_t corners = 0;
        for (std::size_t i = 0; i < n; ++i) {
            const V a = normalized(pts[i] - pts[(i + n - 1) % n]), b = normalized(pts[(i + 1) % n] - pts[i]);
            if (dot(a, b) < cos_limit) {
                corner[i] = true;
                ++corners;
            }
        }
        // Rotate so index 0 is a corner when there is one (chunks then run corner → corner without wrapping).
        std::size_t start = 0;
        if (corners > 0) while (!corner[start]) ++start;
        std::vector<V> ring(n + 1);
        std::vector<bool> ring_corner(n + 1);
        for (std::size_t i = 0; i <= n; ++i) {
            ring[i] = pts[(start + i) % n];
            ring_corner[i] = corner[(start + i) % n];
        }
        out.moveTo({ring[0].x, ring[0].y});
        std::size_t chunk_start = 0;
        for (std::size_t i = 1; i <= n; ++i) {
            const bool end_here = corners > 0 ? (ring_corner[i] || i == n) : i == n;
            if (!end_here) continue;
            // Tangents: at a corner use the chunk's own direction, on a smooth closed curve use the neighbours around the seam.
            V t1, t2;
            if (corners > 0) {
                t1 = normalized(ring[chunk_start + 1] - ring[chunk_start]);
                t2 = normalized(ring[i - 1] - ring[i]);
            } else {
                t1 = normalized(ring[1] - ring[n - 1]);
                t2 = normalized(ring[n - 1] - ring[1]);
            }
            emitChunk(ring, chunk_start, i, t1, t2, options, out);
            chunk_start = i;
        }
        out.close();
    }
    return out;
}

}  // namespace croptop::vector
