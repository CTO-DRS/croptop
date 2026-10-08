// Catalogue adjustment block → mapper → baked texture contract.
#include "adjustment_lut_bridge.h"

#include <gtest/gtest.h>

#include <limits>
#include <string>
#include <vector>

namespace {
using croptop::adjust::bridge::bakeBlock;

TEST(AdjustmentLutBridge, BakesRepresentativeCatalogueBlocks) {
    const std::vector<std::pair<std::string,std::vector<float>>> cases{
        {"brightnessContrast",{0,35,0}}, {"levels",{0,1,1,0,1}},
        {"curves",{2,0,0,1,1,2,0,0,1,1,2,0,0,1,1,2,0,0,1,1}},
        {"exposure",{1,0,1}}, {"vibrance",{25,10}},
        {"hueSaturation",std::vector<float>(46,0)}, {"colorBalance",std::vector<float>(10,0)},
        {"blackWhite",{40,60,40,60,20,80,0,0.88F,0.76F,0.58F}},
        {"photoFilter",{2,0,0,0,25,1}}, {"invert",{}}, {"posterize",{5}}, {"threshold",{128}},
        {"channelMixer",{1,0,100,0,0,0}}, {"gradientMap",{0,0,2,0,0,0,0,1,1,1,1,1,1}},
        {"selectiveColor",{4,20,0,0,0,0}},
    };
    for(const auto& [id,block]:cases){
        std::string error;
        auto lut=bakeBlock(id,block,3,error);
        ASSERT_TRUE(lut) << id << ": " << error;
        EXPECT_EQ(lut->size_3d,3U) << id;
        EXPECT_EQ(lut->table_3d.size(),81U) << id;
        EXPECT_EQ(lut->toTexture().size(),108U) << id;
    }
    std::string error;
    EXPECT_FALSE(bakeBlock("shadowsHighlights",{35,0},3,error));
    EXPECT_NE(error.find("spatial"),std::string::npos);
}

TEST(AdjustmentLutBridge, LutTextureIsRGBAAndRedAxisIsFastest) {
    std::string error;
    auto lut=bakeBlock("invert",{},3,error);
    ASSERT_TRUE(lut) << error;
    const auto rgba=lut->toTexture();
    ASSERT_EQ(rgba.size(),108U);
    // Flat texel index is ((blue * size + green) * size + red).
    EXPECT_FLOAT_EQ(rgba[0],1); EXPECT_FLOAT_EQ(rgba[1],1); EXPECT_FLOAT_EQ(rgba[2],1); EXPECT_FLOAT_EQ(rgba[3],1);
    EXPECT_FLOAT_EQ(rgba[4],0.5F);  // red advances first
    EXPECT_FLOAT_EQ(rgba[(3U*4U)+1U],0.5F); // then green
    EXPECT_FLOAT_EQ(rgba[(9U*4U)+2U],0.5F); // then blue
    for(std::size_t i=3;i<rgba.size();i+=4) EXPECT_FLOAT_EQ(rgba[i],1);
}

TEST(AdjustmentLutBridge, RejectsInvalidBlocksAndBoundsWithoutAllocating) {
    std::string error;
    EXPECT_FALSE(bakeBlock("invert",{0},3,error));
    EXPECT_FALSE(error.empty());
    EXPECT_FALSE(bakeBlock("exposure",{0,std::numeric_limits<float>::infinity(),1},3,error));
    EXPECT_FALSE(bakeBlock("curves",{65},3,error));
    EXPECT_FALSE(bakeBlock("gradientMap",{0,0,64},3,error));
    EXPECT_FALSE(bakeBlock("photoFilter",{4,0,0,0,25,1},3,error));
    EXPECT_FALSE(bakeBlock("channelMixer",{1.5F,0,100,0,0,0},3,error));
    EXPECT_FALSE(bakeBlock("brightnessContrast",{0,0,0.5F},3,error));
    EXPECT_FALSE(bakeBlock("levels",{0.2F,0.1F,1,0,1},3,error));
    EXPECT_FALSE(bakeBlock("levels",{0,1,1,0,1.1F},3,error));
    EXPECT_FALSE(bakeBlock("hueSaturation",[] { auto v=std::vector<float>(46,0); v[3]=2; return v; }(),3,error));
    EXPECT_FALSE(bakeBlock("hueSaturation",[] { auto v=std::vector<float>(46,0); v[5]=101; return v; }(),3,error));
    EXPECT_FALSE(bakeBlock("colorBalance",[] { auto v=std::vector<float>(10,0); v[0]=101; return v; }(),3,error));
    EXPECT_FALSE(bakeBlock("blackWhite",{40,60,40,60,20,80,1,1.2F,0,0},3,error));
    EXPECT_FALSE(bakeBlock("photoFilter",{3,0,0,1.1F,25,1},3,error));
    EXPECT_FALSE(bakeBlock("gradientMap",{2,0,2,0,0,0,0,1,1,1,1,1,1},3,error));
    EXPECT_FALSE(bakeBlock("selectiveColor",{4,0,0,0,0,0.5F},3,error));
    EXPECT_FALSE(bakeBlock("curves",{2,0,0,2,1,1,2,0,0,2,1,1,2,0,0,2,1,1,2,0,0,2,1,1,99},3,error));
    EXPECT_FALSE(bakeBlock("invert",{},1,error));
    EXPECT_FALSE(bakeBlock("invert",{},66,error));
    EXPECT_FALSE(bakeBlock("unknown",{},3,error));
}
}  // namespace
