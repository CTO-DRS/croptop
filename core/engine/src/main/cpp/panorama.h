#pragma once

// Panorama stitching (like Photoshop's Photomerge).
//   Features: FAST-9 corners ranked by Harris response, spread over a grid, with oriented BRIEF (ORB-style)
//             256-bit descriptors, over a 4-level ×1.3 scale pyramid of a box-reduced gray copy (≤ 1200 px
//             on the long side).
//   Matching: brute-force Hamming with a ratio test and cross-check, then RANSAC per image pair for the
//             projection's model (homography, similarity on the cylinder, or translation); pairs are kept
//             when the inliers pass Brown & Lowe's check (n_inliers > 8 + 0.3·n_matches).
//   Placement: a maximum spanning tree over the kept pairs (weighted by inliers), rooted at its centre;
//              images outside the largest connected group are reported as dropped. No bundle adjustment.
//   Projections: Perspective (planar, onto the centre image), Cylindrical (focal length estimated from the
//                pairwise homographies, then pan/roll alignment on the cylinder), Reposition (translation
//                only), and Auto (Perspective unless it would blow up, then Cylindrical).
//   Blending: per-image gain compensation (Brown & Lowe) and feathered weights that fall off toward each
//             image's borders; pixels no image covers stay transparent.

#include <array>
#include <cstdint>
#include <functional>
#include <optional>
#include <span>
#include <string>
#include <vector>

#include "tile_store.h"

namespace croptop::panorama {

enum class Projection : std::uint8_t { Auto, Perspective, Cylindrical, Reposition };

struct StitchOptions final {
    Projection projection = Projection::Auto;
    bool blend = true;                   // feathered blending (false: the centre-most image wins per pixel)
    bool exposure_compensation = true;   // per-image gains from the overlaps
    unsigned worker_threads = 0;
    std::function<bool(float)> progress;
};

struct StitchResult final {
    TileStore image;
    Projection projection = Projection::Perspective;  // the one used (resolved from Auto)
    std::vector<std::size_t> placed, dropped;         // input indices
    std::vector<float> gains;                         // per input (1 for dropped images)
    double focal = 0;                                 // cylinder focal length in pixels (Cylindrical only)
    std::size_t anchor = 0;                           // the centre image the others were placed against
    // Output pixel (0, 0)'s corner in the anchor's model space: its pixels for Perspective/Reposition, or
    // cylinder coordinates about its centre (u = focal·angle, v = focal·height) for Cylindrical.
    double origin_x = 0, origin_y = 0;
};

/** Stitches 2–32 RGBA layers (any mix of formats; the output takes the first placed layer's format). */
std::optional<StitchResult> stitch(std::span<const TileStore* const> images, const StitchOptions& options, std::string& error);

namespace detail {
/** Features for tests and tooling: keypoint positions (full-resolution pixels) and 32-byte descriptors. */
struct Features final {
    std::vector<std::array<float, 2>> points;
    std::vector<std::array<std::uint8_t, 32>> descriptors;
};
Features detect(const TileStore& image, std::size_t max_points = 1500);
}  // namespace detail

}  // namespace croptop::panorama
