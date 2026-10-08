// Blend If sliders in the render graph.
#include "blend_if.h"
#include "render_graph.h"

#include <gtest/gtest.h>

#include <array>
#include <cstdint>
#include <cstring>

namespace {

using croptop::BlendIf;
using croptop::BlendIfRange;
using croptop::BlendSpace;
using croptop::PixelFormat;
using croptop::RenderGraph;
using croptop::RenderLayer;
using croptop::TileBuffer;
using croptop::TileStore;

constexpr std::uint32_t kEdge = croptop::kTileEdge;

TileStore solid(std::array<std::uint8_t, 4> rgba) {
    TileStore store(PixelFormat::RGBA8, kEdge, kEdge);
    TileBuffer tile(store.tile_byte_size());
    for (std::size_t p = 0; p < tile.size(); p += 4) std::memcpy(tile.data() + p, rgba.data(), 4);
    EXPECT_TRUE(store.writeTile({0, 0}, tile));
    return store;
}

std::array<std::uint8_t, 4> centre(RenderGraph& graph) {
    const auto tile = graph.renderTile({0, 0});
    std::array<std::uint8_t, 4> out{};
    std::memcpy(out.data(), tile->data() + (static_cast<std::size_t>(10) * kEdge + 10) * 4, 4);
    return out;
}

RenderLayer layer(std::uint64_t id, const TileStore* pixels) {
    RenderLayer l;
    l.id = id;
    l.pixels = pixels;
    return l;
}

TEST(BlendIf, RangeWeightRamps) {
    const BlendIfRange r{50, 100, 200, 250};
    EXPECT_FLOAT_EQ(r.weight(0.0F), 0.0F);
    EXPECT_FLOAT_EQ(r.weight(50.0F / 255.0F), 0.0F);
    EXPECT_NEAR(r.weight(75.0F / 255.0F), 0.5F, 1e-5F);
    EXPECT_FLOAT_EQ(r.weight(150.0F / 255.0F), 1.0F);
    EXPECT_NEAR(r.weight(225.0F / 255.0F), 0.5F, 1e-5F);
    EXPECT_FLOAT_EQ(r.weight(1.0F), 0.0F);
    EXPECT_FLOAT_EQ(BlendIfRange{}.weight(0.0F), 1.0F);
    EXPECT_FLOAT_EQ(BlendIfRange{}.weight(1.0F), 1.0F);
    EXPECT_FALSE(BlendIf{}.active());
}

TEST(BlendIf, ThisLayerWhiteSliderHidesBrightPixels) {
    const auto base = solid({0, 0, 255, 255});
    const auto white = solid({255, 255, 255, 255});
    auto top = layer(2, &white);
    top.blend_if.self = {0, 0, 200, 220};  // pixels brighter than 220 vanish
    RenderGraph graph;
    ASSERT_TRUE(graph.setLayers({layer(1, &base), top}, BlendSpace::GammaEncoded));
    EXPECT_EQ(centre(graph), (std::array<std::uint8_t, 4>{0, 0, 255, 255}));
}

TEST(BlendIf, UnderlyingBlackSliderShowsLayerOnlyOverDarkBackdrop) {
    const auto dark = solid({10, 10, 10, 255});
    const auto light = solid({240, 240, 240, 255});
    const auto red = solid({255, 0, 0, 255});
    for (const auto& [backdrop, expected] : {std::pair{&dark, std::array<std::uint8_t, 4>{255, 0, 0, 255}},
                                             std::pair{&light, std::array<std::uint8_t, 4>{240, 240, 240, 255}}}) {
        auto top = layer(2, &red);
        top.blend_if.under = {0, 0, 100, 120};  // only where the backdrop is darker than ~100
        RenderGraph graph;
        ASSERT_TRUE(graph.setLayers({layer(1, backdrop), top}, BlendSpace::GammaEncoded));
        EXPECT_EQ(centre(graph), expected);
    }
}

TEST(BlendIf, ChangingTheSlidersInvalidatesTheCache) {
    const auto base = solid({0, 0, 0, 255});
    const auto white = solid({255, 255, 255, 255});
    auto top = layer(2, &white);
    RenderGraph graph;
    ASSERT_TRUE(graph.setLayers({layer(1, &base), top}, BlendSpace::GammaEncoded));
    EXPECT_EQ(centre(graph)[0], 255);
    top.blend_if.self = {0, 0, 100, 100};
    ASSERT_TRUE(graph.setLayers({layer(1, &base), top}, BlendSpace::GammaEncoded));
    EXPECT_EQ(centre(graph)[0], 0);
}

TEST(BlendIf, ARedSliderHidesOnlyPixelsWithRed) {
    const auto base = solid({0, 0, 255, 255});
    const auto red = solid({255, 0, 0, 255});
    const auto green = solid({0, 255, 0, 255});
    for (const auto& [top_pixels, expected] : {std::pair{&red, std::array<std::uint8_t, 4>{0, 0, 255, 255}},
                                               std::pair{&green, std::array<std::uint8_t, 4>{0, 255, 0, 255}}}) {
        auto top = layer(2, top_pixels);
        top.blend_if.channel_self[0] = {0, 0, 100, 120};  // red above ~120 vanishes; green content has no red
        RenderGraph graph;
        ASSERT_TRUE(graph.setLayers({layer(1, &base), top}, BlendSpace::GammaEncoded));
        EXPECT_EQ(centre(graph), expected);
    }
    BlendIf channelOnly;
    channelOnly.channel_under[2] = {0, 0, 10, 20};
    EXPECT_TRUE(channelOnly.active());
}

TEST(BlendIf, ChannelAndGraySlidersMultiply) {
    const float src[3] = {0.5F, 0.5F, 0.5F};
    const float under[3] = {0.5F, 0.5F, 0.5F};
    BlendIf b;
    b.channel_self[1] = {0, 0, 100, 200};  // green 128 sits halfway down the ramp
    const float half = b.weight(src, under);
    EXPECT_NEAR(half, 128.0F > 100.0F ? (200.0F - 127.5F) / 100.0F : 1.0F, 0.02F);
    b.self = {0, 0, 100, 200};  // the gray ramp halves it again
    EXPECT_NEAR(b.weight(src, under), half * half, 0.02F);
}

TEST(BlendIf, ChangingAChannelSliderInvalidatesTheCache) {
    const auto base = solid({0, 0, 0, 255});
    const auto white = solid({255, 255, 255, 255});
    auto top = layer(2, &white);
    RenderGraph graph;
    ASSERT_TRUE(graph.setLayers({layer(1, &base), top}, BlendSpace::GammaEncoded));
    EXPECT_EQ(centre(graph)[0], 255);
    top.blend_if.channel_self[2] = {0, 0, 100, 100};
    ASSERT_TRUE(graph.setLayers({layer(1, &base), top}, BlendSpace::GammaEncoded));
    EXPECT_EQ(centre(graph)[0], 0);
}

}  // namespace