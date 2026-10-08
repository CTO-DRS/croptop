// Tests for the layer styles CPU renderer.
#include "layer_styles.h"
#include "tile_store.h"

#include <gtest/gtest.h>

#include <array>
#include <chrono>
#include <cstring>
#include <filesystem>
#include <memory>
#include <string>

#include "pixel_plane.h"
#include "scratch_store.h"

namespace {

namespace st = croptop::styles;
using croptop::BlendMode;
using croptop::PixelFormat;
using croptop::TileStore;

/** A 300 × 300 transparent layer with an opaque grey square [100, 200)². */
TileStore square() {
    TileStore s(PixelFormat::RGBA8, 300, 300);
    for (std::int32_t ty = 0; ty < 2; ++ty)
        for (std::int32_t tx = 0; tx < 2; ++tx) {
            croptop::TileBuffer t(s.tile_byte_size(), 0);
            bool any = false;
            for (std::uint32_t y = 0; y < 256; ++y)
                for (std::uint32_t x = 0; x < 256; ++x) {
                    const auto gx = static_cast<std::uint32_t>(tx) * 256 + x, gy = static_cast<std::uint32_t>(ty) * 256 + y;
                    if (gx < 100 || gx >= 200 || gy < 100 || gy >= 200) continue;
                    std::uint8_t* p = t.data() + (y * 256 + x) * 4;
                    p[0] = p[1] = p[2] = 128;
                    p[3] = 255;
                    any = true;
                }
            if (any) {
                EXPECT_TRUE(s.writeTile({tx, ty}, t));
            }
        }
    return s;
}

std::array<float, 4> at(const TileStore& s, std::uint32_t x, std::uint32_t y) {
    std::array<float, 4> c{};
    const auto t = s.readTile({static_cast<std::int32_t>(x / 256), static_cast<std::int32_t>(y / 256)});
    if (t) croptop::paint::detail::readPixel(s.format(), t->data() + (static_cast<std::size_t>(y % 256) * 256 + x % 256) * s.bytes_per_pixel(), c.data());
    return c;
}

const st::EffectLayer& find(const std::vector<st::EffectLayer>& effects, const char* name) {
    for (const auto& e : effects)
        if (std::strcmp(name, e.name) == 0) return e;
    ADD_FAILURE() << "missing " << name;
    return effects.front();
}

TEST(LayerStyles, DropShadowFallsAwayFromTheLight) {
    st::LayerStyles styles;
    styles.drop_shadow.enabled = true;
    styles.drop_shadow.angle = 90;  // light from the top: shadow straight down
    styles.drop_shadow.distance = 20;
    styles.drop_shadow.size = 0;
    std::string error;
    const auto effects = st::render(square(), styles, error);
    ASSERT_TRUE(effects) << error;
    ASSERT_EQ(effects->size(), 1U);
    const auto& shadow = effects->front();
    EXPECT_TRUE(shadow.below_content);
    EXPECT_EQ(shadow.mode, BlendMode::Multiply);
    EXPECT_NEAR(at(shadow.pixels, 150, 215)[3], 1.0F, 0.01F) << "20 px below the square";
    EXPECT_NEAR(at(shadow.pixels, 150, 225)[3], 0.0F, 0.01F);
    EXPECT_NEAR(at(shadow.pixels, 150, 110)[3], 0.0F, 0.01F) << "moved off the top edge";
    styles.drop_shadow.size = 10;
    const auto soft = st::render(square(), styles, error);
    ASSERT_TRUE(soft) << error;
    const float edge = at(soft->front().pixels, 150, 220)[3];
    EXPECT_GT(edge, 0.2F);
    EXPECT_LT(edge, 0.8F) << "size blurs the edge";
}

TEST(LayerStyles, GlowsSpreadAndStayInsideOrOutside) {
    st::LayerStyles styles;
    styles.outer_glow.enabled = true;
    styles.outer_glow.size = 10;
    styles.outer_glow.spread = 100;  // hard 10 px halo
    styles.inner_glow.enabled = true;
    styles.inner_glow.size = 10;
    std::string error;
    const auto effects = st::render(square(), styles, error);
    ASSERT_TRUE(effects) << error;
    const auto& outer = find(*effects, "Outer Glow");
    EXPECT_NEAR(at(outer.pixels, 205, 150)[3], 1.0F, 0.01F);
    EXPECT_NEAR(at(outer.pixels, 215, 150)[3], 0.0F, 0.01F);
    const auto& inner = find(*effects, "Inner Glow");
    EXPECT_FALSE(inner.below_content);
    EXPECT_GT(at(inner.pixels, 101, 150)[3], 0.4F) << "bright at the inside edge";
    EXPECT_LT(at(inner.pixels, 150, 150)[3], 0.05F) << "fades towards the centre";
    EXPECT_EQ(at(inner.pixels, 90, 150)[3], 0.0F) << "never outside";
}

TEST(LayerStyles, InnerShadowSitsOnTheEdgeAwayFromTheLight) {
    st::LayerStyles styles;
    styles.inner_shadow.enabled = true;
    styles.inner_shadow.angle = 90;  // light from the top: the shadow hugs the top inside edge
    styles.inner_shadow.distance = 8;
    styles.inner_shadow.size = 0;
    std::string error;
    const auto effects = st::render(square(), styles, error);
    ASSERT_TRUE(effects) << error;
    const auto& shadow = effects->front();
    EXPECT_NEAR(at(shadow.pixels, 150, 103)[3], 1.0F, 0.01F);
    EXPECT_NEAR(at(shadow.pixels, 150, 196)[3], 0.0F, 0.01F);
    EXPECT_EQ(at(shadow.pixels, 150, 90)[3], 0.0F) << "clipped to the content";
}

TEST(LayerStyles, StrokePositions) {
    std::string error;
    for (const auto position : {st::StrokePosition::Outside, st::StrokePosition::Inside, st::StrokePosition::Center}) {
        st::LayerStyles styles;
        styles.stroke.enabled = true;
        styles.stroke.size = 6;
        styles.stroke.position = position;
        const auto effects = st::render(square(), styles, error);
        ASSERT_TRUE(effects) << error;
        const auto& stroke = effects->front();
        const float out3 = at(stroke.pixels, 203, 150)[3], out8 = at(stroke.pixels, 208, 150)[3], in2 = at(stroke.pixels, 197, 150)[3], in9 = at(stroke.pixels, 190, 150)[3];
        switch (position) {
            case st::StrokePosition::Outside:
                EXPECT_NEAR(out3, 1, 0.01F);
                EXPECT_NEAR(out8, 0, 0.01F);
                EXPECT_NEAR(in2, 0, 0.01F);
                break;
            case st::StrokePosition::Inside:
                EXPECT_NEAR(in2, 1, 0.01F);
                EXPECT_NEAR(in9, 0, 0.01F);
                EXPECT_NEAR(out3, 0, 0.01F);
                break;
            case st::StrokePosition::Center:
                EXPECT_NEAR(at(stroke.pixels, 202, 150)[3], 1, 0.01F);
                EXPECT_NEAR(in2, 1, 0.01F);
                EXPECT_NEAR(out8, 0, 0.01F);
                EXPECT_NEAR(in9, 0, 0.01F);
                break;
        }
        EXPECT_EQ(at(stroke.pixels, 150, 150)[3], 0.0F);
    }
}

TEST(LayerStyles, OverlaysFollowTheContent) {
    st::LayerStyles styles;
    styles.color_overlay.enabled = true;
    styles.color_overlay.color = {0, 1, 0, 1};
    styles.gradient_overlay.enabled = true;
    styles.gradient_overlay.stops = {{0, {1, 0, 0, 1}}, {1, {0, 0, 1, 1}}};  // bottom red → top blue at 90°
    std::string error;
    const auto effects = st::render(square(), styles, error);
    ASSERT_TRUE(effects) << error;
    const auto& color = find(*effects, "Color Overlay");
    EXPECT_NEAR(at(color.pixels, 150, 150)[1], 1.0F, 0.01F);
    EXPECT_EQ(at(color.pixels, 50, 50)[3], 0.0F);
    const auto& gradient = find(*effects, "Gradient Overlay");
    EXPECT_GT(at(gradient.pixels, 150, 198)[0], 0.9F) << "bottom is the first stop";
    EXPECT_GT(at(gradient.pixels, 150, 101)[2], 0.9F) << "top is the last stop";
}

TEST(LayerStyles, BevelLightsTheEdgeFacingTheLight) {
    st::LayerStyles styles;
    styles.bevel.enabled = true;
    styles.bevel.size = 10;
    styles.bevel.angle = 90;  // light from the top
    styles.bevel.altitude = 30;
    std::string error;
    const auto effects = st::render(square(), styles, error);
    ASSERT_TRUE(effects) << error;
    const auto& highlight = find(*effects, "Bevel Highlight");
    const auto& shadow = find(*effects, "Bevel Shadow");
    EXPECT_GT(at(highlight.pixels, 150, 104)[3], 0.2F) << "top inside edge is lit";
    EXPECT_GT(at(shadow.pixels, 150, 195)[3], 0.2F) << "bottom inside edge is shaded";
    EXPECT_LT(at(highlight.pixels, 150, 150)[3], 0.02F) << "flat plateau: no highlight";
    EXPECT_LT(at(shadow.pixels, 150, 150)[3], 0.02F);
    styles.bevel.up = false;
    const auto down = st::render(square(), styles, error);
    ASSERT_TRUE(down) << error;
    EXPECT_GT(at(find(*down, "Bevel Shadow").pixels, 150, 104)[3], 0.2F) << "Down swaps the lighting";
}

TEST(LayerStyles, FlattenCompositesInPhotoshopOrder) {
    TileStore white(PixelFormat::RGBA8, 300, 300);
    for (std::int32_t ty = 0; ty < 2; ++ty)
        for (std::int32_t tx = 0; tx < 2; ++tx) {
            croptop::TileBuffer t(white.tile_byte_size(), 255);
            ASSERT_TRUE(white.writeTile({tx, ty}, t));
        }
    st::LayerStyles styles;
    styles.drop_shadow.enabled = true;
    styles.drop_shadow.angle = 90;
    styles.drop_shadow.distance = 20;
    styles.drop_shadow.size = 0;
    styles.drop_shadow.opacity = 0.5F;
    styles.stroke.enabled = true;
    styles.stroke.size = 3;
    std::string error;
    const auto flat = st::flatten(square(), styles, white, 1.0F, error);
    ASSERT_TRUE(flat) << error;
    EXPECT_NEAR(at(*flat, 150, 215)[0], 0.5F, 0.01F) << "50 % black multiply on white";
    EXPECT_NEAR(at(*flat, 150, 150)[0], 128 / 255.0F, 0.01F) << "content on top of its shadow";
    EXPECT_NEAR(at(*flat, 201, 150)[0], 1.0F, 0.01F);
    EXPECT_NEAR(at(*flat, 201, 150)[1], 0.0F, 0.01F) << "red stroke above everything";
    EXPECT_NEAR(at(*flat, 20, 20)[0], 1.0F, 0.01F);
}

TEST(LayerStyles, SatinShadesTheInteriorAlongItsAngle) {
    st::LayerStyles styles;
    styles.satin.enabled = true;
    styles.satin.angle = 0;  // shifts left/right
    styles.satin.distance = 10;
    styles.satin.size = 0;
    styles.satin.invert = false;
    std::string error;
    const auto effects = st::render(square(), styles, error);
    ASSERT_TRUE(effects) << error;
    const auto& satin = find(*effects, "Satin");
    EXPECT_FALSE(satin.below_content);
    EXPECT_NEAR(at(satin.pixels, 105, 150)[3], 1.0F, 0.01F) << "near the left and right edges the shifted copies differ";
    EXPECT_NEAR(at(satin.pixels, 150, 150)[3], 0.0F, 0.01F) << "in the middle they agree";
    EXPECT_EQ(at(satin.pixels, 95, 150)[3], 0.0F) << "clipped to the content";
}

TEST(LayerStyles, ValidationAndEmptyLayers) {
    std::string error;
    st::LayerStyles styles;
    styles.drop_shadow.enabled = true;
    styles.drop_shadow.size = 1000;
    EXPECT_FALSE(st::render(square(), styles, error));
    const auto none = st::render(TileStore(PixelFormat::RGBA8, 100, 100), {}, error);
    ASSERT_TRUE(none);
    EXPECT_TRUE(none->empty());
    EXPECT_FALSE(st::render(TileStore(PixelFormat::A16, 10, 10), {}, error));
}

TEST(LayerStyles, EffectTilesShareTheSourceDocumentMemoryBudget) {
    const auto directory = std::filesystem::temp_directory_path() /
        ("croptop-style-budget-" + std::to_string(std::chrono::steady_clock::now().time_since_epoch().count()));
    auto scratch = std::make_shared<croptop::DiskScratchStore>(directory);
    auto pool = std::make_shared<croptop::TilePool>();
    auto budget = std::make_shared<croptop::MemoryBudgetManager>(16U * 256U * 256U * 4U, scratch);
    {
        TileStore layer(PixelFormat::RGBA8, 256, 256, pool, budget);
        croptop::TileBuffer tile(layer.tile_byte_size(), 0);
        for (std::size_t i = 0; i < tile.size(); i += 4) {
            tile[i] = tile[i + 1] = tile[i + 2] = tile[i + 3] = 255;
        }
        ASSERT_TRUE(layer.writeTile({0, 0}, tile));
        const auto source_resident = budget->residentBytes();

        st::LayerStyles styles;
        styles.color_overlay.enabled = true;
        styles.color_overlay.color = {1.0F, 0.0F, 0.0F, 1.0F};
        std::string error;
        auto effects = st::render(layer, styles, error);
        ASSERT_TRUE(effects) << error;
        ASSERT_EQ(effects->size(), 1U);
        EXPECT_EQ(effects->front().pixels.resident_bytes(), layer.tile_byte_size());
        EXPECT_EQ(budget->residentBytes(), source_resident + layer.tile_byte_size());
        EXPECT_LE(budget->residentBytes(), budget->budgetBytes());
    }
    std::filesystem::remove_all(directory);
}

TEST(LayerStyles, TemporaryPlanesMustFitTheDocumentBudgetAndReleaseTheirReservation) {
    const auto directory = std::filesystem::temp_directory_path() /
        ("croptop-style-reservation-" + std::to_string(std::chrono::steady_clock::now().time_since_epoch().count()));
    auto scratch = std::make_shared<croptop::DiskScratchStore>(directory);
    auto pool = std::make_shared<croptop::TilePool>();
    const std::size_t tile_bytes = 256U * 256U * 4U;
    auto budget = std::make_shared<croptop::MemoryBudgetManager>(tile_bytes * 2U, scratch);
    {
        TileStore layer(PixelFormat::RGBA8, 256, 256, pool, budget);
        croptop::TileBuffer tile(layer.tile_byte_size(), 255);
        ASSERT_TRUE(layer.writeTile({0, 0}, tile));
        st::LayerStyles styles;
        styles.color_overlay.enabled = true;
        std::string error;
        EXPECT_FALSE(st::render(layer, styles, error));
        EXPECT_NE(error.find("document memory budget"), std::string::npos);
        EXPECT_TRUE(budget->reserveWorkingBytes(budget->budgetBytes()));
        budget->releaseWorkingBytes(budget->budgetBytes());
    }
    std::filesystem::remove_all(directory);
}

}  // namespace
