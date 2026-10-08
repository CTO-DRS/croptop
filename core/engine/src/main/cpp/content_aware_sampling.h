#pragma once

// the Content-Aware Fill sampling area (where patches may be copied from), built from brush strokes.
// `Auto` starts from "everything outside the hole" (the engine default, passed as no mask); a stroke then adds to or
// subtracts from a mask that starts as `All` (everything may be sampled) or `None`.

#include <optional>
#include <span>
#include <string>
#include <vector>

#include "selection_mask.h"
#include "tile_store.h"

namespace croptop::paint {

enum class SamplingBase : std::uint8_t { All, None };

struct SamplingStroke final {
    bool add = true;
    double radius = 8.0;
    std::vector<selection::Point> points;
};

/** `base` (A16, same size as `like`) with `strokes` applied in order: add strokes cover pixels, subtract strokes clear them. */
std::optional<TileStore> applyStrokesToMask(const TileStore& like, const TileStore& base, std::span<const SamplingStroke> strokes,
                                            std::string& error);

/** An A16 mask sized like `like` (stroke discs and capsules combined onto `base`), or nullopt with a reason. */
std::optional<TileStore> buildSamplingMask(const TileStore& like, SamplingBase base, std::span<const SamplingStroke> strokes,
                                           std::string& error);

}  // namespace croptop::paint
