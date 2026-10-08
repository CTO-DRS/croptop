// Tests for develop local-mask generators.
#include "develop_masks.h"
#include "tile_store.h"

#include <gtest/gtest.h>

#include <cmath>
#include <cstring>
#include <limits>
#include <string>

namespace {

namespace dv = croptop::develop;
using croptop::PixelFormat;
using croptop::TileStore;

double at(const TileStore& s, std::uint32_t x, std::uint32_t y) {
    const auto t = s.readTile({static_cast<std::int32_t>(x / 256), static_cast<std::int32_t>(y / 256)});
    if (!t) return 0.0;
    std::uint16_t v = 0;
    std::memcpy(&v, t->data() + (static_cast<std::size_t>(y % 256) * 256 + x % 256) * 2, 2);
    return v / 65535.0;
}

TEST(DevelopMasks, LinearGradientRampsFromStartToEnd) {
    const auto m = dv::linearGradient(400, 300, {100, 0}, {300, 0});
    EXPECT_NEAR(at(m, 50, 10), 1.0, 1e-4);
    EXPECT_NEAR(at(m, 199, 200), 0.5, 0.01) << "halfway, perpendicular band";
    EXPECT_NEAR(at(m, 350, 10), 0.0, 1e-4);
    EXPECT_GT(at(m, 150, 0), at(m, 250, 0));
    EXPECT_TRUE(dv::linearGradient(10, 10, {5, 5}, {5, 5}).tileCoordinates().empty()) << "degenerate gradient is empty";
}

TEST(DevelopMasks, RadialFeathersAndInverts) {
    dv::RadialSpec spec;
    spec.centre = {200, 150};
    spec.radius_x = 100;
    spec.radius_y = 50;
    spec.feather = 0.5;
    const auto m = dv::radial(400, 300, spec);
    EXPECT_NEAR(at(m, 200, 150), 1.0, 1e-4);
    EXPECT_NEAR(at(m, 240, 150), 1.0, 1e-4) << "inside the unfeathered half";
    EXPECT_NEAR(at(m, 275, 150), 0.5, 0.02) << "midway through the feather";
    EXPECT_NEAR(at(m, 200, 205), 0.0, 1e-4) << "beyond the 50 px vertical radius";
    spec.invert = true;
    EXPECT_NEAR(at(dv::radial(400, 300, spec), 10, 10), 1.0, 1e-4);
    spec.invert = false;
    spec.rotation = 90;
    EXPECT_NEAR(at(dv::radial(400, 300, spec), 200, 190), 1.0, 1e-4) << "rotated 90°: the long axis is vertical (40 px down is still inside)";
    EXPECT_NEAR(at(dv::radial(400, 300, spec), 255, 150), 0.0, 1e-4) << "and 55 px across is beyond the 50 px short radius";
}

TEST(DevelopMasks, RadialRejectsNonFiniteAndOutOfRangeParametersAndSkipsOutsideTiles) {
    TileStore mask(PixelFormat::A16, 4096, 4096);
    std::string error;
    dv::RadialSpec spec;
    spec.centre = {2000, 2000}; spec.radius_x = 12; spec.radius_y = 8; spec.feather = 0.4;
    ASSERT_TRUE(dv::radial(4096, 4096, spec, mask, error)) << error;
    EXPECT_EQ(mask.tileCoordinates().size(), 1U) << "a small non-inverted radial mask should generate only its intersecting tile";
    EXPECT_GT(at(mask, 2000, 2000), 0.99);
    spec.feather = std::numeric_limits<double>::quiet_NaN();
    EXPECT_FALSE(dv::radial(4096, 4096, spec, mask, error));
    EXPECT_FALSE(error.empty());
    spec.feather = 0.5; spec.rotation = std::numeric_limits<double>::infinity();
    EXPECT_FALSE(dv::radial(4096, 4096, spec, mask, error));
    spec.rotation = 0; spec.radius_x = 0;
    EXPECT_FALSE(dv::radial(4096, 4096, spec, mask, error));
    spec.radius_x = 12; spec.feather = 1.01;
    EXPECT_FALSE(dv::radial(4096, 4096, spec, mask, error));
}

TEST(DevelopMasks, LuminanceRangeSelectsTones) {
    TileStore image(PixelFormat::RGBA8, 256, 10);
    croptop::TileBuffer t(image.tile_byte_size(), 0);
    for (std::size_t i = 0; i < 256U * 256U; ++i) {
        const auto v = static_cast<std::uint8_t>(i % 256);
        t[i * 4] = t[i * 4 + 1] = t[i * 4 + 2] = v;
        t[i * 4 + 3] = 255;
    }
    ASSERT_TRUE(image.writeTile({0, 0}, t));
    TileStore mask(PixelFormat::A16, 256, 10);
    std::string error;
    ASSERT_TRUE(dv::luminanceRange(image, 0.4F, 0.6F, 0.1F, mask, error)) << error;
    EXPECT_NEAR(at(mask, 128, 5), 1.0, 1e-4);
    EXPECT_NEAR(at(mask, 20, 5), 0.0, 1e-4);
    EXPECT_GT(at(mask, 90, 5), 0.0) << "inside the smoothness band";
    EXPECT_LT(at(mask, 90, 5), 1.0);
    EXPECT_FALSE(dv::luminanceRange(image, 0.7F, 0.3F, 0.1F, mask, error));
}

TEST(DevelopMasks, LuminanceRangeClearsPreviousOutputWhenNewResultIsEmpty) {
    TileStore image(PixelFormat::RGBA8, 256, 256);
    croptop::TileBuffer src(image.tile_byte_size(), 0);
    ASSERT_TRUE(image.writeTile({0, 0}, src));
    TileStore mask(PixelFormat::A16, 256, 256);
    croptop::TileBuffer previous(mask.tile_byte_size(), 0xff);
    ASSERT_TRUE(mask.writeTile({0, 0}, previous));
    ASSERT_EQ(mask.tileCoordinates().size(), 1U);
    std::string error;
    ASSERT_TRUE(dv::luminanceRange(image, 0.4F, 0.6F, 0.0F, mask, error)) << error;
    EXPECT_TRUE(error.empty());
    EXPECT_TRUE(mask.tileCoordinates().empty());
    EXPECT_EQ(at(mask, 100, 100), 0.0);
}

TEST(DevelopMasks, BrushDabsAccumulateAndErase) {
    TileStore mask(PixelFormat::A16, 300, 300);
    std::string error;
    dv::Dab dab;
    dab.centre = {150, 150};
    dab.size = 60;
    dab.feather = 0.5;
    dab.flow = 0.5;
    ASSERT_TRUE(dv::brushDab(mask, dab, error)) << error;
    EXPECT_NEAR(at(mask, 150, 150), 0.5, 1e-3);
    ASSERT_TRUE(dv::brushDab(mask, dab, error));
    EXPECT_NEAR(at(mask, 150, 150), 0.75, 1e-3) << "flow builds up towards 1";
    EXPECT_EQ(at(mask, 150, 185), 0.0) << "outside the dab";
    EXPECT_LT(at(mask, 150, 175), at(mask, 150, 150)) << "feathered edge";
    dab.erase = true;
    dab.flow = 1.0;
    ASSERT_TRUE(dv::brushDab(mask, dab, error));
    EXPECT_NEAR(at(mask, 150, 150), 0.0, 1e-3);
    dab.size = -1;
    EXPECT_FALSE(dv::brushDab(mask, dab, error));
}

TEST(DevelopMasks, BrushDabClipsHugeFiniteCentresWithoutIntegerOverflow) {
    TileStore mask(PixelFormat::A16, 520, 520);
    std::string error;
    dv::Dab dab; dab.size = 80;
    dab.centre = {std::numeric_limits<double>::max(), 260};
    ASSERT_TRUE(dv::brushDab(mask, dab, error)) << error;
    EXPECT_TRUE(mask.tileCoordinates().empty());
    dab.centre = {-std::numeric_limits<double>::max(), 260};
    ASSERT_TRUE(dv::brushDab(mask, dab, error)) << error;
    EXPECT_TRUE(mask.tileCoordinates().empty());
    dab.centre = {260, 260};
    ASSERT_TRUE(dv::brushDab(mask, dab, error)) << error;
    EXPECT_NEAR(at(mask, 260, 260), 1.0, 1e-4);
}

TEST(DevelopMasks, BrushDabWritesAcrossFourTileBoundaries) {
    TileStore mask(PixelFormat::A16, 520, 520);
    std::string error;
    dv::Dab dab; dab.centre = {256, 256}; dab.size = 40; dab.feather = 0; dab.flow = 1;
    ASSERT_TRUE(dv::brushDab(mask, dab, error)) << error;
    EXPECT_EQ(mask.tileCoordinates().size(), 4U);
    EXPECT_GT(at(mask, 255, 255), 0.9);
    EXPECT_GT(at(mask, 256, 255), 0.9);
    EXPECT_GT(at(mask, 255, 256), 0.9);
    EXPECT_GT(at(mask, 256, 256), 0.9);
}

}  // namespace
