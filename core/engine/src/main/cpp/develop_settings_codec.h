#pragma once

// Flat float encoding of develop::pipeline::Settings for the JNI boundary. One FloatArray carries a whole
// DevelopSettings without JSON; `NativeDevelopBridge` (Kotlin) writes the same layout. The layout is versioned by element 0.
//
//   [0] version (1)   [1] profile   [2] wb preset   [3] temperature   [4] tint
//   [5..15]  exposure contrast highlights shadows whites blacks texture clarity dehaze vibrance saturation
//   [16..39] mixer: 8 ranges × (hue, saturation, luminance)
//   [40..51] grading wheels shadows, midtones, highlights, global × (hue, saturation, luminance)   [52] blending  [53] balance
//   [54..59] sharpen amount, radius, detail, masking, luminance noise, colour noise
//   [60..63] vignette amount, midpoint, grain amount, grain size
//   [64..67] lens profile (0/1), remove CA (0/1), vignetting, distortion
//   [68..72] crop left, top, right, bottom, angle
//   [73] tone-curve point count n (2..32)   [74 .. 74+2n) x, y pairs
//   then, optionally, calibration (7: shadow tint, red hue/sat, green hue/sat, blue hue/sat), the upright mode (0 off … 4 auto), defringe purple and green (0–20) and spots (count n, then n × 8:
//   x, y, radius, source x, source y, feather, opacity, heal 0/1); both are present whenever either they or masks are used,
//   then, optionally, local masks: [m] count, per mask: enabled, invert, amount, 11 basic floats, component count, per component:
//   kind, op, p[0..7], stroke count, per stroke: erase, size, feather, flow, point count, x, y pairs. Absent = no masks.

#include <optional>
#include <span>
#include <string>
#include <vector>

#include "develop_pipeline.h"

namespace croptop::develop {

inline constexpr std::size_t kSettingsMaxCurvePoints = 32;
/**
 * Most floats one recipe may carry across JNI: the fixed block and curve, calibration, upright guides, spots, and up to 16 masks of
 * 8 components with their brush strokes or AI bitmaps. The codec checks the exact structure; this only bounds the copy.
 */
inline constexpr std::size_t kSettingsMaxFloats = 1U << 20;
inline constexpr std::size_t kSettingsFixedFloats = 74;

std::vector<float> encodeSettings(const pipeline::Settings& settings);

/** nullopt with `error` for a wrong version, a size that does not match the curve count, non-finite values or bad enums. */
std::optional<pipeline::Settings> decodeSettings(std::span<const float> values, std::string& error);

}  // namespace croptop::develop
