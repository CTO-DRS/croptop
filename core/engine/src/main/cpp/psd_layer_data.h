#pragma once

// Decodes Photoshop layer data kept by the PSD reader (psd::Layer::extra) into engine structures:
//   effects:     `lfx2` (object-based effects descriptor) → styles::LayerStyles — drop/inner shadow, outer/inner
//                glow, stroke (solid colour), colour and gradient overlays, bevel & emboss, satin; `masterFXSwitch` off
//                disables all; the effects scale (`Scl `) scales sizes and distances; "use global light" takes the
//                document's global angle/altitude. Pattern overlays and contours are not decoded (warned).
//   adjustments: `levl` (composite record), `curv`, `hue2`, `blnc`, `brit` (or its `CgEd` descriptor), `nvrt`,
//                `post`, `thrs`, `mixr`, `selc`, `expA`, `vibA`, `blwh`, `phfl` (version 2, RGB colour) and `grdm`
//                (RGB colour stops) → adjust::Adjustment.
// Blend modes map from descriptor enums (Nrml, Mltp, Scrn, …) onto croptop::BlendMode.

#include <optional>
#include <string>
#include <vector>

#include "adjustments.h"
#include "layer_styles.h"
#include "psd_reader.h"

namespace croptop::psd {

struct GlobalLight final {
    float angle = 120.0F;
    float altitude = 30.0F;
};

std::optional<styles::LayerStyles> decodeEffects(const std::vector<std::uint8_t>& lfx2, const GlobalLight& light,
                                                 std::vector<std::string>& warnings);

/** `key` is the additional-info key; `extra` gives access to companion blocks (e.g. `CgEd` for `brit`). */
std::optional<adjust::Adjustment> decodeAdjustment(const std::string& key, const std::vector<std::uint8_t>& data,
                                                   const std::vector<AdditionalInfo>& extra, std::vector<std::string>& warnings);

/** The layer's effects (from `lfx2`), if any. */
std::optional<styles::LayerStyles> effectsOf(const Layer& layer, const GlobalLight& light, std::vector<std::string>& warnings);
/** The layer's adjustment (the first adjustment block it carries), if it is an adjustment layer. */
std::optional<adjust::Adjustment> adjustmentOf(const Layer& layer, std::vector<std::string>& warnings);

}  // namespace croptop::psd
