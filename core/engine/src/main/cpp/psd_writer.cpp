#include "psd_writer.h"

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

class Out final {
public:
    explicit Out(bool psb, std::uint64_t limit) : psb_(psb), limit_(limit) {}

    void u8(std::uint8_t v) {
        if (bytes_.size() >= limit_) throw std::runtime_error("the document exceeds the output size limit");
        bytes_.push_back(v);
    }
    void u16(std::uint16_t v) {
        u8(static_cast<std::uint8_t>(v >> 8));
        u8(static_cast<std::uint8_t>(v));
    }
    void u32(std::uint32_t v) {
        u16(static_cast<std::uint16_t>(v >> 16));
        u16(static_cast<std::uint16_t>(v));
    }
    void u64(std::uint64_t v) {
        u32(static_cast<std::uint32_t>(v >> 32));
        u32(static_cast<std::uint32_t>(v));
    }
    void length(std::uint64_t v) { psb_ ? u64(v) : u32(static_cast<std::uint32_t>(v)); }
    void ascii(const std::string& s) {
        for (const char c : s) u8(static_cast<std::uint8_t>(c));
    }
    void append(const std::vector<std::uint8_t>& data) {
        if (bytes_.size() + data.size() > limit_) throw std::runtime_error("the document exceeds the output size limit");
        bytes_.insert(bytes_.end(), data.begin(), data.end());
    }
    void pad(std::size_t multiple) {
        while (bytes_.size() % multiple != 0) u8(0);
    }
    /** Reserves a length field at the current position; fill() later writes the byte count since. */
    std::size_t reserve(bool long_field) {
        const auto at = bytes_.size();
        long_field ? u64(0) : u32(0);
        return at;
    }
    void fill(std::size_t at, bool long_field) {
        const std::uint64_t n = bytes_.size() - at - (long_field ? 8U : 4U);
        for (std::size_t i = 0, width = long_field ? 8U : 4U; i < width; ++i)
            bytes_[at + i] = static_cast<std::uint8_t>(n >> (8U * (width - 1U - i)));
    }
    std::size_t size() const { return bytes_.size(); }
    std::vector<std::uint8_t> take() { return std::move(bytes_); }
    bool psb() const { return psb_; }

private:
    bool psb_;
    std::uint64_t limit_;
    std::vector<std::uint8_t> bytes_;
};

std::string blendKey(BlendMode mode) {
    static const std::map<BlendMode, std::string> kKeys{
        {BlendMode::Normal, "norm"},      {BlendMode::Dissolve, "diss"},     {BlendMode::Darken, "dark"},
        {BlendMode::Multiply, "mul "},    {BlendMode::ColorBurn, "idiv"},    {BlendMode::LinearBurn, "lbrn"},
        {BlendMode::DarkerColor, "dkCl"}, {BlendMode::Lighten, "lite"},      {BlendMode::Screen, "scrn"},
        {BlendMode::ColorDodge, "div "},  {BlendMode::LinearDodge, "lddg"},  {BlendMode::LighterColor, "lgCl"},
        {BlendMode::Overlay, "over"},     {BlendMode::SoftLight, "sLit"},    {BlendMode::HardLight, "hLit"},
        {BlendMode::VividLight, "vLit"},  {BlendMode::LinearLight, "lLit"},  {BlendMode::PinLight, "pLit"},
        {BlendMode::HardMix, "hMix"},     {BlendMode::Difference, "diff"},   {BlendMode::Exclusion, "smud"},
        {BlendMode::Subtract, "fsub"},    {BlendMode::Divide, "fdiv"},       {BlendMode::Hue, "hue "},
        {BlendMode::Saturation, "sat "},  {BlendMode::Color, "colr"},        {BlendMode::Luminosity, "lum "}};
    const auto it = kKeys.find(mode);
    return it == kKeys.end() ? "norm" : it->second;
}

struct Rect {
    std::int32_t top = 0, left = 0, bottom = 0, right = 0;
    bool empty() const { return bottom <= top || right <= left; }
    std::size_t width() const { return static_cast<std::size_t>(right - left); }
    std::size_t height() const { return static_cast<std::size_t>(bottom - top); }
};

