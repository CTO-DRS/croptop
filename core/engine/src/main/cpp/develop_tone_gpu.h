#pragma once

// GPU develop tone: the per-pixel pass of develop::applyTone on a Vulkan compute device. The
// low-resolution stage (dehaze, guided-filter planes, curve tables) runs on the CPU through `makeTonePlan` — it is at most 1024 px on
// the long side — and the full-resolution pass runs in the shader. Texture needs a neighbourhood kernel and is not on the GPU yet:
// such settings are rejected here (`error` says so) and the caller uses `applyTone`.

#include <optional>
#include <string>

#include "develop_tone.h"
#include "gpu_compute.h"

namespace croptop::develop {

std::optional<TileStore> applyToneGpu(gpu::Compute& gpu, const TileStore& linear, const ToneSettings& settings, std::string& error);

}  // namespace croptop::develop
