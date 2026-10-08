#pragma once

// CPU compositor for a psd::Document's layer stack — rebuilds the image for PSDs saved without a
// composite and is the golden reference for the GPU compositor's masks, clipping, groups and fill opacity.
// Rules (Photoshop's, simplified where noted):
//   - hidden layers and groups are skipped; blend modes use blend_modes.h blendRgbaF;
//   - a pixel layer draws its below-content effects, then its content (pixels × mask coverage × fill), then
//     its above-content effects; layer opacity scales all three, fill only the content;
//   - clipped layers are limited to the alpha of the nearest unclipped layer beneath (the clipping base);
//   - adjustment layers apply their adjust:: kernel to everything beneath them in their group, blended by
//     mask × opacity × fill (clipped adjustments are not limited to their base — warned);
//   - groups: pass-through groups composite straight onto the backdrop, then blend back towards the
//     pre-group backdrop by opacity × mask; other groups render on a transparent canvas, get their mask, and
//     blend onto the backdrop with their mode and opacity.
// Not modelled: blend-if ranges, knockout, advanced blending options.

#include <optional>
#include <string>
#include <vector>

#include "psd_layer_data.h"
#include "psd_reader.h"

namespace croptop::psd {

std::optional<TileStore> compose(const Document& document, std::string& error, std::vector<std::string>* warnings = nullptr);

}  // namespace croptop::psd
