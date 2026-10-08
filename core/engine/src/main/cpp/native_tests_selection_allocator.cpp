// Allocator-aware selection combine/modify coverage and memory-budget regressions.
#include "scratch_store.h"
#include "selection_mask.h"
#include "selection_modify.h"
#include "tile_store.h"

#include <gtest/gtest.h>

#include <chrono>
#include <filesystem>
#include <memory>
#include <string>

namespace {

using croptop::PixelFormat;
using croptop::TilePool;
using croptop::TileStore;
namespace selection = croptop::selection;

struct ScratchDirectory final {
    std::filesystem::path path = std::filesystem::temp_directory_path() /
        ("croptop-selection-allocator-" +
         std::to_string(std::chrono::steady_clock::now().time_since_epoch().count()));
    ~ScratchDirectory() { std::filesystem::remove_all(path); }
};

TEST(SelectionAllocator, CombineInvertAndModifyUseDocumentBudgetAndPreservePixelsThroughSpill) {
    ScratchDirectory scratch_path;
    auto scratch = std::make_shared<croptop::DiskScratchStore>(scratch_path.path);
    constexpr std::size_t tile_bytes = croptop::kTileEdge * croptop::kTileEdge * sizeof(std::uint16_t);
    auto budget = std::make_shared<croptop::MemoryBudgetManager>(tile_bytes, scratch);
    auto pool = std::make_shared<TilePool>();
    const TileStore document_pixels(PixelFormat::RGBA8, 512, 256, pool, budget);
    const auto source = selection::rasterizeRect(512, 256, {4, 4, 500, 252}, false);
    const auto shape = selection::rasterizeRect(512, 256, {260, 20, 300, 60}, false);

    std::string error;
    auto added = selection::combine(document_pixels, source, shape, selection::Op::Add, &error);
    ASSERT_TRUE(added) << error;
    EXPECT_EQ(added->memoryBudgetManager(), budget);
    EXPECT_EQ(selection::coverageAt(*added, 10, 10), selection::kFullySelected);
    EXPECT_EQ(selection::coverageAt(*added, 280, 40), selection::kFullySelected);
    EXPECT_EQ(selection::coverageAt(source, 280, 40), selection::kFullySelected);
    EXPECT_LE(budget->residentBytes(), budget->budgetBytes());

    auto inverted = selection::invert(document_pixels, source, &error);
    ASSERT_TRUE(inverted) << error;
    EXPECT_EQ(inverted->memoryBudgetManager(), budget);
    EXPECT_EQ(selection::coverageAt(*inverted, 10, 10), 0);
    EXPECT_EQ(selection::coverageAt(*inverted, 510, 10), selection::kFullySelected);

    auto expanded = selection::expand(document_pixels, source, 2.0F, error);
    ASSERT_TRUE(expanded) << error;
    EXPECT_EQ(expanded->memoryBudgetManager(), budget);
    EXPECT_EQ(selection::coverageAt(*expanded, 4, 100), selection::kFullySelected);
    EXPECT_EQ(selection::coverageAt(*expanded, 2, 100), selection::kFullySelected);
    EXPECT_EQ(selection::coverageAt(*expanded, 1, 100), 0);

    // Touch both managed tiles again after other outputs have evicted them; restored data stays exact.
    EXPECT_EQ(selection::coverageAt(*added, 10, 10), selection::kFullySelected);
    EXPECT_EQ(selection::coverageAt(*added, 280, 40), selection::kFullySelected);
    EXPECT_EQ(selection::coverageAt(*inverted, 510, 10), selection::kFullySelected);
    EXPECT_LE(budget->residentBytes(), budget->budgetBytes());
}

TEST(SelectionAllocator, ModifyLimitFailureLeavesSourceUntouched) {
    ScratchDirectory scratch_path;
    auto scratch = std::make_shared<croptop::DiskScratchStore>(scratch_path.path);
    constexpr std::size_t tile_bytes = croptop::kTileEdge * croptop::kTileEdge * sizeof(std::uint16_t);
    auto budget = std::make_shared<croptop::MemoryBudgetManager>(tile_bytes, scratch);
    const TileStore document_pixels(PixelFormat::RGBA8, 64, 64, std::make_shared<TilePool>(), budget);
    const auto source = selection::rasterizeRect(64, 64, {20, 20, 40, 40}, false);

    selection::ModifyLimits limits;
    limits.max_working_bytes = 1;
    std::string error;
    const auto rejected = selection::expand(document_pixels, source, 2.0F, error, limits);
    EXPECT_FALSE(rejected);
    EXPECT_NE(error.find("memory limit"), std::string::npos);
    EXPECT_EQ(selection::coverageAt(source, 25, 25), selection::kFullySelected);
    EXPECT_EQ(source.memoryBudgetManager(), nullptr);
}

TEST(SelectionAllocator, OutputsReuseDocumentTilePoolWhenNoBudgetManagerIsConfigured) {
    auto pool = std::make_shared<TilePool>();
    const TileStore document_pixels(PixelFormat::RGBA8, 64, 64, pool);
    const auto source = selection::rasterizeRect(64, 64, {20, 20, 40, 40}, false);
    const auto shape = selection::rasterizeRect(64, 64, {30, 30, 50, 50}, false);
    const auto bytes_before = pool->active_bytes();
    std::string error;
    const auto combined = selection::combine(document_pixels, source, shape, selection::Op::Add, &error);
    ASSERT_TRUE(combined) << error;
    EXPECT_GT(pool->active_bytes(), bytes_before);
    const auto expanded = selection::expand(document_pixels, source, 1.0F, error);
    ASSERT_TRUE(expanded) << error;
    EXPECT_GT(pool->active_bytes(), bytes_before);
    EXPECT_EQ(selection::coverageAt(*combined, 35, 35), selection::kFullySelected);
}

}  // namespace
