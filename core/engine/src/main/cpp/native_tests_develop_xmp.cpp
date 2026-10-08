// Tests for develop XMP sidecars.
#include "develop_xmp.h"

#include <gtest/gtest.h>

#include <algorithm>
#include <chrono>
#include <cstdio>
#include <regex>
#include <string>

namespace {

namespace dev = croptop::develop;

dev::DevelopRecipe sample() {
    dev::DevelopRecipe r;
    r.tone.exposure = 0.37F;
    r.tone.contrast = 12.5F;
    r.tone.highlights = -64;
    r.tone.shadows = 41;
    r.tone.whites = 7;
    r.tone.blacks = -9;
    r.tone.texture = 15;
    r.tone.clarity = 22;
    r.tone.dehaze = 8;
    r.tone.vibrance = 18;
    r.tone.saturation = -4;
    r.tone.parametric.shadows = 10;
    r.tone.parametric.split_midtones = 0.55F;
    r.tone.point_curve.points = {{0, 0.02F}, {0.25F, 0.2F}, {0.75F, 0.8F}, {1, 1}};
    r.color.calibration.red_hue = 5;
    r.color.calibration.blue_saturation = 20;
    r.color.calibration.shadow_tint = -3;
    r.color.look = dev::Look::Portrait;
    r.color.mixer.hue[1] = -8;
    r.color.mixer.saturation[5] = 25;
    r.color.mixer.luminance[3] = -12;
    r.color.grading.shadows = {220, 18, -5};
    r.color.grading.highlights = {45, 12, 3};
    r.color.grading.blending = 60;
    r.color.grading.balance = -10;
    r.detail.sharpening = {55, 1.2F, 30, 40};
    r.detail.noise = {20, 45, 30, 50};
    r.detail.vignette.amount = -25;
    r.detail.vignette.feather = 70;
    r.detail.grain = {12, 30, 40, 99};
    r.white_balance = std::array<float, 3>{2.05F, 1.0F, 1.43F};
    return r;
}

TEST(DevelopXmp, OurSidecarsRoundTripExactly) {
    const auto original = sample();
    const auto xmp = dev::writeXmp(original, "CropTop 1.0 & friends");
    EXPECT_NE(xmp.find("crs:Exposure2012=\"0.37\""), std::string::npos);
    EXPECT_NE(xmp.find("CropTop 1.0 &amp; friends"), std::string::npos);
    std::string error;
    const auto back = dev::readXmp(xmp, error);
    ASSERT_TRUE(back) << error;
    EXPECT_EQ(back->tone.exposure, original.tone.exposure);
    EXPECT_EQ(back->tone.contrast, 12.5F) << "our namespace keeps fractions Lightroom rounds away";
    EXPECT_EQ(back->tone.parametric.split_midtones, 0.55F);
    EXPECT_EQ(back->tone.point_curve.points, original.tone.point_curve.points);
    EXPECT_EQ(back->color.look, dev::Look::Portrait);
    EXPECT_EQ(back->color.mixer.saturation[5], 25);
    EXPECT_EQ(back->color.grading.shadows.hue, 220);
    EXPECT_EQ(back->color.grading.balance, -10);
    EXPECT_EQ(back->detail.sharpening.radius, 1.2F);
    EXPECT_EQ(back->detail.grain.seed, 99U);
    ASSERT_TRUE(back->white_balance);
    EXPECT_EQ((*back->white_balance)[2], 1.43F);
}

TEST(DevelopXmp, LightroomReadsTheCrsPartAndWeImportLightroomSidecars) {
    // Drop our namespace: what remains is what Lightroom/ACR sees.
    const std::string xmp = dev::writeXmp(sample());
    std::string crs_only = std::regex_replace(xmp, std::regex(R"(\s+ctd:[A-Za-z0-9]+="[^"]*")"), "");
    crs_only = std::regex_replace(crs_only, std::regex(R"(<ctd:PointCurve>[\s\S]*</ctd:PointCurve>)"), "");
    ASSERT_EQ(crs_only.find("ctd:Clarity"), std::string::npos);
    std::string error;
    const auto lr = dev::readXmp(crs_only, error);
    ASSERT_TRUE(lr) << error << "\n" << crs_only;
    EXPECT_EQ(lr->tone.contrast, 13.0F) << "Lightroom stores whole numbers";
    EXPECT_NEAR(lr->tone.parametric.split_midtones, 0.55F, 1e-6F);
    EXPECT_NEAR(lr->tone.point_curve.points[1].second, 51.0F / 255.0F, 1e-6F);
    EXPECT_EQ(lr->color.look, dev::Look::None) << "looks are ours only";

    // A hand-written ACR-style sidecar: other prefix, element-form values, signs, a tone curve sequence.
    const std::string acr = R"(<x:xmpmeta xmlns:x="adobe:ns:meta/" x:xmptk="Adobe XMP Core 7.0">
 <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
  <rdf:Description rdf:about="" xmlns:cr="http://ns.adobe.com/camera-raw-settings/1.0/"
    cr:Exposure2012="+0.50" cr:Contrast2012="-12" cr:HueAdjustmentRed="-5" cr:ColorGradeShadowHue="200"
    cr:ColorGradeShadowSat="15" cr:Sharpness="40" cr:SharpenRadius="+1.0" cr:PostCropVignetteAmount="-20"
    cr:GrainFrequency="50" cr:Exposure2012Bogus="7">
   <cr:Texture>+20</cr:Texture>
   <cr:ToneCurvePV2012>
    <rdf:Seq>
     <rdf:li>0, 0</rdf:li>
     <rdf:li>128, 140</rdf:li>
     <rdf:li>255, 255</rdf:li>
    </rdf:Seq>
   </cr:ToneCurvePV2012>
  </rdf:Description>
 </rdf:RDF>
</x:xmpmeta>)";
    const auto imported = dev::readXmp(acr, error);
    ASSERT_TRUE(imported) << error;
    EXPECT_EQ(imported->tone.exposure, 0.5F);
    EXPECT_EQ(imported->tone.contrast, -12.0F);
    EXPECT_EQ(imported->tone.texture, 20.0F);
    EXPECT_EQ(imported->color.mixer.hue[0], -5.0F);
    EXPECT_EQ(imported->color.grading.shadows.hue, 200.0F);
    EXPECT_EQ(imported->detail.sharpening.amount, 40.0F);
    EXPECT_EQ(imported->detail.vignette.amount, -20.0F);
    ASSERT_EQ(imported->tone.point_curve.points.size(), 3U);
    EXPECT_NEAR(imported->tone.point_curve.points[1].second, 140.0F / 255.0F, 1e-6F);
    EXPECT_FALSE(imported->white_balance);
}

TEST(DevelopXmp, PrecedenceClampingAndMalformedInput) {
    const std::string both = R"(<x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
<rdf:Description xmlns:crs="http://ns.adobe.com/camera-raw-settings/1.0/" xmlns:ctd="http://ns.croptop.app/develop/1.0/"
 crs:Clarity2012="30" ctd:Clarity2012="31.5" crs:Exposure2012="+9" crs:Shadows2012="abc" ctd:Look="9" ctd:WhiteBalance="2,1"/>
</rdf:RDF></x:xmpmeta>)";
    std::string error;
    const auto r = dev::readXmp(both, error);
    ASSERT_TRUE(r) << error;
    EXPECT_EQ(r->tone.clarity, 31.5F) << "our exact value wins";
    EXPECT_EQ(r->tone.exposure, 5.0F) << "clamped to ±5";
    EXPECT_EQ(r->tone.shadows, 0.0F) << "unparseable values are ignored";
    EXPECT_EQ(r->color.look, dev::Look::None);
    EXPECT_FALSE(r->white_balance);

    EXPECT_FALSE(dev::readXmp("not xml at all", error));
    EXPECT_FALSE(dev::readXmp("", error));
    EXPECT_TRUE(dev::readXmp("<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"><rdf:RDF><rdf:Description crs:Texture=", error)) << "truncated: defaults";

    // Hostile documents parse in linear time: doubling the size of unterminated attributes and unclosed
    // properties must not quadruple the time (checked as a ratio, so sanitizer builds pass too).
    const auto hostileSeconds = [&](int n) {
        std::string hostile = "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\" xmlns:crs=\"http://ns.adobe.com/camera-raw-settings/1.0/\">";
        for (int k = 0; k < n; ++k) hostile += "<a b=\"";
        for (int k = 0; k < n; ++k) hostile += "<crs:Texture>";
        const auto start = std::chrono::steady_clock::now();
        (void)dev::readXmp(hostile, error);
        return std::chrono::duration<double>(std::chrono::steady_clock::now() - start).count();
    };
    (void)hostileSeconds(2000);  // warm up
    const double small = hostileSeconds(20000), large = hostileSeconds(40000);
    EXPECT_LT(large, small * 3.0 + 0.05) << "readXmp must stay linear on hostile input: " << small << " s vs " << large << " s";

    // Mutated sidecars never crash (ASan) and always either parse or fail cleanly.
    const auto xmp = dev::writeXmp(sample());
    std::uint32_t seed = 7;
    for (int round = 0; round < 600; ++round) {
        std::string m = xmp;
        for (int k = 0; k < 1 + round % 6; ++k) {
            seed = seed * 1664525U + 1013904223U;
            const auto pos = seed % m.size();
            switch (seed >> 29) {
                case 0: m.erase(pos, 1 + (seed >> 8) % 40); break;
                case 1: m.insert(pos, "<\"&#x;>"); break;
                default: m[pos] = static_cast<char>(seed >> 16); break;
            }
            if (m.empty()) m = "<";
        }
        (void)dev::readXmp(m, error);
    }
}

TEST(DevelopXmp, Throughput) {
    const auto xmp = dev::writeXmp(sample());
    std::string error;
    const auto t0 = std::chrono::steady_clock::now();
    for (int i = 0; i < 200; ++i) ASSERT_TRUE(dev::readXmp(xmp, error));
    const auto t1 = std::chrono::steady_clock::now();
    for (int i = 0; i < 200; ++i) (void)dev::writeXmp(sample());
    const auto t2 = std::chrono::steady_clock::now();
    const double read_ms = std::chrono::duration<double, std::milli>(t1 - t0).count() / 200, write_ms = std::chrono::duration<double, std::milli>(t2 - t1).count() / 200;
    std::printf("[xmp] read %.3f ms, write %.3f ms (%zu bytes)\n", read_ms, write_ms, xmp.size());
}

TEST(DevelopXmp, LightroomPresetsApplyOnlyTheirControls) {
    // The shape Lightroom Classic writes for a .xmp preset (Settings/…/*.xmp).
    const std::string preset_xmp = R"(<x:xmpmeta xmlns:x="adobe:ns:meta/" x:xmptk="Adobe XMP Core 7.0">
 <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
  <rdf:Description rdf:about="" xmlns:crs="http://ns.adobe.com/camera-raw-settings/1.0/"
   crs:PresetType="Normal" crs:Cluster="" crs:UUID="4B6C0A2E" crs:SupportsAmount="True" crs:SupportsColor="True"
   crs:Version="15.0" crs:ProcessVersion="11.0" crs:Contrast2012="+30" crs:Clarity2012="+20"
   crs:GrainAmount="25" crs:SaturationAdjustmentBlue="-40" crs:HasSettings="True">
   <crs:Name><rdf:Alt><rdf:li xml:lang="x-default">Moody Blue</rdf:li></rdf:Alt></crs:Name>
   <crs:Group><rdf:Alt><rdf:li xml:lang="x-default">CropTop Tests</rdf:li></rdf:Alt></crs:Group>
   <crs:ToneCurvePV2012><rdf:Seq><rdf:li>0, 20</rdf:li><rdf:li>128, 118</rdf:li><rdf:li>255, 255</rdf:li></rdf:Seq></crs:ToneCurvePV2012>
  </rdf:Description>
 </rdf:RDF>
</x:xmpmeta>)";
    std::string error;
    const auto preset = dev::readXmpPreset(preset_xmp, error);
    ASSERT_TRUE(preset) << error;
    EXPECT_EQ(preset->name, "Moody Blue");
    EXPECT_EQ(preset->group, "CropTop Tests");
    const auto has = [&](const char* n) { return std::find(preset->controls.begin(), preset->controls.end(), n) != preset->controls.end(); };
    EXPECT_TRUE(has("Contrast2012"));
    EXPECT_TRUE(has("PointCurve"));
    EXPECT_FALSE(has("Exposure2012"));
    EXPECT_EQ(preset->controls.size(), 5U);

    auto base = sample();
    const auto full = dev::applyPreset(base, *preset);
    EXPECT_FLOAT_EQ(full.tone.contrast, 30);
    EXPECT_FLOAT_EQ(full.tone.clarity, 20);
    EXPECT_FLOAT_EQ(full.detail.grain.amount, 25);
    EXPECT_FLOAT_EQ(full.color.mixer.saturation[5], -40);
    EXPECT_FLOAT_EQ(full.tone.exposure, base.tone.exposure) << "unset controls keep the photo's values";
    EXPECT_FLOAT_EQ(full.tone.highlights, base.tone.highlights);
    EXPECT_EQ(full.color.look, base.color.look);
    EXPECT_EQ(full.white_balance, base.white_balance);
    ASSERT_EQ(full.tone.point_curve.points.size(), 3U);
    EXPECT_NEAR(full.tone.point_curve.points[0].second, 20.0F / 255.0F, 1e-6F);

    // Amount: 50 % goes halfway, 200 % doubles the move (clamped), 0 % is the base.
    const auto half = dev::applyPreset(base, *preset, 0.5F);
    EXPECT_FLOAT_EQ(half.tone.contrast, (base.tone.contrast + 30) / 2);
    EXPECT_EQ(half.tone.point_curve.points.size(), 17U);
    EXPECT_NEAR(half.tone.point_curve.evaluate(0.0F), (base.tone.point_curve.evaluate(0.0F) + 20.0F / 255.0F) / 2, 1e-4F);
    const auto twice = dev::applyPreset(base, *preset, 2.0F);
    EXPECT_FLOAT_EQ(twice.tone.contrast, base.tone.contrast + 2 * (30 - base.tone.contrast));
    EXPECT_FLOAT_EQ(twice.color.mixer.saturation[5], -100) << "clamped";
    const auto none = dev::applyPreset(base, *preset, 0.0F);
    EXPECT_FLOAT_EQ(none.tone.contrast, base.tone.contrast);
    EXPECT_EQ(none.tone.point_curve.points, base.tone.point_curve.points);
}

TEST(DevelopXmp, OurSidecarsWorkAsPresets) {
    const auto source = sample();
    std::string error = "stale error from an earlier call";
    const auto preset = dev::readXmpPreset(dev::writeXmp(source), error);
    ASSERT_TRUE(preset) << error;
    EXPECT_TRUE(error.empty());
    const auto applied = dev::applyPreset(dev::DevelopRecipe{}, *preset);
    EXPECT_FLOAT_EQ(applied.tone.exposure, source.tone.exposure);
    EXPECT_EQ(applied.color.look, source.color.look);
    EXPECT_EQ(applied.white_balance, source.white_balance);
    EXPECT_EQ(applied.detail.grain.seed, source.detail.grain.seed);
    // Presets with nothing we support, or no XMP at all, are rejected.
    EXPECT_FALSE(dev::readXmpPreset(R"(<x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"><rdf:Description xmlns:crs="http://ns.adobe.com/camera-raw-settings/1.0/" crs:PresetType="Normal" crs:LensProfileEnable="1"/></rdf:RDF></x:xmpmeta>)", error));
    EXPECT_FALSE(error.empty());
    EXPECT_FALSE(dev::readXmpPreset("plain text", error));
    error = "stale error from an earlier call";
    EXPECT_TRUE(dev::readXmp(dev::writeXmp(source), error));
    EXPECT_TRUE(error.empty());
}

}  // namespace
