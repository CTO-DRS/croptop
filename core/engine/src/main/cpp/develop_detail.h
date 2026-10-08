#pragma once

// Develop detail and effects — CPU reference for the GPU develop pipeline and the
// export path. On scene-linear input, per 256² tile with a border (seamless across tiles):
// colour noise reduction → luminance noise reduction → sharpening (with detail and edge masking) →
// post-crop vignette → grain. Sharpening and luminance NR act on luminance only, so they add no fringes.

#include <array>
#include <cstdint>
#include <functional>
#include <optional>
#include <string>

#include "tile_store.h"

namespace croptop::develop {

struct Sharpening final {
    float amount = 0;    // 0–150
    float radius = 1.0F; // 0.5–3 px
    float detail = 25;   // 0–100: low suppresses halos on strong edges, high sharpens fine texture fully
    float masking = 0;   // 0–100: restrict sharpening to edges (flat areas and noise untouched)
};

struct NoiseReduction final {
    float luminance = 0;         // 0–100
    float luminance_detail = 50; // 0–100: how much fine detail is kept
    float color = 0;             // 0–100
    float color_detail = 50;     // 0–100: how sharply colour edges are kept
};

struct Vignette final {
    float amount = 0;      // −100 (darken) … 100 (lighten)
    float midpoint = 50;   // 0–100
    float roundness = 0;   // −100 (frame-shaped) … 100 (circular)
    float feather = 50;    // 0–100
    float highlights = 0;  // 0–100: protect bright areas when darkening
    /** The post-crop frame in layer pixels (defaults to the whole layer when empty). */
    std::int64_t left = 0, top = 0, right = 0, bottom = 0;
};

struct Grain final {
    float amount = 0;     // 0–100
    float size = 25;      // 0–100
    float roughness = 50; // 0–100: mix of coarse and fine grain
    std::uint32_t seed = 1;
};

struct DetailSettings final {
    Sharpening sharpening;
    NoiseReduction noise;
    Vignette vignette;
    Grain grain;
    std::array<float, 3> luminance{0.2126F, 0.7152F, 0.0722F};  // weights of the input primaries
    unsigned worker_threads = 0;
    std::function<bool(float)> progress;
    bool isIdentity() const;
};

/** Returns a new layer in the input's format (RGBA16 clamps to 0–1; RGBA32F is unbounded). */
std::optional<TileStore> applyDetail(const TileStore& linear, const DetailSettings& settings, std::string& error);

}  // namespace croptop::develop
