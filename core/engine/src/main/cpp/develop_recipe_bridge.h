#pragma once

// Bridge between the two Develop settings representations: the pipeline's `Settings`
// (mirror of core:model DevelopSettings, what the UI edits and `pipeline::render` runs) and `DevelopRecipe`
// (what XMP sidecars and presets read and write, in the kernels' own units). With it, a sidecar or preset can
// drive `raw::openFlattened`, and the app's settings can be exported as an XMP sidecar.
//
// Lossy where one side has no field: Settings has no parametric tone curve, calibration, vignette roundness /
// feather / highlights, noise detail sliders, grain roughness, and its white balance is a preset plus temperature,
// not camera multipliers. Those recipe values are dropped going to Settings (and default going back), and a
// recipe's camera white-balance multipliers are handed back separately so callers can apply them at demosaic.

#include <array>
#include <optional>

#include "develop_pipeline.h"
#include "develop_xmp.h"

namespace croptop::develop {

struct RecipeSettings final {
    pipeline::Settings settings;
    std::optional<std::array<float, 3>> camera_white_balance;  // the recipe's own multipliers, if it carried them
};

RecipeSettings settingsFromRecipe(const DevelopRecipe& recipe);
DevelopRecipe recipeFromSettings(const pipeline::Settings& settings);

}  // namespace croptop::develop
