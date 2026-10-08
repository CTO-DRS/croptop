// CFF outlines for OpenType fonts with PostScript glyphs.
#include "cff_outlines.h"

#include <array>
#include <cmath>
#include <map>

namespace croptop::text {
namespace {

using Index = CffOutlines::Index;

/** Bounds-checked reads; any out-of-range access clears `ok` and yields 0. */
struct Bytes {
    std::span<const std::uint8_t> d;
    bool ok = true;
    std::uint32_t u(std::size_t at, int n) {
        if (n < 1 || n > 4 || at + static_cast<std::size_t>(n) > d.size() || at + static_cast<std::size_t>(n) < at) return ok = false, 0;
        std::uint32_t v = 0;
        for (int k = 0; k < n; ++k) v = v << 8 | d[at + static_cast<std::size_t>(k)];
        return v;
    }
    std::uint8_t u8(std::size_t at) { return static_cast<std::uint8_t>(u(at, 1)); }
    std::uint16_t u16(std::size_t at) { return static_cast<std::uint16_t>(u(at, 2)); }
};

std::optional<Index> readIndex(Bytes& b, std::size_t at, std::size_t* end = nullptr) {
    Index index;
    index.count = b.u16(at);
    if (!b.ok) return std::nullopt;
    if (index.count == 0) {
        if (end) *end = at + 2;
        return index;
    }
    index.off_size = b.u8(at + 2);
    if (!b.ok || index.off_size < 1 || index.off_size > 4) return std::nullopt;
    index.offsets = at + 3;
    index.data = index.offsets + (index.count + 1) * index.off_size - 1;  // offsets are 1-based
    const std::uint32_t last = b.u(index.offsets + index.count * index.off_size, index.off_size);
    if (!b.ok || index.data + last > b.d.size() || index.data > b.d.size()) return std::nullopt;
    if (end) *end = index.data + last;
    return index;
}

/** Element `i` of an INDEX as [begin, end). */
bool element(Bytes& b, const Index& index, std::size_t i, std::size_t& begin, std::size_t& end) {
    if (i >= index.count) return false;
    const std::uint32_t o0 = b.u(index.offsets + i * index.off_size, index.off_size), o1 = b.u(index.offsets + (i + 1) * index.off_size, index.off_size);
    if (!b.ok || o0 < 1 || o1 < o0) return false;
    begin = index.data + o0;
    end = index.data + o1;
    return end <= b.d.size();
}

/** A DICT: operator (escaped ones as 1200 + byte) → operands. */
std::map<int, std::vector<double>> parseDict(Bytes& b, std::size_t begin, std::size_t end) {
    std::map<int, std::vector<double>> dict;
    std::vector<double> operands;
    for (std::size_t p = begin; p < end && b.ok && operands.size() < 48;) {
        const std::uint8_t b0 = b.u8(p);
        if (b0 <= 21) {
            int op = b0;
            p += 1;
            if (b0 == 12) op = 1200 + b.u8(p++);
            dict[op] = operands;
            operands.clear();
        } else if (b0 == 28) {
            operands.push_back(static_cast<std::int16_t>(b.u16(p + 1)));
            p += 3;
        } else if (b0 == 29) {
            operands.push_back(static_cast<std::int32_t>(b.u(p + 1, 4)));
            p += 5;
        } else if (b0 == 30) {  // real number: nibbles until 0xF
            std::string text;
            for (++p; p < end;) {
                const std::uint8_t byte = b.u8(p++);
                bool done = false;
                for (const int nibble : {byte >> 4, byte & 0xF}) {
                    if (nibble <= 9) text += static_cast<char>('0' + nibble);
                    else if (nibble == 0xA) text += '.';
                    else if (nibble == 0xB) text += 'E';
                    else if (nibble == 0xC) text += "E-";
                    else if (nibble == 0xE) text += '-';
                    else if (nibble == 0xF) done = true;
                    if (done) break;
                }
                if (done || text.size() > 32) break;
            }
            char* tail = nullptr;
            operands.push_back(text.empty() ? 0.0 : std::strtod(text.c_str(), &tail));
        } else if (b0 >= 32 && b0 <= 246) {
            operands.push_back(b0 - 139);
            p += 1;
        } else if (b0 >= 247 && b0 <= 250) {
            operands.push_back((b0 - 247) * 256 + b.u8(p + 1) + 108);
            p += 2;
        } else if (b0 >= 251 && b0 <= 254) {
            operands.push_back(-(b0 - 251) * 256 - b.u8(p + 1) - 108);
            p += 2;
        } else {
            p += 1;  // reserved
        }
    }
    return dict;
}

std::size_t operandAt(const std::map<int, std::vector<double>>& dict, int op, std::size_t k) {
    const auto it = dict.find(op);
    if (it == dict.end() || it->second.size() <= k || !(it->second[k] >= 0) || it->second[k] > 1e9) return 0;
    return static_cast<std::size_t>(it->second[k]);
}

int subrBias(std::size_t count) { return count < 1240 ? 107 : count < 33900 ? 1131 : 32768; }

}  // namespace

std::optional<CffOutlines> CffOutlines::parse(std::span<const std::uint8_t> table, std::string& error) {
    Bytes b{table};
    CffOutlines cff;
    cff.t_ = table;
    const std::uint8_t header_size = b.u8(2);
    std::size_t at = header_size, next = 0;
    const auto name = readIndex(b, at, &next);
    const auto top = name ? readIndex(b, at = next, &next) : std::nullopt;
    const auto strings = top ? readIndex(b, at = next, &next) : std::nullopt;
    const auto globals = strings ? readIndex(b, at = next, &next) : std::nullopt;
    std::size_t tb = 0, te = 0;
    if (!b.ok || b.u8(0) != 1 || !globals || !element(b, *top, 0, tb, te)) {
        error = "CFF header or INDEX structure is corrupt";
        return std::nullopt;
    }
    cff.global_subrs_ = *globals;
    const auto dict = parseDict(b, tb, te);
    if (dict.count(1206) != 0 && operandAt(dict, 1206, 0) != 2) {
        error = "only Type 2 charstrings are supported";
        return std::nullopt;
    }
    const auto charstrings = readIndex(b, operandAt(dict, 17, 0));
    if (!charstrings || charstrings->count == 0 || operandAt(dict, 17, 0) == 0) {
        error = "CFF has no charstrings";
        return std::nullopt;
    }
    cff.charstrings_ = *charstrings;
    const auto readPrivate = [&](const std::map<int, std::vector<double>>& d) {
        Private pr;
        const std::size_t size = operandAt(d, 18, 0), offset = operandAt(d, 18, 1);
        if (size == 0 || offset == 0 || offset + size > table.size()) return pr;
        const auto private_dict = parseDict(b, offset, offset + size);
        const std::size_t subrs = operandAt(private_dict, 19, 0);
        if (subrs != 0) {
            if (const auto index = readIndex(b, offset + subrs)) {
                pr.subrs = *index;
                pr.has_subrs = true;
            }
        }
        return pr;
    };
    cff.cid_ = dict.count(1230) != 0;
    if (cff.cid_) {
        const auto fd_array = readIndex(b, operandAt(dict, 1236, 0));
        cff.fd_select_ = operandAt(dict, 1237, 0);
        if (!fd_array || fd_array->count == 0 || fd_array->count > 256 || cff.fd_select_ == 0) {
            error = "CID-keyed CFF lacks a valid FDArray/FDSelect";
            return std::nullopt;
        }
        for (std::size_t k = 0; k < fd_array->count; ++k) {
            std::size_t fb = 0, fe = 0;
            if (!element(b, *fd_array, k, fb, fe)) {
                error = "CFF FDArray entry is corrupt";
                return std::nullopt;
            }
            cff.privates_.push_back(readPrivate(parseDict(b, fb, fe)));
        }
    } else {
        cff.privates_.push_back(readPrivate(dict));
    }
    if (!b.ok) {
        error = "CFF dictionaries are corrupt";
        return std::nullopt;
    }
    return cff;
}

std::size_t CffOutlines::fdFor(std::uint16_t gid) const {
    if (!cid_) return 0;
    Bytes b{t_};
    const std::uint8_t format = b.u8(fd_select_);
    std::size_t fd = 0;
    if (format == 0) {
        fd = b.u8(fd_select_ + 1 + gid);
    } else if (format == 3) {
        const std::uint16_t ranges = b.u16(fd_select_ + 1);
        for (std::uint16_t r = 0; r < ranges && b.ok; ++r) {
            const std::size_t at = fd_select_ + 3 + static_cast<std::size_t>(r) * 3;
            const std::uint16_t first = b.u16(at), next = b.u16(at + 3);
            if (gid >= first && gid < next) {
                fd = b.u8(at + 2);
                break;
            }
        }
    }
    return b.ok && fd < privates_.size() ? fd : 0;
}

/** Type 2 charstring interpreter state for one glyph. */
struct CffInterpreter {
    const CffOutlines& font;
    const CffOutlines::Private& priv;
    vector::Path& path;
    Bytes b;
    std::array<double, 48> stack{};
    std::size_t sp = 0;
    double x = 0, y = 0;
    int stems = 0;
    bool have_width = false, open = false, done = false, failed = false;
    int budget = 20000;

