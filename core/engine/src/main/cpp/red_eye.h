#pragma once

// Red Eye tool pixel math. `region` is the A16 area the user dragged or clicked
// around an eye. Within it, each pixel's redness is (R − max(G, B)) / R (ignored where R < 0.1). The
// weight is a soft step around a threshold that falls as `pupil_size` rises (0.5 − 0.35·size ± 0.1).
// Only the largest connected red blob, plus a 2 px rim, is corrected, so reddish skin elsewhere in the
// region stays untouched. Corrected pixels become neutral at (G + B)/2 · (1 − 0.8·darken), blended by
// weight and region coverage; alpha and bright catchlights (not red) are kept.

#include <string>

#include "tile_store.h"

namespace croptop::paint {

struct RedEyeSpec final {
    float pupil_size = 0.5F;  // 0–1
    float darken = 0.5F;      // 0–1
};

bool redEye(TileStore& dst, const TileStore& region, const RedEyeSpec& spec, std::string& error);

}  // namespace croptop::paint
