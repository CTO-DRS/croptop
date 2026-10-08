// Tests for the manual Lens Correction filter.
#include "lens_correction.h"
#include "tile_store.h"

#include <gtest/gtest.h>

#include <array>
#include <cmath>
#include <cstring>
#include <functional>
#include <string>

namespace {

namespace fl = croptop::filters;
using croptop::PixelFormat;
using croptop::TileStore;
using Rgba = std::array<std::uint8_t, 4>;

TileStore paint(std::uint32_t w, std::uint32_t h, const std::function<Rgba(std::uint32_t, std::uint32_t)>& f) {
    TileStore s(PixelFormat::RGBA8, w, h);
    for (std::uint32_t ty = 0; ty * 256 < h; ++ty)
        for (std::uint32_t tx = 0; tx * 256 < w; ++tx) {
            croptop::TileBuffer t(s.tile_byte_size(), 0);
            for (std::uint32_t y = 0; y < 256; ++y)
                for (std::uint32_t x = 0; x < 256; ++x) {
                    const auto gx = tx * 256 + x, gy = ty * 256 + y;
                    if (gx >= w || gy >= h) continue;
                    const auto c = f(gx, gy);
                    std::memcpy(t.data() + (static_cast<std::size_t>(y) * 256 + x) * 4, c.data(), 4);
                }
            EXPECT_TRUE(s.writeTile({static_cast<std::int32_t>(tx), static_cast<std::int32_t>(ty)}, t));
        }
    return s;
}

Rgba at(const TileStore& s, std::uint32_t x, std::uint32_t y) {
    Rgba c{};
    const auto t = s.readTile({static_cast<std::int32_t>(x / 256), static_cast<std::int32_t>(y / 256)});
    if (t) std::memcpy(c.data(), t->data() + (static_cast<std::size_t>(y % 256) * 256 + x % 256) * 4, 4);
    return c;
}

TileStore coordinates() {
    return paint(256, 256, [](std::uint32_t x, std::uint32_t y) { return Rgba{static_cast<std::uint8_t>(x), static_cast<std::uint8_t>(y), 128, 255}; });
}

TEST(LensCorrection, DefaultsAreTheIdentity) {
    auto image = coordinates();
    const auto original = image;
    std::string error;
    ASSERT_TRUE(fl::lensCorrection(image, {}, nullptr, error)) << error;
    for (std::uint32_t y = 0; y < 256; y += 17)
        for (std::uint32_t x = 0; x < 256; x += 13) EXPECT_EQ(at(image, x, y), at(original, x, y)) << x << "," << y;
}

TEST(LensCorrection, PositiveDistortionRemovesBarrelByPullingEdgesOut) {
    auto image = coordinates();
    fl::LensCorrectionSpec spec;
    spec.distortion = 50.0F;
    spec.edge_extend = true;
    std::string error;
    ASSERT_TRUE(fl::lensCorrection(image, spec, nullptr, error)) << error;
    EXPECT_EQ(at(image, 128, 128)[0], 128) << "the centre does not move";
    // Near the right edge the sample comes from closer to the centre (edges are stretched outwards).
    EXPECT_LT(at(image, 240, 128)[0], 236);
}

TEST(LensCorrection, AngleScaleAndTransparentEdges) {
    std::string error;
    auto rotated = coordinates();
    fl::LensCorrectionSpec spec;
    spec.angle = 90.0F;
    ASSERT_TRUE(fl::lensCorrection(rotated, spec, nullptr, error)) << error;
    // A 90° rotation about the centre maps (200, 128) to a source straight above or below it.
    EXPECT_NEAR(at(rotated, 200, 128)[0], 128, 2);
    auto shrunk = coordinates();
    spec = {};
    spec.scale = 0.5F;
    ASSERT_TRUE(fl::lensCorrection(shrunk, spec, nullptr, error));
    EXPECT_EQ(at(shrunk, 2, 2)[3], 0) << "areas that map outside the layer become transparent";
    EXPECT_NEAR(at(shrunk, 191, 128)[0], 254, 2) << "halfway out samples the right edge";
    EXPECT_EQ(at(shrunk, 192, 128)[3], 0) << "just past it lies outside the layer";
}

TEST(LensCorrection, PerspectiveAndChromaticAberration) {
    std::string error;
    auto keystone = coordinates();
    fl::LensCorrectionSpec spec;
    spec.vertical_perspective = 60.0F;
    spec.edge_extend = true;
    ASSERT_TRUE(fl::lensCorrection(keystone, spec, nullptr, error)) << error;
    // Top and bottom rows scale differently: the horizontal span sampled differs.
    const int top_span = at(keystone, 240, 20)[0] - at(keystone, 16, 20)[0];
    const int bottom_span = at(keystone, 240, 236)[0] - at(keystone, 16, 236)[0];
    EXPECT_GT(std::abs(top_span - bottom_span), 10);
    auto fringe = paint(256, 256, [](std::uint32_t x, std::uint32_t) { return x < 230 ? Rgba{0, 0, 0, 255} : Rgba{255, 255, 255, 255}; });
    spec = {};
    spec.red_cyan = 100.0F;
    ASSERT_TRUE(fl::lensCorrection(fringe, spec, nullptr, error));
    // +0.5 % red scale samples outwards: the dark pixel next to the edge picks up red, green stays dark.
    const auto p = at(fringe, 229, 128);
    EXPECT_EQ(p[1], 0) << "green unchanged";
    EXPECT_GT(p[0], 60) << "red shifted";
}

TEST(LensCorrection, VignetteBrightensCornersOnly) {
    auto image = paint(256, 256, [](std::uint32_t, std::uint32_t) { return Rgba{100, 100, 100, 255}; });
    fl::LensCorrectionSpec spec;
    spec.vignette_amount = 100.0F;
    std::string error;
    ASSERT_TRUE(fl::lensCorrection(image, spec, nullptr, error)) << error;
    EXPECT_EQ(at(image, 128, 128)[0], 100);
    EXPECT_GT(at(image, 1, 1)[0], 180);
    spec.distortion = 200.0F;
    EXPECT_FALSE(fl::lensCorrection(image, spec, nullptr, error));
}

}  // namespace
