#pragma once

// Camera raw → editor layer ("flattened layer" path): LibRaw decode, demosaic, the Develop
// pipeline with the caller's settings, display encoding and EXIF orientation, in one call. The result is what a
// document opens as its first pixel layer: sRGB-encoded RGBA at 16 or 8 bits, upright, one raster.
// The Develop pipeline's own output is scene-linear, so the sRGB transfer curve is applied here; this file owns
// nothing else about colour (the working-space tag is the caller's).

#include <cstdint>
#include <functional>
#include <optional>
#include <array>
#include <memory>
#include <span>
#include <string>
#include <string_view>
#include <vector>

#include "develop_pipeline.h"
#include "develop_recipe_bridge.h"
#include "lens_profile.h"
#include "raw_decode.h"
#include "tile_store.h"

namespace croptop::raw {

struct OpenOptions final {
    develop::pipeline::Settings settings;      // Develop adjustments applied before encoding (defaults: neutral)
    OutputPrimaries primaries = OutputPrimaries::LinearSRGB;
    bool sixteen_bit = true;                   // RGBA16 (default) or RGBA8 output
    bool apply_orientation = true;             // rotate/flip so the image is upright
    /**
     * Automatic lens correction (distortion, chromatic aberration, vignetting) from this lensfun-format database, applied to
     * the demosaiced linear image before Develop. Null = off. A lens missing from the database only adds a warning.
     */
    std::shared_ptr<const lens::Database> lens_database;
    /** Camera white-balance multipliers (R, G, B) replacing the file's as-shot ones, e.g. from an XMP sidecar. */
    std::optional<std::array<float, 3>> camera_white_balance;
    /**
     * Demosaic method override. Leave empty for the quality default (RCD on Bayer, the directional method on X-Trans). Pick
     * `Demosaic::Bilinear` / `Demosaic::XTransFast` for a quick first view (X-Trans directional is about 6× a Bayer demosaic).
     */
    std::optional<Demosaic> demosaic;
    unsigned worker_threads = 0;
    std::function<bool(float)> progress;       // 0–1 across all stages; return false to cancel
};

struct OpenedRaw final {
    TileStore pixels;                          // sRGB-encoded, opaque RGBA
    RawMetadata metadata;
    std::vector<std::string> warnings;
    std::uint32_t sensor_width = 0;            // before orientation
    std::uint32_t sensor_height = 0;

    explicit OpenedRaw(TileStore store) : pixels(std::move(store)) {}
};

/** sRGB opto-electronic transfer function of a linear value (0–1 clamped). Exposed for tests and previews. */
float srgbEncode(float linear);

/** Applies an EXIF orientation (1–8) so the stored image displays upright; other values leave it unchanged. */
TileStore orient(const TileStore& source, int exif_orientation);

/**
 * Options carrying the develop settings of an XMP sidecar or preset (Adobe Camera Raw / Lightroom `crs:` properties or
 * CropTop's own namespace). Controls the sidecar does not set keep their defaults; see develop_recipe_bridge.h for
 * the settings a sidecar can carry that the pipeline has no field for. nullopt when `xmp` holds no XMP document.
 */
std::optional<OpenOptions> optionsFromXmp(std::string_view xmp, std::string& error);

/** The XMP sidecar for `options`: `crs:` properties (read by Lightroom/ACR/Bridge) plus exact CropTop values. */
std::string sidecarXmp(const OpenOptions& options, std::string_view creator_tool = "CropTop");

/**
 * An opened camera raw kept as an upright scene-linear image, so Develop sliders can re-render a bounded preview without decoding
 * or demosaicing again. `render` runs the pipeline in preview mode (white balance → tone → colour → crop → local masks → detail)
 * and returns sRGB-encoded 8-bit RGBA. One session serves one thread at a time.
 */
class DevelopSession final {
public:
    /** Decodes and demosaics `file` (lens correction and orientation applied from `options`); nullopt-like null on failure. */
    static std::unique_ptr<DevelopSession> open(std::span<const std::uint8_t> file, const OpenOptions& options, std::string& error,
                                                const RawLimits& limits = {});

    /** Upright full-resolution size (before any crop). */
    std::uint32_t width() const { return linear_.width(); }
    std::uint32_t height() const { return linear_.height(); }
    const RawMetadata& metadata() const { return metadata_; }
    const std::vector<std::string>& openWarnings() const { return warnings_; }

    /**
     * Renders `settings` at most `max_dimension` px on the longer edge (the cropped frame when a crop is set). `warnings`, if given,
     * receives the pipeline's notes. nullopt with `error` on invalid settings or cancellation.
     */
    std::optional<TileStore> render(const develop::pipeline::Settings& settings, std::uint32_t max_dimension, unsigned worker_threads,
                                    std::string& error, std::vector<std::string>* warnings = nullptr);

    /**
     * The coverage of local mask `settings.masks[index]` as a red RGBA8 overlay (straight alpha, 60% at full coverage) over the same
     * frame `render` returns for the same bound, so it can be drawn on top of the preview. Only the crop in `settings` is used.
     */
    std::optional<TileStore> maskOverlay(const develop::pipeline::Settings& settings, std::size_t index, std::uint32_t max_dimension,
                                         unsigned worker_threads, std::string& error);

private:
    DevelopSession(TileStore linear, RawMetadata metadata, std::vector<std::string> warnings, develop::pipeline::LensCorrector lens_correction);

    TileStore linear_;
    RawMetadata metadata_;
    std::vector<std::string> warnings_;
    develop::pipeline::LensCorrector lens_correction_;
    develop::pipeline::PreviewProxyCache cache_;
};

/** nullopt with `error` on unsupported files, limit violations, pipeline errors or cancellation. */
std::optional<OpenedRaw> openFlattened(std::span<const std::uint8_t> file, const OpenOptions& options, std::string& error,
                                       const RawLimits& limits = {});

}  // namespace croptop::raw
