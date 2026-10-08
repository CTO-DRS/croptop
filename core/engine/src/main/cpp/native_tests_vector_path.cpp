// Tests for the vector geometry + rasterisation core.
#include "vector_path.h"
#include "tile_store.h"
#include "native_tests_stress_gate.h"

#include <gtest/gtest.h>

#include <cmath>
#include <cstring>
#include <random>

namespace {

namespace vp = croptop::vector;
using croptop::TileStore;

double coverage(const TileStore& s, std::uint32_t x, std::uint32_t y) {
    const auto t = s.readTile({static_cast<std::int32_t>(x / 256), static_cast<std::int32_t>(y / 256)});
    if (!t) return 0.0;
    std::uint16_t v = 0;
    std::memcpy(&v, t->data() + (static_cast<std::size_t>(y % 256) * 256 + x % 256) * 2, 2);
    return v / 65535.0;
}

double area(const TileStore& s) {
    double total = 0;
    for (const auto c : s.tileCoordinates()) {
        const auto t = s.readTile(c);
        for (std::size_t i = 0; i < 256U * 256U; ++i) {
            std::uint16_t v = 0;
            std::memcpy(&v, t->data() + i * 2, 2);
            total += v / 65535.0;
        }
    }
    return total;
}

TEST(VectorPath, FillsWithExactAntialiasedArea) {
    const auto rect = vp::rasterize(vp::rectangle(10.25, 20.5, 30.5, 10.0), 100, 100);
    EXPECT_NEAR(coverage(rect, 20, 25), 1.0, 1e-4);
    EXPECT_NEAR(coverage(rect, 10, 25), 0.75, 0.01) << "left edge at x = 10.25";
    EXPECT_NEAR(coverage(rect, 40, 25), 0.75, 0.01) << "right edge at 40.75";
    EXPECT_NEAR(coverage(rect, 20, 20), 0.5, 0.04) << "top edge at 20.5 (16 sub-scanlines)";
    EXPECT_NEAR(area(rect), 305.0, 0.5);
    const auto disc = vp::rasterize(vp::ellipse(150, 150, 60, 60), 300, 300);
    // Flattened chords lie inside the curve (0.1 px tolerance): about 0.1 % of the area.
    EXPECT_NEAR(area(disc), 3.14159265 * 3600, 3.14159265 * 3600 * 0.0015);
    const auto rounded = vp::rasterize(vp::rectangle(10, 10, 200, 100, {20, 20, 20, 20}), 300, 300);
    EXPECT_NEAR(area(rounded), 200 * 100 - (4 - 3.14159265) * 400, 20000 * 0.001);
    EXPECT_TRUE(vp::rasterize(vp::Path{}, 50, 50).tileCoordinates().empty());
}

TEST(VectorPath, FillRulesDifferOnOverlaps) {
    vp::Path nested = vp::rectangle(10, 10, 80, 80);
    nested.append(vp::rectangle(30, 30, 40, 40));  // same orientation
    EXPECT_NEAR(coverage(vp::rasterize(nested, 100, 100, vp::FillRule::NonZero), 50, 50), 1.0, 1e-6);
    EXPECT_NEAR(coverage(vp::rasterize(nested, 100, 100, vp::FillRule::EvenOdd), 50, 50), 0.0, 1e-6);
    // A pentagram drawn as one self-intersecting loop: nonzero fills the centre, even-odd leaves a hole.
    vp::Path star;
    for (int i = 0; i < 5; ++i) {
        const double a = -3.14159265 / 2 + i * 4 * 3.14159265 / 5;
        const vp::Point p{100 + 80 * std::cos(a), 100 + 80 * std::sin(a)};
        i == 0 ? star.moveTo(p) : star.lineTo(p);
    }
    star.close();
    EXPECT_NEAR(coverage(vp::rasterize(star, 200, 200, vp::FillRule::NonZero), 100, 100), 1.0, 1e-6);
    EXPECT_NEAR(coverage(vp::rasterize(star, 200, 200, vp::FillRule::EvenOdd), 100, 100), 0.0, 1e-6);
}

TEST(VectorPath, StrokeCapsJoinsAndMiterLimit) {
    vp::Path line;
    line.moveTo({20, 50}).lineTo({80, 50});
    vp::StrokeStyle style;
    style.width = 4;
    const auto butt = vp::rasterizeStroke(line, style, 100, 100);
    EXPECT_NEAR(coverage(butt, 50, 49), 1.0, 1e-6);
    EXPECT_NEAR(coverage(butt, 50, 52), 0.0, 1e-6) << "width 4 covers rows 48–51";
    EXPECT_NEAR(coverage(butt, 18, 50), 0.0, 1e-6) << "butt ends at the endpoint";
    EXPECT_NEAR(area(butt), 60 * 4, 0.5);
    style.cap = vp::Cap::Square;
    EXPECT_NEAR(area(vp::rasterizeStroke(line, style, 100, 100)), 64 * 4, 0.5);
    style.cap = vp::Cap::Round;
    EXPECT_NEAR(area(vp::rasterizeStroke(line, style, 100, 100)), 60 * 4 + 3.14159265 * 4, 1.0) << "round caps are inscribed polygons";

    vp::Path corner;
    corner.moveTo({20, 20}).lineTo({60, 20}).lineTo({60, 60});
    vp::StrokeStyle thick;
    thick.width = 10;
    thick.join = vp::Join::Miter;
    const auto miter = vp::rasterizeStroke(corner, thick, 100, 100);
    EXPECT_NEAR(coverage(miter, 63, 16), 1.0, 1e-6) << "the miter fills the outer corner";
    thick.join = vp::Join::Bevel;
    EXPECT_LT(coverage(vp::rasterizeStroke(corner, thick, 100, 100), 64, 16), 0.1) << "bevel cuts it";
    thick.join = vp::Join::Round;
    const auto round = vp::rasterizeStroke(corner, thick, 100, 100);
    EXPECT_NEAR(coverage(round, 63, 17), 1.0, 0.02);
    EXPECT_LT(coverage(round, 64, 16), 0.5);

    vp::Path sharp;
    sharp.moveTo({10, 50}).lineTo({90, 45}).lineTo({10, 40});  // very acute turn
    vp::StrokeStyle limited;
    limited.width = 6;
    const auto clipped = vp::rasterizeStroke(sharp, limited, 200, 100);
    EXPECT_EQ(coverage(clipped, 110, 45), 0.0) << "miter limit falls back to bevel";
}

TEST(VectorPath, DashesAndAlignment) {
    vp::Path line;
    line.moveTo({0, 50}).lineTo({100, 50});
    vp::StrokeStyle dashed;
    dashed.width = 2;
    dashed.dash = {10, 10};
    const auto d = vp::rasterizeStroke(line, dashed, 120, 100);
    EXPECT_NEAR(area(d), 5 * 10 * 2, 0.5);
    EXPECT_NEAR(coverage(d, 5, 50), 1.0, 1e-6);
    EXPECT_NEAR(coverage(d, 15, 50), 0.0, 1e-6);
    dashed.dash_offset = 5;
    EXPECT_NEAR(coverage(vp::rasterizeStroke(line, dashed, 120, 100), 2, 50), 1.0, 1e-6);
    EXPECT_NEAR(coverage(vp::rasterizeStroke(line, dashed, 120, 100), 7, 50), 0.0, 1e-6);

    const auto square = vp::rectangle(20, 20, 60, 60);
    vp::StrokeStyle inside;
    inside.width = 4;
    inside.align = vp::Align::Inside;
    const auto in = vp::rasterizeStroke(square, inside, 100, 100);
    EXPECT_NEAR(coverage(in, 22, 50), 1.0, 1e-6);
    EXPECT_NEAR(coverage(in, 18, 50), 0.0, 1e-6);
    inside.align = vp::Align::Outside;
    const auto out = vp::rasterizeStroke(square, inside, 100, 100);
    EXPECT_NEAR(coverage(out, 18, 50), 1.0, 1e-6);
    EXPECT_NEAR(coverage(out, 22, 50), 0.0, 1e-6);
}

TEST(VectorPath, ShapesArrowsAndTransforms) {
    const auto hexagon = vp::rasterize(vp::polygon(100, 100, 50, 6), 200, 200);
    EXPECT_NEAR(area(hexagon), 3 * std::sqrt(3.0) / 2 * 2500, 10);
    const auto star = vp::rasterize(vp::polygon(100, 100, 50, 5, 0.4), 200, 200);
    EXPECT_NEAR(coverage(star, 100, 100), 1.0, 1e-6);
    EXPECT_LT(area(star), area(hexagon));
    const auto arrow = vp::rasterize(vp::arrowLine({10, 50}, {90, 50}, 2, false, true), 100, 100);
    // Head from x = 82 to the tip at 90, ±3 px wide at its base: row 51 is inside it at x = 84, but outside the
    // 2 px shaft (rows 49–50).
    EXPECT_NEAR(coverage(arrow, 84, 51), 1.0, 0.03) << "arrowhead is wider than the shaft (its slanted edge clips a corner)";
    EXPECT_NEAR(coverage(arrow, 40, 51), 0.0, 1e-6);
    const auto scaled = vp::rasterize(vp::rectangle(10, 10, 20, 20).transformed({2, 0, 0, 0, 2, 0, 0, 0, 1}), 100, 100);
    EXPECT_NEAR(area(scaled), 1600, 0.5);
    const auto flat = vp::flatten(vp::ellipse(0, 0, 100, 100), 0.05);
    ASSERT_EQ(flat.size(), 1U);
    EXPECT_TRUE(flat[0].closed);
    for (const auto& p : flat[0].points) EXPECT_NEAR(std::hypot(p.x, p.y), 100.0, 0.06);
}

}  // namespace

