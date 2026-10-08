#pragma once

// Maps the core:model AdjustmentCatalog parameters (spec id + key → value map, the engine-api
// `Map<String, ParamValue>`) onto adjust:: structs, so JNI glue only marshals values. Keys, units and ranges follow
// AdjustmentSchema.kt; numbers are clamped into the schema's ranges and missing keys take the schema's defaults.
// Model limits mirrored here: channelMixer carries one output row (`output` picks which; the others stay
// identity) and selectiveColor one range (`colors` picks which) .

#include <map>
#include <optional>
#include <string>
#include <variant>
#include <vector>

#include "adjustments.h"

namespace croptop::adjust {

using ParamValue = std::variant<double, std::string, bool, std::array<float, 4>, Levels, Curves, HueSaturation, ColorBalance,
                                std::vector<GradientStop>>;
using ParamMap = std::map<std::string, ParamValue>;

struct MappedAdjustment final {
    std::optional<Adjustment> pixel;           // per-pixel adjustments (bakeable into a LUT)
    std::optional<ShadowsHighlights> spatial;  // shadowsHighlights
};

/** Nullopt (with `error`) for unknown spec ids or values of the wrong type. */
std::optional<MappedAdjustment> fromParams(const std::string& spec_id, const ParamMap& params, std::string& error);

}  // namespace croptop::adjust
