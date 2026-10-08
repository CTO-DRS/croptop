// See psd_effects_writer.h.
#include "psd_effects_writer.h"

#include <algorithm>
#include <array>
#include <bit>
#include <cstring>
#include <string>

namespace croptop::psd {
namespace {

class Writer final {
public:
    std::vector<std::uint8_t> bytes;

    void u8(std::uint8_t v) { bytes.push_back(v); }
    void u32(std::uint32_t v) {
        for (int shift = 24; shift >= 0; shift -= 8) u8(static_cast<std::uint8_t>(v >> shift));
    }
    void f64(double v) {
        const auto bits = std::bit_cast<std::uint64_t>(v);
        for (int shift = 56; shift >= 0; shift -= 8) u8(static_cast<std::uint8_t>(bits >> shift));
    }
    void os(const char* type) {
        for (int i = 0; i < 4; ++i) u8(static_cast<std::uint8_t>(type[i]));
    }
    /** Key / class id: four-character codes are written with a zero length, longer ids with their length. */
    void id(const std::string& key) {
        if (key.size() == 4) {
            u32(0);
        } else {
            u32(static_cast<std::uint32_t>(key.size()));
        }
        for (const char c : key) u8(static_cast<std::uint8_t>(c));
    }
    void unicode(const std::string& ascii) {
        u32(static_cast<std::uint32_t>(ascii.size() + 1));
        for (const char c : ascii) {
            u8(0);
            u8(static_cast<std::uint8_t>(c));
        }
        u8(0);
        u8(0);
    }
};

/** One descriptor being built: items are serialized into [body] as they are added. */
class Desc final {
public:
    explicit Desc(std::string cls) : class_id_(std::move(cls)) {}

    Desc& boolean(const std::string& key, bool v) {
        start(key, "bool");
        body_.u8(v ? 1 : 0);
        return *this;
    }
    Desc& doub(const std::string& key, double v) {
        start(key, "doub");
        body_.f64(v);
        return *this;
    }
    Desc& unit(const std::string& key, const char* unit, double v) {
        start(key, "UntF");
        body_.os(unit);
        body_.f64(v);
        return *this;
    }
    Desc& integer(const std::string& key, std::int32_t v) {
        start(key, "long");
        body_.u32(static_cast<std::uint32_t>(v));
        return *this;
    }
    Desc& enumeration(const std::string& key, const std::string& type, const std::string& value) {
        start(key, "enum");
        body_.id(type);
        body_.id(value);
        return *this;
    }
    Desc& text(const std::string& key, const std::string& v) {
        start(key, "TEXT");
        body_.unicode(v);
        return *this;
    }
    Desc& object(const std::string& key, const Desc& d) {
        start(key, "Objc");
        d.serialize(body_);
        return *this;
    }
    Desc& list(const std::string& key, const std::vector<Desc>& items) {
        start(key, "VlLs");
        body_.u32(static_cast<std::uint32_t>(items.size()));
        for (const auto& item : items) {
            body_.os("Objc");
            item.serialize(body_);
        }
        return *this;
    }

