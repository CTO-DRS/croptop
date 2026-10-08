#include "crop_geometry.h"

#include <algorithm>
#include <cmath>

namespace croptop::crop {
namespace {

bool finite(Point p) { return std::isfinite(p.x) && std::isfinite(p.y); }

double cross(Point o, Point a, Point b) { return (a.x - o.x) * (b.y - o.y) - (a.y - o.y) * (b.x - o.x); }

/** Solves the 8×8 system A·h = b with partial pivoting; false if singular. */
bool solve8(std::array<std::array<double, 9>, 8>& a, std::array<double, 8>& h) {
    for (std::size_t col = 0; col < 8; ++col) {
        std::size_t pivot = col;
        for (std::size_t row = col + 1; row < 8; ++row) {
            if (std::fabs(a[row][col]) > std::fabs(a[pivot][col])) pivot = row;
        }
        if (std::fabs(a[pivot][col]) < 1e-12) return false;
        std::swap(a[col], a[pivot]);
        for (std::size_t row = 0; row < 8; ++row) {
            if (row == col) continue;
            const auto factor = a[row][col] / a[col][col];
            for (std::size_t k = col; k < 9; ++k) a[row][k] -= factor * a[col][k];
        }
    }
    for (std::size_t i = 0; i < 8; ++i) h[i] = a[i][8] / a[i][i];
    return true;
}

}  // namespace

bool apply(const Matrix& m, Point p, Point& out) {
    const auto w = m[6] * p.x + m[7] * p.y + m[8];
    if (!(w > 1e-12)) return false;
    out = {(m[0] * p.x + m[1] * p.y + m[2]) / w, (m[3] * p.x + m[4] * p.y + m[5]) / w};
    return std::isfinite(out.x) && std::isfinite(out.y);
}

std::optional<Matrix> perspectiveCrop(const std::array<Point, 4>& quad, double width, double height, std::string& error) {
    if (!std::all_of(quad.begin(), quad.end(), finite) || !(width >= 1.0) || !(height >= 1.0) || !std::isfinite(width) ||
        !std::isfinite(height)) {
        error = "perspective crop needs four finite corners and a size of at least 1 px";
        return std::nullopt;
    }
    // Convex and non-degenerate: all turns in the same direction with a meaningful area.
    std::array<double, 4> turns{};
    for (std::size_t i = 0; i < 4; ++i) turns[i] = cross(quad[i], quad[(i + 1) % 4], quad[(i + 2) % 4]);
    const bool clockwise = std::all_of(turns.begin(), turns.end(), [](double t) { return t > 1e-9; });
    const bool counter = std::all_of(turns.begin(), turns.end(), [](double t) { return t < -1e-9; });
    if (!clockwise && !counter) {
        error = "perspective crop corners must form a convex quadrilateral";
        return std::nullopt;
    }
    const std::array<Point, 4> target = {{{0.0, 0.0}, {width, 0.0}, {width, height}, {0.0, height}}};
    // DLT with h33 = 1: for each correspondence (x, y) → (u, v):
    //   h11 x + h12 y + h13 − u h31 x − u h32 y = u,   h21 x + h22 y + h23 − v h31 x − v h32 y = v.
    std::array<std::array<double, 9>, 8> a{};
    for (std::size_t i = 0; i < 4; ++i) {
        const auto [x, y] = quad[i];
        const auto [u, v] = target[i];
        a[2 * i] = {x, y, 1.0, 0.0, 0.0, 0.0, -u * x, -u * y, u};
        a[2 * i + 1] = {0.0, 0.0, 0.0, x, y, 1.0, -v * x, -v * y, v};
    }
    std::array<double, 8> h{};
    if (!solve8(a, h)) {
        error = "perspective crop corners are degenerate";
        return std::nullopt;
    }
    Matrix m{h[0], h[1], h[2], h[3], h[4], h[5], h[6], h[7], 1.0};
    // Every quad corner must stay in front of the horizon for warp().
    for (const auto& p : quad) {
        Point mapped;
        if (!apply(m, p, mapped)) {
            error = "perspective crop corners are degenerate";
            return std::nullopt;
        }
    }
    return m;
}

std::array<std::uint32_t, 2> perspectiveCropSize(const std::array<Point, 4>& quad) {
    const auto length = [](Point a, Point b) { return std::hypot(b.x - a.x, b.y - a.y); };
    const auto w = (length(quad[0], quad[1]) + length(quad[3], quad[2])) / 2.0;
    const auto h = (length(quad[0], quad[3]) + length(quad[1], quad[2])) / 2.0;
    const auto clamp_size = [](double v) {
        return static_cast<std::uint32_t>(std::clamp(std::isfinite(v) ? std::lround(v) : 1L, 1L, 65535L));
    };
    return {clamp_size(w), clamp_size(h)};
}

std::optional<Straighten> straighten(std::uint32_t width, std::uint32_t height, double degrees, std::string& error) {
    if (width == 0U || height == 0U || !std::isfinite(degrees)) {
        error = "straighten needs a non-empty image and a finite angle";
        return std::nullopt;
    }
    const auto radians = degrees * M_PI / 180.0;
    const auto c = std::cos(radians);
    const auto s = std::sin(radians);
    const double w = width;
    const double h = height;
    // Largest centered w·k × h·k rectangle whose corners, rotated back by −θ, stay inside the image.
    const auto k = std::min({1.0, w / (w * std::fabs(c) + h * std::fabs(s)), h / (w * std::fabs(s) + h * std::fabs(c))});
    Straighten result;
    result.width = static_cast<std::uint32_t>(std::max(1.0, std::floor(w * k + 1e-9)));
    result.height = static_cast<std::uint32_t>(std::max(1.0, std::floor(h * k + 1e-9)));
    // dest = T(out center) · R(θ) · T(−source center)
    const auto cx = w / 2.0;
    const auto cy = h / 2.0;
    const auto ox = result.width / 2.0;
    const auto oy = result.height / 2.0;
    result.matrix = {c, -s, ox - (c * cx - s * cy), s, c, oy - (s * cx + c * cy), 0.0, 0.0, 1.0};
    return result;
}

}  // namespace croptop::crop
