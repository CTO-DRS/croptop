// Smart objects and smart filters (/).
#include "smart_object.h"

#include <algorithm>
#include <atomic>
#include <cmath>
#include <cstring>
#include <set>

#include "filters_blur.h"
#include "parallel_util.h"
#include "pixel_plane.h"
#include "scratch_store.h"

namespace croptop::smart {
namespace {

Matrix multiply(const Matrix& a, const Matrix& b) {
    Matrix m{};
    for (int r = 0; r < 3; ++r)
        for (int c = 0; c < 3; ++c)
            for (int k = 0; k < 3; ++k) m[static_cast<std::size_t>(r * 3 + c)] += a[static_cast<std::size_t>(r * 3 + k)] * b[static_cast<std::size_t>(k * 3 + c)];
    return m;
}

bool isIdentity(const Matrix& m) {
    const Matrix id{1, 0, 0, 0, 1, 0, 0, 0, 1};
    for (int i = 0; i < 9; ++i)
        if (std::abs(m[static_cast<std::size_t>(i)] - id[static_cast<std::size_t>(i)]) > 1e-12) return false;
    return true;
}

/** Premultiplied interpolation a → b by t (alpha included), straight alpha in and out. */
std::array<float, 4> mix(const float* a, const float* b, float t) {
    const float alpha = a[3] + (b[3] - a[3]) * t;
    std::array<float, 4> out{0, 0, 0, alpha};
    if (alpha > 0.0F)
        for (int c = 0; c < 3; ++c) out[static_cast<std::size_t>(c)] = (a[c] * a[3] + (b[c] * b[3] - a[c] * a[3]) * t) / alpha;
    return out;
}

std::vector<TileCoordinate> unionTiles(const TileStore& a, const TileStore& b) {
    std::set<std::pair<std::int32_t, std::int32_t>> set;
    for (const auto& c : a.tileCoordinates()) set.emplace(c.x, c.y);
    for (const auto& c : b.tileCoordinates()) set.emplace(c.x, c.y);
    std::vector<TileCoordinate> out;
    for (const auto& [x, y] : set) out.push_back({x, y});
    return out;
}

/**
 * Per-pixel combination of two same-sized stores tile by tile: out = f(a, b, x, y, k) where k is the
 * mask coverage (or 1 without a mask).
 */
template <typename F>
std::optional<TileStore> combine(const TileStore& a, const TileStore& b, const TileStore* mask, unsigned workers, std::string& error, F&& f) {
    TileStore out = a.emptyLike(a.format());
    const auto coords = unionTiles(a, b);
    const auto bpp = a.bytes_per_pixel();
    std::atomic<bool> failed{false};
    parallel::parallelFor(coords.size(), parallel::workerCount(workers), [&](std::size_t index, std::size_t) {
        const auto c = coords[index];
        const auto ta = a.readTile(c), tb = b.readTile(c);
        const auto tm = mask != nullptr ? mask->readTile(c) : nullptr;
        TileBuffer tile(out.tile_byte_size(), 0);
        for (std::size_t p = 0; p < static_cast<std::size_t>(kTileEdge) * kTileEdge; ++p) {
            float pa[4] = {0, 0, 0, 0}, pb[4] = {0, 0, 0, 0};
            if (ta) paint::detail::readPixel(a.format(), ta->data() + p * bpp, pa);
            if (tb) paint::detail::readPixel(b.format(), tb->data() + p * bpp, pb);
            float k = 1.0F;
            if (mask != nullptr) {
                std::uint16_t v = 0;
                if (tm) std::memcpy(&v, tm->data() + p * 2, 2);
                k = v / 65535.0F;
            }
            const auto x = static_cast<std::uint32_t>(c.x) * kTileEdge + static_cast<std::uint32_t>(p % kTileEdge);
            const auto y = static_cast<std::uint32_t>(c.y) * kTileEdge + static_cast<std::uint32_t>(p / kTileEdge);
            const auto r = f(pa, pb, x, y, k);
            paint::detail::writePixel(out.format(), tile.data() + p * bpp, r.data());
        }
        if (!out.writeTile(c, tile)) failed.store(true);
    });
    if (failed.load()) {
        error = "could not store smart object pixels";
        return std::nullopt;
    }
    if (auto budget = out.memoryBudgetManager()) budget->trimTo(budget->budgetBytes());
    return out;
}

}  // namespace

SmartObject convertToSmartObject(const TileStore& layer) { return SmartObject{layer, {}, {}, std::nullopt, true}; }

Matrix composedTransform(const std::vector<Matrix>& transforms) {
    Matrix m{1, 0, 0, 0, 1, 0, 0, 0, 1};
    for (const auto& t : transforms) m = multiply(t, m);
    return m;
}

std::optional<TileStore> render(const SmartObject& object, std::uint32_t width, std::uint32_t height, const RenderOptions& options, std::string& error) {
    if (!paint::detail::isRgba(object.source.format())) {
        error = "smart object source must be RGBA";
        return std::nullopt;
    }
    if (width == 0 || height == 0) {
        error = "document size must be positive";
        return std::nullopt;
    }
    for (const auto& t : object.transforms) {
        if (std::any_of(t.begin(), t.end(), [](double v) { return !std::isfinite(v); })) {
            error = "smart object transforms must be finite";
            return std::nullopt;
        }
    }
    for (const auto& f : object.filters) {
        if (!f.apply || !std::isfinite(f.opacity) || f.opacity < 0.0F || f.opacity > 1.0F || static_cast<int>(f.mode) > static_cast<int>(BlendMode::Luminosity)) {
            error = "smart filter '" + f.name + "' is invalid (missing function, opacity outside 0–1 or unknown blend mode)";
            return std::nullopt;
        }
    }
    const bool use_mask = object.filter_mask.has_value() && object.filter_mask_enabled;
    if (use_mask && (object.filter_mask->format() != PixelFormat::A16 || object.filter_mask->width() != width || object.filter_mask->height() != height)) {
        error = "filter mask must be A16 and match the document size";
        return std::nullopt;
    }

    // 1. One resample from the untouched source through the composed transform.
    const Matrix m = composedTransform(object.transforms);
    std::optional<TileStore> placed;
    if (isIdentity(m) && object.source.width() == width && object.source.height() == height) {
        placed.emplace(object.source);  // COW copy: exact, no resampling
    } else {
        placed = resample::warp(object.source, m, width, height, options.quality, error, options.worker_threads);
        if (!placed) return std::nullopt;
    }

    // 2. Smart filters in order, each blended over its own input.
    // TileStore assignment requires an identical configuration, so the running result lives in an optional.
    std::optional<TileStore> filtered(*placed);
    bool any = false;
    for (const auto& f : object.filters) {
        if (!f.enabled) continue;
        TileStore result(*filtered);
        if (!f.apply(result, error)) {
            if (error.empty()) error = "smart filter '" + f.name + "' failed";
            return std::nullopt;
        }
        if (f.mode == BlendMode::Normal && f.opacity == 1.0F) {
            filtered.reset();
            filtered.emplace(std::move(result));
        } else {
            auto blended = combine(*filtered, result, nullptr, options.worker_threads, error, [&](const float* in, const float* fx, std::uint32_t x, std::uint32_t y, float) {
                if (f.mode == BlendMode::Normal) return mix(in, fx, f.opacity);
                return blendRgbaF({in[0], in[1], in[2], in[3]}, {fx[0], fx[1], fx[2], fx[3]}, f.mode, f.opacity, x, y);
            });
            if (!blended) return std::nullopt;
            filtered.reset();
            filtered.emplace(std::move(*blended));
        }
        any = true;
    }
    if (!any) return placed;

    // 3. The shared filter mask chooses between the placement and the filtered result.
    if (!use_mask) return filtered;
    return combine(*placed, *filtered, &*object.filter_mask, options.worker_threads, error,
                   [](const float* base, const float* fx, std::uint32_t, std::uint32_t, float k) { return mix(base, fx, k); });
}

std::optional<TileStore> blendFilterResult(const TileStore& input, const TileStore& filtered, BlendMode mode, float opacity,
                                           unsigned worker_threads, std::string& error) {
    if (input.width() != filtered.width() || input.height() != filtered.height() || input.format() != filtered.format()) {
        error = "smart filter input and result must have matching dimensions and format";
        return std::nullopt;
    }
    if (!std::isfinite(opacity) || opacity < 0.0F || opacity > 1.0F || static_cast<int>(mode) > static_cast<int>(BlendMode::Luminosity)) {
        error = "smart filter blend settings are invalid";
        return std::nullopt;
    }
    if (mode == BlendMode::Normal && opacity == 1.0F) return filtered;
    if (mode == BlendMode::Normal && opacity == 0.0F) return input;
    return combine(input, filtered, nullptr, worker_threads, error,
                   [mode, opacity](const float* base, const float* effect, std::uint32_t x, std::uint32_t y, float) {
                       if (mode == BlendMode::Normal) return mix(base, effect, opacity);
                       return blendRgbaF({base[0], base[1], base[2], base[3]}, {effect[0], effect[1], effect[2], effect[3]}, mode, opacity, x, y);
                   });
}

std::optional<TileStore> blendThroughMask(const TileStore& base, const TileStore& filtered, const TileStore& mask, unsigned worker_threads,
                                          std::string& error) {
    if (base.width() != filtered.width() || base.height() != filtered.height() || base.format() != filtered.format()) {
        error = "filter mask inputs must have matching dimensions and format";
        return std::nullopt;
    }
    if (mask.format() != PixelFormat::A16 || mask.width() != base.width() || mask.height() != base.height()) {
        error = "filter mask must be A16 and match the document size";
        return std::nullopt;
    }
    return combine(base, filtered, &mask, worker_threads, error,
                   [](const float* in, const float* fx, std::uint32_t, std::uint32_t, float k) { return mix(in, fx, k); });
}

SmartFilter gaussianBlurFilter(float radius) {
    return SmartFilter{"Gaussian Blur", [radius](TileStore& layer, std::string& error) { return filters::gaussianBlur(layer, radius, nullptr, error); }};
}

SmartFilter unsharpMaskFilter(float amount, float radius, float threshold) {
    return SmartFilter{"Unsharp Mask", [=](TileStore& layer, std::string& error) { return filters::unsharpMask(layer, amount, radius, threshold, nullptr, error); }};
}

}  // namespace croptop::smart
