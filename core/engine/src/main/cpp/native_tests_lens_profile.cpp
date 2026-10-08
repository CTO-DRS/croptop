// Tests for lens-profile correction.
#include "lens_profile.h"

#include <gtest/gtest.h>

#include <cmath>
#include <cstring>
#include <functional>
#include <string>

namespace {

using croptop::PixelFormat;
using croptop::TileStore;
namespace lens = croptop::lens;

const char* kDatabase = R"(<lensdatabase version="1">
 <camera><maker>Canon</maker><model>Canon EOS 5D Mark IV</model><mount>Canon EF</mount><cropfactor>1.0</cropfactor></camera>
 <camera><maker>Canon</maker><model>Canon EOS 90D</model><mount>Canon EF-S</mount><cropfactor>1.6</cropfactor></camera>
 <lens>
  <maker>Canon</maker>
  <model>Canon EF 24-70mm f/2.8L II USM</model>
  <model lang="de">Canon EF 24-70mm f/2,8L II USM</model>
  <mount>Canon EF</mount>
  <cropfactor>1.0</cropfactor>
  <calibration>
   <distortion model="ptlens" focal="24" a="0.01" b="-0.04" c="0.0"/>
   <distortion model="ptlens" focal="70" a="0.0" b="0.02" c="0.0"/>
   <tca model="poly3" focal="24" vr="1.0004" vb="0.9996"/>
   <tca model="poly3" focal="70" vr="1.0002" vb="0.9998"/>
   <vignetting model="pa" focal="24" aperture="2.8" distance="1000" k1="-0.6" k2="0.2" k3="-0.05"/>
   <vignetting model="pa" focal="24" aperture="8" distance="1000" k1="-0.2" k2="0.05" k3="0"/>
   <vignetting model="pa" focal="70" aperture="2.8" distance="1000" k1="-0.4" k2="0.1" k3="0"/>
   <vignetting model="pa" focal="70" aperture="8" distance="1000" k1="-0.1" k2="0" k3="0"/>
   <vignetting model="pa" focal="24" aperture="2.8" distance="1" k1="-0.9" k2="0" k3="0"/>
  </calibration>
 </lens>
 <lens>
  <maker>Canon</maker><model>Canon EF 50mm f/1.8 STM</model><mount>Canon EF</mount><cropfactor>1.0</cropfactor>
  <calibration><distortion model="poly3" focal="50" k1="-0.012"/><distortion model="poly3" focal="50" k1="0.99"/></calibration>
 </lens>
</lensdatabase>)";

TileStore paint(std::uint32_t w, std::uint32_t h, const std::function<std::array<float, 3>(double, double)>& f) {
    TileStore s(PixelFormat::RGBA32F, w, h);
    for (std::uint32_t ty = 0; ty * 256 < h; ++ty)
        for (std::uint32_t tx = 0; tx * 256 < w; ++tx) {
            croptop::TileBuffer t(s.tile_byte_size(), 0);
            for (std::uint32_t y = 0; y < 256; ++y)
                for (std::uint32_t x = 0; x < 256; ++x) {
                    const auto gx = tx * 256 + x, gy = ty * 256 + y;
                    if (gx >= w || gy >= h) continue;
                    const auto c = f(gx + 0.5, gy + 0.5);
                    const float px[4] = {c[0], c[1], c[2], 1.0F};
                    std::memcpy(t.data() + (static_cast<std::size_t>(y) * 256 + x) * 16, px, 16);
                }
            EXPECT_TRUE(s.writeTile({static_cast<std::int32_t>(tx), static_cast<std::int32_t>(ty)}, t));
        }
    return s;
}

std::array<float, 4> at(const TileStore& s, std::uint32_t x, std::uint32_t y) {
    std::array<float, 4> out{};
    const auto t = s.readTile({static_cast<std::int32_t>(x / 256), static_cast<std::int32_t>(y / 256)});
    if (t) std::memcpy(out.data(), t->data() + (static_cast<std::size_t>(y % 256) * 256 + x % 256) * 16, 16);
    return out;
}

