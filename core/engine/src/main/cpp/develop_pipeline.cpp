#include "develop_pipeline.h"

#include <algorithm>
#include <atomic>
#include <array>
#include <cmath>
#include <cstring>
#include <exception>
#include <limits>
#include <mutex>
#include <unordered_set>
#include <type_traits>

#include "develop_color.h"
#if defined(CROPTOP_HAS_GPU_DEVELOP)
#include "develop_color_gpu.h"
#include "develop_tone_gpu.h"
#endif
#include "develop_detail.h"
#include "develop_mask_blend.h"
#include "develop_mask_ops.h"
#include "defringe.h"
#include "develop_masks.h"
#include "develop_upright_bridge.h"
#include "selection_mask.h"
#include "mask_upsample.h"
#include "develop_tone.h"
#include "lens_correction.h"
#include "parallel_util.h"
#include "pixel_plane.h"
#include "crop_geometry.h"
#include "resample.h"

namespace croptop::develop::pipeline {
namespace {

bool finite(float v) { return std::isfinite(v); }
bool in(float v, float lo, float hi) { return finite(v) && v >= lo && v <= hi; }

float temperatureFor(const WhiteBalance& wb) {
    switch (wb.preset) {
        case WhiteBalancePreset::AsShot:
        case WhiteBalancePreset::Auto: return 5500.0F;
        case WhiteBalancePreset::Daylight: return 5500.0F;
        case WhiteBalancePreset::Cloudy: return 6500.0F;
        case WhiteBalancePreset::Shade: return 7500.0F;
        case WhiteBalancePreset::Tungsten: return 2850.0F;
        case WhiteBalancePreset::Fluorescent: return 4000.0F;
        case WhiteBalancePreset::Flash: return 6000.0F;
        case WhiteBalancePreset::Custom: return wb.temperature;
    }
    return std::numeric_limits<float>::quiet_NaN();
}

// CIE daylight/Planckian approximation. Convert the resulting XYZ white point through the
// linear-sRGB matrix; ratios are relative to a D65-ish 5500K input neutral.
std::array<float, 3> whiteBalanceGains(float kelvin, float tint) {
    const double t = std::clamp(static_cast<double>(kelvin), 2000.0, 50000.0);
    const double x = t <= 4000.0
                         ? -0.2661239e9 / (t * t * t) - 0.2343580e6 / (t * t) + 0.8776956e3 / t + 0.179910
                         : -3.0258469e9 / (t * t * t) + 2.1070379e6 / (t * t) + 0.2226347e3 / t + 0.240390;
    const double y = t <= 2222.0 ? -1.1063814 * x * x * x - 1.34811020 * x * x + 2.18555832 * x - 0.20219683
                   : t <= 4000.0 ? -0.9549476 * x * x * x - 1.37418593 * x * x + 2.09137015 * x - 0.16748867
                                  : 3.0817580 * x * x * x - 5.87338670 * x * x + 3.75112997 * x - 0.37001483;
    const auto linearRgb = [](double cx, double cy) {
        const double X = cx / cy;
        const double Z = (1.0 - cx - cy) / cy;
        return std::array<double, 3>{3.2406 * X - 1.5372 - 0.4986 * Z,
                                     -0.9689 * X + 1.8758 + 0.0415 * Z,
                                     0.0557 * X - 0.2040 + 1.0570 * Z};
    };
    const auto dst = linearRgb(x, y);
    const auto src = linearRgb(0.3324, 0.3474); // approximately 5500 K
    const double tintGain = std::exp2(-static_cast<double>(tint) / 300.0);
    const double g = std::max(1e-6, dst[1] / src[1]);
    return {static_cast<float>(std::clamp((std::max(0.02, dst[0]) / src[0]) / g, 0.125, 8.0)),
            static_cast<float>(std::clamp(tintGain, 0.5, 2.0)),
            static_cast<float>(std::clamp((std::max(0.02, dst[2]) / src[2]) / g, 0.125, 8.0))};
}

bool validate(const Settings& s, std::string& error) {
    const auto bad = [&error](const char* message) {
        error = message;
        return false;
    };
    if (s.version != kSettingsVersion) return bad("unsupported DevelopSettings version");
    if (static_cast<unsigned>(s.profile) > static_cast<unsigned>(Profile::Monochrome) ||
        static_cast<unsigned>(s.white_balance.preset) > static_cast<unsigned>(WhiteBalancePreset::Custom)) return bad("unknown profile or white-balance preset");
    const auto& wb = s.white_balance;
    if (!in(wb.temperature, 2000, 50000) || !in(wb.tint, -150, 150)) return bad("white-balance temperature/tint is outside the v1 schema range");
    const auto& b = s.basic;
    if (!in(b.exposure, -5, 5) || !in(b.contrast, -100, 100) || !in(b.highlights, -100, 100) || !in(b.shadows, -100, 100) ||
        !in(b.whites, -100, 100) || !in(b.blacks, -100, 100) || !in(b.texture, -100, 100) || !in(b.clarity, -100, 100) ||
        !in(b.dehaze, -100, 100) || !in(b.vibrance, -100, 100) || !in(b.saturation, -100, 100)) return bad("basic tone value is outside the v1 schema range");
    if (s.tone_curve.points.size() < 2 || s.tone_curve.points.size() > 256) return bad("tone curve must contain 2–256 points");
    float previousX = -1.0F;
    for (const auto& point : s.tone_curve.points) {
        if (!in(point.first, 0, 1) || !in(point.second, 0, 1) || point.first <= previousX) return bad("tone curve points must be finite, normalized, and strictly ordered by x");
        previousX = point.first;
    }
    for (const auto& shift : s.mixer)
        if (!in(shift.hue, -100, 100) || !in(shift.saturation, -100, 100) || !in(shift.luminance, -100, 100)) return bad("color mixer value is outside the v1 schema range");
    const auto validWheel = [](const GradeWheel& w) { return in(w.hue, 0, 360) && in(w.saturation, 0, 100) && in(w.luminance, -100, 100); };
    if (!validWheel(s.grading.shadows) || !validWheel(s.grading.midtones) || !validWheel(s.grading.highlights) || !validWheel(s.grading.global) ||
        !in(s.grading.blending, 0, 100) || !in(s.grading.balance, -100, 100)) return bad("color grading value is outside the v1 schema range");
    for (const float v : {s.calibration.shadow_tint, s.calibration.red_hue, s.calibration.red_saturation, s.calibration.green_hue,
                          s.calibration.green_saturation, s.calibration.blue_hue, s.calibration.blue_saturation})
        if (!in(v, -100, 100)) return bad("calibration value is outside the v1 schema range");
    const auto& d = s.detail;
    if (!in(d.sharpen_amount, 0, 150) || !in(d.sharpen_radius, 0.5F, 3) || !in(d.sharpen_detail, 0, 100) || !in(d.sharpen_masking, 0, 100) ||
        !in(d.luminance_noise, 0, 100) || !in(d.color_noise, 0, 100)) return bad("detail value is outside the v1 schema range");
    if (!in(s.effects.vignette_amount, -100, 100) || !in(s.effects.vignette_midpoint, 0, 100) || !in(s.effects.grain_amount, 0, 100) || !in(s.effects.grain_size, 0, 100)) return bad("effect value is outside the v1 schema range");
    if (!in(s.optics.vignetting, -100, 100) || !in(s.optics.distortion, -100, 100) || !in(s.optics.defringe_purple, 0, 20) || !in(s.optics.defringe_green, 0, 20)) return bad("optics value is outside the v1 schema range");
    if (s.upright_guides.size() > kMaxUprightGuides) return bad("too many upright guides");
    for (const auto& g : s.upright_guides) {
        if (!in(g.x1, 0, 1) || !in(g.y1, 0, 1) || !in(g.x2, 0, 1) || !in(g.y2, 0, 1)) return bad("upright guide is outside the frame");
    }
    {
        const auto& m = s.upright_manual;
        if (!in(m.vertical, -100, 100) || !in(m.horizontal, -100, 100) || !in(m.rotate, -180, 180) || !in(m.aspect, -100, 100) ||
            !in(m.scale, 50, 200) || !in(m.x_offset, -100, 100) || !in(m.y_offset, -100, 100)) return bad("manual upright value is outside its range");
    }
    if (s.spots.size() > kMaxSpots) return bad("too many spot repairs");
    for (const auto& sp : s.spots) {
        if (!in(sp.x, 0, 1) || !in(sp.y, 0, 1) || !in(sp.source_x, 0, 1) || !in(sp.source_y, 0, 1) || !in(sp.radius, 0.001F, 0.25F) ||
            !in(sp.feather, 0, 1) || !in(sp.opacity, 0, 1)) return bad("spot repair is outside the v1 schema range");
    }
    if (s.masks.size() > kMaxLocalMasks) return bad("too many local masks");
    for (const auto& m : s.masks) {
        const auto& a = m.adjust;
        if (!in(m.amount, 0, 1) || !in(a.exposure, -5, 5) || !in(a.contrast, -100, 100) || !in(a.highlights, -100, 100) || !in(a.shadows, -100, 100) ||
            !in(a.whites, -100, 100) || !in(a.blacks, -100, 100) || !in(a.texture, -100, 100) || !in(a.clarity, -100, 100) ||
            !in(a.dehaze, -100, 100) || !in(a.vibrance, -100, 100) || !in(a.saturation, -100, 100)) return bad("local mask adjustment is outside the v1 schema range");
        if (m.components.empty() || m.components.size() > kMaxMaskComponents) return bad("a local mask needs 1–8 components");
        for (const auto& c : m.components) {
            if (c.op > 2 || !std::all_of(c.p.begin(), c.p.end(), [](float x) { return std::isfinite(x); })) return bad("local mask component parameters are invalid");
            if (c.kind == MaskKind::Radial && (c.p[2] <= 0 || c.p[3] <= 0 || !in(c.p[5], 0, 1))) return bad("radial mask radii must be positive and feather 0–1");
            if (c.kind == MaskKind::Ai) {
                if (c.bitmap_width < 2 || c.bitmap_height < 2 || c.bitmap_width > kMaxAiBitmapSide || c.bitmap_height > kMaxAiBitmapSide ||
                    c.bitmap.size() != static_cast<std::size_t>(c.bitmap_width) * c.bitmap_height) return bad("an AI mask bitmap must be 2–256 px per side");
            } else if (!c.bitmap.empty()) {
                return bad("only AI mask components carry a bitmap");
            }
            if (c.strokes.size() > kMaxBrushStrokes) return bad("too many brush strokes in a local mask");
            for (const auto& st : c.strokes) {
                if (!in(st.size, 0.0005F, 1) || !in(st.feather, 0, 1) || !in(st.flow, 0, 1) || st.points.size() > kMaxBrushPoints) return bad("brush stroke is outside the v1 schema range");
                for (const auto& pt : st.points) if (!std::isfinite(pt.first) || !std::isfinite(pt.second)) return bad("brush stroke has a non-finite point");
            }
        }
    }
    std::size_t ai_pixels = 0;
    for (const auto& m : s.masks) for (const auto& comp : m.components) ai_pixels += comp.bitmap.size();
    if (ai_pixels > kMaxAiBitmapPixels) return bad("the AI mask bitmaps in this recipe are too large");
    const auto& c = s.crop;
    if (!std::isfinite(c.left) || !std::isfinite(c.top) || !std::isfinite(c.right) || !std::isfinite(c.bottom) || !std::isfinite(c.angle) ||
        c.left < 0 || c.top < 0 || c.right > 1 || c.bottom > 1 || c.right - c.left < 0.001F || c.bottom - c.top < 0.001F || !in(c.angle, -45, 45)) {
        return bad("crop is outside the v1 schema range (frame inside 0–1, at least 0.1% of each side, angle ±45°)");
    }
    return true;
}

/** Straightens by `crop.angle` (rotate + largest inscribed rectangle), then keeps the normalized frame of that result. */
std::optional<TileStore> applyCrop(const TileStore& source, const Crop& crop, unsigned workers, std::string& error) {
    std::optional<TileStore> straight;
    const TileStore* current = &source;
    if (crop.angle != 0.0F) {
        const auto geometry = croptop::crop::straighten(source.width(), source.height(), crop.angle, error);
        if (!geometry) return std::nullopt;
        straight = resample::warp(source, geometry->matrix, geometry->width, geometry->height, resample::Filter::Lanczos3, error, workers);
        if (!straight) return std::nullopt;
        current = &*straight;
    }
    const auto width = current->width();
    const auto height = current->height();
    const auto left = static_cast<std::int64_t>(std::lround(static_cast<double>(crop.left) * width));
    const auto top = static_cast<std::int64_t>(std::lround(static_cast<double>(crop.top) * height));
    const auto right = static_cast<std::int64_t>(std::lround(static_cast<double>(crop.right) * width));
    const auto bottom = static_cast<std::int64_t>(std::lround(static_cast<double>(crop.bottom) * height));
    const auto out_width = static_cast<std::uint32_t>(std::max<std::int64_t>(1, right - left));
    const auto out_height = static_cast<std::uint32_t>(std::max<std::int64_t>(1, bottom - top));
    if (out_width == width && out_height == height) return TileStore(*current);
    return resample::canvasSize(*current, out_width, out_height, -left, -top, error);
}

/** A display-referred (sRGB-encoded RGBA16) copy of a linear layer, for luminance and colour range masks. */
std::optional<TileStore> displayCopy(const TileStore& linear, unsigned workers, std::string& error) {
    TileStore out = linear.emptyLike(PixelFormat::RGBA16);
    const auto coordinates = linear.tileCoordinates();
    std::atomic<bool> failed{false};
    std::mutex write_mutex;
    const auto bpp_in = linear.bytes_per_pixel();
    parallel::parallelFor(coordinates.size(), parallel::workerCount(workers), [&](std::size_t index, std::size_t) {
        if (failed.load()) return;
        const auto source = linear.readTile(coordinates[index]);
        if (source == nullptr) return;
        TileBuffer tile(out.tile_byte_size(), 0);
        const std::size_t pixels = static_cast<std::size_t>(kTileEdge) * kTileEdge;
        for (std::size_t p = 0; p < pixels; ++p) {
            float px[4];
            paint::detail::readPixel(linear.format(), source->data() + p * bpp_in, px);
            for (int c = 0; c < 3; ++c) {
                const float v = std::clamp(std::isfinite(px[c]) ? px[c] : 0.0F, 0.0F, 1.0F);
                px[c] = v <= 0.0031308F ? 12.92F * v : 1.055F * std::pow(v, 1.0F / 2.4F) - 0.055F;
            }
            px[3] = 1.0F;
            paint::detail::writePixel(PixelFormat::RGBA16, tile.data() + p * 8U, px);
        }
        const std::lock_guard lock(write_mutex);
        if (!out.writeTile(coordinates[index], tile)) failed = true;
    });
    if (failed) {
        error = "could not build the display-referred mask source";
        return std::nullopt;
    }
    return out;
}

/** Combined A16 coverage of one local mask over `image` (linear), or nullopt with `error`. */
std::optional<TileStore> localCoverage(const LocalMask& mask, const TileStore& image, unsigned workers, std::string& error) {
    const auto width = image.width();
    const auto height = image.height();
    std::optional<TileStore> display;
    std::optional<TileStore> coverage(selection::emptyMask(image));
    bool first = true;
    for (const auto& component : mask.components) {
        std::optional<TileStore> operand;
        switch (component.kind) {
            case MaskKind::Linear:
                operand.emplace(linearGradient(width, height, {component.p[0] * width, component.p[1] * height},
                                               {component.p[2] * width, component.p[3] * height}));
                break;
            case MaskKind::Radial: {
                RadialSpec spec;
                spec.centre = {component.p[0] * width, component.p[1] * height};
                spec.radius_x = component.p[2] * width;
                spec.radius_y = component.p[3] * height;
                spec.rotation = component.p[4];
                spec.feather = component.p[5];
                spec.invert = component.p[6] != 0.0F;
                TileStore out = selection::emptyMask(image);
                if (!radial(width, height, spec, out, error)) return std::nullopt;
                operand.emplace(std::move(out));
                break;
            }
            case MaskKind::Brush: {
                TileStore painted = selection::emptyMask(image);
                const double longer = std::max(width, height);
                for (const auto& stroke : component.strokes) {
                    const double size = std::max(1.0, static_cast<double>(stroke.size) * longer);
                    const double spacing = std::max(1.0, size * 0.2);
                    for (std::size_t i = 0; i < stroke.points.size(); ++i) {
                        const Point to{stroke.points[i].first * width, stroke.points[i].second * height};
                        std::vector<Point> dabs;
                        if (i == 0) {
                            dabs.push_back(to);
                        } else {
                            const Point from{stroke.points[i - 1].first * width, stroke.points[i - 1].second * height};
                            const double length = std::hypot(to.x - from.x, to.y - from.y);
                            const auto steps = std::max<std::size_t>(1, static_cast<std::size_t>(std::ceil(length / spacing)));
                            for (std::size_t s = 1; s <= steps; ++s) {
                                const double t = static_cast<double>(s) / static_cast<double>(steps);
                                dabs.push_back({from.x + (to.x - from.x) * t, from.y + (to.y - from.y) * t});
                            }
                        }
                        for (const auto& centre : dabs) {
                            Dab dab;
                            dab.centre = centre;
                            dab.size = size;
                            dab.feather = stroke.feather;
                            dab.flow = stroke.flow;
                            dab.erase = stroke.erase;
                            if (!brushDab(painted, dab, error)) return std::nullopt;
                        }
                    }
                }
                operand.emplace(std::move(painted));
                break;
            }
            case MaskKind::Ai: {
                if (!display) {
                    display = displayCopy(image, workers, error);
                    if (!display) return std::nullopt;
                }
                if (component.bitmap_width > width || component.bitmap_height > height) {
                    error = "the AI mask is larger than the picture";
                    return std::nullopt;
                }
                TileStore low(PixelFormat::A16, component.bitmap_width, component.bitmap_height);
                for (std::uint32_t ty = 0; ty * kTileEdge < low.height(); ++ty) {
                    for (std::uint32_t tx = 0; tx * kTileEdge < low.width(); ++tx) {
                        TileBuffer tile(low.tile_byte_size(), 0);
                        for (std::uint32_t y = 0; y < kTileEdge && ty * kTileEdge + y < low.height(); ++y) {
                            for (std::uint32_t x = 0; x < kTileEdge && tx * kTileEdge + x < low.width(); ++x) {
                                const auto v = static_cast<std::uint16_t>(
                                    component.bitmap[static_cast<std::size_t>(ty * kTileEdge + y) * low.width() + tx * kTileEdge + x] * 257U);
                                std::memcpy(tile.data() + (static_cast<std::size_t>(y) * kTileEdge + x) * 2U, &v, 2U);
                            }
                        }
                        if (!low.writeTile({static_cast<std::int32_t>(tx), static_cast<std::int32_t>(ty)}, tile)) {
                            error = "could not store the AI mask";
                            return std::nullopt;
                        }
                    }
                }
                selection::GuidedUpsampleSpec guided;
                guided.radius = 8;
                auto spread = selection::guidedUpsample(*display, low, guided, error);
                if (!spread) return std::nullopt;
                operand.emplace(std::move(*spread));
                break;
            }
            case MaskKind::Luminance:
            case MaskKind::Color: {
                if (!display) {
                    display = displayCopy(image, workers, error);
                    if (!display) return std::nullopt;
                }
                TileStore out = selection::emptyMask(image);
                if (component.kind == MaskKind::Luminance) {
                    if (!luminanceRange(*display, component.p[0], component.p[1], component.p[2], out, error)) return std::nullopt;
                } else {
                    mask_ops::ColorRange range;
                    range.target = {component.p[0], component.p[1], component.p[2]};
                    range.tolerance = component.p[3];
                    range.feather = component.p[4];
                    if (!mask_ops::colorRange(*display, range, out, error)) return std::nullopt;
                }
                operand.emplace(std::move(out));
                break;
            }
        }
        const auto op = first ? selection::Op::Replace
                              : component.op == 1 ? selection::Op::Subtract
                              : component.op == 2 ? selection::Op::Intersect
                                                  : selection::Op::Add;
        auto combined = mask_ops::combine(*coverage, *operand, op);
        if (!combined) {
            error = "could not combine local mask components";
            return std::nullopt;
        }
        coverage.reset();
        coverage.emplace(std::move(*combined));
        first = false;
    }
    if (mask.invert) {
        auto inverted = selection::invert(*coverage, &error);
        if (!inverted) return std::nullopt;
        coverage.reset();
        coverage.emplace(std::move(*inverted));
    }
    return coverage;
}

class ProgressRelay final {
public:
    explicit ProgressRelay(const Request& request) : request_(request) {}
    bool report(float value) {
        std::lock_guard lock(mutex_);
        if (cancelled_ || !isCurrent()) {
            cancelled_ = true;
            return false;
        }
        value = std::max(last_, std::clamp(value, 0.0F, 1.0F));
        last_ = value;
        if (request_.progress) {
            try {
                if (!request_.progress(value)) cancelled_ = true;
            } catch (...) {
                cancelled_ = true;
            }
        }
        return !cancelled_;
    }

private:
    bool isCurrent() const {
        return !request_.latest_preview_generation ||
               request_.latest_preview_generation->load(std::memory_order_acquire) == request_.preview_generation;
    }

