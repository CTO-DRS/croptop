#pragma once

// TIFF read/write, own codec (no libtiff). Input is untrusted and bounded.
// Read: classic TIFF and BigTIFF, either byte order; strips or tiles, chunky or planar; bilevel and 4/8-bit
// palette, 8/16-bit integer and 32-bit float samples; gray, RGB, CMYK (converted naively, warned) with an
// optional alpha (associated alpha is un-premultiplied); compression none, LZW, Deflate (8/32946) and
// PackBits, with horizontal (2) or floating-point (3) predictors; the ICC profile (tag 34675); EXIF-style
// Orientation (274) is applied. Only the first page is decoded (`pages` counts them); a layered Photoshop TIFF
// opens flattened, from its composite. Output is RGBA8 (1–8-bit input), RGBA16 or RGBA32F (float input).
// Write: RGBA8/16/32F as RGB + unassociated alpha, little-endian, Deflate with predictor (or none), in strips
// of about 1 MB, plus the ICC profile.

#include <cstdint>
#include <optional>
#include <span>
#include <string>
#include <vector>

#include "tile_store.h"

namespace croptop::tiff {

struct Image final {
    TileStore pixels;
    std::vector<std::uint8_t> icc_profile;
    std::uint32_t pages = 1;
    std::vector<std::string> warnings;
};

struct Limits final {
    std::uint64_t max_pixels = 1ULL << 28;           // 268 MP
    std::uint64_t max_working_bytes = 1ULL << 30;   // decompressed data held at once
};

std::optional<Image> decode(std::span<const std::uint8_t> file, std::string& error, const Limits& limits = {});

struct EncodeOptions final {
    bool deflate = true;
    std::vector<std::uint8_t> icc_profile;
};

std::optional<std::vector<std::uint8_t>> encode(const TileStore& pixels, std::string& error, const EncodeOptions& options = {});

}  // namespace croptop::tiff