    void push(double v) {
        if (sp < stack.size()) stack[sp++] = v;
        else failed = true;
    }
    void dropWidth(bool extra) {
        if (!have_width && extra && sp > 0) {
            for (std::size_t k = 1; k < sp; ++k) stack[k - 1] = stack[k];
            --sp;
        }
        have_width = true;
    }
    void closeContour() {
        if (open) path.close();
        open = false;
    }
    void moveTo(double dx, double dy) {
        closeContour();
        x += dx;
        y += dy;
        path.moveTo({x, y});
        open = true;
    }
    void lineTo(double dx, double dy) {
        if (!open) moveTo(0, 0);
        x += dx;
        y += dy;
        path.lineTo({x, y});
    }
    void curveTo(double dx1, double dy1, double dx2, double dy2, double dx3, double dy3) {
        if (!open) moveTo(0, 0);
        const vector::Point c1{x + dx1, y + dy1}, c2{c1.x + dx2, c1.y + dy2};
        x = c2.x + dx3;
        y = c2.y + dy3;
        path.cubicTo(c1, c2, {x, y});
    }

    void run(std::size_t begin, std::size_t end, int depth) {
        if (depth > 10) {
            failed = true;
            return;
        }
        for (std::size_t p = begin; p < end && !done && !failed;) {
            if (--budget < 0) {
                failed = true;
                return;
            }
            const std::uint8_t b0 = b.u8(p);
            if (!b.ok) {
                failed = true;
                return;
            }
            if (b0 >= 32 || b0 == 28) {
                if (b0 == 28) {
                    push(static_cast<std::int16_t>(b.u16(p + 1)));
                    p += 3;
                } else if (b0 <= 246) {
                    push(b0 - 139);
                    p += 1;
                } else if (b0 <= 250) {
                    push((b0 - 247) * 256 + b.u8(p + 1) + 108);
                    p += 2;
                } else if (b0 <= 254) {
                    push(-(b0 - 251) * 256 - b.u8(p + 1) - 108);
                    p += 2;
                } else {
                    push(static_cast<std::int32_t>(b.u(p + 1, 4)) / 65536.0);
                    p += 5;
                }
                continue;
            }
            p += 1;
            const auto s = [&](std::size_t k) { return stack[k]; };
            switch (b0) {
                case 1: case 3: case 18: case 23:  // hstem, vstem, hstemhm, vstemhm
                    dropWidth(sp % 2 == 1);
                    stems += static_cast<int>(sp / 2);
                    sp = 0;
                    break;
                case 19: case 20:  // hintmask, cntrmask (implicit vstem operands first)
                    dropWidth(sp % 2 == 1);
                    stems += static_cast<int>(sp / 2);
                    sp = 0;
                    p += static_cast<std::size_t>((stems + 7) / 8);
                    break;
                case 21:  // rmoveto
                    dropWidth(sp > 2);
                    if (sp >= 2) moveTo(s(0), s(1));
                    sp = 0;
                    break;
                case 22:  // hmoveto
                    dropWidth(sp > 1);
                    if (sp >= 1) moveTo(s(0), 0);
                    sp = 0;
                    break;
                case 4:  // vmoveto
                    dropWidth(sp > 1);
                    if (sp >= 1) moveTo(0, s(0));
                    sp = 0;
                    break;
                case 5:  // rlineto
                    for (std::size_t i = 0; i + 1 < sp; i += 2) lineTo(s(i), s(i + 1));
                    sp = 0;
                    break;
                case 6: case 7: {  // hlineto, vlineto
                    bool horizontal = b0 == 6;
                    for (std::size_t i = 0; i < sp; ++i, horizontal = !horizontal) lineTo(horizontal ? s(i) : 0, horizontal ? 0 : s(i));
                    sp = 0;
                    break;
                }
                case 8:  // rrcurveto
                    for (std::size_t i = 0; i + 5 < sp; i += 6) curveTo(s(i), s(i + 1), s(i + 2), s(i + 3), s(i + 4), s(i + 5));
                    sp = 0;
                    break;
                case 24: {  // rcurveline
                    std::size_t i = 0;
                    for (; sp >= 8 && i + 6 <= sp - 2; i += 6) curveTo(s(i), s(i + 1), s(i + 2), s(i + 3), s(i + 4), s(i + 5));
                    if (i + 2 <= sp) lineTo(s(i), s(i + 1));
                    sp = 0;
                    break;
                }
                case 25: {  // rlinecurve
                    std::size_t i = 0;
                    for (; sp >= 8 && i + 2 <= sp - 6; i += 2) lineTo(s(i), s(i + 1));
                    if (i + 6 <= sp) curveTo(s(i), s(i + 1), s(i + 2), s(i + 3), s(i + 4), s(i + 5));
                    sp = 0;
                    break;
                }
                case 26: {  // vvcurveto
                    std::size_t i = 0;
                    double dx1 = 0;
                    if (sp % 4 == 1) dx1 = s(i++);
                    for (; i + 3 < sp; i += 4, dx1 = 0) curveTo(dx1, s(i), s(i + 1), s(i + 2), 0, s(i + 3));
                    sp = 0;
                    break;
                }
                case 27: {  // hhcurveto
                    std::size_t i = 0;
                    double dy1 = 0;
                    if (sp % 4 == 1) dy1 = s(i++);
                    for (; i + 3 < sp; i += 4, dy1 = 0) curveTo(s(i), dy1, s(i + 1), s(i + 2), s(i + 3), 0);
                    sp = 0;
                    break;
                }
                case 30: case 31: {  // vhcurveto, hvcurveto
                    bool horizontal = b0 == 31;
                    for (std::size_t i = 0; i + 3 < sp; i += 4, horizontal = !horizontal) {
                        const double extra = sp - i == 5 ? s(i + 4) : 0;
                        if (horizontal) curveTo(s(i), 0, s(i + 1), s(i + 2), extra, s(i + 3));
                        else curveTo(0, s(i), s(i + 1), s(i + 2), s(i + 3), extra);
                    }
                    sp = 0;
                    break;
                }
                case 10: case 29: {  // callsubr, callgsubr
                    if (sp == 0) {
                        failed = true;
                        return;
                    }
                    const bool local = b0 == 10;
                    if (local && !priv.has_subrs) {
                        failed = true;
                        return;
                    }
                    const CffOutlines::Index& subrs = local ? priv.subrs : font.global_subrs_;
                    const long long index = static_cast<long long>(stack[--sp]) + subrBias(subrs.count);
                    std::size_t sb = 0, se = 0;
                    if (index < 0 || !element(b, subrs, static_cast<std::size_t>(index), sb, se)) {
                        failed = true;
                        return;
                    }
                    run(sb, se, depth + 1);
                    break;
                }
                case 11:  // return
                    return;
                case 14:  // endchar
                    dropWidth(sp == 1 || sp == 5);
                    closeContour();
                    done = true;
                    return;
                case 12: {
                    const std::uint8_t b1 = b.u8(p++);
                    if (b1 == 35 && sp >= 12) {  // flex
                        curveTo(s(0), s(1), s(2), s(3), s(4), s(5));
                        curveTo(s(6), s(7), s(8), s(9), s(10), s(11));
                    } else if (b1 == 34 && sp >= 7) {  // hflex
                        curveTo(s(0), 0, s(1), s(2), s(3), 0);
                        curveTo(s(4), 0, s(5), -s(2), s(6), 0);
                    } else if (b1 == 36 && sp >= 9) {  // hflex1
                        curveTo(s(0), s(1), s(2), s(3), s(4), 0);
                        curveTo(s(5), 0, s(6), s(7), s(8), -(s(1) + s(3) + s(7)));
                    } else if (b1 == 37 && sp >= 11) {  // flex1
                        const double dx = s(0) + s(2) + s(4) + s(6) + s(8), dy = s(1) + s(3) + s(5) + s(7) + s(9);
                        curveTo(s(0), s(1), s(2), s(3), s(4), s(5));
                        if (std::abs(dx) > std::abs(dy)) curveTo(s(6), s(7), s(8), s(9), s(10), -dy);
                        else curveTo(s(6), s(7), s(8), s(9), -dx, s(10));
                    }
                    sp = 0;  // arithmetic/storage operators are not used by real fonts' outlines
                    break;
                }
                default:
                    sp = 0;  // reserved operators
                    break;
            }
        }
    }
};

bool CffOutlines::outline(std::uint16_t gid, vector::Path& path) const {
    Bytes b{t_};
    std::size_t begin = 0, end = 0;
    if (!element(b, charstrings_, gid, begin, end)) return false;
    vector::Path glyph;
    CffInterpreter interpreter{*this, privates_[fdFor(gid)], glyph, Bytes{t_}};
    interpreter.run(begin, end, 0);
    if (interpreter.failed) return false;
    interpreter.closeContour();
    path.append(glyph);
    return true;
}

}  // namespace croptop::text
