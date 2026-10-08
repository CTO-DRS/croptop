// Tests for layer transform sessions.
#include "transform_session.h"

#include <gtest/gtest.h>

#include <cmath>
#include <cstdint>
#include <cstring>
#include <functional>

#include "pixel_plane.h"
#include "selection_mask.h"

namespace {

using croptop::PixelFormat;
using croptop::TileStore;
namespace xf = croptop::xform;

TileStore image(int w, int h, const std::function<std::array<float, 4>(int, int)>& pixel) {
    TileStore s(PixelFormat::RGBA8, static_cast<std::uint32_t>(w), static_cast<std::uint32_t>(h));
    for (std::int32_t ty = 0; ty * 256 < h; ++ty)
        for (std::int32_t tx = 0; tx * 256 < w; ++tx) {
            croptop::TileBuffer t(s.tile_byte_size(), 0);
            bool any = false;
            for (int y = 0; y < 256; ++y)
                for (int x = 0; x < 256; ++x) {
                    const int gx = tx * 256 + x, gy = ty * 256 + y;
                    if (gx >= w || gy >= h) continue;
                    const auto v = pixel(gx, gy);
                    any = any || v[3] > 0;
                    croptop::paint::detail::writePixel(PixelFormat::RGBA8, t.data() + (static_cast<std::size_t>(y) * 256 + x) * 4, v.data());
                }
            if (any) {
                EXPECT_TRUE(s.writeTile({tx, ty}, t));
            }
        }
    return s;
}

std::array<float, 4> at(const TileStore& s, int x, int y) {
    const auto tile = s.readTile({x / 256, y / 256});
    std::array<float, 4> v{};
    if (tile) croptop::paint::detail::readPixel(s.format(), tile->data() + (static_cast<std::size_t>(y % 256) * 256 + x % 256) * s.bytes_per_pixel(), v.data());
    return v;
}

/** An opaque textured square [100, 200)² on a transparent 400 × 300 layer. */
std::array<float, 4> square(int x, int y) {
    if (x < 100 || x >= 200 || y < 100 || y >= 200) return {0, 0, 0, 0};
    return {static_cast<float>(x - 100) / 99.0F, static_cast<float>(y - 100) / 99.0F, 0.5F, 1.0F};
}

constexpr xf::Matrix kIdentity{1, 0, 0, 0, 1, 0, 0, 0, 1};

TEST(TransformSession, IdentityAndIntegerTranslationAreExact) {
    const auto layer = image(400, 300, square);
    std::string error;
    auto session = xf::LayerTransform::begin(layer, nullptr, error);
    ASSERT_NE(session, nullptr) << error;
    ASSERT_TRUE(session->floatingBounds());
    EXPECT_EQ(session->floatingBounds()->left, 100);
    EXPECT_EQ(session->floatingBounds()->right, 200);
    const auto same = session->commit(kIdentity, error);
    ASSERT_TRUE(same) << error;
    for (int y = 0; y < 300; y += 3)
        for (int x = 0; x < 400; x += 3) ASSERT_EQ(at(same->pixels, x, y), at(layer, x, y));
    // Move by (+37, −12): pixel-exact with the bicubic filter at integer offsets.
    const auto moved = session->commit({1, 0, 37, 0, 1, -12, 0, 0, 1}, error);
    ASSERT_TRUE(moved) << error;
    for (int y = 100; y < 200; y += 2)
        for (int x = 100; x < 200; x += 2)
            for (int k = 0; k < 4; ++k) ASSERT_NEAR(at(moved->pixels, x + 37, y - 12)[static_cast<std::size_t>(k)], at(layer, x, y)[static_cast<std::size_t>(k)], 1.0F / 255.0F);
    EXPECT_EQ(at(moved->pixels, 110, 195)[3], 0.0F) << "the old position is vacated";
    // Dirty covers both the old and new footprint.
    EXPECT_LE(moved->dirty.left, 100);
    EXPECT_LE(moved->dirty.top, 88);
    EXPECT_GE(moved->dirty.right, 237);
    EXPECT_GE(moved->dirty.bottom, 200);
}

TEST(TransformSession, SelectionLimitsWhatMovesAndLeavesAHole) {
    const auto layer = image(400, 300, [](int x, int y) { return std::array<float, 4>{0.2F, 0.6F, static_cast<float>(x % 7) / 6.0F, y >= 0 ? 1.0F : 0.0F}; });
    const auto selection = croptop::selection::rasterizeRect(400, 300, {50, 50, 150, 100}, false);
    std::string error;
    auto session = xf::LayerTransform::begin(layer, &selection, error);
    ASSERT_NE(session, nullptr) << error;
    const auto result = session->commit({1, 0, 200, 0, 1, 150, 0, 0, 1}, error);
    ASSERT_TRUE(result) << error;
    EXPECT_EQ(at(result->pixels, 100, 75)[3], 0.0F) << "the selected area is cut out";
    EXPECT_EQ(at(result->pixels, 20, 20), at(layer, 20, 20)) << "outside the selection nothing changes";
    EXPECT_EQ(at(result->pixels, 300, 225), at(layer, 100, 75)) << "the selected pixels landed at the target";
    EXPECT_EQ(at(result->pixels, 360, 260), at(layer, 360, 260)) << "the moved piece sits over the remainder only where it lands";
}

TEST(TransformSession, IdentityPreservesPixelsAtFeatheredSelectionEdges) {
    const auto layer = image(8, 8, [](int, int) { return std::array<float, 4>{0.8F, 0.25F, 0.1F, 1.0F}; });
    TileStore selection(PixelFormat::A16, 8, 8);
    croptop::TileBuffer mask(selection.tile_byte_size(), 0);
    const std::uint16_t halfCoverage = 32768;
    std::memcpy(mask.data() + (3U * croptop::kTileEdge + 3U) * sizeof(halfCoverage), &halfCoverage, sizeof(halfCoverage));
    ASSERT_TRUE(selection.writeTile({0, 0}, mask));

    std::string error;
    auto session = xf::LayerTransform::begin(layer, &selection, error);
    ASSERT_NE(session, nullptr) << error;
    const auto preview = session->preview(kIdentity, 1.0, error);
    ASSERT_TRUE(preview) << error;
    const auto committed = session->commit(kIdentity, error);
    ASSERT_TRUE(committed) << error;
    for (int y = 0; y < 8; ++y)
        for (int x = 0; x < 8; ++x) {
            EXPECT_EQ(at(*preview, x, y), at(layer, x, y)) << "preview at " << x << "," << y;
            EXPECT_EQ(at(committed->pixels, x, y), at(layer, x, y)) << "commit at " << x << "," << y;
        }
}

TEST(TransformSession, ProxyPreviewTracksTheCommitAndRotationMatchesRotate90) {
    const auto layer = image(400, 300, square);
    std::string error;
    auto session = xf::LayerTransform::begin(layer, nullptr, error);
    ASSERT_NE(session, nullptr) << error;
    // Rotate 90° clockwise about (150, 150): (x, y) → (300 − y, x).
    const xf::Matrix rotate{0, -1, 300, 1, 0, 0, 0, 0, 1};
    const auto full = session->commit(rotate, error);
    ASSERT_TRUE(full) << error;
    for (int y = 102; y < 198; y += 3)
        for (int x = 102; x < 198; x += 3)
            for (int k = 0; k < 4; ++k) ASSERT_NEAR(at(full->pixels, 299 - y, x)[static_cast<std::size_t>(k)], at(layer, x, y)[static_cast<std::size_t>(k)], 1.5F / 255.0F) << x << "," << y;
    const auto proxy = session->preview(rotate, 0.25, error);
    ASSERT_TRUE(proxy) << error;
    EXPECT_EQ(proxy->width(), 100U);
    EXPECT_EQ(proxy->height(), 75U);
    // The proxy's covered area matches the commit's footprint scaled by ¼ (the rotated square lands at x 100–200, y 100–200).
    EXPECT_GT(at(*proxy, 37, 37)[3], 0.9F);
    EXPECT_LT(at(*proxy, 10, 10)[3], 0.1F);
    EXPECT_FALSE(session->preview(rotate, 1.5, error));
    EXPECT_FALSE(session->commit({0, 0, 0, 0, 0, 0, 0, 0, 1}, error)) << "singular";
    EXPECT_FALSE(session->commit({1, 0, std::nan(""), 0, 1, 0, 0, 0, 1}, error));
    TileStore mask(PixelFormat::A16, 10, 10);
    EXPECT_EQ(xf::LayerTransform::begin(mask, nullptr, error), nullptr);
    EXPECT_EQ(xf::LayerTransform::begin(layer, &mask, error), nullptr) << "selection size mismatch";
}

}  // namespace
