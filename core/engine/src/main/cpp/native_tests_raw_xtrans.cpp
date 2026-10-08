// Tests for directional X-Trans demosaicing.
#include "raw_demosaic.h"
#include "raw_xtrans.h"

#include <gtest/gtest.h>

#include <cmath>
#include <cstdio>
#include <cstring>
#include <functional>
#include <vector>

namespace {

namespace raw = croptop::raw;
using Rgb = std::array<double, 3>;

Rgb scene(double x, double y) {
    Rgb c{0.15 + 0.5 * x / 320.0, 0.25 + 0.4 * y / 240.0, 0.45};
    if (x + 0.6 * y > 260.0) c = {0.75, 0.12, 0.10};
    const double zx = x - 90.0, zy = y - 90.0;
    if (std::abs(zx) < 60 && std::abs(zy) < 60) {
        const double l = 0.45 + 0.3 * std::sin(0.0045 * (zx * zx + zy * zy));
        c = {l, l, l};
    }
    if (static_cast<int>(x) % 24 == 0 && y > 170) c = {0.05, 0.05, 0.05};
    if (static_cast<int>(y) % 20 == 0 && x > 200) c = {0.9, 0.9, 0.9};
    return c;
}

double psnrOf(const std::function<std::array<float, 3>(int, int)>& got, const std::function<Rgb(double, double)>& truth, int x0, int y0, int x1, int y1) {
    double mse = 0;
    std::size_t n = 0;
    for (int y = y0; y < y1; ++y)
        for (int x = x0; x < x1; ++x) {
            const auto p = got(x, y);
            const auto t = truth(x, y);
            for (int c = 0; c < 3; ++c) {
                const double d = p[static_cast<std::size_t>(c)] - t[static_cast<std::size_t>(c)];
                mse += d * d;
                ++n;
            }
        }
    mse /= static_cast<double>(n);
    return mse == 0 ? 99.0 : 10.0 * std::log10(1.0 / mse);
}

struct Planes {
    std::vector<float> cfa, r, g, b;
    std::vector<std::uint8_t> colour;
};

Planes directional(int w, int h, const std::function<Rgb(double, double)>& f) {
    const auto pattern = raw::CfaPattern::xtrans();
    Planes p;
    const auto n = static_cast<std::size_t>(w) * h;
    p.cfa.resize(n);
    p.colour.resize(n);
    p.r.resize(n);
    p.g.resize(n);
    p.b.resize(n);
    for (int y = 0; y < h; ++y)
        for (int x = 0; x < w; ++x) {
            const auto c = pattern.at(x, y);
            p.colour[static_cast<std::size_t>(y) * w + x] = c;
            p.cfa[static_cast<std::size_t>(y) * w + x] = static_cast<float>(f(x, y)[c]);
        }
    raw::detail::xtransDirectional(p.cfa, p.colour, p.r, p.g, p.b, w, h);
    return p;
}

TEST(RawXTrans, DirectionalBeatsFastAndKeepsFlatFieldsExact) {
    const int w = 320, h = 240;
    // Compare both algorithms through the same tiled production path on identical quantized input.
    std::vector<std::uint16_t> photosites(static_cast<std::size_t>(w) * h);
    const auto pattern = raw::CfaPattern::xtrans();
    for (int y = 0; y < h; ++y)
        for (int x = 0; x < w; ++x) photosites[static_cast<std::size_t>(y) * w + x] = static_cast<std::uint16_t>(std::lround(scene(x, y)[pattern.at(x, y)] * 65535.0));
    raw::CfaImage image{static_cast<std::uint32_t>(w), static_cast<std::uint32_t>(h), photosites, pattern, {0, 0, 0}, 65535.0F};
    std::string error;
    const auto render = [&](raw::Demosaic method) {
        raw::DevelopSpec spec;
        spec.method = method;
        spec.output_format = croptop::PixelFormat::RGBA32F;
        spec.highlights = raw::Highlights::Clip;
        return raw::develop(image, spec, error);
    };
    const auto directional_result = render(raw::Demosaic::XTransDirectional);
    ASSERT_TRUE(directional_result) << error;
    const auto fast = render(raw::Demosaic::XTransFast);
    ASSERT_TRUE(fast) << error;
    const auto pixel = [&](const croptop::TileStore& store, int x, int y) {
        std::array<float, 4> v{};
        const auto t = store.readTile({x / 256, y / 256});
        std::memcpy(v.data(), t->data() + (static_cast<std::size_t>(y % 256) * 256 + x % 256) * 16, 16);
        return std::array<float, 3>{v[0], v[1], v[2]};
    };
    const auto directional_px = [&](int x, int y) { return pixel(*directional_result, x, y); };
    const auto fast_px = [&](int x, int y) { return pixel(*fast, x, y); };
    const double q_dir = psnrOf(directional_px, scene, 12, 12, w - 12, h - 12);
    const double q_fast = psnrOf(fast_px, scene, 12, 12, w - 12, h - 12);
    const double zone_dir = psnrOf(directional_px, scene, 40, 40, 140, 140), zone_fast = psnrOf(fast_px, scene, 40, 40, 140, 140);
    std::printf("[raw] X-Trans fast %.1f dB (zone %.1f), directional %.1f dB (zone %.1f)\n", q_fast, zone_fast, q_dir, zone_dir);
    EXPECT_GT(q_dir, q_fast + 1.0);
    EXPECT_GT(zone_dir, zone_fast + 1.0);

    const auto flat = directional(64, 64, [](double, double) { return Rgb{0.3, 0.5, 0.2}; });
    for (const std::size_t i : {std::size_t{20 * 64 + 20}, std::size_t{33 * 64 + 41}}) {
        EXPECT_NEAR(flat.r[i], 0.3F, 1e-5F);
        EXPECT_NEAR(flat.g[i], 0.5F, 1e-5F);
        EXPECT_NEAR(flat.b[i], 0.2F, 1e-5F);
    }
    // Too-small or mismatched buffers are ignored (no out-of-bounds access).
    std::vector<float> tiny(10), out(10);
    std::vector<std::uint8_t> colours(10);
    raw::detail::xtransDirectional(tiny, colours, out, out, out, 2, 5);
}

}  // namespace
