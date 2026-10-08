// Tests for shape-layer records.
#include "shape_layer.h"

#include <gtest/gtest.h>

#include <cmath>

#include "pixel_plane.h"

namespace {

using croptop::PixelFormat;
using croptop::TileStore;
using croptop::vector::Point;
namespace shape = croptop::shape;

std::array<float, 4> at(const TileStore& s, int x, int y) {
    const auto tile = s.readTile({x / 256, y / 256});
    std::array<float, 4> v{};
    if (tile) croptop::paint::detail::readPixel(s.format(), tile->data() + (static_cast<std::size_t>(y % 256) * 256 + x % 256) * s.bytes_per_pixel(), v.data());
    return v;
}

shape::Primitive prim(shape::PrimitiveKind kind, double x, double y, double w, double h) {
    shape::Primitive p;
    p.kind = kind;
    p.x = x;
    p.y = y;
    p.width = w;
    p.height = h;
    return p;
}

shape::Fill red() { return {true, {1, 0, 0, 1}}; }
shape::Stroke blue(double width) {
    shape::Stroke s;
    s.color = {0, 0, 1, 1};
    s.style.width = width;
    return s;
}

TEST(ShapeLayer, PrimitivesBecomeEditableAnchors) {
    shape::Primitive rect = prim(shape::PrimitiveKind::Rectangle, 10, 20, 100, 50);
    auto a = shape::primitiveAnchors(rect, 7);
    ASSERT_EQ(a.size(), 1U);
    EXPECT_TRUE(a[0].closed);
    ASSERT_EQ(a[0].anchors.size(), 4U);
    EXPECT_EQ(a[0].anchors[0].id, 7);
    EXPECT_EQ(a[0].anchors[3].id, 10);
    EXPECT_DOUBLE_EQ(a[0].anchors[0].point.x, 10);
    EXPECT_DOUBLE_EQ(a[0].anchors[0].point.y, 20);
    for (const auto& anchor : a[0].anchors) EXPECT_FALSE(anchor.incoming || anchor.outgoing);

    shape::Primitive ellipse = prim(shape::PrimitiveKind::Ellipse, 0, 0, 200, 100);
    a = shape::primitiveAnchors(ellipse);
    ASSERT_EQ(a[0].anchors.size(), 4U) << "the closing duplicate merges into the first anchor";
    for (const auto& anchor : a[0].anchors) {
        ASSERT_TRUE(anchor.incoming && anchor.outgoing);
        EXPECT_NE(anchor.mode, shape::AnchorMode::Corner) << "ellipse anchors are smooth";
    }
    EXPECT_NEAR(std::hypot(a[0].anchors[0].outgoing->x, a[0].anchors[0].outgoing->y), 50 * 0.5522847, 1e-3);

    shape::Primitive rounded = prim(shape::PrimitiveKind::RoundedRectangle, 0, 0, 100, 60);
    rounded.radii = {10, 10, 10, 10};
    EXPECT_EQ(shape::primitiveAnchors(rounded)[0].anchors.size(), 8U);

    shape::Primitive star = prim(shape::PrimitiveKind::Star, 0, 0, 100, 100);
    star.sides = 5;
    star.inner_ratio = 0.4;
    a = shape::primitiveAnchors(star);
    ASSERT_EQ(a[0].anchors.size(), 10U);
    EXPECT_NEAR(a[0].anchors[0].point.x, 50, 1e-9) << "first point straight up from the centre";
    EXPECT_NEAR(a[0].anchors[0].point.y, 0, 1e-9);
    EXPECT_NEAR(std::hypot(a[0].anchors[1].point.x - 50, a[0].anchors[1].point.y - 50), 20, 1e-9);

    shape::Primitive rotated = prim(shape::PrimitiveKind::Rectangle, 0, 0, 100, 100);
    rotated.rotation = 45;
    a = shape::primitiveAnchors(rotated);
    EXPECT_NEAR(a[0].anchors[0].point.x, 50, 1e-9) << "top-left corner rotates onto the top";
    EXPECT_NEAR(a[0].anchors[0].point.y, 50 - 50 * std::sqrt(2.0), 1e-9);

    shape::Primitive line = prim(shape::PrimitiveKind::Line, 5, 5, 90, 0);
    a = shape::primitiveAnchors(line);
    ASSERT_EQ(a[0].anchors.size(), 2U);
    EXPECT_FALSE(a[0].closed);
}

TEST(ShapeLayer, RendersFillThenStrokeAndRoundTripsGeometry) {
    const auto record = shape::fromPrimitive(1, prim(shape::PrimitiveKind::Rectangle, 40, 30, 120, 80), red(), blue(6));
    ASSERT_TRUE(record.primitive);
    TileStore layer(PixelFormat::RGBA8, 300, 200);
    std::string error;
    ASSERT_TRUE(shape::render(std::span(&record, 1), layer, error)) << error;
    EXPECT_EQ(at(layer, 100, 70), (std::array<float, 4>{1, 0, 0, 1})) << "fill";
    EXPECT_EQ(at(layer, 40, 70), (std::array<float, 4>{0, 0, 1, 1})) << "centred stroke on the edge, drawn over the fill";
    EXPECT_EQ(at(layer, 37, 70)[2], 1.0F) << "stroke reaches 3 px outside";
    EXPECT_EQ(at(layer, 36, 70)[3], 0.0F);
    EXPECT_EQ(at(layer, 30, 70)[3], 0.0F) << "outside is untouched";
    EXPECT_EQ(at(layer, 250, 150)[3], 0.0F);

    // Anchor edits: dragging one corner changes the rendered geometry.
    auto edited = record;
    edited.subpaths[0].anchors[2].point = {220, 150};
    edited.primitive.reset();
    TileStore layer2(PixelFormat::RGBA8, 300, 200);
    ASSERT_TRUE(shape::render(std::span(&edited, 1), layer2, error)) << error;
    EXPECT_GT(at(layer2, 180, 118)[3], 0.9F);
    EXPECT_EQ(at(layer, 180, 118)[3], 0.0F);

    // Bounds: geometry is tight; the stroke grows it.
    const auto b = shape::bounds(record, false), bs = shape::bounds(record, true);
    EXPECT_DOUBLE_EQ(b.x0, 40);
    EXPECT_DOUBLE_EQ(b.x1, 160);
    EXPECT_DOUBLE_EQ(b.y1, 110);
    EXPECT_LT(bs.x0, 37.0);
    const auto eb = shape::bounds(shape::fromPrimitive(2, prim(shape::PrimitiveKind::Ellipse, 0, 0, 100, 60), red(), blue(1)), false);
    EXPECT_NEAR(eb.x1, 100, 0.05) << "tight on curves, not the control points";
    EXPECT_NEAR(eb.y1, 60, 0.05);
}

TEST(ShapeLayer, OpenPenPathsCurvesAndArrows) {
    // A pen path: two anchors with smooth handles, open, stroke only.
    shape::ShapeRecord pen;
    pen.id = 3;
    pen.fill.enabled = false;
    pen.stroke = blue(4);
    shape::Subpath s;
    s.anchors.push_back({1, {20, 100}, std::nullopt, Point{0, -60}, shape::AnchorMode::Smooth});
    s.anchors.push_back({2, {180, 100}, Point{0, -60}, std::nullopt, shape::AnchorMode::Smooth});
    pen.subpaths = {s};
    TileStore layer(PixelFormat::RGBA8, 200, 140);
    std::string error;
    ASSERT_TRUE(shape::render(std::span(&pen, 1), layer, error)) << error;
    // The cubic's midpoint is at y = 100 − 0.75·60 = 55.
    EXPECT_GT(at(layer, 100, 55)[3], 0.9F);
    EXPECT_EQ(at(layer, 100, 100)[3], 0.0F) << "open and unfilled: no chord";
    const auto path = shape::toPath(pen);
    EXPECT_EQ(path.verbs().size(), 2U);

    // Line primitive with an arrowhead at the end.
    auto arrow = shape::fromPrimitive(4, prim(shape::PrimitiveKind::Line, 20, 70, 160, 0), {false, {}}, blue(4));
    arrow.stroke.arrow_end = true;
    TileStore layer2(PixelFormat::RGBA8, 200, 140);
    ASSERT_TRUE(shape::render(std::span(&arrow, 1), layer2, error)) << error;
    EXPECT_GT(at(layer2, 168, 73)[3], 0.5F) << "the head is wider than the shaft";
    EXPECT_EQ(at(layer2, 100, 73)[3], 0.0F) << "the shaft is 4 px wide";
    EXPECT_EQ(at(layer2, 60, 74)[3], 0.0F);
}

TEST(ShapeLayer, HitTestingPrefersHandlesThenAnchorsSegmentsInteriors) {
    const auto lower = shape::fromPrimitive(1, prim(shape::PrimitiveKind::Rectangle, 0, 0, 100, 100), red(), blue(2));
    auto upper = shape::fromPrimitive(2, prim(shape::PrimitiveKind::Ellipse, 60, 60, 80, 80), red(), blue(2));
    const std::vector<shape::ShapeRecord> records{lower, upper};
    auto hit = shape::hitTest(records, {101, 1}, 4);
    EXPECT_EQ(hit.part, shape::HitPart::Anchor);
    EXPECT_EQ(hit.record, 0U);
    EXPECT_EQ(hit.anchor, 1U);
    hit = shape::hitTest(records, {50, 1.5}, 2);
    EXPECT_EQ(hit.part, shape::HitPart::Segment);
    EXPECT_EQ(hit.anchor, 0U);
    // The ellipse's right anchor (140, 100) has an outgoing handle pointing down.
    const auto& right = records[1].subpaths[0].anchors[0];
    ASSERT_TRUE(right.outgoing);
    hit = shape::hitTest(records, {right.point.x + right.outgoing->x, right.point.y + right.outgoing->y + 1}, 3);
    EXPECT_EQ(hit.part, shape::HitPart::OutgoingHandle);
    EXPECT_EQ(hit.record, 1U);
    hit = shape::hitTest(records, {90, 90}, 1);
    EXPECT_EQ(hit.part, shape::HitPart::Interior);
    EXPECT_EQ(hit.record, 1U) << "the upper shape wins where both are filled";
    hit = shape::hitTest(records, {30, 30}, 1);
    EXPECT_EQ(hit.part, shape::HitPart::Interior);
    EXPECT_EQ(hit.record, 0U);
    EXPECT_EQ(shape::hitTest(records, {190, 10}, 3).part, shape::HitPart::None);
}

TEST(ShapeLayer, Validation) {
    auto record = shape::fromPrimitive(1, prim(shape::PrimitiveKind::Rectangle, 0, 0, 10, 10), red(), blue(1));
    std::string error;
    EXPECT_TRUE(shape::validate(record, error));
    auto bad = record;
    bad.subpaths[0].anchors[0].point.x = std::nan("");
    EXPECT_FALSE(shape::validate(bad, error));
    bad = record;
    bad.stroke.style.width = -1;
    EXPECT_FALSE(shape::validate(bad, error));
    bad = record;
    bad.fill.color[0] = 2;
    EXPECT_FALSE(shape::validate(bad, error));
    bad = record;
    bad.stroke.style.dash = {4, 0};
    EXPECT_FALSE(shape::validate(bad, error));
    TileStore mask(PixelFormat::A16, 10, 10);
    EXPECT_FALSE(shape::render(std::span(&record, 1), mask, error));
}

}  // namespace
