#pragma once

// OpenType GPOS pair kerning: the lookups referenced by every 'kern' feature in the
// FeatureList (all scripts/languages), lookup type 2 (PairPos) directly or through type 9 (Extension),
// subtable formats 1 (glyph pairs) and 2 (class pairs), Coverage formats 1/2 and ClassDef formats 1/2. Only
// the first glyph's XAdvance is applied (horizontal kerning). All reads are bounds-checked.

#include <cstdint>
#include <optional>
#include <span>
#include <vector>

namespace croptop::text {

class GposKerning final {
public:
    /** `table` is the GPOS table (referenced, keep alive). nullopt when it holds no usable kern lookups. */
    static std::optional<GposKerning> parse(std::span<const std::uint8_t> table);
    /** XAdvance adjustment (font units) for the pair, 0 when no subtable covers it. */
    std::int16_t adjust(std::uint16_t left, std::uint16_t right) const;
    std::size_t subtableCount() const { return subtables_.size(); }

private:
    std::span<const std::uint8_t> t_;
    std::vector<std::size_t> subtables_;  // absolute offsets of PairPos subtables, in lookup order
};

}  // namespace croptop::text
