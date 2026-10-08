// DevelopSettings CPU orchestration tests.
#include "develop_pipeline.h"
#include "develop_settings_codec.h"

#include <gtest/gtest.h>

#include <algorithm>
#include <array>
#include <atomic>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <limits>
#include <mutex>
#include <vector>

#include "develop_color.h"
#include "develop_detail.h"
#include "develop_tone.h"
#include "pixel_plane.h"
#include "resample.h"

namespace {
using namespace croptop;
namespace pipe = croptop::develop::pipeline;

TileStore scene(std::uint32_t width, std::uint32_t height, PixelFormat format = PixelFormat::RGBA32F) {
    TileStore out(format, width, height);
    for (std::uint32_t ty = 0; ty * kTileEdge < height; ++ty) {
        for (std::uint32_t tx = 0; tx * kTileEdge < width; ++tx) {
            TileBuffer tile(out.tile_byte_size(), 0);
            for (std::uint32_t y = 0; y < kTileEdge; ++y) {
                for (std::uint32_t x = 0; x < kTileEdge; ++x) {
                    const auto gx = tx * kTileEdge + x, gy = ty * kTileEdge + y;
                    if (gx >= width || gy >= height) continue;
                    const float rgba[4] = {0.06F + 0.6F * gx / std::max(1U, width - 1), 0.12F + 0.5F * gy / std::max(1U, height - 1), 0.25F, 1.0F};
                    const auto offset = (static_cast<std::size_t>(y) * kTileEdge + x) * out.bytes_per_pixel();
                    if (format == PixelFormat::RGBA16) {
                        std::uint16_t pixel16[4];
                        for (int c = 0; c < 4; ++c) pixel16[c] = static_cast<std::uint16_t>(std::lround(std::clamp(rgba[c], 0.0F, 1.0F) * 65535.0F));
                        std::memcpy(tile.data() + offset, pixel16, sizeof(pixel16));
                    } else {
                        std::memcpy(tile.data() + offset, rgba, sizeof(rgba));
                    }
                }
            }
            EXPECT_TRUE(out.writeTile({static_cast<std::int32_t>(tx), static_cast<std::int32_t>(ty)}, tile));
        }
    }
    return out;
}

std::array<float, 4> pixel(const TileStore& store, std::uint32_t x, std::uint32_t y) {
    std::array<float, 4> result{};
    const auto tile = store.readTile({static_cast<std::int32_t>(x / kTileEdge), static_cast<std::int32_t>(y / kTileEdge)});
    if (tile) std::memcpy(result.data(), tile->data() + (static_cast<std::size_t>(y % kTileEdge) * kTileEdge + x % kTileEdge) * 16, 16);
    return result;
}

std::vector<std::uint8_t> pixelBytes(const TileStore& store, std::uint32_t x, std::uint32_t y) {
    const auto tile = store.readTile({static_cast<std::int32_t>(x / kTileEdge), static_cast<std::int32_t>(y / kTileEdge)});
    if (!tile) return std::vector<std::uint8_t>(store.bytes_per_pixel(), 0);
    const auto offset = (static_cast<std::size_t>(y % kTileEdge) * kTileEdge + x % kTileEdge) * store.bytes_per_pixel();
    return std::vector<std::uint8_t>(tile->begin() + static_cast<std::ptrdiff_t>(offset),
                                     tile->begin() + static_cast<std::ptrdiff_t>(offset + store.bytes_per_pixel()));
}

TEST(DevelopPipeline, MatchesDeclaredToneColorDetailOrder) {
    const auto input = scene(32, 24);
    pipe::Settings settings;
    settings.profile = pipe::Profile::Vivid;
    settings.basic.exposure = 0.35F;
    settings.basic.contrast = 12;
    settings.basic.highlights = -8; settings.basic.shadows = 10; settings.basic.whites = 4; settings.basic.blacks = -3;
    settings.basic.texture = 5; settings.basic.clarity = -4; settings.basic.dehaze = 3; settings.basic.vibrance = 7; settings.basic.saturation = -2;
    settings.tone_curve.points = {{0, 0}, {0.45F, 0.52F}, {1, 1}};
    settings.mixer[5] = {8, 14, 3};
    settings.grading.shadows = {220, 12, 3};
    settings.effects.vignette_amount = -9; settings.effects.vignette_midpoint = 60;
    settings.effects.grain_amount = 4; settings.effects.grain_size = 31;
    settings.detail.sharpen_amount = 18; settings.detail.sharpen_radius = 1.2F; settings.detail.sharpen_detail = 40; settings.detail.sharpen_masking = 10;
    settings.detail.color_noise = 8; settings.detail.luminance_noise = 6;

    std::string error;
    const auto rendered = pipe::render(input, settings, {}, error);
    ASSERT_TRUE(rendered) << error;
    EXPECT_EQ(rendered->width, input.width());
    EXPECT_EQ(rendered->height, input.height());
    EXPECT_GT(rendered->elapsed.count(), 0);

    develop::ToneSettings tone;
    tone.exposure = settings.basic.exposure;
    tone.contrast = settings.basic.contrast;
    tone.highlights = settings.basic.highlights; tone.shadows = settings.basic.shadows;
    tone.whites = settings.basic.whites; tone.blacks = settings.basic.blacks;
    tone.texture = settings.basic.texture; tone.clarity = settings.basic.clarity;
    tone.dehaze = settings.basic.dehaze; tone.vibrance = settings.basic.vibrance; tone.saturation = settings.basic.saturation;
    tone.point_curve.points = settings.tone_curve.points;
    auto toned = develop::applyTone(input, tone, error);
    ASSERT_TRUE(toned) << error;
    develop::ColorSettings color;
    color.look = develop::Look::Vivid;
    color.mixer.hue[5] = 8;
    color.mixer.saturation[5] = 14;
    color.mixer.luminance[5] = 3;
    color.grading.shadows = {220, 12, 3};
    auto colored = develop::applyColor(*toned, color, error);
    ASSERT_TRUE(colored) << error;
    develop::DetailSettings detail;
    detail.sharpening = {settings.detail.sharpen_amount, settings.detail.sharpen_radius, settings.detail.sharpen_detail, settings.detail.sharpen_masking};
    detail.noise.color = settings.detail.color_noise; detail.noise.luminance = settings.detail.luminance_noise;
    detail.vignette.amount = settings.effects.vignette_amount; detail.vignette.midpoint = settings.effects.vignette_midpoint;
    detail.grain.amount = 4;
    detail.grain.size = settings.effects.grain_size;
    auto expected = develop::applyDetail(*colored, detail, error);
    ASSERT_TRUE(expected) << error;
    for (const auto [x, y] : {std::pair{0U, 0U}, {15U, 11U}, {31U, 23U}}) {
        const auto actualPixel = pixel(rendered->pixels, x, y);
        const auto expectedPixel = pixel(*expected, x, y);
        for (int c = 0; c < 4; ++c) EXPECT_FLOAT_EQ(actualPixel[static_cast<std::size_t>(c)], expectedPixel[static_cast<std::size_t>(c)]);
    }
}

TEST(DevelopPipeline, BoundedPreviewPreservesAspectAndIsDeterministic) {
    const auto input = scene(1024, 512);
    pipe::Request request;
    request.mode = pipe::Mode::Preview;
    request.preview_max_dimension = 256;
    std::string error;
    const auto first = pipe::render(input, {}, request, error);
    ASSERT_TRUE(first) << error;
    const auto second = pipe::render(input, {}, request, error);
    ASSERT_TRUE(second) << error;
    EXPECT_EQ(first->width, 256U);
    EXPECT_EQ(first->height, 128U);
    EXPECT_EQ(first->width, second->width);
    EXPECT_EQ(first->height, second->height);
    EXPECT_EQ(pixel(first->pixels, 128, 64), pixel(second->pixels, 128, 64));
    EXPECT_GT(first->elapsed.count(), 0);
}

TEST(DevelopPipeline, PreviewCacheReusesOnlyMatchingSourceProxy) {
    const auto input = scene(1024, 512);
    pipe::PreviewProxyCache cache;
    pipe::Request request;
    request.mode = pipe::Mode::Preview;
    request.preview_max_dimension = 256;
    request.source_revision = 7;
    request.preview_cache = &cache;
    std::string error;
    auto cached = pipe::render(input, {}, request, error);
    ASSERT_TRUE(cached) << error;
    ASSERT_GT(cache.residentBytes(), 0U);
    EXPECT_LE(cache.residentBytes(), 64U * 1024U * 1024U);

    auto cacheHit = pipe::render(input, {}, request, error);
    ASSERT_TRUE(cacheHit) << error;
    EXPECT_EQ(cache.cacheHits(), 1U);

    pipe::Request uncachedRequest = request;
    uncachedRequest.preview_cache = nullptr;
    auto uncached = pipe::render(input, {}, uncachedRequest, error);
    ASSERT_TRUE(uncached) << error;
    EXPECT_EQ(cache.cacheHits(), 1U);
    EXPECT_EQ(pixel(cached->pixels, 127, 63), pixel(uncached->pixels, 127, 63));

    // Revisions identify source pixel mutations; settings are deliberately not part of the key.
    pipe::Settings changedSettings;
    changedSettings.basic.exposure = 1.0F;
    auto adjusted = pipe::render(input, changedSettings, request, error);
    ASSERT_TRUE(adjusted) << error;
    EXPECT_EQ(cache.cacheHits(), 2U);
    auto adjustedUncached = pipe::render(input, changedSettings, uncachedRequest, error);
    ASSERT_TRUE(adjustedUncached) << error;
    EXPECT_EQ(pixel(adjusted->pixels, 127, 63), pixel(adjustedUncached->pixels, 127, 63));

    auto changed = scene(1024, 512, PixelFormat::RGBA16);
    request.source_revision = 7;
    auto changedCached = pipe::render(changed, {}, request, error);
    ASSERT_TRUE(changedCached) << error;
    EXPECT_EQ(cache.cacheHits(), 2U); // format mismatch must rebuild rather than reuse the RGBA32F proxy
    request.source_revision = 8; // pixel mutations are invalidated by the document revision
    auto changedRevisionCached = pipe::render(changed, {}, request, error);
    ASSERT_TRUE(changedRevisionCached) << error;
    EXPECT_EQ(cache.cacheHits(), 2U);

    auto mutated = changed;
    auto firstTile = changed.readTile({0, 0});
    ASSERT_TRUE(firstTile);
    TileBuffer mutatedTile(*firstTile);
    const std::uint16_t bright[4] = {65535, 12000, 8000, 65535};
    std::memcpy(mutatedTile.data(), bright, sizeof(bright));
    ASSERT_TRUE(mutated.writeTile({0, 0}, mutatedTile));
    request.source_revision = 9;
    auto mutatedCached = pipe::render(mutated, {}, request, error);
    ASSERT_TRUE(mutatedCached) << error;
    uncachedRequest.source_revision = 9;
    auto mutatedUncached = pipe::render(mutated, {}, uncachedRequest, error);
    ASSERT_TRUE(mutatedUncached) << error;
    EXPECT_EQ(cache.cacheHits(), 2U);
    EXPECT_EQ(pixelBytes(mutatedCached->pixels, 127, 63), pixelBytes(mutatedUncached->pixels, 127, 63));
    EXPECT_NE(pixelBytes(mutatedCached->pixels, 0, 0), pixelBytes(changedRevisionCached->pixels, 0, 0));
    auto changedUncached = pipe::render(changed, {}, uncachedRequest, error);
    ASSERT_TRUE(changedUncached) << error;
    EXPECT_EQ(changedCached->pixels.format(), PixelFormat::RGBA16);
    EXPECT_EQ(pixelBytes(changedRevisionCached->pixels, 127, 63), pixelBytes(changedUncached->pixels, 127, 63));

    const auto hitsBeforeBoundChange = cache.cacheHits();
    const TileStore differentDimensions(PixelFormat::RGBA16, 1024, 256);
    auto dimensionMiss = pipe::render(differentDimensions, {}, request, error);
    ASSERT_TRUE(dimensionMiss) << error;
    EXPECT_EQ(cache.cacheHits(), hitsBeforeBoundChange);
    EXPECT_EQ(dimensionMiss->height, 64U);

    request.preview_max_dimension = 128;
    auto otherBound = pipe::render(changed, {}, request, error);
    ASSERT_TRUE(otherBound) << error;
    EXPECT_EQ(cache.cacheHits(), hitsBeforeBoundChange);
    EXPECT_EQ(otherBound->width, 128U);
    EXPECT_EQ(otherBound->height, 64U);
    EXPECT_LE(cache.residentBytes(), 64U * 1024U * 1024U);
    cache.setMaxBytes(1);
    EXPECT_EQ(cache.residentBytes(), 0U);
    auto tooLargeToCache = pipe::render(changed, {}, request, error);
    ASSERT_TRUE(tooLargeToCache) << error;
    EXPECT_EQ(cache.residentBytes(), 0U);
    cache.clear();
    EXPECT_EQ(cache.residentBytes(), 0U);
}

TEST(DevelopPipeline, SkipsSubpixelDetailOnlyInInteractivePreview) {
    const auto input = scene(128, 128);
    pipe::Settings detailed;
    detailed.detail.sharpen_amount = 150;
    detailed.detail.sharpen_radius = 3;
    detailed.detail.sharpen_detail = 100;
    detailed.detail.luminance_noise = 80;
    detailed.detail.color_noise = 80;
    detailed.effects.grain_amount = 50;
    detailed.effects.grain_size = 60;
    detailed.effects.vignette_amount = -45;
    pipe::Settings noPixelDetail = detailed;
    noPixelDetail.detail.sharpen_amount = 0;
    noPixelDetail.detail.luminance_noise = 0;
    noPixelDetail.detail.color_noise = 0;
    noPixelDetail.effects.grain_amount = 0;

    pipe::Request previewRequest;
    previewRequest.mode = pipe::Mode::Preview;
    previewRequest.preview_max_dimension = 32;
    std::string error;
    const auto previewDetailed = pipe::render(input, detailed, previewRequest, error);
    ASSERT_TRUE(previewDetailed) << error;
    const auto previewClean = pipe::render(input, noPixelDetail, previewRequest, error);
    ASSERT_TRUE(previewClean) << error;
    EXPECT_TRUE(std::any_of(previewDetailed->warnings.begin(), previewDetailed->warnings.end(), [](const std::string& warning) {
        return warning.find("omitted sharpening, noise reduction, and grain below 0.5 scale") != std::string::npos;
    }));
    for (const auto [x, y] : {std::pair{0U, 0U}, {16U, 16U}, {31U, 31U}}) {
        EXPECT_EQ(pixel(previewDetailed->pixels, x, y), pixel(previewClean->pixels, x, y));
    }
    auto noVignette = noPixelDetail;
    noVignette.effects.vignette_amount = 0;
    const auto previewNoVignette = pipe::render(input, noVignette, previewRequest, error);
    ASSERT_TRUE(previewNoVignette) << error;
    EXPECT_NE(pixel(previewClean->pixels, 0, 0), pixel(previewNoVignette->pixels, 0, 0));

    pipe::Request fullRequest;
    const auto fullDetailed = pipe::render(input, detailed, fullRequest, error);
    ASSERT_TRUE(fullDetailed) << error;
    const auto fullClean = pipe::render(input, noPixelDetail, fullRequest, error);
    ASSERT_TRUE(fullClean) << error;
    EXPECT_FALSE(std::any_of(fullDetailed->warnings.begin(), fullDetailed->warnings.end(), [](const std::string& warning) {
        return warning.find("omitted sharpening") != std::string::npos;
    }));
    bool fullResolutionEffectsDiffer = false;
    for (const auto [x, y] : {std::pair{8U, 8U}, {40U, 44U}, {100U, 90U}, {127U, 127U}}) {
        fullResolutionEffectsDiffer |= pixel(fullDetailed->pixels, x, y) != pixel(fullClean->pixels, x, y);
    }
    EXPECT_TRUE(fullResolutionEffectsDiffer);
}

TEST(DevelopPipeline, TwoTierRequestsBoundInteractiveThenSettledPreview) {
    const auto input = scene(2048, 1024);
    auto latestGeneration = std::make_shared<std::atomic<std::uint64_t>>(7);
    pipe::PreviewProxyCache cache;
    pipe::Request base;
    base.source_revision = 4;
    base.preview_cache = &cache;

    const auto interactiveRequest = pipe::makePreviewRequest(
        base, pipe::PreviewTier::Interactive, 7, latestGeneration);
    EXPECT_EQ(interactiveRequest.mode, pipe::Mode::Preview);
    EXPECT_EQ(interactiveRequest.preview_max_dimension, 256U);
    pipe::Settings detailed;
    detailed.detail.sharpen_amount = 140;
    detailed.detail.luminance_noise = 70;
    detailed.detail.color_noise = 70;
    detailed.effects.grain_amount = 40;
    detailed.effects.grain_size = 50;
    std::string error;
    const auto interactive = pipe::render(input, detailed, interactiveRequest, error);
    ASSERT_TRUE(interactive) << error;
    EXPECT_EQ(interactive->width, 256U);
    EXPECT_EQ(interactive->height, 128U);
    EXPECT_EQ(interactive->preview_tier, pipe::PreviewTier::Interactive);
    EXPECT_EQ(interactive->preview_generation, 7U);
    EXPECT_TRUE(std::any_of(interactive->warnings.begin(), interactive->warnings.end(), [](const std::string& warning) {
        return warning.find("omitted sharpening, noise reduction, and grain below 0.5 scale") != std::string::npos;
    }));

    const auto settledRequest = pipe::makePreviewRequest(
        base, pipe::PreviewTier::Settled, 7, latestGeneration);
    EXPECT_EQ(settledRequest.preview_max_dimension, 512U);
    const auto settled = pipe::render(input, detailed, settledRequest, error);
    ASSERT_TRUE(settled) << error;
    EXPECT_EQ(settled->width, 512U);
    EXPECT_EQ(settled->height, 256U);
    EXPECT_EQ(settled->preview_tier, pipe::PreviewTier::Settled);
    EXPECT_EQ(settled->preview_generation, 7U);
    EXPECT_FALSE(std::any_of(settled->warnings.begin(), settled->warnings.end(), [](const std::string& warning) {
        return warning.find("omitted sharpening") != std::string::npos;
    }));
    pipe::Settings detailFree = detailed;
    detailFree.detail.sharpen_amount = 0;
    detailFree.detail.luminance_noise = 0;
    detailFree.detail.color_noise = 0;
    detailFree.effects.grain_amount = 0;
    const auto settledDetailFree = pipe::render(input, detailFree, settledRequest, error);
    ASSERT_TRUE(settledDetailFree) << error;
    EXPECT_NE(pixel(settled->pixels, 200, 100), pixel(settledDetailFree->pixels, 200, 100));
    EXPECT_GT(cache.cacheHits(), 0U);

    // Full-resolution/export requests remain outside the UI tier policy and retain full dimensions.
    const auto fullResolution = pipe::render(input, detailed, base, error);
    ASSERT_TRUE(fullResolution) << error;
    EXPECT_EQ(fullResolution->width, input.width());
    EXPECT_EQ(fullResolution->height, input.height());
    EXPECT_EQ(fullResolution->preview_tier, pipe::PreviewTier::Custom);
}

TEST(DevelopPipeline, PreviewGenerationClockCancelsStaleWorkDeterministically) {
    const auto input = scene(512, 512);
    auto latestGeneration = std::make_shared<std::atomic<std::uint64_t>>(12);
    const auto request = pipe::makePreviewRequest({}, pipe::PreviewTier::Interactive, 11, latestGeneration);
    std::string error;
    EXPECT_FALSE(pipe::render(input, {}, request, error));
    EXPECT_EQ(error, "cancelled");

    latestGeneration->store(13, std::memory_order_release);
    pipe::Request current = pipe::makePreviewRequest({}, pipe::PreviewTier::Interactive, 13, latestGeneration);
    current.progress = [latestGeneration](float progress) {
        if (progress == 0.0F) latestGeneration->store(14, std::memory_order_release);
        return true;
    };
    EXPECT_FALSE(pipe::render(input, {}, current, error));
    EXPECT_EQ(error, "cancelled");
}

TEST(DevelopPipeline, CancelledPreviewDoesNotPopulatePartialProxyCache) {
    const TileStore input(PixelFormat::RGBA32F, 16384, 8192);
    pipe::PreviewProxyCache cache;
    pipe::Request request;
    request.mode = pipe::Mode::Preview;
    request.preview_max_dimension = 4096;
    request.preview_cache = &cache;
    int callbackCount = 0;
    request.progress = [&callbackCount](float) { return ++callbackCount < 3; };
    std::string error;
    EXPECT_FALSE(pipe::render(input, {}, request, error));
    EXPECT_EQ(error, "cancelled");
    EXPECT_EQ(cache.residentBytes(), 0U);
}

TEST(DevelopPipeline, PreservesRgba16FullResolutionFormat) {
    const auto input = scene(32, 16, PixelFormat::RGBA16);
    std::string error;
    const auto result = pipe::render(input, {}, {}, error);
    ASSERT_TRUE(result) << error;
    EXPECT_EQ(result->pixels.format(), PixelFormat::RGBA16);
    EXPECT_EQ(result->width, input.width());
    EXPECT_EQ(result->height, input.height());
    EXPECT_TRUE(std::any_of(result->warnings.begin(), result->warnings.end(), [](const std::string& warning) {
        return warning.find("Lens-profile") != std::string::npos;
    }));
}

TEST(DevelopPipeline, WhiteBalanceProfilesAndProgressCancellation) {
    const auto input = scene(512, 512);
    pipe::Settings settings;
    settings.profile = pipe::Profile::Monochrome;
    settings.white_balance = {pipe::WhiteBalancePreset::Custom, 3200, 12};
    settings.detail.sharpen_amount = 0;
    settings.detail.color_noise = settings.detail.luminance_noise = 0;
    std::vector<float> progress;
    pipe::Request request;
    request.worker_threads = 4;
    request.progress = [&progress](float value) { progress.push_back(value); return true; };
    std::string error;
    const auto rendered = pipe::render(input, settings, request, error);
    ASSERT_TRUE(rendered) << error;
    ASSERT_FALSE(progress.empty());
    EXPECT_FLOAT_EQ(progress.front(), 0.0F);
    EXPECT_FLOAT_EQ(progress.back(), 1.0F);
    EXPECT_TRUE(std::is_sorted(progress.begin(), progress.end()));
    const auto p = pixel(rendered->pixels, 20, 16);
    EXPECT_NEAR(p[0], p[1], 1e-5F);
    EXPECT_NEAR(p[1], p[2], 1e-5F);

    pipe::Request cancel;
    cancel.progress = [](float value) { return value < 0.25F; };
    const auto aborted = pipe::render(input, settings, cancel, error);
    EXPECT_FALSE(aborted);
    EXPECT_EQ(error, "cancelled");
}

TEST(DevelopPipeline, CropFrameAndStraightenResizeTheOutput) {
    const auto input = scene(40, 20);
    std::string error;
    pipe::Settings settings;
    settings.crop.left = 0.25F;
    settings.crop.right = 0.75F;
    settings.crop.top = 0.0F;
    settings.crop.bottom = 0.5F;
    auto framed = pipe::render(input, settings, {}, error);
    ASSERT_TRUE(framed) << error;
    EXPECT_EQ(framed->width, 20U);
    EXPECT_EQ(framed->height, 10U);
    settings = {};
    settings.crop.angle = 5.0F;
    auto straightened = pipe::render(input, settings, {}, error);
    ASSERT_TRUE(straightened) << error;
    EXPECT_LT(straightened->width, 40U) << "the inscribed rectangle is smaller than the source";
    EXPECT_NEAR(static_cast<double>(straightened->width) / straightened->height, 2.0, 0.15) << "the aspect ratio is kept";
}

TEST(DevelopPipeline, LocalRadialMaskBrightensOnlyItsRegion) {
    const auto input = scene(64, 64);
    std::string error;
    const auto base = pipe::render(input, {}, {}, error);
    ASSERT_TRUE(base) << error;
    pipe::Settings settings;
    pipe::LocalMask mask;
    mask.adjust.exposure = 1.5F;
    pipe::MaskComponent circle;
    circle.kind = pipe::MaskKind::Radial;
    circle.p = {0.25F, 0.5F, 0.12F, 0.12F, 0.0F, 0.2F, 0.0F, 0.0F};
    mask.components.push_back(circle);
    settings.masks.push_back(mask);
    const auto local = pipe::render(input, settings, {}, error);
    ASSERT_TRUE(local) << error;
    EXPECT_EQ(local->width, base->width);
    const auto sample = [](const TileStore& s, std::uint32_t x, std::uint32_t y) {
        const auto tile = s.readTile({0, 0});
        float px[4];
        paint::detail::readPixel(s.format(), tile->data() + (static_cast<std::size_t>(y) * kTileEdge + x) * s.bytes_per_pixel(), px);
        return px[0] + px[1] + px[2];
    };
    EXPECT_GT(sample(local->pixels, 16, 32), sample(base->pixels, 16, 32) * 1.2F) << "inside the circle";
    EXPECT_NEAR(sample(local->pixels, 56, 8), sample(base->pixels, 56, 8), 1e-3F) << "far outside is untouched";
}

TEST(DevelopPipeline, LocalMaskComponentsCombineAndInvert) {
    const auto input = scene(64, 64);
    std::string error;
    pipe::Settings settings;
    pipe::LocalMask mask;
    mask.adjust.exposure = 2.0F;
    pipe::MaskComponent all;  // a huge radial covering everything, then subtract a left-half linear gradient
    all.kind = pipe::MaskKind::Radial;
    all.p = {0.5F, 0.5F, 2.0F, 2.0F, 0.0F, 0.0F, 0.0F, 0.0F};
    pipe::MaskComponent left;
    left.kind = pipe::MaskKind::Linear;
    left.op = 1;
    left.p = {0.0F, 0.5F, 0.5F, 0.5F, 0, 0, 0, 0};  // 1 at the left edge fading to 0 at the centre
    mask.components = {all, left};
    settings.masks.push_back(mask);
    const auto base = pipe::render(input, {}, {}, error);
    const auto out = pipe::render(input, settings, {}, error);
    ASSERT_TRUE(out && base) << error;
    const auto lum = [](const TileStore& s, std::uint32_t x) {
        const auto tile = s.readTile({0, 0});
        float px[4];
        paint::detail::readPixel(s.format(), tile->data() + (static_cast<std::size_t>(32) * kTileEdge + x) * s.bytes_per_pixel(), px);
        return px[0] + px[1] + px[2];
    };
    EXPECT_NEAR(lum(out->pixels, 2), lum(base->pixels, 2), 0.06F) << "subtracted region keeps the global result";
    EXPECT_GT(lum(out->pixels, 60), lum(base->pixels, 60) * 1.5F) << "the rest is lifted";
    settings.masks[0].invert = true;
    const auto inverted = pipe::render(input, settings, {}, error);
    ASSERT_TRUE(inverted) << error;
    EXPECT_GT(lum(inverted->pixels, 2), lum(base->pixels, 2) * 1.5F) << "inverting swaps the regions";
}

pipe::MaskComponent aiLeftHalf(std::uint16_t side) {
    pipe::MaskComponent ai;
    ai.kind = pipe::MaskKind::Ai;
    ai.bitmap_width = side;
    ai.bitmap_height = side;
    ai.bitmap.assign(static_cast<std::size_t>(side) * side, 0);
    for (std::uint16_t y = 0; y < side; ++y)
        for (std::uint16_t x = 0; x < side / 2; ++x) ai.bitmap[static_cast<std::size_t>(y) * side + x] = 255;
    return ai;
}

TEST(DevelopPipeline, AiMaskBitmapLiftsOnlyTheSelectedSide) {
    const auto input = scene(128, 128);
    std::string error;
    const auto base = pipe::render(input, {}, {}, error);
    ASSERT_TRUE(base) << error;
    pipe::Settings settings;
    pipe::LocalMask mask;
    mask.adjust.exposure = 1.5F;
    mask.components.push_back(aiLeftHalf(32));
    settings.masks.push_back(mask);
    const auto local = pipe::render(input, settings, {}, error);
    ASSERT_TRUE(local) << error;
    const auto sample = [](const TileStore& s, std::uint32_t x, std::uint32_t y) {
        const auto tile = s.readTile({0, 0});
        float px[4];
        paint::detail::readPixel(s.format(), tile->data() + (static_cast<std::size_t>(y) * kTileEdge + x) * s.bytes_per_pixel(), px);
        return px[0] + px[1] + px[2];
    };
    EXPECT_GT(sample(local->pixels, 8, 64), sample(base->pixels, 8, 64) * 1.2F) << "inside the mask";
    EXPECT_NEAR(sample(local->pixels, 120, 64), sample(base->pixels, 120, 64), 0.05F) << "outside the mask";
    settings.masks[0].invert = true;
    const auto inverted = pipe::render(input, settings, {}, error);
    ASSERT_TRUE(inverted) << error;
    EXPECT_GT(sample(inverted->pixels, 120, 64), sample(base->pixels, 120, 64) * 1.2F) << "inverting swaps the sides";
}

TEST(DevelopPipeline, AiMaskBitmapsRoundTripAndBadOnesAreRejected) {
    pipe::Settings settings;
    pipe::LocalMask mask;
    mask.components.push_back(aiLeftHalf(8));
    settings.masks.push_back(mask);
    std::string error;
    const auto decoded = develop::decodeSettings(develop::encodeSettings(settings), error);
    ASSERT_TRUE(decoded) << error;
    const auto& c = decoded->masks[0].components[0];
    EXPECT_EQ(c.kind, pipe::MaskKind::Ai);
    EXPECT_EQ(c.bitmap_width, 8);
    EXPECT_EQ(c.bitmap, settings.masks[0].components[0].bitmap);
    auto bad = settings;
    bad.masks[0].components[0].bitmap.pop_back();
    EXPECT_FALSE(pipe::render(scene(8, 8), bad, {}, error));
    EXPECT_NE(error.find("AI mask"), std::string::npos);
    bad = settings;
    bad.masks[0].components[0].bitmap_width = 300;
    bad.masks[0].components[0].bitmap.assign(300U * 8U, 0);
    EXPECT_FALSE(pipe::render(scene(8, 8), bad, {}, error));
    auto encoded = develop::encodeSettings(settings);
    encoded[encoded.size() - 5] = 300.0F;  // a bitmap value outside 0–255
    EXPECT_FALSE(develop::decodeSettings(encoded, error));
    auto large = settings;
    for (int i = 0; i < 5; ++i) large.masks[0].components.push_back(aiLeftHalf(256));
    EXPECT_FALSE(pipe::render(scene(8, 8), large, {}, error)) << "more than the per-recipe bitmap budget";
}

TEST(DevelopPipeline, RejectsInvalidLocalMasks) {
    const auto input = scene(8, 8);
    std::string error;
    pipe::Settings settings;
    settings.masks.push_back({});  // no components
    EXPECT_FALSE(pipe::render(input, settings, {}, error));
    EXPECT_NE(error.find("component"), std::string::npos);
    settings.masks[0].components.push_back({});
    settings.masks[0].components[0].p[2] = -1.0F;
    EXPECT_FALSE(pipe::render(input, settings, {}, error));
    EXPECT_NE(error.find("radial"), std::string::npos);
}

TEST(DevelopSettingsCodec, LocalMasksRoundTripAndTruncationIsRejected) {
    pipe::Settings settings;
    pipe::LocalMask mask;
    mask.invert = true;
    mask.amount = 0.6F;
    mask.adjust.exposure = 0.7F;
    pipe::MaskComponent brush;
    brush.kind = pipe::MaskKind::Brush;
    brush.strokes.push_back({false, 0.05F, 0.4F, 0.9F, {{0.1F, 0.2F}, {0.3F, 0.4F}}});
    pipe::MaskComponent range;
    range.kind = pipe::MaskKind::Luminance;
    range.op = 2;
    range.p = {0.2F, 0.8F, 0.1F, 0, 0, 0, 0, 0};
    mask.components = {brush, range};
    settings.masks.push_back(mask);
    const auto encoded = develop::encodeSettings(settings);
    std::string error;
    const auto decoded = develop::decodeSettings(encoded, error);
    ASSERT_TRUE(decoded) << error;
    ASSERT_EQ(decoded->masks.size(), 1U);
    const auto& m = decoded->masks[0];
    EXPECT_TRUE(m.invert);
    EXPECT_FLOAT_EQ(m.amount, 0.6F);
    EXPECT_FLOAT_EQ(m.adjust.exposure, 0.7F);
    ASSERT_EQ(m.components.size(), 2U);
    EXPECT_EQ(m.components[0].strokes[0].points.size(), 2U);
    EXPECT_EQ(m.components[1].op, 2);
    auto truncated = encoded;
    truncated.pop_back();
    EXPECT_FALSE(develop::decodeSettings(truncated, error));
    EXPECT_TRUE(develop::decodeSettings(develop::encodeSettings({}), error)) << "settings without masks still decode";
}

TEST(DevelopPipeline, RejectsInvalidOrUnsupportedSchemaValues) {
    const auto input = scene(8, 8);
    std::string error;
    pipe::Settings settings;
    settings.version = 2;
    EXPECT_FALSE(pipe::render(input, settings, {}, error));
    EXPECT_NE(error.find("version"), std::string::npos);
    settings = {};
    settings.tone_curve.points[1].first = 0.0F;
    EXPECT_FALSE(pipe::render(input, settings, {}, error));
    EXPECT_NE(error.find("curve"), std::string::npos);
    settings = {};
    settings.crop.left = 0.9F;
    settings.crop.right = 0.5F;
    EXPECT_FALSE(pipe::render(input, settings, {}, error));
    EXPECT_NE(error.find("crop"), std::string::npos);
    settings = {};
    settings.crop.angle = 60.0F;
    EXPECT_FALSE(pipe::render(input, settings, {}, error));
    EXPECT_NE(error.find("crop"), std::string::npos);
    settings = {};
    settings.optics.distortion = 101.0F;
    EXPECT_FALSE(pipe::render(input, settings, {}, error));
    EXPECT_NE(error.find("optics"), std::string::npos);
    settings = {};
    settings.basic.exposure = std::numeric_limits<float>::quiet_NaN();
    EXPECT_FALSE(pipe::render(input, settings, {}, error));
    EXPECT_NE(error.find("basic tone"), std::string::npos);
    settings = {};
    settings.grading.highlights.saturation = 101;
    EXPECT_FALSE(pipe::render(input, settings, {}, error));
    EXPECT_NE(error.find("grading"), std::string::npos);
    pipe::Request request;
    request.mode = static_cast<pipe::Mode>(255);
    EXPECT_FALSE(pipe::render(input, {}, request, error));
    request = {};
    request.preview_max_dimension = 5000;
    EXPECT_FALSE(pipe::render(input, {}, request, error));
    request = {};
    request.progress = [](float) -> bool { throw std::runtime_error("callback failure"); };
    EXPECT_FALSE(pipe::render(input, {}, request, error));
    EXPECT_EQ(error, "cancelled");
}

TEST(DevelopPipeline, AppliesManualOpticsVignettingAndDistortion) {
    const auto input = scene(32, 24);
    std::string error;
    const auto baseline = pipe::render(input, {}, {}, error);
    ASSERT_TRUE(baseline) << error;

    pipe::Settings vignette_settings;
    vignette_settings.optics.vignetting = -60.0F;
    const auto vignetted = pipe::render(input, vignette_settings, {}, error);
    ASSERT_TRUE(vignetted) << error;
    EXPECT_LT(pixel(vignetted->pixels, 1, 1)[0], pixel(baseline->pixels, 1, 1)[0]);
    EXPECT_NEAR(pixel(vignetted->pixels, 16, 12)[0], pixel(baseline->pixels, 16, 12)[0], 0.01F);

    pipe::Settings distortion_settings;
    distortion_settings.optics.distortion = 70.0F;
    const auto distorted = pipe::render(input, distortion_settings, {}, error);
    ASSERT_TRUE(distorted) << error;
    EXPECT_GT(std::abs(pixel(distorted->pixels, 5, 5)[0] - pixel(baseline->pixels, 5, 5)[0]), 1e-4F);
}

#ifdef CROPTOP_DEVELOP_PIPELINE_BENCHMARK
TEST(DevelopPipeline, Throughput24MPFullAndBoundedPreview) {
    auto input = scene(6000, 4000, PixelFormat::RGBA16);
    std::string error;
    pipe::Settings settings;
    settings.basic.exposure = 0.25F;
    using Clock = std::chrono::steady_clock;
    const auto elapsedSeconds = [](Clock::time_point begin) {
        return std::chrono::duration<double>(Clock::now() - begin).count();
    };
    constexpr std::uint32_t previewBound = 512;
    std::uint32_t mipLevel = 0;
    while (mipLevel < 31U && std::max(input.mip_width(mipLevel), input.mip_height(mipLevel)) > previewBound * 2U) ++mipLevel;
    TileStore mipSource(input.format(), input.mip_width(mipLevel), input.mip_height(mipLevel));
    const auto columns = 1U + (mipSource.width() - 1U) / kTileEdge;
    const auto rows = 1U + (mipSource.height() - 1U) / kTileEdge;
    auto materializeMips = [&] {
        for (std::uint32_t y = 0; y < rows; ++y) {
            for (std::uint32_t x = 0; x < columns; ++x) {
                const auto tile = input.readMipTile(mipLevel, {static_cast<std::int32_t>(x), static_cast<std::int32_t>(y)});
                if (tile) {
                    EXPECT_TRUE(mipSource.writeTile({static_cast<std::int32_t>(x), static_cast<std::int32_t>(y)}, *tile));
                }
            }
        }
    };
    const auto medianSeconds = [](std::vector<double> samples) {
        std::sort(samples.begin(), samples.end());
        return samples[samples.size() / 2U];
    };
    auto begin = Clock::now();
    materializeMips();
    std::fprintf(stderr, "Develop proxy 24MP cold mip generation (level %u, %ux%u): %.3f s\n",
                 mipLevel, mipSource.width(), mipSource.height(), elapsedSeconds(begin));
    std::fprintf(stderr, "Develop proxy 24MP source mip resident payload: %.2f MiB\n",
                 input.mip_resident_bytes() / (1024.0 * 1024.0));
    const auto proxyWidth = previewBound;
    const auto proxyHeight = static_cast<std::uint32_t>(std::llround(input.height() * static_cast<double>(previewBound) / input.width()));
    begin = Clock::now();
    auto resized = resample::resize(mipSource, proxyWidth, proxyHeight, resample::Filter::Lanczos3, error, 8);
    ASSERT_TRUE(resized) << error;
    std::fprintf(stderr, "Develop proxy 24MP Lanczos resize from warm mip: %.3f s\n", elapsedSeconds(begin));
    begin = Clock::now();
    materializeMips();
    std::fprintf(stderr, "Develop proxy 24MP warm mip copy: %.3f s\n", elapsedSeconds(begin));

    develop::ToneSettings stageTone;
    stageTone.exposure = 0.25F;
    begin = Clock::now();
    auto stageToned = develop::applyTone(*resized, stageTone, error);
    ASSERT_TRUE(stageToned) << error;
    std::vector<double> stageSamples{elapsedSeconds(begin)};
    for (int sample = 0; sample < 6; ++sample) {
        begin = Clock::now();
        const auto repeated = develop::applyTone(*resized, stageTone, error);
        ASSERT_TRUE(repeated) << error;
        stageSamples.push_back(elapsedSeconds(begin));
    }
    std::fprintf(stderr, "Develop proxy 512px tone stage median (7 runs): %.3f s\n", medianSeconds(stageSamples));
    develop::ColorSettings stageColor;
    stageColor.look = develop::Look::Neutral;
    begin = Clock::now();
    auto stageColored = develop::applyColor(*stageToned, stageColor, error);
    ASSERT_TRUE(stageColored) << error;
    stageSamples = {elapsedSeconds(begin)};
    for (int sample = 0; sample < 6; ++sample) {
        begin = Clock::now();
        const auto repeated = develop::applyColor(*stageToned, stageColor, error);
        ASSERT_TRUE(repeated) << error;
        stageSamples.push_back(elapsedSeconds(begin));
    }
    std::fprintf(stderr, "Develop proxy 512px neutral color stage median (7 runs): %.3f s\n", medianSeconds(stageSamples));
    develop::DetailSettings stageDetail;
    stageDetail.sharpening = {settings.detail.sharpen_amount, settings.detail.sharpen_radius,
                              settings.detail.sharpen_detail, settings.detail.sharpen_masking};
    stageDetail.noise.color = settings.detail.color_noise;
    stageDetail.noise.luminance = settings.detail.luminance_noise;
    begin = Clock::now();
    auto stageDetailed = develop::applyDetail(*stageColored, stageDetail, error);
    ASSERT_TRUE(stageDetailed) << error;
    stageSamples = {elapsedSeconds(begin)};
    for (int sample = 0; sample < 6; ++sample) {
        begin = Clock::now();
        const auto repeated = develop::applyDetail(*stageColored, stageDetail, error);
        ASSERT_TRUE(repeated) << error;
        stageSamples.push_back(elapsedSeconds(begin));
    }
    std::fprintf(stderr, "Develop proxy 512px detail stage median (7 runs): %.3f s\n", medianSeconds(stageSamples));

    pipe::Request request;
    const auto result = pipe::render(input, settings, request, error);
    ASSERT_TRUE(result) << error;
    std::fprintf(stderr, "Develop pipeline 24MP RGBA16 full: %.3f s\n", result->elapsed.count() / 1e9);
    request.mode = pipe::Mode::Preview;
    request.preview_max_dimension = previewBound;
    input.trimCaches(); // Ensure the next pipeline preview measures cold source-mip construction.
    const auto preview = pipe::render(input, settings, request, error);
    ASSERT_TRUE(preview) << error;
    EXPECT_EQ(preview->width, 512U);
    EXPECT_EQ(preview->height, 341U);
    std::fprintf(stderr, "Develop pipeline 24MP RGBA16 cold bounded 512px CPU preview: %.3f s\n", preview->elapsed.count() / 1e9);
    stageSamples.clear();
    for (int sample = 0; sample < 7; ++sample) {
        const auto warmPreview = pipe::render(input, settings, request, error);
        ASSERT_TRUE(warmPreview) << error;
        EXPECT_EQ(warmPreview->width, 512U);
        EXPECT_EQ(warmPreview->height, 341U);
        stageSamples.push_back(warmPreview->elapsed.count() / 1e9);
    }
    std::fprintf(stderr, "Develop pipeline 24MP RGBA16 warm bounded 512px CPU preview median (7): %.3f s\n", medianSeconds(stageSamples));
    pipe::PreviewProxyCache proxyCache;
    request.preview_cache = &proxyCache;
    request.source_revision = 1;
    const auto cachedCold = pipe::render(input, settings, request, error);
    ASSERT_TRUE(cachedCold) << error;
    std::fprintf(stderr, "Develop pipeline 24MP proxy-cache miss (warm source mips): %.3f s, cache %.2f MiB\n",
                 cachedCold->elapsed.count() / 1e9, proxyCache.residentBytes() / (1024.0 * 1024.0));
    stageSamples.clear();
    for (int sample = 0; sample < 7; ++sample) {
        const auto warm = pipe::render(input, settings, request, error);
        ASSERT_TRUE(warm) << error;
        stageSamples.push_back(warm->elapsed.count() / 1e9);
    }
    std::fprintf(stderr, "Develop pipeline 24MP cached-proxy warm render median (7): %.3f s\n", medianSeconds(stageSamples));

    for (const std::uint32_t bound : {256U, 128U}) {
        pipe::PreviewProxyCache smallerCache;
        pipe::Request smallerRequest;
        smallerRequest.mode = pipe::Mode::Preview;
        smallerRequest.preview_max_dimension = bound;
        smallerRequest.source_revision = 1;
        smallerRequest.preview_cache = &smallerCache;
        const auto first = pipe::render(input, settings, smallerRequest, error);
        ASSERT_TRUE(first) << error;
        std::vector<double> smallSamples;
        std::uint32_t warmWidth = 0, warmHeight = 0;
        for (int sample = 0; sample < 5; ++sample) {
            const auto rendered = pipe::render(input, settings, smallerRequest, error);
            ASSERT_TRUE(rendered) << error;
            smallSamples.push_back(rendered->elapsed.count() / 1e9);
            if (sample == 0) { warmWidth = rendered->width; warmHeight = rendered->height; }
        }
        std::fprintf(stderr, "Develop pipeline cached-proxy warm %upx preview (%ux%u): %.3f s\n",
                     bound, warmWidth, warmHeight, medianSeconds(smallSamples));
    }

    auto generation = std::make_shared<std::atomic<std::uint64_t>>(31);
    pipe::PreviewProxyCache tierCache;
    pipe::Request tierBase;
    tierBase.source_revision = 1;
    tierBase.preview_cache = &tierCache;
    for (const auto tier : {pipe::PreviewTier::Interactive, pipe::PreviewTier::Settled}) {
        const auto tierRequest = pipe::makePreviewRequest(tierBase, tier, 31, generation);
        const auto first = pipe::render(input, settings, tierRequest, error);
        ASSERT_TRUE(first) << error;
        std::vector<double> tierSamples;
        std::uint32_t outputWidth = 0, outputHeight = 0;
        for (int sample = 0; sample < 7; ++sample) {
            const auto rendered = pipe::render(input, settings, tierRequest, error);
            ASSERT_TRUE(rendered) << error;
            tierSamples.push_back(rendered->elapsed.count() / 1e9);
            if (sample == 0) { outputWidth = rendered->width; outputHeight = rendered->height; }
            EXPECT_EQ(rendered->preview_tier, tier);
        }
        std::fprintf(stderr, "Develop pipeline two-tier %s request (%ux%u) cached warm median (7): %.3f s\n",
                     tier == pipe::PreviewTier::Interactive ? "interactive" : "settled",
                     outputWidth, outputHeight, medianSeconds(tierSamples));
    }
}
#endif

} // namespace

