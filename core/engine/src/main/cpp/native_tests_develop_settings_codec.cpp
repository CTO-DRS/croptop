// Tests for the flat develop-settings encoding used by the JNI layer.
#include "develop_settings_codec.h"

#include <gtest/gtest.h>

#include <cmath>
#include <limits>

using namespace croptop::develop;

namespace {
pipeline::Settings busy() {
    pipeline::Settings s;
    s.profile = pipeline::Profile::Landscape;
    s.white_balance = {pipeline::WhiteBalancePreset::Custom, 6100.0F, -12.0F};
    s.basic = {0.5F, 10, -20, 30, 5, -5, 12, 14, 8, 22, -7};
    s.mixer[3] = {5, 10, -15};
    s.mixer[7] = {-30, 40, 25};
    s.grading.shadows = {210, 30, -5};
    s.grading.global = {40, 10, 3};
    s.grading.blending = 65;
    s.grading.balance = -20;
    s.detail = {55, 1.4F, 30, 20, 15, 35};
    s.effects = {-25, 40, 12, 33};
    s.optics = {false, true, 8, -4};
    s.crop = {0.1F, 0.05F, 0.9F, 0.95F, 2.5F};
    s.tone_curve.points = {{0, 0}, {0.25F, 0.2F}, {0.75F, 0.82F}, {1, 1}};
    return s;
}
}  // namespace

TEST(DevelopSettingsCodec, RoundTripKeepsEveryField) {
    const auto original = busy();
    const auto encoded = encodeSettings(original);
    EXPECT_EQ(encoded.size(), kSettingsFixedFloats + 8U);
    std::string error;
    const auto decoded = decodeSettings(encoded, error);
    ASSERT_TRUE(decoded.has_value()) << error;
    EXPECT_EQ(encodeSettings(*decoded), encoded);
    EXPECT_EQ(decoded->profile, pipeline::Profile::Landscape);
    EXPECT_EQ(decoded->white_balance.preset, pipeline::WhiteBalancePreset::Custom);
    EXPECT_FLOAT_EQ(decoded->mixer[7].hue, -30.0F);
    EXPECT_FALSE(decoded->optics.lens_profile);
    EXPECT_EQ(decoded->tone_curve.points.size(), 4U);
    EXPECT_FLOAT_EQ(decoded->tone_curve.points[1].second, 0.2F);
}

TEST(DevelopSettingsCodec, DefaultsRoundTrip) {
    std::string error;
    const auto decoded = decodeSettings(encodeSettings({}), error);
    ASSERT_TRUE(decoded.has_value()) << error;
    EXPECT_EQ(decoded->tone_curve.points.size(), 2U);
    EXPECT_FLOAT_EQ(decoded->detail.sharpen_amount, 40.0F);
}

TEST(DevelopSettingsCodec, MalformedArraysAreRejected) {
    std::string error;
    auto good = encodeSettings(busy());
    EXPECT_FALSE(decodeSettings(std::span<const float>(good).first(10), error).has_value());
    auto bad = good;
    bad[0] = 2;
    EXPECT_FALSE(decodeSettings(bad, error).has_value());
    bad = good;
    bad[1] = 9;
    EXPECT_FALSE(decodeSettings(bad, error).has_value());
    bad = good;
    bad[5] = std::numeric_limits<float>::quiet_NaN();
    EXPECT_FALSE(decodeSettings(bad, error).has_value());
    bad = good;
    bad[73] = 5;  // count does not match the array length
    EXPECT_FALSE(decodeSettings(bad, error).has_value());
    bad = good;
    bad[73] = 33;
    EXPECT_FALSE(decodeSettings(bad, error).has_value());
    bad = good;
    bad.push_back(0.0F);
    EXPECT_FALSE(decodeSettings(bad, error).has_value());
}

