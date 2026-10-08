// Magnetic Lasso (live-wire) selection.
#include "selection_magnetic.h"

#include <algorithm>
#include <cmath>
#include <limits>
#include <memory>
#include <queue>

#include "pixel_plane.h"

namespace croptop::selection {
namespace {

constexpr int kMaxWindow = 1024;

/** Luminance, normalised gradient and edge cost over a pixel window [x0, x1) × [y0, y1). */
struct EdgeWindow {
    int x0 = 0, y0 = 0, w = 0, h = 0;
    std::vector<float> lum, gradient, cost;
    std::size_t at(int x, int y) const { return static_cast<std::size_t>(y - y0) * static_cast<std::size_t>(w) + static_cast<std::size_t>(x - x0); }
    bool contains(int x, int y) const { return x >= x0 && y >= y0 && x < x0 + w && y < y0 + h; }
};

EdgeWindow edgeWindow(const TileStore& image, int x0, int y0, int x1, int y1, float contrast) {
    EdgeWindow e;
    e.x0 = x0;
    e.y0 = y0;
    e.w = std::max(0, x1 - x0);
    e.h = std::max(0, y1 - y0);
    const auto n = static_cast<std::size_t>(e.w) * static_cast<std::size_t>(e.h);
    // One pixel of context around the window for the Sobel/Laplacian stencils.
    const auto plane = paint::detail::readPlane(image, x0 - 1, y0 - 1, e.w + 2, e.h + 2);
    std::vector<float> l(static_cast<std::size_t>(e.w + 2) * static_cast<std::size_t>(e.h + 2));
    for (int y = 0; y < e.h + 2; ++y)
        for (int x = 0; x < e.w + 2; ++x) {
            const float* p = plane.at(x, y);
            const float a = std::clamp(p[3], 0.0F, 1.0F);
            l[static_cast<std::size_t>(y) * static_cast<std::size_t>(e.w + 2) + static_cast<std::size_t>(x)] =
                a * (0.2126F * p[0] + 0.7152F * p[1] + 0.0722F * p[2]);  // transparency reads as dark
        }
    const auto L = [&](int x, int y) {
        x = std::clamp(x, -1, e.w);
        y = std::clamp(y, -1, e.h);
        return l[static_cast<std::size_t>(y + 1) * static_cast<std::size_t>(e.w + 2) + static_cast<std::size_t>(x + 1)];
    };
    e.lum.resize(n);
    e.gradient.resize(n);
    e.cost.resize(n);
    std::vector<float> lap(n);
    for (int y = 0; y < e.h; ++y)
        for (int x = 0; x < e.w; ++x) {
            const float gx = (L(x + 1, y - 1) + 2 * L(x + 1, y) + L(x + 1, y + 1)) - (L(x - 1, y - 1) + 2 * L(x - 1, y) + L(x - 1, y + 1));
            const float gy = (L(x - 1, y + 1) + 2 * L(x, y + 1) + L(x + 1, y + 1)) - (L(x - 1, y - 1) + 2 * L(x, y - 1) + L(x + 1, y - 1));
            const auto i = static_cast<std::size_t>(y) * static_cast<std::size_t>(e.w) + static_cast<std::size_t>(x);
            e.lum[i] = L(x, y);
            e.gradient[i] = std::hypot(gx, gy) / 4.0F;  // a unit luminance step ≈ 1
            lap[i] = L(x - 1, y) + L(x + 1, y) + L(x, y - 1) + L(x, y + 1) - 4 * L(x, y);
        }
    for (int y = 0; y < e.h; ++y)
        for (int x = 0; x < e.w; ++x) {
            const auto i = static_cast<std::size_t>(y) * static_cast<std::size_t>(e.w) + static_cast<std::size_t>(x);
            // Laplacian zero crossing: sign change to a 4-neighbour, taking the pixel nearer zero.
            bool zero = false;
            for (const auto& [dx, dy] : {std::pair{1, 0}, std::pair{0, 1}, std::pair{-1, 0}, std::pair{0, -1}}) {
                const int nx = x + dx, ny = y + dy;
                if (nx < 0 || ny < 0 || nx >= e.w || ny >= e.h) continue;
                const float o = lap[static_cast<std::size_t>(ny) * static_cast<std::size_t>(e.w) + static_cast<std::size_t>(nx)];
                if ((lap[i] > 0) != (o > 0) && std::abs(lap[i]) <= std::abs(o)) zero = true;
            }
            const float g = e.gradient[i];
            const float fg = g < contrast ? 1.0F : 1.0F - std::min(1.0F, (g - contrast) / std::max(1e-3F, 1.0F - contrast));
            const float fz = zero && g >= contrast ? 0.0F : 1.0F;
            e.cost[i] = 0.3F * fz + 0.7F * fg;
        }
    return e;
}

std::vector<Point> straight(Point a, Point b) {
    const double len = std::hypot(b.x - a.x, b.y - a.y);
    const int steps = std::max(1, static_cast<int>(std::ceil(len)));
    std::vector<Point> out;
    for (int k = 0; k <= steps; ++k) out.push_back({a.x + (b.x - a.x) * k / steps, a.y + (b.y - a.y) * k / steps});
    return out;
}

bool finite(Point p) { return std::isfinite(p.x) && std::isfinite(p.y); }

}  // namespace

std::unique_ptr<MagneticLasso> MagneticLasso::begin(const TileStore& image, Point seed, const MagneticSpec& spec, std::string& error) {
    if (!paint::detail::isRgba(image.format()) || image.width() < 2 || image.height() < 2) {
        error = "Magnetic Lasso needs an RGBA image";
        return nullptr;
    }
    if (!std::isfinite(spec.width) || spec.width < 1 || spec.width > 256 || !std::isfinite(spec.contrast) || spec.contrast < 0.01F || spec.contrast > 1 ||
        !std::isfinite(spec.frequency) || spec.frequency < 0 || spec.frequency > 100 || !finite(seed)) {
        error = "Magnetic Lasso width must be 1–256 px, contrast 0.01–1, frequency 0–100, seed finite";
        return nullptr;
    }
    std::unique_ptr<MagneticLasso> lasso(new MagneticLasso(image, spec));
    lasso->committed_.push_back(lasso->snap(seed));
    lasso->fasteners_.push_back(0);
    return lasso;
}

Point MagneticLasso::snap(Point p) const {
    const double W = image_.width(), H = image_.height();
    p = {std::clamp(p.x, 0.5, W - 0.5), std::clamp(p.y, 0.5, H - 0.5)};
    const int r = static_cast<int>(std::ceil(spec_.width));
    const int cx = static_cast<int>(p.x), cy = static_cast<int>(p.y);
    const auto e = edgeWindow(image_, std::max(0, cx - r), std::max(0, cy - r), std::min(static_cast<int>(W), cx + r + 1), std::min(static_cast<int>(H), cy + r + 1), spec_.contrast);
    float best = spec_.contrast;
    Point snapped = p;
    double best_distance = 1e30;
    for (int y = e.y0; y < e.y0 + e.h; ++y)
        for (int x = e.x0; x < e.x0 + e.w; ++x) {
            const double d = std::hypot(x + 0.5 - p.x, y + 0.5 - p.y);
            if (d > spec_.width) continue;
            const float g = e.gradient[e.at(x, y)];
            if (g > best + 1e-6F || (std::abs(g - best) <= 1e-6F && g >= spec_.contrast && d < best_distance)) {
                best = g;
                best_distance = d;
                snapped = {x + 0.5, y + 0.5};
            }
        }
    return snapped;
}

std::vector<Point> MagneticLasso::liveWire(Point from, Point to) {
    const int W = static_cast<int>(image_.width()), H = static_cast<int>(image_.height());
    const int fx = std::clamp(static_cast<int>(from.x), 0, W - 1), fy = std::clamp(static_cast<int>(from.y), 0, H - 1);
    const int tx = std::clamp(static_cast<int>(to.x), 0, W - 1), ty = std::clamp(static_cast<int>(to.y), 0, H - 1);
    const int margin = std::max(32, static_cast<int>(spec_.width) * 2);
    const int x0 = std::max(0, std::min(fx, tx) - margin), y0 = std::max(0, std::min(fy, ty) - margin);
    const int x1 = std::min(W, std::max(fx, tx) + margin + 1), y1 = std::min(H, std::max(fy, ty) + margin + 1);
    if (x1 - x0 > kMaxWindow || y1 - y0 > kMaxWindow) return straight(from, to);  // too far apart: a straight edge
    // Reuse the cached edge window when it covers this search; otherwise build one with slack so the next
    // cursor moves (usually small) hit the cache.
    auto cached = std::static_pointer_cast<const EdgeWindow>(window_cache_);
    if (!cached || x0 < cached->x0 || y0 < cached->y0 || x1 > cached->x0 + cached->w || y1 > cached->y0 + cached->h) {
        const int slack = 128;
        const int cx0 = std::max(0, x0 - slack), cy0 = std::max(0, y0 - slack);
        const int cx1 = std::min(W, std::max(x1 + slack, cx0 + 1)), cy1 = std::min(H, std::max(y1 + slack, cy0 + 1));
        const bool fits = cx1 - cx0 <= kMaxWindow && cy1 - cy0 <= kMaxWindow;
        cached = std::make_shared<EdgeWindow>(fits ? edgeWindow(image_, cx0, cy0, cx1, cy1, spec_.contrast) : edgeWindow(image_, x0, y0, x1, y1, spec_.contrast));
        window_cache_ = cached;
    }
    const EdgeWindow& e = *cached;
    const auto n = static_cast<std::size_t>(e.w) * static_cast<std::size_t>(e.h);
    std::vector<float> dist(n, std::numeric_limits<float>::infinity());
    std::vector<std::int32_t> parent(n, -1);
    using Node = std::pair<float, std::uint32_t>;
    std::priority_queue<Node, std::vector<Node>, std::greater<>> queue;
    const auto start = e.at(fx, fy), goal = e.at(tx, ty);
    constexpr float kDiagonal = 1.41421356F, kLength = 0.05F;  // small length term keeps flat-area paths short
    // A*: every step costs at least kLength per unit length, so kLength × distance never overestimates.
    const auto heuristic = [&](std::size_t i) {
        const float x = static_cast<float>(e.x0 + static_cast<int>(i % static_cast<std::size_t>(e.w))), y = static_cast<float>(e.y0 + static_cast<int>(i / static_cast<std::size_t>(e.w)));
        return kLength * std::hypot(x - static_cast<float>(tx), y - static_cast<float>(ty));
    };
    dist[start] = 0;
    queue.push({heuristic(start), static_cast<std::uint32_t>(start)});
    while (!queue.empty()) {
        const auto [f, index] = queue.top();
        queue.pop();
        const float d = dist[index];
        if (f > d + heuristic(index) + 1e-4F) continue;  // stale entry
        if (index == goal) break;
        const int x = e.x0 + static_cast<int>(index % static_cast<std::uint32_t>(e.w)), y = e.y0 + static_cast<int>(index / static_cast<std::uint32_t>(e.w));
        for (int dy = -1; dy <= 1; ++dy)
            for (int dx = -1; dx <= 1; ++dx) {
                if ((dx == 0 && dy == 0) || !e.contains(x + dx, y + dy)) continue;
                const auto q = e.at(x + dx, y + dy);
                const float step = (dx != 0 && dy != 0) ? kDiagonal : 1.0F;
                const float nd = d + (e.cost[q] + kLength) * step;
                if (nd < dist[q]) {
                    dist[q] = nd;
                    parent[q] = static_cast<std::int32_t>(index);
                    queue.push({nd + heuristic(q), static_cast<std::uint32_t>(q)});
                }
            }
    }
    if (parent[goal] < 0 && goal != start) return straight(from, to);
    std::vector<Point> path;
    for (std::int64_t i = static_cast<std::int64_t>(goal); i >= 0; i = parent[static_cast<std::size_t>(i)]) {
        path.push_back({e.x0 + static_cast<double>(static_cast<std::size_t>(i) % static_cast<std::size_t>(e.w)) + 0.5,
                        e.y0 + static_cast<double>(static_cast<std::size_t>(i) / static_cast<std::size_t>(e.w)) + 0.5});
        if (static_cast<std::size_t>(i) == start) break;
    }
    std::reverse(path.begin(), path.end());
    return path;
}

std::vector<Point> MagneticLasso::update(Point cursor) {
    if (!finite(cursor)) return {};
    const Point target = snap(cursor);
    auto wire = liveWire(committed_.back(), target);
    if (spec_.frequency <= 0) return wire;
    // Automatic fastening points every `spacing` px of live wire.
    const double spacing = 16.0 + (100.0 - spec_.frequency) * 2.4;
    for (int guard = 0; guard < 1000; ++guard) {
        double length = 0;
        std::size_t cut = 0;
        for (std::size_t i = 1; i < wire.size(); ++i) {
            length += std::hypot(wire[i].x - wire[i - 1].x, wire[i].y - wire[i - 1].y);
            if (length >= spacing) {
                cut = i;
                break;
            }
        }
        if (cut == 0) break;
        committed_.insert(committed_.end(), wire.begin() + 1, wire.begin() + static_cast<std::ptrdiff_t>(cut) + 1);
        fasteners_.push_back(committed_.size() - 1);
        wire.erase(wire.begin(), wire.begin() + static_cast<std::ptrdiff_t>(cut));
    }
    return wire;
}

void MagneticLasso::fasten(Point point) {
    if (!finite(point)) return;
    const auto wire = liveWire(committed_.back(), snap(point));
    if (wire.size() > 1) committed_.insert(committed_.end(), wire.begin() + 1, wire.end());
    fasteners_.push_back(committed_.size() - 1);
}

bool MagneticLasso::undoFasten() {
    if (fasteners_.size() <= 1) return false;
    fasteners_.pop_back();
    committed_.resize(fasteners_.back() + 1);
    return true;
}

std::optional<TileStore> MagneticLasso::close(bool antialias, std::string& error) {
    auto polygon = committed_;
    const auto back = liveWire(committed_.back(), committed_.front());
    if (back.size() > 1) polygon.insert(polygon.end(), back.begin() + 1, back.end());
    if (polygon.size() < 3) {
        error = "the outline needs at least three points";
        return std::nullopt;
    }
    return rasterizePolygon(image_, polygon, antialias);
}

}  // namespace croptop::selection