    const Request& request_;
    std::mutex mutex_;
    float last_ = 0;
    bool cancelled_ = false;
};

std::optional<TileStore> applyWhiteBalance(const TileStore& src, const WhiteBalance& wb, const Request& request, ProgressRelay& progress, std::string& error) {
    const float kelvin = temperatureFor(wb);
    if (!finite(kelvin)) { error = "invalid white-balance preset"; return std::nullopt; }
    const bool active = (wb.preset != WhiteBalancePreset::AsShot && wb.preset != WhiteBalancePreset::Auto && kelvin != 5500.0F) || wb.tint != 0.0F;
    if (!active) {
        if (!progress.report(0.05F)) { error = "cancelled"; return std::nullopt; }
        return TileStore(src);
    }
    const auto gains = whiteBalanceGains(kelvin, wb.tint);
    TileStore out(src.format(), src.width(), src.height());
    const auto coords = src.tileCoordinates();
    for (std::size_t n = 0; n < coords.size(); ++n) {
        if (request.progress && !progress.report(0.05F * static_cast<float>(n) / static_cast<float>(std::max<std::size_t>(1, coords.size())))) {
            error = "cancelled"; return std::nullopt;
        }
        const auto tile = src.readTile(coords[n]);
        if (!tile) continue;
        TileBuffer pixels(*tile);
        for (std::size_t offset = 0; offset + src.bytes_per_pixel() <= pixels.size(); offset += src.bytes_per_pixel()) {
            float rgba[4]{};
            paint::detail::readPixel(src.format(), pixels.data() + offset, rgba);
            for (int c = 0; c < 3; ++c) rgba[c] *= gains[static_cast<std::size_t>(c)];
            paint::detail::writePixel(src.format(), pixels.data() + offset, rgba);
        }
        if (!out.writeTile(coords[n], pixels)) { error = "white-balance output tile allocation failed"; return std::nullopt; }
    }
    if (!progress.report(0.05F)) { error = "cancelled"; return std::nullopt; }
    return out;
}

template <typename T> void stageProgress(T& settings, const Request& request, ProgressRelay& relay, float start, float span) {
    settings.worker_threads = request.worker_threads;
    settings.progress = [&relay, start, span](float p) { return relay.report(start + std::clamp(p, 0.0F, 1.0F) * span); };
}

void mapWheel(const GradeWheel& in, develop::GradeWheel& out) { out = {in.hue, in.saturation, in.luminance}; }
develop::Look mapLook(Profile profile) {
    switch (profile) {
        case Profile::Neutral: return develop::Look::Neutral;
        case Profile::Vivid: return develop::Look::Vivid;
        case Profile::Portrait: return develop::Look::Portrait;
        case Profile::Landscape: return develop::Look::Landscape;
        case Profile::Monochrome: return develop::Look::Monochrome;
    }
    return develop::Look::None;
}

} // namespace

Request makePreviewRequest(const Request& base, PreviewTier tier, std::uint64_t generation,
                           std::shared_ptr<const std::atomic<std::uint64_t>> latest_generation) {
    Request request = base;
    request.mode = Mode::Preview;
    request.preview_tier = tier;
    request.preview_generation = generation;
    request.latest_preview_generation = std::move(latest_generation);
    switch (tier) {
        case PreviewTier::Interactive: request.preview_max_dimension = kInteractivePreviewMaxDimension; break;
        case PreviewTier::Settled: request.preview_max_dimension = kSettledPreviewMaxDimension; break;
        case PreviewTier::Custom: break;
    }
    return request;
}

void PreviewProxyCache::clear() {
    const std::lock_guard lock(mutex_);
    proxy_.reset();
    resident_bytes_ = 0;
    valid_ = false;
}

void PreviewProxyCache::setMaxBytes(std::size_t max_bytes) {
    const std::lock_guard lock(mutex_);
    max_bytes_ = max_bytes;
    if (resident_bytes_ > max_bytes_) {
        proxy_.reset();
        resident_bytes_ = 0;
        valid_ = false;
    }
}

std::size_t PreviewProxyCache::residentBytes() const {
    const std::lock_guard lock(mutex_);
    return resident_bytes_;
}

std::uint64_t PreviewProxyCache::cacheHits() const {
    const std::lock_guard lock(mutex_);
    return cache_hits_;
}

std::optional<TileStore> PreviewProxyCache::lookup(std::uint64_t revision, std::uint32_t width,
                                                   std::uint32_t height, PixelFormat format,
                                                   std::uint32_t max_dimension) const {
    const std::lock_guard lock(mutex_);
    if (!valid_ || revision_ != revision || width_ != width || height_ != height || format_ != format ||
        max_dimension_ != max_dimension || !proxy_) return std::nullopt;
    ++cache_hits_;
    return *proxy_;
}

void PreviewProxyCache::remember(std::uint64_t revision, std::uint32_t width, std::uint32_t height,
                                 PixelFormat format, std::uint32_t max_dimension, const TileStore& proxy) {
    const auto tile_size = proxy.tile_byte_size();
    if (proxy.tile_count() > std::numeric_limits<std::size_t>::max() / tile_size) return;
    const auto bytes = proxy.tile_count() * tile_size;
    const std::lock_guard lock(mutex_);
    proxy_.reset();
    resident_bytes_ = 0;
    valid_ = false;
    if (bytes == 0 || bytes > max_bytes_) return;
    proxy_ = proxy;
    resident_bytes_ = bytes;
    revision_ = revision;
    width_ = width;
    height_ = height;
    format_ = format;
    max_dimension_ = max_dimension;
    valid_ = true;
}

std::optional<TileStore> localMaskCoverage(const LocalMask& mask, const TileStore& image, unsigned worker_threads, std::string& error) {
    return localCoverage(mask, image, worker_threads, error);
}

namespace {
/** Reads single pixels of a tiled image through a one-tile cache (spots read nearby, so most reads hit it). */
class PixelReader {
public:
    explicit PixelReader(const TileStore& store) : store_(store), bpp_(store.bytes_per_pixel()) {}

