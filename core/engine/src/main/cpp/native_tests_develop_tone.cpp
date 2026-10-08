// Tests for develop tone operations.
#include "develop_tone.h"
#include "pixel_plane.h"

#include <gtest/gtest.h>

#include <chrono>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <functional>
#include <limits>

namespace {

using croptop::PixelFormat;
using croptop::TileStore;
namespace dev = croptop::develop;
using Rgb = std::array<float, 3>;

TileStore paint(std::uint32_t w, std::uint32_t h, const std::function<Rgb(std::uint32_t, std::uint32_t)>& f, PixelFormat format = PixelFormat::RGBA32F) {
    TileStore s(format, w, h);
    for (std::uint32_t ty = 0; ty * 256 < h; ++ty)
        for (std::uint32_t tx = 0; tx * 256 < w; ++tx) {
            croptop::TileBuffer t(s.tile_byte_size(), 0);
            for (std::uint32_t y = 0; y < 256; ++y)
                for (std::uint32_t x = 0; x < 256; ++x) {
                    const auto gx = tx * 256 + x, gy = ty * 256 + y;
                    if (gx >= w || gy >= h) continue;
                    const auto c = f(gx, gy);
                    const auto i = static_cast<std::size_t>(y) * 256 + x;
                    if (format == PixelFormat::RGBA32F) {
                        const float px[4] = {c[0], c[1], c[2], 1.0F};
                        std::memcpy(t.data() + i * 16, px, 16);
                    } else {
                        const std::uint16_t px[4] = {static_cast<std::uint16_t>(std::lround(std::clamp(c[0], 0.0F, 1.0F) * 65535)),
                                                     static_cast<std::uint16_t>(std::lround(std::clamp(c[1], 0.0F, 1.0F) * 65535)),
                                                     static_cast<std::uint16_t>(std::lround(std::clamp(c[2], 0.0F, 1.0F) * 65535)), 65535};
                        std::memcpy(t.data() + i * 8, px, 8);
                    }
                }
            EXPECT_TRUE(s.writeTile({static_cast<std::int32_t>(tx), static_cast<std::int32_t>(ty)}, t));
        }
    return s;
}

std::array<float, 4> at(const TileStore& s, std::uint32_t x, std::uint32_t y) {
    std::array<float, 4> out{};
    const auto t = s.readTile({static_cast<std::int32_t>(x / 256), static_cast<std::int32_t>(y / 256)});
    if (!t) return out;
    const auto i = static_cast<std::size_t>(y % 256) * 256 + x % 256;
    if (s.format() == PixelFormat::RGBA32F) {
        std::memcpy(out.data(), t->data() + i * 16, 16);
    } else {
        std::uint16_t v[4];
        std::memcpy(v, t->data() + i * 8, 8);
        for (int c = 0; c < 4; ++c) out[static_cast<std::size_t>(c)] = v[c] / 65535.0F;
    }
    return out;
}

float luma(const std::array<float, 4>& p) { return 0.2126F * p[0] + 0.7152F * p[1] + 0.0722F * p[2]; }

TileStore run(const TileStore& in, const std::function<void(dev::ToneSettings&)>& set) {
    dev::ToneSettings t;
    set(t);
    std::string error;
    auto out = dev::applyTone(in, t, error);
    EXPECT_TRUE(out) << error;
    return out ? std::move(*out) : TileStore(in.format(), in.width(), in.height());
}

TEST(DevelopTone, IdentityExposureAndContrast) {
    const auto grey = paint(300, 200, [](std::uint32_t x, std::uint32_t) {
        const float v = 0.01F + 0.9F * static_cast<float>(x) / 300.0F;
        return Rgb{v, v * 0.8F, v * 0.6F};
    });
    std::string error;
    const auto same = dev::applyTone(grey, {}, error);
    ASSERT_TRUE(same) << error;
    EXPECT_EQ(at(*same, 123, 45), at(grey, 123, 45));

    const auto brighter = run(grey, [](auto& t) { t.exposure = 1.0F; });
    EXPECT_NEAR(at(brighter, 150, 10)[0], 2.0F * at(grey, 150, 10)[0], 1e-4F);
    EXPECT_NEAR(at(brighter, 150, 10)[2] / at(brighter, 150, 10)[0], 0.6F, 1e-4F) << "hue is preserved";

    // Contrast pivots on 18 % grey: brighter above, darker below, mid grey fixed.
    const auto mid = paint(64, 64, [](std::uint32_t x, std::uint32_t) {
        const float v = x < 21 ? 0.03F : x < 42 ? 0.18F : 0.7F;
        return Rgb{v, v, v};
    });
    const auto punchy = run(mid, [](auto& t) { t.contrast = 60.0F; });
    EXPECT_LT(at(punchy, 5, 5)[0], 0.03F);
    EXPECT_NEAR(at(punchy, 30, 5)[0], 0.18F, 1e-4F);
    EXPECT_GT(at(punchy, 60, 5)[0], 0.7F);
}

TEST(DevelopTone, ShadowsAndHighlightsAreLocalAndHaloFree) {
    // Dark left half, bright right half.
    const auto scene = paint(512, 256, [](std::uint32_t x, std::uint32_t) {
        const float v = x < 256 ? 0.02F : 0.8F;
        return Rgb{v, v, v};
    });
    const auto lifted = run(scene, [](auto& t) { t.shadows = 100.0F; });
    const float near_edge = at(lifted, 252, 128)[0], far = at(lifted, 150, 128)[0];
    EXPECT_GT(far, 0.02F * 1.8F) << "shadows lift the dark half";
    EXPECT_NEAR(near_edge / far, 1.0F, 0.04F) << "no halo next to the bright side";
    EXPECT_NEAR(at(lifted, 400, 128)[0], 0.8F, 0.05F) << "bright half barely moves";
    const auto recovered = run(scene, [](auto& t) { t.highlights = -100.0F; });
    EXPECT_LT(at(recovered, 400, 128)[0], 0.8F * 0.7F) << "highlights pull the bright half down";
    EXPECT_NEAR(at(recovered, 260, 128)[0] / at(recovered, 400, 128)[0], 1.0F, 0.04F) << "no halo on the bright side";
    EXPECT_NEAR(at(recovered, 150, 128)[0], 0.02F, 0.002F) << "dark half unchanged";
}

TEST(DevelopTone, ClarityAndTextureTargetTheirScales) {
    // Midtone texture at two scales: medium (period 32 px, clarity's scale on a 512 px image, whose radius is
    // 1.5 % of the long side) + fine (period 6 px, texture's scale), ±0.4 stop each.
    const auto textured = paint(512, 512, [](std::uint32_t x, std::uint32_t y) {
        const float l = -2.47F + 0.4F * std::sin(static_cast<float>(x) * 6.2831853F / 32.0F) + 0.4F * std::sin(static_cast<float>(y) * 6.2831853F / 6.0F);
        const float v = std::exp2(l);
        return Rgb{v, v, v};
    });
    const auto spread = [](const TileStore& s, bool along_x) {
        // Amplitude of the log signal along one axis (the other scale held at a fixed phase).
        float lo = 1e9F, hi = -1e9F;
        for (std::uint32_t k = 128; k < 384; ++k) {
            const auto p = along_x ? at(s, k, 256) : at(s, 256, k);
            const float l = std::log2(luma(p));
            lo = std::min(lo, l);
            hi = std::max(hi, l);
        }
        return hi - lo;
    };
    const float coarse0 = spread(textured, true), fine0 = spread(textured, false);
    const auto clear = run(textured, [](auto& t) { t.clarity = 100.0F; });
    EXPECT_GT(spread(clear, true), coarse0 * 1.15F) << "clarity boosts coarse midtone contrast";
    const auto textured_up = run(textured, [](auto& t) { t.texture = 100.0F; });
    const float fine_gain = spread(textured_up, false) / fine0, medium_gain = spread(textured_up, true) / coarse0;
    EXPECT_GT(fine_gain, 1.3F) << "texture boosts fine detail";
    EXPECT_GT(fine_gain, medium_gain + 0.2F) << "…much more than medium structure";
    const auto smooth = run(textured, [](auto& t) { t.texture = -100.0F; });
    EXPECT_LT(spread(smooth, false), fine0 * 0.8F) << "negative texture smooths fine detail";
}

TEST(DevelopTone, WhitesBlacksCurvesAndSaturation) {
    const auto ramp = paint(256, 32, [](std::uint32_t x, std::uint32_t) {
        const float v = std::exp2(-10.0F + 10.0F * static_cast<float>(x) / 255.0F);  // −10 … 0 stops
        return Rgb{v, v * 0.7F, v * 0.4F};
    });
    const auto whites = run(ramp, [](auto& t) { t.whites = 100.0F; });
    EXPECT_GT(luma(at(whites, 250, 5)), luma(at(ramp, 250, 5)) * 1.5F);
    EXPECT_NEAR(luma(at(whites, 60, 5)), luma(at(ramp, 60, 5)), 1e-5F);
    const auto blacks = run(ramp, [](auto& t) { t.blacks = -100.0F; });
    EXPECT_LT(luma(at(blacks, 5, 5)), luma(at(ramp, 5, 5)) * 0.7F);
    EXPECT_NEAR(luma(at(blacks, 200, 5)), luma(at(ramp, 200, 5)), 1e-4F);

    const auto grey = run(ramp, [](auto& t) { t.saturation = -100.0F; });
    const auto g = at(grey, 200, 5);
    EXPECT_NEAR(g[0], g[1], 1e-5F);
    EXPECT_NEAR(g[1], g[2], 1e-5F);
    const auto vivid = run(ramp, [](auto& t) { t.vibrance = 60.0F; });
    const auto v = at(vivid, 200, 5), o = at(ramp, 200, 5);
    EXPECT_GT(v[0] - v[2], o[0] - o[2]);

    // Parametric shadows lift display-dark values; endpoints stay fixed. Point curves keep hue.
    const auto lifted = run(ramp, [](auto& t) { t.parametric.shadows = 100.0F; });
    EXPECT_GT(luma(at(lifted, 170, 5)), luma(at(ramp, 170, 5)) * 1.1F);
    EXPECT_NEAR(luma(at(lifted, 255, 5)), luma(at(ramp, 255, 5)), 1e-3F);
    const auto curved = run(ramp, [](auto& t) { t.point_curve.points = {{0, 0}, {0.5F, 0.65F}, {1, 1}}; });
    const auto c = at(curved, 220, 5);
    EXPECT_GT(luma(c), luma(at(ramp, 220, 5)));
    EXPECT_NEAR(c[2] / c[0], 0.4F, 1e-3F);
}

TEST(DevelopTone, DehazeRecoversAHazyScene) {
    // Clear scene J (colour checker + dark object) seen through uniform haze: I = J·t + A·(1 − t).
    const auto clear = [](std::uint32_t x, std::uint32_t y) {
        const int cell = static_cast<int>(x / 64 + (y / 64) * 8);
        Rgb c{0.1F + 0.1F * static_cast<float>(cell % 5), 0.15F + 0.12F * static_cast<float>(cell % 3), 0.2F + 0.08F * static_cast<float>(cell % 4)};
        if (x > 200 && x < 260 && y > 200 && y < 260) c = {0.02F, 0.02F, 0.02F};
        return c;
    };
    const float tr = 0.55F;
    const Rgb air{0.8F, 0.82F, 0.85F};
    const auto hazy = paint(512, 512, [&](std::uint32_t x, std::uint32_t y) {
        const auto j = clear(x, y);
        return Rgb{j[0] * tr + air[0] * (1 - tr), j[1] * tr + air[1] * (1 - tr), j[2] * tr + air[2] * (1 - tr)};
    });
    // The brightest haze needs a bright region for the air-light estimate: add a sky strip.
    const auto hazy_sky = paint(512, 512, [&](std::uint32_t x, std::uint32_t y) {
        if (y < 40) return air;
        return at(hazy, x, y) == std::array<float, 4>{} ? Rgb{} : Rgb{at(hazy, x, y)[0], at(hazy, x, y)[1], at(hazy, x, y)[2]};
    });
    const auto error = [&](const TileStore& s) {
        double e = 0;
        int n = 0;
        for (std::uint32_t y = 80; y < 480; y += 7)
            for (std::uint32_t x = 16; x < 496; x += 7) {
                const auto p = at(s, x, y);
                const auto j = clear(x, y);
                for (int c = 0; c < 3; ++c) e += std::abs(p[static_cast<std::size_t>(c)] - j[static_cast<std::size_t>(c)]);
                ++n;
            }
        return e / n;
    };
    const auto dehazed = run(hazy_sky, [](auto& t) { t.dehaze = 100.0F; });
    std::printf("[develop] dehaze mean error %.3f → %.3f\n", error(hazy_sky), error(dehazed));
    EXPECT_LT(error(dehazed), error(hazy_sky) * 0.5);
    const auto contrast = [&](const TileStore& s) {
        float lo = 1e9F, hi = -1e9F;
        for (std::uint32_t y = 80; y < 480; y += 5)
            for (std::uint32_t x = 16; x < 496; x += 5) {
                lo = std::min(lo, luma(at(s, x, y)));
                hi = std::max(hi, luma(at(s, x, y)));
            }
        return hi - lo;
    };
    const auto hazier = run(hazy_sky, [](auto& t) { t.dehaze = -100.0F; });
    EXPECT_LT(contrast(hazier), contrast(hazy_sky) * 0.7F) << "negative dehaze veils the scene";
}

TEST(DevelopTone, FormatsValidationAndCancel) {
    const auto rgba16 = paint(300, 300, [](std::uint32_t x, std::uint32_t) { return Rgb{0.6F, 0.5F, 0.4F * static_cast<float>(x) / 300.0F}; }, PixelFormat::RGBA16);
    const auto bright = run(rgba16, [](auto& t) { t.exposure = 2.0F; });
    EXPECT_EQ(bright.format(), PixelFormat::RGBA16);
    EXPECT_FLOAT_EQ(at(bright, 10, 10)[0], 1.0F) << "RGBA16 output clamps";
    std::string error;
    dev::ToneSettings t;
    t.clarity = 101.0F;
    EXPECT_FALSE(dev::applyTone(rgba16, t, error));
    t = {};
    t.exposure = std::nanf("");
    EXPECT_FALSE(dev::applyTone(rgba16, t, error));
    EXPECT_FALSE(dev::applyTone(TileStore(PixelFormat::RGBA8, 8, 8), {}, error));
    t = {};
    t.shadows = 20.0F;
    t.progress = [](float) { return false; };
    EXPECT_FALSE(dev::applyTone(rgba16, t, error));
    EXPECT_EQ(error, "cancelled");
}

TEST(PixelPlane, WritePixelEncodesNonFiniteAsZero) {
    // std::clamp passes NaN through; a NaN float→int cast is undefined behaviour (UBSan float-cast-overflow).
    const float nan = std::numeric_limits<float>::quiet_NaN(), inf = std::numeric_limits<float>::infinity();
    const float bad[4] = {nan, inf, -inf, nan};
    std::uint8_t p8[4];
    croptop::paint::detail::writePixel(PixelFormat::RGBA8, p8, bad);
    EXPECT_EQ(p8[0], 0);
    EXPECT_EQ(p8[1], 255);
    EXPECT_EQ(p8[2], 0);
    EXPECT_EQ(p8[3], 0);
    std::uint16_t p16[4];
    croptop::paint::detail::writePixel(PixelFormat::RGBA16, reinterpret_cast<std::uint8_t*>(p16), bad);
    EXPECT_EQ(p16[0], 0);
    EXPECT_EQ(p16[1], 65535);
    float pf[4];
    croptop::paint::detail::writePixel(PixelFormat::RGBA32F, reinterpret_cast<std::uint8_t*>(pf), bad);
    EXPECT_EQ(pf[0], 0.0F);
    EXPECT_EQ(pf[1], 0.0F) << "infinite colour is not a colour";
    EXPECT_EQ(pf[3], 0.0F);
    const float wide[4] = {1.7F, -0.2F, 0.5F, 1.4F};
    croptop::paint::detail::writePixel(PixelFormat::RGBA32F, reinterpret_cast<std::uint8_t*>(pf), wide);
    EXPECT_EQ(pf[0], 1.7F) << "finite float colour stays unbounded";
    EXPECT_EQ(pf[1], -0.2F);
    EXPECT_EQ(pf[3], 1.0F);
}

#ifdef NDEBUG
TEST(DevelopTone, Throughput24MP) {
    TileStore big(PixelFormat::RGBA16, 6000, 4000);
    for (std::int32_t ty = 0; ty < 16; ++ty)
        for (std::int32_t tx = 0; tx < 24; ++tx) ASSERT_TRUE(big.writeTile({tx, ty}, croptop::TileBuffer(big.tile_byte_size(), static_cast<std::uint8_t>(40 + tx * 5 + ty))));
    dev::ToneSettings t;
    t.exposure = 0.3F;
    t.shadows = 40.0F;
    t.highlights = -30.0F;
    t.clarity = 20.0F;
    t.texture = 15.0F;
    t.vibrance = 10.0F;
    std::string error;
    const auto start = std::chrono::steady_clock::now();
    const auto out = dev::applyTone(big, t, error);
    const double ms = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - start).count();
    ASSERT_TRUE(out) << error;
    std::printf("[develop] 24 MP tone (exposure, shadows, highlights, clarity, texture, vibrance): %.0f ms\n", ms);
}
#endif

}  // namespace
