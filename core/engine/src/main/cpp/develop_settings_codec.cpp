#include "develop_settings_codec.h"

#include <algorithm>
#include <cmath>

namespace croptop::develop {

std::vector<float> encodeSettings(const pipeline::Settings& s) {
    std::vector<float> v;
    v.reserve(kSettingsFixedFloats + s.tone_curve.points.size() * 2U);
    const auto push = [&](float x) { v.push_back(x); };
    push(static_cast<float>(s.version));
    push(static_cast<float>(s.profile));
    push(static_cast<float>(s.white_balance.preset));
    push(s.white_balance.temperature);
    push(s.white_balance.tint);
    for (const float x : {s.basic.exposure, s.basic.contrast, s.basic.highlights, s.basic.shadows, s.basic.whites, s.basic.blacks, s.basic.texture,
                          s.basic.clarity, s.basic.dehaze, s.basic.vibrance, s.basic.saturation})
        push(x);
    for (const auto& m : s.mixer) {
        push(m.hue);
        push(m.saturation);
        push(m.luminance);
    }
    for (const auto* w : {&s.grading.shadows, &s.grading.midtones, &s.grading.highlights, &s.grading.global}) {
        push(w->hue);
        push(w->saturation);
        push(w->luminance);
    }
    push(s.grading.blending);
    push(s.grading.balance);
    for (const float x : {s.detail.sharpen_amount, s.detail.sharpen_radius, s.detail.sharpen_detail, s.detail.sharpen_masking, s.detail.luminance_noise,
                          s.detail.color_noise})
        push(x);
    for (const float x : {s.effects.vignette_amount, s.effects.vignette_midpoint, s.effects.grain_amount, s.effects.grain_size}) push(x);
    push(s.optics.lens_profile ? 1.0F : 0.0F);
    push(s.optics.remove_chromatic_aberration ? 1.0F : 0.0F);
    push(s.optics.vignetting);
    push(s.optics.distortion);
    for (const float x : {s.crop.left, s.crop.top, s.crop.right, s.crop.bottom, s.crop.angle}) push(x);
    push(static_cast<float>(s.tone_curve.points.size()));
    for (const auto& [x, y] : s.tone_curve.points) {
        push(x);
        push(y);
    }
    // Calibration (7 floats) and spots (count, 8 floats each) then masks; all optional, but anything after the curve needs the
    // calibration and spot blocks to keep its position.
    if (!s.calibration.isIdentity() || s.upright != pipeline::Upright::Off || s.upright_manual.vertical != 0.0F || s.upright_manual.scale != 100.0F || !s.upright_guides.empty() || s.optics.defringe_purple != 0.0F || s.optics.defringe_green != 0.0F ||
        !s.spots.empty() || !s.masks.empty()) {
        for (const float x : {s.calibration.shadow_tint, s.calibration.red_hue, s.calibration.red_saturation, s.calibration.green_hue,
                              s.calibration.green_saturation, s.calibration.blue_hue, s.calibration.blue_saturation})
            push(x);
        push(static_cast<float>(s.upright));
        push(s.optics.defringe_purple);
        push(s.optics.defringe_green);
        for (const float x : {s.upright_manual.vertical, s.upright_manual.horizontal, s.upright_manual.rotate, s.upright_manual.aspect,
                              s.upright_manual.scale, s.upright_manual.x_offset, s.upright_manual.y_offset})
            push(x);
        push(static_cast<float>(s.upright_guides.size()));
        for (const auto& g : s.upright_guides)
            for (const float x : {g.x1, g.y1, g.x2, g.y2}) push(x);
        push(static_cast<float>(s.spots.size()));
        for (const auto& sp : s.spots) {
            for (const float x : {sp.x, sp.y, sp.radius, sp.source_x, sp.source_y, sp.feather, sp.opacity}) push(x);
            push(sp.heal ? 1.0F : 0.0F);
        }
    }
    if (!s.masks.empty()) {
        push(static_cast<float>(s.masks.size()));
        for (const auto& m : s.masks) {
            push(m.enabled ? 1.0F : 0.0F);
            push(m.invert ? 1.0F : 0.0F);
            push(m.amount);
            for (const float x : {m.adjust.exposure, m.adjust.contrast, m.adjust.highlights, m.adjust.shadows, m.adjust.whites, m.adjust.blacks,
                                  m.adjust.texture, m.adjust.clarity, m.adjust.dehaze, m.adjust.vibrance, m.adjust.saturation})
                push(x);
            push(static_cast<float>(m.components.size()));
            for (const auto& c : m.components) {
                push(static_cast<float>(c.kind));
                push(static_cast<float>(c.op));
                for (const float x : c.p) push(x);
                if (c.kind == pipeline::MaskKind::Ai) {
                    push(static_cast<float>(c.bitmap_width));
                    push(static_cast<float>(c.bitmap_height));
                    for (const auto b : c.bitmap) push(static_cast<float>(b));
                }
                push(static_cast<float>(c.strokes.size()));
                for (const auto& st : c.strokes) {
                    push(st.erase ? 1.0F : 0.0F);
                    push(st.size);
                    push(st.feather);
                    push(st.flow);
                    push(static_cast<float>(st.points.size()));
                    for (const auto& [x, y] : st.points) {
                        push(x);
                        push(y);
                    }
                }
            }
        }
    }
    return v;
}

std::optional<pipeline::Settings> decodeSettings(std::span<const float> v, std::string& error) {
    if (v.size() < kSettingsFixedFloats) {
        error = "develop settings array is too short";
        return std::nullopt;
    }
    if (!std::all_of(v.begin(), v.end(), [](float x) { return std::isfinite(x); })) {
        error = "develop settings contain a non-finite value";
        return std::nullopt;
    }
    if (v[0] != static_cast<float>(pipeline::kSettingsVersion)) {
        error = "unsupported develop settings version";
        return std::nullopt;
    }
    const auto count_f = v[73];
    if (count_f < 2.0F || count_f > static_cast<float>(kSettingsMaxCurvePoints) || count_f != std::floor(count_f) ||
        v.size() < kSettingsFixedFloats + static_cast<std::size_t>(count_f) * 2U) {
        error = "tone curve point count does not match the settings array";
        return std::nullopt;
    }
    const auto enum_value = [&](float x, int max) -> std::optional<int> {
        const int i = static_cast<int>(x);
        if (static_cast<float>(i) != x || i < 0 || i > max) return std::nullopt;
        return i;
    };
    const auto profile = enum_value(v[1], static_cast<int>(pipeline::Profile::Monochrome));
    const auto preset = enum_value(v[2], static_cast<int>(pipeline::WhiteBalancePreset::Custom));
    if (!profile || !preset) {
        error = "develop settings contain an unknown profile or white-balance preset";
        return std::nullopt;
    }
    pipeline::Settings s;
    s.version = pipeline::kSettingsVersion;
    s.profile = static_cast<pipeline::Profile>(*profile);
    s.white_balance.preset = static_cast<pipeline::WhiteBalancePreset>(*preset);
    s.white_balance.temperature = v[3];
    s.white_balance.tint = v[4];
    std::size_t i = 5;
    float* basic[] = {&s.basic.exposure, &s.basic.contrast, &s.basic.highlights, &s.basic.shadows, &s.basic.whites, &s.basic.blacks,
                      &s.basic.texture, &s.basic.clarity, &s.basic.dehaze, &s.basic.vibrance, &s.basic.saturation};
    for (auto* p : basic) *p = v[i++];
    for (auto& m : s.mixer) {
        m.hue = v[i++];
        m.saturation = v[i++];
        m.luminance = v[i++];
    }
    for (auto* w : {&s.grading.shadows, &s.grading.midtones, &s.grading.highlights, &s.grading.global}) {
        w->hue = v[i++];
        w->saturation = v[i++];
        w->luminance = v[i++];
    }
    s.grading.blending = v[i++];
    s.grading.balance = v[i++];
    float* detail[] = {&s.detail.sharpen_amount, &s.detail.sharpen_radius, &s.detail.sharpen_detail, &s.detail.sharpen_masking,
                       &s.detail.luminance_noise, &s.detail.color_noise};
    for (auto* p : detail) *p = v[i++];
    s.effects.vignette_amount = v[i++];
    s.effects.vignette_midpoint = v[i++];
    s.effects.grain_amount = v[i++];
    s.effects.grain_size = v[i++];
    s.optics.lens_profile = v[i++] != 0.0F;
    s.optics.remove_chromatic_aberration = v[i++] != 0.0F;
    s.optics.vignetting = v[i++];
    s.optics.distortion = v[i++];
    s.crop.left = v[i++];
    s.crop.top = v[i++];
    s.crop.right = v[i++];
    s.crop.bottom = v[i++];
    s.crop.angle = v[i++];
    ++i;  // the point count, already read
    s.tone_curve.points.clear();
    const auto curve_end = kSettingsFixedFloats + static_cast<std::size_t>(count_f) * 2U;
    for (; i + 1 < curve_end + 1 && i < curve_end; i += 2) s.tone_curve.points.emplace_back(v[i], v[i + 1]);
    if (i >= v.size()) return s;

    // Calibration.
    if (v.size() - i < 7U) {
        error = "develop settings calibration section is truncated";
        return std::nullopt;
    }
    for (float* p : {&s.calibration.shadow_tint, &s.calibration.red_hue, &s.calibration.red_saturation, &s.calibration.green_hue,
                     &s.calibration.green_saturation, &s.calibration.blue_hue, &s.calibration.blue_saturation})
        *p = v[i++];
    if (i + 3 >= v.size()) {
        error = "develop settings upright, defringe and spot sections are missing";
        return std::nullopt;
    }
    {
        const auto upright = enum_value(v[i++], static_cast<int>(pipeline::Upright::Manual));
        if (!upright) {
            error = "develop settings contain an unknown upright mode";
            return std::nullopt;
        }
        s.upright = static_cast<pipeline::Upright>(*upright);
        s.optics.defringe_purple = v[i++];
        s.optics.defringe_green = v[i++];
        if (v.size() - i < 8U) {
            error = "develop settings upright manual section is truncated";
            return std::nullopt;
        }
        for (float* p : {&s.upright_manual.vertical, &s.upright_manual.horizontal, &s.upright_manual.rotate, &s.upright_manual.aspect,
                         &s.upright_manual.scale, &s.upright_manual.x_offset, &s.upright_manual.y_offset})
            *p = v[i++];
        const float guide_count = v[i++];
        if (!std::isfinite(guide_count) || guide_count < 0 || guide_count != std::floor(guide_count) || guide_count > static_cast<float>(pipeline::kMaxUprightGuides) ||
            v.size() - i < static_cast<std::size_t>(guide_count) * 4U + 1U) {
            error = "develop settings upright guides are invalid or truncated";
            return std::nullopt;
        }
        for (int k = 0; k < static_cast<int>(guide_count); ++k) {
            pipeline::UprightGuide g;
            g.x1 = v[i++]; g.y1 = v[i++]; g.x2 = v[i++]; g.y2 = v[i++];
            s.upright_guides.push_back(g);
        }
    }
    {
        const float spot_count = v[i++];
        if (!std::isfinite(spot_count) || spot_count < 0 || spot_count != std::floor(spot_count) || spot_count > static_cast<float>(pipeline::kMaxSpots) ||
            v.size() - i < static_cast<std::size_t>(spot_count) * 8U) {
            error = "develop settings spot section is invalid or truncated";
            return std::nullopt;
        }
        for (int k = 0; k < static_cast<int>(spot_count); ++k) {
            pipeline::Spot sp;
            for (float* p : {&sp.x, &sp.y, &sp.radius, &sp.source_x, &sp.source_y, &sp.feather, &sp.opacity}) *p = v[i++];
            sp.heal = v[i++] != 0.0F;
            s.spots.push_back(sp);
        }
    }
    if (i >= v.size()) return s;

    // Local masks.
    bool truncated = false;
    const auto next = [&]() -> float {
        if (i < v.size()) return v[i++];
        truncated = true;
        return 0.0F;
    };
    const auto count = [&](float x, std::size_t max) -> std::optional<std::size_t> {
        if (!std::isfinite(x) || x < 0 || x != std::floor(x) || x > static_cast<float>(max)) return std::nullopt;
        return static_cast<std::size_t>(x);
    };
    const auto mask_count = count(next(), pipeline::kMaxLocalMasks);
    if (!mask_count || *mask_count == 0) {
        error = "local mask count is invalid";
        return std::nullopt;
    }
    std::size_t total_points = 0;
    std::size_t ai_pixels = 0;
    for (std::size_t m = 0; m < *mask_count; ++m) {
        pipeline::LocalMask mask;
        mask.enabled = next() != 0.0F;
        mask.invert = next() != 0.0F;
        mask.amount = next();
        float* adjust[] = {&mask.adjust.exposure, &mask.adjust.contrast, &mask.adjust.highlights, &mask.adjust.shadows, &mask.adjust.whites,
                           &mask.adjust.blacks, &mask.adjust.texture, &mask.adjust.clarity, &mask.adjust.dehaze, &mask.adjust.vibrance,
                           &mask.adjust.saturation};
        for (auto* p : adjust) *p = next();
        const auto components = count(next(), pipeline::kMaxMaskComponents);
        if (!components) {
            error = "local mask component count is invalid";
            return std::nullopt;
        }
        for (std::size_t k = 0; k < *components; ++k) {
            pipeline::MaskComponent c;
            const auto kind = enum_value(next(), static_cast<int>(pipeline::MaskKind::Ai));
            const auto op = enum_value(next(), 2);
            if (!kind || !op) {
                error = "local mask component has an unknown kind or operation";
                return std::nullopt;
            }
            c.kind = static_cast<pipeline::MaskKind>(*kind);
            c.op = static_cast<std::uint8_t>(*op);
            for (auto& x : c.p) x = next();
            if (c.kind == pipeline::MaskKind::Ai) {
                const auto bw = count(next(), pipeline::kMaxAiBitmapSide);
                const auto bh = count(next(), pipeline::kMaxAiBitmapSide);
                if (!bw || !bh || (ai_pixels += *bw * *bh) > pipeline::kMaxAiBitmapPixels) {
                    error = "the AI mask bitmap is invalid or too large";
                    return std::nullopt;
                }
                c.bitmap_width = static_cast<std::uint16_t>(*bw);
                c.bitmap_height = static_cast<std::uint16_t>(*bh);
                c.bitmap.resize(*bw * *bh);
                for (auto& b : c.bitmap) {
                    const float x = next();
                    if (x < 0.0F || x > 255.0F || x != std::floor(x)) {
                        error = "an AI mask bitmap value is outside 0–255";
                        return std::nullopt;
                    }
                    b = static_cast<std::uint8_t>(x);
                }
            }
            const auto strokes = count(next(), pipeline::kMaxBrushStrokes);
            if (!strokes) {
                error = "local mask stroke count is invalid";
                return std::nullopt;
            }
            for (std::size_t t = 0; t < *strokes; ++t) {
                pipeline::BrushStroke st;
                st.erase = next() != 0.0F;
                st.size = next();
                st.feather = next();
                st.flow = next();
                const auto points = count(next(), pipeline::kMaxBrushPoints);
                if (!points || (total_points += *points) > pipeline::kMaxBrushPoints) {
                    error = "local mask brush points exceed the limit";
                    return std::nullopt;
                }
                for (std::size_t q = 0; q < *points; ++q) {
                    const float x = next();
                    const float y = next();
                    st.points.emplace_back(x, y);
                }
                c.strokes.push_back(std::move(st));
            }
            mask.components.push_back(std::move(c));
        }
        s.masks.push_back(std::move(mask));
    }
    if (truncated || i != v.size()) {
        error = "local mask data is truncated or has trailing values";
        return std::nullopt;
    }
    return s;
}

}  // namespace croptop::develop
