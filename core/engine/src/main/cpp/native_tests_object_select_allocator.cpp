// Allocator propagation for model-free Object Selection / Select Subject.
#include "object_select.h"
#include "scratch_store.h"
#include "selection_mask.h"

#include <gtest/gtest.h>

#include <chrono>
#include <cstring>
#include <filesystem>
#include <memory>
#include <string>

namespace {

using croptop::PixelFormat;
using croptop::TileBuffer;
using croptop::TilePool;
using croptop::TileStore;
namespace selection = croptop::selection;

struct ScratchDirectory final {
    std::filesystem::path path = std::filesystem::temp_directory_path() /
        ("croptop-object-selection-allocator-" +
         std::to_string(std::chrono::steady_clock::now().time_since_epoch().count()));
    ~ScratchDirectory() { std::filesystem::remove_all(path); }
};

TileStore syntheticImage(std::uint32_t width, std::uint32_t height) {
    TileStore image(PixelFormat::RGBA8, width, height);
    const auto columns = (width + croptop::kTileEdge - 1U) / croptop::kTileEdge;
    const auto rows = (height + croptop::kTileEdge - 1U) / croptop::kTileEdge;
    for (std::uint32_t ty = 0; ty < rows; ++ty) {
        for (std::uint32_t tx = 0; tx < columns; ++tx) {
            TileBuffer tile(image.tile_byte_size(), 0);
            for (std::uint32_t ly = 0; ly < croptop::kTileEdge && ty * croptop::kTileEdge + ly < height; ++ly) {
                for (std::uint32_t lx = 0; lx < croptop::kTileEdge && tx * croptop::kTileEdge + lx < width; ++lx) {
                    const auto x = tx * croptop::kTileEdge + lx;
                    const auto y = ty * croptop::kTileEdge + ly;
                    const bool compact = width <= 100;
                    const bool subject = compact ? (x >= 15 && x < 49 && y >= 14 && y < 50)
                                                 : (x >= 120 && x < 390 && y >= 60 && y < 190);
                    const std::uint8_t rgba[4] = {subject ? std::uint8_t{225} : std::uint8_t{24},
                                                  subject ? std::uint8_t{35} : std::uint8_t{42},
                                                  subject ? std::uint8_t{28} : std::uint8_t{58}, 255};
                    std::memcpy(tile.data() + (static_cast<std::size_t>(ly) * croptop::kTileEdge + lx) * 4U,
                                rgba, sizeof(rgba));
                }
            }
            EXPECT_TRUE(image.writeTile({static_cast<std::int32_t>(tx), static_cast<std::int32_t>(ty)}, tile));
        }
    }
    return image;
}

TEST(ObjectSelectionAllocator, OutputUsesDocumentBudgetAndRetainsSubjectAcrossSpillRestore) {
    ScratchDirectory scratch_path;
    auto scratch = std::make_shared<croptop::DiskScratchStore>(scratch_path.path);
    constexpr std::size_t a16_tile_bytes = croptop::kTileEdge * croptop::kTileEdge * sizeof(std::uint16_t);
    auto budget = std::make_shared<croptop::MemoryBudgetManager>(a16_tile_bytes, scratch);
    auto document_pool = std::make_shared<TilePool>();
    const TileStore document_pixels(PixelFormat::RGBA8, 512, 256, document_pool, budget);
    const auto image = syntheticImage(512, 256);

    std::string error;
    const auto mask = selection::selectObject(document_pixels, image, {70, 20, 450, 230}, error);
    ASSERT_TRUE(mask) << error;
    EXPECT_EQ(mask->format(), PixelFormat::A16);
    EXPECT_EQ(mask->width(), image.width());
    EXPECT_EQ(mask->height(), image.height());
    EXPECT_EQ(mask->memoryBudgetManager(), budget);
    EXPECT_GT(selection::coverageAt(*mask, 200, 120), 32768);
    EXPECT_GT(selection::coverageAt(*mask, 300, 120), 32768);
    EXPECT_EQ(selection::coverageAt(*mask, 40, 40), 0);
    EXPECT_EQ(mask->tile_count(), 2U);
    EXPECT_LE(budget->residentBytes(), budget->budgetBytes());
    EXPECT_GT(budget->entryCount(), 1U);

    // Re-read after the second output tile has forced a spill; the mask remains complete.
    EXPECT_GT(selection::coverageAt(*mask, 200, 120), 32768);
    EXPECT_GT(selection::coverageAt(*mask, 300, 120), 32768);
    EXPECT_LE(budget->residentBytes(), budget->budgetBytes());
}

TEST(ObjectSelectionAllocator, SelectSubjectWrapperPreservesSourcePoolAndProducesAnA16Mask) {
    auto pool = std::make_shared<TilePool>();
    TileStore image = syntheticImage(64, 64);
    const TileStore allocation_like(PixelFormat::RGBA8, image.width(), image.height(), pool);
    const auto bytes_before = pool->active_bytes();

    std::string error;
    const auto mask = selection::selectSubject(allocation_like, image, error);
    ASSERT_TRUE(mask) << error;
    EXPECT_EQ(mask->format(), PixelFormat::A16);
    EXPECT_EQ(mask->width(), image.width());
    EXPECT_EQ(mask->height(), image.height());
    EXPECT_GT(pool->active_bytes(), bytes_before);
    EXPECT_GT(selection::coverageAt(*mask, 32, 32), 32768);
    EXPECT_EQ(selection::coverageAt(*mask, 4, 4), 0);
}

}  // namespace
