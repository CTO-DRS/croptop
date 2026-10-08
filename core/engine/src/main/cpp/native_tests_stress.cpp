// Memory-stress checks: 200 MP stores under a memory budget, 500-layer composites and
// 500-layer .croptop round trips. They take minutes under sanitizers, so they skip unless CROPTOP_STRESS=1:
//   CROPTOP_STRESS=1 croptop_native_tests --gtest_filter='Stress.*'
#include "croptop_format.h"
#include "render_graph.h"
#include "scratch_store.h"
#include "native_tests_stress_gate.h"
#include "tile_store.h"

#include <gtest/gtest.h>

#include <algorithm>
#include <chrono>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <filesystem>
#include <memory>
#include <span>
#include <string>
#include <vector>

namespace {

using croptop::PixelFormat;
using croptop::TileBuffer;
using croptop::TileCoordinate;
using croptop::TileStore;

// A tile whose bytes depend on its coordinate and on `salt`, so a misplaced or stale tile is detected.
TileBuffer patternTile(std::size_t bytes, TileCoordinate coordinate, std::uint32_t salt) {
    TileBuffer tile(bytes);
    const auto seed = static_cast<std::uint32_t>(coordinate.x) * 73856093U ^ static_cast<std::uint32_t>(coordinate.y) * 19349663U ^ salt;
    for (std::size_t i = 0; i < bytes; ++i) tile[i] = static_cast<std::uint8_t>((seed >> ((i & 3U) * 8U)) + i / 4U);
    return tile;
}

std::vector<TileCoordinate> allTiles(std::uint32_t width, std::uint32_t height) {
    std::vector<TileCoordinate> tiles;
    for (std::uint32_t y = 0; y < (height + croptop::kTileEdge - 1) / croptop::kTileEdge; ++y) {
        for (std::uint32_t x = 0; x < (width + croptop::kTileEdge - 1) / croptop::kTileEdge; ++x) {
            tiles.push_back({static_cast<std::int32_t>(x), static_cast<std::int32_t>(y)});
        }
    }
    return tiles;
}

class ScratchDirectory final {
public:
    ScratchDirectory()
        : path_(std::filesystem::temp_directory_path() /
                ("croptop-stress-" + std::to_string(std::chrono::steady_clock::now().time_since_epoch().count()))) {
        std::filesystem::create_directories(path_);
    }
    ~ScratchDirectory() {
        std::error_code ignored;
        std::filesystem::remove_all(path_, ignored);
    }
    const std::filesystem::path& path() const { return path_; }

private:
    std::filesystem::path path_;
};

}  // namespace

// 16384 × 12208 RGBA8 ≈ 200 MP ≈ 800 MB of pixels through a 256 MB budget: the overflow must spill to the
// compressed scratch store, residency must stay within the budget, and every tile must read back intact.
TEST(Stress, TwoHundredMegapixelStoreStaysWithinMemoryBudget) {
    CROPTOP_REQUIRE_STRESS();
    constexpr std::uint32_t kWidth = 16384;
    constexpr std::uint32_t kHeight = 12208;
    constexpr std::size_t kBudget = 256ULL << 20;
    const ScratchDirectory scratch;
    const auto budget = std::make_shared<croptop::MemoryBudgetManager>(
        kBudget, std::make_shared<croptop::DiskScratchStore>(scratch.path()));
    TileStore store(PixelFormat::RGBA8, kWidth, kHeight, std::make_shared<croptop::TilePool>(), budget);
    const auto tiles = allTiles(kWidth, kHeight);
    ASSERT_EQ(tiles.size(), 64U * 48U);

    std::size_t peak_resident = 0;
    for (const auto coordinate : tiles) {
        ASSERT_TRUE(store.writeTile(coordinate, patternTile(store.tile_byte_size(), coordinate, 1U)));
        peak_resident = std::max(peak_resident, budget->residentBytes());
    }
    EXPECT_LE(peak_resident, kBudget);
    EXPECT_EQ(budget->entryCount(), tiles.size());
    EXPECT_LT(store.resident_bytes(), static_cast<std::size_t>(kWidth) * kHeight * 4U / 2U) << "nothing spilled";

    for (const auto coordinate : tiles) {
        const auto tile = store.readTile(coordinate);
        ASSERT_NE(tile, nullptr) << coordinate.x << "," << coordinate.y;
        ASSERT_EQ(*tile, patternTile(store.tile_byte_size(), coordinate, 1U)) << coordinate.x << "," << coordinate.y;
        peak_resident = std::max(peak_resident, budget->residentBytes());
    }
    EXPECT_LE(peak_resident, kBudget);
}

