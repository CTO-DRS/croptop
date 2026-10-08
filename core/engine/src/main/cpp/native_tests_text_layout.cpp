// Tests for paragraph layout.
#include "text_layout.h"

#include <gtest/gtest.h>

#include <cmath>
#include <string>
#include <vector>

namespace {

namespace text = croptop::text;
const text::FixedAdvanceShaper kShaper;  // size 20: letters 10 px, spaces 5 px, CJK 20 px

std::u32string lineText(const text::Layout& l, std::size_t i) { return l.text.substr(l.lines[i].first_cluster, l.lines[i].end_cluster - l.lines[i].first_cluster); }

float lineRight(const text::Line& line) {
    // Right edge of the last non-space glyph.
    for (auto it = line.glyphs.rbegin(); it != line.glyphs.rend(); ++it)
        if (it->glyph != U' ') return it->x + it->advance;
    return 0;
}

text::ParagraphStyle style20(text::Align align = text::Align::Left, text::Composer composer = text::Composer::SingleLine) {
    text::ParagraphStyle s;
    s.size_px = 20;
    s.align = align;
    s.composer = composer;
    return s;
}

TEST(TextLayout, DecodesUtf8AndReplacesMalformedBytes) {
    EXPECT_EQ(text::decodeUtf8("A\xC3\xA9\xE6\xBC\xA2\xF0\x9F\x98\x80"), (std::u32string{U'A', 0xE9, 0x6F22, 0x1F600}));
    EXPECT_EQ(text::decodeUtf8("\xC0\xAF"), (std::u32string{0xFFFD, 0xFFFD})) << "overlong";
    EXPECT_EQ(text::decodeUtf8("\xED\xA0\x80"), (std::u32string{0xFFFD, 0xFFFD, 0xFFFD})) << "surrogate";
    EXPECT_EQ(text::decodeUtf8("\xE6\xBC"), (std::u32string{0xFFFD, 0xFFFD})) << "truncated";
}

TEST(TextLayout, PointTextAndAlignment) {
    const auto l = text::layout("Hello", style20(), 0, kShaper);
    ASSERT_EQ(l.lines.size(), 1U);
    EXPECT_FLOAT_EQ(l.lines[0].width, 50);
    EXPECT_FLOAT_EQ(l.lines[0].baseline, 16) << "first baseline at the ascent";
    const auto centred = text::layout("Hello", style20(text::Align::Center), 0, kShaper);
    EXPECT_FLOAT_EQ(centred.lines[0].glyphs.front().x, -25) << "point text centres on its anchor";
    const auto boxed = text::layout("Hello", style20(text::Align::Right), 100, kShaper);
    EXPECT_FLOAT_EQ(lineRight(boxed.lines[0]), 100);
}

TEST(TextLayout, WrapsGreedilyAndOptimally) {
    const auto greedy = text::layout("aaa bbb ccc ddd", style20(), 75, kShaper);
    ASSERT_EQ(greedy.lines.size(), 2U);
    EXPECT_EQ(lineText(greedy, 0), U"aaa bbb ");
    EXPECT_EQ(lineText(greedy, 1), U"ccc ddd");
    EXPECT_FLOAT_EQ(greedy.lines[1].baseline, 16 + 24) << "auto leading is 120 %";

    // The every-line composer never does worse than greedy on total squared slack (all lines but the last).
    const std::string para = "a bb ccc dddd eeeee ffffff g hh iii jjjj kkkkk llllll mmmmmmm n oo ppp qqqq rrrrr ssssss t uu vvv";
    const auto score = [&](const text::Layout& l) {
        double s = 0;
        for (std::size_t i = 0; i + 1 < l.lines.size(); ++i) {
            const double slack = (180.0 - lineRight(l.lines[i])) / 180.0;
            s += slack * slack;
        }
        return s;
    };
    const auto g = text::layout(para, style20(), 180, kShaper);
    const auto o = text::layout(para, style20(text::Align::Left, text::Composer::EveryLine), 180, kShaper);
    EXPECT_LE(score(o), score(g) + 1e-9);
    for (const auto& line : o.lines) EXPECT_LE(lineRight(line), 180.0F + 1e-3F);
}

TEST(TextLayout, JustificationIndentsAndParagraphs) {
    auto s = style20(text::Align::JustifyLastLeft);
    s.left_indent = 10;
    s.right_indent = 5;
    s.first_line_indent = 20;
    const auto l = text::layout("aaa bb cccc d eee ff gggg hh", s, 125, kShaper);
    ASSERT_GE(l.lines.size(), 3U);
    EXPECT_FLOAT_EQ(l.lines[0].glyphs.front().x, 30) << "left + first-line indent";
    EXPECT_FLOAT_EQ(l.lines[1].glyphs.front().x, 10);
    for (std::size_t i = 0; i + 1 < l.lines.size(); ++i) EXPECT_NEAR(lineRight(l.lines[i]), 120.0F, 1e-3F) << "justified to the right indent, line " << i;
    EXPECT_LT(lineRight(l.lines.back()), 120.0F) << "last line stays ragged";

    auto p = style20();
    p.space_before = 6;
    p.space_after = 4;
    const auto paras = text::layout("a\nb\r\nc\xE2\x80\xA8" "d", p, 100, kShaper);
    ASSERT_EQ(paras.lines.size(), 4U);
    EXPECT_TRUE(paras.lines[0].hard_break);
    EXPECT_FLOAT_EQ(paras.lines[1].baseline - paras.lines[0].baseline, 24 + 10) << "leading + space after + before";
    EXPECT_EQ(lineText(paras, 2), U"c");
}

TEST(TextLayout, EmergencyBreaksNbspAndCjk) {
    const auto longword = text::layout("aaaaaaaaaaaaaaaaaaaa", style20(text::Align::Left, text::Composer::EveryLine), 75, kShaper);
    ASSERT_GE(longword.lines.size(), 3U);
    for (const auto& line : longword.lines) EXPECT_LE(lineRight(line), 75.0F);

    const auto nbsp = text::layout("xx aaa\xC2\xA0" "bbb", style20(), 70, kShaper);
    ASSERT_EQ(nbsp.lines.size(), 2U);
    EXPECT_EQ(lineText(nbsp, 1), U"aaa bbb") << "no break at a no-break space";

    const auto cjk = text::layout("\xE6\xBC\xA2\xE5\xAD\x97\xE6\xBC\xA2\xE5\xAD\x97\xE6\xBC\xA2\xE5\xAD\x97", style20(), 50, kShaper);
    ASSERT_EQ(cjk.lines.size(), 3U);
    EXPECT_EQ(cjk.lines[0].end_cluster - cjk.lines[0].first_cluster, 2U);
    // Kinsoku: an ideographic full stop never starts a line.
    const auto kinsoku = text::layout("\xE6\xBC\xA2\xE5\xAD\x97\xE3\x80\x82\xE6\xBC\xA2", style20(), 45, kShaper);
    for (std::size_t i = 1; i < kinsoku.lines.size(); ++i) EXPECT_NE(kinsoku.text[kinsoku.lines[i].first_cluster], U'。');
}

TEST(TextLayout, CaretMappingAndRobustness) {
    const auto l = text::layout("ab cd\nef", style20(), 0, kShaper);
    EXPECT_FLOAT_EQ(l.caretFor(0).x, 0);
    EXPECT_FLOAT_EQ(l.caretFor(2).x, 20);
    EXPECT_FLOAT_EQ(l.caretFor(3).x, 25);
    EXPECT_EQ(l.caretFor(5).line, 0U) << "before the newline";
    EXPECT_EQ(l.caretFor(6).line, 1U);
    EXPECT_FLOAT_EQ(l.caretFor(8).x, 20) << "end of text";
    EXPECT_EQ(l.indexAt(21, 10), 2U);
    EXPECT_EQ(l.indexAt(500, 10), 5U) << "clicks past a line end land before its newline";
    EXPECT_EQ(l.indexAt(3, 45), 6U);

    const auto empty = text::layout("", style20(), 100, kShaper);
    EXPECT_EQ(empty.lines.size(), 1U);
    EXPECT_EQ(empty.caretFor(0).line, 0U);
    auto weird = style20();
    weird.size_px = std::nanf("");
    weird.tracking = std::nanf("");
    weird.first_line_indent = 1e30F;
    EXPECT_FALSE(text::layout("some words here", weird, 50, kShaper).lines.empty());

    std::uint32_t seed = 11;
    for (int round = 0; round < 300; ++round) {
        std::string s;
        for (int k = 0; k < round % 60; ++k) {
            seed = seed * 1664525U + 1013904223U;
            s += static_cast<char>(seed >> 24);
        }
        const auto r = text::layout(s, style20(static_cast<text::Align>(round % 5), static_cast<text::Composer>(round % 2)), static_cast<float>(round % 7) * 13.0F, kShaper);
        (void)r.indexAt(10, 10);
        (void)r.caretFor(static_cast<std::size_t>(round));
    }
}

TEST(TextLayout, CharacterRunsStyleSizeShiftTrackingFontAndLeading) {
    const text::FixedAdvanceShaper wide(1.0F, 0.5F, 1.0F);  // a "wide font": letters = 1 em
    std::vector<text::CharacterRun> runs(4);
    runs[0] = {3, 5, 40, std::nullopt, 0, nullptr, 7};   // "CD" at 40 px
    runs[1] = {6, 7, 0, std::nullopt, 5, nullptr, 8};    // "e" raised 5 px
    runs[2] = {7, 8, 0, 100.0F, 0, nullptr, 9};          // "f" with +100/1000 em tracking
    runs[3] = {8, 99, 0, std::nullopt, 0, &wide, 10};    // "g" in the wide font (end clamped to the text)
    const auto l = text::layout("ab CD efg", style20(), runs, 0, kShaper);
    ASSERT_EQ(l.lines.size(), 1U);
    const auto& g = l.lines[0].glyphs;
    ASSERT_EQ(g.size(), 9U);
    EXPECT_FLOAT_EQ(g[3].advance, 20) << "0.5 em at 40 px";
    EXPECT_FLOAT_EQ(g[3].size, 40);
    EXPECT_EQ(g[3].style, 7U);
    EXPECT_EQ(g[0].style, 0U) << "unstyled text";
    EXPECT_FLOAT_EQ(g[6].y_offset, -5) << "baseline shift raises";
    EXPECT_FLOAT_EQ(g[7].advance, 10 + 2) << "tracking override: 100/1000 em of 20 px";
    EXPECT_FLOAT_EQ(g[8].advance, 20) << "wide font: 1 em";
    EXPECT_FLOAT_EQ(l.lines[0].ascent, 32) << "tallest glyph: 0.8 × 40";
    EXPECT_FLOAT_EQ(l.lines[0].baseline, 32);

    // Auto leading follows the largest size on each line; explicit leading stays fixed.
    std::vector<text::CharacterRun> big{{2, 3, 50, std::nullopt, 0, nullptr, 1}};
    const auto two = text::layout("a\nB", style20(), big, 0, kShaper);
    ASSERT_EQ(two.lines.size(), 2U);
    EXPECT_FLOAT_EQ(two.lines[1].baseline - two.lines[0].baseline, 60) << "1.2 × 50";
    auto fixed = style20();
    fixed.leading_px = 30;
    const auto fixed_two = text::layout("a\nB", fixed, big, 0, kShaper);
    EXPECT_FLOAT_EQ(fixed_two.lines[1].baseline - fixed_two.lines[0].baseline, 30);
    EXPECT_FLOAT_EQ(two.caretFor(2).top, two.lines[1].baseline - 40) << "caret spans the tall line";

    // Overlapping runs: the later one wins.
    std::vector<text::CharacterRun> overlap{{0, 3, 30, std::nullopt, 0, nullptr, 1}, {1, 2, 10, std::nullopt, 0, nullptr, 2}};
    const auto o = text::layout("abc", style20(), overlap, 0, kShaper);
    EXPECT_FLOAT_EQ(o.lines[0].glyphs[1].size, 10);
    EXPECT_EQ(o.lines[0].glyphs[1].style, 2U);
    EXPECT_FLOAT_EQ(o.lines[0].glyphs[2].size, 30);
}

}  // namespace
