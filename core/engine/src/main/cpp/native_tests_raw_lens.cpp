// Tests for raw lens-profile auto-detect.
#include "raw_lens.h"

#include <gtest/gtest.h>

namespace {

namespace raw = croptop::raw;

const char* kDb = R"(<lensdatabase version="2">
 <camera><maker>Canon</maker><model>Canon EOS 450D</model><mount>Canon EF-S</mount><cropfactor>1.62</cropfactor></camera>
 <lens><maker>Canon</maker><model>Canon EF-S 55-250mm f/4-5.6 IS</model><mount>Canon EF-S</mount><cropfactor>1.62</cropfactor>
  <calibration>
   <distortion model="ptlens" focal="55" a="0.0" b="0.01" c="0"/>
   <distortion model="ptlens" focal="250" a="0.0" b="-0.02" c="0"/>
   <vignetting model="pa" focal="55" aperture="5.6" distance="1000" k1="-0.3" k2="0" k3="0"/>
  </calibration></lens>
 <lens><maker>Sigma</maker><model>Sigma 35mm f/1.4 DG HSM | A</model><mount>Canon EF</mount><cropfactor>1</cropfactor>
  <calibration><distortion model="poly3" focal="35" k1="-0.01"/></calibration></lens>
</lensdatabase>)";

raw::RawMetadata canon() {
    raw::RawMetadata m;
    m.make = m.normalized_make = "Canon";
    m.model = m.normalized_model = "EOS 450D";
    m.lens = "EF-S 55-250mm f/4-5.6 IS";
    m.focal_length_mm = 152.5F;
    m.aperture = 5.6F;
    return m;
}

TEST(RawLens, DetectsTheProfileAndInterpolatesForTheShot) {
    croptop::lens::Database db;
    std::string error;
    ASSERT_TRUE(db.addXml(kDb, error)) << error;
    const auto detected = raw::detectLensProfile(db, canon(), error);
    ASSERT_TRUE(detected) << error;
    EXPECT_EQ(detected->lens_model, "Canon EF-S 55-250mm f/4-5.6 IS");
    EXPECT_TRUE(detected->camera_known);
    EXPECT_FLOAT_EQ(detected->apply.image_crop_factor, 1.62F);
    // Halfway between the two calibrated focal lengths for distortion b: (0.01 + -0.02) / 2 at 152.5 mm.
    EXPECT_NEAR(detected->correction.distortion.k[1], -0.005F, 1e-4F);
    EXPECT_TRUE(detected->correction.vignetting.has_value());
}

TEST(RawLens, ThirdPartyLensesMatchWithoutTheBodyMaker) {
    croptop::lens::Database db;
    std::string error;
    ASSERT_TRUE(db.addXml(kDb, error)) << error;
    auto meta = canon();
    meta.lens = "Sigma 35mm f/1.4 DG HSM | A";
    meta.focal_length_mm = 35.0F;
    const auto detected = raw::detectLensProfile(db, meta, error);
    ASSERT_TRUE(detected) << error;
    EXPECT_EQ(detected->lens_maker, "Sigma");
}

TEST(RawLens, ExplainsWhyNoProfileWasFound) {
    croptop::lens::Database db;
    std::string error;
    ASSERT_TRUE(db.addXml(kDb, error)) << error;
    auto meta = canon();
    meta.lens.clear();
    EXPECT_FALSE(raw::detectLensProfile(db, meta, error));
    EXPECT_NE(error.find("does not name"), std::string::npos);
    meta.lens = "EF 400mm f/2.8L IS";
    EXPECT_FALSE(raw::detectLensProfile(db, meta, error));
    EXPECT_NE(error.find("no profile"), std::string::npos);
}

TEST(RawLens, UnknownCameraKeepsUnitCropFactorAndSaysSo) {
    croptop::lens::Database db;
    std::string error;
    ASSERT_TRUE(db.addXml(kDb, error)) << error;
    auto meta = canon();
    meta.model = meta.normalized_model = "EOS-Nonexistent";
    const auto detected = raw::detectLensProfile(db, meta, error);
    ASSERT_TRUE(detected) << error;
    EXPECT_FALSE(detected->camera_known);
    EXPECT_FLOAT_EQ(detected->apply.image_crop_factor, 1.0F);
}

TEST(RawLens, MissingFocalLengthFallsBackToTheShortestCalibration) {
    croptop::lens::Database db;
    std::string error;
    ASSERT_TRUE(db.addXml(kDb, error)) << error;
    auto meta = canon();
    meta.focal_length_mm = 0.0F;
    const auto detected = raw::detectLensProfile(db, meta, error);
    ASSERT_TRUE(detected) << error;
    EXPECT_NEAR(detected->correction.distortion.focal, 55.0F, 1e-3F);
}

}  // namespace
