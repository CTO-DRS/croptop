// Tests for layered PSD opening.
#include "psd_import.h"

#include <gtest/gtest.h>

#include <functional>

#include "pixel_plane.h"
#include "psd_reader.h"
#include "psd_writer.h"
#include "selection_mask.h"

namespace {

using croptop::PixelFormat;
using croptop::TileStore;
namespace psd = croptop::psd;

TileStore image(int w, int h, const std::function<std::array<float, 4>(int, int)>& pixel) {
    TileStore s(PixelFormat::RGBA8, static_cast<std::uint32_t>(w), static_cast<std::uint32_t>(h));
    for (std::int32_t ty = 0; ty * 256 < h; ++ty)
        for (std::int32_t tx = 0; tx * 256 < w; ++tx) {
            croptop::TileBuffer t(s.tile_byte_size(), 0);
            for (int y = 0; y < 256; ++y)
                for (int x = 0; x < 256; ++x) {
                    const int gx = tx * 256 + x, gy = ty * 256 + y;
                    if (gx >= w || gy >= h) continue;
                    const auto v = pixel(gx, gy);
                    croptop::paint::detail::writePixel(PixelFormat::RGBA8, t.data() + (static_cast<std::size_t>(y) * 256 + x) * 4, v.data());
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

psd::Layer pixelLayer(const std::string& name, std::array<float, 4> colour, int x0, int y0, int x1, int y1) {
    psd::Layer l;
    l.name = name;
    l.pixels.emplace(image(200, 100, [=](int x, int y) { return x >= x0 && x < x1 && y >= y0 && y < y1 ? colour : std::array<float, 4>{0, 0, 0, 0}; }));
    l.left = x0;
    l.top = y0;
    l.right = x1;
    l.bottom = y1;
    return l;
}

/** Bottom → top: Background; [group "Shapes" (pass-through): Red (multiply 50 %, masked), Blue (clipped)]; Title (text). */
std::vector<std::uint8_t> sampleFile() {
    psd::Document doc;
    doc.width = 200;
    doc.height = 100;
    auto background = pixelLayer("Background", {1, 1, 1, 1}, 0, 0, 200, 100);
    psd::Layer divider;
    divider.name = "</Layer group>";
    divider.kind = psd::LayerKind::GroupDivider;
    auto red = pixelLayer("Red", {1, 0, 0, 1}, 20, 20, 80, 80);
    red.mode = croptop::BlendMode::Multiply;
    red.opacity = 128;
    red.mask.emplace(psd::Mask{croptop::selection::rasterizeRect(200, 100, {20, 20, 50, 80}, false), 0, false, true});
    auto blue = pixelLayer("Blue", {0, 0, 1, 1}, 40, 40, 120, 90);
    blue.clipped = true;
    psd::Layer folder;
    folder.name = "Shapes";
    folder.kind = psd::LayerKind::GroupFolder;
    folder.pass_through = true;
    folder.group_open = false;
    auto title = pixelLayer("Title \"quoted\"", {0, 0, 0, 1}, 10, 5, 190, 15);
    title.extra.push_back({"TySh", {0, 1, 2, 3}});
    doc.layers = {background, divider, red, blue, folder, title};
    doc.composite.emplace(image(200, 100, [](int, int) { return std::array<float, 4>{1, 1, 1, 1}; }));
    std::string error;
    auto bytes = psd::write(doc, error);
    EXPECT_TRUE(bytes) << error;
    return bytes ? *bytes : std::vector<std::uint8_t>{};
}

TEST(PsdImport, BuildsTheLayerTreeInPanelOrder) {
    const auto file = sampleFile();
    TileStore pixel_template(PixelFormat::RGBA16, 200, 100);
    TileStore mask_template(PixelFormat::A16, 200, 100);
    std::string error;
    const auto doc = psd::importLayers(file, pixel_template, &mask_template, error);
    ASSERT_TRUE(doc) << error;
    ASSERT_EQ(doc->layers.size(), 3U);
    const auto& title = doc->layers[0];
    EXPECT_EQ(title.kind, psd::ImportKind::Text);
    EXPECT_EQ(title.name, "Title \"quoted\"");
    ASSERT_TRUE(title.pixels);
    EXPECT_EQ(title.pixels->format(), PixelFormat::RGBA16) << "layers take the document's depth";
    const auto& group = doc->layers[1];
    EXPECT_EQ(group.kind, psd::ImportKind::Group);
    EXPECT_EQ(group.name, "Shapes");
    EXPECT_EQ(group.blend_key, "pass");
    EXPECT_FALSE(group.group_open);
    ASSERT_EQ(group.children.size(), 2U);
    const auto& blue = group.children[0];
    const auto& red = group.children[1];
    EXPECT_EQ(blue.name, "Blue");
    EXPECT_TRUE(blue.clipped);
    EXPECT_EQ(red.name, "Red");
    EXPECT_EQ(red.blend_key, "mul ");
    EXPECT_EQ(red.opacity, 128);
    ASSERT_TRUE(red.mask);
    EXPECT_EQ(red.mask->format(), PixelFormat::A16);
    EXPECT_EQ(croptop::selection::coverageAt(*red.mask, 30, 50), 65535);
    EXPECT_EQ(croptop::selection::coverageAt(*red.mask, 70, 50), 0);
    EXPECT_NEAR(at(*red.pixels, 50, 50)[0], 1.0F, 1e-4F);
    EXPECT_EQ(doc->layers[2].name, "Background");
    EXPECT_EQ(doc->layers[2].kind, psd::ImportKind::Pixel);
    EXPECT_NEAR(at(*doc->layers[2].pixels, 150, 50)[1], 1.0F, 1e-4F);

    std::vector<const TileStore*> handles;
    const auto json = psd::toJson(*doc, handles);
    EXPECT_EQ(handles.size(), 5U) << "title, blue, red (+ mask), background";
    EXPECT_NE(json.find("\"name\":\"Title \\\"quoted\\\"\""), std::string::npos) << json;
    EXPECT_NE(json.find("\"kind\":\"group\",\"name\":\"Shapes\",\"blend\":\"pass\""), std::string::npos);
    EXPECT_NE(json.find("\"blend\":\"mul \""), std::string::npos);
    EXPECT_NE(json.find("\"clipped\":true"), std::string::npos);
}

TEST(PsdImport, FlatFilesAndBadTemplates) {
    psd::Document flat;
    flat.width = 64;
    flat.height = 32;
    flat.composite.emplace(image(64, 32, [](int x, int) { return std::array<float, 4>{x < 32 ? 1.0F : 0.0F, 0, 0, 1}; }));
    std::string error;
    auto bytes = psd::write(flat, error);
    ASSERT_TRUE(bytes) << error;
    TileStore t8(PixelFormat::RGBA8, 64, 32);
    const auto doc = psd::importLayers(*bytes, t8, nullptr, error);
    ASSERT_TRUE(doc) << error;
    ASSERT_EQ(doc->layers.size(), 1U);
    EXPECT_EQ(doc->layers[0].name, "Background");
    EXPECT_NEAR(at(*doc->layers[0].pixels, 10, 10)[0], 1.0F, 1e-4F);
    TileStore wrong(PixelFormat::RGBA8, 10, 10);
    EXPECT_FALSE(psd::importLayers(*bytes, wrong, nullptr, error));
    TileStore mask(PixelFormat::A16, 64, 32);
    EXPECT_FALSE(psd::importLayers(*bytes, mask, nullptr, error));
    EXPECT_FALSE(psd::importLayers(std::vector<std::uint8_t>{1, 2, 3}, t8, nullptr, error));
}

}  // namespace
