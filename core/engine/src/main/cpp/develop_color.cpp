// Develop colour operations.
#include "develop_color.h"

#include <algorithm>
#include <atomic>
#include <cmath>
#include <cstring>
#include <vector>

#include "parallel_util.h"
#include "pixel_plane.h"

namespace croptop::develop {
namespace {

constexpr float kPi = 3.14159265358979323846F;
using Mat3 = std::array<float, 9>;

struct Lab {
    float L, a, b;
};

Lab toOklab(float r, float g, float b) {
    const float l = std::cbrt(0.4122214708F * r + 0.5363325363F * g + 0.0514459929F * b);
    const float m = std::cbrt(0.2119034982F * r + 0.6806995451F * g + 0.1073969566F * b);
    const float s = std::cbrt(0.0883024619F * r + 0.2817188376F * g + 0.6299787005F * b);
    return {0.2104542553F * l + 0.7936177850F * m - 0.0040720468F * s, 1.9779984951F * l - 2.4285922050F * m + 0.4505937099F * s,
            0.0259040371F * l + 0.7827717662F * m - 0.8086757660F * s};
}

std::array<float, 3> fromOklab(const Lab& c) {
    const float l = c.L + 0.3963377774F * c.a + 0.2158037573F * c.b;
    const float m = c.L - 0.1055613458F * c.a - 0.0638541728F * c.b;
    const float s = c.L - 0.0894841775F * c.a - 1.2914855480F * c.b;
    const float l3 = l * l * l, m3 = m * m * m, s3 = s * s * s;
    return {4.0767416621F * l3 - 3.3077115913F * m3 + 0.2309699292F * s3, -1.2684380046F * l3 + 2.6097574011F * m3 - 0.3413193965F * s3,
            -0.0041960863F * l3 - 0.7034186147F * m3 + 1.7076147010F * s3};
}

Mat3 mul(const Mat3& a, const Mat3& b) {
    Mat3 m{};
    for (int r = 0; r < 3; ++r)
        for (int c = 0; c < 3; ++c)
            for (int k = 0; k < 3; ++k) m[static_cast<std::size_t>(r * 3 + c)] += a[static_cast<std::size_t>(r * 3 + k)] * b[static_cast<std::size_t>(k * 3 + c)];
    return m;
}

std::optional<Mat3> inverse(const Mat3& a) {
    const double det = static_cast<double>(a[0]) * (a[4] * a[8] - a[5] * a[7]) - static_cast<double>(a[1]) * (a[3] * a[8] - a[5] * a[6]) +
                       static_cast<double>(a[2]) * (a[3] * a[7] - a[4] * a[6]);
    if (!std::isfinite(det) || std::abs(det) < 1e-9) return std::nullopt;
    const auto k = static_cast<float>(1.0 / det);
    return Mat3{(a[4] * a[8] - a[5] * a[7]) * k, (a[2] * a[7] - a[1] * a[8]) * k, (a[1] * a[5] - a[2] * a[4]) * k,
                (a[5] * a[6] - a[3] * a[8]) * k, (a[0] * a[8] - a[2] * a[6]) * k, (a[2] * a[3] - a[0] * a[5]) * k,
                (a[3] * a[7] - a[4] * a[6]) * k, (a[1] * a[6] - a[0] * a[7]) * k, (a[0] * a[4] - a[1] * a[3]) * k};
}

std::array<float, 3> times(const Mat3& m, const float* v) {
    return {m[0] * v[0] + m[1] * v[1] + m[2] * v[2], m[3] * v[0] + m[4] * v[1] + m[5] * v[2], m[6] * v[0] + m[7] * v[1] + m[8] * v[2]};
}

/**
 * Calibration as a matrix: each primary is moved in OkLCh (hue ±100 → ±20°, saturation ±100 → ×0.5…1.5),
 * then the columns are rescaled so that white stays white.
 */
std::optional<Mat3> calibrationMatrix(const Calibration& c) {
    const float hues[3] = {c.red_hue, c.green_hue, c.blue_hue}, sats[3] = {c.red_saturation, c.green_saturation, c.blue_saturation};
    Mat3 m{};
    for (int p = 0; p < 3; ++p) {
        float primary[3] = {0, 0, 0};
        primary[p] = 1.0F;
        Lab lab = toOklab(primary[0], primary[1], primary[2]);
        const float chroma = std::hypot(lab.a, lab.b) * (1.0F + 0.5F * sats[p] / 100.0F);
        const float angle = std::atan2(lab.b, lab.a) + hues[p] / 100.0F * 20.0F * kPi / 180.0F;
        lab.a = chroma * std::cos(angle);
        lab.b = chroma * std::sin(angle);
        const auto moved = fromOklab(lab);
        for (int r = 0; r < 3; ++r) m[static_cast<std::size_t>(r * 3 + p)] = moved[static_cast<std::size_t>(r)];
    }
    const auto inv = inverse(m);
    if (!inv) return std::nullopt;
    const float ones[3] = {1, 1, 1};
    const auto k = times(*inv, ones);  // M·diag(k)·1 = 1
    for (int r = 0; r < 3; ++r)
        for (int p = 0; p < 3; ++p) m[static_cast<std::size_t>(r * 3 + p)] *= k[static_cast<std::size_t>(p)];
    return m;
}

// Range centres in OkLCh hue (degrees): red, orange, yellow, green, aqua, blue, purple, magenta.
constexpr std::array<float, 8> kCentres{25, 55, 100, 140, 195, 255, 295, 335};

/** Partition-of-unity weights over the 8 ranges (cos² between neighbouring centres). */
std::array<float, 8> rangeWeights(float hue_degrees) {
    std::array<float, 8> w{};
    float h = std::fmod(hue_degrees, 360.0F);
    if (h < 0) h += 360.0F;
    for (int i = 0; i < 8; ++i) {
        const float c0 = kCentres[static_cast<std::size_t>(i)];
        float c1 = kCentres[static_cast<std::size_t>((i + 1) % 8)];
        if (c1 <= c0) c1 += 360.0F;
        float hh = h;
        if (hh < c0) hh += 360.0F;
        if (hh >= c0 && hh <= c1) {
            const float t = (hh - c0) / (c1 - c0);
            const float cw = std::cos(t * kPi * 0.5F);
            w[static_cast<std::size_t>(i)] = cw * cw;
            w[static_cast<std::size_t>((i + 1) % 8)] = 1.0F - cw * cw;
            break;
        }
    }
    return w;
}

float smoothstep(float e0, float e1, float x) {
    const float t = std::clamp((x - e0) / (e1 - e0), 0.0F, 1.0F);
    return t * t * (3.0F - 2.0F * t);
}

struct LookParams {
    float chroma = 1.0F;
    std::array<float, 8> range_sat{};  // extra per-range saturation (slider units)
    std::array<float, 8> range_lum{};
    bool mono = false;
};

LookParams lookParams(Look look) {
    LookParams p;
    switch (look) {
        case Look::None: break;
        case Look::Neutral: break;  // the model's default profile: a faithful baseline, no colour change
        case Look::Vivid: p.chroma = 1.2F; p.range_sat = {5, 5, 5, 10, 10, 10, 0, 0}; break;
        case Look::Portrait: p.range_sat = {-10, -15, -5, 0, 0, 0, 0, -5}; p.range_lum = {5, 8, 0, 0, 0, 0, 0, 0}; break;
        case Look::Landscape: p.chroma = 1.05F; p.range_sat = {0, 0, 10, 20, 15, 20, 0, 0}; p.range_lum = {0, 0, 0, -5, 0, -8, 0, 0}; break;
        case Look::Monochrome: p.mono = true; p.range_lum = {10, 10, 5, -5, -5, -15, -5, 5}; break;  // warm-filter B&W mix
    }
    return p;
}

}  // namespace

bool ColorMixer::isIdentity() const {
    const auto zero = [](const std::array<float, 8>& a) { return std::all_of(a.begin(), a.end(), [](float v) { return v == 0.0F; }); };
    return zero(hue) && zero(saturation) && zero(luminance);
}
bool ColorGrading::isIdentity() const {
    for (const auto* w : {&shadows, &midtones, &highlights, &global}) {
        if (w->saturation != 0.0F || w->luminance != 0.0F) return false;
    }
    return true;
}
bool Calibration::isIdentity() const {
    return shadow_tint == 0 && red_hue == 0 && red_saturation == 0 && green_hue == 0 && green_saturation == 0 && blue_hue == 0 && blue_saturation == 0;
}
bool ColorSettings::isIdentity() const {
    return calibration.isIdentity() && (look == Look::None || look == Look::Neutral) && mixer.isIdentity() && grading.isIdentity();
}

std::optional<ColorPlan> makeColorPlan(const ColorSettings& s, PixelFormat format, bool& identity, std::string& error) {
    identity = false;
    if (format != PixelFormat::RGBA16 && format != PixelFormat::RGBA32F) {
        error = "develop colour needs a linear RGBA16 or RGBA32F layer";
        return std::nullopt;
    }
    const auto slider = [](float v, float lo, float hi) { return std::isfinite(v) && v >= lo && v <= hi; };
    bool valid = slider(s.grading.blending, 0, 100) && slider(s.grading.balance, -100, 100) && slider(s.calibration.shadow_tint, -100, 100);
    for (const float v : {s.calibration.red_hue, s.calibration.red_saturation, s.calibration.green_hue, s.calibration.green_saturation,
                          s.calibration.blue_hue, s.calibration.blue_saturation}) {
        valid = valid && slider(v, -100, 100);
    }
    for (int i = 0; i < 8; ++i) {
        valid = valid && slider(s.mixer.hue[static_cast<std::size_t>(i)], -100, 100) && slider(s.mixer.saturation[static_cast<std::size_t>(i)], -100, 100) &&
                slider(s.mixer.luminance[static_cast<std::size_t>(i)], -100, 100);
    }
    for (const auto* w : {&s.grading.shadows, &s.grading.midtones, &s.grading.highlights, &s.grading.global}) {
        valid = valid && std::isfinite(w->hue) && slider(w->saturation, 0, 100) && slider(w->luminance, -100, 100);
    }
    if (!valid || static_cast<int>(s.look) > static_cast<int>(Look::Monochrome) ||
        std::any_of(s.to_linear_srgb.begin(), s.to_linear_srgb.end(), [](float v) { return !std::isfinite(v); })) {
        error = "develop colour settings are out of range";
        return std::nullopt;
    }
    if (s.isIdentity()) {
        identity = true;
        return std::nullopt;
    }
    const auto from_srgb = inverse(s.to_linear_srgb);
    const auto calibration = s.calibration.isIdentity() ? std::optional<Mat3>(Mat3{1, 0, 0, 0, 1, 0, 0, 0, 1}) : calibrationMatrix(s.calibration);
    if (!from_srgb || !calibration) {
        error = "colour matrices must be invertible";
        return std::nullopt;
    }
    ColorPlan plan;
    plan.into = mul(*calibration, s.to_linear_srgb);
    plan.from_srgb = *from_srgb;
    const LookParams look = lookParams(s.look);
    plan.look_chroma = look.chroma;
    plan.mono = look.mono;
    for (int i = 0; i < 8; ++i) {
        const auto k = static_cast<std::size_t>(i);
        plan.hue[k] = s.mixer.hue[k];
        plan.saturation[k] = s.mixer.saturation[k] + look.range_sat[k];
        plan.luminance[k] = s.mixer.luminance[k] + look.range_lum[k];
    }
    plan.mixing = !s.mixer.isIdentity() || (s.look != Look::None && s.look != Look::Neutral);
    plan.split = 0.56F + s.grading.balance / 100.0F * 0.2F;  // Oklab L of 18 % grey ≈ 0.56
    plan.spread = 0.1F + 0.3F * s.grading.blending / 100.0F;
    const auto wheel = [](const GradeWheel& w) {
        const float a = w.hue * kPi / 180.0F;
        return std::array<float, 3>{w.luminance / 100.0F * 0.12F, std::cos(a) * w.saturation / 100.0F * 0.06F, std::sin(a) * w.saturation / 100.0F * 0.06F};
    };
    plan.shadows = wheel(s.grading.shadows);
    plan.midtones = wheel(s.grading.midtones);
    plan.highlights = wheel(s.grading.highlights);
    plan.global = wheel(s.grading.global);
    plan.grading = !s.grading.isIdentity();
    plan.tint = s.calibration.shadow_tint / 100.0F * 0.03F;  // magenta (+a) / green (−a) in the shadows
    return plan;
}

std::optional<TileStore> applyColor(const TileStore& linear, const ColorSettings& s, std::string& error) {
    bool identity = false;
    const auto maybe_plan = makeColorPlan(s, linear.format(), identity, error);
    if (identity) return linear;
    if (!maybe_plan) return std::nullopt;
    const ColorPlan& plan = *maybe_plan;
    const Mat3& into = plan.into;
    const Mat3& from_srgb_matrix = plan.from_srgb;
    const auto& mix_sat = plan.saturation;
    const auto& mix_lum = plan.luminance;
    const bool mixing = plan.mixing;
    const float split = plan.split, spread = plan.spread, tint = plan.tint;
    const auto &ws = plan.shadows, &wm = plan.midtones, &wh = plan.highlights, &wg = plan.global;
    struct LookView { float chroma; bool mono; } look{plan.look_chroma, plan.mono};

    // Hue-indexed mixer table (0.25° steps, linear interpolation; the underlying weights are smooth cos² ramps).
    constexpr std::size_t kHueSteps = 1440;
    struct HueTable {
        std::vector<float> ds, dl, cos_shift, sin_shift;
    } hue_table;
    if (mixing) {
        hue_table.ds.resize(kHueSteps + 1);
        hue_table.dl.resize(kHueSteps + 1);
        hue_table.cos_shift.resize(kHueSteps + 1);
        hue_table.sin_shift.resize(kHueSteps + 1);
        for (std::size_t i = 0; i <= kHueSteps; ++i) {
            const auto w = rangeWeights(static_cast<float>(i) * 360.0F / static_cast<float>(kHueSteps));
            float dh = 0, dsum = 0, dlum = 0;
            for (std::size_t k = 0; k < 8; ++k) {
                dh += w[k] * plan.hue[k];
                dsum += w[k] * mix_sat[k];
                dlum += w[k] * mix_lum[k];
            }
            const float shift = dh / 100.0F * 30.0F * kPi / 180.0F;
            hue_table.ds[i] = dsum;
            hue_table.dl[i] = dlum;
            hue_table.cos_shift[i] = std::cos(shift);
            hue_table.sin_shift[i] = std::sin(shift);
        }
    }

    TileStore out(linear.format(), linear.width(), linear.height());
    const auto coordinates = linear.tileCoordinates();
    const unsigned workers = parallel::workerCount(s.worker_threads);
    std::atomic<std::size_t> done{0};
    std::atomic<bool> cancelled{false}, failed{false};
    const auto bpp = linear.bytes_per_pixel();
    parallel::parallelFor(coordinates.size(), workers, [&](std::size_t index, std::size_t worker) {
        if (cancelled.load()) return;
        const auto source = linear.readTile(coordinates[index]);
        if (source == nullptr) return;
        TileBuffer tile(out.tile_byte_size(), 0);
        for (std::size_t p = 0; p < static_cast<std::size_t>(kTileEdge) * kTileEdge; ++p) {
            float px[4];
            paint::detail::readPixel(linear.format(), source->data() + p * bpp, px);
            const auto rgb = times(into, px);
            Lab lab = toOklab(rgb[0], rgb[1], rgb[2]);
            if (tint != 0.0F) lab.a += tint * (1.0F - smoothstep(0.1F, 0.5F, lab.L));
            const float chroma0 = std::hypot(lab.a, lab.b);
            float chroma = chroma0;
            if (mixing && chroma0 > 1e-6F) {
                // Range weights, hue shift and the rotation it implies depend on the hue only: read them from the table.
                const float hue = std::atan2(lab.b, lab.a) * (180.0F / kPi);
                const float position = (hue < 0.0F ? hue + 360.0F : hue) * (static_cast<float>(kHueSteps) / 360.0F);
                const auto index = std::min(static_cast<std::size_t>(position), kHueSteps - 1U);
                const float f = position - static_cast<float>(index);
                const auto at = [&](const std::vector<float>& t) { return t[index] + (t[index + 1] - t[index]) * f; };
                const float ds = at(hue_table.ds), dl = at(hue_table.dl), cd = at(hue_table.cos_shift), sd = at(hue_table.sin_shift);
                const float strength = std::min(1.0F, chroma0 / 0.05F);  // neutrals are not coloured by any range
                chroma = chroma0 * std::max(0.0F, 1.0F + ds / 100.0F * strength) * look.chroma;
                lab.L += dl / 100.0F * 0.12F * strength;
                if (look.mono) chroma = 0.0F;
                // Rotating the unit vector by the hue shift replaces cos/sin of the shifted angle.
                const float ua = lab.a / chroma0, ub = lab.b / chroma0;
                lab.a = chroma * (ua * cd - ub * sd);
                lab.b = chroma * (ua * sd + ub * cd);
            } else if (look.mono) {
                lab.a = 0.0F;
                lab.b = 0.0F;
            }
            if (plan.grading) {
                const float w_shadow = 1.0F - smoothstep(split - spread, split, lab.L);
                const float w_highlight = smoothstep(split, split + spread, lab.L);
                const float w_mid = std::max(0.0F, 1.0F - w_shadow - w_highlight);
                for (int k = 0; k < 3; ++k) {
                    const float d = w_shadow * ws[static_cast<std::size_t>(k)] + w_mid * wm[static_cast<std::size_t>(k)] +
                                    w_highlight * wh[static_cast<std::size_t>(k)] + wg[static_cast<std::size_t>(k)];
                    (k == 0 ? lab.L : k == 1 ? lab.a : lab.b) += d;
                }
            }
            const auto srgb = fromOklab(lab);
            const auto back = times(from_srgb_matrix, srgb.data());
            float result[4] = {back[0], back[1], back[2], px[3]};
            if (linear.format() == PixelFormat::RGBA16) {
                for (int c = 0; c < 3; ++c) result[c] = std::max(0.0F, result[c]);
            }
            paint::detail::writePixel(out.format(), tile.data() + p * bpp, result);
        }
        if (!out.writeTile(coordinates[index], tile)) {
            failed.store(true);
            cancelled.store(true);
            return;
        }
        const auto finished = done.fetch_add(1) + 1;
        if (worker == 0 && s.progress && !s.progress(static_cast<float>(finished) / static_cast<float>(coordinates.size()))) cancelled.store(true);
    });
    if (cancelled.load()) {
        error = failed.load() ? "could not store developed pixels" : "cancelled";
        return std::nullopt;
    }
    return out;
}

}  // namespace croptop::develop
