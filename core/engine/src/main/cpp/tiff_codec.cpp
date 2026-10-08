#include "tiff_codec.h"

#include <zlib.h>

#include <algorithm>
#include <array>
#include <bit>
#include <cmath>
#include <cstring>
#include <map>
#include <set>
#include <stdexcept>

#include "pixel_plane.h"
#include "resample.h"

namespace croptop::tiff {
namespace {

struct Truncated final : std::runtime_error {
    using std::runtime_error::runtime_error;
};

class Reader final {
public:
    Reader(std::span<const std::uint8_t> data, bool little) : data_(data), little_(little) {}

    std::span<const std::uint8_t> at(std::uint64_t offset, std::uint64_t size) const {
        if (offset > data_.size() || size > data_.size() - offset) throw Truncated("data lies outside the file");
        return data_.subspan(static_cast<std::size_t>(offset), static_cast<std::size_t>(size));
    }
    std::uint64_t uint(std::uint64_t offset, int bytes) const {
        const auto b = at(offset, static_cast<std::uint64_t>(bytes));
        std::uint64_t v = 0;
        for (int i = 0; i < bytes; ++i) {
            const auto byte = std::uint64_t{b[static_cast<std::size_t>(little_ ? bytes - 1 - i : i)]};
            v = (v << 8) | byte;
        }
        return v;
    }
    bool little() const { return little_; }
    std::size_t size() const { return data_.size(); }

private:
    std::span<const std::uint8_t> data_;
    bool little_;
};

struct Entry {
    std::uint16_t type = 0;
    std::uint64_t count = 0;
    std::uint64_t offset = 0;  // where the values are (inline or pointed-to)
};

int typeSize(std::uint16_t type) {
    switch (type) {
        case 1: case 2: case 6: case 7: return 1;
        case 3: case 8: return 2;
        case 4: case 9: case 11: case 13: return 4;
        case 5: case 10: case 12: case 16: case 17: case 18: return 8;
        default: return 0;
    }
}

class Ifd final {
public:
    Ifd(const Reader& r, std::uint64_t offset, bool big) : r_(r) {
        const std::uint64_t count = big ? r.uint(offset, 8) : r.uint(offset, 2);
        if (count > 4096) throw std::runtime_error("too many TIFF tags");
        const std::uint64_t entry_size = big ? 20 : 12, value_size = big ? 8 : 4;
        const std::uint64_t base = offset + (big ? 8 : 2);
        for (std::uint64_t i = 0; i < count; ++i) {
            const auto e = base + i * entry_size;
            Entry entry;
            const auto tag = static_cast<std::uint16_t>(r.uint(e, 2));
            entry.type = static_cast<std::uint16_t>(r.uint(e + 2, 2));
            entry.count = big ? r.uint(e + 4, 8) : r.uint(e + 4, 4);
            const int size = typeSize(entry.type);
            if (size == 0) continue;
            if (entry.count > (std::uint64_t{1} << 32)) throw std::runtime_error("tag value count out of range");
            const std::uint64_t bytes = entry.count * static_cast<std::uint64_t>(size);
            const auto value_at = e + 4 + (big ? 8 : 4);
            entry.offset = bytes <= value_size ? value_at : r.uint(value_at, big ? 8 : 4);
            entries_[tag] = entry;
        }
        next_ = big ? r.uint(base + count * entry_size, 8) : r.uint(base + count * entry_size, 4);
    }

    bool has(std::uint16_t tag) const { return entries_.count(tag) != 0; }
    std::uint64_t next() const { return next_; }

