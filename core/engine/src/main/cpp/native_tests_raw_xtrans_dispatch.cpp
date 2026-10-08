// Dispatch coverage for the directional X-Trans implementation.
#include "raw_demosaic.h"
#include "raw_xtrans.h"

#include <gtest/gtest.h>

#include <algorithm>
#include <cstdint>
#include <limits>
#include <vector>

namespace {
namespace raw = croptop::raw;

std::vector<std::uint16_t> makeXtransSamples(raw::CfaPattern pattern, std::uint32_t width, std::uint32_t height) {
    std::vector<std::uint16_t> samples(static_cast<std::size_t>(width) * height);
    for (std::uint32_t y = 0; y < height; ++y)
        for (std::uint32_t x = 0; x < width; ++x) {
            const auto channel = pattern.at(x, y);
            const float value = channel == 0 ? 0.15F + 0.006F * x : channel == 1 ? 0.25F + 0.004F * y : 0.35F + 0.003F * (x + y);
            samples[static_cast<std::size_t>(y) * width + x] = static_cast<std::uint16_t>(value * 65535.0F + 0.5F);
        }
    return samples;
}

bool samePixels(const croptop::TileStore& a, const croptop::TileStore& b) {
    if (a.format() != b.format() || a.width() != b.width() || a.height() != b.height()) return false;
    for (const auto coord : a.tileCoordinates()) {
        const auto at = a.readTile(coord), bt = b.readTile(coord);
        if (!at || !bt || *at != *bt) return false;
    }
    return a.tileCoordinates().size() == b.tileCoordinates().size();
}

TEST(RawXTransDispatch, DefaultUsesDirectionalAndFastRemainsAnExplicitPreviewOption) {
    const auto pattern = raw::CfaPattern::xtrans();
    auto samples = makeXtransSamples(pattern, 32, 28);
    raw::CfaImage image{32, 28, samples, pattern};
    raw::DevelopSpec defaults;
    defaults.output_format = croptop::PixelFormat::RGBA16;
    std::string error;
    const auto default_result = raw::develop(image, defaults, error);
    ASSERT_TRUE(default_result) << error;
    auto explicit_hq = defaults;
    explicit_hq.method = raw::Demosaic::XTransDirectional;
    const auto hq_result = raw::develop(image, explicit_hq, error);
    ASSERT_TRUE(hq_result) << error;
    EXPECT_TRUE(samePixels(*default_result, *hq_result)) << "generic RCD default maps to directional X-Trans";
    auto preview = defaults;
    preview.method = raw::Demosaic::XTransFast;
    const auto fast_result = raw::develop(image, preview, error);
    ASSERT_TRUE(fast_result) << error;
    EXPECT_FALSE(samePixels(*default_result, *fast_result)) << "fast preview remains a distinct algorithm";
}

TEST(RawXTransDispatch, DirectionalModeRejectsBayerButEdgeDirectedBayerModeIsAccepted) {
    const auto pattern = raw::CfaPattern::bayer("RGGB");
    std::vector<std::uint16_t> samples(32U * 28U, 32000);
    raw::CfaImage image{32, 28, samples, pattern};
    raw::DevelopSpec spec;
    spec.method = raw::Demosaic::XTransDirectional;
    std::string error;
    EXPECT_FALSE(raw::develop(image, spec, error));
    EXPECT_FALSE(error.empty());
    spec.method = raw::Demosaic::EdgeDirected;
    EXPECT_TRUE(raw::develop(image, spec, error)) << error;
    const auto xtrans_pattern = raw::CfaPattern::xtrans();
    auto xtrans_samples = makeXtransSamples(xtrans_pattern, 32, 28);
    raw::CfaImage xtrans_image{32, 28, xtrans_samples, xtrans_pattern};
    EXPECT_FALSE(raw::develop(xtrans_image, spec, error));
    EXPECT_NE(error.find("Bayer pattern"), std::string::npos);
}

TEST(RawXTransDirectional, OutputRimExtendsCompleteCandidatesWithoutDarkChannels) {
    constexpr int width = 32;
    constexpr int height = 28;
    const auto pattern = raw::CfaPattern::xtrans();
    const auto n = static_cast<std::size_t>(width) * height;
    std::vector<float> cfa(n);
    std::vector<std::uint8_t> colours(n);
    std::vector<float> red(n, 0.0F), green(n, 0.0F), blue(n, 0.0F);
    for (int y = 0; y < height; ++y)
        for (int x = 0; x < width; ++x) {
            const auto i = static_cast<std::size_t>(y) * width + x;
            colours[i] = static_cast<std::uint8_t>(pattern.at(static_cast<std::uint32_t>(x), static_cast<std::uint32_t>(y)));
            const auto channel = colours[i];
            cfa[i] = channel == 0 ? 0.18F + 0.002F * x : channel == 1 ? 0.32F + 0.001F * y : 0.44F + 0.001F * (x + y);
        }

    croptop::raw::detail::xtransDirectional(cfa, colours, red, green, blue, width, height);
    for (int y = 0; y < height; ++y)
        for (int x = 0; x < width; ++x) {
            if (x >= 9 && x < width - 9 && y >= 9 && y < height - 9) continue;
            const auto i = static_cast<std::size_t>(y) * width + x;
            EXPECT_GT(red[i], 0.0F) << "red at " << x << ',' << y;
            EXPECT_GT(green[i], 0.0F) << "green at " << x << ',' << y;
            EXPECT_GT(blue[i], 0.0F) << "blue at " << x << ',' << y;
        }
}

TEST(RawXTransDirectional, HugeMismatchedDimensionsReturnBeforeAllocationOrMutation) {
    std::vector<float> output{7.0F};
    croptop::raw::detail::xtransDirectional({}, {}, output, output, output, std::numeric_limits<int>::max(),
                                             std::numeric_limits<int>::max());
    EXPECT_EQ(output, (std::vector<float>{7.0F}));
}

}  // namespace
