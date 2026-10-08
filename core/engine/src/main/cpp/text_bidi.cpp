// Compact Unicode Bidirectional Algorithm for the text engine.
#include "text_bidi.h"

#include <algorithm>

namespace croptop::text {
namespace {

bool in(char32_t c, char32_t lo, char32_t hi) { return c >= lo && c <= hi; }

bool isNeutralOrIsolate(BidiClass t) { return t == BidiClass::B || t == BidiClass::S || t == BidiClass::WS || t == BidiClass::ON; }

}  // namespace

BidiClass bidiClass(char32_t c) {
    using B = BidiClass;
    // Paragraph and segment separators, whitespace, boundary neutrals.
    if (c == 0x0A || c == 0x0D || in(c, 0x1C, 0x1E) || c == 0x85 || c == 0x2029) return B::B;
    if (c == 0x09 || c == 0x0B || c == 0x1F) return B::S;
    if (c == 0x0C || c == 0x20 || c == 0x1680 || in(c, 0x2000, 0x200A) || c == 0x2028 || c == 0x205F || c == 0x3000) return B::WS;
    if (in(c, 0x00, 0x08) || in(c, 0x0E, 0x1B) || in(c, 0x7F, 0x84) || in(c, 0x86, 0x9F) || c == 0xAD || in(c, 0x200B, 0x200D) ||
        in(c, 0x2060, 0x2064) || c == 0xFEFF || in(c, 0x202A, 0x202E) || in(c, 0x2066, 0x2069) || c == 0x061C)
        return B::BN;  // explicit formatting controls are not interpreted (see header)
    if (c == 0x200E) return B::L;
    if (c == 0x200F) return B::R;
    // Numbers and number-related separators/terminators.
    if (in(c, 0x30, 0x39) || c == 0xB2 || c == 0xB3 || c == 0xB9 || in(c, 0x06F0, 0x06F9) || in(c, 0x2070, 0x2079) || in(c, 0x2080, 0x2089) ||
        in(c, 0xFF10, 0xFF19))
        return B::EN;
    if (in(c, 0x0660, 0x0669) || c == 0x066B || c == 0x066C || in(c, 0x0600, 0x0605) || c == 0x06DD) return B::AN;
    if (c == U'+' || c == U'-' || c == 0x207A || c == 0x207B || c == 0x208A || c == 0x208B || c == 0x2212 || c == 0xFB29 || c == 0xFE62 ||
        c == 0xFE63 || c == 0xFF0B || c == 0xFF0D)
        return B::ES;
    if (c == U'#' || c == U'$' || c == U'%' || in(c, 0xA2, 0xA5) || c == 0xB0 || c == 0xB1 || c == 0x066A || in(c, 0x2030, 0x2034) ||
        in(c, 0x20A0, 0x20CF) || c == 0x212E || c == 0x2213 || c == 0xFE5F || c == 0xFE69 || c == 0xFE6A || in(c, 0xFF03, 0xFF05) || c == 0xFFE0 ||
        c == 0xFFE1 || c == 0xFFE5 || c == 0xFFE6)
        return B::ET;
    if (c == U',' || c == U'.' || c == U'/' || c == U':' || c == 0xA0 || c == 0x060C || c == 0x202F || c == 0x2044 || c == 0xFE50 || c == 0xFE52 ||
        c == 0xFE55 || c == 0xFF0C || in(c, 0xFF0E, 0xFF0F) || c == 0xFF1A)
        return B::CS;
    // Combining marks.
    if (in(c, 0x0300, 0x036F) || in(c, 0x0483, 0x0489) || in(c, 0x0591, 0x05BD) || c == 0x05BF || in(c, 0x05C1, 0x05C2) || in(c, 0x05C4, 0x05C5) ||
        c == 0x05C7 || in(c, 0x0610, 0x061A) || in(c, 0x064B, 0x065F) || c == 0x0670 || in(c, 0x06D6, 0x06DC) || in(c, 0x06DF, 0x06E4) ||
        in(c, 0x06E7, 0x06E8) || in(c, 0x06EA, 0x06ED) || c == 0x0711 || in(c, 0x0730, 0x074A) || in(c, 0x07A6, 0x07B0) || in(c, 0x07EB, 0x07F3) ||
        in(c, 0x20D0, 0x20F0) || in(c, 0xFE00, 0xFE0F) || in(c, 0xFE20, 0xFE2F))
        return B::NSM;
    // Strong right-to-left scripts.
    if (in(c, 0x0590, 0x05FF) || in(c, 0x07C0, 0x085F) || in(c, 0xFB1D, 0xFB4F) || in(c, 0x10800, 0x10FFF) || in(c, 0x1E800, 0x1EDFF) || in(c, 0x1EF00, 0x1EFFF))
        return B::R;
    if (in(c, 0x0600, 0x07BF) || in(c, 0x0860, 0x08FF) || in(c, 0xFB50, 0xFDFF) || in(c, 0xFE70, 0xFEFE) || in(c, 0x1EE00, 0x1EEFF)) return B::AL;
    // Other neutrals: ASCII/Latin-1 punctuation and symbols, general punctuation, symbol blocks, CJK punctuation.
    if (in(c, 0x21, 0x2F) || in(c, 0x3A, 0x40) || in(c, 0x5B, 0x60) || in(c, 0x7B, 0x7E) || in(c, 0xA1, 0xBF) || c == 0xD7 || c == 0xF7 ||
        in(c, 0x2010, 0x2027) || in(c, 0x2030, 0x205E) || in(c, 0x2100, 0x2BFF) || in(c, 0x3001, 0x3004) || in(c, 0x3008, 0x3020) ||
        in(c, 0xFE30, 0xFE4F) || in(c, 0xFF01, 0xFF0F) || in(c, 0xFF1A, 0xFF20))
        return B::ON;
    return B::L;
}

BidiParagraph resolveBidi(std::u32string_view text, Direction direction) {
    using B = BidiClass;
    BidiParagraph out;
    const std::size_t n = text.size();
    std::vector<B> original(n);
    for (std::size_t i = 0; i < n; ++i) original[i] = bidiClass(text[i]);
    // P2–P3: the first strong character sets the paragraph level.
    out.base_level = direction == Direction::RightToLeft ? 1 : 0;
    if (direction == Direction::Auto) {
        for (const auto t : original) {
            if (t == B::L) break;
            if (t == B::R || t == B::AL) {
                out.base_level = 1;
                break;
            }
        }
    }
    out.levels.assign(n, out.base_level);
    // X9: boundary neutrals are removed from resolution (they take the preceding level afterwards).
    std::vector<std::size_t> idx;
    idx.reserve(n);
    for (std::size_t i = 0; i < n; ++i)
        if (original[i] != B::BN) idx.push_back(i);
    std::vector<B> t(idx.size());
    for (std::size_t k = 0; k < idx.size(); ++k) t[k] = original[idx[k]];
    const B sos = (out.base_level & 1U) != 0 ? B::R : B::L;
    const B eos = sos;
    const std::size_t m = t.size();
    // W1: NSM takes the type of the previous character (sos at the start).
    for (std::size_t k = 0; k < m; ++k)
        if (t[k] == B::NSM) t[k] = k == 0 ? sos : t[k - 1];
    // W2: EN after AL (looking back to the last strong type) becomes AN.
    B last_strong = sos;
    for (std::size_t k = 0; k < m; ++k) {
        if (t[k] == B::L || t[k] == B::R || t[k] == B::AL) last_strong = t[k];
        else if (t[k] == B::EN && last_strong == B::AL) t[k] = B::AN;
    }
    // W3: AL → R.
    for (auto& x : t)
        if (x == B::AL) x = B::R;
    // W4: a single ES between ENs → EN; a single CS between two numbers of the same type → that type.
    for (std::size_t k = 1; k + 1 < m; ++k) {
        if (t[k] == B::ES && t[k - 1] == B::EN && t[k + 1] == B::EN) t[k] = B::EN;
        else if (t[k] == B::CS && t[k - 1] == B::EN && t[k + 1] == B::EN) t[k] = B::EN;
        else if (t[k] == B::CS && t[k - 1] == B::AN && t[k + 1] == B::AN) t[k] = B::AN;
    }
    // W5: ET sequences adjacent to EN become EN.
    for (std::size_t k = 0; k < m;) {
        if (t[k] != B::ET) {
            ++k;
            continue;
        }
        std::size_t e = k;
        while (e < m && t[e] == B::ET) ++e;
        const bool touches = (k > 0 && t[k - 1] == B::EN) || (e < m && t[e] == B::EN);
        if (touches)
            for (std::size_t j = k; j < e; ++j) t[j] = B::EN;
        k = e;
    }
    // W6: remaining separators and terminators → ON.
    for (auto& x : t)
        if (x == B::ES || x == B::ET || x == B::CS) x = B::ON;
    // W7: EN after L (looking back to the last strong type) becomes L.
    last_strong = sos;
    for (std::size_t k = 0; k < m; ++k) {
        if (t[k] == B::L || t[k] == B::R) last_strong = t[k];
        else if (t[k] == B::EN && last_strong == B::L) t[k] = B::L;
    }
    // N1–N2: neutral runs take the surrounding strong direction (numbers count as R), else the embedding one.
    const auto strongOf = [](B x) { return x == B::L ? B::L : (x == B::R || x == B::EN || x == B::AN) ? B::R : B::ON; };
    for (std::size_t k = 0; k < m;) {
        if (!isNeutralOrIsolate(t[k])) {
            ++k;
            continue;
        }
        std::size_t e = k;
        while (e < m && isNeutralOrIsolate(t[e])) ++e;
        const B before = k == 0 ? sos : strongOf(t[k - 1]);
        const B after = e == m ? eos : strongOf(t[e]);
        const B resolved = before == after && before != B::ON ? before : sos;
        for (std::size_t j = k; j < e; ++j) t[j] = resolved;
        k = e;
    }
    // I1–I2: implicit levels.
    for (std::size_t k = 0; k < m; ++k) {
        std::uint8_t level = out.base_level;
        if ((level & 1U) == 0) {
            if (t[k] == B::R) level += 1;
            else if (t[k] == B::AN || t[k] == B::EN) level += 2;
        } else if (t[k] == B::L || t[k] == B::EN || t[k] == B::AN) {
            level += 1;
        }
        out.levels[idx[k]] = level;
    }
    // Boundary neutrals take the level of the preceding character (or the paragraph level).
    std::uint8_t previous = out.base_level;
    for (std::size_t i = 0; i < n; ++i) {
        if (original[i] == B::BN) out.levels[i] = previous;
        else previous = out.levels[i];
    }
    return out;
}

std::vector<std::uint32_t> visualOrder(std::u32string_view text, const BidiParagraph& bidi, std::uint32_t begin, std::uint32_t end) {
    using B = BidiClass;
    end = std::min<std::uint32_t>(end, static_cast<std::uint32_t>(std::min(text.size(), bidi.levels.size())));
    if (begin >= end) return {};
    std::vector<std::uint8_t> levels(bidi.levels.begin() + begin, bidi.levels.begin() + end);
    // L1: separators, and whitespace before them or at the end of the line, return to the paragraph level.
    bool trailing = true;
    for (std::uint32_t i = end; i-- > begin;) {
        const B t = bidiClass(text[i]);
        if (t == B::S || t == B::B) {
            levels[i - begin] = bidi.base_level;
            trailing = true;
        } else if (trailing && (t == B::WS || t == B::BN)) {
            levels[i - begin] = bidi.base_level;
        } else {
            trailing = false;
        }
    }
    std::vector<std::uint32_t> order(end - begin);
    for (std::uint32_t i = begin; i < end; ++i) order[i - begin] = i;
    // L2: from the highest level down to the lowest odd level, reverse every run at or above it.
    const std::uint8_t highest = *std::max_element(levels.begin(), levels.end());
    std::uint8_t lowest_odd = 255;
    for (const auto l : levels)
        if ((l & 1U) != 0) lowest_odd = std::min(lowest_odd, l);
    if (lowest_odd == 255) return order;
    for (int level = highest; level >= lowest_odd; --level) {
        for (std::size_t k = 0; k < levels.size();) {
            if (levels[k] < level) {
                ++k;
                continue;
            }
            std::size_t e = k;
            while (e < levels.size() && levels[e] >= level) ++e;
            std::reverse(order.begin() + static_cast<std::ptrdiff_t>(k), order.begin() + static_cast<std::ptrdiff_t>(e));
            std::reverse(levels.begin() + static_cast<std::ptrdiff_t>(k), levels.begin() + static_cast<std::ptrdiff_t>(e));
            k = e;
        }
    }
    return order;
}

char32_t mirror(char32_t c) {
    switch (c) {
        case U'(': return U')';
        case U')': return U'(';
        case U'[': return U']';
        case U']': return U'[';
        case U'{': return U'}';
        case U'}': return U'{';
        case U'<': return U'>';
        case U'>': return U'<';
        case 0x00AB: return 0x00BB;
        case 0x00BB: return 0x00AB;
        case 0x2039: return 0x203A;
        case 0x203A: return 0x2039;
        case 0x2264: return 0x2265;
        case 0x2265: return 0x2264;
        case 0x3008: return 0x3009;
        case 0x3009: return 0x3008;
        case 0xFF08: return 0xFF09;
        case 0xFF09: return 0xFF08;
        default: return c;
    }
}

}  // namespace croptop::text