    void read(std::int64_t x, std::int64_t y, float out[4]) {
        x = std::clamp<std::int64_t>(x, 0, static_cast<std::int64_t>(store_.width()) - 1);
        y = std::clamp<std::int64_t>(y, 0, static_cast<std::int64_t>(store_.height()) - 1);
        const TileCoordinate coordinate{static_cast<std::int32_t>(x / kTileEdge), static_cast<std::int32_t>(y / kTileEdge)};
        if (!tile_ || coordinate.x != at_.x || coordinate.y != at_.y) {
            tile_ = store_.readTile(coordinate);
            at_ = coordinate;
        }
        if (!tile_) {
            out[0] = out[1] = out[2] = 0.0F;
            out[3] = 1.0F;
            return;
        }
        const auto offset = (static_cast<std::size_t>(y % kTileEdge) * kTileEdge + static_cast<std::size_t>(x % kTileEdge)) * bpp_;
        paint::detail::readPixel(store_.format(), tile_->data() + offset, out);
    }

private:
    const TileStore& store_;
    std::size_t bpp_;
    std::shared_ptr<const TileBuffer> tile_;
    TileCoordinate at_{-1, -1};
};

/** Mean colour of the ring just outside a spot circle, used to match a healed patch to its surroundings. */
std::array<float, 3> ringMean(PixelReader& reader, double cx, double cy, double radius, unsigned width, unsigned height) {
    double sum[3] = {0, 0, 0};
    int count = 0;
    constexpr int kSamples = 64;
    for (int ring = 0; ring < 3; ++ring) {
        const double r = radius * (1.15 + 0.15 * ring);
        for (int k = 0; k < kSamples; ++k) {
            const double a = 6.283185307179586 * k / kSamples;
            const auto px = static_cast<std::int64_t>(std::llround(cx + r * std::cos(a)));
            const auto py = static_cast<std::int64_t>(std::llround(cy + r * std::sin(a)));
            if (px < 0 || py < 0 || px >= static_cast<std::int64_t>(width) || py >= static_cast<std::int64_t>(height)) continue;
            float v[4];
            reader.read(px, py, v);
            for (int c = 0; c < 3; ++c) sum[c] += std::isfinite(v[c]) ? v[c] : 0.0F;
            ++count;
        }
    }
    if (count == 0) return {0, 0, 0};
    return {static_cast<float>(sum[0] / count), static_cast<float>(sum[1] / count), static_cast<float>(sum[2] / count)};
}
}  // namespace

/** Replaces each spot circle from its source circle (optionally colour matched), reading every source from the unrepaired image. */
static std::optional<TileStore> applySpots(const TileStore& base, const std::vector<Spot>& spots, unsigned workers, std::string& error) {
    struct Plan {
        double cx, cy, radius, dx, dy, feather, opacity;
        std::array<float, 3> delta;
    };
    const auto width = base.width();
    const auto height = base.height();
    const double longer = std::max(width, height);
    std::vector<Plan> plans;
    PixelReader planning(base);
    for (const auto& sp : spots) {
        if (sp.opacity <= 0.0F) continue;
        Plan p{};
        p.cx = static_cast<double>(sp.x) * (width - 1);
        p.cy = static_cast<double>(sp.y) * (height - 1);
        p.radius = std::max(1.0, static_cast<double>(sp.radius) * longer);
        p.dx = static_cast<double>(sp.source_x) * (width - 1) - p.cx;
        p.dy = static_cast<double>(sp.source_y) * (height - 1) - p.cy;
        p.feather = sp.feather;
        p.opacity = sp.opacity;
        if (sp.heal) {
            const auto target = ringMean(planning, p.cx, p.cy, p.radius, width, height);
            const auto source = ringMean(planning, p.cx + p.dx, p.cy + p.dy, p.radius, width, height);
            p.delta = {target[0] - source[0], target[1] - source[1], target[2] - source[2]};
        }
        plans.push_back(p);
    }
    if (plans.empty()) return TileStore(base);
    TileStore out = base.emptyLike(base.format());
    const auto coordinates = base.tileCoordinates();
    std::atomic<bool> failed{false};
    std::mutex write_mutex;
    const auto bpp = base.bytes_per_pixel();
    parallel::parallelFor(coordinates.size(), parallel::workerCount(workers), [&](std::size_t index, std::size_t) {
        if (failed.load()) return;
        const auto source_tile = base.readTile(coordinates[index]);
        if (source_tile == nullptr) {
            failed = true;
            return;
        }
        TileBuffer tile(*source_tile);
        PixelReader reader(base);
        const std::int64_t x0 = static_cast<std::int64_t>(coordinates[index].x) * kTileEdge;
        const std::int64_t y0 = static_cast<std::int64_t>(coordinates[index].y) * kTileEdge;
        for (std::size_t y = 0; y < kTileEdge; ++y) {
            for (std::size_t x = 0; x < kTileEdge; ++x) {
                const auto gx = x0 + static_cast<std::int64_t>(x);
                const auto gy = y0 + static_cast<std::int64_t>(y);
                if (gx >= static_cast<std::int64_t>(width) || gy >= static_cast<std::int64_t>(height)) continue;
                float px[4];
                bool touched = false;
                for (const auto& p : plans) {
                    const double d = std::hypot(static_cast<double>(gx) - p.cx, static_cast<double>(gy) - p.cy) / p.radius;
                    if (d >= 1.0) continue;
                    // Full strength in the core, a smooth falloff over the feather band at the rim.
                    const double inner = 1.0 - p.feather;
                    const double t = d <= inner ? 1.0 : std::clamp((1.0 - d) / std::max(1e-6, p.feather), 0.0, 1.0);
                    const double weight = p.opacity * t * t * (3.0 - 2.0 * t);
                    if (weight <= 0.0) continue;
                    if (!touched) {
                        paint::detail::readPixel(base.format(), tile.data() + (y * kTileEdge + x) * bpp, px);
                        touched = true;
                    }
                    float s[4];
                    reader.read(std::llround(static_cast<double>(gx) + p.dx), std::llround(static_cast<double>(gy) + p.dy), s);
                    for (int c = 0; c < 3; ++c) {
                        const float repaired = std::max(0.0F, s[c] + p.delta[c]);
                        px[c] = static_cast<float>(px[c] + (repaired - px[c]) * weight);
                    }
                }
                if (touched) paint::detail::writePixel(base.format(), tile.data() + (y * kTileEdge + x) * bpp, px);
            }
        }
        const std::lock_guard lock(write_mutex);
        if (!out.writeTile(coordinates[index], tile)) failed = true;
    });
    if (failed) {
        error = "could not apply spot repairs";
        return std::nullopt;
    }
    return out;
}

static std::optional<TileStore> applyLocalMasks(const TileStore& base, const std::vector<LocalMask>& masks, unsigned workers, std::string& error) {
    std::optional<TileStore> current(base);
    for (const auto& mask : masks) {
        if (!mask.enabled || mask.amount <= 0.0F) continue;
        Settings local;  // neutral except the mask's own adjustments: no detail work, no lens flags, identity curve and crop
        local.basic = mask.adjust;
        local.detail = Detail{0, 1, 0, 0, 0, 0};
        local.optics = Optics{false, false, 0, 0};
        local.effects = Effects{0, 50, 0, 25};
        Request inner;
        inner.worker_threads = workers;
        auto adjusted = render(*current, local, inner, error);
        if (!adjusted) return std::nullopt;
        auto coverage = localCoverage(mask, *current, workers, error);
        if (!coverage) return std::nullopt;
        auto blended = mask_blend::apply(*current, adjusted->pixels, *coverage, mask.amount, error);
        if (!blended) return std::nullopt;
        current.reset();
        current.emplace(std::move(*blended));
    }
    return current;
}

std::optional<Result> render(const TileStore& linear, const Settings& settings, const Request& request, std::string& error) {
    using Clock = std::chrono::steady_clock;
    const auto started = Clock::now();
    error.clear();
    try {
        if (!validate(settings, error)) return std::nullopt;
        if (linear.width() == 0 || linear.height() == 0 || (linear.format() != PixelFormat::RGBA16 && linear.format() != PixelFormat::RGBA32F)) {
            error = "Develop pipeline requires a non-empty scene-linear RGBA16 or RGBA32F source";
            return std::nullopt;
        }
        if (static_cast<unsigned>(request.mode) > static_cast<unsigned>(Mode::Preview) ||
            static_cast<unsigned>(request.preview_tier) > static_cast<unsigned>(PreviewTier::Settled) ||
            (request.mode != Mode::Preview && request.preview_tier != PreviewTier::Custom) ||
            request.preview_max_dimension == 0 || request.preview_max_dimension > 4096) {
            error = "invalid preview mode or max dimension (1–4096 required)";
            return std::nullopt;
        }
        ProgressRelay progress(request);
        if (!progress.report(0.0F)) { error = "cancelled"; return std::nullopt; }

        std::optional<TileStore> source;
        if (request.mode == Mode::Preview && std::max(linear.width(), linear.height()) > request.preview_max_dimension) {
            if (request.preview_cache) {
                source = request.preview_cache->lookup(request.source_revision, linear.width(), linear.height(),
                                                       linear.format(), request.preview_max_dimension);
            }
            const bool cache_hit = source.has_value();
            if (cache_hit && !progress.report(0.04F)) { error = "cancelled"; return std::nullopt; }
            if (cache_hit) {
                // A cached source proxy is independent of DevelopSettings; all adjustment stages
                // below still run for every slider change.
            } else {
            const double scale = static_cast<double>(request.preview_max_dimension) / std::max(linear.width(), linear.height());
            const auto width = std::max(1U, static_cast<std::uint32_t>(std::llround(linear.width() * scale)));
            const auto height = std::max(1U, static_cast<std::uint32_t>(std::llround(linear.height() * scale)));
            const TileStore* resizeSource = &linear;
            std::optional<TileStore> mipSource;
            std::uint32_t mipLevel = 0;
            while (mipLevel < 31U && std::max(linear.mip_width(mipLevel), linear.mip_height(mipLevel)) > request.preview_max_dimension * 2U) ++mipLevel;
            if (mipLevel > 0U) {
                const auto mipWidth = linear.mip_width(mipLevel), mipHeight = linear.mip_height(mipLevel);
                mipSource.emplace(linear.format(), mipWidth, mipHeight);
                const auto columns = 1U + (mipWidth - 1U) / kTileEdge;
                const auto rows = 1U + (mipHeight - 1U) / kTileEdge;
                const auto total = static_cast<std::size_t>(columns) * rows;
                std::unordered_set<std::uint64_t> expectedMipTiles;
                for (const auto coordinate : linear.tileCoordinates()) {
                    const auto mipX = static_cast<std::uint32_t>(coordinate.x) >> mipLevel;
                    const auto mipY = static_cast<std::uint32_t>(coordinate.y) >> mipLevel;
                    expectedMipTiles.insert((static_cast<std::uint64_t>(mipX) << 32U) | mipY);
                }
                std::size_t completed = 0;
                bool mipUnavailable = false;
                for (std::uint32_t ty = 0; ty < rows && !mipUnavailable; ++ty) {
                    for (std::uint32_t tx = 0; tx < columns && !mipUnavailable; ++tx, ++completed) {
                        if (!progress.report(0.04F * static_cast<float>(completed) / static_cast<float>(std::max<std::size_t>(1, total)))) {
                            error = "cancelled"; return std::nullopt;
                        }
                        const TileCoordinate coordinate{static_cast<std::int32_t>(tx), static_cast<std::int32_t>(ty)};
                        auto tile = linear.readMipTile(mipLevel, coordinate);
                        const auto key = (static_cast<std::uint64_t>(tx) << 32U) | ty;
                        if (!tile && expectedMipTiles.contains(key)) {
                            // The mip cache could not retain a tile that covers represented source
                            // pixels. Fall back to direct Lanczos so allocation pressure never turns
                            // valid image content into transparent pixels.
                            mipUnavailable = true;
                            break;
                        }
                        if (tile && !mipSource->writeTile(coordinate, *tile)) {
                            error = "preview mip tile allocation failed"; return std::nullopt;
                        }
                    }
                }
                if (mipUnavailable) mipSource.reset();
                else resizeSource = &*mipSource;
            }
            auto proxy = resample::resize(*resizeSource, width, height, resample::Filter::Lanczos3, error, request.worker_threads);
            if (!proxy) return std::nullopt;
            // Lanczos resize currently has no cancellation hook. Recheck generation immediately
            // when it returns, before caching its proxy or beginning adjustment kernels.
            if (!progress.report(0.04F)) { error = "cancelled"; return std::nullopt; }
            source.emplace(std::move(*proxy));
            if (request.preview_cache) {
                request.preview_cache->remember(request.source_revision, linear.width(), linear.height(),
                                                linear.format(), request.preview_max_dimension, *source);
            }
            }
        } else {
            source.emplace(linear);
        }

        bool lens_applied = false;
        std::string lens_note;
        if (request.lens_correction && (settings.optics.lens_profile || settings.optics.remove_chromatic_aberration)) {
            std::string lens_error;
            if (auto corrected = request.lens_correction(*source, settings.optics.lens_profile, settings.optics.remove_chromatic_aberration, lens_error)) {
                source.reset();
                source.emplace(std::move(*corrected));
                lens_applied = true;
            } else {
                lens_note = "Lens correction was not applied: " + (lens_error.empty() ? std::string("no lens profile for this shot") : lens_error);
            }
        }
        auto balanced = applyWhiteBalance(*source, settings.white_balance, request, progress, error);
        if (!balanced) return std::nullopt;
        if (settings.optics.vignetting != 0.0F || settings.optics.distortion != 0.0F) {
            croptop::filters::LensCorrectionSpec optics;
            optics.distortion = settings.optics.distortion;
            optics.vignette_amount = settings.optics.vignetting;
            if (!croptop::filters::lensCorrection(*balanced, optics, nullptr, error, request.worker_threads)) return std::nullopt;
        }

        if (settings.optics.defringe_purple > 0.0F || settings.optics.defringe_green > 0.0F) {
            croptop::lens::DefringeSpec fringe;
            fringe.purple_amount = settings.optics.defringe_purple;
            fringe.green_amount = settings.optics.defringe_green;
            fringe.worker_threads = request.worker_threads;
            auto cleaned = croptop::lens::defringe(*balanced, fringe, error);
            if (!cleaned) return std::nullopt;
            balanced.reset();
            balanced.emplace(std::move(*cleaned));
        }

        ToneSettings tone;
        tone.exposure = settings.basic.exposure; tone.contrast = settings.basic.contrast;
        tone.highlights = settings.basic.highlights; tone.shadows = settings.basic.shadows;
        tone.whites = settings.basic.whites; tone.blacks = settings.basic.blacks;
        tone.texture = settings.basic.texture; tone.clarity = settings.basic.clarity;
        tone.dehaze = settings.basic.dehaze; tone.vibrance = settings.basic.vibrance; tone.saturation = settings.basic.saturation;
        tone.point_curve.points = settings.tone_curve.points;
        stageProgress(tone, request, progress, 0.05F, 0.40F);
        std::optional<TileStore> toned;
        bool tone_on_gpu = false, color_on_gpu = false;
#if defined(CROPTOP_HAS_GPU_DEVELOP)
        if (request.gpu != nullptr) {
            std::string gpu_error;  // any GPU failure (texture, device loss) just falls back to the CPU reference
            toned = applyToneGpu(*request.gpu, *balanced, tone, gpu_error);
            tone_on_gpu = toned.has_value() && !tone.isIdentity();
        }
#endif
        if (!toned) toned = applyTone(*balanced, tone, error);
        if (!toned) return std::nullopt;
        balanced.reset();
        if (!progress.report(0.45F)) { error = "cancelled"; return std::nullopt; }

        ColorSettings color;
        color.look = mapLook(settings.profile);
        for (std::size_t i = 0; i < settings.mixer.size(); ++i) {
            color.mixer.hue[i] = settings.mixer[i].hue;
            color.mixer.saturation[i] = settings.mixer[i].saturation;
            color.mixer.luminance[i] = settings.mixer[i].luminance;
        }
        mapWheel(settings.grading.shadows, color.grading.shadows); mapWheel(settings.grading.midtones, color.grading.midtones);
        mapWheel(settings.grading.highlights, color.grading.highlights); mapWheel(settings.grading.global, color.grading.global);
        color.grading.blending = settings.grading.blending; color.grading.balance = settings.grading.balance;
        color.calibration.shadow_tint = settings.calibration.shadow_tint;
        color.calibration.red_hue = settings.calibration.red_hue; color.calibration.red_saturation = settings.calibration.red_saturation;
        color.calibration.green_hue = settings.calibration.green_hue; color.calibration.green_saturation = settings.calibration.green_saturation;
        color.calibration.blue_hue = settings.calibration.blue_hue; color.calibration.blue_saturation = settings.calibration.blue_saturation;
        stageProgress(color, request, progress, 0.45F, 0.25F);
        std::optional<TileStore> colored;
#if defined(CROPTOP_HAS_GPU_DEVELOP)
        if (request.gpu != nullptr) {
            std::string gpu_error;
            colored = applyColorGpu(*request.gpu, *toned, color, gpu_error);
            color_on_gpu = colored.has_value() && !color.isIdentity();
        }
#endif
        if (!colored) colored = applyColor(*toned, color, error);
        if (!colored) return std::nullopt;
        toned.reset();
        if (!progress.report(0.70F)) { error = "cancelled"; return std::nullopt; }

        if (!settings.spots.empty()) {
            auto repaired = applySpots(*colored, settings.spots, request.worker_threads, error);
            if (!repaired) return std::nullopt;
            colored.reset();
            colored.emplace(std::move(*repaired));
        }

        std::vector<std::string> geometry_warnings;
        if (settings.upright != Upright::Off) {
            geometry::develop_bridge::Request upright;
            switch (settings.upright) {
                case Upright::Level: upright.mode = geometry::develop_bridge::Mode::Level; break;
                case Upright::Vertical: upright.mode = geometry::develop_bridge::Mode::Vertical; break;
                case Upright::Full: upright.mode = geometry::develop_bridge::Mode::Full; break;
                case Upright::Guided: {
                    upright.mode = geometry::develop_bridge::Mode::Guided;
                    const double w = colored->width(), h = colored->height();
                    for (const auto& g : settings.upright_guides) upright.guides.push_back({g.x1 * w, g.y1 * h, g.x2 * w, g.y2 * h});
                    break;
                }
                case Upright::Manual: {
                    upright.mode = geometry::develop_bridge::Mode::Manual;
                    const auto& m = settings.upright_manual;
                    upright.manual.vertical = m.vertical; upright.manual.horizontal = m.horizontal; upright.manual.rotate = m.rotate;
                    upright.manual.aspect = m.aspect; upright.manual.scale = m.scale; upright.manual.x_offset = m.x_offset; upright.manual.y_offset = m.y_offset;
                    break;
                }
                default: upright.mode = geometry::develop_bridge::Mode::Auto; break;
            }
            upright.auto_crop = true;
            geometry::develop_bridge::Error upright_error;
            const auto solved = geometry::develop_bridge::analyze(*colored, upright, upright_error);
            if (!solved) {
                geometry_warnings.push_back("Upright was not applied: " + upright_error.message);
            } else {
                for (const auto& w : solved->warnings) geometry_warnings.push_back("Upright: " + w.message);
                auto warped = geometry::develop_bridge::render(*colored, *solved, resample::Filter::Lanczos3, upright_error, request.worker_threads);
                if (!warped) {
                    geometry_warnings.push_back("Upright was not applied: " + upright_error.message);
                } else {
                    colored.reset();
                    colored.emplace(std::move(*warped));
                }
            }
        }

        // Crop frame and straighten angle (the frame is relative to the straightened image), before post-crop vignette and grain.
        if (const auto& c = settings.crop; c.left != 0 || c.top != 0 || c.right != 1 || c.bottom != 1 || c.angle != 0) {
            auto cropped = applyCrop(*colored, c, request.worker_threads, error);
            if (!cropped) return std::nullopt;
            colored.reset();
            colored.emplace(std::move(*cropped));
        }

        if (!settings.masks.empty()) {
            auto masked = applyLocalMasks(*colored, settings.masks, request.worker_threads, error);
            if (!masked) return std::nullopt;
            colored.reset();
            colored.emplace(std::move(*masked));
        }

        DetailSettings detail;
        const double preview_scale = request.mode == Mode::Preview
                                         ? std::min(static_cast<double>(source->width()) / linear.width(),
                                                    static_cast<double>(source->height()) / linear.height())
                                         : 1.0;
        const bool skip_subpixel_detail = request.mode == Mode::Preview && preview_scale < 0.5 &&
                                          request.preview_tier != PreviewTier::Settled;
        if (!skip_subpixel_detail) {
            detail.sharpening = {settings.detail.sharpen_amount, settings.detail.sharpen_radius,
                                 settings.detail.sharpen_detail, settings.detail.sharpen_masking};
            detail.noise.luminance = settings.detail.luminance_noise;
            detail.noise.color = settings.detail.color_noise;
            detail.grain.amount = settings.effects.grain_amount;
        } else {
            detail.sharpening.amount = 0;
            detail.noise.luminance = 0;
            detail.noise.color = 0;
            detail.grain.amount = 0;
        }
        detail.vignette.amount = settings.effects.vignette_amount; detail.vignette.midpoint = settings.effects.vignette_midpoint;
        detail.grain.size = settings.effects.grain_size; detail.grain.seed = request.grain_seed;
        stageProgress(detail, request, progress, 0.70F, 0.30F);
        auto output = applyDetail(*colored, detail, error);
        if (!output) return std::nullopt;
        colored.reset();
        if (!progress.report(1.0F)) { error = "cancelled"; return std::nullopt; }

        Result result(std::move(*output));
        result.tone_on_gpu = tone_on_gpu;
        result.color_on_gpu = color_on_gpu;
        result.preview_tier = request.preview_tier;
        result.preview_generation = request.preview_generation;
        for (auto& w : geometry_warnings) result.warnings.push_back(std::move(w));
        if (settings.white_balance.preset == WhiteBalancePreset::Auto) {
            result.warnings.emplace_back("Auto white balance was not computed because source statistics/metadata are unavailable");
        }
        if (!lens_note.empty()) {
            result.warnings.push_back(lens_note);
        } else if (!lens_applied && !request.lens_correction && (settings.optics.lens_profile || settings.optics.remove_chromatic_aberration)) {
            result.warnings.emplace_back("Lens-profile/chromatic-aberration correction was not applied because lens metadata and profiles are unavailable");
        }
        if (skip_subpixel_detail) {
            result.warnings.emplace_back("Interactive preview omitted sharpening, noise reduction, and grain below 0.5 scale; full-resolution rendering applies them");
        }
        result.elapsed = std::chrono::duration_cast<std::chrono::nanoseconds>(Clock::now() - started);
        return result;
    } catch (const std::exception& exception) {
        error = std::string("Develop pipeline failed: ") + exception.what();
        return std::nullopt;
    } catch (...) {
        error = "Develop pipeline failed with an unknown error";
        return std::nullopt;
    }
}

} // namespace croptop::develop::pipeline
