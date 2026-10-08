#pragma once

#include <array>
#include <cstddef>
#include <cstdint>
#include <functional>
#include <span>
#include <string>
#include <vector>

namespace croptop {

class TileStore;

struct DecodedRaster final {
    std::uint32_t width = 0;
    std::uint32_t height = 0;
    std::string format;
    std::uint8_t bit_depth = 8;
};

using RgbaScanlineSink = std::function<bool(std::uint32_t y, std::span<const std::uint8_t> rgba)>;
using EncodedBytesSink = std::function<bool(std::span<const std::uint8_t> bytes)>;

struct DecodeResult final {
    bool success = false;
    DecodedRaster raster;
    std::string error;
};

struct EncodeResult final {
    bool success = false;
    std::vector<std::uint8_t> encoded;
    std::string error;
};

struct PngEncodeMetadata final {
    std::span<const std::uint8_t> icc_profile;
    std::span<const std::uint8_t> exif_tiff;
    /** Virtual canvas fill, non-premultiplied sRGB; transparent preserves PNG alpha. */
    std::array<float, 4> canvas_background{};
};

struct JpegEncodeMetadata final {
    std::span<const std::uint8_t> icc_profile;
    std::span<const std::uint8_t> exif_tiff;
    /** Virtual canvas fill, non-premultiplied sRGB; JPEG defaults to white. */
    std::array<float, 4> canvas_background{1.0F, 1.0F, 1.0F, 1.0F};
};

/** Decodes bounded image input and emits tightly packed RGBA scanlines. BMP RLE8 rows are emitted bottom-up. */
DecodeResult decodeJpeg(std::span<const std::uint8_t> encoded, const RgbaScanlineSink& sink,
                        std::uint32_t max_dimension = 32768, std::uint64_t max_pixels = 500'000'000);
DecodeResult decodePng(std::span<const std::uint8_t> encoded, const RgbaScanlineSink& sink,
                       std::uint32_t max_dimension = 32768, std::uint64_t max_pixels = 500'000'000);
DecodeResult decodeBmp(std::span<const std::uint8_t> encoded, const RgbaScanlineSink& sink,
                       std::uint32_t max_dimension = 32768, std::uint64_t max_pixels = 500'000'000);

/** Decode directly into 256² sparse tiles; PNG16 requires an RGBA16 destination store. */
DecodeResult decodeJpegToTiles(std::span<const std::uint8_t> encoded, TileStore& destination,
                               std::uint32_t max_dimension = 32768, std::uint64_t max_pixels = 500'000'000,
                               std::uint8_t exif_orientation = 1);
DecodeResult decodePngToTiles(std::span<const std::uint8_t> encoded, TileStore& destination,
                              std::uint32_t max_dimension = 32768, std::uint64_t max_pixels = 500'000'000,
                              std::uint8_t exif_orientation = 1);
DecodeResult decodeBmpToTiles(std::span<const std::uint8_t> encoded, TileStore& destination,
                              std::uint32_t max_dimension = 32768, std::uint64_t max_pixels = 500'000'000);

/** Encodes sparse RGBA8/RGBA16 tile rows to PNG without allocating a full-frame pixel buffer. */
EncodeResult encodePngFromTiles(const TileStore& source, int compression_level = 6,
                                const PngEncodeMetadata& metadata = {});
/** Streams encoded PNG chunks to a caller-owned sink; pixel memory stays one scanline. */
EncodeResult encodePngToSink(const TileStore& source, const EncodedBytesSink& sink, int compression_level = 6,
                             const PngEncodeMetadata& metadata = {});
/** Encodes RGBA8/RGBA16 tiles as opaque JPEG, compositing transparency over white. */
EncodeResult encodeJpegToSink(const TileStore& source, const EncodedBytesSink& sink, int quality = 92,
                              const JpegEncodeMetadata& metadata = {});

}  // namespace croptop
