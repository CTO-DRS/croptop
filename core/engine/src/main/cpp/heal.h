#pragma once

// Clone Stamp, Healing Brush, Spot Healing and Patch pixel math. Brush-dab
// integration, sampling-mode plumbing (current/below/all) and history belong to NativeDocument.
//
// `mask` / `region` is an A16 coverage mask of the destination's size (a brush dab footprint or a patch
// selection). The source position for destination pixel p is p + offset; fractional offsets are sampled
// bilinearly. All results are blended into `dst` by coverage; pixels with zero coverage are untouched.
//   cloneStamp:   copies the source.
//   healingBlend: gradient-domain healing — the source texture plus a membrane correction c = dst − src
//                 that is harmonic inside the mask and matches the destination around it, so texture
//                 comes from the source and tone from the surroundings (solved coarse to fine).
//   spotHeal:     removes the masked spot (grown 2 px so its anti-aliased rim isn't reused):
//                 ContentAware  — PatchMatch fill from the neighbourhood;
//                 ProximityMatch — heals from the nearby area (16 directions at 1–1.5× the spot size)
//                                  whose surroundings best match the spot's surroundings;
//                 CreateTexture — heals from a texture tiled out of random 8 px patches of the
//                                  surroundings (no structure copied, tone matched by the heal).
//   patch:        heals `region` from `region + offset` (Normal), or fills it by PatchMatch sampling only
//                 that source area (ContentAware).

#include <optional>
#include <string>

#include "tile_store.h"

namespace croptop::paint {

bool cloneStamp(TileStore& dst, const TileStore& src, double offset_x, double offset_y, const TileStore& mask,
                std::string& error);

bool healingBlend(TileStore& dst, const TileStore& src, double offset_x, double offset_y, const TileStore& mask,
                  std::string& error, unsigned worker_threads = 0);

enum class SpotHealType : std::uint8_t { ContentAware, ProximityMatch, CreateTexture };

bool spotHeal(TileStore& dst, const TileStore& mask, std::string& error, SpotHealType type = SpotHealType::ContentAware);

enum class PatchMode : std::uint8_t { Normal, ContentAware };

bool patch(TileStore& dst, const TileStore& region, double offset_x, double offset_y, PatchMode mode,
           std::string& error);

}  // namespace croptop::paint
