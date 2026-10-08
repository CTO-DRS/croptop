// Tests for smart objects and smart filters (/).
#include "smart_object.h"

#include <gtest/gtest.h>

#include <cmath>
#include <chrono>
#include <cstring>
#include <functional>
#include <filesystem>

#include "selection_mask.h"
#include "scratch_store.h"

namespace {

using croptop::BlendMode;
using croptop::PixelFormat;
using croptop::TileStore;
namespace smart = croptop::smart;
using Rgba = std::array<std::uint8_t, 4>;

TileStore paint(std::uint32_t w, std::uint32_t h, const std::function<Rgba(std::uint32_t, std::uint32_t)>& f) {
    TileStore s(PixelFormat::RGBA8, w, h);
    for (std::uint32_t ty = 0; ty * 256 < h; ++ty)
        for (std::uint32_t tx = 0; tx * 256 < w; ++tx) {
            croptop::TileBuffer t(s.tile_byte_size(), 0);
            for (std::uint32_t y = 0; y < 256; ++y)
                for (std::uint32_t x = 0; x < 256; ++x) {
                    const auto gx = tx * 256 + x, gy = ty * 256 + y;
                    if (gx >= w || gy >= h) continue;
                    const auto c = f(gx, gy);
                    std::memcpy(t.data() + (static_cast<std::size_t>(y) * 256 + x) * 4, c.data(), 4);
                }
            EXPECT_TRUE(s.writeTile({static_cast<std::int32_t>(tx), static_cast<std::int32_t>(ty)}, t));
        }
    return s;
}

Rgba at(const TileStore& s, std::uint32_t x, std::uint32_t y) {
    Rgba c{};
    const auto t = s.readTile({static_cast<std::int32_t>(x / 256), static_cast<std::int32_t>(y / 256)});
    if (t) std::memcpy(c.data(), t->data() + (static_cast<std::size_t>(y % 256) * 256 + x % 256) * 4, 4);
    return c;
}

TileStore renderOk(const smart::SmartObject& object, std::uint32_t w, std::uint32_t h) {
    std::string error;
    auto out = smart::render(object, w, h, {}, error);
    EXPECT_TRUE(out) << error;
    return out ? std::move(*out) : TileStore(PixelFormat::RGBA8, w, h);
}

const auto kPattern = [](std::uint32_t x, std::uint32_t y) {
    return Rgba{static_cast<std::uint8_t>((x * 7) ^ (y * 3)), static_cast<std::uint8_t>(x % 17 * 15), static_cast<std::uint8_t>(y % 11 * 23), 255};
};

TEST(SmartObject, ScaleDownThenUpLosesNothing) {
    const auto source = paint(300, 200, kPattern);
    auto object = smart::convertToSmartObject(source);
    object.transforms = {{0.25, 0, 0, 0, 0.25, 0, 0, 0, 1}, {4, 0, 0, 0, 4, 0, 0, 0, 1}};
    std::string error;
    const auto rendered = smart::render(object, 300, 200, {}, error);
    ASSERT_TRUE(rendered) << error;
    for (const auto& [x, y] : std::vector<std::pair<std::uint32_t, std::uint32_t>>{{0, 0}, {123, 77}, {299, 199}, {256, 128}}) {
        EXPECT_EQ(at(*rendered, x, y), at(source, x, y)) << x << "," << y;
    }
    // The destructive equivalent (resample down, then up) is visibly different.
    const auto small = croptop::resample::resize(source, 75, 50, croptop::resample::Filter::Bicubic, error);
    const auto back = croptop::resample::resize(*small, 300, 200, croptop::resample::Filter::Bicubic, error);
    int differing = 0;
    for (std::uint32_t y = 0; y < 200; y += 5)
        for (std::uint32_t x = 0; x < 300; x += 5) differing += at(*back, x, y) != at(source, x, y) ? 1 : 0;
    EXPECT_GT(differing, 1000);
}

TEST(SmartObject, TransformPlacesTheSource) {
    const auto source = paint(64, 64, [](std::uint32_t x, std::uint32_t y) { return x < 32 && y < 32 ? Rgba{255, 0, 0, 255} : Rgba{0, 0, 255, 255}; });
    auto object = smart::convertToSmartObject(source);
    object.transforms = {{1, 0, 40, 0, 1, 20, 0, 0, 1}};
    std::string error;
    const auto placed = smart::render(object, 200, 150, {}, error);
    ASSERT_TRUE(placed) << error;
    EXPECT_EQ(at(*placed, 45, 25), (Rgba{255, 0, 0, 255}));
    EXPECT_EQ(at(*placed, 95, 75), (Rgba{0, 0, 255, 255}));
    EXPECT_EQ(at(*placed, 10, 10)[3], 0) << "outside the placed source is transparent";
    const auto m = smart::composedTransform({{2, 0, 0, 0, 2, 0, 0, 0, 1}, {1, 0, 5, 0, 1, 0, 0, 0, 1}});
    EXPECT_DOUBLE_EQ(m[0], 2.0);
    EXPECT_DOUBLE_EQ(m[2], 5.0) << "scale first, then translate";
}

TEST(SmartObject, FiltersBlendOverTheirInputAndTheMaskRestricts) {
    const auto source = paint(256, 128, [](std::uint32_t x, std::uint32_t) { return x % 32 < 16 ? Rgba{200, 200, 200, 255} : Rgba{40, 40, 40, 255}; });
    std::string error;
    auto blurred = smart::convertToSmartObject(source);
    blurred.filters = {smart::gaussianBlurFilter(6.0F)};
    const auto full = smart::render(blurred, 256, 128, {}, error);
    ASSERT_TRUE(full) << error;
    const int edge_before = at(source, 15, 64)[0] - at(source, 16, 64)[0];
    const int edge_after = at(*full, 15, 64)[0] - at(*full, 16, 64)[0];
    EXPECT_LT(edge_after, edge_before / 3) << "blur softens the stripes";

    auto half = blurred;
    half.filters[0].opacity = 0.5F;
    const auto halfway = smart::render(half, 256, 128, {}, error);
    ASSERT_TRUE(halfway) << error;
    EXPECT_NEAR(at(*halfway, 15, 64)[0], (at(source, 15, 64)[0] + at(*full, 15, 64)[0]) / 2.0, 1.5);

    auto disabled = blurred;
    disabled.filters[0].enabled = false;
    EXPECT_EQ(at(renderOk(disabled, 256, 128), 15, 64), at(source, 15, 64));

    auto multiply = smart::convertToSmartObject(source);
    multiply.filters = {smart::SmartFilter{"identity", [](TileStore&, std::string&) { return true; }, BlendMode::Multiply, 1.0F, true}};
    const auto darker = smart::render(multiply, 256, 128, {}, error);
    ASSERT_TRUE(darker) << error;
    EXPECT_NEAR(at(*darker, 5, 64)[0], 200.0 * 200.0 / 255.0, 1.5) << "multiply of the layer with itself";

    // Shared filter mask: filters only apply on the right half.
    auto masked = blurred;
    masked.filter_mask = croptop::selection::rasterizeRect(256, 128, {128, 0, 256, 128}, false);
    const auto partial = smart::render(masked, 256, 128, {}, error);
    ASSERT_TRUE(partial) << error;
    EXPECT_EQ(at(*partial, 15, 64), at(source, 15, 64)) << "masked-out half untouched";
    EXPECT_EQ(at(*partial, 143, 64), at(*full, 143, 64)) << "unmasked half filtered";
    masked.filter_mask_enabled = false;
    EXPECT_EQ(at(renderOk(masked, 256, 128), 15, 64), at(*full, 15, 64)) << "disabled mask";

    // Stacked filters apply in order (blur then sharpen differs from blur alone).
    auto stacked = blurred;
    stacked.filters.push_back(smart::unsharpMaskFilter(1.5F, 2.0F, 0.0F));
    const auto sharpened = renderOk(stacked, 256, 128);
    int difference = 0;
    for (std::uint32_t x = 0; x < 32; ++x) difference += std::abs(at(sharpened, x, 64)[0] - at(*full, x, 64)[0]);
    EXPECT_GT(difference, 40) << "the second filter acts on the first one's output";
}

TEST(SmartObject, Validation) {
    const auto source = paint(32, 32, kPattern);
    std::string error;
    auto object = smart::convertToSmartObject(source);
    EXPECT_FALSE(smart::render(object, 0, 32, {}, error));
    object.transforms = {{1, 0, std::nan(""), 0, 1, 0, 0, 0, 1}};
    EXPECT_FALSE(smart::render(object, 32, 32, {}, error));
    object.transforms.clear();
    object.filters = {smart::SmartFilter{"broken", nullptr}};
    EXPECT_FALSE(smart::render(object, 32, 32, {}, error));
    object.filters = {smart::gaussianBlurFilter(2.0F)};
    object.filters[0].opacity = 1.5F;
    EXPECT_FALSE(smart::render(object, 32, 32, {}, error));
    object.filters = {smart::SmartFilter{"fails", [](TileStore&, std::string& e) {
                                             e = "boom";
                                             return false;
                                         }}};
    EXPECT_FALSE(smart::render(object, 32, 32, {}, error));
    EXPECT_EQ(error, "boom");
    object.filters = {smart::gaussianBlurFilter(2.0F)};
    object.filter_mask = croptop::selection::emptyMask(16, 16);
    EXPECT_FALSE(smart::render(object, 32, 32, {}, error)) << "mask must match the document";
    EXPECT_FALSE(smart::render(smart::convertToSmartObject(TileStore(PixelFormat::A16, 8, 8)), 8, 8, {}, error));
}

TEST(SmartObject, FilterBlendResultSupportsOpacityAndBlendModes) {
    const auto input = paint(1, 1, [](std::uint32_t, std::uint32_t) { return Rgba{255, 0, 0, 255}; });
    const auto effect = paint(1, 1, [](std::uint32_t, std::uint32_t) { return Rgba{0, 255, 0, 255}; });
    std::string error;

    const auto half = smart::blendFilterResult(input, effect, BlendMode::Normal, 0.5F, 1, error);
    ASSERT_TRUE(half) << error;
    EXPECT_EQ(at(*half, 0, 0), (Rgba{128, 128, 0, 255}));

    const auto multiply = smart::blendFilterResult(input, effect, BlendMode::Multiply, 1.0F, 1, error);
    ASSERT_TRUE(multiply) << error;
    EXPECT_EQ(at(*multiply, 0, 0), (Rgba{0, 0, 0, 255}));

    EXPECT_FALSE(smart::blendFilterResult(input, effect, BlendMode::Normal, std::nanf(""), 1, error));
}

TEST(SmartObject, FilterBlendOutputKeepsDocumentAllocatorAndBudget) {
    const auto scratch_path = std::filesystem::temp_directory_path() /
                              ("croptop-smart-filter-budget-" +
                               std::to_string(std::chrono::steady_clock::now().time_since_epoch().count()));
    auto scratch = std::make_shared<croptop::DiskScratchStore>(scratch_path);
    constexpr std::size_t tile_bytes = croptop::kTileEdge * croptop::kTileEdge * 4U;
    auto budget = std::make_shared<croptop::MemoryBudgetManager>(tile_bytes, scratch);
    auto pool = std::make_shared<croptop::TilePool>();
    TileStore input(PixelFormat::RGBA8, 256, 256, pool, budget);
    TileStore effect(PixelFormat::RGBA8, 256, 256, pool, budget);
    croptop::TileBuffer input_tile(input.tile_byte_size(), 0);
    croptop::TileBuffer effect_tile(effect.tile_byte_size(), 0);
    for (std::size_t offset = 0; offset < input_tile.size(); offset += 4U) {
        input_tile[offset] = 128;
        input_tile[offset + 3U] = 255;
        effect_tile[offset + 1U] = 255;
        effect_tile[offset + 3U] = 255;
    }
    ASSERT_TRUE(input.writeTile({0, 0}, input_tile));
    ASSERT_TRUE(effect.writeTile({0, 0}, effect_tile));

    std::string error;
    const auto blended = smart::blendFilterResult(input, effect, BlendMode::Multiply, 1.0F, 1, error);
    ASSERT_TRUE(blended) << error;
    EXPECT_EQ(blended->memoryBudgetManager(), budget);
    EXPECT_EQ(budget->entryCount(), 3U) << "the blend output must be accounted in the document budget";
    EXPECT_LE(budget->residentBytes(), budget->budgetBytes());
    std::filesystem::remove_all(scratch_path);
}

}  // namespace
