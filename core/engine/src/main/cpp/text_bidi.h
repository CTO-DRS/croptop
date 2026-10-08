#pragma once

// Bidirectional text for the text engine: a compact Unicode Bidirectional
// Algorithm (UAX #9) covering paragraph direction (P2–P3), weak types (W1–W7), neutrals (N1–N2), implicit
// levels (I1–I2) and line reordering (L1–L2). Explicit embeddings/overrides/isolates (X1–X10) are not
// interpreted (their controls are treated as boundary neutrals). Character classes cover Latin, Greek,
// Cyrillic, Hebrew, Arabic (incl. presentation forms), Syriac, Thaana, NKo, digits, separators, spaces and
// common combining marks; unknown code points are strong left-to-right.

#include <cstdint>
#include <string_view>
#include <vector>

namespace croptop::text {

enum class Direction : std::uint8_t { Auto, LeftToRight, RightToLeft };

enum class BidiClass : std::uint8_t { L, R, AL, EN, ES, ET, AN, CS, NSM, BN, B, S, WS, ON };

BidiClass bidiClass(char32_t c);

/** Embedding level per code point (even = LTR, odd = RTL) for one paragraph. */
struct BidiParagraph final {
    std::uint8_t base_level = 0;
    std::vector<std::uint8_t> levels;
};

BidiParagraph resolveBidi(std::u32string_view paragraph, Direction direction);

/**
 * Visual order of the code points [begin, end) of a resolved paragraph laid out on one line: indices in
 * left-to-right display order (L1 resets trailing whitespace to the base level, L2 reverses runs).
 */
std::vector<std::uint32_t> visualOrder(std::u32string_view paragraph, const BidiParagraph& bidi, std::uint32_t begin, std::uint32_t end);

/** Mirrored glyph for brackets in right-to-left runs (L4, common pairs), or the code point itself. */
char32_t mirror(char32_t c);

}  // namespace croptop::text
