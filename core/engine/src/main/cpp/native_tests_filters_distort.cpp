// Tests for distort, stylize and render CPU filters.
#include "filters_distort.h"
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

namespace fl = croptop::filters;
using croptop::PixelFormat;
using croptop::TileStore;
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

/** Red channel = x, green = y: reading a pixel tells where it was sampled from. */
TileStore coordinates(std::uint32_t size = 256) {
    return paint(size, size, [](std::uint32_t x, std::uint32_t y) { return Rgba{static_cast<std::uint8_t>(x), static_cast<std::uint8_t>(y), 0, 255}; });
}

TEST(FiltersDistort, LensFlareBrightensAroundTheLightAndLeavesTheFarCornerAlone) {
    const auto dark = [](std::uint32_t, std::uint32_t) { return Rgba{20, 20, 30, 255}; };
    for (int lens = 0; lens < 4; ++lens) {
        auto img = paint(256, 256, dark);
        std::string error;
        ASSERT_TRUE(fl::lensFlare(img, 0.25F, 0.3F, 1.0F, lens, nullptr, error)) << error;
        const auto light = at(img, 64, 77);   // the light is at (0.25, 0.3)
        const auto corner = at(img, 250, 250);
        EXPECT_GT(light[0], 200) << "lens " << lens << ": the core of the light is nearly white";
        EXPECT_GT(light[2], 120);
        EXPECT_EQ(light[3], 255) << "alpha is untouched";
        EXPECT_LT(std::abs(corner[0] - 20) + std::abs(corner[2] - 30), 30) << "lens " << lens << ": far from every element the picture is as it was";
    }
}

TEST(FiltersDistort, LensFlareBrightnessScalesAndAxisPutsGhostsPastTheCentre) {
    const auto dark = [](std::uint32_t, std::uint32_t) { return Rgba{0, 0, 0, 255}; };
    auto dim = paint(256, 256, dark);
    auto bright = paint(256, 256, dark);
    std::string error;
    ASSERT_TRUE(fl::lensFlare(dim, 0.2F, 0.2F, 0.3F, 0, nullptr, error)) << error;
    ASSERT_TRUE(fl::lensFlare(bright, 0.2F, 0.2F, 2.0F, 0, nullptr, error)) << error;
    EXPECT_GT(at(bright, 51, 51)[0], at(dim, 51, 51)[0]);
    // Lens 0 puts a ghost on the axis through the centre, at t = 0.45 from the light: (0.2 + 0.45 * 0.3, same) of the picture.
    const auto ghost = at(bright, static_cast<std::uint32_t>(0.335 * 256), static_cast<std::uint32_t>(0.335 * 256));
    const auto off = at(bright, static_cast<std::uint32_t>(0.335 * 256) + 70, static_cast<std::uint32_t>(0.335 * 256) - 40);
    EXPECT_GT(ghost[2], off[2]) << "a ghost sits on the line through the picture centre";
}

TEST(FiltersDistort, LensFlareRejectsBadParametersAndHonoursTheMask) {
    auto img = paint(64, 64, [](std::uint32_t, std::uint32_t) { return Rgba{10, 10, 10, 255}; });
    std::string error;
    EXPECT_FALSE(fl::lensFlare(img, 1.5F, 0.5F, 1.0F, 0, nullptr, error));
    EXPECT_FALSE(fl::lensFlare(img, 0.5F, 0.5F, 5.0F, 0, nullptr, error));
    EXPECT_FALSE(fl::lensFlare(img, 0.5F, 0.5F, 1.0F, 7, nullptr, error));
    EXPECT_NE(error.find("lens flare"), std::string::npos);
}

TEST(FiltersDistort, TwirlRotatesTheCentreMostAndLeavesTheOutsideAlone) {
    auto image = coordinates();
    const auto original = image;
    std::string error;
    ASSERT_TRUE(fl::twirl(image, 90.0F, nullptr, error)) << error;
    EXPECT_EQ(at(image, 2, 2), at(original, 2, 2)) << "corners are outside R";
    // Just right of the centre the sample comes from a rotated position (not the same x).
    const auto p = at(image, 140, 128);
    EXPECT_GT(std::abs(p[1] - 128), 5) << "sampled from above or below";
}

