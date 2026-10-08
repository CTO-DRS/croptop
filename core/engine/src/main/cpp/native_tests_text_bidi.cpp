// Tests for the compact bidirectional algorithm and its use in layout.
#include "text_bidi.h"
#include "text_layout.h"

#include <gtest/gtest.h>

#include <string>

namespace {

namespace text = croptop::text;
using BC = text::BidiClass;

std::u32string visual(const std::u32string& s, text::Direction d = text::Direction::Auto) {
    const auto bidi = text::resolveBidi(s, d);
    std::u32string out;
    for (const auto i : text::visualOrder(s, bidi, 0, static_cast<std::uint32_t>(s.size()))) out += s[i];
    return out;
}

TEST(TextBidi, ClassesAndLevels) {
    EXPECT_EQ(text::bidiClass(U'a'), BC::L);
    EXPECT_EQ(text::bidiClass(U'א'), BC::R);
    EXPECT_EQ(text::bidiClass(U'ب'), BC::AL);
    EXPECT_EQ(text::bidiClass(U'1'), BC::EN);
    EXPECT_EQ(text::bidiClass(U'٣'), BC::AN);
    EXPECT_EQ(text::bidiClass(U' '), BC::WS);
    EXPECT_EQ(text::bidiClass(U','), BC::CS);
    EXPECT_EQ(text::bidiClass(U'('), BC::ON);
    EXPECT_EQ(text::bidiClass(U'́'), BC::NSM);

    EXPECT_EQ(text::resolveBidi(U"abc", text::Direction::Auto).base_level, 0);
    const auto hebrew = text::resolveBidi(U"אב ab", text::Direction::Auto);
    EXPECT_EQ(hebrew.base_level, 1) << "first strong character is RTL";
    EXPECT_EQ(hebrew.levels, (std::vector<std::uint8_t>{1, 1, 1, 2, 2}));
    EXPECT_EQ(text::resolveBidi(U"123", text::Direction::RightToLeft).levels, (std::vector<std::uint8_t>{2, 2, 2}));
}

TEST(TextBidi, VisualOrder) {
    // LTR paragraph with an embedded Hebrew word.
    EXPECT_EQ(visual(U"ab אבג cd"), U"ab גבא cd");
    // RTL paragraph: numbers stay left-to-right inside it.
    EXPECT_EQ(visual(U"אב 12 גד"), U"דג 12 בא");
    // Arabic with Arabic-Indic digits (AN) and a comma between them (CS → AN).
    EXPECT_EQ(visual(U"بت ١,٢"), U"١,٢ تب");
    // A price in Hebrew keeps "$12.50" together and in order.
    EXPECT_EQ(visual(U"א $12.50"), U"$12.50 א");
    // Neutrals between opposite directions take the paragraph direction.
    EXPECT_EQ(visual(U"a א", text::Direction::RightToLeft), U"א a");
    // L1: trailing whitespace returns to the paragraph level (stays at the visual end in LTR).
    EXPECT_EQ(visual(U"אב  ", text::Direction::LeftToRight), U"בא  ");
    EXPECT_EQ(text::mirror(U'('), U')');
    EXPECT_EQ(text::mirror(U'a'), U'a');
}

TEST(TextBidi, LayoutPlacesMixedTextVisually) {
    const text::FixedAdvanceShaper shaper;
    text::ParagraphStyle style;
    style.size_px = 20;
    const auto l = text::layout("abc \xD7\x90\xD7\x91\xD7\x92", style, 0, shaper);  // "abc אבג"
    ASSERT_EQ(l.lines.size(), 1U);
    std::u32string glyphs;
    for (const auto& g : l.lines[0].glyphs) glyphs += static_cast<char32_t>(g.glyph);
    EXPECT_EQ(glyphs, U"abc גבא");
    for (std::size_t i = 1; i < l.lines[0].glyphs.size(); ++i) EXPECT_GT(l.lines[0].glyphs[i].x, l.lines[0].glyphs[i - 1].x);
    // Caret before א (index 4, RTL) sits on its right edge, which is the line's right end.
    EXPECT_FLOAT_EQ(l.caretFor(4).x, 35 + 30);
    EXPECT_EQ(l.levels[4], 1);
    EXPECT_EQ(l.indexAt(64, 10), 4U) << "clicking the right edge of the Hebrew word lands before its first letter";

    // Explicit RTL direction with only LTR text still lays out (levels 2).
    style.direction = text::Direction::RightToLeft;
    const auto rtl = text::layout("ab", style, 0, shaper);
    EXPECT_EQ(rtl.levels[0], 2);
    EXPECT_EQ(rtl.lines[0].glyphs.front().glyph, static_cast<std::uint32_t>(U'a'));
}

}  // namespace
