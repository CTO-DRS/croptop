#pragma once

// Layer-mask operations for the engine's mask commands: create (Reveal/Hide All or
// Selection), invert, apply into the pixels, and mask → selection. A mask is an A16 store plus `empty`, the
// coverage of canvas tiles it holds no pixels for, so Reveal All is free until painted. Effective coverage
// (enabled, density, feather) always comes from `MaskCache`, exactly as the compositor sees it.

#include <cstdint>
#include <optional>
#include <span>
#include <string>
#include <vector>

#include "layer_mask.h"
#include "stroke_compositor.h"
#include "tile_store.h"

namespace croptop::layermask {

enum class NewMask : std::uint8_t { RevealAll, HideAll, RevealSelection, HideSelection };

struct MaskData final {
    TileStore pixels;      // A16, canvas-sized
    float empty = 0.0F;    // coverage where `pixels` has no tile (0 hidden, 1 revealed)
};

/**
 * A new mask sized and allocated like `like` (any store of the canvas's size). The selection kinds take an A16
 * `selection` of the same size and fail without one.
 */
std::optional<MaskData> createMask(const TileStore& like, NewMask kind, const TileStore* selection, std::string& error);

/** Photoshop's Invert on a mask: every coverage value v becomes 1 − v (stored tiles flip; empty flips). */
MaskData invertMask(const MaskData& mask);

/**
 * "Apply Layer Mask": a copy of `pixels` (RGBA8/16/32F) with alpha multiplied by the mask's effective coverage under
 * `properties`. Tiles the layer has no pixels for stay empty.
 */
std::optional<TileStore> applyToPixels(const TileStore& pixels, const MaskData& mask, const MaskProperties& properties, std::string& error);

/** "Load Selection": the effective coverage as an A16 selection store (empty tiles stay empty where coverage is 0). */
std::optional<TileStore> toSelection(const MaskData& mask, const MaskProperties& properties, std::string& error);

/** A grayscale preview of a mask for the Layers panel: 8-bit coverage, row-major, `width` × `height`. */
struct MaskThumbnail final {
    std::uint32_t width = 0;
    std::uint32_t height = 0;
    std::vector<std::uint8_t> gray;
};

/**
 * Reduces the mask (plain painted coverage: no density or feather, disabled masks included) so its longer edge is at
 * most `max_px`, by box averaging. Uses the same reduced-level tiles the compositor previews with.
 */
std::optional<MaskThumbnail> thumbnail(const MaskData& mask, std::uint32_t max_px, std::string& error);

/**
 * Paints a finished brush stroke onto a mask (Brush Tool / Eraser with the mask targeted). `coverage` holds the stroke's
 * accumulated flow; `value` is the luminance the stroke paints (1 white reveals, 0 black hides; an eraser stroke paints
 * black, so pass 0), scaled by `opacity`. Only `affected` tiles are visited. Returns the painted mask; tiles that end
 * up uniformly equal to `mask.empty` are dropped so a mask stays sparse. `selection`, when given, limits the paint like
 * it does for pixel layers.
 */
std::optional<MaskData> paintStroke(const MaskData& mask, const brush::StrokeMaskCompositor& coverage, float value, float opacity,
                                    std::span<const TileCoordinate> affected, const TileStore* selection, std::string& error);

}  // namespace croptop::layermask
