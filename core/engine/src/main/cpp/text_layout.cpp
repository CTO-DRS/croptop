// Paragraph layout for the text engine.
#include "text_layout.h"

#include <algorithm>
#include <cmath>
#include <limits>

namespace croptop::text {
namespace {

bool isHardBreak(char32_t c) { return c == U'\n' || c == U'\r' || c == 0x2028 || c == 0x2029; }
bool isSpace(char32_t c) { return c == U' ' || c == U'\t' || c == 0x3000; }
bool isNoBreakSpace(char32_t c) { return c == 0x00A0 || c == 0x202F; }
bool isHyphen(char32_t c) { return c == U'-' || c == 0x2010 || c == 0x2013; }
bool isCjk(char32_t c) {
    return (c >= 0x4E00 && c <= 0x9FFF) || (c >= 0x3400 && c <= 0x4DBF) || (c >= 0x3040 && c <= 0x30FF) || (c >= 0xAC00 && c <= 0xD7AF) ||
           (c >= 0xF900 && c <= 0xFAFF) || (c >= 0x20000 && c <= 0x2FFFF);
}
/** Closing punctuation that must not start a line (a light kinsoku rule). */
bool isClosing(char32_t c) {
    return c == U',' || c == U'.' || c == U')' || c == U']' || c == U'}' || c == U'!' || c == U'?' || c == U':' || c == U';' || c == 0x3001 ||
           c == 0x3002 || c == 0xFF0C || c == 0xFF0E || c == 0x300D || c == 0x300F;
}

/** One breakable unit of a paragraph: code points [begin, end) with their advance. */
struct Item {
    std::uint32_t begin = 0, end = 0;
    float width = 0;           // advance of the whole item
    float trailing_space = 0;  // advance of trailing spaces (hang at line end)
    int spaces = 0;            // spaces inside (excluding trailing)
    bool emergency = false;    // an overlong word split at a cluster boundary (penalised)
};

}  // namespace

std::vector<ShapedGlyph> FixedAdvanceShaper::shape(std::u32string_view run, float size_px) const {
    std::vector<ShapedGlyph> out;
    out.reserve(run.size());
    for (std::uint32_t i = 0; i < run.size(); ++i) {
        const char32_t c = run[i];
        const float fraction = isSpace(c) || isNoBreakSpace(c) ? space_ : isCjk(c) ? cjk_ : em_;
        out.push_back({static_cast<std::uint32_t>(c), i, fraction * size_px});
    }
    return out;
}

std::u32string decodeUtf8(std::string_view s) {
    std::u32string out;
    out.reserve(s.size());
    for (std::size_t i = 0; i < s.size();) {
        const auto b = static_cast<unsigned char>(s[i]);
        int len = b < 0x80 ? 1 : (b >> 5) == 0x6 ? 2 : (b >> 4) == 0xE ? 3 : (b >> 3) == 0x1E ? 4 : 0;
        char32_t cp = len == 1 ? b : len == 2 ? (b & 0x1F) : len == 3 ? (b & 0x0F) : (b & 0x07);
        bool ok = len > 0 && i + static_cast<std::size_t>(len) <= s.size();
        for (int k = 1; ok && k < len; ++k) {
            const auto c = static_cast<unsigned char>(s[i + static_cast<std::size_t>(k)]);
            if ((c >> 6) != 0x2) ok = false;
            else cp = (cp << 6) | (c & 0x3F);
        }
        // Reject overlong forms, surrogates and values beyond U+10FFFF.
        if (ok && ((len == 2 && cp < 0x80) || (len == 3 && cp < 0x800) || (len == 4 && cp < 0x10000) || cp > 0x10FFFF || (cp >= 0xD800 && cp <= 0xDFFF))) ok = false;
        if (!ok) {
            out.push_back(0xFFFD);
            ++i;
            continue;
        }
        out.push_back(cp);
        i += static_cast<std::size_t>(len);
    }
    return out;
}

Layout layout(std::string_view utf8, const ParagraphStyle& style, float width, const Shaper& shaper) {
    return layout(utf8, style, std::span<const CharacterRun>{}, width, shaper);
}

Layout layout(std::string_view utf8, const ParagraphStyle& style, std::span<const CharacterRun> runs, float width, const Shaper& shaper) {
    Layout result;
    result.text = decodeUtf8(utf8);
    const auto& text = result.text;
    const float size = std::isfinite(style.size_px) && style.size_px > 0 ? std::min(style.size_px, 10000.0F) : 16.0F;
    result.ascent = shaper.ascent(size);
    result.descent = shaper.descent(size);
    result.leading = std::isfinite(style.leading_px) && style.leading_px > 0 ? style.leading_px : size * 1.2F;
    const float tracking = std::isfinite(style.tracking) ? style.tracking / 1000.0F * size : 0.0F;
    const bool wrap = std::isfinite(width) && width > 0;
    const auto clean = [](float v) { return std::isfinite(v) ? std::max(0.0F, v) : 0.0F; };
    const float left = clean(style.left_indent), right = clean(style.right_indent);
    const bool auto_leading = !(std::isfinite(style.leading_px) && style.leading_px > 0);
    // Character runs: which run styles each code point (later runs win), validated once.
    std::vector<int> run_of(text.size(), -1);
    for (std::size_t r = 0; r < runs.size(); ++r) {
        const auto b = std::min<std::size_t>(runs[r].begin, text.size()), e = std::min<std::size_t>(runs[r].end, text.size());
        for (std::size_t i = b; i < e; ++i) run_of[i] = static_cast<int>(r);
    }
    const auto runSize = [&](int r) {
        const float v = r >= 0 ? runs[static_cast<std::size_t>(r)].size_px : 0.0F;
        return std::isfinite(v) && v > 0 ? std::min(v, 10000.0F) : size;
    };
    const auto runShaper = [&](int r) -> const Shaper& { return r >= 0 && runs[static_cast<std::size_t>(r)].shaper != nullptr ? *runs[static_cast<std::size_t>(r)].shaper : shaper; };
    const auto runTracking = [&](int r) {
        if (r < 0 || !runs[static_cast<std::size_t>(r)].tracking) return tracking / size;  // em fraction
        const float v = *runs[static_cast<std::size_t>(r)].tracking;
        return std::isfinite(v) ? v / 1000.0F : 0.0F;
    };
    const auto runShift = [&](int r) {
        const float v = r >= 0 ? runs[static_cast<std::size_t>(r)].baseline_shift : 0.0F;
        return std::isfinite(v) ? std::clamp(v, -10000.0F, 10000.0F) : 0.0F;
    };
    const float first = std::isfinite(style.first_line_indent) ? style.first_line_indent : 0.0F;

    float y = 0;
    bool first_paragraph = true;
    std::uint32_t p0 = 0;
    const auto n = static_cast<std::uint32_t>(text.size());
    while (p0 <= n) {
        // Paragraph [p0, p1) ends at a hard break (CRLF counts once) or the end of the text.
        std::uint32_t p1 = p0;
        while (p1 < n && !isHardBreak(text[p1])) ++p1;
        std::uint32_t next = p1;
        if (next < n) next += (text[next] == U'\r' && next + 1 < n && text[next + 1] == U'\n') ? 2U : 1U;
        const bool hard = p1 < n;
        const auto bidi = resolveBidi(std::u32string_view(text).substr(p0, p1 - p0), style.direction);
        if (result.levels.size() != text.size()) result.levels.assign(text.size(), 0);
        for (std::uint32_t i = p0; i < p1; ++i) result.levels[i] = bidi.levels[i - p0];
        for (std::uint32_t i = p1; i < next; ++i) result.levels[i] = bidi.base_level;
        bool mixed = false;
        for (const auto l : bidi.levels) mixed = mixed || l != 0;

        // Per-code-point advances from the shaper (a cluster's advance is attributed to its first code point).
        std::vector<float> advance(p1 - p0, 0.0F);
        std::vector<ShapedGlyph> glyphs;
        std::vector<int> glyph_run;  // parallel to glyphs: the character run that styled each glyph
        for (std::uint32_t a = p0; a < p1;) {
            // Shape each maximal stretch of code points sharing a character run with that run's font and size.
            std::uint32_t b = a;
            while (b < p1 && run_of[b] == run_of[a]) ++b;
            const int r = run_of[a];
            const float run_size = runSize(r), run_tracking = runTracking(r) * run_size;
            auto shaped = runShaper(r).shape(std::u32string_view(text).substr(a, b - a), run_size);
            for (auto& g : shaped) {
                g.cluster = std::min(g.cluster, b - a - 1) + (a - p0);
                g.advance = std::isfinite(g.advance) ? g.advance + run_tracking : run_tracking;
                advance[g.cluster] += g.advance;
                glyphs.push_back(g);
                glyph_run.push_back(r);
            }
            a = b;
        }
        // Glyphs grouped by cluster (stable order within a cluster), with the first glyph of each cluster.
        {
            std::vector<std::size_t> order(glyphs.size());
            for (std::size_t k = 0; k < order.size(); ++k) order[k] = k;
            std::stable_sort(order.begin(), order.end(), [&](std::size_t x, std::size_t y) { return glyphs[x].cluster < glyphs[y].cluster; });
            std::vector<ShapedGlyph> sorted_glyphs(glyphs.size());
            std::vector<int> sorted_runs(glyphs.size());
            for (std::size_t k = 0; k < order.size(); ++k) {
                sorted_glyphs[k] = glyphs[order[k]];
                sorted_runs[k] = glyph_run[order[k]];
            }
            glyphs.swap(sorted_glyphs);
            glyph_run.swap(sorted_runs);
        }
        std::vector<std::size_t> cluster_first(p1 - p0, glyphs.size());
        for (std::size_t gi = glyphs.size(); gi-- > 0;) cluster_first[glyphs[gi].cluster] = gi;
        // Cluster starts: breaks only happen where a glyph cluster begins.
        std::vector<bool> cluster_start(p1 - p0 + 1, glyphs.empty());
        for (const auto& g : glyphs) cluster_start[g.cluster] = true;
        cluster_start[p1 - p0] = true;

        // Items: split after break opportunities.
        std::vector<Item> items;
        Item cur{p0, p0};
        for (std::uint32_t i = p0; i < p1; ++i) {
            const char32_t c = text[i];
            const float a = advance[i - p0];
            cur.end = i + 1;
            if (isSpace(c)) {
                cur.trailing_space += a;
            } else {
                cur.width += cur.trailing_space + a;
                if (cur.trailing_space > 0) ++cur.spaces;  // an interior space run (only when no break was taken)
                cur.trailing_space = 0;
            }
            const char32_t nx = i + 1 < p1 ? text[i + 1] : 0;
            bool opportunity = false;
            if (i + 1 < p1 && cluster_start[i + 1 - p0] && !isNoBreakSpace(c) && !isNoBreakSpace(nx) && !isClosing(nx)) {
                opportunity = (isSpace(c) && !isSpace(nx)) || (isHyphen(c) && !isSpace(nx)) || isCjk(c) || isCjk(nx);
            }
            if (opportunity) {
                items.push_back(cur);
                cur = Item{i + 1, i + 1};
            }
        }
        if (cur.end > cur.begin || items.empty()) items.push_back(cur);

        const auto available = [&](bool first_line) { return std::max(1.0F, width - left - right - (first_line ? first : 0.0F)); };
        // Emergency: split any item wider than the narrowest line at cluster boundaries.
        if (wrap) {
            const float narrow = std::min(available(true), available(false));
            std::vector<Item> split;
            for (const auto& it : items) {
                if (it.width <= narrow) {
                    split.push_back(it);
                    continue;
                }
                Item piece{it.begin, it.begin};
                for (std::uint32_t i = it.begin; i < it.end; ++i) {
                    const float a = advance[i - p0];
                    if (piece.end > piece.begin && cluster_start[i - p0] && piece.width + a > narrow && !isSpace(text[i])) {
                        piece.emergency = true;
                        split.push_back(piece);
                        piece = Item{i, i};
                    }
                    piece.end = i + 1;
                    if (isSpace(text[i])) piece.trailing_space += a;
                    else {
                        piece.width += piece.trailing_space + a;
                        piece.trailing_space = 0;
                    }
                }
                split.push_back(piece);
            }
            items.swap(split);
        }

        // Compose: choose break positions (index after the item that ends each line).
        std::vector<std::size_t> breaks;
        const std::size_t m = items.size();
        if (!wrap) {
            breaks.push_back(m);
        } else if (style.composer == Composer::SingleLine) {
            float w = 0;
            std::size_t start = 0;
            for (std::size_t k = 0; k < m; ++k) {
                const float trial = w + items[k].width;
                if (k > start && trial > available(breaks.empty())) {
                    breaks.push_back(k);
                    start = k;
                    w = items[k].width + items[k].trailing_space;
                } else {
                    w = trial + items[k].trailing_space;
                }
            }
            breaks.push_back(m);
        } else {
            // Every-line composer: minimise Σ (slack / available)² over all lines but the last (Knuth–Plass
            // without stretch/shrink boxes), plus a penalty per emergency break.
            constexpr double kInf = std::numeric_limits<double>::infinity();
            std::vector<double> cost(m + 1, kInf);
            std::vector<std::size_t> from(m + 1, 0);
            cost[0] = 0;
            for (std::size_t a = 0; a < m; ++a) {
                if (cost[a] == kInf) continue;
                float w = 0;
                const float avail = available(a == 0);
                for (std::size_t b = a; b < m; ++b) {
                    w += items[b].width + (b > a ? items[b - 1].trailing_space : 0.0F);
                    if (w > avail && b > a) break;
                    const bool last = b + 1 == m;
                    const double slack = (avail - w) / avail;
                    double c = last ? 0.0 : slack * slack * 100.0;
                    if (items[b].emergency) c += 50.0;
                    if (w > avail) c += 1e6;  // a single item wider than the line (only possible without splitting)
                    if (cost[a] + c < cost[b + 1]) {
                        cost[b + 1] = cost[a] + c;
                        from[b + 1] = a;
                    }
                }
            }
            for (std::size_t k = m; k > 0; k = from[k]) breaks.push_back(k);
            std::reverse(breaks.begin(), breaks.end());
        }

        // Place lines.
        if (!first_paragraph) y += clean(style.space_after) + clean(style.space_before);
        std::size_t start = 0;
        for (std::size_t li = 0; li < breaks.size(); ++li) {
            const std::size_t end = breaks[li];
            const bool first_line = li == 0, last_line = li + 1 == breaks.size();
            Line line;
            line.first_cluster = m > 0 ? items[start].begin : p0;
            line.end_cluster = m > 0 && end > 0 ? items[end - 1].end : p0;
            line.hard_break = last_line && hard;
            if (!last_line) line.end_cluster = items[end].begin;
            // Line metrics from the glyphs on this line (paragraph defaults for an empty line).
            float line_size = 0;
            line.ascent = 0;
            line.descent = 0;
            for (std::size_t gi = 0; gi < glyphs.size(); ++gi) {
                const std::uint32_t c = p0 + glyphs[gi].cluster;
                if (c < line.first_cluster || c >= line.end_cluster) continue;
                const int r = glyph_run[gi];
                const float gs = runSize(r), shift = runShift(r);
                line_size = std::max(line_size, gs);
                line.ascent = std::max(line.ascent, runShaper(r).ascent(gs) + shift);
                line.descent = std::max(line.descent, runShaper(r).descent(gs) - shift);
            }
            if (line_size == 0) {
                line_size = size;
                line.ascent = result.ascent;
                line.descent = result.descent;
            }
            y += result.lines.empty() ? line.ascent : (auto_leading ? line_size * 1.2F : result.leading);
            line.baseline = y;
            // Natural width without the line's trailing spaces.
            float natural = 0;
            for (std::size_t k = start; k < end; ++k) natural += items[k].width + (k + 1 < end ? items[k].trailing_space : 0.0F);
            // Trailing spaces of the line hang past the edge; justification works on [first, ink_end).
            std::uint32_t ink_end = line.end_cluster;
            while (ink_end > line.first_cluster && (isSpace(text[ink_end - 1]) || isHardBreak(text[ink_end - 1]))) --ink_end;
            int spaces = 0;
            std::size_t ink_glyphs = 0;
            for (std::uint32_t i = line.first_cluster; i < ink_end; ++i) spaces += isSpace(text[i]) ? 1 : 0;
            for (const auto& g : glyphs) ink_glyphs += (p0 + g.cluster >= line.first_cluster && p0 + g.cluster < ink_end) ? 1U : 0U;
            const float avail = wrap ? available(first_line) : natural;
            const float origin = (first_line ? first : 0.0F) + (wrap ? left : 0.0F);
            const bool justify = wrap && (style.align == Align::JustifyAll || (style.align == Align::JustifyLastLeft && !last_line));
            float offset = 0, per_space = 0, per_gap = 0;
            float space_width = size * 0.25F;
            if (justify && natural < avail) {
                float extra = avail - natural;
                if (spaces > 0) {
                    per_space = std::min(extra / static_cast<float>(spaces), space_width * (std::max(1.0F, style.max_word_spacing) - 1.0F));
                    extra -= per_space * static_cast<float>(spaces);
                }
                if (extra > 0 && ink_glyphs > 1) per_gap = extra / static_cast<float>(ink_glyphs - 1);
            } else if (style.align == Align::Center) {
                offset = wrap ? (avail - natural) / 2.0F : -natural / 2.0F;
            } else if (style.align == Align::Right) {
                offset = wrap ? avail - natural : -natural;
            }
            float x = origin + offset;
            std::size_t placed = 0;
            // Display order: logical for pure left-to-right text; otherwise the UBA visual order of the ink,
            // with the line's trailing spaces still hanging after it.
            std::vector<std::uint32_t> sequence;
            if (mixed && ink_end > line.first_cluster) {
                sequence = visualOrder(std::u32string_view(text).substr(p0, p1 - p0), bidi, line.first_cluster - p0, ink_end - p0);
                for (auto& v : sequence) v += p0;
            } else {
                for (std::uint32_t c = line.first_cluster; c < ink_end; ++c) sequence.push_back(c);
            }
            for (std::uint32_t c = ink_end; c < line.end_cluster; ++c) sequence.push_back(c);
            std::vector<std::pair<std::size_t, std::size_t>> placed_of;  // (paragraph glyph index, line glyph index)
            for (const std::uint32_t c : sequence) {
                if (c < p0 || c >= p1) continue;  // hard-break characters have no glyph
                for (std::size_t gi = cluster_first[c - p0]; gi < glyphs.size() && p0 + glyphs[gi].cluster == c; ++gi) {
                    const auto& g = glyphs[gi];
                    const int r = glyph_run[gi];
                    placed_of.emplace_back(gi, line.glyphs.size());
                    line.glyphs.push_back({g.glyph, c, x + g.x_offset, g.advance, runSize(r), -runShift(r) - g.y_offset, r >= 0 ? runs[static_cast<std::size_t>(r)].style : 0U});
                    x += g.advance;
                    if (c < ink_end) {
                        if (isSpace(text[c])) x += per_space;
                        if (++placed < ink_glyphs) x += per_gap;
                    }
                }
            }
            // Attached marks: position relative to their target's final placement (logical order, so chains of
            // marks resolve), independent of the visual order the line was placed in.
            if (!placed_of.empty()) {
                std::sort(placed_of.begin(), placed_of.end());
                const auto lineIndex = [&](std::size_t gi) -> std::ptrdiff_t {
                    const auto it = std::lower_bound(placed_of.begin(), placed_of.end(), std::pair<std::size_t, std::size_t>{gi, 0});
                    return it != placed_of.end() && it->first == gi ? static_cast<std::ptrdiff_t>(it->second) : -1;
                };
                for (const auto& [gi, li] : placed_of) {
                    const auto& g = glyphs[gi];
                    if (g.attach_back == 0 || g.attach_back > gi) continue;
                    const auto target = lineIndex(gi - g.attach_back);
                    if (target < 0) continue;
                    line.glyphs[li].x = line.glyphs[static_cast<std::size_t>(target)].x + g.x_offset;
                    line.glyphs[li].y_offset = line.glyphs[static_cast<std::size_t>(target)].y_offset - g.y_offset;
                }
            }
            line.width = std::max(0.0F, (justify && natural < avail) ? avail : natural);
            result.lines.push_back(std::move(line));
            start = end;
        }
        first_paragraph = false;
        if (!hard) break;
        p0 = next;
    }
    result.height = result.lines.empty() ? 0.0F : result.lines.back().baseline + result.lines.back().descent;
    return result;
}

Layout::Caret Layout::caretFor(std::size_t index) const {
    Caret caret;
    if (lines.empty()) return caret;
    index = std::min(index, text.size());
    std::size_t li = lines.size() - 1;
    for (std::size_t k = 0; k < lines.size(); ++k) {
        if (index < lines[k].end_cluster || (index == lines[k].end_cluster && (k + 1 == lines.size() || lines[k].hard_break))) {
            li = k;
            break;
        }
    }
    const Line& line = lines[li];
    caret.line = li;
    caret.top = line.baseline - line.ascent;
    caret.bottom = line.baseline + line.descent;
    const auto rtl = [&](std::size_t i) { return i < levels.size() && (levels[i] & 1U) != 0; };
    caret.x = line.glyphs.empty() ? 0.0F : line.glyphs.front().x;
    // The caret before a character sits on its leading edge: left for LTR, right for RTL.
    for (const auto& g : line.glyphs) {
        if (g.cluster == index) {
            caret.x = rtl(index) ? g.x + g.advance : g.x;
            return caret;
        }
    }
    // After the last character of the line: its trailing edge.
    for (const auto& g : line.glyphs) {
        if (index > 0 && g.cluster == index - 1) {
            caret.x = rtl(index - 1) ? g.x : g.x + g.advance;
            return caret;
        }
    }
    if (!line.glyphs.empty()) caret.x = line.glyphs.back().x + line.glyphs.back().advance;
    return caret;
}

std::size_t Layout::indexAt(float x, float y) const {
    if (lines.empty()) return 0;
    std::size_t li = 0;
    float best = std::numeric_limits<float>::infinity();
    for (std::size_t k = 0; k < lines.size(); ++k) {
        const float mid = lines[k].baseline - (lines[k].ascent - lines[k].descent) / 2.0F;
        const float d = std::abs(y - mid);
        if (d < best) {
            best = d;
            li = k;
        }
    }
    const Line& line = lines[li];
    std::size_t index = line.first_cluster;
    float best_x = std::numeric_limits<float>::infinity();
    for (const auto& g : line.glyphs) {
        const bool rtl = g.cluster < levels.size() && (levels[g.cluster] & 1U) != 0;
        const std::size_t leading = g.cluster, trailing = static_cast<std::size_t>(g.cluster) + 1;
        for (const auto& [edge, idx] : {std::pair{g.x, rtl ? trailing : leading}, std::pair{g.x + g.advance, rtl ? leading : trailing}}) {
            if (std::abs(x - edge) < best_x) {
                best_x = std::abs(x - edge);
                index = idx;
            }
        }
    }
    // A caret never lands after a hard break character, only before it.
    std::size_t limit = line.end_cluster;
    while (limit > line.first_cluster && limit <= text.size() && limit > 0 && isHardBreak(text[limit - 1])) --limit;
    return std::min(index, limit);
}

}  // namespace croptop::text
