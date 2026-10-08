// Tests for Warp Text and type on a path.
#include "text_effects.h"
#include "truetype_font.h"

#include <gtest/gtest.h>

#include <cmath>
#include <fstream>
#include <iterator>

namespace {

namespace text = croptop::text;
namespace vec = croptop::vector;

struct Box {
    double x0 = 1e9, y0 = 1e9, x1 = -1e9, y1 = -1e9;
};
Box boxOf(const vec::Path& p) {
    Box b;
    for (const auto& poly : vec::flatten(p, 0.5))
        for (const auto& q : poly.points) {
            EXPECT_TRUE(std::isfinite(q.x) && std::isfinite(q.y));
            b.x0 = std::min(b.x0, q.x), b.y0 = std::min(b.y0, q.y), b.x1 = std::max(b.x1, q.x), b.y1 = std::max(b.y1, q.y);
        }
    return b;
}

/** Top edge height (min y) of the path's points within a vertical band. */
double topIn(const vec::Path& p, double xa, double xb) {
    double top = 1e9;
    for (const auto& poly : vec::flatten(p, 0.5))
        for (const auto& q : poly.points)
            if (q.x >= xa && q.x <= xb) top = std::min(top, q.y);
    return top;
}

vec::Path bar() {  // a wide rectangle, densely subdivided so the envelope has points to bend
    vec::Path p;
    p.moveTo({0, 0});
    for (int x = 10; x <= 400; x += 10) p.lineTo({static_cast<double>(x), 0});
    for (int x = 400; x >= 0; x -= 10) p.lineTo({static_cast<double>(x), 100});
    p.close();
    return p;
}

TEST(TextEffects, WarpStylesBendAsExpected) {
    const auto rect = bar();
    text::WarpSpec none;
    EXPECT_EQ(text::warp(rect, none).points().size(), rect.points().size()) << "None is the identity";
    text::WarpSpec flat{text::WarpStyle::Arc, 0.0F};
    const auto same = boxOf(text::warp(rect, flat));
    EXPECT_NEAR(same.x0, 0, 1e-6);
    EXPECT_NEAR(same.y1, 100, 1e-6);

    text::WarpSpec arc{text::WarpStyle::Arc, 0.5F};
    const auto arced = text::warp(rect, arc);
    EXPECT_LT(topIn(arced, 190, 210), topIn(arced, 0, 20) - 10) << "an arc lifts the middle above the ends";
    text::WarpSpec bulge{text::WarpStyle::Bulge, 0.5F};
    const auto bulged = boxOf(text::warp(rect, bulge));
    EXPECT_GT(bulged.y1 - bulged.y0, 120) << "bulge grows the middle";
    text::WarpSpec rise{text::WarpStyle::Rise, 0.5F};
    const auto risen = text::warp(rect, rise);
    EXPECT_LT(topIn(risen, 380, 400), topIn(risen, 0, 20) - 20) << "rise lifts the right end";
    for (int style = 1; style <= static_cast<int>(text::WarpStyle::Twist); ++style) {
        for (const float bend : {-1.0F, -0.3F, 0.7F, 1.0F}) {
            text::WarpSpec spec{static_cast<text::WarpStyle>(style), bend, 0.4F, -0.4F, style % 2 == 0};
            EXPECT_FALSE(text::warp(rect, spec).empty());
            (void)boxOf(text::warp(rect, spec));  // asserts every point is finite
        }
    }
    text::WarpSpec nan_spec{text::WarpStyle::Wave, std::nanf("")};
    (void)boxOf(text::warp(rect, nan_spec));
}

TEST(TextEffects, TypeOnAPathAndFill) {
    std::ifstream file("/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf", std::ios::binary);
    if (!file) GTEST_SKIP() << "DejaVuSans.ttf not installed";
    const std::vector<std::uint8_t> bytes((std::istreambuf_iterator<char>(file)), std::istreambuf_iterator<char>());
    std::string error;
    const auto font = text::TrueTypeFont::load(bytes, error);
    ASSERT_NE(font, nullptr) << error;
    const text::TrueTypeShaper shaper(font);
    text::ParagraphStyle style;
    style.size_px = 24;
    const auto layout = text::layout("AROUND THE CIRCLE", style, 0, shaper);
    const auto circle = vec::ellipse(200, 200, 120, 120);
    const auto on_path = text::textOnPath(layout, *font, circle, 0);
    ASSERT_FALSE(on_path.empty());
    // Every glyph point stays within a ring around the circle (baseline on the path, glyphs outside it or in).
    for (const auto& poly : vec::flatten(on_path, 0.5))
        for (const auto& q : poly.points) {
            const double r = std::hypot(q.x - 200, q.y - 200);
            EXPECT_GT(r, 120 - 30);
            EXPECT_LT(r, 120 + 30);
        }
    // Glyphs past the path's end are dropped.
    EXPECT_TRUE(text::textOnPath(layout, *font, circle, 10000).empty());
    croptop::TileStore layer(croptop::PixelFormat::RGBA8, 400, 400);
    ASSERT_TRUE(text::fillPath(layer, on_path, {0, 0, 0, 1}, error)) << error;
    EXPECT_GT(layer.tile_count(), 0U);
    // Warped text renders too.
    text::WarpSpec flag{text::WarpStyle::Flag, 0.6F};
    const auto warped = text::warp(text::layoutOutlines(layout, *font, 20, 20), flag);
    ASSERT_TRUE(text::fillPath(layer, warped, {1, 0, 0, 1}, error)) << error;
    EXPECT_FALSE(text::fillPath(layer, warped, {1.5F, 0, 0, 1}, error));
}

}  // namespace
