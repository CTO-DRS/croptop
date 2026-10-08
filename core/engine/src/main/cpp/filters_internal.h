#pragma once

// Internal machinery shared by the CPU filter libraries (filters_blur, filters_noise): premultiplied
// row reads with edge replicate, a decoded-tile sampler, per-tile windows, and the pointwise tile engine that
// blends results by the optional A16 mask.

#include <algorithm>
#include <array>
#include <atomic>
#include <cmath>
#include <cstring>
#include <exception>
#include <functional>
#include <memory>
#include <optional>
#include <string>
#include <vector>

#include "parallel_util.h"
#include "pixel_plane.h"
#include "selection_mask.h"
#include "tile_store.h"

namespace croptop::filters::detail {

using paint::detail::decodeSpan;
using paint::detail::isRgba;
using paint::detail::readPixel;
using paint::detail::writePixel;
using Pixel = std::array<float, 4>;

constexpr int kEdge = static_cast<int>(kTileEdge);

inline Pixel unpremultiply(Pixel p) {
    if (p[3] > 1e-7F) {
        for (int c = 0; c < 3; ++c) p[static_cast<std::size_t>(c)] /= p[3];
    } else {
        p = {0.0F, 0.0F, 0.0F, 0.0F};
    }
    return p;
}

inline bool validate(const TileStore& layer, const TileStore* mask, std::string& error) {
    if (!isRgba(layer.format())) {
        error = "filters apply to RGBA layers";
        return false;
    }
    if (mask != nullptr && (mask->format() != PixelFormat::A16 || mask->width() != layer.width() || mask->height() != layer.height())) {
        error = "the mask must be an A16 mask of the layer's size";
        return false;
    }
    return true;
}

/** Output region: the mask's bounds, or the whole layer. */
inline std::optional<PixelRect> region(const TileStore& layer, const TileStore* mask) {
    if (mask != nullptr) return selection::bounds(*mask);
    return PixelRect{0, 0, static_cast<std::int32_t>(layer.width()), static_cast<std::int32_t>(layer.height())};
}


/** Reads one row segment [x0, x0 + n) of `y` (both clamped to the layer: edge replicate), premultiplied. */
class RowReader final {
public:
    explicit RowReader(const TileStore& layer) : layer_(layer) {}

    void read(int y, int x0, int n, float* out) {
        y = std::clamp(y, 0, static_cast<int>(layer_.height()) - 1);
        const int width = static_cast<int>(layer_.width());
        const auto bpp = layer_.bytes_per_pixel();
        // Inside the layer: decode whole spans per tile.
        const int inside0 = std::clamp(x0, 0, width), inside1 = std::clamp(x0 + n, 0, width);
        for (int x = inside0; x < inside1;) {
            const int tile_x = x / kEdge;
            const int span_end = std::min(inside1, (tile_x + 1) * kEdge);
            float* dst = out + static_cast<std::size_t>(x - x0) * 4U;
            const auto tile = layer_.readTile({tile_x, y / kEdge});
            if (tile == nullptr) {
                std::fill_n(dst, static_cast<std::size_t>(span_end - x) * 4U, 0.0F);
            } else {
                decodeSpan(layer_.format(), tile->data() + (static_cast<std::size_t>(y % kEdge) * kTileEdge + static_cast<std::size_t>(x % kEdge)) * bpp, dst,
                           static_cast<std::size_t>(span_end - x), true);
            }
            x = span_end;
        }
        // Outside: replicate the edge pixels.
        if (inside0 >= inside1) {
            const int edge_x = x0 + n <= 0 ? 0 : width - 1;
            float pixel[4];
            read(y, edge_x, 1, pixel);
            for (int i = 0; i < n; ++i) std::copy_n(pixel, 4, out + static_cast<std::size_t>(i) * 4U);
            return;
        }
        for (int x = x0; x < inside0; ++x) std::copy_n(out + static_cast<std::size_t>(inside0 - x0) * 4U, 4, out + static_cast<std::size_t>(x - x0) * 4U);
        for (int x = inside1; x < x0 + n; ++x) std::copy_n(out + static_cast<std::size_t>(inside1 - 1 - x0) * 4U, 4, out + static_cast<std::size_t>(x - x0) * 4U);
    }

private:
    const TileStore& layer_;
};


/**
 * Premultiplied bilinear sampling from anywhere in a store (edge replicate). Keeps the 8 most recently used
 * tiles decoded to premultiplied float (8 MB per worker), so taps are plain array reads.
 */
class Sampler final {
public:
    explicit Sampler(const TileStore& layer) : layer_(layer) {}

