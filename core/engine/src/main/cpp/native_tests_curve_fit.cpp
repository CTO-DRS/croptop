// Tests for polygon → cubic re-fitting (path ops).
#include "curve_fit.h"
#include "vector_boolean.h"

#include <gtest/gtest.h>

#include <algorithm>
#include <cmath>
#include <string>

namespace {

using namespace croptop::vector;

std::size_t count(const Path& p, Verb v) { return static_cast<std::size_t>(std::count(p.verbs().begin(), p.verbs().end(), v)); }

// Largest distance from any vertex of `a` to the polyline of `b`.
double deviation(const Path& a, const Path& b) {
    const auto pb = flatten(b, 0.02);
    double worst = 0;
    for (const auto& pa : flatten(a, 0.02))
        for (const auto& q : pa.points) {
            double best = 1e18;
            for (const auto& poly : pb)
                for (std::size_t i = 0; i + 1 < poly.points.size() + (poly.closed ? 1 : 0); ++i) {
                    const auto& s = poly.points[i];
                    const auto& e = poly.points[(i + 1) % poly.points.size()];
                    const double dx = e.x - s.x, dy = e.y - s.y, l2 = dx * dx + dy * dy;
                    const double t = l2 > 0 ? std::clamp(((q.x - s.x) * dx + (q.y - s.y) * dy) / l2, 0.0, 1.0) : 0.0;
                    best = std::min(best, std::hypot(q.x - (s.x + t * dx), q.y - (s.y + t * dy)));
                }
            worst = std::max(worst, best);
        }
    return worst;
}

}  // namespace

TEST(CurveFit, CirclePolygonBecomesAFewCubics) {
    const Path circle = ellipse(100, 100, 50, 50);
    std::string error;
    const auto polygon = simplify(circle, FillRule::NonZero, error, 0.05);
    ASSERT_TRUE(polygon.has_value()) << error;
    ASSERT_GT(count(*polygon, Verb::Line), 60U);
    const auto fitted = refit(*polygon);
    EXPECT_LE(count(fitted, Verb::Cubic), 12U);
    EXPECT_EQ(count(fitted, Verb::Line), 0U);
    EXPECT_LT(deviation(*polygon, fitted), 0.3);
    EXPECT_NEAR(netArea(fitted), netArea(*polygon), netArea(*polygon) * 0.005);
}

TEST(CurveFit, RectangleKeepsCornersAndLines) {
    const auto fitted = refit(rectangle(10, 10, 80, 40));
    EXPECT_EQ(count(fitted, Verb::Line), 4U);
    EXPECT_EQ(count(fitted, Verb::Cubic), 0U);
    EXPECT_LT(deviation(rectangle(10, 10, 80, 40), fitted), 1e-6);
}

TEST(CurveFit, UnionOfCirclesKeepsCuspsSharpAndCurvesSmooth) {
    std::string error;
    const auto uni = combine(ellipse(80, 100, 50, 50), FillRule::NonZero, ellipse(140, 100, 50, 50), FillRule::NonZero, BoolOp::Unite, error, 0.05);
    ASSERT_TRUE(uni.has_value()) << error;
    const auto fitted = refit(*uni);
    EXPECT_LT(count(fitted, Verb::Cubic) + count(fitted, Verb::Line), count(*uni, Verb::Line) / 4);
    EXPECT_LT(deviation(*uni, fitted), 0.3);
    EXPECT_NEAR(netArea(fitted), netArea(*uni), netArea(*uni) * 0.005);
    // The two circles meet in two cusps: at least two sub-curves join at a corner (more than one contour break is not expected).
    EXPECT_GE(count(fitted, Verb::Cubic), 4U);
}

TEST(CurveFit, HoleAndOpenInputsSurvive) {
    std::string error;
    Path ring = ellipse(100, 100, 60, 60);
    ring.append(ellipse(100, 100, 30, 30));
    const auto poly = simplify(ring, FillRule::EvenOdd, error, 0.05);
    ASSERT_TRUE(poly.has_value()) << error;
    const auto fitted = refit(*poly);
    EXPECT_EQ(count(fitted, Verb::Move), 2U);
    EXPECT_NEAR(netArea(fitted), netArea(*poly), std::abs(netArea(*poly)) * 0.01);
    Path open;
    open.moveTo({0, 0}).lineTo({10, 5}).lineTo({20, 0});
    const auto kept = refit(open);
    EXPECT_EQ(count(kept, Verb::Line), 2U);
    EXPECT_EQ(count(kept, Verb::Close), 0U);
}
