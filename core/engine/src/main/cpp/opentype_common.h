#pragma once

// Shared bounds-checked readers for OpenType layout tables (GSUB/GPOS/GDEF).

#include <cstdint>
#include <span>

namespace croptop::text::otl {

/** Big-endian reads; out-of-range access clears `ok` and yields 0. */
struct Reader {
    std::span<const std::uint8_t> d;
    bool ok = true;
    std::uint16_t u16(std::size_t at) {
        if (at + 2 > d.size() || at + 2 < at) return ok = false, 0;
        return static_cast<std::uint16_t>(d[at] << 8 | d[at + 1]);
    }
    std::int16_t i16(std::size_t at) { return static_cast<std::int16_t>(u16(at)); }
    std::uint32_t u32(std::size_t at) { return static_cast<std::uint32_t>(u16(at)) << 16 | u16(at + 2); }
};

/** Coverage index of `glyph` (formats 1/2), or −1. */
inline long coverageIndex(Reader& r, std::size_t at, std::uint16_t glyph) {
    const std::uint16_t format = r.u16(at), count = r.u16(at + 2);
    std::size_t lo = 0, hi = count;
    if (format == 1) {
        while (lo < hi) {
            const std::size_t mid = lo + (hi - lo) / 2;
            const std::uint16_t g = r.u16(at + 4 + mid * 2);
            if (!r.ok) return -1;
            if (g < glyph) lo = mid + 1;
            else if (g > glyph) hi = mid;
            else return static_cast<long>(mid);
        }
    } else if (format == 2) {
        while (lo < hi) {
            const std::size_t mid = lo + (hi - lo) / 2;
            const std::size_t rec = at + 4 + mid * 6;
            const std::uint16_t start = r.u16(rec), end = r.u16(rec + 2);
            if (!r.ok) return -1;
            if (glyph < start) hi = mid;
            else if (glyph > end) lo = mid + 1;
            else return static_cast<long>(r.u16(rec + 4)) + (glyph - start);
        }
    }
    return -1;
}

/** Class of `glyph` in a ClassDef (formats 1/2); 0 when absent. */
inline std::uint16_t classOf(Reader& r, std::size_t at, std::uint16_t glyph) {
    const std::uint16_t format = r.u16(at);
    if (format == 1) {
        const std::uint16_t start = r.u16(at + 2), count = r.u16(at + 4);
        if (glyph < start || glyph >= start + count) return 0;
        return r.u16(at + 6 + static_cast<std::size_t>(glyph - start) * 2);
    }
    if (format == 2) {
        const std::uint16_t ranges = r.u16(at + 2);
        std::size_t lo = 0, hi = ranges;
        while (lo < hi) {
            const std::size_t mid = lo + (hi - lo) / 2;
            const std::size_t rec = at + 4 + mid * 6;
            const std::uint16_t start = r.u16(rec), end = r.u16(rec + 2);
            if (!r.ok) return 0;
            if (glyph < start) hi = mid;
            else if (glyph > end) lo = mid + 1;
            else return r.u16(rec + 4);
        }
    }
    return 0;
}

}  // namespace croptop::text::otl
