// Brush dab generation, incremental boundaries, and persistent pixel stamping.
#include "brush_engine.h"
#include "brush_queue_budget.h"

#include <gtest/gtest.h>

#include <array>
#include <cmath>
#include <cstring>
#include <limits>
#include <string>
#include <vector>

#include "pixel_plane.h"

namespace {

using croptop::PixelFormat;
using croptop::TileBuffer;
using croptop::TileStore;
namespace brush = croptop::brush;

TEST(DabQueueBudgetTest, KeepsInFlightWorkInsideTheCapacityLimit) {
    brush::DabQueueBudget budget(4);

    ASSERT_TRUE(budget.enqueue(3));
    ASSERT_TRUE(budget.begin(2));
    EXPECT_EQ(budget.queued(), 1U);
    EXPECT_EQ(budget.inFlight(), 2U);
    EXPECT_EQ(budget.reserved(), 3U);
    EXPECT_FALSE(budget.enqueue(2));

    ASSERT_TRUE(budget.complete(2));
    EXPECT_TRUE(budget.enqueue(3));
    EXPECT_EQ(budget.reserved(), 4U);
}

TEST(DabQueueBudgetTest, DiscardingPendingWorkRetainsInFlightReservation) {
    brush::DabQueueBudget budget(4);

    ASSERT_TRUE(budget.enqueue(4));
    ASSERT_TRUE(budget.begin(2));
    budget.discardQueued();
    EXPECT_EQ(budget.queued(), 0U);
    EXPECT_EQ(budget.inFlight(), 2U);
    EXPECT_FALSE(budget.enqueue(3));
    EXPECT_TRUE(budget.complete(2));
    EXPECT_TRUE(budget.enqueue(4));
}

TEST(DabQueueBudgetTest, RejectsInvalidTransitions) {
    brush::DabQueueBudget budget(4);

    EXPECT_FALSE(budget.begin(1));
    EXPECT_FALSE(budget.complete(1));
    EXPECT_TRUE(budget.enqueue(4));
    EXPECT_FALSE(budget.enqueue(1));
}

TEST(DabQueueBudgetTest, GeneratorStopsAtAvailableCapacityDuringLargeSegment) {
    brush::Settings settings;
    settings.size = 2;
    settings.spacing = 0.25F;
    settings.size_dynamics.input = brush::DynamicsInput::Off;
    brush::DabGenerator generator(settings);
    const std::array samples{brush::Sample{0, 8, 1, 0, 0, 0, 0}, brush::Sample{100'000, 8, 1, 0, 0, 0, 1}};
    std::vector<brush::Dab> dabs;
    std::string error;

    EXPECT_FALSE(generator.add(samples, dabs, error, 4));
    EXPECT_EQ(dabs.size(), 4U);
    EXPECT_EQ(error, "Brush segment exceeded its available raster queue capacity");
}

std::array<float, 4> pixelAt(const TileStore& store, std::uint32_t x, std::uint32_t y) {
    const auto tile = store.readTile({static_cast<std::int32_t>(x / croptop::kTileEdge), static_cast<std::int32_t>(y / croptop::kTileEdge)});
    if (!tile) return {};
    std::array<float, 4> pixel{};
    croptop::paint::detail::readPixel(
        store.format(),
        tile->data() + (static_cast<std::size_t>(y % croptop::kTileEdge) * croptop::kTileEdge + x % croptop::kTileEdge) * store.bytes_per_pixel(),
        pixel.data());
    return pixel;
}

TEST(BrushDabGenerator, MatchesCoreModelStraightLineSpacingGolden) {
    brush::Settings settings;
    settings.size = 40;
    settings.spacing = 0.25F;
    settings.size_dynamics.input = brush::DynamicsInput::Off;
    brush::DabGenerator generator(settings);
    const std::array samples{brush::Sample{0, 0, 1, 0, 0, 0, 0}, brush::Sample{1000, 0, 1, 0, 0, 0, 1}};
    std::vector<brush::Dab> dabs;
    std::string error;
    ASSERT_TRUE(generator.add(samples, dabs, error)) << error;
    ASSERT_EQ(dabs.size(), 101U);
    for (std::size_t index = 0; index < dabs.size(); ++index) {
        EXPECT_NEAR(dabs[index].x, static_cast<float>(index) * 10.0F, 0.01F);
        EXPECT_FLOAT_EQ(dabs[index].diameter, 40.0F);
        EXPECT_FLOAT_EQ(dabs[index].opacity, 1.0F);
    }
}

TEST(BrushDabGenerator, ChunkBoundariesPreserveSeededDabsExactly) {
    brush::Settings settings;
    settings.size = 12;
    settings.hardness = 0.6F;
    settings.spacing = 0.17F;
    settings.size_jitter = 0.35F;
    settings.angle_jitter = 0.4F;
    settings.scatter = 0.25F;
    settings.scatter_both_axes = true;
    settings.count = 2;
    const std::array samples{
        brush::Sample{10, 10, 0.2F, 0.1F, 0.05F, 0, 0},
        brush::Sample{14, 11, 0.4F, 0.15F, 0.1F, 0, 0.01F},
        brush::Sample{21, 14, 0.8F, 0.2F, 0.1F, 0, 0.02F},
        brush::Sample{30, 14, 1, 0.2F, 0.1F, 0, 0.03F},
    };

    brush::DabGenerator whole(settings, 2741);
    std::vector<brush::Dab> all;
    std::string error;
    ASSERT_TRUE(whole.add(samples, all, error)) << error;

    brush::DabGenerator incremental(settings, 2741);
    std::vector<brush::Dab> chunks;
    for (const auto& sample : samples) ASSERT_TRUE(incremental.add(std::span(&sample, 1), chunks, error)) << error;
    EXPECT_EQ(all, chunks);
    EXPECT_GT(all.size(), 4U);

    brush::DabGenerator alternate(settings, 2742);
    std::vector<brush::Dab> other_seed;
    ASSERT_TRUE(alternate.add(samples, other_seed, error)) << error;
    EXPECT_NE(all, other_seed);
}

TEST(BrushDabGenerator, InvalidSamplesFailWithoutProducingDabs) {
    brush::DabGenerator generator({}, 1);
    std::vector<brush::Dab> output;
    std::string error;
    const brush::Sample invalid{std::numeric_limits<float>::quiet_NaN(), 1, 1, 0, 0, 0, 0};
    EXPECT_FALSE(generator.add(std::span(&invalid, 1), output, error));
    EXPECT_TRUE(output.empty());
    EXPECT_FALSE(error.empty());
}

TEST(BrushDabGenerator, SeparatesOpacityDynamicsFlowDynamicsAndStrokeOpacity) {
    brush::Settings settings;
    settings.size = 8;
    settings.opacity = 0.25F;
    settings.flow = 0.5F;
    settings.size_dynamics.input = brush::DynamicsInput::Off;
    settings.opacity_dynamics.input = brush::DynamicsInput::Pressure;
    settings.flow_dynamics.input = brush::DynamicsInput::Pressure;
    brush::DabGenerator generator(settings);
    const brush::Sample sample{4, 4, 0.5F, 0, 0, 0, 0};
    std::vector<brush::Dab> dabs;
    std::string error;
    ASSERT_TRUE(generator.add(std::span(&sample, 1), dabs, error)) << error;
    ASSERT_EQ(dabs.size(), 1U);
    EXPECT_FLOAT_EQ(dabs.front().opacity, 0.5F);
    EXPECT_FLOAT_EQ(dabs.front().flow, 0.25F);
    EXPECT_FLOAT_EQ(dabs.front().stroke_opacity, 0.25F);
    EXPECT_FALSE(generator.buildUp());
    settings.build_up = true;
    brush::DabGenerator build_up_generator(settings);
    EXPECT_TRUE(build_up_generator.buildUp());
}

TEST(BrushStamp, SeparatedOpacityFlowAndStrokeOpacityKeepLegacyPerDabResult) {
    TileStore target(PixelFormat::RGBA8, 4, 4);
    brush::Dab dab;
    dab.x = 1.5F;
    dab.y = 1.5F;
    dab.diameter = 2;
    dab.hardness = 1;
    dab.opacity = 0.5F;
    dab.flow = 0.5F;
    dab.stroke_opacity = 0.25F;
    std::string error;
    ASSERT_TRUE(brush::stamp(target, dab, 1, 0, 0, 1, false, false, error)) << error;
    EXPECT_NEAR(pixelAt(target, 1, 1)[3], 0.0625F, 1.0F / 255.0F);
}

TEST(BrushStamp, HardTipUsesDeterministicOnePixelAnalyticEdge) {
    TileStore target(PixelFormat::RGBA32F, 6, 6);
    brush::Dab dab;
    dab.x = 2.5F;
    dab.y = 2.5F;
    dab.diameter = 2.0F;
    dab.hardness = 1.0F;
    dab.opacity = 1.0F;
    dab.flow = 1.0F;
    dab.stroke_opacity = 1.0F;
    std::string error;

    ASSERT_TRUE(brush::stamp(target, dab, 1, 0, 0, 1, false, false, error)) << error;
    EXPECT_FLOAT_EQ(pixelAt(target, 2, 2)[3], 1.0F);
    EXPECT_FLOAT_EQ(pixelAt(target, 3, 2)[3], 0.5F);
    EXPECT_FLOAT_EQ(pixelAt(target, 4, 2)[3], 0.0F);
}

TEST(BrushDabGenerator, ReportsDabLimitInsteadOfSilentlyTruncating) {
    brush::Settings settings;
    settings.size = 1;
    settings.spacing = 0.01F;
    settings.size_dynamics.input = brush::DynamicsInput::Off;
    brush::DabGenerator generator(settings);
    const std::array samples{brush::Sample{0, 0, 1, 0, 0, 0, 0}, brush::Sample{10001, 0, 1, 0, 0, 0, 1}};
    std::vector<brush::Dab> dabs;
    std::string error;
    EXPECT_FALSE(generator.add(samples, dabs, error));
    EXPECT_EQ(dabs.size(), 20'000U);
    EXPECT_NE(error.find("20,000-dab"), std::string::npos);
}

TEST(BrushStamp, SoftSourceOverDoesNotBleedTransparentBackdropColor) {
    TileStore target(PixelFormat::RGBA8, 20, 20);
    TileBuffer initial(target.tile_byte_size(), 0);
    const std::size_t offset = (10U * croptop::kTileEdge + 10U) * 4U;
    initial[offset + 1] = 255;  // green RGB under zero alpha must not leak into soft red paint
    ASSERT_TRUE(target.writeTile({0, 0}, initial));

    brush::Dab dab;
    dab.x = 10.5F;
    dab.y = 10.5F;
    dab.diameter = 8;
    dab.hardness = 0;
    dab.opacity = 0.5F;
    std::string error;
    ASSERT_TRUE(brush::stamp(target, dab, 1, 0, 0, 1, false, false, error)) << error;
    const auto center = pixelAt(target, 10, 10);
    EXPECT_NEAR(center[0], 1.0F, 1.0F / 255.0F);
    EXPECT_NEAR(center[1], 0.0F, 1.0F / 255.0F);
    EXPECT_NEAR(center[3], 0.5F, 1.0F / 255.0F);
    EXPECT_GT(pixelAt(target, 13, 10)[3], 0.0F) << "soft falloff reaches edge pixels";
}

TEST(BrushStamp, EraserReducesAlphaAndFullyErasedSparseTilesAreReleased) {
    TileStore target(PixelFormat::RGBA16, 20, 20);
    TileBuffer initial(target.tile_byte_size(), 0);
    const std::uint16_t red = 50000, alpha = 40000;
    const std::size_t offset = (10U * croptop::kTileEdge + 10U) * 8U;
    std::memcpy(initial.data() + offset, &red, sizeof(red));
    std::memcpy(initial.data() + offset + 6U, &alpha, sizeof(alpha));
    ASSERT_TRUE(target.writeTile({0, 0}, initial));

    brush::Dab dab;
    dab.x = 10.5F;
    dab.y = 10.5F;
    dab.diameter = 2;
    dab.hardness = 1;
    dab.opacity = 0.5F;
    std::string error;
    // The UI eraser's color is transparent; erase coverage comes from brush opacity, not source alpha.
    ASSERT_TRUE(brush::stamp(target, dab, 0, 0, 0, 0, true, false, error)) << error;
    const auto partial = pixelAt(target, 10, 10);
    EXPECT_NEAR(partial[0], red / 65535.0F, 1.0F / 65535.0F);
    EXPECT_NEAR(partial[3], (alpha / 65535.0F) * 0.5F, 2.0F / 65535.0F);
    dab.opacity = 1.0F;
    ASSERT_TRUE(brush::stamp(target, dab, 0, 0, 0, 0, true, false, error)) << error;
    EXPECT_EQ(pixelAt(target, 10, 10)[3], 0.0F);
    EXPECT_EQ(target.tile_count(), 0U);
}

TEST(BrushStamp, ClipsAtCanvasEdgeAndStoresFloatLayersInLinearLight) {
    TileStore edge_target(PixelFormat::RGBA8, 4, 4);
    brush::Dab dab;
    dab.x = 0;
    dab.y = 0;
    dab.diameter = 6;
    dab.hardness = 1;
    dab.opacity = 1;
    std::string error;
    ASSERT_TRUE(brush::stamp(edge_target, dab, 1, 0, 0, 1, false, false, error)) << error;
    EXPECT_GT(pixelAt(edge_target, 0, 0)[3], 0.0F);
    EXPECT_EQ(edge_target.tile_count(), 1U);

    TileStore float_target(PixelFormat::RGBA32F, 4, 4);
    dab.x = 2;
    dab.y = 2;
    dab.diameter = 4;
    ASSERT_TRUE(brush::stamp(float_target, dab, 0.5F, 0.5F, 0.5F, 1, false, false, error)) << error;
    const auto half_gray = pixelAt(float_target, 1, 1);
    EXPECT_NEAR(half_gray[0], 0.214041F, 1e-5F);
    EXPECT_NEAR(half_gray[1], 0.214041F, 1e-5F);
    EXPECT_NEAR(half_gray[3], 1.0F, 1e-6F);
}

}  // namespace
