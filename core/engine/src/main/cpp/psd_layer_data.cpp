#include "psd_layer_data.h"

#include <algorithm>
#include <bit>
#include <cmath>
#include <map>
#include <span>
#include <stdexcept>

#include "psd_descriptor.h"

namespace croptop::psd {
namespace {

/** Big-endian reader over an additional-info block. */
class Block final {
public:
    explicit Block(const std::vector<std::uint8_t>& d) : d_(d) {}
    std::size_t remaining() const { return d_.size() - pos_; }
    std::uint16_t u16() {
        need(2);
        const auto v = static_cast<std::uint16_t>((d_[pos_] << 8) | d_[pos_ + 1]);
        pos_ += 2;
        return v;
    }
    std::int16_t i16() { return static_cast<std::int16_t>(u16()); }
    std::uint8_t u8() {
        need(1);
        return d_[pos_++];
    }
    std::uint32_t u32() {
        const std::uint32_t high = u16();
        return (high << 16) | u16();
    }
    float f32() { return std::bit_cast<float>(u32()); }
    void skip(std::size_t n) {
        need(n);
        pos_ += n;
    }
    std::span<const std::uint8_t> rest() const { return std::span<const std::uint8_t>(d_).subspan(pos_); }

private:
    void need(std::size_t n) const {
        if (n > remaining()) throw std::runtime_error("adjustment data is truncated");
    }
    const std::vector<std::uint8_t>& d_;
    std::size_t pos_ = 0;
};

BlendMode blendFromEnum(const std::string& e) {
    static const std::map<std::string, BlendMode> kModes{
        {"Nrml", BlendMode::Normal},        {"Dslv", BlendMode::Dissolve},        {"Drkn", BlendMode::Darken},
        {"Mltp", BlendMode::Multiply},      {"CBrn", BlendMode::ColorBurn},       {"linearBurn", BlendMode::LinearBurn},
        {"darkerColor", BlendMode::DarkerColor}, {"Lghn", BlendMode::Lighten},    {"Scrn", BlendMode::Screen},
        {"CDdg", BlendMode::ColorDodge},    {"linearDodge", BlendMode::LinearDodge}, {"lighterColor", BlendMode::LighterColor},
        {"Ovrl", BlendMode::Overlay},       {"SftL", BlendMode::SoftLight},       {"HrdL", BlendMode::HardLight},
        {"vividLight", BlendMode::VividLight}, {"linearLight", BlendMode::LinearLight}, {"pinLight", BlendMode::PinLight},
        {"hardMix", BlendMode::HardMix},    {"Dfrn", BlendMode::Difference},      {"Xclu", BlendMode::Exclusion},
        {"blendSubtraction", BlendMode::Subtract}, {"blendDivide", BlendMode::Divide}, {"H   ", BlendMode::Hue},
        {"Strt", BlendMode::Saturation},    {"Clr ", BlendMode::Color},           {"Lmns", BlendMode::Luminosity}};
    const auto it = kModes.find(e);
    return it == kModes.end() ? BlendMode::Normal : it->second;
}

styles::Rgba colorOf(const DescValue* c, styles::Rgba fallback) {
    if (c == nullptr || c->type != "Objc") return fallback;
    if (c->text == "RGBC") return {static_cast<float>(c->number_or("Rd  ", 0) / 255.0), static_cast<float>(c->number_or("Grn ", 0) / 255.0),
                                   static_cast<float>(c->number_or("Bl  ", 0) / 255.0), 1.0F};
    if (c->text == "Grsc") {
        const auto g = static_cast<float>(1.0 - c->number_or("Gry ", 0) / 100.0);
        return {g, g, g, 1.0F};
    }
    return fallback;
}

float percent(const DescValue& d, const std::string& key, float fallback) {
    return static_cast<float>(std::clamp(d.number_or(key, fallback * 100.0) / 100.0, 0.0, 1.0));
}

}  // namespace

std::optional<styles::LayerStyles> decodeEffects(const std::vector<std::uint8_t>& lfx2, const GlobalLight& light, std::vector<std::string>& warnings) {
    if (lfx2.size() < 8) {
        warnings.push_back("layer effects block is too short");
        return std::nullopt;
    }
    std::string error;
    const auto root = parseDescriptor(std::span<const std::uint8_t>(lfx2).subspan(8), error);
    if (!root) {
        warnings.push_back("layer effects could not be read: " + error);
        return std::nullopt;
    }
    styles::LayerStyles out;
    if (!root->flag_or("masterFXSwitch", true)) return out;
    const float scale = static_cast<float>(std::clamp(root->number_or("Scl ", 100.0) / 100.0, 0.01, 10.0));
    const auto angleOf = [&](const DescValue& e) {
        return e.flag_or("uglg", true) ? light.angle : static_cast<float>(e.number_or("lagl", light.angle));
    };
    const auto shadow = [&](const DescValue& e, styles::Shadow& s) {
        s.enabled = e.flag_or("enab", false);
        s.mode = blendFromEnum(e.text_or("Md  ", "Mltp"));
        s.color = colorOf(e.find("Clr "), s.color);
        s.opacity = percent(e, "Opct", s.opacity);
        s.angle = angleOf(e);
        s.distance = static_cast<float>(e.number_or("Dstn", s.distance)) * scale;
        s.spread = static_cast<float>(std::clamp(e.number_or("Ckmt", 0.0), 0.0, 100.0));  // stored as the percentage
        s.size = std::min(250.0F, static_cast<float>(e.number_or("blur", s.size)) * scale);
    };
    const auto glow = [&](const DescValue& e, styles::Glow& g) {
        g.enabled = e.flag_or("enab", false);
        g.mode = blendFromEnum(e.text_or("Md  ", "Scrn"));
        g.color = colorOf(e.find("Clr "), g.color);
        g.opacity = percent(e, "Opct", g.opacity);
        g.spread = static_cast<float>(std::clamp(e.number_or("Ckmt", 0.0), 0.0, 100.0));
        g.size = std::min(250.0F, static_cast<float>(e.number_or("blur", g.size)) * scale);
        g.from_center = e.text_or("glwS") == "SrcC";
    };
    for (std::size_t i = 0; i < root->keys.size(); ++i) {
        const auto& key = root->keys[i];
        const auto& e = root->values[i];
        if (e.type != "Objc") continue;
        if (key == "DrSh") {
            shadow(e, out.drop_shadow);
        } else if (key == "IrSh") {
            shadow(e, out.inner_shadow);
        } else if (key == "OrGl") {
            glow(e, out.outer_glow);
        } else if (key == "IrGl") {
            glow(e, out.inner_glow);
        } else if (key == "FrFX") {
            auto& s = out.stroke;
            s.enabled = e.flag_or("enab", false);
            s.mode = blendFromEnum(e.text_or("Md  ", "Nrml"));
            s.opacity = percent(e, "Opct", 1.0F);
            s.size = std::clamp(static_cast<float>(e.number_or("Sz  ", 3.0)) * scale, 1.0F, 250.0F);
            s.color = colorOf(e.find("Clr "), s.color);
            const auto style = e.text_or("Styl", "OutF");
            s.position = style == "InsF" ? styles::StrokePosition::Inside : style == "CtrF" ? styles::StrokePosition::Center : styles::StrokePosition::Outside;
            if (e.text_or("PntT", "SClr") != "SClr") warnings.push_back("gradient and pattern strokes are drawn with a solid colour");
        } else if (key == "SoFi") {
            auto& c = out.color_overlay;
            c.enabled = e.flag_or("enab", false);
            c.mode = blendFromEnum(e.text_or("Md  ", "Nrml"));
            c.opacity = percent(e, "Opct", 1.0F);
            c.color = colorOf(e.find("Clr "), c.color);
        } else if (key == "GrFl") {
            auto& g = out.gradient_overlay;
            g.enabled = e.flag_or("enab", false);
            g.mode = blendFromEnum(e.text_or("Md  ", "Nrml"));
            g.opacity = percent(e, "Opct", 1.0F);
            g.angle = static_cast<float>(e.number_or("Angl", 90.0));
            g.reverse = e.flag_or("Rvrs", false);
            g.scale = static_cast<float>(std::clamp(e.number_or("Scl ", 100.0) / 100.0, 0.1, 1.5));
            if (const auto* grad = e.find("Grad"); grad != nullptr && grad->type == "Objc") {
                std::vector<styles::GradientStop> stops;
                if (const auto* colors = grad->find("Clrs")) {
                    for (const auto& stop : colors->list) {
                        if (stop.type != "Objc") continue;
                        stops.push_back({static_cast<float>(std::clamp(stop.number_or("Lctn", 0.0) / 4096.0, 0.0, 1.0)), colorOf(stop.find("Clr "), {0, 0, 0, 1})});
                    }
                }
                // Transparency stops modulate alpha, interpolated at each colour stop.
                std::vector<std::pair<float, float>> alpha;
                if (const auto* trans = grad->find("Trns")) {
                    for (const auto& stop : trans->list)
                        if (stop.type == "Objc") alpha.emplace_back(static_cast<float>(std::clamp(stop.number_or("Lctn", 0.0) / 4096.0, 0.0, 1.0)), percent(stop, "Opct", 1.0F));
                }
                std::sort(alpha.begin(), alpha.end());
                for (auto& stop : stops) {
                    if (alpha.empty()) break;
                    float a = alpha.front().second;
                    for (std::size_t k = 0; k + 1 < alpha.size(); ++k)
                        if (stop.position >= alpha[k].first && stop.position <= alpha[k + 1].first) {
                            const float span = alpha[k + 1].first - alpha[k].first;
                            const float t = span > 0 ? (stop.position - alpha[k].first) / span : 0;
                            a = alpha[k].second + t * (alpha[k + 1].second - alpha[k].second);
                        }
                    if (stop.position > alpha.back().first) a = alpha.back().second;
                    stop.color[3] = a;
                }
                if (stops.size() >= 2) g.stops = stops;
            }
        } else if (key == "ebbl") {
            auto& b = out.bevel;
            b.enabled = e.flag_or("enab", false);
            const auto style = e.text_or("bvlS", "InrB");
            b.style = style == "OtrB" ? styles::BevelStyle::OuterBevel : style == "Embs" ? styles::BevelStyle::Emboss
                      : style == "PlEb" ? styles::BevelStyle::PillowEmboss : styles::BevelStyle::InnerBevel;
            b.technique = e.text_or("bvlT", "SfBL") == "SfBL" ? styles::BevelTechnique::Smooth : styles::BevelTechnique::ChiselHard;
            b.depth = static_cast<float>(std::clamp(e.number_or("srgR", 100.0) / 100.0, 0.01, 10.0));
            b.size = std::min(250.0F, static_cast<float>(e.number_or("blur", 5.0)) * scale);
            b.soften = std::min(16.0F, static_cast<float>(e.number_or("Sftn", 0.0)) * scale);
            b.up = e.text_or("bvlD", "In  ") != "Out ";
            b.angle = angleOf(e);
            b.altitude = e.flag_or("uglg", true) ? light.altitude : static_cast<float>(std::clamp(e.number_or("Lald", 30.0), 0.0, 90.0));
            b.highlight_mode = blendFromEnum(e.text_or("hglM", "Scrn"));
            b.highlight_color = colorOf(e.find("hglC"), b.highlight_color);
            b.highlight_opacity = percent(e, "hglO", 0.75F);
            b.shadow_mode = blendFromEnum(e.text_or("sdwM", "Mltp"));
            b.shadow_color = colorOf(e.find("sdwC"), b.shadow_color);
            b.shadow_opacity = percent(e, "sdwO", 0.75F);
        } else if (key == "ChFX") {
            auto& t = out.satin;
            t.enabled = e.flag_or("enab", false);
            t.mode = blendFromEnum(e.text_or("Md  ", "Mltp"));
            t.color = colorOf(e.find("Clr "), t.color);
            t.opacity = percent(e, "Opct", 0.5F);
            t.angle = static_cast<float>(e.number_or("lagl", 19.0));
            t.distance = std::min(250.0F, static_cast<float>(e.number_or("Dstn", 11.0)) * scale);
            t.size = std::min(250.0F, static_cast<float>(e.number_or("blur", 14.0)) * scale);
            t.invert = e.flag_or("Invr", true);
        } else if (key == "patternFill") {
            if (e.flag_or("enab", false)) warnings.push_back("pattern overlays are not decoded yet");
        }
    }
    return out;
}

std::optional<adjust::Adjustment> decodeAdjustment(const std::string& key, const std::vector<std::uint8_t>& data, const std::vector<AdditionalInfo>& extra,
                                                   std::vector<std::string>& warnings) {
    try {
        Block b(data);
        if (key == "levl") {
            b.u16();  // version
            adjust::Levels l;
            l.in_black = b.i16() / 255.0F;
            l.in_white = b.i16() / 255.0F;
            l.out_black = b.i16() / 255.0F;
            l.out_white = b.i16() / 255.0F;
            l.gamma = std::clamp(b.i16() / 100.0F, 0.1F, 9.99F);
            for (int channel = 0; channel < 3 && b.remaining() >= 10; ++channel) {
                const auto a = b.i16(), c = b.i16(), d = b.i16(), e = b.i16(), g = b.i16();
                if (a != 0 || c != 255 || d != 0 || e != 255 || g != 100) {
                    warnings.push_back("per-channel levels are not supported; the composite levels were used");
                    break;
                }
            }
            return l;
        }
        if (key == "curv") {
            b.u8();  // padding
            b.u16();  // version
            const auto channels = b.u32();
            adjust::Curves curves;
            std::array<adjust::Curve*, 4> targets{&curves.rgb, &curves.red, &curves.green, &curves.blue};
            for (int channel = 0; channel < 32; ++channel) {
                if ((channels & (1U << channel)) == 0) continue;
                const auto count = b.u16();
                if (count < 2 || count > 19) throw std::runtime_error("bad curve point count");
                std::vector<std::pair<float, float>> points;
                for (std::uint16_t i = 0; i < count; ++i) {
                    const float out = b.i16() / 255.0F, in = b.i16() / 255.0F;
                    points.emplace_back(std::clamp(in, 0.0F, 1.0F), std::clamp(out, 0.0F, 1.0F));
                }
                std::sort(points.begin(), points.end());
                if (channel < 4) targets[static_cast<std::size_t>(channel)]->points = points;
                else warnings.push_back("curves for extra channels were ignored");
            }
            return curves;
        }
        if (key == "hue2") {
            b.u16();
            adjust::HueSaturation h;
            h.colorize = b.u8() != 0;
            b.u8();
            const float ch = b.i16(), cs = b.i16(), cl = b.i16();
            h.master = {static_cast<float>(b.i16()), static_cast<float>(b.i16()), static_cast<float>(b.i16())};
            if (h.colorize) h.master = {ch < 0 ? ch + 360 : ch, cs, cl};
            for (std::size_t i = 0; i < 6; ++i) {
                const float a = b.i16(), c = b.i16(), d = b.i16(), e = b.i16();
                const auto wrap = [](float v) { return std::fmod(std::fmod(v, 360.0F) + 360.0F, 360.0F); };
                h.ranges[i] = {wrap(a), wrap(c), wrap(d), wrap(e)};
                h.channels[i] = {static_cast<float>(b.i16()), static_cast<float>(b.i16()), static_cast<float>(b.i16())};
            }
            return h;
        }
        if (key == "blnc") {
            adjust::ColorBalance c;
            for (auto* shift : {&c.shadows, &c.midtones, &c.highlights}) {
                shift->cyan_red = b.i16();
                shift->magenta_green = b.i16();
                shift->yellow_blue = b.i16();
            }
            c.preserve_luminosity = b.remaining() >= 1 ? b.u8() != 0 : true;
            return c;
        }
        if (key == "brit") {
            adjust::BrightnessContrast bc;
            for (const auto& block : extra) {
                if (block.key != "CgEd" || block.data.size() < 4) continue;
                std::string error;
                if (const auto d = parseDescriptor(std::span<const std::uint8_t>(block.data).subspan(4), error)) {
                    bc.brightness = static_cast<float>(d->number_or("Brgh", 0));
                    bc.contrast = static_cast<float>(d->number_or("Cntr", 0));
                    bc.legacy = d->flag_or("useLegacy", false);
                    return bc;
                }
            }
            bc.brightness = b.i16();
            bc.contrast = b.i16();
            bc.legacy = true;
            return bc;
        }
        if (key == "nvrt") return adjust::Invert{};
        if (key == "post") return adjust::Posterize{static_cast<float>(std::clamp<int>(b.i16(), 2, 255))};
        if (key == "thrs") return adjust::Threshold{static_cast<float>(std::clamp<int>(b.i16(), 1, 255))};
        if (key == "mixr") {
            b.u16();
            adjust::ChannelMixer m;
            m.monochrome = b.u16() != 0;
            for (std::size_t o = 0; o < 3; ++o) {
                for (std::size_t k = 0; k < 3; ++k) m.matrix[o][k] = b.i16();
                b.i16();  // black (CMYK only)
                m.constant[o] = b.i16();
            }
            return m;
        }
        if (key == "selc") {
            b.u16();
            adjust::SelectiveColor s;
            s.absolute = b.u16() == 1;
            b.skip(8);  // the first record is unused
            for (std::size_t r = 0; r < 9; ++r)
                for (std::size_t k = 0; k < 4; ++k) s.cmyk[r][k] = b.i16();
            return s;
        }
        if (key == "expA") {
            b.u16();
            adjust::Exposure e;
            e.exposure = b.f32();
            e.offset = b.f32();
            e.gamma = b.f32();
            if (!std::isfinite(e.exposure) || !std::isfinite(e.offset) || !std::isfinite(e.gamma)) throw std::runtime_error("bad exposure values");
            return e;
        }
        if (key == "vibA" || key == "blwh") {
            b.skip(4);
            std::string error;
            const auto d = parseDescriptor(b.rest(), error);
            if (!d) throw std::runtime_error(error);
            if (key == "vibA") return adjust::Vibrance{static_cast<float>(d->number_or("vibrance", 0)), static_cast<float>(d->number_or("Strt", 0))};
            adjust::BlackWhite bw;
            bw.reds = static_cast<float>(d->number_or("Rd  ", bw.reds));
            bw.yellows = static_cast<float>(d->number_or("Yllw", bw.yellows));
            bw.greens = static_cast<float>(d->number_or("Grn ", bw.greens));
            bw.cyans = static_cast<float>(d->number_or("Cyn ", bw.cyans));
            bw.blues = static_cast<float>(d->number_or("Bl  ", bw.blues));
            bw.magentas = static_cast<float>(d->number_or("Mgnt", bw.magentas));
            bw.tint = d->flag_or("useTint", false);
            const auto tint = colorOf(d->find("tintColor"), {bw.tint_color[0], bw.tint_color[1], bw.tint_color[2], 1});
            bw.tint_color = {tint[0], tint[1], tint[2]};
            return bw;
        }
        if (key == "phfl") {
            const auto version = b.u16();
            adjust::PhotoFilter f;
            if (version == 2) {
                const auto space = b.u16();
                const float c0 = b.u16() / 65535.0F, c1 = b.u16() / 65535.0F, c2 = b.u16() / 65535.0F;
                b.u16();
                if (space != 0) throw std::runtime_error("only RGB photo filter colours are supported");
                f.color = {c0, c1, c2};
            } else {
                warnings.push_back("photo filter colour in Lab/XYZ form; the default warming colour was used");
                b.skip(12);
            }
            f.density = static_cast<float>(std::clamp<std::int32_t>(static_cast<std::int32_t>(b.u32()), 1, 100));
            f.preserve_luminosity = b.remaining() >= 1 ? b.u8() != 0 : true;
            return f;
        }
        if (key == "grdm") {
            b.u16();  // version
            adjust::GradientMap g;
            g.reverse = b.u8() != 0;
            g.dither = b.u8() != 0;
            const auto name_length = b.u32();
            if (name_length > b.remaining() / 2) throw std::runtime_error("bad gradient name");
            b.skip(static_cast<std::size_t>(name_length) * 2U);
            const auto count = b.u16();
            if (count < 2 || count > 256) throw std::runtime_error("bad gradient stop count");
            std::vector<adjust::GradientStop> stops;
            for (std::uint16_t i = 0; i < count; ++i) {
                const float location = std::clamp(static_cast<float>(b.u32()) / 4096.0F, 0.0F, 1.0F);
                b.u32();  // midpoint
                const auto space = b.u16();
                const float c0 = b.u16() / 65535.0F, c1 = b.u16() / 65535.0F, c2 = b.u16() / 65535.0F;
                b.u16();
                b.u16();  // padding
                if (space != 0) throw std::runtime_error("only RGB gradient colours are supported");
                stops.push_back({location, {c0, c1, c2, 1.0F}});
            }
            g.stops = std::move(stops);
            return g;
        }
    } catch (const std::exception& e) {
        warnings.push_back("adjustment '" + key + "' could not be read: " + e.what());
    }
    return std::nullopt;
}

std::optional<styles::LayerStyles> effectsOf(const Layer& layer, const GlobalLight& light, std::vector<std::string>& warnings) {
    for (const auto& block : layer.extra)
        if (block.key == "lfx2") return decodeEffects(block.data, light, warnings);
    return std::nullopt;
}

std::optional<adjust::Adjustment> adjustmentOf(const Layer& layer, std::vector<std::string>& warnings) {
    static const std::array<const char*, 15> kKeys{"levl", "curv", "hue2", "blnc", "brit", "nvrt", "post", "thrs", "mixr", "selc", "expA", "vibA", "blwh", "phfl", "grdm"};
    for (const auto& block : layer.extra)
        for (const auto* key : kKeys)
            if (block.key == key) return decodeAdjustment(block.key, block.data, layer.extra, warnings);
    return std::nullopt;
}

}  // namespace croptop::psd