TEST(FiltersDistort, PinchAndSpherizeScaleTheCentre) {
    std::string error;
    auto pinched = coordinates();
    ASSERT_TRUE(fl::pinch(pinched, 100.0F, nullptr, error)) << error;
    EXPECT_GT(at(pinched, 138, 128)[0], 141) << "pinch samples farther out (content pulled in)";
    auto bulge = coordinates();
    ASSERT_TRUE(fl::spherize(bulge, 100.0F, nullptr, error)) << error;
    EXPECT_LT(at(bulge, 148, 128)[0], 145) << "spherize samples closer to the centre (magnifies)";
    // The mapping stays monotonic out to R (asin is steep near the rim, so no plateau is expected).
    for (std::uint32_t x = 129; x < 255; ++x) EXPECT_GE(at(bulge, x, 128)[0], at(bulge, x - 1, 128)[0]) << x;
    auto tall = paint(256, 400, [](std::uint32_t x, std::uint32_t y) { return Rgba{static_cast<std::uint8_t>(x), static_cast<std::uint8_t>(y % 256), 0, 255}; });
    const auto tall_original = tall;
    ASSERT_TRUE(fl::spherize(tall, 100.0F, nullptr, error));
    EXPECT_EQ(at(tall, 128, 350), at(tall_original, 128, 350)) << "beyond R nothing moves";
    auto inward = coordinates();
    ASSERT_TRUE(fl::spherize(inward, -100.0F, nullptr, error));
    EXPECT_GT(at(inward, 138, 128)[0], 141);
}

TEST(FiltersDistort, WaveAndRippleDisplaceByTheirAmplitude) {
    std::string error;
    auto waved = coordinates();
    ASSERT_TRUE(fl::wave(waved, 40.0F, 6.0F, true, false, nullptr, error)) << error;
    int max_shift = 0;
    for (std::uint32_t y = 0; y < 40; ++y) max_shift = std::max(max_shift, std::abs(at(waved, 100, y)[0] - 100));
    EXPECT_NEAR(max_shift, 6, 1);
    EXPECT_EQ(at(waved, 100, 17)[1], 17) << "horizontal only";
    auto rippled = coordinates();
    ASSERT_TRUE(fl::ripple(rippled, 100.0F, fl::RippleSize::Medium, nullptr, error));
    int ripple_shift = 0;
    for (std::uint32_t y = 0; y < 16; ++y) ripple_shift = std::max(ripple_shift, std::abs(at(rippled, 100, y)[0] - 100));
    EXPECT_NEAR(ripple_shift, 4, 1) << "A = λ/4 at 100 %";
}

TEST(FiltersDistort, PolarRoundTripRecoversTheImage) {
    auto image = paint(256, 256, [](std::uint32_t x, std::uint32_t y) {
        return Rgba{static_cast<std::uint8_t>((x / 32) * 32), static_cast<std::uint8_t>((y / 32) * 32), 0, 255};
    });
    const auto original = image;
    std::string error;
    ASSERT_TRUE(fl::polarCoordinates(image, fl::PolarMode::RectangularToPolar, nullptr, error)) << error;
    EXPECT_NE(at(image, 200, 60), at(original, 200, 60));
    ASSERT_TRUE(fl::polarCoordinates(image, fl::PolarMode::PolarToRectangular, nullptr, error));
    // Block centres away from the dense polar centre (bottom rows) and the angular seam survive exactly;
    // block edges are blended by bilinear resampling, so they are not compared.
    int matching = 0, total = 0;
    for (std::uint32_t y = 48; y < 208; y += 32)
        for (std::uint32_t x = 48; x < 208; x += 32) {
            ++total;
            const auto a = at(image, x, y), b = at(original, x, y);
            matching += (std::abs(a[0] - b[0]) <= 2 && std::abs(a[1] - b[1]) <= 2) ? 1 : 0;
        }
    EXPECT_EQ(matching, total);
}

