// Tests for path booleans and offsetting.
#include "vector_boolean.h"

#include <gtest/gtest.h>

#include <cmath>
#include <cstring>
#include <numbers>
#include <string>

namespace {

namespace vec = croptop::vector;

double absArea(const vec::Path& p) { return std::abs(vec::netArea(p)); }

TEST(VectorBoolean, TwoOverlappingSquaresGiveTheExpectedAreas) {
    const auto a = vec::rectangle(0, 0, 10, 10), b = vec::rectangle(5, 5, 10, 10);
    std::string error;
    const auto unite = vec::combine(a, vec::FillRule::NonZero, b, vec::FillRule::NonZero, vec::BoolOp::Unite, error);
    const auto subtract = vec::combine(a, vec::FillRule::NonZero, b, vec::FillRule::NonZero, vec::BoolOp::Subtract, error);
    const auto intersect = vec::combine(a, vec::FillRule::NonZero, b, vec::FillRule::NonZero, vec::BoolOp::Intersect, error);
    const auto exclude = vec::combine(a, vec::FillRule::NonZero, b, vec::FillRule::NonZero, vec::BoolOp::Exclude, error);
    ASSERT_TRUE(unite && subtract && intersect && exclude) << error;
    EXPECT_NEAR(absArea(*unite), 175.0, 1e-6);
    EXPECT_NEAR(absArea(*subtract), 75.0, 1e-6);
    EXPECT_NEAR(absArea(*intersect), 25.0, 1e-6);
    EXPECT_NEAR(absArea(*exclude), 150.0, 1e-6);
}

TEST(VectorBoolean, SubtractingAnInnerShapeLeavesAHole) {
    std::string error;
    const auto ring = vec::combine(vec::rectangle(0, 0, 20, 20), vec::FillRule::NonZero, vec::rectangle(5, 5, 10, 10), vec::FillRule::NonZero,
                                   vec::BoolOp::Subtract, error);
    ASSERT_TRUE(ring) << error;
    EXPECT_NEAR(vec::netArea(*ring), 300.0, 1e-6) << "outer 400 minus hole 100";
    int contours = 0;
    for (const auto v : ring->verbs()) contours += v == vec::Verb::Close ? 1 : 0;
    EXPECT_EQ(contours, 2);
    // Rasterised with NonZero the hole is empty and the ring is filled.
    const auto raster = vec::rasterize(*ring, 24, 24, vec::FillRule::NonZero);
    const auto tile = raster.readTile({0, 0});
    ASSERT_NE(tile, nullptr);
    std::uint16_t hole = 0, body = 0;
    std::memcpy(&hole, tile->data() + (static_cast<std::size_t>(10) * croptop::kTileEdge + 10) * 2, 2);
    std::memcpy(&body, tile->data() + (static_cast<std::size_t>(2) * croptop::kTileEdge + 2) * 2, 2);
    EXPECT_EQ(hole, 0);
    EXPECT_EQ(body, 65535);
}

TEST(VectorBoolean, EllipsesAndCurvesAreFlattenedWithinTolerance) {
    std::string error;
    const auto lens = vec::combine(vec::ellipse(0, 0, 10, 10), vec::FillRule::NonZero, vec::ellipse(10, 0, 10, 10), vec::FillRule::NonZero,
                                   vec::BoolOp::Intersect, error, 0.01);
    ASSERT_TRUE(lens) << error;
    // Two unit-ish circles of radius r with centres r apart overlap in r²·(2π/3 − √3/2).
    const double expected = 100.0 * (2.0 * std::numbers::pi / 3.0 - std::sqrt(3.0) / 2.0);
    EXPECT_NEAR(absArea(*lens), expected, expected * 0.002);
}

TEST(VectorBoolean, OperandsCanUseDifferentFillRules) {
    // One path made of two overlapping squares: under even-odd their overlap is a hole (area 150), under nonzero it is filled.
    vec::Path both = vec::rectangle(0, 0, 10, 10);
    both.append(vec::rectangle(5, 5, 10, 10));
    std::string error;
    const auto holed = vec::simplify(both, vec::FillRule::EvenOdd, error);
    ASSERT_TRUE(holed) << error;
    EXPECT_NEAR(absArea(*holed), 150.0, 1e-6);
    // Uniting the even-odd subject with a nonzero square over the overlap fills the hole again.
    const auto filled = vec::combine(both, vec::FillRule::EvenOdd, vec::rectangle(5, 5, 5, 5), vec::FillRule::NonZero, vec::BoolOp::Unite, error);
    ASSERT_TRUE(filled) << error;
    EXPECT_NEAR(absArea(*filled), 175.0, 1e-6);
}

TEST(VectorBoolean, SimplifyResolvesSelfOverlapAndOffsetGrowsAndShrinks) {
    std::string error;
    vec::Path both = vec::rectangle(0, 0, 10, 10);
    both.append(vec::rectangle(5, 5, 10, 10));
    const auto merged = vec::simplify(both, vec::FillRule::NonZero, error);
    ASSERT_TRUE(merged) << error;
    EXPECT_NEAR(absArea(*merged), 175.0, 1e-6);

    const auto square = vec::rectangle(0, 0, 10, 10);
    const auto grown = vec::offset(square, vec::FillRule::NonZero, 2.0, vec::Join::Miter, 4.0, error);
    const auto shrunk = vec::offset(square, vec::FillRule::NonZero, -2.0, vec::Join::Miter, 4.0, error);
    ASSERT_TRUE(grown && shrunk) << error;
    EXPECT_NEAR(absArea(*grown), 14.0 * 14.0, 1e-6);
    EXPECT_NEAR(absArea(*shrunk), 6.0 * 6.0, 1e-6);
    const auto rounded = vec::offset(square, vec::FillRule::NonZero, 2.0, vec::Join::Round, 2.0, error, 0.01);
    ASSERT_TRUE(rounded) << error;
    EXPECT_NEAR(absArea(*rounded), 100.0 + 4 * 10.0 * 2.0 + std::numbers::pi * 4.0, 0.2);
}

TEST(VectorBoolean, RejectsNonFiniteInputAndBadParameters) {
    std::string error;
    vec::Path bad;
    bad.moveTo({0, 0}).lineTo({std::nan(""), 1}).lineTo({5, 5}).close();
    EXPECT_FALSE(vec::combine(bad, vec::FillRule::NonZero, vec::rectangle(0, 0, 1, 1), vec::FillRule::NonZero, vec::BoolOp::Unite, error));
    EXPECT_FALSE(error.empty());
    EXPECT_FALSE(vec::combine(vec::rectangle(0, 0, 1, 1), vec::FillRule::NonZero, vec::rectangle(0, 0, 1, 1), vec::FillRule::NonZero,
                              vec::BoolOp::Unite, error, 0.0));
    EXPECT_FALSE(vec::offset(vec::rectangle(0, 0, 1, 1), vec::FillRule::NonZero, std::nan(""), vec::Join::Miter, 4.0, error));
    // Empty input is fine and gives an empty path.
    const auto empty = vec::combine({}, vec::FillRule::NonZero, {}, vec::FillRule::NonZero, vec::BoolOp::Unite, error);
    ASSERT_TRUE(empty);
    EXPECT_TRUE(empty->empty());
}

}  // namespace
