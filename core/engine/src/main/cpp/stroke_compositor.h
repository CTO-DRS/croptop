#pragma once

// Sparse, high-precision coverage accumulation and single-pass
// brush/eraser composition from an immutable stroke baseline.

#include <cstddef>
#include <cstdint>
#include <span>
#include <string>
#include <unordered_map>

#include "tile_store.h"

namespace croptop::brush {

struct Dab;

/**
 * A stroke coverage mask. Callers add each dab's flow-weighted pixel coverage
 * in input order, then compose once over a COW copy of the immutable baseline.
 * The mask is A16 so low-flow increments are not rounded away by RGBA8 layers.
 * Callers must serialize access to a mask.
 */
class StrokeMaskCompositor final {
public:
    StrokeMaskCompositor(std::uint32_t width, std::uint32_t height);
    StrokeMaskCompositor(const StrokeMaskCompositor&) = delete;
    StrokeMaskCompositor& operator=(const StrokeMaskCompositor&) = delete;

    /** Add one pixel's dab coverage using source-over mask accumulation. */
    bool accumulate(std::int32_t x, std::int32_t y, float flow_coverage, std::string& error);

    /** Rasterize an elliptical Dab's antialiased geometry into the flow mask. */
    bool accumulateDab(const Dab& dab, std::string& error);

    /**
     * Composite accumulated flow from `baseline` into `target` exactly once.
     * Opacity caps the full stroke coverage. Color is straight sRGB and is
     * decoded to linear RGB for RGBA32F targets. `target` must be an isolated
     * COW copy; if a tile write fails, discard it instead of publishing it.
     * When provided, the matching A16 selection mask multiplies each pixel's
     * coverage; zero-coverage pixels are left byte-for-byte unchanged.
     */
    bool compose(const TileStore& baseline, TileStore& target, const float color[4], float opacity, bool erase,
                 bool preserve_alpha, std::string& error, const TileStore* selection_mask = nullptr) const;

    /** Materialize the accumulated stroke coverage into an A16 store for effect brushes. */
    bool composeMask(TileStore& target, float opacity, std::string& error) const;

    /** Darken the inner coverage rim while preserving alpha and selection clipping. */
    bool applyWetEdges(TileStore& target, float strength, const TileStore* selection_mask, std::string& error) const;

    /**
     * Apply an A16 selection once to the final build-up stroke by interpolating
     * baseline to painted pixels. `target` may alias `painted`; it must not
     * alias baseline. Only caller-provided affected tiles are visited. If a
     * write fails, discard `target` instead of publishing the partial result.
     */
    static bool applySelectionMask(const TileStore& baseline, const TileStore& painted, TileStore& target,
                                   const TileStore& selection_mask, std::span<const TileCoordinate> affected_tiles,
                                   std::string& error);

private:
    static constexpr std::size_t kMaximumMaskBytes = 64U * 1024U * 1024U;
    TileBuffer* mutableTile(TileCoordinate coordinate, std::string& error);
    std::uint32_t width_;
    std::uint32_t height_;
    std::size_t allocated_bytes_ = 0;
    std::unordered_map<TileCoordinate, TileBuffer, TileCoordinateHash> coverage_tiles_;
};

}  // namespace croptop::brush
