#pragma once

// CPU resampling for the transform engine: Image Size, Canvas Size, flip,
// 90° rotation, and affine/perspective warp (Free Transform, Distort/Perspective, Rotate Canvas by any
// angle) on TileStores.
//
// Filtering happens in linear light with premultiplied alpha: RGBA8 and RGBA16 are sRGB-decoded first
// and re-encoded after; RGBA32F is taken as linear; A8/A16 masks are filtered directly. When shrinking,
// the kernel is stretched by the scale factor so downscales are band-limited (no aliasing). Edges clamp
// (replicate). Results are clamped to the format's range; transparent tiles stay sparse.

#include <array>
#include <cstdint>
#include <optional>
#include <string>

#include "tile_store.h"

namespace croptop::resample {

enum class Filter : std::uint8_t { Nearest, Bilinear, Bicubic, Lanczos3 };

/** Scales `source` to width × height. */
std::optional<TileStore> resize(const TileStore& source, std::uint32_t width, std::uint32_t height, Filter filter,
                                std::string& error, unsigned worker_threads = 0);

/** Mirrors left-right (`horizontal`) or top-bottom. Exact. */
TileStore flip(const TileStore& source, bool horizontal);

/** Rotates clockwise by quarter_turns × 90° (any integer; negative is counter-clockwise). Exact. */
TileStore rotate90(const TileStore& source, int quarter_turns);

/**
 * Canvas Size: a width × height store with `source` placed so its top-left lands at (dx, dy) (may be
 * negative or beyond the new canvas; the overlap is copied exactly, the rest is transparent).
 */
std::optional<TileStore> canvasSize(const TileStore& source, std::uint32_t width, std::uint32_t height, std::int64_t dx,
                                    std::int64_t dy, std::string& error);

/**
 * Maps `source` through `matrix` (3×3 row-major, source → destination document points, like core:model
 * Matrix3) into a width × height store. Each output pixel center is mapped back through the inverse;
 * the filter footprint widens by the local scale when minifying (capped at 16×). Pixels that map outside
 * the source are transparent, so edges come out anti-aliased. Rejects singular matrices and projective
 * maps that send any source corner through the horizon (w <= 0).
 */
std::optional<TileStore> warp(const TileStore& source, const std::array<double, 9>& matrix, std::uint32_t width,
                              std::uint32_t height, Filter filter, std::string& error, unsigned worker_threads = 0);

}  // namespace croptop::resample
