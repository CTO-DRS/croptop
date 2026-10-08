#pragma once

// Gradient tool and paint bucket, painting into RGBA layers.
//
// Gradient parameter t for a pixel center p, start s, end e (d = e − s, L = |d|):
//   Linear: (p−s)·d / L²   Radial: |p−s| / L   Reflected: |(p−s)·d| / L²
//   Angle: clockwise sweep (y-down) from the direction of d, / 360°
//   Diamond: (|(p−s)·u| + |(p−s)·v|) / L with u = d/L, v ⟂ u
// t is clamped to [0, 1] (reversed if asked). Colour stops are sRGB-encoded; `Perceptual` interpolates
// encoded values (Photoshop's default), `LinearLight` interpolates in linear light. Opacity stops are
// interpolated linearly. The result is composited source-over (RGBA8/16 in encoded space; RGBA32F
// stores linear light) with alpha = stop opacity × paint opacity × selection coverage. Dither adds
// deterministic triangular noise of ±1 LSB before quantization.
//
// Paint bucket: the region is selection::magicWand on `sample_source` (the layer, or the merged image
// for "all layers"), intersected with the selection; the colour is composited with alpha = coverage ×
// opacity.

#include <cstdint>
#include <optional>
#include <string>
#include <vector>

#include "tile_store.h"

namespace croptop::paint {

enum class GradientShape : std::uint8_t { Linear, Radial, Angle, Reflected, Diamond };
enum class Interpolation : std::uint8_t { Perceptual, LinearLight };

struct Point final {
    double x = 0.0;
    double y = 0.0;
};

struct ColorStop final {
    double position = 0.0;  // 0–1
    float r = 0.0F;         // sRGB-encoded 0–1
    float g = 0.0F;
    float b = 0.0F;
};

struct OpacityStop final {
    double position = 0.0;  // 0–1
    float opacity = 1.0F;   // 0–1
};

struct GradientSpec final {
    GradientShape shape = GradientShape::Linear;
    Point start;
    Point end;
    std::vector<ColorStop> colors;       // at least one
    std::vector<OpacityStop> opacities;  // empty = fully opaque
    Interpolation interpolation = Interpolation::Perceptual;
    bool reverse = false;
    bool dither = true;
    float opacity = 1.0F;                // paint opacity 0–1
    unsigned worker_threads = 0;
};

/** Paints the gradient into `target` (RGBA8/16/32F); `mask` is an optional A16 selection of the same size. */
bool renderGradient(TileStore& target, const GradientSpec& spec, const TileStore* mask, std::string& error);

struct Rgba final {
    float r = 0.0F;  // sRGB-encoded 0–1
    float g = 0.0F;
    float b = 0.0F;
    float a = 1.0F;
};

/** Paint bucket at (x, y): fills matching pixels (see magicWand) inside the optional selection. */
bool bucketFill(TileStore& target, const TileStore& sample_source, std::int64_t x, std::int64_t y, Rgba color,
                int tolerance, bool contiguous, const TileStore* mask, float opacity, std::string& error);

}  // namespace croptop::paint
