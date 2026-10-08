#pragma once

// Local develop mask colour matching and A16 mask algebra.

#include <array>
#include <cstdint>
#include <optional>
#include <string>

#include "selection_mask.h"
#include "tile_store.h"

namespace croptop::develop::mask_ops {

struct ColorRange final {
    // Normalized channel values in the same stored RGB domain as the source TileStore.
    std::array<float, 3> target{};
    // Normalized Euclidean RGB distance (sqrt((dr² + dg² + db²) / 3)).
    float tolerance = 0.05F;
    // Smooth falloff width beyond tolerance; zero gives a hard threshold.
    float feather = 0.05F;
};

// Builds an alpha-weighted color-range A16 mask. Output must be a matching-size A16 store.
bool colorRange(const TileStore& image, const ColorRange& range, TileStore& out, std::string& error);

// Applies the established selection mask algebra to develop A16 masks.
std::optional<TileStore> combine(const TileStore& base, const TileStore& operand, selection::Op op);

}  // namespace croptop::develop::mask_ops
