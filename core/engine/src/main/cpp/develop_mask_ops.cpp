// Develop local mask color-range and algebra helpers.
#include "develop_mask_ops.h"

#include <algorithm>
#include <cmath>
#include <cstring>

#include "pixel_plane.h"

namespace croptop::develop::mask_ops {
namespace {

double smoothstep(double t) {
    t = std::clamp(t, 0.0, 1.0);
    return t * t * (3.0 - 2.0 * t);
}

bool validRgb(const std::array<float, 3>& rgb) {
    return std::all_of(rgb.begin(), rgb.end(), [](float channel) { return std::isfinite(channel) && channel >= 0.0F && channel <= 1.0F; });
}

}  // namespace

bool colorRange(const TileStore& image, const ColorRange& range, TileStore& out, std::string& error) {
    error.clear();
    if (!paint::detail::isRgba(image.format())) {
        error = "color range needs an RGBA image";
        return false;
    }
    if (!validRgb(range.target) || !std::isfinite(range.tolerance) || !std::isfinite(range.feather) || range.tolerance < 0.0F ||
        range.tolerance > 1.0F || range.feather < 0.0F || range.feather > 1.0F) {
        error = "color range needs a normalized finite target, tolerance, and feather";
        return false;
    }
    if (out.format() != PixelFormat::A16 || out.width() != image.width() || out.height() != image.height()) {
        error = "the output must be an A16 mask of the image's size";
        return false;
    }
    out.clear();
    const auto bpp = image.bytes_per_pixel();
    for (const auto coordinate : image.tileCoordinates()) {
        const auto source = image.readTile(coordinate);
        if (!source) {
            out.clear();
            error = "could not read image tile";
            return false;
        }
        TileBuffer tile(out.tile_byte_size(), 0);
        bool any = false;
        for (std::uint32_t y = 0; y < kTileEdge; ++y) {
            const auto gy = static_cast<std::uint32_t>(coordinate.y) * kTileEdge + y;
            if (gy >= image.height()) break;
            for (std::uint32_t x = 0; x < kTileEdge; ++x) {
                const auto gx = static_cast<std::uint32_t>(coordinate.x) * kTileEdge + x;
                if (gx >= image.width()) break;
                const auto* raw = source->data() + (static_cast<std::size_t>(y) * kTileEdge + x) * bpp;
                if (image.format() == PixelFormat::RGBA32F) {
                    for (std::size_t channel = 0; channel < 4; ++channel) {
                        float value = 0.0F;
                        std::memcpy(&value, raw + channel * sizeof(float), sizeof(float));
                        if (!std::isfinite(value)) {
                            out.clear();
                            error = "color range image contains non-finite pixels";
                            return false;
                        }
                    }
                }
                std::array<float, 4> pixel{};
                paint::detail::readPixel(image.format(), raw, pixel.data());
                const double dr = pixel[0] - range.target[0], dg = pixel[1] - range.target[1], db = pixel[2] - range.target[2];
                const double distance = std::sqrt((dr * dr + dg * dg + db * db) / 3.0);
                double coverage = 0.0;
                if (distance <= range.tolerance) coverage = 1.0;
                else if (range.feather > 0.0F && distance < range.tolerance + range.feather)
                    coverage = 1.0 - smoothstep((distance - range.tolerance) / range.feather);
                coverage *= std::clamp(pixel[3], 0.0F, 1.0F);
                if (coverage <= 0.0) continue;
                const auto quantized = static_cast<std::uint16_t>(coverage * 65535.0 + 0.5);
                std::memcpy(tile.data() + (static_cast<std::size_t>(y) * kTileEdge + x) * sizeof(quantized), &quantized, sizeof(quantized));
                any = true;
            }
        }
        if (any && !out.writeTile(coordinate, tile)) {
            out.clear();
            error = "could not store the color-range mask";
            return false;
        }
    }
    return true;
}

std::optional<TileStore> combine(const TileStore& base, const TileStore& operand, selection::Op op) {
    return selection::combine(base, operand, op);
}

}  // namespace croptop::develop::mask_ops
