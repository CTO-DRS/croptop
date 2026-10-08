// Tests for colour management.
#include "color_management.h"
#include "scratch_store.h"

#include <gtest/gtest.h>
#include <lcms2.h>

#include <chrono>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <functional>
#include <filesystem>
#include <memory>
#include <string>

namespace {

using croptop::PixelFormat;
using croptop::TileStore;
namespace cms = croptop::cms;
using Rgba = std::array<std::uint8_t, 4>;

struct TransformDeleter {
    void operator()(void* t) const {
        if (t != nullptr) cmsDeleteTransform(static_cast<cmsHTRANSFORM>(t));
    }
};
using TransformHandle = std::unique_ptr<void, TransformDeleter>;

cmsInt32Number sampleBlack(const cmsUInt16Number[], cmsUInt16Number out[], void*) {
    out[0] = 0;
    out[1] = 0;
    out[2] = 0;
    return 1;
}

std::vector<std::uint8_t> matrixProfileWithPerceptualLut() {
    const cmsCIExyY white{0.3127, 0.3290, 1.0};
    const cmsCIExyYTRIPLE primaries{{0.640, 0.330, 1.0}, {0.300, 0.600, 1.0}, {0.150, 0.060, 1.0}};
    cmsToneCurve* curve = cmsBuildGamma(nullptr, 2.2);
    if (curve == nullptr) return {};
    cmsToneCurve* curves[3]{curve, curve, curve};
    cmsHPROFILE profile = cmsCreateRGBProfile(&white, &primaries, curves);
    cmsFreeToneCurve(curve);
    if (profile == nullptr) return {};

    cmsPipeline* pipeline = cmsPipelineAlloc(nullptr, 3, 3);
    cmsStage* clut = cmsStageAllocCLut16bit(nullptr, 2, 3, 3, nullptr);
    bool ready = pipeline != nullptr && clut != nullptr;
    if (ready) ready = cmsStageSampleCLut16bit(clut, sampleBlack, nullptr, 0) != 0;
    if (ready) ready = cmsPipelineInsertStage(pipeline, cmsAT_END, cmsStageAllocToneCurves(nullptr, 3, nullptr)) != 0;
    if (ready) ready = cmsPipelineInsertStage(pipeline, cmsAT_END, clut) != 0;
    else if (clut != nullptr) cmsStageFree(clut);
    if (ready) ready = cmsPipelineInsertStage(pipeline, cmsAT_END, cmsStageAllocToneCurves(nullptr, 3, nullptr)) != 0;
    if (ready) ready = cmsWriteTag(profile, cmsSigAToB0Tag, pipeline) != 0;
    if (ready) ready = cmsWriteTag(profile, cmsSigAToB1Tag, pipeline) != 0;
    if (pipeline != nullptr) cmsPipelineFree(pipeline);

    cmsUInt32Number size = 0;
    if (!ready || !cmsSaveProfileToMem(profile, nullptr, &size) || size < 132U) {
        cmsCloseProfile(profile);
        return {};
    }
    std::vector<std::uint8_t> bytes(size);
    if (!cmsSaveProfileToMem(profile, bytes.data(), &size)) bytes.clear();
    else bytes.resize(size);
    cmsCloseProfile(profile);
    return bytes;
}

TileStore paint(std::uint32_t w, std::uint32_t h, const std::function<Rgba(std::uint32_t, std::uint32_t)>& f) {
    TileStore s(PixelFormat::RGBA8, w, h);
    for (std::uint32_t ty = 0; ty * 256 < h; ++ty)
        for (std::uint32_t tx = 0; tx * 256 < w; ++tx) {
            croptop::TileBuffer t(s.tile_byte_size(), 0);
            for (std::uint32_t y = 0; y < 256; ++y)
                for (std::uint32_t x = 0; x < 256; ++x) {
                    const auto gx = tx * 256 + x, gy = ty * 256 + y;
                    if (gx >= w || gy >= h) continue;
                    const auto c = f(gx, gy);
                    std::memcpy(t.data() + (static_cast<std::size_t>(y) * 256 + x) * 4, c.data(), 4);
                }
            EXPECT_TRUE(s.writeTile({static_cast<std::int32_t>(tx), static_cast<std::int32_t>(ty)}, t));
        }
    return s;
}

template <typename T>
std::array<T, 4> pixel(const TileStore& s, std::uint32_t x, std::uint32_t y) {
    std::array<T, 4> c{};
    const auto t = s.readTile({static_cast<std::int32_t>(x / 256), static_cast<std::int32_t>(y / 256)});
    if (t) std::memcpy(c.data(), t->data() + (static_cast<std::size_t>(y % 256) * 256 + x % 256) * sizeof(T) * 4, sizeof(T) * 4);
    return c;
}

std::uint16_t coverage(const TileStore& m, std::uint32_t x, std::uint32_t y) {
    std::uint16_t v = 0;
    const auto t = m.readTile({static_cast<std::int32_t>(x / 256), static_cast<std::int32_t>(y / 256)});
    if (t) std::memcpy(&v, t->data() + (static_cast<std::size_t>(y % 256) * 256 + x % 256) * 2, 2);
    return v;
}

std::array<float, 3> convertOne(std::array<float, 3> rgb, cms::WorkingSpace from, cms::WorkingSpace to) {
    TileStore s(PixelFormat::RGBA32F, 1, 1);
    croptop::TileBuffer t(s.tile_byte_size(), 0);
    const float px[4] = {rgb[0], rgb[1], rgb[2], 1.0F};
    std::memcpy(t.data(), px, sizeof(px));
    EXPECT_TRUE(s.writeTile({0, 0}, t));
    std::string error;
    const auto out = cms::convert(s, cms::Profile::builtin(from), cms::Profile::builtin(to), PixelFormat::RGBA32F, {}, error);
    EXPECT_TRUE(out) << error;
    const auto p = pixel<float>(*out, 0, 0);
    return {p[0], p[1], p[2]};
}

TEST(ColorManagement, BuiltinSpacesMatchReferenceValues) {
    // sRGB red in Display P3 (reference: Apple ColorSync / CSS Color 4 conversion).
    const auto p3 = convertOne({1, 0, 0}, cms::WorkingSpace::SRGB, cms::WorkingSpace::DisplayP3);
    EXPECT_NEAR(p3[0], 0.9175F, 0.003F);
    EXPECT_NEAR(p3[1], 0.2003F, 0.003F);
    EXPECT_NEAR(p3[2], 0.1386F, 0.003F);
    // sRGB mid grey (128) in linear sRGB.
    const auto lin = convertOne({128.0F / 255.0F, 128.0F / 255.0F, 128.0F / 255.0F}, cms::WorkingSpace::SRGB, cms::WorkingSpace::LinearSRGB);
    EXPECT_NEAR(lin[0], 0.21586F, 0.001F);
    // Float conversions are unbounded: P3 red lies outside sRGB, so linear sRGB goes negative in G/B.
    const auto wide = convertOne({1, 0, 0}, cms::WorkingSpace::DisplayP3, cms::WorkingSpace::LinearSRGB);
    EXPECT_GT(wide[0], 1.05F);
    EXPECT_LT(wide[1], -0.01F);
    // ProPhoto ↔ Adobe RGB ↔ Rec.2020 round trip returns the colour.
    const auto there = convertOne({0.3F, 0.6F, 0.2F}, cms::WorkingSpace::SRGB, cms::WorkingSpace::ProPhoto);
    const auto mid = convertOne(there, cms::WorkingSpace::ProPhoto, cms::WorkingSpace::Rec2020);
    const auto adobe = convertOne(mid, cms::WorkingSpace::Rec2020, cms::WorkingSpace::AdobeRGB);
    const auto back = convertOne(adobe, cms::WorkingSpace::AdobeRGB, cms::WorkingSpace::SRGB);
    EXPECT_NEAR(back[0], 0.3F, 0.002F);
    EXPECT_NEAR(back[1], 0.6F, 0.002F);
    EXPECT_NEAR(back[2], 0.2F, 0.002F);
    EXPECT_EQ(cms::Profile::builtin(cms::WorkingSpace::DisplayP3).description(), "Display P3");
    EXPECT_EQ(cms::Profile::builtin(cms::WorkingSpace::ProPhoto).model(), cms::ColorModel::RGB);
}

TEST(ColorManagement, MatrixShaperFastPathRespectsIntentTablesAndBlackPointOptions) {
    const auto bytes = matrixProfileWithPerceptualLut();
    ASSERT_GT(bytes.size(), 132U);
    std::string error;
    const auto document = cms::Profile::fromIcc(bytes, error);
    ASSERT_TRUE(document) << error;
    ASSERT_EQ(document->model(), cms::ColorModel::RGB);
    ASSERT_TRUE(cmsIsMatrixShaper(static_cast<cmsHPROFILE>(document->handle())))
        << "fixture intentionally has both matrix/TRC tags and intent LUT tags";

    TileStore layer(PixelFormat::RGBA32F, 1, 1);
    croptop::TileBuffer tile(layer.tile_byte_size(), 0);
    const float source[4]{0.5F, 0.4F, 0.3F, 0.25F};
    std::memcpy(tile.data(), source, sizeof(source));
    ASSERT_TRUE(layer.writeTile({0, 0}, tile));
    cms::ConvertOptions perceptual;
    perceptual.intent = cms::Intent::Perceptual;
    perceptual.black_point_compensation = false;
    perceptual.worker_threads = 1;
    const auto output = cms::convert(layer, *document, cms::Profile::builtin(cms::WorkingSpace::SRGB), PixelFormat::RGBA32F,
                                     perceptual, error);
    ASSERT_TRUE(output) << error;
    const auto result = pixel<float>(*output, 0, 0);
    EXPECT_NEAR(result[0], 0.0F, 1e-4F);
    EXPECT_NEAR(result[1], 0.0F, 1e-4F);
    EXPECT_NEAR(result[2], 0.0F, 1e-4F);
    EXPECT_FLOAT_EQ(result[3], source[3]);

    // Even relative-colorimetric matrix conversion must leave LCMS in charge when BPC is requested.
    cms::ConvertOptions bpc;
    bpc.intent = cms::Intent::RelativeColorimetric;
    bpc.black_point_compensation = true;
    bpc.worker_threads = 1;
    const auto bpc_output = cms::convert(layer, *document, cms::Profile::builtin(cms::WorkingSpace::SRGB), PixelFormat::RGBA32F, bpc, error);
    ASSERT_TRUE(bpc_output) << error;
    const auto bpc_result = pixel<float>(*bpc_output, 0, 0);
    EXPECT_NEAR(bpc_result[0], 0.0F, 1e-4F);
    EXPECT_NEAR(bpc_result[1], 0.0F, 1e-4F);
    EXPECT_NEAR(bpc_result[2], 0.0F, 1e-4F);
    EXPECT_FLOAT_EQ(bpc_result[3], source[3]);
}

TEST(ColorManagement, FastPathMatchesLcmsForEveryIntentAndBpc) {
    // Built-in spaces carry no intent tables and have zero black points: every non-absolute intent, with
    // or without BPC, must give LCMS's answer (absolute colorimetric always runs through LCMS).
    TileStore layer(PixelFormat::RGBA32F, 4, 1);
    croptop::TileBuffer tile(layer.tile_byte_size(), 0);
    const float px[16] = {0.9F, 0.1F, 0.05F, 1, 0.02F, 0.03F, 0.04F, 1, 0.5F, 0.6F, 0.7F, 1, 0.0F, 0.0F, 0.0F, 1};
    std::memcpy(tile.data(), px, sizeof(px));
    ASSERT_TRUE(layer.writeTile({0, 0}, tile));
    const auto pro = cms::Profile::builtin(cms::WorkingSpace::ProPhoto);
    const auto srgb = cms::Profile::builtin(cms::WorkingSpace::SRGB);
    TransformHandle reference(cmsCreateTransform(pro.handle(), TYPE_RGBA_FLT, srgb.handle(), TYPE_RGBA_FLT, INTENT_RELATIVE_COLORIMETRIC,
                                                 cmsFLAGS_NOCACHE | cmsFLAGS_COPY_ALPHA | cmsFLAGS_NOOPTIMIZE));
    ASSERT_NE(reference.get(), nullptr);
    float expected[16];
    cmsDoTransform(reference.get(), px, expected, 4);
    std::string error;
    for (const auto intent : {cms::Intent::Perceptual, cms::Intent::RelativeColorimetric, cms::Intent::Saturation}) {
        for (const bool bpc : {false, true}) {
            cms::ConvertOptions options;
            options.intent = intent;
            options.black_point_compensation = bpc;
            const auto out = cms::convert(layer, pro, srgb, PixelFormat::RGBA32F, options, error);
            ASSERT_TRUE(out) << error;
            for (std::uint32_t x = 0; x < 4; ++x)
                for (int c = 0; c < 4; ++c) EXPECT_NEAR(pixel<float>(*out, x, 0)[c], expected[x * 4 + c], 2e-4F) << "x " << x << " c " << c;
        }
    }
}

TEST(ColorManagement, ConvertLayerKeepsAlphaEmptyTilesAndFormats) {
    const auto layer = paint(300, 40, [](std::uint32_t x, std::uint32_t) {
        return x < 150 ? Rgba{255, 0, 0, 77} : Rgba{200, 150, 100, 255};
    });
    std::string error;
    const auto srgb = cms::Profile::builtin(cms::WorkingSpace::SRGB);
    const auto pro = cms::Profile::builtin(cms::WorkingSpace::ProPhoto);
    const auto wide = cms::convert(layer, srgb, pro, PixelFormat::RGBA16, {}, error);
    ASSERT_TRUE(wide) << error;
    EXPECT_EQ(wide->format(), PixelFormat::RGBA16);
    EXPECT_EQ(pixel<std::uint16_t>(*wide, 10, 10)[3], 77 * 257) << "alpha is copied (and rescaled)";
    // Back to 8-bit sRGB: every channel within one code value.
    const auto back = cms::convert(*wide, pro, srgb, PixelFormat::RGBA8, {}, error);
    ASSERT_TRUE(back) << error;
    for (const std::uint32_t x : {10U, 200U}) {
        const auto a = pixel<std::uint8_t>(layer, x, 5), b = pixel<std::uint8_t>(*back, x, 5);
        for (int c = 0; c < 4; ++c) EXPECT_NEAR(a[c], b[c], 1) << "x " << x << " channel " << c;
    }
    // Adobe RGB pure green is outside sRGB: relative colorimetric clips it to the sRGB boundary.
    const auto green = paint(4, 4, [](std::uint32_t, std::uint32_t) { return Rgba{0, 255, 0, 255}; });
    const auto clipped = cms::convert(green, cms::Profile::builtin(cms::WorkingSpace::AdobeRGB), srgb, PixelFormat::RGBA8, {}, error);
    ASSERT_TRUE(clipped) << error;
    EXPECT_EQ(pixel<std::uint8_t>(*clipped, 1, 1)[1], 255);
    EXPECT_EQ(pixel<std::uint8_t>(*clipped, 1, 1)[0], 0);

    // A sparse layer stays sparse; multi-tile work runs in parallel (TSan covers this).
    TileStore sparse(PixelFormat::RGBA8, 1000, 1000);
    ASSERT_TRUE(sparse.writeTile({2, 2}, croptop::TileBuffer(sparse.tile_byte_size(), 200)));
    ASSERT_TRUE(sparse.writeTile({0, 1}, croptop::TileBuffer(sparse.tile_byte_size(), 90)));
    cms::ConvertOptions options;
    options.worker_threads = 4;
    const auto p3 = cms::convert(sparse, srgb, cms::Profile::builtin(cms::WorkingSpace::DisplayP3), PixelFormat::RGBA8, options, error);
    ASSERT_TRUE(p3) << error;
    EXPECT_EQ(p3->tile_count(), 2U);

    EXPECT_FALSE(cms::convert(layer, srgb, cms::Profile::genericCmyk(), PixelFormat::RGBA8, {}, error));
    EXPECT_FALSE(cms::convert(TileStore(PixelFormat::A16, 4, 4), srgb, pro, PixelFormat::RGBA8, {}, error));
}

TEST(ColorManagement, ConvertedOutputInheritsSourcePoolAndMemoryBudget) {
    const auto srgb = cms::Profile::builtin(cms::WorkingSpace::SRGB);
    const auto p3 = cms::Profile::builtin(cms::WorkingSpace::DisplayP3);
    std::string error;

    auto pool = std::make_shared<croptop::TilePool>();
    TileStore pooled_source(PixelFormat::RGBA8, 256, 256, pool);
    croptop::TileBuffer source_tile(pooled_source.tile_byte_size(), 0);
    std::fill(source_tile.begin(), source_tile.end(), 127U);
    ASSERT_TRUE(pooled_source.writeTile({0, 0}, source_tile));
    const auto source_pool_bytes = pool->active_bytes();
    ASSERT_GT(source_pool_bytes, 0U);
    auto pooled_output = cms::convert(pooled_source, srgb, p3, PixelFormat::RGBA16, {}, error);
    ASSERT_TRUE(pooled_output) << error;
    EXPECT_EQ(pool->active_bytes(), source_pool_bytes + pooled_output->tile_byte_size())
        << "derived output storage should acquire tiles from the source TilePool";

    const auto scratch_path = std::filesystem::temp_directory_path() /
                              ("croptop-color-budget-" + std::to_string(std::chrono::steady_clock::now().time_since_epoch().count()));
    auto scratch = std::make_shared<croptop::DiskScratchStore>(scratch_path);
    auto budget = std::make_shared<croptop::MemoryBudgetManager>(source_tile.size(), scratch);
    TileStore managed_source(PixelFormat::RGBA8, 256, 256, std::make_shared<croptop::TilePool>(), budget);
    ASSERT_TRUE(managed_source.writeTile({0, 0}, source_tile));
    auto managed_output = cms::convert(managed_source, srgb, p3, PixelFormat::RGBA16, {}, error);
    ASSERT_TRUE(managed_output) << error;
    EXPECT_EQ(managed_output->memoryBudgetManager(), budget)
        << "derived output must share the document's spill/restore manager";
    EXPECT_EQ(managed_source.memoryBudgetManager(), managed_output->memoryBudgetManager());
    EXPECT_EQ(managed_output->tile_count(), 1U);

    // Many-tile conversion under a two-output-tile budget forces source and output data to spill as
    // batches advance. Only the final tile set stays in the output store; no image-sized transient set
    // is retained by cms::convert.
    auto stress_budget = std::make_shared<croptop::MemoryBudgetManager>(2U * 256U * 256U * 8U, scratch);
    TileStore tiled_source(PixelFormat::RGBA8, 256U * 16U, 256U, std::make_shared<croptop::TilePool>(), stress_budget);
    for (std::int32_t x = 0; x < 16; ++x) {
        croptop::TileBuffer tile(tiled_source.tile_byte_size(), static_cast<std::uint8_t>(x + 1));
        ASSERT_TRUE(tiled_source.writeTile({x, 0}, tile));
    }
    cms::ConvertOptions bounded_options;
    bounded_options.worker_threads = 2;
    auto tiled_output = cms::convert(tiled_source, srgb, p3, PixelFormat::RGBA16, bounded_options, error);
    ASSERT_TRUE(tiled_output) << error;
    EXPECT_EQ(tiled_output->memoryBudgetManager(), stress_budget);
    EXPECT_EQ(tiled_output->tile_count(), 16U);
    EXPECT_LE(stress_budget->residentBytes(), stress_budget->budgetBytes());
    std::filesystem::remove_all(scratch_path);
}

TEST(ColorManagement, IccBytesRoundTripAndMalformedInput) {
    std::string error;
    const auto p3 = cms::Profile::builtin(cms::WorkingSpace::DisplayP3);
    const auto bytes = p3.iccBytes();
    ASSERT_GT(bytes.size(), 128U);
    EXPECT_EQ(std::memcmp(bytes.data() + 36, "acsp", 4), 0) << "ICC signature";
    const auto parsed = cms::Profile::fromIcc(bytes, error);
    ASSERT_TRUE(parsed) << error;
    EXPECT_EQ(parsed->description(), "Display P3");
    // The parsed profile converts exactly like the built-in one.
    const auto layer = paint(8, 8, [](std::uint32_t x, std::uint32_t y) {
        return Rgba{static_cast<std::uint8_t>(x * 30), static_cast<std::uint8_t>(y * 30), 90, 255};
    });
    const auto srgb = cms::Profile::builtin(cms::WorkingSpace::SRGB);
    const auto a = cms::convert(layer, srgb, p3, PixelFormat::RGBA16, {}, error);
    const auto b = cms::convert(layer, srgb, *parsed, PixelFormat::RGBA16, {}, error);
    ASSERT_TRUE(a && b) << error;
    for (int c = 0; c < 4; ++c) EXPECT_NEAR(pixel<std::uint16_t>(*a, 7, 5)[c], pixel<std::uint16_t>(*b, 7, 5)[c], 2);

    std::vector<std::uint8_t> garbage(500, 0x5A);
    EXPECT_FALSE(cms::Profile::fromIcc(garbage, error));
    EXPECT_FALSE(error.empty());
    EXPECT_FALSE(cms::Profile::fromIcc(std::span(bytes.data(), 100), error));
    auto damaged = bytes;
    for (std::size_t i = 132; i < damaged.size(); ++i) damaged[i] ^= 0xA5;  // tag table and data
    EXPECT_FALSE(cms::Profile::fromIcc(damaged, error));

    // Mutated profiles either parse into something usable or are rejected; never crash (ASan).
    std::uint32_t seed = 12345;
    for (int round = 0; round < 300; ++round) {
        auto mutated = bytes;
        for (int k = 0; k < 1 + round % 8; ++k) {
            seed = seed * 1664525U + 1013904223U;
            mutated[seed % mutated.size()] = static_cast<std::uint8_t>(seed >> 24);
        }
        const auto maybe = cms::Profile::fromIcc(mutated, error);
        if (maybe && maybe->model() == cms::ColorModel::RGB) (void)cms::convert(layer, *maybe, srgb, PixelFormat::RGBA8, {}, error);
    }
}

TEST(ColorManagement, DisplayLutAndSoftProof) {
    std::string error;
    const auto srgb = cms::Profile::builtin(cms::WorkingSpace::SRGB);
    const auto p3 = cms::Profile::builtin(cms::WorkingSpace::DisplayP3);
    const auto identity = cms::bakeDisplayLut(srgb, srgb, 17, {}, error);
    ASSERT_TRUE(identity) << error;
    EXPECT_EQ(identity->table_3d.size(), 17U * 17U * 17U * 3U);
    const auto grey = identity->apply({0.37F, 0.37F, 0.37F});
    EXPECT_NEAR(grey[0], 0.37F, 0.003F);
    // sRGB document on a Display P3 panel: sRGB red lands inside P3 (no clipping on wide-colour displays).
    const auto wide = cms::bakeDisplayLut(srgb, p3, 17, {}, error);
    ASSERT_TRUE(wide) << error;
    const auto red = wide->apply({1, 0, 0});
    EXPECT_NEAR(red[0], 0.9175F, 0.003F);
    EXPECT_NEAR(red[1], 0.2003F, 0.003F);
    // P3 document on an sRGB panel keeps in-gamut colours (a P3 image on a P3 display is the identity).
    const auto p3_on_p3 = cms::bakeDisplayLut(p3, p3, 17, {}, error);
    ASSERT_TRUE(p3_on_p3) << error;
    EXPECT_NEAR(p3_on_p3->apply({1, 0, 0})[0], 1.0F, 0.002F);
    EXPECT_NEAR(p3_on_p3->apply({1, 0, 0})[1], 0.0F, 0.002F);

    // Soft proof through the generic CMYK press: skin tones survive, saturated blue gets the warning grey.
    const auto press = cms::Profile::genericCmyk();
    ASSERT_EQ(press.model(), cms::ColorModel::CMYK);
    cms::DisplayOptions proof;
    proof.proof = &press;
    const auto proofed = cms::bakeDisplayLut(srgb, srgb, 33, proof, error);
    ASSERT_TRUE(proofed) << error;
    const auto skin = proofed->apply({0.85F, 0.65F, 0.55F});
    EXPECT_NEAR(skin[0], 0.85F, 0.04F);
    EXPECT_NEAR(skin[1], 0.65F, 0.04F);
    EXPECT_NEAR(skin[2], 0.55F, 0.04F);
    const auto blue = proofed->apply({0, 0, 1});
    EXPECT_GT(blue[0] + blue[1], 0.15F) << "press blue is duller than sRGB blue";
    proof.gamut_warning = true;
    const auto warned = cms::bakeDisplayLut(srgb, srgb, 33, proof, error);
    ASSERT_TRUE(warned) << error;
    const auto alarm = warned->apply({0, 0, 1});
    EXPECT_NEAR(alarm[0], 0.5F, 0.01F);
    EXPECT_NEAR(alarm[2], 0.5F, 0.01F);
    EXPECT_NEAR(warned->apply({0.85F, 0.65F, 0.55F})[0], 0.85F, 0.04F);

    EXPECT_FALSE(cms::bakeDisplayLut(srgb, srgb, 1, {}, error));
    EXPECT_FALSE(cms::bakeDisplayLut(srgb, press, 17, {}, error));

    cms::DisplayOptions invalid_warning;
    invalid_warning.proof = &press;
    invalid_warning.gamut_warning = true;
    invalid_warning.gamut_warning_rgb[1] = std::nanf("");
    EXPECT_FALSE(cms::bakeDisplayLut(srgb, srgb, 17, invalid_warning, error));
    EXPECT_NE(error.find("finite"), std::string::npos);
    const auto ordinary = cms::Profile::genericCmyk(300.0F).iccBytes();
    const auto invalid_limit = cms::Profile::genericCmyk(std::nanf("")).iccBytes();
    EXPECT_EQ(invalid_limit, ordinary) << "NaN ink limits fall back to the documented 300% limit";
}

TEST(ColorManagement, LutOnViewportFramesKeepsAlphaAndMatchesPerPixelApplication) {
    std::string error;
    const auto srgb = cms::Profile::builtin(cms::WorkingSpace::SRGB);
    const auto press = cms::Profile::genericCmyk();
    cms::DisplayOptions proof;
    proof.proof = &press;
    const auto lut = cms::bakeDisplayLut(srgb, srgb, 33, proof, error);
    ASSERT_TRUE(lut) << error;
    // Enough pixels to take the multi-threaded path, with runs of flat colour and noise in between.
    std::vector<std::uint32_t> frame(200000);
    std::uint32_t seed = 12345U;
    for (std::size_t i = 0; i < frame.size(); ++i) {
        seed = seed * 1664525U + 1013904223U;
        const std::uint32_t rgb = (i % 7 < 4) ? 0x0033AAEEU : (seed & 0x00FFFFFFU);
        frame[i] = ((i % 5 == 0 ? 0x80U : 0xFFU) << 24) | rgb;
    }
    auto expected = frame;
    cms::applyLutToArgb(expected.data(), expected.size(), *lut, 1);
    auto threaded = frame;
    cms::applyLutToArgb(threaded.data(), threaded.size(), *lut, 4);
    EXPECT_EQ(threaded, expected) << "the thread count does not change the result";
    for (const std::size_t i : {0U, 3U, 4U, 5U, 999U, 150000U}) {
        EXPECT_EQ(threaded[i] >> 24, frame[i] >> 24) << "alpha is untouched at " << i;
        const auto out = lut->apply({static_cast<float>((frame[i] >> 16) & 0xFF) / 255.0F, static_cast<float>((frame[i] >> 8) & 0xFF) / 255.0F,
                                     static_cast<float>(frame[i] & 0xFF) / 255.0F});
        EXPECT_NEAR(static_cast<int>((threaded[i] >> 16) & 0xFF), std::lround(out[0] * 255.0F), 1) << i;
        EXPECT_NEAR(static_cast<int>(threaded[i] & 0xFF), std::lround(out[2] * 255.0F), 1) << i;
    }
    EXPECT_NE(threaded, frame) << "the press simulation changes saturated colours";
    cms::applyLutToArgb(nullptr, 5, *lut);  // harmless
}

TEST(ColorManagement, GamutMaskAndReadouts) {
    std::string error;
    const auto srgb = cms::Profile::builtin(cms::WorkingSpace::SRGB);
    const auto press = cms::Profile::genericCmyk();
    const auto layer = paint(300, 20, [](std::uint32_t x, std::uint32_t) {
        if (x < 100) return Rgba{0, 0, 255, 255};       // out of press gamut
        if (x < 200) return Rgba{190, 150, 130, 255};   // in gamut
        return Rgba{0, 0, 255, 0};                      // out of gamut but fully transparent
    });
    const auto mask = cms::gamutMask(layer, srgb, press, cms::Intent::RelativeColorimetric, error, 3);
    ASSERT_TRUE(mask) << error;
    EXPECT_EQ(mask->format(), PixelFormat::A16);
    EXPECT_EQ(coverage(*mask, 50, 10), 65535);
    EXPECT_EQ(coverage(*mask, 150, 10), 0);
    EXPECT_EQ(coverage(*mask, 250, 10), 0);

    const auto reader = cms::ColorReadout::create(srgb, press, error);
    ASSERT_TRUE(reader) << error;
    const auto white = reader->read({1, 1, 1});
    EXPECT_NEAR(white.lab[0], 100.0F, 0.2F);
    EXPECT_NEAR(white.lab[1], 0.0F, 0.2F);
    for (const float ink : white.cmyk) EXPECT_LT(ink, 3.0F);
    EXPECT_FALSE(white.out_of_gamut);
    const auto red = reader->read({1, 0, 0});
    EXPECT_NEAR(red.lab[0], 54.29F, 0.5F);  // sRGB red, D50 (ICC reference)
    EXPECT_NEAR(red.lab[1], 80.80F, 0.8F);
    EXPECT_NEAR(red.lab[2], 69.89F, 0.8F);
    const auto grey = reader->read({0.5F, 0.5F, 0.5F});
    EXPECT_FALSE(grey.out_of_gamut);
    EXPECT_GT(grey.cmyk[3], 5.0F) << "grey component replacement puts K in neutrals";
    EXPECT_FALSE(reader->read({0.2F, 0.2F, 0.2F}).out_of_gamut) << "dark neutrals are printable";
    const auto black = reader->read({0, 0, 0});
    EXPECT_GT(black.cmyk[3], 60.0F) << "rich black leans on K";
    EXPECT_LE(black.cmyk[0] + black.cmyk[1] + black.cmyk[2] + black.cmyk[3], 301.0F) << "total ink limit";
    const auto blue = reader->read({0, 0, 1});
    EXPECT_TRUE(blue.out_of_gamut);
    EXPECT_GT(blue.cmyk[0], 60.0F);
    EXPECT_GT(blue.cmyk[1], 60.0F);
    EXPECT_EQ(reader->read({0.5F, std::nanf(""), 0}).lab, reader->read({0.5F, 0, 0}).lab);
    const auto start = std::chrono::steady_clock::now();
    for (int i = 0; i < 1000; ++i) (void)reader->read({i / 1000.0F, 0.5F, 0.25F});
    const double us = std::chrono::duration<double, std::micro>(std::chrono::steady_clock::now() - start).count() / 1000.0;
    std::printf("[color] readout %.1f µs\n", us);
    EXPECT_FALSE(cms::ColorReadout::create(press, press, error));
}

#ifdef NDEBUG
TEST(ColorManagement, ConvertThroughput) {
    TileStore big(PixelFormat::RGBA16, 4096, 4096);
    for (std::int32_t ty = 0; ty < 16; ++ty)
        for (std::int32_t tx = 0; tx < 16; ++tx) ASSERT_TRUE(big.writeTile({tx, ty}, croptop::TileBuffer(big.tile_byte_size(), static_cast<std::uint8_t>(tx * 13 + ty))));
    std::string error;
    const auto start = std::chrono::steady_clock::now();
    const auto out = cms::convert(big, cms::Profile::builtin(cms::WorkingSpace::ProPhoto), cms::Profile::builtin(cms::WorkingSpace::SRGB),
                                  PixelFormat::RGBA16, {}, error);
    const double ms = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - start).count();
    ASSERT_TRUE(out) << error;
    std::printf("[color] 16 MP RGBA16 ProPhoto→sRGB: %.1f ms\n", ms);
}
#endif

}  // namespace
