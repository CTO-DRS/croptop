// Tests for the flat layer-styles encoding and a render through it.
#include "layer_styles_codec.h"

#include <gtest/gtest.h>

#include <cstring>
#include <limits>
#include <string>

using namespace croptop;
using namespace croptop::styles;

namespace {
TileStore disc() {
    TileStore s(PixelFormat::RGBA8, 120, 120);
    TileBuffer t(s.tile_byte_size(), 0);
    for (int y = 0; y < 120; ++y)
        for (int x = 0; x < 120; ++x) {
            const bool in = (x - 60) * (x - 60) + (y - 60) * (y - 60) < 30 * 30;
            auto* p = t.data() + (static_cast<std::size_t>(y) * kTileEdge + x) * 4;
            p[0] = 200; p[1] = 60; p[2] = 40; p[3] = in ? 255 : 0;
        }
    EXPECT_TRUE(s.writeTile({0, 0}, t));
    return s;
}
LayerStyles busy() {
    LayerStyles s;
    s.drop_shadow.enabled = true;
    s.drop_shadow.distance = 8;
    s.drop_shadow.size = 6;
    s.stroke = {true, {0, 0, 1, 1}, BlendMode::Normal, 1.0F, 3.0F, StrokePosition::Outside};
    s.gradient_overlay.enabled = true;
    s.gradient_overlay.opacity = 0.5F;
    s.gradient_overlay.stops = {{0, {1, 0, 0, 1}}, {0.5F, {0, 1, 0, 1}}, {1, {0, 0, 1, 1}}};
    s.bevel.enabled = true;
    s.bevel.style = BevelStyle::Emboss;
    s.inner_glow.enabled = true;
    s.inner_glow.from_center = true;
    return s;
}
}  // namespace

TEST(LayerStylesCodec, RoundTripKeepsEveryBlock) {
    const auto original = busy();
    const auto encoded = encodeStyles(original);
    std::string error;
    const auto decoded = decodeStyles(encoded, nullptr, error);
    ASSERT_TRUE(decoded.has_value()) << error;
    EXPECT_EQ(encodeStyles(*decoded), encoded);
    EXPECT_TRUE(decoded->drop_shadow.enabled);
    EXPECT_EQ(decoded->bevel.style, BevelStyle::Emboss);
    EXPECT_EQ(decoded->gradient_overlay.stops.size(), 3U);
    EXPECT_TRUE(decoded->inner_glow.from_center);
    EXPECT_EQ(decoded->stroke.position, StrokePosition::Outside);
}

TEST(LayerStylesCodec, DecodedStylesRenderEffectLayersAndFlatten) {
    std::string error;
    const auto styles = decodeStyles(encodeStyles(busy()), nullptr, error);
    ASSERT_TRUE(styles.has_value()) << error;
    const auto layer = disc();
    const auto effects = render(layer, *styles, error);
    ASSERT_TRUE(effects.has_value()) << error;
    bool below = false, above = false;
    for (const auto& e : *effects) (e.below_content ? below : above) = true;
    EXPECT_TRUE(below);   // the drop shadow
    EXPECT_TRUE(above);   // stroke / overlays / bevel
    TileStore backdrop(PixelFormat::RGBA8, 120, 120);
    TileBuffer white(backdrop.tile_byte_size(), 255);
    ASSERT_TRUE(backdrop.writeTile({0, 0}, white));
    const auto flat = flatten(layer, *styles, backdrop, 1.0F, error);
    ASSERT_TRUE(flat.has_value()) << error;
    EXPECT_NE(*flat->readTile({0, 0}), *backdrop.readTile({0, 0}));
    const auto centre = flat->readTile({0, 0});
    EXPECT_NE((*centre)[(60 * kTileEdge + 60) * 4 + 2], 255);  // the disc (and its overlays) replaced the white backdrop
}

TEST(LayerStylesCodec, MalformedDataIsRejected) {
    std::string error;
    auto good = encodeStyles(busy());
    auto bad = good;
    bad.pop_back();
    EXPECT_FALSE(decodeStyles(bad, nullptr, error).has_value());
    bad = good;
    bad.push_back(0);
    EXPECT_FALSE(decodeStyles(bad, nullptr, error).has_value());
    bad = good;
    bad[0] = 2;  // flag out of range
    EXPECT_FALSE(decodeStyles(bad, nullptr, error).has_value());
    bad = good;
    bad[5] = 99;  // blend mode ordinal
    EXPECT_FALSE(decodeStyles(bad, nullptr, error).has_value());
    bad = good;
    bad[7] = std::numeric_limits<float>::infinity();
    EXPECT_FALSE(decodeStyles(bad, nullptr, error).has_value());
    LayerStyles patterned;
    patterned.pattern_overlay.enabled = true;
    EXPECT_FALSE(decodeStyles(encodeStyles(patterned), nullptr, error).has_value());
    EXPECT_NE(error.find("pattern"), std::string::npos);
}

TEST(LayerStylesCodec, DefaultLengthMatchesTheKotlinBridge) {
    EXPECT_EQ(encodeStyles({}).size(), 111U);  // NativeLayerStylesBridgeEncodingTest pins the same number
}
