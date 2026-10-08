#include "image_metrics.h"
#include "job_system.h"
#include "render_graph.h"
#include "tile_store.h"

#include <benchmark/benchmark.h>

#include <array>
#include <latch>
#include <memory>
#include <vector>

namespace {

void BM_DeltaE2000(benchmark::State& state) {
    const croptop::Rgba8 lhs{34, 119, 202, 255};
    const croptop::Rgba8 rhs{39, 115, 194, 255};
    for (auto _ : state) {
        benchmark::DoNotOptimize(croptop::deltaE2000(lhs, rhs));
    }
    state.SetItemsProcessed(state.iterations());
}

BENCHMARK(BM_DeltaE2000);

void BM_RenderGraph50LayerDirtyTile(benchmark::State& state) {
    std::vector<std::unique_ptr<croptop::TileStore>> stores;
    std::vector<croptop::RenderLayer> layers;
    stores.reserve(50);
    layers.reserve(50);
    constexpr std::array modes{
        croptop::BlendMode::Normal, croptop::BlendMode::Multiply, croptop::BlendMode::Screen,
        croptop::BlendMode::Overlay, croptop::BlendMode::SoftLight, croptop::BlendMode::Difference,
        croptop::BlendMode::Color, croptop::BlendMode::Luminosity,
    };
    for (std::uint64_t i = 0; i < 50; ++i) {
        auto store = std::make_unique<croptop::TileStore>(croptop::PixelFormat::RGBA8, 256, 256);
        croptop::TileBuffer pixels(store->tile_byte_size());
        for (std::size_t pixel = 0; pixel < pixels.size(); pixel += 4) {
            pixels[pixel] = static_cast<std::uint8_t>((pixel / 4 + i * 37) & 0xffU);
            pixels[pixel + 1] = static_cast<std::uint8_t>((pixel / 16 + i * 61) & 0xffU);
            pixels[pixel + 2] = static_cast<std::uint8_t>((pixel / 64 + i * 19) & 0xffU);
            pixels[pixel + 3] = 255;
        }
        if (!store->writeTile({0, 0}, pixels)) {
            state.SkipWithError("could not allocate a benchmark layer tile");
            return;
        }
        layers.push_back({i + 1, store.get(), modes[i % modes.size()], 255, 255, true});
        stores.push_back(std::move(store));
    }
    croptop::RenderGraph graph;
    if (!graph.setLayers(std::move(layers), croptop::BlendSpace::LinearLight)) {
        state.SkipWithError("could not configure the 50-layer benchmark graph");
        return;
    }
    for (auto _ : state) {
        graph.clearCache();
        const auto tile = graph.renderTile({0, 0});
        benchmark::DoNotOptimize(tile.get());
    }
    state.SetItemsProcessed(state.iterations() * 50 * 256 * 256);
    state.SetLabel("50 layers; recomposite one dirty 256x256 tile (CPU reference)");
}

BENCHMARK(BM_RenderGraph50LayerDirtyTile)->Unit(benchmark::kMillisecond)->MinTime(0.01);

void BM_RenderGraphParallelVisibleTiles(benchmark::State& state) {
    constexpr std::size_t kTiles = 8;
    std::array<std::unique_ptr<croptop::TileStore>, 2> stores;
    std::vector<croptop::RenderLayer> layers;
    for (std::size_t layer = 0; layer < stores.size(); ++layer) {
        stores[layer] = std::make_unique<croptop::TileStore>(croptop::PixelFormat::RGBA8, 2048, 256);
        croptop::TileBuffer pixels(stores[layer]->tile_byte_size(), 0);
        for (std::size_t pixel = 0; pixel < pixels.size(); pixel += 4) {
            pixels[pixel] = static_cast<std::uint8_t>(layer == 0 ? 96 : 180);
            pixels[pixel + 1] = 80;
            pixels[pixel + 2] = 140;
            pixels[pixel + 3] = 255;
        }
        for (std::size_t tile = 0; tile < kTiles; ++tile) {
            if (!stores[layer]->writeTile({static_cast<std::int32_t>(tile), 0}, pixels)) {
                state.SkipWithError("could not allocate visible benchmark tiles");
                return;
            }
        }
        layers.push_back({layer + 1, stores[layer].get(), layer == 0 ? croptop::BlendMode::Normal :
            croptop::BlendMode::Multiply, 255, 255, true});
    }
    croptop::RenderGraph graph;
    if (!graph.setLayers(std::move(layers), croptop::BlendSpace::LinearLight)) {
        state.SkipWithError("could not configure visible benchmark graph");
        return;
    }
    croptop::JobPool pool(static_cast<std::size_t>(state.range(0)), kTiles);
    for (auto _ : state) {
        graph.clearCache();
        std::latch done(kTiles);
        std::array<std::shared_ptr<const croptop::TileBuffer>, kTiles> output;
        for (std::size_t tile = 0; tile < kTiles; ++tile) {
            auto work = [&, tile](const croptop::JobContext& context) {
                if (!context.cancellationRequested()) output[tile] = graph.renderTile({static_cast<std::int32_t>(tile), 0});
                done.count_down();
            };
            if (!pool.submit(croptop::JobPriority::Visible, work)) {
                output[tile] = graph.renderTile({static_cast<std::int32_t>(tile), 0});
                done.count_down();
            }
        }
        done.wait();
        for (const auto& tile : output) benchmark::DoNotOptimize(tile.get());
    }
    state.SetItemsProcessed(state.iterations() * kTiles * stores.size() * 256 * 256);
    state.SetLabel("8 visible tiles; 2 layers; CPU pool scaling diagnostic");
}

BENCHMARK(BM_RenderGraphParallelVisibleTiles)->Arg(1)->Arg(2)->Arg(4)->Arg(8)
    ->UseRealTime()->Unit(benchmark::kMillisecond)->MinTime(0.01);

}  // namespace
