// Layer masks and clipping groups in the render graph.
#include "layer_mask.h"
#include "layer_style_attachment.h"
#include "native_store_handle.h"
#include "render_graph.h"
#include "layer_styles.h"

#include <gtest/gtest.h>

#include <array>
#include <cstdint>
#include <cstring>
#include <memory>
#include <string>
#include <vector>

namespace {

using croptop::BlendMode;
using croptop::BlendSpace;
using croptop::PixelFormat;
using croptop::RenderGraph;
using croptop::RenderLayer;
using croptop::TileBuffer;
using croptop::TileStore;
namespace mask = croptop::layermask;

constexpr std::uint32_t kEdge = croptop::kTileEdge;

/** A store whose every pixel of the covered tiles is `rgba`; `width`×`height` need not be tile multiples. */
TileStore solid(std::uint32_t width, std::uint32_t height, std::array<std::uint8_t, 4> rgba) {
    TileStore store(PixelFormat::RGBA8, width, height);
    for (std::uint32_t ty = 0; ty * kEdge < height; ++ty) {
        for (std::uint32_t tx = 0; tx * kEdge < width; ++tx) {
            TileBuffer tile(store.tile_byte_size());
            for (std::size_t p = 0; p < tile.size(); p += 4) std::memcpy(tile.data() + p, rgba.data(), 4);
            EXPECT_TRUE(store.writeTile({static_cast<std::int32_t>(tx), static_cast<std::int32_t>(ty)}, tile));
        }
    }
    return store;
}

/** An A16 mask where `value(x, y)` gives the coverage of each pixel. */
template <typename Value>
TileStore maskOf(std::uint32_t width, std::uint32_t height, Value value) {
    TileStore store(PixelFormat::A16, width, height);
    for (std::uint32_t ty = 0; ty * kEdge < height; ++ty) {
        for (std::uint32_t tx = 0; tx * kEdge < width; ++tx) {
            TileBuffer tile(store.tile_byte_size(), 0);
            for (std::uint32_t y = 0; y < kEdge && ty * kEdge + y < height; ++y) {
                for (std::uint32_t x = 0; x < kEdge && tx * kEdge + x < width; ++x) {
                    const std::uint16_t v = value(tx * kEdge + x, ty * kEdge + y);
                    std::memcpy(tile.data() + (static_cast<std::size_t>(y) * kEdge + x) * 2, &v, 2);
                }
            }
            EXPECT_TRUE(store.writeTile({static_cast<std::int32_t>(tx), static_cast<std::int32_t>(ty)}, tile));
        }
    }
    return store;
}

std::shared_ptr<mask::MaskCache> cacheOf(const TileStore& store, mask::MaskProperties properties = {}, float outside = 0.0F) {
    std::string error;
    std::shared_ptr<mask::MaskCache> cache = mask::MaskCache::create(store, properties, outside, error);
    EXPECT_NE(cache, nullptr) << error;
    return cache;
}

std::array<std::uint8_t, 4> pixelAt(const std::shared_ptr<const TileBuffer>& tile, std::uint32_t x, std::uint32_t y) {
    std::array<std::uint8_t, 4> out{};
    std::memcpy(out.data(), tile->data() + (static_cast<std::size_t>(y) * kEdge + x) * 4, 4);
    return out;
}

RenderLayer layer(std::uint64_t id, const TileStore* pixels) {
    RenderLayer l;
    l.id = id;
    l.pixels = pixels;
    return l;
}

TEST(RenderGraphMasks, MaskCoverageScalesTheLayersAlpha) {
    const auto white = solid(256, 256, {255, 255, 255, 255});
    const auto red = solid(256, 256, {255, 0, 0, 255});
    const auto half_right = maskOf(256, 256, [](std::uint32_t x, std::uint32_t) -> std::uint16_t { return x < 128 ? 65535 : 32768; });
    auto top = layer(2, &red);
    top.mask = cacheOf(half_right);
    RenderGraph graph;
    ASSERT_TRUE(graph.setLayers({layer(1, &white), top}, BlendSpace::GammaEncoded));
    const auto tile = graph.renderTile({0, 0});
    ASSERT_NE(tile, nullptr);
    EXPECT_EQ(pixelAt(tile, 10, 10), (std::array<std::uint8_t, 4>{255, 0, 0, 255}));
    const auto blended = pixelAt(tile, 200, 10);
    EXPECT_EQ(blended[0], 255);
    EXPECT_NEAR(blended[1], 127, 1);  // half red over white
    EXPECT_NEAR(blended[2], 127, 1);
    EXPECT_EQ(blended[3], 255);
}

TEST(RenderGraphMasks, DensityInvertAndDisableChangeTheCoverage) {
    const auto white = solid(256, 256, {255, 255, 255, 255});
    const auto blue = solid(256, 256, {0, 0, 255, 255});
    const auto left_black = maskOf(256, 256, [](std::uint32_t x, std::uint32_t) -> std::uint16_t { return x < 128 ? 0 : 65535; });
    auto masked = layer(2, &blue);
    masked.mask = cacheOf(left_black);
    RenderGraph graph;
    ASSERT_TRUE(graph.setLayers({layer(1, &white), masked}, BlendSpace::GammaEncoded));
    auto tile = graph.renderTile({0, 0});
    EXPECT_EQ(pixelAt(tile, 10, 10)[0], 255);  // masked out: white shows through
    EXPECT_EQ(pixelAt(tile, 200, 10)[0], 0);   // covered: blue

    std::string error;
    mask::MaskProperties properties;
    properties.inverted = true;
    ASSERT_TRUE(masked.mask->setProperties(properties, error)) << error;
    graph.clearCache();
    tile = graph.renderTile({0, 0});
    EXPECT_EQ(pixelAt(tile, 10, 10)[0], 0);
    EXPECT_EQ(pixelAt(tile, 200, 10)[0], 255);

    properties.inverted = false;
    properties.density = 0.0F;  // density 0 = the mask has no effect
    ASSERT_TRUE(masked.mask->setProperties(properties, error)) << error;
    graph.clearCache();
    tile = graph.renderTile({0, 0});
    EXPECT_EQ(pixelAt(tile, 10, 10)[0], 0);
    EXPECT_EQ(pixelAt(tile, 200, 10)[0], 0);

    properties.density = 1.0F;
    properties.enabled = false;
    ASSERT_TRUE(masked.mask->setProperties(properties, error)) << error;
    graph.clearCache();
    tile = graph.renderTile({0, 0});
    EXPECT_EQ(pixelAt(tile, 10, 10)[0], 0);
}

TEST(RenderGraphMasks, ClippedLayerOnlyShowsWhereTheBaseIsOpaque) {
    const auto white = solid(256, 256, {255, 255, 255, 255});
    const auto base = solid(256, 256, {0, 255, 0, 255});
    const auto child = solid(256, 256, {255, 0, 0, 255});
    const auto left_only = maskOf(256, 256, [](std::uint32_t x, std::uint32_t) -> std::uint16_t { return x < 128 ? 65535 : 0; });
    auto base_layer = layer(2, &base);
    base_layer.mask = cacheOf(left_only);
    auto clipped = layer(3, &child);
    clipped.clipped = true;
    RenderGraph graph;
    ASSERT_TRUE(graph.setLayers({layer(1, &white), base_layer, clipped}, BlendSpace::GammaEncoded));
    auto tile = graph.renderTile({0, 0});
    EXPECT_EQ(pixelAt(tile, 10, 10), (std::array<std::uint8_t, 4>{255, 0, 0, 255}));       // child over base
    EXPECT_EQ(pixelAt(tile, 200, 10), (std::array<std::uint8_t, 4>{255, 255, 255, 255}));  // base masked: child clipped away

    // A hidden base takes its clipping group with it.
    base_layer.visible = false;
    ASSERT_TRUE(graph.setLayers({layer(1, &white), base_layer, clipped}, BlendSpace::GammaEncoded));
    tile = graph.renderTile({0, 0});
    EXPECT_EQ(pixelAt(tile, 10, 10), (std::array<std::uint8_t, 4>{255, 255, 255, 255}));

    // An unclipped layer above ends the group: its own child clips to it, not to the layer below.
    const auto blue = solid(256, 256, {0, 0, 255, 255});
    auto next_base = layer(4, &blue);
    auto next_child = layer(5, &child);
    next_child.clipped = true;
    base_layer.visible = true;
    ASSERT_TRUE(graph.setLayers({layer(1, &white), base_layer, clipped, next_base, next_child}, BlendSpace::GammaEncoded));
    tile = graph.renderTile({0, 0});
    EXPECT_EQ(pixelAt(tile, 200, 10), (std::array<std::uint8_t, 4>{255, 0, 0, 255}));  // blue base is unmasked, child shows
}

TEST(RenderGraphMasks, StyledLayerEffectsDoNotReplaceTheirBaseForClipping) {
    const auto green = solid(kEdge, kEdge, {0, 255, 0, 255});
    const auto red = solid(kEdge, kEdge, {255, 0, 0, 255});
    croptop::jni::NativeTileStore styled(green);
    croptop::styles::LayerStyles settings;
    settings.inner_shadow.enabled = true;
    settings.inner_shadow.size = 20.0F;
    settings.inner_shadow.distance = 5.0F;
    styled.styles = std::make_shared<croptop::jni::StyleAttachment>(settings);

    std::vector<RenderLayer> layers;
    std::vector<std::shared_ptr<const croptop::jni::StyleAttachment::Effects>> keep;
    RenderLayer base{10, &styled.pixels};
    croptop::jni::appendStyledLayer(layers, std::move(base), styled, keep);
    RenderLayer clipped{11, &red};
    clipped.clipped = true;
    layers.push_back(clipped);

    RenderGraph graph;
    ASSERT_TRUE(graph.setLayers(std::move(layers), BlendSpace::GammaEncoded));
    const auto tile = graph.renderTile({0, 0});
    // The inner shadow is transparent in the flat centre. The clipped sibling must still use the
    // actual green layer's alpha as its clipping base, rather than the synthetic shadow's alpha.
    EXPECT_EQ(pixelAt(tile, kEdge / 2, kEdge / 2), (std::array<std::uint8_t, 4>{255, 0, 0, 255}));
}

TEST(RenderGraphMasks, ReducedLevelsAverageTheCoverage) {
    const auto white = solid(1024, 1024, {255, 255, 255, 255});
    const auto black = solid(1024, 1024, {0, 0, 0, 255});
    // A 1-px checkerboard averages to half coverage at any reduction; the left quarter is fully covered.
    const auto pattern = maskOf(1024, 1024, [](std::uint32_t x, std::uint32_t y) -> std::uint16_t {
        if (x < 256) return 65535;
        return ((x + y) & 1U) != 0 ? 65535 : 0;
    });
    auto top = layer(2, &black);
    top.mask = cacheOf(pattern);
    RenderGraph graph;
    ASSERT_TRUE(graph.setLayers({layer(1, &white), top}, BlendSpace::GammaEncoded));
    const auto reduced = graph.renderTile({0, 0}, 2);  // 4:1: the 1024 px canvas becomes one 256 px tile
    ASSERT_NE(reduced, nullptr);
    EXPECT_EQ(pixelAt(reduced, 10, 10)[0], 0);                 // fully covered black
    EXPECT_NEAR(pixelAt(reduced, 200, 100)[0], 127, 2);        // averaged half coverage over white
}

TEST(RenderGraphMasks, EditingTheMaskInvalidatesOnlyTheTilesItReaches) {
    const auto white = solid(1024, 512, {255, 255, 255, 255});
    const auto black = solid(1024, 512, {0, 0, 0, 255});
    auto full = maskOf(1024, 512, [](std::uint32_t, std::uint32_t) -> std::uint16_t { return 65535; });
    auto top = layer(2, &black);
    top.mask = cacheOf(full);
    RenderGraph graph;
    ASSERT_TRUE(graph.setLayers({layer(1, &white), top}, BlendSpace::GammaEncoded));
    for (std::int32_t x = 0; x < 4; ++x) {
        for (std::int32_t y = 0; y < 2; ++y) ASSERT_NE(graph.renderTile({x, y}), nullptr);
    }
    ASSERT_EQ(top.mask->computedTiles(), 8U);
    const auto unaffected = graph.renderTile({0, 0});
    ASSERT_NE(unaffected, nullptr);
    ASSERT_EQ(graph.cachedTileCount(), 16U);

    // Erase a patch inside tile (1, 0); RenderGraph must notice the in-place mask revision itself.
    auto edited = maskOf(1024, 512, [](std::uint32_t x, std::uint32_t y) -> std::uint16_t {
        return (x >= 300 && x < 340 && y >= 20 && y < 60) ? 0 : 65535;
    });
    std::string error;
    const croptop::PixelRect dirty{300, 20, 340, 60};
    ASSERT_TRUE(top.mask->update(edited, dirty, error)) << error;
    const auto redrawn = graph.renderTile({1, 0});
    EXPECT_EQ(pixelAt(redrawn, 300 - 256 + 5, 25)[0], 255);  // the erased patch shows white
    EXPECT_EQ(pixelAt(redrawn, 5, 5)[0], 0);
    EXPECT_EQ(graph.renderTile({0, 0}), unaffected);
    EXPECT_EQ(graph.cachedTileCount(), 16U);
    for (std::int32_t x = 0; x < 4; ++x) {
        for (std::int32_t y = 0; y < 2; ++y) ASSERT_NE(graph.renderTile({x, y}), nullptr);
    }
    EXPECT_EQ(top.mask->computedTiles(), 9U) << "only the edited mask tile should have been recomputed";
}

TEST(RenderGraphMasks, FeatheredMaskEditInvalidatesOnlyNeighboringCoverageTiles) {
    const auto white = solid(768, 256, {255, 255, 255, 255});
    const auto black = solid(768, 256, {0, 0, 0, 255});
    auto full = maskOf(768, 256, [](std::uint32_t, std::uint32_t) -> std::uint16_t { return 65535; });
    mask::MaskProperties properties;
    properties.feather = 8.0F;
    auto top = layer(2, &black);
    top.mask = cacheOf(full, properties);
    RenderGraph graph;
    ASSERT_TRUE(graph.setLayers({layer(1, &white), top}, BlendSpace::GammaEncoded));
    const auto before_left = graph.renderTile({0, 0});
    const auto before_right = graph.renderTile({1, 0});
    const auto before_far = graph.renderTile({2, 0});
    ASSERT_NE(before_left, nullptr);
    ASSERT_NE(before_right, nullptr);
    ASSERT_NE(before_far, nullptr);

    auto edited = maskOf(768, 256, [](std::uint32_t x, std::uint32_t y) -> std::uint16_t {
        return x >= 255 && x < 257 && y >= 100 && y < 156 ? 0 : 65535;
    });
    std::string error;
    ASSERT_TRUE(top.mask->update(edited, {255, 100, 257, 156}, error)) << error;
    const auto after_left = graph.renderTile({0, 0});
    const auto after_right = graph.renderTile({1, 0});
    const auto after_far = graph.renderTile({2, 0});
    EXPECT_NE(after_left, before_left);
    EXPECT_NE(after_right, before_right);
    EXPECT_EQ(after_far, before_far);
    EXPECT_EQ(graph.cachedTileCount(), 6U);
}

}  // namespace