namespace {

TEST(VectorPath, SurvivesHostileInput) {
    CROPTOP_REQUIRE_STRESS();
    std::mt19937 random(3);
    std::uniform_real_distribution<double> wild(-1e7, 1e7), near(-50, 350);
    for (int i = 0; i < 100; ++i) {  // keeps the sanitizer run short
        vp::Path p;
        const int n = 2 + static_cast<int>(random() % 12);
        for (int k = 0; k < n; ++k) {
            const vp::Point q{random() % 5 == 0 ? wild(random) : near(random), random() % 7 == 0 ? std::nan("") : near(random)};
            switch (random() % 4) {
                case 0: p.moveTo(q); break;
                case 1: p.lineTo(q); break;
                case 2: p.quadTo({near(random), near(random)}, q); break;
                default: p.cubicTo({near(random), near(random)}, {wild(random), near(random)}, q); break;
            }
            if (random() % 5 == 0) p.close();
        }
        const auto fill = vp::rasterize(p, 300, 300, random() % 2 ? vp::FillRule::NonZero : vp::FillRule::EvenOdd);
        for (const auto c : fill.tileCoordinates()) ASSERT_TRUE(c.x >= 0 && c.y >= 0 && c.x < 2 && c.y < 2);
        vp::StrokeStyle style;
        style.width = 1 + random() % 20;
        style.join = static_cast<vp::Join>(random() % 3);
        style.cap = static_cast<vp::Cap>(random() % 3);
        if (random() % 2) style.dash = {1.0 + random() % 9, 1.0 + random() % 9};
        (void)vp::rasterizeStroke(p, style, 300, 300);
    }
    vp::StrokeStyle zero;
    zero.width = 0;
    EXPECT_TRUE(vp::rasterizeStroke(vp::rectangle(0, 0, 10, 10), zero, 50, 50).tileCoordinates().empty());
}

}  // namespace
