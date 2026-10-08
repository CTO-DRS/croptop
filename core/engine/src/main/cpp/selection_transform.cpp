#include "selection_transform.h"

#include <algorithm>
#include <atomic>
#include <cmath>
#include <cstring>
#include <mutex>

#include "parallel_util.h"
#include "selection_mask.h"

namespace croptop::selection {
namespace {

using Matrix = std::array<double, 9>;

std::optional<Matrix> inverse(const Matrix& m) {
    const auto a = m[0], b = m[1], c = m[2], d = m[3], e = m[4], f = m[5], g = m[6], h = m[7], i = m[8];
    const auto co0 = e * i - f * h;
    const auto co1 = f * g - d * i;
    const auto co2 = d * h - e * g;
    const auto det = a * co0 + b * co1 + c * co2;
    if (!std::isfinite(det) || std::fabs(det) < 1e-12) return std::nullopt;
    const auto s = 1.0 / det;
    return Matrix{co0 * s, (c * h - b * i) * s, (b * f - c * e) * s,
                  co1 * s, (a * i - c * g) * s, (c * d - a * f) * s,
                  co2 * s, (b * g - a * h) * s, (a * e - b * d) * s};
}

/** Maps (x, y); returns false when w <= 0 (behind the horizon). */
bool apply(const Matrix& m, double x, double y, double& out_x, double& out_y) {
    const auto w = m[6] * x + m[7] * y + m[8];
    if (!(w > 1e-12)) return false;
    out_x = (m[0] * x + m[1] * y + m[2]) / w;
    out_y = (m[3] * x + m[4] * y + m[5]) / w;
    return std::isfinite(out_x) && std::isfinite(out_y);
}

/** Bilinear coverage sampler with a small per-worker cache of source tiles. */
class Sampler final {
public:
    explicit Sampler(const TileStore& mask) : mask_(mask) {}

    /** Coverage (0..65535) at continuous position (sx, sy); pixel centers at +0.5. */
    double sample(double sx, double sy) {
        const auto fx = sx - 0.5;
        const auto fy = sy - 0.5;
        const auto x0 = std::floor(fx);
        const auto y0 = std::floor(fy);
        const auto tx = fx - x0;
        const auto ty = fy - y0;
        return (at(x0, y0) * (1.0 - tx) + at(x0 + 1.0, y0) * tx) * (1.0 - ty) +
               (at(x0, y0 + 1.0) * (1.0 - tx) + at(x0 + 1.0, y0 + 1.0) * tx) * ty;
    }

private:
    double at(double x, double y) {
        if (x < 0.0 || y < 0.0 || x >= mask_.width() || y >= mask_.height()) return 0.0;
        const auto ix = static_cast<std::uint32_t>(x);
        const auto iy = static_cast<std::uint32_t>(y);
        const TileCoordinate coordinate{static_cast<std::int32_t>(ix / kTileEdge), static_cast<std::int32_t>(iy / kTileEdge)};
        auto* slot = &cache_[(static_cast<std::size_t>(coordinate.x) & 1U) | ((static_cast<std::size_t>(coordinate.y) & 1U) << 1U)];
        if (!slot->valid || !(slot->coordinate == coordinate)) {
            slot->tile = mask_.readTile(coordinate);
            slot->coordinate = coordinate;
            slot->valid = true;
        }
        if (slot->tile == nullptr) return 0.0;
        std::uint16_t value = 0;
        std::memcpy(&value, slot->tile->data() + (static_cast<std::size_t>(iy % kTileEdge) * kTileEdge + ix % kTileEdge) * 2U, 2U);
        return value;
    }

