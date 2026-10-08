#pragma once

// Internal helpers shared by the retouch pixel libraries (heal, retouch, red eye): dense
// float RGBA planes read from a TileStore, A16 coverage reads, and coverage-weighted write-back.

#include <algorithm>
#include <array>
#include <cmath>
#include <cstring>
#include <memory>
#include <limits>
#include <stdexcept>
#include <string>
#include <vector>

#include "tile_store.h"

namespace croptop::paint::detail {

inline bool isRgba(PixelFormat f) { return f == PixelFormat::RGBA8 || f == PixelFormat::RGBA16 || f == PixelFormat::RGBA32F; }

inline void readPixel(PixelFormat format, const std::uint8_t* p, float* out) {
    for (std::size_t c = 0; c < 4; ++c) {
        if (format == PixelFormat::RGBA8) {
            out[c] = p[c] / 255.0F;
        } else if (format == PixelFormat::RGBA16) {
            std::uint16_t v = 0;
            std::memcpy(&v, p + c * 2U, 2U);
            out[c] = v / 65535.0F;
        } else {
            float v = 0.0F;
            std::memcpy(&v, p + c * 4U, 4U);
            out[c] = std::isfinite(v) ? v : 0.0F;
        }
    }
}

/** Clamp to 0–1 with NaN → 0 (std::clamp passes NaN through, and a NaN float→int cast is undefined). */
inline float unitClamp(float v) { return v > 0.0F ? (v < 1.0F ? v : 1.0F) : 0.0F; }

inline void writePixel(PixelFormat format, std::uint8_t* p, const float* v) {
    for (std::size_t c = 0; c < 4; ++c) {
        if (format == PixelFormat::RGBA8) {
            // Clamped values are non-negative, so +0.5 and truncation rounds like lround, only faster.
            p[c] = static_cast<std::uint8_t>(unitClamp(v[c]) * 255.0F + 0.5F);
        } else if (format == PixelFormat::RGBA16) {
            const auto q = static_cast<std::uint16_t>(unitClamp(v[c]) * 65535.0F + 0.5F);
            std::memcpy(p + c * 2U, &q, 2U);
        } else {
            // Float colour stays unbounded, but a non-finite value is never a colour: store 0.
            const float value = c == 3 ? unitClamp(v[c]) : (std::isfinite(v[c]) ? v[c] : 0.0F);
            std::memcpy(p + c * 4U, &value, 4U);
        }
    }
}

/** Decodes n pixels (format switch hoisted out of the loop); optionally premultiplies. */
inline void decodeSpan(PixelFormat format, const std::uint8_t* src, float* out, std::size_t n, bool premultiply) {
    switch (format) {
        case PixelFormat::RGBA8:
            for (std::size_t i = 0; i < n * 4U; ++i) out[i] = src[i] * (1.0F / 255.0F);
            break;
        case PixelFormat::RGBA16:
            for (std::size_t i = 0; i < n * 4U; ++i) {
                std::uint16_t v = 0;
                std::memcpy(&v, src + i * 2U, 2U);
                out[i] = v * (1.0F / 65535.0F);
            }
            break;
        default:
            std::memcpy(out, src, n * 16U);
            for (std::size_t i = 0; i < n * 4U; ++i)
                if (!std::isfinite(out[i])) out[i] = 0.0F;
            break;
    }
    if (premultiply) {
        for (std::size_t i = 0; i < n; ++i)
            for (int c = 0; c < 3; ++c) out[i * 4U + static_cast<std::size_t>(c)] *= out[i * 4U + 3U];
    }
}

/** Encodes n straight-alpha pixels (the same rounding as writePixel). */
inline void encodeSpan(PixelFormat format, const float* in, std::uint8_t* dst, std::size_t n) {
    for (std::size_t i = 0; i < n; ++i) writePixel(format, dst + i * (format == PixelFormat::RGBA8 ? 4U : format == PixelFormat::RGBA16 ? 8U : 16U), in + i * 4U);
}

/** Dense RGBA float copy of a rectangle (pixels outside the store read as transparent). */
struct Plane {
    int x0 = 0;
    int y0 = 0;
    int w = 0;
    int h = 0;
    std::vector<float> px;
    float* at(int x, int y) { return px.data() + (static_cast<std::size_t>(y) * w + x) * 4U; }
    const float* at(int x, int y) const { return px.data() + (static_cast<std::size_t>(y) * w + x) * 4U; }
};

inline Plane readPlane(const TileStore& store, int x0, int y0, int w, int h) {
    Plane plane{x0, y0, w, h, std::vector<float>(static_cast<std::size_t>(w) * h * 4U, 0.0F)};
    for (int y = 0; y < h; ++y) {
        const int gy = y0 + y;
        if (gy < 0 || gy >= static_cast<int>(store.height())) continue;
        std::shared_ptr<const TileBuffer> tile;
        int loaded = -1;
        for (int x = 0; x < w; ++x) {
            const int gx = x0 + x;
            if (gx < 0 || gx >= static_cast<int>(store.width())) continue;
            if (gx / static_cast<int>(kTileEdge) != loaded) {
                loaded = gx / static_cast<int>(kTileEdge);
                tile = store.readTile({loaded, gy / static_cast<std::int32_t>(kTileEdge)});
            }
            if (tile == nullptr) continue;
            readPixel(store.format(), tile->data() + (static_cast<std::size_t>(gy % kTileEdge) * kTileEdge + gx % kTileEdge) * store.bytes_per_pixel(),
                      plane.at(x, y));
        }
    }
    return plane;
}

/** Dense A16 coverage (0 outside the store) for a rectangle. */
inline std::vector<std::uint16_t> readCoverage(const TileStore& mask, int x0, int y0, int w, int h) {
    std::vector<std::uint16_t> out(static_cast<std::size_t>(w) * h, 0);
    for (int y = 0; y < h; ++y) {
        const int gy = y0 + y;
        if (gy < 0 || gy >= static_cast<int>(mask.height())) continue;
        std::shared_ptr<const TileBuffer> tile;
        int loaded = -1;
        for (int x = 0; x < w; ++x) {
            const int gx = x0 + x;
            if (gx < 0 || gx >= static_cast<int>(mask.width())) continue;
            if (gx / static_cast<int>(kTileEdge) != loaded) {
                loaded = gx / static_cast<int>(kTileEdge);
                tile = mask.readTile({loaded, gy / static_cast<std::int32_t>(kTileEdge)});
            }
            if (tile != nullptr) {
                std::memcpy(&out[static_cast<std::size_t>(y) * w + x],
                            tile->data() + (static_cast<std::size_t>(gy % kTileEdge) * kTileEdge + gx % kTileEdge) * 2U, 2U);
            }
        }
    }
    return out;
}

/** Bilinear sample of a plane at continuous position (pixel centres at +0.5), clamped to its bounds. */
inline std::array<float, 4> sampleBilinear(const Plane& p, double x, double y) {
    const double fx = std::clamp(x - 0.5, 0.0, static_cast<double>(p.w - 1));
    const double fy = std::clamp(y - 0.5, 0.0, static_cast<double>(p.h - 1));
    const int ix = static_cast<int>(fx), iy = static_cast<int>(fy);
    const int jx = std::min(ix + 1, p.w - 1), jy = std::min(iy + 1, p.h - 1);
    const float tx = static_cast<float>(fx - ix), ty = static_cast<float>(fy - iy);
    std::array<float, 4> out{};
    for (int c = 0; c < 4; ++c) {
        const float top = p.at(ix, iy)[c] * (1 - tx) + p.at(jx, iy)[c] * tx;
        const float bottom = p.at(ix, jy)[c] * (1 - tx) + p.at(jx, jy)[c] * tx;
        out[static_cast<std::size_t>(c)] = top * (1 - ty) + bottom * ty;
    }
    return out;
}

inline bool validate(const TileStore& dst, const TileStore& mask, std::string& error) {
    if (!isRgba(dst.format()) || mask.format() != PixelFormat::A16 || mask.width() != dst.width() || mask.height() != dst.height()) {
        error = "retouching needs an RGBA layer and an A16 mask of the same size";
        return false;
    }
    return true;
}

/** Blends `result` (a plane over the mask's bounds) into `dst` by mask coverage. */
inline bool blendInto(TileStore& dst, const TileStore& mask, const Plane& result, std::string& error) {
    const auto bpp = dst.bytes_per_pixel();
    for (int ty = result.y0 / static_cast<int>(kTileEdge); ty * static_cast<int>(kTileEdge) < result.y0 + result.h; ++ty) {
        for (int tx = result.x0 / static_cast<int>(kTileEdge); tx * static_cast<int>(kTileEdge) < result.x0 + result.w; ++tx) {
            const TileCoordinate coordinate{tx, ty};
            const auto mask_tile = mask.readTile(coordinate);
            if (mask_tile == nullptr) continue;
            const auto existing = dst.readTile(coordinate);
            TileBuffer tile = existing ? *existing : TileBuffer(dst.tile_byte_size(), 0);
            bool changed = false;
            for (int ly = 0; ly < static_cast<int>(kTileEdge); ++ly) {
                const int gy = ty * static_cast<int>(kTileEdge) + ly;
                if (gy < result.y0 || gy >= result.y0 + result.h || gy >= static_cast<int>(dst.height())) continue;
                for (int lx = 0; lx < static_cast<int>(kTileEdge); ++lx) {
                    const int gx = tx * static_cast<int>(kTileEdge) + lx;
                    if (gx < result.x0 || gx >= result.x0 + result.w || gx >= static_cast<int>(dst.width())) continue;
                    std::uint16_t coverage_raw = 0;
                    std::memcpy(&coverage_raw, mask_tile->data() + (static_cast<std::size_t>(ly) * kTileEdge + lx) * 2U, 2U);
                    if (coverage_raw == 0U) continue;
                    const float coverage = coverage_raw / 65535.0F;
                    auto* p = tile.data() + (static_cast<std::size_t>(ly) * kTileEdge + lx) * bpp;
                    std::array<float, 4> original{};
                    readPixel(dst.format(), p, original.data());
                    const float* r = result.at(gx - result.x0, gy - result.y0);
                    std::array<float, 4> mixed{};
                    const float original_alpha = unitClamp(original[3]);
                    const float result_alpha = unitClamp(r[3]);
                    mixed[3] = original_alpha + coverage * (result_alpha - original_alpha);
                    for (int c = 0; c < 3; ++c) {
                        const auto channel = static_cast<std::size_t>(c);
                        const float original_premultiplied = original[channel] * original_alpha;
                        const float result_premultiplied = r[c] * result_alpha;
                        const float premultiplied = original_premultiplied + coverage * (result_premultiplied - original_premultiplied);
                        mixed[channel] = mixed[3] > 1e-8F ? premultiplied / mixed[3]
                                                           : original[channel] + coverage * (r[c] - original[channel]);
                    }
                    writePixel(dst.format(), p, mixed.data());
                    changed = true;
                }
            }
            if (changed && !dst.writeTile(coordinate, tile)) {
                error = "could not store healed pixels";
                return false;
            }
        }
    }
    return true;
}

/** Source plane for the mask bounds (plus `pad`), sampled at p + offset. */
inline Plane sourcePlane(const TileStore& src, const PixelRect& bounds, int pad, double ox, double oy) {
    const auto x0_wide = static_cast<std::int64_t>(bounds.left) - pad;
    const auto y0_wide = static_cast<std::int64_t>(bounds.top) - pad;
    const auto w_wide = static_cast<std::int64_t>(bounds.right) - bounds.left + 2LL * pad;
    const auto h_wide = static_cast<std::int64_t>(bounds.bottom) - bounds.top + 2LL * pad;
    constexpr auto low = std::numeric_limits<int>::min();
    constexpr auto high = std::numeric_limits<int>::max();
    if (pad < 0 || x0_wide < low || x0_wide > high || y0_wide < low || y0_wide > high ||
        w_wide <= 0 || h_wide <= 0 || w_wide > high - 3 || h_wide > high - 3) {
        throw std::invalid_argument("source plane geometry exceeds the supported coordinate range");
    }
    const int x0 = static_cast<int>(x0_wide), y0 = static_cast<int>(y0_wide);
    const int w = static_cast<int>(w_wide), h = static_cast<int>(h_wide);
    Plane out{x0, y0, w, h, std::vector<float>(static_cast<std::size_t>(w) * h * 4U, 0.0F)};
    const double sx = std::floor(static_cast<double>(x0) + ox) - 1.0;
    const double sy = std::floor(static_cast<double>(y0) + oy) - 1.0;
    if (!std::isfinite(sx) || !std::isfinite(sy)) throw std::invalid_argument("source plane offset must be finite");
    // Completely out-of-canvas windows are transparent, including finite offsets beyond int range.
    if (sx + w + 3 <= 0 || sy + h + 3 <= 0 || sx >= src.width() || sy >= src.height()) return out;
    if (sx < low || sy < low || sx > static_cast<double>(high) - w - 3 || sy > static_cast<double>(high) - h - 3) {
        throw std::invalid_argument("source sampling window exceeds the supported coordinate range");
    }
    const int sx0 = static_cast<int>(sx), sy0 = static_cast<int>(sy);
    const auto window = readPlane(src, sx0, sy0, w + 3, h + 3);
    const bool integral = ox == std::floor(ox) && oy == std::floor(oy);
    for (int y = 0; y < h; ++y) {
        for (int x = 0; x < w; ++x) {
            if (integral) {
                std::copy_n(window.at(x + 1, y + 1), 4, out.at(x, y));
            } else {
                const auto v = sampleBilinear(window, x0 + x + 0.5 + ox - sx0, y0 + y + 0.5 + oy - sy0);
                std::copy(v.begin(), v.end(), out.at(x, y));
            }
        }
    }
    return out;
}

/** Writes every pixel of `plane` (clipped to the store) into `store`. */
inline bool writePlane(TileStore& store, const Plane& plane) {
    const auto bpp = store.bytes_per_pixel();
    const int x_end = std::min<int>(plane.x0 + plane.w, static_cast<int>(store.width()));
    const int y_end = std::min<int>(plane.y0 + plane.h, static_cast<int>(store.height()));
    const int x_begin = std::max(plane.x0, 0), y_begin = std::max(plane.y0, 0);
    if (x_begin >= x_end || y_begin >= y_end) return true;
    const int edge = static_cast<int>(kTileEdge);
    for (int ty = y_begin / edge; ty * edge < y_end; ++ty) {
        for (int tx = x_begin / edge; tx * edge < x_end; ++tx) {
            const auto existing = store.readTile({tx, ty});
            TileBuffer tile = existing ? *existing : TileBuffer(store.tile_byte_size(), 0);
            for (int y = std::max(y_begin, ty * edge); y < std::min(y_end, (ty + 1) * edge); ++y)
                for (int x = std::max(x_begin, tx * edge); x < std::min(x_end, (tx + 1) * edge); ++x)
                    writePixel(store.format(), tile.data() + (static_cast<std::size_t>(y - ty * edge) * kTileEdge + static_cast<std::size_t>(x - tx * edge)) * bpp,
                               plane.at(x - plane.x0, y - plane.y0));
            if (!store.writeTile({tx, ty}, tile)) return false;
        }
    }
    return true;
}

}  // namespace croptop::paint::detail
