#include "layer_pixel_ops.h"

#include <algorithm>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <cstring>
#include <unordered_set>
#include <vector>

namespace croptop::layer_pixels {
namespace {

double readComponent(const std::uint8_t* pixel, PixelFormat format, std::size_t component) {
    switch (format) {
        case PixelFormat::RGBA8: return static_cast<double>(pixel[component]) / 255.0;
        case PixelFormat::RGBA16: {
            std::uint16_t value = 0;
            std::memcpy(&value, pixel + component * sizeof(value), sizeof(value));
            return static_cast<double>(value) / 65535.0;
        }
        case PixelFormat::RGBA32F: {
            float value = 0.0F;
            std::memcpy(&value, pixel + component * sizeof(value), sizeof(value));
            return std::isfinite(value) ? std::clamp(static_cast<double>(value), 0.0, 1.0) : 0.0;
        }
        case PixelFormat::A8:
        case PixelFormat::A16: return 0.0;
    }
    return 0.0;
}

void writeAlpha(std::uint8_t* pixel, PixelFormat format, double alpha) {
    alpha = std::clamp(alpha, 0.0, 1.0);
    switch (format) {
        case PixelFormat::RGBA8: pixel[3] = static_cast<std::uint8_t>(std::lround(alpha * 255.0)); break;
        case PixelFormat::RGBA16: {
            const auto value = static_cast<std::uint16_t>(std::lround(alpha * 65535.0));
            std::memcpy(pixel + 3U * sizeof(value), &value, sizeof(value));
            break;
        }
        case PixelFormat::RGBA32F: {
            const auto value = static_cast<float>(alpha);
            std::memcpy(pixel + 3U * sizeof(value), &value, sizeof(value));
            break;
        }
        case PixelFormat::A8:
        case PixelFormat::A16: break;
    }
}

double maskCoverage(const TileBuffer* tile, std::size_t pixel_index) {
    if (tile == nullptr) return 0.0;
    std::uint16_t raw = 0;
    std::memcpy(&raw, tile->data() + pixel_index * sizeof(raw), sizeof(raw));
    return static_cast<double>(raw) / 65535.0;
}

}  // namespace

bool split(const TileStore& source, TileStore& cut, TileStore& copy, const TileStore* mask, std::string& error) {
    if (source.format() != PixelFormat::RGBA8 && source.format() != PixelFormat::RGBA16 &&
        source.format() != PixelFormat::RGBA32F) {
        error = "Layer via Cut requires an RGBA pixel store";
        return false;
    }
    if (cut.format() != source.format() || copy.format() != source.format() || cut.width() != source.width() ||
        copy.width() != source.width() || cut.height() != source.height() || copy.height() != source.height()) {
        error = "Layer via Cut output stores must match the source";
        return false;
    }
    if (mask != nullptr && (mask->format() != PixelFormat::A16 || mask->width() != source.width() ||
                            mask->height() != source.height())) {
        error = "Layer via Cut selection mask must be matching A16";
        return false;
    }

    const std::size_t bpp = source.bytes_per_pixel();
    const auto source_coordinates = source.tileCoordinates();
    const auto mask_coordinates = mask == nullptr ? std::vector<TileCoordinate>{} : mask->tileCoordinates();
    std::unordered_set<TileCoordinate, TileCoordinateHash> mask_coordinate_set(mask_coordinates.begin(), mask_coordinates.end());
    for (const auto coordinate : source_coordinates) {
        const auto source_tile = source.readTile(coordinate);
        if (source_tile == nullptr) {
            error = "Layer via Cut could not read source pixels";
            return false;
        }
        const auto mask_tile = mask == nullptr ? nullptr : mask->readTile(coordinate);
        if (mask != nullptr && mask_tile == nullptr && mask_coordinate_set.contains(coordinate)) {
            error = "Layer via Cut could not read selection pixels";
            return false;
        }
        if (mask != nullptr && mask_tile == nullptr) continue;
        TileBuffer cut_tile = *source_tile;
        TileBuffer copy_tile(source.tile_byte_size(), 0U);
        const std::uint32_t origin_x = static_cast<std::uint32_t>(coordinate.x) * kTileEdge;
        const std::uint32_t origin_y = static_cast<std::uint32_t>(coordinate.y) * kTileEdge;
        const std::uint32_t valid_width = std::min(kTileEdge, source.width() - origin_x);
        const std::uint32_t valid_height = std::min(kTileEdge, source.height() - origin_y);
        bool changed = false;
        for (std::uint32_t y = 0; y < valid_height; ++y) {
            for (std::uint32_t x = 0; x < valid_width; ++x) {
                const std::size_t index = static_cast<std::size_t>(y) * kTileEdge + x;
                const double coverage = mask == nullptr ? 1.0 : maskCoverage(mask_tile.get(), index);
                if (!(coverage > 0.0)) continue;
                const std::size_t offset = index * bpp;
                std::memcpy(copy_tile.data() + offset, source_tile->data() + offset, bpp);
                const double alpha = readComponent(source_tile->data() + offset, source.format(), 3U);
                const double copy_alpha = alpha * coverage;
                const double cut_alpha = alpha * (1.0 - coverage);
                writeAlpha(copy_tile.data() + offset, source.format(), copy_alpha);
                if (coverage >= 1.0) {
                    std::memset(cut_tile.data() + offset, 0, bpp);
                } else {
                    writeAlpha(cut_tile.data() + offset, source.format(), cut_alpha);
                }
                changed = true;
            }
        }
        if (changed && (!cut.writeTile(coordinate, cut_tile) || !copy.writeTile(coordinate, copy_tile))) {
            error = "Layer via Cut could not allocate output pixels";
            return false;
        }
    }
    return true;
}

}  // namespace croptop::layer_pixels
