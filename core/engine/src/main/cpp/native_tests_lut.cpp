// Tests for .cube/.3dl LUT parsing and the CPU reference.
#include "lut.h"
#include "selection_mask.h"
#include "tile_store.h"

#include <gtest/gtest.h>

#include <array>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <functional>
#include <random>
#include <sstream>
#include <string>

namespace {

namespace cl = croptop::color;
using croptop::PixelFormat;
using croptop::TileStore;
using Rgb = std::array<float, 3>;

/** A .cube 3D table of size n generated from f (red fastest). */
std::string cube3d(int n, const std::function<Rgb(float, float, float)>& f, const std::string& header = "") {
    std::ostringstream out;
    out << "# generated\nTITLE \"Test LUT\"\n" << header << "LUT_3D_SIZE " << n << "\n";
    for (int b = 0; b < n; ++b)
        for (int g = 0; g < n; ++g)
            for (int r = 0; r < n; ++r) {
                const auto v = f(r / float(n - 1), g / float(n - 1), b / float(n - 1));
                out << v[0] << " " << v[1] << " " << v[2] << "\n";
            }
    return out.str();
}

std::string threeDl(int n, int out_max, const std::function<Rgb(float, float, float)>& f, const std::string& header = "") {
    std::ostringstream out;
    out << header;
    for (int i = 0; i < n; ++i) out << (i * 1023 / (n - 1)) << (i + 1 < n ? " " : "\n");
    for (int r = 0; r < n; ++r)
        for (int g = 0; g < n; ++g)
            for (int b = 0; b < n; ++b) {
                const auto v = f(r / float(n - 1), g / float(n - 1), b / float(n - 1));
                out << std::lround(v[0] * out_max) << " " << std::lround(v[1] * out_max) << " " << std::lround(v[2] * out_max) << "\n";
            }
    return out.str();
}

void expectNear(const Rgb& a, const Rgb& b, float tolerance) {
    for (int c = 0; c < 3; ++c) EXPECT_NEAR(a[c], b[c], tolerance) << "channel " << c;
}

TEST(Lut, IdentityCubeIsExact) {
    std::string error;
    const auto lut = cl::parseCube(cube3d(17, [](float r, float g, float b) { return Rgb{r, g, b}; }), error);
    ASSERT_TRUE(lut) << error;
    EXPECT_EQ(lut->title, "Test LUT");
    EXPECT_EQ(lut->size_3d, 17U);
    for (const Rgb v : {Rgb{0, 0, 0}, Rgb{1, 1, 1}, Rgb{0.3F, 0.71F, 0.05F}, Rgb{0.999F, 0.001F, 0.5F}}) expectNear(lut->apply(v), v, 1e-5F);
}

TEST(Lut, TetrahedralIsExactForPerChannelLinearMaps) {
    // Channel mixing that is linear in r, g, b: tetrahedral interpolation reproduces it everywhere.
    const auto mix = [](float r, float g, float b) { return Rgb{0.6F * r + 0.3F * g + 0.1F * b, 0.2F * r + 0.8F * g, 1.0F - b}; };
    std::string error;
    const auto lut = cl::parseCube(cube3d(5, mix), error);
    ASSERT_TRUE(lut) << error;
    std::mt19937 random(3);
    std::uniform_real_distribution<float> unit(0.0F, 1.0F);
    for (int i = 0; i < 500; ++i) {
        const Rgb v{unit(random), unit(random), unit(random)};
        expectNear(lut->apply(v), mix(v[0], v[1], v[2]), 1e-5F);
    }
}

TEST(Lut, NonLinearCubeInterpolatesCloselyAndClampsOutside) {
    const auto curve = [](float r, float g, float b) { return Rgb{r * r, std::sqrt(g), 0.5F * (r + b)}; };
    std::string error;
    const auto lut = cl::parseCube(cube3d(33, curve), error);
    ASSERT_TRUE(lut) << error;
    expectNear(lut->apply({0.4F, 0.4F, 0.4F}), curve(0.4F, 0.4F, 0.4F), 2e-3F);
    expectNear(lut->apply({-1.0F, 2.0F, 0.0F}), curve(0.0F, 1.0F, 0.0F), 1e-5F);
}

TEST(Lut, OneDimensionalShaperAndDomains) {
    std::ostringstream text;
    text << "LUT_1D_SIZE 3\nLUT_1D_INPUT_RANGE 0 2\nLUT_3D_SIZE 2\n";
    text << "0 0 0\n0.25 0.25 0.25\n1 1 1\n";  // shaper: [0,2] → piecewise 0, .25, 1
    for (int b = 0; b < 2; ++b)
        for (int g = 0; g < 2; ++g)
            for (int r = 0; r < 2; ++r) text << r << " " << g << " " << b << "\n";
    std::string error;
    const auto lut = cl::parseCube(text.str(), error);
    ASSERT_TRUE(lut) << error;
    EXPECT_EQ(lut->size_1d, 3U);
    // Input 1.0 is the shaper's midpoint → 0.25; the identity 3D table keeps it.
    expectNear(lut->apply({1.0F, 0.5F, 2.0F}), {0.25F, 0.125F, 1.0F}, 1e-5F);
    const auto domain = cl::parseCube(cube3d(2, [](float r, float g, float b) { return Rgb{r, g, b}; }, "DOMAIN_MIN 0 0 0\nDOMAIN_MAX 2 2 2\n"), error);
    ASSERT_TRUE(domain) << error;
    expectNear(domain->apply({1.0F, 2.0F, 0.0F}), {0.5F, 1.0F, 0.0F}, 1e-5F);
}

TEST(Lut, Parses3dlWithBlueFastestOrderingAndDepths) {
    const auto swap = [](float r, float g, float b) { return Rgb{b, g, r}; };
    for (const auto& [depth, header] : std::array<std::pair<int, const char*>, 3>{{{1023, ""}, {4095, ""}, {65535, "3DMESH\nMesh 10 16\n"}}}) {
        std::string error;
        const auto lut = cl::parse3dl(threeDl(9, depth, swap, header), error);
        ASSERT_TRUE(lut) << error;
        EXPECT_EQ(lut->size_3d, 9U);
        expectNear(lut->apply({0.2F, 0.5F, 0.9F}), {0.9F, 0.5F, 0.2F}, 2e-3F);
    }
}

TEST(Lut, PicksTheParserByExtensionOrContent) {
    const auto identity = [](float r, float g, float b) { return Rgb{r, g, b}; };
    std::string error;
    EXPECT_TRUE(cl::parseLut(cube3d(2, identity), "Film.CUBE", error));
    EXPECT_TRUE(cl::parseLut(threeDl(2, 1023, identity), "grade.3dl", error));
    EXPECT_TRUE(cl::parseLut(threeDl(2, 1023, identity), "noext", error)) << error;
    EXPECT_TRUE(cl::parseLut(cube3d(2, identity), "noext", error)) << error;
}

TEST(Lut, RejectsMalformedFiles) {
    std::string error;
    EXPECT_FALSE(cl::parseCube("", error));
    EXPECT_FALSE(cl::parseCube("LUT_3D_SIZE 2\n0 0 0\n", error));
    EXPECT_NE(error.find("expected 8 data rows"), std::string::npos) << error;
    EXPECT_FALSE(cl::parseCube("LUT_3D_SIZE 1000\n", error));
    EXPECT_FALSE(cl::parseCube("LUT_3D_SIZE 2\n0 0 nan\n", error));
    EXPECT_FALSE(cl::parseCube("LUT_3D_SIZE 2\n0 0\n", error));
    EXPECT_FALSE(cl::parseCube("LUT_1D_SIZE 2\nDOMAIN_MIN 1 1 1\nDOMAIN_MAX 0 0 0\n0 0 0\n1 1 1\n", error));
    EXPECT_FALSE(cl::parse3dl("0 512 1023\n1 2 3\n", error));
    EXPECT_FALSE(cl::parse3dl("0 1023 512\n", error));
    EXPECT_FALSE(cl::parse3dl("Mesh 10 40\n0 1023\n", error));
}

TEST(Lut, AppliesToLayersWithStrengthAndMask) {
    TileStore layer(PixelFormat::RGBA8, 300, 100);
    for (std::int32_t tx = 0; tx < 2; ++tx) {
        croptop::TileBuffer t(layer.tile_byte_size(), 0);
        for (std::size_t i = 0; i < 256U * 256U; ++i) {
            t[i * 4] = 200;
            t[i * 4 + 1] = 100;
            t[i * 4 + 2] = 50;
            t[i * 4 + 3] = static_cast<std::uint8_t>(i % 256 < 128 ? 255 : 0);
        }
        ASSERT_TRUE(layer.writeTile({tx, 0}, t));
    }
    std::string error;
    const auto invert = cl::parseCube(cube3d(2, [](float r, float g, float b) { return Rgb{1 - r, 1 - g, 1 - b}; }), error);
    ASSERT_TRUE(invert) << error;
    const auto mask = croptop::selection::rasterizeRect(300, 100, {0, 0, 50, 100}, false);
    ASSERT_TRUE(cl::applyLut(layer, *invert, 0.5F, &mask, error)) << error;
    const auto px = [&](int x, int y) {
        const auto t = layer.readTile({x / 256, y / 256});
        const auto* p = t->data() + ((y % 256) * 256 + x % 256) * 4;
        return std::array<int, 4>{p[0], p[1], p[2], p[3]};
    };
    EXPECT_EQ(px(10, 10), (std::array<int, 4>{128, 128, 128, 255})) << "halfway to the inverse (200→55, 100→155, 50→205)";
    EXPECT_EQ(px(100, 10), (std::array<int, 4>{200, 100, 50, 255})) << "outside the mask";
    ASSERT_TRUE(cl::applyLut(layer, *invert, 1.0F, nullptr, error)) << error;
    EXPECT_EQ(px(200, 10), (std::array<int, 4>{200, 100, 50, 0})) << "transparent pixels are skipped";
    EXPECT_EQ(px(100, 10), (std::array<int, 4>{55, 155, 205, 255}));
    EXPECT_FALSE(cl::applyLut(layer, *invert, 1.5F, nullptr, error));
    EXPECT_FALSE(cl::applyLut(layer, cl::Lut{}, 1.0F, nullptr, error));
}

TEST(Lut, TextureLayoutMatchesTheTable) {
    std::string error;
    const auto lut = cl::parseCube(cube3d(3, [](float r, float g, float b) { return Rgb{r, g, b}; }), error);
    ASSERT_TRUE(lut) << error;
    const auto texels = lut->toTexture();
    ASSERT_EQ(texels.size(), 27U * 4U);
    // Texel (x=2, y=1, z=0): red 1, green 0.5, blue 0.
    const auto i = (0 * 9 + 1 * 3 + 2) * 4;
    EXPECT_FLOAT_EQ(texels[i], 1.0F);
    EXPECT_FLOAT_EQ(texels[i + 1], 0.5F);
    EXPECT_FLOAT_EQ(texels[i + 2], 0.0F);
    EXPECT_FLOAT_EQ(texels[i + 3], 1.0F);
}

TEST(Lut, SurvivesRandomMutations) {
    std::mt19937 random(11);
    const std::vector<std::string> seeds{cube3d(3, [](float r, float g, float b) { return Rgb{r, g, b}; }, "DOMAIN_MIN 0 0 0\n"),
                                         threeDl(3, 1023, [](float r, float g, float b) { return Rgb{b, r, g}; }, "3DMESH\nMesh 10 12\n")};
    int parsed = 0;
    for (int i = 0; i < 4000; ++i) {
        auto text = seeds[static_cast<std::size_t>(i) % seeds.size()];
        for (int e = 0; e < 1 + static_cast<int>(random() % 6); ++e) {
            const auto at = random() % text.size();
            switch (random() % 3) {
                case 0: text[at] = static_cast<char>(random()); break;
                case 1: text.insert(at, 1, "0123456789 .-e\n#"[random() % 16]); break;
                default: text.erase(at, 1 + random() % 4); break;
            }
        }
        std::string error;
        const auto lut = cl::parseLut(text, i % 2 ? "x.3dl" : "x.cube", error);
        if (lut) {
            ++parsed;
            const auto v = lut->apply({0.5F, 0.25F, 0.75F});
            (void)v;
        }
    }
    std::printf("[lut] %d / 4000 mutated files still parsed\n", parsed);
}

}  // namespace
