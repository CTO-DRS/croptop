// . GPU accumulation uses fixed-point alpha/selection weights so portable 32-bit atomics are sufficient.
#include "histogram_gpu.h"

#include <algorithm>
#include <array>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <set>
#include <vector>

#include "histogram_comp_spv.h"
#include "pixel_plane.h"

namespace croptop::color {
namespace {

constexpr std::size_t kTilePixels = static_cast<std::size_t>(kTileEdge) * kTileEdge;
constexpr std::size_t kBinsPerTile = 4U * 256U;
constexpr std::size_t kBatchTiles = 16;
constexpr double kWeightScale = 65535.0;

struct Push final {
    std::uint32_t tile_count;
    std::uint32_t width;
    std::uint32_t height;
};

std::vector<TileCoordinate> coordinatesAtMip(const TileStore& layer, std::uint32_t mip_level) {
    const auto divisor = 1U << mip_level;
    std::set<std::pair<std::int32_t, std::int32_t>> unique;
    for (const auto coordinate : layer.tileCoordinates()) {
        unique.emplace(coordinate.x / static_cast<std::int32_t>(divisor), coordinate.y / static_cast<std::int32_t>(divisor));
    }
    std::vector<TileCoordinate> result;
    result.reserve(unique.size());
    for (const auto& [x, y] : unique) result.push_back({x, y});
    return result;
}

}  // namespace

std::optional<Histogram> computeHistogramGpu(gpu::Compute& gpu, const TileStore& layer, const TileStore* mask, std::string& error,
                                             std::uint32_t mip_level) {
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

    Histogram result;
    const auto width = layer.mip_width(mip_level);
    const auto height = layer.mip_height(mip_level);
    if (width == 0U || height == 0U) return result;
    const auto coordinates = coordinatesAtMip(layer, mip_level);
    for (std::size_t first = 0; first < coordinates.size(); first += kBatchTiles) {
        const auto count = std::min(kBatchTiles, coordinates.size() - first);
        std::vector<float> pixels(count * kTilePixels * 4U, 0.0F);
        std::vector<std::int32_t> positions(count * 2U, 0);
        std::vector<std::shared_ptr<const TileBuffer>> source_tiles(count);
        std::vector<std::shared_ptr<const TileBuffer>> mask_tiles(count);
        for (std::size_t tile_index = 0; tile_index < count; ++tile_index) {
            const auto coordinate = coordinates[first + tile_index];
            positions[tile_index * 2U] = coordinate.x;
            positions[tile_index * 2U + 1U] = coordinate.y;
            source_tiles[tile_index] = mip_level == 0U ? layer.readTile(coordinate) : layer.readMipTile(mip_level, coordinate);
            if (source_tiles[tile_index] == nullptr) continue;
            if (mask != nullptr) {
                mask_tiles[tile_index] = mip_level == 0U ? mask->readTile(coordinate) : mask->readMipTile(mip_level, coordinate);
                if (mask_tiles[tile_index] == nullptr) continue;
            }
            for (std::size_t pixel = 0; pixel < kTilePixels; ++pixel) {
                std::array<float, 4> value{};
                paint::detail::readPixel(layer.format(), source_tiles[tile_index]->data() + pixel * layer.bytes_per_pixel(), value.data());
                float weight = std::clamp(value[3], 0.0F, 1.0F);
                if (mask_tiles[tile_index] != nullptr) {
                    std::uint16_t coverage = 0;
                    std::memcpy(&coverage, mask_tiles[tile_index]->data() + pixel * sizeof(coverage), sizeof(coverage));
                    weight *= static_cast<float>(coverage) / 65535.0F;
                }
                value[3] = weight;
                std::copy(value.begin(), value.end(), pixels.begin() + static_cast<std::ptrdiff_t>((tile_index * kTilePixels + pixel) * 4U));
            }
        }

        std::vector<std::uint32_t> bins(count * kBinsPerTile, 0U);
        const Push push{static_cast<std::uint32_t>(count), width, height};
        const std::array<gpu::Binding, 3> bindings{
            gpu::Binding{pixels.data(), nullptr, pixels.size() * sizeof(float)},
            gpu::Binding{bins.data(), bins.data(), bins.size() * sizeof(std::uint32_t)},
            gpu::Binding{positions.data(), nullptr, positions.size() * sizeof(std::int32_t)},
        };
        const auto invocations = static_cast<std::uint32_t>(count * kTilePixels);
        const auto groups = (invocations + 255U) / 256U;
        const auto gx = std::min(groups, 65535U);
        const auto gy = (groups + gx - 1U) / gx;
        const auto* bytes = reinterpret_cast<const std::uint8_t*>(&push);
        if (!gpu.dispatch(kHistogramShaderSpirv, bindings, std::span<const std::uint8_t>(bytes, sizeof(push)), gx, gy, 1U, error)) {
            return std::nullopt;
        }
        for (std::size_t tile_index = 0; tile_index < count; ++tile_index) {
            if (source_tiles[tile_index] == nullptr || (mask != nullptr && mask_tiles[tile_index] == nullptr)) continue;
            const auto base = tile_index * kBinsPerTile;
            for (std::size_t channel = 0; channel < result.bins.size(); ++channel) {
                for (std::size_t bin = 0; bin < 256U; ++bin) {
                    result.bins[channel][bin] += static_cast<double>(bins[base + channel * 256U + bin]) / kWeightScale;
                }
            }
        }
    }
    for (const auto value : result.bins[0]) result.total += value;
    return result;
}

}  // namespace croptop::color
