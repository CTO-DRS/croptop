// Per-mask adjusted-result blending.
#include "develop_mask_blend.h"

#include <algorithm>
#include <array>
#include <cmath>
#include <cstring>

#include "pixel_plane.h"

namespace croptop::develop::mask_blend {
namespace {

bool valid(const TileStore& store) { return paint::detail::isRgba(store.format()); }

bool finitePixel(PixelFormat format, const std::uint8_t* pixel) {
    if (format != PixelFormat::RGBA32F) return true;
    for (std::size_t c = 0; c < 4; ++c) {
        float v = 0.0F;
        std::memcpy(&v, pixel + c * sizeof(float), sizeof(float));
        if (!std::isfinite(v)) return false;
    }
    return true;
}

}  // namespace

std::optional<TileStore> apply(const TileStore& base, const TileStore& adjusted, const TileStore& mask, float opacity,
                               std::string& error) {
    error.clear();
    if (!valid(base) || !valid(adjusted) || base.format() != adjusted.format() || base.width() != adjusted.width() || base.height() != adjusted.height()) {
        error = "masked adjustment needs matching RGBA source and adjusted stores";
        return std::nullopt;
    }
    if (mask.format() != PixelFormat::A16 || mask.width() != base.width() || mask.height() != base.height()) {
        error = "masked adjustment needs a matching-size A16 mask";
        return std::nullopt;
    }
    if (!std::isfinite(opacity) || opacity < 0.0F || opacity > 1.0F) {
        error = "masked adjustment opacity must be finite and in [0, 1]";
        return std::nullopt;
    }
    TileStore out(base);
    if (opacity == 0.0F || base.width() == 0 || base.height() == 0) return out;
    const auto bpp = base.bytes_per_pixel();
    for (const auto coordinate : mask.tileCoordinates()) {
        const auto coverage = mask.readTile(coordinate);
        if (!coverage) continue;
        const auto original = base.readTile(coordinate);
        // The output keeps the base alpha exactly. A missing source tile is therefore
        // transparent even when the adjusted store has pixels here; blending cannot
        // make it visible, and materializing this tile would break sparse storage.
        if (!original) continue;
        const auto result = adjusted.readTile(coordinate);
        TileBuffer tile = *original;
        bool changed = false;
        for (std::uint32_t y = 0; y < kTileEdge; ++y) {
            const auto gy = static_cast<std::uint32_t>(coordinate.y) * kTileEdge + y;
            if (gy >= base.height()) break;
            for (std::uint32_t x = 0; x < kTileEdge; ++x) {
                const auto gx = static_cast<std::uint32_t>(coordinate.x) * kTileEdge + x;
                if (gx >= base.width()) break;
                std::uint16_t mask_value = 0;
                std::memcpy(&mask_value, coverage->data() + (static_cast<std::size_t>(y) * kTileEdge + x) * sizeof(mask_value), sizeof(mask_value));
                if (!mask_value) continue;
                const float factor = (mask_value / 65535.0F) * opacity;
                if (factor <= 0.0F) continue;
                auto* destination = tile.data() + (static_cast<std::size_t>(y) * kTileEdge + x) * bpp;
                const auto* original_pixel = original ? original->data() + (static_cast<std::size_t>(y) * kTileEdge + x) * bpp : nullptr;
                const auto* adjusted_pixel = result ? result->data() + (static_cast<std::size_t>(y) * kTileEdge + x) * bpp : nullptr;
                if ((original_pixel && !finitePixel(base.format(), original_pixel)) ||
                    (adjusted_pixel && !finitePixel(adjusted.format(), adjusted_pixel))) {
                    error = "masked adjustment contains non-finite float pixels";
                    return std::nullopt;
                }
                std::array<float, 4> a{}, b{}, mixed{};
                if (original_pixel) paint::detail::readPixel(base.format(), original_pixel, a.data());
                if (adjusted_pixel) paint::detail::readPixel(adjusted.format(), adjusted_pixel, b.data());
                for (std::size_t c = 0; c < 3; ++c) mixed[c] = a[c] + (b[c] - a[c]) * factor;
                mixed[3] = a[3];
                paint::detail::writePixel(base.format(), destination, mixed.data());
                // Preserve float alpha exactly too; writePixel intentionally clamps float alpha.
                if (original_pixel) std::memcpy(destination + 3U * (bpp / 4U), original_pixel + 3U * (bpp / 4U), bpp / 4U);
                changed = true;
            }
        }
        if (changed && !out.writeTile(coordinate, tile)) {
            error = "could not store masked adjustment pixels";
            return std::nullopt;
        }
    }
    return out;
}

}  // namespace croptop::develop::mask_blend
