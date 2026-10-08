#pragma once

// Geometry / Upright, native core. Produces 3×3 homographies (row-major, source →
// destination document points, like resample::warp expects) that straighten perspective.
//
// detectSegments: LSD-style line segments on a luminance copy reduced to ≤ 1200 px (mip levels): pixels with a
//   strong gradient grow regions of similar level-line angle (±22.5°); each region's weighted PCA gives a
//   segment, kept when it is at least 2 % of the diagonal long and 5× longer than wide. Coordinates are full
//   resolution.
// upright: vanishing points of near-vertical and near-horizontal segments (length-weighted RANSAC, refined by
//   least squares), then a camera rotation R with H = K·R·K⁻¹ (K from `focal_length_px`, default 1.2 × the
//   longer side), re-centred so the image centre stays put:
//     Level    — rotation about the view axis so the dominant verticals (or horizontals) are axis-aligned;
//     Vertical — the vertical vanishing point is sent to infinity along y (verticals become parallel, upright);
//     Full     — Vertical, then a rotation about y so horizontals become parallel too;
//     Auto     — Vertical when its tilt is ≤ 25°, else Level;
//     Guided   — from user lines (≥ 2 near-vertical → vertical point, ≥ 2 near-horizontal → horizontal point,
//                a single line → level).
// manualTransform: Lightroom-style sliders as the same camera model (vertical/horizontal tilt, rotation, aspect,
//   scale, offsets).
// suggestedCrop: the largest rectangle of the original aspect, centred on the image centre, inside the
//   transformed image.

#include <array>
#include <cstdint>
#include <optional>
#include <string>
#include <vector>

#include "tile_store.h"

namespace croptop::geometry {

using Matrix3 = std::array<double, 9>;

struct Segment final {
    double x1, y1, x2, y2;
    double length() const;
};

enum class UprightMode : std::uint8_t { Level, Vertical, Full, Auto, Guided };

struct UprightSpec final {
    UprightMode mode = UprightMode::Auto;
    double focal_length_px = 0.0;        // 0 = 1.2 × the longer side
    std::vector<Segment> guides;         // Guided mode, document px
};

struct UprightResult final {
    Matrix3 matrix{1, 0, 0, 0, 1, 0, 0, 0, 1};
    bool vertical_found = false;
    bool horizontal_found = false;
    double tilt_degrees = 0.0;   // total rotation applied
    std::size_t segments = 0;
    std::string note;            // why a mode fell back (empty when it applied as asked)
};

std::vector<Segment> detectSegments(const TileStore& image, std::string& error);

std::optional<UprightResult> upright(const TileStore& image, const UprightSpec& spec, std::string& error);
/** The same, from already-detected segments (for re-running modes without re-detecting). */
UprightResult uprightFromSegments(std::uint32_t width, std::uint32_t height, const std::vector<Segment>& segments, const UprightSpec& spec);

struct ManualTransform final {
    double vertical = 0.0;    // −100…100 → ±30° tilt about x
    double horizontal = 0.0;  // −100…100 → ±30° about y
    double rotate = 0.0;      // degrees
    double aspect = 0.0;      // −100…100 → x scale 0.5…1.5 relative to y
    double scale = 100.0;     // %
    double x_offset = 0.0;    // −100…100 % of half the width
    double y_offset = 0.0;
    double focal_length_px = 0.0;
};

Matrix3 manualTransform(std::uint32_t width, std::uint32_t height, const ManualTransform& spec);

/**
 * Focal length in pixels from a 35 mm-equivalent focal length (e.g. Exif::focal_length_35mm): the 35 mm frame's
 * diagonal (43.27 mm) maps onto the image diagonal. Returns 0 (use the default) for missing or absurd values.
 */
double focalLengthPixels(double focal_35mm, std::uint32_t width, std::uint32_t height);

struct Rect final {
    double left, top, right, bottom;
};
/** Largest centred rectangle with the image's aspect inside the image transformed by `matrix`. */
Rect suggestedCrop(std::uint32_t width, std::uint32_t height, const Matrix3& matrix);

}  // namespace croptop::geometry
