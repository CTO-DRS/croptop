#pragma once

// Histogram and statistics, the CPU reference for the GPU histogram.
// 256 bins per channel for Red, Green, Blue and Luminosity (Photoshop weights 0.30/0.59/0.11 on the stored,
// sRGB-encoded values; RGBA32F is clamped to 0–1). Each pixel counts with weight alpha × mask coverage, so
// transparent pixels and unselected areas don't count. `mip_level` > 0 reads the store's cached 2^level
// reduction (a fast, approximate histogram for live panels; the mask is reduced the same way).

#include <array>
#include <cstdint>
#include <optional>
#include <string>

#include "tile_store.h"

namespace croptop::color {

enum class HistogramChannel : std::uint8_t { Red, Green, Blue, Luminosity, Rgb };

struct Histogram final {
    std::array<std::array<double, 256>, 4> bins{};  // Red, Green, Blue, Luminosity
    double total = 0.0;                            // summed weights (the pixel count when opaque)

    /** Bins for a channel; Rgb is the sum of the three colour channels (Photoshop's composite). */
    std::array<double, 256> channel(HistogramChannel channel) const;
};

struct HistogramStats final {
    double mean = 0.0;    // 0–255
    double stddev = 0.0;
    double median = 0.0;  // bin index
    double pixels = 0.0;
};

std::optional<Histogram> computeHistogram(const TileStore& layer, const TileStore* mask, std::string& error,
                                          std::uint32_t mip_level = 0, unsigned worker_threads = 0);

HistogramStats statistics(const Histogram& histogram, HistogramChannel channel);
/** Smallest bin whose cumulative share reaches `fraction` (0–1), e.g. 0.005 / 0.995 for Auto Levels clipping. */
int percentile(const Histogram& histogram, HistogramChannel channel, double fraction);

}  // namespace croptop::color
