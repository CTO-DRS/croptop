// Golden tests for the geometric selection rasterizers: rectangles, ellipses and polygons, anti-aliased and hard, pinned by a
// hash of every stored tile (order-independent) plus analytic checks of area, symmetry and edge coverage, so an unintended change to any edge pixel fails.
#include "selection_mask.h"

#include <gtest/gtest.h>

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <vector>

namespace {

using namespace croptop;

std::uint64_t fingerprint(const TileStore& s) {
    auto coordinates = s.tileCoordinates();
    std::sort(coordinates.begin(), coordinates.end(), [](auto a, auto b) { return a.y != b.y ? a.y < b.y : a.x < b.x; });
    std::uint64_t h = 1469598103934665603ULL;
    for (const auto c : coordinates) {
        for (const std::uint8_t byte : *s.readTile(c)) {
            h ^= byte;
            h *= 1099511628211ULL;
        }
        h ^= static_cast<std::uint64_t>(static_cast<std::uint32_t>(c.x) * 73856093U ^ static_cast<std::uint32_t>(c.y) * 19349663U);
        h *= 1099511628211ULL;
    }
    return h;
}

double area(const TileStore& s) {
    double sum = 0;
    for (std::int64_t y = 0; y < s.height(); ++y)
        for (std::int64_t x = 0; x < s.width(); ++x) sum += selection::coverageAt(s, x, y) / 65535.0;
    return sum;
}

constexpr std::uint32_t kW = 300, kH = 200;

}  // namespace

TEST(SelectionGolden, RectangleFingerprintsAndArea) {
    const selection::RectF rect{10.25, 20.5, 150.75, 90.1};
    const auto aa = selection::rasterizeRect(kW, kH, rect, true);
    const auto hard = selection::rasterizeRect(kW, kH, rect, false);
    EXPECT_EQ(fingerprint(aa), 5042105199367394905ULL);
    EXPECT_EQ(fingerprint(hard), 13371102883825542997ULL);
    EXPECT_NEAR(area(aa), (150.75 - 10.25) * (90.1 - 20.5), 0.5);  // anti-aliasing preserves the exact area
    EXPECT_EQ(selection::coverageAt(aa, 80, 50), 65535);
    EXPECT_EQ(selection::coverageAt(aa, 5, 50), 0);
    const auto edge = selection::coverageAt(aa, 10, 50);  // covers 0.75 of the pixel column
    EXPECT_NEAR(edge / 65535.0, 0.75, 0.01);
}

TEST(SelectionGolden, EllipseFingerprintsAreaAndSymmetry) {
    const selection::RectF bounds{40.5, 30.2, 260.3, 170.9};
    const auto aa = selection::rasterizeEllipse(kW, kH, bounds, true);
    const auto hard = selection::rasterizeEllipse(kW, kH, bounds, false);
    EXPECT_EQ(fingerprint(aa), 232188809491566706ULL);
    EXPECT_EQ(fingerprint(hard), 2958303052082772248ULL);
    const double expected = M_PI * (bounds.right - bounds.left) / 2.0 * (bounds.bottom - bounds.top) / 2.0;
    EXPECT_NEAR(area(aa), expected, expected * 0.002);
    EXPECT_EQ(selection::coverageAt(aa, 150, 100), 65535);
    EXPECT_EQ(selection::coverageAt(aa, 41, 31), 0);  // the corner of the bounding box is outside the ellipse
}

TEST(SelectionGolden, PolygonFingerprintsAndAreas) {
    const std::vector<selection::Point> triangle{{20, 20}, {280, 60}, {150, 180}};
    const auto tri = selection::rasterizePolygon(kW, kH, triangle, true);
    EXPECT_EQ(fingerprint(tri), 3612146349907038392ULL);
    const double shoelace = std::abs((280 - 20) * (180 - 20) - (150 - 20) * (60 - 20)) / 2.0;
    EXPECT_NEAR(area(tri), shoelace, shoelace * 0.002);

    std::vector<selection::Point> star;
    for (int i = 0; i < 10; ++i) {
        const double a = i * 3.14159265358979 / 5 - 1.5707963, r = (i % 2) ? 40 : 90;  // literals pinned with the fingerprint
        star.push_back({150 + r * std::cos(a), 100 + r * std::sin(a)});
    }
    const auto s = selection::rasterizePolygon(kW, kH, star, true);
    EXPECT_EQ(fingerprint(s), 1561533366525211555ULL);
    EXPECT_EQ(selection::coverageAt(s, 150, 100), 65535);   // the centre is inside the star
    EXPECT_EQ(selection::coverageAt(s, 150 + 60, 100 + 60), 0);  // between two arms, outside
}

TEST(SelectionGolden, DegenerateShapesAreEmpty) {
    EXPECT_TRUE(selection::isEmpty(selection::rasterizePolygon(kW, kH, std::vector<selection::Point>{{1, 1}, {5, 5}}, true)));
    EXPECT_TRUE(selection::isEmpty(selection::rasterizeRect(kW, kH, {50, 50, 50, 90}, true)));
    EXPECT_TRUE(selection::isEmpty(selection::rasterizeEllipse(kW, kH, {10, 10, 10, 10}, true)));
}