TEST(LensProfile, ParsesLooksUpAndInterpolates) {
    lens::Database db;
    std::string error;
    ASSERT_TRUE(db.addXml(kDatabase, error)) << error;
    ASSERT_EQ(db.lenses.size(), 2U);
    ASSERT_EQ(db.cameras.size(), 2U);
    const auto* l = db.findLens("canon", "EF 24-70mm f/2.8L II");
    ASSERT_NE(l, nullptr);
    EXPECT_EQ(l->model, "Canon EF 24-70mm f/2.8L II USM") << "English name preferred over lang=de";
    EXPECT_EQ(l->distortion.size(), 2U);
    EXPECT_EQ(db.findLens("Nikon", "24-70mm"), nullptr) << "maker must match";
    EXPECT_EQ(db.findLens("Canon", "EF 85mm"), nullptr) << "every requested token must be present";
    const auto* fifty = db.findLens("Canon", "50mm f/1.8");
    ASSERT_NE(fifty, nullptr);
    EXPECT_EQ(fifty->distortion.size(), 1U) << "degenerate poly3 (k1 = 0.99 → d ≈ 0) rejected";
    EXPECT_FLOAT_EQ(db.findCamera("Canon", "90D")->crop_factor, 1.6F);

    const auto mid = lens::interpolate(*l, 47.0F, 5.4F);
    EXPECT_EQ(mid.distortion.model, lens::DistortionModel::PTLens);
    EXPECT_NEAR(mid.distortion.k[0], 0.005F, 1e-6F);
    EXPECT_NEAR(mid.distortion.k[1], -0.01F, 1e-6F);
    EXPECT_NEAR(mid.tca.red[0], 1.0003F, 1e-6F);
    ASSERT_TRUE(mid.vignetting);
    EXPECT_EQ(mid.vignetting->distance, 1000.0F) << "farthest calibrated distance";
    // Bilinear in focal (0.5) and aperture ((5.4−2.8)/5.2 = 0.5): k1 = mean(−0.6, −0.2, −0.4, −0.1).
    EXPECT_NEAR(mid.vignetting->k[0], -0.325F, 1e-5F);
    const auto wide = lens::interpolate(*l, 10.0F, 1.0F);
    EXPECT_NEAR(wide.distortion.k[1], -0.04F, 1e-6F) << "clamped below the calibrated range";

    EXPECT_FALSE(lens::Database{}.addXml("<html>nope</html>", error));
}

/** PanoTools/lensfun PTLENS in Hugin units (the reference the implementation must reproduce). */
double ptlens(double r, double a, double b, double c) {
    const double d = 1 - a - b - c, x = r / d;
    return x * (a * x * x * x + b * x * x + c * x + d);
}

TEST(LensProfile, DistortionCorrectionStraightensTheGrid) {
    const int w = 600, h = 400;
    const double a = 0.0, b = -0.08, c = 0.0;  // strong barrel
    const double hugin_px = h / 2.0;            // FF 3:2 calibration, FF image: r = 1 at half the short side
    const auto grid = [](double x, double y) {
        const bool line = std::fmod(x, 40.0) < 2.0 || std::fmod(y, 40.0) < 2.0;
        const float v = line ? 0.9F : 0.1F;
        return std::array<float, 3>{v, v, v};
    };
    // Captured (distorted) image: pixel p shows the ideal point q with distort(|q|) = |p| (bisection).
    const auto distorted = paint(w, h, [&](double x, double y) {
        const double dx = x - w / 2.0, dy = y - h / 2.0, rd = std::hypot(dx, dy) / hugin_px;
        double lo = 0, hi = 3;
        for (int i = 0; i < 60; ++i) {
            const double m = 0.5 * (lo + hi);
            (ptlens(m, a, b, c) < rd ? lo : hi) = m;
        }
        const double s = rd > 0 ? lo / rd : 1.0;
        return grid(w / 2.0 + dx * s, h / 2.0 + dy * s);
    });
    lens::Correction corr;
    corr.distortion = {24, lens::DistortionModel::PTLens, {static_cast<float>(a), static_cast<float>(b), static_cast<float>(c)}};
    corr.calibration_crop = 1.0F;
    corr.calibration_aspect = 1.5F;
    std::string error;
    const auto fixed = lens::apply(distorted, corr, {}, error);
    ASSERT_TRUE(fixed) << error;
    // Compare with the ideal grid away from the rim (where the barrel pulled content outside the frame).
    int mismatches = 0, total = 0;
    for (int y = 60; y < h - 60; y += 3)
        for (int x = 80; x < w - 80; x += 3) {
            const float want = grid(x + 0.5, y + 0.5)[0], got = at(*fixed, x, y)[1];
            mismatches += std::abs(want - got) > 0.45F ? 1 : 0;
            ++total;
        }
    EXPECT_LT(mismatches, total / 50) << mismatches << " of " << total;
    // Without correction the distorted grid disagrees far more.
    int before = 0;
    for (int y = 60; y < h - 60; y += 3)
        for (int x = 80; x < w - 80; x += 3) before += std::abs(grid(x + 0.5, y + 0.5)[0] - at(distorted, x, y)[1]) > 0.45F ? 1 : 0;
    EXPECT_GT(before, mismatches * 3);
}

