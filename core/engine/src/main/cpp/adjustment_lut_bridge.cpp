// Slider preview adapter; commit/apply stays on the existing CPU path.
#include "adjustment_lut_bridge.h"

#include <algorithm>
#include <array>
#include <cmath>
#include <map>
#include <variant>

#include "adjustment_params.h"
#include "adjustments.h"

namespace croptop::adjust::bridge {
namespace {
using M = ParamMap;

bool finite(const std::vector<float>& v) {
    return std::all_of(v.begin(), v.end(), [](float x) { return std::isfinite(x); });
}
bool integral(float x) { return std::floor(x) == x; }
bool boolAt(const std::vector<float>& v, std::size_t i) { return i < v.size() && (v[i] == 0.0F || v[i] == 1.0F); }
bool inRange(float x, float lo, float hi) { return x >= lo && x <= hi; }
bool normalizedColor(const std::vector<float>& v, std::size_t i) {
    return i + 2 < v.size() && inRange(v[i], 0, 1) && inRange(v[i + 1], 0, 1) && inRange(v[i + 2], 0, 1);
}
bool readCurves(const std::vector<float>& v, Curves& curves) {
    std::array<Curve*, 4> dst{&curves.rgb, &curves.red, &curves.green, &curves.blue};
    std::size_t pos = 0;
    for (auto* c : dst) {
        if (pos >= v.size() || !integral(v[pos]) || v[pos] < 2 || v[pos] > 64) return false;
        const auto n = static_cast<std::size_t>(v[pos++]);
        if (n > (v.size() - pos) / 2) return false;
        c->points.clear();
        c->points.reserve(n);
        float prev = -1;
        for (std::size_t i = 0; i < n; ++i) {
            const float x = v[pos++], y = v[pos++];
            if (x < 0 || x > 1 || y < 0 || y > 1 || x <= prev) return false;
            c->points.emplace_back(x, y);
            prev = x;
        }
    }
    return pos == v.size();
}
bool exactSize(const std::vector<float>& b, std::size_t n) { return b.size() == n; }
void number(M& p, const char* key, float v) { p.emplace(key, static_cast<double>(v)); }
void flag(M& p, const char* key, float v) { p.emplace(key, v != 0.0F); }
void color(M& p, const char* key, float r, float g, float b) { p.emplace(key, std::array<float, 4>{r, g, b, 1.0F}); }

std::optional<M> decode(const std::string& id, const std::vector<float>& b, std::string& err) {
    M p;
    if (id == "brightnessContrast" && exactSize(b, 3)) {
        if (!boolAt(b, 2)) { err = "legacy must be 0 or 1"; return std::nullopt; }
        number(p,"brightness",b[0]); number(p,"contrast",b[1]); flag(p,"legacy",b[2]);
    } else if (id == "exposure" && exactSize(b,3)) {
        number(p,"exposure",b[0]); number(p,"offset",b[1]); number(p,"gamma",b[2]);
    } else if (id == "vibrance" && exactSize(b,2)) {
        number(p,"vibrance",b[0]); number(p,"saturation",b[1]);
    } else if (id == "invert" && b.empty()) {
    } else if (id == "posterize" && exactSize(b,1)) number(p,"levels",b[0]);
    else if (id == "threshold" && exactSize(b,1)) number(p,"level",b[0]);
    else if (id == "levels" && exactSize(b,5)) {
        if (!inRange(b[0], 0, 1) || !inRange(b[1], 0, 1) || b[1] <= b[0] || !inRange(b[2], 0.1F, 9.99F) ||
            !inRange(b[3], 0, 1) || !inRange(b[4], 0, 1) || b[4] < b[3]) {
            err = "Levels fields are outside valid ranges"; return std::nullopt;
        }
        p.emplace("levels", Levels{b[0],b[1],b[2],b[3],b[4]});
    }
    else if (id == "curves") {
        Curves c;
        if (!readCurves(b,c)) { err = "Invalid curve channels"; return std::nullopt; }
        p.emplace("curve", std::move(c));
    } else if (id == "hueSaturation" && exactSize(b,46)) {
        if (!boolAt(b, 3)) { err = "Hue/Saturation colorize must be 0 or 1"; return std::nullopt; }
        for (std::size_t i = 0; i < 7; ++i) {
            const std::size_t k = i == 0 ? 0 : 4 + (i - 1) * 3;
            if (!inRange(b[k], -360, 360) || !inRange(b[k + 1], -100, 100) || !inRange(b[k + 2], -100, 100)) {
                err = "Hue/Saturation shifts are outside valid ranges"; return std::nullopt;
            }
        }
        for (std::size_t i = 22; i < 46; ++i) if (!inRange(b[i], 0, 360)) { err = "Hue/Saturation ranges must use 0–360 degrees"; return std::nullopt; }
        HueSaturation h; h.master={b[0],b[1],b[2]}; h.colorize=b[3]!=0;
        std::size_t k=4;
        for(auto& c:h.channels){ c={b[k],b[k+1],b[k+2]}; k+=3; }
        for(auto& r:h.ranges){ r={b[k],b[k+1],b[k+2],b[k+3]}; k+=4; }
        p.emplace("hueSat",std::move(h));
    } else if (id == "colorBalance" && exactSize(b,10)) {
        if (!boolAt(b, 9)) { err = "Color Balance preserveLuminosity must be 0 or 1"; return std::nullopt; }
        for (std::size_t i = 0; i < 9; ++i) if (!inRange(b[i], -100, 100)) { err = "Color Balance shifts are outside valid ranges"; return std::nullopt; }
        ColorBalance c; c.shadows={b[0],b[1],b[2]}; c.midtones={b[3],b[4],b[5]};
        c.highlights={b[6],b[7],b[8]}; c.preserve_luminosity=b[9]!=0; p.emplace("balance",c);
    } else if (id == "blackWhite" && exactSize(b,10)) {
        if (!boolAt(b, 6) || !normalizedColor(b, 7)) { err = "Black & White tint fields are invalid"; return std::nullopt; }
        for(std::size_t i=0;i<6;++i) number(p, std::array<const char*,6>{"reds","yellows","greens","cyans","blues","magentas"}[i],b[i]);
        flag(p,"tint",b[6]); color(p,"tintColor",b[7],b[8],b[9]);
    } else if (id == "photoFilter" && exactSize(b,6)) {
        if (!integral(b[0]) || b[0]<0 || b[0]>3) { err="Invalid Photo Filter preset"; return std::nullopt; }
        if (!normalizedColor(b, 1) || !boolAt(b, 5)) { err = "Photo Filter color/flag fields are invalid"; return std::nullopt; }
        const std::array<const char*,4> presets{"warming85","cooling80","sepia","custom"};
        p.emplace("filter",std::string(presets[static_cast<std::size_t>(b[0])]));
        color(p,"color",b[1],b[2],b[3]); number(p,"density",b[4]); flag(p,"preserveLuminosity",b[5]);
    } else if (id == "channelMixer" && exactSize(b,6)) {
        if (!integral(b[0]) || b[0]<0 || b[0]>2) { err="Invalid Channel Mixer output"; return std::nullopt; }
        if (!boolAt(b, 5)) { err = "Channel Mixer monochrome must be 0 or 1"; return std::nullopt; }
        p.emplace("output",std::string(std::array<const char*,3>{"red","green","blue"}[static_cast<std::size_t>(b[0])]));
        number(p,"red",b[1]); number(p,"green",b[2]); number(p,"blue",b[3]); number(p,"constant",b[4]); flag(p,"monochrome",b[5]);
    } else if (id == "gradientMap") {
        if (b.size()<13 || !boolAt(b, 0) || !boolAt(b, 1) || !integral(b[2]) || b[2]<2 || b[2]>64 || b.size()!=3+static_cast<std::size_t>(b[2])*5) {
            err="Invalid Gradient Map options or stop count"; return std::nullopt;
        }
        std::vector<GradientStop> stops;
        std::size_t k=3; float prev=-1;
        for(std::size_t i=0;i<static_cast<std::size_t>(b[2]);++i) {
            GradientStop s{b[k],{b[k+1],b[k+2],b[k+3],b[k+4]}};
            if(s.position<0 || s.position>1 || s.position<prev || std::any_of(s.color.begin(),s.color.end(),[](float x){return x<0||x>1;})) {
                err="Gradient Map stops must be ordered and normalized"; return std::nullopt;
            }
            stops.push_back(s); prev=s.position; k+=5;
        }
        p.emplace("gradient",std::move(stops)); flag(p,"reverse",b[0]); flag(p,"dither",b[1]);
    } else if (id == "selectiveColor" && exactSize(b,6)) {
        if(!integral(b[0])||b[0]<0||b[0]>8){err="Invalid Selective Color range";return std::nullopt;}
        if (!boolAt(b, 5)) { err = "Selective Color method must be 0 or 1"; return std::nullopt; }
        p.emplace("colors",std::string(std::array<const char*,9>{"reds","yellows","greens","cyans","blues","magentas","whites","neutrals","blacks"}[static_cast<std::size_t>(b[0])]));
        number(p,"cyan",b[1]);number(p,"magenta",b[2]);number(p,"yellow",b[3]);number(p,"black",b[4]);p.emplace("method",std::string(b[5]!=0?"absolute":"relative"));
    } else if (id == "shadowsHighlights") {
        err="Shadows/Highlights is spatial and cannot be represented by a color LUT"; return std::nullopt;
    } else {
        err="Adjustment block has an unsupported id or invalid length"; return std::nullopt;
    }
    return p;
}
}  // namespace

std::optional<color::Lut> bakeBlock(const std::string& id, const std::vector<float>& block,
                                    std::uint32_t lut_size, std::string& error) {
    error.clear();
    if (block.size() > 520 || !finite(block)) { error="Adjustment block is too large or contains non-finite values"; return std::nullopt; }
    if (lut_size < 2 || lut_size > 65) { error="LUT size must be between 2 and 65"; return std::nullopt; }
    auto params=decode(id,block,error); if(!params) return std::nullopt;
    auto mapped=fromParams(id,*params,error); if(!mapped) return std::nullopt;
    if(!mapped->pixel) { error="Adjustment does not have a per-pixel LUT representation"; return std::nullopt; }
    return bakeLut({*mapped->pixel},lut_size);
}
}  // namespace croptop::adjust::bridge
