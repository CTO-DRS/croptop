#pragma once

// Develop colour operations — CPU reference for the GPU develop pipeline and the
// export path. Works in Oklab/OkLCh (perceptually even hue and lightness) on scene-linear input:
// calibration (primary hue/saturation + shadow tint, as one white-preserving 3×3 matrix) → look →
// 8-range colour mixer (hue/saturation/luminance) → 3-way colour grading (shadows/midtones/highlights +
// global, with balance and blending).

#include <array>
#include <functional>
#include <optional>
#include <string>

#include "tile_store.h"

namespace croptop::develop {

/** Ranges: red, orange, yellow, green, aqua, blue, purple, magenta. Sliders −100…100. */
struct ColorMixer final {
    std::array<float, 8> hue{};
    std::array<float, 8> saturation{};
    std::array<float, 8> luminance{};
    bool isIdentity() const;
};

struct GradeWheel final {
    float hue = 0;         // degrees 0–360
    float saturation = 0;  // 0–100
    float luminance = 0;   // −100…100
};

struct ColorGrading final {
    GradeWheel shadows, midtones, highlights, global;
    float blending = 50;  // 0–100: overlap between the shadow/highlight masks
    float balance = 0;    // −100…100: moves the shadow/highlight split darker/brighter
    bool isIdentity() const;
};

struct Calibration final {
    float shadow_tint = 0;  // −100 (green) … 100 (magenta)
    float red_hue = 0, red_saturation = 0;
    float green_hue = 0, green_saturation = 0;
    float blue_hue = 0, blue_saturation = 0;
    bool isIdentity() const;
};

/**
 * The colour components of the develop profiles (their tone components belong in ToneSettings).
 * Neutral is core:model's default profile and is a faithful baseline (identical to None).
 */
enum class Look : std::uint8_t { None, Neutral, Vivid, Portrait, Landscape, Monochrome };

struct ColorSettings final {
    Calibration calibration;
    Look look = Look::None;
    ColorMixer mixer;
    ColorGrading grading;
    /** Input primaries → linear sRGB/Rec.709 (row-major); identity for sRGB-linear input. */
    std::array<float, 9> to_linear_srgb{1, 0, 0, 0, 1, 0, 0, 0, 1};
    unsigned worker_threads = 0;
    std::function<bool(float)> progress;
    bool isIdentity() const;
};

/**
 * Everything applyColor derives from the settings before touching a pixel: the folded input matrix, the mixer/look tables and the
 * grading offsets. The CPU loop and the GPU shader (`develop_color_gpu`) both consume it, so the two cannot drift apart.
 */
struct ColorPlan final {
    std::array<float, 9> into{1, 0, 0, 0, 1, 0, 0, 0, 1};      // input primaries → linear sRGB → calibration
    std::array<float, 9> from_srgb{1, 0, 0, 0, 1, 0, 0, 0, 1};  // linear sRGB → input primaries
    bool mixing = false, grading = false, mono = false;
    float look_chroma = 1.0F, tint = 0.0F, split = 0.56F, spread = 0.25F;
    std::array<float, 8> hue{}, saturation{}, luminance{};       // mixer + look, slider units
    std::array<float, 3> shadows{}, midtones{}, highlights{}, global{};  // Oklab L, a, b offsets
};

/** Validates `settings` and builds the plan. `identity` is set when the settings change nothing (no plan is needed then). */
std::optional<ColorPlan> makeColorPlan(const ColorSettings& settings, PixelFormat format, bool& identity, std::string& error);

/** Returns a new layer in the input's format (RGBA16 clamps to 0–1; RGBA32F is unbounded). */
std::optional<TileStore> applyColor(const TileStore& linear, const ColorSettings& settings, std::string& error);

}  // namespace croptop::develop