/** Tight bounds of pixels with non-zero alpha (RGBA) or ≠ `background` (A16). */
Rect contentBounds(const TileStore& store, std::uint16_t background) {
    Rect r{static_cast<std::int32_t>(store.height()), static_cast<std::int32_t>(store.width()), 0, 0};
    const auto bpp = store.bytes_per_pixel();
    const auto columns = (store.width() + kTileEdge - 1U) / kTileEdge, rows = (store.height() + kTileEdge - 1U) / kTileEdge;
    for (std::uint32_t ty = 0; ty < rows; ++ty) {
        for (std::uint32_t tx = 0; tx < columns; ++tx) {
            const auto tile = store.readTile({static_cast<std::int32_t>(tx), static_cast<std::int32_t>(ty)});
            const auto x_end = std::min<std::uint32_t>(kTileEdge, store.width() - tx * kTileEdge);
            const auto y_end = std::min<std::uint32_t>(kTileEdge, store.height() - ty * kTileEdge);
            for (std::uint32_t y = 0; y < y_end; ++y) {
                for (std::uint32_t x = 0; x < x_end; ++x) {
                    bool content = false;
                    const auto i = static_cast<std::size_t>(y) * kTileEdge + x;
                    if (store.format() == PixelFormat::A16) {
                        std::uint16_t v = 0;
                        if (tile) std::memcpy(&v, tile->data() + i * 2U, 2U);
                        content = v != background;
                    } else if (tile) {
                        std::array<float, 4> px{};
                        paint::detail::readPixel(store.format(), tile->data() + i * bpp, px.data());
                        content = px[3] > 0.0F;
                    }
                    if (!content) continue;
                    const auto gx = static_cast<std::int32_t>(tx * kTileEdge + x), gy = static_cast<std::int32_t>(ty * kTileEdge + y);
                    r.left = std::min(r.left, gx);
                    r.top = std::min(r.top, gy);
                    r.right = std::max(r.right, gx + 1);
                    r.bottom = std::max(r.bottom, gy + 1);
                }
            }
        }
    }
    if (r.empty()) return {};
    return r;
}

/** Big-endian planes (one per channel index) of `rect` from an RGBA store, or the single plane of an A16 mask. */
std::vector<std::vector<std::uint8_t>> planesOf(const TileStore& store, const Rect& rect, std::size_t bps, const std::vector<int>& channels) {
    std::vector<std::vector<std::uint8_t>> planes(channels.size(), std::vector<std::uint8_t>(rect.width() * rect.height() * bps));
    const auto bpp = store.bytes_per_pixel();
    for (std::size_t y = 0; y < rect.height(); ++y) {
        const std::int64_t gy = rect.top + static_cast<std::int64_t>(y);
        std::shared_ptr<const TileBuffer> tile;
        std::int64_t loaded = -1;
        for (std::size_t x = 0; x < rect.width(); ++x) {
            const std::int64_t gx = rect.left + static_cast<std::int64_t>(x);
            if (gx / kTileEdge != loaded) {
                loaded = gx / kTileEdge;
                tile = store.readTile({static_cast<std::int32_t>(loaded), static_cast<std::int32_t>(gy / kTileEdge)});
            }
            std::array<float, 4> px{};
            const auto local = static_cast<std::size_t>(gy % kTileEdge) * kTileEdge + static_cast<std::size_t>(gx % kTileEdge);
            if (store.format() == PixelFormat::A16) {
                std::uint16_t v = 0;
                if (tile) std::memcpy(&v, tile->data() + local * 2U, 2U);
                px[0] = v / 65535.0F;
            } else if (tile) {
                paint::detail::readPixel(store.format(), tile->data() + local * bpp, px.data());
            }
            const auto index = y * rect.width() + x;
            for (std::size_t p = 0; p < channels.size(); ++p) {
                const float v = px[static_cast<std::size_t>(channels[p])];
                std::uint8_t* out = planes[p].data() + index * bps;
                if (bps == 1) {
                    out[0] = static_cast<std::uint8_t>(std::clamp(v, 0.0F, 1.0F) * 255.0F + 0.5F);
                } else if (bps == 2) {
                    const auto q = static_cast<std::uint16_t>(std::clamp(v, 0.0F, 1.0F) * 65535.0F + 0.5F);
                    out[0] = static_cast<std::uint8_t>(q >> 8);
                    out[1] = static_cast<std::uint8_t>(q);
                } else {
                    const auto bits = std::bit_cast<std::uint32_t>(v);
                    for (int k = 0; k < 4; ++k) out[k] = static_cast<std::uint8_t>(bits >> (24 - 8 * k));
                }
            }
        }
    }
    return planes;
}

