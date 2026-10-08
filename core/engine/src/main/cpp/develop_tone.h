#pragma once

// Develop tone operations — the CPU reference the GPU develop pipeline is
// checked against, and the full-resolution export path.
//
// Input is a scene-linear RGBA layer (RGBA16 or RGBA32F, e.g. from raw::develop). Local operations work
// on log2 luminance with a *fast guided filter*: coefficients are solved on a low-resolution plane and
// applied at full resolution with the full-resolution luminance as guide, so large images need no
// full-frame planes and edges stay halo-free. Order: dehaze → exposure → highlights/shadows (local) →
// clarity → texture → contrast → whites/blacks → tone curves → vibrance/saturation.

#include <array>
#include <cstdint>
#include <functional>
#include <optional>
#include <string>
#include <vector>

#include "adjustments.h"
#include "tile_store.h"

namespace croptop::develop {

/** Lightroom-style parametric tone curve: region amounts −100…100 and split points (0–1, ascending). */
struct ParametricCurve final {
    float highlights = 0, lights = 0, darks = 0, shadows = 0;
    float split_shadows = 0.25F, split_midtones = 0.5F, split_highlights = 0.75F;
    bool isIdentity() const { return highlights == 0 && lights == 0 && darks == 0 && shadows == 0; }
};

struct ToneSettings final {
    float dehaze = 0;      // −100…100 (negative adds haze)
    float exposure = 0;    // stops, −5…5
    float contrast = 0;    // −100…100
    float highlights = 0;  // −100…100 (local)
    float shadows = 0;     // −100…100 (local)
    float whites = 0;      // −100…100
    float blacks = 0;      // −100…100
    float texture = 0;     // −100…100 (fine/medium detail)
    float clarity = 0;     // −100…100 (midtone local contrast)
    float vibrance = 0;    // −100…100
    float saturation = 0;  // −100…100
    ParametricCurve parametric;
    adjust::Curve point_curve;  // applied to display-encoded luminance (0–1), hue preserved
    /** Luminance weights of the input primaries: Rec.709/sRGB by default; ProPhoto is {0.28804, 0.71188, 0.00008}. */
    std::array<float, 3> luminance{0.2126F, 0.7152F, 0.0722F};
    unsigned worker_threads = 0;
    std::function<bool(float)> progress;  // 0–1; false cancels

    bool isIdentity() const;
};

/**
 * What applyTone derives at low resolution before the per-pixel pass: block size, the dehaze/local-tone/clarity guided-filter
 * coefficient planes (`low_width × low_height`, output = a·guide + b) and the curve tables. The CPU pass and the GPU shader
 * (`develop_tone_gpu`) both read it.
 */
struct TonePlan final {
    int block = 1, low_width = 0, low_height = 0;
    std::array<float, 3> air{1, 1, 1};
    bool local_tone = false, clarity = false, texture = false, dehaze_positive = false, point_curve = false;
    std::vector<float> haze_a, haze_b, base_a, base_b, detail_a, detail_b;
    std::vector<float> parametric;  // 1025-entry lookup on display-encoded luminance, empty when unused
    std::vector<float> point_lut;   // 1025-entry sampling of the point curve, empty when unused
};

/** Range checks shared by the CPU and GPU paths. false with `error` for out-of-range sliders or bad luminance weights. */
bool validateToneSettings(const ToneSettings& settings, std::string& error);

/** The low-resolution stage of applyTone (also reports progress 0.2 and 0.35 and honours cancellation). nullopt with `error` on failure. */
std::optional<TonePlan> makeTonePlan(const TileStore& linear, const ToneSettings& settings, std::string& error);

/** Returns a new layer in the input's format (RGBA16 output is clamped to 0–1; RGBA32F is not). */
std::optional<TileStore> applyTone(const TileStore& linear, const ToneSettings& settings, std::string& error);

}  // namespace croptop::develop
