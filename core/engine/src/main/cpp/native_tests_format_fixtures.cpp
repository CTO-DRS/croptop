// Frozen-format fixtures (acceptance: older-version files keep loading).
#include "croptop_format.h"
#include "tile_store.h"
#include "webp_codec.h"

#include <gtest/gtest.h>

#include <cstring>
#include <string>
#include <vector>

#include "croptop_v1_fixture.inc"

namespace {

using croptop::PixelFormat;
using croptop::TileStore;

croptop::CroptopReadSource fixtureSource() {
    return {sizeof(kCroptopV1Fixture), [](std::uint64_t offset, std::span<std::uint8_t> out) {
                if (offset > sizeof(kCroptopV1Fixture) || out.size() > sizeof(kCroptopV1Fixture) - offset) return false;
                std::memcpy(out.data(), kCroptopV1Fixture + offset, out.size());
                return true;
            }};
}

TEST(CroptopFixtures, VersionOneFileStillOpensWithIdenticalContent) {
    std::string error;
    const auto archive = croptop::CroptopArchive::open(fixtureSource(), error);
    ASSERT_NE(archive, nullptr) << error;

    std::vector<std::uint8_t> manifest;
    ASSERT_TRUE(archive->readManifest(manifest, error)) << error;
    EXPECT_EQ(std::string(manifest.begin(), manifest.end()),
              R"({"formatVersion":1,"appVersion":"fixture-1","name":"Fixture","canvas":{"width":260,"height":12},)"
              R"("layers":[{"uuid":"layer-rgba","name":"Pixels","kind":"PIXEL","pixelFormat":"RGBA8","blendMode":"NORMAL"},)"
              R"({"uuid":"layer-a16","name":"Mask","kind":"PIXEL","pixelFormat":"A16","blendMode":"NORMAL"}],"futureKey":{"keep":true}})");
    std::vector<std::uint8_t> icc;
    ASSERT_TRUE(archive->readWorkingIcc(icc, error));
    EXPECT_EQ(std::string(icc.begin(), icc.end()), "fake-icc");
    EXPECT_EQ(archive->layerUuids(), (std::vector<std::string>{"layer-a16", "layer-rgba"}));

    croptop::CroptopLayerInfo info;
    ASSERT_TRUE(archive->layerInfo("layer-rgba", info, error));
    EXPECT_EQ(info.format, PixelFormat::RGBA8);
    EXPECT_EQ(info.width, 260U);
    EXPECT_EQ(info.height, 12U);
    TileStore rgba(PixelFormat::RGBA8, 260, 12);
    ASSERT_TRUE(archive->loadLayer("layer-rgba", rgba, error)) << error;
    for (std::uint32_t y = 0; y < 12; ++y) {
        for (std::uint32_t x = 0; x < 260; ++x) {
            const auto tile = rgba.readTile({static_cast<std::int32_t>(x / 256), 0});
            ASSERT_NE(tile, nullptr);
            const auto* p = tile->data() + (static_cast<std::size_t>(y) * 256 + x % 256) * 4;
            ASSERT_EQ(p[0], static_cast<std::uint8_t>(x * 255 / 259));
            ASSERT_EQ(p[1], static_cast<std::uint8_t>(y * 20));
            ASSERT_EQ(p[2], 128);
            ASSERT_EQ(p[3], x < 40 ? 0 : 255);
        }
    }

    ASSERT_TRUE(archive->layerInfo("layer-a16", info, error));
    EXPECT_EQ(info.format, PixelFormat::A16);
    TileStore mask(PixelFormat::A16, info.width, info.height);
    ASSERT_TRUE(archive->loadLayer("layer-a16", mask, error)) << error;
    const auto tile = mask.readTile({0, 0});
    ASSERT_NE(tile, nullptr);
    for (std::uint32_t y = 0; y < 10; ++y) {
        for (std::uint32_t x = 0; x < 20; ++x) {
            std::uint16_t v = 0;
            std::memcpy(&v, tile->data() + (static_cast<std::size_t>(y) * 256 + x) * 2, 2);
            ASSERT_EQ(v, static_cast<std::uint16_t>((x * 700 + y * 311) & 0xffff));
        }
    }

    for (const auto* name : {"thumb.webp", "preview.webp"}) {
        std::vector<std::uint8_t> webp;
        ASSERT_TRUE(archive->readPreview(name, webp, error)) << name << ": " << error;
        std::uint32_t rows = 0;
        const auto decoded = croptop::decodeWebp(webp, [&](std::uint32_t, std::span<const std::uint8_t>) {
            ++rows;
            return true;
        });
        ASSERT_TRUE(decoded.success) << decoded.error;
        EXPECT_EQ(decoded.raster.width, 260U);
        EXPECT_EQ(rows, 12U);
    }
}

}  // namespace
