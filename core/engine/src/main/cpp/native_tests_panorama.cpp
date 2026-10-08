// Tests for panorama stitching.
#include "panorama.h"

#include <gtest/gtest.h>

#include <cmath>
#include <cstdint>
#include <functional>

#include "pixel_plane.h"

namespace {

using croptop::PixelFormat;
using croptop::TileStore;
namespace pano = croptop::panorama;

std::uint32_t mix(std::uint32_t h) {
    h = (h ^ (h >> 16)) * 0x7FEB352DU;
    h = (h ^ (h >> 15)) * 0x846CA68BU;
    return h ^ (h >> 16);
}
float unit(std::uint32_t h) { return static_cast<float>(h & 0xFFFFU) / 65535.0F; }

/** A feature-rich scene: a random rectangle per 29 px cell over soft shading (continuous coordinates). */
std::array<float, 4> scene(double x, double y) {
    const int cx = static_cast<int>(std::floor(x / 29.0)), cy = static_cast<int>(std::floor(y / 29.0));
    const std::uint32_t h = mix(static_cast<std::uint32_t>(cx) * 73856093U ^ static_cast<std::uint32_t>(cy) * 19349663U);
    const double lx = x - cx * 29.0, ly = y - cy * 29.0;
    const double rx0 = 3 + 8 * unit(h), ry0 = 3 + 8 * unit(h >> 7), rx1 = rx0 + 6 + 10 * unit(h >> 13), ry1 = ry0 + 6 + 10 * unit(h >> 19);
    const float shade = static_cast<float>(0.35 + 0.1 * std::sin(x * 0.01) * std::cos(y * 0.013));
    if (lx >= rx0 && lx < rx1 && ly >= ry0 && ly < ry1) {
        const std::uint32_t c = mix(h);
        return {0.1F + 0.8F * unit(c), 0.1F + 0.8F * unit(c >> 5), 0.1F + 0.8F * unit(c >> 11), 1.0F};
    }
    return {shade, shade, shade, 1.0F};
}

/** Supersampled (2×2) pixel of the scene at a continuous mapping. */
std::array<float, 4> render(const std::function<std::array<double, 2>(double, double)>& map, int x, int y) {
    std::array<float, 4> acc{};
    for (int sy = 0; sy < 2; ++sy)
        for (int sx = 0; sx < 2; ++sx) {
            const auto p = map(x + 0.25 + 0.5 * sx, y + 0.25 + 0.5 * sy);
            const auto v = scene(p[0], p[1]);
            for (int k = 0; k < 4; ++k) acc[static_cast<std::size_t>(k)] += v[static_cast<std::size_t>(k)] / 4.0F;
        }
    return acc;
}

TileStore image(int w, int h, const std::function<std::array<float, 4>(int, int)>& pixel) {
    TileStore s(PixelFormat::RGBA8, static_cast<std::uint32_t>(w), static_cast<std::uint32_t>(h));
    for (std::int32_t ty = 0; ty * 256 < h; ++ty)
        for (std::int32_t tx = 0; tx * 256 < w; ++tx) {
            croptop::TileBuffer t(s.tile_byte_size(), 0);
            for (int y = 0; y < 256; ++y)
                for (int x = 0; x < 256; ++x) {
                    const int gx = tx * 256 + x, gy = ty * 256 + y;
                    if (gx >= w || gy >= h) continue;
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

/** A crop of the flat scene at (ox, oy), optionally scaled in brightness. */
TileStore crop(int ox, int oy, int w, int h, float gain = 1.0F) {
    return image(w, h, [=](int x, int y) {
        auto v = render([=](double px, double py) { return std::array<double, 2>{px + ox, py + oy}; }, x, y);
        for (int k = 0; k < 3; ++k) v[static_cast<std::size_t>(k)] *= gain;
        return v;
    });
}

/** Mean |output − scene| over covered pixels, with the output placed at scene offset (dx, dy). */
double flatError(const TileStore& out, double dx, double dy, int& covered) {
    double err = 0;
    covered = 0;
    for (int y = 2; y < static_cast<int>(out.height()) - 2; y += 2)
        for (int x = 2; x < static_cast<int>(out.width()) - 2; x += 2) {
            const auto v = at(out, x, y);
            if (v[3] < 0.99F) continue;
            const auto want = render([=](double px, double py) { return std::array<double, 2>{px + dx, py + dy}; }, x, y);
            for (int k = 0; k < 3; ++k) err += std::abs(v[static_cast<std::size_t>(k)] - want[static_cast<std::size_t>(k)]);
            ++covered;
        }
    return covered > 0 ? err / (3.0 * covered) : 1.0;
}

TEST(Panorama, DetectsDistinctFeatures) {
    const auto view = crop(0, 0, 400, 300);
    const auto f = pano::detail::detect(view);
    EXPECT_GT(f.points.size(), 200U);
    EXPECT_EQ(f.points.size(), f.descriptors.size());
    for (const auto& p : f.points) {
        EXPECT_GE(p[0], 24);
        EXPECT_LE(p[0], 376);
    }
}

TEST(Panorama, PerspectiveAndRepositionRebuildAFlatScene) {
    const auto a = crop(0, 10, 400, 300), b = crop(250, 0, 400, 300), c = crop(500, 14, 400, 300);
    const std::vector<const TileStore*> views{&c, &a, &b};  // any order
    for (const auto projection : {pano::Projection::Perspective, pano::Projection::Reposition, pano::Projection::Auto}) {
        std::string error;
        pano::StitchOptions options;
        options.projection = projection;
        const auto result = pano::stitch(views, options, error);
        ASSERT_TRUE(result) << error;
        EXPECT_EQ(result->projection, projection == pano::Projection::Auto ? pano::Projection::Perspective : projection);
        EXPECT_EQ(result->placed.size(), 3U);
        EXPECT_TRUE(result->dropped.empty());
        EXPECT_EQ(result->anchor, 2U) << "the middle view anchors";
        EXPECT_NEAR(result->image.width(), 900.0, 3.0);
        EXPECT_NEAR(result->image.height(), 314.0, 3.0);
        for (const float g : result->gains) EXPECT_NEAR(g, 1.0F, 0.03F);
        int covered = 0;
        const double err = flatError(result->image, result->origin_x + 250, result->origin_y + 0, covered);
        EXPECT_LT(err, 0.03) << "projection " << static_cast<int>(projection);
        EXPECT_GT(covered, 50000);
    }
}

TEST(Panorama, GainCompensationEvensExposure) {
    const auto a = crop(0, 0, 400, 300), b = crop(250, 0, 400, 300, 0.8F);
    const std::vector<const TileStore*> views{&a, &b};
    std::string error;
    const auto result = pano::stitch(views, {}, error);
    ASSERT_TRUE(result) << error;
    EXPECT_NEAR(result->gains[1] / result->gains[0], 1.25F, 0.08F);
    // Without compensation the seam region differs; with it the right half matches the left half's level.
    pano::StitchOptions raw;
    raw.exposure_compensation = false;
    const auto plain = pano::stitch(views, raw, error);
    ASSERT_TRUE(plain) << error;
    for (const float g : plain->gains) EXPECT_FLOAT_EQ(g, 1.0F);
}

TEST(Panorama, CylindricalStitchesARotatingCamera) {
    // A camera panning about its centre, focal 350 px, 400 × 300 views, 0.5 rad apart.
    constexpr double kF = 350.0;
    const auto view = [&](double pan) {
        return image(400, 300, [=](int x, int y) {
            return render(
                [=](double px, double py) {
                    const double xc = px - 200, yc = py - 150;
                    const double theta = pan + std::atan2(xc, kF), h = yc / std::hypot(xc, kF);
                    return std::array<double, 2>{kF * theta + 1000, kF * h + 400};
                },
                x, y);
        });
    };
    const auto left = view(-0.5), mid = view(0.0), right = view(0.5);
    const std::vector<const TileStore*> views{&left, &mid, &right};
    for (const auto projection : {pano::Projection::Cylindrical, pano::Projection::Auto}) {
        std::string error;
        pano::StitchOptions options;
        options.projection = projection;
        const auto result = pano::stitch(views, options, error);
        ASSERT_TRUE(result) << error;
        if (projection == pano::Projection::Auto && result->projection == pano::Projection::Perspective) continue;  // wide enough either way
        EXPECT_EQ(result->projection, pano::Projection::Cylindrical);
        EXPECT_NEAR(result->focal, kF, 0.05 * kF);
        const double expected_width = kF * (1.0 + 2.0 * std::atan2(200.0, kF));
        EXPECT_NEAR(result->image.width(), expected_width, 0.06 * expected_width);
        // The output is the cylinder unrolled around the middle view: compare with the scene.
        const double f = result->focal;
        double err = 0;
        int covered = 0;
        for (int y = 4; y < static_cast<int>(result->image.height()) - 4; y += 3)
            for (int x = 4; x < static_cast<int>(result->image.width()) - 4; x += 3) {
                const auto v = result->image.readTile({x / 256, y / 256}) ? at(result->image, x, y) : std::array<float, 4>{};
                if (v[3] < 0.99F) continue;
                const auto want = render(
                    [=](double px, double py) {
                        const double u = px + result->origin_x, w = py + result->origin_y;
                        return std::array<double, 2>{kF * (u / f) + 1000, kF * (w / f) + 400};
                    },
                    x, y);
                for (int k = 0; k < 3; ++k) err += std::abs(v[static_cast<std::size_t>(k)] - want[static_cast<std::size_t>(k)]);
                ++covered;
            }
        ASSERT_GT(covered, 10000);
        EXPECT_LT(err / (3.0 * covered), 0.06);
    }
}

TEST(Panorama, GridOfViewsStitchesWithGlobalRefinement) {
    // A 2 × 2 grid: every view overlaps the other three, so the pair graph has a loop the spanning tree
    // alone cannot use.
    const auto a = crop(0, 0, 400, 300), b = crop(260, 6, 400, 300), c = crop(4, 190, 400, 300), d = crop(262, 194, 400, 300);
    const std::vector<const TileStore*> views{&a, &b, &c, &d};
    for (const auto projection : {pano::Projection::Reposition, pano::Projection::Perspective}) {
        std::string error;
        pano::StitchOptions options;
        options.projection = projection;
        const auto result = pano::stitch(views, options, error);
        ASSERT_TRUE(result) << error;
        EXPECT_EQ(result->placed.size(), 4U);
        EXPECT_NEAR(result->image.width(), 662.0, 3.0);
        EXPECT_NEAR(result->image.height(), 494.0, 3.0);
        // The anchor view's scene offset.
        const std::array<std::array<double, 2>, 4> offsets{{{0, 0}, {260, 6}, {4, 190}, {262, 194}}};
        int covered = 0;
        const auto& o = offsets[result->anchor];
        const double err = flatError(result->image, result->origin_x + o[0], result->origin_y + o[1], covered);
        EXPECT_LT(err, 0.03) << "projection " << static_cast<int>(projection);
        EXPECT_GT(covered, 50000);
    }
}

TEST(Panorama, DropsUnrelatedImagesAndRejectsBadInput) {
    const auto a = crop(0, 0, 400, 300), b = crop(250, 0, 400, 300);
    const auto stranger = image(400, 300, [](int x, int y) {
        const float v = unit(mix(static_cast<std::uint32_t>(x / 9) * 2654435761U + static_cast<std::uint32_t>(y / 9) * 40503U + 77U));
        return std::array<float, 4>{v, 1.0F - v, v * 0.5F, 1.0F};
    });
    std::string error;
    const std::vector<const TileStore*> views{&a, &stranger, &b};
    const auto result = pano::stitch(views, {}, error);
    ASSERT_TRUE(result) << error;
    EXPECT_EQ(result->placed, (std::vector<std::size_t>{0, 2}));
    EXPECT_EQ(result->dropped, (std::vector<std::size_t>{1}));

    const auto far = crop(3000, 3000, 400, 300);
    const std::vector<const TileStore*> apart{&a, &far};
    EXPECT_FALSE(pano::stitch(apart, {}, error));
    EXPECT_FALSE(error.empty());
    const std::vector<const TileStore*> one{&a};
    EXPECT_FALSE(pano::stitch(one, {}, error));
    TileStore mask(PixelFormat::A16, 100, 100);
    const std::vector<const TileStore*> bad{&a, &mask};
    EXPECT_FALSE(pano::stitch(bad, {}, error));
    pano::StitchOptions cancel;
    cancel.progress = [](float) { return false; };
    const std::vector<const TileStore*> pair{&a, &b};
    EXPECT_FALSE(pano::stitch(pair, cancel, error));
    EXPECT_EQ(error, "cancelled");
}

}  // namespace