    void serialize(Writer& out) const {
        out.unicode("");
        out.id(class_id_);
        out.u32(count_);
        out.bytes.insert(out.bytes.end(), body_.bytes.begin(), body_.bytes.end());
    }

private:
    void start(const std::string& key, const char* type) {
        ++count_;
        body_.id(key);
        body_.os(type);
    }
    std::string class_id_;
    std::uint32_t count_ = 0;
    Writer body_;
};

std::string blendEnum(BlendMode mode) {
    static const std::array<const char*, 27> kKeys{
        "Nrml", "Dslv", "Drkn", "Mltp", "CBrn", "linearBurn", "darkerColor", "Lghn", "Scrn", "CDdg", "linearDodge", "lighterColor", "Ovrl", "SftL",
        "HrdL", "vividLight", "linearLight", "pinLight", "hardMix", "Dfrn", "Xclu", "blendSubtraction", "blendDivide", "H   ", "Strt", "Clr ", "Lmns"};
    const auto i = static_cast<std::size_t>(mode);
    return i < kKeys.size() ? kKeys[i] : "Nrml";
}

Desc rgb(const styles::Rgba& c) {
    Desc d("RGBC");
    d.doub("Rd  ", static_cast<double>(c[0]) * 255.0).doub("Grn ", static_cast<double>(c[1]) * 255.0).doub("Bl  ", static_cast<double>(c[2]) * 255.0);
    return d;
}

double pct(float v) { return static_cast<double>(v) * 100.0; }

Desc shadow(const char* cls, const styles::Shadow& s) {
    Desc d(cls);
    d.boolean("enab", s.enabled).enumeration("Md  ", "BlnM", blendEnum(s.mode)).object("Clr ", rgb(s.color)).unit("Opct", "#Prc", pct(s.opacity));
    d.boolean("uglg", false).unit("lagl", "#Ang", s.angle).unit("Dstn", "#Pxl", s.distance).unit("Ckmt", "#Pxl", s.spread).unit("blur", "#Pxl", s.size);
    d.unit("Nose", "#Prc", 0).boolean("AntA", false).boolean("layerConceals", true);
    return d;
}

Desc glow(const char* cls, const styles::Glow& g, bool inner) {
    Desc d(cls);
    d.boolean("enab", g.enabled).enumeration("Md  ", "BlnM", blendEnum(g.mode)).object("Clr ", rgb(g.color)).unit("Opct", "#Prc", pct(g.opacity));
    d.enumeration("GlwT", "BETE", "SfBL").unit("Ckmt", "#Pxl", g.spread).unit("blur", "#Pxl", g.size).unit("Nose", "#Prc", 0).unit("ShdN", "#Prc", 0);
    d.boolean("AntA", false).unit("Inpr", "#Prc", 50);
    if (inner) d.enumeration("glwS", "IGSr", g.from_center ? "SrcC" : "SrcE");
    return d;
}

}  // namespace

std::vector<std::uint8_t> encodeEffects(const styles::LayerStyles& s) {
    Desc root("null");
    root.unit("Scl ", "#Prc", 100).boolean("masterFXSwitch", true);
    root.object("DrSh", shadow("DrSh", s.drop_shadow));
    root.object("IrSh", shadow("IrSh", s.inner_shadow));
    root.object("OrGl", glow("OrGl", s.outer_glow, false));
    root.object("IrGl", glow("IrGl", s.inner_glow, true));
    {
        const auto& b = s.bevel;
        static const char* kStyles[] = {"InrB", "OtrB", "Embs", "PlEb"};
        Desc d("ebbl");
        d.boolean("enab", b.enabled).enumeration("hglM", "BlnM", blendEnum(b.highlight_mode)).object("hglC", rgb(b.highlight_color)).unit("hglO", "#Prc", pct(b.highlight_opacity));
        d.enumeration("sdwM", "BlnM", blendEnum(b.shadow_mode)).object("sdwC", rgb(b.shadow_color)).unit("sdwO", "#Prc", pct(b.shadow_opacity));
        d.enumeration("bvlT", "bvlT", b.technique == styles::BevelTechnique::Smooth ? "SfBL" : "PrBL");
        d.enumeration("bvlS", "BESl", kStyles[static_cast<std::size_t>(b.style) % 4]);
        d.boolean("uglg", false).unit("lagl", "#Ang", b.angle).unit("Lald", "#Ang", b.altitude).unit("srgR", "#Prc", pct(b.depth));
        d.unit("blur", "#Pxl", b.size).enumeration("bvlD", "BESs", b.up ? "In  " : "Out ").unit("Sftn", "#Pxl", b.soften);
        d.boolean("antialiasGloss", false).boolean("useShape", false).boolean("useTexture", false);
        root.object("ebbl", d);
    }
    {
        const auto& t = s.satin;
        Desc d("ChFX");
        d.boolean("enab", t.enabled).enumeration("Md  ", "BlnM", blendEnum(t.mode)).object("Clr ", rgb(t.color)).boolean("AntA", true).boolean("Invr", t.invert);
        d.unit("Opct", "#Prc", pct(t.opacity)).unit("lagl", "#Ang", t.angle).unit("Dstn", "#Pxl", t.distance).unit("blur", "#Pxl", t.size);
        root.object("ChFX", d);
    }
    {
        const auto& c = s.color_overlay;
        Desc d("SoFi");
        d.boolean("enab", c.enabled).enumeration("Md  ", "BlnM", blendEnum(c.mode)).unit("Opct", "#Prc", pct(c.opacity)).object("Clr ", rgb(c.color));
        root.object("SoFi", d);
    }
    {
        const auto& g = s.gradient_overlay;
        std::vector<Desc> colors;
        std::vector<Desc> alphas;
        for (const auto& stop : g.stops) {
            const auto location = static_cast<std::int32_t>(std::clamp(stop.position, 0.0F, 1.0F) * 4096.0F);
            Desc c("Clrt");
            c.object("Clr ", rgb(stop.color)).enumeration("Type", "Clry", "UsrS").integer("Lctn", location).integer("Mdpn", 50);
            colors.push_back(std::move(c));
            Desc a("TrnS");
            a.unit("Opct", "#Prc", pct(stop.color[3])).integer("Lctn", location).integer("Mdpn", 50);
            alphas.push_back(std::move(a));
        }
        Desc gradient("Grdn");
        gradient.text("Nm  ", "Custom").enumeration("GrdF", "GrdF", "CstS").doub("Intr", 4096).list("Clrs", colors).list("Trns", alphas);
        Desc offset("Pnt ");
        offset.unit("Hrzn", "#Prc", 0).unit("Vrtc", "#Prc", 0);
        Desc d("GrFl");
        d.boolean("enab", g.enabled).enumeration("Md  ", "BlnM", blendEnum(g.mode)).unit("Opct", "#Prc", pct(g.opacity)).object("Grad", gradient);
        d.unit("Angl", "#Ang", g.angle).enumeration("Type", "GrdT", "Lnr ").boolean("Rvrs", g.reverse).boolean("Dthr", false);
        d.unit("Scl ", "#Prc", pct(g.scale)).boolean("Algn", true).object("Ofst", offset);
        root.object("GrFl", d);
    }
    {
        const auto& k = s.stroke;
        static const char* kPositions[] = {"OutF", "InsF", "CtrF"};
        Desc d("FrFX");
        d.boolean("enab", k.enabled).enumeration("Styl", "FStl", kPositions[static_cast<std::size_t>(k.position) % 3]).enumeration("PntT", "FrFl", "SClr");
        d.enumeration("Md  ", "BlnM", blendEnum(k.mode)).unit("Opct", "#Prc", pct(k.opacity)).unit("Sz  ", "#Pxl", k.size).object("Clr ", rgb(k.color));
        root.object("FrFX", d);
    }
    Writer out;
    out.u32(0);   // object effects version
    out.u32(16);  // descriptor version
    root.serialize(out);
    return out.bytes;
}

}  // namespace croptop::psd
