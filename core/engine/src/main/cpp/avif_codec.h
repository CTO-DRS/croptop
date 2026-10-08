#pragma once

// AVIF import/export on libavif with libaom (BSD-2-Clause). libavif is confined to avif_codec.cpp.
//   encode: RGBA8/16/32F store → AVIF at 8, 10 or 12 bits per channel (values are sRGB-encoded; float is clamped to 0–1),
//           lossy by quality 0–100 (100 = lossless with 4:4:4 identity matrix), optional ICC profile, alpha kept as an AV1 alpha plane.
//   decode: first image of the file → RGBA8 for 8-bit files, RGBA16 for 10/12-bit files, with the embedded ICC profile.

#include <cstdint>
#include <optional>
#include <span>
#include <string>
#include <vector>

#include "tile_store.h"

namespace croptop::avif {

struct EncodeOptions final {
    int quality = 80;          // 0–100
    int speed = 6;             // 0 (slowest, smallest) – 10
    int bit_depth = 8;         // 8, 10 or 12
    unsigned worker_threads = 0;
    std::vector<std::uint8_t> icc_profile;
};

struct Decoded final {
    TileStore pixels;
    std::vector<std::uint8_t> icc_profile;
    int source_bit_depth = 8;
    bool has_alpha = false;
};

bool isAvif(std::span<const std::uint8_t> bytes);  // ISO-BMFF "ftyp" with an avif/avis brand

std::optional<std::vector<std::uint8_t>> encode(const TileStore& pixels, const EncodeOptions& options, std::string& error);

/** Header-only: dimensions and bit depth. */
struct Info final { std::uint32_t width = 0, height = 0; int bit_depth = 8; bool has_alpha = false; };
std::optional<Info> probe(std::span<const std::uint8_t> bytes, std::string& error);

std::optional<Decoded> decode(std::span<const std::uint8_t> bytes, std::string& error, std::uint64_t max_pixels = 1ULL << 28);

}  // namespace croptop::avif
