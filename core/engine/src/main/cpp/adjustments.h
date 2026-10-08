#pragma once

// CPU reference kernels for the v1 adjustments, parameterised like the
// core:model AdjustmentCatalog. They work on the stored values (sRGB-encoded for RGBA8/16, as stored for
// RGBA32F) with straight alpha, which is kept. Formulas (x = a channel value 0–1, L = Rec.709 luma):
//   BrightnessContrast: brightness b: y = x^γ, γ = ln(0.5 + b/600)/ln(0.5); contrast c ≥ 0: y + c/100·(S(y) − y)
//     with S(t) = t²(3 − 2t); c < 0: 0.5 + (y − 0.5)(1 + c/100). Legacy: y = x + b/255, then (y − 0.5)(1 + c/100) + 0.5.
//   Levels: outBlack + ((x − inBlack)/(inWhite − inBlack))^(1/γ)·(outWhite − outBlack) (core:model Levels.apply).
//   Curves: per channel curve, then the RGB (composite) curve; monotone cubic (Fritsch–Carlson), exactly
//     core:model Curve.evaluate.
//   Exposure: in linear light, (lin·2^exposure + offset)^(1/gamma), re-encoded.
//   Vibrance: in HSL saturation S (always in gamut): S ×= 1 + saturation/100, then S += vibrance/100 · S(1 − S)
//     (muted colours move most, grays not at all).
//   HueSaturation: in HSL; hue += Δh, saturation ×(1 + Δs/100), lightness towards white/black by Δl/100, where
//     each Δ is master + Σ channel shift × its hue-range weight (core:model HueRange.weight); colorize sets
//     hue = master hue (0–360), saturation = master saturation/100 and adjusts lightness the same way.
//   ColorBalance: channel += 0.25 · shift/100 · weight(L) for shadows (1 − L)², midtones 4L(1 − L),
//     highlights L²; preserve luminosity rescales to the original luma.
//   BlackWhite: gray = min + (max − mid)·w(primary of max) + (mid − min)·w(secondary of max & mid), weights in
//     % (Photoshop's defaults 40/60/40/60/20/80); tint colours the gray via HSL (tint hue/saturation, gray as
//     lightness).
//   PhotoFilter: mix(c, c·filter, density); preserve luminosity rescales to the original luma (limited so no
//     channel clips). Presets: Warming (85) (236,138,0), Cooling (80) (0,109,255), Sepia (172,122,51).
//   Invert: 1 − x.  Posterize n: floor(min(x·n, n − 1))/(n − 1).  Threshold t: L ≥ t/255 → 1, else 0.
//   ChannelMixer: out_c = Σ_k m[c][k]/100 · in_k + constant_c/100; monochrome uses the red row for all.
//   GradientMap: colour at L (1 − L when reversed); optional ±0.5/255 ordered dither.
//   SelectiveColor: per range weight a (reds/greens/blues: max − mid when that channel is max;
//     yellows/cyans/magentas: mid − min when the other channel is min; whites: 2·(min − 0.5)⁺;
//     blacks: 2·(0.5 − max)⁺; neutrals: 1 − |max − 0.5| − |min − 0.5|, ≥ 0); with ink i = 1 − x and
//     CMY p (cyan→R, magenta→G, yellow→B) and black k as fractions: relative i += a·(p·i + k·i),
//     absolute i += a·(p + k).
//   ShadowsHighlights (spatial): a luminance copy blurred with σ = 1.5 % of the longer side gives Lb;
//     y = x^γ with γ = 1 − 0.6·s·(1 − Lb)² + 0.6·h·Lb².

#include <array>
#include <cstdint>
#include <optional>
#include <string>
#include <utility>
#include <variant>
#include <vector>

#include "lut.h"
#include "tile_store.h"

