// Tests for Puppet Warp.
#include "puppet_warp.h"

#include <gtest/gtest.h>

#include <cmath>
#include <cstdint>
#include <functional>

#include "pixel_plane.h"

namespace {

using croptop::PixelFormat;
using croptop::TileStore;
using croptop::selection::Point;
namespace puppet = croptop::puppet;

constexpr int kW = 320, kH = 220;

TileStore fill(const std::function<std::array<float, 4>(int, int)>& pixel) {
    TileStore s(PixelFormat::RGBA8, kW, kH);
    for (std::int32_t ty = 0; ty * 256 < kH; ++ty)
        for (std::int32_t tx = 0; tx * 256 < kW; ++tx) {
            croptop::TileBuffer t(s.tile_byte_size(), 0);
            for (int y = 0; y < 256; ++y)
                for (int x = 0; x < 256; ++x) {
                    const int gx = tx * 256 + x, gy = ty * 256 + y;
                    if (gx >= kW || gy >= kH) continue;
                    const auto v = pixel(gx, gy);
                    croptop::paint::detail::writePixel(PixelFormat::RGBA8, t.data() + (static_cast<std::size_t>(y) * 256 + x) * 4, v.data());
                }
            EXPECT_TRUE(s.writeTile({tx, ty}, t));
        }
    return s;
}

std::array<float, 4> at(const TileStore& s, int x, int y) {
    const auto tile = s.readTile({x / 256, y / 256});
    std::array<float, 4> v{};
    if (tile) croptop::paint::detail::readPixel(s.format(), tile->data() + (static_cast<std::size_t>(y % 256) * 256 + x % 256) * s.bytes_per_pixel(), v.data());
    return v;
}

/** A textured bar [60, 260) × [90, 130). */
bool inBar(int x, int y) { return x >= 60 && x < 260 && y >= 90 && y < 130; }
std::array<float, 4> barColour(int x, int y) {
    return {0.2F + 0.6F * static_cast<float>((x / 8 + y / 8) % 2), 0.1F + 0.8F * static_cast<float>(x - 60) / 200.0F, 0.5F, 1.0F};
}
TileStore bar() {
    return fill([](int x, int y) { return inBar(x, y) ? barColour(x, y) : std::array<float, 4>{0, 0, 0, 0}; });
}

double length(Point a, Point b) { return std::hypot(a.x - b.x, a.y - b.y); }

TEST(PuppetWarp, MeshCoversContentAndPinsAttach) {
    const auto layer = bar();
    std::string error;
    auto mesh = puppet::PuppetMesh::build(layer, {}, error);
    ASSERT_NE(mesh, nullptr) << error;
    EXPECT_GT(mesh->triangleCount(), 100U);
    EXPECT_LT(mesh->vertexCount(), 3000U);
    // Every content corner is on the mesh; far-away points are not.
    for (const Point p : {Point{60.5, 90.5}, Point{259.5, 90.5}, Point{60.5, 129.5}, Point{259.5, 129.5}, Point{160, 110}}) EXPECT_GE(mesh->addPin(p), 0) << p.x << ", " << p.y;
    EXPECT_EQ(mesh->addPin({10, 10}), -1);
    EXPECT_EQ(mesh->addPin({160, 200}), -1);
    EXPECT_EQ(mesh->pinCount(), 5U);
    EXPECT_TRUE(mesh->removePin(4));
    EXPECT_FALSE(mesh->removePin(9));
    // Vertices stay near the content (mesh margin ≈ one spacing + expansion).
    for (const auto& v : mesh->restVertices()) {
        EXPECT_GT(v.x, 20);
        EXPECT_LT(v.x, 300);
        EXPECT_GT(v.y, 50);
        EXPECT_LT(v.y, 170);
    }
}

TEST(PuppetWarp, UnmovedPinsRenderTheSourceUnchanged) {
    const auto layer = bar();
    std::string error;
    auto mesh = puppet::PuppetMesh::build(layer, {}, error);
    ASSERT_NE(mesh, nullptr) << error;
    ASSERT_GE(mesh->addPin({70, 110}), 0);
    ASSERT_GE(mesh->addPin({250, 110}), 0);
    mesh->solve(puppet::Mode::Rigid);
    const auto out = mesh->render(2, error);
    ASSERT_TRUE(out) << error;
    for (int y = 0; y < kH; y += 2)
        for (int x = 0; x < kW; x += 2) {
            const auto a = at(layer, x, y), b = at(*out, x, y);
            for (int k = 0; k < 4; ++k) ASSERT_NEAR(a[static_cast<std::size_t>(k)], b[static_cast<std::size_t>(k)], 1.5F / 255.0F) << x << ", " << y;
        }
}

TEST(PuppetWarp, MovingEveryPinTogetherTranslates) {
    const auto layer = bar();
    std::string error;
    auto mesh = puppet::PuppetMesh::build(layer, {}, error);
    ASSERT_NE(mesh, nullptr) << error;
    const int a = mesh->addPin({70, 100}), b = mesh->addPin({250, 120});
    mesh->pin(a).target = {70 + 20, 100 - 30};
    mesh->pin(b).target = {250 + 20, 120 - 30};
    mesh->solve(puppet::Mode::Rigid);
    const auto out = mesh->render(0, error);
    ASSERT_TRUE(out) << error;
    int checked = 0;
    for (int y = 62; y < 98; ++y)
        for (int x = 82; x < 278; ++x) {
            const auto got = at(*out, x, y), want = barColour(x - 20, y + 30);
            ASSERT_NEAR(got[3], 1.0F, 0.02F) << x << ", " << y;
            // The checker edges resample, the gradient must match closely.
            ASSERT_NEAR(got[1], want[1], 0.02F) << x << ", " << y;
            ++checked;
        }
    EXPECT_GT(checked, 5000);
    EXPECT_NEAR(at(*out, 160, 110)[3], 0.0F, 1e-6F) << "the old position is empty";
}

TEST(PuppetWarp, RigidTurnsNormalScalesDistortShears) {
    const auto layer = bar();
    std::string error;
    for (const auto mode : {puppet::Mode::Rigid, puppet::Mode::Normal, puppet::Mode::Distort}) {
        auto mesh = puppet::PuppetMesh::build(layer, {}, error);
        ASSERT_NE(mesh, nullptr) << error;
        ASSERT_GE(mesh->addPin({70, 110}), 0);
        const int right = mesh->addPin({250, 110});
        // Swing the right end up around the left pin by 25°, and pull it 15 % farther out.
        const double angle = -25.0 * 3.14159265358979 / 180.0, reach = 180.0 * 1.15;
        mesh->pin(right).target = {70 + reach * std::cos(angle), 110 + reach * std::sin(angle)};
        mesh->solve(mode);
        const auto& rest = mesh->restVertices();
        const auto& now = mesh->deformedVertices();
        // Mean edge-length ratio over the mesh.
        double ratio = 0;
        int edges = 0;
        for (const auto& tri : mesh->triangles())
            for (int k = 0; k < 3; ++k) {
                const auto i = tri[static_cast<std::size_t>(k)], j = tri[static_cast<std::size_t>((k + 1) % 3)];
                ratio += length(now[i], now[j]) / length(rest[i], rest[j]);
                ++edges;
            }
        ratio /= edges;
        // Mean local rotation of the triangles at the swung end (rest centroid x > 230).
        double dot = 0, crs = 0;
        for (const auto& tri : mesh->triangles()) {
            if ((rest[tri[0]].x + rest[tri[1]].x + rest[tri[2]].x) / 3 < 230) continue;
            for (int k = 0; k < 3; ++k) {
                const auto i = tri[static_cast<std::size_t>(k)], j = tri[static_cast<std::size_t>((k + 1) % 3)];
                const double ex = rest[j].x - rest[i].x, ey = rest[j].y - rest[i].y, fx = now[j].x - now[i].x, fy = now[j].y - now[i].y;
                dot += ex * fx + ey * fy;
                crs += ex * fy - ey * fx;
            }
        }
        const double turn = std::atan2(crs, dot) * 180.0 / 3.14159265358979;
        if (mode == puppet::Mode::Rigid) {
            EXPECT_NEAR(ratio, 1.0, 0.08) << "rigid keeps lengths";
            EXPECT_NEAR(turn, -25.0, 6.0) << "rigid turns the arm's end with the swing";
        } else if (mode == puppet::Mode::Normal) {
            EXPECT_GT(ratio, 1.08) << "normal scales with the pull";
            EXPECT_NEAR(turn, -25.0, 6.0);
        } else {
            EXPECT_LT(std::abs(turn), 16.0) << "distort shears more than it turns";
        }
        const auto out = mesh->render(0, error);
        ASSERT_TRUE(out) << error;
        // The right end moved up: content near the new right pin, nothing at the old right end.
        const auto target = mesh->pin(right).target;
        EXPECT_GT(at(*out, static_cast<int>(target.x), static_cast<int>(target.y))[3], 0.9F);
        EXPECT_LT(at(*out, 245, 110)[3], 0.1F);
        EXPECT_GT(at(*out, 70, 110)[3], 0.9F) << "the left pin holds";
    }
}

TEST(PuppetWarp, PinsReachTargetsAndUnpinnedPartsStay) {
    // Two separate blobs: pins only on the left one.
    const auto layer = fill([](int x, int y) {
        const bool left = std::hypot(x - 80.0, y - 110.0) < 40, right = std::hypot(x - 240.0, y - 110.0) < 40;
        return left || right ? std::array<float, 4>{0.8F, 0.3F, 0.2F, 1.0F} : std::array<float, 4>{0, 0, 0, 0};
    });
    std::string error;
    puppet::MeshSpec spec;
    spec.density = puppet::Density::More;
    auto mesh = puppet::PuppetMesh::build(layer, spec, error);
    ASSERT_NE(mesh, nullptr) << error;
    const int a = mesh->addPin({60, 110}), b = mesh->addPin({100, 110});
    ASSERT_GE(a, 0);
    ASSERT_GE(b, 0);
    mesh->pin(a).target = {60, 90};
    mesh->pin(b).target = {100, 130};
    const int rounds = mesh->solve(puppet::Mode::Rigid);
    EXPECT_LT(rounds, 64) << "converges";
    const auto& rest = mesh->restVertices();
    const auto& now = mesh->deformedVertices();
    for (std::size_t i = 0; i < rest.size(); ++i)
        if (rest[i].x > 170) {
            EXPECT_LT(length(rest[i], now[i]), 0.05) << "unpinned blob moved at " << rest[i].x << ", " << rest[i].y;
        }
    std::size_t moved = 0;
    for (std::size_t i = 0; i < rest.size(); ++i) moved += rest[i].x < 150 && length(rest[i], now[i]) > 5 ? 1U : 0U;
    EXPECT_GT(moved, 10U);
    // Solving again from the converged pose keeps it (the warm start is near a fixed point).
    const auto before = now;
    mesh->solve(puppet::Mode::Rigid, 4);
    double drift = 0;
    for (std::size_t i = 0; i < rest.size(); ++i) drift = std::max(drift, length(before[i], mesh->deformedVertices()[i]));
    EXPECT_LT(drift, 0.5);
}

TEST(PuppetWarp, Validation) {
    std::string error;
    TileStore empty(PixelFormat::RGBA8, 64, 64);
    EXPECT_EQ(puppet::PuppetMesh::build(empty, {}, error), nullptr);
    EXPECT_FALSE(error.empty());
    TileStore mask(PixelFormat::A16, 64, 64);
    EXPECT_EQ(puppet::PuppetMesh::build(mask, {}, error), nullptr);
    puppet::MeshSpec bad;
    bad.expansion = 100;
    EXPECT_EQ(puppet::PuppetMesh::build(bar(), bad, error), nullptr);
    // Negative expansion shrinks the mesh but keeps it valid.
    puppet::MeshSpec shrink;
    shrink.expansion = -4;
    auto mesh = puppet::PuppetMesh::build(bar(), shrink, error);
    ASSERT_NE(mesh, nullptr) << error;
    EXPECT_EQ(mesh->addPin({std::nan(""), 3}), -1);
}

}  // namespace
