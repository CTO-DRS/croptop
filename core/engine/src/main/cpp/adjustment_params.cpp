#include "adjustment_params.h"

#include <algorithm>
#include <cmath>
#include <stdexcept>

namespace croptop::adjust {
namespace {

class Reader final {
public:
    explicit Reader(const ParamMap& p) : p_(p) {}
    float number(const std::string& key, float min, float max, float fallback) const {
        const auto it = p_.find(key);
        if (it == p_.end()) return fallback;
        const auto* v = std::get_if<double>(&it->second);
        if (v == nullptr) throw std::invalid_argument("'" + key + "' must be a number");
        if (!std::isfinite(*v)) return fallback;
        return static_cast<float>(std::clamp(*v, static_cast<double>(min), static_cast<double>(max)));
    }
    bool flag(const std::string& key, bool fallback) const {
        const auto it = p_.find(key);
        if (it == p_.end()) return fallback;
        const auto* v = std::get_if<bool>(&it->second);
        if (v == nullptr) throw std::invalid_argument("'" + key + "' must be a toggle");
        return *v;
    }
    std::string choice(const std::string& key, const std::vector<std::string>& options) const {
        const auto it = p_.find(key);
        if (it != p_.end()) {
            const auto* v = std::get_if<std::string>(&it->second);
            if (v == nullptr) throw std::invalid_argument("'" + key + "' must be a choice");
            if (std::find(options.begin(), options.end(), *v) != options.end()) return *v;
        }
        return options.front();
    }
    std::array<float, 4> color(const std::string& key, std::array<float, 4> fallback) const {
        const auto it = p_.find(key);
        if (it == p_.end()) return fallback;
        const auto* v = std::get_if<std::array<float, 4>>(&it->second);
        if (v == nullptr) throw std::invalid_argument("'" + key + "' must be a colour");
        return *v;
    }
    template <typename T>
    T value(const std::string& key, T fallback) const {
        const auto it = p_.find(key);
        if (it == p_.end()) return fallback;
        const auto* v = std::get_if<T>(&it->second);
        if (v == nullptr) throw std::invalid_argument("'" + key + "' has the wrong editor value");
        return *v;
    }

private:
    const ParamMap& p_;
};

}  // namespace

std::optional<MappedAdjustment> fromParams(const std::string& id, const ParamMap& params, std::string& error) {
    try {
        const Reader r(params);
        MappedAdjustment out;
        if (id == "brightnessContrast") {
            out.pixel = BrightnessContrast{r.number("brightness", -150, 150, 0), r.number("contrast", -50, 100, 0), r.flag("legacy", false)};
        } else if (id == "levels") {
            auto l = r.value<Levels>("levels", Levels{});
            l.gamma = std::clamp(l.gamma, 0.1F, 9.99F);
            out.pixel = l;
        } else if (id == "curves") {
            out.pixel = r.value<Curves>("curve", Curves{});
        } else if (id == "exposure") {
            out.pixel = Exposure{r.number("exposure", -20, 20, 0), r.number("offset", -0.5F, 0.5F, 0), r.number("gamma", 0.01F, 9.99F, 1)};
        } else if (id == "vibrance") {
            out.pixel = Vibrance{r.number("vibrance", -100, 100, 0), r.number("saturation", -100, 100, 0)};
        } else if (id == "hueSaturation") {
            out.pixel = r.value<HueSaturation>("hueSat", HueSaturation{});
        } else if (id == "colorBalance") {
            out.pixel = r.value<ColorBalance>("balance", ColorBalance{});
        } else if (id == "blackWhite") {
            BlackWhite bw;
            bw.reds = r.number("reds", -200, 300, 40);
            bw.yellows = r.number("yellows", -200, 300, 60);
            bw.greens = r.number("greens", -200, 300, 40);
            bw.cyans = r.number("cyans", -200, 300, 60);
            bw.blues = r.number("blues", -200, 300, 20);
            bw.magentas = r.number("magentas", -200, 300, 80);
            bw.tint = r.flag("tint", false);
            const auto t = r.color("tintColor", {0.88F, 0.76F, 0.58F, 1});
            bw.tint_color = {t[0], t[1], t[2]};
            out.pixel = bw;
        } else if (id == "photoFilter") {
            PhotoFilter f;
            const auto filter = r.choice("filter", {"warming85", "cooling80", "sepia", "custom"});
            if (filter == "warming85") f.color = {236 / 255.0F, 138 / 255.0F, 0};
            else if (filter == "cooling80") f.color = {0, 109 / 255.0F, 1};
            else if (filter == "sepia") f.color = {172 / 255.0F, 122 / 255.0F, 51 / 255.0F};
            else {
                const auto c = r.color("color", {0.93F, 0.54F, 0.1F, 1});
                f.color = {c[0], c[1], c[2]};
            }
            f.density = r.number("density", 1, 100, 25);
            f.preserve_luminosity = r.flag("preserveLuminosity", true);
            out.pixel = f;
        } else if (id == "invert") {
            out.pixel = Invert{};
        } else if (id == "posterize") {
            out.pixel = Posterize{r.number("levels", 2, 255, 4)};
        } else if (id == "threshold") {
            out.pixel = Threshold{r.number("level", 1, 255, 128)};
        } else if (id == "channelMixer") {
            ChannelMixer m;
            const auto output = r.choice("output", {"red", "green", "blue"});
            const std::size_t row = output == "red" ? 0 : output == "green" ? 1 : 2;
            m.matrix[row] = {r.number("red", -200, 200, row == 0 ? 100 : 0), r.number("green", -200, 200, row == 1 ? 100 : 0),
                             r.number("blue", -200, 200, row == 2 ? 100 : 0)};
            m.constant[row] = r.number("constant", -200, 200, 0);
            m.monochrome = r.flag("monochrome", false);
            if (m.monochrome && row != 0) {
                m.matrix[0] = m.matrix[row];
                m.constant[0] = m.constant[row];
            }
            out.pixel = m;
        } else if (id == "gradientMap") {
            GradientMap g;
            g.stops = r.value<std::vector<GradientStop>>("gradient", g.stops);
            if (g.stops.size() < 2) throw std::invalid_argument("a gradient needs at least two stops");
            g.reverse = r.flag("reverse", false);
            g.dither = r.flag("dither", false);
            out.pixel = g;
        } else if (id == "selectiveColor") {
            SelectiveColor s;
            static const std::vector<std::string> kRanges{"reds", "yellows", "greens", "cyans", "blues", "magentas", "whites", "neutrals", "blacks"};
            const auto range = r.choice("colors", kRanges);
            const auto index = static_cast<std::size_t>(std::find(kRanges.begin(), kRanges.end(), range) - kRanges.begin());
            s.cmyk[index] = {r.number("cyan", -100, 100, 0), r.number("magenta", -100, 100, 0), r.number("yellow", -100, 100, 0), r.number("black", -100, 100, 0)};
            s.absolute = r.choice("method", {"relative", "absolute"}) == "absolute";
            out.pixel = s;
        } else if (id == "shadowsHighlights") {
            out.spatial = ShadowsHighlights{r.number("shadows", 0, 100, 35), r.number("highlights", 0, 100, 0)};
        } else {
            error = "unknown adjustment '" + id + "'";
            return std::nullopt;
        }
        return out;
    } catch (const std::exception& e) {
        error = e.what();
        return std::nullopt;
    }
}

}  // namespace croptop::adjust
