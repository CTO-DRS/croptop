#pragma once

// Vulkan compute implementation of the CPU histogram reference.

#include <optional>
#include <string>

#include "gpu_compute.h"
#include "histogram.h"

namespace croptop::color {

/** Returns nullopt on invalid input or GPU failure; callers may fall back to computeHistogram. */
std::optional<Histogram> computeHistogramGpu(gpu::Compute& gpu, const TileStore& layer, const TileStore* mask, std::string& error,
                                             std::uint32_t mip_level = 0);

}  // namespace croptop::color
