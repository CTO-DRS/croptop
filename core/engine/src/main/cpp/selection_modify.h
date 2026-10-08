#pragma once

// Select ▸ Modify on A16 selection masks: feather, expand, contract, border,
// smooth. Each works on the selection's bounds plus the operation's reach (tile-aligned, clipped to
// the canvas) and returns a new copy-on-write store; nullopt with `error` set on invalid input or
// when the working set would exceed the limit.
//
// Semantics (radii in document pixels):
//   feather(r):  Gaussian blur with sigma = r / 2 (three box passes); outside the canvas counts as
//                unselected.
//   expand(r):   coverage = clamp(r + 1 - d_in), d_in = Euclidean distance from the pixel center to the
//                nearest selected (>= 50%) pixel center; r = 0 is the identity.
//   contract(r): coverage = clamp(d_out - r), d_out = distance to the nearest unselected pixel inside the
//                canvas. Canvas edges do not erode (select-all then contract stays whole).
//   border(w):   min(expand(w / 2), 1 - contract(w / 2)): a band of about width w across the edge.
//   smooth(r):   majority vote in the (2r+1)^2 window over in-canvas pixels (ties keep the pixel).

#include <cstdint>
#include <optional>
#include <string>

#include "tile_store.h"

namespace croptop::selection {

struct ModifyLimits final {
    /** Peak working memory for the processed region (planes, distance fields, row buffers). */
    std::uint64_t max_working_bytes = 512ULL * 1024ULL * 1024ULL;
};

std::optional<TileStore> feather(const TileStore& selection, float radius, std::string& error,
                                 const ModifyLimits& limits = {});
std::optional<TileStore> expand(const TileStore& selection, float radius, std::string& error,
                                const ModifyLimits& limits = {});
std::optional<TileStore> contract(const TileStore& selection, float radius, std::string& error,
                                  const ModifyLimits& limits = {});
std::optional<TileStore> border(const TileStore& selection, float width, std::string& error,
                                const ModifyLimits& limits = {});
std::optional<TileStore> smooth(const TileStore& selection, float radius, std::string& error,
                                const ModifyLimits& limits = {});

/** Allocator-aware variants. `allocation_like` is typically the document pixel store; all returned masks
 * share its TilePool and MemoryBudgetManager, including identity and empty-selection results. */
std::optional<TileStore> feather(const TileStore& allocation_like, const TileStore& selection, float radius,
                                 std::string& error, const ModifyLimits& limits = {});
std::optional<TileStore> expand(const TileStore& allocation_like, const TileStore& selection, float radius,
                                std::string& error, const ModifyLimits& limits = {});
std::optional<TileStore> contract(const TileStore& allocation_like, const TileStore& selection, float radius,
                                  std::string& error, const ModifyLimits& limits = {});
std::optional<TileStore> border(const TileStore& allocation_like, const TileStore& selection, float width,
                                std::string& error, const ModifyLimits& limits = {});
std::optional<TileStore> smooth(const TileStore& allocation_like, const TileStore& selection, float radius,
                                std::string& error, const ModifyLimits& limits = {});

}  // namespace croptop::selection
