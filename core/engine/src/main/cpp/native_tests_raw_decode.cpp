// Tests for the LibRaw front end. Hostile-input checks always run. Real-camera checks need
// sample raws (CC0 files from https://raw.pixls.us) in the directory named by CROPTOP_RAW_SAMPLES and skip otherwise;
// they decode every file there and check plausibility rather than exact pixels.
#include "native_tests_stress_gate.h"
#include "native_tests_synthetic_dng.h"
#include "raw_decode.h"
#include "raw_open.h"

#include <gtest/gtest.h>

#include <algorithm>
#include <array>
#include <cmath>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <filesystem>
#include <fstream>
#include <iterator>
#include <memory>
#include <string>
#include <utility>
#include <vector>

namespace {

namespace raw = croptop::raw;
using croptop::testing::kDngBlack;
using croptop::testing::kDngHeight;
using croptop::testing::kDngWhite;
using croptop::testing::kDngWidth;
using croptop::testing::syntheticDng;
using croptop::testing::syntheticLinearDng;

std::vector<std::uint8_t> readFile(const std::filesystem::path& path) {
    std::ifstream file(path, std::ios::binary);
    return {std::istreambuf_iterator<char>(file), std::istreambuf_iterator<char>()};
}

std::vector<std::filesystem::path> sampleFiles() {
    std::vector<std::filesystem::path> files;
    const char* directory = std::getenv("CROPTOP_RAW_SAMPLES");
    if (directory == nullptr || !std::filesystem::is_directory(directory)) return files;
    for (const auto& entry : std::filesystem::directory_iterator(directory)) {
        if (entry.is_regular_file()) files.push_back(entry.path());
    }
    std::sort(files.begin(), files.end());
    return files;
}


TEST(RawDecode, ASyntheticDngDecodesToItsMosaicLevelsAndWhiteBalance) {
    const auto file = syntheticDng();
    std::string error;
    const auto probe = raw::probe(file, error);
    ASSERT_TRUE(probe) << error;
    EXPECT_EQ(probe->width, kDngWidth);
    EXPECT_EQ(probe->height, kDngHeight);
    EXPECT_TRUE(probe->metadata.is_dng);
    EXPECT_TRUE(probe->decodable) << probe->unsupported_reason;
    EXPECT_FALSE(probe->xtrans);

    const auto image = raw::decode(file, error);
    ASSERT_TRUE(image) << error;
    ASSERT_EQ(image->photosites.size(), static_cast<std::size_t>(kDngWidth) * kDngHeight);
    EXPECT_TRUE(image->pattern.isBayer());
    EXPECT_EQ(image->pattern.at(0, 0), 0) << "top-left photosite is red";
    EXPECT_EQ(image->pattern.at(1, 0), 1);
    EXPECT_EQ(image->pattern.at(1, 1), 2) << "the diagonal partner of red is blue";
    EXPECT_EQ(image->photosites[0], kDngBlack + 1000);
    EXPECT_EQ(image->photosites[1], kDngBlack + 2000);
    EXPECT_EQ(image->photosites[kDngWidth + 1], kDngBlack + 1333);
    for (const float b : image->black) EXPECT_NEAR(b, kDngBlack, 0.5F);
    EXPECT_NEAR(image->white, kDngWhite, 1.0F);
    EXPECT_NEAR(image->as_shot_wb[0], 2.0F, 0.02F);
    EXPECT_FLOAT_EQ(image->as_shot_wb[1], 1.0F);
    EXPECT_NEAR(image->as_shot_wb[2], 1.5F, 0.02F);
    EXPECT_TRUE(image->xyz_to_camera.has_value());
}

TEST(RawDecode, ASyntheticDngOpensAsANeutralGrayImage) {
    const auto file = syntheticDng();
    std::string error;
    const auto opened = raw::openFlattened(file, {}, error);
    ASSERT_TRUE(opened) << error;
    EXPECT_EQ(opened->pixels.width(), kDngWidth);
    EXPECT_EQ(opened->pixels.height(), kDngHeight);
    const auto tile = opened->pixels.readTile({0, 0});
    ASSERT_NE(tile, nullptr);
    // Away from the demosaic border: white balance makes the gray patch neutral, at about 0.5 linear = 0.73 sRGB.
    const std::size_t at = (static_cast<std::size_t>(24) * croptop::kTileEdge + 32) * 8;
    std::array<std::uint16_t, 4> px{};
    std::memcpy(px.data(), tile->data() + at, 8);
    const double r = px[0] / 65535.0, g = px[1] / 65535.0, b = px[2] / 65535.0;
    EXPECT_NEAR(r, g, 0.03);
    EXPECT_NEAR(b, g, 0.03);
    EXPECT_GT(g, 0.55);
    EXPECT_LT(g, 0.9);
    EXPECT_EQ(px[3], 0xFFFF);
}

TEST(RawDecode, ADemosaicedLinearDngDecodesAndOpensAsTheSameNeutralGray) {
    const auto file = syntheticLinearDng();
    std::string error;
    const auto probe = raw::probe(file, error);
    ASSERT_TRUE(probe) << error;
    EXPECT_TRUE(probe->linear);
    EXPECT_TRUE(probe->decodable) << probe->unsupported_reason;
    const auto image = raw::decode(file, error);
    ASSERT_TRUE(image) << error;
    ASSERT_TRUE(image->isLinear());
    ASSERT_EQ(image->linear_rgb.size(), static_cast<std::size_t>(kDngWidth) * kDngHeight * 3);
    EXPECT_EQ(image->linear_rgb[0], kDngBlack + 1000);
    EXPECT_EQ(image->linear_rgb[1], kDngBlack + 2000);
    EXPECT_EQ(image->linear_rgb[2], kDngBlack + 1333);
    EXPECT_NEAR(image->as_shot_wb[0], 2.0F, 0.02F);
    EXPECT_NEAR(image->as_shot_wb[2], 1.5F, 0.02F);

    const auto opened = raw::openFlattened(file, {}, error);
    ASSERT_TRUE(opened) << error;
    const auto tile = opened->pixels.readTile({0, 0});
    ASSERT_NE(tile, nullptr);
    std::array<std::uint16_t, 4> px{};
    std::memcpy(px.data(), tile->data() + (static_cast<std::size_t>(24) * croptop::kTileEdge + 32) * 8, 8);
    const double r = px[0] / 65535.0, g = px[1] / 65535.0, b = px[2] / 65535.0;
    EXPECT_NEAR(r, g, 0.03);
    EXPECT_NEAR(b, g, 0.03);
    EXPECT_GT(g, 0.55);
    EXPECT_LT(g, 0.9);
}

TEST(RawDecode, LensCorrectionWithoutAMatchingLensOnlyWarns) {
    const auto file = syntheticDng();
    raw::OpenOptions options;
    options.lens_database = std::make_shared<croptop::lens::Database>();
    std::string error;
    const auto opened = raw::openFlattened(file, options, error);
    ASSERT_TRUE(opened) << error;
    EXPECT_TRUE(std::any_of(opened->warnings.begin(), opened->warnings.end(),
                            [](const std::string& w) { return w.find("lens correction skipped") != std::string::npos; }));
    EXPECT_EQ(opened->pixels.width(), kDngWidth);
}

TEST(RawDecode, RejectsEmptyGarbageAndTruncatedInput) {
    std::string error;
    EXPECT_FALSE(raw::probe({}, error));
    EXPECT_FALSE(error.empty());
    const std::vector<std::uint8_t> garbage(4096, 0x5A);
    error.clear();
    EXPECT_FALSE(raw::probe(garbage, error));
    error.clear();
    EXPECT_FALSE(raw::decode(garbage, error));
    EXPECT_FALSE(error.empty());
    error.clear();
    EXPECT_FALSE(raw::extractPreview(garbage, error));
    EXPECT_FALSE(error.empty());
    // A TIFF header with nothing behind it is not a raw either.
    const std::vector<std::uint8_t> bare_tiff = {'I', 'I', 42, 0, 8, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0};
    error.clear();
    EXPECT_FALSE(raw::decode(bare_tiff, error));
}

TEST(RawDecode, EnforcesTheFileSizeLimit) {
    raw::RawLimits limits;
    limits.max_file_bytes = 16;
    const std::vector<std::uint8_t> bytes(64, 0);
    std::string error;
    EXPECT_FALSE(raw::probe(bytes, error, limits));
    EXPECT_NE(error.find("too large"), std::string::npos);
}

TEST(RawDecode, SampleRawsDecodeToPlausibleMosaics) {
    const auto files = sampleFiles();
    if (files.empty()) GTEST_SKIP() << "set CROPTOP_RAW_SAMPLES to a directory of raw files";
    for (const auto& path : files) {
        SCOPED_TRACE(path.filename().string());
        const auto bytes = readFile(path);
        std::string error;
        const auto probe = raw::probe(bytes, error);
        ASSERT_TRUE(probe) << error;
        EXPECT_FALSE(probe->metadata.make.empty());
        ASSERT_TRUE(probe->decodable) << probe->unsupported_reason;
        const auto image = raw::decode(bytes, error);
        ASSERT_TRUE(image) << error;
        EXPECT_EQ(image->width, probe->width);
        EXPECT_EQ(image->height, probe->height);
        ASSERT_EQ(image->photosites.size(), static_cast<std::size_t>(image->width) * image->height);
        EXPECT_TRUE(image->pattern.isBayer() || image->pattern.width == 6);
        EXPECT_EQ(image->pattern.isBayer(), !probe->xtrans);
        const float black = *std::max_element(image->black.begin(), image->black.end());
        EXPECT_GT(image->white, black);
        EXPECT_LE(image->white, 65535.0F);
        // White balance is green-normalised and both red and blue need a boost on real sensors.
        EXPECT_FLOAT_EQ(image->as_shot_wb[1], 1.0F);
        EXPECT_GT(image->as_shot_wb[0], 0.5F);
        EXPECT_GT(image->as_shot_wb[2], 0.5F);
        // The photosites really span the sensor range: some are above the black level and none above 16 bits.
        const auto max = *std::max_element(image->photosites.begin(), image->photosites.end());
        EXPECT_GT(static_cast<float>(max), black + 100.0F);

        const auto preview = raw::extractPreview(bytes, error);
        ASSERT_TRUE(preview) << error;
        ASSERT_GT(preview->jpeg.size(), 1000U);
        EXPECT_EQ(preview->jpeg[0], 0xFF);
        EXPECT_EQ(preview->jpeg[1], 0xD8);
        EXPECT_GT(preview->width, 100U);
    }
}

TEST(RawDecode, SampleRawsDevelopAtTheSensorSize) {
    CROPTOP_REQUIRE_STRESS();
    const auto files = sampleFiles();
    if (files.empty()) GTEST_SKIP() << "set CROPTOP_RAW_SAMPLES to a directory of raw files";
    for (const auto& path : files) {
        SCOPED_TRACE(path.filename().string());
        const auto bytes = readFile(path);
        std::string error;
        const auto image = raw::decode(bytes, error);
        ASSERT_TRUE(image) << error;
        const auto developed = raw::develop(image->cfa(), image->developSpec(), error);
        ASSERT_TRUE(developed) << error;
        EXPECT_EQ(developed->width(), image->width);
        EXPECT_EQ(developed->height(), image->height);
    }
}

}  // namespace