    Pixel texel(int x, int y) {
        x = std::clamp(x, 0, static_cast<int>(layer_.width()) - 1);
        y = std::clamp(y, 0, static_cast<int>(layer_.height()) - 1);
        const float* p = tile({x / kEdge, y / kEdge}) + (static_cast<std::size_t>(y % kEdge) * kTileEdge + static_cast<std::size_t>(x % kEdge)) * 4U;
        return {p[0], p[1], p[2], p[3]};
    }

    /** Bilinear at a continuous position (pixel centres at +0.5). */
    Pixel bilinear(double x, double y) {
        const double fx = x - 0.5, fy = y - 0.5;
        const int ix = static_cast<int>(std::floor(fx)), iy = static_cast<int>(std::floor(fy));
        const auto tx = static_cast<float>(fx - ix), ty = static_cast<float>(fy - iy);
        Pixel out{};
        // Fast path: the 2×2 footprint lies inside one tile and inside the layer.
        if (ix >= 0 && iy >= 0 && ix + 1 < static_cast<int>(layer_.width()) && iy + 1 < static_cast<int>(layer_.height()) &&
            ix / kEdge == (ix + 1) / kEdge && iy / kEdge == (iy + 1) / kEdge) {
            const float* base = tile({ix / kEdge, iy / kEdge}) + (static_cast<std::size_t>(iy % kEdge) * kTileEdge + static_cast<std::size_t>(ix % kEdge)) * 4U;
            const float* below = base + kTileEdge * 4U;
            for (std::size_t k = 0; k < 4; ++k) {
                out[k] = (base[k] * (1 - tx) + base[4 + k] * tx) * (1 - ty) + (below[k] * (1 - tx) + below[4 + k] * tx) * ty;
            }
            return out;
        }
        const auto a = texel(ix, iy), b = texel(ix + 1, iy), c = texel(ix, iy + 1), d = texel(ix + 1, iy + 1);
        for (std::size_t k = 0; k < 4; ++k) {
            out[k] = (a[k] * (1 - tx) + b[k] * tx) * (1 - ty) + (c[k] * (1 - tx) + d[k] * tx) * ty;
        }
        return out;
    }

private:
    struct Slot {
        TileCoordinate coordinate{0, 0};
        std::vector<float> pixels;  // premultiplied RGBA, kTileEdge² × 4
        std::uint64_t used = 0;
        bool valid = false;
    };

    const float* tile(TileCoordinate coordinate) {
        ++clock_;
        if (last_ != nullptr && last_->coordinate == coordinate) {
            last_->used = clock_;
            return last_->pixels.data();
        }
        Slot* victim = &slots_[0];
        for (auto& slot : slots_) {
            if (slot.valid && slot.coordinate == coordinate) {
                slot.used = clock_;
                last_ = &slot;
                return slot.pixels.data();
            }
            if (!slot.valid || slot.used < victim->used) victim = &slot;
        }
        victim->pixels.resize(kTileEdge * kTileEdge * 4U);
        const auto source = layer_.readTile(coordinate);
        if (source == nullptr) {
            std::fill(victim->pixels.begin(), victim->pixels.end(), 0.0F);
        } else {
            decodeSpan(layer_.format(), source->data(), victim->pixels.data(), kTileEdge * kTileEdge, true);
        }
        victim->coordinate = coordinate;
        victim->valid = true;
        victim->used = clock_;
        last_ = victim;
        return victim->pixels.data();
    }

    const TileStore& layer_;
    std::array<Slot, 8> slots_{};
    Slot* last_ = nullptr;
    std::uint64_t clock_ = 0;
};

/** Premultiplied float copy of an output tile plus `pad` px (edge replicate); reads clamp to its bounds. */
struct Window final {
    int x0 = 0;
    int y0 = 0;
    int w = 0;
    int h = 0;
    std::vector<float> px;

