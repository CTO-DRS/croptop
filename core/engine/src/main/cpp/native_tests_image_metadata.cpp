// Tests for metadata and embedded-preview extraction.
#include "image_metadata.h"

#include <gtest/gtest.h>

#include <algorithm>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <map>
#include <random>
#include <string>
#include <vector>

namespace {

namespace md = croptop::metadata;
using Bytes = std::vector<std::uint8_t>;

/** A minimal JPEG: SOI, SOF (marker `sof`) with the size, EOI. */
Bytes miniJpeg(std::uint16_t w, std::uint16_t h, std::uint8_t sof = 0xC0) {
    return Bytes{0xFF, 0xD8, 0xFF, sof, 0, 17, 8, static_cast<std::uint8_t>(h >> 8), static_cast<std::uint8_t>(h), static_cast<std::uint8_t>(w >> 8),
                 static_cast<std::uint8_t>(w), 3, 1, 0x22, 0, 2, 0x11, 1, 3, 0x11, 1, 0xFF, 0xD9};
}

/** Builds TIFF structures; IFDs are laid out after the header with out-of-line data appended. */
struct TiffBuilder {
    bool little = true;
    Bytes b;
    struct Tag {
        std::uint16_t tag, type;
        std::uint32_t count;
        Bytes value;  // raw value bytes in file order
    };
    void put(std::uint64_t v, int n, Bytes& out) const {
        for (int i = 0; i < n; ++i) out.push_back(static_cast<std::uint8_t>(little ? v >> (8 * i) : v >> (8 * (n - 1 - i))));
    }
    Bytes u16(std::uint32_t v) const { Bytes o; put(v, 2, o); return o; }
    Bytes u32(std::uint32_t v) const { Bytes o; put(v, 4, o); return o; }
    Bytes rational(std::uint32_t n, std::uint32_t d) const { Bytes o; put(n, 4, o); put(d, 4, o); return o; }
    Bytes text(const std::string& s) const { Bytes o(s.begin(), s.end()); o.push_back(0); return o; }