TEST(DevelopPipeline, CalibrationMovesColourAndRejectsOutOfRangeValues) {
    const auto input = scene(16, 16);
    std::string error;
    pipe::Settings base;
    const auto plain = pipe::render(input, base, {}, error);
    ASSERT_TRUE(plain) << error;
    pipe::Settings calibrated;
    calibrated.calibration.red_hue = 60;
    calibrated.calibration.blue_saturation = -80;
    const auto moved = pipe::render(input, calibrated, {}, error);
    ASSERT_TRUE(moved) << error;
    const auto a = pixel(plain->pixels, 8, 8);
    const auto b = pixel(moved->pixels, 8, 8);
    EXPECT_GT(std::fabs(a[0] - b[0]) + std::fabs(a[1] - b[1]) + std::fabs(a[2] - b[2]), 0.005F) << "calibration must change colour";
    EXPECT_FLOAT_EQ(a[3], b[3]);
    pipe::Settings invalid;
    invalid.calibration.shadow_tint = 150;
    EXPECT_FALSE(pipe::render(input, invalid, {}, error));
    EXPECT_NE(error.find("calibration"), std::string::npos);
}

TEST(DevelopPipeline, SpotRepairReplacesTheCircleFromItsSourceAndLeavesTheRestAlone) {
    // A flat grey field with one bright blemish; the spot repairs it from a clean patch on the right.
    const std::uint32_t size = 64;
    auto input = scene(size, size, PixelFormat::RGBA32F);
    std::string error;
    pipe::Settings base;
    const auto plain = pipe::render(input, base, {}, error);
    ASSERT_TRUE(plain) << error;
    pipe::Settings spot;
    spot.spots.push_back({0.25F, 0.5F, 0.1F, 0.75F, 0.5F, 0.0F, 1.0F, false});
    const auto repaired = pipe::render(input, spot, {}, error);
    ASSERT_TRUE(repaired) << error;
    const auto centre = pixel(repaired->pixels, 16, 31);
    const auto sourceCentre = pixel(plain->pixels, 47, 31);
    for (int c = 0; c < 3; ++c) EXPECT_NEAR(centre[c], sourceCentre[c], 0.02F) << "the spot centre takes the source colour";
    const auto before = pixel(plain->pixels, 16, 31);
    EXPECT_GT(std::fabs(centre[0] - before[0]) + std::fabs(centre[1] - before[1]), 0.02F) << "the repair must change the spot";
    const auto far = pixel(repaired->pixels, 60, 4);
    const auto farBefore = pixel(plain->pixels, 60, 4);
    for (int c = 0; c < 4; ++c) EXPECT_FLOAT_EQ(far[c], farBefore[c]);
    pipe::Settings invalid;
    invalid.spots.push_back({1.5F, 0.5F, 0.1F, 0.5F, 0.5F, 0.5F, 1.0F, true});
    EXPECT_FALSE(pipe::render(input, invalid, {}, error));
    EXPECT_NE(error.find("spot"), std::string::npos);
}

