#pragma once

// Region planes, exact distance transform and box sizing shared by selection_modify and
// refine_edge. Internal; not a public API.

#include <algorithm>
#include <array>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <optional>
#include <string>
#include <vector>

#include "parallel_util.h"
#include "tile_store.h"

namespace croptop::selection::detail {

inline constexpr std::uint16_t kHalf = 32768;
inline constexpr float kInfinity = 1e20F;

/** Tile-aligned, canvas-clipped rectangle that is processed as one contiguous plane. */
struct Region final {
    std::uint32_t x0 = 0;
    std::uint32_t y0 = 0;
    std::uint32_t x1 = 0;
    std::uint32_t y1 = 0;
    std::uint32_t width() const { return x1 - x0; }
    std::uint32_t height() const { return y1 - y0; }
    std::uint64_t pixels() const { return static_cast<std::uint64_t>(width()) * height(); }
};

inline Region regionAround(const TileStore& mask, PixelRect bounds, std::int64_t margin) {
    const auto align_down = [](std::int64_t v) { return std::max<std::int64_t>(0, v / kTileEdge * kTileEdge); };
    const auto align_up = [](std::int64_t v, std::uint32_t limit) {
        return static_cast<std::uint32_t>(std::min<std::int64_t>(limit, (v + kTileEdge - 1) / kTileEdge * kTileEdge));
    };
    return {static_cast<std::uint32_t>(align_down(bounds.left - margin)),
            static_cast<std::uint32_t>(align_down(bounds.top - margin)), align_up(bounds.right + margin, mask.width()),
            align_up(bounds.bottom + margin, mask.height())};
}

inline std::vector<std::uint16_t> extract(const TileStore& mask, Region region) {
    std::vector<std::uint16_t> plane(static_cast<std::size_t>(region.pixels()), 0);
    for (std::uint32_t ty = region.y0 / kTileEdge; ty * kTileEdge < region.y1; ++ty) {
        for (std::uint32_t tx = region.x0 / kTileEdge; tx * kTileEdge < region.x1; ++tx) {
            const auto tile = mask.readTile({static_cast<std::int32_t>(tx), static_cast<std::int32_t>(ty)});
            if (tile == nullptr) continue;
            const auto columns = std::min<std::uint32_t>(kTileEdge, region.x1 - tx * kTileEdge);
            const auto rows = std::min<std::uint32_t>(kTileEdge, region.y1 - ty * kTileEdge);
            for (std::uint32_t y = 0; y < rows; ++y) {
                std::memcpy(plane.data() + static_cast<std::size_t>(ty * kTileEdge + y - region.y0) * region.width() +
                                (tx * kTileEdge - region.x0),
                            tile->data() + static_cast<std::size_t>(y) * kTileEdge * 2U, columns * 2U);
            }
        }
    }
    return plane;
}

/**
 * Copies `mask` and replaces every tile of `region` with the plane's contents. Returns nullopt (the
 * input is untouched: the copy is discarded) if any tile write or erase fails.
 */
inline std::optional<TileStore> replaceRegion(const TileStore& mask, Region region, const std::vector<std::uint16_t>& plane,
                                       std::string& error) {
    TileStore out(mask);
    for (std::uint32_t ty = region.y0 / kTileEdge; ty * kTileEdge < region.y1; ++ty) {
        for (std::uint32_t tx = region.x0 / kTileEdge; tx * kTileEdge < region.x1; ++tx) {
            TileBuffer tile(out.tile_byte_size(), 0);
            const auto columns = std::min<std::uint32_t>(kTileEdge, region.x1 - tx * kTileEdge);
            const auto rows = std::min<std::uint32_t>(kTileEdge, region.y1 - ty * kTileEdge);
            bool any = false;
            for (std::uint32_t y = 0; y < rows; ++y) {
                const auto* source = plane.data() + static_cast<std::size_t>(ty * kTileEdge + y - region.y0) * region.width() +
                                     (tx * kTileEdge - region.x0);
                std::memcpy(tile.data() + static_cast<std::size_t>(y) * kTileEdge * 2U, source, columns * 2U);
                any = any || std::any_of(source, source + columns, [](std::uint16_t v) { return v != 0U; });
            }
            const TileCoordinate coordinate{static_cast<std::int32_t>(tx), static_cast<std::int32_t>(ty)};
            // eraseTile returns false for a tile that was already absent; only a present tile can fail.
            const bool ok = any ? out.writeTile(coordinate, tile) : (out.readTile(coordinate) == nullptr || out.eraseTile(coordinate));
            if (!ok) {
                error = "could not store the modified selection";
                return std::nullopt;
            }
        }
    }
    return out;
}

/** Writes a modified region directly into a store owned by the supplied document allocator. */
inline std::optional<TileStore> replaceRegion(const TileStore& allocation_like, const TileStore& mask, Region region,
                                              const std::vector<std::uint16_t>& plane, std::string& error) {
    if (allocation_like.width() != mask.width() || allocation_like.height() != mask.height()) {
        error = "allocation template must match the selection dimensions";
        return std::nullopt;
    }
    TileStore out = allocation_like.emptyLike(PixelFormat::A16);
    for (const auto coordinate : mask.tileCoordinates()) {
        const auto tile_x = static_cast<std::uint32_t>(coordinate.x);
        const auto tile_y = static_cast<std::uint32_t>(coordinate.y);
        if (tile_x * kTileEdge < region.x1 && (tile_x + 1U) * kTileEdge > region.x0 &&
            tile_y * kTileEdge < region.y1 && (tile_y + 1U) * kTileEdge > region.y0) continue;
        const auto tile = mask.readTile(coordinate);
        if (tile == nullptr) continue;
        if (!out.writeTile(coordinate, *tile)) {
            error = "could not copy selection into the document allocator";
            return std::nullopt;
        }
    }
    for (std::uint32_t ty = region.y0 / kTileEdge; ty * kTileEdge < region.y1; ++ty) {
        for (std::uint32_t tx = region.x0 / kTileEdge; tx * kTileEdge < region.x1; ++tx) {
            TileBuffer tile(out.tile_byte_size(), 0);
            const auto columns = std::min<std::uint32_t>(kTileEdge, region.x1 - tx * kTileEdge);
            const auto rows = std::min<std::uint32_t>(kTileEdge, region.y1 - ty * kTileEdge);
            bool any = false;
            for (std::uint32_t y = 0; y < rows; ++y) {
                const auto* source = plane.data() + static_cast<std::size_t>(ty * kTileEdge + y - region.y0) * region.width() +
                                     (tx * kTileEdge - region.x0);
                std::memcpy(tile.data() + static_cast<std::size_t>(y) * kTileEdge * 2U, source, columns * 2U);
                any = any || std::any_of(source, source + columns, [](std::uint16_t value) { return value != 0U; });
            }
            const TileCoordinate coordinate{static_cast<std::int32_t>(tx), static_cast<std::int32_t>(ty)};
            const bool ok = any ? out.writeTile(coordinate, tile) : (out.readTile(coordinate) == nullptr || out.eraseTile(coordinate));
            if (!ok) {
                error = "could not store the modified selection";
                return std::nullopt;
            }
        }
    }
    return out;
}

/** Felzenszwalb–Huttenlocher 1-D squared distance transform of f[0..n) into d[0..n). */
inline void distance1d(const float* f, float* d, std::size_t n, std::vector<std::size_t>& v, std::vector<float>& z) {
    v.resize(n);
    z.resize(n + 1U);
    std::size_t k = 0;
    v[0] = 0;
    z[0] = -kInfinity;
    z[1] = kInfinity;
    for (std::size_t q = 1; q < n; ++q) {
        const auto fq = f[q] + static_cast<float>(q) * static_cast<float>(q);
        float s = 0.0F;
        while (true) {
            const auto p = v[k];
            s = (fq - (f[p] + static_cast<float>(p) * static_cast<float>(p))) /
                (2.0F * (static_cast<float>(q) - static_cast<float>(p)));
            if (s > z[k] || k == 0U) break;
            --k;
        }
        if (s <= z[k]) {  // Only reachable with k == 0: q replaces the whole envelope.
            v[0] = q;
            z[0] = -kInfinity;
            z[1] = kInfinity;
            continue;
        }
        ++k;
        v[k] = q;
        z[k] = s;
        z[k + 1U] = kInfinity;
    }
    k = 0;
    for (std::size_t q = 0; q < n; ++q) {
        while (z[k + 1U] < static_cast<float>(q)) ++k;
        const auto delta = static_cast<float>(q) - static_cast<float>(v[k]);
        d[q] = delta * delta + f[v[k]];
    }
}

/** Exact squared Euclidean distance (in pixels) from every pixel to the nearest `source` pixel. */
inline std::vector<float> distanceField(Region region, const std::vector<std::uint8_t>& source, unsigned worker_threads = 0) {
    const auto width = static_cast<std::size_t>(region.width());
    const auto height = static_cast<std::size_t>(region.height());
    std::vector<float> grid(width * height);
    for (std::size_t i = 0; i < grid.size(); ++i) grid[i] = source[i] != 0U ? 0.0F : kInfinity;
    const auto workers = parallel::workerCount(worker_threads);
    struct Scratch {
        std::vector<std::size_t> v;
        std::vector<float> z;
        std::vector<float> in;
        std::vector<float> out;
    };
    std::vector<Scratch> scratch(workers);
    for (auto& s : scratch) {
        s.in.resize(std::max(width, height));
        s.out.resize(std::max(width, height));
    }
    // Columns and rows are independent 1-D transforms.
    parallel::parallelFor(width, workers, [&](std::size_t x, std::size_t worker) {
        auto& s = scratch[worker];
        for (std::size_t y = 0; y < height; ++y) s.in[y] = grid[y * width + x];
        distance1d(s.in.data(), s.out.data(), height, s.v, s.z);
        for (std::size_t y = 0; y < height; ++y) grid[y * width + x] = s.out[y];
    });
    parallel::parallelFor(height, workers, [&](std::size_t y, std::size_t worker) {
        auto& s = scratch[worker];
        std::copy_n(grid.data() + y * width, width, s.in.data());
        distance1d(s.in.data(), grid.data() + y * width, width, s.v, s.z);
    });
    return grid;
}

/** Box sizes (odd) whose three passes approximate a Gaussian of `sigma` (Kovesi). */
inline std::array<std::uint32_t, 3> boxSizes(double sigma) {
    constexpr int kPasses = 3;
    const auto ideal = std::sqrt(12.0 * sigma * sigma / kPasses + 1.0);
    auto lower = static_cast<int>(std::floor(ideal));
    if (lower % 2 == 0) --lower;
    lower = std::max(lower, 1);
    const auto upper = lower + 2;
    const auto m_ideal = (12.0 * sigma * sigma - kPasses * lower * lower - 4.0 * kPasses * lower - 3.0 * kPasses) /
                         (-4.0 * lower - 4.0);
    const auto m = static_cast<int>(std::lround(m_ideal));
    std::array<std::uint32_t, 3> sizes{};
    for (int i = 0; i < kPasses; ++i) sizes[static_cast<std::size_t>(i)] = static_cast<std::uint32_t>(i < m ? lower : upper);
    return sizes;
}

}  // namespace croptop::selection::detail
