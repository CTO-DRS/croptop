// Tests for the ABR brush parser. Files are built with a small writer following
// the published layouts (GIMP/Krita readers); real-pack coverage comes with the import acceptance run.
#include "abr_import.h"

#include <gtest/gtest.h>

#include <algorithm>
#include <bit>
#include <cstdio>
#include <cstdint>
#include <random>
#include <string>
#include <vector>

namespace {

namespace br = croptop::brush;
using Bytes = std::vector<std::uint8_t>;

struct Writer {
    Bytes b;
    void u8(std::uint8_t v) { b.push_back(v); }
    void u16(std::uint16_t v) {
        u8(static_cast<std::uint8_t>(v >> 8));
        u8(static_cast<std::uint8_t>(v));
    }
    void u32(std::uint32_t v) {
        u16(static_cast<std::uint16_t>(v >> 16));
        u16(static_cast<std::uint16_t>(v));
    }
    void f64(double v) {
        const auto bits = std::bit_cast<std::uint64_t>(v);
        u32(static_cast<std::uint32_t>(bits >> 32));
        u32(static_cast<std::uint32_t>(bits));
    }
    void ascii(const std::string& s) { b.insert(b.end(), s.begin(), s.end()); }
    void unicode(const std::u16string& s) {
        u32(static_cast<std::uint32_t>(s.size() + 1));
        for (const auto c : s) u16(static_cast<std::uint16_t>(c));
        u16(0);
    }
    void key(const std::string& k) {
        if (k.size() == 4) {
            u32(0);
        } else {
            u32(static_cast<std::uint32_t>(k.size()));
        }
        ascii(k);
    }
    void append(const Bytes& other) { b.insert(b.end(), other.begin(), other.end()); }
};

/** A w × h tip with a diagonal gradient. */
Bytes tipPixels(std::uint32_t w, std::uint32_t h) {
    Bytes px(static_cast<std::size_t>(w) * h);
    for (std::uint32_t y = 0; y < h; ++y)
        for (std::uint32_t x = 0; x < w; ++x) px[static_cast<std::size_t>(y) * w + x] = static_cast<std::uint8_t>((x + y) * 7);
    return px;
}

/** PackBits rows with per-row counts (repeat runs for flat spans, literals otherwise). */
Bytes packBits(const Bytes& px, std::uint32_t w, std::uint32_t h) {
    std::vector<Bytes> rows;
    for (std::uint32_t y = 0; y < h; ++y) {
        Bytes row;
        std::uint32_t x = 0;
        while (x < w) {
            std::uint32_t run = 1;
            const auto v = px[static_cast<std::size_t>(y) * w + x];
            while (x + run < w && run < 128 && px[static_cast<std::size_t>(y) * w + x + run] == v) ++run;
            if (run >= 3) {
                row.push_back(static_cast<std::uint8_t>(static_cast<std::int8_t>(1 - static_cast<int>(run))));
                row.push_back(v);
                x += run;
            } else {
                std::uint32_t n = 1;
                while (x + n < w && n < 128) ++n;
                row.push_back(static_cast<std::uint8_t>(n - 1));
                for (std::uint32_t i = 0; i < n; ++i) row.push_back(px[static_cast<std::size_t>(y) * w + x + i]);
                x += n;
            }
        }
        rows.push_back(row);
    }
    Writer out;
    for (const auto& r : rows) out.u16(static_cast<std::uint16_t>(r.size()));
    for (const auto& r : rows) out.append(r);
    return out.b;
}

Bytes v2File(bool rle) {
    Writer w;
    w.u16(2);
    w.u16(2);
    // Computed brush.
    Writer computed;
    computed.u32(0);
    computed.u16(25);
    computed.unicode(u"Hard Round");
    computed.u16(19);
    computed.u16(80);
    computed.u16(static_cast<std::uint16_t>(-30));
    computed.u16(100);
    w.u16(1);
    w.u32(static_cast<std::uint32_t>(computed.b.size()));
    w.append(computed.b);
    // Sampled brush, 20 × 12 tip.
    Writer sampled;
    sampled.u32(0);
    sampled.u16(40);
    sampled.unicode(u"Grass é");
    sampled.u8(1);
    for (int i = 0; i < 4; ++i) sampled.u16(0);
    sampled.u32(0);
    sampled.u32(0);
    sampled.u32(12);
    sampled.u32(20);
    sampled.u16(8);
    sampled.u8(rle ? 1 : 0);
    const auto px = tipPixels(20, 12);
    sampled.append(rle ? packBits(px, 20, 12) : px);
    w.u16(2);
    w.u32(static_cast<std::uint32_t>(sampled.b.size()));
    w.append(sampled.b);
    return w.b;
}

Bytes sampleRecord(const std::string& uuid, int subversion, std::uint32_t tw, std::uint32_t th, int depth) {
    Writer s;
    s.u8(static_cast<std::uint8_t>(uuid.size()));
    s.ascii(uuid);
    while (s.b.size() < (subversion == 1 ? 47U : 301U)) s.u8(0);
    s.u32(0);
    s.u32(0);
    s.u32(th);
    s.u32(tw);
    s.u16(static_cast<std::uint16_t>(depth));
    const auto px = tipPixels(tw, th);
    if (depth == 16) {
        s.u8(0);
        for (const auto v : px) s.u16(static_cast<std::uint16_t>(v * 257));
    } else {
        s.u8(1);
        s.append(packBits(px, tw, th));
    }
    return s.b;
}

void untf(Writer& w, const std::string& k, const std::string& unit, double v) {
    w.key(k);
    w.ascii("UntF");
    w.ascii(unit);
    w.f64(v);
}

Bytes presetDescriptor() {
    Writer d;
    d.unicode(u"");
    d.key("null");
    d.u32(1);
    d.key("Brsh");
    d.ascii("VlLs");
    d.u32(2);
    // Preset 1: sampled, with pressure-controlled size.
    d.ascii("Objc");
    d.unicode(u"");
    d.key("brushPreset");
    d.u32(5);
    d.key("Nm  ");
    d.ascii("TEXT");
    d.unicode(u"Chalk \U0001F58C");
    d.key("Brsh");
    d.ascii("Objc");
    d.unicode(u"");
    d.key("sampledBrush");
    d.u32(4);
    untf(d, "Dmtr", "#Pxl", 60);
    untf(d, "Angl", "#Ang", 45);
    untf(d, "Spcn", "#Prc", 30);
    d.key("sampledData");
    d.ascii("TEXT");
    d.unicode(u"$11111111-2222-3333-4444-555555555555");
    d.key("useTipDynamics");
    d.ascii("bool");
    d.u8(1);
    d.key("szVr");
    d.ascii("Objc");
    d.unicode(u"");
    d.key("brVr");
    d.u32(2);
    d.key("bVTy");
    d.ascii("long");
    d.u32(2);
    untf(d, "jitter", "#Prc", 20);
    untf(d, "minimumDiameter", "#Prc", 35);
    // Preset 2: computed.
    d.ascii("Objc");
    d.unicode(u"");
    d.key("brushPreset");
    d.u32(2);
    d.key("Nm  ");
    d.ascii("TEXT");
    d.unicode(u"Soft Round");
    d.key("Brsh");
    d.ascii("Objc");
    d.unicode(u"");
    d.key("computedBrush");
    d.u32(5);
    untf(d, "Dmtr", "#Pxl", 100);
    untf(d, "Hrdn", "#Prc", 0);
    untf(d, "Rndn", "#Prc", 50);
    untf(d, "Spcn", "#Prc", 25);
    d.key("Intr");
    d.ascii("bool");
    d.u8(1);
    return d.b;
}

Bytes v6File(int subversion, bool with_descriptor, int depth = 8) {
    Writer samples;
    for (const auto* uuid : {"$11111111-2222-3333-4444-555555555555", "$99999999-2222-3333-4444-555555555555"}) {
        const auto record = sampleRecord(uuid, subversion, 33, 17, depth);
        samples.u32(static_cast<std::uint32_t>(record.size()));
        samples.append(record);
        while (samples.b.size() % 4) samples.u8(0);
    }
    Writer w;
    w.u16(6);
    w.u16(static_cast<std::uint16_t>(subversion));
    w.ascii("8BIMsamp");
    w.u32(static_cast<std::uint32_t>(samples.b.size()));
    w.append(samples.b);
    w.ascii("8BIMpatt");
    w.u32(0);
    if (with_descriptor) {
        const auto desc = presetDescriptor();
        w.ascii("8BIMdesc");
        w.u32(static_cast<std::uint32_t>(desc.size()));
        w.append(desc);
    }
    return w.b;
}

TEST(Abr, ReadsV2ComputedAndSampledBrushes) {
    for (const bool rle : {false, true}) {
        const auto bytes = v2File(rle);
        std::string error;
        const auto file = br::parseAbr(bytes, error);
        ASSERT_TRUE(file) << error;
        EXPECT_EQ(file->version, 2);
        ASSERT_EQ(file->brushes.size(), 2U);
        const auto& computed = file->brushes[0];
        EXPECT_EQ(computed.name, "Hard Round");
        EXPECT_FALSE(computed.sampled);
        EXPECT_FLOAT_EQ(computed.diameter, 19.0F);
        EXPECT_FLOAT_EQ(computed.roundness, 0.8F);
        EXPECT_FLOAT_EQ(computed.angle, -30.0F);
        EXPECT_FLOAT_EQ(computed.hardness, 1.0F);
        EXPECT_FLOAT_EQ(computed.spacing, 0.25F);
        const auto& sampled = file->brushes[1];
        EXPECT_EQ(sampled.name, "Grass \xC3\xA9");
        ASSERT_TRUE(sampled.tip);
        const auto& tip = file->tips[*sampled.tip];
        EXPECT_EQ(tip.width, 20U);
        EXPECT_EQ(tip.height, 12U);
        EXPECT_EQ(tip.coverage, tipPixels(20, 12));
        EXPECT_FLOAT_EQ(sampled.diameter, 20.0F);
        EXPECT_TRUE(file->warnings.empty());
    }
}

TEST(Abr, ReadsV6SampledTipsAndPresetDescriptor) {
    for (const int subversion : {1, 2}) {
        const auto bytes = v6File(subversion, true);
        std::string error;
        const auto file = br::parseAbr(bytes, error);
        ASSERT_TRUE(file) << error;
        ASSERT_EQ(file->tips.size(), 2U);
        EXPECT_EQ(file->tips[0].uuid, "$11111111-2222-3333-4444-555555555555");
        EXPECT_EQ(file->tips[1].coverage, tipPixels(33, 17));
        ASSERT_EQ(file->brushes.size(), 2U);
        const auto& chalk = file->brushes[0];
        EXPECT_EQ(chalk.name, "Chalk \xF0\x9F\x96\x8C");
        EXPECT_TRUE(chalk.sampled);
        ASSERT_TRUE(chalk.tip);
        EXPECT_EQ(*chalk.tip, 0U);
        EXPECT_FLOAT_EQ(chalk.diameter, 60.0F);
        EXPECT_FLOAT_EQ(chalk.angle, 45.0F);
        EXPECT_FLOAT_EQ(chalk.spacing, 0.3F);
        EXPECT_TRUE(chalk.has_dynamics);
        EXPECT_EQ(chalk.size.control, 2);
        EXPECT_FLOAT_EQ(chalk.size.jitter, 0.2F);
        EXPECT_FLOAT_EQ(chalk.size.minimum, 0.35F);
        const auto& soft = file->brushes[1];
        EXPECT_EQ(soft.name, "Soft Round");
        EXPECT_FALSE(soft.sampled);
        EXPECT_FLOAT_EQ(soft.hardness, 0.0F);
        EXPECT_FLOAT_EQ(soft.roundness, 0.5F);
        EXPECT_FLOAT_EQ(soft.diameter, 100.0F);
        EXPECT_TRUE(file->warnings.empty()) << file->warnings.front();
    }
}

TEST(Abr, V6WithoutDescriptorImportsTipsWithDefaults) {
    const auto bytes = v6File(1, false, 16);
    std::string error;
    const auto file = br::parseAbr(bytes, error);
    ASSERT_TRUE(file) << error;
    ASSERT_EQ(file->brushes.size(), 2U);
    EXPECT_EQ(file->brushes[1].name, "Sampled Brush 2");
    EXPECT_FLOAT_EQ(file->brushes[1].diameter, 33.0F);
    EXPECT_EQ(file->tips[0].coverage, tipPixels(33, 17)) << "16-bit tips round to 8-bit exactly";
}

TEST(Abr, RejectsNonBrushFilesAndUnsupportedVersions) {
    std::string error;
    EXPECT_FALSE(br::parseAbr(Bytes{}, error));
    EXPECT_FALSE(br::parseAbr(Bytes{0x89, 'P', 'N', 'G'}, error));
    EXPECT_FALSE(br::parseAbr(Bytes{0, 3, 0, 0}, error));
    EXPECT_NE(error.find("version"), std::string::npos);
    EXPECT_FALSE(br::parseAbr(Bytes{0, 2, 0, 0}, error)) << "no brushes";
}

TEST(Abr, TruncatedFilesKeepCompleteBrushesAndWarn) {
    const auto full = v2File(true);
    // Cut inside the second (sampled) brush: the first survives, with a warning.
    const Bytes cut(full.begin(), full.end() - 10);
    std::string error;
    const auto file = br::parseAbr(cut, error);
    ASSERT_TRUE(file) << error;
    EXPECT_EQ(file->brushes.size(), 1U);
    EXPECT_FALSE(file->warnings.empty());
}

TEST(Abr, LimitsBoundTipSizeAndMemory) {
    br::AbrLimits limits;
    limits.max_tip_edge = 16;
    std::string error;
    const auto file = br::parseAbr(v2File(false), error, limits);
    ASSERT_TRUE(file) << error;
    EXPECT_EQ(file->brushes.size(), 1U) << "the 20 px tip is over the limit";
    EXPECT_EQ(file->warnings.size(), 1U);
    limits = {};
    limits.max_tip_bytes = 100;
    EXPECT_FALSE(br::parseAbr(v6File(1, false), error, limits)) << "no tips fit, so no brushes";
}

TEST(Abr, CorruptDescriptorFallsBackToTips) {
    auto bytes = v6File(2, true);
    // Corrupt the descriptor's first list item type ("Objc" → "????").
    const Bytes needle{'V', 'l', 'L', 's'};
    const auto it = std::search(bytes.begin(), bytes.end(), needle.begin(), needle.end());
    ASSERT_NE(it, bytes.end());
    std::fill_n(it + 8, 4, static_cast<std::uint8_t>('?'));
    std::string error;
    const auto file = br::parseAbr(bytes, error);
    ASSERT_TRUE(file) << error;
    EXPECT_EQ(file->brushes.size(), 2U);
    EXPECT_EQ(file->brushes[0].name, "Sampled Brush 1");
    EXPECT_FALSE(file->warnings.empty());
}

TEST(Abr, SurvivesRandomMutations) {
    // Deterministic smoke fuzz (sanitizer builds catch overreads); the libFuzzer target runs longer.
    std::mt19937 random(7);
    const std::vector<Bytes> seeds{v2File(true), v2File(false), v6File(1, true), v6File(2, true), v6File(1, false, 16)};
    int parsed = 0;
    for (int i = 0; i < 3000; ++i) {
        auto bytes = seeds[static_cast<std::size_t>(i) % seeds.size()];
        const int edits = 1 + static_cast<int>(random() % 8);
        for (int e = 0; e < edits; ++e) {
            const auto at = random() % bytes.size();
            switch (random() % 3) {
                case 0: bytes[at] = static_cast<std::uint8_t>(random()); break;
                case 1: bytes[at] = static_cast<std::uint8_t>(random() % 2 ? 0xFF : 0x00); break;
                default: bytes.resize(std::max<std::size_t>(1, at)); break;
            }
        }
        std::string error;
        br::AbrLimits limits;
        limits.max_tip_bytes = 64ULL * 1024ULL * 1024ULL;
        if (br::parseAbr(bytes, error, limits)) ++parsed;
    }
    std::printf("[abr] %d / 3000 mutated files still parsed\n", parsed);
}

}  // namespace
