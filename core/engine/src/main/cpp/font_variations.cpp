// TrueType variable fonts; see font_variations.h.
#include "font_variations.h"

#include <algorithm>
#include <cmath>

namespace croptop::text {
namespace {

constexpr std::size_t kMaxAxes = 64;
constexpr std::size_t kMaxTuples = 4095;
constexpr std::size_t kMaxPoints = 65535 + 4;

struct Bytes {
    std::span<const std::uint8_t> d;
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
    float f2dot14(std::size_t at) { return static_cast<float>(i16(at)) / 16384.0F; }
    float fixed(std::size_t at) { return static_cast<float>(static_cast<std::int32_t>(u32(at))) / 65536.0F; }
};

/** Packed point numbers (gvar): `all` is true for the "every point" marker (a zero count). */
bool readPackedPoints(Bytes& b, std::size_t& pos, bool& all, std::vector<std::uint32_t>& points) {
    all = false;
    points.clear();
    std::uint32_t count = b.u8(pos++);
    if (count == 0) {
        all = true;
        return b.ok;
    }
    if ((count & 0x80U) != 0) count = ((count & 0x7FU) << 8U) | b.u8(pos++);
    if (count > kMaxPoints) return false;
    points.reserve(count);
    std::uint32_t point = 0;
    while (points.size() < count && b.ok) {
        const std::uint8_t control = b.u8(pos++);
        const std::size_t run = (control & 0x7FU) + 1U;
        const bool words = (control & 0x80U) != 0;
        for (std::size_t k = 0; k < run && points.size() < count && b.ok; ++k) {
            if (words) {
                point += b.u16(pos);
                pos += 2;
            } else {
                point += b.u8(pos++);
            }
            points.push_back(point);
        }
    }
    return b.ok && points.size() == count;
}

/** Packed deltas (gvar): `count` signed values. */
bool readPackedDeltas(Bytes& b, std::size_t& pos, std::size_t count, std::vector<double>& out) {
    out.clear();
    out.reserve(count);
    while (out.size() < count && b.ok) {
        const std::uint8_t control = b.u8(pos++);
        const std::size_t run = (control & 0x3FU) + 1U;
        for (std::size_t k = 0; k < run && out.size() < count && b.ok; ++k) {
            if ((control & 0x80U) != 0) {
                out.push_back(0);
            } else if ((control & 0x40U) != 0) {
                out.push_back(b.i16(pos));
                pos += 2;
            } else {
                out.push_back(static_cast<std::int8_t>(b.u8(pos++)));
            }
        }
    }
    return b.ok && out.size() == count;
}

/** Interpolate Untouched Points along one axis of one contour [first, last] (inclusive), TrueType's IUP. */
void iupAxis(std::span<const double> original, std::vector<double>& delta, const std::vector<bool>& touched, std::size_t first, std::size_t last) {
    std::vector<std::size_t> pins;
    for (std::size_t i = first; i <= last; ++i) {
        if (touched[i]) pins.push_back(i);
    }
    if (pins.empty()) return;
    if (pins.size() == 1) {
        for (std::size_t i = first; i <= last; ++i) delta[i] = delta[pins[0]];
        return;
    }
    const std::size_t count = last - first + 1;
    for (std::size_t k = 0; k < pins.size(); ++k) {
        const std::size_t a = pins[k], b = pins[(k + 1) % pins.size()];
        const double xa = original[a], xb = original[b], da = delta[a], db = delta[b];
        for (std::size_t p = first + (a - first + 1) % count; p != b; p = first + (p - first + 1) % count) {
            const double xp = original[p];
            double d;
            if (xa == xb) {
                d = da == db ? da : 0.0;
            } else {
                const double lo = std::min(xa, xb), hi = std::max(xa, xb);
                const double dlo = xa <= xb ? da : db, dhi = xa <= xb ? db : da;
                if (xp <= lo) d = dlo;
                else if (xp >= hi) d = dhi;
                else d = dlo + (dhi - dlo) * (xp - lo) / (hi - lo);
            }
            delta[p] = d;
        }
    }
}

float tupleScalar(const std::vector<float>& coords, const std::vector<float>& peak, const std::vector<float>* start, const std::vector<float>* end) {
    float scalar = 1.0F;
    for (std::size_t axis = 0; axis < coords.size(); ++axis) {
        const float p = peak[axis];
        if (p == 0.0F) continue;
        const float c = coords[axis];
        const float s = start != nullptr ? (*start)[axis] : std::min(0.0F, p);
        const float e = end != nullptr ? (*end)[axis] : std::max(0.0F, p);
        if (c == p) continue;
        if (c <= s || c >= e) return 0.0F;
        scalar *= c < p ? (c - s) / (p - s) : (e - c) / (e - p);
    }
    return scalar;
}

/** One ItemVariationStore delta for (outer, inner) at `coords`, in font units. */
double itemDelta(Bytes& b, std::size_t store, std::uint32_t outer, std::uint32_t inner, const std::vector<float>& coords) {
    const std::uint16_t data_count = b.u16(store + 6);
    if (!b.ok || outer >= data_count) return 0.0;
    const std::size_t regions = store + b.u32(store + 2);
    const std::size_t data = store + b.u32(store + 8 + 4U * outer);
    const std::uint16_t axis_count = b.u16(regions);
    const std::uint16_t region_count = b.u16(regions + 2);
    const std::uint16_t item_count = b.u16(data);
    const std::uint16_t word_count_field = b.u16(data + 2);
    const std::uint16_t region_index_count = b.u16(data + 4);
    if (!b.ok || axis_count != coords.size() || inner >= item_count) return 0.0;
    const bool long_words = (word_count_field & 0x8000U) != 0;
    const std::size_t word_count = word_count_field & 0x7FFFU;
    if (word_count > region_index_count) return 0.0;
    const std::size_t entry_size = long_words ? 4 : 2, small_size = long_words ? 2 : 1;
    const std::size_t row_size = word_count * entry_size + (region_index_count - word_count) * small_size;
    const std::size_t row = data + 6 + 2U * region_index_count + static_cast<std::size_t>(inner) * row_size;
    double total = 0.0;
    for (std::size_t k = 0; k < region_index_count; ++k) {
        const std::uint16_t region = b.u16(data + 6 + 2 * k);
        if (region >= region_count) continue;
        double delta;
        if (k < word_count) {
            delta = long_words ? static_cast<std::int32_t>(b.u32(row + k * 4)) : b.i16(row + k * 2);
        } else {
            const std::size_t at = row + word_count * entry_size + (k - word_count) * small_size;
            delta = long_words ? b.i16(at) : static_cast<std::int8_t>(b.u8(at));
        }
        double scalar = 1.0;
        for (std::size_t axis = 0; axis < axis_count; ++axis) {
            const std::size_t at = regions + 4 + (static_cast<std::size_t>(region) * axis_count + axis) * 6;
            const float start = b.f2dot14(at), peak = b.f2dot14(at + 2), end = b.f2dot14(at + 4);
            if (peak == 0.0F || start > peak || peak > end) continue;
            const float c = coords[axis];
            if (c == peak) continue;
            if (c < start || c > end) {
                scalar = 0.0;
                break;
            }
            scalar *= c < peak ? (c - start) / (peak - start) : (end - c) / (end - peak);
        }
        total += delta * scalar;
    }
    return b.ok ? total : 0.0;
}

}  // namespace

std::shared_ptr<const FontVariations> FontVariations::parse(std::span<const std::uint8_t> fvar, std::span<const std::uint8_t> avar,
                                                            std::span<const std::uint8_t> gvar, std::span<const std::uint8_t> hvar,
                                                            std::uint16_t glyph_count) {
    if (fvar.empty()) return nullptr;
    Bytes f{fvar};
    const std::size_t axes_offset = f.u16(4);
    const std::size_t axis_count = f.u16(8);
    const std::size_t axis_size = f.u16(10);
    if (!f.ok || axis_count == 0 || axis_count > kMaxAxes || axis_size < 20) return nullptr;
    std::shared_ptr<FontVariations> out(new FontVariations());
    out->glyph_count_ = glyph_count;
    for (std::size_t a = 0; a < axis_count; ++a) {
        const std::size_t at = axes_offset + a * axis_size;
        VariationAxis axis;
        for (std::size_t c = 0; c < 4; ++c) axis.tag.push_back(static_cast<char>(f.u8(at + c)));
        axis.min = f.fixed(at + 4);
        axis.def = f.fixed(at + 8);
        axis.max = f.fixed(at + 12);
        if (!f.ok || !(axis.min <= axis.def && axis.def <= axis.max)) return nullptr;
        out->axes_.push_back(std::move(axis));
    }
    out->segment_maps_.assign(axis_count, {});
    if (!avar.empty()) {
        Bytes v{avar};
        if (v.u16(0) == 1 && v.u16(6) == axis_count) {
            std::size_t at = 8;
            for (std::size_t a = 0; a < axis_count && v.ok; ++a) {
                const std::uint16_t pairs = v.u16(at);
                at += 2;
                std::vector<std::pair<float, float>> map;
                for (std::uint16_t k = 0; k < pairs && v.ok; ++k) {
                    map.emplace_back(v.f2dot14(at), v.f2dot14(at + 2));
                    at += 4;
                }
                if (v.ok) out->segment_maps_[a] = std::move(map);
            }
            if (!v.ok) out->segment_maps_.assign(axis_count, {});
        }
    }
    if (!gvar.empty()) {
        Bytes g{gvar};
        if (g.u16(0) == 1 && g.u16(4) == axis_count && g.ok) out->gvar_.assign(gvar.begin(), gvar.end());
    }
    if (!hvar.empty()) {
        Bytes h{hvar};
        if (h.u16(0) == 1 && h.ok) out->hvar_.assign(hvar.begin(), hvar.end());
    }
    return out;
}

std::vector<float> FontVariations::normalize(std::span<const std::pair<std::string, float>> user) const {
    std::vector<float> coords(axes_.size(), 0.0F);
    for (std::size_t a = 0; a < axes_.size(); ++a) {
        const auto& axis = axes_[a];
        float value = axis.def;
        for (const auto& [tag, requested] : user) {
            if (tag == axis.tag && std::isfinite(requested)) value = std::clamp(requested, axis.min, axis.max);
        }
        float n = 0.0F;
        if (value < axis.def && axis.def > axis.min) n = (value - axis.def) / (axis.def - axis.min);
        else if (value > axis.def && axis.max > axis.def) n = (value - axis.def) / (axis.max - axis.def);
        const auto& map = segment_maps_[a];
        if (map.size() >= 2) {
            if (n <= map.front().first) {
                n = map.front().second;
            } else if (n >= map.back().first) {
                n = map.back().second;
            } else {
                for (std::size_t k = 0; k + 1 < map.size(); ++k) {
                    const auto [f0, t0] = map[k];
                    const auto [f1, t1] = map[k + 1];
                    if (n >= f0 && n <= f1) {
                        n = f1 == f0 ? t0 : t0 + (t1 - t0) * (n - f0) / (f1 - f0);
                        break;
                    }
                }
            }
        }
        coords[a] = std::clamp(n, -1.0F, 1.0F);
    }
    return coords;
}

bool FontVariations::glyphDeltas(std::uint16_t glyph, const std::vector<float>& coords, std::size_t point_count, const VariationOutline* outline,
                                 std::vector<double>& dx, std::vector<double>& dy) const {
    if (gvar_.empty() || glyph >= glyph_count_ || coords.size() != axes_.size() || dx.size() < point_count || dy.size() < point_count) return false;
    Bytes b{gvar_};
    const std::size_t axis_count = b.u16(4), shared_count = b.u16(6), shared_offset = b.u32(8);
    const std::size_t flags = b.u16(14), array_offset = b.u32(16);
    const bool long_offsets = (flags & 1U) != 0;
    const auto offset_at = [&](std::size_t index) -> std::size_t {
        return long_offsets ? b.u32(20 + index * 4) : static_cast<std::size_t>(b.u16(20 + index * 2)) * 2;
    };
    const std::size_t begin = array_offset + offset_at(glyph), end = array_offset + offset_at(static_cast<std::size_t>(glyph) + 1);
    if (!b.ok || end <= begin || end > gvar_.size() || axis_count != axes_.size()) return false;
    const std::uint16_t tuple_word = b.u16(begin);
    const std::size_t tuple_count = tuple_word & 0x0FFFU, data_offset = b.u16(begin + 2);
    if (!b.ok || tuple_count == 0 || tuple_count > kMaxTuples) return false;
    std::size_t header = begin + 4;
    std::size_t cursor = begin + data_offset;
    bool shared_all = true;
    std::vector<std::uint32_t> shared_points;
    if ((tuple_word & 0x8000U) != 0 && !readPackedPoints(b, cursor, shared_all, shared_points)) return false;
    const bool has_shared_points = (tuple_word & 0x8000U) != 0;
    bool any = false;
    std::vector<double> xd, yd, tx, ty;
    std::vector<bool> touched;
    for (std::size_t t = 0; t < tuple_count && b.ok; ++t) {
        const std::size_t data_size = b.u16(header);
        const std::uint16_t index = b.u16(header + 2);
        header += 4;
        std::vector<float> peak(axis_count), start, finish;
        if ((index & 0x8000U) != 0) {
            for (std::size_t a = 0; a < axis_count; ++a, header += 2) peak[a] = b.f2dot14(header);
        } else {
            const std::size_t shared_index = index & 0x0FFFU;
            if (shared_index >= shared_count) return false;
            for (std::size_t a = 0; a < axis_count; ++a) peak[a] = b.f2dot14(shared_offset + (shared_index * axis_count + a) * 2);
        }
        if ((index & 0x4000U) != 0) {
            start.resize(axis_count);
            finish.resize(axis_count);
            for (std::size_t a = 0; a < axis_count; ++a, header += 2) start[a] = b.f2dot14(header);
            for (std::size_t a = 0; a < axis_count; ++a, header += 2) finish[a] = b.f2dot14(header);
        }
        if (!b.ok) return false;
        const std::size_t tuple_data = cursor;
        cursor += data_size;
        const float scalar = tupleScalar(coords, peak, start.empty() ? nullptr : &start, finish.empty() ? nullptr : &finish);
        if (scalar == 0.0F) continue;
        std::size_t pos = tuple_data;
        bool all = shared_all;
        std::vector<std::uint32_t> points = shared_points;
        if ((index & 0x2000U) != 0) {
            if (!readPackedPoints(b, pos, all, points)) return false;
        } else if (!has_shared_points) {
            all = true;
        }
        const std::size_t n = all ? point_count : points.size();
        if (!readPackedDeltas(b, pos, n, xd) || !readPackedDeltas(b, pos, n, yd)) return false;
        tx.assign(point_count, 0.0);
        ty.assign(point_count, 0.0);
        touched.assign(point_count, false);
        if (all) {
            for (std::size_t i = 0; i < point_count; ++i) {
                tx[i] = xd[i];
                ty[i] = yd[i];
                touched[i] = true;
            }
        } else {
            for (std::size_t k = 0; k < n; ++k) {
                if (points[k] < point_count) {
                    tx[points[k]] = xd[k];
                    ty[points[k]] = yd[k];
                    touched[points[k]] = true;
                }
            }
            if (outline != nullptr && outline->xs.size() == outline->ys.size() && outline->xs.size() + 4 <= point_count) {
                std::size_t first = 0;
                for (const std::uint16_t last : outline->contour_ends) {
                    if (last >= outline->xs.size() || last < first) break;
                    iupAxis(outline->xs, tx, touched, first, last);
                    iupAxis(outline->ys, ty, touched, first, last);
                    first = static_cast<std::size_t>(last) + 1;
                }
            }
        }
        for (std::size_t i = 0; i < point_count; ++i) {
            dx[i] += static_cast<double>(scalar) * tx[i];
            dy[i] += static_cast<double>(scalar) * ty[i];
        }
        any = true;
    }
    return b.ok && any;
}

double FontVariations::advanceDelta(std::uint16_t glyph, const std::vector<float>& coords, std::size_t point_count) const {
    if (coords.size() != axes_.size()) return 0.0;
    if (!hvar_.empty()) {
        Bytes h{hvar_};
        const std::size_t store = h.u32(4), mapping = h.u32(8);
        if (!h.ok || store == 0) return 0.0;
        std::uint32_t outer = 0, inner = glyph;
        if (mapping != 0) {
            const std::uint8_t format = h.u8(mapping), entry_format = h.u8(mapping + 1);
            const std::size_t map_count = format == 0 ? h.u16(mapping + 2) : h.u32(mapping + 2);
            const std::size_t entries = mapping + (format == 0 ? 4 : 6);
            const std::size_t entry_size = ((entry_format >> 4U) & 3U) + 1U, inner_bits = (entry_format & 0xFU) + 1U;
            if (map_count > 0) {
                const std::size_t i = std::min<std::size_t>(glyph, map_count - 1);
                std::uint32_t entry = 0;
                for (std::size_t k = 0; k < entry_size; ++k) entry = (entry << 8U) | h.u8(entries + i * entry_size + k);
                outer = entry >> inner_bits;
                inner = entry & ((1U << inner_bits) - 1U);
            }
        }
        return h.ok ? itemDelta(h, store, outer, inner, coords) : 0.0;
    }
    if (point_count < 4) return 0.0;
    std::vector<double> dx(point_count, 0.0), dy(point_count, 0.0);
    if (!glyphDeltas(glyph, coords, point_count, nullptr, dx, dy)) return 0.0;
    return dx[point_count - 3] - dx[point_count - 4];  // phantom points: left side bearing, then advance
}

}  // namespace croptop::text