TEST(FiltersDistort, DisplaceMovesByTheMap) {
    auto image = coordinates();
    const auto map = paint(64, 64, [](std::uint32_t, std::uint32_t) { return Rgba{192, 128, 0, 255}; });  // +0.25·2 = +0.5
    std::string error;
    ASSERT_TRUE(fl::displace(image, map, 10.0F, 10.0F, false, nullptr, error)) << error;
    // (192/255 − 0.5)·2·10·1.28 ≈ 6.45 px to the right, none vertically.
    EXPECT_NEAR(at(image, 100, 100)[0], 106, 1);
    EXPECT_NEAR(at(image, 100, 100)[1], 100, 1);
    TileStore bad(PixelFormat::A16, 4, 4);
    EXPECT_FALSE(fl::displace(image, bad, 10.0F, 10.0F, false, nullptr, error));
}

TEST(FiltersDistort, StylizeFilters) {
    const auto edge = [](std::uint32_t x, std::uint32_t) { return x < 128 ? Rgba{40, 40, 40, 255} : Rgba{220, 220, 220, 255}; };
    std::string error;
    auto embossed = paint(256, 64, edge);
    ASSERT_TRUE(fl::emboss(embossed, 0.0F, 2.0F, 1.0F, nullptr, error)) << error;
    EXPECT_NEAR(at(embossed, 40, 30)[0], 128, 1) << "flat areas become mid grey";
    EXPECT_GT(at(embossed, 127, 30)[0], 200) << "light from the right lifts the step";
    auto edges = paint(256, 64, edge);
    ASSERT_TRUE(fl::findEdges(edges, nullptr, error));
    EXPECT_EQ(at(edges, 40, 30)[0], 255) << "white background";
    EXPECT_LT(at(edges, 128, 30)[0], 150) << "dark edge";
    auto glowing = paint(256, 64, edge);
    ASSERT_TRUE(fl::glowingEdges(glowing, 2, 10.0F, 3, nullptr, error));
    EXPECT_LT(at(glowing, 40, 30)[0], 5) << "black background";
    EXPECT_GT(at(glowing, 128, 30)[0], 100) << "bright edge";
    auto oil = paint(256, 64, [](std::uint32_t x, std::uint32_t y) {
        const int base = x < 128 ? 60 : 200;
        return Rgba{static_cast<std::uint8_t>(base + (x * 7 + y * 3) % 9), 60, 60, 255};
    });
    ASSERT_TRUE(fl::oilPaint(oil, 1.0F, 3.0F, nullptr, error));
    EXPECT_NEAR(at(oil, 60, 30)[0], 64, 5);
    EXPECT_NEAR(at(oil, 200, 30)[0], 204, 5);
}

TEST(FiltersDistort, DiffuseGlowBrightensAroundHighlights) {
    auto image = paint(256, 256, [](std::uint32_t x, std::uint32_t y) {
        return (x > 120 && x < 136 && y > 120 && y < 136) ? Rgba{255, 255, 255, 255} : Rgba{40, 40, 40, 255};
    });
    std::string error;
    ASSERT_TRUE(fl::diffuseGlow(image, 0.0F, 10.0F, 15.0F, 1, nullptr, error)) << error;
    EXPECT_GT(at(image, 142, 128)[0], 50) << "glow spreads beyond the highlight";
    EXPECT_EQ(at(image, 10, 10)[0], 40) << "far away nothing changes (no grain)";
}