namespace {
/** A building-like grid (dark lines every 50 px on white) rotated by `degrees` about the centre, scene-linear RGBA32F. */
TileStore tiltedGrid(std::uint32_t width, std::uint32_t height, double degrees) {
    TileStore out(PixelFormat::RGBA32F, width, height);
    const double a = degrees * 3.14159265358979323846 / 180.0, c = std::cos(a), s = std::sin(a);
    const double cx = width / 2.0, cy = height / 2.0;
    for (std::uint32_t ty = 0; ty * kTileEdge < height; ++ty) {
        for (std::uint32_t tx = 0; tx * kTileEdge < width; ++tx) {
            TileBuffer tile(out.tile_byte_size(), 0);
            for (std::uint32_t y = 0; y < kTileEdge; ++y) {
                for (std::uint32_t x = 0; x < kTileEdge; ++x) {
                    const auto gx = tx * kTileEdge + x, gy = ty * kTileEdge + y;
                    if (gx >= width || gy >= height) continue;
                    const double ux = c * (gx - cx) + s * (gy - cy) + cx, uy = -s * (gx - cx) + c * (gy - cy) + cy;
                    const auto ix = static_cast<long>(std::floor(ux)), iy = static_cast<long>(std::floor(uy));
                    const bool line = ((ix % 50 + 50) % 50 < 3 && ix > 60 && ix < static_cast<long>(width) - 60) ||
                                      ((iy % 50 + 50) % 50 < 3 && iy > 60 && iy < static_cast<long>(height) - 60);
                    const float v = line ? 0.02F : 0.9F;
                    const float rgba[4] = {v, v, v, 1.0F};
                    std::memcpy(tile.data() + (static_cast<std::size_t>(y) * kTileEdge + x) * 16U, rgba, sizeof(rgba));
                }
            }
            EXPECT_TRUE(out.writeTile({static_cast<std::int32_t>(tx), static_cast<std::int32_t>(ty)}, tile));
        }
    }
    return out;
}
}  // namespace

