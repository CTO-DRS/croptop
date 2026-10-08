#pragma once

// OpenType mark positioning: GPOS lookup types 4 (mark-to-base) and 6 (mark-to-mark),
// directly or through type 9 (extension), for the lookups of the 'mark' and 'mkmk' features, with anchor
// formats 1–3 (device/contour-point data ignored) and GDEF glyph classes to tell marks from bases.

#include <cstdint>
#include <optional>
#include <span>
#include <vector>

namespace croptop::text {

struct Anchor final {
    std::int16_t x = 0, y = 0;
};

class GposMarks final {
public:
    /** `gpos` and `gdef` (may be empty) are referenced, keep them alive. nullopt without mark lookups. */
    static std::optional<GposMarks> parse(std::span<const std::uint8_t> gpos, std::span<const std::uint8_t> gdef);
    /** GDEF class 3 (mark); without GDEF, any glyph covered as a mark by a lookup. */
    bool isMark(std::uint16_t glyph) const;
    /** (mark anchor, base anchor) from the first mark-to-base subtable covering both glyphs. */
    std::optional<std::pair<Anchor, Anchor>> markToBase(std::uint16_t mark, std::uint16_t base) const;
    /** (mark anchor, previous-mark anchor) from the first mark-to-mark subtable covering both. */
    std::optional<std::pair<Anchor, Anchor>> markToMark(std::uint16_t mark, std::uint16_t previous) const;

private:
    std::span<const std::uint8_t> gpos_, gdef_;
    std::size_t glyph_classes_ = 0;  // GDEF GlyphClassDef (absolute), 0 = none
    std::vector<std::size_t> mark_base_, mark_mark_;  // subtable offsets
    std::optional<std::pair<Anchor, Anchor>> attach(const std::vector<std::size_t>& subtables, std::uint16_t mark, std::uint16_t base) const;
};

}  // namespace croptop::text
