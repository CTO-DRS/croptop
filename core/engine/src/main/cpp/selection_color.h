#pragma once

// Color-based selections: Magic Wand and Select ▸ Color Range.
//
// `source` is the pixels being sampled: the primary layer, or the merged composite for "sample all
// layers" (RGBA8, RGBA16 or RGBA32F; RGBA32F is clamped to [0, 1]). The result is an A16 selection
// mask of the same size, to be combined with the current selection by the caller (selection::combine).
//
// Magic Wand: a pixel matches when every channel (R, G, B and A) differs from the seed pixel by at
//   most `tolerance` (0–255, scaled to 16 bits for deeper formats). Contiguous mode flood-fills with
//   4-connectivity from the seed; otherwise every matching pixel is selected. Hard edges.
// Color Range (fuzziness 0–200, like Photoshop), coverage multiplied by pixel alpha:
//   SAMPLED: ΔE76 in CIELAB (D65) to the nearest sample; full up to fuzziness/4, linear to 0 at
//            fuzziness/2 (fuzziness 0: ΔE < 1 only).
//   Hue families (REDS 0°, YELLOWS 60°, GREENS 120°, CYANS 180°, BLUES 240°, MAGENTAS 300°): full within
//            15° of the center, linear to 0 at 45°, gated by HSV saturation (0 below 0.08, full above 0.2).
//   HIGHLIGHTS / MIDTONES / SHADOWS: by CIELAB L*/100 — shadows full below 0.25 fading out by 0.35,
//            highlights full above 0.75 fading in from 0.65, midtones full in 0.35–0.65 fading to 0
//            at 0.25 and 0.75.
//   SKIN_TONES: hue 5°–40° (fading 0°/50°), saturation 0.15–0.65 (fading 0.08/0.8), L* 0.25–0.9.
//   `invert` returns 1 − coverage.

#include <array>
#include <cstdint>
#include <optional>
#include <string>
#include <vector>

#include "tile_store.h"

namespace croptop::selection {

struct MagicWandSpec final {
    std::int64_t x = 0;
    std::int64_t y = 0;
    int tolerance = 32;
    bool contiguous = true;
};

std::optional<TileStore> magicWand(const TileStore& source, const MagicWandSpec& spec, std::string& error);

enum class ColorRangeTarget : std::uint8_t {
    Sampled, Reds, Yellows, Greens, Cyans, Blues, Magentas, Highlights, Midtones, Shadows, SkinTones,
};

struct ColorRangeSpec final {
    ColorRangeTarget target = ColorRangeTarget::Sampled;
    /** sRGB-encoded samples in [0, 1] (alpha is not used for matching). */
    std::vector<std::array<float, 3>> samples;
    int fuzziness = 40;
    bool invert = false;
    /** 0 = hardware concurrency (capped at 8). */
    unsigned worker_threads = 0;
};

std::optional<TileStore> colorRange(const TileStore& source, const ColorRangeSpec& spec, std::string& error);

}  // namespace croptop::selection
