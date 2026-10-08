#pragma once

// GPU develop colour: develop::applyColor on a Vulkan compute device. Same `ColorSettings`, same
// `ColorPlan` (so the maths cannot diverge from the CPU reference in develop_color.cpp), same result format. Tiles are packed
// into batches and uploaded, so sparse layers cost only their stored tiles.

#include <optional>
#include <string>

#include "develop_color.h"
#include "gpu_compute.h"

namespace croptop::develop {

/** nullopt with `error` on invalid settings or a GPU failure; callers fall back to `applyColor` (the CPU path) then. */
std::optional<TileStore> applyColorGpu(gpu::Compute& gpu, const TileStore& linear, const ColorSettings& settings, std::string& error);

}  // namespace croptop::develop
