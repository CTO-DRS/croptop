// Layer-mask operations; see layer_mask_ops.h.
#include "layer_mask_ops.h"

#include <algorithm>
#include <cmath>
#include <cstring>
#include <memory>
#include <vector>

namespace croptop::layermask {
namespace {

std::vector<TileCoordinate> canvasTiles(const TileStore& store) {
    std::vector<TileCoordinate> tiles;
    for (std::uint32_t ty = 0; ty * kTileEdge < store.height(); ++ty) {
        for (std::uint32_t tx = 0; tx * kTileEdge < store.width(); ++tx) {
            tiles.push_back({static_cast<std::int32_t>(tx), static_cast<std::int32_t>(ty)});
        }
    }
    return tiles;
}

bool isUniform(const TileBuffer& tile, std::uint16_t value) {
    for (std::size_t i = 0; i < tile.size(); i += 2) {
        std::uint16_t v;
        std::memcpy(&v, tile.data() + i, 2);
        if (v != value) return false;
    }
    return true;
}

}  // namespace

std::optional<MaskData> createMask(const TileStore& like, NewMask kind, const TileStore* selection, std::string& error) {
    const bool needs_selection = kind == NewMask::RevealSelection || kind == NewMask::HideSelection;
    if (needs_selection) {
        if (selection == nullptr) {
            error = "this mask needs a selection";
            return std::nullopt;
        }
        if (selection->format() != PixelFormat::A16 || selection->width() != like.width() || selection->height() != like.height()) {
            error = "the selection must be an A16 store of the canvas size";
            return std::nullopt;
        }
    }
    try {
        switch (kind) {
            case NewMask::RevealAll: return MaskData{like.emptyLike(PixelFormat::A16), 1.0F};
            case NewMask::HideAll: return MaskData{like.emptyLike(PixelFormat::A16), 0.0F};
            case NewMask::RevealSelection: return MaskData{*selection, 0.0F};
            case NewMask::HideSelection: return invertMask({*selection, 0.0F});
        }
    } catch (const std::exception& exception) {
        error = exception.what();
    }
    error = error.empty() ? "unknown mask kind" : error;
    return std::nullopt;
}

MaskData invertMask(const MaskData& mask) {
    MaskData out{mask.pixels.emptyLike(PixelFormat::A16), 1.0F - mask.empty};
    for (const auto coordinate : mask.pixels.tileCoordinates()) {
        const auto source = mask.pixels.readTile(coordinate);
        if (source == nullptr) continue;
        TileBuffer tile(source->size());
        for (std::size_t i = 0; i < tile.size(); i += 2) {
            std::uint16_t v;
            std::memcpy(&v, source->data() + i, 2);
            v = static_cast<std::uint16_t>(65535U - v);
            std::memcpy(tile.data() + i, &v, 2);
        }
        out.pixels.writeTile(coordinate, tile);
    }
    return out;
}

std::optional<TileStore> applyToPixels(const TileStore& pixels, const MaskData& mask, const MaskProperties& properties, std::string& error) {
    if (pixels.format() != PixelFormat::RGBA8 && pixels.format() != PixelFormat::RGBA16 && pixels.format() != PixelFormat::RGBA32F) {
        error = "a mask applies to RGBA pixel layers";
        return std::nullopt;
    }
    if (mask.pixels.width() != pixels.width() || mask.pixels.height() != pixels.height()) {
        error = "the mask and the layer differ in size";
        return std::nullopt;
    }
    auto cache = MaskCache::create(mask.pixels, properties, mask.empty, error, mask.empty);
    if (!cache) return std::nullopt;
    TileStore out = pixels.emptyLike(pixels.format());
    for (const auto coordinate : pixels.tileCoordinates()) {
        const auto source = pixels.readTile(coordinate);
        if (source == nullptr) continue;
        TileBuffer tile = *source;
        applyCoverage(pixels.format(), tile, *cache->tile(coordinate));
        if (!out.writeTile(coordinate, tile)) {
            error = "could not allocate the masked pixels";
            return std::nullopt;
        }
    }
    return out;
}

std::optional<TileStore> toSelection(const MaskData& mask, const MaskProperties& properties, std::string& error) {
    auto cache = MaskCache::create(mask.pixels, properties, mask.empty, error, mask.empty);
    if (!cache) return std::nullopt;
    TileStore out = mask.pixels.emptyLike(PixelFormat::A16);
    for (const auto coordinate : canvasTiles(mask.pixels)) {
        const auto coverage = cache->tile(coordinate);
        if (isUniform(*coverage, 0)) continue;
        if (!out.writeTile(coordinate, *coverage)) {
            error = "could not allocate the selection";
            return std::nullopt;
        }
    }
    return out;
}

std::optional<MaskThumbnail> thumbnail(const MaskData& mask, std::uint32_t max_px, std::string& error) {
    if (max_px == 0) {
        error = "thumbnail size must be positive";
        return std::nullopt;
    }
    const auto width = mask.pixels.width(), height = mask.pixels.height();
    std::uint32_t level = 0;
    while (level < 16 && (std::max(width, height) + (1U << level) - 1U) >> level > max_px) ++level;
    const std::uint32_t out_w = std::max(1U, (width + (1U << level) - 1U) >> level);
    const std::uint32_t out_h = std::max(1U, (height + (1U << level) - 1U) >> level);
    MaskProperties plain;  // enabled, density 1, no feather: what was painted
    auto cache = MaskCache::create(mask.pixels, plain, mask.empty, error, mask.empty);
    if (!cache) return std::nullopt;
    MaskThumbnail out;
    out.width = out_w;
    out.height = out_h;
    out.gray.resize(static_cast<std::size_t>(out_w) * out_h);
    for (std::uint32_t ty = 0; ty * kTileEdge < out_h; ++ty) {
        for (std::uint32_t tx = 0; tx * kTileEdge < out_w; ++tx) {
            const auto tile = cache->tileAtLevel(level, {static_cast<std::int32_t>(tx), static_cast<std::int32_t>(ty)});
            for (std::uint32_t y = 0; y < kTileEdge && ty * kTileEdge + y < out_h; ++y) {
                for (std::uint32_t x = 0; x < kTileEdge && tx * kTileEdge + x < out_w; ++x) {
                    std::uint16_t v;
                    std::memcpy(&v, tile->data() + (static_cast<std::size_t>(y) * kTileEdge + x) * 2, 2);
                    out.gray[static_cast<std::size_t>(ty * kTileEdge + y) * out_w + tx * kTileEdge + x] =
                        static_cast<std::uint8_t>((static_cast<unsigned>(v) + 128U) / 257U);
                }
            }
        }
    }
    return out;
}

std::optional<MaskData> paintStroke(const MaskData& mask, const brush::StrokeMaskCompositor& coverage, float value, float opacity,
                                    std::span<const TileCoordinate> affected, const TileStore* selection, std::string& error) {
    if (!(value >= 0.0F && value <= 1.0F) || !(opacity >= 0.0F && opacity <= 1.0F)) {
        error = "stroke value and opacity must be 0..1";
        return std::nullopt;
    }
    const auto width = mask.pixels.width(), height = mask.pixels.height();
    const auto empty16 = static_cast<std::uint16_t>(std::lround(mask.empty * 65535.0F));
    // The stroke compositor paints RGBA layers: view the affected mask tiles as opaque gray RGBA16, paint, read gray back.
    TileStore baseline = mask.pixels.emptyLike(PixelFormat::RGBA16);
    for (const auto coordinate : affected) {
        const auto source = mask.pixels.readTile(coordinate);
        TileBuffer tile(baseline.tile_byte_size());
        for (std::size_t p = 0; p < static_cast<std::size_t>(kTileEdge) * kTileEdge; ++p) {
            std::uint16_t v = empty16;
            if (source != nullptr) std::memcpy(&v, source->data() + p * 2, 2);
            const std::uint16_t rgba[4] = {v, v, v, 0xFFFF};
            std::memcpy(tile.data() + p * 8, rgba, 8);
        }
        if (!baseline.writeTile(coordinate, tile)) {
            error = "could not allocate the mask paint buffer";
            return std::nullopt;
        }
    }
    TileStore painted = baseline;
    const float color[4] = {value, value, value, 1.0F};
    if (!coverage.compose(baseline, painted, color, opacity, /*erase=*/false, /*preserve_alpha=*/true, error, selection)) return std::nullopt;

    MaskData out{mask.pixels, mask.empty};
    for (const auto coordinate : affected) {
        if (coordinate.x < 0 || coordinate.y < 0 || static_cast<std::uint64_t>(coordinate.x) * kTileEdge >= width ||
            static_cast<std::uint64_t>(coordinate.y) * kTileEdge >= height) {
            continue;
        }
        const auto gray = painted.readTile(coordinate);
        if (gray == nullptr) continue;
        TileBuffer tile(out.pixels.tile_byte_size());
        bool uniform = true;
        for (std::size_t p = 0; p < static_cast<std::size_t>(kTileEdge) * kTileEdge; ++p) {
            std::uint16_t v;
            std::memcpy(&v, gray->data() + p * 8, 2);
            std::memcpy(tile.data() + p * 2, &v, 2);
            uniform = uniform && v == empty16;
        }
        if (uniform) {
            out.pixels.eraseTile(coordinate);
        } else if (!out.pixels.writeTile(coordinate, tile)) {
            error = "could not allocate the painted mask";
            return std::nullopt;
        }
    }
    return out;
}

}  // namespace croptop::layermask
