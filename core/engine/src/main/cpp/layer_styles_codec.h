#pragma once

// Flat float encoding of styles::LayerStyles for the JNI boundary. `NativeLayerStylesBridge` (Kotlin) writes the
// same layout. Blocks in this order, each starting with an `enabled` flag (0/1); colours are straight RGBA 0–1; enums are ordinals:
//
//   drop shadow, inner shadow (11 each): enabled, r g b a, blendMode, opacity, angle, distance, spread, size
//   outer glow, inner glow (10 each):    enabled, r g b a, blendMode, opacity, spread, size, fromCenter
//   bevel (21):  enabled, style, technique, depth, up, size, soften, angle, altitude,
//                highlight r g b a, highlightMode, highlightOpacity, shadow r g b a, shadowMode, shadowOpacity
//   satin (11):  enabled, r g b a, blendMode, opacity, angle, distance, size, invert
//   colour overlay (7): enabled, r g b a, blendMode, opacity
//   gradient overlay: enabled, blendMode, opacity, angle, scale, reverse, stopCount (1..16), then stopCount × (position, r g b a)
//   pattern overlay (4): enabled, blendMode, opacity, scale        (the pattern store travels as its own handle)
//   stroke (9):  enabled, r g b a, blendMode, opacity, size, position
//
// The block order is: drop shadow, outer glow, bevel, inner shadow, inner glow, satin, colour overlay, gradient overlay, pattern overlay, stroke.

#include <optional>
#include <span>
#include <string>
#include <vector>

#include "layer_styles.h"

namespace croptop::styles {

inline constexpr std::size_t kMaxGradientStops = 16;

std::vector<float> encodeStyles(const LayerStyles& styles);

/** `pattern` is attached to the pattern overlay when it is enabled. nullopt with `error` on malformed or out-of-range data. */
std::optional<LayerStyles> decodeStyles(std::span<const float> values, const TileStore* pattern, std::string& error);

}  // namespace croptop::styles
