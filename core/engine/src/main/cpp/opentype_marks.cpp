// OpenType mark positioning.
#include "opentype_marks.h"

#include <set>

#include "opentype_common.h"

namespace croptop::text {
namespace {

using otl::classOf;
using otl::coverageIndex;
using otl::Reader;

std::set<std::uint16_t> featureLookups(Reader& r, std::size_t features, std::uint32_t tag) {
    std::set<std::uint16_t> out;
    const std::uint16_t count = r.u16(features);
    for (std::uint16_t k = 0; k < count && k < 4096 && r.ok; ++k) {
        const std::size_t rec = features + 2 + static_cast<std::size_t>(k) * 6;
        if (r.u32(rec) != tag) continue;
        const std::size_t feature = features + r.u16(rec + 4);
        const std::uint16_t n = r.u16(feature + 2);
        for (std::uint16_t i = 0; i < n && i < 4096; ++i) out.insert(r.u16(feature + 4 + static_cast<std::size_t>(i) * 2));
    }
    return out;
}

Anchor anchorAt(Reader& r, std::size_t at) { return {r.i16(at + 2), r.i16(at + 4)}; }

}  // namespace

std::optional<GposMarks> GposMarks::parse(std::span<const std::uint8_t> gpos, std::span<const std::uint8_t> gdef) {
    Reader r{gpos};
    if (r.u16(0) != 1) return std::nullopt;
    const std::size_t features = r.u16(6), lookups = r.u16(8);
    if (!r.ok || features == 0 || lookups == 0) return std::nullopt;
    GposMarks marks;
    marks.gpos_ = gpos;
    marks.gdef_ = gdef;
    if (!gdef.empty()) {
        Reader g{gdef};
        const std::uint16_t class_def = g.u16(4);
        if (g.ok && class_def != 0) marks.glyph_classes_ = class_def;
    }
    const std::uint16_t lookup_count = r.u16(lookups);
    for (const auto& [tag, list] : {std::pair{0x6D61726BU, &marks.mark_base_}, std::pair{0x6D6B6D6BU, &marks.mark_mark_}}) {  // mark, mkmk
        for (const std::uint16_t index : featureLookups(r, features, tag)) {
            if (index >= lookup_count) continue;
            const std::size_t lookup = lookups + r.u16(lookups + 2 + static_cast<std::size_t>(index) * 2);
            const std::uint16_t type = r.u16(lookup), subtables = r.u16(lookup + 4);
            for (std::uint16_t s = 0; s < subtables && s < 1024 && r.ok; ++s) {
                std::size_t sub = lookup + r.u16(lookup + 6 + static_cast<std::size_t>(s) * 2);
                std::uint16_t sub_type = type;
                if (type == 9) {
                    sub_type = r.u16(sub + 2);
                    sub += r.u32(sub + 4);
                }
                if (!r.ok || sub >= gpos.size()) continue;
                if (sub_type == 4) marks.mark_base_.push_back(sub);
                else if (sub_type == 6) marks.mark_mark_.push_back(sub);
            }
        }
        (void)list;
    }
    if (!r.ok || (marks.mark_base_.empty() && marks.mark_mark_.empty())) return std::nullopt;
    return marks;
}

bool GposMarks::isMark(std::uint16_t glyph) const {
    if (glyph_classes_ != 0) {
        Reader g{gdef_};
        return classOf(g, glyph_classes_, glyph) == 3;
    }
    Reader r{gpos_};
    for (const std::size_t sub : mark_base_)
        if (coverageIndex(r, sub + r.u16(sub + 2), glyph) >= 0) return true;
    return false;
}

std::optional<std::pair<Anchor, Anchor>> GposMarks::attach(const std::vector<std::size_t>& subtables, std::uint16_t mark, std::uint16_t base) const {
    Reader r{gpos_};
    for (const std::size_t sub : subtables) {
        if (r.u16(sub) != 1) continue;
        const long mark_index = coverageIndex(r, sub + r.u16(sub + 2), mark);
        const long base_index = coverageIndex(r, sub + r.u16(sub + 4), base);
        if (!r.ok) return std::nullopt;
        if (mark_index < 0 || base_index < 0) continue;
        const std::uint16_t classes = r.u16(sub + 6);
        const std::size_t mark_array = sub + r.u16(sub + 8), base_array = sub + r.u16(sub + 10);
        if (mark_index >= r.u16(mark_array) || base_index >= r.u16(base_array)) continue;
        const std::size_t mark_record = mark_array + 2 + static_cast<std::size_t>(mark_index) * 4;
        const std::uint16_t mark_class = r.u16(mark_record);
        const std::uint16_t mark_anchor = r.u16(mark_record + 2);
        if (mark_class >= classes || mark_anchor == 0) continue;
        const std::uint16_t base_anchor = r.u16(base_array + 2 + (static_cast<std::size_t>(base_index) * classes + mark_class) * 2);
        if (!r.ok || base_anchor == 0) continue;
        const Anchor m = anchorAt(r, mark_array + mark_anchor), b = anchorAt(r, base_array + base_anchor);
        if (!r.ok) return std::nullopt;
        return std::pair{m, b};
    }
    return std::nullopt;
}

std::optional<std::pair<Anchor, Anchor>> GposMarks::markToBase(std::uint16_t mark, std::uint16_t base) const { return attach(mark_base_, mark, base); }
std::optional<std::pair<Anchor, Anchor>> GposMarks::markToMark(std::uint16_t mark, std::uint16_t previous) const { return attach(mark_mark_, mark, previous); }

}  // namespace croptop::text
