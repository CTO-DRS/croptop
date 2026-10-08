#pragma once

// Edit ▸ Fill: fills the selection (or the whole layer) of an RGBA layer with a
// colour, a tiled pattern or content-aware fill, composited with an opacity and a blend mode.
//   Color:        `color` (straight sRGB-encoded RGBA, 0–1).
//   Pattern:      any RGBA TileStore, tiled from document (pattern_x, pattern_y).
//   ContentAware: content_aware_fill.h over the mask's coverage > 50% (needs a mask); the result is then
//                 composited like the other sources.
// Each pixel is blended with blend_modes.h's blendRgbaF (source-over, straight alpha; RGBA8/16 in encoded
// values, RGBA32F as stored), with opacity × mask coverage. `preserve_transparency` keeps the layer's
// alpha: only existing pixels change colour, as if the layer's transparency were locked.

#include <array>
#include <cstdint>
#include <functional>
#include <string>

#include "blend_modes.h"
#include "content_aware_fill.h"
#include "tile_store.h"

namespace croptop::paint {

enum class FillSource : std::uint8_t { Color, Pattern, ContentAware };

struct FillSpec final {
    FillSource source = FillSource::Color;
    std::array<float, 4> color{0.0F, 0.0F, 0.0F, 1.0F};
    const TileStore* pattern = nullptr;
    std::int64_t pattern_x = 0;
    std::int64_t pattern_y = 0;
    float opacity = 1.0F;  // 0–1
    BlendMode mode = BlendMode::Normal;
    bool preserve_transparency = false;
    std::uint32_t dissolve_seed = 0;
    /** Content-Aware Fill progress in [0, 1]; return false to cancel before the command commits. */
    std::function<bool(float)> progress;
    /** Content-Aware options: patch transforms, colour adaptation and an optional A16 sampling mask (null = automatic). */
    RotationAdaptation rotation_adaptation = RotationAdaptation::None;
    bool mirror_adaptation = false;
    bool color_adaptation = true;
    const TileStore* sampling = nullptr;
};

/** `mask` is an optional A16 selection of the layer's size; without one the whole layer is filled. */
bool fill(TileStore& target, const FillSpec& spec, const TileStore* mask, std::string& error);

}  // namespace croptop::paint
