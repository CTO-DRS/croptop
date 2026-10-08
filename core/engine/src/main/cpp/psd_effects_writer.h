#pragma once

// Writes layer styles as a Photoshop `lfx2` block (object-based effects descriptor), the inverse of
// psd::decodeEffects: drop/inner shadow, outer/inner glow, stroke (solid), colour and gradient overlays, bevel &
// emboss and satin, with "use global light" off so each effect keeps its own angle. Pattern overlays are not written.

#include <cstdint>
#include <vector>

#include "layer_styles.h"

namespace croptop::psd {

/** The `lfx2` additional-info payload (version 0, descriptor version 16, descriptor) for [styles]. */
std::vector<std::uint8_t> encodeEffects(const styles::LayerStyles& styles);

}  // namespace croptop::psd
