#pragma once

// Magnetic Lasso (intelligent scissors / live-wire, Mortensen & Barrett): the selection edge
// follows the cheapest path between fastening points over an edge cost map (gradient magnitude +
// Laplacian zero-crossings, diagonal steps weighted by √2). Photoshop's options map as:
//   width      — the cursor snaps to the strongest edge within this radius (px);
//   contrast   — gradients weaker than this fraction of the strongest are treated as no edge;
//   frequency  — automatic fastening points along the live wire (0 = never, 100 = every 16 px).
// Shortest paths are searched in a window around the last fastening point and the cursor (≤ 1024 px per
// side), so each update stays interactive on large images.

#include <cstdint>
#include <memory>
#include <optional>
#include <string>
#include <vector>

#include "selection_mask.h"
#include "tile_store.h"

namespace croptop::selection {

struct MagneticSpec final {
    float width = 10;      // 1–256 px
    float contrast = 0.1F;  // 0.01–1
    float frequency = 57;  // 0–100
};

class MagneticLasso final {
public:
    /** Starts at `seed` (snapped to an edge within `width`). `image` must be RGBA; it is copied (COW). */
    static std::unique_ptr<MagneticLasso> begin(const TileStore& image, Point seed, const MagneticSpec& spec, std::string& error);

    /**
     * Moves the cursor: returns the live wire from the last fastening point to the snapped cursor (pixel
     * centres). May commit automatic fastening points (see `frequency`).
     */
    std::vector<Point> update(Point cursor);
    /** Commits the current live wire up to the (snapped) point as a fastening point (a click). */
    void fasten(Point point);
    /** Removes the last fastening point (Backspace); false when only the seed remains. */
    bool undoFasten();
    /** Committed outline so far (seed → last fastening point). */
    const std::vector<Point>& committed() const { return committed_; }
    std::size_t fastenerCount() const { return fasteners_.size(); }
    /** Closes back to the seed along the live wire and rasterises the selection. */
    std::optional<TileStore> close(bool antialias, std::string& error);

private:
    MagneticLasso(const TileStore& image, const MagneticSpec& spec) : image_(image), spec_(spec) {}
    TileStore image_;
    MagneticSpec spec_;
    std::vector<Point> committed_;
    std::vector<std::size_t> fasteners_;  // indices into committed_
    // Edge data for the last search window (reused while the cursor stays inside it).
    std::shared_ptr<const void> window_cache_;
    Point snap(Point p) const;
    std::vector<Point> liveWire(Point from, Point to);
};

}  // namespace croptop::selection