TEST(DevelopPipeline, UprightLevelStraightensATiltedFrameBeforeTheCrop) {
    const auto input = tiltedGrid(900, 700, 4.0);
    std::string error;
    pipe::Settings off;
    off.detail = {0, 1, 0, 0, 0, 0};
    const auto plain = pipe::render(input, off, {}, error);
    ASSERT_TRUE(plain) << error;
    EXPECT_EQ(plain->width, 900U);
    pipe::Settings level = off;
    level.upright = pipe::Upright::Level;
    const auto straightened = pipe::render(input, level, {}, error);
    ASSERT_TRUE(straightened) << error;
    EXPECT_LT(straightened->width, 900U) << "levelling a tilted frame crops to the inscribed rectangle";
    EXPECT_LT(straightened->height, 700U);
    EXPECT_GT(straightened->width, 600U);
    // The longest unbroken dark run along a row measures how level the grid lines are: a line tilted 4° stays in a row for
    // about 3 / tan 4° ≈ 43 px, so a much longer run means the frame was levelled.
    const auto longestRun = [](const pipe::Result& r) {
        std::size_t best = 0;
        for (std::uint32_t y = 40; y < r.height - 40; ++y) {
            std::size_t run = 0;
            for (std::uint32_t x = 40; x < r.width - 40; ++x) {
                run = pixel(r.pixels, x, y)[0] < 0.3F ? run + 1U : 0U;
                best = std::max(best, run);
            }
        }
        return best;
    };
    EXPECT_LT(longestRun(*plain), 90U);
    EXPECT_GT(longestRun(*straightened), 3U * longestRun(*plain)) << "levelling must make the grid lines far straighter";
    pipe::Settings crop = level;
    crop.crop = {0.1F, 0.1F, 0.9F, 0.9F, 0.0F};
    const auto cropped = pipe::render(input, crop, {}, error);
    ASSERT_TRUE(cropped) << error;
    EXPECT_NEAR(static_cast<double>(cropped->width), straightened->width * 0.8, 2.0) << "the crop frame is relative to the upright output";
}