TEST(LensProfile, TcaAndVignettingAndCropFactor) {
    const int w = 512, h = 512;
    const double hugin_px = h / 2.0 / 1.0;  // aspect 1 calibration below: short side = full side
    const double kr = 1.004;
    // A white dot at (440, 256): the captured red channel sits at radius × kr (linear TCA), green in place.
    const auto dot = [](double x, double y, double cx, double cy) { return std::hypot(x - cx, y - cy) < 2.5 ? 1.0F : 0.0F; };
    const double gx = 440, gy = 256, rx = 256 + (gx - 256) * kr;
    const auto captured = paint(w, h, [&](double x, double y) { return std::array<float, 3>{dot(x, y, rx, gy), dot(x, y, gx, gy), dot(x, y, gx, gy)}; });
    lens::Correction corr;
    corr.tca.model = lens::TcaModel::Linear;
    corr.tca.red = {static_cast<float>(kr), 0, 0};
    corr.calibration_aspect = 1.0F;
    std::string error;
    const auto fixed = lens::apply(captured, corr, {}, error);
    ASSERT_TRUE(fixed) << error;
    // Red-channel centroid along the dot's row: offset by ≈0.74 px before, on the green centroid after.
    const auto centroid = [](const TileStore& s, int ch) {
        double sum = 0, weight = 0;
        for (std::uint32_t x = 430; x < 452; ++x)
            for (std::uint32_t y = 250; y < 263; ++y) {
                const double v = at(s, x, y)[static_cast<std::size_t>(ch)];
                sum += v * (x + 0.5);
                weight += v;
            }
        return sum / weight;
    };
    EXPECT_NEAR(centroid(captured, 0) - centroid(captured, 1), (kr - 1.0) * (gx - 256), 0.05);
    EXPECT_NEAR(centroid(*fixed, 0), centroid(*fixed, 1), 0.08) << "red realigned onto green";
    (void)hugin_px;

    // Vignetting: a flat 0.5 field darkened by the pa model becomes flat again.
    const float k1 = -0.3F, k2 = 0.05F;
    const double corner = std::hypot(w, h) / 2.0;  // FF calibration, FF image
    const auto vignetted = paint(w, h, [&](double x, double y) {
        const double r2 = std::pow(std::hypot(x - w / 2.0, y - h / 2.0) / corner, 2.0);
        const float v = static_cast<float>(0.5 * (1 + k1 * r2 + k2 * r2 * r2));
        return std::array<float, 3>{v, v, v};
    });
    lens::Correction vc;
    vc.vignetting = lens::VignettingCalibration{50, 4, 1000, {k1, k2, 0}};
    vc.calibration_aspect = 1.0F;
    const auto flat = lens::apply(vignetted, vc, {}, error);
    ASSERT_TRUE(flat) << error;
    EXPECT_NEAR(at(*flat, 2, 2)[0], 0.5F, 2e-3F);
    EXPECT_NEAR(at(*flat, 256, 256)[0], 0.5F, 2e-3F);

    // Crop factor: the same image shot on a 1.6× body covers only the centre of the calibration circle,
    // so the corner correction is much weaker.
    lens::ApplyOptions crop;
    crop.image_crop_factor = 1.6F;
    const auto partial = lens::apply(vignetted, vc, crop, error);
    ASSERT_TRUE(partial) << error;
    EXPECT_LT(at(*partial, 2, 2)[0], at(*flat, 2, 2)[0] - 0.05F);

    // Degenerate input is rejected or harmless.
    lens::ApplyOptions bad;
    bad.image_crop_factor = 0.0F;
    EXPECT_FALSE(lens::apply(vignetted, vc, bad, error));
    EXPECT_FALSE(lens::apply(TileStore(PixelFormat::A16, 8, 8), vc, {}, error));
    lens::Correction wild;
    wild.distortion = {50, lens::DistortionModel::PTLens, {9.0F, -9.0F, 0.9F}};
    EXPECT_TRUE(lens::apply(vignetted, wild, {}, error)) << "extreme maps fall back to direct reads";
}

