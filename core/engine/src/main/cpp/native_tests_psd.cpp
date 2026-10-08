// Tests for the PSD reader. Files are produced by a small writer that follows Adobe's
// published layout; the real-world round-trip suite comes with the Acceptance run.
#include "native_tests_stress_gate.h"
#include "psd_reader.h"

#include <gtest/gtest.h>
#include <zlib.h>

#include <array>
#include <bit>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <functional>
#include <optional>
#include <random>
#include <string>
#include <vector>

namespace {

namespace ps = croptop::psd;
using Bytes = std::vector<std::uint8_t>;

struct Writer {
    Bytes b;
    bool psb = false;
    void u8(std::uint8_t v) { b.push_back(v); }
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
    void len(std::uint64_t v) { psb ? u64(v) : u32(static_cast<std::uint32_t>(v)); }
    void ascii(const std::string& s) { b.insert(b.end(), s.begin(), s.end()); }
    void append(const Bytes& o) { b.insert(b.end(), o.begin(), o.end()); }
};

enum class Compression : std::uint16_t { Raw = 0, Rle = 1, Zip = 2, ZipPredicted = 3 };

/** A channel plane: w × h samples at `depth` bits, big-endian. */
Bytes plane(std::uint32_t w, std::uint32_t h, int depth, const std::function<double(std::uint32_t, std::uint32_t)>& f) {
    Writer out;
    for (std::uint32_t y = 0; y < h; ++y)
        for (std::uint32_t x = 0; x < w; ++x) {
            const double v = f(x, y);
            if (depth == 8) out.u8(static_cast<std::uint8_t>(std::lround(v * 255)));
            else if (depth == 16) out.u16(static_cast<std::uint16_t>(std::lround(v * 65535)));
            else out.u32(std::bit_cast<std::uint32_t>(static_cast<float>(v)));
        }
    return out.b;
}

Bytes packBitsRow(const std::uint8_t* row, std::size_t n) {
    Bytes out;
    std::size_t i = 0;
    while (i < n) {
        std::size_t run = 1;
        while (i + run < n && run < 128 && row[i + run] == row[i]) ++run;
        if (run >= 3) {
            out.push_back(static_cast<std::uint8_t>(static_cast<std::int8_t>(1 - static_cast<int>(run))));
            out.push_back(row[i]);
            i += run;
        } else {
            std::size_t lit = 0;
            while (i + lit < n && lit < 128) {
                if (i + lit + 2 < n && row[i + lit] == row[i + lit + 1] && row[i + lit] == row[i + lit + 2]) break;
                ++lit;
            }
            if (lit == 0) lit = 1;
            out.push_back(static_cast<std::uint8_t>(lit - 1));
            out.insert(out.end(), row + i, row + i + lit);
            i += lit;
        }
    }
    return out;
}

/** Encodes planes (each w × h × bps) with the given compression; `layer` channels each carry their own header. */
Bytes encode(const std::vector<Bytes>& planes, std::uint32_t w, std::uint32_t h, int depth, Compression c, bool psb) {
    const std::size_t bps = static_cast<std::size_t>(depth / 8), row_bytes = w * bps;
    Writer out;
    out.psb = psb;
    if (c == Compression::Raw) {
        for (const auto& p : planes) out.append(p);
    } else if (c == Compression::Rle) {
        std::vector<Bytes> rows;
        for (const auto& p : planes)
            for (std::uint32_t y = 0; y < h; ++y) rows.push_back(packBitsRow(p.data() + y * row_bytes, row_bytes));
        for (const auto& r : rows) psb ? out.u32(static_cast<std::uint32_t>(r.size())) : out.u16(static_cast<std::uint16_t>(r.size()));
        for (const auto& r : rows) out.append(r);
    } else {
        Bytes all;
        for (auto p : planes) {
            if (c == Compression::ZipPredicted) {
                for (std::uint32_t y = 0; y < h; ++y) {
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
                        Bytes planar(row_bytes);
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
        Bytes z(size);
        EXPECT_EQ(compress(z.data(), &size, all.data(), static_cast<uLong>(all.size())), Z_OK);
        z.resize(size);
        out.append(z);
    }
    return out.b;
}

struct TestLayer {
    std::string name;
    std::int32_t top = 0, left = 0, bottom = 0, right = 0;
    std::vector<std::pair<std::int16_t, Bytes>> channels;  // id → raw plane
    std::string blend = "norm";
    std::uint8_t opacity = 255;
    bool clipped = false, hidden = false;
    std::optional<std::uint32_t> section;  // lsct type
    std::optional<std::uint8_t> fill;
    std::optional<std::u16string> unicode_name;
    struct M {
        std::int32_t top, left, bottom, right;
        std::uint8_t default_color, flags;
        Bytes plane;
    };
    std::optional<M> mask;
};

void additional(Writer& w, const std::string& key, const Bytes& data) {
    w.ascii("8BIM");
    w.ascii(key);
    w.u32(static_cast<std::uint32_t>(data.size()));
    w.append(data);
}

Bytes layerInfo(const std::vector<TestLayer>& layers, int depth, Compression c, bool psb, bool alpha_first) {
    Writer records, data;
    records.psb = data.psb = psb;
    std::vector<std::vector<Bytes>> encoded;
    for (const auto& l : layers) {
        std::vector<Bytes> ch;
        for (const auto& [id, p] : l.channels) {
            std::uint32_t w = static_cast<std::uint32_t>(l.right - l.left), h = static_cast<std::uint32_t>(l.bottom - l.top);
            if (id == -2) w = static_cast<std::uint32_t>(l.mask->right - l.mask->left), h = static_cast<std::uint32_t>(l.mask->bottom - l.mask->top);
            Writer one;
            one.u16(static_cast<std::uint16_t>(c));
            one.append(encode({p}, w, h, depth, c, psb));
            ch.push_back(one.b);
        }
        encoded.push_back(ch);
    }
    records.u16(static_cast<std::uint16_t>(alpha_first ? -static_cast<int>(layers.size()) : static_cast<int>(layers.size())));
    for (std::size_t i = 0; i < layers.size(); ++i) {
        const auto& l = layers[i];
        records.u32(static_cast<std::uint32_t>(l.top));
        records.u32(static_cast<std::uint32_t>(l.left));
        records.u32(static_cast<std::uint32_t>(l.bottom));
        records.u32(static_cast<std::uint32_t>(l.right));
        records.u16(static_cast<std::uint16_t>(l.channels.size()));
        for (std::size_t k = 0; k < l.channels.size(); ++k) {
            records.u16(static_cast<std::uint16_t>(l.channels[k].first));
            records.len(encoded[i][k].size());
        }
        records.ascii("8BIM");
        records.ascii(l.blend);
        records.u8(l.opacity);
        records.u8(l.clipped ? 1 : 0);
        records.u8(l.hidden ? 0x02 : 0x00);
        records.u8(0);
        Writer extra;
        if (l.mask) {
            extra.u32(20);
            extra.u32(static_cast<std::uint32_t>(l.mask->top));
            extra.u32(static_cast<std::uint32_t>(l.mask->left));
            extra.u32(static_cast<std::uint32_t>(l.mask->bottom));
            extra.u32(static_cast<std::uint32_t>(l.mask->right));
            extra.u8(l.mask->default_color);
            extra.u8(l.mask->flags);
            extra.u16(0);
        } else {
            extra.u32(0);
        }
        extra.u32(0);  // blending ranges
        // Pascal name padded to a multiple of 4 bytes (length byte included).
        extra.u8(static_cast<std::uint8_t>(l.name.size()));
        extra.ascii(l.name);
        for (std::size_t n = 1 + l.name.size(); n % 4 != 0; ++n) extra.u8(0);
        if (l.section) {
            Writer d;
            d.u32(*l.section);
            d.ascii("8BIM");
            d.ascii(l.blend);
            additional(extra, "lsct", d.b);
        }
        if (l.fill) additional(extra, "iOpa", Bytes{*l.fill, 0, 0, 0});
        if (l.unicode_name) {
            Writer d;
            d.u32(static_cast<std::uint32_t>(l.unicode_name->size()));
            for (const auto ch : *l.unicode_name) d.u16(static_cast<std::uint16_t>(ch));
            while (d.b.size() % 4) d.u8(0);
            additional(extra, "luni", d.b);
        }
        additional(extra, "TySh", Bytes{1, 2, 3, 4});
        records.u32(static_cast<std::uint32_t>(extra.b.size()));
        records.append(extra.b);
    }
    for (const auto& ch : encoded)
        for (const auto& one : ch) data.append(one);
    Writer out;
    out.append(records.b);
    out.append(data.b);
    return out.b;
}

struct TestDoc {
    std::uint32_t w = 64, h = 48;
    int depth = 8;
    std::uint16_t mode = 3;
    bool psb = false;
    bool use_lr16 = false;  // put layer info inside a tail "Lr16" block (Photoshop's 16-bit layout)
    Compression c = Compression::Rle;
    std::vector<TestLayer> layers;
    std::vector<Bytes> composite;  // planes
    Bytes icc;
    bool merged_alpha = true;
};

Bytes writePsd(const TestDoc& d) {
    Writer w;
    w.psb = d.psb;
    w.ascii("8BPS");
    w.u16(d.psb ? 2 : 1);
    for (int i = 0; i < 6; ++i) w.u8(0);
    w.u16(static_cast<std::uint16_t>(d.composite.size()));
    w.u32(d.h);
    w.u32(d.w);
    w.u16(static_cast<std::uint16_t>(d.depth));
    w.u16(d.mode);
    w.u32(0);
    Writer resources;
    if (!d.icc.empty()) {
        resources.ascii("8BIM");
        resources.u16(1039);
        resources.u16(0);  // empty pascal name + pad
        resources.u32(static_cast<std::uint32_t>(d.icc.size()));
        resources.append(d.icc);
        if (d.icc.size() % 2) resources.u8(0);
    }
    w.u32(static_cast<std::uint32_t>(resources.b.size()));
    w.append(resources.b);
    Writer section;
    section.psb = d.psb;
    if (!d.layers.empty()) {
        auto info = layerInfo(d.layers, d.depth, d.c, d.psb, d.merged_alpha);
        while (info.size() % 2) info.push_back(0);
        if (d.use_lr16) {
            section.len(0);
            section.u32(0);  // global mask
            section.ascii("8BIM");
            section.ascii("Lr16");
            section.len(info.size());
            section.append(info);
            while (section.b.size() % 4) section.u8(0);
        } else {
            section.len(info.size());
            section.append(info);
            section.u32(0);
        }
    }
    w.len(section.b.size());
    w.append(section.b);
    w.u16(static_cast<std::uint16_t>(d.c == Compression::ZipPredicted ? Compression::Rle : d.c));
    w.append(encode(d.composite, d.w, d.h, d.depth, d.c == Compression::ZipPredicted ? Compression::Rle : d.c, d.psb));
    return w.b;
}

double px(const croptop::TileStore& s, std::uint32_t x, std::uint32_t y, int channel) {
    const auto t = s.readTile({static_cast<std::int32_t>(x / 256), static_cast<std::int32_t>(y / 256)});
    if (!t) return 0.0;
    const auto i = (static_cast<std::size_t>(y % 256) * 256 + x % 256);
    switch (s.format()) {
        case croptop::PixelFormat::RGBA8: return t->data()[i * 4 + static_cast<std::size_t>(channel)] / 255.0;
        case croptop::PixelFormat::RGBA16: {
            std::uint16_t v = 0;
            std::memcpy(&v, t->data() + i * 8 + static_cast<std::size_t>(channel) * 2, 2);
            return v / 65535.0;
        }
        case croptop::PixelFormat::RGBA32F: {
            float v = 0;
            std::memcpy(&v, t->data() + i * 16 + static_cast<std::size_t>(channel) * 4, 4);
            return v;
        }
        case croptop::PixelFormat::A16: {
            std::uint16_t v = 0;
            std::memcpy(&v, t->data() + i * 2, 2);
            return v / 65535.0;
        }
        default: return 0.0;
    }
}

/** A realistic small document: background, a group with a clipped layer and a masked layer. */
TestDoc sampleDoc(int depth, Compression c, bool psb = false) {
    TestDoc d;
    d.depth = depth;
    d.c = c;
    d.psb = psb;
    const auto gradient = [](std::uint32_t x, std::uint32_t) { return x / 63.0; };
    TestLayer background;
    background.name = "Background";
    background.bottom = 48;
    background.right = 64;
    background.channels = {{-1, plane(64, 48, depth, [](auto, auto) { return 1.0; })},
                           {0, plane(64, 48, depth, gradient)},
                           {1, plane(64, 48, depth, [](auto, auto) { return 0.25; })},
                           {2, plane(64, 48, depth, [](auto, std::uint32_t y) { return y / 47.0; })}};
    TestLayer divider;
    divider.name = "</Layer group>";
    divider.section = 3;
    TestLayer patch;
    patch.name = "Patch";
    patch.top = 10, patch.left = 50, patch.bottom = 30, patch.right = 80;  // extends past the right edge
    patch.blend = "mul ";
    patch.opacity = 128;
    patch.fill = 200;
    patch.clipped = true;
    patch.unicode_name = u"Patch é";
    patch.channels = {{-1, plane(30, 20, depth, [](std::uint32_t x, auto) { return x < 8 ? 1.0 : 0.0; })},
                      {0, plane(30, 20, depth, [](auto, auto) { return 1.0; })},
                      {1, plane(30, 20, depth, [](auto, auto) { return 0.0; })},
                      {2, plane(30, 20, depth, [](auto, auto) { return 0.5; })}};
    patch.mask = TestLayer::M{12, 52, 20, 60, 255, 0, plane(8, 8, depth, [](std::uint32_t x, auto) { return x < 4 ? 0.0 : 1.0; })};
    patch.channels.push_back({-2, patch.mask->plane});
    TestLayer folder;
    folder.name = "Group 1";
    folder.section = 2;
    folder.blend = "pass";
    folder.hidden = true;
    d.layers = {background, divider, patch, folder};
    d.composite = {plane(64, 48, depth, gradient), plane(64, 48, depth, [](auto, auto) { return 0.25; }),
                   plane(64, 48, depth, [](auto, std::uint32_t y) { return y / 47.0; }), plane(64, 48, depth, [](auto, auto) { return 1.0; })};
    return d;
}

void checkSample(const ps::Document& doc, int depth) {
    const double tolerance = depth == 8 ? 1.0 / 255.0 : 1e-4;
    ASSERT_EQ(doc.layers.size(), 4U);
    const auto& background = doc.layers[0];
    EXPECT_EQ(background.name, "Background");
    ASSERT_TRUE(background.pixels);
    EXPECT_NEAR(px(*background.pixels, 63, 5, 0), 1.0, tolerance);
    EXPECT_NEAR(px(*background.pixels, 10, 47, 2), 1.0, tolerance);
    EXPECT_NEAR(px(*background.pixels, 10, 10, 1), 0.25, tolerance);
    EXPECT_EQ(doc.layers[1].kind, ps::LayerKind::GroupDivider);
    const auto& patch = doc.layers[2];
    EXPECT_EQ(patch.name, "Patch \xC3\xA9") << "luni wins over the Pascal name";
    EXPECT_EQ(patch.mode, croptop::BlendMode::Multiply);
    EXPECT_EQ(patch.opacity, 128);
    EXPECT_EQ(patch.fill, 200);
    EXPECT_TRUE(patch.clipped);
    ASSERT_TRUE(patch.pixels);
    EXPECT_NEAR(px(*patch.pixels, 55, 15, 0), 1.0, tolerance);
    EXPECT_NEAR(px(*patch.pixels, 55, 15, 2), 0.5, tolerance);
    EXPECT_EQ(px(*patch.pixels, 63, 15, 3), 0.0) << "transparent from x = 58";
    EXPECT_EQ(px(*patch.pixels, 20, 15, 3), 0.0) << "outside the layer";
    ASSERT_TRUE(patch.mask);
    EXPECT_NEAR(px(patch.mask->coverage, 53, 15, 0), 0.0, tolerance);
    EXPECT_NEAR(px(patch.mask->coverage, 58, 15, 0), 1.0, tolerance);
    EXPECT_NEAR(px(patch.mask->coverage, 5, 40, 0), 1.0, tolerance) << "default colour outside the mask rectangle";
    ASSERT_EQ(patch.extra.size(), 1U);
    EXPECT_EQ(patch.extra[0].key, "TySh");
    const auto& folder = doc.layers[3];
    EXPECT_EQ(folder.kind, ps::LayerKind::GroupFolder);
    EXPECT_FALSE(folder.group_open);
    EXPECT_TRUE(folder.pass_through);
    EXPECT_FALSE(folder.visible);
    ASSERT_TRUE(doc.composite);
    EXPECT_NEAR(px(*doc.composite, 63, 0, 0), 1.0, tolerance);
    EXPECT_NEAR(px(*doc.composite, 0, 47, 2), 1.0, tolerance);
    EXPECT_NEAR(px(*doc.composite, 30, 30, 3), 1.0, tolerance);
}

TEST(Psd, ReadsLayersGroupsMasksAndCompositeInEveryCompression) {
    for (const auto c : {Compression::Raw, Compression::Rle, Compression::Zip, Compression::ZipPredicted}) {
        std::string error;
        const auto bytes = writePsd(sampleDoc(8, c));
        const auto doc = ps::read(bytes, error);
        ASSERT_TRUE(doc) << error;
        SCOPED_TRACE(static_cast<int>(c));
        checkSample(*doc, 8);
        bool clipped_warning = false;
        for (const auto& w : doc->warnings) clipped_warning = clipped_warning || w.find("outside the canvas") != std::string::npos;
        EXPECT_TRUE(clipped_warning);
    }
}

TEST(Psd, ReadsHighBitDepthPsbAndLr16) {
    for (const int depth : {16, 32}) {
        for (const auto c : {Compression::Rle, Compression::ZipPredicted}) {
            std::string error;
            auto d = sampleDoc(depth, c, depth == 32);
            d.use_lr16 = depth == 16;
            const auto doc = ps::read(writePsd(d), error);
            ASSERT_TRUE(doc) << error;
            SCOPED_TRACE(depth);
            EXPECT_EQ(doc->psb, depth == 32);
            EXPECT_EQ(doc->layers.front().pixels->format(), depth == 16 ? croptop::PixelFormat::RGBA16 : croptop::PixelFormat::RGBA32F);
            checkSample(*doc, depth);
        }
    }
}

TEST(Psd, GrayscaleCmykAndIcc) {
    TestDoc gray;
    gray.mode = 1;
    gray.composite = {plane(64, 48, 8, [](std::uint32_t x, auto) { return x / 63.0; })};
    gray.icc = {1, 2, 3};
    std::string error;
    const auto doc = ps::read(writePsd(gray), error);
    ASSERT_TRUE(doc) << error;
    EXPECT_EQ(doc->icc_profile, (Bytes{1, 2, 3}));
    EXPECT_NEAR(px(*doc->composite, 63, 3, 1), 1.0, 1e-9);
    EXPECT_NEAR(px(*doc->composite, 63, 3, 3), 1.0, 1e-9) << "opaque without merged alpha";
    TestDoc cmyk;
    cmyk.mode = 4;
    // Stored inverted: 1.0 = no ink. Pure cyan: C plane 0, others 1.
    cmyk.composite = {plane(64, 48, 8, [](auto, auto) { return 0.0; }), plane(64, 48, 8, [](auto, auto) { return 1.0; }),
                      plane(64, 48, 8, [](auto, auto) { return 1.0; }), plane(64, 48, 8, [](auto, auto) { return 1.0; })};
    // Documents hold TileStores, which cannot be reassigned (see the TileStore API note): use a new variable.
    const auto cmyk_doc = ps::read(writePsd(cmyk), error);
    ASSERT_TRUE(cmyk_doc) << error;
    EXPECT_NEAR(px(*cmyk_doc->composite, 5, 5, 0), 0.0, 1e-9);
    EXPECT_NEAR(px(*cmyk_doc->composite, 5, 5, 1), 1.0, 1e-9);
    EXPECT_FALSE(cmyk_doc->warnings.empty());
}

TEST(Psd, RejectsWhatItCannotRead) {
    std::string error;
    EXPECT_FALSE(ps::read(Bytes{'8', 'B', 'P', 'X'}, error));
    EXPECT_FALSE(ps::read(Bytes{}, error));
    TestDoc lab;
    lab.mode = 9;
    lab.composite = {plane(64, 48, 8, [](auto, auto) { return 0.5; })};
    EXPECT_FALSE(ps::read(writePsd(lab), error));
    EXPECT_NE(error.find("colour mode"), std::string::npos);
    TestDoc huge;
    huge.w = 40000;
    huge.h = 10;
    huge.composite = {};
    EXPECT_FALSE(ps::read(writePsd(huge), error)) << "PSD sides are limited to 30000 px";
}

TEST(Psd, TruncatedFilesKeepWhatWasRead) {
    const auto bytes = writePsd(sampleDoc(8, Compression::Raw));
    for (const std::size_t cut : {bytes.size() - 20, bytes.size() / 2, std::size_t{40}}) {
        std::string error;
        const Bytes partial(bytes.begin(), bytes.begin() + static_cast<std::ptrdiff_t>(cut));
        const auto doc = ps::read(partial, error);
        if (doc) {
            EXPECT_FALSE(doc->warnings.empty()) << cut;
        }
    }
}

TEST(Psd, TinyFileCannotForceHugeWork) {
    // Found by libFuzzer: 40 bytes declaring a 13 MP 32-bit canvas with an empty ZIP-predicted composite
    // used to allocate and un-predict ~150 MB (6 s); short ZIP output is now rejected as truncated.
    const Bytes bytes{0x38,0x42,0x50,0x53,0x00,0x01,0x42,0xff,0x03,0x00,0x28,0x53,0x00,0x24,0x00,0x00,0x5b,0x28,0x00,0x00,0x02,0x32,0x00,0x20,0x00,0x01,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x03};
    std::string error;
    const auto start = std::chrono::steady_clock::now();
    const auto doc = ps::read(bytes, error);
    const auto ms = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - start).count();
    EXPECT_LT(ms, 1000.0);
    if (doc) {
        EXPECT_FALSE(doc->composite);
        EXPECT_FALSE(doc->warnings.empty());
    }
}

TEST(Psd, SurvivesRandomMutations) {
    CROPTOP_REQUIRE_STRESS();
    std::mt19937 random(5);
    const std::vector<Bytes> seeds{writePsd(sampleDoc(8, Compression::Rle)), writePsd(sampleDoc(8, Compression::ZipPredicted)),
                                   writePsd(sampleDoc(16, Compression::Rle)), writePsd(sampleDoc(32, Compression::ZipPredicted, true))};
    ps::Limits limits;
    limits.max_working_bytes = 64ULL << 20;
    limits.max_canvas_pixels = 1ULL << 24;
    int opened = 0;
    for (int i = 0; i < 2000; ++i) {
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
        if (ps::read(bytes, error, limits)) ++opened;
    }
    std::printf("[psd] %d / 2000 mutated files still opened\n", opened);
}

}  // namespace
