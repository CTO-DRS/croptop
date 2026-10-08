// Embedded files in .croptop archives (smart-object sources, RAW files and their settings).
#include "croptop_format.h"
#include "tile_store.h"

#include <gtest/gtest.h>

#include <algorithm>
#include <cstdint>
#include <cstring>
#include <string>
#include <vector>

namespace {

using croptop::CroptopArchive;
using croptop::CroptopEmbeddedFile;
using croptop::CroptopSaveInput;

std::vector<std::uint8_t> save(const CroptopSaveInput& input, std::string* error = nullptr) {
    std::vector<std::uint8_t> bytes;
    const auto result = croptop::saveCroptop(input, [&bytes](std::span<const std::uint8_t> chunk) {
        bytes.insert(bytes.end(), chunk.begin(), chunk.end());
        return true;
    });
    if (error != nullptr) *error = result.error;
    if (!result.success) bytes.clear();
    return bytes;
}

croptop::CroptopReadSource sourceOf(const std::vector<std::uint8_t>& bytes) {
    return {bytes.size(), [&bytes](std::uint64_t offset, std::span<std::uint8_t> out) {
                if (offset > bytes.size() || out.size() > bytes.size() - offset) return false;
                std::memcpy(out.data(), bytes.data() + offset, out.size());
                return true;
            }};
}

const std::string kManifest = R"({"formatVersion":1})";

CroptopSaveInput baseInput() {
    CroptopSaveInput input;
    input.manifest_json = {reinterpret_cast<const std::uint8_t*>(kManifest.data()), kManifest.size()};
    input.worker_threads = 1;
    return input;
}

TEST(CroptopEmbedded, FilesRoundTripByteForByte) {
    std::vector<std::uint8_t> raw(300000);
    for (std::size_t i = 0; i < raw.size(); ++i) raw[i] = static_cast<std::uint8_t>(i * 131 + (i >> 8));
    const std::string settings = R"({"exposure":0.5})";
    const std::vector<std::uint8_t> settings_bytes(settings.begin(), settings.end());
    auto input = baseInput();
    input.embedded = {{"so-1", "original.dng", raw}, {"so-1", "settings.json", settings_bytes}, {"so-2", "source.png", settings_bytes}};
    const auto bytes = save(input);
    ASSERT_FALSE(bytes.empty());

    std::string error;
    const auto archive = CroptopArchive::open(sourceOf(bytes), error);
    ASSERT_NE(archive, nullptr) << error;
    EXPECT_EQ(archive->embeddedFiles(), (std::vector<std::string>{"so-1/original.dng", "so-1/settings.json", "so-2/source.png"}));
    std::vector<std::uint8_t> out;
    ASSERT_TRUE(archive->readEmbedded("so-1", "original.dng", out, error)) << error;
    EXPECT_EQ(out, raw);
    ASSERT_TRUE(archive->readEmbedded("so-1", "settings.json", out, error)) << error;
    EXPECT_EQ(out, settings_bytes);
    ASSERT_TRUE(archive->readManifest(out, error)) << error;
    EXPECT_EQ(std::string(out.begin(), out.end()), kManifest);
}

TEST(CroptopEmbedded, ArchivesWithoutEmbeddedFilesStillOpenAndListNone) {
    const auto bytes = save(baseInput());
    ASSERT_FALSE(bytes.empty());
    std::string error;
    const auto archive = CroptopArchive::open(sourceOf(bytes), error);
    ASSERT_NE(archive, nullptr) << error;
    EXPECT_TRUE(archive->embeddedFiles().empty());
    std::vector<std::uint8_t> out;
    EXPECT_FALSE(archive->readEmbedded("so-1", "original.dng", out, error));
    EXPECT_NE(error.find("missing entry"), std::string::npos);
}

TEST(CroptopEmbedded, RejectsUnsafeDuplicateAndOversizedFilesBeforeWriting) {
    const std::vector<std::uint8_t> data(10, 1);
    std::string error;
    for (const auto& [id, name] : std::vector<std::pair<std::string, std::string>>{
             {"", "a.bin"}, {"so/1", "a.bin"}, {"so-1", ""}, {"so-1", ".."}, {"so-1", "a/b"}, {"so-1", "a b"}, {"so-1", std::string(65, 'x')}}) {
        SCOPED_TRACE(id + " | " + name);
        auto input = baseInput();
        input.embedded = {{id, name, data}};
        EXPECT_TRUE(save(input, &error).empty());
        EXPECT_NE(error.find("invalid embedded"), std::string::npos);
    }
    auto twice = baseInput();
    twice.embedded = {{"so-1", "a.bin", data}, {"so-1", "a.bin", data}};
    EXPECT_TRUE(save(twice, &error).empty());
    EXPECT_NE(error.find("duplicate embedded"), std::string::npos);
}

TEST(CroptopEmbedded, ReaderRefusesUnsafeNamesAndEnforcesItsSizeLimit) {
    const std::vector<std::uint8_t> data(1000, 3);
    auto input = baseInput();
    input.embedded = {{"so-1", "big.bin", data}};
    const auto bytes = save(input);
    ASSERT_FALSE(bytes.empty());
    std::string error;
    croptop::CroptopLimits small;
    small.max_embedded_bytes = 100;
    const auto archive = CroptopArchive::open(sourceOf(bytes), error, small);
    ASSERT_NE(archive, nullptr) << error;
    std::vector<std::uint8_t> out;
    EXPECT_FALSE(archive->readEmbedded("so-1", "big.bin", out, error));
    EXPECT_NE(error.find("size limit"), std::string::npos);
    EXPECT_FALSE(archive->readEmbedded("so-1", "../manifest.json", out, error));
    EXPECT_FALSE(archive->readEmbedded("../x", "big.bin", out, error));
}

TEST(CroptopEmbedded, EmbeddedFilesCountTowardsTheEntryLimitAndLayersStillLoad) {
    croptop::TileStore layer(croptop::PixelFormat::RGBA8, 4, 3);
    croptop::TileBuffer tile(layer.tile_byte_size(), 9);
    ASSERT_TRUE(layer.writeTile({0, 0}, tile));
    const std::vector<std::uint8_t> data(10, 1);
    auto input = baseInput();
    input.layers = {{"layer-a", &layer}};
    input.embedded = {{"so-1", "a.bin", data}};
    input.max_entries = 4;  // mimetype + manifest + layer info + embedded file = 4, so no tile fits
    std::string error;
    EXPECT_TRUE(save(input, &error).empty());
    input.max_entries = 100;
    const auto bytes = save(input, &error);
    ASSERT_FALSE(bytes.empty()) << error;
    const auto archive = CroptopArchive::open(sourceOf(bytes), error);
    ASSERT_NE(archive, nullptr) << error;
    croptop::CroptopLayerInfo info;
    ASSERT_TRUE(archive->layerInfo("layer-a", info, error)) << error;
    croptop::TileStore loaded(info.format, info.width, info.height);
    ASSERT_TRUE(archive->loadLayer("layer-a", loaded, error)) << error;
    EXPECT_EQ(*loaded.readTile({0, 0}), tile);
}

}  // namespace
