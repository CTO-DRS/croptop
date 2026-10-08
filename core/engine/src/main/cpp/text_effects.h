#pragma once

// Text effects: Warp Text (Photoshop's fifteen envelope styles with bend and
// horizontal/vertical distortion) and type on a path. Both work on glyph outlines as vector paths, so the
// result stays resolution-independent until it is filled.
//
// Warp maps each outline point through an envelope over the text's bounding box: coordinates are
// normalised to u, v ∈ [−1, 1] (v up), bent by the style, distorted, and mapped back. Curves are flattened
// first (the envelope is non-linear). The formulas are CropTop's own analogues of Photoshop's styles.

#include <array>
#include <cstdint>
#include <string>

#include "text_layout.h"
#include "tile_store.h"
#include "vector_path.h"

namespace croptop::text {

class TrueTypeFont;

enum class WarpStyle : std::uint8_t {
    None, Arc, ArcLower, ArcUpper, Arch, Bulge, ShellLower, ShellUpper, Flag, Wave, Fish, Rise, Fisheye, Inflate, Squeeze, Twist
};

struct WarpSpec final {
    WarpStyle style = WarpStyle::None;
    float bend = 0.5F;                   // −1…1 (Photoshop's −100…100 %)
    float horizontal_distortion = 0;     // −1…1
    float vertical_distortion = 0;       // −1…1
    bool vertical = false;               // warp along the vertical axis instead (Photoshop's Vertical option)
};

/** The layout's glyph outlines (layer pixel space, origin at the text box's top-left + origin). */
vector::Path layoutOutlines(const Layout& layout, const TrueTypeFont& font, double origin_x, double origin_y);

/** Warps a path through the style's envelope over its own bounding box (tolerance: flattening, px). */
vector::Path warp(const vector::Path& path, const WarpSpec& spec, double tolerance = 0.25);

/**
 * Type on a path: the first line of `layout` is set along `path` starting `start` px along it; each glyph is
 * rotated to the path's tangent at its centre, its baseline on the path. Glyphs past the end are dropped.
 */
vector::Path textOnPath(const Layout& layout, const TrueTypeFont& font, const vector::Path& path, double start = 0, double tolerance = 0.25);

/** Fills a path (non-zero) over an RGBA layer in a straight colour (source-over). */
bool fillPath(TileStore& layer, const vector::Path& path, std::array<float, 4> color, std::string& error);

}  // namespace croptop::text
