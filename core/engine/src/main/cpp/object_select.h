#pragma once

// Model-free Object Selection / Select Subject (fallback until AI segmentation models ship).
// A GrabCut-style colour model: inside the region, the centre seeds the foreground and the region's border
// ring plus the context around it seed the background (4096-bin joint RGB histograms). Pixels are classified
// by likelihood ratio, the models are re-estimated from the result a few times, the probability map is
// smoothed, and the connected component under the seed is kept (holes filled). Works on a ≤ 512 px proxy of
// the region and upsamples the probability for an anti-aliased A16 mask of the image's size.

#include <optional>
#include <string>

#include "tile_store.h"

namespace croptop::selection {

struct ObjectRegion final {
    double left = 0, top = 0, right = 0, bottom = 0;  // document pixels; the object lies inside
};

/** Object Selection: the most prominent object inside `region` of an RGBA image. */
std::optional<TileStore> selectObject(const TileStore& image, ObjectRegion region, std::string& error);
/** Allocator-aware form; the result shares `allocation_like`'s tile pool and memory-budget manager. */
std::optional<TileStore> selectObject(const TileStore& allocation_like, const TileStore& image,
                                      ObjectRegion region, std::string& error);

/** Select Subject: the main subject, assumed to sit roughly centred and away from the borders. */
std::optional<TileStore> selectSubject(const TileStore& image, std::string& error);
/** Allocator-aware form; the result shares `allocation_like`'s tile pool and memory-budget manager. */
std::optional<TileStore> selectSubject(const TileStore& allocation_like, const TileStore& image, std::string& error);

}  // namespace croptop::selection
