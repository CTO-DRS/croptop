// Content-Aware Fill sampling-area strokes.
#include "content_aware_sampling.h"
#include "fill_command.h"
#include "smart_object.h"
#include "selection_mask.h"
#include "tile_store.h"

#include <gtest/gtest.h>

#include <array>
#include <cstring>
#include <string>

namespace {

using croptop::PixelFormat;
using croptop::TileStore;
namespace pt = croptop::paint;
namespace sel = croptop::selection;

constexpr std::uint32_t kSize = 256;

/** Left 100 columns red, everything else blue. */
TileStore twoColour() {
    TileStore s(PixelFormat::RGBA8, kSize, kSize);
    croptop::TileBuffer t(s.tile_byte_size(), 0);
    for (std::uint32_t y = 0; y < kSize; ++y) {
        for (std::uint32_t x = 0; x < kSize; ++x) {
            const std::array<std::uint8_t, 4> c = x < 100 ? std::array<std::uint8_t, 4>{255, 0, 0, 255} : std::array<std::uint8_t, 4>{0, 0, 255, 255};
            std::memcpy(t.data() + (static_cast<std::size_t>(y) * kSize + x) * 4, c.data(), 4);
        }
    }
    EXPECT_TRUE(s.writeTile({0, 0}, t));
    return s;
}

std::array<std::uint8_t, 4> pixel(const TileStore& s, std::uint32_t x, std::uint32_t y) {
    const auto tile = s.readTile({0, 0});
    std::array<std::uint8_t, 4> out{};
    std::memcpy(out.data(), tile->data() + (static_cast<std::size_t>(y) * kSize + x) * 4, 4);
    return out;
}

TEST(ContentAwareSampling, StrokesAddAndSubtractFromTheBase) {
    const auto like = twoColour();
    std::string error;
    pt::SamplingStroke add;
    add.add = true;
    add.radius = 10;
    add.points = {{40, 40}, {40, 200}};
    const pt::SamplingStroke strokes[] = {add};
    auto mask = pt::buildSamplingMask(like, pt::SamplingBase::None, strokes, error);
    ASSERT_TRUE(mask) << error;
    EXPECT_EQ(sel::coverageAt(*mask, 40, 120), 65535);  // on the capsule
    EXPECT_EQ(sel::coverageAt(*mask, 200, 120), 0);

    pt::SamplingStroke cut = add;
    cut.add = false;
    const pt::SamplingStroke cuts[] = {cut};
    auto all = pt::buildSamplingMask(like, pt::SamplingBase::All, cuts, error);
    ASSERT_TRUE(all) << error;
    EXPECT_EQ(sel::coverageAt(*all, 40, 120), 0);
    EXPECT_EQ(sel::coverageAt(*all, 200, 120), 65535);
}

TEST(ContentAwareSampling, RejectsBadInput) {
    const auto like = twoColour();
    std::string error;
    pt::SamplingStroke bad;
    bad.radius = 0.0;
    bad.points = {{1, 1}};
    const pt::SamplingStroke strokes[] = {bad};
    EXPECT_FALSE(pt::buildSamplingMask(like, pt::SamplingBase::All, strokes, error));
    EXPECT_FALSE(error.empty());
}

TEST(ContentAwareSampling, FillOnlyCopiesFromTheSamplingArea) {
    auto image = twoColour();
    const auto hole = sel::rasterizeRect(kSize, kSize, {150, 100, 190, 140}, false);
    pt::SamplingStroke left;
    left.radius = 60;
    left.points = {{40, 20}, {40, 236}};  // covers x ≤ 100, the red area
    const pt::SamplingStroke strokes[] = {left};
    std::string error;
    const auto sampling = pt::buildSamplingMask(image, pt::SamplingBase::None, strokes, error);
    ASSERT_TRUE(sampling) << error;

    pt::FillSpec spec;
    spec.source = pt::FillSource::ContentAware;
    spec.color_adaptation = false;
    spec.sampling = &*sampling;
    ASSERT_TRUE(pt::fill(image, spec, &hole, error)) << error;
    const auto centre = pixel(image, 170, 120);
    EXPECT_GT(centre[0], 200) << "hole should be filled from the red sampling area";
    EXPECT_LT(centre[2], 60);
}

TEST(ContentAwareSampling, StrokesApplyOntoAnExistingMask) {
    const auto like = twoColour();
    std::string error;
    pt::SamplingStroke freeze;
    freeze.radius = 12;
    freeze.points = {{30, 30}, {120, 30}};
    const pt::SamplingStroke first[] = {freeze};
    const auto once = pt::applyStrokesToMask(like, sel::emptyMask(like), first, error);
    ASSERT_TRUE(once) << error;
    EXPECT_EQ(sel::coverageAt(*once, 70, 30), 65535);
    pt::SamplingStroke thaw = freeze;
    thaw.add = false;
    thaw.points = {{60, 30}, {80, 30}};
    thaw.radius = 6;
    const pt::SamplingStroke second[] = {thaw};
    const auto twice = pt::applyStrokesToMask(like, *once, second, error);
    ASSERT_TRUE(twice) << error;
    EXPECT_EQ(sel::coverageAt(*twice, 70, 30), 0) << "thaw clears what an earlier freeze covered";
    EXPECT_EQ(sel::coverageAt(*twice, 110, 30), 65535) << "untouched freeze stays";
    EXPECT_EQ(sel::coverageAt(*once, 70, 30), 65535) << "the base mask is not modified";
}

TEST(SmartFilterMask, BlendsFilteredResultThroughTheMask) {
    const auto base = twoColour();  // left red, right blue
    TileStore filtered(PixelFormat::RGBA8, kSize, kSize);
    croptop::TileBuffer green(filtered.tile_byte_size());
    for (std::size_t p = 0; p < green.size(); p += 4) {
        green[p] = 0;
        green[p + 1] = 255;
        green[p + 2] = 0;
        green[p + 3] = 255;
    }
    ASSERT_TRUE(filtered.writeTile({0, 0}, green));
    // The mask reveals the filtered (green) result only in the top half.
    const auto mask = sel::rasterizeRect(kSize, kSize, {0, 0, kSize, kSize / 2.0}, false);
    std::string error;
    const auto out = croptop::smart::blendThroughMask(base, filtered, mask, 1, error);
    ASSERT_TRUE(out) << error;
    EXPECT_EQ(pixel(*out, 50, 20), (std::array<std::uint8_t, 4>{0, 255, 0, 255})) << "masked in: filtered";
    EXPECT_EQ(pixel(*out, 50, 200), (std::array<std::uint8_t, 4>{255, 0, 0, 255})) << "masked out: placement";
    const auto wrong = sel::emptyMask(8, 8);
    EXPECT_FALSE(croptop::smart::blendThroughMask(base, filtered, wrong, 1, error));
}

}  // namespace