std::vector<std::uint8_t> packBitsRow(const std::uint8_t* row, std::size_t n) {
    std::vector<std::uint8_t> out;
    std::size_t i = 0;
    while (i < n) {
        std::size_t run = 1;
        while (i + run < n && run < 128 && row[i + run] == row[i]) ++run;
        if (run >= 3) {
            out.push_back(static_cast<std::uint8_t>(static_cast<std::int8_t>(1 - static_cast<int>(run))));
            out.push_back(row[i]);
            i += run;
            continue;
        }
        std::size_t literal = 0;
        while (i + literal < n && literal < 128) {
            if (i + literal + 2 < n && row[i + literal] == row[i + literal + 1] && row[i + literal] == row[i + literal + 2]) break;
            ++literal;
        }
        literal = std::max<std::size_t>(literal, 1);
        out.push_back(static_cast<std::uint8_t>(literal - 1));
        out.insert(out.end(), row + i, row + i + literal);
        i += literal;
    }
    return out;
}

/** Encodes planes back to back (layer channels: one plane; composite: all planes sharing one header). */
std::vector<std::uint8_t> encodePlanes(std::vector<std::vector<std::uint8_t>> planes, std::size_t w, std::size_t h, std::size_t bps,
                                       Compression compression, bool psb) {
    Out out(psb, ~std::uint64_t{0});
    const std::size_t row_bytes = w * bps;
    switch (compression) {
        case Compression::Raw:
            for (const auto& p : planes) out.append(p);
            break;
        case Compression::Rle: {
            std::vector<std::vector<std::uint8_t>> rows;
            rows.reserve(planes.size() * h);
            for (const auto& p : planes)
                for (std::size_t y = 0; y < h; ++y) rows.push_back(packBitsRow(p.data() + y * row_bytes, row_bytes));
            for (const auto& r : rows) psb ? out.u32(static_cast<std::uint32_t>(r.size())) : out.u16(static_cast<std::uint16_t>(r.size()));
            for (const auto& r : rows) out.append(r);
            break;
        }
        default: {
            std::vector<std::uint8_t> all;
            for (auto& p : planes) {
                if (compression == Compression::ZipPredicted) {
                    std::vector<std::uint8_t> planar(row_bytes);
                    for (std::size_t y = 0; y < h; ++y) {
                        std::uint8_t* row = p.data() + y * row_bytes;
                        if (bps == 1) {
                            for (std::size_t x = w - 1; x > 0; --x) row[x] = static_cast<std::uint8_t>(row[x] - row[x - 1]);
                        } else if (bps == 2) {
                            for (std::size_t x = w - 1; x > 0; --x) {
                                const auto v = static_cast<std::uint16_t>(((row[x * 2] << 8) | row[x * 2 + 1]) - ((row[x * 2 - 2] << 8) | row[x * 2 - 1]));
                                row[x * 2] = static_cast<std::uint8_t>(v >> 8);
                                row[x * 2 + 1] = static_cast<std::uint8_t>(v);
                            }
                        } else {
                            for (std::size_t x = 0; x < w; ++x)
                                for (std::size_t k = 0; k < 4; ++k) planar[k * w + x] = row[x * 4 + k];
                            for (std::size_t i = row_bytes - 1; i > 0; --i) planar[i] = static_cast<std::uint8_t>(planar[i] - planar[i - 1]);
                            std::memcpy(row, planar.data(), row_bytes);
                        }
                    }
                }
                all.insert(all.end(), p.begin(), p.end());
            }
            uLongf size = compressBound(static_cast<uLong>(all.size()));
            std::vector<std::uint8_t> z(size);
            if (compress2(z.data(), &size, all.data(), static_cast<uLong>(all.size()), 6) != Z_OK) throw std::runtime_error("ZIP compression failed");
            z.resize(size);
            out.append(z);
        }
    }
    return out.take();
}

