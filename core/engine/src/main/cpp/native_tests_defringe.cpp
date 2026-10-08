// Tests for manual defringe.
#include "defringe.h"

#include <gtest/gtest.h>

#include <algorithm>
#include <cmath>
#include <cstring>
#include <functional>

namespace {

using croptop::PixelFormat;
using croptop::TileStore;
using Rgba = std::array<std::uint8_t, 4>;

TileStore paint(std::uint32_t w, std::uint32_t h, const std::function<Rgba(std::uint32_t, std::uint32_t)>& f) {
    TileStore s(PixelFormat::RGBA8, w, h);
    croptop::TileBuffer t(s.tile_byte_size(), 0);
    for (std::uint32_t y = 0; y < h; ++y)
        for (std::uint32_t x = 0; x < w; ++x) {
            const auto c = f(x, y);
            std::memcpy(t.data() + (static_cast<std::size_t>(y) * 256 + x) * 4, c.data(), 4);
        }
    EXPECT_TRUE(s.writeTile({0, 0}, t));
    return s;
}

Rgba at(const TileStore& s, std::uint32_t x, std::uint32_t y) {
    Rgba c{};
    std::memcpy(c.data(), s.readTile({0, 0})->data() + (static_cast<std::size_t>(y) * 256 + x) * 4, 4);
    return c;
}

int spread(const Rgba& c) { return std::max({c[0], c[1], c[2]}) - std::min({c[0], c[1], c[2]}); }

TEST(Defringe, RemovesPurpleAndGreenFringesOnlyAtEdges) {
    // Dark branch (x < 100) against bright sky, with a 2 px purple fringe and a 2 px green fringe at the
    // boundary; a flat purple flower and flat green leaf away from any edge.
    const auto scene = paint(200, 120, [](std::uint32_t x, std::uint32_t y) {
        if (y >= 80 && x >= 20 && x < 60) return Rgba{150, 60, 170, 255};  // flat purple flower
        if (y >= 80 && x >= 130 && x < 170) return Rgba{70, 150, 60, 255};  // flat green leaf
        if (y < 60) {
            if (x == 99 || x == 100) return Rgba{140, 50, 170, 255};  // purple fringe
            if (x == 101 || x == 102) return Rgba{90, 170, 80, 255};  // green fringe
            return x < 99 ? Rgba{20, 20, 20, 255} : Rgba{235, 235, 235, 255};
        }
        return Rgba{128, 128, 128, 255};
    });
    croptop::lens::DefringeSpec spec;
    spec.purple_amount = 20;
    spec.green_amount = 20;
    std::string error;
    const auto clean = croptop::lens::defringe(scene, spec, error);
    ASSERT_TRUE(clean) << error;
    EXPECT_LT(spread(at(*clean, 99, 30)), spread(at(scene, 99, 30)) / 4) << "purple fringe desaturated";
    EXPECT_LT(spread(at(*clean, 102, 30)), spread(at(scene, 102, 30)) / 4) << "green fringe desaturated";
    EXPECT_NEAR(spread(at(*clean, 40, 100)), spread(at(scene, 40, 100)), 3) << "flat purple flower keeps its colour";
    EXPECT_NEAR(spread(at(*clean, 150, 100)), spread(at(scene, 150, 100)), 3) << "flat green leaf keeps its colour";
    EXPECT_NEAR(at(*clean, 50, 30)[0], 20, 1) << "neutral pixels unchanged";
    EXPECT_EQ(at(*clean, 99, 30)[3], 255);

    croptop::lens::DefringeSpec purple_only;
    purple_only.purple_amount = 20;
    const auto partial = croptop::lens::defringe(scene, purple_only, error);
    ASSERT_TRUE(partial) << error;
    EXPECT_NEAR(spread(at(*partial, 102, 30)), spread(at(scene, 102, 30)), 3) << "green band untouched";

    EXPECT_EQ(at(*croptop::lens::defringe(scene, {}, error), 99, 30), at(scene, 99, 30)) << "identity";
    spec.purple_amount = 21;
    EXPECT_FALSE(croptop::lens::defringe(scene, spec, error));
    EXPECT_FALSE(croptop::lens::defringe(TileStore(PixelFormat::A16, 4, 4), purple_only, error));
}

}  // namespace
