// Tests for the liquify engine CPU core.
#include "liquify.h"
#include "native_tests_stress_gate.h"
#include "selection_mask.h"
#include "tile_store.h"

#include <gtest/gtest.h>

#include <array>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <functional>
#include <string>

namespace {

using croptop::PixelFormat;
using croptop::TileStore;
namespace pt = croptop::paint;
namespace sel = croptop::selection;
using Rgba = std::array<std::uint8_t, 4>;

TileStore paint(std::uint32_t w, std::uint32_t h, const std::function<Rgba(std::uint32_t, std::uint32_t)>& f) {
    TileStore s(PixelFormat::RGBA8, w, h);
    for (std::uint32_t ty = 0; ty * 256 < h; ++ty) {
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
    }
    return s;
}

Rgba at(const TileStore& s, std::uint32_t x, std::uint32_t y) {
    Rgba c{};
    const auto t = s.readTile({static_cast<std::int32_t>(x / 256), static_cast<std::int32_t>(y / 256)});
    if (t) std::memcpy(c.data(), t->data() + (static_cast<std::size_t>(y % 256) * 256 + x % 256) * 4, 4);
    return c;
}

/** White canvas with a black 10×10 square centred on (cx, cy). */
TileStore dot(std::uint32_t w, std::uint32_t h, int cx, int cy) {
    return paint(w, h, [=](std::uint32_t x, std::uint32_t y) {
        const bool inside = std::abs(static_cast<int>(x) - cx) < 5 && std::abs(static_cast<int>(y) - cy) < 5;
        return inside ? Rgba{0, 0, 0, 255} : Rgba{255, 255, 255, 255};
    });
}

std::array<double, 2> darkCentroid(const TileStore& s) {
    double sx = 0, sy = 0, n = 0;
    for (std::uint32_t y = 0; y < s.height(); ++y)
        for (std::uint32_t x = 0; x < s.width(); ++x) {
            const double darkness = 255.0 - at(s, x, y)[0];
            sx += darkness * x;
            sy += darkness * y;
            n += darkness;
        }
    return {sx / n, sy / n};
}

pt::LiquifyBrush brush(double x, double y, double size, double dx = 0, double dy = 0) {
    pt::LiquifyBrush b;
    b.x = x;
    b.y = y;
    b.size = size;
    b.dx = dx;
    b.dy = dy;
    b.rate = 1.0F;
    return b;
}

TEST(Liquify, IdentityMeshReproducesTheSourceAndSharesItsTiles) {
    const auto image = dot(300, 300, 100, 100);
    pt::LiquifyMesh mesh(300, 300);
    EXPECT_TRUE(mesh.isIdentity());
    std::string error;
    const auto out = mesh.render(image, error);
    ASSERT_TRUE(out) << error;
    EXPECT_EQ(out->readTile({0, 0}).get(), image.readTile({0, 0}).get()) << "untouched tiles are shared";
}

TEST(Liquify, ForwardWarpMovesContentWithTheBrush) {
    const auto image = dot(256, 256, 100, 100);
    pt::LiquifyMesh mesh(256, 256);
    std::string error;
    ASSERT_TRUE(mesh.apply(pt::LiquifyTool::ForwardWarp, brush(100, 100, 120, 10, 0), error)) << error;
    const auto out = mesh.render(image, error);
    ASSERT_TRUE(out) << error;
    const auto c = darkCentroid(*out);
    std::printf("[liquify] one forward-warp dab moved the dot by %.2f px\n", c[0] - 100.0);
    EXPECT_NEAR(c[0] - 100.0, 10.0, 1.5);
    EXPECT_NEAR(c[1], 100.0, 0.5);
    EXPECT_EQ(at(*out, 250, 250), (Rgba{255, 255, 255, 255}));
}

TEST(Liquify, SuccessiveDabsComposeAlongTheStroke) {
    const auto image = dot(256, 256, 60, 128);
    pt::LiquifyMesh mesh(256, 256);
    std::string error;
    for (int i = 0; i < 20; ++i) {
        ASSERT_TRUE(mesh.apply(pt::LiquifyTool::ForwardWarp, brush(60 + 3.0 * i, 128, 80, 3, 0), error));
    }
    const auto out = mesh.render(image, error);
    ASSERT_TRUE(out) << error;
    const auto c = darkCentroid(*out);
    std::printf("[liquify] 20 dabs of 3 px dragged the dot %.1f px\n", c[0] - 60.0);
    EXPECT_GT(c[0] - 60.0, 45.0) << "content follows the brush instead of smearing behind";
}

TEST(Liquify, ReconstructAndReconstructAllRestoreTheSource) {
    const auto image = dot(256, 256, 128, 128);
    pt::LiquifyMesh mesh(256, 256);
    std::string error;
    ASSERT_TRUE(mesh.apply(pt::LiquifyTool::ForwardWarp, brush(128, 128, 100, 12, 5), error));
    for (int i = 0; i < 30; ++i) ASSERT_TRUE(mesh.apply(pt::LiquifyTool::Reconstruct, brush(128, 128, 140), error));
    const auto d = mesh.displacementAt(128, 128);
    EXPECT_LT(std::hypot(d[0], d[1]), 0.05);
    ASSERT_TRUE(mesh.apply(pt::LiquifyTool::ForwardWarp, brush(128, 128, 100, 12, 5), error));
    mesh.reconstructAll(1.0F);
    EXPECT_TRUE(mesh.isIdentity());
}

TEST(Liquify, TwirlPuckerBloatAndPushLeftMoveTheRightWay) {
    std::string error;
    {
        pt::LiquifyMesh mesh(256, 256);
        ASSERT_TRUE(mesh.apply(pt::LiquifyTool::TwirlClockwise, brush(128, 128, 120), error));
        // Clockwise on screen: right of centre, content arrives from above (samples above, d.y < 0).
        EXPECT_LT(mesh.displacementAt(158, 128)[1], -0.5F);
        EXPECT_GT(mesh.displacementAt(98, 128)[1], 0.5F);
    }
    {
        pt::LiquifyMesh mesh(256, 256);
        ASSERT_TRUE(mesh.apply(pt::LiquifyTool::TwirlCounterClockwise, brush(128, 128, 120), error));
        EXPECT_GT(mesh.displacementAt(158, 128)[1], 0.5F);
    }
    {
        pt::LiquifyMesh mesh(256, 256);
        ASSERT_TRUE(mesh.apply(pt::LiquifyTool::Pucker, brush(128, 128, 120), error));
        EXPECT_GT(mesh.displacementAt(158, 128)[0], 0.3F) << "pucker samples farther out, shrinking content";
    }
    {
        pt::LiquifyMesh mesh(256, 256);
        ASSERT_TRUE(mesh.apply(pt::LiquifyTool::Bloat, brush(128, 128, 120), error));
        EXPECT_LT(mesh.displacementAt(158, 128)[0], -0.3F);
    }
    {
        pt::LiquifyMesh mesh(256, 256);
        ASSERT_TRUE(mesh.apply(pt::LiquifyTool::PushLeft, brush(128, 128, 120, 0, -10), error));
        // Stroke moving up: content slides left, so pixels sample from the right.
        EXPECT_GT(mesh.displacementAt(128, 128)[0], 5.0F);
        EXPECT_NEAR(mesh.displacementAt(128, 128)[1], 0.0F, 1e-3F);
    }
}

TEST(Liquify, FrozenAreasDoNotMove) {
    pt::LiquifyMesh mesh(256, 256);
    std::string error;
    const auto freeze = sel::rasterizeRect(256, 256, {128, 0, 256, 256}, false);
    ASSERT_TRUE(mesh.setFreezeMask(&freeze, error)) << error;
    ASSERT_TRUE(mesh.apply(pt::LiquifyTool::ForwardWarp, brush(128, 128, 150, 10, 0), error));
    EXPECT_GT(mesh.displacementAt(100, 128)[0], -20.0F);
    EXPECT_LT(mesh.displacementAt(100, 128)[0], -2.0F) << "unfrozen side moves";
    EXPECT_EQ(mesh.displacementAt(160, 128)[0], 0.0F) << "frozen side stays";
    ASSERT_TRUE(mesh.setFreezeMask(nullptr, error));
    TileStore wrong(PixelFormat::A16, 10, 10);
    EXPECT_FALSE(mesh.setFreezeMask(&wrong, error));
}

TEST(Liquify, SmoothEvensOutTheField) {
    pt::LiquifyMesh mesh(256, 256);
    std::string error;
    auto sharp = brush(128, 128, 30, 15, 0);
    sharp.density = 0.0F;
    ASSERT_TRUE(mesh.apply(pt::LiquifyTool::ForwardWarp, sharp, error));
    const auto step = [&] { return std::abs(mesh.displacementAt(128, 128)[0] - mesh.displacementAt(140, 128)[0]); };
    const float before = step();
    for (int i = 0; i < 10; ++i) ASSERT_TRUE(mesh.apply(pt::LiquifyTool::Smooth, brush(128, 128, 120), error));
    EXPECT_LT(step(), before * 0.7F) << "the field's slope flattens";
}

TEST(Liquify, WarpingNearTransparencyLeavesNoDarkFringe) {
    const auto image = paint(256, 256, [](std::uint32_t x, std::uint32_t) {
        return x < 128 ? Rgba{0, 0, 0, 0} : Rgba{255, 40, 40, 255};
    });
    pt::LiquifyMesh mesh(256, 256);
    std::string error;
    ASSERT_TRUE(mesh.apply(pt::LiquifyTool::ForwardWarp, brush(128, 128, 120, -7.3, 2.1), error));
    const auto out = mesh.render(image, error);
    ASSERT_TRUE(out) << error;
    for (std::uint32_t x = 60; x < 200; ++x) {
        const auto c = at(*out, x, 128);
        if (c[3] > 20) {
            EXPECT_GE(c[0], 245) << x;
        }
    }
}

TEST(Liquify, RejectsBadInput) {
    pt::LiquifyMesh mesh(128, 128);
    std::string error;
    EXPECT_FALSE(mesh.apply(pt::LiquifyTool::Bloat, brush(NAN, 0, 10), error));
    EXPECT_FALSE(mesh.apply(pt::LiquifyTool::Bloat, brush(0, 0, 0.5), error));
    auto b = brush(10, 10, 10);
    b.rate = 2.0F;
    EXPECT_FALSE(mesh.apply(pt::LiquifyTool::Bloat, b, error));
    EXPECT_FALSE(mesh.render(dot(64, 64, 10, 10), error));
    EXPECT_FALSE(mesh.render(TileStore(PixelFormat::A16, 128, 128), error));
    EXPECT_TRUE(mesh.apply(pt::LiquifyTool::Bloat, brush(-5000, -5000, 10), error)) << "off-canvas dabs are no-ops";
    EXPECT_TRUE(mesh.isIdentity());
}

TEST(Liquify, ExtremeDisplacementFallsBackToPerPixelSampling) {
    // A 5000 px drag inside a 40 px brush varies the field by thousands of px within one render block,
    // past the windowed path's limit; content pulled from outside the canvas is transparent.
    const auto image = dot(256, 256, 128, 128);
    pt::LiquifyMesh mesh(256, 256);
    std::string error;
    ASSERT_TRUE(mesh.apply(pt::LiquifyTool::ForwardWarp, brush(128, 128, 40, 5000, 0), error));
    const auto out = mesh.render(image, error);
    ASSERT_TRUE(out) << error;
    EXPECT_EQ(at(*out, 128, 128)[3], 0) << "the centre samples 5000 px off-canvas";
    EXPECT_EQ(at(*out, 20, 20), (Rgba{255, 255, 255, 255}));
}

TEST(Liquify, HighBitDepthRenders) {
    TileStore image(PixelFormat::RGBA16, 256, 256);
    croptop::TileBuffer t(image.tile_byte_size(), 0);
    for (std::size_t i = 0; i < 256U * 256U; ++i) {
        const std::array<std::uint16_t, 4> px{static_cast<std::uint16_t>((i % 256) * 256), 1000, 60000, 65535};
        std::memcpy(t.data() + i * 8, px.data(), 8);
    }
    ASSERT_TRUE(image.writeTile({0, 0}, t));
    pt::LiquifyMesh mesh(256, 256);
    std::string error;
    ASSERT_TRUE(mesh.apply(pt::LiquifyTool::ForwardWarp, brush(128, 128, 100, 10, 0), error));
    const auto out = mesh.render(image, error);
    ASSERT_TRUE(out) << error;
    std::uint16_t r = 0;
    std::memcpy(&r, out->readTile({0, 0})->data() + (128U * 256U + 128U) * 8, 2);
    EXPECT_NEAR(r, (128 - 10) * 256, 400) << "a ramp shifts by the warp";
}

TEST(Liquify, TimingOn24Megapixels) {
    CROPTOP_REQUIRE_STRESS();
    constexpr std::uint32_t kW = 6000, kH = 4000;
    const auto image = paint(kW, kH, [](std::uint32_t x, std::uint32_t y) {
        return Rgba{static_cast<std::uint8_t>(x), static_cast<std::uint8_t>(y), static_cast<std::uint8_t>(x ^ y), 255};
    });
    pt::LiquifyMesh mesh(kW, kH);
    std::string error;
    auto start = std::chrono::steady_clock::now();
    for (int i = 0; i < 50; ++i) ASSERT_TRUE(mesh.apply(pt::LiquifyTool::ForwardWarp, brush(2000 + 4.0 * i, 2000, 200, 4, 0), error));
    const double dab_ms = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - start).count() / 50;
    start = std::chrono::steady_clock::now();
    auto local = mesh.render(image, error);
    ASSERT_TRUE(local) << error;
    const double local_ms = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - start).count();
    ASSERT_TRUE(mesh.apply(pt::LiquifyTool::Bloat, brush(3000, 2000, 7000), error));
    start = std::chrono::steady_clock::now();
    auto full = mesh.render(image, error);
    ASSERT_TRUE(full) << error;
    const double full_ms = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - start).count();
    std::printf("[liquify] 24 MP: 200 px dab %.3f ms; apply after a stroke %.0f ms; apply with the whole image warped %.0f ms\n",
                dab_ms, local_ms, full_ms);
}

}  // namespace