TEST(DevelopSettingsCodec, CalibrationRoundTripsAloneAndBeforeMasks) {
    pipeline::Settings s;
    s.calibration = {20, 10, -30, 5, 15, -25, 40};
    s.upright = pipeline::Upright::Vertical;
    s.optics.defringe_purple = 8.0F;
    s.optics.defringe_green = 3.0F;
    const auto encoded = encodeSettings(s);
    EXPECT_EQ(encoded.size(), kSettingsFixedFloats + 4U + 7U + 1U + 2U + 8U + 1U);
    std::string error;
    auto decoded = decodeSettings(encoded, error);
    ASSERT_TRUE(decoded.has_value()) << error;
    EXPECT_FLOAT_EQ(decoded->calibration.shadow_tint, 20.0F);
    EXPECT_FLOAT_EQ(decoded->calibration.red_saturation, -30.0F);
    EXPECT_FLOAT_EQ(decoded->calibration.blue_saturation, 40.0F);
    EXPECT_TRUE(decoded->masks.empty());
    EXPECT_EQ(decoded->upright, pipeline::Upright::Vertical);
    EXPECT_FLOAT_EQ(decoded->optics.defringe_purple, 8.0F);
    EXPECT_FLOAT_EQ(decoded->optics.defringe_green, 3.0F);

    pipeline::LocalMask mask;
    pipeline::MaskComponent c;
    c.kind = pipeline::MaskKind::Linear;
    c.p = {0.5F, 0.1F, 0.5F, 0.6F, 0, 0, 0, 0};
    mask.components = {c};
    s.masks.push_back(mask);
    decoded = decodeSettings(encodeSettings(s), error);
    ASSERT_TRUE(decoded.has_value()) << error;
    EXPECT_FLOAT_EQ(decoded->calibration.green_hue, 5.0F);
    ASSERT_EQ(decoded->masks.size(), 1U);

    pipeline::Settings masked_only;
    masked_only.masks.push_back(mask);
    decoded = decodeSettings(encodeSettings(masked_only), error);
    ASSERT_TRUE(decoded.has_value()) << error;
    EXPECT_TRUE(decoded->calibration.isIdentity());
    EXPECT_EQ(decoded->masks.size(), 1U);

    auto truncated = encoded;
    truncated.pop_back();
    EXPECT_FALSE(decodeSettings(truncated, error).has_value());
}

TEST(DevelopSettingsCodec, SpotsRoundTripAndAreBounded) {
    pipeline::Settings s;
    s.spots.push_back({0.2F, 0.3F, 0.02F, 0.4F, 0.5F, 0.6F, 0.9F, true});
    s.spots.push_back({0.7F, 0.1F, 0.05F, 0.1F, 0.1F, 0.0F, 1.0F, false});
    const auto encoded = encodeSettings(s);
    EXPECT_EQ(encoded.size(), kSettingsFixedFloats + 4U + 7U + 1U + 2U + 8U + 1U + 16U);
    std::string error;
    const auto decoded = decodeSettings(encoded, error);
    ASSERT_TRUE(decoded.has_value()) << error;
    ASSERT_EQ(decoded->spots.size(), 2U);
    EXPECT_FLOAT_EQ(decoded->spots[0].source_x, 0.4F);
    EXPECT_TRUE(decoded->spots[0].heal);
    EXPECT_FALSE(decoded->spots[1].heal);
    EXPECT_FLOAT_EQ(decoded->spots[1].radius, 0.05F);

    auto truncated = encoded;
    truncated.pop_back();
    EXPECT_FALSE(decodeSettings(truncated, error).has_value());
    auto absurd = encoded;
    absurd[kSettingsFixedFloats + 4U + 7U + 1U + 2U + 8U] = 1000.0F;
    EXPECT_FALSE(decodeSettings(absurd, error).has_value());
}

TEST(DevelopSettingsCodec, UprightManualAndGuidesRoundTrip) {
    pipeline::Settings s;
    s.upright = pipeline::Upright::Guided;
    s.upright_manual.vertical = 12.0F;
    s.upright_manual.scale = 110.0F;
    s.upright_guides = {{0.1F, 0.2F, 0.15F, 0.8F}, {0.7F, 0.1F, 0.72F, 0.9F}};
    std::string error;
    const auto decoded = decodeSettings(encodeSettings(s), error);
    ASSERT_TRUE(decoded.has_value()) << error;
    EXPECT_EQ(decoded->upright, pipeline::Upright::Guided);
    EXPECT_FLOAT_EQ(decoded->upright_manual.vertical, 12.0F);
    EXPECT_FLOAT_EQ(decoded->upright_manual.scale, 110.0F);
    ASSERT_EQ(decoded->upright_guides.size(), 2U);
    EXPECT_FLOAT_EQ(decoded->upright_guides[1].x2, 0.72F);
    auto truncated = encodeSettings(s);
    truncated.pop_back();
    EXPECT_FALSE(decodeSettings(truncated, error).has_value());
}
