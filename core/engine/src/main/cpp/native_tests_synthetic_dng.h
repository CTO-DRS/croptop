#pragma once

// A tiny synthetic DNG for tests, so the LibRaw path is exercised without camera files:
// 64×48, RGGB, 12-bit data in 16-bit samples, black level 64, white level 4095, as-shot neutral for multipliers R 2 and
// B 1.5, and a ColorMatrix1 mapping XYZ (D65) to linear sRGB. The scene is a neutral gray once white balance is applied.

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <string>
#include <utility>
#include <vector>

namespace croptop::testing {

class TiffWriter final {
public:
    void entry(std::uint16_t tag, std::uint16_t type, std::uint32_t count, std::vector<std::uint8_t> data) {
        entries_.push_back({tag, type, count, std::move(data)});
    }
    void shorts(std::uint16_t tag, std::vector<std::uint16_t> v) {
        std::vector<std::uint8_t> d;
        for (auto x : v) put16(d, x);
        entry(tag, 3, static_cast<std::uint32_t>(v.size()), std::move(d));
    }
    void longs(std::uint16_t tag, std::vector<std::uint32_t> v) {
        std::vector<std::uint8_t> d;
        for (auto x : v) put32(d, x);
        entry(tag, 4, static_cast<std::uint32_t>(v.size()), std::move(d));
    }
    void bytes(std::uint16_t tag, std::vector<std::uint8_t> v) { entry(tag, 1, static_cast<std::uint32_t>(v.size()), v); }
    void ascii(std::uint16_t tag, const std::string& text) {
        std::vector<std::uint8_t> d(text.begin(), text.end());
        d.push_back(0);
        const auto count = static_cast<std::uint32_t>(d.size());  // before the move: argument evaluation order is unspecified
        entry(tag, 2, count, std::move(d));
    }
    void rationals(std::uint16_t tag, const std::vector<std::pair<std::uint32_t, std::uint32_t>>& v) {
        std::vector<std::uint8_t> d;
        for (auto [n, den] : v) {
            put32(d, n);
            put32(d, den);
        }
        entry(tag, 5, static_cast<std::uint32_t>(v.size()), std::move(d));
    }
    void srationals(std::uint16_t tag, const std::vector<double>& v) {
        std::vector<std::uint8_t> d;
        for (double x : v) {
            put32(d, static_cast<std::uint32_t>(static_cast<std::int32_t>(std::lround(x * 10000.0))));
            put32(d, 10000);
        }
        entry(tag, 10, static_cast<std::uint32_t>(v.size()), std::move(d));
    }
    /** Little-endian TIFF with the IFD at offset 8, entries sorted by tag, out-of-line values after it, then `payload`. */
    std::vector<std::uint8_t> finish(const std::vector<std::uint8_t>& payload, std::uint16_t strip_offset_tag) {
        std::sort(entries_.begin(), entries_.end(), [](const Entry& a, const Entry& b) { return a.tag < b.tag; });
        const std::size_t ifd_size = 2 + entries_.size() * 12 + 4;
        std::size_t external = 8 + ifd_size;
        std::vector<std::uint32_t> offsets;
        for (const auto& e : entries_) {
            offsets.push_back(static_cast<std::uint32_t>(external));
            if (e.data.size() > 4) external += (e.data.size() + 1) & ~std::size_t{1};
        }
        const auto payload_at = static_cast<std::uint32_t>(external);
        std::vector<std::uint8_t> out = {'I', 'I', 42, 0};
        put32(out, 8);
        put16(out, static_cast<std::uint16_t>(entries_.size()));
        for (std::size_t i = 0; i < entries_.size(); ++i) {
            auto data = entries_[i].data;
            if (entries_[i].tag == strip_offset_tag) {
                data.clear();
                put32(data, payload_at);
            }
            put16(out, entries_[i].tag);
            put16(out, entries_[i].type);
            put32(out, entries_[i].count);
            if (data.size() <= 4) {
                data.resize(4, 0);
                out.insert(out.end(), data.begin(), data.end());
            } else {
                put32(out, offsets[i]);
            }
        }
        put32(out, 0);
        for (const auto& e : entries_) {
            if (e.data.size() > 4) {
                out.insert(out.end(), e.data.begin(), e.data.end());
                if (e.data.size() & 1U) out.push_back(0);
            }
        }
        out.insert(out.end(), payload.begin(), payload.end());
        return out;
    }

private:
    struct Entry {
        std::uint16_t tag, type;
        std::uint32_t count;
        std::vector<std::uint8_t> data;
    };
    static void put16(std::vector<std::uint8_t>& v, std::uint16_t x) {
        v.push_back(static_cast<std::uint8_t>(x));
        v.push_back(static_cast<std::uint8_t>(x >> 8));
    }
    static void put32(std::vector<std::uint8_t>& v, std::uint32_t x) {
        put16(v, static_cast<std::uint16_t>(x));
        put16(v, static_cast<std::uint16_t>(x >> 16));
    }
    std::vector<Entry> entries_;
};

inline constexpr std::uint32_t kDngWidth = 64, kDngHeight = 48;
inline constexpr std::uint16_t kDngBlack = 64, kDngWhite = 4095;

inline std::vector<std::uint8_t> syntheticDng() {
    // Scene: neutral gray. With multipliers R 2, G 1, B 1.5 equal linear light needs raw R 1000, G 2000, B 1333 over black.
    std::vector<std::uint8_t> payload;
    for (std::uint32_t y = 0; y < kDngHeight; ++y) {
        for (std::uint32_t x = 0; x < kDngWidth; ++x) {
            const bool red = (y % 2 == 0) && (x % 2 == 0), blue = (y % 2 == 1) && (x % 2 == 1);
            const std::uint16_t v = static_cast<std::uint16_t>(kDngBlack + (red ? 1000 : blue ? 1333 : 2000));
            payload.push_back(static_cast<std::uint8_t>(v));
            payload.push_back(static_cast<std::uint8_t>(v >> 8));
        }
    }
    TiffWriter t;
    t.longs(254, {0});
    t.longs(256, {kDngWidth});
    t.longs(257, {kDngHeight});
    t.shorts(258, {16});
    t.shorts(259, {1});
    t.shorts(262, {32803});
    t.ascii(271, "CropTop");
    t.ascii(272, "Synthetic DNG");
    t.longs(273, {0});
    t.shorts(274, {1});
    t.shorts(277, {1});
    t.longs(278, {kDngHeight});
    t.longs(279, {static_cast<std::uint32_t>(payload.size())});
    t.shorts(284, {1});
    t.shorts(33421, {2, 2});
    t.bytes(33422, {0, 1, 1, 2});  // RGGB
    t.bytes(50706, {1, 4, 0, 0});
    t.bytes(50707, {1, 1, 0, 0});
    t.ascii(50708, "CropTop Synthetic");
    t.shorts(50714, {kDngBlack});
    t.longs(50717, {kDngWhite});
    t.srationals(50721, {3.2406, -1.5372, -0.4986, -0.9689, 1.8758, 0.0415, 0.0557, -0.2040, 1.0570});
    t.rationals(50728, {{1, 2}, {1, 1}, {2, 3}});
    t.shorts(50778, {21});
    return t.finish(payload, 273);
}

/** The same scene as a demosaiced "LinearRaw" DNG (3 samples per pixel, no CFA), like a phone's ProRAW or a sRAW conversion. */
inline std::vector<std::uint8_t> syntheticLinearDng() {
    std::vector<std::uint8_t> payload;
    for (std::uint32_t i = 0; i < kDngWidth * kDngHeight; ++i) {
        for (const std::uint16_t v : {static_cast<std::uint16_t>(kDngBlack + 1000), static_cast<std::uint16_t>(kDngBlack + 2000),
                                      static_cast<std::uint16_t>(kDngBlack + 1333)}) {
            payload.push_back(static_cast<std::uint8_t>(v));
            payload.push_back(static_cast<std::uint8_t>(v >> 8));
        }
    }
    TiffWriter t;
    t.longs(254, {0});
    t.longs(256, {kDngWidth});
    t.longs(257, {kDngHeight});
    t.shorts(258, {16, 16, 16});
    t.shorts(259, {1});
    t.shorts(262, {34892});  // LinearRaw
    t.ascii(271, "CropTop");
    t.ascii(272, "Synthetic Linear DNG");
    t.longs(273, {0});
    t.shorts(274, {1});
    t.shorts(277, {3});
    t.longs(278, {kDngHeight});
    t.longs(279, {static_cast<std::uint32_t>(payload.size())});
    t.shorts(284, {1});
    t.bytes(50706, {1, 4, 0, 0});
    t.bytes(50707, {1, 1, 0, 0});
    t.ascii(50708, "CropTop Synthetic Linear");
    t.shorts(50714, {kDngBlack, kDngBlack, kDngBlack});
    t.longs(50717, {kDngWhite});
    t.srationals(50721, {3.2406, -1.5372, -0.4986, -0.9689, 1.8758, 0.0415, 0.0557, -0.2040, 1.0570});
    t.rationals(50728, {{1, 2}, {1, 1}, {2, 3}});
    t.shorts(50778, {21});
    return t.finish(payload, 273);
}

}  // namespace croptop::testing