namespace croptop::adjust {

using Rgb = std::array<float, 3>;

struct Curve final {
    std::vector<std::pair<float, float>> points{{0.0F, 0.0F}, {1.0F, 1.0F}};  // sorted by x, 0–1
    float evaluate(float x) const;
};

struct BrightnessContrast final { float brightness = 0, contrast = 0; bool legacy = false; };
struct Levels final { float in_black = 0, in_white = 1, gamma = 1, out_black = 0, out_white = 1; };
struct Curves final { Curve rgb, red, green, blue; };
struct Exposure final { float exposure = 0, offset = 0, gamma = 1; };
struct Vibrance final { float vibrance = 0, saturation = 0; };
struct HueShift final { float hue = 0, saturation = 0, lightness = 0; };
struct HueRange final { float outer_start, inner_start, inner_end, outer_end; float weight(float hue) const; };
struct HueSaturation final {
    HueShift master;
    std::array<HueShift, 6> channels{};  // reds, yellows, greens, cyans, blues, magentas
    std::array<HueRange, 6> ranges{{{315, 345, 15, 45}, {15, 45, 75, 105}, {75, 105, 135, 165}, {135, 165, 195, 225}, {195, 225, 255, 285}, {255, 285, 315, 345}}};
    bool colorize = false;
};
struct BalanceShift final { float cyan_red = 0, magenta_green = 0, yellow_blue = 0; };
struct ColorBalance final { BalanceShift shadows, midtones, highlights; bool preserve_luminosity = true; };
struct BlackWhite final {
    float reds = 40, yellows = 60, greens = 40, cyans = 60, blues = 20, magentas = 80;
    bool tint = false;
    Rgb tint_color{0.88F, 0.76F, 0.58F};
};
struct PhotoFilter final { Rgb color{236 / 255.0F, 138 / 255.0F, 0.0F}; float density = 25; bool preserve_luminosity = true; };
struct Invert final {};
struct Posterize final { float levels = 4; };
struct Threshold final { float level = 128; };
struct ChannelMixer final {
    std::array<std::array<float, 3>, 3> matrix{{{100, 0, 0}, {0, 100, 0}, {0, 0, 100}}};  // % [out][in]
    std::array<float, 3> constant{};
    bool monochrome = false;
};
struct GradientStop final { float position; std::array<float, 4> color; };
struct GradientMap final { std::vector<GradientStop> stops{{0, {0, 0, 0, 1}}, {1, {1, 1, 1, 1}}}; bool reverse = false; bool dither = false; };
enum class SelectiveRange : std::uint8_t { Reds, Yellows, Greens, Cyans, Blues, Magentas, Whites, Neutrals, Blacks };
struct SelectiveColor final {
    std::array<std::array<float, 4>, 9> cmyk{};  // % per range: cyan, magenta, yellow, black
    bool absolute = false;
};

using Adjustment = std::variant<BrightnessContrast, Levels, Curves, Exposure, Vibrance, HueSaturation, ColorBalance, BlackWhite, PhotoFilter,
                                Invert, Posterize, Threshold, ChannelMixer, GradientMap, SelectiveColor>;

/** One pixel (straight RGB, 0–1). Dither for GradientMap needs a position: pass it, or 0. */
Rgb applyPixel(const Adjustment& adjustment, Rgb rgb, std::uint32_t x = 0, std::uint32_t y = 0);

/** Applies to an RGBA layer, blended by opacity (0–1) × the optional A16 mask's coverage. */
bool apply(TileStore& layer, const Adjustment& adjustment, const TileStore* mask, float opacity, std::string& error,
           unsigned worker_threads = 0);

/** Bakes a per-pixel adjustment (or a stack, applied in order) into a size³ 3D LUT for the GPU. */
color::Lut bakeLut(const std::vector<Adjustment>& stack, std::uint32_t size = 33);

struct ShadowsHighlights final { float shadows = 35, highlights = 0; };
bool shadowsHighlights(TileStore& layer, const ShadowsHighlights& spec, const TileStore* mask, float opacity, std::string& error,
                       unsigned worker_threads = 0);

}  // namespace croptop::adjust
