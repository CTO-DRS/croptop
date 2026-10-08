// Mesh Warp; see mesh_warp.h.
#include "mesh_warp.h"

#include <algorithm>
#include <array>
#include <cmath>
#include <limits>

#include "liquify.h"
#include "parallel_util.h"

namespace croptop::warp {
namespace {

/** Catmull-Rom weights (value and derivative) of P0..P3 at t in [0, 1] between P1 and P2. */
struct Basis final {
    std::array<double, 4> w;
    std::array<double, 4> d;
};

Basis basis(double t) {
    const double t2 = t * t, t3 = t2 * t;
    return {{0.5 * (-t3 + 2 * t2 - t), 0.5 * (3 * t3 - 5 * t2 + 2), 0.5 * (-3 * t3 + 4 * t2 + t), 0.5 * (t3 - t2)},
            {0.5 * (-3 * t2 + 4 * t - 1), 0.5 * (9 * t2 - 10 * t), 0.5 * (-9 * t2 + 8 * t + 1), 0.5 * (3 * t2 - 2 * t)}};
}

struct Eval final {
    Point value;
    Point du;  // d/du
    Point dv;  // d/dv
};

/** Node (i, j) with linear extrapolation one step past the lattice, so a uniform lattice stays exactly linear at its border cells. */
Point nodeAt(const Mesh& mesh, std::int64_t i, std::int64_t j) {
    const auto cols = static_cast<std::int64_t>(mesh.columns), rows = static_cast<std::int64_t>(mesh.rows);
    if (i < 0 || i >= rows) {
        const std::int64_t edge = i < 0 ? 0 : rows - 1, inner = i < 0 ? 1 : rows - 2;
        const Point a = nodeAt(mesh, edge, j), b = nodeAt(mesh, inner, j);
        return {2 * a.x - b.x, 2 * a.y - b.y};
    }
    if (j < 0 || j >= cols) {
        const std::int64_t edge = j < 0 ? 0 : cols - 1, inner = j < 0 ? 1 : cols - 2;
        const Point a = mesh.nodes[static_cast<std::size_t>(i * cols + edge)], b = mesh.nodes[static_cast<std::size_t>(i * cols + inner)];
        return {2 * a.x - b.x, 2 * a.y - b.y};
    }
    return mesh.nodes[static_cast<std::size_t>(i * cols + j)];
}

/**
 * Evaluates the surface at lattice coordinate (u, v). Inside [0, columns−1] × [0, rows−1] this is the Catmull-Rom surface;
 * outside, the border cell's polynomial continues (exactly linear for a uniform lattice), which the field needs a little
 * beyond the source rectangle.
 */
Eval evaluate(const Mesh& mesh, double u, double v) {
    const auto cols = static_cast<std::int64_t>(mesh.columns), rows = static_cast<std::int64_t>(mesh.rows);
    const std::int64_t j0 = std::clamp<std::int64_t>(static_cast<std::int64_t>(std::floor(u)), 0, cols - 2);
    const std::int64_t i0 = std::clamp<std::int64_t>(static_cast<std::int64_t>(std::floor(v)), 0, rows - 2);
    const auto bu = basis(u - static_cast<double>(j0)), bv = basis(v - static_cast<double>(i0));
    Eval out;
    for (int r = 0; r < 4; ++r) {
        const std::int64_t i = i0 - 1 + r;
        Point row{}, row_du{};
        for (int c = 0; c < 4; ++c) {
            const Point n = nodeAt(mesh, i, j0 - 1 + c);
            row.x += bu.w[static_cast<std::size_t>(c)] * n.x;
            row.y += bu.w[static_cast<std::size_t>(c)] * n.y;
            row_du.x += bu.d[static_cast<std::size_t>(c)] * n.x;
            row_du.y += bu.d[static_cast<std::size_t>(c)] * n.y;
        }
        out.value.x += bv.w[static_cast<std::size_t>(r)] * row.x;
        out.value.y += bv.w[static_cast<std::size_t>(r)] * row.y;
        out.du.x += bv.w[static_cast<std::size_t>(r)] * row_du.x;
        out.du.y += bv.w[static_cast<std::size_t>(r)] * row_du.y;
        out.dv.x += bv.d[static_cast<std::size_t>(r)] * row.x;
        out.dv.y += bv.d[static_cast<std::size_t>(r)] * row.y;
    }
    return out;
}

}  // namespace

Mesh Mesh::identity(std::uint32_t width, std::uint32_t height, std::uint32_t columns, std::uint32_t rows) {
    Mesh mesh;
    mesh.columns = columns;
    mesh.rows = rows;
    if (columns < 2 || rows < 2) return mesh;
    for (std::uint32_t i = 0; i < rows; ++i) {
        for (std::uint32_t j = 0; j < columns; ++j) {
            mesh.nodes.push_back({static_cast<double>(j) * (width > 0 ? width - 1 : 0) / (columns - 1),
                                  static_cast<double>(i) * (height > 0 ? height - 1 : 0) / (rows - 1)});
        }
    }
    return mesh;
}

Point surface(const Mesh& mesh, double u, double v) { return evaluate(mesh, u, v).value; }

std::optional<TileStore> apply(const TileStore& source, const Mesh& mesh, const Options& options, std::string& error) {
    if (!mesh.valid()) {
        error = "the warp mesh must have 2-64 columns and rows and one node per lattice point";
        return std::nullopt;
    }
    for (const auto& n : mesh.nodes) {
        if (!std::isfinite(n.x) || !std::isfinite(n.y)) {
            error = "the warp mesh has a non-finite node";
            return std::nullopt;
        }
    }
    if (options.spacing < 1 || options.spacing > 64) {
        error = "field spacing must be 1-64";
        return std::nullopt;
    }
    const auto width = source.width(), height = source.height();
    if (width < 2 || height < 2) {
        error = "the source is too small to warp";
        return std::nullopt;
    }
    try {
        paint::LiquifyMesh field_mesh(width, height, options.spacing);
        const auto cols = field_mesh.columns(), rows = field_mesh.rows();
        std::vector<float> field(static_cast<std::size_t>(cols) * rows * 2, 0.0F);
        // Source position of lattice coordinate (u, v): the lattice is laid evenly over the pixel-index rectangle.
        const double sx = static_cast<double>(width - 1) / (mesh.columns - 1), sy = static_cast<double>(height - 1) / (mesh.rows - 1);
        parallel::parallelFor(rows, parallel::workerCount(options.worker_threads), [&](std::size_t row, std::size_t) {
            for (std::uint32_t col = 0; col < cols; ++col) {
                const Point p{static_cast<double>(col) * options.spacing, static_cast<double>(row) * options.spacing};
                // Start from the nearest node's lattice coordinate, then Newton on surface(u, v) = p.
                double best = std::numeric_limits<double>::max(), u = 0, v = 0;
                for (std::uint32_t i = 0; i < mesh.rows; ++i) {
                    for (std::uint32_t j = 0; j < mesh.columns; ++j) {
                        const auto& n = mesh.nodes[static_cast<std::size_t>(i) * mesh.columns + j];
                        const double d2 = (n.x - p.x) * (n.x - p.x) + (n.y - p.y) * (n.y - p.y);
                        if (d2 < best) {
                            best = d2;
                            u = j;
                            v = i;
                        }
                    }
                }
                bool converged = false;
                for (int iteration = 0; iteration < 24; ++iteration) {
                    const auto e = evaluate(mesh, u, v);
                    const double rx = e.value.x - p.x, ry = e.value.y - p.y;
                    if (rx * rx + ry * ry < 1e-6) {
                        converged = true;
                        break;
                    }
                    const double det = e.du.x * e.dv.y - e.dv.x * e.du.y;
                    if (std::abs(det) < 1e-12) break;
                    const double du = (rx * e.dv.y - ry * e.dv.x) / det, dv = (e.du.x * ry - e.du.y * rx) / det;
                    u = std::clamp(u - du, -2.0, static_cast<double>(mesh.columns) + 1.0);
                    v = std::clamp(v - dv, -2.0, static_cast<double>(mesh.rows) + 1.0);
                }
                const std::size_t at = (row * cols + col) * 2;
                // Converged points map even a little outside the source rectangle (the renderer samples those as transparent);
                // only points the surface cannot reach are marked as uncovered.
                if (converged) {
                    field[at] = static_cast<float>(u * sx - p.x);
                    field[at + 1] = static_cast<float>(v * sy - p.y);
                } else {
                    // Not covered by the mesh: sample just outside the source (a bounded displacement, so the renderer keeps
                    // its fast windowed path), which is transparent.
                    field[at] = static_cast<float>(-4.0 - p.x);
                    field[at + 1] = static_cast<float>(-4.0 - p.y);
                }
            }
        });
        if (!field_mesh.setField(std::move(field))) {
            error = "could not load the warp field";
            return std::nullopt;
        }
        return field_mesh.render(source, error, options.worker_threads);
    } catch (const std::exception& exception) {
        error = std::string("mesh warp failed: ") + exception.what();
        return std::nullopt;
    }
}

}  // namespace croptop::warp
