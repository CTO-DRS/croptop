#pragma once

#include "pixel_plane.h"

#include <cstring>
#include <optional>
#include <string>

namespace croptop {

/** Promote archive tile data to the document's common RGBA format without reducing source precision. */
inline std::optional<TileStore> promotePixelStore(const TileStore& source, PixelFormat output_format, std::string& error,
                                                  std::uint32_t output_width = 0, std::uint32_t output_height = 0) {
    const auto precision = [](PixelFormat format) -> int {
        switch (format) {
            case PixelFormat::RGBA8:
            case PixelFormat::A8: return 0;
            case PixelFormat::RGBA16:
            case PixelFormat::A16: return 1;
            case PixelFormat::RGBA32F: return 2;
        }
        return -1;
    };
    if (output_format != PixelFormat::RGBA8 && output_format != PixelFormat::RGBA16 &&
        output_format != PixelFormat::RGBA32F) {
        error = "archive promotion target must be an RGBA format";
        return std::nullopt;
    }
    if (precision(output_format) < precision(source.format())) {
        error = "archive layer promotion would reduce pixel precision";
        return std::nullopt;
    }
    if (output_width == 0) output_width = source.width();
    if (output_height == 0) output_height = source.height();
    if (output_width < source.width() || output_height < source.height()) {
        error = "archive layer promotion cannot crop source pixels";
        return std::nullopt;
    }

    auto output = source.emptyLike(output_format, output_width, output_height);
    for (const auto coordinate : source.tileCoordinates()) {
        const auto input = source.readTile(coordinate);
        if (input == nullptr) {
            error = "archive layer tile disappeared during format promotion";
            return std::nullopt;
        }
        TileBuffer converted(output.tile_byte_size(), 0U);
        const auto source_bpp = source.bytes_per_pixel();
        const auto output_bpp = output.bytes_per_pixel();
        for (std::size_t pixel = 0; pixel < static_cast<std::size_t>(kTileEdge) * kTileEdge; ++pixel) {
            const auto* from = input->data() + pixel * source_bpp;
            auto* to = converted.data() + pixel * output_bpp;
            float rgba[4]{};
            if (source.format() == PixelFormat::A8) {
                rgba[0] = rgba[1] = rgba[2] = 1.0F;
                rgba[3] = static_cast<float>(*from) / 255.0F;
            } else if (source.format() == PixelFormat::A16) {
                std::uint16_t alpha = 0;
                std::memcpy(&alpha, from, sizeof(alpha));
                rgba[0] = rgba[1] = rgba[2] = 1.0F;
                rgba[3] = static_cast<float>(alpha) / 65535.0F;
            } else {
                paint::detail::readPixel(source.format(), from, rgba);
            }
            paint::detail::writePixel(output_format, to, rgba);
        }
        if (!output.writeTile(coordinate, converted)) {
            error = "could not write promoted archive layer tile";
            return std::nullopt;
        }
    }
    return output;
}

}  // namespace croptop