    std::vector<std::uint64_t> values(std::uint16_t tag, std::size_t max_count = 1U << 24) const {
        const auto it = entries_.find(tag);
        if (it == entries_.end()) return {};
        const auto& e = it->second;
        const int size = typeSize(e.type);
        if (e.count > max_count) throw std::runtime_error("tag has too many values");
        std::vector<std::uint64_t> out(static_cast<std::size_t>(e.count));
        r_.at(e.offset, e.count * static_cast<std::uint64_t>(size));
        for (std::size_t i = 0; i < out.size(); ++i) out[i] = r_.uint(e.offset + i * static_cast<std::uint64_t>(size), size == 8 && (e.type == 5 || e.type == 10) ? 4 : size);
        return out;
    }
    std::uint64_t value(std::uint16_t tag, std::uint64_t fallback) const {
        const auto v = values(tag, 64);
        return v.empty() ? fallback : v[0];
    }
    std::span<const std::uint8_t> bytes(std::uint16_t tag) const {
        const auto it = entries_.find(tag);
        if (it == entries_.end()) return {};
        return r_.at(it->second.offset, it->second.count * static_cast<std::uint64_t>(typeSize(it->second.type)));
    }

private:
    const Reader& r_;
    std::map<std::uint16_t, Entry> entries_;
    std::uint64_t next_ = 0;
};

void unpackBits(std::span<const std::uint8_t> in, std::uint8_t* out, std::size_t size) {
    std::size_t i = 0, o = 0;
    while (i < in.size() && o < size) {
        const auto n = static_cast<std::int8_t>(in[i++]);
        if (n >= 0) {
            const std::size_t run = std::min<std::size_t>(static_cast<std::size_t>(n) + 1U, size - o);
            if (i + run > in.size()) break;
            std::memcpy(out + o, in.data() + i, run);
            i += static_cast<std::size_t>(n) + 1U;
            o += run;
        } else if (n != -128) {
            if (i >= in.size()) break;
            const std::size_t run = std::min<std::size_t>(static_cast<std::size_t>(1 - n), size - o);
            std::memset(out + o, in[i++], run);
            o += run;
        }
    }
}

/** TIFF LZW (MSB-first codes, "early change"). Output beyond `size` is dropped; short data leaves zeros. */
void lzw(std::span<const std::uint8_t> in, std::uint8_t* out, std::size_t size) {
    if (in.size() >= 2 && in[0] == 0 && (in[1] & 0x01U) != 0) throw std::runtime_error("old-style LZW is not supported");
    struct Code {
        std::int32_t prefix;
        std::uint8_t first;
        std::uint8_t last;
        std::uint32_t length;
    };
    std::vector<Code> table(4096);
    for (std::uint32_t i = 0; i < 256; ++i) table[i] = {-1, static_cast<std::uint8_t>(i), static_cast<std::uint8_t>(i), 1};
    std::size_t bit = 0, o = 0;
    int width = 9;
    std::uint32_t next = 258;
    std::int32_t previous = -1;
    std::vector<std::uint8_t> scratch;
    const auto emit = [&](std::uint32_t code) {
        const auto length = table[code].length;
        scratch.resize(length);
        std::int32_t c = static_cast<std::int32_t>(code);
        for (std::size_t k = length; k-- > 0 && c >= 0;) {
            scratch[k] = table[static_cast<std::size_t>(c)].last;
            c = table[static_cast<std::size_t>(c)].prefix;
        }
        const std::size_t n = std::min<std::size_t>(length, size - std::min(size, o));
        std::memcpy(out + o, scratch.data(), n);
        o += length;
    };
    while (bit + static_cast<std::size_t>(width) <= in.size() * 8U && o < size) {
        std::uint32_t code = 0;
        for (int k = 0; k < width; ++k, ++bit) code = (code << 1) | ((in[bit / 8] >> (7 - bit % 8)) & 1U);
        if (code == 257) break;
        if (code == 256) {
            width = 9;
            next = 258;
            previous = -1;
            continue;
        }
        if (previous < 0) {
            if (code > 255) throw std::runtime_error("corrupt LZW data");
            emit(code);
            previous = static_cast<std::int32_t>(code);
            continue;
        }
        if (code < next) {
            emit(code);
            if (next < 4096) table[next] = {previous, table[static_cast<std::size_t>(previous)].first, table[code].first, table[static_cast<std::size_t>(previous)].length + 1};
        } else if (code == next && next < 4096) {
            table[next] = {previous, table[static_cast<std::size_t>(previous)].first, table[static_cast<std::size_t>(previous)].first,
                           table[static_cast<std::size_t>(previous)].length + 1};
            emit(code);
        } else {
            throw std::runtime_error("corrupt LZW data");
        }
        if (next < 4096) ++next;
        if (next >= (1U << width) - 1U && width < 12) ++width;
        previous = static_cast<std::int32_t>(code);
    }
}

void inflateInto(std::span<const std::uint8_t> in, std::uint8_t* out, std::size_t size) {
    z_stream stream{};
    if (inflateInit(&stream) != Z_OK) throw std::runtime_error("zlib init failed");
    stream.next_in = const_cast<Bytef*>(in.data());
    stream.avail_in = static_cast<uInt>(std::min<std::size_t>(in.size(), 0xFFFFFFFFU));
    stream.next_out = out;
    stream.avail_out = static_cast<uInt>(size);
    const int result = inflate(&stream, Z_FINISH);
    inflateEnd(&stream);
    if (result != Z_STREAM_END && result != Z_OK && result != Z_BUF_ERROR) throw std::runtime_error("corrupt Deflate data");
}

struct Layout {
    std::uint32_t width = 0, height = 0;
    int bits = 8;
    int spp = 1;
    bool planar = false;
    bool floating = false;
    bool little = true;
    int predictor = 1;
    /** Bytes per row of one chunk row holding `samples` samples per pixel. */
    std::size_t rowBytes(std::uint32_t w, int samples) const { return (static_cast<std::size_t>(w) * static_cast<std::size_t>(samples) * static_cast<std::size_t>(bits) + 7U) / 8U; }
};

/** Undoes the predictor for `rows` rows of width `w` with `samples` samples per pixel, in place. */
void unpredict(std::uint8_t* data, std::uint32_t w, std::size_t rows, int samples, const Layout& l) {
    const std::size_t row_bytes = l.rowBytes(w, samples);
    std::vector<std::uint8_t> tmp;
    for (std::size_t y = 0; y < rows; ++y) {
        std::uint8_t* row = data + y * row_bytes;
        if (l.predictor == 2) {
            const std::size_t n = static_cast<std::size_t>(w) * static_cast<std::size_t>(samples);
            if (l.bits == 8) {
                for (std::size_t i = static_cast<std::size_t>(samples); i < n; ++i) row[i] = static_cast<std::uint8_t>(row[i] + row[i - static_cast<std::size_t>(samples)]);
            } else if (l.bits == 16) {
                const auto get = [&](std::size_t i) { return static_cast<std::uint16_t>(l.little ? row[i * 2] | (row[i * 2 + 1] << 8) : (row[i * 2] << 8) | row[i * 2 + 1]); };
                for (std::size_t i = static_cast<std::size_t>(samples); i < n; ++i) {
                    const auto v = static_cast<std::uint16_t>(get(i) + get(i - static_cast<std::size_t>(samples)));
                    row[i * 2] = static_cast<std::uint8_t>(l.little ? v : v >> 8);
                    row[i * 2 + 1] = static_cast<std::uint8_t>(l.little ? v >> 8 : v);
                }
            } else if (l.bits == 32) {
                const auto get = [&](std::size_t i) {
                    std::uint32_t v = 0;
                    for (int k = 0; k < 4; ++k) v |= std::uint32_t{row[i * 4 + static_cast<std::size_t>(k)]} << (l.little ? 8 * k : 24 - 8 * k);
                    return v;
                };
                for (std::size_t i = static_cast<std::size_t>(samples); i < n; ++i) {
                    const auto v = get(i) + get(i - static_cast<std::size_t>(samples));
                    for (int k = 0; k < 4; ++k) row[i * 4 + static_cast<std::size_t>(k)] = static_cast<std::uint8_t>(v >> (l.little ? 8 * k : 24 - 8 * k));
                }
            }
        } else if (l.predictor == 3 && l.bits == 32) {
            // Byte-wise delta over the row, then four byte planes (most significant first) → samples in file order.
            const std::size_t n = static_cast<std::size_t>(w) * static_cast<std::size_t>(samples);
            for (std::size_t i = static_cast<std::size_t>(samples); i < n * 4; ++i) row[i] = static_cast<std::uint8_t>(row[i] + row[i - static_cast<std::size_t>(samples)]);
            tmp.assign(row, row + n * 4);
            for (std::size_t i = 0; i < n; ++i)
                for (std::size_t k = 0; k < 4; ++k) row[i * 4 + (l.little ? 3 - k : k)] = tmp[k * n + i];
        }
    }
}

float readSample(const std::uint8_t* row, std::size_t index, const Layout& l) {
    switch (l.bits) {
        case 1: return static_cast<float>((row[index / 8] >> (7 - index % 8)) & 1U);
        case 4: return static_cast<float>((row[index / 2] >> (index % 2 == 0 ? 4 : 0)) & 0x0FU) / 15.0F;
        case 8: return row[index] / 255.0F;
        case 16: {
            const std::uint16_t v = l.little ? static_cast<std::uint16_t>(row[index * 2] | (row[index * 2 + 1] << 8))
                                             : static_cast<std::uint16_t>((row[index * 2] << 8) | row[index * 2 + 1]);
            return v / 65535.0F;
        }
        default: {
            std::uint32_t v = 0;
            for (int k = 0; k < 4; ++k) v |= std::uint32_t{row[index * 4 + static_cast<std::size_t>(k)]} << (l.little ? 8 * k : 24 - 8 * k);
            if (!l.floating) return static_cast<float>(v / 4294967295.0);
            const float f = std::bit_cast<float>(v);
            return std::isfinite(f) ? f : 0.0F;
        }
    }
}

/** Raw sample integer (palette indices). */
std::uint32_t readIndex(const std::uint8_t* row, std::size_t index, int bits) {
    if (bits == 8) return row[index];
    if (bits == 4) return (row[index / 2] >> (index % 2 == 0 ? 4 : 0)) & 0x0FU;
    if (bits == 1) return (row[index / 8] >> (7 - index % 8)) & 1U;
    return 0;
}

TileStore applyOrientation(TileStore store, std::uint64_t orientation) {
    switch (orientation) {
        case 2: return resample::flip(store, true);
        case 3: return resample::rotate90(store, 2);
        case 4: return resample::flip(store, false);
        case 5: return resample::flip(resample::rotate90(store, 1), true);
        case 6: return resample::rotate90(store, 1);
        case 7: return resample::flip(resample::rotate90(store, 3), true);
        case 8: return resample::rotate90(store, 3);
        default: return store;
    }
}

}  // namespace

std::optional<Image> decode(std::span<const std::uint8_t> file, std::string& error, const Limits& limits) {
    try {
        if (file.size() < 8) throw Truncated("file too small");
        const bool little = file[0] == 'I' && file[1] == 'I';
        if (!little && !(file[0] == 'M' && file[1] == 'M')) {
            error = "not a TIFF file";
            return std::nullopt;
        }
        const Reader r(file, little);
        const auto magic = r.uint(2, 2);
        const bool big = magic == 43;
        if (magic != 42 && !big) {
            error = "not a TIFF file";
            return std::nullopt;
        }
        std::uint64_t first = 0;
        if (big) {
            if (r.uint(4, 2) != 8) throw std::runtime_error("unsupported BigTIFF offset size");
            first = r.uint(8, 8);
        } else {
            first = r.uint(4, 4);
        }
        const Ifd ifd(r, first, big);
        std::vector<std::string> warnings;
        // Count pages (bounded, loop-safe).
        std::uint32_t pages = 1;
        {
            std::set<std::uint64_t> seen{first};
            std::uint64_t next = ifd.next();
            while (next != 0 && pages < 10000 && seen.insert(next).second) {
                try {
                    next = Ifd(r, next, big).next();
                    ++pages;
                } catch (const std::exception&) {
                    break;
                }
            }
        }
        Layout l;
        l.little = little;
        l.width = static_cast<std::uint32_t>(std::min<std::uint64_t>(ifd.value(256, 0), 0xFFFFFFFFU));
        l.height = static_cast<std::uint32_t>(std::min<std::uint64_t>(ifd.value(257, 0), 0xFFFFFFFFU));
        l.spp = static_cast<int>(ifd.value(277, 1));
        const auto bits = ifd.values(258, 64);
        l.bits = bits.empty() ? 1 : static_cast<int>(bits[0]);
        const auto compression = ifd.value(259, 1);
        const auto photometric = ifd.value(262, 1);
        l.planar = ifd.value(284, 1) == 2;
        l.predictor = static_cast<int>(ifd.value(317, 1));
        const auto format = ifd.value(339, 1);
        l.floating = format == 3;
        if (l.width == 0 || l.height == 0 || std::uint64_t{l.width} * l.height > limits.max_pixels || l.width > 1000000 || l.height > 1000000) {
            error = "image size is out of range";
            return std::nullopt;
        }
        if (l.spp < 1 || l.spp > 16) throw std::runtime_error("unsupported samples per pixel");
        for (const auto b : bits)
            if (static_cast<int>(b) != l.bits) throw std::runtime_error("mixed bit depths are not supported");
        if (!(l.bits == 1 || l.bits == 4 || l.bits == 8 || l.bits == 16 || l.bits == 32)) throw std::runtime_error("unsupported bit depth");
        if (l.floating && l.bits != 32) throw std::runtime_error("only 32-bit floats are supported");
        if (compression != 1 && compression != 5 && compression != 8 && compression != 32946 && compression != 32773) {
            throw std::runtime_error(compression == 7 || compression == 6 ? "JPEG-compressed TIFF is not supported" : "unsupported TIFF compression");
        }
        int colour = 0;
        switch (photometric) {
            case 0: case 1: colour = 1; break;
            case 2: colour = 3; break;
            case 3: colour = 1; break;
            case 5: colour = 4; warnings.push_back("CMYK converted to RGB without colour management"); break;
            default: throw std::runtime_error("unsupported photometric interpretation");
        }
        if (l.spp < colour) throw std::runtime_error("too few samples for the colour model");
        const auto extra = ifd.values(338, 16);
        const int alpha_kind = (l.spp > colour && !extra.empty()) ? static_cast<int>(extra[0]) : 0;  // 1 associated, 2 unassociated
        const bool has_alpha = alpha_kind == 1 || alpha_kind == 2;
        std::vector<std::uint64_t> palette;
        if (photometric == 3) {
            palette = ifd.values(320, 3U << 16);
            if (palette.size() < 3U * (1U << l.bits) || l.bits > 8) throw std::runtime_error("bad palette");
        }
        // Decode every chunk into one buffer: chunky rows, or one plane per sample when planar.
        const int chunk_samples = l.planar ? 1 : l.spp;
        const int planes = l.planar ? l.spp : 1;
        const std::size_t row_bytes = l.rowBytes(l.width, chunk_samples);
        const std::uint64_t plane_bytes = std::uint64_t{row_bytes} * l.height;
        if (plane_bytes * static_cast<std::uint64_t>(planes) > limits.max_working_bytes) throw std::runtime_error("image exceeds the decode memory limit");
        // No TIFF compression expands data more than ~1100:1 (Deflate peaks near 1032:1, PackBits at 64:1), so a
        // file far smaller than its declared image is bogus; reject it before allocating the image buffer.
        if (compression != 1 && plane_bytes * static_cast<std::uint64_t>(planes) > 1100ULL * file.size()) {
            throw std::runtime_error("image data is implausibly small for its declared size");
        }
        if (compression == 1 && plane_bytes * static_cast<std::uint64_t>(planes) > file.size()) {
            throw std::runtime_error("uncompressed image data is missing");
        }
        std::vector<std::uint8_t> buffer(static_cast<std::size_t>(plane_bytes) * static_cast<std::size_t>(planes), 0);
        const bool tiled = ifd.has(322);
        const std::uint32_t chunk_w = tiled ? static_cast<std::uint32_t>(ifd.value(322, 0)) : l.width;
        const std::uint32_t chunk_h = tiled ? static_cast<std::uint32_t>(ifd.value(323, 0))
                                            : static_cast<std::uint32_t>(std::clamp<std::uint64_t>(ifd.value(278, l.height), 1, l.height));
        if (chunk_w == 0 || chunk_h == 0 || chunk_w > 65536 || chunk_h > 1000000) throw std::runtime_error("bad strip or tile size");
        const auto offsets = ifd.values(tiled ? 324 : 273);
        const auto counts = ifd.values(tiled ? 325 : 279);
        const std::uint32_t across = (l.width + chunk_w - 1) / chunk_w, down = (l.height + chunk_h - 1) / chunk_h;
        const std::uint64_t per_plane = std::uint64_t{across} * down;
        if (offsets.size() < per_plane * static_cast<std::uint64_t>(planes)) throw std::runtime_error("missing strip or tile offsets");
        const std::size_t chunk_row_bytes = l.rowBytes(chunk_w, chunk_samples);
        std::vector<std::uint8_t> chunk(chunk_row_bytes * chunk_h);
        bool damaged = false;
        for (int p = 0; p < planes; ++p) {
            for (std::uint64_t c = 0; c < per_plane; ++c) {
                const auto index = static_cast<std::size_t>(static_cast<std::uint64_t>(p) * per_plane + c);
                const std::uint32_t cx = static_cast<std::uint32_t>(c % across) * chunk_w, cy = static_cast<std::uint32_t>(c / across) * chunk_h;
                const std::uint32_t rows = std::min(chunk_h, l.height - cy);
                const std::uint64_t count = index < counts.size() ? counts[index] : (compression == 1 ? chunk_row_bytes * rows : 0);
                std::fill(chunk.begin(), chunk.end(), 0);
                try {
                    const auto data = r.at(offsets[index], std::min<std::uint64_t>(count, file.size() - std::min<std::uint64_t>(offsets[index], file.size())));
                    const std::size_t expected = chunk_row_bytes * (tiled ? chunk_h : rows);
                    switch (compression) {
                        case 1: std::memcpy(chunk.data(), data.data(), std::min(expected, data.size())); break;
                        case 5: lzw(data, chunk.data(), expected); break;
                        case 8: case 32946: inflateInto(data, chunk.data(), expected); break;
                        default: unpackBits(data, chunk.data(), expected); break;
                    }
                    if (l.predictor != 1) unpredict(chunk.data(), chunk_w, tiled ? chunk_h : rows, chunk_samples, l);
                } catch (const std::exception&) {
                    damaged = true;
                }
                // Copy the valid part of the chunk into the image buffer.
                const std::uint32_t cols = std::min(chunk_w, l.width - cx);
                for (std::uint32_t y = 0; y < rows; ++y) {
                    std::uint8_t* dst = buffer.data() + static_cast<std::size_t>(p) * plane_bytes + static_cast<std::size_t>(cy + y) * row_bytes;
                    const std::uint8_t* src = chunk.data() + static_cast<std::size_t>(y) * chunk_row_bytes;
                    if (l.bits >= 8) {
                        const std::size_t bpp = static_cast<std::size_t>(chunk_samples) * static_cast<std::size_t>(l.bits / 8);
                        std::memcpy(dst + cx * bpp, src, cols * bpp);
                    } else {
                        // Sub-byte samples: copy bit by bit (chunks start on byte boundaries per row).
                        for (std::size_t i = 0; i < static_cast<std::size_t>(cols) * static_cast<std::size_t>(chunk_samples); ++i) {
                            const std::size_t out_index = static_cast<std::size_t>(cx) * static_cast<std::size_t>(chunk_samples) + i;
                            const auto v = readIndex(src, i, l.bits);
                            const int per_byte = 8 / l.bits;
                            const int shift = (per_byte - 1 - static_cast<int>(out_index % static_cast<std::size_t>(per_byte))) * l.bits;
                            dst[out_index / static_cast<std::size_t>(per_byte)] = static_cast<std::uint8_t>(
                                (dst[out_index / static_cast<std::size_t>(per_byte)] & ~(((1U << l.bits) - 1U) << shift)) | (v << shift));
                        }
                    }
                }
            }
        }
        if (damaged) warnings.push_back("some image data was damaged and reads as black");
        // Convert to RGBA.
        const PixelFormat out_format = l.floating ? PixelFormat::RGBA32F : l.bits == 16 ? PixelFormat::RGBA16 : PixelFormat::RGBA8;
        TileStore store(out_format, l.width, l.height);
        const auto out_bpp = store.bytes_per_pixel();
        const auto sampleAt = [&](std::uint32_t x, std::uint32_t y, int s) {
            if (l.planar) return readSample(buffer.data() + static_cast<std::size_t>(s) * plane_bytes + static_cast<std::size_t>(y) * row_bytes, x, l);
            return readSample(buffer.data() + static_cast<std::size_t>(y) * row_bytes, static_cast<std::size_t>(x) * static_cast<std::size_t>(l.spp) + static_cast<std::size_t>(s), l);
        };
        const std::uint32_t columns = (l.width + kTileEdge - 1) / kTileEdge, tile_rows = (l.height + kTileEdge - 1) / kTileEdge;
        for (std::uint32_t ty = 0; ty < tile_rows; ++ty) {
            for (std::uint32_t tx = 0; tx < columns; ++tx) {
                TileBuffer tile(store.tile_byte_size(), 0);
                for (std::uint32_t ly = 0; ly < kTileEdge && ty * kTileEdge + ly < l.height; ++ly) {
                    const std::uint32_t y = ty * kTileEdge + ly;
                    for (std::uint32_t lx = 0; lx < kTileEdge && tx * kTileEdge + lx < l.width; ++lx) {
                        const std::uint32_t x = tx * kTileEdge + lx;
                        std::array<float, 4> px{0, 0, 0, 1};
                        if (photometric == 3) {
                            const std::uint8_t* row = buffer.data() + static_cast<std::size_t>(y) * row_bytes;
                            const auto i = readIndex(row, static_cast<std::size_t>(x) * static_cast<std::size_t>(chunk_samples), l.bits);
                            const std::size_t n = std::size_t{1} << l.bits;
                            for (std::size_t c = 0; c < 3; ++c) px[c] = static_cast<float>(palette[c * n + i]) / 65535.0F;
                        } else if (colour == 1) {
                            float v = sampleAt(x, y, 0);
                            if (photometric == 0) v = 1.0F - v;
                            px[0] = px[1] = px[2] = v;
                        } else if (colour == 3) {
                            for (int c = 0; c < 3; ++c) px[static_cast<std::size_t>(c)] = sampleAt(x, y, c);
                        } else {
                            const float k = 1.0F - sampleAt(x, y, 3);
                            for (int c = 0; c < 3; ++c) px[static_cast<std::size_t>(c)] = (1.0F - sampleAt(x, y, c)) * k;
                        }
                        if (has_alpha) {
                            px[3] = sampleAt(x, y, colour);
                            if (alpha_kind == 1 && px[3] > 0.0F)
                                for (std::size_t c = 0; c < 3; ++c) px[c] /= px[3];
                        }
                        paint::detail::writePixel(out_format, tile.data() + (static_cast<std::size_t>(ly) * kTileEdge + lx) * out_bpp, px.data());
                    }
                }
                if (!store.writeTile({static_cast<std::int32_t>(tx), static_cast<std::int32_t>(ty)}, tile)) throw std::runtime_error("could not store pixels");
            }
        }
        std::vector<std::uint8_t> icc;
        const auto icc_bytes = ifd.bytes(34675);
        if (icc_bytes.size() > (16U << 20U)) throw std::runtime_error("TIFF ICC profile exceeds the 16 MiB limit");
        icc.assign(icc_bytes.begin(), icc_bytes.end());
        if (ifd.has(37724)) warnings.push_back("Photoshop layers were flattened (the composite image is used)");
        return Image{applyOrientation(std::move(store), ifd.value(274, 1)), std::move(icc), pages, std::move(warnings)};
    } catch (const std::bad_alloc&) {
        error = "not enough memory to decode the TIFF";
    } catch (const std::exception& e) {
        error = std::string("not a readable TIFF: ") + e.what();
    }
    return std::nullopt;
}

std::optional<std::vector<std::uint8_t>> encode(const TileStore& pixels, std::string& error, const EncodeOptions& options) {
    if (!paint::detail::isRgba(pixels.format()) || pixels.width() == 0 || pixels.height() == 0) {
        error = "TIFF export needs a non-empty RGBA image";
        return std::nullopt;
    }
    try {
        const std::uint32_t w = pixels.width(), h = pixels.height();
        const int bits = pixels.format() == PixelFormat::RGBA8 ? 8 : pixels.format() == PixelFormat::RGBA16 ? 16 : 32;
        const std::size_t bps = static_cast<std::size_t>(bits / 8), row_bytes = static_cast<std::size_t>(w) * 4U * bps;
        const std::uint32_t rows_per_strip = static_cast<std::uint32_t>(std::clamp<std::size_t>((1U << 20) / row_bytes, 1, h));
        std::vector<std::uint8_t> out{'I', 'I', 42, 0, 0, 0, 0, 0};
        const auto put = [&](std::uint64_t v, int bytes) {
            for (int i = 0; i < bytes; ++i) out.push_back(static_cast<std::uint8_t>(v >> (8 * i)));
        };
        std::vector<std::uint32_t> strip_offsets, strip_counts;
        std::vector<std::uint8_t> strip;
        for (std::uint32_t y0 = 0; y0 < h; y0 += rows_per_strip) {
            const std::uint32_t rows = std::min(rows_per_strip, h - y0);
            strip.assign(row_bytes * rows, 0);
            for (std::uint32_t y = 0; y < rows; ++y) {
                std::uint8_t* row = strip.data() + y * row_bytes;
                for (std::uint32_t x = 0; x < w; ++x) {
                    std::array<float, 4> px{};
                    const auto t = pixels.readTile({static_cast<std::int32_t>(x / kTileEdge), static_cast<std::int32_t>((y0 + y) / kTileEdge)});
                    if (t) paint::detail::readPixel(pixels.format(), t->data() + (static_cast<std::size_t>((y0 + y) % kTileEdge) * kTileEdge + x % kTileEdge) * pixels.bytes_per_pixel(), px.data());
                    for (std::size_t c = 0; c < 4; ++c) {
                        std::uint8_t* s = row + (static_cast<std::size_t>(x) * 4U + c) * bps;
                        if (bits == 8) {
                            s[0] = static_cast<std::uint8_t>(std::clamp(px[c], 0.0F, 1.0F) * 255.0F + 0.5F);
                        } else if (bits == 16) {
                            const auto v = static_cast<std::uint16_t>(std::clamp(px[c], 0.0F, 1.0F) * 65535.0F + 0.5F);
                            s[0] = static_cast<std::uint8_t>(v);
                            s[1] = static_cast<std::uint8_t>(v >> 8);
                        } else {
                            const auto v = std::bit_cast<std::uint32_t>(px[c]);
                            for (int k = 0; k < 4; ++k) s[k] = static_cast<std::uint8_t>(v >> (8 * k));
                        }
                    }
                }
                if (options.deflate) {
                    // Predictor: horizontal differencing (2) for integers, floating-point (3) for floats.
                    const std::size_t n = static_cast<std::size_t>(w) * 4U;
                    if (bits == 8) {
                        for (std::size_t i = n; i-- > 4;) row[i] = static_cast<std::uint8_t>(row[i] - row[i - 4]);
                    } else if (bits == 16) {
                        for (std::size_t i = n; i-- > 4;) {
                            const auto v = static_cast<std::uint16_t>((row[i * 2] | (row[i * 2 + 1] << 8)) - (row[(i - 4) * 2] | (row[(i - 4) * 2 + 1] << 8)));
                            row[i * 2] = static_cast<std::uint8_t>(v);
                            row[i * 2 + 1] = static_cast<std::uint8_t>(v >> 8);
                        }
                    } else {
                        std::vector<std::uint8_t> planes(n * 4);
                        for (std::size_t i = 0; i < n; ++i)
                            for (std::size_t k = 0; k < 4; ++k) planes[k * n + i] = row[i * 4 + (3 - k)];
                        for (std::size_t i = n * 4; i-- > 4;) planes[i] = static_cast<std::uint8_t>(planes[i] - planes[i - 4]);
                        std::memcpy(row, planes.data(), n * 4);
                    }
                }
            }
            std::vector<std::uint8_t> data = strip;
            if (options.deflate) {
                uLongf size = compressBound(static_cast<uLong>(strip.size()));
                data.assign(size, 0);
                if (compress2(data.data(), &size, strip.data(), static_cast<uLong>(strip.size()), 6) != Z_OK) throw std::runtime_error("Deflate failed");
                data.resize(size);
            }
            if (out.size() + data.size() > 0xFFFFFFF0ULL) throw std::runtime_error("the image is too large for classic TIFF");
            strip_offsets.push_back(static_cast<std::uint32_t>(out.size()));
            strip_counts.push_back(static_cast<std::uint32_t>(data.size()));
            out.insert(out.end(), data.begin(), data.end());
            if (out.size() % 2) out.push_back(0);
        }
        // Out-of-line arrays.
        const auto array = [&](const std::vector<std::uint32_t>& v) {
            const auto at = static_cast<std::uint32_t>(out.size());
            for (const auto x : v) put(x, 4);
            return at;
        };
        const std::uint32_t offsets_at = strip_offsets.size() > 1 ? array(strip_offsets) : strip_offsets[0];
        const std::uint32_t counts_at = strip_counts.size() > 1 ? array(strip_counts) : strip_counts[0];
        const auto bits_at = static_cast<std::uint32_t>(out.size());
        for (int i = 0; i < 4; ++i) put(static_cast<std::uint64_t>(bits), 2);
        const auto format_at = static_cast<std::uint32_t>(out.size());
        for (int i = 0; i < 4; ++i) put(bits == 32 ? 3 : 1, 2);
        std::uint32_t icc_at = 0;
        if (!options.icc_profile.empty()) {
            icc_at = static_cast<std::uint32_t>(out.size());
            out.insert(out.end(), options.icc_profile.begin(), options.icc_profile.end());
        }
        if (out.size() % 2) out.push_back(0);
        // IFD (tags ascending).
        struct Tag {
            std::uint16_t tag, type;
            std::uint32_t count, value;
        };
        std::vector<Tag> tags{{256, 4, 1, w},
                              {257, 4, 1, h},
                              {258, 3, 4, bits_at},
                              {259, 3, 1, options.deflate ? 8U : 1U},
                              {262, 3, 1, 2},
                              {273, 4, static_cast<std::uint32_t>(strip_offsets.size()), offsets_at},
                              {277, 3, 1, 4},
                              {278, 4, 1, rows_per_strip},
                              {279, 4, static_cast<std::uint32_t>(strip_counts.size()), counts_at},
                              {284, 3, 1, 1}};
        if (options.deflate) tags.push_back({317, 3, 1, bits == 32 ? 3U : 2U});
        tags.push_back({338, 3, 1, 2});  // unassociated alpha
        tags.push_back({339, 3, 4, format_at});
        if (icc_at != 0) tags.push_back({34675, 7, static_cast<std::uint32_t>(options.icc_profile.size()), icc_at});
        const auto ifd_at = static_cast<std::uint32_t>(out.size());
        put(tags.size(), 2);
        for (const auto& t : tags) {
            put(t.tag, 2);
            put(t.type, 2);
            put(t.count, 4);
            if (t.type == 3 && t.count == 1) {
                put(t.value, 2);
                put(0, 2);
            } else {
                put(t.value, 4);
            }
        }
        put(0, 4);
        for (int i = 0; i < 4; ++i) out[4 + static_cast<std::size_t>(i)] = static_cast<std::uint8_t>(ifd_at >> (8 * i));
        return out;
    } catch (const std::bad_alloc&) {
        error = "not enough memory to encode the TIFF";
    } catch (const std::exception& e) {
        error = e.what();
    }
    return std::nullopt;
}

}  // namespace croptop::tiff