    TiffBuilder() { b = {'I', 'I', 42, 0, 8, 0, 0, 0}; }
    void begin(bool le) {
        little = le;
        b = le ? Bytes{'I', 'I', 42, 0} : Bytes{'M', 'M', 0, 42};
        put(8, 4, b);
    }
    /** Writes an IFD at the end; returns its offset. `next` patches later via patchNext. */
    std::uint32_t ifd(std::vector<Tag> tags) {
        std::sort(tags.begin(), tags.end(), [](const Tag& a, const Tag& c) { return a.tag < c.tag; });
        if (b.size() % 2) b.push_back(0);
        const auto at = static_cast<std::uint32_t>(b.size());
        const std::size_t data_start = at + 2 + tags.size() * 12 + 4;
        Bytes head, data;
        put(tags.size(), 2, head);
        for (const auto& t : tags) {
            put(t.tag, 2, head);
            put(t.type, 2, head);
            put(t.count, 4, head);
            if (t.value.size() <= 4) {
                Bytes v = t.value;
                v.resize(4, 0);
                head.insert(head.end(), v.begin(), v.end());
            } else {
                put(data_start + data.size(), 4, head);
                data.insert(data.end(), t.value.begin(), t.value.end());
                if (data.size() % 2) data.push_back(0);
            }
        }
        put(0, 4, head);
        b.insert(b.end(), head.begin(), head.end());
        b.insert(b.end(), data.begin(), data.end());
        return at;
    }
    void patchNext(std::uint32_t ifd_at, std::uint32_t next) {
        const std::uint32_t count = little ? (b[ifd_at] | (b[ifd_at + 1] << 8)) : ((b[ifd_at] << 8) | b[ifd_at + 1]);
        const std::size_t at = ifd_at + 2 + count * 12;
        Bytes v;
        put(next, 4, v);
        std::memcpy(b.data() + at, v.data(), 4);
    }
    void setFirst(std::uint32_t ifd_at) {
        Bytes v;
        put(ifd_at, 4, v);
        std::memcpy(b.data() + 4, v.data(), 4);
    }
    std::uint32_t blob(const Bytes& data) {
        if (b.size() % 2) b.push_back(0);
        const auto at = static_cast<std::uint32_t>(b.size());
        b.insert(b.end(), data.begin(), data.end());
        return at;
    }
};

/** EXIF block: IFD0 (make/model/orientation → EXIF + GPS IFDs), IFD1 with a JPEG thumbnail. */
Bytes exifBlock(bool little) {
    TiffBuilder t;
    t.begin(little);
    const auto thumb_at = t.blob(miniJpeg(160, 120));
    const auto exif_ifd = t.ifd({{33434, 5, 1, t.rational(1, 250)},
                                 {33437, 5, 1, t.rational(28, 10)},
                                 {34855, 3, 1, t.u16(400)},
                                 {36867, 2, 20, t.text("2026:09:26 06:30:00")},
                                 {37380, 10, 1, t.rational(static_cast<std::uint32_t>(-1), 3)},
                                 {37386, 5, 1, t.rational(50, 1)},
                                 {41989, 3, 1, t.u16(75)},
                                 {42036, 2, 12, t.text("EF 50mm f/2")}});
    const auto gps_ifd = t.ifd({{1, 2, 2, t.text("S")},
                                {2, 5, 3, [&] { Bytes v = t.rational(1, 1); auto m = t.rational(30, 1); auto s = t.rational(0, 1); v.insert(v.end(), m.begin(), m.end()); v.insert(v.end(), s.begin(), s.end()); return v; }()},
                                {3, 2, 2, t.text("E")},
                                {4, 5, 3, [&] { Bytes v = t.rational(36, 1); auto m = t.rational(49, 1); auto s = t.rational(18, 1); v.insert(v.end(), m.begin(), m.end()); v.insert(v.end(), s.begin(), s.end()); return v; }()},
                                {5, 1, 1, Bytes{1}},
                                {6, 5, 1, t.rational(15, 1)}});
    const auto ifd1 = t.ifd({{259, 3, 1, t.u16(6)}, {513, 4, 1, t.u32(thumb_at)}, {514, 4, 1, t.u32(static_cast<std::uint32_t>(miniJpeg(160, 120).size()))}});
    const auto ifd0 = t.ifd({{271, 2, 6, t.text("Canon")},
                             {272, 2, 8, t.text("EOS R6 ")},
                             {274, 3, 1, t.u16(6)},
                             {34665, 4, 1, t.u32(exif_ifd)},
                             {34853, 4, 1, t.u32(gps_ifd)}});
    t.patchNext(ifd0, ifd1);
    t.setFirst(ifd0);
    return t.b;
}

Bytes segment(std::uint8_t marker, const Bytes& payload) {
    Bytes out{0xFF, marker, static_cast<std::uint8_t>((payload.size() + 2) >> 8), static_cast<std::uint8_t>(payload.size() + 2)};
    out.insert(out.end(), payload.begin(), payload.end());
    return out;
}

Bytes jpegFile(bool little) {
    Bytes f{0xFF, 0xD8};
    Bytes app1{'E', 'x', 'i', 'f', 0, 0};
    const auto exif = exifBlock(little);
    app1.insert(app1.end(), exif.begin(), exif.end());
    const auto a = segment(0xE1, app1);
    f.insert(f.end(), a.begin(), a.end());
    const std::string xmp_key = "http://ns.adobe.com/xap/1.0/";
    Bytes xmp(xmp_key.begin(), xmp_key.end());
    xmp.push_back(0);
    const std::string packet = "<x:xmpmeta>rating 5</x:xmpmeta>";
    xmp.insert(xmp.end(), packet.begin(), packet.end());
    const auto x = segment(0xE1, xmp);
    f.insert(f.end(), x.begin(), x.end());
    const std::string ps = "Photoshop 3.0";
    Bytes irb(ps.begin(), ps.end());
    irb.push_back(0);
    const Bytes iptc{0x1C, 0x02, 0x78, 0x00, 0x05, 'h', 'e', 'l', 'l', 'o'};
    const Bytes block{'8', 'B', 'I', 'M', 0x04, 0x04, 0, 0, 0, 0, 0, static_cast<std::uint8_t>(iptc.size())};
    irb.insert(irb.end(), block.begin(), block.end());
    irb.insert(irb.end(), iptc.begin(), iptc.end());
    const auto i = segment(0xED, irb);
    f.insert(f.end(), i.begin(), i.end());
    const auto sof = segment(0xC0, Bytes{8, 0x0F, 0xA0, 0x17, 0x70, 3, 1, 0x22, 0, 2, 0x11, 1, 3, 0x11, 1});  // 6000 × 4000
    f.insert(f.end(), sof.begin(), sof.end());
    f.insert(f.end(), {0xFF, 0xDA, 0, 2, 0xFF, 0xD9});
    return f;
}

TEST(ImageMetadata, ReadsExifGpsXmpIptcAndThumbnailFromJpeg) {
    for (const bool little : {true, false}) {
        std::string error;
        const auto m = md::read(jpegFile(little), error);
        ASSERT_TRUE(m) << error;
        EXPECT_EQ(m->container, "jpeg");
        EXPECT_EQ(m->width, 6000U);
        EXPECT_EQ(m->height, 4000U);
        ASSERT_TRUE(m->exif);
        const auto& e = *m->exif;
        EXPECT_EQ(e.make, "Canon");
        EXPECT_EQ(e.model, "EOS R6") << "trailing spaces trimmed";
        EXPECT_EQ(e.lens_model, "EF 50mm f/2");
        EXPECT_EQ(e.date_time_original, "2026:09:26 06:30:00");
        EXPECT_DOUBLE_EQ(*e.exposure_time, 1.0 / 250.0);
        EXPECT_DOUBLE_EQ(*e.f_number, 2.8);
        EXPECT_NEAR(*e.exposure_bias, -1.0 / 3.0, 1e-12);
        EXPECT_DOUBLE_EQ(*e.focal_length, 50.0);
        EXPECT_EQ(*e.focal_length_35mm, 75U);
        EXPECT_EQ(*e.iso, 400U);
        EXPECT_EQ(*e.orientation, 6);
        EXPECT_NEAR(*e.gps_latitude, -1.5, 1e-12);
        EXPECT_NEAR(*e.gps_longitude, 36.0 + 49.0 / 60 + 18.0 / 3600, 1e-12);
        EXPECT_DOUBLE_EQ(*e.gps_altitude, -15.0);
        EXPECT_EQ(std::string(m->xmp.begin(), m->xmp.end()), "<x:xmpmeta>rating 5</x:xmpmeta>");
        EXPECT_EQ(m->iptc.size(), 10U);
        ASSERT_EQ(m->previews.size(), 1U);
        EXPECT_EQ(m->previews[0].kind, md::PreviewKind::ExifThumbnail);
        EXPECT_EQ(m->previews[0].width, 160U);
        EXPECT_EQ(m->previews[0].height, 120U);
        const auto file = jpegFile(little);
        EXPECT_EQ(file[m->previews[0].offset], 0xFF);
        EXPECT_EQ(file[m->previews[0].offset + 1], 0xD8);
    }
}

TEST(ImageMetadata, FindsRawPreviewsInSubIfdsAndSkipsLosslessData) {
    TiffBuilder t;
    t.begin(true);
    const auto big_preview = miniJpeg(6000, 4000);
    const auto small_preview = miniJpeg(1620, 1080);
    const auto lossless = miniJpeg(6024, 4020, 0xC3);
    const auto big_at = t.blob(big_preview), small_at = t.blob(small_preview), raw_at = t.blob(lossless);
    const auto sub_preview = t.ifd({{254, 4, 1, t.u32(1)}, {259, 3, 1, t.u16(7)}, {273, 4, 1, t.u32(small_at)}, {279, 4, 1, t.u32(static_cast<std::uint32_t>(small_preview.size()))}});
    const auto sub_raw = t.ifd({{254, 4, 1, t.u32(0)}, {259, 3, 1, t.u16(7)}, {273, 4, 1, t.u32(raw_at)}, {279, 4, 1, t.u32(static_cast<std::uint32_t>(lossless.size()))}});
    Bytes subs = t.u32(sub_preview);
    const auto second = t.u32(sub_raw);
    subs.insert(subs.end(), second.begin(), second.end());
    const auto ifd0 = t.ifd({{256, 4, 1, t.u32(6024)},
                             {257, 4, 1, t.u32(4020)},
                             {259, 3, 1, t.u16(6)},
                             {271, 2, 6, t.text("Nikon")},
                             {273, 4, 1, t.u32(big_at)},
                             {279, 4, 1, t.u32(static_cast<std::uint32_t>(big_preview.size()))},
                             {330, 4, 2, subs}});
    t.setFirst(ifd0);
    std::string error;
    const auto m = md::read(t.b, error);
    ASSERT_TRUE(m) << error;
    EXPECT_EQ(m->container, "tiff");
    EXPECT_EQ(m->exif->make, "Nikon");
    EXPECT_EQ(m->width, 6024U);
    ASSERT_EQ(m->previews.size(), 2U) << "the lossless raw data is not a preview";
    EXPECT_EQ(m->previews[0].width, 6000U) << "largest first";
    EXPECT_EQ(m->previews[1].width, 1620U);
}

TEST(ImageMetadata, ReadsPngExifAndXmp) {
    const auto chunk = [](const char* type, const Bytes& data) {
        Bytes c{static_cast<std::uint8_t>(data.size() >> 24), static_cast<std::uint8_t>(data.size() >> 16), static_cast<std::uint8_t>(data.size() >> 8),
                static_cast<std::uint8_t>(data.size())};
        c.insert(c.end(), type, type + 4);
        c.insert(c.end(), data.begin(), data.end());
        c.insert(c.end(), {0, 0, 0, 0});  // CRC not checked
        return c;
    };
    Bytes png{0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};
    const auto ihdr = chunk("IHDR", Bytes{0, 0, 1, 0, 0, 0, 0, 200, 8, 6, 0, 0, 0});
    png.insert(png.end(), ihdr.begin(), ihdr.end());
    const auto exif = chunk("eXIf", exifBlock(false));
    png.insert(png.end(), exif.begin(), exif.end());
    const std::string key = "XML:com.adobe.xmp";
    Bytes itxt(key.begin(), key.end());
    itxt.insert(itxt.end(), {0, 0, 0, 0, 0});
    const std::string packet = "<xmp/>";
    itxt.insert(itxt.end(), packet.begin(), packet.end());
    const auto x = chunk("iTXt", itxt);
    png.insert(png.end(), x.begin(), x.end());
    const auto end = chunk("IEND", {});
    png.insert(png.end(), end.begin(), end.end());
    std::string error;
    const auto m = md::read(png, error);
    ASSERT_TRUE(m) << error;
    EXPECT_EQ(m->container, "png");
    EXPECT_EQ(m->width, 256U);
    EXPECT_EQ(m->height, 200U);
    EXPECT_EQ(m->exif->model, "EOS R6");
    EXPECT_EQ(std::string(m->xmp.begin(), m->xmp.end()), "<xmp/>");
    ASSERT_EQ(m->previews.size(), 1U) << "offsets are relative to the eXIf block";
    EXPECT_EQ(png[m->previews[0].offset + 1], 0xD8);
}

TEST(ImageMetadata, RejectsUnknownAndSurvivesDamage) {
    std::string error;
    EXPECT_FALSE(md::read(Bytes{'G', 'I', 'F', '8'}, error));
    const auto good = jpegFile(true);
    const Bytes cut(good.begin(), good.begin() + 40);
    const auto m = md::read(cut, error);
    ASSERT_TRUE(m);
    EXPECT_FALSE(m->warnings.empty());
    // Self-referencing IFD chains terminate.
    TiffBuilder t;
    t.begin(true);
    const auto ifd = t.ifd({{271, 2, 4, t.text("abc")}});
    t.patchNext(ifd, ifd);
    t.setFirst(ifd);
    ASSERT_TRUE(md::read(t.b, error));
}

TEST(ImageMetadata, SurvivesRandomMutations) {
    std::mt19937 random(13);
    const std::vector<Bytes> seeds{jpegFile(true), jpegFile(false), exifBlock(true)};
    int parsed = 0;
    for (int i = 0; i < 5000; ++i) {
        auto bytes = seeds[static_cast<std::size_t>(i) % seeds.size()];
        for (int e = 0; e < 1 + static_cast<int>(random() % 6); ++e) {
            const auto at = random() % bytes.size();
            switch (random() % 3) {
                case 0: bytes[at] = static_cast<std::uint8_t>(random()); break;
                case 1: bytes[at] = random() % 2 ? 0xFF : 0x00; break;
                default: bytes.resize(std::max<std::size_t>(1, at)); break;
            }
        }
        std::string error;
        const auto m = md::read(bytes, error);
        if (m) {
            ++parsed;
            for (const auto& p : m->previews) ASSERT_LE(p.offset + p.length, bytes.size());
        }
    }
    std::printf("[metadata] %d / 5000 mutated files parsed\n", parsed);
}

}  // namespace
