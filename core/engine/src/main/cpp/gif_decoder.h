#pragma once

// Bounded first-frame GIF decoder (no third-party dependency).

#include <cstdint>
#include <span>

#include "image_decoder.h"
#include "webp_codec.h"  // kStillImageMaxWorkingBytes

namespace croptop {

class TileStore;

/**
 * Decodes the first frame of a GIF87a/GIF89a stream to RGBA8 scanlines at logical-screen size.
 * The frame is placed at its offset on a transparent canvas; the GCE transparent index, local and
 * global palettes and interlacing are honored. Working memory is one index byte per frame pixel
 * (capped by `max_working_bytes`) plus one RGBA row. Truncated data, out-of-palette indices and
 * bad LZW codes are rejected; rows reach the sink only after the frame decoded successfully.
 */
DecodeResult decodeGif(std::span<const std::uint8_t> encoded, const RgbaScanlineSink& sink,
                       std::uint32_t max_dimension = 32768, std::uint64_t max_pixels = 500'000'000,
                       std::uint64_t max_working_bytes = kStillImageMaxWorkingBytes);

/**
 * Decodes the first GIF frame into an RGBA8 store of logical-screen size. The screen size is checked
 * against the store before decoding, so a mismatched or invalid input never mutates the destination.
 */
DecodeResult decodeGifToTiles(std::span<const std::uint8_t> encoded, TileStore& destination,
                              std::uint32_t max_dimension = 32768, std::uint64_t max_pixels = 500'000'000,
                              std::uint64_t max_working_bytes = kStillImageMaxWorkingBytes);

}  // namespace croptop
