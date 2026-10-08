// Bounded row-major RGBA8 shape-layer render adapter tests.
#include "shape_render_adapter.h"

#include <gtest/gtest.h>

#include <array>
#include <cstdint>
#include <vector>

#include "jni_shape_layer.h"
#include "shape_layer.h"

namespace {
using namespace croptop::shape;
using namespace croptop::shape::bridge;
using croptop::vector::Point;

Record rectangleRecord(std::int64_t id, double x, double y, double width, double height, Color color) {
    Record record;
    record.name = "render";
    record.shape.id = id;
    record.shape.fill = {true, color};
    record.shape.stroke.enabled = false;
    const auto anchor = [](std::int64_t anchor_id, Point point) {
        return Anchor{anchor_id, point, std::nullopt, std::nullopt, AnchorMode::Corner};
    };
    record.shape.subpaths = {Subpath{{anchor(1, {x, y}), anchor(2, {x + width, y}), anchor(3, {x + width, y + height}),
                                               anchor(4, {x, y + height})},
                                              true}};
    return record;
}

std::vector<std::uint8_t> batchOf(std::vector<Record> records) {
    std::vector<std::uint8_t> encoded;
    std::string error;
    EXPECT_TRUE(encodeBatch(records, encoded, error)) << error;
    return encoded;
}

std::array<std::uint8_t, 4> pixel(const croptop::TileStore& store, std::uint32_t x, std::uint32_t y) {
    const auto tile = store.readTile({static_cast<std::int32_t>(x / croptop::kTileEdge), static_cast<std::int32_t>(y / croptop::kTileEdge)});
    if (!tile) return {0, 0, 0, 0};
    const auto offset = ((y % croptop::kTileEdge) * croptop::kTileEdge + (x % croptop::kTileEdge)) * 4U;
    return {(*tile)[offset], (*tile)[offset + 1U], (*tile)[offset + 2U], (*tile)[offset + 3U]};
}

TEST(ShapeRenderAdapter, ProducesRowMajorVisiblePixelsAcrossTileBoundary) {
    const auto encoded = batchOf({rectangleRecord(1, 254, 2, 6, 6, {1, 0, 0, 1})});
    std::vector<Record> decoded;
    std::string decode_error;
    ASSERT_TRUE(decodeBatch(encoded, decoded, decode_error)) << decode_error;
    ASSERT_EQ(decoded.size(), 1U);
    ASSERT_EQ(decoded[0].shape.subpaths.size(), 1U);
    ASSERT_TRUE(decoded[0].shape.subpaths[0].closed);
    ASSERT_EQ(decoded[0].shape.subpaths[0].anchors.size(), 4U);
    EXPECT_TRUE(decoded[0].shape.fill.enabled);
    EXPECT_EQ(decoded[0].shape.fill.color, (Color{1, 0, 0, 1}));
    EXPECT_FALSE(toPath(decoded[0].shape).empty());
    ASSERT_EQ(toPath(decoded[0].shape).points().size(), 4U);
    EXPECT_DOUBLE_EQ(toPath(decoded[0].shape).points()[0].x, 254.0);
    EXPECT_DOUBLE_EQ(toPath(decoded[0].shape).points()[0].y, 2.0);
    croptop::TileStore rgba(croptop::PixelFormat::RGBA8, 260, 10);
    std::string error;
    ASSERT_TRUE(renderBatchToTileStore(encoded, rgba, error)) << error;
    EXPECT_EQ(pixel(rgba, 254, 3), (std::array<std::uint8_t, 4>{255, 0, 0, 255}));
    EXPECT_EQ(pixel(rgba, 255, 5), (std::array<std::uint8_t, 4>{255, 0, 0, 255}));
    EXPECT_EQ(pixel(rgba, 256, 3), (std::array<std::uint8_t, 4>{255, 0, 0, 255}));
    EXPECT_EQ(pixel(rgba, 259, 5), (std::array<std::uint8_t, 4>{255, 0, 0, 255}));
    EXPECT_EQ(pixel(rgba, 253, 3), (std::array<std::uint8_t, 4>{0, 0, 0, 0}));
    EXPECT_EQ(pixel(rgba, 0, 8), (std::array<std::uint8_t, 4>{0, 0, 0, 0}));
}

TEST(ShapeRenderAdapter, EmptyBatchReturnsTransparentCanvas) {
    const auto encoded = batchOf({});
    croptop::TileStore rgba(croptop::PixelFormat::RGBA8, 3, 2);
    std::string error;
    ASSERT_TRUE(renderBatchToTileStore(encoded, rgba, error)) << error;
    EXPECT_EQ(rgba.tile_count(), 0U);
}

TEST(ShapeRenderAdapter, RejectsNonRgbaStores) {
    croptop::TileStore rgba(croptop::PixelFormat::A8, 3, 2);
    std::string error;
    EXPECT_FALSE(renderBatchToTileStore(batchOf({}), rgba, error));
    EXPECT_FALSE(error.empty());
    EXPECT_EQ(rgba.tile_count(), 0U);
}

TEST(ShapeRenderAdapter, RejectsMalformedBatchAndKeepsCallerOutput) {
    croptop::TileStore rgba(croptop::PixelFormat::RGBA8, 2, 2);
    std::string error;
    EXPECT_FALSE(renderBatchToTileStore(std::array<std::uint8_t, 4>{'b', 'a', 'd', '!'}, rgba, error));
    EXPECT_FALSE(error.empty());
    EXPECT_EQ(rgba.tile_count(), 0U);
}
}  // namespace
