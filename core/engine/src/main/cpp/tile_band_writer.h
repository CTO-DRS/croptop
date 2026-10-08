#pragma once

#include "tile_store.h"

#include <algorithm>
#include <array>
#include <cstddef>
#include <cstdint>
#include <cstring>
#include <span>
#include <unordered_map>
#include <utility>
#include <vector>

namespace croptop::detail {

class TileBandWriter final {
public:
    TileBandWriter(TileStore& destination, std::uint8_t orientation = 1, bool reverse_rows = false)
        : destination_(destination), bytes_per_pixel_(destination.bytes_per_pixel()), orientation_(orientation),
          source_width_(orientation >= 5 ? destination.height() : destination.width()),
          source_height_(orientation >= 5 ? destination.width() : destination.height()),
          reverse_rows_(reverse_rows) {}

    bool write(std::uint32_t y, std::span<const std::uint8_t> row) {
        const auto expected_y = rows_written_ == 0 ? (reverse_rows_ ? source_height_ - 1U : 0U)
                                                   : (reverse_rows_ ? last_y_ - 1U : last_y_ + 1U);
        if (y != expected_y || y >= source_height_ || rows_written_ >= source_height_ ||
            row.size() != static_cast<std::size_t>(source_width_) * bytes_per_pixel_) {
            return false;
        }
        const auto row_in_tile = y % kTileEdge;
        const auto current_tile_y = y / kTileEdge;
        if (tiles_.empty()) {
            tile_y_ = y / kTileEdge;
            const auto columns = 1U + (source_width_ - 1U) / kTileEdge;
            tiles_.assign(columns, TileBuffer(destination_.tile_byte_size(), 0));
        } else if (current_tile_y != tile_y_) {
            return false;
        }

        const auto tile_row_offset = static_cast<std::size_t>(row_in_tile) * kTileEdge * bytes_per_pixel_;
        const auto columns = static_cast<std::uint32_t>(tiles_.size());
        for (std::uint32_t tile_x = 0; tile_x < columns; ++tile_x) {
            const auto source_x = tile_x * kTileEdge;
            const auto pixel_count = std::min<std::uint32_t>(kTileEdge, source_width_ - source_x);
            std::memcpy(tiles_[tile_x].data() + tile_row_offset,
                        row.data() + static_cast<std::size_t>(source_x) * bytes_per_pixel_,
                        static_cast<std::size_t>(pixel_count) * bytes_per_pixel_);
        }

        ++rows_written_;
        last_y_ = y;
        const bool band_complete = reverse_rows_ ? (row_in_tile == 0) : (row_in_tile + 1U == kTileEdge);
        if (band_complete || rows_written_ == source_height_) {
            const bool committed = orientation_ == 1 ? commitUnchangedBand() : commitOrientedBand();
            if (!committed) return false;
            tiles_.clear();
        }
        return true;
    }

    bool complete() const { return rows_written_ == source_height_; }

private:
    std::pair<std::uint32_t, std::uint32_t> orient(std::uint32_t x, std::uint32_t y) const {
        switch (orientation_) {
            case 1: return {x, y};
            case 2: return {source_width_ - 1U - x, y};
            case 3: return {source_width_ - 1U - x, source_height_ - 1U - y};
            case 4: return {x, source_height_ - 1U - y};
            case 5: return {y, x};
            case 6: return {source_height_ - 1U - y, x};
            case 7: return {source_height_ - 1U - y, source_width_ - 1U - x};
            case 8: return {y, source_width_ - 1U - x};
            default: return {x, y};
        }
    }

    bool commitUnchangedBand() {
        const auto columns = static_cast<std::uint32_t>(tiles_.size());
        for (std::uint32_t tile_x = 0; tile_x < columns; ++tile_x) {
            if (!destination_.writeTile({static_cast<std::int32_t>(tile_x), static_cast<std::int32_t>(tile_y_)},
                                        tiles_[tile_x])) {
                return false;
            }
        }
        return true;
    }

    bool commitOrientedBand() {
        std::unordered_map<TileCoordinate, TileBuffer, TileCoordinateHash> output_tiles;
        for (std::uint32_t source_tile_x = 0; source_tile_x < tiles_.size(); ++source_tile_x) {
            const auto source_x = source_tile_x * kTileEdge;
            const auto tile_width = std::min<std::uint32_t>(kTileEdge, source_width_ - source_x);
            const auto source_y = tile_y_ * kTileEdge;
            const auto tile_height = std::min<std::uint32_t>(kTileEdge, source_height_ - source_y);
            const auto& source_tile = tiles_[source_tile_x];
            for (std::uint32_t local_y = 0; local_y < tile_height; ++local_y) {
                for (std::uint32_t local_x = 0; local_x < tile_width; ++local_x) {
                    const auto [destination_x, destination_y] = orient(source_x + local_x, source_y + local_y);
                    const TileCoordinate coordinate{static_cast<std::int32_t>(destination_x / kTileEdge),
                                                    static_cast<std::int32_t>(destination_y / kTileEdge)};
                    auto [entry, inserted] = output_tiles.try_emplace(coordinate);
                    if (inserted) entry->second.assign(destination_.tile_byte_size(), 0);
                    const auto source_offset = (static_cast<std::size_t>(local_y) * kTileEdge + local_x) *
                                               bytes_per_pixel_;
                    const auto destination_offset = (static_cast<std::size_t>(destination_y % kTileEdge) * kTileEdge +
                                                     destination_x % kTileEdge) * bytes_per_pixel_;
                    std::memcpy(entry->second.data() + destination_offset, source_tile.data() + source_offset,
                                bytes_per_pixel_);
                }
            }
        }
        for (const auto& [coordinate, tile] : output_tiles) {
            if (!destination_.writeTile(coordinate, tile)) return false;
        }
        return true;
    }

    TileStore& destination_;
    std::size_t bytes_per_pixel_;
    std::uint8_t orientation_;
    std::uint32_t source_width_;
    std::uint32_t source_height_;
    std::uint32_t rows_written_ = 0;
    std::uint32_t last_y_ = 0;
    std::uint32_t tile_y_ = 0;
    bool reverse_rows_;
    std::vector<TileBuffer> tiles_;
};

}  // namespace croptop::detail
