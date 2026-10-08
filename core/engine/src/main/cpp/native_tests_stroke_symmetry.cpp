// Tests for symmetry painting.
#include "stroke_symmetry.h"

#include <gtest/gtest.h>

#include <cmath>
#include <cstring>

namespace {

using croptop::PixelFormat;
using croptop::TileStore;
namespace brush = croptop::brush;

float alphaAt(const TileStore& s, int x, int y) {
    const auto tile = s.readTile({x / 256, y / 256});
    if (!tile) return 0.0F;
    return (*tile)[(static_cast<std::size_t>(y % 256) * 256 + static_cast<std::size_t>(x % 256)) * 4 + 3] / 255.0F;
}

/** A jittery, scattered elliptical stroke, symmetrised and stamped. */
TileStore paint(const brush::SymmetrySpec& spec, int size) {
    brush::Settings settings;
    settings.size = 14;
    settings.roundness = 0.4F;
    settings.angle = 30;
    settings.angle_jitter = 0.3F;
    settings.scatter = 0.5F;
    settings.size_jitter = 0.3F;
    settings.spacing = 0.3F;
    brush::DabGenerator generator(settings, 7);
    std::vector<brush::Sample> samples;
    for (int k = 0; k <= 40; ++k) samples.push_back({30.0F + 1.5F * static_cast<float>(k), 40.0F + 25.0F * std::sin(static_cast<float>(k) * 0.2F), 0.8F});
    std::vector<brush::Dab> dabs, all;
    std::string error;
    EXPECT_TRUE(generator.add(samples, dabs, error)) << error;
    EXPECT_TRUE(brush::symmetrize(spec, dabs, all, error)) << error;
    EXPECT_EQ(all.size(), dabs.size() * static_cast<std::size_t>(brush::symmetryCopies(spec)));
    TileStore layer(PixelFormat::RGBA8, static_cast<std::uint32_t>(size), static_cast<std::uint32_t>(size));
    for (const auto& dab : all) EXPECT_TRUE(brush::stamp(layer, dab, 0.2F, 0.3F, 0.9F, 1.0F, false, false, error)) << error;
    return layer;
}

TEST(StrokeSymmetry, CopiesAndDabTransforms) {
    brush::SymmetrySpec spec;
    EXPECT_EQ(brush::symmetryCopies(spec), 1);
    spec.type = brush::SymmetryType::Vertical;
    EXPECT_EQ(brush::symmetryCopies(spec), 2);
    spec.type = brush::SymmetryType::DualAxis;
    EXPECT_EQ(brush::symmetryCopies(spec), 4);
    spec.type = brush::SymmetryType::Radial;
    EXPECT_EQ(brush::symmetryCopies(spec), 6);
    spec.type = brush::SymmetryType::Mandala;
    spec.segments = 5;
    EXPECT_EQ(brush::symmetryCopies(spec), 10);

    // Vertical mirror through x = 100: position and tip orientation (30° → 150°) are reflected.
    spec = {brush::SymmetryType::Vertical, 100, 50, 0, 6};
    const brush::Dab dab{110, 55, 12, 30, 0.5F, 0.8F, 0.9F, 0.7F, 1};
    std::vector<brush::Dab> out;
    std::string error;
    ASSERT_TRUE(brush::symmetrize(spec, std::span(&dab, 1), out, error));
    ASSERT_EQ(out.size(), 2U);
    EXPECT_EQ(out[0], dab);
    EXPECT_FLOAT_EQ(out[1].x, 90);
    EXPECT_FLOAT_EQ(out[1].y, 55);
    EXPECT_FLOAT_EQ(out[1].angle, 150);
    EXPECT_FLOAT_EQ(out[1].diameter, dab.diameter);
    EXPECT_FLOAT_EQ(out[1].flow, dab.flow);
    // Radial 4 about (100, 50): the second copy is rotated by 90° (y down: (10, 5) → (−5, 10)).
    spec = {brush::SymmetryType::Radial, 100, 50, 0, 4};
    out.clear();
    ASSERT_TRUE(brush::symmetrize(spec, std::span(&dab, 1), out, error));
    ASSERT_EQ(out.size(), 4U);
    EXPECT_NEAR(out[1].x, 95, 1e-4);
    EXPECT_NEAR(out[1].y, 60, 1e-4);
    EXPECT_NEAR(out[1].angle, 120, 1e-4);
    EXPECT_NEAR(out[2].x, 90, 1e-4);
    EXPECT_NEAR(out[2].y, 45, 1e-4);

    spec.segments = 1;
    EXPECT_FALSE(brush::symmetrize(spec, std::span(&dab, 1), out, error));
    spec = {brush::SymmetryType::Vertical, std::nanf(""), 0, 0, 6};
    EXPECT_FALSE(brush::symmetrize(spec, std::span(&dab, 1), out, error));
}

TEST(StrokeSymmetry, PaintedStrokesAreMirrorAndRotationSymmetric) {
    // Vertical mirror about x = 100 on a 200 px canvas: pixel i ↔ 199 − i.
    const auto mirrored = paint({brush::SymmetryType::Vertical, 100, 100, 0, 6}, 200);
    int painted = 0;
    for (int y = 0; y < 200; ++y)
        for (int x = 0; x < 100; ++x) {
            ASSERT_NEAR(alphaAt(mirrored, x, y), alphaAt(mirrored, 199 - x, y), 2.0F / 255.0F) << x << ", " << y;
            painted += alphaAt(mirrored, x, y) > 0.5F ? 1 : 0;
        }
    EXPECT_GT(painted, 500);

    // Radial 4 about (100, 100): pixel (i, j) ↔ (199 − j, i).
    const auto radial = paint({brush::SymmetryType::Radial, 100, 100, 0, 4}, 200);
    for (int j = 0; j < 200; j += 2)
        for (int i = 0; i < 200; i += 2) ASSERT_NEAR(alphaAt(radial, i, j), alphaAt(radial, 199 - j, i), 3.0F / 255.0F) << i << ", " << j;

    // Mandala 3 (6 copies) is symmetric under its vertical mirror too.
    const auto mandala = paint({brush::SymmetryType::Mandala, 100, 100, 0, 3}, 200);
    for (int y = 0; y < 200; y += 2)
        for (int x = 0; x < 100; x += 2) ASSERT_NEAR(alphaAt(mandala, x, y), alphaAt(mandala, 199 - x, y), 3.0F / 255.0F) << x << ", " << y;
}

}  // namespace
