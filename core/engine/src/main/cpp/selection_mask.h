#pragma once

// Selection core and geometric rasterizers.
//
// A selection is an A16 TileStore of document size: 0 = not selected, 65535 = fully selected.
// Missing tiles are unselected, so small selections stay sparse. Every operation returns a new
// store (copy-on-write: untouched tiles are shared), which keeps history snapshots cheap.
//
// Semantics (documented choices, matching common editors):
//   ADD = max(a, b), SUBTRACT = min(a, 1 - b), INTERSECT = min(a, b), REPLACE = b.
//   Anti-aliased shapes store exact area coverage (rectangles) or 16-sub-scanline coverage with exact
//   horizontal spans (ellipses, polygons). Polygons use the even-odd rule; non-AA shapes select the
//   pixels whose centers are inside.

#include <cstdint>
#include <optional>
#include <span>
#include <string>
#include <vector>

#include "tile_store.h"

namespace croptop::selection {

inline constexpr std::uint16_t kFullySelected = 65535;

enum class Op : std::uint8_t { Replace, Add, Subtract, Intersect };

struct Point final {
    double x = 0.0;
    double y = 0.0;
};

/** Document-space rectangle in pixels (edges may be fractional). */
struct RectF final {
    double left = 0.0;
    double top = 0.0;
    double right = 0.0;
    double bottom = 0.0;
};

/** An empty (nothing selected) mask for a width × height document. */
TileStore emptyMask(std::uint32_t width, std::uint32_t height);
/** Empty A16 mask sharing `allocation_like`'s tile pool and memory-budget manager. */
TileStore emptyMask(const TileStore& allocation_like);
/** Everything selected. */
TileStore fullMask(std::uint32_t width, std::uint32_t height);
TileStore fullMask(const TileStore& allocation_like);

TileStore rasterizeRect(std::uint32_t width, std::uint32_t height, RectF rect, bool antialias);
/** Allocator-aware overloads for masks owned by a document. */
TileStore rasterizeRect(const TileStore& allocation_like, RectF rect, bool antialias);
TileStore rasterizeEllipse(std::uint32_t width, std::uint32_t height, RectF bounds, bool antialias);
TileStore rasterizeEllipse(const TileStore& allocation_like, RectF bounds, bool antialias);
/** Closed polygon or lasso; needs at least 3 finite points (otherwise the result is empty). */
TileStore rasterizePolygon(std::uint32_t width, std::uint32_t height, std::span<const Point> points, bool antialias);
TileStore rasterizePolygon(const TileStore& allocation_like, std::span<const Point> points, bool antialias);

/** Combines `shape` into `selection` (same size, A16); reports incompatible inputs or tile-write failures. */
std::optional<TileStore> combine(const TileStore& selection, const TileStore& shape, Op op,
                                 std::string* error = nullptr);
/** Like [combine], but allocate the returned A16 store from `allocation_like`'s pool and budget. */
std::optional<TileStore> combine(const TileStore& allocation_like, const TileStore& selection, const TileStore& shape,
                                 Op op, std::string* error = nullptr);
/** Returns the inverse A16 mask, or nullopt with a reason for invalid input or tile-write failure. */
std::optional<TileStore> invert(const TileStore& selection, std::string* error = nullptr);
/** Like [invert], but allocate the returned A16 store from `allocation_like`'s pool and budget. */
std::optional<TileStore> invert(const TileStore& allocation_like, const TileStore& selection, std::string* error = nullptr);

/** Tight half-open bounds of all pixels with nonzero coverage, or nullopt when nothing is selected. */
std::optional<PixelRect> bounds(const TileStore& selection);
bool isEmpty(const TileStore& selection);

/** Pixel-edge boundary segment of the thresholded selection, in document pixels (axis-aligned). */
struct Segment final {
    std::int32_t x0 = 0;
    std::int32_t y0 = 0;
    std::int32_t x1 = 0;
    std::int32_t y1 = 0;
};

struct ContourResult final {
    std::vector<Segment> segments;
    /** True when `max_segments` was reached and the list is incomplete. */
    bool truncated = false;
};

/**
 * Marching-ants outline: maximal horizontal and vertical runs of pixel edges where coverage crosses
 * `threshold` (default 50%). `mip_level` > 0 traces the 2^level downsampled mask (use it when zoomed
 * out); coordinates are always document pixels. Only edges inside `region` (document pixels) are
 * returned, so the viewport can ask for what is visible.
 */
ContourResult contour(const TileStore& selection, PixelRect region, std::uint32_t mip_level = 0,
                      std::uint16_t threshold = 32768, std::size_t max_segments = 200'000);

/** Coverage of one pixel (0 outside the document); for tests and sampling. */
std::uint16_t coverageAt(const TileStore& selection, std::int64_t x, std::int64_t y);

}  // namespace croptop::selection
