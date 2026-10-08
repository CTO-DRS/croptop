// TrueType font reader and text rendering.
#include "truetype_font.h"

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstring>

#include "pixel_plane.h"
#include "text_effects.h"

namespace croptop::text {
namespace {

constexpr std::size_t kMaxFontBytes = 64U << 20U;
constexpr int kMaxCompoundDepth = 8;

/** Bounds-checked big-endian reads over the font bytes; out-of-range reads return 0 and clear `ok`. */
struct Reader {
    const std::vector<std::uint8_t>& d;
    bool ok = true;
    std::uint8_t u8(std::size_t at) {
        if (at >= d.size()) return ok = false, 0;
        return d[at];
    }
    std::uint16_t u16(std::size_t at) {
        if (at + 2 > d.size() || at + 2 < at) return ok = false, 0;
        return static_cast<std::uint16_t>(d[at] << 8 | d[at + 1]);
    }
    std::int16_t i16(std::size_t at) { return static_cast<std::int16_t>(u16(at)); }
    std::uint32_t u32(std::size_t at) {
        if (at + 4 > d.size() || at + 4 < at) return ok = false, 0;
        return static_cast<std::uint32_t>(d[at]) << 24 | static_cast<std::uint32_t>(d[at + 1]) << 16 | static_cast<std::uint32_t>(d[at + 2]) << 8 | d[at + 3];
    }
};

using Affine = std::array<double, 6>;  // (x, y) → (a·x + c·y + e, b·x + d·y + f)

Affine compose(const Affine& outer, const Affine& inner) {
    return {outer[0] * inner[0] + outer[2] * inner[1], outer[1] * inner[0] + outer[3] * inner[1],
            outer[0] * inner[2] + outer[2] * inner[3], outer[1] * inner[2] + outer[3] * inner[3],
            outer[0] * inner[4] + outer[2] * inner[5] + outer[4], outer[1] * inner[4] + outer[3] * inner[5] + outer[5]};
}

vector::Point apply(const Affine& m, double x, double y) { return {m[0] * x + m[2] * y + m[4], m[1] * x + m[3] * y + m[5]}; }

}  // namespace

std::uint32_t TrueTypeFont::faceCount(std::span<const std::uint8_t> bytes) {
    if (bytes.size() < 12) return 0;
    const auto u32 = [&](std::size_t at) {
        return static_cast<std::uint32_t>(bytes[at]) << 24 | static_cast<std::uint32_t>(bytes[at + 1]) << 16 | static_cast<std::uint32_t>(bytes[at + 2]) << 8 | bytes[at + 3];
    };
    return u32(0) == 0x74746366U ? std::min<std::uint32_t>(u32(8), 1024) : 1;  // collection tag
}

std::shared_ptr<const TrueTypeFont> TrueTypeFont::load(std::span<const std::uint8_t> bytes, std::string& error, std::uint32_t face) {
    if (bytes.size() < 12 || bytes.size() > kMaxFontBytes) {
        error = "font file is truncated or too large";
        return nullptr;
    }
    std::shared_ptr<TrueTypeFont> font(new TrueTypeFont());
    font->data_.assign(bytes.begin(), bytes.end());
    Reader r{font->data_};
    // A collection lists offset tables; table offsets inside are relative to the file start.
    std::size_t directory = 0;
    if (r.u32(0) == 0x74746366U) {
        if (face >= r.u32(8) || face >= 1024) {
            error = "font collection has no face " + std::to_string(face);
            return nullptr;
        }
        directory = r.u32(12 + static_cast<std::size_t>(face) * 4);
    }
    const std::uint32_t version = r.u32(directory);
    const bool cff = version == 0x4F54544FU;  // OTTO
    if (!r.ok || (!cff && version != 0x00010000U && version != 0x74727565U)) {
        error = "not a TrueType/OpenType font";
        return nullptr;
    }
    const std::uint16_t tables = r.u16(directory + 4);
    std::size_t fvar = 0, fvar_size = 0, avar = 0, avar_size = 0, gvar = 0, gvar_size = 0, hvar = 0, hvar_size = 0;
    std::size_t head = 0, hhea = 0, maxp = 0, cmap = 0, hmtx = 0, hmtx_size = 0, cff_table = 0, cff_size = 0, gpos = 0, gpos_size = 0, gsub = 0, gsub_size = 0, gdef = 0, gdef_size = 0;
    for (std::uint16_t t = 0; t < tables; ++t) {
        const std::size_t rec = directory + 12 + static_cast<std::size_t>(t) * 16;
        const std::uint32_t tag = r.u32(rec), off = r.u32(rec + 8), len = r.u32(rec + 12);
        if (!r.ok || static_cast<std::size_t>(off) + len > font->data_.size()) {
            error = "font table directory is corrupt";
            return nullptr;
        }
        switch (tag) {
            case 0x68656164: head = off; break;           // head
            case 0x68686561: hhea = off; break;           // hhea
            case 0x6D617870: maxp = off; break;           // maxp
            case 0x636D6170: cmap = off; break;           // cmap
            case 0x686D7478: hmtx = off, hmtx_size = len; break;  // hmtx
            case 0x6C6F6361: font->loca_ = off, font->loca_size_ = len; break;  // loca
            case 0x676C7966: font->glyf_ = off, font->glyf_size_ = len; break;  // glyf
            case 0x6B65726E: font->kern_ = off, font->kern_size_ = len; break;  // kern
            case 0x43464620: cff_table = off, cff_size = len; break;             // CFF table
            case 0x47504F53: gpos = off, gpos_size = len; break;                 // GPOS
            case 0x47535542: gsub = off, gsub_size = len; break;                 // GSUB
            case 0x47444546: gdef = off, gdef_size = len; break;                 // GDEF
            case 0x66766172: fvar = off, fvar_size = len; break;                 // fvar
            case 0x61766172: avar = off, avar_size = len; break;                 // avar
            case 0x67766172: gvar = off, gvar_size = len; break;                 // gvar
            case 0x48564152: hvar = off, hvar_size = len; break;                 // HVAR
            default: break;
        }
    }
    if (head == 0 || hhea == 0 || maxp == 0 || cmap == 0 || hmtx == 0 || (cff ? cff_table == 0 : (font->loca_ == 0 || font->glyf_ == 0))) {
        error = cff ? "font is missing a required table (head/hhea/maxp/cmap/hmtx/CFF)" : "font is missing a required table (head/hhea/maxp/cmap/hmtx/loca/glyf)";
        return nullptr;
    }
    font->units_per_em_ = r.u16(head + 18);
    font->long_loca_ = r.i16(head + 50) == 1;
    font->ascender_ = r.i16(hhea + 4);
    font->descender_ = r.i16(hhea + 6);
    font->line_gap_ = r.i16(hhea + 8);
    font->long_hor_metrics_ = r.u16(hhea + 34);
    font->glyph_count_ = r.u16(maxp + 4);
    if (!r.ok || font->units_per_em_ < 16 || font->units_per_em_ > 16384 || font->long_hor_metrics_ == 0 ||
        static_cast<std::size_t>(font->long_hor_metrics_) * 4 > hmtx_size || font->glyph_count_ == 0 ||
        (!cff && font->loca_size_ < (static_cast<std::size_t>(font->glyph_count_) + 1) * (font->long_loca_ ? 4U : 2U))) {
        error = "font header, metrics or glyph index is inconsistent";
        return nullptr;
    }
    font->hmtx_ = hmtx;
    if (!cff && fvar != 0) {
        const std::span<const std::uint8_t> all(font->data_);
        font->variations_ = FontVariations::parse(all.subspan(fvar, fvar_size), avar != 0 ? all.subspan(avar, avar_size) : std::span<const std::uint8_t>{},
                                                  gvar != 0 ? all.subspan(gvar, gvar_size) : std::span<const std::uint8_t>{},
                                                  hvar != 0 ? all.subspan(hvar, hvar_size) : std::span<const std::uint8_t>{}, font->glyph_count_);
    }
    if (gpos != 0) {
        const std::span<const std::uint8_t> all(font->data_);
        font->gpos_ = GposKerning::parse(all.subspan(gpos, gpos_size));
        font->marks_ = GposMarks::parse(all.subspan(gpos, gpos_size), gdef != 0 ? all.subspan(gdef, gdef_size) : std::span<const std::uint8_t>{});
    }
    if (gsub != 0) {
        font->gsub_ = GsubTable::parse(std::span<const std::uint8_t>(font->data_).subspan(gsub, gsub_size));
        if (font->gsub_) {
            font->isol_ = font->gsub_->lookups(0x69736F6CU);  // isol
            font->init_ = font->gsub_->lookups(0x696E6974U);  // init
            font->medi_ = font->gsub_->lookups(0x6D656469U);  // medi
            font->fina_ = font->gsub_->lookups(0x66696E61U);  // fina
            font->rlig_ = font->gsub_->lookups(0x726C6967U);  // rlig
            font->liga_ = font->gsub_->lookups(0x6C696761U);  // liga
        }
    }
    if (cff) {
        font->cff_ = CffOutlines::parse(std::span<const std::uint8_t>(font->data_).subspan(cff_table, cff_size), error);
        if (!font->cff_) return nullptr;
        font->glyph_count_ = static_cast<std::uint16_t>(std::min<std::size_t>(font->glyph_count_, font->cff_->glyphCount()));
    }
    // cmap: prefer full Unicode (format 12), then BMP (format 4).
    const std::uint16_t subtables = r.u16(cmap + 2);
    int best = -1;
    for (std::uint16_t k = 0; k < subtables && k < 256; ++k) {
        const std::size_t rec = cmap + 4 + static_cast<std::size_t>(k) * 8;
        const std::uint16_t platform = r.u16(rec), encoding = r.u16(rec + 2);
        const std::size_t off = cmap + r.u32(rec + 4);
        const std::uint16_t format = r.u16(off);
        if (!r.ok) break;
        int score = -1;
        if (format == 12 && ((platform == 3 && encoding == 10) || platform == 0)) score = 3;
        else if (format == 4 && ((platform == 3 && encoding == 1) || platform == 0)) score = 2;
        if (score > best) {
            best = score;
            font->cmap_ = off;
            font->cmap_format_ = format;
        }
    }
    if (best < 0) {
        error = "font has no Unicode character map (cmap format 4 or 12)";
        return nullptr;
    }
    return font;
}

std::uint16_t TrueTypeFont::glyphFor(char32_t cp) const {
    Reader r{data_};
    if (cmap_format_ == 12) {
        const std::uint32_t groups = r.u32(cmap_ + 12);
        std::uint32_t lo = 0, hi = std::min<std::uint32_t>(groups, static_cast<std::uint32_t>((data_.size() - std::min(data_.size(), cmap_ + 16)) / 12));
        while (lo < hi) {
            const std::uint32_t mid = lo + (hi - lo) / 2;
            const std::size_t g = cmap_ + 16 + static_cast<std::size_t>(mid) * 12;
            const std::uint32_t start = r.u32(g), end = r.u32(g + 4);
            if (!r.ok) return 0;
            if (cp < start) hi = mid;
            else if (cp > end) lo = mid + 1;
            else {
                const std::uint32_t glyph = r.u32(g + 8) + (cp - start);
                return glyph < glyph_count_ ? static_cast<std::uint16_t>(glyph) : 0;
            }
        }
        return 0;
    }
    if (cp > 0xFFFF) return 0;
    const std::uint16_t seg_x2 = r.u16(cmap_ + 6);
    const std::size_t segs = seg_x2 / 2U, ends = cmap_ + 14, starts = ends + seg_x2 + 2, deltas = starts + seg_x2, ranges = deltas + seg_x2;
    std::size_t lo = 0, hi = segs;
    while (lo < hi) {  // first segment whose end ≥ cp
        const std::size_t mid = lo + (hi - lo) / 2;
        if (r.u16(ends + mid * 2) < cp) lo = mid + 1;
        else hi = mid;
    }
    if (lo >= segs || !r.ok) return 0;
    const std::uint16_t start = r.u16(starts + lo * 2), delta = r.u16(deltas + lo * 2), range = r.u16(ranges + lo * 2);
    if (cp < start || !r.ok) return 0;
    std::uint16_t glyph;
    if (range == 0) {
        glyph = static_cast<std::uint16_t>(cp + delta);
    } else {
        const std::size_t at = ranges + lo * 2 + range + (cp - start) * 2;
        glyph = r.u16(at);
        if (!r.ok) return 0;
        if (glyph != 0) glyph = static_cast<std::uint16_t>(glyph + delta);
    }
    return glyph < glyph_count_ ? glyph : 0;
}

std::uint16_t TrueTypeFont::advance(std::uint16_t glyph) const {
    Reader r{data_};
    const std::uint16_t index = glyph < long_hor_metrics_ ? glyph : static_cast<std::uint16_t>(long_hor_metrics_ - 1);
    const std::uint16_t base = r.u16(hmtx_ + static_cast<std::size_t>(index) * 4);
    if (!variations_ || coords_.empty() || glyph >= glyph_count_) return base;
    const double delta = variations_->advanceDelta(glyph, coords_, glyphPointCount(glyph) + 4);
    return static_cast<std::uint16_t>(std::clamp<long>(std::lround(base + delta), 0L, 65535L));
}

const std::vector<VariationAxis>& TrueTypeFont::variationAxes() const {
    static const std::vector<VariationAxis> none;
    return variations_ ? variations_->axes() : none;
}

std::shared_ptr<const TrueTypeFont> TrueTypeFont::withVariation(std::span<const std::pair<std::string, float>> axes) const {
    std::shared_ptr<TrueTypeFont> copy(new TrueTypeFont(*this));
    copy->coords_ = variations_ ? variations_->normalize(axes) : std::vector<float>{};
    if (std::all_of(copy->coords_.begin(), copy->coords_.end(), [](float c) { return c == 0.0F; })) copy->coords_.clear();
    return copy;
}

std::size_t TrueTypeFont::glyphPointCount(std::uint16_t glyph) const {
    if (cff_ || glyph >= glyph_count_) return 0;
    Reader r{data_};
    const std::size_t begin = long_loca_ ? r.u32(loca_ + static_cast<std::size_t>(glyph) * 4) : static_cast<std::size_t>(r.u16(loca_ + static_cast<std::size_t>(glyph) * 2)) * 2;
    const std::size_t end = long_loca_ ? r.u32(loca_ + static_cast<std::size_t>(glyph + 1) * 4) : static_cast<std::size_t>(r.u16(loca_ + static_cast<std::size_t>(glyph + 1) * 2)) * 2;
    if (!r.ok || end <= begin || end > glyf_size_ || end - begin < 10) return 0;
    const std::size_t g = glyf_ + begin;
    const std::int16_t contours = r.i16(g);
    if (contours >= 0) {
        if (contours == 0) return 0;
        const std::size_t last = r.u16(g + 10 + (static_cast<std::size_t>(contours) - 1) * 2);
        return r.ok ? last + 1 : 0;
    }
    std::size_t at = g + 10, count = 0;
    for (int component = 0; component < 256; ++component) {
        const std::uint16_t flags = r.u16(at);
        at += 4 + ((flags & 0x0001) != 0 ? 4 : 2);
        at += (flags & 0x0008) != 0 ? 2 : (flags & 0x0040) != 0 ? 4 : (flags & 0x0080) != 0 ? 8 : 0;
        ++count;
        if (!r.ok || (flags & 0x0020) == 0) break;
    }
    return r.ok ? count : 0;
}

std::int16_t TrueTypeFont::kerning(std::uint16_t left, std::uint16_t right) const {
    if (gpos_) return gpos_->adjust(left, right);
    if (kern_ == 0 || kern_size_ < 4) return 0;
    Reader r{data_};
    if (r.u16(kern_) != 0) return 0;  // Microsoft version-0 table only
    const std::uint16_t count = r.u16(kern_ + 2);
    std::size_t at = kern_ + 4;
    for (std::uint16_t t = 0; t < count && t < 64; ++t) {
        const std::uint16_t length = r.u16(at + 2), coverage = r.u16(at + 4);
        if (!r.ok || length < 6) return 0;
        if ((coverage >> 8) == 0 && (coverage & 0x1) != 0) {  // format 0, horizontal
            const std::uint16_t pairs = r.u16(at + 6);
            const std::uint32_t key = static_cast<std::uint32_t>(left) << 16 | right;
            std::size_t lo = 0, hi = pairs;
            while (lo < hi) {
                const std::size_t mid = lo + (hi - lo) / 2;
                const std::size_t p = at + 14 + mid * 6;
                const std::uint32_t k = r.u32(p);
                if (!r.ok) return 0;
                if (k < key) lo = mid + 1;
                else if (k > key) hi = mid;
                else return r.i16(p + 4);
            }
            return 0;
        }
        at += length;
    }
    return 0;
}

vector::Path TrueTypeFont::outline(std::uint16_t glyph) const {
    vector::Path path;
    if (glyph >= glyph_count_) return path;
    if (cff_) {
        if (!cff_->outline(glyph, path)) return {};
        return path;
    }
    appendOutline(path, glyph, {1, 0, 0, 1, 0, 0}, 0);
    return path;
}

void TrueTypeFont::appendOutline(vector::Path& path, std::uint16_t glyph, const Affine& m, int depth) const {
    if (glyph >= glyph_count_ || depth > kMaxCompoundDepth) return;
    Reader r{data_};
    const std::size_t begin = long_loca_ ? r.u32(loca_ + static_cast<std::size_t>(glyph) * 4) : static_cast<std::size_t>(r.u16(loca_ + static_cast<std::size_t>(glyph) * 2)) * 2;
    const std::size_t end = long_loca_ ? r.u32(loca_ + static_cast<std::size_t>(glyph + 1) * 4) : static_cast<std::size_t>(r.u16(loca_ + static_cast<std::size_t>(glyph + 1) * 2)) * 2;
    if (!r.ok || end <= begin || end > glyf_size_ || end - begin < 10) return;  // empty glyph (space) or corrupt
    const std::size_t g = glyf_ + begin, limit = glyf_ + end;
    const std::int16_t contours = r.i16(g);
    if (contours >= 0) {
        // Simple glyph.
        std::vector<std::uint16_t> end_points(static_cast<std::size_t>(contours));
        std::size_t at = g + 10;
        for (auto& e : end_points) {
            e = r.u16(at);
            at += 2;
        }
        if (!r.ok || at > limit) return;
        for (std::size_t k = 1; k < end_points.size(); ++k)
            if (end_points[k] <= end_points[k - 1]) return;
        const std::size_t points = end_points.empty() ? 0 : static_cast<std::size_t>(end_points.back()) + 1;
        const std::uint16_t instructions = r.u16(at);
        at += 2 + instructions;
        if (!r.ok || at > limit || points == 0) return;
        std::vector<std::uint8_t> flags(points);
        for (std::size_t k = 0; k < points;) {
            const std::uint8_t f = r.u8(at++);
            flags[k++] = f;
            if ((f & 0x08) != 0) {
                std::uint8_t repeat = r.u8(at++);
                while (repeat-- > 0 && k < points) flags[k++] = f;
            }
            if (!r.ok || at > limit) return;
        }
        std::vector<double> xs(points), ys(points);
        std::int32_t v = 0;
        for (std::size_t k = 0; k < points; ++k) {
            const std::uint8_t f = flags[k];
            if ((f & 0x02) != 0) {
                const std::uint8_t d = r.u8(at++);
                v += (f & 0x10) != 0 ? d : -static_cast<std::int32_t>(d);
            } else if ((f & 0x10) == 0) {
                v += r.i16(at);
                at += 2;
            }
            xs[k] = v;
        }
        v = 0;
        for (std::size_t k = 0; k < points; ++k) {
            const std::uint8_t f = flags[k];
            if ((f & 0x04) != 0) {
                const std::uint8_t d = r.u8(at++);
                v += (f & 0x20) != 0 ? d : -static_cast<std::int32_t>(d);
            } else if ((f & 0x20) == 0) {
                v += r.i16(at);
                at += 2;
            }
            ys[k] = v;
        }
        if (!r.ok || at > limit) return;
        if (variations_ && !coords_.empty()) {
            std::vector<double> dx(points + 4, 0.0), dy(points + 4, 0.0);
            const VariationOutline shape{end_points, xs, ys};
            if (variations_->glyphDeltas(glyph, coords_, points + 4, &shape, dx, dy)) {
                for (std::size_t k = 0; k < points; ++k) {
                    xs[k] += dx[k];
                    ys[k] += dy[k];
                }
            }
        }
        // Quadratic B-spline contours: consecutive off-curve points imply an on-curve midpoint.
        std::size_t first = 0;
        for (const std::uint16_t last_point : end_points) {
            const std::size_t last = last_point, count = last - first + 1;
            const auto on = [&](std::size_t i) { return (flags[i] & 0x01) != 0; };
            const auto pt = [&](std::size_t i) { return apply(m, xs[i], ys[i]); };
            const auto mid = [&](std::size_t a, std::size_t b) { return apply(m, (xs[a] + xs[b]) / 2, (ys[a] + ys[b]) / 2); };
            if (count >= 2) {
                // Start on an on-curve point; with none at either end, at the implied midpoint of last/first.
                vector::Point start_point;
                std::vector<std::size_t> sequence;
                if (on(first)) {
                    start_point = pt(first);
                    for (std::size_t i = first + 1; i <= last; ++i) sequence.push_back(i);
                } else if (on(last)) {
                    start_point = pt(last);
                    for (std::size_t i = first; i < last; ++i) sequence.push_back(i);
                } else {
                    start_point = mid(last, first);
                    for (std::size_t i = first; i <= last; ++i) sequence.push_back(i);
                }
                path.moveTo(start_point);
                std::optional<std::size_t> control;
                for (const std::size_t i : sequence) {
                    if (on(i)) {
                        if (control) path.quadTo(pt(*control), pt(i));
                        else path.lineTo(pt(i));
                        control.reset();
                    } else {
                        if (control) path.quadTo(pt(*control), mid(*control, i));
                        control = i;
                    }
                }
                if (control) path.quadTo(pt(*control), start_point);
                path.close();
            }
            first = last + 1;
        }
        return;
    }
    // Compound glyph.
    std::size_t at = g + 10;
    std::vector<double> component_dx, component_dy;
    if (variations_ && !coords_.empty()) {
        const std::size_t components = glyphPointCount(glyph);
        component_dx.assign(components + 4, 0.0);
        component_dy.assign(components + 4, 0.0);
        if (!variations_->glyphDeltas(glyph, coords_, components + 4, nullptr, component_dx, component_dy)) component_dx.clear(), component_dy.clear();
    }
    for (int component = 0; component < 256; ++component) {
        const std::uint16_t flags = r.u16(at), index = r.u16(at + 2);
        at += 4;
        double dx = 0, dy = 0;
        if ((flags & 0x0001) != 0) {  // ARG_1_AND_2_ARE_WORDS
            dx = r.i16(at);
            dy = r.i16(at + 2);
            at += 4;
        } else {
            dx = static_cast<std::int8_t>(r.u8(at));
            dy = static_cast<std::int8_t>(r.u8(at + 1));
            at += 2;
        }
        if ((flags & 0x0002) == 0) dx = dy = 0;  // point-matching placement is not supported
        if (static_cast<std::size_t>(component) < component_dx.size()) {
            dx += component_dx[static_cast<std::size_t>(component)];
            dy += component_dy[static_cast<std::size_t>(component)];
        }
        double a = 1, b = 0, c = 0, d = 1;
        const auto f2dot14 = [&](std::size_t p) { return r.i16(p) / 16384.0; };
        if ((flags & 0x0008) != 0) {
            a = d = f2dot14(at);
            at += 2;
        } else if ((flags & 0x0040) != 0) {
            a = f2dot14(at);
            d = f2dot14(at + 2);
            at += 4;
        } else if ((flags & 0x0080) != 0) {
            a = f2dot14(at);
            b = f2dot14(at + 2);
            c = f2dot14(at + 4);
            d = f2dot14(at + 6);
            at += 8;
        }
        if (!r.ok || at > limit) return;
        appendOutline(path, index, compose(m, {a, b, c, d, dx, dy}), depth + 1);
        if ((flags & 0x0020) == 0) break;  // MORE_COMPONENTS
    }
}

void TrueTypeFont::mapAndSubstitute(std::u32string_view run, std::vector<std::uint16_t>& glyphs, std::vector<std::uint32_t>& clusters) const {
    glyphs.resize(run.size());
    clusters.resize(run.size());
    for (std::uint32_t i = 0; i < run.size(); ++i) {
        glyphs[i] = glyphFor(run[i]);
        clusters[i] = i;
    }
    if (!gsub_) return;
    const auto forms = arabicForms(run);
    for (std::size_t i = 0; i < glyphs.size(); ++i) {
        const std::vector<std::uint16_t>* lookups = nullptr;
        switch (forms[i]) {
            case ArabicForm::Isolated: lookups = &isol_; break;
            case ArabicForm::Initial: lookups = &init_; break;
            case ArabicForm::Medial: lookups = &medi_; break;
            case ArabicForm::Final: lookups = &fina_; break;
            case ArabicForm::None: break;
        }
        if (lookups != nullptr && !lookups->empty()) glyphs[i] = gsub_->substituteSingle(*lookups, glyphs[i]);
    }
    if (!rlig_.empty()) gsub_->substituteLigatures(rlig_, glyphs, clusters);
    if (!liga_.empty()) gsub_->substituteLigatures(liga_, glyphs, clusters);
    for (auto& g : glyphs)
        if (g >= glyph_count_) g = 0;
}

std::vector<ShapedGlyph> TrueTypeShaper::shape(std::u32string_view run, float size_px) const {
    std::vector<std::uint16_t> glyphs;
    std::vector<std::uint32_t> clusters;
    font_->mapAndSubstitute(run, glyphs, clusters);
    std::vector<ShapedGlyph> out;
    out.reserve(glyphs.size());
    const float scale = size_px / static_cast<float>(font_->unitsPerEm());
    const GposMarks* marks = font_->marks();
    for (std::size_t i = 0; i < glyphs.size(); ++i) {
        const bool is_mark = marks != nullptr && marks->isMark(glyphs[i]);
        if (!out.empty() && !is_mark) out.back().advance += static_cast<float>(font_->kerning(static_cast<std::uint16_t>(out.back().glyph), glyphs[i])) * scale;
        out.push_back({glyphs[i], clusters[i], static_cast<float>(font_->advance(glyphs[i])) * scale});
    }
    if (marks == nullptr) return out;
    // Mark attachment: each mark attaches to the preceding base (or the preceding mark for mark-to-mark)
    // and does not advance.
    for (std::size_t i = 1; i < out.size(); ++i) {
        const auto mark = static_cast<std::uint16_t>(out[i].glyph);
        if (!marks->isMark(mark)) continue;
        std::optional<std::pair<Anchor, Anchor>> anchors;
        std::size_t target = i - 1;
        if (marks->isMark(static_cast<std::uint16_t>(out[target].glyph))) anchors = marks->markToMark(mark, static_cast<std::uint16_t>(out[target].glyph));
        if (!anchors) {
            std::size_t j = i;
            while (j > 0 && marks->isMark(static_cast<std::uint16_t>(out[j - 1].glyph))) --j;
            if (j == 0) continue;
            target = j - 1;
            anchors = marks->markToBase(mark, static_cast<std::uint16_t>(out[target].glyph));
        }
        if (!anchors) continue;
        // Offsets relative to the target glyph's origin; layout resolves them after (bidi) placement.
        out[i].x_offset = static_cast<float>(anchors->second.x - anchors->first.x) * scale;
        out[i].y_offset = static_cast<float>(anchors->second.y - anchors->first.y) * scale;
        out[i].attach_back = static_cast<std::uint16_t>(std::min<std::size_t>(i - target, 0xFFFF));
        out[i].advance = 0;
    }
    return out;
}

float TrueTypeShaper::ascent(float size_px) const { return static_cast<float>(font_->ascender()) * size_px / static_cast<float>(font_->unitsPerEm()); }
float TrueTypeShaper::descent(float size_px) const { return -static_cast<float>(font_->descender()) * size_px / static_cast<float>(font_->unitsPerEm()); }

std::vector<ShapedGlyph> FallbackShaper::shape(std::u32string_view run, float size_px) const {
    std::vector<ShapedGlyph> out;
    if (fonts_.empty()) return out;
    out.reserve(run.size());
    std::uint32_t previous_font = UINT32_MAX;
    for (std::uint32_t i = 0; i < run.size(); ++i) {
        std::uint32_t chosen = 0;
        std::uint16_t glyph = 0;
        for (std::uint32_t f = 0; f < fonts_.size() && f < 0xFFFF; ++f) {
            if (!fonts_[f]) continue;
            glyph = fonts_[f]->glyphFor(run[i]);
            if (glyph != 0) {
                chosen = f;
                break;
            }
        }
        const auto& font = *fonts_[chosen];
        const float scale = size_px / static_cast<float>(font.unitsPerEm());
        if (!out.empty() && previous_font == chosen) {
            out.back().advance += static_cast<float>(font.kerning(static_cast<std::uint16_t>(out.back().glyph & 0xFFFFU), glyph)) * scale;
        }
        out.push_back({chosen << 16U | glyph, i, static_cast<float>(font.advance(glyph)) * scale});
        previous_font = chosen;
    }
    return out;
}

float FallbackShaper::ascent(float size_px) const {
    float a = 0;
    for (const auto& f : fonts_)
        if (f) a = std::max(a, static_cast<float>(f->ascender()) * size_px / static_cast<float>(f->unitsPerEm()));
    return a;
}

float FallbackShaper::descent(float size_px) const {
    float d = 0;
    for (const auto& f : fonts_)
        if (f) d = std::max(d, -static_cast<float>(f->descender()) * size_px / static_cast<float>(f->unitsPerEm()));
    return d;
}

namespace {

bool validRender(const TileStore& layer, const std::array<float, 4>& color, double origin_x, double origin_y, std::string& error) {
    if (!paint::detail::isRgba(layer.format())) {
        error = "text renders into an RGBA layer";
        return false;
    }
    if (std::any_of(color.begin(), color.end(), [](float v) { return !std::isfinite(v) || v < 0.0F || v > 1.0F; }) || !std::isfinite(origin_x) || !std::isfinite(origin_y)) {
        error = "text colour must be 0–1 and the origin finite";
        return false;
    }
    return true;
}

void appendGlyph(vector::Path& all, const TrueTypeFont& font, std::uint16_t glyph, const Line& line, const PlacedGlyph& g, double origin_x, double origin_y) {
    const double s = g.size / font.unitsPerEm();
    const double x0 = origin_x + g.x, y0 = origin_y + line.baseline + g.y_offset;
    all.append(font.outline(glyph).transformed({s, 0, x0, 0, -s, y0, 0, 0, 1}));
}

}  // namespace

bool renderText(TileStore& layer, const Layout& layout, const std::vector<std::shared_ptr<const TrueTypeFont>>& fonts, std::array<float, 4> color, double origin_x,
                double origin_y, std::string& error) {
    if (!validRender(layer, color, origin_x, origin_y, error)) return false;
    vector::Path all;
    for (const auto& line : layout.lines) {
        for (const auto& g : line.glyphs) {
            const std::uint32_t f = g.glyph >> 16U;
            if (f < fonts.size() && fonts[f]) appendGlyph(all, *fonts[f], static_cast<std::uint16_t>(g.glyph & 0xFFFFU), line, g, origin_x, origin_y);
        }
    }
    return text::fillPath(layer, all, color, error);
}

bool renderText(TileStore& layer, const Layout& layout, const TrueTypeFont& font, std::array<float, 4> color, double origin_x, double origin_y, std::string& error) {
    if (!validRender(layer, color, origin_x, origin_y, error)) return false;
    vector::Path all;
    for (const auto& line : layout.lines)
        for (const auto& g : line.glyphs) appendGlyph(all, font, static_cast<std::uint16_t>(g.glyph & 0xFFFFU), line, g, origin_x, origin_y);
    return text::fillPath(layer, all, color, error);
}

}  // namespace croptop::text
