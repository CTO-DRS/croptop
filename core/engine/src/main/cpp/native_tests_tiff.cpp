// Tests for the TIFF codec. Files are built by a small writer that follows the TIFF 6.0 layout.
#include "tiff_codec.h"

#include <gtest/gtest.h>
#include <zlib.h>

#include <array>
#include <bit>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <functional>
#include <map>
#include <random>
#include <string>
#include <vector>

#include "pixel_plane.h"

namespace {

namespace tf = croptop::tiff;
using Bytes = std::vector<std::uint8_t>;
using croptop::PixelFormat;
using croptop::TileStore;

std::array<float, 4> at(const TileStore& s, std::uint32_t x, std::uint32_t y) {
    std::array<float, 4> c{};
    const auto t = s.readTile({static_cast<std::int32_t>(x / 256), static_cast<std::int32_t>(y / 256)});
    if (t) croptop::paint::detail::readPixel(s.format(), t->data() + (static_cast<std::size_t>(y % 256) * 256 + x % 256) * s.bytes_per_pixel(), c.data());
    return c;
}

Bytes lzwEncode(const Bytes& in) {
    Bytes out;
    std::uint64_t acc = 0;
    int acc_bits = 0, width = 9;
    const auto emit = [&](std::uint32_t code) {
        acc = (acc << width) | code;
        acc_bits += width;
        while (acc_bits >= 8) {
            out.push_back(static_cast<std::uint8_t>(acc >> (acc_bits - 8)));
            acc_bits -= 8;
        }
    };
    std::map<std::pair<std::int32_t, std::uint8_t>, std::uint32_t> dict;
    std::uint32_t next = 258;
    emit(256);
    std::int32_t current = -1;
    for (const auto byte : in) {
        if (current < 0) {
            current = byte;
            continue;
        }
        const auto it = dict.find({current, byte});
        if (it != dict.end()) {
            current = static_cast<std::int32_t>(it->second);
            continue;
        }
        emit(static_cast<std::uint32_t>(current));
        dict[{current, byte}] = next++;
        if (next > (1U << width) - 1U && width < 12) ++width;
        if (next >= 4094) {
            emit(256);
            dict.clear();
            next = 258;
            width = 9;
        }
        current = byte;
    }
    if (current >= 0) {
        emit(static_cast<std::uint32_t>(current));
        ++next;
        if (next > (1U << width) - 1U && width < 12) ++width;
    }
    emit(257);
    if (acc_bits > 0) out.push_back(static_cast<std::uint8_t>(acc << (8 - acc_bits)));
    return out;
}

Bytes packBits(const Bytes& in) {
    Bytes out;
    std::size_t i = 0;
    while (i < in.size()) {
        std::size_t run = 1;
        while (i + run < in.size() && run < 128 && in[i + run] == in[i]) ++run;
        if (run >= 2) {
            out.push_back(static_cast<std::uint8_t>(static_cast<std::int8_t>(1 - static_cast<int>(run))));
            out.push_back(in[i]);
            i += run;
        } else {
            out.push_back(0);
            out.push_back(in[i++]);
        }
    }
    return out;
}

struct Spec {
    std::uint32_t w = 40, h = 30;
    int bits = 8, spp = 3;
    std::uint16_t photometric = 2;
    std::uint16_t compression = 1;  // 1, 5, 8, 32773
    int predictor = 1;
    bool little = true, big = false, planar = false, floating = false;
    std::uint32_t tile = 0;          // 0 = strips
    std::uint32_t rows_per_strip = 7;
    int extra = -1;                  // ExtraSamples value
    int orientation = 0;
    Bytes palette_rgb;               // 3 × 2^bits entries (u16)
    std::function<double(std::uint32_t x, std::uint32_t y, int s)> sample;
};

struct TiffWriter {
    Bytes b;
    bool little = true;
    void put(std::uint64_t v, int n) {
        for (int i = 0; i < n; ++i) b.push_back(static_cast<std::uint8_t>(little ? v >> (8 * i) : v >> (8 * (n - 1 - i))));
    }
    void putAt(std::size_t at, std::uint64_t v, int n) {
        for (int i = 0; i < n; ++i) b[at + static_cast<std::size_t>(i)] = static_cast<std::uint8_t>(little ? v >> (8 * i) : v >> (8 * (n - 1 - i)));
    }
};

/** Packs a chunk's rows (w × rows, `samples` per pixel) into bytes in the file's order. */
Bytes rawChunk(const Spec& s, std::uint32_t x0, std::uint32_t y0, std::uint32_t w, std::uint32_t rows, int plane) {
    const int samples = s.planar ? 1 : s.spp;
    Bytes out;
    for (std::uint32_t y = 0; y < rows; ++y) {
        std::uint32_t bitbuf = 0;
        int nbits = 0;
        Bytes row;
        for (std::uint32_t x = 0; x < w; ++x) {
            for (int k = 0; k < samples; ++k) {
                const int channel = s.planar ? plane : k;
                const bool inside = x0 + x < s.w && y0 + y < s.h;
                const double v = inside ? s.sample(x0 + x, y0 + y, channel) : 0.0;
                if (s.bits < 8) {
                    const auto q = static_cast<std::uint32_t>(std::lround(v));  // palette/bilevel: raw values
                    bitbuf = (bitbuf << s.bits) | q;
                    nbits += s.bits;
                    if (nbits == 8) row.push_back(static_cast<std::uint8_t>(bitbuf)), bitbuf = 0, nbits = 0;
                } else if (s.bits == 8) {
                    row.push_back(static_cast<std::uint8_t>(s.photometric == 3 ? std::lround(v) : std::lround(v * 255)));
                } else if (s.bits == 16) {
                    const auto q = static_cast<std::uint16_t>(std::lround(v * 65535));
                    s.little ? (row.push_back(static_cast<std::uint8_t>(q)), row.push_back(static_cast<std::uint8_t>(q >> 8)))
                             : (row.push_back(static_cast<std::uint8_t>(q >> 8)), row.push_back(static_cast<std::uint8_t>(q)));
                } else {
                    const auto q = std::bit_cast<std::uint32_t>(static_cast<float>(v));
                    for (int i = 0; i < 4; ++i) row.push_back(static_cast<std::uint8_t>(s.little ? q >> (8 * i) : q >> (24 - 8 * i)));
                }
            }
        }
        if (nbits > 0) row.push_back(static_cast<std::uint8_t>(bitbuf << (8 - nbits)));
        // Predictors.
        const std::size_t n = static_cast<std::size_t>(w) * static_cast<std::size_t>(samples);
        if (s.predictor == 2 && s.bits == 8) {
            for (std::size_t i = n; i-- > static_cast<std::size_t>(samples);) row[i] = static_cast<std::uint8_t>(row[i] - row[i - static_cast<std::size_t>(samples)]);
        } else if (s.predictor == 2 && s.bits == 16) {
            const auto get = [&](std::size_t i) { return static_cast<std::uint16_t>(s.little ? row[i * 2] | (row[i * 2 + 1] << 8) : (row[i * 2] << 8) | row[i * 2 + 1]); };
            for (std::size_t i = n; i-- > static_cast<std::size_t>(samples);) {
                const auto v = static_cast<std::uint16_t>(get(i) - get(i - static_cast<std::size_t>(samples)));
                row[i * 2] = static_cast<std::uint8_t>(s.little ? v : v >> 8);
                row[i * 2 + 1] = static_cast<std::uint8_t>(s.little ? v >> 8 : v);
            }
        } else if (s.predictor == 3) {
            Bytes planes(n * 4);
            for (std::size_t i = 0; i < n; ++i)
                for (std::size_t k = 0; k < 4; ++k) planes[k * n + i] = row[i * 4 + (s.little ? 3 - k : k)];
            for (std::size_t i = n * 4; i-- > static_cast<std::size_t>(samples);) planes[i] = static_cast<std::uint8_t>(planes[i] - planes[i - static_cast<std::size_t>(samples)]);
            row = planes;
        }
        out.insert(out.end(), row.begin(), row.end());
    }
    return out;
}

Bytes compressChunk(const Spec& s, const Bytes& raw) {
    switch (s.compression) {
        case 5: return lzwEncode(raw);
        case 8: {
            uLongf size = compressBound(static_cast<uLong>(raw.size()));
            Bytes z(size);
            compress(z.data(), &size, raw.data(), static_cast<uLong>(raw.size()));
            z.resize(size);
            return z;
        }
        case 32773: return packBits(raw);
        default: return raw;
    }
}

Bytes writeTiff(const Spec& s) {
    TiffWriter t;
    t.little = s.little;
    t.b = s.little ? Bytes{'I', 'I'} : Bytes{'M', 'M'};
    t.put(s.big ? 43 : 42, 2);
    std::size_t first_ifd_at = 0;
    if (s.big) {
        t.put(8, 2);
        t.put(0, 2);
        first_ifd_at = t.b.size();
        t.put(0, 8);
    } else {
        first_ifd_at = t.b.size();
        t.put(0, 4);
    }
    const std::uint32_t cw = s.tile ? s.tile : s.w, ch = s.tile ? s.tile : s.rows_per_strip;
    const std::uint32_t across = (s.w + cw - 1) / cw, down = (s.h + ch - 1) / ch;
    std::vector<std::uint64_t> offsets, counts;
    for (int p = 0; p < (s.planar ? s.spp : 1); ++p)
        for (std::uint32_t c = 0; c < across * down; ++c) {
            const std::uint32_t x0 = (c % across) * cw, y0 = (c / across) * ch;
            const std::uint32_t rows = s.tile ? ch : std::min(ch, s.h - y0);
            const auto data = compressChunk(s, rawChunk(s, x0, y0, cw, rows, p));
            offsets.push_back(t.b.size());
            counts.push_back(data.size());
            t.b.insert(t.b.end(), data.begin(), data.end());
        }
    const int offset_size = s.big ? 8 : 4;
    const auto arrayAt = [&](const std::vector<std::uint64_t>& v, int size) {
        const auto at = t.b.size();
        for (const auto x : v) t.put(x, size);
        return at;
    };
    struct Tag {
        std::uint16_t tag, type;
        std::uint64_t count, value;
        bool inline_value;
    };
    std::vector<Tag> tags;
    const auto shortTag = [&](std::uint16_t tag, std::uint64_t v) { tags.push_back({tag, 3, 1, v, true}); };
    const auto longTag = [&](std::uint16_t tag, std::uint64_t v) { tags.push_back({tag, 4, 1, v, true}); };
    longTag(256, s.w);
    longTag(257, s.h);
    std::vector<std::uint64_t> bits(static_cast<std::size_t>(s.spp), static_cast<std::uint64_t>(s.bits));
    if (s.spp == 1) shortTag(258, static_cast<std::uint64_t>(s.bits));
    else tags.push_back({258, 3, static_cast<std::uint64_t>(s.spp), arrayAt(bits, 2), false});
    shortTag(259, s.compression);
    shortTag(262, s.photometric);
    const auto offsets_at = arrayAt(offsets, offset_size);
    const auto counts_at = arrayAt(counts, offset_size);
    const std::uint16_t offset_type = s.big ? 16 : 4;
    if (!s.tile) tags.push_back({273, offset_type, offsets.size(), offsets.size() == 1 ? offsets[0] : offsets_at, offsets.size() == 1});
    if (s.orientation) shortTag(274, static_cast<std::uint64_t>(s.orientation));
    shortTag(277, static_cast<std::uint64_t>(s.spp));
    if (!s.tile) longTag(278, s.rows_per_strip);
    if (!s.tile) tags.push_back({279, offset_type, counts.size(), counts.size() == 1 ? counts[0] : counts_at, counts.size() == 1});
    shortTag(284, s.planar ? 2 : 1);
    if (s.predictor != 1) shortTag(317, static_cast<std::uint64_t>(s.predictor));
    if (!s.palette_rgb.empty()) tags.push_back({320, 3, s.palette_rgb.size() / 2, arrayAt([&] {
                                                                                    std::vector<std::uint64_t> v;
                                                                                    for (std::size_t i = 0; i < s.palette_rgb.size(); i += 2) v.push_back(s.palette_rgb[i] | (s.palette_rgb[i + 1] << 8));
                                                                                    return v;
                                                                                }(), 2), false});
    if (s.tile) {
        longTag(322, s.tile);
        longTag(323, s.tile);
        tags.push_back({324, offset_type, offsets.size(), offsets_at, false});
        tags.push_back({325, offset_type, counts.size(), counts_at, false});
    }
    if (s.extra >= 0) shortTag(338, static_cast<std::uint64_t>(s.extra));
    if (s.floating) shortTag(339, 3);
    std::sort(tags.begin(), tags.end(), [](const Tag& a, const Tag& b) { return a.tag < b.tag; });
    if (t.b.size() % 2) t.b.push_back(0);
    const auto ifd_at = t.b.size();
    t.put(tags.size(), s.big ? 8 : 2);
    for (const auto& tag : tags) {
        t.put(tag.tag, 2);
        t.put(tag.type, 2);
        t.put(tag.count, s.big ? 8 : 4);
        const int type_size = tag.type == 3 ? 2 : tag.type == 16 ? 8 : 4;
        if (tag.inline_value) {
            t.put(tag.value, type_size);
            for (int i = type_size; i < offset_size; ++i) t.b.push_back(0);
        } else if (tag.count * static_cast<std::uint64_t>(type_size) <= static_cast<std::uint64_t>(offset_size)) {
            // Small arrays live inside the entry (4 bytes in TIFF, 8 in BigTIFF): copy them in.
            const auto n = static_cast<std::size_t>(tag.count) * static_cast<std::size_t>(type_size);
            const Bytes copy(t.b.begin() + static_cast<std::ptrdiff_t>(tag.value), t.b.begin() + static_cast<std::ptrdiff_t>(tag.value + n));
            t.b.insert(t.b.end(), copy.begin(), copy.end());
            for (std::size_t i = n; i < static_cast<std::size_t>(offset_size); ++i) t.b.push_back(0);
        } else {
            t.put(tag.value, offset_size);
        }
    }
    t.put(0, offset_size);
    t.putAt(first_ifd_at, ifd_at, offset_size);
    return t.b;
}

double gradient(std::uint32_t x, std::uint32_t y, int s) { return std::fmod((x * 7 + y * 3 + static_cast<std::uint32_t>(s) * 40) / 255.0, 1.0); }

void expectGradient(const tf::Image& img, const Spec& s, double tolerance) {
    ASSERT_EQ(img.pixels.width(), s.w);
    ASSERT_EQ(img.pixels.height(), s.h);
    for (std::uint32_t y = 0; y < s.h; y += 3)
        for (std::uint32_t x = 0; x < s.w; x += 3) {
            const auto p = at(img.pixels, x, y);
            for (int c = 0; c < 3; ++c) ASSERT_NEAR(p[static_cast<std::size_t>(c)], gradient(x, y, c), tolerance) << x << "," << y << " c" << c;
        }
}

TEST(Tiff, DecodesEveryCompressionPredictorAndByteOrder) {
    for (const std::uint16_t compression : {1, 5, 8, 32773})
        for (const bool little : {true, false})
            for (const int predictor : {1, 2}) {
                if (predictor == 2 && compression == 1) continue;
                Spec s;
                s.compression = compression;
                s.little = little;
                s.predictor = predictor;
                s.sample = gradient;
                std::string error;
                const auto img = tf::decode(writeTiff(s), error);
                ASSERT_TRUE(img) << error << " compression " << compression << " little " << little << " predictor " << predictor;
                expectGradient(*img, s, 0.6 / 255.0);
            }
}

TEST(Tiff, LzwHandlesLongRunsAcrossCodeWidths) {
    Spec s;
    s.w = 700;
    s.h = 90;
    s.rows_per_strip = 90;
    s.compression = 5;
    s.sample = [](std::uint32_t x, std::uint32_t y, int c) { return ((x * 13 + y * 7 + static_cast<std::uint32_t>(c)) % 251) / 255.0; };
    std::string error;
    const auto img = tf::decode(writeTiff(s), error);
    ASSERT_TRUE(img) << error;
    for (std::uint32_t y = 0; y < s.h; y += 11)
        for (std::uint32_t x = 0; x < s.w; x += 13) ASSERT_NEAR(at(img->pixels, x, y)[1], s.sample(x, y, 1), 0.6 / 255.0) << x << "," << y;
}

TEST(Tiff, DecodesTilesPlanar16BitFloatAndBigTiff) {
    Spec tiled;
    tiled.tile = 16;
    tiled.bits = 16;
    tiled.compression = 8;
    tiled.predictor = 2;
    tiled.sample = gradient;
    std::string error;
    auto img = tf::decode(writeTiff(tiled), error);
    ASSERT_TRUE(img) << error;
    EXPECT_EQ(img->pixels.format(), PixelFormat::RGBA16);
    expectGradient(*img, tiled, 1e-4);

    Spec planar;
    planar.planar = true;
    planar.little = false;
    planar.compression = 32773;
    planar.sample = gradient;
    const auto planar_img = tf::decode(writeTiff(planar), error);
    ASSERT_TRUE(planar_img) << error;
    expectGradient(*planar_img, planar, 0.6 / 255.0);

    Spec floating;
    floating.bits = 32;
    floating.floating = true;
    floating.compression = 8;
    floating.predictor = 3;
    floating.sample = [](std::uint32_t x, std::uint32_t y, int c) { return (x + y) * 0.01 + c * 0.5; };
    const auto float_img = tf::decode(writeTiff(floating), error);
    ASSERT_TRUE(float_img) << error;
    EXPECT_EQ(float_img->pixels.format(), PixelFormat::RGBA32F);
    EXPECT_FLOAT_EQ(at(float_img->pixels, 7, 5)[2], static_cast<float>(12 * 0.01 + 1.0));

    Spec big;
    big.big = true;
    big.little = false;
    big.compression = 8;
    big.sample = gradient;
    const auto big_img = tf::decode(writeTiff(big), error);
    ASSERT_TRUE(big_img) << error;
    expectGradient(*big_img, big, 0.6 / 255.0);
}

TEST(Tiff, GrayPaletteBilevelCmykAlphaAndOrientation) {
    std::string error;
    Spec gray;
    gray.spp = 1;
    gray.photometric = 0;  // WhiteIsZero
    gray.sample = [](std::uint32_t x, std::uint32_t, int) { return x / 39.0; };
    const auto g = tf::decode(writeTiff(gray), error);
    ASSERT_TRUE(g) << error;
    EXPECT_NEAR(at(g->pixels, 39, 3)[0], 0.0, 0.01) << "white-is-zero inverts";

    Spec palette;
    palette.spp = 1;
    palette.bits = 4;
    palette.photometric = 3;
    for (int c = 0; c < 3; ++c)
        for (int i = 0; i < 16; ++i) {
            const std::uint16_t v = c == 0 ? static_cast<std::uint16_t>(i * 4369) : 0;
            palette.palette_rgb.push_back(static_cast<std::uint8_t>(v));
            palette.palette_rgb.push_back(static_cast<std::uint8_t>(v >> 8));
        }
    palette.sample = [](std::uint32_t x, std::uint32_t, int) { return static_cast<double>(x % 16); };
    const auto p = tf::decode(writeTiff(palette), error);
    ASSERT_TRUE(p) << error;
    EXPECT_NEAR(at(p->pixels, 15, 2)[0], 1.0, 0.01);
    EXPECT_NEAR(at(p->pixels, 17, 2)[0], 1.0 / 15.0, 0.01);

    Spec bilevel;
    bilevel.spp = 1;
    bilevel.bits = 1;
    bilevel.photometric = 1;
    bilevel.w = 37;  // rows end mid-byte
    bilevel.sample = [](std::uint32_t x, std::uint32_t, int) { return static_cast<double>(x % 3 == 0); };
    const auto b = tf::decode(writeTiff(bilevel), error);
    ASSERT_TRUE(b) << error;
    EXPECT_EQ(at(b->pixels, 36, 0)[0], 1.0F);
    EXPECT_EQ(at(b->pixels, 35, 9)[0], 0.0F);

    Spec cmyk;
    cmyk.spp = 4;
    cmyk.photometric = 5;
    cmyk.sample = [](std::uint32_t, std::uint32_t, int c) { return c == 1 ? 1.0 : 0.0; };  // pure magenta ink
    const auto k = tf::decode(writeTiff(cmyk), error);
    ASSERT_TRUE(k) << error;
    const auto m = at(k->pixels, 3, 3);
    EXPECT_NEAR(m[0], 1.0, 0.01);
    EXPECT_NEAR(m[1], 0.0, 0.01);
    EXPECT_FALSE(k->warnings.empty());

    Spec rgba;
    rgba.spp = 4;
    rgba.extra = 1;  // associated alpha
    rgba.sample = [](std::uint32_t, std::uint32_t, int c) { return c == 3 ? 0.5 : c == 0 ? 0.25 : 0.0; };
    const auto a = tf::decode(writeTiff(rgba), error);
    ASSERT_TRUE(a) << error;
    EXPECT_NEAR(at(a->pixels, 1, 1)[0], 0.5, 0.01) << "associated alpha is un-premultiplied";
    EXPECT_NEAR(at(a->pixels, 1, 1)[3], 0.5, 0.01);

    Spec rotated;
    rotated.orientation = 6;  // rotate 90° clockwise to display
    rotated.sample = gradient;
    const auto r = tf::decode(writeTiff(rotated), error);
    ASSERT_TRUE(r) << error;
    EXPECT_EQ(r->pixels.width(), 30U);
    EXPECT_EQ(r->pixels.height(), 40U);
    EXPECT_NEAR(at(r->pixels, 29, 0)[0], gradient(0, 0, 0), 0.01) << "the stored top-left lands top-right";
}

TEST(Tiff, EncodeRoundTripsEveryDepthWithIcc) {
    for (const auto format : {PixelFormat::RGBA8, PixelFormat::RGBA16, PixelFormat::RGBA32F}) {
        for (const bool deflate : {true, false}) {
            TileStore source(format, 300, 280);
            const auto bpp = source.bytes_per_pixel();
            for (std::int32_t ty = 0; ty < 2; ++ty)
                for (std::int32_t tx = 0; tx < 2; ++tx) {
                    croptop::TileBuffer t(source.tile_byte_size(), 0);
                    for (std::uint32_t y = 0; y < 256; ++y)
                        for (std::uint32_t x = 0; x < 256; ++x) {
                            const std::array<float, 4> px{((tx * 256 + static_cast<std::int32_t>(x)) % 300) / 299.0F, y / 255.0F, 0.3F, (x % 2) ? 1.0F : 0.5F};
                            croptop::paint::detail::writePixel(format, t.data() + (y * 256 + x) * bpp, px.data());
                        }
                    ASSERT_TRUE(source.writeTile({tx, ty}, t));
                }
            std::string error;
            tf::EncodeOptions options;
            options.deflate = deflate;
            options.icc_profile = {1, 2, 3, 4, 5};
            const auto bytes = tf::encode(source, error, options);
            ASSERT_TRUE(bytes) << error;
            const auto back = tf::decode(*bytes, error);
            ASSERT_TRUE(back) << error;
            EXPECT_EQ(back->pixels.format(), format);
            EXPECT_EQ(back->icc_profile, (Bytes{1, 2, 3, 4, 5}));
            for (std::uint32_t y = 0; y < 280; y += 17)
                for (std::uint32_t x = 0; x < 300; x += 13) ASSERT_EQ(at(back->pixels, x, y), at(source, x, y)) << x << "," << y;
        }
    }
}

TEST(Tiff, RejectsWhatItCannotRead) {
    std::string error;
    EXPECT_FALSE(tf::decode(Bytes{'I', 'I', 42}, error));
    EXPECT_FALSE(tf::decode(Bytes{'P', 'K', 3, 4, 0, 0, 0, 0}, error));
    Spec jpeg;
    jpeg.compression = 7;
    jpeg.sample = gradient;
    EXPECT_FALSE(tf::decode(writeTiff(jpeg), error));
    EXPECT_NE(error.find("JPEG"), std::string::npos);
    Spec ycc;
    ycc.photometric = 6;
    ycc.sample = gradient;
    EXPECT_FALSE(tf::decode(writeTiff(ycc), error));
    TileStore a16(PixelFormat::A16, 4, 4);
    EXPECT_FALSE(tf::encode(a16, error));
}

TEST(Tiff, SurvivesRandomMutations) {
    std::mt19937 random(9);
    std::vector<Bytes> seeds;
    for (const std::uint16_t c : {1, 5, 8, 32773}) {
        Spec s;
        s.compression = c;
        s.predictor = c == 1 ? 1 : 2;
        s.sample = gradient;
        seeds.push_back(writeTiff(s));
    }
    Spec tiled;
    tiled.tile = 16;
    tiled.compression = 8;
    tiled.sample = gradient;
    seeds.push_back(writeTiff(tiled));
    tf::Limits limits;
    limits.max_pixels = 1 << 22;
    limits.max_working_bytes = 64 << 20;
    int opened = 0;
    for (int i = 0; i < 3000; ++i) {
        auto bytes = seeds[static_cast<std::size_t>(i) % seeds.size()];
        for (int e = 0; e < 1 + static_cast<int>(random() % 6); ++e) {
            const auto at_byte = random() % bytes.size();
            switch (random() % 3) {
                case 0: bytes[at_byte] = static_cast<std::uint8_t>(random()); break;
                case 1: bytes[at_byte] = random() % 2 ? 0xFF : 0x00; break;
                default: bytes.resize(std::max<std::size_t>(1, at_byte)); break;
            }
        }
        std::string error;
        if (tf::decode(bytes, error, limits)) ++opened;
    }
    std::printf("[tiff] %d / 3000 mutated files still decoded\n", opened);
}

}  // namespace
