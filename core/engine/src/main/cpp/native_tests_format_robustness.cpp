// Supplemental bounded-input and persistent-identity checks for the native format API.
#include "croptop_format.h"
#include "pixel_format_conversion.h"
#include "tile_store.h"

#include <gtest/gtest.h>

#include <algorithm>
#include <array>
#include <cstdint>
#include <cstring>
#include <string>
#include <vector>

namespace {

using croptop::PixelFormat;
using croptop::TileStore;

std::vector<std::uint8_t> saveArchive(const croptop::CroptopSaveInput& input) {
    std::vector<std::uint8_t> bytes;
    const auto result = croptop::saveCroptop(input, [&bytes](std::span<const std::uint8_t> chunk) {
        bytes.insert(bytes.end(), chunk.begin(), chunk.end());
        return true;
    });
    EXPECT_TRUE(result.success) << result.error;
    return bytes;
}

croptop::CroptopReadSource memorySource(const std::vector<std::uint8_t>& bytes, std::uint64_t length = UINT64_MAX) {
    const auto bounded_length = std::min<std::uint64_t>(bytes.size(), length);
    return {bounded_length, [&bytes, bounded_length](std::uint64_t offset, std::span<std::uint8_t> output) {
                if (offset > bounded_length || output.size() > bounded_length - offset) return false;
                std::copy_n(bytes.data() + static_cast<std::size_t>(offset), output.size(), output.data());
                return true;
            }};
}

TileStore rgbaStore(std::uint8_t red, std::uint8_t green, std::uint8_t blue, std::uint8_t alpha) {
    TileStore store(PixelFormat::RGBA8, 4, 3);
    croptop::TileBuffer tile(store.tile_byte_size(), 0);
    for (std::size_t i = 0; i < 12; ++i) {
        tile[i * 4U] = red;
        tile[i * 4U + 1U] = green;
        tile[i * 4U + 2U] = blue;
        tile[i * 4U + 3U] = alpha;
    }
    EXPECT_TRUE(store.writeTile({0, 0}, tile));
    return store;
}

croptop::CroptopSaveInput baseInput(const std::string& manifest, const TileStore& first, const TileStore& second) {
    croptop::CroptopSaveInput input;
    input.manifest_json = {reinterpret_cast<const std::uint8_t*>(manifest.data()), manifest.size()};
    input.layers = {{"z-layer", &first}, {"a-mask", &second}};
    input.worker_threads = 1;
    return input;
}

TEST(CroptopFormatRobustness, RejectsTruncatedAndMalformedContainers) {
    const auto pixels = rgbaStore(20, 40, 60, 255);
    TileStore mask(PixelFormat::A16, 4, 3);
    croptop::CroptopSaveInput input;
    const std::string manifest = R"({"formatVersion":1})";
    input.manifest_json = {reinterpret_cast<const std::uint8_t*>(manifest.data()), manifest.size()};
    input.layers = {{"pixels", &pixels}, {"mask", &mask}};
    const auto bytes = saveArchive(input);
    ASSERT_GT(bytes.size(), 32U);

    std::string error;
    for (const auto truncated_size : std::array<std::size_t, 6>{0U, 1U, 4U, 16U, bytes.size() / 2U, bytes.size() - 1U}) {
        SCOPED_TRACE(truncated_size);
        EXPECT_EQ(croptop::CroptopArchive::open(memorySource(bytes, truncated_size), error), nullptr);
    }

    auto corrupt = bytes;
    corrupt[0] ^= 0xffU;
    EXPECT_EQ(croptop::CroptopArchive::open(memorySource(corrupt), error), nullptr);
    EXPECT_FALSE(error.empty());
}

TEST(CroptopFormatRobustness, EnforcesReaderEntryManifestDirectoryAndDimensionLimits) {
    const auto pixels = rgbaStore(20, 40, 60, 255);
    TileStore mask(PixelFormat::A16, 4, 3);
    const std::string manifest = R"({"formatVersion":1,"name":"bounded"})";
    const auto bytes = saveArchive(baseInput(manifest, pixels, mask));
    ASSERT_FALSE(bytes.empty());

    std::string error;
    croptop::CroptopLimits limits;
    limits.max_entries = 2;
    EXPECT_EQ(croptop::CroptopArchive::open(memorySource(bytes), error, limits), nullptr);

    limits = {};
    limits.max_central_directory_bytes = 1;
    EXPECT_EQ(croptop::CroptopArchive::open(memorySource(bytes), error, limits), nullptr);

    limits = {};
    limits.max_manifest_bytes = 8;
    const auto manifest_limited = croptop::CroptopArchive::open(memorySource(bytes), error, limits);
    ASSERT_NE(manifest_limited, nullptr) << error;
    std::vector<std::uint8_t> decoded_manifest;
    EXPECT_FALSE(manifest_limited->readManifest(decoded_manifest, error));
    EXPECT_FALSE(error.empty());

    limits = {};
    limits.max_dimension = 2;
    const auto dimension_limited = croptop::CroptopArchive::open(memorySource(bytes), error, limits);
    ASSERT_NE(dimension_limited, nullptr) << error;
    croptop::CroptopLayerInfo info;
    EXPECT_FALSE(dimension_limited->layerInfo("z-layer", info, error));
    EXPECT_FALSE(error.empty());
}

TEST(CroptopFormatRobustness, PreservesLayerTreeManifestAndStablePixelUuids) {
    const auto pixels = rgbaStore(13, 91, 177, 203);
    TileStore mask(PixelFormat::A16, 4, 3);
    const std::string manifest =
        R"({"formatVersion":1,"documentId":"doc-stable","layers":[{"id":"group-root","kind":"GROUP",)"
        R"("children":[{"id":"z-layer","kind":"PIXEL","visible":true},{"id":"a-mask","kind":"PIXEL",)"
        R"("visible":false}]}],"future":{"preserve":[1,2,3]}})";
    const auto bytes = saveArchive(baseInput(manifest, pixels, mask));
    ASSERT_FALSE(bytes.empty());

    std::string error;
    const auto archive = croptop::CroptopArchive::open(memorySource(bytes), error);
    ASSERT_NE(archive, nullptr) << error;
    std::vector<std::uint8_t> manifest_out;
    ASSERT_TRUE(archive->readManifest(manifest_out, error)) << error;
    EXPECT_EQ(std::string(manifest_out.begin(), manifest_out.end()), manifest);
    EXPECT_EQ(archive->layerUuids(), (std::vector<std::string>{"a-mask", "z-layer"}));

    croptop::CroptopLayerInfo info;
    ASSERT_TRUE(archive->layerInfo("z-layer", info, error)) << error;
    TileStore loaded(info.format, info.width, info.height);
    ASSERT_TRUE(archive->loadLayer("z-layer", loaded, error)) << error;
    const auto tile = loaded.readTile({0, 0});
    ASSERT_NE(tile, nullptr);
    for (std::size_t i = 0; i < 12; ++i) {
        EXPECT_EQ((*tile)[i * 4U], 13U);
        EXPECT_EQ((*tile)[i * 4U + 1U], 91U);
        EXPECT_EQ((*tile)[i * 4U + 2U], 177U);
        EXPECT_EQ((*tile)[i * 4U + 3U], 203U);
    }
    ASSERT_TRUE(archive->layerInfo("a-mask", info, error)) << error;
    EXPECT_EQ(info.format, PixelFormat::A16);
}

TEST(CroptopFormatRobustness, PromotesLegacyAlphaOnlyPixelLayerToWhiteRgba16Exactly) {
    TileStore alpha(PixelFormat::A16, 2, 1);
    croptop::TileBuffer tile(alpha.tile_byte_size(), 0U);
    const std::uint16_t values[] = {0U, 42'731U};
    std::memcpy(tile.data(), values, sizeof(values));
    ASSERT_TRUE(alpha.writeTile({0, 0}, tile));

    std::string error;
    auto promoted = croptop::promotePixelStore(alpha, PixelFormat::RGBA16, error);
    ASSERT_TRUE(promoted.has_value()) << error;
    const auto output = promoted->readTile({0, 0});
    ASSERT_NE(output, nullptr);
    std::uint16_t rgba[4]{};
    std::memcpy(rgba, output->data(), sizeof(rgba));
    EXPECT_EQ(rgba[0], 65535U);
    EXPECT_EQ(rgba[1], 65535U);
    EXPECT_EQ(rgba[2], 65535U);
    EXPECT_EQ(rgba[3], values[0]);
    std::memcpy(rgba, output->data() + 4U * sizeof(std::uint16_t), sizeof(rgba));
    EXPECT_EQ(rgba[0], 65535U);
    EXPECT_EQ(rgba[1], 65535U);
    EXPECT_EQ(rgba[2], 65535U);
    EXPECT_EQ(rgba[3], values[1]);
}

TEST(CroptopFormatRobustness, PromotesRgba8ToRgba16WithoutChangingNormalizedChannels) {
    auto source = rgbaStore(13U, 91U, 177U, 203U);
    std::string error;
    auto promoted = croptop::promotePixelStore(source, PixelFormat::RGBA16, error);
    ASSERT_TRUE(promoted.has_value()) << error;
    const auto output = promoted->readTile({0, 0});
    ASSERT_NE(output, nullptr);
    std::uint16_t rgba[4]{};
    std::memcpy(rgba, output->data(), sizeof(rgba));
    EXPECT_EQ(rgba[0], 13U * 257U);
    EXPECT_EQ(rgba[1], 91U * 257U);
    EXPECT_EQ(rgba[2], 177U * 257U);
    EXPECT_EQ(rgba[3], 203U * 257U);
}

}  // namespace
