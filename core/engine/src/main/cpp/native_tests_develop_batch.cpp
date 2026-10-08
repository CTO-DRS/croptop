// Tests for bounded ordered Develop batch rendering.
#include "develop_batch.h"

#include <gtest/gtest.h>

#include <algorithm>
#include <atomic>
#include <cmath>
#include <cstring>
#include <limits>
#include <string>
#include <vector>

namespace {
using namespace croptop;
namespace batch = develop::batch;
namespace pipeline = develop::pipeline;

TileStore source(float red) {
    TileStore image(PixelFormat::RGBA16, 8, 8);
    TileBuffer tile(image.tile_byte_size(), 0);
    const std::uint16_t channel = static_cast<std::uint16_t>(std::lround(red * 65535.0F));
    const std::uint16_t alpha = 65535;
    for (std::size_t i = 0; i < kTileEdge * kTileEdge; ++i) {
        std::memcpy(tile.data() + i * 8U, &channel, 2U);
        std::memcpy(tile.data() + i * 8U + 6U, &alpha, 2U);
    }
    EXPECT_TRUE(image.writeTile({0, 0}, tile));
    return image;
}

pipeline::Settings settings() {
    pipeline::Settings result;
    result.detail.color_noise = 0;
    return result;
}

TEST(DevelopBatch, RendersInOrderStreamsOneResultAndReportsMonotonicProgress) {
    auto a = source(0.2F), b = source(0.6F), c = source(0.8F);
    std::vector<batch::Item> items{{"one", &a, settings(), {}}, {"two", &b, settings(), {}}, {"three", &c, settings(), {}}};
    std::vector<std::string> ids;
    std::vector<float> progress;
    const auto summary = batch::run(items, [&](std::size_t index, std::string_view id, pipeline::Result&& result) {
        EXPECT_EQ(index, ids.size());
        EXPECT_FALSE(result.pixels.tileCoordinates().empty());
        ids.emplace_back(id);
        return true;
    }, {}, [&](float value) { progress.push_back(value); return true; });
    ASSERT_FALSE(summary.cancelled) << summary.error;
    ASSERT_EQ(summary.items.size(), 3U);
    EXPECT_EQ(ids, (std::vector<std::string>{"one", "two", "three"}));
    for (const auto& item : summary.items) {
        EXPECT_EQ(item.state, batch::ItemState::Succeeded);
        EXPECT_EQ(item.output_width, 8U);
        EXPECT_EQ(item.output_height, 8U);
    }
    ASSERT_FALSE(progress.empty());
    EXPECT_FLOAT_EQ(progress.front(), 0.0F);
    EXPECT_FLOAT_EQ(progress.back(), 1.0F);
    EXPECT_TRUE(std::is_sorted(progress.begin(), progress.end()));
}

TEST(DevelopBatch, RecordsItemFailuresAndContinues) {
    auto a = source(0.2F), b = source(0.4F), c = source(0.8F);
    auto invalid = settings(); invalid.basic.exposure = std::numeric_limits<float>::quiet_NaN();
    std::vector<batch::Item> items{{"valid-a", &a, settings(), {}}, {"invalid", &b, invalid, {}}, {"valid-c", &c, settings(), {}}};
    std::vector<std::string> delivered;
    const auto summary = batch::run(items, [&](std::size_t, std::string_view id, pipeline::Result&&) {
        delivered.emplace_back(id); return true;
    });
    ASSERT_FALSE(summary.cancelled);
    ASSERT_EQ(summary.items.size(), 3U);
    EXPECT_EQ(summary.items[0].state, batch::ItemState::Succeeded);
    EXPECT_EQ(summary.items[1].state, batch::ItemState::Failed);
    EXPECT_FALSE(summary.items[1].error.empty());
    EXPECT_EQ(summary.items[2].state, batch::ItemState::Succeeded);
    EXPECT_EQ(delivered, (std::vector<std::string>{"valid-a", "valid-c"}));
}

TEST(DevelopBatch, CancellationStopsCurrentRenderAndMarksRemainingItems) {
    auto a = source(0.2F), b = source(0.4F), c = source(0.8F);
    auto heavy = settings(); heavy.basic.clarity = 50;
    std::vector<batch::Item> items{{"first", &a, heavy, {}}, {"second", &b, heavy, {}}, {"third", &c, heavy, {}}};
    std::atomic_bool stop = false;
    std::vector<std::string> delivered;
    const auto summary = batch::run(items, [&](std::size_t, std::string_view id, pipeline::Result&&) {
        delivered.emplace_back(id); return true;
    }, [&] { return stop.load(); }, [&](float value) { if (value >= 0.15F) stop.store(true); return true; });
    EXPECT_TRUE(summary.cancelled);
    ASSERT_EQ(summary.items.size(), 3U);
    EXPECT_EQ(summary.items[0].state, batch::ItemState::Cancelled);
    EXPECT_EQ(summary.items[1].state, batch::ItemState::Cancelled);
    EXPECT_EQ(summary.items[2].state, batch::ItemState::Cancelled);
    EXPECT_TRUE(delivered.empty());
}

TEST(DevelopBatch, StopsWhenSinkRejectsAndHandlesMissingSources) {
    auto a = source(0.3F);
    std::vector<batch::Item> items{{"bad-source", nullptr, settings(), {}}, {"sink-reject", &a, settings(), {}}, {"unstarted", &a, settings(), {}}};
    const auto summary = batch::run(items, [](std::size_t, std::string_view, pipeline::Result&&) { return false; });
    EXPECT_TRUE(summary.cancelled);
    ASSERT_EQ(summary.items.size(), 3U);
    EXPECT_EQ(summary.items[0].state, batch::ItemState::Failed);
    EXPECT_EQ(summary.items[1].state, batch::ItemState::SinkRejected);
    EXPECT_EQ(summary.items[2].state, batch::ItemState::Cancelled);
}

TEST(DevelopBatch, EmptyBatchHonorsFinalProgressRejection) {
    std::vector<batch::Item> items;
    std::vector<float> progress;
    const auto summary = batch::run(items, {}, {}, [&](float value) {
        progress.push_back(value);
        return value < 1.0F;
    });
    EXPECT_TRUE(summary.cancelled);
    EXPECT_EQ(summary.error, "batch progress callback cancelled");
    EXPECT_EQ(progress, (std::vector<float>{0.0F, 1.0F}));
}

}  // namespace
