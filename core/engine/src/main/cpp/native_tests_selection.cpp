// Host tests for the selection core and rasterizers.
#include "parallel_util.h"
#include "selection_color.h"
#include "selection_mask.h"
#include "selection_modify.h"
#include "selection_transform.h"
#include "scratch_store.h"
#include "tile_store.h"

#include <gtest/gtest.h>

#include <atomic>
#include <array>
#include <chrono>
#include <filesystem>
#include <memory>
#include <stdexcept>

#include <algorithm>
#include <cmath>
#include <cstring>
#include <random>
#include <string>
#include <vector>

namespace {

using croptop::PixelFormat;
using croptop::TileStore;

bool sameTiles(const TileStore& a, const TileStore& b) {
    if (a.format() != b.format() || a.width() != b.width() || a.height() != b.height()) return false;
    const croptop::TileBuffer zero(a.tile_byte_size(), 0);
    for (std::uint32_t y = 0; y * croptop::kTileEdge < a.height(); ++y) {
        for (std::uint32_t x = 0; x * croptop::kTileEdge < a.width(); ++x) {
            const croptop::TileCoordinate c{static_cast<std::int32_t>(x), static_cast<std::int32_t>(y)};
            const auto ta = a.readTile(c);
            const auto tb = b.readTile(c);
            if ((ta ? *ta : zero) != (tb ? *tb : zero)) return false;
        }
    }
    return true;
}



namespace sel = croptop::selection;

double coverageSum(const TileStore& mask) {
    double sum = 0.0;
    for (const auto c : mask.tileCoordinates()) {
        const auto tile = mask.readTile(c);
        for (std::size_t i = 0; i < tile->size(); i += 2) {
            std::uint16_t v = 0;
            std::memcpy(&v, tile->data() + i, 2);
            sum += v / 65535.0;
        }
    }
    return sum;
}

TEST(Selection, RectangleCoverageIsExactAreaAndCentersWithoutAntialias) {
    const auto aa = sel::rasterizeRect(300, 300, {10.25, 20.5, 270.75, 21.5}, true);
    EXPECT_EQ(sel::coverageAt(aa, 10, 20), static_cast<std::uint16_t>(std::lround(0.75 * 0.5 * 65535)));
    EXPECT_EQ(sel::coverageAt(aa, 11, 20), static_cast<std::uint16_t>(std::lround(0.5 * 65535)));
    EXPECT_EQ(sel::coverageAt(aa, 270, 21), static_cast<std::uint16_t>(std::lround(0.75 * 0.5 * 65535)));
    EXPECT_EQ(sel::coverageAt(aa, 9, 20), 0);
    EXPECT_NEAR(coverageSum(aa), 260.5 * 1.0, 0.01);
    const auto bounds = sel::bounds(aa);
    ASSERT_TRUE(bounds.has_value());
    EXPECT_EQ(bounds->left, 10);
    EXPECT_EQ(bounds->top, 20);
    EXPECT_EQ(bounds->right, 271);
    EXPECT_EQ(bounds->bottom, 22);

    const auto hard = sel::rasterizeRect(300, 300, {10.25, 20.5, 270.75, 21.5}, false);
    EXPECT_EQ(sel::coverageAt(hard, 10, 20), 65535);  // Center 10.5 is inside [10.25, 270.75).
    EXPECT_EQ(sel::coverageAt(hard, 270, 20), 65535);
    EXPECT_EQ(sel::coverageAt(hard, 271, 20), 0);
    EXPECT_EQ(sel::coverageAt(hard, 10, 21), 0);  // Center y 21.5 is not below 21.5.
    // Reversed corners, off-canvas parts and non-finite input.
    EXPECT_EQ(coverageSum(sel::rasterizeRect(100, 100, {-50, -50, 10, 10}, true)), 100.0);
    EXPECT_EQ(coverageSum(sel::rasterizeRect(100, 100, {10, 10, -50, -50}, true)), 100.0);
    EXPECT_TRUE(sel::isEmpty(sel::rasterizeRect(100, 100, {200, 200, 300, 300}, true)));
    EXPECT_TRUE(sel::isEmpty(sel::rasterizeRect(100, 100, {0, 0, NAN, 5}, true)));
}

TEST(Selection, AllocatorAwareRasterizersShareDocumentMemoryBudget) {
    const auto scratch_path = std::filesystem::temp_directory_path() /
                              ("croptop-selection-budget-" +
                               std::to_string(std::chrono::steady_clock::now().time_since_epoch().count()));
    auto scratch = std::make_shared<croptop::DiskScratchStore>(scratch_path);
    constexpr std::size_t a16_tile_bytes = croptop::kTileEdge * croptop::kTileEdge * sizeof(std::uint16_t);
    auto budget = std::make_shared<croptop::MemoryBudgetManager>(a16_tile_bytes, scratch);
    const TileStore document_pixels(PixelFormat::RGBA8, 512, 256, std::make_shared<croptop::TilePool>(), budget);

    auto blank = croptop::selection::emptyMask(document_pixels);
    EXPECT_EQ(blank.format(), PixelFormat::A16);
    EXPECT_EQ(blank.width(), document_pixels.width());
    EXPECT_EQ(blank.height(), document_pixels.height());
    EXPECT_TRUE(croptop::selection::isEmpty(blank));

    auto rectangle = croptop::selection::rasterizeRect(document_pixels, {0.0, 0.0, 512.0, 256.0}, false);
    EXPECT_EQ(croptop::selection::coverageAt(rectangle, 0, 0), croptop::selection::kFullySelected);
    EXPECT_EQ(croptop::selection::coverageAt(rectangle, 511, 255), croptop::selection::kFullySelected);
    EXPECT_LE(budget->residentBytes(), budget->budgetBytes());
    EXPECT_EQ(budget->entryCount(), 2U);

    auto ellipse = croptop::selection::rasterizeEllipse(document_pixels, {300.0, 20.0, 500.0, 220.0}, false);
    EXPECT_EQ(croptop::selection::coverageAt(ellipse, 400, 120), croptop::selection::kFullySelected);
    auto polygon = croptop::selection::rasterizePolygon(
        document_pixels, std::array<croptop::selection::Point, 3>{{{20.0, 20.0}, {90.0, 20.0}, {20.0, 90.0}}}, false);
    EXPECT_EQ(croptop::selection::coverageAt(polygon, 30, 30), croptop::selection::kFullySelected);
    EXPECT_LE(budget->residentBytes(), budget->budgetBytes());
}

TEST(Selection, ScratchFailurePreservesCompleteMaskInsteadOfReturningPartialOutput) {
    const auto scratch_path = std::filesystem::temp_directory_path() /
                              ("croptop-selection-no-scratch-" +
                               std::to_string(std::chrono::steady_clock::now().time_since_epoch().count()));
    auto scratch = std::make_shared<croptop::DiskScratchStore>(scratch_path);
    std::filesystem::remove_all(scratch_path);  // Force subsequent spill writes to fail.
    auto budget = std::make_shared<croptop::MemoryBudgetManager>(0U, scratch);
    const TileStore document_pixels(PixelFormat::RGBA8, 512, 256, std::make_shared<croptop::TilePool>(), budget);

    const auto mask = croptop::selection::rasterizeRect(document_pixels, {0.0, 0.0, 512.0, 256.0}, false);
    EXPECT_EQ(croptop::selection::coverageAt(mask, 0, 0), croptop::selection::kFullySelected);
    EXPECT_EQ(croptop::selection::coverageAt(mask, 511, 255), croptop::selection::kFullySelected);
    EXPECT_EQ(mask.tile_count(), 2U);
    EXPECT_GT(budget->residentBytes(), budget->budgetBytes());  // The manager deliberately favors preserving pixels.
    std::filesystem::remove(scratch_path);
}

TEST(Selection, EllipseAndPolygonAreasMatchGeometry) {
    const auto ellipse = sel::rasterizeEllipse(600, 400, {50.3, 40.7, 550.1, 360.9}, true);
    const double expected = M_PI * (550.1 - 50.3) / 2.0 * (360.9 - 40.7) / 2.0;
    EXPECT_NEAR(coverageSum(ellipse), expected, expected * 0.0005);
    // Non-AA ellipse selects exactly the pixels whose centers are inside.
    const auto hard = sel::rasterizeEllipse(64, 48, {3.2, 5.1, 58.9, 40.3}, false);
    const double cx = (3.2 + 58.9) / 2, cy = (5.1 + 40.3) / 2, rx = (58.9 - 3.2) / 2, ry = (40.3 - 5.1) / 2;
    for (int y = 0; y < 48; ++y) {
        for (int x = 0; x < 64; ++x) {
            const double dx = (x + 0.5 - cx) / rx, dy = (y + 0.5 - cy) / ry;
            const double half = dy * dy < 1 ? rx * std::sqrt(1 - dy * dy) : -1;
            const bool in = half >= 0 && x + 0.5 >= cx - half && x + 0.5 < cx + half;
            ASSERT_EQ(sel::coverageAt(hard, x, y), in ? 65535 : 0) << x << "," << y;
            (void)dx;
        }
    }

    const std::vector<sel::Point> triangle = {{20.5, 10.25}, {480.75, 300.5}, {60.0, 290.0}};
    const double triangle_area = std::abs((triangle[1].x - triangle[0].x) * (triangle[2].y - triangle[0].y) -
                                          (triangle[2].x - triangle[0].x) * (triangle[1].y - triangle[0].y)) / 2.0;
    EXPECT_NEAR(coverageSum(sel::rasterizePolygon(512, 320, triangle, true)), triangle_area, triangle_area * 0.001);

    // An integer-aligned square polygon equals the rectangle rasterizer exactly.
    const std::vector<sel::Point> square = {{10, 10}, {300, 10}, {300, 270}, {10, 270}};
    const auto polygon = sel::rasterizePolygon(512, 320, square, true);
    const auto rect = sel::rasterizeRect(512, 320, {10, 10, 300, 270}, true);
    EXPECT_TRUE(sameTiles(polygon, rect));

    // Even-odd: the pentagram's center is not selected, its points are.
    std::vector<sel::Point> star;
    for (int i = 0; i < 5; ++i) {
        const double angle = -M_PI / 2 + i * 4 * M_PI / 5;
        star.push_back({200 + 150 * std::cos(angle), 200 + 150 * std::sin(angle)});
    }
    const auto pentagram = sel::rasterizePolygon(400, 400, star, true);
    EXPECT_EQ(sel::coverageAt(pentagram, 200, 200), 0);
    EXPECT_EQ(sel::coverageAt(pentagram, 200, 70), 65535);
    // Degenerate input.
    EXPECT_TRUE(sel::isEmpty(sel::rasterizePolygon(100, 100, std::vector<sel::Point>{{1, 1}, {5, 5}}, true)));
    EXPECT_TRUE(sel::isEmpty(sel::rasterizePolygon(100, 100, std::vector<sel::Point>{{1, 1}, {5, 5}, {9, 9}}, true)));
}

TEST(Selection, CombineInvertAndBoundsFollowTheDocumentedSemantics) {
    const auto a = sel::rasterizeEllipse(700, 300, {20, 20, 400, 280}, true);
    const auto b = sel::rasterizeRect(700, 300, {300.5, 100.25, 650.5, 200.75}, true);
    const auto expect = [&](const TileStore& result, auto op) {
        for (int y = 0; y < 300; y += 7) {
            for (int x = 0; x < 700; x += 3) {
                ASSERT_EQ(sel::coverageAt(result, x, y), op(sel::coverageAt(a, x, y), sel::coverageAt(b, x, y)))
                    << x << "," << y;
            }
        }
    };
    expect(*sel::combine(a, b, sel::Op::Replace), [](std::uint16_t, std::uint16_t y) { return y; });
    expect(*sel::combine(a, b, sel::Op::Add), [](std::uint16_t x, std::uint16_t y) { return std::max(x, y); });
    expect(*sel::combine(a, b, sel::Op::Subtract),
           [](std::uint16_t x, std::uint16_t y) { return std::min<std::uint16_t>(x, 65535 - y); });
    expect(*sel::combine(a, b, sel::Op::Intersect), [](std::uint16_t x, std::uint16_t y) { return std::min(x, y); });
    EXPECT_FALSE(sel::combine(a, sel::emptyMask(701, 300), sel::Op::Add).has_value());

    const auto inverted = *sel::invert(a);
    EXPECT_EQ(sel::coverageAt(inverted, 0, 0), 65535);
    EXPECT_EQ(sel::coverageAt(inverted, 210, 150), 0);
    EXPECT_TRUE(sameTiles(*sel::invert(inverted), a));
    EXPECT_TRUE(sameTiles(*sel::invert(sel::emptyMask(700, 300)), sel::fullMask(700, 300)));
    EXPECT_TRUE(sel::isEmpty(*sel::invert(sel::fullMask(700, 300))));
    EXPECT_EQ(sel::bounds(*sel::invert(sel::fullMask(700, 300))), std::nullopt);
    // Pixels past the document edge inside edge tiles stay zero, so bounds never exceed the canvas.
    const auto full_bounds = *sel::bounds(sel::fullMask(700, 300));
    EXPECT_EQ(full_bounds.right, 700);
    EXPECT_EQ(full_bounds.bottom, 300);
    // Subtracting everything leaves no tiles behind.
    EXPECT_EQ(sel::combine(a, sel::fullMask(700, 300), sel::Op::Subtract)->tile_count(), 0U);
}

TEST(Selection, CombineAndInvertReportPreciseInputFailuresAndClearErrorsOnSuccess) {
    std::string error = "stale failure";
    const TileStore selection(PixelFormat::A16, 8, 8);
    const TileStore wrong_dimensions(PixelFormat::A16, 9, 8);
    EXPECT_FALSE(sel::combine(selection, wrong_dimensions, sel::Op::Add, &error));
    EXPECT_EQ(error, "selection masks must have matching dimensions");

    const TileStore wrong_format(PixelFormat::RGBA8, 8, 8);
    EXPECT_FALSE(sel::combine(selection, wrong_format, sel::Op::Add, &error));
    EXPECT_EQ(error, "selection masks must use A16 format");

    EXPECT_FALSE(sel::invert(wrong_format, &error));
    EXPECT_EQ(error, "selection mask must use A16 format");

    error = "stale failure";
    ASSERT_TRUE(sel::combine(selection, selection, sel::Op::Add, &error));
    EXPECT_TRUE(error.empty());
    error = "stale failure";
    ASSERT_TRUE(sel::invert(selection, &error));
    EXPECT_TRUE(error.empty());
}

TEST(Selection, ContourTracesPixelEdgesWithinTheRequestedRegion) {
    const auto rect = sel::rasterizeRect(600, 400, {10, 20, 310, 120}, false);
    const auto all = sel::contour(rect, {0, 0, 600, 400});
    ASSERT_FALSE(all.truncated);
    ASSERT_EQ(all.segments.size(), 4U);
    const auto has = [&](const sel::ContourResult& r, sel::Segment s) {
        return std::any_of(r.segments.begin(), r.segments.end(), [&](const sel::Segment& t) {
            return t.x0 == s.x0 && t.y0 == s.y0 && t.x1 == s.x1 && t.y1 == s.y1;
        });
    };
    EXPECT_TRUE(has(all, {10, 20, 310, 20}));
    EXPECT_TRUE(has(all, {10, 120, 310, 120}));
    EXPECT_TRUE(has(all, {10, 20, 10, 120}));
    EXPECT_TRUE(has(all, {310, 20, 310, 120}));

    // A region strictly inside the selection has no edges (the margin is read from the real mask).
    EXPECT_TRUE(sel::contour(rect, {50, 50, 100, 100}).segments.empty());
    // A region crossing the left edge only reports the part of that edge it contains.
    const auto part = sel::contour(rect, {0, 40, 60, 80});
    ASSERT_EQ(part.segments.size(), 1U);
    EXPECT_TRUE(has(part, {10, 40, 10, 80}));
    // Mip level 1 traces the 2x-downsampled mask in document coordinates.
    const auto coarse = sel::contour(rect, {0, 0, 600, 400}, 1);
    EXPECT_TRUE(has(coarse, {10, 20, 310, 20}));
    // Truncation is reported.
    const auto noisy = sel::rasterizePolygon(600, 400, std::vector<sel::Point>{{0, 0}, {600, 3}, {0, 6}}, false);
    const auto capped = sel::contour(*sel::combine(rect, noisy, sel::Op::Add), {0, 0, 600, 400}, 0, 32768, 3);
    EXPECT_TRUE(capped.truncated);
    EXPECT_EQ(capped.segments.size(), 3U);
}

// ---- Select ▸ Modify ------------------------------------------------------------------------------

TEST(SelectionModify, ExpandAndContractMatchBruteForceDistances) {
    // Random binary mask on a small canvas, checked against O(n^2) nearest-distance search.
    constexpr std::uint32_t kW = 70;
    constexpr std::uint32_t kH = 45;
    TileStore mask(PixelFormat::A16, kW, kH);
    croptop::TileBuffer tile(mask.tile_byte_size(), 0);
    std::mt19937 random(3);
    std::vector<bool> selected(kW * kH);
    for (std::uint32_t y = 0; y < kH; ++y) {
        for (std::uint32_t x = 0; x < kW; ++x) {
            selected[y * kW + x] = (x > 20 && x < 50 && y > 10 && y < 35) ? random() % 9 != 0 : random() % 40 == 0;
            const std::uint16_t v = selected[y * kW + x] ? 65535 : 0;
            std::memcpy(tile.data() + (static_cast<std::size_t>(y) * croptop::kTileEdge + x) * 2, &v, 2);
        }
    }
    ASSERT_TRUE(mask.writeTile({0, 0}, tile));
    // Brute-force nearest distances, computed once for both targets.
    std::vector<double> to_selected(kW * kH, 1e9);
    std::vector<double> to_unselected(kW * kH, 1e9);
    for (std::uint32_t py = 0; py < kH; ++py) {
        for (std::uint32_t px = 0; px < kW; ++px) {
            for (std::uint32_t y = 0; y < kH; ++y) {
                for (std::uint32_t x = 0; x < kW; ++x) {
                    const double d = std::hypot(double(x) - px, double(y) - py);
                    auto& best = selected[y * kW + x] ? to_selected[py * kW + px] : to_unselected[py * kW + px];
                    best = std::min(best, d);
                }
            }
        }
    }
    const auto nearest = [&](std::uint32_t px, std::uint32_t py, bool want) {
        return want ? to_selected[py * kW + px] : to_unselected[py * kW + px];
    };
    std::string error;
    for (const float radius : {0.0F, 1.0F, 2.5F, 6.25F}) {
        const auto grown = croptop::selection::expand(mask, radius, error);
        const auto shrunk = croptop::selection::contract(mask, radius, error);
        ASSERT_TRUE(grown && shrunk) << error;
        for (std::uint32_t y = 0; y < kH; ++y) {
            for (std::uint32_t x = 0; x < kW; ++x) {
                const auto want_grow = static_cast<std::uint16_t>(
                    std::lround(std::clamp(radius + 1.0 - nearest(x, y, true), 0.0, 1.0) * 65535));
                const auto want_shrink = static_cast<std::uint16_t>(
                    std::lround(std::clamp(nearest(x, y, false) - radius, 0.0, 1.0) * 65535));
                ASSERT_NEAR(croptop::selection::coverageAt(*grown, x, y), want_grow, 2) << radius << " " << x << "," << y;
                ASSERT_NEAR(croptop::selection::coverageAt(*shrunk, x, y), want_shrink, 2) << radius << " " << x << "," << y;
            }
        }
    }
}

TEST(SelectionModify, ContractDoesNotErodeCanvasEdgesAndBorderIsABand) {
    std::string error;
    const auto all = croptop::selection::fullMask(600, 300);
    EXPECT_TRUE(sameTiles(*croptop::selection::contract(all, 20.0F, error), all));

    const auto rect = croptop::selection::rasterizeRect(600, 300, {100, 100, 300, 200}, false);
    const auto shrunk = *croptop::selection::contract(rect, 5.0F, error);
    EXPECT_EQ(croptop::selection::coverageAt(shrunk, 104, 150), 0);      // Columns 100..104 removed.
    EXPECT_EQ(croptop::selection::coverageAt(shrunk, 105, 150), 65535);
    const auto band = *croptop::selection::border(rect, 8.0F, error);
    EXPECT_EQ(croptop::selection::coverageAt(band, 200, 150), 0);        // Deep inside.
    EXPECT_EQ(croptop::selection::coverageAt(band, 50, 150), 0);         // Far outside.
    EXPECT_EQ(croptop::selection::coverageAt(band, 100, 150), 65535);    // On the edge.
    EXPECT_EQ(croptop::selection::coverageAt(band, 98, 150), 65535);
    EXPECT_EQ(croptop::selection::coverageAt(band, 102, 150), 65535);
}

TEST(SelectionModify, FeatherFollowsAGaussianEdgeProfileAndConservesArea) {
    std::string error;
    const auto rect = croptop::selection::rasterizeRect(800, 600, {200, 150, 600, 450}, false);
    const auto soft = croptop::selection::feather(rect, 20.0F, error);  // sigma = 10
    ASSERT_TRUE(soft) << error;
    EXPECT_EQ(croptop::selection::coverageAt(*soft, 400, 300), 65535);
    EXPECT_EQ(croptop::selection::coverageAt(*soft, 50, 300), 0);
    for (int d = -30; d <= 30; d += 5) {
        // Pixel at signed distance d (+ inside) from the left edge, along the middle row.
        const double center_offset = d + 0.5;
        const double expected = 0.5 * std::erfc(-center_offset / (10.0 * std::sqrt(2.0)));
        EXPECT_NEAR(croptop::selection::coverageAt(*soft, 200 + d, 300) / 65535.0, expected, 0.03) << d;
    }
    const auto area = [](const TileStore& mask) {
        double sum = 0;
        for (std::uint32_t y = 0; y < mask.height(); ++y)
            for (std::uint32_t x = 0; x < mask.width(); ++x) sum += croptop::selection::coverageAt(mask, x, y) / 65535.0;
        return sum;
    };
    EXPECT_NEAR(area(*soft), 400.0 * 300.0, 400.0 * 300.0 * 0.002);
}

TEST(SelectionModify, SmoothRemovesSpecksAndFillsPinholes) {
    std::string error;
    auto mask = croptop::selection::rasterizeRect(400, 300, {100, 100, 300, 200}, false);
    const auto speck = croptop::selection::rasterizeRect(400, 300, {30, 30, 31, 31}, false);
    const auto hole = croptop::selection::rasterizeRect(400, 300, {200, 150, 201, 151}, false);
    mask = *croptop::selection::combine(*croptop::selection::combine(mask, speck, croptop::selection::Op::Add), hole,
                                        croptop::selection::Op::Subtract);
    ASSERT_EQ(croptop::selection::coverageAt(mask, 30, 30), 65535);
    ASSERT_EQ(croptop::selection::coverageAt(mask, 200, 150), 0);
    const auto smoothed = croptop::selection::smooth(mask, 2.0F, error);
    ASSERT_TRUE(smoothed) << error;
    EXPECT_EQ(croptop::selection::coverageAt(*smoothed, 30, 30), 0);
    EXPECT_EQ(croptop::selection::coverageAt(*smoothed, 200, 150), 65535);
    EXPECT_EQ(croptop::selection::coverageAt(*smoothed, 150, 150), 65535);
    // Straight edges of the rectangle survive (majority on each side of the edge).
    EXPECT_EQ(croptop::selection::coverageAt(*smoothed, 100, 150), 65535);
    EXPECT_EQ(croptop::selection::coverageAt(*smoothed, 99, 150), 0);
}

TEST(SelectionModify, RejectsBadRadiiAndOversizedWork) {
    std::string error;
    const auto rect = croptop::selection::rasterizeRect(300, 300, {10, 10, 290, 290}, false);
    EXPECT_FALSE(croptop::selection::feather(rect, -1.0F, error));
    EXPECT_FALSE(croptop::selection::feather(rect, 1001.0F, error));
    EXPECT_FALSE(croptop::selection::expand(rect, NAN, error));
    EXPECT_FALSE(croptop::selection::border(rect, 201.0F, error));
    croptop::selection::ModifyLimits tiny;
    tiny.max_working_bytes = 1024;
    EXPECT_FALSE(croptop::selection::expand(rect, 5.0F, error, tiny));
    EXPECT_NE(error.find("memory"), std::string::npos);
    // Empty selections and zero radii are no-ops.
    const auto empty = croptop::selection::emptyMask(300, 300);
    EXPECT_TRUE(croptop::selection::isEmpty(*croptop::selection::feather(empty, 50.0F, error)));
    EXPECT_TRUE(sameTiles(*croptop::selection::expand(rect, 0.0F, error), rect));
}

// ---- Transform Selection -------------------------------------------------------------------------

TEST(SelectionTransform, IntegerMovesAreExactAndRotationsPreserveShape) {
    namespace sel = croptop::selection;
    std::string error;
    const auto rect = sel::rasterizeRect(600, 400, {100, 50, 300, 150}, true);
    EXPECT_TRUE(sameTiles(*sel::transform(rect, {1, 0, 0, 0, 1, 0, 0, 0, 1}, error), rect));
    EXPECT_TRUE(sameTiles(*sel::transform(rect, {1, 0, 7, 0, 1, -3, 0, 0, 1}, error),
                          sel::rasterizeRect(600, 400, {107, 47, 307, 147}, true)));
    // Half-pixel move: edge pixels become half covered.
    const auto half = *sel::transform(rect, {1, 0, 0.5, 0, 1, 0, 0, 0, 1}, error);
    EXPECT_NEAR(sel::coverageAt(half, 100, 100), 32768, 1);
    EXPECT_NEAR(sel::coverageAt(half, 300, 100), 32768, 1);
    EXPECT_EQ(sel::coverageAt(half, 200, 100), 65535);
    // 90° rotation about the rectangle's center maps it onto the rotated rectangle exactly.
    const double cx = 200, cy = 100;
    const auto rotated = *sel::transform(rect, {0, -1, cx + cy, 1, 0, cy - cx, 0, 0, 1}, error);
    EXPECT_TRUE(sameTiles(rotated, sel::rasterizeRect(600, 400, {150, 0, 250, 200}, true)));
    // Scaling by 2 about the origin quadruples the area (within bilinear edge error).
    const auto scaled = *sel::transform(rect, {2, 0, 0, 0, 2, 0, 0, 0, 1}, error);
    const auto area = [&](const TileStore& m) {
        double sum = 0;
        for (std::uint32_t y = 0; y < m.height(); ++y)
            for (std::uint32_t x = 0; x < m.width(); ++x) sum += sel::coverageAt(m, x, y) / 65535.0;
        return sum;
    };
    EXPECT_NEAR(area(scaled), 4 * 200 * 100, 4 * 200 * 100 * 0.002);
}

TEST(SelectionTransform, PerspectiveClippingAndRejections) {
    namespace sel = croptop::selection;
    std::string error;
    const auto rect = sel::rasterizeRect(400, 400, {100, 100, 300, 300}, false);
    // Mild perspective keeps the center selected and changes the outline.
    const auto keystone = sel::transform(rect, {1, 0, 0, 0, 1, 0, 0.0005, 0, 1}, error);
    ASSERT_TRUE(keystone) << error;
    EXPECT_GT(sel::coverageAt(*keystone, 150, 150), 60000);
    EXPECT_FALSE(sel::isEmpty(*keystone));
    // Moving most of the selection off-canvas clips it.
    const auto off = *sel::transform(rect, {1, 0, 250, 0, 1, 0, 0, 0, 1}, error);
    EXPECT_EQ(sel::bounds(off)->right, 400);
    EXPECT_TRUE(sel::isEmpty(*sel::transform(rect, {1, 0, 1000, 0, 1, 0, 0, 0, 1}, error)));
    EXPECT_FALSE(sel::transform(rect, {1, 2, 0, 2, 4, 0, 0, 0, 1}, error)) << "singular";
    EXPECT_FALSE(sel::transform(rect, {1, 0, 0, 0, 1, 0, -0.01, 0, 1}, error)) << "crosses the horizon";
    EXPECT_FALSE(sel::transform(rect, {1, 0, NAN, 0, 1, 0, 0, 0, 1}, error));
    EXPECT_TRUE(sel::isEmpty(*sel::transform(sel::emptyMask(400, 400), {2, 0, 0, 0, 2, 0, 0, 0, 1}, error)));
}

// ---- Threading helper --------------------------------------------------------------------------

TEST(ParallelFor, WorkerExceptionsReachTheCallerAfterAllThreadsJoin) {
    std::atomic<int> started{0};
    EXPECT_THROW(croptop::parallel::parallelFor(1000, 4, [&](std::size_t index, std::size_t) {
                     started.fetch_add(1);
                     if (index == 7) throw std::runtime_error("boom");
                 }),
                 std::runtime_error);
    EXPECT_GE(started.load(), 1);  // Which other items ran is timing-dependent; the thrower always did.
    // All threads were joined (no terminate) and the helper remains usable.
    std::atomic<int> sum{0};
    croptop::parallel::parallelFor(100, 4, [&](std::size_t index, std::size_t) { sum += static_cast<int>(index); });
    EXPECT_EQ(sum.load(), 4950);
}

TEST(ColorRange, RejectsUnknownTargetsEvenWhenInverted) {
    TileStore image(PixelFormat::RGBA8, 4, 4);
    croptop::selection::ColorRangeSpec spec;
    spec.target = static_cast<croptop::selection::ColorRangeTarget>(200);
    spec.invert = true;
    std::string error;
    EXPECT_FALSE(croptop::selection::colorRange(image, spec, error));
    EXPECT_EQ(error, "unknown Color Range target");
}

}  // namespace
