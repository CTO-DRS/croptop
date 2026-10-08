#include "histogram.h"

#include <algorithm>
#include <cmath>
#include <cstring>
#include <exception>
#include <mutex>
#include <vector>

#include "parallel_util.h"
#include "pixel_plane.h"

namespace croptop::color {
namespace {

std::size_t bin(float v) { return static_cast<std::size_t>(std::clamp(v, 0.0F, 1.0F) * 255.0F + 0.5F); }

}  // namespace

std::array<double, 256> Histogram::channel(HistogramChannel which) const {
    if (which != HistogramChannel::Rgb) return bins[static_cast<std::size_t>(which)];
    std::array<double, 256> sum{};
    for (std::size_t i = 0; i < 256; ++i) sum[i] = bins[0][i] + bins[1][i] + bins[2][i];
    return sum;
}

std::optional<Histogram> computeHistogram(const TileStore& layer, const TileStore* mask, std::string& error, std::uint32_t mip_level,
                                          unsigned worker_threads) {
    if (!paint::detail::isRgba(layer.format())) {
        error = "histograms need an RGBA layer";
        return std::nullopt;
    }
    if (mask != nullptr && (mask->format() != PixelFormat::A16 || mask->width() != layer.width() || mask->height() != layer.height())) {
        error = "the mask must be an A16 mask of the layer's size";
        return std::nullopt;
    }
    if (mip_level > 16U) {
        error = "mip level must be 0–16";
        return std::nullopt;
    }
    const auto width = layer.mip_width(mip_level), height = layer.mip_height(mip_level);
    if (width == 0U || height == 0U) return Histogram{};
    const auto columns = (width + kTileEdge - 1U) / kTileEdge, rows = (height + kTileEdge - 1U) / kTileEdge;
    const auto read = [mip_level](const TileStore& store, TileCoordinate c) {
        return mip_level == 0U ? store.readTile(c) : store.readMipTile(mip_level, c);
    };
    Histogram result;
    std::mutex merge;
    const auto bpp = layer.bytes_per_pixel();
    try {
        parallel::parallelFor(static_cast<std::size_t>(columns) * rows, parallel::workerCount(worker_threads), [&](std::size_t item, std::size_t) {
            const TileCoordinate coordinate{static_cast<std::int32_t>(item % columns), static_cast<std::int32_t>(item / columns)};
            const auto tile = read(layer, coordinate);
            if (tile == nullptr) return;  // transparent: weight 0
            const auto mask_tile = mask != nullptr ? read(*mask, coordinate) : nullptr;
            if (mask != nullptr && mask_tile == nullptr) return;
            Histogram local;
            const auto x_end = std::min<std::uint32_t>(kTileEdge, width - static_cast<std::uint32_t>(coordinate.x) * kTileEdge);
            const auto y_end = std::min<std::uint32_t>(kTileEdge, height - static_cast<std::uint32_t>(coordinate.y) * kTileEdge);
            std::array<float, 4> px{};
            for (std::uint32_t y = 0; y < y_end; ++y) {
                for (std::uint32_t x = 0; x < x_end; ++x) {
                    const auto i = static_cast<std::size_t>(y) * kTileEdge + x;
                    paint::detail::readPixel(layer.format(), tile->data() + i * bpp, px.data());
                    double weight = std::clamp(px[3], 0.0F, 1.0F);
                    if (mask_tile != nullptr) {
                        std::uint16_t raw = 0;
                        std::memcpy(&raw, mask_tile->data() + i * 2U, 2U);
                        weight *= raw / 65535.0;
                    }
                    if (weight <= 0.0) continue;
                    for (std::size_t c = 0; c < 3; ++c) local.bins[c][bin(px[c])] += weight;
                    local.bins[3][bin(0.30F * px[0] + 0.59F * px[1] + 0.11F * px[2])] += weight;
                    local.total += weight;
                }
            }
            const std::lock_guard<std::mutex> lock(merge);
            for (std::size_t c = 0; c < 4; ++c)
                for (std::size_t b = 0; b < 256; ++b) result.bins[c][b] += local.bins[c][b];
            result.total += local.total;
        });
    } catch (const std::exception&) {
        error = "could not compute the histogram";
        return std::nullopt;
    }
    return result;
}

HistogramStats statistics(const Histogram& histogram, HistogramChannel channel) {
    const auto bins = histogram.channel(channel);
    HistogramStats stats;
    double sum = 0.0, square = 0.0;
    for (std::size_t i = 0; i < 256; ++i) {
        stats.pixels += bins[i];
        sum += bins[i] * static_cast<double>(i);
        square += bins[i] * static_cast<double>(i) * static_cast<double>(i);
    }
    if (stats.pixels <= 0.0) return stats;
    stats.mean = sum / stats.pixels;
    stats.stddev = std::sqrt(std::max(0.0, square / stats.pixels - stats.mean * stats.mean));
    stats.median = percentile(histogram, channel, 0.5);
    if (channel == HistogramChannel::Rgb) stats.pixels /= 3.0;
    return stats;
}

int percentile(const Histogram& histogram, HistogramChannel channel, double fraction) {
    const auto bins = histogram.channel(channel);
    double total = 0.0;
    for (const double v : bins) total += v;
    if (total <= 0.0 || !std::isfinite(fraction)) return 0;
    const double target = std::clamp(fraction, 0.0, 1.0) * total;
    double seen = 0.0;
    for (int i = 0; i < 256; ++i) {
        seen += bins[static_cast<std::size_t>(i)];
        if (seen >= target && seen > 0.0) return i;
    }
    return 255;
}

}  // namespace croptop::color
