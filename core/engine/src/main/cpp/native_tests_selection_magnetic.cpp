// Tests for the Magnetic Lasso (live-wire) selection.
#include "selection_magnetic.h"

#include <gtest/gtest.h>

#include <chrono>
#include <cmath>
#include <cstdio>
#include <cstring>

namespace {

using croptop::PixelFormat;
using croptop::TileStore;
namespace sel = croptop::selection;

/** A dark square [50,150) × [40,120) on a light background, 200 × 160. */
TileStore squareImage() {
    TileStore s(PixelFormat::RGBA8, 200, 160);
    croptop::TileBuffer t(s.tile_byte_size(), 0);
    for (std::uint32_t y = 0; y < 160; ++y)
        for (std::uint32_t x = 0; x < 200; ++x) {
            const bool inside = x >= 50 && x < 150 && y >= 40 && y < 120;
            const std::uint8_t v = inside ? 40 : 210;
            const std::uint8_t px[4] = {v, v, v, 255};
            std::memcpy(t.data() + (static_cast<std::size_t>(y) * 256 + x) * 4, px, 4);
        }
    EXPECT_TRUE(s.writeTile({0, 0}, t));
    return s;
}

double distanceToSquareEdge(const sel::Point& p) {
    const double dx = std::min(std::abs(p.x - 50), std::abs(p.x - 150)), dy = std::min(std::abs(p.y - 40), std::abs(p.y - 120));
    const bool within_x = p.x >= 48 && p.x <= 152, within_y = p.y >= 38 && p.y <= 122;
    if (within_x && within_y) return std::min(dx, dy);
    return std::hypot(within_x ? 0 : std::min(std::abs(p.x - 50), std::abs(p.x - 150)), within_y ? 0 : std::min(std::abs(p.y - 40), std::abs(p.y - 120)));
}

TEST(MagneticLasso, SnapsAndFollowsEdges) {
    const auto image = squareImage();
    std::string error;
    sel::MagneticSpec spec;
    spec.frequency = 0;
    auto lasso = sel::MagneticLasso::begin(image, {45.0, 60.0}, spec, error);
    ASSERT_NE(lasso, nullptr) << error;
    const auto seed = lasso->committed().front();
    EXPECT_NEAR(seed.x, 50, 1.6) << "the seed snaps onto the square's left edge";

    // Live wire to a point on the right edge, around the top-left and top-right corners.
    const auto wire = lasso->update({154.0, 60.0});
    ASSERT_GT(wire.size(), 50U);
    for (const auto& p : wire) EXPECT_LE(distanceToSquareEdge(p), 2.0) << "(" << p.x << ", " << p.y << ")";
    EXPECT_NEAR(wire.back().x, 150, 1.6);

    lasso->fasten({154.0, 60.0});
    lasso->fasten({100.0, 125.0});
    EXPECT_EQ(lasso->fastenerCount(), 3U);
    const auto mask = lasso->close(false, error);
    ASSERT_TRUE(mask) << error;
    int inter = 0, uni = 0;
    for (std::int64_t y = 0; y < 160; ++y)
        for (std::int64_t x = 0; x < 200; ++x) {
            const bool a = sel::coverageAt(*mask, x, y) > 32768, b = x >= 50 && x < 150 && y >= 40 && y < 120;
            inter += a && b;
            uni += a || b;
        }
    EXPECT_GT(static_cast<double>(inter) / uni, 0.9) << "the closed outline selects the square";

    EXPECT_TRUE(lasso->undoFasten());
    EXPECT_EQ(lasso->fastenerCount(), 2U);
    EXPECT_TRUE(lasso->undoFasten());
    EXPECT_FALSE(lasso->undoFasten()) << "the seed stays";
}

TEST(MagneticLasso, AutoFasteningFlatAreasAndValidation) {
    const auto image = squareImage();
    std::string error;
    sel::MagneticSpec spec;
    spec.frequency = 100;  // a fastening point every 16 px
    auto lasso = sel::MagneticLasso::begin(image, {50.0, 60.0}, spec, error);
    ASSERT_NE(lasso, nullptr) << error;
    (void)lasso->update({150.0, 100.0});
    EXPECT_GE(lasso->fastenerCount(), 5U) << "long live wires drop fastening points automatically";

    // In a flat region the wire is (nearly) straight.
    sel::MagneticSpec flat_spec;
    flat_spec.frequency = 0;
    flat_spec.width = 2;
    auto flat = sel::MagneticLasso::begin(image, {10.5, 10.5}, flat_spec, error);
    ASSERT_NE(flat, nullptr) << error;
    const auto wire = flat->update({40.5, 30.5});
    double length = 0;
    for (std::size_t i = 1; i < wire.size(); ++i) length += std::hypot(wire[i].x - wire[i - 1].x, wire[i].y - wire[i - 1].y);
    EXPECT_LT(length, std::hypot(30.0, 20.0) * 1.15);

    sel::MagneticSpec bad;
    bad.width = 0;
    EXPECT_EQ(sel::MagneticLasso::begin(image, {1, 1}, bad, error), nullptr);
    EXPECT_EQ(sel::MagneticLasso::begin(image, {std::nan(""), 1}, {}, error), nullptr);
    EXPECT_EQ(sel::MagneticLasso::begin(TileStore(PixelFormat::A16, 8, 8), {1, 1}, {}, error), nullptr);
    EXPECT_TRUE(flat->update({std::nan(""), 0}).empty());
}

#ifdef NDEBUG
TEST(MagneticLasso, InteractiveUpdateCost) {
    TileStore image(PixelFormat::RGBA8, 2000, 2000);
    for (std::int32_t ty = 0; ty < 8; ++ty)
        for (std::int32_t tx = 0; tx < 8; ++tx) {
            croptop::TileBuffer t(image.tile_byte_size());
            for (std::size_t i = 0; i < t.size(); ++i) t[i] = static_cast<std::uint8_t>((i * 2654435761U >> 13) ^ (i / 1024));
            ASSERT_TRUE(image.writeTile({tx, ty}, t));
        }
    std::string error;
    auto lasso = sel::MagneticLasso::begin(image, {500, 500}, {10, 0.1F, 0}, error);
    ASSERT_NE(lasso, nullptr) << error;
    auto start = std::chrono::steady_clock::now();
    const auto wire = lasso->update({1200, 900});
    const double cold = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - start).count();
    EXPECT_FALSE(wire.empty());
    start = std::chrono::steady_clock::now();
    (void)lasso->update({1204, 903});  // the next pointer move: edge data cached
    const double warm = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - start).count();
    start = std::chrono::steady_clock::now();
    (void)lasso->update({560, 540});  // a short wire near the fastening point
    const double near = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - start).count();
    std::printf("[magnetic] noisy 4 MP image, 700 px wire: cold %.1f ms, next move %.1f ms; 70 px wire %.1f ms\n", cold, warm, near);
}
#endif

}  // namespace
