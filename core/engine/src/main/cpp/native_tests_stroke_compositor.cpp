// Focused acceptance tests for stroke opacity and erase composition.
#include "stroke_compositor.h"

#include <gtest/gtest.h>

#include <array>
#include <cmath>
#include <cstring>
#include <limits>
#include <string>

#include "brush_engine.h"
#include "pixel_plane.h"

namespace {

using croptop::PixelFormat;
using croptop::TileBuffer;
using croptop::TileStore;
namespace brush = croptop::brush;
using croptop::brush::StrokeMaskCompositor;

std::array<float, 4> pixelAt(const TileStore& store, std::uint32_t x, std::uint32_t y) {
    const auto tile = store.readTile({static_cast<std::int32_t>(x / croptop::kTileEdge), static_cast<std::int32_t>(y / croptop::kTileEdge)});
    if (!tile) return {};
    std::array<float, 4> pixel{};
    croptop::paint::detail::readPixel(
        store.format(),
        tile->data() + (static_cast<std::size_t>(y % croptop::kTileEdge) * croptop::kTileEdge + x % croptop::kTileEdge) *
            store.bytes_per_pixel(),
        pixel.data());
    return pixel;
}

void writePixel(TileStore& store, std::uint32_t x, std::uint32_t y, const std::array<float, 4>& pixel) {
    const auto existing = store.readTile({static_cast<std::int32_t>(x / croptop::kTileEdge), static_cast<std::int32_t>(y / croptop::kTileEdge)});
    TileBuffer tile = existing ? *existing : TileBuffer(store.tile_byte_size(), 0);
    croptop::paint::detail::writePixel(
        store.format(),
        tile.data() + (static_cast<std::size_t>(y % croptop::kTileEdge) * croptop::kTileEdge + x % croptop::kTileEdge) *
            store.bytes_per_pixel(),
        pixel.data());
    ASSERT_TRUE(store.writeTile({static_cast<std::int32_t>(x / croptop::kTileEdge), static_cast<std::int32_t>(y / croptop::kTileEdge)}, tile));
}

void writeMaskCoverage(TileStore& mask, std::uint32_t x, std::uint32_t y, std::uint16_t coverage) {
    const auto coordinate = croptop::TileCoordinate{static_cast<std::int32_t>(x / croptop::kTileEdge),
                                                     static_cast<std::int32_t>(y / croptop::kTileEdge)};
    const auto existing = mask.readTile(coordinate);
    TileBuffer tile = existing ? *existing : TileBuffer(mask.tile_byte_size(), 0);
    const auto index = (static_cast<std::size_t>(y % croptop::kTileEdge) * croptop::kTileEdge + x % croptop::kTileEdge) *
                       sizeof(coverage);
    std::memcpy(tile.data() + index, &coverage, sizeof(coverage));
    ASSERT_TRUE(mask.writeTile(coordinate, tile));
}

TEST(StrokeMaskCompositor, OverlappingDabsRespectTheSingleStrokeOpacityCeiling) {
    StrokeMaskCompositor compositor(4, 4);
    std::string error;
    for (int dab = 0; dab < 20; ++dab) ASSERT_TRUE(compositor.accumulate(1, 1, 1.0F, error)) << error;
    TileStore baseline(PixelFormat::RGBA8, 4, 4);
    TileStore target = baseline;
    const float red[4]{1, 0, 0, 1};
    ASSERT_TRUE(compositor.compose(baseline, target, red, 0.25F, false, false, error)) << error;
    const auto result = pixelAt(target, 1, 1);
    EXPECT_NEAR(result[3], 0.25F, 1.0F / 255.0F);
    EXPECT_NEAR(result[0], 1.0F, 1.0F / 255.0F);
}

TEST(StrokeMaskCompositor, DabFlowAndOpacityDynamicsAreAccumulatedBelowStrokeOpacity) {
    StrokeMaskCompositor compositor(4, 4);
    croptop::brush::Dab dab;
    dab.x = 1.5F;
    dab.y = 1.5F;
    dab.diameter = 2;
    dab.hardness = 1;
    dab.opacity = 0.5F;  // per-dab opacity dynamics
    dab.flow = 0.5F;     // brush flow × flow dynamics
    dab.stroke_opacity = 0.25F;
    std::string error;
    ASSERT_TRUE(compositor.accumulateDab(dab, error)) << error;
    ASSERT_TRUE(compositor.accumulateDab(dab, error)) << error;
    TileStore baseline(PixelFormat::RGBA8, 4, 4);
    TileStore target = baseline;
    const float red[4]{1, 0, 0, 1};
    ASSERT_TRUE(compositor.compose(baseline, target, red, dab.stroke_opacity, false, false, error)) << error;
    // Each dab contributes .5 dynamics × .5 flow; over two dabs is .4375,
    // then the stroke opacity ceiling is applied once: .4375 × .25.
    EXPECT_NEAR(pixelAt(target, 1, 1)[3], 0.109375F, 2.0F / 255.0F);
}

TEST(StrokeMaskCompositor, HardTipUsesDeterministicOnePixelAnalyticEdge) {
    StrokeMaskCompositor compositor(6, 6);
    croptop::brush::Dab dab;
    dab.x = 2.5F;
    dab.y = 2.5F;
    dab.diameter = 2.0F;
    dab.hardness = 1.0F;
    dab.opacity = 1.0F;
    dab.flow = 1.0F;
    dab.stroke_opacity = 1.0F;
    std::string error;

    ASSERT_TRUE(compositor.accumulateDab(dab, error)) << error;
    TileStore baseline(PixelFormat::RGBA32F, 6, 6);
    TileStore target = baseline;
    const float red[4]{1, 0, 0, 1};
    ASSERT_TRUE(compositor.compose(baseline, target, red, 1.0F, false, false, error)) << error;
    EXPECT_FLOAT_EQ(pixelAt(target, 2, 2)[3], 1.0F);
    EXPECT_NEAR(pixelAt(target, 3, 2)[3], 0.5F, 1.0F / 65535.0F);
    EXPECT_FLOAT_EQ(pixelAt(target, 4, 2)[3], 0.0F);
}

TEST(StrokeMaskCompositor, A16MaskRetainsRepeatedLowFlowOnRgba8) {
    StrokeMaskCompositor compositor(4, 4);
    std::string error;
    for (int dab = 0; dab < 100; ++dab) ASSERT_TRUE(compositor.accumulate(2, 2, 0.01F, error)) << error;
    TileStore baseline(PixelFormat::RGBA8, 4, 4);
    TileStore target = baseline;
    const float blue[4]{0, 0, 1, 1};
    ASSERT_TRUE(compositor.compose(baseline, target, blue, 1.0F, false, false, error)) << error;
    EXPECT_NEAR(pixelAt(target, 2, 2)[3], 1.0F - std::pow(0.99F, 100.0F), 2.0F / 255.0F);
    EXPECT_GT(pixelAt(target, 2, 2)[3], 0.6F);

    TileStore capped_target = baseline;
    ASSERT_TRUE(compositor.compose(baseline, capped_target, blue, 0.25F, false, false, error)) << error;
    EXPECT_NEAR(pixelAt(capped_target, 2, 2)[3], 0.25F * (1.0F - std::pow(0.99F, 100.0F)), 2.0F / 255.0F);
}

TEST(StrokeMaskCompositor, EraseAndPreserveAlphaWorkAcrossRgbaFormats) {
    for (const auto format : {PixelFormat::RGBA8, PixelFormat::RGBA16, PixelFormat::RGBA32F}) {
        SCOPED_TRACE(static_cast<int>(format));
        TileStore baseline(format, 4, 4);
        writePixel(baseline, 1, 1, {0.25F, 0.5F, 0.75F, 0.8F});
        TileStore target = baseline;
        StrokeMaskCompositor erase(4, 4);
        std::string error;
        ASSERT_TRUE(erase.accumulate(1, 1, 0.5F, error)) << error;
        const float transparent[4]{0, 0, 0, 0};
        ASSERT_TRUE(erase.compose(baseline, target, transparent, 0.5F, true, false, error)) << error;
        const auto partially_erased = pixelAt(target, 1, 1);
        EXPECT_NEAR(partially_erased[3], 0.6F, format == PixelFormat::RGBA8 ? 1.0F / 255.0F : 2e-5F);
        EXPECT_NEAR(partially_erased[0], 0.25F, format == PixelFormat::RGBA8 ? 1.0F / 255.0F : 2e-5F);

        StrokeMaskCompositor preserve_alpha(4, 4);
        ASSERT_TRUE(preserve_alpha.accumulate(1, 1, 1.0F, error)) << error;
        TileStore preserved = baseline;
        const float green[4]{0, 1, 0, 1};
        ASSERT_TRUE(preserve_alpha.compose(baseline, preserved, green, 1.0F, false, true, error)) << error;
        const auto mixed = pixelAt(preserved, 1, 1);
        EXPECT_NEAR(mixed[3], 0.8F, format == PixelFormat::RGBA8 ? 1.0F / 255.0F : 2e-5F);
        EXPECT_NEAR(mixed[1], 1.0F, format == PixelFormat::RGBA8 ? 1.0F / 255.0F : 2e-5F);

        StrokeMaskCompositor full_erase(4, 4);
        ASSERT_TRUE(full_erase.accumulate(1, 1, 1.0F, error)) << error;
        TileStore cleared = baseline;
        ASSERT_TRUE(full_erase.compose(baseline, cleared, transparent, 1.0F, true, false, error)) << error;
        EXPECT_EQ(pixelAt(cleared, 1, 1), (std::array<float, 4>{}));
        EXPECT_EQ(cleared.tile_count(), 0U);
    }
}

TEST(StrokeMaskCompositor, FloatPaintDecodesSrgbAndRejectsInvalidInputs) {
    TileStore baseline(PixelFormat::RGBA32F, 4, 4);
    TileStore target = baseline;
    StrokeMaskCompositor compositor(4, 4);
    std::string error;
    ASSERT_TRUE(compositor.accumulate(0, 0, 1.0F, error)) << error;
    const float gray[4]{0.5F, 0.5F, 0.5F, 1.0F};
    ASSERT_TRUE(compositor.compose(baseline, target, gray, 1.0F, false, false, error)) << error;
    EXPECT_NEAR(pixelAt(target, 0, 0)[0], 0.214041F, 1e-5F);
    EXPECT_FALSE(compositor.accumulate(5, 0, 1.0F, error));
    EXPECT_FALSE(compositor.accumulate(0, 0, std::numeric_limits<float>::quiet_NaN(), error));
    EXPECT_FALSE(compositor.compose(baseline, target, gray, 1.01F, false, false, error));
    EXPECT_FALSE(compositor.compose(baseline, target, gray, 1.0F, true, true, error));
}

TEST(StrokeMaskCompositor, BoundsSparseMaskMemoryAndRejectsMismatchedTargets) {
    StrokeMaskCompositor bounded(513U * croptop::kTileEdge, 1);
    std::string error;
    for (std::int32_t tile = 0; tile < 512; ++tile) {
        ASSERT_TRUE(bounded.accumulate(tile * static_cast<std::int32_t>(croptop::kTileEdge), 0, 0.5F, error)) << error;
    }
    EXPECT_FALSE(bounded.accumulate(512 * static_cast<std::int32_t>(croptop::kTileEdge), 0, 0.5F, error));
    EXPECT_NE(error.find("64 MiB"), std::string::npos);

    TileStore baseline(PixelFormat::RGBA8, 4, 4);
    TileStore wrong_format(PixelFormat::RGBA16, 4, 4);
    const float color[4]{1, 1, 1, 1};
    EXPECT_FALSE(bounded.compose(baseline, wrong_format, color, 1.0F, false, false, error));
}

TEST(StrokeMaskCompositor, SelectionCoverageWeightsPaintAndLeavesUnselectedPixelsByteIdentical) {
    TileStore baseline(PixelFormat::RGBA16, 8, 4);
    writePixel(baseline, 1, 1, {0, 0, 1, 1});
    writePixel(baseline, 3, 1, {0, 1, 0, 1});
    TileStore target = baseline;
    TileStore selection(PixelFormat::A16, 8, 4);
    writeMaskCoverage(selection, 1, 1, 32768);

    StrokeMaskCompositor compositor(8, 4);
    std::string error;
    ASSERT_TRUE(compositor.accumulate(1, 1, 1.0F, error)) << error;
    ASSERT_TRUE(compositor.accumulate(3, 1, 1.0F, error)) << error;
    const auto before_tile = baseline.readTile({0, 0});
    ASSERT_NE(before_tile, nullptr);
    const float red[4]{1, 0, 0, 1};
    ASSERT_TRUE(compositor.compose(baseline, target, red, 1.0F, false, false, error, &selection)) << error;

    const auto selected = pixelAt(target, 1, 1);
    EXPECT_NEAR(selected[0], 0.5F, 2.0F / 65535.0F);
    EXPECT_NEAR(selected[2], 0.5F, 2.0F / 65535.0F);
    const auto after_tile = target.readTile({0, 0});
    ASSERT_NE(after_tile, nullptr);
    const auto outside_offset = (static_cast<std::size_t>(1) * croptop::kTileEdge + 3U) * target.bytes_per_pixel();
    EXPECT_EQ(std::memcmp(before_tile->data() + outside_offset, after_tile->data() + outside_offset, target.bytes_per_pixel()), 0);
}

TEST(StrokeMaskCompositor, WetEdgesScaleDarkeningByPartialSelectionCoverage) {
    TileStore target(PixelFormat::RGBA32F, 8, 4);
    writePixel(target, 3, 1, {1, 1, 1, 0.75F});
    TileStore selection(PixelFormat::A16, 8, 4);
    writeMaskCoverage(selection, 3, 1, 32768);

    StrokeMaskCompositor compositor(8, 4);
    std::string error;
    ASSERT_TRUE(compositor.accumulate(3, 1, 1.0F, error)) << error;
    ASSERT_TRUE(compositor.applyWetEdges(target, 1.0F, &selection, error)) << error;

    const auto pixel = pixelAt(target, 3, 1);
    EXPECT_NEAR(pixel[0], 1.0F - 0.55F * (32768.0F / 65535.0F), 1e-6F);
    EXPECT_NEAR(pixel[1], pixel[0], 1e-6F);
    EXPECT_NEAR(pixel[2], pixel[0], 1e-6F);
    EXPECT_FLOAT_EQ(pixel[3], 0.75F);
}

TEST(StrokeMaskCompositor, MissingSelectionTileClipsAndInvalidMasksAreRejected) {
    TileStore baseline(PixelFormat::RGBA8, 300, 4);
    writePixel(baseline, 260, 1, {0.25F, 0.5F, 0.75F, 1});
    TileStore target = baseline;
    TileStore selection(PixelFormat::A16, 300, 4);
    writeMaskCoverage(selection, 2, 1, 65535);
    StrokeMaskCompositor compositor(300, 4);
    std::string error;
    ASSERT_TRUE(compositor.accumulate(2, 1, 1.0F, error)) << error;
    ASSERT_TRUE(compositor.accumulate(260, 1, 1.0F, error)) << error;
    const auto before = baseline.readTile({1, 0});
    ASSERT_NE(before, nullptr);
    const float red[4]{1, 0, 0, 1};
    ASSERT_TRUE(compositor.compose(baseline, target, red, 1.0F, false, false, error, &selection)) << error;
    const auto after = target.readTile({1, 0});
    ASSERT_NE(after, nullptr);
    EXPECT_EQ(*before, *after);

    TileStore wrong_format(PixelFormat::A8, 300, 4);
    EXPECT_FALSE(compositor.compose(baseline, target, red, 1.0F, false, false, error, &wrong_format));
    TileStore wrong_size(PixelFormat::A16, 299, 4);
    EXPECT_FALSE(compositor.compose(baseline, target, red, 1.0F, false, false, error, &wrong_size));
}

TEST(StrokeMaskCompositor, FinalSelectionClippingCapsBuildUpPaintAndEraseAgainstBaseline) {
    TileStore selection(PixelFormat::A16, 4, 4);
    writeMaskCoverage(selection, 1, 1, 32768);
    TileStore baseline(PixelFormat::RGBA32F, 4, 4);
    writePixel(baseline, 1, 1, {0, 0, 1, 1});
    writePixel(baseline, 2, 1, {0, 1, 0, 1});
    const auto before_tile = baseline.readTile({0, 0});
    ASSERT_NE(before_tile, nullptr);

    brush::Dab dab;
    dab.x = 1.5F;
    dab.y = 1.5F;
    dab.diameter = 4;
    dab.hardness = 1;
    std::string error;
    TileStore painted = baseline;
    ASSERT_TRUE(brush::stamp(painted, dab, 1, 0, 0, 1, false, false, error)) << error;
    ASSERT_TRUE(brush::stamp(painted, dab, 1, 0, 0, 1, false, false, error)) << error;
    EXPECT_FLOAT_EQ(pixelAt(painted, 1, 1)[0], 1.0F);
    EXPECT_FLOAT_EQ(pixelAt(painted, 1, 1)[2], 0.0F);
    const std::array affected_tiles{croptop::TileCoordinate{0, 0}};
    ASSERT_TRUE(StrokeMaskCompositor::applySelectionMask(baseline, painted, painted, selection, affected_tiles, error)) << error;
    const auto clipped_pixel = pixelAt(painted, 1, 1);
    EXPECT_NEAR(clipped_pixel[0], 0.5F, 1e-5F);
    EXPECT_NEAR(clipped_pixel[2], 0.5F, 1e-5F);
    auto after_tile = painted.readTile({0, 0});
    ASSERT_NE(after_tile, nullptr);
    const auto outside_offset = (static_cast<std::size_t>(1) * croptop::kTileEdge + 2U) * painted.bytes_per_pixel();
    EXPECT_EQ(std::memcmp(before_tile->data() + outside_offset, after_tile->data() + outside_offset, painted.bytes_per_pixel()), 0);

    TileStore erased_painted = baseline;
    ASSERT_TRUE(brush::stamp(erased_painted, dab, 0, 0, 0, 0, true, false, error)) << error;
    ASSERT_TRUE(brush::stamp(erased_painted, dab, 0, 0, 0, 0, true, false, error)) << error;
    ASSERT_TRUE(StrokeMaskCompositor::applySelectionMask(baseline, erased_painted, erased_painted, selection, affected_tiles, error))
        << error;
    const auto erased = pixelAt(erased_painted, 1, 1);
    EXPECT_NEAR(erased[3], 0.5F, 1e-5F);
    after_tile = erased_painted.readTile({0, 0});
    ASSERT_NE(after_tile, nullptr);
    EXPECT_EQ(std::memcmp(before_tile->data() + outside_offset, after_tile->data() + outside_offset, erased_painted.bytes_per_pixel()), 0);

    TileStore wrong_format(PixelFormat::A8, 4, 4);
    EXPECT_FALSE(StrokeMaskCompositor::applySelectionMask(baseline, painted, painted, wrong_format, affected_tiles, error));
}

}  // namespace