TEST(DevelopPipeline, ManualDefringeDesaturatesAPurpleEdgeButNotAFlatPurpleField) {
    // Left: white; a two-pixel purple fringe; right: dark. Far right: a purple patch away from any edge.
    const std::uint32_t width = 64, height = 32;
    TileStore input(PixelFormat::RGBA32F, width, height);
    TileBuffer tile(input.tile_byte_size(), 0);
    for (std::uint32_t y = 0; y < kTileEdge; ++y) {
        for (std::uint32_t x = 0; x < kTileEdge; ++x) {
            float rgba[4];
            if (x < 20) {
                rgba[0] = rgba[1] = rgba[2] = 0.8F;
            } else if (x < 22) {
                rgba[0] = 0.55F; rgba[1] = 0.25F; rgba[2] = 0.7F;  // the purple fringe
            } else if (x >= 48) {
                rgba[0] = 0.4F; rgba[1] = 0.1F; rgba[2] = 0.5F;  // a purple field, far from the edge
            } else {
                rgba[0] = rgba[1] = rgba[2] = 0.05F;
            }
            rgba[3] = 1.0F;
            std::memcpy(tile.data() + (static_cast<std::size_t>(y) * kTileEdge + x) * 16U, rgba, sizeof(rgba));
        }
    }
    ASSERT_TRUE(input.writeTile({0, 0}, tile));
    std::string error;
    pipe::Settings plain;
    plain.detail = {0, 1, 0, 0, 0, 0};
    const auto before = pipe::render(input, plain, {}, error);
    ASSERT_TRUE(before) << error;
    pipe::Settings fringe = plain;
    fringe.optics.defringe_purple = 20.0F;
    const auto after = pipe::render(input, fringe, {}, error);
    ASSERT_TRUE(after) << error;
    const auto chroma = [](const std::array<float, 4>& p) { return std::fabs(p[0] - p[1]) + std::fabs(p[2] - p[1]); };
    const auto fringeBefore = pixel(before->pixels, 20, 10);
    const auto fringeAfter = pixel(after->pixels, 20, 10);
    EXPECT_LT(chroma(fringeAfter), chroma(fringeBefore) * 0.7F) << "the fringe loses most of its colour";
    EXPECT_NEAR(chroma(pixel(after->pixels, 56, 10)), chroma(pixel(before->pixels, 56, 10)), 0.02F) << "a purple field away from an edge keeps its colour";
    pipe::Settings invalid = plain;
    invalid.optics.defringe_green = 25.0F;
    EXPECT_FALSE(pipe::render(input, invalid, {}, error));
}