void additional(Out& out, const std::string& key, const std::vector<std::uint8_t>& data) {
    out.ascii("8BIM");
    out.ascii(key);
    out.u32(static_cast<std::uint32_t>((data.size() + 1U) & ~std::size_t{1}));
    out.append(data);
    if (data.size() % 2 == 1) out.u8(0);
}

struct Encoded {
    Rect rect;
    std::vector<std::pair<std::int16_t, std::vector<std::uint8_t>>> channels;  // id → compression + data
    Rect mask_rect;
    bool has_mask = false;
};

}  // namespace

std::optional<std::vector<std::uint8_t>> write(const Document& doc, std::string& error, const WriteOptions& options) {
    if (doc.width == 0 || doc.height == 0 || doc.width > 300000 || doc.height > 300000) {
        error = "document size is out of range";
        return std::nullopt;
    }
    if (doc.depth != 8 && doc.depth != 16 && doc.depth != 32) {
        error = "only 8, 16 and 32-bit documents can be written";
        return std::nullopt;
    }
    if (!doc.composite || doc.composite->width() != doc.width || doc.composite->height() != doc.height ||
        !paint::detail::isRgba(doc.composite->format())) {
        error = "a merged RGBA composite of the document's size is required";
        return std::nullopt;
    }
    for (const auto& layer : doc.layers) {
        if ((layer.pixels && (layer.pixels->width() != doc.width || layer.pixels->height() != doc.height || !paint::detail::isRgba(layer.pixels->format()))) ||
            (layer.mask && (layer.mask->coverage.width() != doc.width || layer.mask->coverage.height() != doc.height ||
                            layer.mask->coverage.format() != PixelFormat::A16))) {
            error = "layer \"" + layer.name + "\" pixels or mask do not match the document";
            return std::nullopt;
        }
    }
    const bool psb = options.force_psb || doc.width > 30000 || doc.height > 30000;
    const std::size_t bps = doc.depth / 8U;
    const Compression layer_compression = options.compression != Compression::Automatic ? options.compression
                                          : doc.depth == 8                               ? Compression::Rle
                                                                                         : Compression::ZipPredicted;
    try {
        Out out(psb, options.max_output_bytes);
        out.ascii("8BPS");
        out.u16(psb ? 2 : 1);
        for (int i = 0; i < 6; ++i) out.u8(0);
        out.u16(4);  // RGB + merged alpha
        out.u32(doc.height);
        out.u32(doc.width);
        out.u16(doc.depth);
        out.u16(static_cast<std::uint16_t>(ColorMode::Rgb));
        out.u32(0);  // colour mode data
        // Image resources: the ICC profile.
        const auto resources = out.reserve(false);
        if (!doc.icc_profile.empty()) {
            out.ascii("8BIM");
            out.u16(1039);
            out.u16(0);  // empty Pascal name, padded
            out.u32(static_cast<std::uint32_t>(doc.icc_profile.size()));
            out.append(doc.icc_profile);
            out.pad(2);
        }
        out.fill(resources, false);

        // Layer records and channel data.
        Out info(psb, options.max_output_bytes);
        info.u16(static_cast<std::uint16_t>(-static_cast<std::int32_t>(doc.layers.size())));  // negative: merged alpha
        std::vector<Encoded> encoded(doc.layers.size());
        for (std::size_t i = 0; i < doc.layers.size(); ++i) {
            const auto& layer = doc.layers[i];
            auto& e = encoded[i];
            if (layer.kind == LayerKind::Pixel && layer.pixels) e.rect = contentBounds(*layer.pixels, 0);
            const auto code = static_cast<std::uint16_t>(layer_compression == Compression::Raw ? 0 : layer_compression == Compression::Rle ? 1
                                                          : layer_compression == Compression::Zip ? 2 : 3);
            const auto channelBytes = [&](std::vector<std::vector<std::uint8_t>> plane, const Rect& rect) {
                std::vector<std::uint8_t> bytes{static_cast<std::uint8_t>(code >> 8), static_cast<std::uint8_t>(code)};
                const auto data = encodePlanes(std::move(plane), rect.width(), rect.height(), bps, layer_compression, psb);
                bytes.insert(bytes.end(), data.begin(), data.end());
                return bytes;
            };
            const std::array<std::int16_t, 4> ids{-1, 0, 1, 2};
            if (!e.rect.empty()) {
                auto planes = planesOf(*layer.pixels, e.rect, bps, {3, 0, 1, 2});  // alpha, R, G, B
                for (std::size_t p = 0; p < 4; ++p) e.channels.emplace_back(ids[p], channelBytes({std::move(planes[p])}, e.rect));
            } else {
                // Empty and group layers still carry their four channel records (raw, no data), as Photoshop writes.
                for (const auto id : ids) e.channels.emplace_back(id, std::vector<std::uint8_t>{0, 0});
            }
            if (layer.mask) {
                e.mask_rect = contentBounds(layer.mask->coverage, static_cast<std::uint16_t>(layer.mask->default_color * 257U));
                if (e.mask_rect.empty()) e.mask_rect = {0, 0, 1, 1};  // Photoshop needs a non-empty rectangle
                e.has_mask = true;
                e.channels.emplace_back(-2, channelBytes(planesOf(layer.mask->coverage, e.mask_rect, bps, {0}), e.mask_rect));
            }
            // Record.
            info.u32(static_cast<std::uint32_t>(e.rect.top));
            info.u32(static_cast<std::uint32_t>(e.rect.left));
            info.u32(static_cast<std::uint32_t>(e.rect.bottom));
            info.u32(static_cast<std::uint32_t>(e.rect.right));
            info.u16(static_cast<std::uint16_t>(e.channels.size()));
            for (const auto& [id, bytes] : e.channels) {
                info.u16(static_cast<std::uint16_t>(id));
                info.length(bytes.size());
            }
            info.ascii("8BIM");
            info.ascii(layer.pass_through && layer.kind == LayerKind::GroupFolder ? "pass" : blendKey(layer.mode));
            info.u8(layer.opacity);
            info.u8(layer.clipped ? 1 : 0);
            info.u8(static_cast<std::uint8_t>((layer.lock_transparency ? 0x01 : 0) | (layer.visible ? 0 : 0x02) | 0x08));
            info.u8(0);
            const auto extra = info.reserve(false);
            if (e.has_mask) {
                info.u32(20);
                info.u32(static_cast<std::uint32_t>(e.mask_rect.top));
                info.u32(static_cast<std::uint32_t>(e.mask_rect.left));
                info.u32(static_cast<std::uint32_t>(e.mask_rect.bottom));
                info.u32(static_cast<std::uint32_t>(e.mask_rect.right));
                info.u8(layer.mask->default_color);
                info.u8(static_cast<std::uint8_t>((layer.mask->linked ? 0 : 0x01) | (layer.mask->disabled ? 0x02 : 0)));
                info.u16(0);
            } else {
                info.u32(0);
            }
            if (layer.blend_ranges == kDefaultBlendRanges || layer.kind == LayerKind::GroupDivider) {
                info.u32(0);  // blending ranges
            } else {
                // Composite gray, then a pair for every channel record in order (alpha, R, G, B, then the mask's).
                info.u32(static_cast<std::uint32_t>(8 + 8 * e.channels.size()));
                for (std::size_t i = 0; i < 8; ++i) info.u8(layer.blend_ranges[i]);
                for (const auto& [id, bytes] : e.channels) {
                    if (id >= 0 && id <= 2) {
                        for (std::size_t i = 0; i < 8; ++i) info.u8(layer.blend_ranges[8 + 8 * static_cast<std::size_t>(id) + i]);
                    } else {
                        for (const std::uint8_t value : {0, 0, 255, 255, 0, 0, 255, 255}) info.u8(value);
                    }
                }
            }
            // Pascal name: ASCII only (non-ASCII becomes '?'); the full name goes into `luni`.
            std::string pascal;
            std::u16string utf16;
            for (std::size_t k = 0; k < layer.name.size();) {
                const auto c = static_cast<unsigned char>(layer.name[k]);
                const std::size_t len = c >= 0xF0 ? 4 : c >= 0xE0 ? 3 : c >= 0xC0 ? 2 : 1;
                std::uint32_t cp = len == 1 ? c : len == 2 ? (c & 0x1FU) : len == 3 ? (c & 0x0FU) : (c & 0x07U);
                for (std::size_t j = 1; j < len && k + j < layer.name.size(); ++j) cp = (cp << 6) | (static_cast<unsigned char>(layer.name[k + j]) & 0x3FU);
                k += len;
                if (pascal.size() < 255) pascal += cp < 0x80 ? static_cast<char>(cp) : '?';
                if (cp >= 0x10000) {
                    cp -= 0x10000;
                    utf16 += static_cast<char16_t>(0xD800 + (cp >> 10));
                    utf16 += static_cast<char16_t>(0xDC00 + (cp & 0x3FF));
                } else {
                    utf16 += static_cast<char16_t>(cp);
                }
            }
            info.u8(static_cast<std::uint8_t>(pascal.size()));
            info.ascii(pascal);
            for (std::size_t n = 1 + pascal.size(); n % 4 != 0; ++n) info.u8(0);
            {
                std::vector<std::uint8_t> luni;
                const auto count = static_cast<std::uint32_t>(utf16.size());
                for (int k = 3; k >= 0; --k) luni.push_back(static_cast<std::uint8_t>(count >> (8 * k)));
                for (const char16_t ch : utf16) {
                    luni.push_back(static_cast<std::uint8_t>(ch >> 8));
                    luni.push_back(static_cast<std::uint8_t>(ch));
                }
                while (luni.size() % 4) luni.push_back(0);
                additional(info, "luni", luni);
            }
            if (layer.kind != LayerKind::Pixel) {
                const std::uint32_t type = layer.kind == LayerKind::GroupDivider ? 3U : layer.group_open ? 1U : 2U;
                std::vector<std::uint8_t> section{0, 0, 0, static_cast<std::uint8_t>(type), '8', 'B', 'I', 'M'};
                const auto key = layer.pass_through ? std::string("pass") : blendKey(layer.mode);
                section.insert(section.end(), key.begin(), key.end());
                additional(info, "lsct", section);
            }
            if (layer.fill != 255) additional(info, "iOpa", {layer.fill, 0, 0, 0});
            for (const auto& block : layer.extra) {
                if (block.key.size() != 4 || block.key == "luni" || block.key == "lsct" || block.key == "lsdk" || block.key == "iOpa") continue;
                additional(info, block.key, block.data);
            }
            info.fill(extra, false);
        }
        for (const auto& e : encoded)
            for (const auto& [id, bytes] : e.channels) info.append(bytes);
        info.pad(4);
        const auto layer_info = info.take();

        // Layer and mask section: 8-bit data inline; 16/32-bit inside Lr16/Lr32.
        const auto section = out.reserve(psb);
        if (doc.depth == 8) {
            out.length(layer_info.size());
            out.append(layer_info);
            out.u32(0);  // global layer mask
        } else {
            out.length(0);
            out.u32(0);
            out.ascii("8BIM");
            out.ascii(doc.depth == 16 ? "Lr16" : "Lr32");
            out.length(layer_info.size());
            out.append(layer_info);
        }
        out.pad(4);
        out.fill(section, psb);

        // Merged composite with alpha.
        const Rect all{0, 0, static_cast<std::int32_t>(doc.height), static_cast<std::int32_t>(doc.width)};
        auto planes = planesOf(*doc.composite, all, bps, {0, 1, 2, 3});
        out.u16(1);
        out.append(encodePlanes(std::move(planes), doc.width, doc.height, bps, Compression::Rle, psb));
        return out.take();
    } catch (const std::bad_alloc&) {
        error = "not enough memory to write the document";
        return std::nullopt;
    } catch (const std::exception& e) {
        error = e.what();
        return std::nullopt;
    }
}

}  // namespace croptop::psd