// 500 half-transparent full-tile layers over one tile. Checks the composite is right and records how many
// intermediate tiles RenderGraph keeps for a single output tile (one per layer today: 500 × 256 KB).
TEST(Stress, FiveHundredLayerCompositeTile) {
    CROPTOP_REQUIRE_STRESS();
    constexpr std::size_t kLayers = 500;
    constexpr std::uint32_t kEdge = croptop::kTileEdge;
    std::vector<TileStore> stores;
    stores.reserve(kLayers);
    std::vector<croptop::RenderLayer> layers;
    for (std::size_t i = 0; i < kLayers; ++i) {
        stores.emplace_back(PixelFormat::RGBA8, kEdge, kEdge);
        TileBuffer tile(stores.back().tile_byte_size());
        for (std::size_t p = 0; p < tile.size(); p += 4) {
            tile[p] = static_cast<std::uint8_t>(i);
            tile[p + 1] = static_cast<std::uint8_t>(255 - i % 256);
            tile[p + 2] = 64;
            tile[p + 3] = 255;
        }
        ASSERT_TRUE(stores.back().writeTile({0, 0}, tile));
        layers.push_back({static_cast<std::uint64_t>(i + 1), &stores.back(), croptop::BlendMode::Normal,
                          static_cast<std::uint8_t>(i + 1 == kLayers ? 255 : 128), 255, true});
    }
    croptop::RenderGraph graph;
    ASSERT_TRUE(graph.setLayers(std::move(layers)));
    const auto start = std::chrono::steady_clock::now();
    const auto composed = graph.renderTile({0, 0});
    const auto elapsed = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - start).count();
    ASSERT_NE(composed, nullptr);
    // The opaque top layer decides the result.
    EXPECT_EQ((*composed)[0], static_cast<std::uint8_t>(kLayers - 1));
    EXPECT_EQ((*composed)[3], 255);
    const auto cached_bytes = graph.cachedTileCount() * stores.front().tile_byte_size();
    RecordProperty("render_ms", std::to_string(elapsed));
    RecordProperty("cached_intermediate_bytes", std::to_string(cached_bytes));
    std::printf("[stress] 500-layer tile: %.1f ms, %zu cached intermediate tiles (%.1f MiB)\n", elapsed,
                graph.cachedTileCount(), static_cast<double>(cached_bytes) / (1 << 20));
    graph.clearCache();
    EXPECT_EQ(graph.cachedTileCount(), 0U);
}

// 500 sparse 2048² layers (distinct tiles per layer) saved to .croptop and reopened: every layer comes back
// under its UUID with identical tiles and nothing else.
TEST(Stress, FiveHundredLayerCroptopRoundTrip) {
    CROPTOP_REQUIRE_STRESS();
    constexpr std::size_t kLayers = 500;
    constexpr std::uint32_t kEdge = 2048;
    const auto pool = std::make_shared<croptop::TilePool>();
    std::vector<TileStore> stores;
    stores.reserve(kLayers);
    std::vector<std::string> uuids;
    croptop::CroptopSaveInput input;
    const std::string manifest = R"({"formatVersion":1,"name":"stress-500"})";
    input.manifest_json = {reinterpret_cast<const std::uint8_t*>(manifest.data()), manifest.size()};
    for (std::size_t i = 0; i < kLayers; ++i) {
        stores.emplace_back(PixelFormat::RGBA8, kEdge, kEdge, pool);
        const TileCoordinate coordinate{static_cast<std::int32_t>(i % 8), static_cast<std::int32_t>(i / 8 % 8)};
        ASSERT_TRUE(stores.back().writeTile(coordinate, patternTile(stores.back().tile_byte_size(), coordinate, static_cast<std::uint32_t>(i))));
        uuids.push_back("layer-" + std::to_string(i));
    }
    for (std::size_t i = 0; i < kLayers; ++i) input.layers.push_back({uuids[i], &stores[i]});

    std::vector<std::uint8_t> bytes;
    const auto saved = croptop::saveCroptop(input, [&bytes](std::span<const std::uint8_t> chunk) {
        bytes.insert(bytes.end(), chunk.begin(), chunk.end());
        return true;
    });
    ASSERT_TRUE(saved.success) << saved.error;

    const croptop::CroptopReadSource source{bytes.size(), [&bytes](std::uint64_t offset, std::span<std::uint8_t> out) {
        if (offset > bytes.size() || out.size() > bytes.size() - offset) return false;
        std::memcpy(out.data(), bytes.data() + offset, out.size());
        return true;
    }};
    std::string error;
    const auto archive = croptop::CroptopArchive::open(source, error);
    ASSERT_NE(archive, nullptr) << error;
    auto listed = archive->layerUuids();
    auto expected = uuids;
    std::sort(expected.begin(), expected.end());
    ASSERT_EQ(listed, expected);
    for (std::size_t i = 0; i < kLayers; ++i) {
        SCOPED_TRACE(uuids[i]);
        croptop::CroptopLayerInfo info;
        ASSERT_TRUE(archive->layerInfo(uuids[i], info, error)) << error;
        TileStore loaded(info.format, info.width, info.height, pool);
        ASSERT_TRUE(archive->loadLayer(uuids[i], loaded, error)) << error;
        ASSERT_EQ(loaded.tileCoordinates(), stores[i].tileCoordinates());
        for (const auto coordinate : loaded.tileCoordinates()) {
            ASSERT_EQ(*loaded.readTile(coordinate), *stores[i].readTile(coordinate));
        }
    }
}