    void build(RowReader& reader, int left, int top, int right, int bottom, int pad) {
        x0 = left - pad;
        y0 = top - pad;
        w = right - left + 2 * pad;
        h = bottom - top + 2 * pad;
        px.resize(static_cast<std::size_t>(w) * static_cast<std::size_t>(h) * 4U);
        for (int y = 0; y < h; ++y) reader.read(y0 + y, x0, w, px.data() + static_cast<std::size_t>(y) * static_cast<std::size_t>(w) * 4U);
    }
    const float* at(int x, int y) const {
        x = std::clamp(x - x0, 0, w - 1);
        y = std::clamp(y - y0, 0, h - 1);
        return px.data() + (static_cast<std::size_t>(y) * static_cast<std::size_t>(w) + static_cast<std::size_t>(x)) * 4U;
    }
    Pixel texel(int x, int y) const {
        const float* p = at(x, y);
        return {p[0], p[1], p[2], p[3]};
    }
    Pixel bilinear(double x, double y) const {
        const double fx = x - 0.5, fy = y - 0.5;
        const int ix = static_cast<int>(std::floor(fx)), iy = static_cast<int>(std::floor(fy));
        const auto tx = static_cast<float>(fx - ix), ty = static_cast<float>(fy - iy);
        const float *a = nullptr, *b = nullptr, *c = nullptr, *d = nullptr;
        if (ix >= x0 && iy >= y0 && ix + 1 < x0 + w && iy + 1 < y0 + h) {  // no clamping needed
            a = px.data() + (static_cast<std::size_t>(iy - y0) * static_cast<std::size_t>(w) + static_cast<std::size_t>(ix - x0)) * 4U;
            b = a + 4;
            c = a + static_cast<std::size_t>(w) * 4U;
            d = c + 4;
        } else {
            a = at(ix, iy), b = at(ix + 1, iy), c = at(ix, iy + 1), d = at(ix + 1, iy + 1);
        }
        Pixel out{};
        for (std::size_t k = 0; k < 4; ++k) out[k] = (a[k] * (1 - tx) + b[k] * tx) * (1 - ty) + (c[k] * (1 - tx) + d[k] * tx) * ty;
        return out;
    }
};

/** What a pointwise filter can read: the tile's window (when a pad was given) and samplers over the whole layer. */
struct Taps final {
    const Window& window;
    Sampler& layer;
    Sampler& aux;
};

/** Per-pixel filter over the region's tiles; `filter(sampler, x, y)` returns the premultiplied result. */
inline bool pointwise(TileStore& layer, const TileStore* mask, int pad, const std::function<Pixel(Taps&, int, int)>& filter, std::string& error,
               unsigned worker_threads, const TileStore* aux = nullptr) {
    if (!validate(layer, mask, error)) return false;
    const auto bounds = region(layer, mask);
    if (!bounds) return true;
    const TileStore source(layer);
    const int tx0 = bounds->left / kEdge, ty0 = bounds->top / kEdge;
    const int tx1 = (bounds->right - 1) / kEdge, ty1 = (bounds->bottom - 1) / kEdge;
    const auto columns = static_cast<std::size_t>(tx1 - tx0 + 1);
    const auto bpp = layer.bytes_per_pixel();
    std::atomic<bool> failed{false};
    try {
        parallel::parallelFor(columns * static_cast<std::size_t>(ty1 - ty0 + 1), parallel::workerCount(worker_threads), [&](std::size_t item, std::size_t) {
            const TileCoordinate coordinate{tx0 + static_cast<int>(item % columns), ty0 + static_cast<int>(item / columns)};
            const auto mask_tile = mask != nullptr ? mask->readTile(coordinate) : nullptr;
            if (mask != nullptr && mask_tile == nullptr) return;
            Sampler sampler(source);
            Sampler aux_sampler(aux != nullptr ? *aux : source);
            Window window;
            if (pad >= 0) {
                RowReader reader(source);
                const int left = std::max(bounds->left, coordinate.x * kEdge), top = std::max(bounds->top, coordinate.y * kEdge);
                const int right = std::min(bounds->right, (coordinate.x + 1) * kEdge), bottom = std::min(bounds->bottom, (coordinate.y + 1) * kEdge);
                window.build(reader, left, top, right, bottom, pad);
            }
            Taps taps{window, sampler, aux_sampler};
            const auto existing = source.readTile(coordinate);
            TileBuffer tile = existing ? *existing : TileBuffer(layer.tile_byte_size(), 0);
            for (int ly = 0; ly < kEdge; ++ly) {
                const int y = coordinate.y * kEdge + ly;
                if (y < bounds->top || y >= bounds->bottom) continue;
                for (int lx = 0; lx < kEdge; ++lx) {
                    const int x = coordinate.x * kEdge + lx;
                    if (x < bounds->left || x >= bounds->right) continue;
                    const auto local = static_cast<std::size_t>(ly) * kTileEdge + static_cast<std::size_t>(lx);
                    float coverage = 1.0F;
                    if (mask_tile != nullptr) {
                        std::uint16_t raw = 0;
                        std::memcpy(&raw, mask_tile->data() + local * 2U, 2U);
                        if (raw == 0U) continue;
                        coverage = raw / 65535.0F;
                    }
                    auto* p = tile.data() + local * bpp;
                    Pixel original{};
                    readPixel(layer.format(), p, original.data());
                    const auto result = unpremultiply(filter(taps, x, y));
                    Pixel mixed{};
                    for (std::size_t c = 0; c < 4; ++c) mixed[c] = original[c] + coverage * (result[c] - original[c]);
                    writePixel(layer.format(), p, mixed.data());
                }
            }
            if (!layer.writeTile(coordinate, tile)) failed.store(true);
        });
    } catch (const std::exception&) {
        error = "could not allocate filter buffers";
        return false;
    }
    if (failed.load()) error = "could not store filtered pixels";
    return !failed.load();
}


}  // namespace croptop::filters::detail
