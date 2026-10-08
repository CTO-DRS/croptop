// Golden tests for the CPU resampling library.
#include "resample.h"
#include "scratch_store.h"
#include "tile_store.h"

#include <gtest/gtest.h>

#include <algorithm>
#include <array>
#include <chrono>
#include <cstring>
#include <filesystem>
#include <functional>
#include <memory>
#include <string>
#include <vector>

namespace {

using croptop::PixelFormat;
using croptop::TileStore;
namespace rs = croptop::resample;
using Rgba = std::array<std::uint8_t, 4>;

TileStore paint(std::uint32_t width, std::uint32_t height, const std::function<Rgba(std::uint32_t, std::uint32_t)>& color) {
    TileStore store(PixelFormat::RGBA8, width, height);
    for (std::uint32_t ty = 0; ty * croptop::kTileEdge < height; ++ty) {
        for (std::uint32_t tx = 0; tx * croptop::kTileEdge < width; ++tx) {
            croptop::TileBuffer tile(store.tile_byte_size(), 0);
            for (std::uint32_t y = 0; y < croptop::kTileEdge; ++y) {
                for (std::uint32_t x = 0; x < croptop::kTileEdge; ++x) {
                    const auto gx = tx * croptop::kTileEdge + x;
                    const auto gy = ty * croptop::kTileEdge + y;
                    if (gx >= width || gy >= height) continue;
                    const auto c = color(gx, gy);
                    std::memcpy(tile.data() + (static_cast<std::size_t>(y) * croptop::kTileEdge + x) * 4, c.data(), 4);
                }
            }
            EXPECT_TRUE(store.writeTile({static_cast<std::int32_t>(tx), static_cast<std::int32_t>(ty)}, tile));
        }
    }
    return store;
}

Rgba at(const TileStore& store, std::uint32_t x, std::uint32_t y) {
    Rgba c{};
    const auto tile = store.readTile({static_cast<std::int32_t>(x / croptop::kTileEdge), static_cast<std::int32_t>(y / croptop::kTileEdge)});
    if (tile) std::memcpy(c.data(), tile->data() + (static_cast<std::size_t>(y % croptop::kTileEdge) * croptop::kTileEdge + x % croptop::kTileEdge) * 4, 4);
    return c;
}

int maxDifference(const TileStore& a, const TileStore& b) {
    int worst = 0;
    for (std::uint32_t y = 0; y < a.height(); ++y) {
        for (std::uint32_t x = 0; x < a.width(); ++x) {
            const auto p = at(a, x, y);
            const auto q = at(b, x, y);
            for (int c = 0; c < 4; ++c) worst = std::max(worst, std::abs(int(p[c]) - int(q[c])));
        }
    }
    return worst;
}

const auto kFilters = {rs::Filter::Bilinear, rs::Filter::Bicubic, rs::Filter::Lanczos3};

TEST(Resample, SameSizeIsIdentityAndConstantsStayConstant) {
    const auto image = paint(300, 260, [](std::uint32_t x, std::uint32_t y) {
        return Rgba{static_cast<std::uint8_t>(x), static_cast<std::uint8_t>(y), static_cast<std::uint8_t>(x ^ y), 255};
    });
    const auto flat = paint(300, 260, [](std::uint32_t, std::uint32_t) { return Rgba{37, 180, 99, 200}; });
    std::string error;
    for (const auto filter : kFilters) {
        const auto same = rs::resize(image, 300, 260, filter, error);
        ASSERT_TRUE(same) << error;
        EXPECT_LE(maxDifference(*same, image), 1) << "filter " << int(filter);
        for (const auto [w, h] : {std::pair{97u, 211u}, std::pair{611u, 523u}, std::pair{1u, 1u}}) {
            const auto scaled = *rs::resize(flat, w, h, filter, error);
            for (std::uint32_t y = 0; y < h; y += std::max(1u, h / 7)) {
                for (std::uint32_t x = 0; x < w; x += std::max(1u, w / 7)) {
                    const auto c = at(scaled, x, y);
                    ASSERT_NEAR(c[0], 37, 1);
                    ASSERT_NEAR(c[1], 180, 1);
                    ASSERT_NEAR(c[2], 99, 1);
                    ASSERT_NEAR(c[3], 200, 1);
                }
            }
        }
    }
}

TEST(Resample, DownscalingIsBandLimitedAndLinearLight) {
    // A 1-pixel checkerboard averages to 50% linear light, which is sRGB 188, not 128.
    const auto checker = paint(512, 512, [](std::uint32_t x, std::uint32_t y) {
        const std::uint8_t v = (x + y) % 2 == 0 ? 255 : 0;
        return Rgba{v, v, v, 255};
    });
    std::string error;
    for (const auto filter : kFilters) {
        for (const std::uint32_t size : {256u, 100u, 17u}) {
            const auto small = *rs::resize(checker, size, size, filter, error);
            for (std::uint32_t y = 2; y + 2 < size; y += 3) {
                for (std::uint32_t x = 2; x + 2 < size; x += 3) {
                    ASSERT_NEAR(at(small, x, y)[0], 188, 3) << "filter " << int(filter) << " size " << size;
                }
            }
        }
    }
}

TEST(Resample, TransparentPixelsDoNotTintEdges) {
    // Opaque red next to fully transparent *green*: premultiplied filtering must never show green.
    const auto image = paint(64, 64, [](std::uint32_t x, std::uint32_t) {
        return x < 32 ? Rgba{255, 0, 0, 255} : Rgba{0, 255, 0, 0};
    });
    std::string error;
    for (const auto filter : kFilters) {
        for (const std::uint32_t size : {31u, 96u}) {
            const auto scaled = *rs::resize(image, size, size, filter, error);
            for (std::uint32_t x = 0; x < size; ++x) {
                const auto c = at(scaled, x, size / 2);
                if (c[3] == 0) continue;
                EXPECT_LE(c[1], 1) << "green fringe at x=" << x << " filter " << int(filter);
                EXPECT_GE(c[0], 250) << "red stays red where visible, x=" << x;
            }
        }
    }
}

TEST(Resample, GeometricOperationsAreExact) {
    const auto image = paint(300, 7, [](std::uint32_t x, std::uint32_t y) {
        return Rgba{static_cast<std::uint8_t>(x), static_cast<std::uint8_t>(y), 9, 255};
    });
    EXPECT_EQ(maxDifference(rs::flip(rs::flip(image, true), true), image), 0);
    EXPECT_EQ(at(rs::flip(image, true), 0, 3), at(image, 299, 3));
    EXPECT_EQ(at(rs::flip(image, false), 10, 0), at(image, 10, 6));
    const auto cw = rs::rotate90(image, 1);
    ASSERT_EQ(cw.width(), 7u);
    ASSERT_EQ(cw.height(), 300u);
    EXPECT_EQ(at(cw, 6, 0), at(image, 0, 0)) << "top-left goes to top-right";
    EXPECT_EQ(at(cw, 0, 299), at(image, 299, 6)) << "bottom-right goes to bottom-left";
    EXPECT_EQ(maxDifference(rs::rotate90(rs::rotate90(cw, 1), 2), image), 0);
    EXPECT_EQ(maxDifference(rs::rotate90(image, -1), rs::rotate90(image, 3)), 0);

    std::string error;
    const auto grown = *rs::canvasSize(image, 400, 20, 50, 5, error);
    EXPECT_EQ(at(grown, 50, 5), at(image, 0, 0));
    EXPECT_EQ(at(grown, 349, 11), at(image, 299, 6));
    EXPECT_EQ(at(grown, 10, 10)[3], 0) << "new area is transparent";
    const auto cropped = *rs::canvasSize(image, 100, 3, -120, -2, error);
    EXPECT_EQ(at(cropped, 0, 0), at(image, 120, 2));
    EXPECT_FALSE(rs::canvasSize(image, 0, 3, 0, 0, error));

    const auto doubled = *rs::resize(image, 600, 14, rs::Filter::Nearest, error);
    EXPECT_EQ(at(doubled, 0, 0), at(image, 0, 0));
    EXPECT_EQ(at(doubled, 1, 1), at(image, 0, 0));
    EXPECT_EQ(at(doubled, 599, 13), at(image, 299, 6));
    EXPECT_FALSE(rs::resize(image, 0, 5, rs::Filter::Bilinear, error));
}

TEST(Resample, MasksAndSixteenBitKeepTheirFormat) {
    TileStore mask(PixelFormat::A16, 300, 300);
    croptop::TileBuffer tile(mask.tile_byte_size(), 0);
    for (std::size_t i = 0; i < tile.size(); i += 2) {
        const std::uint16_t v = 40000;
        std::memcpy(tile.data() + i, &v, 2);
    }
    for (int ty = 0; ty < 2; ++ty)
        for (int tx = 0; tx < 2; ++tx) ASSERT_TRUE(mask.writeTile({tx, ty}, tile));
    std::string error;
    const auto scaled = *rs::resize(mask, 123, 77, rs::Filter::Lanczos3, error);
    EXPECT_EQ(scaled.format(), PixelFormat::A16);
    std::uint16_t v = 0;
    std::memcpy(&v, scaled.readTile({0, 0})->data() + (10 * croptop::kTileEdge + 10) * 2, 2);
    EXPECT_NEAR(v, 40000, 2);

    TileStore deep(PixelFormat::RGBA16, 50, 50);
    croptop::TileBuffer deep_tile(deep.tile_byte_size(), 0);
    for (std::size_t i = 0; i < deep_tile.size(); i += 8) {
        const std::array<std::uint16_t, 4> px{12000, 30000, 50000, 65535};
        std::memcpy(deep_tile.data() + i, px.data(), 8);
    }
    ASSERT_TRUE(deep.writeTile({0, 0}, deep_tile));
    const auto deep_scaled = *rs::resize(deep, 31, 90, rs::Filter::Bicubic, error);
    std::array<std::uint16_t, 4> px{};
    std::memcpy(px.data(), deep_scaled.readTile({0, 0})->data() + (20 * croptop::kTileEdge + 15) * 8, 8);
    EXPECT_NEAR(px[0], 12000, 2);
    EXPECT_NEAR(px[1], 30000, 2);
    EXPECT_NEAR(px[2], 50000, 2);
}

TEST(Resample, FilteredOutputKeepsDocumentAllocatorAndBudget) {
    const auto scratch_path = std::filesystem::temp_directory_path() /
                              ("croptop-resample-budget-" +
                               std::to_string(std::chrono::steady_clock::now().time_since_epoch().count()));
    auto scratch = std::make_shared<croptop::DiskScratchStore>(scratch_path);
    constexpr std::size_t tile_bytes = croptop::kTileEdge * croptop::kTileEdge * 4U;
    auto budget = std::make_shared<croptop::MemoryBudgetManager>(tile_bytes, scratch);
    TileStore source(PixelFormat::RGBA8, 512, 256, std::make_shared<croptop::TilePool>(), budget);
    croptop::TileBuffer tile(source.tile_byte_size(), 0);
    for (std::size_t offset = 0; offset < tile.size(); offset += 4U) {
        tile[offset] = 40;
        tile[offset + 1U] = 120;
        tile[offset + 2U] = 200;
        tile[offset + 3U] = 255;
    }
    ASSERT_TRUE(source.writeTile({0, 0}, tile));
    ASSERT_TRUE(source.writeTile({1, 0}, tile));

    std::string error;
    const auto resized = rs::resize(source, 512, 256, rs::Filter::Bilinear, error);
    ASSERT_TRUE(resized) << error;
    EXPECT_EQ(resized->format(), PixelFormat::RGBA8);
    EXPECT_EQ(resized->tile_count(), 2U);
    EXPECT_EQ(budget->entryCount(), 4U) << "both output tiles must use the source document budget";
    EXPECT_LE(budget->residentBytes(), budget->budgetBytes());
    EXPECT_EQ(at(*resized, 400, 128), (Rgba{40, 120, 200, 255}));

    const auto warped = rs::warp(source, {1, 0, 0, 0, 1, 0, 0, 0, 1}, 512, 256, rs::Filter::Bicubic, error);
    ASSERT_TRUE(warped) << error;
    EXPECT_EQ(warped->memoryBudgetManager(), budget) << "warp output must keep the source document budget";
    EXPECT_EQ(budget->entryCount(), 6U) << "the two warp output tiles must use the source document budget";
    EXPECT_LE(budget->residentBytes(), budget->budgetBytes());
    EXPECT_EQ(at(*warped, 400, 128), (Rgba{40, 120, 200, 255}));
    std::filesystem::remove_all(scratch_path);
}

TEST(Warp, IdentityTranslationAndRotationMatchExactOperations) {
    const auto image = paint(300, 260, [](std::uint32_t x, std::uint32_t y) {
        return Rgba{static_cast<std::uint8_t>(x), static_cast<std::uint8_t>(y), static_cast<std::uint8_t>(x ^ y), 255};
    });
    std::string error;
    for (const auto filter : kFilters) {
        const auto same = rs::warp(image, {1, 0, 0, 0, 1, 0, 0, 0, 1}, 300, 260, filter, error);
        ASSERT_TRUE(same) << error;
        EXPECT_LE(maxDifference(*same, image), 1) << "filter " << int(filter);
        const auto moved = *rs::warp(image, {1, 0, 7, 0, 1, -3, 0, 0, 1}, 300, 260, filter, error);
        const auto expected = *rs::canvasSize(image, 300, 260, 7, -3, error);
        EXPECT_LE(maxDifference(moved, expected), 1);
        // Clockwise quarter turn of a 300×260 layer into a 260×300 canvas: (x, y) → (260 - y, x).
        const auto turned = *rs::warp(image, {0, -1, 260, 1, 0, 0, 0, 0, 1}, 260, 300, filter, error);
        EXPECT_LE(maxDifference(turned, rs::rotate90(image, 1)), 1);
    }
}

TEST(Warp, MinificationIsAntialiasedAndEdgesAreSoft) {
    const auto checker = paint(512, 512, [](std::uint32_t x, std::uint32_t y) {
        const std::uint8_t v = (x + y) % 2 == 0 ? 255 : 0;
        return Rgba{v, v, v, 255};
    });
    std::string error;
    for (const auto filter : kFilters) {
        const auto half = *rs::warp(checker, {0.5, 0, 0, 0, 0.5, 0, 0, 0, 1}, 256, 256, filter, error);
        for (std::uint32_t y = 10; y < 246; y += 17) {
            for (std::uint32_t x = 10; x < 246; x += 17) {
                ASSERT_NEAR(at(half, x, y)[0], 188, 4) << "filter " << int(filter);
            }
        }
    }
    // A half-pixel move leaves a half-covered edge column instead of a hard cut.
    const auto opaque = paint(40, 40, [](std::uint32_t, std::uint32_t) { return Rgba{200, 100, 50, 255}; });
    const auto shifted = *rs::warp(opaque, {1, 0, 10.5, 0, 1, 0, 0, 0, 1}, 80, 40, rs::Filter::Bilinear, error);
    EXPECT_NEAR(at(shifted, 10, 20)[3], 128, 2);
    EXPECT_EQ(at(shifted, 20, 20)[3], 255);
    EXPECT_EQ(at(shifted, 5, 20)[3], 0);
    EXPECT_EQ(at(shifted, 20, 20)[0], 200) << "colour is not darkened by the transparent surround";
}

TEST(Warp, PerspectiveAndRejections) {
    const auto image = paint(200, 200, [](std::uint32_t, std::uint32_t) { return Rgba{10, 20, 30, 255}; });
    std::string error;
    const auto keystone = rs::warp(image, {1, 0, 0, 0, 1, 0, 0.001, 0, 1}, 200, 200, rs::Filter::Bicubic, error);
    ASSERT_TRUE(keystone) << error;
    EXPECT_EQ(at(*keystone, 20, 100)[3], 255);
    EXPECT_NEAR(at(*keystone, 20, 100)[2], 30, 1);
    EXPECT_EQ(at(*keystone, 190, 5)[3], 0) << "far corner shrinks towards the horizon";
    EXPECT_FALSE(rs::warp(image, {1, 0, 0, 0, 1, 0, -0.01, 0, 1}, 200, 200, rs::Filter::Bilinear, error));
    EXPECT_FALSE(rs::warp(image, {1, 2, 0, 2, 4, 0, 0, 0, 1}, 200, 200, rs::Filter::Bilinear, error));
    EXPECT_FALSE(rs::warp(image, {1, 0, 0, 0, 1, 0, 0, 0, 1}, 0, 200, rs::Filter::Bilinear, error));
    EXPECT_EQ(rs::warp(image, {1, 0, 5000, 0, 1, 0, 0, 0, 1}, 200, 200, rs::Filter::Bilinear, error)->tile_count(), 0u);
}

}  // namespace
