#pragma once

// Mesh Warp (Edit ▸ Transform ▸ Warp): a cols × rows lattice of control points laid evenly over the
// source rectangle is dragged to new positions, and the layer is re-rendered through the smooth surface they define.
//   Surface: bicubic Catmull-Rom interpolation of the node positions (C1, passes through every node, so a dragged node lands
//            exactly where it was put). The identity mesh leaves the layer bit-identical.
//   Render:  the surface is inverted (Newton iterations from the nearest node) on a coarse lattice into a displacement
//            field; the layer is then resampled through it with the Liquify renderer (bicubic, premultiplied alpha).
//            Pixels the mesh does not cover are transparent.

#include <cstdint>
#include <optional>
#include <string>
#include <vector>

#include "tile_store.h"

namespace croptop::warp {

struct Point final {
    double x = 0.0;
    double y = 0.0;
};

struct Mesh final {
    std::uint32_t columns = 4;
    std::uint32_t rows = 4;
    /** Destination position of each lattice node, row-major (rows × columns). */
    std::vector<Point> nodes;

    /** The undeformed mesh over a width × height source, in pixel-index space: node (i, j) at (j·(width−1)/(columns−1), i·(height−1)/(rows−1)). */
    static Mesh identity(std::uint32_t width, std::uint32_t height, std::uint32_t columns = 4, std::uint32_t rows = 4);
    bool valid() const { return columns >= 2 && rows >= 2 && columns <= 64 && rows <= 64 && nodes.size() == static_cast<std::size_t>(columns) * rows; }
};

struct Options final {
    std::uint32_t spacing = 8;  // px between inverted-field samples (1–64); smaller is more exact and slower
    unsigned worker_threads = 0;
};

/** Warps `source` (RGBA8/16/32F) through `mesh`. nullopt with `error` for invalid meshes or allocation failure. */
std::optional<TileStore> apply(const TileStore& source, const Mesh& mesh, const Options& options, std::string& error);

/** The surface: destination position of the source-lattice coordinate (u, v), u in [0, columns−1], v in [0, rows−1]. */
Point surface(const Mesh& mesh, double u, double v);

}  // namespace croptop::warp
