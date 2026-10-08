#pragma once

// Paragraph layout for the text engine: line breaking, composition, alignment,
// justification, indents and caret mapping. Shaping is abstract (`Shaper`), so a HarfBuzz/FreeType shaper
// can be plugged in without touching layout; `FixedAdvanceShaper` serves tests and fallbacks.
//
// Line-break opportunities follow a simplified UAX #14: after spaces (U+0020, U+3000, tabs), after
// hyphens (U+002D, U+2010) and en dashes, between CJK ideographs/kana/hangul, never at NBSP (U+00A0,
// U+202F) and always at newlines (LF, CR, CRLF, U+2028, U+2029). Mixed-direction lines are reordered with
// the compact UBA in text_bidi.h (breaking stays logical, as UAX #9 requires).

#include <cstdint>
#include <memory>
#include <optional>
#include <span>
#include <string>
#include <string_view>
#include <vector>

#include "text_bidi.h"

namespace croptop::text {

/** One shaped glyph; `cluster` is the index of its first code point in the paragraph's code points. */
struct ShapedGlyph final {
    std::uint32_t glyph = 0;
    std::uint32_t cluster = 0;
    float advance = 0;   // px, already including kerning from the shaper
    float x_offset = 0;  // px, positioning without advancing (relative to the attach target when attach_back > 0)
    float y_offset = 0;  // px, positive = up (font convention)
    /** > 0: a mark attached to the glyph this many positions earlier (logical order); offsets are relative to
     *  that glyph's final position, so attachment survives right-to-left reordering. */
    std::uint16_t attach_back = 0;
};

class Shaper {
public:
    virtual ~Shaper() = default;
    /** Shapes a run of code points at `size_px` (one glyph per code point is fine for simple shapers). */
    virtual std::vector<ShapedGlyph> shape(std::u32string_view run, float size_px) const = 0;
    virtual float ascent(float size_px) const = 0;   // px above the baseline
    virtual float descent(float size_px) const = 0;  // px below the baseline (positive)
};

/** Every code point advances `em_fraction × size`; spaces use `space_fraction`, CJK `cjk_fraction`. */
class FixedAdvanceShaper final : public Shaper {
public:
    explicit FixedAdvanceShaper(float em_fraction = 0.5F, float space_fraction = 0.25F, float cjk_fraction = 1.0F)
        : em_(em_fraction), space_(space_fraction), cjk_(cjk_fraction) {}
    std::vector<ShapedGlyph> shape(std::u32string_view run, float size_px) const override;
    float ascent(float size_px) const override { return size_px * 0.8F; }
    float descent(float size_px) const override { return size_px * 0.2F; }

private:
    float em_, space_, cjk_;
};

enum class Align : std::uint8_t { Left, Center, Right, JustifyLastLeft, JustifyAll };
enum class Composer : std::uint8_t { SingleLine, EveryLine };

struct ParagraphStyle final {
    float size_px = 16;
    float leading_px = 0;        // baseline-to-baseline; 0 = auto (120 % of size)
    float tracking = 0;          // 1/1000 em added after every glyph
    Align align = Align::Left;
    Composer composer = Composer::EveryLine;
    float first_line_indent = 0, left_indent = 0, right_indent = 0;  // px
    float space_before = 0, space_after = 0;                          // px (between paragraphs)
    float max_word_spacing = 2.0F;  // justified spaces may grow to this multiple before letter spacing kicks in
    Direction direction = Direction::Auto;  // paragraph direction (Auto = first strong character)
};

/**
 * Character-level styling for a range of code points [begin, end) (Character panel). Later runs override
 * earlier ones where they overlap; code points without a run use the paragraph's size, tracking and shaper.
 */
struct CharacterRun final {
    std::uint32_t begin = 0, end = 0;
    float size_px = 0;             // 0 = paragraph size
    std::optional<float> tracking;  // 1/1000 em; absent = paragraph tracking
    float baseline_shift = 0;      // px, positive raises the glyphs
    const Shaper* shaper = nullptr;  // font; nullptr = the layout's shaper
    std::uint32_t style = 0;       // caller's style id, copied to the placed glyphs
};

struct PlacedGlyph final {
    std::uint32_t glyph = 0, cluster = 0;
    float x = 0, advance = 0;  // relative to the text box's left edge (x includes the shaper's x offset)
    float size = 0;            // px, the glyph's font size
    float y_offset = 0;        // px below the baseline (negative = raised by a baseline shift or mark attachment)
    std::uint32_t style = 0;   // from the CharacterRun (0 without runs)
};

struct Line final {
    std::vector<PlacedGlyph> glyphs;
    std::uint32_t first_cluster = 0, end_cluster = 0;  // code point range [first, end)
    float baseline = 0;                                 // y from the text box top
    float width = 0;                                    // ink advance of the line (after justification)
    bool hard_break = false;                            // ended by a newline
    float ascent = 0, descent = 0;                      // of the tallest glyphs on the line (with baseline shifts)
};

struct Layout final {
    std::u32string text;  // decoded code points (invalid UTF-8 → U+FFFD)
    std::vector<std::uint8_t> levels;  // bidi embedding level per code point (odd = right-to-left)
    std::vector<Line> lines;
    float height = 0;  // total height including the last line's descent

    /** Caret x/line for a code-point index (0…text.size()). */
    struct Caret final {
        std::size_t line = 0;
        float x = 0, top = 0, bottom = 0;
    };
    Caret caretFor(std::size_t index) const;
    /** Nearest code-point index to a point in the text box (click-to-place-caret). */
    std::size_t indexAt(float x, float y) const;

    float ascent = 0, descent = 0, leading = 0;
};

/** Decodes UTF-8, replacing malformed sequences with U+FFFD. */
std::u32string decodeUtf8(std::string_view utf8);

/**
 * Lays out UTF-8 `text` in a box `width` px wide (≤ 0 = point text: no wrapping). Each newline starts a new
 * paragraph (space_before/after apply between them).
 */
Layout layout(std::string_view text, const ParagraphStyle& style, float width, const Shaper& shaper);

/**
 * As above with character runs. Auto leading follows the largest size on each line (120 %), and each line's
 * ascent/descent come from the glyphs actually on it.
 */
Layout layout(std::string_view text, const ParagraphStyle& style, std::span<const CharacterRun> runs, float width, const Shaper& shaper);

}  // namespace croptop::text
