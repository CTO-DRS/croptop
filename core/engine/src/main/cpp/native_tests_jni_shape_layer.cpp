// Shape-record wire contract, malformed-input and direct-select bridge tests.
#include "jni_shape_layer.h"

#include <gtest/gtest.h>

#include <array>
#include <bit>
#include <cmath>
#include <cstdint>
#include <limits>
#include <string>
#include <vector>

namespace {
using namespace croptop::shape;
using namespace croptop::shape::bridge;
using croptop::vector::Point;

void writeLittleEndianDouble(std::vector<std::uint8_t>& bytes, std::size_t offset, double value) {
    const auto bits = std::bit_cast<std::uint64_t>(value);
    for (std::size_t i = 0; i < sizeof(bits); ++i) bytes[offset + i] = static_cast<std::uint8_t>(bits >> (i * 8U));
}

Record sampleRecord() {
    Primitive primitive;
    primitive.kind = PrimitiveKind::Star;
    primitive.x = 12.25;
    primitive.y = 30.5;
    primitive.width = 80.0;
    primitive.height = 64.0;
    primitive.sides = 7;
    primitive.inner_ratio = 0.37;
    primitive.rotation = -22.5;
    primitive.radii = {1, 2, 3, 4};
    Fill fill{true, {0.1F, 0.2F, 0.3F, 0.75F}};
    Stroke stroke;
    stroke.enabled = true;
    stroke.color = {0.8F, 0.7F, 0.6F, 0.9F};
    stroke.style.width = 3.25;
    stroke.style.join = croptop::vector::Join::Bevel;
    stroke.style.cap = croptop::vector::Cap::Round;
    stroke.style.align = croptop::vector::Align::Outside;
    stroke.style.miter_limit = 6.5;
    stroke.style.dash = {8.0, 2.5};
    stroke.style.dash_offset = 1.25;
    stroke.arrow_start = true;
    stroke.arrow_end = false;
    stroke.arrow_length = 6.0;
    stroke.arrow_width = 2.0;
    Record record;
    record.name = "Étoile ✦";
    record.shape = fromPrimitive(420, primitive, fill, stroke, 900);
    record.shape.fill_rule = croptop::vector::FillRule::EvenOdd;
    return record;
}

void expectSameRecord(const Record& a, const Record& b) {
    EXPECT_EQ(a.name, b.name);
    EXPECT_EQ(a.shape.id, b.shape.id);
    ASSERT_EQ(a.shape.fill_rule, b.shape.fill_rule);
    EXPECT_EQ(a.shape.fill.enabled, b.shape.fill.enabled);
    EXPECT_EQ(a.shape.fill.color, b.shape.fill.color);
    EXPECT_EQ(a.shape.stroke.enabled, b.shape.stroke.enabled);
    EXPECT_EQ(a.shape.stroke.color, b.shape.stroke.color);
    EXPECT_DOUBLE_EQ(a.shape.stroke.style.width, b.shape.stroke.style.width);
    EXPECT_EQ(a.shape.stroke.style.join, b.shape.stroke.style.join);
    EXPECT_EQ(a.shape.stroke.style.cap, b.shape.stroke.style.cap);
    EXPECT_EQ(a.shape.stroke.style.align, b.shape.stroke.style.align);
    EXPECT_DOUBLE_EQ(a.shape.stroke.style.miter_limit, b.shape.stroke.style.miter_limit);
    EXPECT_EQ(a.shape.stroke.style.dash, b.shape.stroke.style.dash);
    EXPECT_DOUBLE_EQ(a.shape.stroke.style.dash_offset, b.shape.stroke.style.dash_offset);
    EXPECT_EQ(a.shape.stroke.arrow_start, b.shape.stroke.arrow_start);
    EXPECT_EQ(a.shape.stroke.arrow_end, b.shape.stroke.arrow_end);
    EXPECT_DOUBLE_EQ(a.shape.stroke.arrow_length, b.shape.stroke.arrow_length);
    EXPECT_DOUBLE_EQ(a.shape.stroke.arrow_width, b.shape.stroke.arrow_width);
    ASSERT_EQ(a.shape.primitive.has_value(), b.shape.primitive.has_value());
    ASSERT_EQ(a.shape.subpaths.size(), b.shape.subpaths.size());
    if (a.shape.primitive) {
        const auto& x = *a.shape.primitive;
        const auto& y = *b.shape.primitive;
        EXPECT_EQ(x.kind, y.kind);
        EXPECT_DOUBLE_EQ(x.x, y.x);
        EXPECT_DOUBLE_EQ(x.y, y.y);
        EXPECT_DOUBLE_EQ(x.width, y.width);
        EXPECT_DOUBLE_EQ(x.height, y.height);
        EXPECT_DOUBLE_EQ(x.radii.top_left, y.radii.top_left);
        EXPECT_DOUBLE_EQ(x.radii.top_right, y.radii.top_right);
        EXPECT_DOUBLE_EQ(x.radii.bottom_right, y.radii.bottom_right);
        EXPECT_DOUBLE_EQ(x.radii.bottom_left, y.radii.bottom_left);
        EXPECT_EQ(x.sides, y.sides);
        EXPECT_DOUBLE_EQ(x.inner_ratio, y.inner_ratio);
        EXPECT_DOUBLE_EQ(x.rotation, y.rotation);
    }
    for (std::size_t si = 0; si < a.shape.subpaths.size(); ++si) {
        const auto& x = a.shape.subpaths[si];
        const auto& y = b.shape.subpaths[si];
        EXPECT_EQ(x.closed, y.closed);
        ASSERT_EQ(x.anchors.size(), y.anchors.size());
        for (std::size_t ai = 0; ai < x.anchors.size(); ++ai) {
            EXPECT_EQ(x.anchors[ai].id, y.anchors[ai].id);
            EXPECT_DOUBLE_EQ(x.anchors[ai].point.x, y.anchors[ai].point.x);
            EXPECT_DOUBLE_EQ(x.anchors[ai].point.y, y.anchors[ai].point.y);
            EXPECT_EQ(x.anchors[ai].mode, y.anchors[ai].mode);
            EXPECT_EQ(x.anchors[ai].incoming.has_value(), y.anchors[ai].incoming.has_value());
            EXPECT_EQ(x.anchors[ai].outgoing.has_value(), y.anchors[ai].outgoing.has_value());
            if (x.anchors[ai].incoming) {
                EXPECT_DOUBLE_EQ(x.anchors[ai].incoming->x, y.anchors[ai].incoming->x);
                EXPECT_DOUBLE_EQ(x.anchors[ai].incoming->y, y.anchors[ai].incoming->y);
            }
            if (x.anchors[ai].outgoing) {
                EXPECT_DOUBLE_EQ(x.anchors[ai].outgoing->x, y.anchors[ai].outgoing->x);
                EXPECT_DOUBLE_EQ(x.anchors[ai].outgoing->y, y.anchors[ai].outgoing->y);
            }
        }
    }
}

TEST(JniShapeLayerCodec, RoundTripsAllRecordFieldsAndIsDeterministic) {
    const auto source = sampleRecord();
    std::vector<std::uint8_t> first, second;
    std::string error;
    ASSERT_TRUE(encodeRecord(source, first, error)) << error;
    ASSERT_TRUE(encodeRecord(source, second, error)) << error;
    EXPECT_EQ(first, second);
    Record decoded;
    ASSERT_TRUE(decodeRecord(first, decoded, error)) << error;
    expectSameRecord(source, decoded);
}

TEST(JniShapeLayerCodec, RoundTripsRelativeHandlesAndOpenSubpaths) {
    Record source;
    source.name = "Pen draft";
    source.shape.id = 17;
    Subpath open;
    open.closed = false;
    open.anchors = {Anchor{5, {10, 20}, Point{-2, 3}, Point{4, 0}, AnchorMode::Symmetric},
                    Anchor{6, {30, 40}, std::nullopt, Point{-5, -5}, AnchorMode::Smooth}};
    source.shape.subpaths.push_back(open);
    source.shape.fill.enabled = false;
    source.shape.stroke.enabled = true;
    std::vector<std::uint8_t> bytes;
    std::string error;
    ASSERT_TRUE(encodeRecord(source, bytes, error)) << error;
    Record decoded;
    ASSERT_TRUE(decodeRecord(bytes, decoded, error)) << error;
    expectSameRecord(source, decoded);
}

TEST(JniShapeLayerCodec, BatchRoundTripPreservesOrderAndDirectSelectIndices) {
    Record first = sampleRecord();
    first.shape.primitive.reset();
    first.shape.subpaths = {Subpath{{Anchor{1, {10, 10}, std::nullopt, std::nullopt, AnchorMode::Corner},
                                     Anchor{2, {30, 10}, std::nullopt, std::nullopt, AnchorMode::Corner},
                                     Anchor{3, {30, 30}, std::nullopt, std::nullopt, AnchorMode::Corner},
                                     Anchor{4, {10, 30}, std::nullopt, std::nullopt, AnchorMode::Corner}}, true}};
    Record top = first;
    top.name = "top";
    top.shape.id = 421;
    top.shape.subpaths[0].anchors = {Anchor{10, {15, 15}, std::nullopt, std::nullopt, AnchorMode::Corner},
                                     Anchor{11, {25, 15}, std::nullopt, std::nullopt, AnchorMode::Corner},
                                     Anchor{12, {25, 25}, std::nullopt, std::nullopt, AnchorMode::Corner},
                                     Anchor{13, {15, 25}, std::nullopt, std::nullopt, AnchorMode::Corner}};
    std::vector<std::uint8_t> bytes;
    std::string error;
    ASSERT_TRUE(encodeBatch(std::array<Record, 2>{first, top}, bytes, error)) << error;
    std::vector<Record> decoded;
    ASSERT_TRUE(decodeBatch(bytes, decoded, error)) << error;
    ASSERT_EQ(decoded.size(), 2U);
    expectSameRecord(first, decoded[0]);
    expectSameRecord(top, decoded[1]);
    Hit hit;
    ASSERT_TRUE(hitTestBatch(bytes, {20, 20}, 0.5, hit, error)) << error;
    EXPECT_EQ(hit.part, HitPart::Interior);
    EXPECT_EQ(hit.record, 1U);
    EXPECT_EQ(hit.subpath, 0U);
}

TEST(JniShapeLayerCodec, RejectsTruncationTrailingDataBadEnumsAndInvalidUtf8Atomically) {
    std::vector<std::uint8_t> bytes;
    std::string error;
    ASSERT_TRUE(encodeRecord(sampleRecord(), bytes, error)) << error;
    Record destination;
    destination.name = "unchanged";
    const auto expectReject = [&](std::vector<std::uint8_t> malformed) {
        EXPECT_FALSE(decodeRecord(malformed, destination, error));
        EXPECT_FALSE(error.empty());
        EXPECT_EQ(destination.name, "unchanged");
    };
    auto truncated = bytes;
    truncated.pop_back();
    expectReject(std::move(truncated));
    auto trailing = bytes;
    trailing.push_back(0);
    expectReject(std::move(trailing));
    auto bad_version = bytes;
    bad_version[4] = 2;
    expectReject(std::move(bad_version));
    auto bad_utf8 = bytes;
    bad_utf8[10] = 0xff;
    expectReject(std::move(bad_utf8));
    auto bad_enum = bytes;
    bad_enum[10 + sampleRecord().name.size() + 8] = 0xff;  // fill-rule ordinal after header/name/id
    expectReject(std::move(bad_enum));
    auto bad_boolean = bytes;
    bad_boolean[10 + sampleRecord().name.size() + 9] = 2;  // fill-enabled must be exactly 0 or 1
    expectReject(std::move(bad_boolean));
    auto nonzero_reserved = bytes;
    nonzero_reserved[6] = 1;
    expectReject(std::move(nonzero_reserved));
}

TEST(JniShapeLayerCodec, RejectsNonFiniteOrOutOfRangeInputsAndOversizedNames) {
    std::string error;
    std::vector<std::uint8_t> bytes;
    auto invalid = sampleRecord();
    invalid.shape.subpaths[0].anchors[0].point.x = std::numeric_limits<double>::quiet_NaN();
    EXPECT_FALSE(encodeRecord(invalid, bytes, error));
    invalid = sampleRecord();
    invalid.shape.primitive->x = std::numeric_limits<double>::infinity();
    EXPECT_FALSE(encodeRecord(invalid, bytes, error));
    invalid = sampleRecord();
    invalid.shape.primitive->rotation = 360;
    EXPECT_FALSE(encodeRecord(invalid, bytes, error));
    invalid = sampleRecord();
    invalid.name.assign(kMaxNameBytes + 1, 'n');
    EXPECT_FALSE(encodeRecord(invalid, bytes, error));
    invalid = sampleRecord();
    invalid.shape.subpaths[0].anchors[1].id = invalid.shape.subpaths[0].anchors[0].id;
    EXPECT_FALSE(encodeRecord(invalid, bytes, error));
    auto duplicate = sampleRecord();
    duplicate.shape.id = invalid.shape.id;
    EXPECT_FALSE(encodeBatch(std::array<Record, 2>{invalid, duplicate}, bytes, error));
    Hit hit;
    EXPECT_FALSE(hitTestBatch({}, {std::numeric_limits<double>::quiet_NaN(), 0}, 1, hit, error));
}

TEST(JniShapeLayerCodec, RejectsZeroAndNegativePrimitiveSourceBoxDimensions) {
    std::vector<std::uint8_t> bytes;
    std::string error;
    ASSERT_TRUE(encodeRecord(sampleRecord(), bytes, error)) << error;
    const std::size_t primitive_offset = 10 + sampleRecord().name.size() + 112;
    // Primitive header (4), x/y, then width and height. The fixed prefix includes two sample dashes.
    constexpr std::size_t width_offset = 4 + 2 * sizeof(double);
    constexpr std::size_t height_offset = width_offset + sizeof(double);
    Record destination;
    destination.name = "unchanged";

    auto zero_width = bytes;
    writeLittleEndianDouble(zero_width, primitive_offset + width_offset, 0.0);
    EXPECT_FALSE(decodeRecord(zero_width, destination, error));
    EXPECT_EQ(error, "primitive metadata is incomplete or out of range");
    EXPECT_EQ(destination.name, "unchanged");

    auto negative_height = bytes;
    writeLittleEndianDouble(negative_height, primitive_offset + height_offset, -1.0);
    EXPECT_FALSE(decodeRecord(negative_height, destination, error));
    EXPECT_EQ(error, "primitive metadata is incomplete or out of range");
    EXPECT_EQ(destination.name, "unchanged");
}

TEST(JniShapeLayerCodec, EmptyBatchRoundTripsAndHasNoHit) {
    std::vector<std::uint8_t> bytes;
    std::string error;
    ASSERT_TRUE(encodeBatch({}, bytes, error)) << error;
    std::vector<Record> records;
    ASSERT_TRUE(decodeBatch(bytes, records, error)) << error;
    EXPECT_TRUE(records.empty());
    Hit hit;
    ASSERT_TRUE(hitTestBatch(bytes, {0, 0}, 1, hit, error)) << error;
    EXPECT_EQ(hit.part, HitPart::None);
}

}  // namespace
