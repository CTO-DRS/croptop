// Tests for decoding PSD layer effects and adjustment layers (psd_layer_data).
#include "psd_descriptor.h"
#include "psd_layer_data.h"

#include <gtest/gtest.h>

#include <algorithm>
#include <bit>
#include <cstdio>
#include <random>
#include <cstring>
#include <string>
#include <vector>

namespace {

namespace ps = croptop::psd;
using Bytes = std::vector<std::uint8_t>;

struct D {
    Bytes b;
    void u8(std::uint8_t v) { b.push_back(v); }
    void u16(std::uint16_t v) { u8(static_cast<std::uint8_t>(v >> 8)), u8(static_cast<std::uint8_t>(v)); }
    void u32(std::uint32_t v) { u16(static_cast<std::uint16_t>(v >> 16)), u16(static_cast<std::uint16_t>(v)); }
    void f64(double v) {
        const auto bits = std::bit_cast<std::uint64_t>(v);
        u32(static_cast<std::uint32_t>(bits >> 32)), u32(static_cast<std::uint32_t>(bits));
    }
    void ascii(const std::string& s) { b.insert(b.end(), s.begin(), s.end()); }
    void key(const std::string& k) {
        u32(k.size() == 4 ? 0 : static_cast<std::uint32_t>(k.size()));
        ascii(k);
    }
    void begin(const std::string& cls, std::uint32_t items) {
        u32(1), u16(0);  // empty class name
        key(cls);
        u32(items);
    }
    void untf(const std::string& k, const std::string& unit, double v) { key(k), ascii("UntF"), ascii(unit), f64(v); }
    void boolean(const std::string& k, bool v) { key(k), ascii("bool"), u8(v ? 1 : 0); }
    void enumeration(const std::string& k, const std::string& type, const std::string& value) { key(k), ascii("enum"), key(type), key(value); }
    void longv(const std::string& k, std::int32_t v) { key(k), ascii("long"), u32(static_cast<std::uint32_t>(v)); }
    void rgb(const std::string& k, double r, double g, double bl) {
        key(k), ascii("Objc");
        begin("RGBC", 3);
        key("Rd  "), ascii("doub"), f64(r);
        key("Grn "), ascii("doub"), f64(g);
        key("Bl  "), ascii("doub"), f64(bl);
    }
    void object(const std::string& k, const std::string& cls, std::uint32_t items) { key(k), ascii("Objc"), begin(cls, items); }
};

Bytes lfx2() {
    D d;
    d.u32(0), d.u32(16);
    d.begin("null", 6);
    d.untf("Scl ", "#Prc", 200);
    d.boolean("masterFXSwitch", true);
    d.object("DrSh", "DrSh", 9);
    d.boolean("enab", true);
    d.enumeration("Md  ", "BlnM", "Mltp");
    d.rgb("Clr ", 255, 0, 0);
    d.untf("Opct", "#Prc", 60);
    d.boolean("uglg", false);
    d.untf("lagl", "#Ang", 90);
    d.untf("Dstn", "#Pxl", 10);
    d.untf("Ckmt", "#Pxl", 20);
    d.untf("blur", "#Pxl", 8);
    d.object("FrFX", "FrFX", 5);
    d.boolean("enab", true);
    d.enumeration("Styl", "FStl", "InsF");
    d.enumeration("PntT", "FrFl", "SClr");
    d.untf("Sz  ", "#Pxl", 4);
    d.rgb("Clr ", 0, 0, 255);
    d.object("ebbl", "ebbl", 5);
    d.boolean("enab", true);
    d.enumeration("bvlS", "BESl", "Embs");
    d.enumeration("bvlD", "BESs", "Out ");
    d.untf("blur", "#Pxl", 6);
    d.boolean("uglg", true);
    d.object("GrFl", "GrFl", 4);
    d.boolean("enab", true);
    d.untf("Angl", "#Ang", 0);
    d.object("Grad", "Grdn", 2);
    d.key("Clrs"), d.ascii("VlLs"), d.u32(2);
    for (const auto& [location, red] : std::vector<std::pair<int, double>>{{0, 0}, {4096, 255}}) {
        d.ascii("Objc");
        d.begin("Clrt", 2);
        d.rgb("Clr ", red, 0, 0);
        d.longv("Lctn", location);
    }
    d.key("Trns"), d.ascii("VlLs"), d.u32(2);
    for (const auto& [location, opacity] : std::vector<std::pair<int, double>>{{0, 100}, {4096, 50}}) {
        d.ascii("Objc");
        d.begin("TrnS", 2);
        d.untf("Opct", "#Prc", opacity);
        d.longv("Lctn", location);
    }
    d.enumeration("Md  ", "BlnM", "Scrn");
    return d.b;
}

TEST(PsdLayerData, DecodesEffects) {
    std::vector<std::string> warnings;
    const auto s = ps::decodeEffects(lfx2(), {135, 45}, warnings);
    ASSERT_TRUE(s);
    EXPECT_TRUE(s->drop_shadow.enabled);
    EXPECT_EQ(s->drop_shadow.mode, croptop::BlendMode::Multiply);
    EXPECT_FLOAT_EQ(s->drop_shadow.color[0], 1.0F);
    EXPECT_FLOAT_EQ(s->drop_shadow.opacity, 0.6F);
    EXPECT_FLOAT_EQ(s->drop_shadow.angle, 90.0F) << "local angle when global light is off";
    EXPECT_FLOAT_EQ(s->drop_shadow.distance, 20.0F) << "scaled 200 %";
    EXPECT_FLOAT_EQ(s->drop_shadow.spread, 20.0F);
    EXPECT_FLOAT_EQ(s->drop_shadow.size, 16.0F);
    EXPECT_TRUE(s->stroke.enabled);
    EXPECT_EQ(s->stroke.position, croptop::styles::StrokePosition::Inside);
    EXPECT_FLOAT_EQ(s->stroke.size, 8.0F);
    EXPECT_FLOAT_EQ(s->stroke.color[2], 1.0F);
    EXPECT_TRUE(s->bevel.enabled);
    EXPECT_EQ(s->bevel.style, croptop::styles::BevelStyle::Emboss);
    EXPECT_FALSE(s->bevel.up);
    EXPECT_FLOAT_EQ(s->bevel.angle, 135.0F) << "global light";
    EXPECT_FLOAT_EQ(s->bevel.altitude, 45.0F);
    EXPECT_TRUE(s->gradient_overlay.enabled);
    EXPECT_EQ(s->gradient_overlay.mode, croptop::BlendMode::Screen);
    ASSERT_EQ(s->gradient_overlay.stops.size(), 2U);
    EXPECT_FLOAT_EQ(s->gradient_overlay.stops[1].color[0], 1.0F);
    EXPECT_FLOAT_EQ(s->gradient_overlay.stops[1].color[3], 0.5F) << "transparency stops";
    EXPECT_FALSE(s->inner_glow.enabled);
    // Master switch off disables everything; garbage is reported, not crashed on.
    auto off = lfx2();
    const std::string key = "masterFXSwitch";
    const auto it = std::search(off.begin(), off.end(), key.begin(), key.end());
    ASSERT_NE(it, off.end());
    *(it + static_cast<std::ptrdiff_t>(key.size()) + 4) = 0;
    const auto disabled = ps::decodeEffects(off, {}, warnings);
    ASSERT_TRUE(disabled);
    EXPECT_FALSE(disabled->drop_shadow.enabled);
    EXPECT_FALSE(ps::decodeEffects(Bytes{0, 0, 0, 0, 0, 0, 0, 16, 1}, {}, warnings));
    EXPECT_FALSE(warnings.empty());
}

TEST(PsdLayerData, SurvivesMutatedBlocks) {
    std::mt19937 random(21);
    const auto seed = lfx2();
    int decoded = 0;
    for (int i = 0; i < 3000; ++i) {
        auto bytes = seed;
        for (int e = 0; e < 1 + static_cast<int>(random() % 6); ++e) {
            const auto at = random() % bytes.size();
            switch (random() % 3) {
                case 0: bytes[at] = static_cast<std::uint8_t>(random()); break;
                case 1: bytes[at] = random() % 2 ? 0xFF : 0x00; break;
                default: bytes.resize(std::max<std::size_t>(1, at)); break;
            }
        }
        std::vector<std::string> warnings;
        if (ps::decodeEffects(bytes, {}, warnings)) ++decoded;
        for (const char* key : {"levl", "curv", "hue2", "blnc", "mixr", "selc", "expA", "vibA", "blwh"}) (void)ps::decodeAdjustment(key, bytes, {}, warnings);
    }
    std::printf("[psd] %d / 3000 mutated effect blocks decoded\n", decoded);
}

TEST(PsdLayerData, DecodesBinaryAdjustments) {
    std::vector<std::string> warnings;
    const std::vector<ps::AdditionalInfo> none;
    D levl;
    levl.u16(2);
    for (const int v : {20, 230, 10, 245, 150}) levl.u16(static_cast<std::uint16_t>(v));
    for (int i = 0; i < 28; ++i)
        for (const int v : {0, 255, 0, 255, 100}) levl.u16(static_cast<std::uint16_t>(v));
    const auto l = ps::decodeAdjustment("levl", levl.b, none, warnings);
    ASSERT_TRUE(l);
    const auto& levels = std::get<croptop::adjust::Levels>(*l);
    EXPECT_FLOAT_EQ(levels.in_black, 20 / 255.0F);
    EXPECT_FLOAT_EQ(levels.gamma, 1.5F);
    EXPECT_FLOAT_EQ(levels.out_white, 245 / 255.0F);

    D curv;
    curv.u8(0), curv.u16(1), curv.u32(0b0011);
    curv.u16(3);
    for (const auto& [out, in] : std::vector<std::pair<int, int>>{{0, 0}, {160, 128}, {255, 255}}) curv.u16(static_cast<std::uint16_t>(out)), curv.u16(static_cast<std::uint16_t>(in));
    curv.u16(2);
    for (const auto& [out, in] : std::vector<std::pair<int, int>>{{0, 0}, {128, 255}}) curv.u16(static_cast<std::uint16_t>(out)), curv.u16(static_cast<std::uint16_t>(in));
    const auto c = ps::decodeAdjustment("curv", curv.b, none, warnings);
    ASSERT_TRUE(c);
    const auto& curves = std::get<croptop::adjust::Curves>(*c);
    EXPECT_NEAR(curves.rgb.evaluate(128 / 255.0F), 160 / 255.0F, 1e-6F);
    EXPECT_NEAR(curves.red.evaluate(1.0F), 128 / 255.0F, 1e-6F);

    D hue;
    hue.u16(2), hue.u8(0), hue.u8(0);
    for (int i = 0; i < 3; ++i) hue.u16(0);
    hue.u16(30), hue.u16(static_cast<std::uint16_t>(-20)), hue.u16(10);
    for (int i = 0; i < 6; ++i) {
        for (const int v : {315, 345, 15, 45}) hue.u16(static_cast<std::uint16_t>(v));
        hue.u16(i == 0 ? 5 : 0), hue.u16(0), hue.u16(0);
    }
    const auto h = ps::decodeAdjustment("hue2", hue.b, none, warnings);
    ASSERT_TRUE(h);
    const auto& hs = std::get<croptop::adjust::HueSaturation>(*h);
    EXPECT_FLOAT_EQ(hs.master.hue, 30);
    EXPECT_FLOAT_EQ(hs.master.saturation, -20);
    EXPECT_FLOAT_EQ(hs.channels[0].hue, 5);

    D blnc;
    for (const int v : {10, -20, 30, 0, 0, 0, -5, 5, 0}) blnc.u16(static_cast<std::uint16_t>(v));
    blnc.u8(0);
    const auto b = ps::decodeAdjustment("blnc", blnc.b, none, warnings);
    ASSERT_TRUE(b);
    const auto& balance = std::get<croptop::adjust::ColorBalance>(*b);
    EXPECT_FLOAT_EQ(balance.shadows.magenta_green, -20);
    EXPECT_FLOAT_EQ(balance.highlights.cyan_red, -5);
    EXPECT_FALSE(balance.preserve_luminosity);

    D post, thrs;
    post.u16(6), post.u16(0);
    thrs.u16(100), thrs.u16(0);
    EXPECT_FLOAT_EQ(std::get<croptop::adjust::Posterize>(*ps::decodeAdjustment("post", post.b, none, warnings)).levels, 6);
    EXPECT_FLOAT_EQ(std::get<croptop::adjust::Threshold>(*ps::decodeAdjustment("thrs", thrs.b, none, warnings)).level, 100);
    EXPECT_TRUE(std::holds_alternative<croptop::adjust::Invert>(*ps::decodeAdjustment("nvrt", {}, none, warnings)));

    D mixr;
    mixr.u16(1), mixr.u16(0);
    for (const auto& row : std::vector<std::array<int, 5>>{{0, 0, 100, 0, 5}, {0, 100, 0, 0, 0}, {100, 0, 0, 0, 0}, {0, 0, 0, 0, 0}})
        for (const int v : row) mixr.u16(static_cast<std::uint16_t>(v));
    const auto m = ps::decodeAdjustment("mixr", mixr.b, none, warnings);
    ASSERT_TRUE(m);
    const auto& mixer = std::get<croptop::adjust::ChannelMixer>(*m);
    EXPECT_FLOAT_EQ(mixer.matrix[0][2], 100);
    EXPECT_FLOAT_EQ(mixer.constant[0], 5);

    D selc;
    selc.u16(1), selc.u16(1);
    for (int r = 0; r < 10; ++r)
        for (int k = 0; k < 4; ++k) selc.u16(static_cast<std::uint16_t>(r == 1 && k == 0 ? 40 : 0));
    const auto sc = ps::decodeAdjustment("selc", selc.b, none, warnings);
    ASSERT_TRUE(sc);
    EXPECT_TRUE(std::get<croptop::adjust::SelectiveColor>(*sc).absolute);
    EXPECT_FLOAT_EQ(std::get<croptop::adjust::SelectiveColor>(*sc).cmyk[0][0], 40) << "reds (after the unused record)";

    D expa;
    expa.u16(1);
    for (const float v : {1.5F, 0.01F, 1.2F}) expa.u32(std::bit_cast<std::uint32_t>(v));
    const auto e = ps::decodeAdjustment("expA", expa.b, none, warnings);
    ASSERT_TRUE(e);
    EXPECT_FLOAT_EQ(std::get<croptop::adjust::Exposure>(*e).exposure, 1.5F);
}

TEST(PsdLayerData, DecodesDescriptorAdjustmentsAndCompanionBlocks) {
    std::vector<std::string> warnings;
    D vib;
    vib.u32(16);
    vib.begin("null", 2);
    vib.longv("vibrance", 25);
    vib.longv("Strt", -10);
    const auto v = ps::decodeAdjustment("vibA", vib.b, {}, warnings);
    ASSERT_TRUE(v);
    EXPECT_FLOAT_EQ(std::get<croptop::adjust::Vibrance>(*v).vibrance, 25);
    EXPECT_FLOAT_EQ(std::get<croptop::adjust::Vibrance>(*v).saturation, -10);

    D bw;
    bw.u32(16);
    bw.begin("null", 3);
    bw.longv("Rd  ", 120);
    bw.boolean("useTint", true);
    bw.rgb("tintColor", 255, 128, 0);
    const auto b = ps::decodeAdjustment("blwh", bw.b, {}, warnings);
    ASSERT_TRUE(b);
    EXPECT_FLOAT_EQ(std::get<croptop::adjust::BlackWhite>(*b).reds, 120);
    EXPECT_TRUE(std::get<croptop::adjust::BlackWhite>(*b).tint);

    D cged;
    cged.u32(16);
    cged.begin("null", 3);
    cged.longv("Brgh", 40);
    cged.longv("Cntr", -20);
    cged.boolean("useLegacy", false);
    ps::Layer layer;
    layer.extra.push_back({"brit", {0, 1, 0, 2, 0, 0, 0}});
    layer.extra.push_back({"CgEd", cged.b});
    const auto adjustment = ps::adjustmentOf(layer, warnings);
    ASSERT_TRUE(adjustment);
    const auto& bc = std::get<croptop::adjust::BrightnessContrast>(*adjustment);
    EXPECT_FLOAT_EQ(bc.brightness, 40) << "CgEd wins over the legacy brit values";
    EXPECT_FALSE(bc.legacy);

    warnings.clear();
    EXPECT_FALSE(ps::decodeAdjustment("levl", Bytes{0, 2, 0}, {}, warnings));
    EXPECT_FALSE(warnings.empty());
    EXPECT_FALSE(ps::decodeAdjustment("grdm", Bytes{}, {}, warnings));
    D phfl;
    phfl.u16(2), phfl.u16(0), phfl.u16(65535), phfl.u16(32768), phfl.u16(0), phfl.u16(0), phfl.u32(40), phfl.u8(1);
    const auto f = ps::decodeAdjustment("phfl", phfl.b, {}, warnings);
    ASSERT_TRUE(f);
    EXPECT_FLOAT_EQ(std::get<croptop::adjust::PhotoFilter>(*f).density, 40);
    EXPECT_NEAR(std::get<croptop::adjust::PhotoFilter>(*f).color[1], 0.5F, 1e-4F);
    D grdm;
    grdm.u16(1), grdm.u8(1), grdm.u8(0), grdm.u32(2), grdm.u16('G'), grdm.u16('M'), grdm.u16(2);
    for (const auto& [location, value] : std::vector<std::pair<std::uint32_t, std::uint16_t>>{{0, 0}, {4096, 65535}}) {
        grdm.u32(location), grdm.u32(50), grdm.u16(0), grdm.u16(value), grdm.u16(0), grdm.u16(0), grdm.u16(0), grdm.u16(0);
    }
    const auto g = ps::decodeAdjustment("grdm", grdm.b, {}, warnings);
    ASSERT_TRUE(g);
    const auto& map = std::get<croptop::adjust::GradientMap>(*g);
    EXPECT_TRUE(map.reverse);
    ASSERT_EQ(map.stops.size(), 2U);
    EXPECT_FLOAT_EQ(map.stops[1].position, 1.0F);
    EXPECT_FLOAT_EQ(map.stops[1].color[0], 1.0F);
    ps::Layer plain;
    EXPECT_FALSE(ps::adjustmentOf(plain, warnings));
    EXPECT_FALSE(ps::effectsOf(plain, {}, warnings));
}

}  // namespace
