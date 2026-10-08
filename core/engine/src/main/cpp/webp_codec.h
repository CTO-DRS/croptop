#pragma once

// Still-image WebP decode/encode on top of the pinned libwebp.

#include <cstdint>
#include <array>
#include <span>

#include "image_decoder.h"

namespace croptop {

class TileStore;

/**
 * v1 decode ceiling for one still WebP/GIF working frame (the RGBA8 decode buffer or the GIF index
 * plane). Inputs that would need more are rejected before anything is allocated or written.
 */
inline constexpr std::uint64_t kStillImageMaxWorkingBytes = 256ULL * 1024ULL * 1024ULL;

/** Default whole-encode budget for WebP export, checked against estimateWebpEncodeBytes(). */
inline constexpr std::uint64_t kWebpEncodeMaxWorkingBytes = 1024ULL * 1024ULL * 1024ULL;

/**
 * Conservative peak-memory estimate for one encodeWebpToSink() call: the ARGB source picture,
 * libwebp's internal buffers (lossy VP8 buffers tokens for the whole image; lossless keeps several
 * ARGB planes), the growing encoded output, the WebPMux copy and the metadata. Per-pixel factors
 * come from peak-RSS growth measured on libwebp 1.6.0 for 6000×4000 (x86_64, 2026-09-25): lossy
 * 7.2 B/px on photo-like content and 25.0 B/px on noise; lossless 29.4 and 38.0 B/px. The estimate
 * uses 30 and 44 B/px so worst-case content stays inside the budget.
 */
std::uint64_t estimateWebpEncodeBytes(std::uint32_t width, std::uint32_t height, bool lossless,
                                      std::uint64_t metadata_bytes = 0);

struct WebpEncodeOptions final {
    /** 0..100; for lossless it trades encoder effort for size. */
    float quality = 90.0f;
    bool lossless = false;
    /** 0 (fast) .. 6 (slowest, smallest). */
    int method = 4;
    std::span<const std::uint8_t> icc_profile;
    std::span<const std::uint8_t> exif_tiff;
    std::array<float, 4> canvas_background{};
    /** Budget for the whole encode, compared with estimateWebpEncodeBytes() before allocating. */
    std::uint64_t max_working_bytes = kWebpEncodeMaxWorkingBytes;
};

/**
 * Decodes a still WebP (lossy, lossless, with or without alpha) to unpremultiplied RGBA8 scanlines.
 * libwebp has no row-streaming output, so decoding holds one full RGBA8 frame; its size is checked
 * against the limits and `max_working_bytes` before allocation. Animated WebP is rejected.
 * Rows reach the sink only after the whole bitstream decoded successfully.
 */
DecodeResult decodeWebp(std::span<const std::uint8_t> encoded, const RgbaScanlineSink& sink,
                        std::uint32_t max_dimension = 16383, std::uint64_t max_pixels = 500'000'000,
                        std::uint64_t max_working_bytes = kStillImageMaxWorkingBytes);

/**
 * Decodes a still WebP into an RGBA8 store, applying EXIF orientation 1..8 (5..8 swap the store's
 * width and height). The header's size is checked against the store before decoding, so a
 * mismatched or over-limit input never mutates the destination.
 */
DecodeResult decodeWebpToTiles(std::span<const std::uint8_t> encoded, TileStore& destination,
                               std::uint32_t max_dimension = 16383, std::uint64_t max_pixels = 500'000'000,
                               std::uint64_t max_working_bytes = kStillImageMaxWorkingBytes,
                               std::uint8_t exif_orientation = 1);

/**
 * Encodes an RGBA8 or RGBA16 store (16-bit is rounded to 8-bit) to WebP. Tiles are copied
 * straight into libwebp's ARGB picture (no intermediate RGBA frame). Inputs whose
 * estimateWebpEncodeBytes() exceeds `options.max_working_bytes` are rejected before any allocation.
 * ICC and EXIF are added with WebPMux. WebP's limit is 16383 px per side.
 */
EncodeResult encodeWebpToSink(const TileStore& source, const EncodedBytesSink& sink,
                              const WebpEncodeOptions& options = {});

}  // namespace croptop
