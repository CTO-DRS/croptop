#pragma once

// CPU orchestration/reference for the v1 core:model DevelopSettings.
// The field names/defaults and numeric units mirror core/model/Develop.kt. The GPU preview path
// should match this ordered CPU reference: white balance → tone → color → detail/effects → output.

#include <array>
#include <atomic>
#include <chrono>
#include <cstdint>
#include <functional>
#include <memory>
#include <mutex>
#include <optional>
#include <string>
#include <utility>
#include <vector>

#include "gpu_compute.h"
#include "tile_store.h"

namespace croptop::develop::pipeline {

inline constexpr std::uint32_t kSettingsVersion = 1;

enum class Profile : std::uint8_t { Neutral, Vivid, Portrait, Landscape, Monochrome };
enum class WhiteBalancePreset : std::uint8_t { AsShot, Auto, Daylight, Cloudy, Shade, Tungsten, Fluorescent, Flash, Custom };

struct WhiteBalance final {
    WhiteBalancePreset preset = WhiteBalancePreset::AsShot;
    float temperature = 5500.0F; // Kelvin, 2000–50000; used by Custom
    float tint = 0.0F;            // −150…150
};

struct BasicTone final {
    float exposure = 0, contrast = 0, highlights = 0, shadows = 0, whites = 0, blacks = 0;
    float texture = 0, clarity = 0, dehaze = 0, vibrance = 0, saturation = 0;
};

struct Curve final {
    std::vector<std::pair<float, float>> points{{0, 0}, {1, 1}};
};
struct HslShift final { float hue = 0, saturation = 0, luminance = 0; };
struct GradeWheel final { float hue = 0, saturation = 0, luminance = 0; };
struct ColorGrading final {
    GradeWheel shadows, midtones, highlights, global;
    float blending = 50, balance = 0;
};
struct Detail final {
    float sharpen_amount = 40, sharpen_radius = 1, sharpen_detail = 25, sharpen_masking = 0;
    float luminance_noise = 0, color_noise = 25;
};
struct Effects final { float vignette_amount = 0, vignette_midpoint = 50, grain_amount = 0, grain_size = 25; };
struct Optics final {
    bool lens_profile = true, remove_chromatic_aberration = true;
    float vignetting = 0, distortion = 0;
    /** Manual defringe (0–20 each): desaturates purple/green fringes next to high-contrast edges. */
    float defringe_purple = 0, defringe_green = 0;
};
/** Camera-calibration style primary shifts (hue/saturation per primary, shadow tint), all −100…100. */
struct Calibration final {
    float shadow_tint = 0;
    float red_hue = 0, red_saturation = 0, green_hue = 0, green_saturation = 0, blue_hue = 0, blue_saturation = 0;
    bool isIdentity() const {
        return shadow_tint == 0 && red_hue == 0 && red_saturation == 0 && green_hue == 0 && green_saturation == 0 && blue_hue == 0 && blue_saturation == 0;
    }
};
/** Automatic perspective/level correction run on the colour result before the crop (the crop frame is relative to its output). */
enum class Upright : std::uint8_t { Off, Level, Vertical, Full, Auto, Guided, Manual };

/** A user-drawn guide for Guided upright: a line that should become vertical or horizontal; fractions of the frame. */
struct UprightGuide final { float x1 = 0, y1 = 0, x2 = 0, y2 = 0; };
/** Manual upright: vertical/horizontal tilt (−100…100), rotate (°), aspect (−100…100), scale (%), x/y offset (−100…100). */
struct UprightManual final { float vertical = 0, horizontal = 0, rotate = 0, aspect = 0, scale = 100, x_offset = 0, y_offset = 0; };
inline constexpr std::size_t kMaxUprightGuides = 4;

struct Crop final { float left = 0, top = 0, right = 1, bottom = 1, angle = 0; };

enum class MaskKind : std::uint8_t { Linear, Radial, Brush, Luminance, Color, Ai };

/** One brush stroke over a local mask, in normalized image coordinates (0–1); `size` is a fraction of the longer edge. */
struct BrushStroke final {
    bool erase = false;
    float size = 0.05F, feather = 0.5F, flow = 1.0F;
    std::vector<std::pair<float, float>> points;
};

/**
 * One building block of a local mask. `op` combines it into the mask so far: 0 add, 1 subtract, 2 intersect (the first component
 * always adds). Parameters `p` by kind: Linear x1 y1 x2 y2 (full coverage at the start); Radial centre x, y, radius x, y (fractions
 * of width/height), rotation°, feather 0–1, invert 0/1; Luminance low, high, smoothness (0–1, display-referred luma);
 * Color r, g, b (display-referred), tolerance, feather; Brush uses `strokes`; Ai uses `bitmap` (`bitmap_width` × `bitmap_height` bytes,
 * 255 = inside, from a segmentation model), spread over the frame with its edges following the picture.
 */
struct MaskComponent final {
    MaskKind kind = MaskKind::Radial;
    std::uint8_t op = 0;
    std::array<float, 8> p{};
    std::vector<BrushStroke> strokes;
    std::uint16_t bitmap_width = 0, bitmap_height = 0;
    std::vector<std::uint8_t> bitmap;
};

/** A local adjustment: `adjust` (the Basic controls) applied only where the combined components cover, scaled by `amount`. */
struct LocalMask final {
    bool enabled = true;
    bool invert = false;
    float amount = 1.0F;
    BasicTone adjust;
    std::vector<MaskComponent> components;
};

/**
 * One spot repair on the uncropped frame: the circle at (x, y) is replaced from the circle at (source_x, source_y). Positions are
 * fractions of the width/height, `radius` a fraction of the longer edge. `heal` shifts the copied colour to match the surroundings.
 */
struct Spot final {
    float x = 0.5F, y = 0.5F, radius = 0.02F, source_x = 0.6F, source_y = 0.5F, feather = 0.5F, opacity = 1.0F;
    bool heal = true;
};

inline constexpr std::size_t kMaxSpots = 32;
inline constexpr std::size_t kMaxLocalMasks = 16;
inline constexpr std::size_t kMaxMaskComponents = 8;
inline constexpr std::size_t kMaxBrushStrokes = 64;
inline constexpr std::size_t kMaxBrushPoints = 8192;
/** Longest side of an AI mask bitmap, and the most bitmap pixels one recipe may carry. */
inline constexpr std::size_t kMaxAiBitmapSide = 256;
inline constexpr std::size_t kMaxAiBitmapPixels = 4 * 256 * 256;

/** C++ mirror of core:model DevelopSettings v1; ranges/units follow Develop.kt. */
struct Settings final {
    std::uint32_t version = kSettingsVersion;
    Profile profile = Profile::Neutral;
    WhiteBalance white_balance;
    BasicTone basic;
    Curve tone_curve;
    std::array<HslShift, 8> mixer{}; // red, orange, yellow, green, aqua, blue, purple, magenta
    ColorGrading grading;
    Calibration calibration;
    Detail detail;
    Optics optics;
    Effects effects;
    Crop crop;
    Upright upright = Upright::Off;
    UprightManual upright_manual;
    std::vector<UprightGuide> upright_guides;
    /** Spot repairs, applied before the crop so their positions refer to the full frame. */
    std::vector<Spot> spots;
    /** Local adjustments applied, in order, on top of the global result (after crop, before post-crop detail effects). */
    std::vector<LocalMask> masks;
};

enum class Mode : std::uint8_t { FullResolution, Preview };
enum class PreviewTier : std::uint8_t { Custom, Interactive, Settled };
inline constexpr std::uint32_t kInteractivePreviewMaxDimension = 256;
inline constexpr std::uint32_t kSettledPreviewMaxDimension = 512;
class PreviewProxyCache;
/**
 * Lens correction supplied by the caller (it knows the shot's lens): returns the corrected copy of `source`, or nullopt with `error`
 * when no profile applies. `distortion_and_vignetting` follows Optics::lens_profile, `tca` follows Optics::remove_chromatic_aberration.
 * Runs on the preview proxy for previews (the correction is resolution independent), before white balance.
 */
using LensCorrector = std::function<std::optional<TileStore>(const TileStore& source, bool distortion_and_vignetting, bool tca, std::string& error)>;

struct Request final {
    Mode mode = Mode::FullResolution;
    PreviewTier preview_tier = PreviewTier::Custom;
    std::uint32_t preview_max_dimension = 1024;
    std::uint64_t source_revision = 0;
    std::uint64_t preview_generation = 0;
    // Optional shared generation clock. Advance it when settings change or the preview is
    // cancelled; cancellable pipeline stages stop at progress boundaries. resample::resize has no
    // cancellation callback, so a generation change during proxy resize is observed immediately
    // after resize returns. Consumers must compare Result::preview_generation before publication.
    std::shared_ptr<const std::atomic<std::uint64_t>> latest_preview_generation;
    PreviewProxyCache* preview_cache = nullptr;
    unsigned worker_threads = 0;
    /** Optional compute device: tone and colour run on it when set, each falling back to the CPU on its own. */
    gpu::Compute* gpu = nullptr;
    std::uint32_t grain_seed = 1;
    /** Optional lens profile corrector; without one the Optics lens flags only produce a warning. */
    LensCorrector lens_correction;
    std::function<bool(float)> progress; // monotonic 0–1; false cancels
};
struct Result final {
    TileStore pixels;
    std::uint32_t width = 0, height = 0;
    PreviewTier preview_tier = PreviewTier::Custom;
    std::uint64_t preview_generation = 0;
    std::chrono::nanoseconds elapsed{};
    std::vector<std::string> warnings;
    bool tone_on_gpu = false, color_on_gpu = false;  // which stages actually ran on Request::gpu
    explicit Result(TileStore store) : pixels(std::move(store)), width(pixels.width()), height(pixels.height()) {}
};

/**
 * Create one request in the two-tier interactive policy. Call Interactive immediately for slider
 * changes; after the UI's debounce/settle interval, dispatch Settled. The C++ reference has no
 * scheduler or clock and intentionally leaves that quiet period to the UI owner. A generation
 * clock makes cancellable work stop at progress boundaries and the returned generation lets the
 * UI reject a result that raced with the final cancellation check. Lanczos proxy resize is not
 * interruptible currently, but stale work is checked as soon as it returns. A caller-owned proxy
 * cache may be shared across both requests because it stores only source pixels.
 */
Request makePreviewRequest(const Request& base, PreviewTier tier, std::uint64_t generation,
                           std::shared_ptr<const std::atomic<std::uint64_t>> latest_generation = {});

/**
 * One-entry, byte-bounded source-proxy cache. Keep one instance per document and increment
 * source_revision whenever source pixels change. This stores only the geometry-resampled proxy;
 * adjustment kernels always rerun for each settings change.
 */
class PreviewProxyCache final {
public:
    explicit PreviewProxyCache(std::size_t max_bytes = 64U * 1024U * 1024U) : max_bytes_(max_bytes) {}
    PreviewProxyCache(const PreviewProxyCache&) = delete;
    PreviewProxyCache& operator=(const PreviewProxyCache&) = delete;

