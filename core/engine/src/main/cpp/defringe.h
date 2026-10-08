#pragma once

// Manual defringe: removes purple and green longitudinal chromatic aberration.
// A pixel is desaturated when its OkLCh hue lies in the chosen purple/green hue band *and* it sits next to
// a high-contrast luminance edge (within 2 px), so purple flowers and green foliage away from edges keep
// their colour. Works on RGBA8/16/32F (linear light assumed for float; encoded for integer formats).

#include <functional>
#include <optional>
#include <string>

#include "tile_store.h"

namespace croptop::lens {

struct DefringeSpec final {
    float purple_amount = 0;       // 0–20 (as Lightroom)
    float purple_hue_low = 270;    // OkLCh hue band, degrees (purple/magenta sits around 270–340)
    float purple_hue_high = 340;
    float green_amount = 0;        // 0–20
    float green_hue_low = 110;     // green/yellow-green around 110–170
    float green_hue_high = 170;
    float edge_threshold = 0.08F;  // OkLab lightness step (0–1) that counts as a high-contrast edge
    unsigned worker_threads = 0;
};

std::optional<TileStore> defringe(const TileStore& layer, const DefringeSpec& spec, std::string& error);

}  // namespace croptop::lens
