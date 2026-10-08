// Camera raw → editor layer; see raw_open.h.
#include "raw_open.h"

#include <algorithm>
#include <array>
#include <atomic>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <mutex>

#include "parallel_util.h"
#include "raw_lens.h"
#include "resample.h"

namespace croptop::raw {
namespace {

constexpr float kDecodeShare = 0.15F;  // of the progress range; then demosaic 0.35, Develop 0.4, encode 0.1

const std::array<std::uint16_t, 65536>& srgb16FromLinear16() {
    static const auto table = [] {
        std::array<std::uint16_t, 65536> t{};
        for (std::size_t i = 0; i < t.size(); ++i) {
            t[i] = static_cast<std::uint16_t>(std::lround(srgbEncode(static_cast<float>(i) / 65535.0F) * 65535.0F));
        }
        return t;
    }();
    return table;
}

std::function<bool(float)> scaledProgress(const std::function<bool(float)>& outer, float begin, float span) {
    if (!outer) return {};
    return [outer, begin, span](float fraction) { return outer(begin + span * std::clamp(fraction, 0.0F, 1.0F)); };
}

/** Linear RGBA16/RGBA32F → sRGB-encoded RGBA16 or RGBA8, opaque. */
std::optional<TileStore> encode(const TileStore& linear, bool sixteen_bit, unsigned workers, const std::function<bool(float)>& progress,
                                std::string& error) {
    const auto out_format = sixteen_bit ? PixelFormat::RGBA16 : PixelFormat::RGBA8;
    TileStore out = linear.emptyLike(out_format);
    const auto coordinates = linear.tileCoordinates();
    const auto& lut = srgb16FromLinear16();
    const bool float_input = linear.format() == PixelFormat::RGBA32F;
    std::atomic<bool> failed{false};
    std::atomic<std::size_t> done{0};
    std::mutex write_mutex;
    parallel::parallelFor(coordinates.size(), parallel::workerCount(workers), [&](std::size_t index, std::size_t) {
        if (failed.load()) return;
        const auto source = linear.readTile(coordinates[index]);
        if (source == nullptr) {
            failed = true;
            return;
        }
        TileBuffer tile(out.tile_byte_size(), 0);
        const std::size_t pixels = static_cast<std::size_t>(kTileEdge) * kTileEdge;
        for (std::size_t p = 0; p < pixels; ++p) {
            for (std::size_t c = 0; c < 3; ++c) {
                std::uint16_t v16;
                if (float_input) {
                    float f;
                    std::memcpy(&f, source->data() + (p * 4 + c) * sizeof(float), sizeof(float));
                    v16 = static_cast<std::uint16_t>(std::lround(std::clamp(std::isfinite(f) ? f : 0.0F, 0.0F, 1.0F) * 65535.0F));
                } else {
                    std::memcpy(&v16, source->data() + (p * 4 + c) * sizeof(std::uint16_t), sizeof(v16));
                }
                const std::uint16_t encoded = lut[v16];
                if (sixteen_bit) std::memcpy(tile.data() + (p * 4 + c) * sizeof(std::uint16_t), &encoded, sizeof(encoded));
                else tile[p * 4 + c] = static_cast<std::uint8_t>((static_cast<unsigned>(encoded) + 128U) / 257U);
            }
            if (sixteen_bit) {
                const std::uint16_t opaque = 0xFFFF;
                std::memcpy(tile.data() + (p * 4 + 3) * sizeof(std::uint16_t), &opaque, sizeof(opaque));
            } else {
                tile[p * 4 + 3] = 0xFF;
            }
        }
        {
            const std::lock_guard lock(write_mutex);
            if (!out.writeTile(coordinates[index], tile)) failed = true;
        }
        const auto finished = done.fetch_add(1) + 1;
        if (progress && (finished % 8 == 0 || finished == coordinates.size()) &&
            !progress(static_cast<float>(finished) / static_cast<float>(coordinates.size()))) {
            failed = true;
        }
    });
    if (failed) {
        error = "raw encode was cancelled or ran out of memory";
        return std::nullopt;
    }
    return out;
}

}  // namespace

float srgbEncode(float linear) {
    const float v = std::clamp(std::isfinite(linear) ? linear : 0.0F, 0.0F, 1.0F);
    return v <= 0.0031308F ? 12.92F * v : 1.055F * std::pow(v, 1.0F / 2.4F) - 0.055F;
}

TileStore orient(const TileStore& source, int exif_orientation) {
    switch (exif_orientation) {
        case 2: return resample::flip(source, true);
        case 3: return resample::rotate90(source, 2);
        case 4: return resample::flip(source, false);
        case 5: return resample::rotate90(resample::flip(source, true), 3);
        case 6: return resample::rotate90(source, 1);
        case 7: return resample::rotate90(resample::flip(source, true), 1);
        case 8: return resample::rotate90(source, 3);
        default: return source;
    }
}

std::optional<OpenOptions> optionsFromXmp(std::string_view xmp, std::string& error) {
    const auto recipe = develop::readXmp(xmp, error);
    if (!recipe) return std::nullopt;
    auto converted = develop::settingsFromRecipe(*recipe);
    OpenOptions options;
    options.settings = std::move(converted.settings);
    options.camera_white_balance = converted.camera_white_balance;
    return options;
}

std::string sidecarXmp(const OpenOptions& options, std::string_view creator_tool) {
    auto recipe = develop::recipeFromSettings(options.settings);
    recipe.white_balance = options.camera_white_balance;
    return develop::writeXmp(recipe, creator_tool);
}

/** The pipeline's lens corrector for this shot (empty when there is no database or no matching lens; a warning says why). */
static develop::pipeline::LensCorrector lensCorrectorFor(const OpenOptions& options, const RawMetadata& metadata, std::vector<std::string>& warnings) {
    if (options.lens_database == nullptr) return {};
    std::string reason;
    const auto detected = detectLensProfile(*options.lens_database, metadata, reason);
    if (!detected) {
        warnings.push_back("lens correction skipped: " + reason);
        return {};
    }
    // Shared so the corrector outlives the session's open call and the database handle stays valid while it runs.
    return [profile = std::make_shared<DetectedLensProfile>(*detected), database = options.lens_database](
               const TileStore& source, bool distortion_and_vignetting, bool tca, std::string& error) -> std::optional<TileStore> {
        auto apply = profile->apply;
        apply.distortion = distortion_and_vignetting;
        apply.vignetting = distortion_and_vignetting;
        apply.tca = tca;
        return lens::apply(source, profile->correction, apply, error);
    };
}

std::optional<OpenedRaw> openFlattened(std::span<const std::uint8_t> file, const OpenOptions& options, std::string& error,
                                       const RawLimits& limits) {
    // CROPTOP_RAW_TIMING=1 prints where the time goes (decode, demosaic, Develop, encode) to stderr; handy for tuning.
    const bool timing = std::getenv("CROPTOP_RAW_TIMING") != nullptr;
    auto mark = std::chrono::steady_clock::now();
    const auto lap = [&](const char* stage) {
        if (!timing) return;
        const auto now = std::chrono::steady_clock::now();
        std::fprintf(stderr, "[raw] %-9s %8.0f ms\n", stage, std::chrono::duration<double, std::milli>(now - mark).count());
        mark = now;
    };
    auto image = decode(file, error, limits);
    lap("decode");
    if (!image) return std::nullopt;
    if (options.progress && !options.progress(kDecodeShare)) {
        error = "raw open was cancelled";
        return std::nullopt;
    }

    auto spec = image->developSpec(options.primaries);
    if (options.camera_white_balance) spec.white_balance = *options.camera_white_balance;
    if (options.demosaic) spec.method = *options.demosaic;
    spec.worker_threads = options.worker_threads;
    spec.progress = scaledProgress(options.progress, kDecodeShare, 0.35F);
    auto linear = image->isLinear() ? image->developLinear(spec, error) : develop(image->cfa(), spec, error);
    if (!linear) return std::nullopt;
    // The mosaic is no longer needed; free it before the Develop pipeline allocates its output.
    RawMetadata metadata = std::move(image->metadata);
    std::vector<std::string> warnings = std::move(image->warnings);
    // The profile is applied inside the Develop pipeline so its Optics toggles decide what is corrected.
    develop::pipeline::LensCorrector corrector = lensCorrectorFor(options, metadata, warnings);
    const auto sensor_width = image->width, sensor_height = image->height;
    image.reset();
    // Orient before Develop so the crop frame and straighten angle are in the upright image the user sees.
    if (options.apply_orientation && metadata.orientation >= 2 && metadata.orientation <= 8) {
        TileStore upright = orient(*linear, metadata.orientation);
        linear.reset();
        linear.emplace(std::move(upright));
    }
    lap("demosaic");

    develop::pipeline::Request request;
    request.worker_threads = options.worker_threads;
    request.lens_correction = corrector;
    request.progress = scaledProgress(options.progress, kDecodeShare + 0.35F, 0.4F);
    auto graded = develop::pipeline::render(*linear, options.settings, request, error);
    if (!graded) return std::nullopt;
    linear.reset();
    lap("develop");
    warnings.insert(warnings.end(), graded->warnings.begin(), graded->warnings.end());

    auto encoded = encode(graded->pixels, options.sixteen_bit, options.worker_threads,
                          scaledProgress(options.progress, 0.9F, 0.1F), error);
    if (!encoded) return std::nullopt;
    graded.reset();
    lap("encode");

    OpenedRaw opened(std::move(*encoded));
    opened.metadata = std::move(metadata);
    opened.warnings = std::move(warnings);
    opened.sensor_width = sensor_width;
    opened.sensor_height = sensor_height;
    return opened;
}

DevelopSession::DevelopSession(TileStore linear, RawMetadata metadata, std::vector<std::string> warnings,
                               develop::pipeline::LensCorrector lens_correction)
    : linear_(std::move(linear)), metadata_(std::move(metadata)), warnings_(std::move(warnings)), lens_correction_(std::move(lens_correction)) {}

std::unique_ptr<DevelopSession> DevelopSession::open(std::span<const std::uint8_t> file, const OpenOptions& options, std::string& error,
                                                     const RawLimits& limits) {
    auto image = decode(file, error, limits);
    if (!image) return nullptr;
    auto spec = image->developSpec(options.primaries);
    if (options.camera_white_balance) spec.white_balance = *options.camera_white_balance;
    if (options.demosaic) spec.method = *options.demosaic;
    spec.worker_threads = options.worker_threads;
    auto linear = image->isLinear() ? image->developLinear(spec, error) : develop(image->cfa(), spec, error);
    if (!linear) return nullptr;
    RawMetadata metadata = std::move(image->metadata);
    std::vector<std::string> warnings = std::move(image->warnings);
    develop::pipeline::LensCorrector corrector = lensCorrectorFor(options, metadata, warnings);
    image.reset();
    if (options.apply_orientation && metadata.orientation >= 2 && metadata.orientation <= 8) {
        TileStore upright = orient(*linear, metadata.orientation);
        linear.reset();
        linear.emplace(std::move(upright));
    }
    return std::unique_ptr<DevelopSession>(new DevelopSession(std::move(*linear), std::move(metadata), std::move(warnings), std::move(corrector)));
}

std::optional<TileStore> DevelopSession::render(const develop::pipeline::Settings& settings, std::uint32_t max_dimension,
                                                unsigned worker_threads, std::string& error, std::vector<std::string>* warnings) {
    develop::pipeline::Request request;
    request.mode = develop::pipeline::Mode::Preview;
    request.preview_max_dimension = std::clamp<std::uint32_t>(max_dimension, 64U, 4096U);
    request.source_revision = 1;
    request.preview_cache = &cache_;
    request.worker_threads = worker_threads;
    request.lens_correction = lens_correction_;
    auto graded = develop::pipeline::render(linear_, settings, request, error);
    if (!graded) return std::nullopt;
    if (warnings != nullptr) warnings->insert(warnings->end(), graded->warnings.begin(), graded->warnings.end());
    return encode(graded->pixels, false, worker_threads, {}, error);
}

std::optional<TileStore> DevelopSession::maskOverlay(const develop::pipeline::Settings& settings, std::size_t index, std::uint32_t max_dimension,
                                                     unsigned worker_threads, std::string& error) {
    if (index >= settings.masks.size()) {
        error = "no such local mask";
        return std::nullopt;
    }
    develop::pipeline::Settings frame;  // neutral except the crop, so the proxy has the same geometry as the preview
    frame.crop = settings.crop;
    frame.detail = develop::pipeline::Detail{0, 1, 0, 0, 0, 0};
    frame.optics = develop::pipeline::Optics{false, false, 0, 0};
    develop::pipeline::Request request;
    request.mode = develop::pipeline::Mode::Preview;
    request.preview_max_dimension = std::clamp<std::uint32_t>(max_dimension, 64U, 4096U);
    request.source_revision = 1;
    request.preview_cache = &cache_;
    request.worker_threads = worker_threads;
    auto proxy = develop::pipeline::render(linear_, frame, request, error);
    if (!proxy) return std::nullopt;
    const auto coverage = develop::pipeline::localMaskCoverage(settings.masks[index], proxy->pixels, worker_threads, error);
    if (!coverage) return std::nullopt;
    TileStore overlay(PixelFormat::RGBA8, coverage->width(), coverage->height());
    for (const auto coordinate : coverage->tileCoordinates()) {
        const auto source = coverage->readTile(coordinate);
        if (source == nullptr) continue;
        TileBuffer tile(overlay.tile_byte_size(), 0);
        for (std::size_t p = 0; p < static_cast<std::size_t>(kTileEdge) * kTileEdge; ++p) {
            std::uint16_t value = 0;
            std::memcpy(&value, source->data() + p * 2U, 2U);
            tile[p * 4U] = 229;
            tile[p * 4U + 1U] = 72;
            tile[p * 4U + 2U] = 77;
            tile[p * 4U + 3U] = static_cast<std::uint8_t>((static_cast<unsigned>(value) * 153U + 32767U) / 65535U);
        }
        if (!overlay.writeTile(coordinate, tile)) {
            error = "could not store the mask overlay";
            return std::nullopt;
        }
    }
    return overlay;
}

}  // namespace croptop::raw
