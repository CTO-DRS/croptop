#pragma once

#include <cstdint>
#include <span>
#include <string>

#include "tile_store.h"

namespace croptop::shape::bridge {

// Renders a CTSB v1 batch directly into a caller-owned tiled RGBA8 cache. This avoids allocating a
// canvas-sized JNI byte array and lets the engine's shared memory budget spill/reclaim tiles.
bool renderBatchToTileStore(std::span<const std::uint8_t> encoded_batch, TileStore& target, std::string& error);

}  // namespace croptop::shape::bridge