// Raw files and the database spell the same optic differently: maker suffixes, "F2.8" vs "f/2.8", a maker prefix on
// the lens, and a maker token glued to a lens designation ("M.12-50mm").
TEST(LensProfile, MatchesRawFileNamesToDatabaseSpellings) {
    lens::Database db;
    std::string error;
    ASSERT_TRUE(db.addXml(R"(<lensdatabase version="2">
 <camera><maker>Nikon Corporation</maker><model>Nikon D3200</model><mount>Nikon F AF</mount><cropfactor>1.55</cropfactor></camera>
 <camera><maker>Olympus Imaging Corp.</maker><model>E-M5</model><mount>Micro 4/3 System</mount><cropfactor>2</cropfactor></camera>
 <camera><maker>Olympus Imaging Corp.</maker><model>E-M5 Mark II</model><mount>Micro 4/3 System</mount><cropfactor>2</cropfactor></camera>
 <lens><maker>Sony</maker><model>Sony FE 16-35mm f/2.8 GM</model><mount>Sony E</mount><cropfactor>1</cropfactor>
  <calibration><distortion model="ptlens" focal="16" a="0" b="0.01" c="0"/></calibration></lens>
 <lens><maker>Olympus</maker><model>Olympus M.Zuiko Digital ED 12-50mm f/3.5-6.3 EZ</model><mount>Micro 4/3 System</mount><cropfactor>2</cropfactor>
  <calibration><distortion model="ptlens" focal="12" a="0" b="0.02" c="0"/></calibration></lens>
 <lens><maker>Sony</maker><model>Sony FE 16-35mm f/4 ZA OSS</model><mount>Sony E</mount><cropfactor>1</cropfactor>
  <calibration><distortion model="ptlens" focal="16" a="0" b="0.03" c="0"/></calibration></lens>
</lensdatabase>)", error)) << error;

    const auto* d3200 = db.findCamera("Nikon", "D3200");
    ASSERT_NE(d3200, nullptr);
    EXPECT_FLOAT_EQ(d3200->crop_factor, 1.55F);
    // The exact model wins over the longer one that also contains its tokens.
    const auto* em5 = db.findCamera("Olympus", "E-M5");
    ASSERT_NE(em5, nullptr);
    EXPECT_EQ(em5->model, "E-M5");
    EXPECT_EQ(db.findCamera("Olympus", "E-M10"), nullptr);

    const auto* fe = db.findLens("Sony", "FE 16-35mm F2.8 GM");
    ASSERT_NE(fe, nullptr);
    EXPECT_EQ(fe->model, "Sony FE 16-35mm f/2.8 GM");
    const auto* zuiko = db.findLens("Olympus", "OLYMPUS M.12-50mm F3.5-6.3");
    ASSERT_NE(zuiko, nullptr);
    EXPECT_NE(zuiko->model.find("12-50mm"), std::string::npos);
    // A different maker never matches, and a lens missing from the database stays missing.
    EXPECT_EQ(db.findLens("Canon", "FE 16-35mm F2.8 GM"), nullptr);
    EXPECT_EQ(db.findLens("Sony", "FE 24-70mm F2.8 GM"), nullptr);
}

TEST(LensProfile, MutatedDatabasesNeverCrash) {
    std::string xml = kDatabase, error;
    std::uint32_t seed = 3;
    for (int round = 0; round < 400; ++round) {
        std::string m = xml;
        for (int k = 0; k < 1 + round % 5; ++k) {
            seed = seed * 1664525U + 1013904223U;
            const auto pos = seed % m.size();
            if ((seed >> 30) == 0) m.erase(pos, 1 + (seed >> 8) % 30);
            else m[pos] = static_cast<char>(seed >> 16);
        }
        lens::Database db;
        if (db.addXml(m, error)) {
            for (const auto& l : db.lenses) (void)lens::interpolate(l, 35.0F, 4.0F);
        }
    }
}

}  // namespace
