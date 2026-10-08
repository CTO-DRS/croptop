#pragma once

// LibRaw front end: opens a camera raw from memory (about 1000 models plus DNG),
// exposes the sensor mosaic in the shape `raw_demosaic.h` consumes, the as-shot white balance and colour
// matrix, camera/lens/exposure metadata, and the embedded JPEG previews. LibRaw is confined to raw_decode.cpp;
// callers never see its types. One LibRaw instance lives only for the duration of a call.
//
// Limits: only single-plane colour-filter-array rasters (Bayer 2×2 and Fujifilm X-Trans 6×6) decode; Foveon,
// linear/demosaiced DNGs, four-colour and floating-point rasters fail with an explicit error.

#include <array>
#include <cstdint>
#include <optional>
#include <span>
#include <string>
#include <vector>

#include "raw_demosaic.h"

namespace croptop::raw {

struct RawLimits final {
    std::uint64_t max_pixels = 1ULL << 28;  // 268 MP: no stills camera is near this.
    std::uint64_t max_file_bytes = 1ULL << 30;
};

/** Camera and shot metadata as LibRaw parsed it; empty strings / zeros when the file does not say. */
struct RawMetadata final {
    std::string make;              // e.g. "Canon"
    std::string model;             // e.g. "EOS R5"
    std::string normalized_make;
    std::string normalized_model;
    std::string lens;
    std::string software;
    float iso = 0.0F;
    float shutter_seconds = 0.0F;
    float aperture = 0.0F;
    float focal_length_mm = 0.0F;
    std::int64_t timestamp = 0;    // Unix seconds, 0 when unknown
    int orientation = 1;           // EXIF orientation 1–8
    bool is_dng = false;
    int raw_count = 1;             // images in the file (LibRaw's raw_count)
};

struct RawProbe final {
    RawMetadata metadata;
    std::uint32_t width = 0;       // active (visible) sensor area
    std::uint32_t height = 0;
    std::uint32_t raw_width = 0;   // full raster including masked borders
    std::uint32_t raw_height = 0;
    bool xtrans = false;
    bool linear = false;           // demosaiced camera RGB (Canon sRAW/mRAW, linear DNG): no CFA, decoded to `linear_rgb`
    bool decodable = false;        // one of the two paths below supports this file
    std::string unsupported_reason;
};

/** Sensor data in the shape `develop()` takes. `photosites` is tightly packed active area, width × height. */
struct RawImage final {
    RawMetadata metadata;
    std::uint32_t width = 0;
    std::uint32_t height = 0;
    std::vector<std::uint16_t> photosites;
    /** Set instead of `photosites` for demosaiced raws: width × height × 3 camera-RGB samples (black not yet subtracted). */
    std::vector<std::uint16_t> linear_rgb;
    bool isLinear() const { return !linear_rgb.empty(); }
    CfaPattern pattern;
    std::array<float, 3> black{};          // per colour (R, G, B), raw units
    float white = 65535.0F;                // saturation level, raw units
    std::array<float, 3> as_shot_wb{1.0F, 1.0F, 1.0F};  // camera multipliers (R, G, B), green normalised to 1
    /** XYZ (D65) → camera RGB, row-major; the input of `cameraToOutput()`. nullopt when the file carries no matrix. */
    std::optional<std::array<double, 9>> xyz_to_camera;
    std::vector<std::string> warnings;

    /**
     * Linear-output RGBA of a demosaiced raw: black/white normalisation, `spec.white_balance`, `spec.camera_to_output`,
     * clipped to 0-1 (RGBA16, or RGBA32F unbounded). The counterpart of `develop()` for `isLinear()` images.
     */
    std::optional<TileStore> developLinear(const DevelopSpec& spec, std::string& error) const;

    /** A `CfaImage` viewing this object's photosites; valid while the object lives. */
    CfaImage cfa() const;
    /** A `DevelopSpec` with this image's as-shot white balance and camera matrix into `primaries`. */
    DevelopSpec developSpec(OutputPrimaries primaries = OutputPrimaries::LinearSRGB) const;
};

struct EmbeddedPreview final {  // always JPEG: other embedded formats (Canon CR3 HEVC) are reported as unavailable
    std::vector<std::uint8_t> jpeg;
    std::uint32_t width = 0;
    std::uint32_t height = 0;
};

/** Reads only headers and metadata. nullopt with `error` when LibRaw does not recognise the file. */
std::optional<RawProbe> probe(std::span<const std::uint8_t> file, std::string& error, const RawLimits& limits = {});

/** Full decode of the first image in the file. Fails with `error` for unsupported rasters or limit violations. */
std::optional<RawImage> decode(std::span<const std::uint8_t> file, std::string& error, const RawLimits& limits = {});

/**
 * Largest embedded JPEG preview, without decoding the raster (the fast path for library thumbnails). A preview
 * whose longer edge is below `min_long_edge` is skipped; pass 0 to accept any. nullopt when none qualifies.
 */
std::optional<EmbeddedPreview> extractPreview(std::span<const std::uint8_t> file, std::string& error,
                                              std::uint32_t min_long_edge = 0, const RawLimits& limits = {});

}  // namespace croptop::raw
