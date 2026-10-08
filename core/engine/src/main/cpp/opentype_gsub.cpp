// OpenType GSUB glyph substitution and Arabic joining.
#include "opentype_gsub.h"

#include <algorithm>

#include "opentype_common.h"
#include <set>

namespace croptop::text {
namespace {

using otl::Reader;
using otl::coverageIndex;
using otl::classOf;



enum class Joining : std::uint8_t { NonJoining, Right, Dual, Causing, Transparent };

bool in(char32_t c, char32_t lo, char32_t hi) { return c >= lo && c <= hi; }

Joining joiningType(char32_t c) {
    if (in(c, 0x064B, 0x065F) || c == 0x0670 || in(c, 0x06D6, 0x06DC) || in(c, 0x06DF, 0x06E4) || in(c, 0x06E7, 0x06E8) || in(c, 0x06EA, 0x06ED) ||
        in(c, 0x0610, 0x061A) || c == 0x200C)
        return c == 0x200C ? Joining::NonJoining : Joining::Transparent;  // ZWNJ breaks joining
    if (c == 0x0640 || c == 0x200D) return Joining::Causing;             // tatweel, ZWJ
    if (in(c, 0x0622, 0x0625) || c == 0x0627 || c == 0x0629 || in(c, 0x062F, 0x0632) || c == 0x0648 || in(c, 0x0671, 0x0673) || in(c, 0x0675, 0x0677) ||
        in(c, 0x0688, 0x0699) || c == 0x06C0 || in(c, 0x06C3, 0x06CB) || c == 0x06CD || c == 0x06CF || in(c, 0x06D2, 0x06D3) || c == 0x06D5 ||
        in(c, 0x06EE, 0x06EF))
        return Joining::Right;
    if (c == 0x0626 || c == 0x0628 || in(c, 0x062A, 0x062E) || in(c, 0x0633, 0x063F) || in(c, 0x0641, 0x0647) || in(c, 0x0649, 0x064A) ||
        in(c, 0x066E, 0x066F) || in(c, 0x0678, 0x0687) || in(c, 0x069A, 0x06BF) || in(c, 0x06C1, 0x06C2) || c == 0x06CC || c == 0x06CE ||
        in(c, 0x06D0, 0x06D1) || in(c, 0x06FA, 0x06FC) || c == 0x06FF)
        return Joining::Dual;
    return Joining::NonJoining;
}

}  // namespace

std::optional<GsubTable> GsubTable::parse(std::span<const std::uint8_t> table) {
    Reader r{table};
    if (r.u16(0) != 1) return std::nullopt;
    GsubTable gsub;
    gsub.t_ = table;
    gsub.features_ = r.u16(6);
    gsub.lookups_ = r.u16(8);
    if (!r.ok || gsub.features_ == 0 || gsub.lookups_ == 0 || gsub.features_ >= table.size() || gsub.lookups_ >= table.size()) return std::nullopt;
    return gsub;
}

std::vector<std::uint16_t> GsubTable::lookups(std::uint32_t tag) const {
    Reader r{t_};
    std::set<std::uint16_t> indices;
    const std::uint16_t count = r.u16(features_);
    for (std::uint16_t k = 0; k < count && k < 4096 && r.ok; ++k) {
        const std::size_t rec = features_ + 2 + static_cast<std::size_t>(k) * 6;
        if (r.u32(rec) != tag) continue;
        const std::size_t feature = features_ + r.u16(rec + 4);
        const std::uint16_t n = r.u16(feature + 2);
        for (std::uint16_t i = 0; i < n && i < 4096; ++i) indices.insert(r.u16(feature + 4 + static_cast<std::size_t>(i) * 2));
    }
    if (!r.ok) return {};
    return {indices.begin(), indices.end()};
}

std::vector<std::pair<std::uint16_t, std::size_t>> GsubTable::subtables(std::uint16_t index) const {
    Reader r{t_};
    std::vector<std::pair<std::uint16_t, std::size_t>> out;
    if (index >= r.u16(lookups_)) return out;
    const std::size_t lookup = lookups_ + r.u16(lookups_ + 2 + static_cast<std::size_t>(index) * 2);
    const std::uint16_t type = r.u16(lookup), count = r.u16(lookup + 4);
    for (std::uint16_t s = 0; s < count && s < 1024 && r.ok; ++s) {
        std::size_t sub = lookup + r.u16(lookup + 6 + static_cast<std::size_t>(s) * 2);
        std::uint16_t sub_type = type;
        if (type == 7) {  // Extension: format 1, real type, 32-bit offset from this subtable
            sub_type = r.u16(sub + 2);
            sub += r.u32(sub + 4);
        }
        if (r.ok && sub < t_.size()) out.emplace_back(sub_type, sub);
    }
    return out;
}

std::uint16_t GsubTable::substituteSingle(std::span<const std::uint16_t> lookups, std::uint16_t glyph) const {
    Reader r{t_};
    for (const std::uint16_t index : lookups) {
        for (const auto& [type, sub] : subtables(index)) {
            if (type != 1) continue;
            const std::uint16_t format = r.u16(sub);
            const long k = coverageIndex(r, sub + r.u16(sub + 2), glyph);
            if (!r.ok) return glyph;
            if (k < 0) continue;
            if (format == 1) {
                glyph = static_cast<std::uint16_t>(glyph + static_cast<std::int16_t>(r.u16(sub + 4)));
            } else if (format == 2 && k < r.u16(sub + 4)) {
                glyph = r.u16(sub + 6 + static_cast<std::size_t>(k) * 2);
            }
            break;  // the first applicable subtable of a lookup wins
        }
    }
    return glyph;
}

void GsubTable::substituteLigatures(std::span<const std::uint16_t> lookups, std::vector<std::uint16_t>& glyphs, std::vector<std::uint32_t>& clusters) const {
    Reader r{t_};
    for (const std::uint16_t index : lookups) {
        const auto subs = subtables(index);
        for (std::size_t i = 0; i < glyphs.size(); ++i) {
            bool done = false;
            for (const auto& [type, sub] : subs) {
                if (type != 4 || done) continue;
                const long k = coverageIndex(r, sub + r.u16(sub + 2), glyphs[i]);
                if (!r.ok) return;
                if (k < 0 || k >= r.u16(sub + 4)) continue;
                const std::size_t set = sub + r.u16(sub + 6 + static_cast<std::size_t>(k) * 2);
                const std::uint16_t ligatures = r.u16(set);
                for (std::uint16_t l = 0; l < ligatures && l < 1024 && !done; ++l) {
                    const std::size_t lig = set + r.u16(set + 2 + static_cast<std::size_t>(l) * 2);
                    const std::uint16_t ligature_glyph = r.u16(lig), components = r.u16(lig + 2);
                    if (!r.ok || components == 0 || i + components > glyphs.size()) continue;
                    bool match = true;
                    for (std::uint16_t c = 1; c < components && match; ++c) match = glyphs[i + c] == r.u16(lig + 4 + static_cast<std::size_t>(c - 1) * 2);
                    if (!match || !r.ok) continue;
                    glyphs[i] = ligature_glyph;
                    glyphs.erase(glyphs.begin() + static_cast<std::ptrdiff_t>(i) + 1, glyphs.begin() + static_cast<std::ptrdiff_t>(i) + components);
                    clusters.erase(clusters.begin() + static_cast<std::ptrdiff_t>(i) + 1, clusters.begin() + static_cast<std::ptrdiff_t>(i) + components);
                    done = true;
                }
            }
        }
    }
}

std::vector<ArabicForm> arabicForms(std::u32string_view run) {
    std::vector<ArabicForm> forms(run.size(), ArabicForm::None);
    std::vector<Joining> types(run.size());
    for (std::size_t i = 0; i < run.size(); ++i) types[i] = joiningType(run[i]);
    for (std::size_t i = 0; i < run.size(); ++i) {
        const Joining t = types[i];
        if (t != Joining::Dual && t != Joining::Right) continue;
        std::size_t p = i, n = i;
        bool has_prev = false, has_next = false;
        while (p > 0) {
            --p;
            if (types[p] != Joining::Transparent) {
                has_prev = true;
                break;
            }
        }
        while (n + 1 < run.size()) {
            ++n;
            if (types[n] != Joining::Transparent) {
                has_next = true;
                break;
            }
        }
        const bool joins_prev = has_prev && (types[p] == Joining::Dual || types[p] == Joining::Causing);
        const bool joins_next = t == Joining::Dual && has_next && (types[n] == Joining::Dual || types[n] == Joining::Right || types[n] == Joining::Causing);
        forms[i] = joins_prev && joins_next ? ArabicForm::Medial : joins_prev ? ArabicForm::Final : joins_next ? ArabicForm::Initial : ArabicForm::Isolated;
    }
    return forms;
}

}  // namespace croptop::text
