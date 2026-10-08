#pragma once

// Smart objects and smart filters (/), native rendering core.
//
// A smart object keeps its embedded source untouched and renders it into document space on demand:
//   1. the transform stack (source → document, applied in order) is composed into ONE matrix, and the source
//      is resampled once — so scaling down and back up loses nothing;
//   2. enabled smart filters run in order on the placed pixels; each filter's result is blended back over
//      its own input with that filter's blend mode and opacity (Photoshop's per-filter blending options);
//   3. the shared filter mask (A16, document space) blends between the unfiltered placement and the
//      filtered result.
// "Rasterize" is render(); "convert to smart object" wraps a layer with an identity transform; editing the
// contents replaces `source` and re-renders.

#include <array>
#include <functional>
#include <optional>
#include <string>
#include <vector>

#include "blend_modes.h"
#include "resample.h"
#include "tile_store.h"

namespace croptop::smart {

using Matrix = std::array<double, 9>;  // row-major 3×3, source → destination points

struct SmartFilter final {
    std::string name;
    /** Filters the layer in place (document space, the layer's format). Return false with `error` on failure. */
    std::function<bool(TileStore& layer, std::string& error)> apply;
    BlendMode mode = BlendMode::Normal;
    float opacity = 1.0F;  // 0–1
    bool enabled = true;
};

struct SmartObject final {
    TileStore source;
    std::vector<Matrix> transforms;  // applied first to last
    std::vector<SmartFilter> filters;
    std::optional<TileStore> filter_mask;  // A16, document size; absent = filters apply everywhere
    bool filter_mask_enabled = true;
};

/** Wraps a layer placed 1:1 at the document origin. */
SmartObject convertToSmartObject(const TileStore& layer);

/** transforms[n−1] · … · transforms[0]. */
Matrix composedTransform(const std::vector<Matrix>& transforms);

struct RenderOptions final {
    resample::Filter quality = resample::Filter::Bicubic;
    unsigned worker_threads = 0;
};

/** Renders into a document of the given size (the source's format). This is also "Rasterize". */
std::optional<TileStore> render(const SmartObject& object, std::uint32_t document_width, std::uint32_t document_height,
                                const RenderOptions& options, std::string& error);

/** Blend one rendered filter result over its input, preserving the input format and geometry. */
std::optional<TileStore> blendFilterResult(const TileStore& input, const TileStore& filtered, BlendMode mode, float opacity,
                                           unsigned worker_threads, std::string& error);

/**
 * The smart-filter mask applied to an already filtered result: each pixel is `mix(base, filtered, coverage)` (A16 `mask`, same size
 * as both inputs). Used when the filter stack is rendered one filter at a time and the shared mask is applied last.
 */
std::optional<TileStore> blendThroughMask(const TileStore& base, const TileStore& filtered, const TileStore& mask, unsigned worker_threads,
                                          std::string& error);

// Factory helpers for smart filters backed by CropTop's CPU filters.
SmartFilter gaussianBlurFilter(float radius);
/** Units as filters::unsharpMask: amount 0–5 (1 = 100 %), radius px, threshold 0–255. */
SmartFilter unsharpMaskFilter(float amount, float radius, float threshold);

}  // namespace croptop::smart
