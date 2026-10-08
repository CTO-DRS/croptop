#pragma once

// XMP sidecars for develop settings. Writes Adobe Camera Raw `crs:` properties
// (so Lightroom/ACR/Bridge read the edit) plus exact values in CropTop's own namespace, and reads either:
// our sidecars round-trip exactly, and Lightroom/ACR sidecars import the matching controls.

#include <array>
#include <optional>
#include <string>
#include <string_view>
#include <vector>

#include "develop_color.h"
#include "develop_detail.h"
#include "develop_tone.h"

namespace croptop::develop {

struct DevelopRecipe final {
    ToneSettings tone;
    ColorSettings color;
    DetailSettings detail;
    std::optional<std::array<float, 3>> white_balance;  // camera multipliers (R, G, B); ours only
};

/** A complete `<x:xmpmeta>` sidecar document (UTF-8). `creator_tool` goes into xmp:CreatorTool. */
std::string writeXmp(const DevelopRecipe& recipe, std::string_view creator_tool = "CropTop");

/**
 * Reads a sidecar or an embedded XMP packet (≤ 16 MiB). Properties may be attributes or elements of any
 * rdf:Description. Our namespace wins over `crs:` when both are present; unknown properties are ignored;
 * values are clamped to our ranges. Fails only when no XMP document is found.
 */
std::optional<DevelopRecipe> readXmp(std::string_view xmp, std::string& error);

/**
 * A develop preset (Lightroom Classic / ACR `.xmp` presets, or our sidecars used as presets): only the
 * controls it sets are applied, so a "B&W grain" preset leaves exposure alone.
 */
struct DevelopPreset final {
    std::string name, group;            // crs:Name / crs:Group (x-default), may be empty
    DevelopRecipe values;               // the preset's values (unset controls keep defaults)
    std::vector<std::string> controls;  // the Camera Raw names it sets, plus "Look", "GrainSeed", "WhiteBalance", "PointCurve"
};

/** Reads a preset; fails when there is no XMP or it sets none of our controls. */
std::optional<DevelopPreset> readXmpPreset(std::string_view xmp, std::string& error);

/**
 * Applies a preset over `base` at Lightroom's preset amount (0–2 = 0–200 %): each set control moves from
 * its base value toward (or past) the preset's, clamped to range. Curves blend on 17 samples; a look
 * and a grain seed switch over whenever the amount is above zero.
 */
DevelopRecipe applyPreset(const DevelopRecipe& base, const DevelopPreset& preset, float amount = 1.0F);

}  // namespace croptop::develop
