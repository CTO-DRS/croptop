#pragma once

// OpenType GSUB glyph substitution: the lookups of a feature (all scripts/languages),
// lookup types 1 (single, formats 1/2) and 4 (ligature), directly or through type 7 (extension); plus the
// Unicode Arabic joining analysis that picks isol/init/medi/fina forms. Lookup flags (mark skipping) and
// contextual lookups (types 5/6/8) are not applied. All reads are bounds-checked.

#include <cstdint>
#include <optional>
#include <span>
#include <string_view>
#include <vector>

namespace croptop::text {

class GsubTable final {
public:
    /** `table` is the GSUB table (referenced, keep alive). */
    static std::optional<GsubTable> parse(std::span<const std::uint8_t> table);
    /** Lookup indices (ascending) of every feature with this tag, e.g. 'liga' = 0x6C696761. */
    std::vector<std::uint16_t> lookups(std::uint32_t feature_tag) const;
    /** Applies the single-substitution lookups among `lookups` in order. */
    std::uint16_t substituteSingle(std::span<const std::uint16_t> lookups, std::uint16_t glyph) const;
    /**
     * Applies the ligature lookups among `lookups` in order over the sequence; `clusters` stays parallel to
     * `glyphs` (a ligature keeps its first component's cluster).
     */
    void substituteLigatures(std::span<const std::uint16_t> lookups, std::vector<std::uint16_t>& glyphs, std::vector<std::uint32_t>& clusters) const;

private:
    std::span<const std::uint8_t> t_;
    std::size_t features_ = 0, lookups_ = 0;
    /** Subtables of lookup `index` with their resolved type (extensions unwrapped). */
    std::vector<std::pair<std::uint16_t, std::size_t>> subtables(std::uint16_t index) const;
};

enum class ArabicForm : std::uint8_t { None, Isolated, Initial, Medial, Final };

/** Contextual forms for a run of code points (logical order); None for characters that do not join. */
std::vector<ArabicForm> arabicForms(std::u32string_view run);

}  // namespace croptop::text
