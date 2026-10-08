// Tests for TIFF / PSD import helpers.
#include "codec_extra.h"

#include <gtest/gtest.h>

#include <cstring>
#include <functional>

#include "pixel_plane.h"
#include "psd_writer.h"
#include "tiff_codec.h"

namespace {

using croptop::PixelFormat;
using croptop::TileStore;
namespace codec = croptop::codec;

TileStore image(PixelFormat format, int w, int h, const std::function<std::array<float, 4>(int, int)>& pixel) {
    TileStore s(format, static_cast<std::uint32_t>(w), static_cast<std::uint32_t>(h));
    for (std::int32_t ty = 0; ty * 256 < h; ++ty)
        for (std::int32_t tx = 0; tx * 256 < w; ++tx) {
            croptop::TileBuffer t(s.tile_byte_size(), 0);
            for (int y = 0; y < 256; ++y)
                for (int x = 0; x < 256; ++x) {
                    const int gx = tx * 256 + x, gy = ty * 256 + y;
                    if (gx >= w || gy >= h) continue;
                    const auto v = pixel(gx, gy);
                    croptop::paint::detail::writePixel(format, t.data() + (static_cast<std::size_t>(y) * 256 + x) * s.bytes_per_pixel(), v.data());
                }
            EXPECT_TRUE(s.writeTile({tx, ty}, t));
        }
    return s;
}

std::array<float, 4> at(const TileStore& s, int x, int y) {
    const auto tile = s.readTile({x / 256, y / 256});
    std::array<float, 4> v{};
    if (tile) croptop::paint::detail::readPixel(s.format(), tile->data() + (static_cast<std::size_t>(y % 256) * 256 + x % 256) * s.bytes_per_pixel(), v.data());
    return v;
}

std::array<float, 4> gradient(int x, int y) { return {static_cast<float>(x) / 299.0F, static_cast<float>(y) / 199.0F, 0.25F, x < 20 ? 0.5F : 1.0F}; }

TEST(CodecExtra, TiffProbesAndDecodesAtItsDepth) {
    std::string error;
    for (const auto format : {PixelFormat::RGBA8, PixelFormat::RGBA16}) {
        const auto source = image(format, 300, 200, gradient);
        const auto bytes = croptop::tiff::encode(source, error);
        ASSERT_TRUE(bytes) << error;
        EXPECT_EQ(codec::detect(*bytes), codec::ExtraFormat::Tiff);
        const auto p = codec::probe(*bytes, error);
        ASSERT_TRUE(p) << error;
        EXPECT_EQ(p->width, 300U);
        EXPECT_EQ(p->height, 200U);
        EXPECT_EQ(p->pixel_format, format);
        TileStore destination(p->pixel_format, p->width, p->height);
        const auto result = codec::decodeToTiles(*bytes, destination);
        ASSERT_TRUE(result.success) << result.error;
        EXPECT_EQ(result.raster.format, "tiff");
        for (const auto& [x, y] : std::vector<std::pair<int, int>>{{0, 0}, {150, 100}, {299, 199}, {270, 10}})
            for (int k = 0; k < 4; ++k) EXPECT_NEAR(at(destination, x, y)[static_cast<std::size_t>(k)], at(source, x, y)[static_cast<std::size_t>(k)], 1e-4F);
    }
    // A 16-bit TIFF opened into an 8-bit document is converted.
    const auto deep = image(PixelFormat::RGBA16, 300, 200, gradient);
    const auto bytes = croptop::tiff::encode(deep, error);
    ASSERT_TRUE(bytes);
    TileStore eight(PixelFormat::RGBA8, 300, 200);
    ASSERT_TRUE(codec::decodeToTiles(*bytes, eight).success);
    EXPECT_NEAR(at(eight, 150, 100)[0], 150.0F / 299.0F, 1.0F / 255.0F);
}

TEST(CodecExtra, PsdUsesTheCompositeOrRebuildsIt) {
    std::string error;
    croptop::psd::Document doc;
    doc.width = 300;
    doc.height = 200;
    croptop::psd::Layer base;
    base.name = "Background";
    base.pixels.emplace(image(PixelFormat::RGBA8, 300, 200, [](int, int) { return std::array<float, 4>{0.2F, 0.4F, 0.6F, 1.0F}; }));
    base.right = 300;
    base.bottom = 200;
    croptop::psd::Layer top;
    top.name = "Red square";
    top.opacity = 128;
    top.pixels.emplace(image(PixelFormat::RGBA8, 300, 200, [](int x, int y) {
        return x >= 100 && x < 200 && y >= 50 && y < 150 ? std::array<float, 4>{1, 0, 0, 1} : std::array<float, 4>{0, 0, 0, 0};
    }));
    top.left = 100;
    top.top = 50;
    top.right = 200;
    top.bottom = 150;
    doc.layers = {base, top};
    doc.composite.emplace(image(PixelFormat::RGBA8, 300, 200, [](int, int) { return std::array<float, 4>{0, 1, 0, 1}; }));
    const auto full = croptop::psd::write(doc, error);
    ASSERT_TRUE(full) << error;
    // Cut the file after the layer section: the merged image is missing, so it is rebuilt from the layers.
    const auto be32 = [&](std::size_t at) {
        return (static_cast<std::uint32_t>((*full)[at]) << 24) | (static_cast<std::uint32_t>((*full)[at + 1]) << 16) | (static_cast<std::uint32_t>((*full)[at + 2]) << 8) |
               (*full)[at + 3];
    };
    std::size_t offset = 26;
    offset += 4 + be32(offset);  // colour mode data
    offset += 4 + be32(offset);  // image resources
    offset += 4 + be32(offset);  // layer and mask information
    ASSERT_LT(offset, full->size());
    const std::vector<std::uint8_t> bytes(full->begin(), full->begin() + static_cast<std::ptrdiff_t>(offset));
    EXPECT_EQ(codec::detect(bytes), codec::ExtraFormat::Psd);
    const auto p = codec::probe(bytes, error);
    ASSERT_TRUE(p) << error;
    EXPECT_EQ(p->width, 300U);
    EXPECT_EQ(p->height, 200U);
    EXPECT_EQ(p->pixel_format, PixelFormat::RGBA8);
    TileStore destination(p->pixel_format, p->width, p->height);
    std::vector<std::string> warnings;
    const auto result = codec::decodeToTiles(bytes, destination, &warnings);
    ASSERT_TRUE(result.success) << result.error;
    EXPECT_EQ(result.raster.format, "psd");
    bool rebuilt = false;
    for (const auto& w : warnings) rebuilt = rebuilt || w.find("rebuilt") != std::string::npos;
    EXPECT_TRUE(rebuilt);
    EXPECT_NEAR(at(destination, 10, 10)[2], 0.6F, 1.5F / 255.0F);
    const auto mixed = at(destination, 150, 100);  // 50 % red over blue-grey
    EXPECT_NEAR(mixed[0], 0.5F * 1.0F + 0.5F * 0.2F, 0.02F);
    EXPECT_NEAR(mixed[3], 1.0F, 1e-3F);
    // Saved without "Maximize compatibility": resource 1057 says the merged image is a placeholder.
    {
        std::vector<std::uint8_t> patched(full->begin(), full->end());
        const std::size_t resources_at = 26 + 4 + be32(26);
        const std::vector<std::uint8_t> block{'8', 'B', 'I', 'M', 0x04, 0x21, 0, 0, 0, 0, 0, 5, 0, 0, 0, 1, 0, 0};  // id 1057, size 5, pad
        const auto length = be32(resources_at) + static_cast<std::uint32_t>(block.size());
        patched.insert(patched.begin() + static_cast<std::ptrdiff_t>(resources_at + 4), block.begin(), block.end());
        for (int i = 0; i < 4; ++i) patched[resources_at + static_cast<std::size_t>(i)] = static_cast<std::uint8_t>(length >> (24 - 8 * i));
        TileStore rebuilt(PixelFormat::RGBA8, 300, 200);
        warnings.clear();
        ASSERT_TRUE(codec::decodeToTiles(patched, rebuilt, &warnings).success);
        EXPECT_NEAR(at(rebuilt, 150, 100)[0], 0.6F, 0.02F) << "rebuilt from the layers, not the green placeholder";
        EXPECT_NEAR(at(rebuilt, 150, 100)[1], 0.2F, 0.02F);
        bool noted = false;
        for (const auto& w : warnings) noted = noted || w.find("Maximize Compatibility") != std::string::npos;
        EXPECT_TRUE(noted);
    }
    // With the stored composite present, it is used as is.
    TileStore d2(PixelFormat::RGBA8, 300, 200);
    ASSERT_TRUE(codec::decodeToTiles(*full, d2).success);
    EXPECT_NEAR(at(d2, 150, 100)[1], 1.0F, 1e-3F);
}

TEST(CodecExtra, TiffAndPsdExportRoundTripWithProfiles) {
    std::string error;
    const std::vector<std::uint8_t> icc{0, 0, 0, 24, 'a', 'p', 'p', 'l', 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16};
    for (const auto format : {PixelFormat::RGBA8, PixelFormat::RGBA16}) {
        const auto source = image(format, 300, 200, gradient);
        for (const bool psd : {false, true}) {
            const auto bytes = psd ? codec::encodePsd(source, icc, error) : codec::encodeTiff(source, icc, true, error);
            ASSERT_TRUE(bytes) << error;
            const auto p = codec::probe(*bytes, error);
            ASSERT_TRUE(p) << error;
            EXPECT_EQ(p->format, psd ? codec::ExtraFormat::Psd : codec::ExtraFormat::Tiff);
            EXPECT_EQ(p->pixel_format, format);
            TileStore back(p->pixel_format, p->width, p->height);
            std::vector<std::string> warnings;
            ASSERT_TRUE(codec::decodeToTiles(*bytes, back, &warnings).success);
            for (const auto& [x, y] : std::vector<std::pair<int, int>>{{0, 0}, {150, 100}, {299, 199}, {5, 50}})
                for (int k = 0; k < 4; ++k)
                    EXPECT_NEAR(at(back, x, y)[static_cast<std::size_t>(k)], at(source, x, y)[static_cast<std::size_t>(k)], 1e-4F) << (psd ? "psd " : "tiff ") << x << "," << y;
            EXPECT_TRUE(warnings.empty()) << "a flattened export carries a real merged image";
        }
    }
    const auto tiff_bytes = codec::encodeTiff(image(PixelFormat::RGBA8, 30, 20, gradient), icc, false, error);
    ASSERT_TRUE(tiff_bytes);
    const auto tiff_image = croptop::tiff::decode(*tiff_bytes, error);
    ASSERT_TRUE(tiff_image);
    EXPECT_EQ(tiff_image->icc_profile, icc) << "the profile travels with the TIFF";
    TileStore mask(PixelFormat::A16, 10, 10);
    EXPECT_FALSE(codec::encodeTiff(mask, {}, true, error));
    EXPECT_FALSE(codec::encodePsd(mask, {}, error));
}

TEST(CodecExtra, RejectsMalformedInputAndKeepsTheDestination) {
    std::string error;
    EXPECT_EQ(codec::detect(std::vector<std::uint8_t>{0xFF, 0xD8, 0xFF, 0xE0}), codec::ExtraFormat::Unknown);
    EXPECT_FALSE(codec::probe(std::vector<std::uint8_t>{'I', 'I', 42, 0, 0xFF, 0xFF, 0xFF, 0x7F, 0, 0, 0, 0, 0, 0, 0, 0}, error)) << "IFD offset out of range";
    EXPECT_FALSE(codec::probe(std::vector<std::uint8_t>{'8', 'B', 'P', 'S', 0, 1}, error)) << "truncated PSD";
    std::vector<std::uint8_t> psd(26, 0);
    std::memcpy(psd.data(), "8BPS", 4);
    psd[5] = 1;
    psd[17] = 10;  // height 10, width 0
    EXPECT_FALSE(codec::probe(psd, error));

    const auto source = image(PixelFormat::RGBA8, 300, 200, gradient);
    auto bytes = croptop::tiff::encode(source, error);
    ASSERT_TRUE(bytes);
    TileStore wrong(PixelFormat::RGBA8, 100, 100);
    const auto marker = image(PixelFormat::RGBA8, 100, 100, [](int, int) { return std::array<float, 4>{1, 1, 1, 1}; });
    wrong.writeTile({0, 0}, *marker.readTile({0, 0}));
    EXPECT_FALSE(codec::decodeToTiles(*bytes, wrong).success) << "size mismatch";
    EXPECT_EQ(at(wrong, 5, 5)[0], 1.0F) << "destination untouched";
    bytes->resize(bytes->size() / 2);
    TileStore half(PixelFormat::RGBA8, 300, 200);
    EXPECT_FALSE(codec::decodeToTiles(*bytes, half).success) << "truncated pixel data";
    EXPECT_TRUE(half.tileCoordinates().empty());
}

TEST(CodecExtra, LayeredPsdRoundTripsTheLayerTreeMasksAndPixels) {
    const auto red = image(PixelFormat::RGBA8, 64, 48, [](int, int) { return std::array<float, 4>{1.0F, 0.0F, 0.0F, 1.0F}; });
    const auto blue = image(PixelFormat::RGBA8, 64, 48, [](int x, int) { return std::array<float, 4>{0.0F, 0.0F, 1.0F, x < 32 ? 1.0F : 0.0F}; });
    TileStore coverage(PixelFormat::A16, 64, 48);
    croptop::TileBuffer mask_tile(coverage.tile_byte_size(), 0);
    for (int y = 0; y < 48; ++y)
        for (int x = 0; x < 64; ++x) {
            const std::uint16_t v = y < 24 ? 65535 : 0;
            std::memcpy(mask_tile.data() + (static_cast<std::size_t>(y) * 256 + x) * 2, &v, 2);
        }
    ASSERT_TRUE(coverage.writeTile({0, 0}, mask_tile));

    std::vector<croptop::psd::Layer> layers;
    croptop::psd::Layer background;
    background.name = "Background";
    background.right = 64;
    background.bottom = 48;
    background.pixels.emplace(red);
    layers.push_back(std::move(background));
    croptop::psd::Layer divider;
    divider.kind = croptop::psd::LayerKind::GroupDivider;
    divider.name = "</Layer group>";
    layers.push_back(std::move(divider));
    croptop::psd::Layer child;
    child.name = "Blue half";
    child.right = 64;
    child.bottom = 48;
    child.opacity = 128;
    child.mode = croptop::BlendMode::Multiply;
    child.clipped = true;
    child.visible = false;
    child.pixels.emplace(blue);
    child.mask = croptop::psd::Mask{coverage, 255, false, true};
    layers.push_back(std::move(child));
    croptop::psd::Layer folder;
    folder.kind = croptop::psd::LayerKind::GroupFolder;
    folder.name = "Group 1";
    folder.pass_through = true;
    layers.push_back(std::move(folder));

    std::string error;
    const auto bytes = codec::encodePsdLayered(std::move(layers), red, {}, error);
    ASSERT_TRUE(bytes) << error;
    const auto read = croptop::psd::read(*bytes, error);
    ASSERT_TRUE(read) << error;
    ASSERT_EQ(read->layers.size(), 4U);
    EXPECT_EQ(read->layers[0].name, "Background");
    EXPECT_EQ(read->layers[1].kind, croptop::psd::LayerKind::GroupDivider);
    const auto& blue_layer = read->layers[2];
    EXPECT_EQ(blue_layer.name, "Blue half");
    EXPECT_EQ(blue_layer.opacity, 128);
    EXPECT_EQ(blue_layer.mode, croptop::BlendMode::Multiply);
    EXPECT_TRUE(blue_layer.clipped);
    EXPECT_FALSE(blue_layer.visible);
    ASSERT_TRUE(blue_layer.mask.has_value());
    EXPECT_EQ(blue_layer.mask->default_color, 255);
    EXPECT_EQ(read->layers[3].kind, croptop::psd::LayerKind::GroupFolder);
    EXPECT_EQ(read->layers[3].name, "Group 1");
    EXPECT_TRUE(read->layers[3].pass_through);
    ASSERT_TRUE(blue_layer.pixels.has_value());
    EXPECT_NEAR(at(*blue_layer.pixels, 10, 10)[2], 1.0F, 0.01F);
    EXPECT_NEAR(at(*blue_layer.pixels, 50, 10)[3], 0.0F, 0.01F);
}

TEST(CodecExtra, LayeredPsdRejectsStructuralMistakes) {
    const auto base = image(PixelFormat::RGBA8, 32, 32, gradient);
    std::string error;
    EXPECT_FALSE(codec::encodePsdLayered({}, base, {}, error));
    croptop::psd::Layer folder;
    folder.kind = croptop::psd::LayerKind::GroupFolder;
    std::vector<croptop::psd::Layer> unbalanced;
    unbalanced.push_back(folder);
    EXPECT_FALSE(codec::encodePsdLayered(unbalanced, base, {}, error));
    EXPECT_NE(error.find("no matching divider"), std::string::npos);
    croptop::psd::Layer divider;
    divider.kind = croptop::psd::LayerKind::GroupDivider;
    EXPECT_FALSE(codec::encodePsdLayered({divider}, base, {}, error));
    EXPECT_NE(error.find("no matching folder"), std::string::npos);
    croptop::psd::Layer wrong;
    wrong.name = "wrong size";
    wrong.pixels.emplace(image(PixelFormat::RGBA8, 16, 16, gradient));
    EXPECT_FALSE(codec::encodePsdLayered({wrong}, base, {}, error));
    EXPECT_NE(error.find("does not match"), std::string::npos);
    TileStore a8(PixelFormat::A8, 32, 32);
    EXPECT_FALSE(codec::encodePsdLayered({}, a8, {}, error));
}

}  // namespace