    struct Slot {
        TileCoordinate coordinate{0, 0};
        std::shared_ptr<const TileBuffer> tile;
        bool valid = false;
    };
    const TileStore& mask_;
    std::array<Slot, 4> cache_{};
};

}  // namespace

std::optional<TileStore> transform(const TileStore& selection, const Matrix& matrix, std::string& error,
                                   unsigned worker_threads) {
    if (selection.format() != PixelFormat::A16 || selection.width() == 0U || selection.height() == 0U) {
        error = "selection must be a non-empty A16 mask";
        return std::nullopt;
    }
    if (!std::all_of(matrix.begin(), matrix.end(), [](double v) { return std::isfinite(v); })) {
        error = "transform matrix must be finite";
        return std::nullopt;
    }
    const auto inverted = inverse(matrix);
    if (!inverted) {
        error = "transform matrix is singular";
        return std::nullopt;
    }
    const auto source_bounds = bounds(selection);
    if (!source_bounds) return emptyMask(selection);
    // Output region: the transformed corners of the source bounds (exact for affine and, with w > 0 at
    // every corner, for projective maps of a convex box).
    double left = 1e300, top = 1e300, right = -1e300, bottom = -1e300;
    for (const auto& [x, y] : {std::pair{source_bounds->left, source_bounds->top}, std::pair{source_bounds->right, source_bounds->top},
                              std::pair{source_bounds->left, source_bounds->bottom}, std::pair{source_bounds->right, source_bounds->bottom}}) {
        double mx = 0.0;
        double my = 0.0;
        if (!apply(matrix, x, y, mx, my)) {
            error = "perspective transform sends the selection through the horizon";
            return std::nullopt;
        }
        left = std::min(left, mx);
        top = std::min(top, my);
        right = std::max(right, mx);
        bottom = std::max(bottom, my);
    }
    TileStore out = selection.emptyLike(PixelFormat::A16);
    const auto clamp_to = [](double v, std::uint32_t limit) {
        return static_cast<std::uint32_t>(std::clamp(v, 0.0, static_cast<double>(limit)));
    };
    // One pixel of slack for bilinear spill on each side.
    const auto x0 = clamp_to(std::floor(left) - 1.0, selection.width());
    const auto y0 = clamp_to(std::floor(top) - 1.0, selection.height());
    const auto x1 = clamp_to(std::ceil(right) + 1.0, selection.width());
    const auto y1 = clamp_to(std::ceil(bottom) + 1.0, selection.height());
    if (x1 <= x0 || y1 <= y0) return out;
    const auto first_tx = x0 / kTileEdge;
    const auto first_ty = y0 / kTileEdge;
    const auto columns = (x1 - 1U) / kTileEdge - first_tx + 1U;
    const auto rows = (y1 - 1U) / kTileEdge - first_ty + 1U;
    std::mutex out_mutex;
    std::atomic<bool> write_failed{false};
    parallel::parallelFor(static_cast<std::size_t>(columns) * rows, parallel::workerCount(worker_threads),
                          [&](std::size_t item, std::size_t) {
        if (write_failed.load(std::memory_order_relaxed)) return;
        const auto tx = first_tx + static_cast<std::uint32_t>(item % columns);
        const auto ty = first_ty + static_cast<std::uint32_t>(item / columns);
        TileBuffer tile(out.tile_byte_size(), 0);
        bool any = false;
        Sampler sampler(selection);
        for (std::uint32_t ly = 0; ly < kTileEdge; ++ly) {
            const auto y = ty * kTileEdge + ly;
            if (y < y0 || y >= y1) continue;
            for (std::uint32_t lx = 0; lx < kTileEdge; ++lx) {
                const auto x = tx * kTileEdge + lx;
                if (x < x0 || x >= x1) continue;
                double sx = 0.0;
                double sy = 0.0;
                if (!apply(*inverted, x + 0.5, y + 0.5, sx, sy)) continue;
                const auto value = static_cast<std::uint16_t>(std::lround(std::clamp(sampler.sample(sx, sy), 0.0, 65535.0)));
                if (value == 0U) continue;
                std::memcpy(tile.data() + (static_cast<std::size_t>(ly) * kTileEdge + lx) * 2U, &value, 2U);
                any = true;
            }
        }
        if (!any) return;
        const std::lock_guard lock(out_mutex);
        if (!out.writeTile({static_cast<std::int32_t>(tx), static_cast<std::int32_t>(ty)}, tile)) {
            write_failed.store(true, std::memory_order_relaxed);
        }
    });
    if (write_failed.load(std::memory_order_relaxed)) {
        error = "could not store transformed selection tile";
        return std::nullopt;
    }
    return out;
}

}  // namespace croptop::selection
