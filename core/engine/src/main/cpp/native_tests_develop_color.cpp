// Tests for develop colour operations.
#include "develop_color.h"

#include <gtest/gtest.h>

#include <cmath>
#include <cstring>
#include <vector>

namespace {

using croptop::PixelFormat;
using croptop::TileStore;
namespace dev = croptop::develop;
using Rgb = std::array<float, 3>;

/** One row of swatches (linear sRGB), 8 px each. */
TileStore swatches(const std::vector<Rgb>& colours, PixelFormat format = PixelFormat::RGBA32F) {
    TileStore s(format, static_cast<std::uint32_t>(colours.size() * 8), 8);
    croptop::TileBuffer t(s.tile_byte_size(), 0);
    for (std::size_t i = 0; i < colours.size(); ++i)
        for (std::uint32_t y = 0; y < 8; ++y)
            for (std::uint32_t x = 0; x < 8; ++x) {
                const auto p = static_cast<std::size_t>(y) * 256 + i * 8 + x;
                if (format == PixelFormat::RGBA32F) {
                    const float px[4] = {colours[i][0], colours[i][1], colours[i][2], 0.5F};
                    std::memcpy(t.data() + p * 16, px, 16);
                } else {
                    const std::uint16_t px[4] = {static_cast<std::uint16_t>(colours[i][0] * 65535), static_cast<std::uint16_t>(colours[i][1] * 65535),
                                                 static_cast<std::uint16_t>(colours[i][2] * 65535), 65535};
                    std::memcpy(t.data() + p * 8, px, 8);
                }
            }
    EXPECT_TRUE(s.writeTile({0, 0}, t));
    return s;
}

std::array<float, 4> swatch(const TileStore& s, std::size_t i) {
    std::array<float, 4> out{};
    const auto t = s.readTile({0, 0});
    const auto p = 3 * 256 + i * 8 + 3;
    if (s.format() == PixelFormat::RGBA32F) {
        std::memcpy(out.data(), t->data() + p * 16, 16);
    } else {
        std::uint16_t v[4];
        std::memcpy(v, t->data() + p * 8, 8);
        for (int c = 0; c < 4; ++c) out[static_cast<std::size_t>(c)] = v[c] / 65535.0F;
    }
    return out;
}

TileStore run(const TileStore& in, const std::function<void(dev::ColorSettings&)>& set) {
    dev::ColorSettings s;
    set(s);
    std::string error;
    auto out = dev::applyColor(in, s, error);
    EXPECT_TRUE(out) << error;
    return out ? std::move(*out) : in;
}

float hueOf(const std::array<float, 4>& p) {
    // Rough RGB hue (degrees) — enough to tell the direction of a rotation.
    const float r = p[0], g = p[1], b = p[2];
    return std::atan2(std::sqrt(3.0F) * (g - b), 2 * r - g - b) * 57.29578F;
}

const std::vector<Rgb> kPalette{{0.6F, 0.05F, 0.04F}, {0.05F, 0.4F, 0.08F}, {0.04F, 0.08F, 0.6F}, {0.18F, 0.18F, 0.18F}, {0.02F, 0.02F, 0.02F}, {0.9F, 0.9F, 0.9F}};

TEST(DevelopColor, MixerRangesAreIsolatedAndNeutralsUntouched) {
    const auto in = swatches(kPalette);
    std::string error;
    ASSERT_EQ(swatch(*dev::applyColor(in, {}, error), 0), swatch(in, 0)) << "identity";

    const auto shifted = run(in, [](auto& s) { s.mixer.hue[0] = 100.0F; });  // reds → orange
    EXPECT_GT(hueOf(swatch(shifted, 0)), hueOf(swatch(in, 0)) + 5.0F);
    for (const std::size_t i : {1U, 2U, 3U, 4U, 5U}) {
        for (int c = 0; c < 3; ++c) EXPECT_NEAR(swatch(shifted, i)[static_cast<std::size_t>(c)], swatch(in, i)[static_cast<std::size_t>(c)], 2e-3F) << i;
    }
    const auto grey_red = run(in, [](auto& s) { s.mixer.saturation[0] = -100.0F; });
    const auto r = swatch(grey_red, 0);
    EXPECT_NEAR(r[0], r[1], 0.01F);
    EXPECT_NEAR(r[1], r[2], 0.01F);
    EXPECT_NEAR(swatch(grey_red, 2)[2], swatch(in, 2)[2], 2e-3F) << "blue untouched";
    const auto dark_blue = run(in, [](auto& s) { s.mixer.luminance[5] = -100.0F; });
    EXPECT_LT(swatch(dark_blue, 2)[2], swatch(in, 2)[2] * 0.8F);
    EXPECT_NEAR(swatch(dark_blue, 0)[0], swatch(in, 0)[0], 2e-3F);
    EXPECT_EQ(swatch(dark_blue, 3)[3], 0.5F) << "alpha copied";
}

TEST(DevelopColor, GradingTintsShadowsAndHighlightsSeparately) {
    const auto greys = swatches({{0.01F, 0.01F, 0.01F}, {0.18F, 0.18F, 0.18F}, {0.85F, 0.85F, 0.85F}});
    const auto graded = run(greys, [](auto& s) {
        s.grading.shadows = {240.0F, 100.0F, 0.0F};    // blue shadows
        s.grading.highlights = {70.0F, 100.0F, 0.0F};  // warm highlights
    });
    const auto dark = swatch(graded, 0), mid = swatch(graded, 1), bright = swatch(graded, 2);
    EXPECT_GT(dark[2], dark[0] * 1.3F) << "shadows turn blue";
    EXPECT_GT(bright[0], bright[2] * 1.05F) << "highlights turn warm";
    EXPECT_LT(std::abs(mid[0] - mid[2]), std::abs(dark[2] - dark[0]) + std::abs(bright[0] - bright[2])) << "midtones mostly neutral";
    const auto global = run(greys, [](auto& s) { s.grading.global.luminance = 50.0F; });
    EXPECT_GT(swatch(global, 1)[1], 0.18F * 1.2F);
    // Balance moves the split: with balance +100 the mid grey falls in the shadow mask.
    const auto balanced = run(greys, [](auto& s) {
        s.grading.shadows = {240.0F, 100.0F, 0.0F};
        s.grading.balance = 100.0F;
    });
    EXPECT_GT(swatch(balanced, 1)[2] - swatch(balanced, 1)[0], swatch(graded, 1)[2] - swatch(graded, 1)[0]);
}

TEST(DevelopColor, CalibrationKeepsWhiteAndMovesPrimaries) {
    const auto in = swatches(kPalette);
    const auto calibrated = run(in, [](auto& s) {
        s.calibration.red_hue = 100.0F;
        s.calibration.blue_saturation = 60.0F;
    });
    const auto white = swatch(calibrated, 5), grey = swatch(calibrated, 3);
    EXPECT_NEAR(white[0], 0.9F, 2e-3F);
    EXPECT_NEAR(white[1], 0.9F, 2e-3F);
    EXPECT_NEAR(white[2], 0.9F, 2e-3F);
    EXPECT_NEAR(grey[0], grey[2], 2e-3F);
    EXPECT_GT(hueOf(swatch(calibrated, 0)), hueOf(swatch(in, 0)) + 3.0F);
    const auto tinted = run(in, [](auto& s) { s.calibration.shadow_tint = 100.0F; });
    const auto shadow = swatch(tinted, 4);
    EXPECT_GT(shadow[0] + shadow[2], 2.0F * shadow[1]) << "magenta shadow tint";
}

TEST(DevelopColor, LooksAndInputPrimaries) {
    const auto in = swatches(kPalette);
    const auto mono = run(in, [](auto& s) { s.look = dev::Look::Monochrome; });
    for (std::size_t i = 0; i < kPalette.size(); ++i) {
        const auto p = swatch(mono, i);
        EXPECT_NEAR(p[0], p[1], 2e-3F) << i;
        EXPECT_NEAR(p[1], p[2], 2e-3F) << i;
    }
    std::string error;
    dev::ColorSettings neutral;
    neutral.look = dev::Look::Neutral;  // the model's default profile must not alter colour
    const auto baseline = dev::applyColor(in, neutral, error);
    ASSERT_TRUE(baseline) << error;
    EXPECT_EQ(swatch(*baseline, 1), swatch(in, 1));
    const auto vivid = run(in, [](auto& s) { s.look = dev::Look::Vivid; });
    EXPECT_GT(swatch(vivid, 1)[1] - swatch(vivid, 1)[0], swatch(in, 1)[1] - swatch(in, 1)[0]);

    // ProPhoto-linear input: a mixer change on a hue absent from the image leaves pixels unchanged.
    const std::array<float, 9> pro_to_srgb{2.0343F, -0.7276F, -0.3067F, -0.2288F, 1.2317F, -0.0029F, -0.0085F, -0.1533F, 1.1618F};
    const auto greens = swatches({{0.2F, 0.4F, 0.1F}, {0.3F, 0.3F, 0.3F}});
    const auto same = run(greens, [&](auto& s) {
        s.to_linear_srgb = pro_to_srgb;
        s.mixer.saturation[6] = 50.0F;  // purple
    });
    for (std::size_t i = 0; i < 2; ++i)
        for (int c = 0; c < 3; ++c) EXPECT_NEAR(swatch(same, i)[static_cast<std::size_t>(c)], swatch(greens, i)[static_cast<std::size_t>(c)], 5e-4F);
}

TEST(DevelopColor, ValidationFormatsAndCancel) {
    const auto rgba16 = swatches(kPalette, PixelFormat::RGBA16);
    const auto out = run(rgba16, [](auto& s) { s.mixer.saturation[3] = 100.0F; });
    EXPECT_EQ(out.format(), PixelFormat::RGBA16);
    std::string error;
    dev::ColorSettings s;
    s.mixer.hue[2] = 150.0F;
    EXPECT_FALSE(dev::applyColor(rgba16, s, error));
    s = {};
    s.grading.global.saturation = std::nanf("");
    EXPECT_FALSE(dev::applyColor(rgba16, s, error));
    s = {};
    s.to_linear_srgb = {};
    s.look = dev::Look::Vivid;
    EXPECT_FALSE(dev::applyColor(rgba16, s, error));
    s = {};
    s.look = dev::Look::Vivid;
    s.progress = [](float) { return false; };
    EXPECT_FALSE(dev::applyColor(rgba16, s, error));
    EXPECT_EQ(error, "cancelled");
    EXPECT_FALSE(dev::applyColor(TileStore(PixelFormat::RGBA8, 4, 4), {}, error));
}

}  // namespace