TEST(FiltersDistort, RenderFilters) {
    std::string error;
    auto vignetted = paint(256, 256, [](std::uint32_t, std::uint32_t) { return Rgba{200, 200, 200, 255}; });
    ASSERT_TRUE(fl::vignette(vignetted, 1.0F, 0.5F, 0.4F, nullptr, error)) << error;
    EXPECT_EQ(at(vignetted, 128, 128)[0], 200);
    EXPECT_LT(at(vignetted, 2, 2)[0], 20);
    TileStore sky(PixelFormat::RGBA8, 256, 256);
    ASSERT_TRUE(fl::clouds(sky, {1, 1, 1, 1}, {0, 0, 0.5F, 1}, 42, nullptr, error)) << error;
    const auto a = at(sky, 10, 10), b = at(sky, 200, 180);
    EXPECT_EQ(a[3], 255);
    EXPECT_NE(a, b);
    EXPECT_EQ(a[0], a[1]) << "between the two colours";
    auto shifted = paint(256, 256, [](std::uint32_t x, std::uint32_t) {
        return x == 250 ? Rgba{255, 255, 255, 255} : Rgba{0, 0, 0, 255};
    });
    ASSERT_TRUE(fl::chromaticAberration(shifted, 5.0F, -5.0F, nullptr, error)) << error;
    EXPECT_EQ(at(shifted, 250, 128)[1], 255) << "green stays";
    EXPECT_LT(at(shifted, 250, 128)[0], 128) << "red moved";
}

TEST(FiltersDistort, Validation) {
    auto image = coordinates(64);
    std::string error;
    EXPECT_FALSE(fl::twirl(image, 2000.0F, nullptr, error));
    EXPECT_FALSE(fl::pinch(image, 101.0F, nullptr, error));
    EXPECT_FALSE(fl::wave(image, 1.0F, 1.0F, true, true, nullptr, error));
    EXPECT_FALSE(fl::emboss(image, 0.0F, 20.0F, 1.0F, nullptr, error));
    EXPECT_FALSE(fl::glowingEdges(image, 0, 1.0F, 1, nullptr, error));
    EXPECT_FALSE(fl::vignette(image, 2.0F, 0.5F, 0.5F, nullptr, error));
    EXPECT_FALSE(fl::clouds(image, {2, 0, 0, 1}, {0, 0, 0, 1}, 1, nullptr, error));
    const auto mask = croptop::selection::rasterizeRect(64, 64, {0, 0, 10, 10}, false);
    const auto original = image;
    ASSERT_TRUE(fl::twirl(image, 180.0F, &mask, error));
    EXPECT_EQ(at(image, 40, 40), at(original, 40, 40));
}

TEST(FiltersDistort, TimingOn24Megapixels) {
    CROPTOP_REQUIRE_STRESS();
    TileStore image(PixelFormat::RGBA8, 6000, 4000);
    croptop::TileBuffer t(image.tile_byte_size(), 0);
    for (std::size_t i = 0; i < t.size(); ++i) t[i] = static_cast<std::uint8_t>(i * 13U);
    for (std::int32_t ty = 0; ty * 256 < 4000; ++ty)
        for (std::int32_t tx = 0; tx * 256 < 6000; ++tx) ASSERT_TRUE(image.writeTile({tx, ty}, t));
    const auto time = [&](const char* name, const std::function<bool(TileStore&, std::string&)>& f) {
        TileStore copy(image);
        std::string error;
        const auto start = std::chrono::steady_clock::now();
        ASSERT_TRUE(f(copy, error)) << error;
        std::printf("[filters] %s on 24 MP: %.0f ms\n", name, std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - start).count());
    };
    time("twirl", [](TileStore& c, std::string& e) { return fl::twirl(c, 90.0F, nullptr, e); });
    time("wave", [](TileStore& c, std::string& e) { return fl::wave(c, 50.0F, 10.0F, true, true, nullptr, e); });
    time("oil paint", [](TileStore& c, std::string& e) { return fl::oilPaint(c, 1.0F, 3.0F, nullptr, e); });
    time("diffuse glow", [](TileStore& c, std::string& e) { return fl::diffuseGlow(c, 3.0F, 5.0F, 10.0F, 1, nullptr, e); });
    time("clouds", [](TileStore& c, std::string& e) { return fl::clouds(c, {1, 1, 1, 1}, {0, 0, 0, 1}, 1, nullptr, e); });
}

}  // namespace
