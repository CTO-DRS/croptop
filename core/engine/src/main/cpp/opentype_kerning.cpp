// OpenType GPOS pair kerning.
#include "opentype_kerning.h"

#include <algorithm>

#include "opentype_common.h"
#include <bit>
#include <set>

namespace croptop::text {
namespace {

using otl::Reader;
using otl::coverageIndex;
using otl::classOf;


/** Coverage index of `glyph`, or −1. */


std::size_t valueSize(std::uint16_t format) { return static_cast<std::size_t>(std::popcount(static_cast<unsigned>(format & 0xFFU))) * 2; }

/** XAdvance from a ValueRecord at `at`, 0 when the format has none. */
std::int16_t xAdvance(Reader& r, std::size_t at, std::uint16_t format) {
    if ((format & 0x0004) == 0) return 0;
    const std::size_t offset = static_cast<std::size_t>(std::popcount(static_cast<unsigned>(format & 0x0003U))) * 2;
    return static_cast<std::int16_t>(r.u16(at + offset));
}

}  // namespace

std::optional<GposKerning> GposKerning::parse(std::span<const std::uint8_t> table) {
    Reader r{table};
    if (r.u16(0) != 1) return std::nullopt;
    const std::size_t features = r.u16(6), lookups = r.u16(8);
    if (!r.ok || features == 0 || lookups == 0) return std::nullopt;
    std::set<std::uint16_t> indices;
    const std::uint16_t feature_count = r.u16(features);
    for (std::uint16_t k = 0; k < feature_count && k < 4096; ++k) {
        const std::size_t rec = features + 2 + static_cast<std::size_t>(k) * 6;
        if (r.u32(rec) != 0x6B65726EU) continue;  // 'kern'
        const std::size_t feature = features + r.u16(rec + 4);
        const std::uint16_t count = r.u16(feature + 2);
        for (std::uint16_t i = 0; i < count && i < 4096; ++i) indices.insert(r.u16(feature + 4 + static_cast<std::size_t>(i) * 2));
    }
    GposKerning kerning;
    kerning.t_ = table;
    const std::uint16_t lookup_count = r.u16(lookups);
    for (const std::uint16_t index : indices) {
        if (index >= lookup_count || !r.ok) continue;
        const std::size_t lookup = lookups + r.u16(lookups + 2 + static_cast<std::size_t>(index) * 2);
        const std::uint16_t type = r.u16(lookup), subtables = r.u16(lookup + 4);
        for (std::uint16_t s = 0; s < subtables && s < 1024; ++s) {
            std::size_t sub = lookup + r.u16(lookup + 6 + static_cast<std::size_t>(s) * 2);
            std::uint16_t sub_type = type;
            if (type == 9) {  // Extension: format 1, real type, 32-bit offset from this subtable
                sub_type = r.u16(sub + 2);
                sub += r.u32(sub + 4);
            }
            if (sub_type == 2 && r.ok && sub < table.size()) kerning.subtables_.push_back(sub);
        }
    }
    if (!r.ok || kerning.subtables_.empty()) return std::nullopt;
    return kerning;
}

std::int16_t GposKerning::adjust(std::uint16_t left, std::uint16_t right) const {
    Reader r{t_};
    for (const std::size_t sub : subtables_) {
        const std::uint16_t format = r.u16(sub);
        const std::size_t coverage = sub + r.u16(sub + 2);
        const std::uint16_t vf1 = r.u16(sub + 4), vf2 = r.u16(sub + 6);
        const long index = coverageIndex(r, coverage, left);
        if (!r.ok) return 0;
        if (index < 0) continue;
        const std::size_t record = 2 + valueSize(vf1) + valueSize(vf2);
        if (format == 1) {
            const std::uint16_t sets = r.u16(sub + 8);
            if (index >= sets) continue;
            const std::size_t set = sub + r.u16(sub + 10 + static_cast<std::size_t>(index) * 2);
            const std::uint16_t count = r.u16(set);
            std::size_t lo = 0, hi = count;
            while (lo < hi) {
                const std::size_t mid = lo + (hi - lo) / 2;
                const std::size_t at = set + 2 + mid * record;
                const std::uint16_t second = r.u16(at);
                if (!r.ok) return 0;
                if (second < right) lo = mid + 1;
                else if (second > right) hi = mid;
                else return xAdvance(r, at + 2, vf1);
            }
            continue;  // left covered but no pair: the next subtable may apply
        }
        if (format == 2) {
            const std::size_t class_def1 = sub + r.u16(sub + 8), class_def2 = sub + r.u16(sub + 10);
            const std::uint16_t class1_count = r.u16(sub + 12), class2_count = r.u16(sub + 14);
            const std::uint16_t c1 = classOf(r, class_def1, left), c2 = classOf(r, class_def2, right);
            if (!r.ok || c1 >= class1_count || c2 >= class2_count) return 0;
            const std::size_t pair = record - 2;
            const std::size_t at = sub + 16 + (static_cast<std::size_t>(c1) * class2_count + c2) * pair;
            return xAdvance(r, at, vf1);
        }
    }
    return 0;
}

}  // namespace croptop::text
