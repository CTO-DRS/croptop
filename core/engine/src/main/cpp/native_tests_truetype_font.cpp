// Tests for the TrueType reader and text rendering.
#include "truetype_font.h"

#include <gtest/gtest.h>

#include <algorithm>
#include <cstring>
#include <fstream>
#include <iterator>
#include <map>
#include <string>
#include <vector>

namespace {

namespace text = croptop::text;
using Bytes = std::vector<std::uint8_t>;

void put16(Bytes& b, int v) {
    b.push_back(static_cast<std::uint8_t>((v >> 8) & 0xFF));
    b.push_back(static_cast<std::uint8_t>(v & 0xFF));
}
void put32(Bytes& b, std::uint32_t v) {
    put16(b, static_cast<int>(v >> 16));
    put16(b, static_cast<int>(v & 0xFFFF));
}

/** A simple glyph from contours of (x, y, on-curve) points, using 16-bit coordinates throughout. */
Bytes simpleGlyph(const std::vector<std::vector<std::array<int, 3>>>& contours) {
    Bytes g;
    put16(g, static_cast<int>(contours.size()));
    for (int k = 0; k < 4; ++k) put16(g, 0);  // bbox (unused by the reader)
    int end = -1;
    for (const auto& c : contours) put16(g, end += static_cast<int>(c.size()));
    put16(g, 0);  // no instructions
    for (const auto& c : contours)
        for (const auto& p : c) g.push_back(static_cast<std::uint8_t>(p[2] ? 0x01 : 0x00));
    int last = 0;
    for (const auto& c : contours)
        for (const auto& p : c) put16(g, p[0] - last), last = p[0];
    last = 0;
    for (const auto& c : contours)
        for (const auto& p : c) put16(g, p[1] - last), last = p[1];
    while (g.size() % 2) g.push_back(0);
    return g;
}

/** Test font: .notdef (empty), 'A' square, 'V' curve (off-curve points), space (empty), 'B' compound of 'A'. */
Bytes buildFont(int curve_code = 86) {  // 86 = 'V'
    std::vector<Bytes> glyphs;
    glyphs.push_back({});
    glyphs.push_back(simpleGlyph({{{0, 0, 1}, {600, 0, 1}, {600, 700, 1}, {0, 700, 1}}}));
    glyphs.push_back(simpleGlyph({{{0, 700, 1}, {300, -200, 0}, {600, 700, 0}, {300, 400, 1}}}));
    glyphs.push_back({});
    Bytes compound;
    put16(compound, -1);
    for (int k = 0; k < 4; ++k) put16(compound, 0);
    put16(compound, 0x0001 | 0x0002 | 0x0008);  // word args, xy values, uniform scale; no more components
    put16(compound, 1);                          // glyph 'A'
    put16(compound, 100);
    put16(compound, 0);
    put16(compound, 8192);  // 0.5 in F2Dot14
    glyphs.push_back(compound);

    std::map<std::string, Bytes> t;
    Bytes& head = t["head"];
    put32(head, 0x00010000);
    put32(head, 0x00010000);
    put32(head, 0);
    put32(head, 0x5F0F3CF5);
    put16(head, 0);
    put16(head, 1000);
    for (int k = 0; k < 16; ++k) head.push_back(0);
    for (int k = 0; k < 4; ++k) put16(head, 0);
    put16(head, 0);
    put16(head, 8);
    put16(head, 2);
    put16(head, 0);  // indexToLocFormat: short
    put16(head, 0);
    Bytes& hhea = t["hhea"];
    put32(hhea, 0x00010000);
    put16(hhea, 800);
    put16(hhea, -200);
    put16(hhea, 0);
    for (int k = 0; k < 12; ++k) put16(hhea, 0);
    put16(hhea, static_cast<int>(glyphs.size()));
    Bytes& maxp = t["maxp"];
    put32(maxp, 0x00005000);
    put16(maxp, static_cast<int>(glyphs.size()));
    Bytes& hmtx = t["hmtx"];
    for (const int adv : {500, 600, 600, 250, 600}) put16(hmtx, adv), put16(hmtx, 0);
    // cmap format 4: ' ' → 3, 'A' → 1, 'B' → 4, 'V' → 2, and the terminating 0xFFFF segment.
    Bytes& cmap = t["cmap"];
    put16(cmap, 0);
    put16(cmap, 1);
    put16(cmap, 3);
    put16(cmap, 1);
    put32(cmap, 12);
    const std::vector<std::array<int, 3>> segments{{32, 32, 3}, {65, 66, 1}, {86, 86, 2}, {0xFFFF, 0xFFFF, 0}};
    const int seg_x2 = static_cast<int>(segments.size()) * 2;
    put16(cmap, 4);
    put16(cmap, 16 + seg_x2 * 4);
    put16(cmap, 0);
    put16(cmap, seg_x2);
    put16(cmap, 0), put16(cmap, 0), put16(cmap, 0);
    for (const auto& s : segments) put16(cmap, s[1]);
    put16(cmap, 0);
    for (const auto& s : segments) put16(cmap, s[0]);
    for (const auto& s : segments) put16(cmap, s[0] == 0xFFFF ? 1 : (s[2] - s[0]) & 0xFFFF);  // idDelta
    for (std::size_t k = 0; k < segments.size(); ++k) put16(cmap, 0);
    // 'A'..'B' map to glyphs 1 and 2 by delta, but 'B' must be 4: give 'B' its own segment instead.
    cmap.clear();
    put16(cmap, 0);
    put16(cmap, 1);
    put16(cmap, 3);
    put16(cmap, 1);
    put32(cmap, 12);
    const std::vector<std::array<int, 3>> segs{{32, 32, 3}, {65, 65, 1}, {66, 66, 4}, {curve_code, curve_code, 2}, {0xFFFF, 0xFFFF, 0}};
    const int sx2 = static_cast<int>(segs.size()) * 2;
    put16(cmap, 4);
    put16(cmap, 16 + sx2 * 4);
    put16(cmap, 0);
    put16(cmap, sx2);
    put16(cmap, 0), put16(cmap, 0), put16(cmap, 0);
    for (const auto& s : segs) put16(cmap, s[1]);
    put16(cmap, 0);
    for (const auto& s : segs) put16(cmap, s[0]);
    for (const auto& s : segs) put16(cmap, s[0] == 0xFFFF ? 1 : (s[2] - s[0]) & 0xFFFF);
    for (std::size_t k = 0; k < segs.size(); ++k) put16(cmap, 0);
    Bytes& glyf = t["glyf"];
    Bytes& loca = t["loca"];
    for (const auto& g : glyphs) {
        put16(loca, static_cast<int>(glyf.size() / 2));
        glyf.insert(glyf.end(), g.begin(), g.end());
    }
    put16(loca, static_cast<int>(glyf.size() / 2));
    Bytes& kern = t["kern"];
    put16(kern, 0);
    put16(kern, 1);
    put16(kern, 0);
    put16(kern, 14 + 6);
    put16(kern, 0x0001);
    put16(kern, 1);
    put16(kern, 6), put16(kern, 0), put16(kern, 0);
    put16(kern, 1);
    put16(kern, 2);
    put16(kern, -80);

    Bytes font;
    put32(font, 0x00010000);
    put16(font, static_cast<int>(t.size()));
    put16(font, 0), put16(font, 0), put16(font, 0);
    std::size_t offset = 12 + t.size() * 16;
    Bytes body;
    for (const auto& [tag, data] : t) {
        for (const char ch : tag) font.push_back(static_cast<std::uint8_t>(ch));
        put32(font, 0);
        put32(font, static_cast<std::uint32_t>(offset + body.size()));
        put32(font, static_cast<std::uint32_t>(data.size()));
        body.insert(body.end(), data.begin(), data.end());
        while (body.size() % 4) body.push_back(0);
    }
    font.insert(font.end(), body.begin(), body.end());
    return font;
}

TEST(TrueTypeFont, ParsesTablesGlyphsMetricsAndKerning) {
    const auto bytes = buildFont();
    std::string error;
    const auto font = text::TrueTypeFont::load(bytes, error);
    ASSERT_NE(font, nullptr) << error;
    EXPECT_EQ(font->unitsPerEm(), 1000);
    EXPECT_EQ(font->ascender(), 800);
    EXPECT_EQ(font->descender(), -200);
    EXPECT_EQ(font->glyphFor(U'A'), 1);
    EXPECT_EQ(font->glyphFor(U'V'), 2);
    EXPECT_EQ(font->glyphFor(U' '), 3);
    EXPECT_EQ(font->glyphFor(U'B'), 4);
    EXPECT_EQ(font->glyphFor(U'Z'), 0);
    EXPECT_EQ(font->glyphFor(0x1F600), 0);
    EXPECT_EQ(font->advance(1), 600);
    EXPECT_EQ(font->advance(3), 250);
    EXPECT_EQ(font->kerning(1, 2), -80);
    EXPECT_EQ(font->kerning(2, 1), 0);
    EXPECT_TRUE(font->outline(3).empty()) << "space has no outline";
    EXPECT_FALSE(font->outline(1).empty());
    // The compound 'B' is 'A' at half size shifted by 100 units.
    const auto b = croptop::vector::flatten(font->outline(4));
    ASSERT_FALSE(b.empty());
    double min_x = 1e9, max_x = -1e9, max_y = -1e9;
    for (const auto& poly : b)
        for (const auto& p : poly.points) min_x = std::min(min_x, p.x), max_x = std::max(max_x, p.x), max_y = std::max(max_y, p.y);
    EXPECT_NEAR(min_x, 100, 1e-6);
    EXPECT_NEAR(max_x, 400, 1e-6);
    EXPECT_NEAR(max_y, 350, 1e-6);
    // The 'V' outline has quadratic segments (off-curve points).
    const auto v_outline = font->outline(2);
    const auto& verbs = v_outline.verbs();
    EXPECT_NE(std::find(verbs.begin(), verbs.end(), croptop::vector::Verb::Quad), verbs.end());
}

TEST(TrueTypeFont, ShapesAndRendersLaidOutText) {
    std::string error;
    const auto font = text::TrueTypeFont::load(buildFont(), error);
    ASSERT_NE(font, nullptr) << error;
    const text::TrueTypeShaper shaper(font);
    const auto glyphs = shaper.shape(U"AV", 100);
    ASSERT_EQ(glyphs.size(), 2U);
    EXPECT_FLOAT_EQ(glyphs[0].advance, 60 - 8) << "advance plus the AV kerning pair";
    EXPECT_FLOAT_EQ(glyphs[1].advance, 60);
    EXPECT_FLOAT_EQ(shaper.ascent(100), 80);
    EXPECT_FLOAT_EQ(shaper.descent(100), 20);

    text::ParagraphStyle style;
    style.size_px = 100;
    const auto layout = text::layout("A A", style, 0, shaper);
    croptop::TileStore layer(croptop::PixelFormat::RGBA8, 300, 150);
    ASSERT_TRUE(text::renderText(layer, layout, *font, {1, 0, 0, 1}, 10, 10, error)) << error;
    const auto at = [&](std::uint32_t x, std::uint32_t y) {
        std::array<std::uint8_t, 4> c{};
        const auto t = layer.readTile({static_cast<std::int32_t>(x / 256), static_cast<std::int32_t>(y / 256)});
        if (t) std::memcpy(c.data(), t->data() + (static_cast<std::size_t>(y % 256) * 256 + x % 256) * 4, 4);
        return c;
    };
    // First 'A': x 10…70, baseline at 10 + 80 = 90, square from y 20 to 90.
    EXPECT_EQ(at(40, 60), (std::array<std::uint8_t, 4>{255, 0, 0, 255}));
    EXPECT_EQ(at(40, 95)[3], 0) << "below the baseline";
    EXPECT_EQ(at(75, 60)[3], 0) << "in the space";
    EXPECT_EQ(at(100, 60)[3], 255) << "second 'A' after 60 + 25 px";
    EXPECT_FALSE(text::renderText(layer, layout, *font, {2, 0, 0, 1}, 0, 0, error));
}

TEST(TrueTypeFont, FallbackShaperPicksTheFirstFontWithTheGlyph) {
    std::string error;
    const auto primary = text::TrueTypeFont::load(buildFont(), error);
    ASSERT_NE(primary, nullptr) << error;
    // A second font whose curve glyph is mapped to 'Z' instead of 'V' (the primary lacks 'Z').
    const Bytes secondary_bytes = buildFont(90);
    const auto secondary = text::TrueTypeFont::load(secondary_bytes, error);
    ASSERT_NE(secondary, nullptr) << error;
    ASSERT_EQ(secondary->glyphFor(U'Z'), 2);
    const text::FallbackShaper shaper({primary, secondary});
    const auto glyphs = shaper.shape(U"AZ?", 100);
    ASSERT_EQ(glyphs.size(), 3U);
    EXPECT_EQ(glyphs[0].glyph, 1U) << "font 0, glyph 1";
    EXPECT_EQ(glyphs[1].glyph, (1U << 16) | 2U) << "font 1, glyph 2";
    EXPECT_EQ(glyphs[2].glyph, 0U) << "unmapped: first font's .notdef";
    EXPECT_FLOAT_EQ(glyphs[0].advance, 60) << "no kerning across fonts";

    text::ParagraphStyle style;
    style.size_px = 100;
    const auto layout = text::layout("AZ", style, 0, shaper);
    croptop::TileStore layer(croptop::PixelFormat::RGBA8, 200, 120);
    ASSERT_TRUE(text::renderText(layer, layout, shaper.fonts(), {0, 0, 1, 1}, 0, 0, error)) << error;
    EXPECT_GT(layer.tile_count(), 0U);
}

TEST(TrueTypeFont, RejectsMalformedFontsAndSurvivesMutation) {
    std::string error;
    EXPECT_EQ(text::TrueTypeFont::load(Bytes(8, 0), error), nullptr);
    Bytes otto = buildFont();
    otto[0] = 'O', otto[1] = 'T', otto[2] = 'T', otto[3] = 'O';
    EXPECT_EQ(text::TrueTypeFont::load(otto, error), nullptr);
    EXPECT_NE(error.find("CFF"), std::string::npos);
    Bytes bad_dir = buildFont();
    bad_dir[12 + 8] = 0x7F;  // first table offset far out of range
    EXPECT_EQ(text::TrueTypeFont::load(bad_dir, error), nullptr);

    const Bytes good = buildFont();
    std::uint32_t seed = 5;
    for (int round = 0; round < 2000; ++round) {
        Bytes m = good;
        for (int k = 0; k < 1 + round % 6; ++k) {
            seed = seed * 1664525U + 1013904223U;
            m[seed % m.size()] = static_cast<std::uint8_t>(seed >> 24);
        }
        const auto font = text::TrueTypeFont::load(m, error);
        if (!font) continue;
        for (std::uint16_t g = 0; g < std::min<std::uint16_t>(font->glyphCount(), 16); ++g) (void)croptop::vector::flatten(font->outline(g));
        for (const char32_t c : {U'A', U'B', U'V', U' ', U'Z'}) (void)font->kerning(font->glyphFor(c), 2);
    }
}

TEST(TrueTypeFont, SystemFontSmokeTest) {
    std::ifstream file("/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf", std::ios::binary);
    if (!file) GTEST_SKIP() << "DejaVuSans.ttf not installed";
    const Bytes bytes((std::istreambuf_iterator<char>(file)), std::istreambuf_iterator<char>());
    std::string error;
    const auto font = text::TrueTypeFont::load(bytes, error);
    ASSERT_NE(font, nullptr) << error;
    const text::TrueTypeShaper shaper(font);
    text::ParagraphStyle style;
    style.size_px = 48;
    const auto layout = text::layout("Hello, \xCE\xA9 \xD0\x96 g", style, 0, shaper);  // Latin, Greek, Cyrillic
    for (const auto& g : layout.lines[0].glyphs) {
        if (layout.text[g.cluster] != U' ') {
            EXPECT_NE(g.glyph, 0U) << "unmapped U+" << std::hex << static_cast<std::uint32_t>(layout.text[g.cluster]);
        }
    }
    croptop::TileStore layer(croptop::PixelFormat::RGBA8, 600, 100);
    ASSERT_TRUE(text::renderText(layer, layout, *font, {0, 0, 0, 1}, 5, 5, error)) << error;
    EXPECT_GT(layer.tile_count(), 0U);
}

Bytes readFile(const char* path) {
    std::ifstream file(path, std::ios::binary);
    if (!file) return {};
    return Bytes((std::istreambuf_iterator<char>(file)), std::istreambuf_iterator<char>());
}

struct Bounds {
    double min_x = 1e9, min_y = 1e9, max_x = -1e9, max_y = -1e9;
};
Bounds boundsOf(const croptop::vector::Path& path) {
    Bounds b;
    for (const auto& poly : croptop::vector::flatten(path, 1.0))
        for (const auto& p : poly.points) b.min_x = std::min(b.min_x, p.x), b.max_x = std::max(b.max_x, p.x), b.min_y = std::min(b.min_y, p.y), b.max_y = std::max(b.max_y, p.y);
    return b;
}

TEST(TrueTypeFont, CffNameKeyedOpenTypeSystemFont) {
    const auto bytes = readFile("/usr/share/fonts/opentype/urw-base35/NimbusSans-Regular.otf");
    if (bytes.empty()) GTEST_SKIP() << "NimbusSans-Regular.otf not installed";
    std::string error;
    const auto font = text::TrueTypeFont::load(bytes, error);
    ASSERT_NE(font, nullptr) << error;
    EXPECT_TRUE(font->hasCffOutlines());
    const auto h = boundsOf(font->outline(font->glyphFor(U'H')));
    EXPECT_NEAR(h.min_y, 0, 5) << "'H' sits on the baseline";
    EXPECT_GT(h.max_y, 0.6 * font->unitsPerEm());
    EXPECT_LT(h.max_y, 0.8 * font->unitsPerEm());
    EXPECT_GT(h.max_x - h.min_x, 0.4 * font->unitsPerEm());
    const auto o = boundsOf(font->outline(font->glyphFor(U'o')));
    EXPECT_LT(o.min_y, 0) << "round overshoot below the baseline";
    const text::TrueTypeShaper shaper(font);
    text::ParagraphStyle style;
    style.size_px = 40;
    const auto layout = text::layout("Hamburgefonts", style, 0, shaper);
    croptop::TileStore layer(croptop::PixelFormat::RGBA8, 400, 60);
    ASSERT_TRUE(text::renderText(layer, layout, *font, {0, 0, 0, 1}, 5, 5, error)) << error;
    EXPECT_GT(layer.tile_count(), 0U);
}

TEST(TrueTypeFont, CffCidKeyedCollectionSystemFont) {
    const auto bytes = readFile("/usr/share/fonts/opentype/noto/NotoSerifCJK-Bold.ttc");
    if (bytes.empty()) GTEST_SKIP() << "NotoSerifCJK-Bold.ttc not installed";
    EXPECT_GT(text::TrueTypeFont::faceCount(bytes), 1U);
    std::string error;
    const auto font = text::TrueTypeFont::load(bytes, error, 0);
    ASSERT_NE(font, nullptr) << error;
    EXPECT_TRUE(font->hasCffOutlines());
    const auto glyph = font->glyphFor(U'\u6F22');  // 漢
    ASSERT_NE(glyph, 0);
    const auto b = boundsOf(font->outline(glyph));
    EXPECT_GT(b.max_x - b.min_x, 0.7 * font->unitsPerEm()) << "a full-width ideograph";
    EXPECT_GT(b.min_y, -0.2 * font->unitsPerEm());
    EXPECT_LT(b.max_y, 0.95 * font->unitsPerEm());
    EXPECT_EQ(text::TrueTypeFont::load(bytes, error, 9999), nullptr) << "missing face";
    const text::TrueTypeShaper shaper(font);
    text::ParagraphStyle style;
    style.size_px = 48;
    const auto layout = text::layout("\xE6\xBC\xA2\xE5\xAD\x97", style, 0, shaper);
    croptop::TileStore layer(croptop::PixelFormat::RGBA8, 200, 80);
    ASSERT_TRUE(text::renderText(layer, layout, *font, {0, 0, 0, 1}, 4, 4, error)) << error;
    EXPECT_GT(layer.tile_count(), 0U);
}

TEST(TrueTypeFont, GposPairKerningFromSystemFonts) {
    const auto lato_bytes = readFile("/usr/share/fonts/truetype/lato/Lato-Medium.ttf");
    if (lato_bytes.empty()) GTEST_SKIP() << "Lato not installed";
    std::string error;
    const auto lato = text::TrueTypeFont::load(lato_bytes, error);
    ASSERT_NE(lato, nullptr) << error;
    ASSERT_TRUE(lato->hasGposKerning());
    const auto g = [&](char32_t c) { return lato->glyphFor(c); };
    EXPECT_LT(lato->kerning(g(U'A'), g(U'V')), -20) << "AV kerns tighter";
    EXPECT_LT(lato->kerning(g(U'T'), g(U'o')), -20) << "To kerns tighter";
    EXPECT_EQ(lato->kerning(g(U'H'), g(U'H')), 0) << "straight stems do not kern";
    // Kerning reaches the shaper: "AV" is narrower than A + V shaped separately.
    const text::TrueTypeShaper shaper(lato);
    const auto pair = shaper.shape(U"AV", 100), a = shaper.shape(U"A", 100), v = shaper.shape(U"V", 100);
    EXPECT_LT(pair[0].advance + pair[1].advance, a[0].advance + v[0].advance - 2.0F);
    const auto dejavu_bytes = readFile("/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf");
    if (!dejavu_bytes.empty()) {
        const auto dejavu = text::TrueTypeFont::load(dejavu_bytes, error);
        ASSERT_NE(dejavu, nullptr) << error;
        EXPECT_LT(dejavu->kerning(dejavu->glyphFor(U'A'), dejavu->glyphFor(U'V')), 0);
    }
}

TEST(TrueTypeFont, ArabicJoiningAnalysis) {
    using F = text::ArabicForm;
    // ب ي ت : initial, medial, final. ا (alef, right-joining) after ب: ب initial, ا final.
    EXPECT_EQ(text::arabicForms(U"\u0628\u064A\u062A"), (std::vector<F>{F::Initial, F::Medial, F::Final}));
    EXPECT_EQ(text::arabicForms(U"\u0628\u0627"), (std::vector<F>{F::Initial, F::Final}));
    // Alef does not join to the next letter: ا ب → isolated, isolated.
    EXPECT_EQ(text::arabicForms(U"\u0627\u0628"), (std::vector<F>{F::Isolated, F::Isolated}));
    // A transparent mark (fatha) does not break joining; ZWNJ does.
    EXPECT_EQ(text::arabicForms(U"\u0628\u064E\u062A"), (std::vector<F>{F::Initial, F::None, F::Final}));
    EXPECT_EQ(text::arabicForms(U"\u0628\u200C\u062A"), (std::vector<F>{F::Isolated, F::None, F::Isolated}));
    EXPECT_EQ(text::arabicForms(U"ab"), (std::vector<F>{F::None, F::None}));
}

TEST(TrueTypeFont, GsubLigaturesAndArabicFormsFromSystemFonts) {
    const auto lato_bytes = readFile("/usr/share/fonts/truetype/lato/Lato-Medium.ttf");
    const auto naskh_bytes = readFile("/usr/share/fonts/truetype/noto/NotoNaskhArabic-Bold.ttf");
    if (lato_bytes.empty() || naskh_bytes.empty()) GTEST_SKIP() << "Lato / Noto Naskh Arabic not installed";
    std::string error;
    const auto lato = text::TrueTypeFont::load(lato_bytes, error);
    ASSERT_NE(lato, nullptr) << error;
    ASSERT_TRUE(lato->hasGsub());
    std::vector<std::uint16_t> glyphs;
    std::vector<std::uint32_t> clusters;
    lato->mapAndSubstitute(U"office", glyphs, clusters);
    EXPECT_EQ(glyphs.size(), 4U) << "o + ffi ligature + c + e";
    EXPECT_EQ(clusters, (std::vector<std::uint32_t>{0, 1, 4, 5}));
    // Layout keeps the ligature's code points in one cluster: no break or caret stop inside it.
    const text::TrueTypeShaper shaper(lato);
    text::ParagraphStyle style;
    style.size_px = 40;
    const auto l = text::layout("office", style, 0, shaper);
    EXPECT_EQ(l.lines[0].glyphs.size(), 4U);

    const auto naskh = text::TrueTypeFont::load(naskh_bytes, error);
    ASSERT_NE(naskh, nullptr) << error;
    std::vector<std::uint16_t> isolated, joined;
    std::vector<std::uint32_t> c1, c2;
    naskh->mapAndSubstitute(U"\u0628", isolated, c1);                 // ب alone
    naskh->mapAndSubstitute(U"\u0628\u062A\u0628", joined, c2);       // ب ت ب
    ASSERT_EQ(joined.size(), 3U);
    EXPECT_NE(joined[0], isolated[0]) << "initial form differs from the isolated one";
    EXPECT_NE(joined[1], naskh->glyphFor(U'\u062A')) << "medial form substituted";
    EXPECT_NE(joined[2], isolated[0]) << "final form";
    std::vector<std::uint16_t> lam_alef;
    std::vector<std::uint32_t> c3;
    naskh->mapAndSubstitute(U"\u0644\u0627", lam_alef, c3);  // ل ا → the mandatory lam-alef ligature
    EXPECT_EQ(lam_alef.size(), 1U);
}

TEST(TrueTypeFont, GposMarkAttachmentSurvivesBidiReordering) {
    const auto dejavu_bytes = readFile("/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf");
    const auto naskh_bytes = readFile("/usr/share/fonts/truetype/noto/NotoNaskhArabic-Bold.ttf");
    if (dejavu_bytes.empty() || naskh_bytes.empty()) GTEST_SKIP() << "DejaVu / Noto Naskh Arabic not installed";
    std::string error;
    const auto dejavu = text::TrueTypeFont::load(dejavu_bytes, error);
    ASSERT_NE(dejavu, nullptr) << error;
    ASSERT_NE(dejavu->marks(), nullptr);
    const text::TrueTypeShaper latin(dejavu);
    text::ParagraphStyle style;
    style.size_px = 100;
    const auto lower = text::layout("e\xCC\x81", style, 0, latin), upper = text::layout("E\xCC\x81", style, 0, latin);
    ASSERT_EQ(lower.lines[0].glyphs.size(), 2U);
    const auto& e = lower.lines[0].glyphs[0];
    const auto& acute = lower.lines[0].glyphs[1];
    EXPECT_FLOAT_EQ(acute.advance, 0) << "marks do not advance";
    EXPECT_GE(acute.x, e.x - 30);
    EXPECT_LE(acute.x, e.x + e.advance);
    EXPECT_LT(upper.lines[0].glyphs[1].y_offset, acute.y_offset - 5) << "the accent rides higher on a capital";

    const auto naskh = text::TrueTypeFont::load(naskh_bytes, error);
    ASSERT_NE(naskh, nullptr) << error;
    const text::TrueTypeShaper arabic(naskh);
    const auto rtl = text::layout("\xD8\xA8\xD9\x90\xD8\xB3", style, 0, arabic);  // بِس : kasra on beh, RTL
    ASSERT_EQ(rtl.lines[0].glyphs.size(), 3U);
    const text::PlacedGlyph* beh = nullptr;
    const text::PlacedGlyph* kasra = nullptr;
    for (const auto& g : rtl.lines[0].glyphs) {
        if (g.cluster == 0) beh = &g;
        if (g.cluster == 1) kasra = &g;
    }
    ASSERT_TRUE(beh && kasra);
    EXPECT_GT(kasra->x, beh->x - 20) << "kasra stays under its beh after right-to-left reordering";
    EXPECT_LT(kasra->x, beh->x + beh->advance + 20);
    EXPECT_GT(kasra->y_offset, 0) << "kasra hangs below the baseline";
}

}  // namespace
