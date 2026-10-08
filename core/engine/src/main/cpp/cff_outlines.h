#pragma once

// CFF (Compact Font Format) outlines for OpenType fonts with PostScript glyphs:
// INDEX/DICT parsing, name-keyed and CID-keyed fonts (FDArray + FDSelect formats 0/3), and a Type 2
// charstring interpreter (all path operators, flex, hint counting/masks, local/global subroutines).
// Everything is bounds-checked; subroutine nesting is capped at 10 and each glyph at 20 000 operators, so
// hostile fonts cannot loop. Hinting is ignored; `seac`-style accented endchar is not composed.

#include <cstdint>
#include <optional>
#include <span>
#include <string>
#include <vector>

#include "vector_path.h"

namespace croptop::text {

class CffOutlines final {
public:
    /** Parses a 'CFF ' table (bytes are referenced, not copied: keep them alive). */
    static std::optional<CffOutlines> parse(std::span<const std::uint8_t> table, std::string& error);

    std::size_t glyphCount() const { return charstrings_.count; }
    /** Appends glyph `gid`'s outline (font units, y up) to `path`; false if the charstring is invalid. */
    bool outline(std::uint16_t gid, vector::Path& path) const;

    struct Index {
        std::size_t count = 0, offsets = 0, data = 0;  // absolute positions in the table
        std::uint8_t off_size = 0;
    };

private:
    struct Private {
        Index subrs;
        bool has_subrs = false;
    };
    std::span<const std::uint8_t> t_;
    Index charstrings_, global_subrs_;
    std::vector<Private> privates_;  // one (name-keyed) or one per FD (CID-keyed)
    std::size_t fd_select_ = 0;
    bool cid_ = false;

    std::size_t fdFor(std::uint16_t gid) const;
    friend struct CffInterpreter;
};

}  // namespace croptop::text
