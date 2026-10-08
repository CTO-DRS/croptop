#pragma once

// Native side of engine-api TransformSession (/) for one pixel layer: Move
// drag and Free Transform, optionally limited to a selection.
//   begin: the layer splits into the floating part (layer × selection, or the whole layer) and the remainder
//          (layer × (1 − selection)); both are COW copies, so the original is untouched until commit.
//   preview(matrix, scale): the transformed floating part over the remainder at `scale` (a proxy for the
//          viewport while dragging), bilinear; the scaled remainder is cached per scale.
//   commit(matrix, filter): the full-resolution result (Bicubic by default) plus the dirty rectangle the
//          caller invalidates and records as one history step.
// Matrices are core:model Matrix3 (row-major, document points, relative to the state at begin).

#include <array>
#include <memory>
#include <optional>
#include <string>

#include "resample.h"
#include "tile_store.h"

namespace croptop::xform {

using Matrix = std::array<double, 9>;

struct Committed final {
    TileStore pixels;
    PixelRect dirty{0, 0, 0, 0};  // union of the original and transformed floating bounds (clamped to the canvas)
};

class LayerTransform final {
public:
    /** `layer` RGBA; `selection` optional A16 of the layer's size (null = the whole layer moves). */
    static std::unique_ptr<LayerTransform> begin(const TileStore& layer, const TileStore* selection, std::string& error);

    /** Proxy render at 0 < scale ≤ 1 (size ⌈w·scale⌉ × ⌈h·scale⌉). */
    std::optional<TileStore> preview(const Matrix& matrix, double scale, std::string& error, unsigned worker_threads = 0);
    std::optional<Committed> commit(const Matrix& matrix, std::string& error, resample::Filter filter = resample::Filter::Bicubic,
                                    unsigned worker_threads = 0);

    /** Bounds of the floating content before transforming (nullopt when nothing is selected/opaque). */
    std::optional<PixelRect> floatingBounds() const { return floating_bounds_; }

private:
    LayerTransform(const TileStore& original, const TileStore& floating, std::optional<TileStore> remainder, std::optional<PixelRect> bounds)
        : original_(original), floating_(floating), floating_bounds_(bounds) {
        if (remainder) remainder_.emplace(std::move(*remainder));
    }
    TileStore original_;
    TileStore floating_;
    std::optional<TileStore> remainder_;
    std::optional<PixelRect> floating_bounds_;
    double proxy_scale_ = 0;
    std::optional<TileStore> proxy_remainder_;
};

}  // namespace croptop::xform
