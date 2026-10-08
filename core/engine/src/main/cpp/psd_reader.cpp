#include "psd_reader.h"

#include <zlib.h>

#include <algorithm>
#include <array>
#include <bit>
#include <cmath>
#include <cstring>
#include <map>
#include <stdexcept>

#include "pixel_plane.h"

namespace croptop::psd {
namespace {

struct Truncated final : std::runtime_error {
    using std::runtime_error::runtime_error;
};

/** Big-endian bounds-checked cursor over [pos, end) of the file. */
class Reader final {
public:
    Reader(std::span<const std::uint8_t> data, std::size_t begin, std::size_t end) : data_(data), pos_(begin), end_(end) {}

    std::size_t pos() const { return pos_; }
    std::size_t end() const { return end_; }
    std::size_t remaining() const { return end_ - pos_; }
    /** Skips up to 3 padding bytes so the next 4 bytes are a block signature, if one is there. */
    void alignToSignature() {
        for (std::size_t pad = 0; pad <= 3 && pos_ + pad + 4 <= end_; ++pad) {
            const auto* p = data_.data() + pos_ + pad;
            if (p[0] == '8' && p[1] == 'B' && (std::memcmp(p, "8BIM", 4) == 0 || std::memcmp(p, "8B64", 4) == 0)) {
                pos_ += pad;
                return;
            }
        }
    }
    void skip(std::uint64_t n) {
        if (n > remaining()) throw Truncated("unexpected end of data");
        pos_ += static_cast<std::size_t>(n);
    }
    Reader sub(std::uint64_t n) {
        if (n > remaining()) throw Truncated("section past the end");
        Reader r(data_, pos_, pos_ + static_cast<std::size_t>(n));
        pos_ += static_cast<std::size_t>(n);
        return r;
    }
    std::span<const std::uint8_t> bytes(std::uint64_t n) {
        if (n > remaining()) throw Truncated("unexpected end of data");
        const auto s = data_.subspan(pos_, static_cast<std::size_t>(n));
        pos_ += static_cast<std::size_t>(n);
        return s;
    }
    std::uint8_t u8() { return bytes(1)[0]; }
    std::uint16_t u16() {
        const auto b = bytes(2);
        return static_cast<std::uint16_t>((b[0] << 8) | b[1]);
    }
    std::uint32_t u32() {
        const auto b = bytes(4);
        return (std::uint32_t{b[0]} << 24) | (std::uint32_t{b[1]} << 16) | (std::uint32_t{b[2]} << 8) | b[3];
    }
    std::uint64_t u64() {
        const std::uint64_t high = u32();
        return (high << 32) | u32();
    }
    std::int16_t i16() { return static_cast<std::int16_t>(u16()); }
    std::int32_t i32() { return static_cast<std::int32_t>(u32()); }
    std::string ascii(std::size_t n) {
        const auto b = bytes(n);
        return {b.begin(), b.end()};
    }

private:
    std::span<const std::uint8_t> data_;
    std::size_t pos_;
    std::size_t end_;
};

std::string utf16ToUtf8(Reader& r, std::uint32_t count) {
    std::string out;
    for (std::uint32_t i = 0; i < count; ++i) {
        std::uint32_t c = r.u16();
        if (c >= 0xD800 && c < 0xDC00 && i + 1 < count) {
            const std::uint32_t low = r.u16();
            ++i;
            c = (low >= 0xDC00 && low < 0xE000) ? 0x10000 + ((c - 0xD800) << 10) + (low - 0xDC00) : 0xFFFD;
        } else if (c >= 0xD800 && c < 0xE000) {
            c = 0xFFFD;
        }
        if (c == 0) continue;
        if (c < 0x80) {
            out += static_cast<char>(c);
        } else if (c < 0x800) {
            out += static_cast<char>(0xC0 | (c >> 6));
            out += static_cast<char>(0x80 | (c & 0x3F));
        } else if (c < 0x10000) {
            out += static_cast<char>(0xE0 | (c >> 12));
            out += static_cast<char>(0x80 | ((c >> 6) & 0x3F));
            out += static_cast<char>(0x80 | (c & 0x3F));
        } else {
            out += static_cast<char>(0xF0 | (c >> 18));
            out += static_cast<char>(0x80 | ((c >> 12) & 0x3F));
            out += static_cast<char>(0x80 | ((c >> 6) & 0x3F));
            out += static_cast<char>(0x80 | (c & 0x3F));
        }
    }
    return out;
}

std::optional<BlendMode> blendFromKey(const std::string& key) {
    static const std::map<std::string, BlendMode> kModes{
        {"norm", BlendMode::Normal},      {"diss", BlendMode::Dissolve},     {"dark", BlendMode::Darken},
        {"mul ", BlendMode::Multiply},    {"idiv", BlendMode::ColorBurn},    {"lbrn", BlendMode::LinearBurn},
        {"dkCl", BlendMode::DarkerColor}, {"lite", BlendMode::Lighten},      {"scrn", BlendMode::Screen},
        {"div ", BlendMode::ColorDodge},  {"lddg", BlendMode::LinearDodge},  {"lgCl", BlendMode::LighterColor},
        {"over", BlendMode::Overlay},     {"sLit", BlendMode::SoftLight},    {"hLit", BlendMode::HardLight},
        {"vLit", BlendMode::VividLight},  {"lLit", BlendMode::LinearLight},  {"pLit", BlendMode::PinLight},
        {"hMix", BlendMode::HardMix},     {"diff", BlendMode::Difference},   {"smud", BlendMode::Exclusion},
        {"fsub", BlendMode::Subtract},    {"fdiv", BlendMode::Divide},       {"hue ", BlendMode::Hue},
        {"sat ", BlendMode::Saturation},  {"colr", BlendMode::Color},        {"lum ", BlendMode::Luminosity}};
    const auto it = kModes.find(key);
    if (it == kModes.end()) return std::nullopt;
    return it->second;
}

/** Keys whose length field is 8 bytes in PSB files. */
bool longLengthKey(const std::string& key) {
    static const std::array<const char*, 13> kKeys{"LMsk", "Lr16", "Lr32", "Layr", "Mt16", "Mt32", "Mtrn", "Alph", "FMsk", "lnk2", "FEid", "FXid", "PxSD"};
    return std::any_of(kKeys.begin(), kKeys.end(), [&](const char* k) { return key == k; });
}

struct Context final {
    bool psb = false;
    std::uint32_t width = 0, height = 0;
    std::uint16_t depth = 8;
    ColorMode mode = ColorMode::Rgb;
    const Limits* limits = nullptr;
    std::vector<std::string>* warnings = nullptr;
    std::size_t bps() const { return depth == 8 ? 1U : depth == 16 ? 2U : 4U; }
    PixelFormat format() const { return depth == 8 ? PixelFormat::RGBA8 : depth == 16 ? PixelFormat::RGBA16 : PixelFormat::RGBA32F; }
};

/** PackBits into exactly `size` bytes. */
void unpackBits(std::span<const std::uint8_t> in, std::uint8_t* out, std::size_t size) {
    std::size_t i = 0, o = 0;
    while (i < in.size() && o < size) {
        const auto n = static_cast<std::int8_t>(in[i++]);
        if (n >= 0) {
            const std::size_t run = std::min<std::size_t>(static_cast<std::size_t>(n) + 1U, size - o);
            if (i + static_cast<std::size_t>(n) + 1U > in.size()) throw Truncated("RLE literal past the row");
            std::memcpy(out + o, in.data() + i, run);
            i += static_cast<std::size_t>(n) + 1U;
            o += run;
        } else if (n != -128) {
            if (i >= in.size()) throw Truncated("RLE run past the row");
            const std::size_t run = std::min<std::size_t>(static_cast<std::size_t>(1 - n), size - o);
            std::memset(out + o, in[i++], run);
            o += run;
        }
    }
    if (o < size) std::memset(out + o, 0, size - o);  // short rows read as zero
}

void inflateInto(std::span<const std::uint8_t> in, std::uint8_t* out, std::size_t size) {
    z_stream stream{};
    if (inflateInit(&stream) != Z_OK) throw std::runtime_error("zlib init failed");
    stream.next_in = const_cast<Bytef*>(in.data());
    stream.avail_in = static_cast<uInt>(std::min<std::size_t>(in.size(), 0xFFFFFFFFU));
    stream.next_out = out;
    stream.avail_out = static_cast<uInt>(size);
    const int result = inflate(&stream, Z_FINISH);
    const auto produced = size - stream.avail_out;
    inflateEnd(&stream);
    if (result != Z_STREAM_END && result != Z_OK && result != Z_BUF_ERROR) throw std::runtime_error("corrupt ZIP channel data");
    // Short output means truncated or bogus data: reject it rather than zero-filling a declared (possibly huge)
    // plane, so a tiny file cannot force large allocations and work.
    if (produced < size) throw Truncated("ZIP channel data ends early");
}

/** Undoes ZIP prediction in place for w × h samples of `bps` bytes. */
void unpredict(std::uint8_t* data, std::size_t w, std::size_t h, std::size_t bps, std::vector<std::uint8_t>& scratch) {
    for (std::size_t y = 0; y < h; ++y) {
        std::uint8_t* row = data + y * w * bps;
        if (bps == 1) {
            for (std::size_t x = 1; x < w; ++x) row[x] = static_cast<std::uint8_t>(row[x] + row[x - 1]);
        } else if (bps == 2) {
            std::uint16_t previous = static_cast<std::uint16_t>((row[0] << 8) | row[1]);
            for (std::size_t x = 1; x < w; ++x) {
                const auto v = static_cast<std::uint16_t>(((row[x * 2] << 8) | row[x * 2 + 1]) + previous);
                row[x * 2] = static_cast<std::uint8_t>(v >> 8);
                row[x * 2 + 1] = static_cast<std::uint8_t>(v);
                previous = v;
            }
        } else {
            // 32-bit: bytewise delta over the row, then the row holds four byte planes (MSB first).
            for (std::size_t i = 1; i < w * 4; ++i) row[i] = static_cast<std::uint8_t>(row[i] + row[i - 1]);
            scratch.assign(row, row + w * 4);
            for (std::size_t x = 0; x < w; ++x)
                for (std::size_t b = 0; b < 4; ++b) row[x * 4 + b] = scratch[b * w + x];
        }
    }
}

/**
 * Decodes `planes` consecutive planes of w × h samples. `r` holds exactly the data after the compression
 * field; `row_counts_per_plane` is true for layer channels (each plane is its own record).
 */
std::vector<std::uint8_t> decodePlanes(Reader& r, std::uint16_t compression, std::size_t w, std::size_t h, std::size_t planes,
                                       const Context& ctx) {
    const std::size_t bps = ctx.bps();
    const std::uint64_t plane_bytes = static_cast<std::uint64_t>(w) * h * bps;
    if (plane_bytes * planes > ctx.limits->max_working_bytes) throw std::runtime_error("layer exceeds the decode memory limit");
    std::vector<std::uint8_t> out(static_cast<std::size_t>(plane_bytes * planes));
    if (out.empty()) return out;
    switch (compression) {
        case 0: {
            const auto raw = r.bytes(out.size());
            std::memcpy(out.data(), raw.data(), out.size());
            break;
        }
        case 1: {
            std::vector<std::uint32_t> counts(planes * h);
            for (auto& c : counts) c = ctx.psb ? r.u32() : r.u16();
            for (std::size_t i = 0; i < counts.size(); ++i) unpackBits(r.bytes(counts[i]), out.data() + i * w * bps, w * bps);
            break;
        }
        case 2:
        case 3: {
            inflateInto(r.bytes(r.remaining()), out.data(), out.size());
            if (compression == 3) {
                std::vector<std::uint8_t> scratch;
                for (std::size_t p = 0; p < planes; ++p) unpredict(out.data() + p * plane_bytes, w, h, bps, scratch);
            }
            break;
        }
        default: throw std::runtime_error("unknown channel compression");
    }
    return out;
}

float sample(const std::uint8_t* plane, std::size_t index, std::size_t bps) {
    if (bps == 1) return plane[index] / 255.0F;
    if (bps == 2) return static_cast<float>((plane[index * 2] << 8) | plane[index * 2 + 1]) / 65535.0F;
    const std::uint32_t bits = (std::uint32_t{plane[index * 4]} << 24) | (std::uint32_t{plane[index * 4 + 1]} << 16) |
                               (std::uint32_t{plane[index * 4 + 2]} << 8) | plane[index * 4 + 3];
    const float v = std::bit_cast<float>(bits);
    return std::isfinite(v) ? v : 0.0F;
}

/**
 * Writes planes (one per colour channel, plus optional alpha) of a rect at (left, top) into a canvas-sized
 * RGBA store. Colour planes: 1 (gray), 3 (RGB) or 4 (CMYK, stored inverted).
 */
void placeRgba(TileStore& store, const Context& ctx, std::int64_t left, std::int64_t top, std::size_t w, std::size_t h,
               const std::vector<const std::uint8_t*>& colour, const std::uint8_t* alpha, bool& clipped) {
    const std::size_t bps = ctx.bps();
    const std::int64_t x0 = std::max<std::int64_t>(left, 0), y0 = std::max<std::int64_t>(top, 0);
    const std::int64_t x1 = std::min<std::int64_t>(left + static_cast<std::int64_t>(w), ctx.width);
    const std::int64_t y1 = std::min<std::int64_t>(top + static_cast<std::int64_t>(h), ctx.height);
    if (x0 > left || y0 > top || x1 < left + static_cast<std::int64_t>(w) || y1 < top + static_cast<std::int64_t>(h)) clipped = true;
    if (x0 >= x1 || y0 >= y1) return;
    const auto out_bpp = store.bytes_per_pixel();
    for (std::int64_t ty = y0 / kTileEdge; ty * kTileEdge < y1; ++ty) {
        for (std::int64_t tx = x0 / kTileEdge; tx * kTileEdge < x1; ++tx) {
            TileBuffer tile(store.tile_byte_size(), 0);
            bool any = false;
            for (std::int64_t y = std::max<std::int64_t>(y0, ty * kTileEdge); y < std::min<std::int64_t>(y1, (ty + 1) * kTileEdge); ++y) {
                for (std::int64_t x = std::max<std::int64_t>(x0, tx * kTileEdge); x < std::min<std::int64_t>(x1, (tx + 1) * kTileEdge); ++x) {
                    const auto i = static_cast<std::size_t>(y - top) * w + static_cast<std::size_t>(x - left);
                    std::array<float, 4> px{};
                    if (colour.size() == 1) {
                        px[0] = px[1] = px[2] = sample(colour[0], i, bps);
                    } else if (colour.size() == 3) {
                        for (std::size_t c = 0; c < 3; ++c) px[c] = sample(colour[c], i, bps);
                    } else {
                        const float k = sample(colour[3], i, bps);
                        for (std::size_t c = 0; c < 3; ++c) px[c] = sample(colour[c], i, bps) * k;
                    }
                    px[3] = alpha != nullptr ? sample(alpha, i, bps) : 1.0F;
                    if (px[3] <= 0.0F && alpha != nullptr) continue;
                    any = true;
                    paint::detail::writePixel(store.format(), tile.data() + (static_cast<std::size_t>(y % kTileEdge) * kTileEdge + static_cast<std::size_t>(x % kTileEdge)) * out_bpp,
                                              px.data());
                }
            }
            if (any && !store.writeTile({static_cast<std::int32_t>(tx), static_cast<std::int32_t>(ty)}, tile)) throw std::runtime_error("could not store layer pixels");
        }
    }
}

/** Canvas-sized A16 mask: `default_color` outside the rect, the plane inside it. */
TileStore placeMask(const Context& ctx, std::int64_t left, std::int64_t top, std::size_t w, std::size_t h, const std::uint8_t* plane,
                    std::uint8_t default_color) {
    TileStore mask(PixelFormat::A16, ctx.width, ctx.height);
    const std::size_t bps = ctx.bps();
    const auto fill = static_cast<std::uint16_t>(default_color * 257U);
    const auto columns = (ctx.width + kTileEdge - 1U) / kTileEdge, rows = (ctx.height + kTileEdge - 1U) / kTileEdge;
    for (std::uint32_t ty = 0; ty < rows; ++ty) {
        for (std::uint32_t tx = 0; tx < columns; ++tx) {
            const std::int64_t tile_x0 = std::int64_t{tx} * kTileEdge, tile_y0 = std::int64_t{ty} * kTileEdge;
            const bool overlaps = tile_x0 < left + static_cast<std::int64_t>(w) && tile_x0 + kTileEdge > left && tile_y0 < top + static_cast<std::int64_t>(h) &&
                                  tile_y0 + kTileEdge > top;
            if (!overlaps && fill == 0U) continue;
            TileBuffer tile(mask.tile_byte_size(), 0);
            for (std::uint32_t ly = 0; ly < kTileEdge; ++ly) {
                for (std::uint32_t lx = 0; lx < kTileEdge; ++lx) {
                    const std::int64_t x = tile_x0 + lx, y = tile_y0 + ly;
                    std::uint16_t v = fill;
                    if (x >= left && y >= top && x < left + static_cast<std::int64_t>(w) && y < top + static_cast<std::int64_t>(h)) {
                        v = static_cast<std::uint16_t>(std::lround(std::clamp(sample(plane, static_cast<std::size_t>(y - top) * w + static_cast<std::size_t>(x - left), bps), 0.0F, 1.0F) * 65535.0F));
                    }
                    std::memcpy(tile.data() + (static_cast<std::size_t>(ly) * kTileEdge + lx) * 2U, &v, 2U);
                }
            }
            if (!mask.writeTile({static_cast<std::int32_t>(tx), static_cast<std::int32_t>(ty)}, tile)) throw std::runtime_error("could not store the layer mask");
        }
    }
    return mask;
}

struct ChannelInfo {
    std::int16_t id = 0;
    std::uint64_t length = 0;
};

struct MaskInfo {
    bool present = false;
    std::int32_t top = 0, left = 0, bottom = 0, right = 0;
    std::uint8_t default_color = 0;
    std::uint8_t flags = 0;
};

struct Record {
    Layer layer;
    std::vector<ChannelInfo> channels;
    MaskInfo mask;
};

void parseAdditional(Reader& r, Record& record, const Context& ctx) {
    while (r.remaining() >= 12) {
        const auto signature = r.ascii(4);
        if (signature != "8BIM" && signature != "8B64") break;
        const auto key = r.ascii(4);
        const std::uint64_t length = (ctx.psb && longLengthKey(key)) ? r.u64() : r.u32();
        auto block = r.sub(std::min<std::uint64_t>(length, r.remaining()));
        r.alignToSignature();  // writers pad blocks to 2 or 4 bytes
        if (key == "luni") {
            const auto count = block.u32();
            if (count <= block.remaining() / 2) record.layer.name = utf16ToUtf8(block, count);
        } else if (key == "lsct" || key == "lsdk") {
            const auto type = block.u32();
            if (type == 1 || type == 2) {
                record.layer.kind = LayerKind::GroupFolder;
                record.layer.group_open = type == 1;
            } else if (type == 3) {
                record.layer.kind = LayerKind::GroupDivider;
            }
            if (block.remaining() >= 8 && block.ascii(4) == "8BIM") {
                const auto mode = block.ascii(4);
                if (mode == "pass") {
                    record.layer.pass_through = true;
                } else if (const auto m = blendFromKey(mode)) {
                    record.layer.mode = *m;
                    record.layer.pass_through = false;
                }
            }
        } else if (key == "iOpa") {
            record.layer.fill = block.u8();
        } else {
            const auto data = block.bytes(block.remaining());
            record.layer.extra.push_back({key, std::vector<std::uint8_t>(data.begin(), data.end())});
        }
    }
}

Record parseRecord(Reader& r, const Context& ctx) {
    Record record;
    auto& layer = record.layer;
    layer.top = r.i32();
    layer.left = r.i32();
    layer.bottom = r.i32();
    layer.right = r.i32();
    const auto channel_count = r.u16();
    if (channel_count > 56) throw std::runtime_error("too many channels in a layer");
    for (std::uint16_t i = 0; i < channel_count; ++i) {
        ChannelInfo c;
        c.id = r.i16();
        c.length = ctx.psb ? r.u64() : r.u32();
        record.channels.push_back(c);
    }
    if (r.ascii(4) != "8BIM") throw std::runtime_error("bad layer record signature");
    const auto mode = r.ascii(4);
    if (mode == "pass") {
        layer.pass_through = true;
    } else if (const auto m = blendFromKey(mode)) {
        layer.mode = *m;
    } else {
        ctx.warnings->push_back("unknown blend mode '" + mode + "' read as Normal");
    }
    layer.opacity = r.u8();
    layer.clipped = r.u8() != 0;
    const auto flags = r.u8();
    layer.lock_transparency = (flags & 0x01U) != 0;
    layer.visible = (flags & 0x02U) == 0;
    r.skip(1);
    auto extra = r.sub(r.u32());
    // Layer mask data.
    auto mask = extra.sub(extra.u32());
    if (mask.remaining() >= 18) {
        record.mask.present = true;
        record.mask.top = mask.i32();
        record.mask.left = mask.i32();
        record.mask.bottom = mask.i32();
        record.mask.right = mask.i32();
        record.mask.default_color = mask.u8();
        record.mask.flags = mask.u8();
    }
    // Blending ranges: composite gray source (this layer) then destination (underlying), each [black lo, black hi, white lo, white hi].
    auto ranges = extra.sub(extra.u32());
    if (ranges.remaining() >= 8) {
        for (std::size_t i = 0; i < 8; ++i) layer.blend_ranges[i] = ranges.u8();
        // Then one source/destination pair per channel in the record's channel order; only red, green and blue (ids 0–2) are kept.
        for (const auto& channel : record.channels) {
            if (ranges.remaining() < 8) break;
            std::array<std::uint8_t, 8> pair{};
            for (auto& value : pair) value = ranges.u8();
            if (channel.id >= 0 && channel.id <= 2) {
                std::copy(pair.begin(), pair.end(), layer.blend_ranges.begin() + 8 + 8 * channel.id);
            }
        }
    }
    const auto name_length = extra.u8();
    layer.name = extra.ascii(name_length);
    const std::size_t padded = ((1U + name_length + 3U) & ~std::size_t{3}) - 1U - name_length;
    extra.skip(std::min<std::size_t>(padded, extra.remaining()));
    parseAdditional(extra, record, ctx);
    return record;
}

/** Reads a layer-info structure (count, records, channel data). */
void parseLayerInfo(Reader& r, Document& doc, const Context& ctx) {
    const auto raw_count = r.i16();
    const int count = std::abs(static_cast<int>(raw_count));
    if (static_cast<std::uint32_t>(count) > ctx.limits->max_layers) throw std::runtime_error("too many layers");
    std::vector<Record> records;
    records.reserve(static_cast<std::size_t>(count));
    for (int i = 0; i < count; ++i) records.push_back(parseRecord(r, ctx));
    bool clipped_warning = false;
    for (auto& record : records) {
        auto& layer = record.layer;
        const std::int64_t w = std::int64_t{layer.right} - layer.left, h = std::int64_t{layer.bottom} - layer.top;
        std::map<std::int16_t, std::vector<std::uint8_t>> planes;
        bool damaged = false;
        for (const auto& channel : record.channels) {
            if (channel.length > r.remaining()) throw Truncated("channel data past the end");
            auto data = r.sub(channel.length);
            if (channel.length < 2 || damaged) continue;
            std::int64_t cw = w, ch = h;
            if (channel.id == -2 || channel.id == -3) {
                cw = std::int64_t{record.mask.right} - record.mask.left;
                ch = std::int64_t{record.mask.bottom} - record.mask.top;
                if (channel.id == -3) continue;  // real user mask (vector-derived): not imported
            }
            if (cw <= 0 || ch <= 0) continue;
            if (cw > ctx.limits->max_dimension || ch > ctx.limits->max_dimension) {
                damaged = true;
                continue;
            }
            try {
                const auto compression = data.u16();
                planes[channel.id] = decodePlanes(data, compression, static_cast<std::size_t>(cw), static_cast<std::size_t>(ch), 1, ctx);
            } catch (const std::exception& e) {
                damaged = true;
                ctx.warnings->push_back("layer \"" + layer.name + "\": " + e.what());
            }
        }
        if (damaged) {
            ctx.warnings->push_back("layer \"" + layer.name + "\" has damaged pixel data and was left empty");
        } else if (layer.kind == LayerKind::Pixel && w > 0 && h > 0) {
            std::vector<const std::uint8_t*> colour;
            const int colour_channels = ctx.mode == ColorMode::Grayscale ? 1 : ctx.mode == ColorMode::Cmyk ? 4 : 3;
            for (int c = 0; c < colour_channels; ++c) {
                const auto it = planes.find(static_cast<std::int16_t>(c));
                if (it == planes.end()) break;
                colour.push_back(it->second.data());
            }
            if (static_cast<int>(colour.size()) == colour_channels) {
                const auto alpha = planes.find(-1);
                TileStore pixels(ctx.format(), ctx.width, ctx.height);
                bool clipped = false;
                placeRgba(pixels, ctx, layer.left, layer.top, static_cast<std::size_t>(w), static_cast<std::size_t>(h), colour,
                          alpha != planes.end() ? alpha->second.data() : nullptr, clipped);
                clipped_warning = clipped_warning || clipped;
                layer.pixels.emplace(std::move(pixels));
            }
        }
        const auto mask_plane = planes.find(-2);
        if (record.mask.present && mask_plane != planes.end()) {
            Mask mask{placeMask(ctx, record.mask.left, record.mask.top, static_cast<std::size_t>(record.mask.right - record.mask.left),
                                static_cast<std::size_t>(record.mask.bottom - record.mask.top), mask_plane->second.data(), record.mask.default_color),
                      record.mask.default_color, (record.mask.flags & 0x02U) != 0, (record.mask.flags & 0x01U) == 0};
            layer.mask.emplace(std::move(mask));
        }
        doc.layers.push_back(std::move(layer));
    }
    if (clipped_warning) ctx.warnings->push_back("pixels outside the canvas were dropped");
}

void parseComposite(Reader& r, Document& doc, const Context& ctx, bool merged_alpha, std::uint16_t file_channels) {
    const auto compression = r.u16();
    const int colour_channels = ctx.mode == ColorMode::Grayscale ? 1 : ctx.mode == ColorMode::Cmyk ? 4 : 3;
    const std::size_t planes = std::min<std::size_t>(file_channels, static_cast<std::size_t>(colour_channels + (merged_alpha ? 1 : 0)));
    if (planes < static_cast<std::size_t>(colour_channels)) throw std::runtime_error("the composite has too few channels");
    // RLE row counts cover every channel in the file; decode only the ones we use.
    std::vector<std::uint8_t> data;
    if (compression == 1 && file_channels > planes) {
        Reader counts = r;
        std::vector<std::uint32_t> lengths(static_cast<std::size_t>(file_channels) * ctx.height);
        for (auto& l : lengths) l = ctx.psb ? counts.u32() : counts.u16();
        const auto bps = ctx.bps();
        if (static_cast<std::uint64_t>(ctx.width) * ctx.height * bps * planes > ctx.limits->max_working_bytes) throw std::runtime_error("composite exceeds the decode memory limit");
        data.resize(static_cast<std::size_t>(ctx.width) * ctx.height * bps * planes);
        for (std::size_t i = 0; i < planes * ctx.height; ++i) unpackBits(counts.bytes(lengths[i]), data.data() + i * ctx.width * bps, ctx.width * bps);
    } else {
        data = decodePlanes(r, compression, ctx.width, ctx.height, planes, ctx);
    }
    const std::size_t plane_bytes = static_cast<std::size_t>(ctx.width) * ctx.height * ctx.bps();
    std::vector<const std::uint8_t*> colour;
    for (int c = 0; c < colour_channels; ++c) colour.push_back(data.data() + static_cast<std::size_t>(c) * plane_bytes);
    TileStore composite(ctx.format(), ctx.width, ctx.height);
    bool clipped = false;
    placeRgba(composite, ctx, 0, 0, ctx.width, ctx.height, colour, merged_alpha && planes > static_cast<std::size_t>(colour_channels) ? data.data() + static_cast<std::size_t>(colour_channels) * plane_bytes : nullptr, clipped);
    doc.composite.emplace(std::move(composite));
}

}  // namespace

std::optional<Document> read(std::span<const std::uint8_t> file, std::string& error, const Limits& limits) {
    Document doc;
    Context ctx;
    ctx.limits = &limits;
    ctx.warnings = &doc.warnings;
    try {
        Reader r(file, 0, file.size());
        if (r.ascii(4) != "8BPS") {
            error = "not a Photoshop document";
            return std::nullopt;
        }
        const auto version = r.u16();
        if (version != 1 && version != 2) {
            error = "unsupported PSD version";
            return std::nullopt;
        }
        ctx.psb = doc.psb = version == 2;
        r.skip(6);
        const auto channels = r.u16();
        ctx.height = doc.height = r.u32();
        ctx.width = doc.width = r.u32();
        ctx.depth = doc.depth = r.u16();
        ctx.mode = doc.color_mode = static_cast<ColorMode>(r.u16());
        const std::uint32_t max_side = ctx.psb ? std::min<std::uint32_t>(300000U, limits.max_dimension) : std::min<std::uint32_t>(30000U, limits.max_dimension);
        if (channels < 1 || channels > 56 || doc.width == 0 || doc.height == 0 || doc.width > max_side || doc.height > max_side ||
            std::uint64_t{doc.width} * doc.height > limits.max_canvas_pixels) {
            error = "document size or channel count is out of range";
            return std::nullopt;
        }
        if (doc.depth != 8 && doc.depth != 16 && doc.depth != 32) {
            error = "only 8, 16 and 32-bit documents are supported";
            return std::nullopt;
        }
        if (ctx.mode != ColorMode::Rgb && ctx.mode != ColorMode::Grayscale && ctx.mode != ColorMode::Cmyk) {
            error = "unsupported colour mode (RGB, Grayscale and CMYK are supported)";
            return std::nullopt;
        }
        if (ctx.mode == ColorMode::Cmyk) doc.warnings.push_back("CMYK converted to RGB without colour management");
        r.skip(r.u32());  // colour mode data
        // Image resources: pick up the ICC profile.
        {
            auto resources = r.sub(r.u32());
            while (resources.remaining() >= 12) {
                if (resources.ascii(4) != "8BIM") break;
                const auto id = resources.u16();
                const auto name_length = resources.u8();
                resources.skip(name_length + ((name_length + 1U) % 2U));
                const auto size = resources.u32();
                auto block = resources.sub(std::min<std::uint64_t>(size, resources.remaining()));
                if (size % 2 == 1 && resources.remaining() > 0) resources.skip(1);
                if (id == 1039) {
                    if (size > (16U << 20U)) throw std::runtime_error("PSD ICC profile exceeds the 16 MiB limit");
                    const auto icc = block.bytes(block.remaining());
                    doc.icc_profile.assign(icc.begin(), icc.end());
                } else if (id == 1037 && block.remaining() >= 4) {
                    doc.global_light_angle = static_cast<float>(block.i32());
                } else if (id == 1049 && block.remaining() >= 4) {
                    doc.global_light_altitude = static_cast<float>(block.i32());
                } else if (id == 1057 && block.remaining() >= 5) {
                    block.u32();  // version
                    doc.real_merged_data = block.u8() != 0;
                }
            }
        }
        // Layer and mask information.
        bool merged_alpha = false;
        {
            const std::uint64_t length = ctx.psb ? r.u64() : r.u32();
            auto section = r.sub(length);
            if (section.remaining() > 0) {
                const std::uint64_t info_length = ctx.psb ? section.u64() : section.u32();
                if (info_length > 0) {
                    auto info = section.sub(info_length);
                    Reader peek = info;
                    merged_alpha = peek.i16() < 0;
                    parseLayerInfo(info, doc, ctx);
                }
                if (section.remaining() >= 4) section.skip(std::min<std::uint64_t>(section.u32(), section.remaining()));  // global mask
                // Additional layer information at the tail (16/32-bit layers live in Lr16/Lr32).
                while (section.remaining() >= 12) {
                    const auto signature = section.ascii(4);
                    if (signature != "8BIM" && signature != "8B64") break;
                    const auto key = section.ascii(4);
                    const std::uint64_t block_length = (ctx.psb && longLengthKey(key)) ? section.u64() : section.u32();
                    auto block = section.sub(std::min<std::uint64_t>(block_length, section.remaining()));
                    section.alignToSignature();
                    if ((key == "Lr16" || key == "Lr32" || key == "Layr") && doc.layers.empty() && block.remaining() >= 2) {
                        Reader peek = block;
                        merged_alpha = peek.i16() < 0;
                        parseLayerInfo(block, doc, ctx);
                    }
                }
            }
        }
        if (limits.read_composite && r.remaining() >= 2) {
            try {
                // Without layers, extra channels are saved alpha channels (selections), not transparency.
                parseComposite(r, doc, ctx, merged_alpha, channels);
            } catch (const std::exception& e) {
                doc.warnings.push_back(std::string("the merged image could not be read: ") + e.what());
            }
        }
    } catch (const std::bad_alloc&) {
        error = "not enough memory to open the document";
        return std::nullopt;
    } catch (const std::exception& e) {
        if (doc.layers.empty()) {
            error = std::string("not a readable Photoshop document: ") + e.what();
            return std::nullopt;
        }
        doc.warnings.push_back(std::string("the file is truncated: ") + e.what());
    }
    return doc;
}

}  // namespace croptop::psd