    void clear();
    void setMaxBytes(std::size_t max_bytes);
    std::size_t residentBytes() const;
    std::uint64_t cacheHits() const;

private:
    friend std::optional<Result> render(const TileStore&, const Settings&, const Request&, std::string&);
    std::optional<TileStore> lookup(std::uint64_t revision, std::uint32_t width, std::uint32_t height,
                                    PixelFormat format, std::uint32_t max_dimension) const;
    void remember(std::uint64_t revision, std::uint32_t width, std::uint32_t height,
                  PixelFormat format, std::uint32_t max_dimension, const TileStore& proxy);

    mutable std::mutex mutex_;
    std::size_t max_bytes_;
    std::size_t resident_bytes_ = 0;
    mutable std::uint64_t cache_hits_ = 0;
    bool valid_ = false;
    std::uint64_t revision_ = 0;
    std::uint32_t width_ = 0, height_ = 0, max_dimension_ = 0;
    PixelFormat format_ = PixelFormat::RGBA8;
    std::optional<TileStore> proxy_;
};

/** Combined A16 coverage of one local mask over `image` (scene-linear), exactly as `render` computes it; nullopt with `error`. */
std::optional<TileStore> localMaskCoverage(const LocalMask& mask, const TileStore& image, unsigned worker_threads, std::string& error);

/**
 * Runs WB → tone (including point curve/basic tone) → profile/mixer/grading → detail/effects.
 * Input must be scene-linear RGBA16 or RGBA32F. Preview first creates an aspect-preserving bounded
 * tile-mip/Lanczos proxy, then runs the same kernels at proxy resolution. Crop/geometry is rejected
 * when non-default. Optics numeric corrections are rejected; enabled lens-profile/CA flags return a
 * warning because this input carries no lens metadata or profiles. AS_SHOT/AUTO WB are identity
 * because source metadata is outside TileStore; named/custom temperatures apply a documented
 * D65-relative linear-sRGB approximation, not camera-profile WB. This is a CPU reference, not the
 * <16 ms slider-preview implementation; Result::elapsed is measurement evidence only.
 */
std::optional<Result> render(const TileStore& linear, const Settings& settings, const Request& request, std::string& error);

} // namespace croptop::develop::pipeline