TEST(DevelopPipeline, ManualUprightRotatesAndGuidedUprightStraightensTheDrawnLines) {
    const auto input = tiltedGrid(900, 700, 4.0);
    std::string error;
    pipe::Settings base;
    base.detail = {0, 1, 0, 0, 0, 0};
    const auto plain = pipe::render(input, base, {}, error);
    ASSERT_TRUE(plain) << error;
    pipe::Settings manual = base;
    manual.upright = pipe::Upright::Manual;
    manual.upright_manual.rotate = -4.0F;
    const auto turned = pipe::render(input, manual, {}, error);
    ASSERT_TRUE(turned) << error;
    EXPECT_NE(turned->width, 0U);
    const auto longestRun = [](const pipe::Result& r) {
        std::size_t best = 0;
        for (std::uint32_t y = 40; y < r.height - 40; ++y) {
            std::size_t run = 0;
            for (std::uint32_t x = 40; x < r.width - 40; ++x) {
                run = pixel(r.pixels, x, y)[0] < 0.3F ? run + 1U : 0U;
                best = std::max(best, run);
            }
        }
        return best;
    };
    EXPECT_GT(longestRun(*turned), 2U * longestRun(*plain)) << "rotating by the tilt makes the grid lines run along rows";
    pipe::Settings invalid = manual;
    invalid.upright_manual.scale = 500.0F;
    EXPECT_FALSE(pipe::render(input, invalid, {}, error));
    invalid = base;
    invalid.upright_guides.resize(5);
    EXPECT_FALSE(pipe::render(input, invalid, {}, error));
}
