// Tests for the layer-mask operations behind Add / Invert / Apply / Load Selection.
#include "layer_mask_ops.h"

#include <gtest/gtest.h>

#include <cstdint>
#include <cstring>
#include <string>

namespace {

namespace mask = croptop::layermask;
using croptop::PixelFormat;
using croptop::TileBuffer;
using croptop::TileStore;

constexpr std::uint32_t kEdge = croptop::kTileEdge;

TileStore opaque(std::uint32_t width, std::uint32_t height) {
    TileStore store(PixelFormat::RGBA8, width, height);
    for (std::uint32_t ty = 0; ty * kEdge < height; ++ty) {
        for (std::uint32_t tx = 0; tx * kEdge < width; ++tx) {
            TileBuffer tile(store.tile_byte_size());
            for (std::size_t p = 0; p < tile.size(); p += 4) {
                tile[p] = 200;
                tile[p + 1] = 100;
                tile[p + 2] = 50;
                tile[p + 3] = 255;
            }
            EXPECT_TRUE(store.writeTile({static_cast<std::int32_t>(tx), static_cast<std::int32_t>(ty)}, tile));
        }
    }
    return store;
}

/** A16 selection with `left_value` for x < 128 in every tile and nothing stored where `skip_tile_x` says so. */
TileStore selectionLeftHalf(std::uint32_t width, std::uint32_t height, std::uint16_t left_value, int skip_tile_x = -1) {
    TileStore store(PixelFormat::A16, width, height);
    for (std::uint32_t ty = 0; ty * kEdge < height; ++ty) {
        for (std::uint32_t tx = 0; tx * kEdge < width; ++tx) {
            if (static_cast<int>(tx) == skip_tile_x) continue;
            TileBuffer tile(store.tile_byte_size(), 0);
            for (std::uint32_t y = 0; y < kEdge; ++y) {
                for (std::uint32_t x = 0; x < 128; ++x) std::memcpy(tile.data() + (static_cast<std::size_t>(y) * kEdge + x) * 2, &left_value, 2);
            }
            EXPECT_TRUE(store.writeTile({static_cast<std::int32_t>(tx), static_cast<std::int32_t>(ty)}, tile));
        }
    }
    return store;
}

std::uint8_t alphaAt(const TileStore& store, std::uint32_t x, std::uint32_t y) {
    const auto tile = store.readTile({static_cast<std::int32_t>(x / kEdge), static_cast<std::int32_t>(y / kEdge)});
    return tile == nullptr ? 0 : (*tile)[(static_cast<std::size_t>(y % kEdge) * kEdge + x % kEdge) * 4 + 3];
}

std::uint16_t coverageAt(const TileStore& store, std::uint32_t x, std::uint32_t y) {
    const auto tile = store.readTile({static_cast<std::int32_t>(x / kEdge), static_cast<std::int32_t>(y / kEdge)});
    if (tile == nullptr) return 0;
    std::uint16_t v;
    std::memcpy(&v, tile->data() + (static_cast<std::size_t>(y % kEdge) * kEdge + x % kEdge) * 2, 2);
    return v;
}

/** A mask's coverage at a pixel, honouring `empty` for tiles it holds no pixels for. */
std::uint16_t effectiveCoverage(const mask::MaskData& m, std::uint32_t x, std::uint32_t y) {
    const auto tile = m.pixels.readTile({static_cast<std::int32_t>(x / kEdge), static_cast<std::int32_t>(y / kEdge)});
    if (tile == nullptr) return static_cast<std::uint16_t>(m.empty * 65535.0F + 0.5F);
    return coverageAt(m.pixels, x, y);
}

TEST(LayerMaskOps, RevealAllCostsNoTilesAndLeavesPixelsAlone) {
    const auto layer = opaque(600, 300);
    std::string error;
    const auto created = mask::createMask(layer, mask::NewMask::RevealAll, nullptr, error);
    ASSERT_TRUE(created) << error;
    EXPECT_EQ(created->pixels.tileCoordinates().size(), 0U);
    const auto applied = mask::applyToPixels(layer, *created, {}, error);
    ASSERT_TRUE(applied) << error;
    EXPECT_EQ(alphaAt(*applied, 10, 10), 255);
    EXPECT_EQ(alphaAt(*applied, 590, 290), 255);
}

TEST(LayerMaskOps, HideAllErasesEveryPixelOnApply) {
    const auto layer = opaque(600, 300);
    std::string error;
    const auto created = mask::createMask(layer, mask::NewMask::HideAll, nullptr, error);
    ASSERT_TRUE(created) << error;
    const auto applied = mask::applyToPixels(layer, *created, {}, error);
    ASSERT_TRUE(applied) << error;
    EXPECT_EQ(alphaAt(*applied, 10, 10), 0);
    EXPECT_EQ(alphaAt(*applied, 590, 290), 0);
    // Colour is untouched (straight alpha), so a later re-reveal is lossless up to the applied alpha.
    EXPECT_EQ((*applied->readTile({0, 0}))[0], 200);
}

TEST(LayerMaskOps, SelectionKindsFollowTheSelectionAndHideIsItsInverse) {
    const auto layer = opaque(600, 300);
    const auto selection = selectionLeftHalf(600, 300, 65535, /*skip_tile_x=*/2);
    std::string error;
    const auto reveal = mask::createMask(layer, mask::NewMask::RevealSelection, &selection, error);
    ASSERT_TRUE(reveal) << error;
    auto applied = mask::applyToPixels(layer, *reveal, {}, error);
    ASSERT_TRUE(applied) << error;
    EXPECT_EQ(alphaAt(*applied, 10, 10), 255);   // selected
    EXPECT_EQ(alphaAt(*applied, 200, 10), 0);    // not selected
    EXPECT_EQ(alphaAt(*applied, 530, 10), 0);    // tile with no selection pixels at all

    const auto hide = mask::createMask(layer, mask::NewMask::HideSelection, &selection, error);
    ASSERT_TRUE(hide) << error;
    applied = mask::applyToPixels(layer, *hide, {}, error);
    ASSERT_TRUE(applied) << error;
    EXPECT_EQ(alphaAt(*applied, 10, 10), 0);
    EXPECT_EQ(alphaAt(*applied, 200, 10), 255);
    EXPECT_EQ(alphaAt(*applied, 530, 10), 255);  // missing selection tile: revealed, without storing it
}

TEST(LayerMaskOps, SelectionKindsNeedAMatchingSelection) {
    const auto layer = opaque(300, 300);
    std::string error;
    EXPECT_FALSE(mask::createMask(layer, mask::NewMask::RevealSelection, nullptr, error));
    EXPECT_NE(error.find("needs a selection"), std::string::npos);
    const auto wrong_size = selectionLeftHalf(256, 256, 65535);
    EXPECT_FALSE(mask::createMask(layer, mask::NewMask::HideSelection, &wrong_size, error));
    EXPECT_NE(error.find("canvas size"), std::string::npos);
}

TEST(LayerMaskOps, InvertFlipsStoredValuesAndTheEmptyCoverage) {
    const auto layer = opaque(600, 300);
    const auto selection = selectionLeftHalf(600, 300, 65535, 2);
    std::string error;
    const auto original = mask::createMask(layer, mask::NewMask::RevealSelection, &selection, error);
    ASSERT_TRUE(original) << error;
    const auto inverted = mask::invertMask(*original);
    EXPECT_FLOAT_EQ(inverted.empty, 1.0F);
    EXPECT_EQ(coverageAt(inverted.pixels, 10, 10), 0);
    EXPECT_EQ(coverageAt(inverted.pixels, 200, 10), 65535);
    const auto twice = mask::invertMask(inverted);
    EXPECT_FLOAT_EQ(twice.empty, 0.0F);
    EXPECT_EQ(coverageAt(twice.pixels, 10, 10), 65535);
    EXPECT_EQ(coverageAt(twice.pixels, 200, 10), 0);
}

TEST(LayerMaskOps, PropertiesShapeTheAppliedAndLoadedCoverage) {
    const auto layer = opaque(300, 300);
    std::string error;
    const auto hidden = mask::createMask(layer, mask::NewMask::HideAll, nullptr, error);
    ASSERT_TRUE(hidden) << error;
    mask::MaskProperties half;
    half.density = 0.5F;  // a fully hiding mask at 50% density hides half
    auto applied = mask::applyToPixels(layer, *hidden, half, error);
    ASSERT_TRUE(applied) << error;
    EXPECT_NEAR(alphaAt(*applied, 10, 10), 127, 1);
    mask::MaskProperties disabled;
    disabled.enabled = false;
    applied = mask::applyToPixels(layer, *hidden, disabled, error);
    ASSERT_TRUE(applied) << error;
    EXPECT_EQ(alphaAt(*applied, 10, 10), 255);
}

TEST(LayerMaskOps, ToSelectionLoadsTheEffectiveCoverage) {
    const auto layer = opaque(600, 300);
    std::string error;
    const auto reveal = mask::createMask(layer, mask::NewMask::RevealAll, nullptr, error);
    ASSERT_TRUE(reveal) << error;
    const auto everything = mask::toSelection(*reveal, {}, error);
    ASSERT_TRUE(everything) << error;
    EXPECT_EQ(everything->format(), PixelFormat::A16);
    EXPECT_EQ(coverageAt(*everything, 10, 10), 65535);
    EXPECT_EQ(coverageAt(*everything, 590, 290), 65535);

    const auto hide = mask::createMask(layer, mask::NewMask::HideAll, nullptr, error);
    ASSERT_TRUE(hide) << error;
    const auto nothing = mask::toSelection(*hide, {}, error);
    ASSERT_TRUE(nothing) << error;
    EXPECT_EQ(nothing->tileCoordinates().size(), 0U) << "an empty selection stores nothing";

    const auto partial = selectionLeftHalf(600, 300, 32768);
    const auto from_partial = mask::createMask(layer, mask::NewMask::RevealSelection, &partial, error);
    ASSERT_TRUE(from_partial) << error;
    const auto half = mask::toSelection(*from_partial, {}, error);
    ASSERT_TRUE(half) << error;
    EXPECT_EQ(coverageAt(*half, 10, 10), 32768);
}

TEST(LayerMaskOps, PaintingBlackHidesAndWhiteRevealsAndUntouchedTilesStaySparse) {
    const auto layer = opaque(600, 300);
    std::string error;
    const auto reveal = mask::createMask(layer, mask::NewMask::RevealAll, nullptr, error);
    ASSERT_TRUE(reveal) << error;
    croptop::brush::StrokeMaskCompositor stroke(600, 300);
    for (int x = 10; x < 30; ++x) ASSERT_TRUE(stroke.accumulate(x, 10, 1.0F, error)) << error;
    ASSERT_TRUE(stroke.accumulate(40, 10, 0.5F, error)) << error;
    const croptop::TileCoordinate affected[] = {{0, 0}};

    const auto hidden = mask::paintStroke(*reveal, stroke, 0.0F, 1.0F, affected, nullptr, error);
    ASSERT_TRUE(hidden) << error;
    EXPECT_EQ(effectiveCoverage(*hidden, 15, 10), 0);            // black over a full-flow pixel hides it
    EXPECT_NEAR(effectiveCoverage(*hidden, 40, 10), 32768, 2);   // half flow: half hidden
    EXPECT_EQ(effectiveCoverage(*hidden, 15, 11), 65535);        // outside the stroke: still revealed
    EXPECT_EQ(hidden->pixels.tileCoordinates().size(), 1U) << "only the painted tile is stored";
    EXPECT_FLOAT_EQ(hidden->empty, 1.0F);
    EXPECT_EQ(effectiveCoverage(*reveal, 15, 10), 65535) << "the input mask is untouched (copy-on-write)";

    // Painting white at full flow over the hidden pixels reveals them again, and a tile that returns to its empty value is dropped.
    croptop::brush::StrokeMaskCompositor restore(600, 300);
    for (int x = 10; x < 30; ++x) ASSERT_TRUE(restore.accumulate(x, 10, 1.0F, error)) << error;
    ASSERT_TRUE(restore.accumulate(40, 10, 1.0F, error)) << error;
    const auto restored = mask::paintStroke(*hidden, restore, 1.0F, 1.0F, affected, nullptr, error);
    ASSERT_TRUE(restored) << error;
    EXPECT_EQ(effectiveCoverage(*restored, 15, 10), 65535);
    EXPECT_EQ(restored->pixels.tileCoordinates().size(), 0U);
    // The same white stroke at half flow only half restores the half-hidden pixel, so its tile stays.
    const auto partly = mask::paintStroke(*hidden, stroke, 1.0F, 1.0F, affected, nullptr, error);
    ASSERT_TRUE(partly) << error;
    EXPECT_NEAR(effectiveCoverage(*partly, 40, 10), 49151, 4);
    EXPECT_EQ(partly->pixels.tileCoordinates().size(), 1U);

    // Opacity caps the paint: 40 % black over a revealed pixel leaves 60 %.
    const auto faint = mask::paintStroke(*reveal, stroke, 0.0F, 0.4F, affected, nullptr, error);
    ASSERT_TRUE(faint) << error;
    EXPECT_NEAR(effectiveCoverage(*faint, 15, 10), 39321, 4);
    EXPECT_FALSE(mask::paintStroke(*reveal, stroke, 2.0F, 1.0F, affected, nullptr, error));
}

TEST(LayerMaskOps, PaintingOnAHideAllMaskRevealsWithWhite) {
    const auto layer = opaque(300, 300);
    std::string error;
    const auto hide = mask::createMask(layer, mask::NewMask::HideAll, nullptr, error);
    ASSERT_TRUE(hide) << error;
    croptop::brush::StrokeMaskCompositor stroke(300, 300);
    ASSERT_TRUE(stroke.accumulate(100, 100, 1.0F, error)) << error;
    const croptop::TileCoordinate affected[] = {{0, 0}};
    const auto revealed = mask::paintStroke(*hide, stroke, 1.0F, 1.0F, affected, nullptr, error);
    ASSERT_TRUE(revealed) << error;
    EXPECT_EQ(coverageAt(revealed->pixels, 100, 100), 65535);
    EXPECT_EQ(coverageAt(revealed->pixels, 101, 100), 0);
    const auto applied = mask::applyToPixels(layer, *revealed, {}, error);
    ASSERT_TRUE(applied) << error;
    EXPECT_EQ(alphaAt(*applied, 100, 100), 255);
    EXPECT_EQ(alphaAt(*applied, 101, 100), 0);
}

TEST(LayerMaskOps, ThumbnailsReduceThePaintedMask) {
    const auto layer = opaque(1024, 512);
    const auto selection = [] {
        TileStore store(PixelFormat::A16, 1024, 512);
        for (std::uint32_t ty = 0; ty < 2; ++ty) {
            for (std::uint32_t tx = 2; tx < 4; ++tx) {  // the right half is white
                TileBuffer tile(store.tile_byte_size());
                for (std::size_t i = 0; i < tile.size(); i += 2) {
                    const std::uint16_t v = 65535;
                    std::memcpy(tile.data() + i, &v, 2);
                }
                EXPECT_TRUE(store.writeTile({static_cast<std::int32_t>(tx), static_cast<std::int32_t>(ty)}, tile));
            }
        }
        return store;
    }();
    std::string error;
    const auto data = mask::createMask(layer, mask::NewMask::RevealSelection, &selection, error);
    ASSERT_TRUE(data) << error;
    const auto small = mask::thumbnail(*data, 64, error);
    ASSERT_TRUE(small) << error;
    EXPECT_EQ(small->width, 64U);
    EXPECT_EQ(small->height, 32U);
    EXPECT_EQ(small->gray[10 * 64 + 5], 0);
    EXPECT_EQ(small->gray[10 * 64 + 60], 255);
    // Reveal All is white everywhere, and it stays cheap: no tiles were stored for it.
    const auto white = mask::createMask(layer, mask::NewMask::RevealAll, nullptr, error);
    ASSERT_TRUE(white) << error;
    const auto full = mask::thumbnail(*white, 256, error);
    ASSERT_TRUE(full) << error;
    EXPECT_EQ(full->width, 256U);
    EXPECT_EQ(full->gray.front(), 255);
    EXPECT_EQ(full->gray.back(), 255);
    // A canvas smaller than the request is not enlarged.
    const auto tiny = mask::thumbnail(*white, 4096, error);
    ASSERT_TRUE(tiny) << error;
    EXPECT_EQ(tiny->width, 1024U);
    EXPECT_FALSE(mask::thumbnail(*white, 0, error));
}

TEST(LayerMaskOps, ApplyRejectsMismatchedOrNonRgbaInput) {
    const auto layer = opaque(300, 300);
    const auto other = opaque(256, 256);
    std::string error;
    const auto reveal = mask::createMask(other, mask::NewMask::RevealAll, nullptr, error);
    ASSERT_TRUE(reveal) << error;
    EXPECT_FALSE(mask::applyToPixels(layer, *reveal, {}, error));
    EXPECT_NE(error.find("differ in size"), std::string::npos);
    TileStore a16(PixelFormat::A16, 300, 300);
    const auto same = mask::createMask(layer, mask::NewMask::RevealAll, nullptr, error);
    ASSERT_TRUE(same) << error;
    EXPECT_FALSE(mask::applyToPixels(a16, *same, {}, error));
}

}  // namespace
