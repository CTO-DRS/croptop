// Puppet Warp.
#include "puppet_warp.h"

#include <algorithm>
#include <atomic>
#include <cmath>
#include <limits>
#include <numeric>

#include "filters_internal.h"
#include "parallel_util.h"
#include "pixel_plane.h"

namespace croptop::puppet {
namespace {

using selection::Point;
using filters::detail::Pixel;

constexpr double kPinWeight = 1e3;    // soft pin constraint (relative to unit edge weights)
constexpr double kRestWeight = 1e-4;  // pull toward the rest pose (keeps unpinned parts in place, system SPD)
constexpr std::size_t kMaxVertices = 40000;

struct Linear final {
    double a = 1, b = 0, c = 0, d = 1;  // [a b; c d]
    Point apply(Point p) const { return {a * p.x + b * p.y, c * p.x + d * p.y}; }
};

double cross(Point a, Point b) { return a.x * b.y - a.y * b.x; }
Point sub(Point a, Point b) { return {a.x - b.x, a.y - b.y}; }

/** Barycentric weights of `p` in (a, b, c); false for degenerate triangles. */
bool barycentric(Point p, Point a, Point b, Point c, double& u, double& v, double& w) {
    const double area = cross(sub(b, a), sub(c, a));
    if (std::abs(area) < 1e-12) return false;
    u = cross(sub(b, p), sub(c, p)) / area;
    v = cross(sub(c, p), sub(a, p)) / area;
    w = 1.0 - u - v;
    return true;
}

/** Best local map of rest edges onto deformed edges for the mode. */
Linear fit(Mode mode, const std::array<Point, 3>& rest, const std::array<Point, 3>& now) {
    double sxx = 0, sxy = 0, syx = 0, syy = 0, mxx = 0, mxy = 0, myy = 0, dot = 0, crs = 0, norm = 0;
    for (int k = 0; k < 3; ++k) {
        const auto e = sub(rest[static_cast<std::size_t>((k + 1) % 3)], rest[static_cast<std::size_t>(k)]);
        const auto f = sub(now[static_cast<std::size_t>((k + 1) % 3)], now[static_cast<std::size_t>(k)]);
        sxx += f.x * e.x;
        sxy += f.x * e.y;
        syx += f.y * e.x;
        syy += f.y * e.y;
        mxx += e.x * e.x;
        mxy += e.x * e.y;
        myy += e.y * e.y;
        dot += e.x * f.x + e.y * f.y;
        crs += e.x * f.y - e.y * f.x;
        norm += e.x * e.x + e.y * e.y;
    }
    if (mode == Mode::Distort) {
        const double det = mxx * myy - mxy * mxy;
        if (std::abs(det) < 1e-12) return {};
        const double ixx = myy / det, ixy = -mxy / det, iyy = mxx / det;  // M⁻¹
        return {sxx * ixx + sxy * ixy, sxx * ixy + sxy * iyy, syx * ixx + syy * ixy, syx * ixy + syy * iyy};
    }
    if (norm < 1e-12) return {};
    double a = dot / norm, b = crs / norm;  // similarity [a −b; b a]
    if (mode == Mode::Rigid) {
        const double len = std::hypot(a, b);
        if (len < 1e-12) return {};
        a /= len;
        b /= len;
    }
    return {a, -b, b, a};
}

}  // namespace

std::unique_ptr<PuppetMesh> PuppetMesh::build(const TileStore& layer, const MeshSpec& spec, std::string& error) {
    if (!paint::detail::isRgba(layer.format())) {
        error = "puppet warp needs an RGBA layer";
        return nullptr;
    }
    if (!std::isfinite(spec.expansion) || spec.expansion < -32.0F || spec.expansion > 64.0F) {
        error = "puppet mesh expansion must be −32…64 px";
        return nullptr;
    }
    const int width = static_cast<int>(layer.width()), height = static_cast<int>(layer.height());
    // Content bounds first (sets the spacing), at pixel resolution.
    std::atomic<int> left{width}, top{height}, right{-1}, bottom{-1};
    const auto coordinates = layer.tileCoordinates();
    const auto bpp = layer.bytes_per_pixel();
    const auto opaque = [&](const TileBuffer& tile, int lx, int ly) {
        float px[4];
        paint::detail::readPixel(layer.format(), tile.data() + (static_cast<std::size_t>(ly) * kTileEdge + static_cast<std::size_t>(lx)) * bpp, px);
        return px[3] > 1.0F / 255.0F;
    };
    const auto lower = [](std::atomic<int>& a, int v) {
        for (int cur = a.load(); v < cur && !a.compare_exchange_weak(cur, v);) {
        }
    };
    const auto raise = [](std::atomic<int>& a, int v) {
        for (int cur = a.load(); v > cur && !a.compare_exchange_weak(cur, v);) {
        }
    };
    parallel::parallelFor(coordinates.size(), parallel::workerCount(0), [&](std::size_t index, std::size_t) {
        const auto c = coordinates[index];
        const auto tile = layer.readTile(c);
        if (tile == nullptr) return;
        for (int ly = 0; ly < static_cast<int>(kTileEdge); ++ly)
            for (int lx = 0; lx < static_cast<int>(kTileEdge); ++lx) {
                const int x = c.x * static_cast<int>(kTileEdge) + lx, y = c.y * static_cast<int>(kTileEdge) + ly;
                if (x >= width || y >= height || !opaque(*tile, lx, ly)) continue;
                lower(left, x);
                lower(top, y);
                raise(right, x);
                raise(bottom, y);
            }
    });
    if (right.load() < 0) {
        error = "the layer has no content to warp";
        return nullptr;
    }
    const double bw = right.load() - left.load() + 1, bh = bottom.load() - top.load() + 1;
    const double target = spec.density == Density::Fewer ? 300.0 : spec.density == Density::Normal ? 1000.0 : 3000.0;
    double spacing = std::clamp(std::sqrt(bw * bh / target), 4.0, 128.0);

    // Coverage cells (spacing / 4), dilated or eroded by the expansion.
    const int cell = std::max(1, static_cast<int>(spacing / 4.0));
    const int cw = (width + cell - 1) / cell, ch = (height + cell - 1) / cell;
    std::vector<std::atomic<std::uint8_t>> marks(static_cast<std::size_t>(cw) * ch);
    parallel::parallelFor(coordinates.size(), parallel::workerCount(0), [&](std::size_t index, std::size_t) {
        const auto c = coordinates[index];
        const auto tile = layer.readTile(c);
        if (tile == nullptr) return;
        for (int ly = 0; ly < static_cast<int>(kTileEdge); ++ly)
            for (int lx = 0; lx < static_cast<int>(kTileEdge); ++lx) {
                const int x = c.x * static_cast<int>(kTileEdge) + lx, y = c.y * static_cast<int>(kTileEdge) + ly;
                if (x >= width || y >= height || !opaque(*tile, lx, ly)) continue;
                marks[static_cast<std::size_t>(y / cell) * cw + static_cast<std::size_t>(x / cell)].store(1, std::memory_order_relaxed);
            }
    });
    std::vector<std::uint8_t> cells(marks.size());
    for (std::size_t i = 0; i < marks.size(); ++i) cells[i] = marks[i].load(std::memory_order_relaxed);
    const int radius = static_cast<int>(std::lround(std::abs(spec.expansion) / cell));
    if (radius > 0) {
        const bool grow = spec.expansion > 0;
        std::vector<std::uint8_t> tmp(cells.size());
        for (int y = 0; y < ch; ++y)
            for (int x = 0; x < cw; ++x) {
                std::uint8_t v = grow ? 0 : 1;
                for (int k = std::max(0, x - radius); k <= std::min(cw - 1, x + radius); ++k)
                    v = grow ? std::max(v, cells[static_cast<std::size_t>(y) * cw + k]) : std::min(v, cells[static_cast<std::size_t>(y) * cw + k]);
                tmp[static_cast<std::size_t>(y) * cw + x] = v;
            }
        for (int y = 0; y < ch; ++y)
            for (int x = 0; x < cw; ++x) {
                std::uint8_t v = grow ? 0 : 1;
                for (int k = std::max(0, y - radius); k <= std::min(ch - 1, y + radius); ++k)
                    v = grow ? std::max(v, tmp[static_cast<std::size_t>(k) * cw + x]) : std::min(v, tmp[static_cast<std::size_t>(k) * cw + x]);
                cells[static_cast<std::size_t>(y) * cw + x] = v;
            }
    }

    std::unique_ptr<PuppetMesh> mesh(new PuppetMesh(layer));
    for (;;) {
        const double margin = spacing + std::max(0.0, static_cast<double>(spec.expansion));
        mesh->spacing_ = spacing;
        mesh->row_height_ = spacing * std::sqrt(3.0) / 2.0;
        mesh->x0_ = left.load() - margin;
        mesh->y0_ = top.load() - margin;
        mesh->columns_ = static_cast<int>(std::ceil((bw + 2 * margin) / spacing)) + 2;
        mesh->rows_ = static_cast<int>(std::ceil((bh + 2 * margin) / mesh->row_height_)) + 2;
        if (static_cast<std::size_t>(mesh->columns_) * static_cast<std::size_t>(mesh->rows_) <= 4 * kMaxVertices || spacing >= 128.0) break;
        spacing *= 1.25;
    }
    const int per_band = 2 * (mesh->columns_ - 1);
    const auto gridVertex = [&](int i, int j) { return static_cast<std::uint32_t>(j * mesh->columns_ + i); };
    const auto gridTriangle = [&](int band, int t) -> std::array<std::uint32_t, 3> {
        const int i = t / 2;
        const bool first = t % 2 == 0;
        if (band % 2 == 0) return first ? std::array{gridVertex(i, band), gridVertex(i + 1, band), gridVertex(i, band + 1)}
                                        : std::array{gridVertex(i + 1, band), gridVertex(i + 1, band + 1), gridVertex(i, band + 1)};
        return first ? std::array{gridVertex(i, band + 1), gridVertex(i + 1, band + 1), gridVertex(i, band)}
                     : std::array{gridVertex(i, band), gridVertex(i + 1, band), gridVertex(i + 1, band + 1)};
    };
    const auto gridPoint = [&](std::uint32_t v) {
        const int i = static_cast<int>(v) % mesh->columns_, j = static_cast<int>(v) / mesh->columns_;
        return Point{mesh->x0_ + i * mesh->spacing_ + (j % 2 == 1 ? mesh->spacing_ / 2 : 0.0), mesh->y0_ + j * mesh->row_height_};
    };
    // Mark the grid triangles that contain a covered cell's centre or corners.
    std::vector<std::uint8_t> keep(static_cast<std::size_t>(per_band) * static_cast<std::size_t>(mesh->rows_ - 1), 0);
    const auto markAt = [&](Point p) {
        const int band = static_cast<int>(std::floor((p.y - mesh->y0_) / mesh->row_height_));
        if (band < 0 || band >= mesh->rows_ - 1) return;
        const int i = static_cast<int>(std::floor((p.x - mesh->x0_) / mesh->spacing_));
        for (int t = 2 * (i - 1); t <= 2 * (i + 1) + 1; ++t) {
            if (t < 0 || t >= per_band) continue;
            const auto tri = gridTriangle(band, t);
            double u, v, w;
            if (barycentric(p, gridPoint(tri[0]), gridPoint(tri[1]), gridPoint(tri[2]), u, v, w) && u >= -1e-9 && v >= -1e-9 && w >= -1e-9)
                keep[static_cast<std::size_t>(band) * per_band + static_cast<std::size_t>(t)] = 1;
        }
    };
    for (int y = 0; y < ch; ++y)
        for (int x = 0; x < cw; ++x) {
            if (cells[static_cast<std::size_t>(y) * cw + x] == 0) continue;
            const double cx = x * cell, cy = y * cell;
            const double x1 = std::min<double>(width, cx + cell), y1 = std::min<double>(height, cy + cell);
            markAt({(cx + x1) / 2, (cy + y1) / 2});
            markAt({cx, cy});
            markAt({x1, cy});
            markAt({cx, y1});
            markAt({x1, y1});
        }
    // Compact: used vertices and kept triangles.
    std::vector<std::int32_t> remap(static_cast<std::size_t>(mesh->columns_) * mesh->rows_, -1);
    mesh->triangle_at_.assign(keep.size(), -1);
    for (int band = 0; band < mesh->rows_ - 1; ++band)
        for (int t = 0; t < per_band; ++t) {
            const auto slot = static_cast<std::size_t>(band) * per_band + static_cast<std::size_t>(t);
            if (keep[slot] == 0) continue;
            auto tri = gridTriangle(band, t);
            for (auto& v : tri) {
                if (remap[v] < 0) {
                    remap[v] = static_cast<std::int32_t>(mesh->rest_.size());
                    mesh->rest_.push_back(gridPoint(v));
                }
                v = static_cast<std::uint32_t>(remap[v]);
            }
            mesh->triangle_at_[slot] = static_cast<std::int32_t>(mesh->triangles_.size());
            mesh->triangles_.push_back(tri);
        }
    if (mesh->triangles_.empty()) {
        error = "the layer's content is too small for a mesh";
        return nullptr;
    }
    mesh->deformed_ = mesh->rest_;
    return mesh;
}

int PuppetMesh::locate(Point p, double& u, double& v, double& w) const {
    const int band = static_cast<int>(std::floor((p.y - y0_) / row_height_));
    if (band < 0 || band >= rows_ - 1) return -1;
    const int per_band = 2 * (columns_ - 1);
    const int i = static_cast<int>(std::floor((p.x - x0_) / spacing_));
    for (int t = 2 * (i - 1); t <= 2 * (i + 1) + 1; ++t) {
        if (t < 0 || t >= per_band) continue;
        const auto id = triangle_at_[static_cast<std::size_t>(band) * per_band + static_cast<std::size_t>(t)];
        if (id < 0) continue;
        const auto& tri = triangles_[static_cast<std::size_t>(id)];
        if (barycentric(p, rest_[tri[0]], rest_[tri[1]], rest_[tri[2]], u, v, w) && u >= -1e-9 && v >= -1e-9 && w >= -1e-9) return id;
    }
    return -1;
}

int PuppetMesh::addPin(Point at) {
    if (!std::isfinite(at.x) || !std::isfinite(at.y)) return -1;
    Attached a;
    const int id = locate(at, a.u, a.v, a.w);
    if (id < 0) return -1;
    a.triangle = static_cast<std::uint32_t>(id);
    // The pin starts where the deformed mesh currently shows that point.
    const auto& tri = triangles_[a.triangle];
    a.pin.target = {a.u * deformed_[tri[0]].x + a.v * deformed_[tri[1]].x + a.w * deformed_[tri[2]].x,
                    a.u * deformed_[tri[0]].y + a.v * deformed_[tri[1]].y + a.w * deformed_[tri[2]].y};
    pins_.push_back(a);
    system_dirty_ = true;
    return static_cast<int>(pins_.size() - 1);
}

bool PuppetMesh::removePin(int index) {
    if (index < 0 || static_cast<std::size_t>(index) >= pins_.size()) return false;
    pins_.erase(pins_.begin() + index);
    system_dirty_ = true;
    return true;
}

Point PuppetMesh::pinRest(int index) const {
    const auto& a = pins_.at(static_cast<std::size_t>(index));
    const auto& tri = triangles_[a.triangle];
    return {a.u * rest_[tri[0]].x + a.v * rest_[tri[1]].x + a.w * rest_[tri[2]].x, a.u * rest_[tri[0]].y + a.v * rest_[tri[1]].y + a.w * rest_[tri[2]].y};
}

void PuppetMesh::buildSystem() {
    const std::size_t n = rest_.size();
    std::vector<std::vector<std::pair<std::uint32_t, double>>> rows(n);
    const auto add = [&](std::uint32_t i, std::uint32_t j, double value) {
        for (auto& entry : rows[i])
            if (entry.first == j) {
                entry.second += value;
                return;
            }
        rows[i].emplace_back(j, value);
    };
    for (std::uint32_t i = 0; i < n; ++i) add(i, i, kRestWeight);
    for (const auto& tri : triangles_)
        for (int k = 0; k < 3; ++k) {
            const auto i = tri[static_cast<std::size_t>(k)], j = tri[static_cast<std::size_t>((k + 1) % 3)];
            add(i, i, 1);
            add(j, j, 1);
            add(i, j, -1);
            add(j, i, -1);
        }
    for (const auto& a : pins_) {
        const auto& tri = triangles_[a.triangle];
        const double b[3] = {a.u, a.v, a.w};
        for (int r = 0; r < 3; ++r)
            for (int c = 0; c < 3; ++c) add(tri[static_cast<std::size_t>(r)], tri[static_cast<std::size_t>(c)], kPinWeight * b[r] * b[c]);
    }
    row_start_.assign(n + 1, 0);
    column_.clear();
    value_.clear();
    diagonal_.assign(n, 1);
    for (std::size_t i = 0; i < n; ++i) {
        row_start_[i] = static_cast<std::uint32_t>(column_.size());
        for (const auto& [j, value] : rows[i]) {
            column_.push_back(j);
            value_.push_back(value);
            if (j == i) diagonal_[i] = value;
        }
    }
    row_start_[n] = static_cast<std::uint32_t>(column_.size());
    system_dirty_ = false;
}

int PuppetMesh::solve(Mode mode, int max_iterations, double tolerance) {
    if (system_dirty_) buildSystem();
    const std::size_t n = rest_.size();
    max_iterations = std::clamp(max_iterations, 1, 256);
    int rounds = 0;
    std::vector<Linear> local(triangles_.size());
    std::vector<double> bx(n), by(n), rx(n), ry(n), zx(n), zy(n), px(n), py(n), qx(n), qy(n), x(n), y(n);
    for (std::size_t i = 0; i < n; ++i) {
        x[i] = deformed_[i].x;
        y[i] = deformed_[i].y;
    }
    const auto multiply = [&](const std::vector<double>& in, std::vector<double>& out) {
        for (std::size_t i = 0; i < n; ++i) {
            double sum = 0;
            for (auto k = row_start_[i]; k < row_start_[i + 1]; ++k) sum += value_[k] * in[column_[k]];
            out[i] = sum;
        }
    };
    std::vector<double> last_x, last_y;
    for (; rounds < max_iterations; ++rounds) {
        last_x = x;
        last_y = y;
        // Local step.
        for (std::size_t t = 0; t < triangles_.size(); ++t) {
            const auto& tri = triangles_[t];
            local[t] = fit(mode, {rest_[tri[0]], rest_[tri[1]], rest_[tri[2]]}, {Point{x[tri[0]], y[tri[0]]}, Point{x[tri[1]], y[tri[1]]}, Point{x[tri[2]], y[tri[2]]}});
        }
        // Right-hand side.
        for (std::size_t i = 0; i < n; ++i) {
            bx[i] = kRestWeight * rest_[i].x;
            by[i] = kRestWeight * rest_[i].y;
        }
        for (std::size_t t = 0; t < triangles_.size(); ++t) {
            const auto& tri = triangles_[t];
            for (int k = 0; k < 3; ++k) {
                const auto i = tri[static_cast<std::size_t>(k)], j = tri[static_cast<std::size_t>((k + 1) % 3)];
                const auto e = local[t].apply(sub(rest_[i], rest_[j]));
                bx[i] += e.x;
                by[i] += e.y;
                bx[j] -= e.x;
                by[j] -= e.y;
            }
        }
        for (const auto& a : pins_) {
            const auto& tri = triangles_[a.triangle];
            const double b[3] = {a.u, a.v, a.w};
            for (int r = 0; r < 3; ++r) {
                bx[tri[static_cast<std::size_t>(r)]] += kPinWeight * b[r] * a.pin.target.x;
                by[tri[static_cast<std::size_t>(r)]] += kPinWeight * b[r] * a.pin.target.y;
            }
        }
        // Global step: Jacobi-preconditioned conjugate gradients on both coordinates, warm-started.
        multiply(x, qx);
        multiply(y, qy);
        double norm_b = 0, rz_x = 0, rz_y = 0;
        for (std::size_t i = 0; i < n; ++i) {
            rx[i] = bx[i] - qx[i];
            ry[i] = by[i] - qy[i];
            zx[i] = rx[i] / diagonal_[i];
            zy[i] = ry[i] / diagonal_[i];
            px[i] = zx[i];
            py[i] = zy[i];
            rz_x += rx[i] * zx[i];
            rz_y += ry[i] * zy[i];
            norm_b += bx[i] * bx[i] + by[i] * by[i];
        }
        const double cg_tolerance = 1e-12 * std::max(1.0, norm_b);
        const int max_steps = static_cast<int>(std::min<std::size_t>(2000, 4 * n + 50));
        for (int step = 0; step < max_steps; ++step) {
            double rr = 0;
            for (std::size_t i = 0; i < n; ++i) rr += rx[i] * rx[i] + ry[i] * ry[i];
            if (rr <= cg_tolerance) break;
            multiply(px, qx);
            multiply(py, qy);
            double pq_x = 0, pq_y = 0;
            for (std::size_t i = 0; i < n; ++i) {
                pq_x += px[i] * qx[i];
                pq_y += py[i] * qy[i];
            }
            const double ax = pq_x > 0 ? rz_x / pq_x : 0, ay = pq_y > 0 ? rz_y / pq_y : 0;
            double nz_x = 0, nz_y = 0;
            for (std::size_t i = 0; i < n; ++i) {
                x[i] += ax * px[i];
                y[i] += ay * py[i];
                rx[i] -= ax * qx[i];
                ry[i] -= ay * qy[i];
                zx[i] = rx[i] / diagonal_[i];
                zy[i] = ry[i] / diagonal_[i];
                nz_x += rx[i] * zx[i];
                nz_y += ry[i] * zy[i];
            }
            const double beta_x = rz_x > 0 ? nz_x / rz_x : 0, beta_y = rz_y > 0 ? nz_y / rz_y : 0;
            rz_x = nz_x;
            rz_y = nz_y;
            for (std::size_t i = 0; i < n; ++i) {
                px[i] = zx[i] + beta_x * px[i];
                py[i] = zy[i] + beta_y * py[i];
            }
        }
        double moved = 0;
        for (std::size_t i = 0; i < n; ++i) moved = std::max(moved, std::hypot(x[i] - last_x[i], y[i] - last_y[i]));
        if (moved < tolerance) {
            ++rounds;
            break;
        }
    }
    for (std::size_t i = 0; i < n; ++i) deformed_[i] = {x[i], y[i]};
    return rounds;
}

std::optional<TileStore> PuppetMesh::render(unsigned worker_threads, std::string& error) const {
    const int width = static_cast<int>(source_.width()), height = static_cast<int>(source_.height());
    const int edge = static_cast<int>(kTileEdge);
    const int columns = (width + edge - 1) / edge, rows = (height + edge - 1) / edge;
    // Draw order: triangles nearest a higher-depth pin (in the rest pose) go last.
    std::vector<int> depth(triangles_.size(), 0);
    if (!pins_.empty())
        for (std::size_t t = 0; t < triangles_.size(); ++t) {
            const auto& tri = triangles_[t];
            const Point centre{(rest_[tri[0]].x + rest_[tri[1]].x + rest_[tri[2]].x) / 3, (rest_[tri[0]].y + rest_[tri[1]].y + rest_[tri[2]].y) / 3};
            double best = std::numeric_limits<double>::max();
            for (std::size_t p = 0; p < pins_.size(); ++p) {
                const auto r = pinRest(static_cast<int>(p));
                const double d = std::hypot(r.x - centre.x, r.y - centre.y);
                if (d < best) {
                    best = d;
                    depth[t] = pins_[p].pin.depth;
                }
            }
        }
    std::vector<std::uint32_t> order(triangles_.size());
    std::iota(order.begin(), order.end(), 0U);
    std::stable_sort(order.begin(), order.end(), [&](std::uint32_t a, std::uint32_t b) { return depth[a] < depth[b]; });
    // Bin triangles by output tile (in draw order).
    std::vector<std::vector<std::uint32_t>> bins(static_cast<std::size_t>(columns) * rows);
    for (const auto t : order) {
        const auto& tri = triangles_[t];
        double x0 = deformed_[tri[0]].x, x1 = x0, y0 = deformed_[tri[0]].y, y1 = y0;
        for (int k = 1; k < 3; ++k) {
            x0 = std::min(x0, deformed_[tri[static_cast<std::size_t>(k)]].x);
            x1 = std::max(x1, deformed_[tri[static_cast<std::size_t>(k)]].x);
            y0 = std::min(y0, deformed_[tri[static_cast<std::size_t>(k)]].y);
            y1 = std::max(y1, deformed_[tri[static_cast<std::size_t>(k)]].y);
        }
        if (!std::isfinite(x0) || !std::isfinite(x1) || !std::isfinite(y0) || !std::isfinite(y1) || x1 < 0 || y1 < 0 || x0 >= width || y0 >= height) continue;
        const int tx0 = std::max(0, static_cast<int>(x0) / edge), tx1 = std::min(columns - 1, static_cast<int>(x1) / edge);
        const int ty0 = std::max(0, static_cast<int>(y0) / edge), ty1 = std::min(rows - 1, static_cast<int>(y1) / edge);
        for (int ty = ty0; ty <= ty1; ++ty)
            for (int tx = tx0; tx <= tx1; ++tx) bins[static_cast<std::size_t>(ty) * columns + tx].push_back(t);
    }
    TileStore out(source_.format(), source_.width(), source_.height());
    std::atomic<bool> failed{false};
    parallel::parallelFor(bins.size(), parallel::workerCount(worker_threads), [&](std::size_t index, std::size_t) {
        if (failed.load() || bins[index].empty()) return;
        const int tx = static_cast<int>(index) % columns, ty = static_cast<int>(index) / columns;
        filters::detail::Sampler sampler(source_);
        std::vector<float> plane(static_cast<std::size_t>(edge) * edge * 4, 0.0F);
        bool any = false;
        for (const auto t : bins[index]) {
            const auto& tri = triangles_[t];
            const Point a = deformed_[tri[0]], b = deformed_[tri[1]], c = deformed_[tri[2]];
            const double area = cross(sub(b, a), sub(c, a));
            if (std::abs(area) < 1e-9) continue;
            const int px0 = std::max(tx * edge, static_cast<int>(std::floor(std::min({a.x, b.x, c.x}))));
            const int px1 = std::min(std::min(width, (tx + 1) * edge) - 1, static_cast<int>(std::ceil(std::max({a.x, b.x, c.x}))));
            const int py0 = std::max(ty * edge, static_cast<int>(std::floor(std::min({a.y, b.y, c.y}))));
            const int py1 = std::min(std::min(height, (ty + 1) * edge) - 1, static_cast<int>(std::ceil(std::max({a.y, b.y, c.y}))));
            constexpr double kInside = -1e-7;
            for (int y = py0; y <= py1; ++y)
                for (int x = px0; x <= px1; ++x) {
                    const Point p{x + 0.5, y + 0.5};
                    const double u = cross(sub(b, p), sub(c, p)) / area, v = cross(sub(c, p), sub(a, p)) / area, w = 1.0 - u - v;
                    if (u < kInside || v < kInside || w < kInside) continue;
                    const double sx = u * rest_[tri[0]].x + v * rest_[tri[1]].x + w * rest_[tri[2]].x;
                    const double sy = u * rest_[tri[0]].y + v * rest_[tri[1]].y + w * rest_[tri[2]].y;
                    Pixel value{};
                    if (sx >= 0 && sy >= 0 && sx <= width && sy <= height) value = sampler.bilinear(sx, sy);
                    std::copy(value.begin(), value.end(), plane.begin() + (static_cast<std::ptrdiff_t>(y - ty * edge) * edge + (x - tx * edge)) * 4);
                    any = true;
                }
        }
        if (!any) return;
        TileBuffer tile(out.tile_byte_size(), 0);
        const auto bpp = out.bytes_per_pixel();
        for (std::size_t i = 0; i < static_cast<std::size_t>(edge) * edge; ++i) {
            const auto straight = filters::detail::unpremultiply({plane[i * 4], plane[i * 4 + 1], plane[i * 4 + 2], plane[i * 4 + 3]});
            paint::detail::writePixel(out.format(), tile.data() + i * bpp, straight.data());
        }
        if (!out.writeTile({tx, ty}, tile)) failed.store(true);
    });
    if (failed.load()) {
        error = "could not store warped pixels";
        return std::nullopt;
    }
    return out;
}

}  // namespace croptop::puppet
