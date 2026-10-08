#pragma once

// Quick Selection brush, incremental and deterministic.
//
// Each dab samples the pixels under the brush into a colour model (up to 64 CIELAB prototypes, one
// per dab mean) with a tolerance derived from the brushed colours' spread: T = clamp(1.5 × RMS spread
// + 6, 6, 30) ΔE. The dab then grows 4-connected from the brush disc into pixels within T of the
// nearest prototype; pixels on strong edges (Sobel magnitude of L* ≥ 12 per pixel) are included when
// similar but growth does not continue through them. Growth stays inside a
// region of interest of four brush radii around the dab (capped at 2048² pixels). Brush pixels are
// always included. The stroke region is added to (or subtracted from) the selection that existed
// before the stroke; `finish()` optionally softens the stroke edge (enhance edge).

#include <memory>
#include <optional>
#include <span>
#include <string>
#include <vector>

#include "color_lab.h"
#include "tile_store.h"

namespace croptop::selection {

struct QuickSelectSpec final {
    /** Brush diameter in document pixels, 1–5000. */
    float diameter = 20.0F;
    bool subtract = false;
    bool enhance_edge = false;
};

class QuickSelector final {
public:
    /** `source` is RGBA8/16/32F (layer or merged); `before` is the A16 selection at stroke start. */
    static std::unique_ptr<QuickSelector> begin(const TileStore& source, const TileStore& before, QuickSelectSpec spec,
                                                std::string& error);

    /** Adds dabs at packed x,y pairs (document pixels) and updates current(). */
    bool add(std::span<const float> points, std::string& error);
    /** Preview: the selection before the stroke combined with the stroke so far. */
    const TileStore& current() const { return current_; }
    /** Final selection for the commit (applies enhance-edge). */
    std::optional<TileStore> finish(std::string& error) const;

private:
    QuickSelector(const TileStore& source, const TileStore& before, QuickSelectSpec spec);
    void dab(double cx, double cy);

    TileStore source_;  // COW copy so the stroke sees stable pixels.
    TileStore before_;
    TileStore stroke_;
    TileStore current_;
    QuickSelectSpec spec_;
    bool allocation_failed_ = false;
    std::vector<color::Lab> prototypes_;
    double tolerance_ = 6.0;
    double spread_sum_ = 0.0;
    std::size_t spread_count_ = 0;
};

}  // namespace croptop::selection
