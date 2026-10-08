// Tests for the raw development front end.
#include "raw_demosaic.h"

#include <gtest/gtest.h>

#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <functional>
#include <limits>
#include <vector>

namespace {

using croptop::PixelFormat;
using croptop::TileStore;
namespace raw = croptop::raw;
using Rgb = std::array<double, 3>;

/** Linear test scene: smooth gradients, a saturated diagonal edge, a zone plate and thin lines. */
Rgb scene(double x, double y) {
    Rgb c{0.15 + 0.5 * x / 320.0, 0.25 + 0.4 * y / 240.0, 0.45};
    if (x + 0.6 * y > 260.0) c = {0.75, 0.12, 0.10};
    const double zx = x - 90.0, zy = y - 90.0;
    if (std::abs(zx) < 60 && std::abs(zy) < 60) {
        const double l = 0.45 + 0.3 * std::sin(0.0045 * (zx * zx + zy * zy));  // ≤ 0.09 cycles/px at the corners
        c = {l, l, l};
    }
    if (static_cast<int>(x) % 24 == 0 && y > 170) c = {0.05, 0.05, 0.05};
    if (static_cast<int>(y) % 20 == 0 && x > 200) c = {0.9, 0.9, 0.9};
    return c;
}

struct Mosaic {
    std::vector<std::uint16_t> data;
    raw::CfaImage image;
};

Mosaic mosaic(std::uint32_t w, std::uint32_t h, const raw::CfaPattern& pattern, const std::function<Rgb(double, double)>& f,
              std::array<float, 3> black = {512, 512, 512}, float white = 15000.0F, std::array<double, 3> sensitivity = {1, 1, 1}) {
    Mosaic m;
    m.data.resize(static_cast<std::size_t>(w) * h);
    for (std::uint32_t y = 0; y < h; ++y)
        for (std::uint32_t x = 0; x < w; ++x) {
            const auto c = pattern.at(x, y);
            const double v = std::clamp(f(x, y)[c] * sensitivity[c], 0.0, 1.0);
            m.data[static_cast<std::size_t>(y) * w + x] = static_cast<std::uint16_t>(std::lround(black[c] + v * (white - black[c])));
        }
    m.image = {w, h, m.data, pattern, black, white};
    return m;
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

double psnrIn(const TileStore& s, const std::function<Rgb(double, double)>& f, std::uint32_t x0, std::uint32_t y0, std::uint32_t x1,
              std::uint32_t y1) {
    double mse = 0;
    std::size_t n = 0;
    for (std::uint32_t y = y0; y < y1; ++y)
        for (std::uint32_t x = x0; x < x1; ++x) {
            const auto p = at(s, x, y);
            const auto t = f(x, y);
            for (int c = 0; c < 3; ++c) {
                const double d = p[static_cast<std::size_t>(c)] - t[static_cast<std::size_t>(c)];
                mse += d * d;
                ++n;
            }
        }
    mse /= static_cast<double>(n);
    return mse == 0 ? 99.0 : 10.0 * std::log10(1.0 / mse);
}

double psnr(const TileStore& s, const std::function<Rgb(double, double)>& f, std::uint32_t w, std::uint32_t h) {
    return psnrIn(s, f, 8, 8, w - 8, h - 8);
}

TEST(RawDemosaic, RcdBeatsBilinearOnEveryBayerLayout) {
    double phase_edge_min = std::numeric_limits<double>::infinity(), phase_edge_max = -std::numeric_limits<double>::infinity();
    double phase_rcd_min = std::numeric_limits<double>::infinity(), phase_rcd_max = -std::numeric_limits<double>::infinity();
    for (const char* layout : {"RGGB", "BGGR", "GRBG", "GBRG"}) {
        const auto m = mosaic(320, 240, raw::CfaPattern::bayer(layout), scene);
        std::string error;
        raw::DevelopSpec spec;
        spec.method = raw::Demosaic::Bilinear;
        const auto linear = raw::develop(m.image, spec, error);
        ASSERT_TRUE(linear) << error;
        spec.method = raw::Demosaic::RCD;
        const auto rcd = raw::develop(m.image, spec, error);
        ASSERT_TRUE(rcd) << error;
        spec.method = raw::Demosaic::EdgeDirected;
        const auto edge_directed = raw::develop(m.image, spec, error);
        ASSERT_TRUE(edge_directed) << error;
        const double q_linear = psnr(*linear, scene, 320, 240), q_rcd = psnr(*rcd, scene, 320, 240), q_edge = psnr(*edge_directed, scene, 320, 240);
        std::printf("[raw] %s bilinear %.1f dB, RCD %.1f dB, EdgeDirected %.1f dB\n", layout, q_linear, q_rcd, q_edge);
        // One-pixel chromatic lines and a saturated edge exceed what the R/B sampling can resolve, so the
        // whole-frame figure is modest for any demosaicer; smooth areas and the zone plate must be clean.
        EXPECT_GT(q_rcd, q_linear + 4.0) << layout;
        EXPECT_GT(q_rcd, 28.0) << layout;
        EXPECT_GT(q_edge, q_linear + 4.0) << layout;
        EXPECT_GT(q_edge, 28.0) << layout;
        EXPECT_GE(q_edge, q_rcd - 0.5) << layout << " EdgeDirected remains comparable with the RCD default";
        EXPECT_GT(psnrIn(*rcd, scene, 40, 40, 140, 140), 44.0) << layout << " zone plate";
        EXPECT_GT(psnrIn(*edge_directed, scene, 40, 40, 140, 140), 44.0) << layout << " EdgeDirected zone plate";
        EXPECT_GT(psnrIn(*rcd, scene, 10, 10, 25, 160), 60.0) << layout << " smooth gradient";
        EXPECT_GT(psnrIn(*edge_directed, scene, 10, 10, 25, 160), 60.0) << layout << " EdgeDirected smooth gradient";
        EXPECT_EQ(at(*rcd, 100, 100)[3], 1.0F);

        // Shift scene features relative to each CFA phase; report the full observed range rather
        // than taking one especially favourable 1-pixel-line alignment as the quality result.
        for (std::uint32_t dy = 0; dy <= 1; ++dy) {
            for (std::uint32_t dx = 0; dx <= 1; ++dx) {
                if (dx == 0 && dy == 0) continue;
                const auto shifted_scene = [dx, dy](double x, double y) { return scene(x + dx, y + dy); };
                const auto shifted = mosaic(320, 240, raw::CfaPattern::bayer(layout), shifted_scene);
                spec.method = raw::Demosaic::RCD;
                const auto shifted_rcd = raw::develop(shifted.image, spec, error);
                ASSERT_TRUE(shifted_rcd) << error;
                spec.method = raw::Demosaic::EdgeDirected;
                const auto shifted_edge = raw::develop(shifted.image, spec, error);
                ASSERT_TRUE(shifted_edge) << error;
                const double shifted_rcd_psnr = psnr(*shifted_rcd, shifted_scene, 320, 240);
                const double shifted_edge_psnr = psnr(*shifted_edge, shifted_scene, 320, 240);
                phase_rcd_min = std::min(phase_rcd_min, shifted_rcd_psnr);
                phase_rcd_max = std::max(phase_rcd_max, shifted_rcd_psnr);
                phase_edge_min = std::min(phase_edge_min, shifted_edge_psnr);
                phase_edge_max = std::max(phase_edge_max, shifted_edge_psnr);
            }
        }
    }
    std::printf("[raw] phase-shifted Bayer min/max: RCD %.1f/%.1f dB, EdgeDirected %.1f/%.1f dB\n",
                phase_rcd_min, phase_rcd_max, phase_edge_min, phase_edge_max);
}

TEST(RawDemosaic, FlatFieldsWhiteBalanceAndBlackLevelsStayNeutral) {
    const auto grey = [](double, double) { return Rgb{0.4, 0.4, 0.4}; };
    // A camera whose red and blue channels are half/two-thirds as sensitive, with per-channel black levels.
    const std::array<double, 3> sensitivity{0.5, 1.0, 0.625};
    for (const auto& pattern : {raw::CfaPattern::bayer("RGGB"), raw::CfaPattern::xtrans()}) {
        const auto m = mosaic(300, 280, pattern, grey, {600, 520, 580}, 16000.0F, sensitivity);
        std::string error;
        raw::DevelopSpec spec;
        spec.white_balance = {2.0F, 1.0F, 1.6F};
        spec.output_format = PixelFormat::RGBA32F;
        const auto out = raw::develop(m.image, spec, error);
        ASSERT_TRUE(out) << error;
        for (const auto& [x, y] : std::vector<std::pair<std::uint32_t, std::uint32_t>>{{0, 0}, {150, 140}, {299, 279}, {257, 3}}) {
            const auto p = at(*out, x, y);
            for (int c = 0; c < 3; ++c) EXPECT_NEAR(p[static_cast<std::size_t>(c)], 0.4F, 0.002F) << "x " << x << " y " << y << " c " << c;
        }
    }
}

TEST(RawDemosaic, HighlightsClipOrBlendToNeutralNotMagenta) {
    // A fully saturated sensor with daylight multipliers: raw clipping would render magenta.
    std::vector<std::uint16_t> saturated(64 * 64, 15000);
    raw::CfaImage image{64, 64, saturated, raw::CfaPattern::bayer("RGGB"), {512, 512, 512}, 15000.0F};
    std::string error;
    raw::DevelopSpec spec;
    spec.white_balance = {2.0F, 1.0F, 1.6F};
    spec.output_format = PixelFormat::RGBA32F;
    spec.highlights = raw::Highlights::Clip;
    const auto clipped = raw::develop(image, spec, error);
    ASSERT_TRUE(clipped) << error;
    const auto c = at(*clipped, 30, 30);
    EXPECT_NEAR(c[0], 1.0F, 1e-5F);  // RCD's ratio guard (ε) leaves a few 1e-6
    EXPECT_NEAR(c[1], 1.0F, 1e-5F);
    EXPECT_NEAR(c[2], 1.0F, 1e-5F);
    spec.highlights = raw::Highlights::Blend;
    const auto blended = raw::develop(image, spec, error);
    ASSERT_TRUE(blended) << error;
    const auto b = at(*blended, 30, 30);
    EXPECT_NEAR(b[0], b[1], 1e-4F) << "blend keeps luminance but drops the false chroma";
    EXPECT_NEAR(b[2], b[1], 1e-4F);
    EXPECT_GT(b[1], 1.0F);

    // Partly clipped sky (only green saturates): blend keeps a blue hue instead of turning it neutral/magenta.
    const auto sky = mosaic(64, 64, raw::CfaPattern::bayer("RGGB"), [](double, double) { return Rgb{0.5, 1.4, 0.9}; });
    spec.white_balance = {1.0F, 1.0F, 1.0F};
    const auto sky_out = raw::develop(sky.image, spec, error);
    ASSERT_TRUE(sky_out) << error;
    const auto s = at(*sky_out, 30, 30);
    EXPECT_LT(s[0], s[1]);
    EXPECT_LE(s[1], s[2] + 0.35F);
    spec.method = raw::Demosaic::EdgeDirected;
    const auto sky_amaze = raw::develop(sky.image, spec, error);
    ASSERT_TRUE(sky_amaze) << error;
    const auto sa = at(*sky_amaze, 30, 30);
    EXPECT_LT(sa[0], sa[1]);
    EXPECT_LE(sa[1], sa[2] + 0.35F);
}

TEST(RawDemosaic, CameraMatricesMapNeutralAndMatchKnownConversions) {
    // A camera whose native RGB is linear sRGB: ColorMatrix = XYZ(D65) → sRGB.
    const std::array<double, 9> xyz_to_srgb{3.2404542, -1.5371385, -0.4985314, -0.9692660, 1.8760108, 0.0415560, 0.0556434, -0.2040259, 1.0572252};
    const auto identity = raw::cameraToOutput(xyz_to_srgb, raw::OutputPrimaries::LinearSRGB);
    ASSERT_TRUE(identity);
    for (int i = 0; i < 9; ++i) EXPECT_NEAR((*identity)[static_cast<std::size_t>(i)], i % 4 == 0 ? 1.0 : 0.0, 2e-3) << i;
    const auto p3 = raw::cameraToOutput(xyz_to_srgb, raw::OutputPrimaries::LinearP3);
    ASSERT_TRUE(p3);
    const std::array<double, 9> srgb_to_p3{0.8225, 0.1774, 0.0000, 0.0332, 0.9669, 0.0000, 0.0171, 0.0724, 0.9108};
    for (int i = 0; i < 9; ++i) EXPECT_NEAR((*p3)[static_cast<std::size_t>(i)], srgb_to_p3[static_cast<std::size_t>(i)], 2e-3) << i;
    for (const auto out : {raw::OutputPrimaries::LinearProPhoto, raw::OutputPrimaries::LinearRec2020}) {
        const auto m = raw::cameraToOutput(xyz_to_srgb, out);
        ASSERT_TRUE(m);
        for (int r = 0; r < 3; ++r) {
            EXPECT_NEAR((*m)[static_cast<std::size_t>(r * 3)] + (*m)[static_cast<std::size_t>(r * 3 + 1)] + (*m)[static_cast<std::size_t>(r * 3 + 2)], 1.0, 1e-6)
                << "white-balanced neutral stays neutral";
        }
    }
    EXPECT_FALSE(raw::cameraToOutput({}, raw::OutputPrimaries::LinearSRGB));

    // Applied in develop: camera = sRGB, output P3 — a pure sRGB red lands on P3's (0.8225, 0.0332, 0.0171).
    const auto red = mosaic(64, 64, raw::CfaPattern::bayer("RGGB"), [](double, double) { return Rgb{0.8, 0, 0}; });
    raw::DevelopSpec spec;
    spec.camera_to_output = *p3;
    spec.output_format = PixelFormat::RGBA32F;
    std::string error;
    const auto out = raw::develop(red.image, spec, error);
    ASSERT_TRUE(out) << error;
    const auto p = at(*out, 32, 32);
    EXPECT_NEAR(p[0], 0.8 * 0.8225, 0.004);
    EXPECT_NEAR(p[1], 0.8 * 0.0332, 0.004);
    EXPECT_NEAR(p[2], 0.8 * 0.0171, 0.004);
}

TEST(RawDemosaic, XTransDevelopsTheScene) {
    const auto m = mosaic(320, 240, raw::CfaPattern::xtrans(), scene);
    std::string error;
    raw::DevelopSpec spec;
    spec.method = raw::Demosaic::RCD;  // Default RCD selects the directional X-Trans implementation
    const auto out = raw::develop(m.image, spec, error);
    ASSERT_TRUE(out) << error;
    const double q = psnr(*out, scene, 320, 240);
    std::printf("[raw] X-Trans fast %.1f dB\n", q);
    EXPECT_GT(q, 24.0);
    EXPECT_GT(psnrIn(*out, scene, 40, 40, 140, 140), 32.0) << "zone plate";
}

TEST(RawDemosaic, ValidationAndCancellation) {
    const auto m = mosaic(300, 300, raw::CfaPattern::bayer("RGGB"), scene);
    std::string error;
    auto bad = m.image;
    bad.width = 299;
    EXPECT_FALSE(raw::develop(bad, {}, error));
    bad = m.image;
    bad.white = 100.0F;  // below black
    EXPECT_FALSE(raw::develop(bad, {}, error));
    bad = m.image;
    bad.pattern.colours[0] = 1;
    bad.pattern.colours[3] = 1;  // no red or blue
    bad.pattern.colours[1] = 1;
    bad.pattern.colours[2] = 1;
    EXPECT_FALSE(raw::develop(bad, {}, error));
    raw::DevelopSpec spec;
    spec.white_balance = {0.0F, 1.0F, 1.0F};
    EXPECT_FALSE(raw::develop(m.image, spec, error));
    spec = {};
    spec.output_format = PixelFormat::RGBA8;
    EXPECT_FALSE(raw::develop(m.image, spec, error));
    spec = {};
    spec.progress = [](float) { return false; };
    EXPECT_FALSE(raw::develop(m.image, spec, error));
    EXPECT_EQ(error, "cancelled");
}

TEST(RawDemosaic, CfaPatternHandlesNegativeCoordinatesAndRejectsMalformedBayerLayouts) {
    const auto rggb = raw::CfaPattern::bayer("RGGB");
    EXPECT_EQ(rggb.at(-1, -1), rggb.at(1, 1));
    EXPECT_EQ(rggb.at(-1, 0), rggb.at(1, 0));
    EXPECT_EQ(rggb.at(0, -1), rggb.at(0, 1));
    const auto xtrans = raw::CfaPattern::xtrans();
    EXPECT_EQ(xtrans.at(-1, -1), xtrans.at(5, 5));

    for (const char* invalid : {"R", "RGB", "RXXG", "RGGBX"}) {
        const auto pattern = raw::CfaPattern::bayer(invalid);
        EXPECT_EQ(pattern.at(0, 0), 255) << invalid;
        const auto image_data = std::vector<std::uint16_t>(16 * 16, 1000);
        const raw::CfaImage image{16, 16, image_data, pattern, {0, 0, 0}, 15000};
        std::string error;
        EXPECT_FALSE(raw::develop(image, {}, error)) << invalid;
    }
    EXPECT_EQ(raw::CfaPattern::bayer(nullptr).at(0, 0), 0);
    EXPECT_EQ(raw::CfaPattern::bayer("").at(0, 0), 0);
}

TEST(RawDemosaic, RejectsUnknownProcessingModes) {
    const auto image = mosaic(32, 32, raw::CfaPattern::bayer("RGGB"), scene);
    std::string error;
    raw::DevelopSpec spec;
    spec.method = static_cast<raw::Demosaic>(255);
    EXPECT_FALSE(raw::develop(image.image, spec, error));
    spec = {};
    spec.highlights = static_cast<raw::Highlights>(255);
    EXPECT_FALSE(raw::develop(image.image, spec, error));
}

TEST(RawDemosaic, EdgeDirectedIsDeterministicAcrossTilesAndReconstructsHighlights) {
    const auto m = mosaic(520, 300, raw::CfaPattern::bayer("GRBG"), scene);
    std::string error;
    raw::DevelopSpec spec;
    spec.method = raw::Demosaic::EdgeDirected;
    spec.worker_threads = 1;
    spec.output_format = PixelFormat::RGBA32F;
    const auto serial = raw::develop(m.image, spec, error);
    ASSERT_TRUE(serial) << error;
    spec.worker_threads = 4;
    const auto parallel = raw::develop(m.image, spec, error);
    ASSERT_TRUE(parallel) << error;
    for (const auto x : {254U, 255U, 256U, 257U, 510U, 511U, 512U}) {
        for (const auto y : {0U, 1U, 127U, 255U, 299U}) {
            EXPECT_EQ(at(*serial, x, y), at(*parallel, x, y)) << "tile boundary x=" << x << " y=" << y;
        }
    }

    const std::vector<std::uint16_t> saturated(64 * 64, 15000);
    const raw::CfaImage clipped_input{64, 64, saturated, raw::CfaPattern::bayer("RGGB"), {512, 512, 512}, 15000};
    spec.worker_threads = 2;
    spec.highlights = raw::Highlights::Blend;
    const auto highlight = raw::develop(clipped_input, spec, error);
    ASSERT_TRUE(highlight) << error;
    const auto px = at(*highlight, 32, 32);
    EXPECT_NEAR(px[0], px[1], 1e-4F);
    EXPECT_NEAR(px[2], px[1], 1e-4F);
    EXPECT_NEAR(px[1], 1.0F, 1e-4F);
}

#ifdef NDEBUG
TEST(RawDemosaic, Throughput24MP) {
    const std::uint32_t w = 6000, h = 4000;
    std::vector<std::uint16_t> data(static_cast<std::size_t>(w) * h);
    for (std::size_t i = 0; i < data.size(); ++i) data[i] = static_cast<std::uint16_t>(600 + (i * 2654435761U >> 20) % 12000);
    raw::CfaImage image{w, h, data, raw::CfaPattern::bayer("RGGB"), {512, 512, 512}, 15000.0F};
    for (const auto method : {raw::Demosaic::Bilinear, raw::Demosaic::RCD, raw::Demosaic::EdgeDirected}) {
        raw::DevelopSpec spec;
        spec.method = method;
        std::string error;
        const auto start = std::chrono::steady_clock::now();
        const auto out = raw::develop(image, spec, error);
        const double ms = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - start).count();
        ASSERT_TRUE(out) << error;
        const char* name = method == raw::Demosaic::RCD ? "RCD" : method == raw::Demosaic::EdgeDirected ? "EdgeDirected" : "bilinear";
        std::printf("[raw] 24 MP %s → RGBA16: %.0f ms\n", name, ms);
    }
}
#endif

}  // namespace
