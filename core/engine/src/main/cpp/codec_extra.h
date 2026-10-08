#pragma once

// TIFF and PSD/PSB opening for the engine's import path, next to the JPEG/PNG/BMP/WebP/GIF
// dispatch (`nativeDecodeToTileStore`). Same shape as those decoders: probe the header for the document's
// size and depth, let the caller create the destination store, then decode into it.
//   TIFF: the first image (baseline + LZW/Deflate/PackBits via tiff::decode); 8/16-bit and float samples
//         keep their depth when the destination does (else they are converted).
//   PSD/PSB: flattened, from the stored composite, or rebuilt with psd::compose when the file was saved
//            without "Maximize compatibility". Layer import is a separate, document-model step.
// Export: TIFF at the store's depth and flattened PSD/PSB, both carrying an ICC profile when given.

#include <cstdint>
#include <optional>
#include <span>
#include <string>
#include <vector>

#include "image_decoder.h"
#include "psd_reader.h"
#include "tile_store.h"

namespace croptop::codec {

enum class ExtraFormat : std::uint8_t { Unknown, Tiff, Psd };

/** Content signature: "II*\0" / "MM\0*" (and BigTIFF's 43) → Tiff; "8BPS" → Psd. */
ExtraFormat detect(std::span<const std::uint8_t> bytes);

struct Probe final {
    ExtraFormat format = ExtraFormat::Unknown;
    std::uint32_t width = 0, height = 0;
    PixelFormat pixel_format = PixelFormat::RGBA8;  // the depth to create the destination store with
};

/** Header-only probe (no pixel decode). nullopt with `error` for malformed or unsupported headers. */
std::optional<Probe> probe(std::span<const std::uint8_t> bytes, std::string& error);

/**
 * Decodes into `destination` (RGBA, exactly the probed size). The destination is only written on success.
 * `warnings` (optional) receives non-fatal notes, e.g. "composite rebuilt from layers".
 */
DecodeResult decodeToTiles(std::span<const std::uint8_t> bytes, TileStore& destination, std::vector<std::string>* warnings = nullptr,
                           std::vector<std::uint8_t>* icc_profile = nullptr);

/** TIFF export of an RGBA store at its depth (8, 16 or float), Deflate-compressed unless `deflate` is false. */
std::optional<std::vector<std::uint8_t>> encodeTiff(const TileStore& pixels, std::span<const std::uint8_t> icc_profile, bool deflate, std::string& error);

/**
 * Flattened PSD export: one full-canvas layer plus the merged image (so every reader shows it), at the store's
 * depth; PSB automatically when a side exceeds 30000 px. Layered PSD save is a document-model step.
 */
std::optional<std::vector<std::uint8_t>> encodePsd(const TileStore& pixels, std::span<const std::uint8_t> icc_profile, std::string& error);

/**
 * Layered PSD/PSB export: `layers` in file order, bottom to top, groups as GroupDivider ... children ... GroupFolder
 * records exactly as psd::Document expects (see psd_reader.h). Every pixel layer is a canvas-sized RGBA store at the composite's
 * depth, masks are canvas-sized A16, and the caller renders `composite` (the merged image every reader shows). Structural
 * mistakes (unbalanced groups, mismatched sizes/formats, non-RGBA data) fail before anything is encoded.
 */
std::optional<std::vector<std::uint8_t>> encodePsdLayered(std::vector<psd::Layer> layers, const TileStore& composite,
                                                          std::span<const std::uint8_t> icc_profile, std::string& error);

}  // namespace croptop::codec
