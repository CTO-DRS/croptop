// Tests for the RAW smart object, on the synthetic DNG (no camera files needed).
#include "raw_smart_object.h"
#include "native_tests_synthetic_dng.h"

#include <gtest/gtest.h>

#include <array>
#include <span>
#include <cstdint>
#include <cstring>
#include <memory>
#include <string>
#include <vector>

namespace {

namespace raw = croptop::raw;
namespace smart = croptop::smart;
using croptop::testing::kDngHeight;
using croptop::testing::kDngWidth;

std::shared_ptr<const std::vector<std::uint8_t>> dngFile() {
    return std::make_shared<const std::vector<std::uint8_t>>(croptop::testing::syntheticDng());
}

double greenAt(const croptop::TileStore& store, std::uint32_t x, std::uint32_t y) {
    const auto tile = store.readTile({static_cast<std::int32_t>(x / croptop::kTileEdge), static_cast<std::int32_t>(y / croptop::kTileEdge)});
    if (tile == nullptr) return 0.0;
    std::uint16_t g;
    std::memcpy(&g, tile->data() + ((static_cast<std::size_t>(y % croptop::kTileEdge) * croptop::kTileEdge) + x % croptop::kTileEdge) * 8 + 2, 2);
    return g / 65535.0;
}

TEST(RawSmartObject, RendersTheDevelopedRawAtDocumentSize) {
    std::string error;
    auto object = raw::RawSmartObject::create(dngFile(), {}, error);
    ASSERT_TRUE(object) << error;
    EXPECT_EQ(object->metadata().model, "Synthetic DNG");
    const auto same = object->render(kDngWidth, kDngHeight, {}, error);
    ASSERT_TRUE(same) << error;
    EXPECT_EQ(same->width(), kDngWidth);
    EXPECT_NEAR(greenAt(*same, 32, 24), 0.73, 0.15);
    // A larger document scales the placed source once: the middle is still the same gray.
    object->object().transforms.push_back({2, 0, 0, 0, 2, 0, 0, 0, 1});
    const auto doubled = object->render(kDngWidth * 2, kDngHeight * 2, {}, error);
    ASSERT_TRUE(doubled) << error;
    EXPECT_EQ(doubled->width(), kDngWidth * 2);
    EXPECT_NEAR(greenAt(*doubled, 64, 48), greenAt(*same, 32, 24), 0.02);
}

TEST(RawSmartObject, ChangingTheSettingsRedevelopsButKeepsTheEditableStack) {
    std::string error;
    auto object = raw::RawSmartObject::create(dngFile(), {}, error);
    ASSERT_TRUE(object) << error;
    object->object().transforms.push_back({1, 0, 8, 0, 1, 4, 0, 0, 1});
    smart::SmartFilter filter;
    filter.name = "marker";
    filter.apply = [](croptop::TileStore&, std::string&) { return true; };
    filter.opacity = 0.5F;
    object->object().filters.push_back(filter);
    const auto before = object->render(kDngWidth, kDngHeight, {}, error);
    ASSERT_TRUE(before) << error;

    raw::OpenOptions brighter;
    brighter.settings.basic.exposure = 1.0F;
    ASSERT_TRUE(object->setOptions(brighter, error)) << error;
    EXPECT_FLOAT_EQ(object->options().settings.basic.exposure, 1.0F);
    ASSERT_EQ(object->object().transforms.size(), 1U);
    ASSERT_EQ(object->object().filters.size(), 1U);
    EXPECT_EQ(object->object().filters[0].name, "marker");
    EXPECT_FLOAT_EQ(object->object().filters[0].opacity, 0.5F);

    const auto after = object->render(kDngWidth, kDngHeight, {}, error);
    ASSERT_TRUE(after) << error;
    EXPECT_GT(greenAt(*after, 40, 24), greenAt(*before, 40, 24) + 0.05) << "+1 EV should brighten the developed source";
}

TEST(RawSmartObject, AFailedRedevelopLeavesTheObjectUntouched) {
    std::string error;
    auto object = raw::RawSmartObject::create(dngFile(), {}, error);
    ASSERT_TRUE(object) << error;
    raw::OpenOptions cancelling;
    cancelling.progress = [](float) { return false; };
    EXPECT_FALSE(object->setOptions(cancelling, error));
    EXPECT_FALSE(error.empty());
    EXPECT_FLOAT_EQ(object->options().settings.basic.exposure, 0.0F);
    EXPECT_TRUE(object->render(kDngWidth, kDngHeight, {}, error)) << error;
}

TEST(RawSmartObject, SurvivesASaveAndReopenInsideACroptopArchive) {
    std::string error;
    raw::OpenOptions options;
    options.settings.basic.exposure = 0.5F;
    options.settings.basic.contrast = 10.0F;
    auto object = raw::RawSmartObject::create(dngFile(), options, error);
    ASSERT_TRUE(object) << error;

    const std::string manifest = R"({"formatVersion":1})";
    const std::string xmp = object->settingsXmp();
    croptop::CroptopSaveInput input;
    input.manifest_json = {reinterpret_cast<const std::uint8_t*>(manifest.data()), manifest.size()};
    input.embedded = object->toEmbedded("raw-1", xmp);
    input.worker_threads = 1;
    std::vector<std::uint8_t> bytes;
    const auto saved = croptop::saveCroptop(input, [&bytes](std::span<const std::uint8_t> chunk) {
        bytes.insert(bytes.end(), chunk.begin(), chunk.end());
        return true;
    });
    ASSERT_TRUE(saved.success) << saved.error;

    const croptop::CroptopReadSource source{bytes.size(), [&bytes](std::uint64_t offset, std::span<std::uint8_t> out) {
        if (offset > bytes.size() || out.size() > bytes.size() - offset) return false;
        std::memcpy(out.data(), bytes.data() + offset, out.size());
        return true;
    }};
    const auto archive = croptop::CroptopArchive::open(source, error);
    ASSERT_NE(archive, nullptr) << error;
    const auto reopened = raw::RawSmartObject::fromArchive(*archive, "raw-1", error);
    ASSERT_TRUE(reopened) << error;
    EXPECT_EQ(reopened->file(), object->file());
    EXPECT_NEAR(reopened->options().settings.basic.exposure, 0.5F, 1e-3F);
    EXPECT_NEAR(reopened->options().settings.basic.contrast, 10.0F, 1e-3F);
    const auto a = object->render(kDngWidth, kDngHeight, {}, error);
    const auto b = reopened->render(kDngWidth, kDngHeight, {}, error);
    ASSERT_TRUE(a && b) << error;
    EXPECT_NEAR(greenAt(*a, 32, 24), greenAt(*b, 32, 24), 0.002);

    EXPECT_FALSE(raw::RawSmartObject::fromArchive(*archive, "missing", error));
    EXPECT_NE(error.find("missing entry"), std::string::npos);
}

TEST(RawSmartObject, RejectsMissingOrUnreadableFiles) {
    std::string error;
    EXPECT_FALSE(raw::RawSmartObject::create(nullptr, {}, error));
    EXPECT_FALSE(raw::RawSmartObject::create(std::make_shared<const std::vector<std::uint8_t>>(std::vector<std::uint8_t>(512, 7)), {}, error));
    EXPECT_FALSE(error.empty());
}

}  // namespace
