#pragma once

// Layer styles, CPU reference renderer. Photoshop blends each effect with the layers
// beneath (a multiply drop shadow darkens the backdrop, not the content), so rendering returns effect layers
// — colour × coverage with their own blend mode and opacity — for the compositor to stack in order:
//   below the content: drop shadow, outer glow, outer bevel/emboss half;
//   above the content (coverage already clipped to the content's alpha where Photoshop clips):
//   inner bevel highlight and shadow, inner shadow, inner glow, satin, colour overlay, gradient overlay,
//   pattern overlay, stroke.
// flatten() composites a layer with its styles onto a backdrop with blend_modes.h (the golden for the GPU path).
//
// Geometry (A = the layer's alpha, d = exact Euclidean distances to the A ≥ 50 % shape):
//   shadows: the mask (A, or 1 − A for inner shadows) is shifted by distance at `angle` (the light comes from
//     angle, the shadow falls opposite), spread/choke dilates it by spread% × size (via distance), then blurs
//     with σ = (1 − spread) × size / 2; inner shadows are multiplied by A;
//   glows: full within spread% × size of the edge, then a smoothstep fade to nothing at `size` px (outside the
//     shape for outer glows; inside, multiplied by A, for inner glows — or the complement for "from centre");
//   stroke: coverage = clamp(size + 1 − d) with d between pixel centres (the edge is at d = 0.5), outside,
//     inside or centred (half each side);
//   bevel & emboss: a height field from the distance inside the shape (inner), outside (outer) or both
//     (emboss / pillow), smoothed by size (Smooth) or kept sharp (Chisel), softened by `soften`; normals from
//     its gradient scaled by depth light it from (angle, altitude); the brightening beyond the flat response
//     is the highlight (screen), the darkening the shadow (multiply).

#include <array>
#include <cstdint>
#include <optional>
#include <string>
#include <vector>

#include "blend_modes.h"
#include "tile_store.h"

namespace croptop::styles {

using Rgba = std::array<float, 4>;  // straight, 0–1

struct Shadow final {  // drop shadow and inner shadow
    bool enabled = false;
    Rgba color{0, 0, 0, 1};
    BlendMode mode = BlendMode::Multiply;
    float opacity = 0.75F;
    float angle = 120.0F;     // degrees, light direction
    float distance = 5.0F;    // px
    float spread = 0.0F;      // % (choke for inner shadows)
    float size = 5.0F;        // px
};

struct Glow final {  // outer and inner glow
    bool enabled = false;
    Rgba color{1.0F, 1.0F, 190 / 255.0F, 1};
    BlendMode mode = BlendMode::Screen;
    float opacity = 0.75F;
    float spread = 0.0F;  // % (choke for inner glows)
    float size = 5.0F;
    bool from_center = false;  // inner glow source: centre instead of edge
};

enum class StrokePosition : std::uint8_t { Outside, Inside, Center };
struct Stroke final {
    bool enabled = false;
    Rgba color{1, 0, 0, 1};
    BlendMode mode = BlendMode::Normal;
    float opacity = 1.0F;
    float size = 3.0F;
    StrokePosition position = StrokePosition::Outside;
};

struct ColorOverlay final {
    bool enabled = false;
    Rgba color{1, 0, 0, 1};
    BlendMode mode = BlendMode::Normal;
    float opacity = 1.0F;
};

struct GradientStop final {
    float position;
    Rgba color;
};
struct GradientOverlay final {
    bool enabled = false;
    std::vector<GradientStop> stops{{0, {0, 0, 0, 1}}, {1, {1, 1, 1, 1}}};
    BlendMode mode = BlendMode::Normal;
    float opacity = 1.0F;
    float angle = 90.0F;   // degrees, direction the gradient runs (bottom → top at 90)
    float scale = 1.0F;    // 0.1–1.5 of the layer bounds
    bool reverse = false;
};

struct PatternOverlay final {
    bool enabled = false;
    const TileStore* pattern = nullptr;  // RGBA
    BlendMode mode = BlendMode::Normal;
    float opacity = 1.0F;
    float scale = 1.0F;
};

enum class BevelStyle : std::uint8_t { InnerBevel, OuterBevel, Emboss, PillowEmboss };
enum class BevelTechnique : std::uint8_t { Smooth, ChiselHard };
struct Bevel final {
    bool enabled = false;
    BevelStyle style = BevelStyle::InnerBevel;
    BevelTechnique technique = BevelTechnique::Smooth;
    float depth = 1.0F;   // 0.01–10 (100 % = 1)
    bool up = true;
    float size = 5.0F;
    float soften = 0.0F;
    float angle = 120.0F;
    float altitude = 30.0F;
    Rgba highlight_color{1, 1, 1, 1};
    BlendMode highlight_mode = BlendMode::Screen;
    float highlight_opacity = 0.75F;
    Rgba shadow_color{0, 0, 0, 1};
    BlendMode shadow_mode = BlendMode::Multiply;
    float shadow_opacity = 0.75F;
};

/** Satin: |blur(A shifted +d) − blur(A shifted −d)| along `angle` (inverted when asked), clipped to A. */
struct Satin final {
    bool enabled = false;
    Rgba color{0, 0, 0, 1};
    BlendMode mode = BlendMode::Multiply;
    float opacity = 0.5F;
    float angle = 19.0F;
    float distance = 11.0F;
    float size = 14.0F;
    bool invert = true;
};

struct LayerStyles final {
    Shadow drop_shadow;
    Glow outer_glow;
    Bevel bevel;
    Shadow inner_shadow;
    Glow inner_glow;
    Satin satin;
    ColorOverlay color_overlay;
    GradientOverlay gradient_overlay;
    PatternOverlay pattern_overlay;
    Stroke stroke;
};

struct EffectLayer final {
    TileStore pixels;  // RGBA (straight), canvas-sized: colour with alpha = coverage
    BlendMode mode = BlendMode::Normal;
    float opacity = 1.0F;
    bool below_content = false;
    const char* name = "";
};

std::optional<std::vector<EffectLayer>> render(const TileStore& layer, const LayerStyles& styles, std::string& error,
                                               std::uint64_t max_working_bytes = 1ULL << 30);

/** The layer (drawn Normal at `fill_opacity`) with its effects composited onto `backdrop` (same size, RGBA). */
std::optional<TileStore> flatten(const TileStore& layer, const LayerStyles& styles, const TileStore& backdrop, float fill_opacity,
                                 std::string& error);

}  // namespace croptop::styles
