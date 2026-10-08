#include "fill_command.h"

#include <algorithm>
#include <atomic>
#include <cmath>
#include <cstring>
#include <exception>
#include <optional>
#include <vector>

#include "content_aware_fill.h"
#include "parallel_util.h"
#include "pixel_plane.h"
#include "selection_mask.h"

namespace croptop::paint {
namespace {

using namespace detail;

std::int64_t floorMod(std::int64_t a, std::int64_t m) {
    const auto r = a % m;
    return r < 0 ? r + m : r;
}

}  // namespace

bool fill(TileStore& target, const FillSpec& spec, const TileStore* mask, std::string& error) {
    if (!isRgba(target.format())) {
        error = "fill needs an RGBA layer";
        return false;
    }
    if (mask != nullptr && (mask->format() != PixelFormat::A16 || mask->width() != target.width() || mask->height() != target.height())) {
        error = "the selection must be an A16 mask of the layer's size";
        return false;
    }
    if (!std::isfinite(spec.opacity) || spec.opacity < 0.0F || spec.opacity > 1.0F ||
        !std::all_of(spec.color.begin(), spec.color.end(), [](float v) { return std::isfinite(v); })) {
        error = "fill opacity must be 0–1 and the colour finite";
        return false;
    }
    if (spec.source == FillSource::Pattern &&
        (spec.pattern == nullptr || !isRgba(spec.pattern->format()) || spec.pattern->width() == 0U || spec.pattern->height() == 0U ||
         static_cast<std::uint64_t>(spec.pattern->width()) * spec.pattern->height() > 64ULL * 1024ULL * 1024ULL)) {
        error = "pattern fill needs an RGBA pattern of at most 64 MP";
        return false;
    }
    if (spec.source == FillSource::ContentAware && mask == nullptr) {
        error = "content-aware fill needs a selection";
        return false;
    }
    if (spec.opacity == 0.0F) return true;
    const auto bounds = mask != nullptr ? selection::bounds(*mask) : std::optional<PixelRect>(PixelRect{
                                                                       0, 0, static_cast<std::int32_t>(target.width()),
                                                                       static_cast<std::int32_t>(target.height())});
    if (!bounds) return true;
    try {
        std::optional<TileStore> filled;
        Plane pattern;
        if (spec.source == FillSource::ContentAware) {
            ContentAwareFillSpec content_spec;
            content_spec.progress = spec.progress;
            content_spec.rotation_adaptation = spec.rotation_adaptation;
            content_spec.mirror_adaptation = spec.mirror_adaptation;
            content_spec.color_adaptation = spec.color_adaptation;
            filled = contentAwareFill(target, *mask, spec.sampling, content_spec, error);
            if (!filled) return false;
        } else if (spec.source == FillSource::Pattern) {
            pattern = readPlane(*spec.pattern, 0, 0, static_cast<int>(spec.pattern->width()), static_cast<int>(spec.pattern->height()));
        }
        const TileStore& caf = filled ? *filled : target;
        const int edge = static_cast<int>(kTileEdge);
        const int tx0 = bounds->left / edge, ty0 = bounds->top / edge;
        const int tx1 = (bounds->right - 1) / edge, ty1 = (bounds->bottom - 1) / edge;
        const auto columns = static_cast<std::size_t>(tx1 - tx0 + 1);
        const auto count = columns * static_cast<std::size_t>(ty1 - ty0 + 1);
        const auto bpp = target.bytes_per_pixel();
        std::atomic<bool> failed{false};
        parallel::parallelFor(count, parallel::workerCount(0), [&](std::size_t item, std::size_t) {
            const TileCoordinate coordinate{tx0 + static_cast<std::int32_t>(item % columns), ty0 + static_cast<std::int32_t>(item / columns)};
            const auto mask_tile = mask != nullptr ? mask->readTile(coordinate) : nullptr;
            if (mask != nullptr && mask_tile == nullptr) return;
            const auto existing = target.readTile(coordinate);
            if (spec.preserve_transparency && existing == nullptr) return;
            const auto source_tile = filled ? caf.readTile(coordinate) : nullptr;
            TileBuffer tile = existing ? *existing : TileBuffer(target.tile_byte_size(), 0);
            for (int ly = 0; ly < edge; ++ly) {
                const int y = coordinate.y * edge + ly;
                if (y < bounds->top || y >= bounds->bottom) continue;
                for (int lx = 0; lx < edge; ++lx) {
                    const int x = coordinate.x * edge + lx;
                    if (x < bounds->left || x >= bounds->right) continue;
                    float coverage = 1.0F;
                    if (mask_tile != nullptr) {
                        std::uint16_t raw = 0;
                        std::memcpy(&raw, mask_tile->data() + (static_cast<std::size_t>(ly) * kTileEdge + lx) * 2U, 2U);
                        if (raw == 0U) continue;
                        coverage = raw / 65535.0F;
                    }
                    auto* p = tile.data() + (static_cast<std::size_t>(ly) * kTileEdge + lx) * bpp;
                    BlendPixelF backdrop{};
                    readPixel(target.format(), p, backdrop.data());
                    if (spec.preserve_transparency && backdrop[3] <= 0.0F) continue;
                    BlendPixelF source = spec.color;
                    if (spec.source == FillSource::Pattern) {
                        const auto px = floorMod(x - spec.pattern_x, pattern.w), py = floorMod(y - spec.pattern_y, pattern.h);
                        std::copy_n(pattern.at(static_cast<int>(px), static_cast<int>(py)), 4, source.begin());
                    } else if (spec.source == FillSource::ContentAware) {
                        if (source_tile == nullptr) continue;
                        readPixel(target.format(), source_tile->data() + (static_cast<std::size_t>(ly) * kTileEdge + lx) * bpp, source.data());
                    }
                    const float alpha = backdrop[3];
                    if (spec.preserve_transparency) backdrop[3] = 1.0F;
                    auto result = blendRgbaF(backdrop, source, spec.mode, spec.opacity * coverage, static_cast<std::uint32_t>(x),
                                             static_cast<std::uint32_t>(y), spec.dissolve_seed);
                    if (spec.preserve_transparency) result[3] = alpha;
                    writePixel(target.format(), p, result.data());
                }
            }
            if (!target.writeTile(coordinate, tile)) failed.store(true);
        });
        if (failed.load()) {
            error = "could not store filled pixels";
            return false;
        }
        return true;
    } catch (const std::exception&) {
        error = "could not allocate fill buffers";
        return false;
    }
}

}  // namespace croptop::paint
