// Tests for the PSD writer: documents round-trip through the reader.
#include "psd_reader.h"
#include "pixel_plane.h"
#include "psd_writer.h"
#include "native_tests_stress_gate.h"

#include <gtest/gtest.h>

#include <array>
#include <cmath>
#include <cstring>
#include <functional>
#include <string>

namespace {

namespace ps = croptop::psd;
using croptop::PixelFormat;
using croptop::TileStore;
using Rgbaf = std::array<float, 4>;

PixelFormat formatFor(int depth) { return depth == 8 ? PixelFormat::RGBA8 : depth == 16 ? PixelFormat::RGBA16 : PixelFormat::RGBA32F; }

TileStore paint(int depth, std::uint32_t w, std::uint32_t h, const std::function<Rgbaf(std::uint32_t, std::uint32_t)>& f,
                std::uint32_t x0 = 0, std::uint32_t y0 = 0, std::uint32_t x1 = ~0U, std::uint32_t y1 = ~0U) {
    TileStore s(formatFor(depth), w, h);
    const auto bpp = s.bytes_per_pixel();
    for (std::uint32_t ty = 0; ty * 256 < h; ++ty)
        for (std::uint32_t tx = 0; tx * 256 < w; ++tx) {
            croptop::TileBuffer t(s.tile_byte_size(), 0);
            bool any = false;
            for (std::uint32_t y = 0; y < 256; ++y)
                for (std::uint32_t x = 0; x < 256; ++x) {
                    const auto gx = tx * 256 + x, gy = ty * 256 + y;
                    if (gx >= w || gy >= h || gx < x0 || gy < y0 || gx >= x1 || gy >= y1) continue;
                    const auto c = f(gx, gy);
                    croptop::paint::detail::writePixel(s.format(), t.data() + (static_cast<std::size_t>(y) * 256 + x) * bpp, c.data());
                    any = true;
                }
            if (any) {
                EXPECT_TRUE(s.writeTile({static_cast<std::int32_t>(tx), static_cast<std::int32_t>(ty)}, t));
            }
        }
    return s;
}

Rgbaf at(const TileStore& s, std::uint32_t x, std::uint32_t y) {
    Rgbaf c{};
    const auto t = s.readTile({static_cast<std::int32_t>(x / 256), static_cast<std::int32_t>(y / 256)});
    if (t) croptop::paint::detail::readPixel(s.format(), t->data() + (static_cast<std::size_t>(y % 256) * 256 + x % 256) * s.bytes_per_pixel(), c.data());
    return c;
}

float maskAt(const TileStore& m, std::uint32_t x, std::uint32_t y) {
    const auto t = m.readTile({static_cast<std::int32_t>(x / 256), static_cast<std::int32_t>(y / 256)});
    if (!t) return 0.0F;
    std::uint16_t v = 0;
    std::memcpy(&v, t->data() + (static_cast<std::size_t>(y % 256) * 256 + x % 256) * 2, 2);
    return v / 65535.0F;
}

ps::Document sample(int depth, std::uint32_t w = 300, std::uint32_t h = 200) {
    ps::Document doc;
    doc.width = w;
    doc.height = h;
    doc.depth = static_cast<std::uint16_t>(depth);
    doc.icc_profile = {9, 8, 7, 6, 5};
    ps::Layer background;
    background.name = "Background";
    background.pixels.emplace(paint(depth, w, h, [w](std::uint32_t x, std::uint32_t) { return Rgbaf{x / float(w - 1), 0.25F, 0.5F, 1.0F}; }));
    ps::Layer divider;
    divider.name = "</Layer group>";
    divider.kind = ps::LayerKind::GroupDivider;
    ps::Layer patch;
    patch.name = "Patch \xC3\xA9 \xF0\x9F\x96\x8C";
    patch.mode = croptop::BlendMode::Multiply;
    patch.opacity = 128;
    patch.fill = 200;
    patch.clipped = true;
    patch.visible = false;
    patch.lock_transparency = true;
    patch.pixels.emplace(paint(depth, w, h, [](auto, auto) { return Rgbaf{1.0F, 0.0F, 0.5F, 0.75F}; }, 260, 20, 290, 60));
    TileStore coverage(PixelFormat::A16, w, h);
    {
        croptop::TileBuffer t(coverage.tile_byte_size(), 0);
        for (std::size_t i = 0; i < 256U * 256U; ++i) {
            const std::uint16_t v = (i % 256) < 100 ? 65535 : 0;
            std::memcpy(t.data() + i * 2, &v, 2);
        }
        EXPECT_TRUE(coverage.writeTile({0, 0}, t));
    }
    patch.mask.emplace(ps::Mask{std::move(coverage), 0, true, false});
    patch.extra.push_back({"TySh", {1, 2, 3, 4, 5}});
    ps::Layer folder;
    folder.name = "Group 1";
    folder.kind = ps::LayerKind::GroupFolder;
    folder.group_open = false;
    folder.pass_through = true;
    doc.layers.push_back(std::move(background));
    doc.layers.push_back(std::move(divider));
    doc.layers.push_back(std::move(patch));
    doc.layers.push_back(std::move(folder));
    doc.composite.emplace(paint(depth, w, h, [w](std::uint32_t x, std::uint32_t) { return Rgbaf{x / float(w - 1), 0.25F, 0.5F, 1.0F}; }));
    return doc;
}

void expectRoundTrip(const ps::Document& back, int depth, std::uint32_t w) {
    const float tolerance = depth == 8 ? 1.0F / 255.0F : depth == 16 ? 1.0F / 65535.0F : 1e-6F;
    EXPECT_EQ(back.depth, depth);
    EXPECT_EQ(back.icc_profile, (std::vector<std::uint8_t>{9, 8, 7, 6, 5}));
    ASSERT_EQ(back.layers.size(), 4U);
    const auto& background = back.layers[0];
    EXPECT_EQ(background.name, "Background");
    ASSERT_TRUE(background.pixels);
    EXPECT_NEAR(at(*background.pixels, w - 1, 5)[0], 1.0F, tolerance);
    EXPECT_NEAR(at(*background.pixels, 10, 150)[2], 0.5F, tolerance);
    EXPECT_EQ(back.layers[1].kind, ps::LayerKind::GroupDivider);
    const auto& patch = back.layers[2];
    EXPECT_EQ(patch.name, "Patch \xC3\xA9 \xF0\x9F\x96\x8C");
    EXPECT_EQ(patch.mode, croptop::BlendMode::Multiply);
    EXPECT_EQ(patch.opacity, 128);
    EXPECT_EQ(patch.fill, 200);
    EXPECT_TRUE(patch.clipped);
    EXPECT_FALSE(patch.visible);
    EXPECT_TRUE(patch.lock_transparency);
    EXPECT_EQ(patch.left, 260);
    EXPECT_EQ(patch.top, 20);
    EXPECT_EQ(patch.right, 290);
    EXPECT_EQ(patch.bottom, 60) << "records are cropped to content";
    ASSERT_TRUE(patch.pixels);
    const auto p = at(*patch.pixels, 270, 30);
    EXPECT_NEAR(p[0], 1.0F, tolerance);
    EXPECT_NEAR(p[2], 0.5F, tolerance);
    EXPECT_NEAR(p[3], 0.75F, tolerance);
    EXPECT_EQ(at(*patch.pixels, 100, 100)[3], 0.0F);
    ASSERT_TRUE(patch.mask);
    EXPECT_TRUE(patch.mask->disabled);
    EXPECT_FALSE(patch.mask->linked);
    EXPECT_NEAR(maskAt(patch.mask->coverage, 50, 50), 1.0F, tolerance);
    EXPECT_NEAR(maskAt(patch.mask->coverage, 150, 50), 0.0F, tolerance);
    ASSERT_EQ(patch.extra.size(), 1U);
    EXPECT_EQ(patch.extra[0].key, "TySh");
    EXPECT_EQ(patch.extra[0].data.size(), 6U) << "padded to even";
    EXPECT_EQ(patch.extra[0].data[4], 5);
    const auto& folder = back.layers[3];
    EXPECT_EQ(folder.kind, ps::LayerKind::GroupFolder);
    EXPECT_FALSE(folder.group_open);
    EXPECT_TRUE(folder.pass_through);
    ASSERT_TRUE(back.composite);
    EXPECT_NEAR(at(*back.composite, w - 1, 0)[0], 1.0F, tolerance);
    EXPECT_NEAR(at(*back.composite, 0, 0)[3], 1.0F, tolerance);
}

TEST(PsdWriter, RoundTripsEveryDepth) {
    for (const int depth : {8, 16, 32}) {
        SCOPED_TRACE(depth);
        const auto doc = sample(depth);
        std::string error;
        const auto bytes = ps::write(doc, error);
        ASSERT_TRUE(bytes) << error;
        EXPECT_EQ((*bytes)[4], 0);
        EXPECT_EQ((*bytes)[5], 1) << "PSD, not PSB";
        const auto back = ps::read(*bytes, error);
        ASSERT_TRUE(back) << error;
        EXPECT_TRUE(back->warnings.empty()) << back->warnings.front();
        expectRoundTrip(*back, depth, 300);
    }
}

TEST(PsdWriter, BlendIfRangesRoundTrip) {
    auto doc = sample(8);
    ASSERT_FALSE(doc.layers.empty());
    std::array<std::uint8_t, 32> ranges = ps::kDefaultBlendRanges;
    const std::array<std::uint8_t, 8> gray{10, 40, 180, 220, 5, 5, 250, 255};
    const std::array<std::uint8_t, 8> red{20, 30, 200, 210, 0, 0, 255, 255};
    const std::array<std::uint8_t, 8> blue{0, 0, 255, 255, 12, 24, 230, 250};
    std::copy(gray.begin(), gray.end(), ranges.begin());
    std::copy(red.begin(), red.end(), ranges.begin() + 8);
    std::copy(blue.begin(), blue.end(), ranges.begin() + 24);
    for (auto& layer : doc.layers) {
        if (layer.kind == ps::LayerKind::Pixel) {
            layer.blend_ranges = ranges;
            break;
        }
    }
    std::string error;
    const auto bytes = ps::write(doc, error);
    ASSERT_TRUE(bytes) << error;
    const auto back = ps::read(*bytes, error);
    ASSERT_TRUE(back) << error;
    bool found = false;
    for (const auto& layer : back->layers) {
        if (layer.blend_ranges == ranges) found = true;
    }
    EXPECT_TRUE(found) << "the Blend If ranges should survive a write and read";
    for (const auto& layer : back->layers) {
        if (layer.blend_ranges != ranges) {
            EXPECT_EQ(layer.blend_ranges, ps::kDefaultBlendRanges);
        }
    }
}

TEST(PsdWriter, EveryCompressionRoundTrips) {
    for (const auto c : {ps::Compression::Raw, ps::Compression::Rle, ps::Compression::Zip, ps::Compression::ZipPredicted}) {
        const auto doc = sample(16);
        ps::WriteOptions options;
        options.compression = c;
        std::string error;
        const auto bytes = ps::write(doc, error, options);
        ASSERT_TRUE(bytes) << error;
        const auto back = ps::read(*bytes, error);
        ASSERT_TRUE(back) << error;
        expectRoundTrip(*back, 16, 300);
    }
}

TEST(PsdWriter, LargeCanvasesBecomePsb) {
    CROPTOP_REQUIRE_STRESS();
    const auto doc = sample(8, 30001, 200);
    std::string error;
    const auto bytes = ps::write(doc, error);
    ASSERT_TRUE(bytes) << error;
    EXPECT_EQ((*bytes)[5], 2) << "PSB";
    const auto back = ps::read(*bytes, error);
    ASSERT_TRUE(back) << error;
    EXPECT_TRUE(back->psb);
    expectRoundTrip(*back, 8, 30001);
}

TEST(PsdWriter, RejectsInconsistentDocuments) {
    std::string error;
    auto doc = sample(8);
    doc.composite.reset();
    EXPECT_FALSE(ps::write(doc, error));
    auto odd = sample(8);
    odd.depth = 12;
    EXPECT_FALSE(ps::write(odd, error));
    auto mismatched = sample(8);
    mismatched.layers[0].pixels.reset();
    mismatched.layers[0].pixels.emplace(PixelFormat::RGBA8, 10, 10);
    EXPECT_FALSE(ps::write(mismatched, error));
    ps::WriteOptions tiny;
    tiny.max_output_bytes = 1000;
    EXPECT_FALSE(ps::write(sample(8), error, tiny));
}

TEST(PsdWriter, ReadWriteReadIsStable) {
    std::string error;
    const auto first = ps::write(sample(8), error);
    ASSERT_TRUE(first) << error;
    const auto read_back = ps::read(*first, error);
    ASSERT_TRUE(read_back) << error;
    const auto second = ps::write(*read_back, error);
    ASSERT_TRUE(second) << error;
    EXPECT_EQ(*first, *second) << "writing what was read reproduces the same bytes";
}

}  // namespace
