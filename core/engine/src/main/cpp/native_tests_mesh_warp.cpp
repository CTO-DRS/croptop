// Tests for Mesh Warp.
#include "mesh_warp.h"

#include <gtest/gtest.h>

#include <array>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <string>

namespace {

namespace warp = croptop::warp;
using croptop::PixelFormat;
using croptop::TileStore;

constexpr std::uint32_t kW = 300, kH = 200;

/** R = x / 2, G = y / 2, B = 100, opaque: smooth, so bicubic resampling is accurate to a level or two. */
TileStore ramp() {
    TileStore store(PixelFormat::RGBA8, kW, kH);
    for (std::uint32_t ty = 0; ty * croptop::kTileEdge < kH; ++ty) {
        for (std::uint32_t tx = 0; tx * croptop::kTileEdge < kW; ++tx) {
            croptop::TileBuffer tile(store.tile_byte_size(), 0);
            for (std::uint32_t y = 0; y < croptop::kTileEdge && ty * croptop::kTileEdge + y < kH; ++y) {
                for (std::uint32_t x = 0; x < croptop::kTileEdge && tx * croptop::kTileEdge + x < kW; ++x) {
                    const std::size_t at = (static_cast<std::size_t>(y) * croptop::kTileEdge + x) * 4;
                    tile[at] = static_cast<std::uint8_t>((tx * croptop::kTileEdge + x) / 2);
                    tile[at + 1] = static_cast<std::uint8_t>((ty * croptop::kTileEdge + y) / 2);
                    tile[at + 2] = 100;
                    tile[at + 3] = 255;
                }
            }
            EXPECT_TRUE(store.writeTile({static_cast<std::int32_t>(tx), static_cast<std::int32_t>(ty)}, tile));
        }
    }
    return store;
}

std::array<int, 4> at(const TileStore& s, std::uint32_t x, std::uint32_t y) {
    std::array<int, 4> out{};
    const auto tile = s.readTile({static_cast<std::int32_t>(x / croptop::kTileEdge), static_cast<std::int32_t>(y / croptop::kTileEdge)});
    if (tile == nullptr) return out;
    const std::size_t o = (static_cast<std::size_t>(y % croptop::kTileEdge) * croptop::kTileEdge + x % croptop::kTileEdge) * 4;
    for (int i = 0; i < 4; ++i) out[static_cast<std::size_t>(i)] = (*tile)[o + static_cast<std::size_t>(i)];
    return out;
}

TEST(MeshWarp, TheIdentityMeshLeavesEveryPixelUnchanged) {
    const auto source = ramp();
    std::string error;
    const auto out = warp::apply(source, warp::Mesh::identity(kW, kH, 5, 4), {}, error);
    ASSERT_TRUE(out) << error;
    for (std::uint32_t y = 0; y < kH; y += 7) {
        for (std::uint32_t x = 0; x < kW; x += 5) EXPECT_EQ(at(*out, x, y), at(source, x, y)) << x << "," << y;
    }
}

TEST(MeshWarp, TranslatingEveryNodeShiftsTheImage) {
    const auto source = ramp();
    auto mesh = warp::Mesh::identity(kW, kH, 4, 4);
    for (auto& n : mesh.nodes) {
        n.x += 40;
        n.y += 20;
    }
    std::string error;
    const auto out = warp::apply(source, mesh, {}, error);
    ASSERT_TRUE(out) << error;
    for (const auto [x, y] : {std::pair{100U, 80U}, {200U, 150U}, {150U, 100U}}) {
        const auto moved = at(*out, x, y), original = at(source, x - 40, y - 20);
        EXPECT_NEAR(moved[0], original[0], 2) << x << "," << y;
        EXPECT_NEAR(moved[1], original[1], 2);
        EXPECT_EQ(moved[3], 255);
    }
    EXPECT_EQ(at(*out, 10, 10)[3], 0) << "the uncovered corner is transparent";
}

TEST(MeshWarp, ScalingTheMeshScalesTheContent) {
    const auto source = ramp();
    auto mesh = warp::Mesh::identity(kW, kH, 4, 4);
    for (auto& n : mesh.nodes) {
        n.x *= 0.5;
        n.y *= 0.5;
    }
    std::string error;
    const auto out = warp::apply(source, mesh, {}, error);
    ASSERT_TRUE(out) << error;
    const auto shrunk = at(*out, 50, 40), original = at(source, 100, 80);
    EXPECT_NEAR(shrunk[0], original[0], 2);
    EXPECT_NEAR(shrunk[1], original[1], 2);
    EXPECT_EQ(at(*out, 250, 180)[3], 0);
}

TEST(MeshWarp, DraggingOneNodeMovesTheContentAtThatNodeAndKeepsTheEdges) {
    const auto source = ramp();
    auto mesh = warp::Mesh::identity(kW, kH, 5, 5);
    auto& centre = mesh.nodes[2 * 5 + 2];
    const warp::Point rest = centre;
    centre.x += 30;
    std::string error;
    const auto out = warp::apply(source, mesh, {.spacing = 4}, error);
    ASSERT_TRUE(out) << error;
    const auto landed = at(*out, static_cast<std::uint32_t>(std::lround(rest.x + 30)), static_cast<std::uint32_t>(std::lround(rest.y)));
    const auto expected = at(source, static_cast<std::uint32_t>(std::lround(rest.x)), static_cast<std::uint32_t>(std::lround(rest.y)));
    EXPECT_NEAR(landed[0], expected[0], 3);
    EXPECT_NEAR(landed[1], expected[1], 3);
    // The outer boundary nodes did not move, so the frame's corners still show the source's corners.
    EXPECT_NEAR(at(*out, 2, 2)[0], at(source, 2, 2)[0], 2);
    EXPECT_NEAR(at(*out, kW - 3, kH - 3)[1], at(source, kW - 3, kH - 3)[1], 2);
    EXPECT_NEAR(warp::surface(mesh, 2, 2).x, rest.x + 30, 1e-9);
}

TEST(MeshWarp, RejectsInvalidMeshesAndOptions) {
    const auto source = ramp();
    std::string error;
    warp::Mesh bad;
    bad.columns = 1;
    EXPECT_FALSE(warp::apply(source, bad, {}, error));
    auto mismatch = warp::Mesh::identity(kW, kH, 4, 4);
    mismatch.nodes.pop_back();
    EXPECT_FALSE(warp::apply(source, mismatch, {}, error));
    auto nan = warp::Mesh::identity(kW, kH, 4, 4);
    nan.nodes[3].x = std::nan("");
    EXPECT_FALSE(warp::apply(source, nan, {}, error));
    EXPECT_FALSE(warp::apply(source, warp::Mesh::identity(kW, kH), {.spacing = 0}, error));
}

}  // namespace
