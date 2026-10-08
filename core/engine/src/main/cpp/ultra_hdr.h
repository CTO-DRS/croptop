#pragma once

// Ultra HDR JPEG export on libultrahdr (Apache-2.0), confined to ultra_hdr.cpp.
// The input is the document's scene-linear image: an RGBA32F store in linear BT.709/sRGB primaries where 1.0 is SDR white (203 nits)
// and values above 1.0 are the HDR headroom. The SDR base image is either supplied (`sdr`, RGBA8 sRGB, so the export matches what
// the editor shows) or derived by libultrahdr's tone mapper. Output is a JPEG with an ISO 21496-1 + XMP gain map that SDR viewers
// show as a normal JPEG. Alpha is dropped (JPEG has none).

#include <cstdint>
#include <optional>
#include <span>
#include <string>
#include <vector>

#include "tile_store.h"

namespace croptop::ultrahdr {

struct EncodeOptions final {
    int base_quality = 90;          // 1–100, SDR JPEG
    int gain_map_quality = 85;      // 1–100
    bool multi_channel_gain_map = false;  // per-channel gain (bigger, more accurate colour)
    float target_peak_nits = 1000.0F;     // 203–10000: display the gain map is tuned for
    std::vector<std::uint8_t> exif;       // optional raw EXIF block (with the "Exif\0\0" header)
};

/** True for a JPEG that carries an Ultra HDR gain map. */
bool isUltraHdr(std::span<const std::uint8_t> bytes);

/** Both sides must be at least 8×8 and equal in size when `sdr` is given. */
std::optional<std::vector<std::uint8_t>> encode(const TileStore& linear_hdr, const TileStore* sdr, const EncodeOptions& options,
                                                std::string& error);

/**
 * Decodes an Ultra HDR JPEG to scene-linear RGBA32F (linear BT.709 primaries, 1.0 = SDR white, alpha 1) with the gain map fully
 * applied. `max_pixels` bounds the decoded size. nullopt with `error` when the file is not Ultra HDR or is malformed.
 */
std::optional<TileStore> decode(std::span<const std::uint8_t> bytes, std::string& error, std::uint64_t max_pixels = 1ULL << 27);

}  // namespace croptop::ultrahdr
