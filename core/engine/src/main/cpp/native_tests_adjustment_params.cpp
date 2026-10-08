// Tests for mapping AdjustmentCatalog parameters onto adjust:: structs.
#include "adjustment_params.h"

#include <gtest/gtest.h>

#include <string>

namespace {

namespace aj = croptop::adjust;

TEST(AdjustmentParams, EveryCatalogIdMapsWithDefaults) {
    for (const char* id : {"brightnessContrast", "levels", "curves", "exposure", "vibrance", "hueSaturation", "colorBalance", "blackWhite", "photoFilter",
                           "invert", "posterize", "threshold", "channelMixer", "gradientMap", "selectiveColor"}) {
        std::string error;
        const auto m = aj::fromParams(id, {}, error);
        ASSERT_TRUE(m) << id << ": " << error;
        EXPECT_TRUE(m->pixel.has_value()) << id;
    }
    std::string error;
    const auto sh = aj::fromParams("shadowsHighlights", {}, error);
    ASSERT_TRUE(sh);
    EXPECT_FLOAT_EQ(sh->spatial->shadows, 35);
    EXPECT_FALSE(aj::fromParams("nope", {}, error));
}

TEST(AdjustmentParams, ValuesAreClampedAndTyped) {
    std::string error;
    const auto bc = aj::fromParams("brightnessContrast", {{"brightness", 500.0}, {"contrast", -20.0}, {"legacy", true}}, error);
    ASSERT_TRUE(bc) << error;
    const auto& b = std::get<aj::BrightnessContrast>(*bc->pixel);
    EXPECT_FLOAT_EQ(b.brightness, 150);
    EXPECT_FLOAT_EQ(b.contrast, -20);
    EXPECT_TRUE(b.legacy);
    EXPECT_FALSE(aj::fromParams("exposure", {{"exposure", std::string("x")}}, error)) << "wrong type is rejected";
    EXPECT_NE(error.find("exposure"), std::string::npos);
}

TEST(AdjustmentParams, ChoicesPresetsAndLimitedModels) {
    std::string error;
    const auto sepia = aj::fromParams("photoFilter", {{"filter", std::string("sepia")}, {"density", 60.0}}, error);
    ASSERT_TRUE(sepia) << error;
    EXPECT_NEAR(std::get<aj::PhotoFilter>(*sepia->pixel).color[0], 172 / 255.0F, 1e-6F);
    EXPECT_FLOAT_EQ(std::get<aj::PhotoFilter>(*sepia->pixel).density, 60);
    const auto mixer = aj::fromParams("channelMixer", {{"output", std::string("blue")}, {"red", 50.0}, {"blue", 50.0}}, error);
    ASSERT_TRUE(mixer) << error;
    const auto& mx = std::get<aj::ChannelMixer>(*mixer->pixel);
    EXPECT_FLOAT_EQ(mx.matrix[2][0], 50);
    EXPECT_FLOAT_EQ(mx.matrix[0][0], 100) << "other outputs stay identity";
    const auto selective = aj::fromParams("selectiveColor", {{"colors", std::string("blues")}, {"cyan", 30.0}, {"method", std::string("absolute")}}, error);
    ASSERT_TRUE(selective) << error;
    const auto& sc = std::get<aj::SelectiveColor>(*selective->pixel);
    EXPECT_FLOAT_EQ(sc.cmyk[4][0], 30);
    EXPECT_TRUE(sc.absolute);
    aj::Levels levels{0.1F, 0.9F, 20.0F, 0, 1};
    const auto l = aj::fromParams("levels", {{"levels", levels}}, error);
    ASSERT_TRUE(l) << error;
    EXPECT_FLOAT_EQ(std::get<aj::Levels>(*l->pixel).gamma, 9.99F) << "gamma clamped to the schema range";
    EXPECT_FALSE(aj::fromParams("gradientMap", {{"gradient", std::vector<aj::GradientStop>{{0, {0, 0, 0, 1}}}}}, error));
}

}  // namespace
