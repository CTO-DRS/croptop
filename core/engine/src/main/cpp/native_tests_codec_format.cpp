// Host tests for codec and format libraries.
#include "croptop_format.h"
#include "croptop_io_internal.h"
#include "gif_decoder.h"
#include "jni_parallel.h"
#include "recovery_autosave.h"
#include "recovery_journal.h"
#include "tile_store.h"
#include "webp_codec.h"

#include <gtest/gtest.h>
#include <unistd.h>

#include <algorithm>
#include <array>
#include <atomic>
#include <chrono>
#include <cmath>
#include <condition_variable>
#include <cstring>
#include <mutex>
#include <filesystem>
#include <fstream>
#include <limits>
#include <latch>
#include <map>
#include <optional>
#include <random>
#include <thread>
#include <vector>

namespace croptop::detail {
std::uint8_t srgbMipEncodeForTesting(double linear);
std::uint8_t srgbMipEncodeReferenceForTesting(double linear);
}  // namespace croptop::detail

namespace {

using croptop::PixelFormat;
using croptop::TileStore;

TEST(NativeRenderPool, ParallelBatchesCoverEveryTileAndJoinBeforeCacheTrim) {
    croptop::JobPool pool(4, 16, 0);
    std::mutex batch_mutex;
    std::latch first_wave(4);
    std::array<std::atomic<unsigned int>, 17> visits{};
    std::atomic<unsigned int> active{0};
    std::atomic<unsigned int> peak{0};
    std::atomic<unsigned int> completed_batches{0};

    croptop::jni::parallelRenderBatches(
        pool, batch_mutex, 17, 8, croptop::JobPriority::Visible,
        [&](std::size_t index) {
            const auto concurrent = active.fetch_add(1U, std::memory_order_acq_rel) + 1U;
            auto previous = peak.load(std::memory_order_relaxed);
            while (previous < concurrent && !peak.compare_exchange_weak(previous, concurrent, std::memory_order_relaxed)) {}
            visits[index].fetch_add(1U, std::memory_order_relaxed);
            if (index < 4U) {
                first_wave.count_down();
                first_wave.wait();
            }
            active.fetch_sub(1U, std::memory_order_release);
        },
        [&] {
            EXPECT_EQ(active.load(std::memory_order_acquire), 0U);
            completed_batches.fetch_add(1U, std::memory_order_relaxed);
        });

    EXPECT_GE(peak.load(std::memory_order_relaxed), 4U);
    EXPECT_EQ(completed_batches.load(std::memory_order_relaxed), 5U);
    for (const auto& count : visits) EXPECT_EQ(count.load(std::memory_order_relaxed), 1U);
}

TEST(NativeRenderPool, BackgroundBatchRecoversWhenVisibleWorkEvictsQueuedWorker) {
    croptop::JobPool pool(1, 1, 0);
    std::latch blocker_started(1);
    std::latch release_blocker(1);
    const auto blocker = pool.submit(croptop::JobPriority::Interactive, [&](const croptop::JobContext&) {
        blocker_started.count_down();
        release_blocker.wait();
    });
    ASSERT_TRUE(blocker.has_value());
    blocker_started.wait();

    std::mutex batch_mutex;
    std::array<std::atomic<unsigned int>, 2> visits{};
    std::exception_ptr batch_failure;
    std::thread batch([&] {
        try {
            croptop::jni::parallelRenderBatches(
                pool, batch_mutex, visits.size(), visits.size(), croptop::JobPriority::Background,
                [&](std::size_t index) { visits[index].fetch_add(1U, std::memory_order_relaxed); });
        } catch (...) {
            batch_failure = std::current_exception();
        }
    });

    while (pool.pendingCount() == 0U) std::this_thread::yield();
    const auto visible = pool.submit(croptop::JobPriority::Visible, [](const croptop::JobContext&) {});
    release_blocker.count_down();
    batch.join();

    EXPECT_TRUE(visible.has_value());
    EXPECT_EQ(batch_failure, nullptr);
    for (const auto& count : visits) EXPECT_EQ(count.load(std::memory_order_relaxed), 1U);
}

TEST(NativeRenderPool, QueuedVisibleTileRunsBeforeNextBackgroundBatch) {
    croptop::JobPool pool(1, 8, 0);
    std::mutex batch_mutex;
    std::mutex order_mutex;
    std::vector<unsigned int> order;
    std::latch background_started(1);
    std::latch release_background(1);

    std::thread background([&] {
        croptop::jni::parallelRenderBatches(pool, batch_mutex, 2, 1, croptop::JobPriority::Background,
            [&](std::size_t index) {
                if (index == 0U) {
                    background_started.count_down();
                    release_background.wait();
                }
                const std::lock_guard lock(order_mutex);
                order.push_back(100U + static_cast<unsigned int>(index));
            });
    });
    background_started.wait();

    std::thread visible([&] {
        croptop::jni::parallelRenderBatches(pool, batch_mutex, 2, 1, croptop::JobPriority::Visible,
            [&](std::size_t index) {
                const std::lock_guard lock(order_mutex);
                order.push_back(200U + static_cast<unsigned int>(index));
            });
    });
    while (pool.pendingCount() == 0U) std::this_thread::yield();
    release_background.count_down();
    background.join();
    visible.join();

    ASSERT_EQ(order.size(), 4U);
    EXPECT_EQ(order[0], 100U);
    EXPECT_EQ(order[1], 200U);
    std::sort(order.begin(), order.end());
    EXPECT_EQ(order, (std::vector<unsigned int>{100U, 101U, 200U, 201U}));
}

struct DecodedImage {
    croptop::DecodeResult result;
    std::vector<std::uint8_t> rgba;
};

DecodedImage decodeWebpToBuffer(std::span<const std::uint8_t> encoded) {
    DecodedImage image;
    std::uint32_t next_row = 0;
    image.result = croptop::decodeWebp(encoded, [&](std::uint32_t y, std::span<const std::uint8_t> row) {
        if (y != next_row++) return false;
        image.rgba.insert(image.rgba.end(), row.begin(), row.end());
        return true;
    });
    return image;
}

/** A 300×270 RGBA8 store (spans two tile columns/rows) with a gradient and a transparent corner. */
TileStore gradientStore(bool with_alpha) {
    constexpr std::uint32_t kWidth = 300;
    constexpr std::uint32_t kHeight = 270;
    TileStore store(PixelFormat::RGBA8, kWidth, kHeight);
    for (std::uint32_t tile_y = 0; tile_y < 2; ++tile_y) {
        for (std::uint32_t tile_x = 0; tile_x < 2; ++tile_x) {
            croptop::TileBuffer tile(store.tile_byte_size(), 0);
            for (std::uint32_t local_y = 0; local_y < croptop::kTileEdge; ++local_y) {
                for (std::uint32_t local_x = 0; local_x < croptop::kTileEdge; ++local_x) {
                    const auto x = tile_x * croptop::kTileEdge + local_x;
                    const auto y = tile_y * croptop::kTileEdge + local_y;
                    if (x >= kWidth || y >= kHeight) continue;
                    auto* pixel = tile.data() + (static_cast<std::size_t>(local_y) * croptop::kTileEdge + local_x) * 4U;
                    pixel[0] = static_cast<std::uint8_t>(x * 255U / (kWidth - 1U));
                    pixel[1] = static_cast<std::uint8_t>(y * 255U / (kHeight - 1U));
                    pixel[2] = 128;
                    pixel[3] = with_alpha && x < 40U && y < 40U ? 0 : 255;
                }
            }
            EXPECT_TRUE(store.writeTile({static_cast<std::int32_t>(tile_x), static_cast<std::int32_t>(tile_y)}, tile));
        }
    }
    return store;
}

std::vector<std::uint8_t> encodeWebp(const TileStore& store, const croptop::WebpEncodeOptions& options) {
    std::vector<std::uint8_t> encoded;
    const auto result = croptop::encodeWebpToSink(store, [&](std::span<const std::uint8_t> bytes) {
        encoded.insert(encoded.end(), bytes.begin(), bytes.end());
        return true;
    }, options);
    EXPECT_TRUE(result.success) << result.error;
    return encoded;
}

std::vector<std::uint8_t> storePixels(const TileStore& store) {
    std::vector<std::uint8_t> rgba(static_cast<std::size_t>(store.width()) * store.height() * 4U, 0);
    for (std::uint32_t y = 0; y < store.height(); ++y) {
        for (std::uint32_t x = 0; x < store.width(); ++x) {
            const auto tile = store.readTile({static_cast<std::int32_t>(x / croptop::kTileEdge),
                                              static_cast<std::int32_t>(y / croptop::kTileEdge)});
            if (tile == nullptr) continue;
            std::memcpy(rgba.data() + (static_cast<std::size_t>(y) * store.width() + x) * 4U,
                        tile->data() + (static_cast<std::size_t>(y % croptop::kTileEdge) * croptop::kTileEdge +
                                        x % croptop::kTileEdge) * 4U,
                        4U);
        }
    }
    return rgba;
}

/** Returns the payload of the first chunk with this FourCC, or an empty vector. */
std::vector<std::uint8_t> findChunk(const std::vector<std::uint8_t>& webp, const char fourcc[4]) {
    std::size_t offset = 12;
    while (offset + 8U <= webp.size()) {
        std::uint32_t size = 0;
        std::memcpy(&size, webp.data() + offset + 4U, 4U);
        if (std::memcmp(webp.data() + offset, fourcc, 4) == 0) {
            return {webp.begin() + static_cast<std::ptrdiff_t>(offset + 8U),
                    webp.begin() + static_cast<std::ptrdiff_t>(offset + 8U + size)};
        }
        offset += 8U + size + (size & 1U);
    }
    return {};
}

TEST(WebpCodec, LosslessRoundTripIsPixelExactIncludingAlpha) {
    const auto store = gradientStore(true);
    croptop::WebpEncodeOptions options;
    options.lossless = true;
    const auto encoded = encodeWebp(store, options);
    const auto decoded = decodeWebpToBuffer(encoded);
    ASSERT_TRUE(decoded.result.success) << decoded.result.error;
    EXPECT_EQ(decoded.result.raster.width, 300U);
    EXPECT_EQ(decoded.result.raster.height, 270U);
    EXPECT_EQ(decoded.result.raster.format, "webp");
    const auto expected = storePixels(store);
    ASSERT_EQ(decoded.rgba.size(), expected.size());
    for (std::size_t i = 0; i < expected.size(); i += 4) {
        if (expected[i + 3] == 0) {
            EXPECT_EQ(decoded.rgba[i + 3], 0);  // Lossless may rewrite RGB under zero alpha.
            continue;
        }
        ASSERT_EQ(std::memcmp(decoded.rgba.data() + i, expected.data() + i, 4), 0) << "pixel " << i / 4;
    }
}

TEST(WebpCodec, LossyRoundTripStaysClose) {
    const auto store = gradientStore(false);
    croptop::WebpEncodeOptions options;
    options.quality = 95.0f;
    const auto encoded = encodeWebp(store, options);
    const auto decoded = decodeWebpToBuffer(encoded);
    ASSERT_TRUE(decoded.result.success) << decoded.result.error;
    const auto expected = storePixels(store);
    ASSERT_EQ(decoded.rgba.size(), expected.size());
    double error = 0.0;
    for (std::size_t i = 0; i < expected.size(); ++i) error += std::abs(int(decoded.rgba[i]) - int(expected[i]));
    EXPECT_LT(error / static_cast<double>(expected.size()), 3.0);
    EXPECT_EQ(decoded.rgba[3], 255);
}

TEST(WebpCodec, RoundsSixteenBitStoresToEightBit) {
    TileStore store(PixelFormat::RGBA16, 2, 1);
    croptop::TileBuffer tile(store.tile_byte_size(), 0);
    const std::uint16_t samples[8] = {0, 32896, 65535, 65535, 257, 514, 771, 65535};
    std::memcpy(tile.data(), samples, sizeof(samples));
    ASSERT_TRUE(store.writeTile({0, 0}, tile));
    croptop::WebpEncodeOptions options;
    options.lossless = true;
    const auto decoded = decodeWebpToBuffer(encodeWebp(store, options));
    ASSERT_TRUE(decoded.result.success) << decoded.result.error;
    const std::vector<std::uint8_t> expected = {0, 128, 255, 255, 1, 2, 3, 255};
    EXPECT_EQ(decoded.rgba, expected);
}

TEST(WebpCodec, EmbedsIccAndExifInExtendedContainer) {
    const auto store = gradientStore(true);
    const std::vector<std::uint8_t> icc = {'i', 'c', 'c', '!', 1, 2, 3};  // Odd length exercises chunk padding.
    const std::vector<std::uint8_t> exif = {'M', 'M', 0, 42, 0, 0, 0, 8};
    for (const bool lossless : {false, true}) {
        croptop::WebpEncodeOptions options;
        options.lossless = lossless;
        options.icc_profile = icc;
        options.exif_tiff = exif;
        const auto encoded = encodeWebp(store, options);
        ASSERT_GE(encoded.size(), 30U);
        EXPECT_EQ(std::memcmp(encoded.data() + 12, "VP8X", 4), 0);
        std::uint32_t riff_size = 0;
        std::memcpy(&riff_size, encoded.data() + 4, 4);
        EXPECT_EQ(riff_size, encoded.size() - 8U);
        const auto vp8x = findChunk(encoded, "VP8X");
        ASSERT_EQ(vp8x.size(), 10U);
        EXPECT_EQ(vp8x[0] & 0x38, 0x38) << "ICC, alpha and EXIF flags";
        EXPECT_EQ(vp8x[0] & 0x02, 0) << "not animated";
        EXPECT_EQ(vp8x[4] | (vp8x[5] << 8) | (vp8x[6] << 16), 299);
        EXPECT_EQ(vp8x[7] | (vp8x[8] << 8) | (vp8x[9] << 16), 269);
        EXPECT_EQ(findChunk(encoded, "ICCP"), icc);
        EXPECT_EQ(findChunk(encoded, "EXIF"), exif);
        const auto decoded = decodeWebpToBuffer(encoded);
        ASSERT_TRUE(decoded.result.success) << decoded.result.error;
        EXPECT_EQ(decoded.rgba[3], 0);
    }
}

TEST(WebpCodec, RejectsInvalidOptionsAndStores) {
    const auto sink = [](std::span<const std::uint8_t>) { return true; };
    const auto store = gradientStore(false);
    croptop::WebpEncodeOptions bad_quality;
    bad_quality.quality = 101.0f;
    EXPECT_FALSE(croptop::encodeWebpToSink(store, sink, bad_quality).success);
    croptop::WebpEncodeOptions bad_method;
    bad_method.method = 7;
    EXPECT_FALSE(croptop::encodeWebpToSink(store, sink, bad_method).success);
    TileStore float_store(PixelFormat::RGBA32F, 4, 4);
    EXPECT_FALSE(croptop::encodeWebpToSink(float_store, sink).success);
    TileStore too_wide(PixelFormat::RGBA8, 16384, 1);
    EXPECT_FALSE(croptop::encodeWebpToSink(too_wide, sink).success);
    EXPECT_FALSE(croptop::encodeWebpToSink(store, [](std::span<const std::uint8_t>) { return false; }).success);
}

TEST(WebpCodec, DecodeEnforcesLimitsAndRejectsMalformedInput) {
    const auto encoded = encodeWebp(gradientStore(false), {});
    const auto accept = [](std::uint32_t, std::span<const std::uint8_t>) { return true; };
    EXPECT_FALSE(croptop::decodeWebp(encoded, accept, 299).success);
    EXPECT_FALSE(croptop::decodeWebp(encoded, accept, 16383, 300ULL * 270ULL - 1ULL).success);
    EXPECT_FALSE(croptop::decodeWebp(encoded, [](std::uint32_t, std::span<const std::uint8_t>) { return false; })
                     .success);
    EXPECT_FALSE(croptop::decodeWebp({}, accept).success);
    const std::vector<std::uint8_t> truncated(encoded.begin(), encoded.begin() + static_cast<std::ptrdiff_t>(encoded.size() / 2));
    EXPECT_FALSE(croptop::decodeWebp(truncated, accept).success);

    std::mt19937 random(1234);
    for (int iteration = 0; iteration < 512; ++iteration) {
        auto mutated = encoded;
        for (int flips = 0; flips < 8; ++flips) {
            mutated[random() % mutated.size()] = static_cast<std::uint8_t>(random());
        }
        (void)croptop::decodeWebp(mutated, accept);  // Must not crash or trip sanitizers.
    }
}

TEST(WebpCodec, RejectsAnimatedWebp) {
    // Minimal VP8X header with the animation flag set; features parsing is enough to reject it.
    std::vector<std::uint8_t> animated = {'R', 'I', 'F', 'F', 30, 0, 0, 0, 'W', 'E', 'B', 'P',
                                          'V', 'P', '8', 'X', 10, 0, 0, 0, 0x02, 0, 0, 0,
                                          0, 0, 0, 0, 0, 0, 'A', 'N', 'I', 'M', 6, 0, 0, 0,
                                          0, 0, 0, 0, 0, 0};
    const auto result = croptop::decodeWebp(animated, [](std::uint32_t, std::span<const std::uint8_t>) { return true; });
    EXPECT_FALSE(result.success);
    EXPECT_EQ(result.error, "animated WebP is not supported");
}

// ---- GIF ----------------------------------------------------------------------------------------

struct GifFrameSpec {
    std::uint16_t screen_width = 0;
    std::uint16_t screen_height = 0;
    std::uint16_t left = 0;
    std::uint16_t top = 0;
    std::uint16_t width = 0;
    std::uint16_t height = 0;
    std::vector<std::uint8_t> indices;  // Display order; the encoder interlaces when asked.
    std::vector<std::uint8_t> palette;  // RGB triples, power-of-two entry count >= 2.
    bool local_palette = false;
    bool interlaced = false;
    int transparent = -1;
    std::uint8_t min_code_size = 8;
    bool clear_when_full = true;
};

void putLittle16(std::vector<std::uint8_t>& out, std::uint16_t value) {
    out.push_back(static_cast<std::uint8_t>(value & 0xFFU));
    out.push_back(static_cast<std::uint8_t>(value >> 8U));
}

std::uint8_t paletteBits(const std::vector<std::uint8_t>& palette) {
    std::uint8_t bits = 0;
    while ((2U << bits) < palette.size() / 3U) ++bits;
    return bits;
}

/** Straightforward GIF LZW encoder used only to produce test streams. */
std::vector<std::uint8_t> lzwEncode(const std::vector<std::uint8_t>& indices, std::uint32_t min_code_size,
                                    bool clear_when_full) {
    const std::uint32_t clear = 1U << min_code_size;
    std::vector<std::uint8_t> bytes;
    std::uint32_t buffer = 0;
    std::uint32_t count = 0;
    std::uint32_t code_size = min_code_size + 1U;
    const auto emit = [&](std::uint32_t code) {
        buffer |= code << count;
        count += code_size;
        while (count >= 8U) {
            bytes.push_back(static_cast<std::uint8_t>(buffer & 0xFFU));
            buffer >>= 8U;
            count -= 8U;
        }
    };
    std::map<std::pair<std::uint32_t, std::uint8_t>, std::uint32_t> table;
    std::uint32_t next_code = clear + 2U;
    emit(clear);
    std::optional<std::uint32_t> current;
    for (const auto index : indices) {
        if (!current) {
            current = index;
            continue;
        }
        const auto found = table.find({*current, index});
        if (found != table.end()) {
            current = found->second;
            continue;
        }
        emit(*current);
        if (next_code < 4096U) {
            if (next_code == (1U << code_size) && code_size < 12U) ++code_size;
            table[{*current, index}] = next_code++;
        }
        if (next_code >= 4093U && clear_when_full) {
            emit(clear);
            table.clear();
            next_code = clear + 2U;
            code_size = min_code_size + 1U;
        }
        current = index;
    }
    if (current) emit(*current);
    emit(clear + 1U);
    if (count > 0U) bytes.push_back(static_cast<std::uint8_t>(buffer & 0xFFU));
    return bytes;
}

std::vector<std::uint8_t> makeGif(const GifFrameSpec& spec) {
    std::vector<std::uint8_t> out = {'G', 'I', 'F', '8', '9', 'a'};
    putLittle16(out, spec.screen_width);
    putLittle16(out, spec.screen_height);
    const auto bits = paletteBits(spec.palette);
    out.push_back(spec.local_palette ? 0x00 : static_cast<std::uint8_t>(0x80U | bits));
    out.push_back(0);
    out.push_back(0);
    if (!spec.local_palette) out.insert(out.end(), spec.palette.begin(), spec.palette.end());
    // An application extension to exercise extension skipping.
    out.insert(out.end(), {0x21, 0xFF, 11, 'N', 'E', 'T', 'S', 'C', 'A', 'P', 'E', '2', '.', '0', 3, 1, 0, 0, 0});
    if (spec.transparent >= 0) {
        out.insert(out.end(), {0x21, 0xF9, 4, 0x01, 0, 0, static_cast<std::uint8_t>(spec.transparent), 0});
    }
    out.push_back(0x2C);
    putLittle16(out, spec.left);
    putLittle16(out, spec.top);
    putLittle16(out, spec.width);
    putLittle16(out, spec.height);
    std::uint8_t flags = spec.interlaced ? 0x40 : 0x00;
    if (spec.local_palette) flags |= static_cast<std::uint8_t>(0x80U | bits);
    out.push_back(flags);
    if (spec.local_palette) out.insert(out.end(), spec.palette.begin(), spec.palette.end());
    std::vector<std::uint8_t> stored = spec.indices;
    if (spec.interlaced) {
        stored.clear();
        for (const auto [start, step] : {std::pair{0U, 8U}, {4U, 8U}, {2U, 4U}, {1U, 2U}}) {
            for (std::uint32_t y = start; y < spec.height; y += step) {
                stored.insert(stored.end(), spec.indices.begin() + static_cast<std::ptrdiff_t>(y * spec.width),
                              spec.indices.begin() + static_cast<std::ptrdiff_t>((y + 1U) * spec.width));
            }
        }
    }
    out.push_back(spec.min_code_size);
    const auto data = lzwEncode(stored, spec.min_code_size, spec.clear_when_full);
    for (std::size_t offset = 0; offset < data.size(); offset += 255U) {
        const auto size = std::min<std::size_t>(255U, data.size() - offset);
        out.push_back(static_cast<std::uint8_t>(size));
        out.insert(out.end(), data.begin() + static_cast<std::ptrdiff_t>(offset),
                   data.begin() + static_cast<std::ptrdiff_t>(offset + size));
    }
    out.push_back(0);
    out.push_back(0x3B);
    return out;
}

std::vector<std::uint8_t> grayPalette(std::uint32_t entries) {
    std::vector<std::uint8_t> palette;
    for (std::uint32_t i = 0; i < entries; ++i) {
        const auto value = static_cast<std::uint8_t>(i * 255U / (entries - 1U));
        palette.insert(palette.end(), {value, static_cast<std::uint8_t>(255U - value), static_cast<std::uint8_t>(i)});
    }
    return palette;
}

DecodedImage decodeGifToBuffer(std::span<const std::uint8_t> encoded, std::uint32_t max_dimension = 32768,
                               std::uint64_t max_pixels = 500'000'000) {
    DecodedImage image;
    std::uint32_t next_row = 0;
    image.result = croptop::decodeGif(encoded, [&](std::uint32_t y, std::span<const std::uint8_t> row) {
        if (y != next_row++) return false;
        image.rgba.insert(image.rgba.end(), row.begin(), row.end());
        return true;
    }, max_dimension, max_pixels);
    return image;
}

/** Expected RGBA for a spec: frame placed on a transparent canvas. */
std::vector<std::uint8_t> expectedGifPixels(const GifFrameSpec& spec) {
    std::vector<std::uint8_t> rgba(static_cast<std::size_t>(spec.screen_width) * spec.screen_height * 4U, 0);
    for (std::uint32_t y = 0; y < spec.height; ++y) {
        for (std::uint32_t x = 0; x < spec.width; ++x) {
            const auto cx = spec.left + x;
            const auto cy = spec.top + y;
            if (cx >= spec.screen_width || cy >= spec.screen_height) continue;
            const auto index = spec.indices[static_cast<std::size_t>(y) * spec.width + x];
            if (static_cast<int>(index) == spec.transparent) continue;
            auto* pixel = rgba.data() + (static_cast<std::size_t>(cy) * spec.screen_width + cx) * 4U;
            std::memcpy(pixel, spec.palette.data() + static_cast<std::size_t>(index) * 3U, 3U);
            pixel[3] = 255;
        }
    }
    return rgba;
}

GifFrameSpec noisySpec(std::uint16_t width, std::uint16_t height, std::uint32_t entries, std::uint32_t seed) {
    GifFrameSpec spec;
    spec.screen_width = width;
    spec.screen_height = height;
    spec.width = width;
    spec.height = height;
    spec.palette = grayPalette(entries);
    std::mt19937 random(seed);
    spec.indices.resize(static_cast<std::size_t>(width) * height);
    // Runs of repeated values plus noise produce both long strings and many table entries.
    for (std::size_t i = 0; i < spec.indices.size(); ++i) {
        spec.indices[i] = static_cast<std::uint8_t>((random() % 4U == 0U ? random() : i / 7U) % entries);
    }
    return spec;
}

TEST(GifDecoder, DecodesGlobalPaletteFrameExactly) {
    const auto spec = noisySpec(300, 270, 256, 7);
    const auto decoded = decodeGifToBuffer(makeGif(spec));
    ASSERT_TRUE(decoded.result.success) << decoded.result.error;
    EXPECT_EQ(decoded.result.raster.width, 300U);
    EXPECT_EQ(decoded.result.raster.height, 270U);
    EXPECT_EQ(decoded.result.raster.format, "gif");
    EXPECT_EQ(decoded.rgba, expectedGifPixels(spec));
}

TEST(GifDecoder, HandlesSmallCodeSizesAndTableResets) {
    for (const std::uint8_t min_code : {2, 4, 8}) {
        for (const bool clear_when_full : {true, false}) {
            auto spec = noisySpec(320, 200, 1U << min_code, 100U + min_code);
            spec.min_code_size = min_code;
            spec.clear_when_full = clear_when_full;
            const auto decoded = decodeGifToBuffer(makeGif(spec));
            ASSERT_TRUE(decoded.result.success) << decoded.result.error << " code " << int(min_code);
            EXPECT_EQ(decoded.rgba, expectedGifPixels(spec)) << "code " << int(min_code) << " clear " << clear_when_full;
        }
    }
}

TEST(GifDecoder, HonorsInterlaceLocalPaletteTransparencyAndOffset) {
    auto spec = noisySpec(37, 29, 16, 3);
    spec.screen_width = 50;
    spec.screen_height = 40;
    spec.left = 5;
    spec.top = 9;
    spec.local_palette = true;
    spec.interlaced = true;
    spec.transparent = 3;
    const auto decoded = decodeGifToBuffer(makeGif(spec));
    ASSERT_TRUE(decoded.result.success) << decoded.result.error;
    EXPECT_EQ(decoded.result.raster.width, 50U);
    EXPECT_EQ(decoded.rgba, expectedGifPixels(spec));
    EXPECT_EQ(decoded.rgba[3], 0) << "canvas outside the frame is transparent";
}

TEST(GifDecoder, ClipsFramesThatExtendPastTheScreen) {
    auto spec = noisySpec(20, 20, 4, 9);
    spec.screen_width = 16;
    spec.screen_height = 12;
    spec.left = 4;
    spec.top = 2;
    const auto decoded = decodeGifToBuffer(makeGif(spec));
    ASSERT_TRUE(decoded.result.success) << decoded.result.error;
    EXPECT_EQ(decoded.rgba, expectedGifPixels(spec));
}

TEST(GifDecoder, DecodesClassicTransparentPixel) {
    const std::vector<std::uint8_t> gif = {'G', 'I', 'F', '8', '9', 'a', 1, 0, 1, 0, 0x80, 0, 0, 0, 0, 0,
                                           0xFF, 0xFF, 0xFF, 0x21, 0xF9, 4, 1, 0, 0, 0, 0, 0x2C, 0, 0, 0,
                                           0, 1, 0, 1, 0, 0, 2, 2, 0x44, 0x01, 0, 0x3B};
    const auto decoded = decodeGifToBuffer(gif);
    ASSERT_TRUE(decoded.result.success) << decoded.result.error;
    EXPECT_EQ(decoded.rgba, (std::vector<std::uint8_t>{0, 0, 0, 0}));
}

TEST(GifDecoder, RejectsMalformedAndOverLimitStreams) {
    const auto spec = noisySpec(64, 48, 16, 5);
    const auto gif = makeGif(spec);
    EXPECT_TRUE(decodeGifToBuffer(gif).result.success);
    EXPECT_FALSE(decodeGifToBuffer(gif, 63).result.success);
    EXPECT_FALSE(decodeGifToBuffer(gif, 32768, 64ULL * 48ULL - 1ULL).result.success);
    EXPECT_FALSE(croptop::decodeGif(gif, [](std::uint32_t, std::span<const std::uint8_t>) { return false; }).success);
    EXPECT_FALSE(decodeGifToBuffer({}).result.success);
    for (std::size_t cut = 1; cut < gif.size() - 2U; cut += 7) {
        const std::vector<std::uint8_t> truncated(gif.begin(), gif.begin() + static_cast<std::ptrdiff_t>(cut));
        EXPECT_FALSE(decodeGifToBuffer(truncated).result.success) << "cut at " << cut;
    }

    auto no_palette = spec;
    auto bytes = makeGif(no_palette);
    bytes[10] = 0;  // Drop the global palette flag; the palette bytes now parse as blocks.
    EXPECT_FALSE(decodeGifToBuffer(bytes).result.success);

    auto small_palette = spec;
    small_palette.palette = grayPalette(4);  // Indices go up to 15.
    EXPECT_EQ(decodeGifToBuffer(makeGif(small_palette)).result.error, "GIF pixel index is outside the palette");

    std::mt19937 random(99);
    for (int iteration = 0; iteration < 2000; ++iteration) {
        auto mutated = gif;
        for (int flips = 0; flips < 6; ++flips) mutated[random() % mutated.size()] = static_cast<std::uint8_t>(random());
        (void)decodeGifToBuffer(mutated);  // Must not crash or trip sanitizers.
    }
}

TEST(TileDecode, WebpAndGifDecodeStraightIntoTiles) {
    const auto source = gradientStore(true);
    croptop::WebpEncodeOptions options;
    options.lossless = true;
    const auto webp = encodeWebp(source, options);
    TileStore webp_tiles(PixelFormat::RGBA8, source.width(), source.height());
    const auto webp_result = croptop::decodeWebpToTiles(webp, webp_tiles);
    ASSERT_TRUE(webp_result.success) << webp_result.error;
    const auto expected = storePixels(source);
    const auto actual = storePixels(webp_tiles);
    for (std::size_t i = 0; i < expected.size(); i += 4) {
        if (expected[i + 3] == 0) continue;
        ASSERT_EQ(std::memcmp(actual.data() + i, expected.data() + i, 4), 0) << i / 4;
    }

    auto spec = noisySpec(300, 270, 64, 11);
    spec.transparent = 2;
    TileStore gif_tiles(PixelFormat::RGBA8, 300, 270);
    const auto gif_result = croptop::decodeGifToTiles(makeGif(spec), gif_tiles);
    ASSERT_TRUE(gif_result.success) << gif_result.error;
    EXPECT_EQ(storePixels(gif_tiles), expectedGifPixels(spec));
}

TEST(TileDecode, RejectsMismatchedDestinationStores) {
    const auto webp = encodeWebp(gradientStore(false), {});
    TileStore wrong_size(PixelFormat::RGBA8, 299, 270);
    EXPECT_FALSE(croptop::decodeWebpToTiles(webp, wrong_size).success);
    TileStore sixteen(PixelFormat::RGBA16, 300, 270);
    EXPECT_FALSE(croptop::decodeWebpToTiles(webp, sixteen).success);
    const auto gif = makeGif(noisySpec(40, 30, 4, 1));
    TileStore gif_wrong(PixelFormat::RGBA8, 40, 31);
    EXPECT_FALSE(croptop::decodeGifToTiles(gif, gif_wrong).success);
    TileStore gif_float(PixelFormat::RGBA32F, 40, 30);
    EXPECT_FALSE(croptop::decodeGifToTiles(gif, gif_float).success);
}

TEST(WorkingMemory, RejectsFramesOverTheWorkingCapBeforeAllocating) {
    EXPECT_EQ(croptop::kStillImageMaxWorkingBytes, 256ULL * 1024ULL * 1024ULL);
    const auto accept = [](std::uint32_t, std::span<const std::uint8_t>) { return true; };
    const auto store = gradientStore(false);
    const auto webp = encodeWebp(store, {});
    constexpr std::uint64_t kFrameBytes = 300ULL * 270ULL * 4ULL;
    EXPECT_TRUE(croptop::decodeWebp(webp, accept, 16383, 500'000'000, kFrameBytes).success);
    EXPECT_EQ(croptop::decodeWebp(webp, accept, 16383, 500'000'000, kFrameBytes - 1U).error,
              "WebP frame exceeds the working memory limit");

    croptop::WebpEncodeOptions capped;
    const auto sink = [](std::span<const std::uint8_t>) { return true; };
    capped.max_working_bytes = croptop::estimateWebpEncodeBytes(300, 270, false);
    EXPECT_TRUE(croptop::encodeWebpToSink(store, sink, capped).success);
    capped.max_working_bytes -= 1U;
    EXPECT_EQ(croptop::encodeWebpToSink(store, sink, capped).error, "WebP encode exceeds the working memory limit");
    capped.lossless = true;  // Lossless needs a larger budget than lossy for the same size.
    capped.max_working_bytes = croptop::estimateWebpEncodeBytes(300, 270, false);
    EXPECT_FALSE(croptop::encodeWebpToSink(store, sink, capped).success);
    // The default export budget admits a 24 MP photo in either mode.
    EXPECT_LE(croptop::estimateWebpEncodeBytes(6000, 4000, true), croptop::kWebpEncodeMaxWorkingBytes);

    const auto gif = makeGif(noisySpec(64, 48, 16, 5));
    EXPECT_TRUE(croptop::decodeGif(gif, accept, 32768, 500'000'000, 64U * 48U).success);
    EXPECT_EQ(croptop::decodeGif(gif, accept, 32768, 500'000'000, 64U * 48U - 1U).error,
              "GIF frame exceeds the working memory limit");

    // A header-only GIF claiming a 20000² frame (400 MB index plane) passes max_pixels but not the
    // default working cap, so it is rejected before any image data is read.
    std::vector<std::uint8_t> huge = {'G', 'I', 'F', '8', '9', 'a', 0x20, 0x4E, 0x20, 0x4E, 0x80, 0, 0,
                                      0, 0, 0, 255, 255, 255, 0x2C, 0, 0, 0, 0, 0x20, 0x4E, 0x20, 0x4E, 0, 8};
    EXPECT_EQ(croptop::decodeGif(huge, accept).error, "GIF frame exceeds the working memory limit");
}

TEST(TileDecode, FailedDecodesLeaveTheDestinationUntouched) {
    const auto webp = encodeWebp(gradientStore(false), {});
    auto corrupt_webp = webp;
    for (std::size_t i = corrupt_webp.size() / 2; i < corrupt_webp.size(); ++i) corrupt_webp[i] ^= 0x5AU;
    TileStore webp_store(PixelFormat::RGBA8, 300, 270);
    EXPECT_FALSE(croptop::decodeWebpToTiles(corrupt_webp, webp_store).success);
    EXPECT_FALSE(croptop::decodeWebpToTiles(webp, webp_store, 16383, 500'000'000, 1024).success);
    EXPECT_EQ(webp_store.tile_count(), 0U);

    const auto gif = makeGif(noisySpec(300, 270, 64, 2));
    auto truncated_gif = gif;
    truncated_gif.resize(gif.size() * 3 / 4);
    TileStore gif_store(PixelFormat::RGBA8, 300, 270);
    EXPECT_FALSE(croptop::decodeGifToTiles(truncated_gif, gif_store).success);
    EXPECT_FALSE(croptop::decodeGifToTiles(gif, gif_store, 32768, 500'000'000, 1024).success);
    EXPECT_EQ(gif_store.tile_count(), 0U);

    TileStore mismatched(PixelFormat::RGBA8, 301, 270);
    EXPECT_EQ(croptop::decodeGifToTiles(gif, mismatched).error, "GIF dimensions do not match tile store");
    EXPECT_EQ(croptop::decodeWebpToTiles(webp, mismatched).error, "WebP dimensions do not match tile store");
    EXPECT_EQ(mismatched.tile_count(), 0U);
}

TEST(TileDecode, WebpToTilesAppliesExifOrientation) {
    // 3×2 source with distinct opaque pixels p(x, y) = (10x + y, 0, 0).
    TileStore source(PixelFormat::RGBA8, 3, 2);
    croptop::TileBuffer tile(source.tile_byte_size(), 0);
    for (std::uint32_t y = 0; y < 2; ++y) {
        for (std::uint32_t x = 0; x < 3; ++x) {
            auto* pixel = tile.data() + (static_cast<std::size_t>(y) * croptop::kTileEdge + x) * 4U;
            pixel[0] = static_cast<std::uint8_t>(10U * x + y);
            pixel[3] = 255;
        }
    }
    ASSERT_TRUE(source.writeTile({0, 0}, tile));
    croptop::WebpEncodeOptions options;
    options.lossless = true;
    const auto webp = encodeWebp(source, options);

    // Destination position of source pixel (x, y) per EXIF orientation (w = 3, h = 2).
    const auto oriented = [](int orientation, std::uint32_t x, std::uint32_t y) -> std::pair<std::uint32_t, std::uint32_t> {
        switch (orientation) {
            case 2: return {2U - x, y};
            case 3: return {2U - x, 1U - y};
            case 4: return {x, 1U - y};
            case 5: return {y, x};
            case 6: return {1U - y, x};
            case 7: return {1U - y, 2U - x};
            case 8: return {y, 2U - x};
            default: return {x, y};
        }
    };
    for (int orientation = 1; orientation <= 8; ++orientation) {
        const bool swap = orientation >= 5;
        TileStore destination(PixelFormat::RGBA8, swap ? 2 : 3, swap ? 3 : 2);
        const auto result = croptop::decodeWebpToTiles(webp, destination, 16383, 500'000'000,
                                                       croptop::kStillImageMaxWorkingBytes,
                                                       static_cast<std::uint8_t>(orientation));
        ASSERT_TRUE(result.success) << orientation << ": " << result.error;
        const auto pixels = storePixels(destination);
        for (std::uint32_t y = 0; y < 2; ++y) {
            for (std::uint32_t x = 0; x < 3; ++x) {
                const auto [dx, dy] = oriented(orientation, x, y);
                EXPECT_EQ(pixels[(static_cast<std::size_t>(dy) * destination.width() + dx) * 4U], 10U * x + y)
                    << "orientation " << orientation << " source (" << x << ", " << y << ")";
            }
        }
        TileStore unswapped(PixelFormat::RGBA8, 3, 2);
        EXPECT_EQ(croptop::decodeWebpToTiles(webp, unswapped, 16383, 500'000'000, croptop::kStillImageMaxWorkingBytes,
                                             static_cast<std::uint8_t>(orientation)).success, !swap);
    }
    TileStore any(PixelFormat::RGBA8, 3, 2);
    EXPECT_FALSE(croptop::decodeWebpToTiles(webp, any, 16383, 500'000'000, croptop::kStillImageMaxWorkingBytes, 0).success);
    EXPECT_FALSE(croptop::decodeWebpToTiles(webp, any, 16383, 500'000'000, croptop::kStillImageMaxWorkingBytes, 9).success);
    EXPECT_EQ(any.tile_count(), 0U);
}

// ---- .croptop -----------------------------------------------------------------------------------

TileStore randomStore(PixelFormat format, std::uint32_t width, std::uint32_t height, std::uint32_t seed,
                      bool sparse) {
    TileStore store(format, width, height);
    std::mt19937 random(seed);
    const auto columns = 1U + (width - 1U) / croptop::kTileEdge;
    const auto rows = 1U + (height - 1U) / croptop::kTileEdge;
    for (std::uint32_t y = 0; y < rows; ++y) {
        for (std::uint32_t x = 0; x < columns; ++x) {
            if (sparse && (x + y) % 2U == 1U) continue;
            croptop::TileBuffer tile(store.tile_byte_size(), 0);
            // Smooth ramp plus low-bit noise: compressible but not trivially so.
            for (std::size_t i = 0; i < tile.size(); ++i) {
                tile[i] = static_cast<std::uint8_t>((i / 64U + x * 31U + y * 17U) ^ (random() & 0x07U));
            }
            EXPECT_TRUE(store.writeTile({static_cast<std::int32_t>(x), static_cast<std::int32_t>(y)}, tile));
        }
    }
    return store;
}

bool sameTiles(const TileStore& a, const TileStore& b) {
    if (a.format() != b.format() || a.width() != b.width() || a.height() != b.height()) return false;
    const auto columns = 1U + (a.width() - 1U) / croptop::kTileEdge;
    const auto rows = 1U + (a.height() - 1U) / croptop::kTileEdge;
    const croptop::TileBuffer zero(a.tile_byte_size(), 0);
    for (std::uint32_t y = 0; y < rows; ++y) {
        for (std::uint32_t x = 0; x < columns; ++x) {
            const croptop::TileCoordinate c{static_cast<std::int32_t>(x), static_cast<std::int32_t>(y)};
            const auto ta = a.readTile(c);
            const auto tb = b.readTile(c);
            if ((ta ? *ta : zero) != (tb ? *tb : zero)) return false;
        }
    }
    return true;
}

std::vector<std::uint8_t> saveToBytes(const croptop::CroptopSaveInput& input, std::string* error = nullptr) {
    std::vector<std::uint8_t> bytes;
    const auto result = croptop::saveCroptop(input, [&](std::span<const std::uint8_t> chunk) {
        bytes.insert(bytes.end(), chunk.begin(), chunk.end());
        return true;
    });
    if (error != nullptr) *error = result.error;
    if (!result.success) bytes.clear();
    return bytes;
}

croptop::CroptopReadSource memorySource(const std::vector<std::uint8_t>& bytes) {
    return {bytes.size(), [&bytes](std::uint64_t offset, std::span<std::uint8_t> out) {
                if (offset > bytes.size() || out.size() > bytes.size() - offset) return false;
                std::memcpy(out.data(), bytes.data() + offset, out.size());
                return true;
            }};
}

std::span<const std::uint8_t> textBytes(std::string_view text) {
    return {reinterpret_cast<const std::uint8_t*>(text.data()), text.size()};
}

TEST(CroptopFormat, RoundTripsEveryPixelFormatExactly) {
    const std::vector<std::pair<std::string, TileStore>> layers = {
        {"a-rgba8", randomStore(PixelFormat::RGBA8, 600, 300, 1, false)},
        {"b-rgba16", randomStore(PixelFormat::RGBA16, 300, 520, 2, true)},
        {"c-rgba32f", randomStore(PixelFormat::RGBA32F, 257, 257, 3, false)},
        {"d-a8", randomStore(PixelFormat::A8, 1000, 90, 4, true)},
        {"e-a16", randomStore(PixelFormat::A16, 90, 1000, 5, false)},
        {"f-empty", TileStore(PixelFormat::RGBA8, 4000, 3000)},
    };
    croptop::CroptopSaveInput input;
    const std::string manifest = R"({"formatVersion":1,"future":{"keep":true}})";
    input.manifest_json = textBytes(manifest);
    const std::vector<std::uint8_t> icc = {1, 2, 3, 4, 5};
    input.working_icc = icc;
    for (const auto& [uuid, store] : layers) input.layers.push_back({uuid, &store});
    const auto bytes = saveToBytes(input);
    ASSERT_FALSE(bytes.empty());

    std::string error;
    const auto archive = croptop::CroptopArchive::open(memorySource(bytes), error);
    ASSERT_NE(archive, nullptr) << error;
    std::vector<std::uint8_t> read_manifest;
    ASSERT_TRUE(archive->readManifest(read_manifest, error)) << error;
    EXPECT_EQ(std::string(read_manifest.begin(), read_manifest.end()), manifest);
    std::vector<std::uint8_t> read_icc;
    ASSERT_TRUE(archive->readWorkingIcc(read_icc, error)) << error;
    EXPECT_EQ(read_icc, icc);
    EXPECT_EQ(archive->layerUuids(),
              (std::vector<std::string>{"a-rgba8", "b-rgba16", "c-rgba32f", "d-a8", "e-a16", "f-empty"}));
    for (const auto& [uuid, original] : layers) {
        croptop::CroptopLayerInfo info;
        ASSERT_TRUE(archive->layerInfo(uuid, info, error)) << error;
        EXPECT_EQ(info.format, original.format());
        TileStore loaded(info.format, info.width, info.height);
        ASSERT_TRUE(archive->loadLayer(uuid, loaded, error)) << uuid << ": " << error;
        EXPECT_TRUE(sameTiles(original, loaded)) << uuid;
        EXPECT_EQ(loaded.tile_count(), original.tile_count()) << uuid << " keeps sparse tiles sparse";
    }
    std::vector<std::uint8_t> preview;
    EXPECT_FALSE(archive->readPreview("thumb.webp", preview, error)) << "no composite, no previews";
}

TEST(CroptopFormat, SavesAreDeterministicAndSkipAllZeroTiles) {
    auto store = randomStore(PixelFormat::RGBA8, 512, 512, 9, false);
    ASSERT_TRUE(store.writeTile({1, 1}, croptop::TileBuffer(store.tile_byte_size(), 0)));
    croptop::CroptopSaveInput input;
    input.manifest_json = textBytes("{}");
    input.layers.push_back({"layer", &store});
    input.worker_threads = 1;
    const auto single = saveToBytes(input);
    input.worker_threads = 8;
    EXPECT_EQ(saveToBytes(input), single);
    std::string error;
    const auto archive = croptop::CroptopArchive::open(memorySource(single), error);
    ASSERT_NE(archive, nullptr) << error;
    TileStore loaded(PixelFormat::RGBA8, 512, 512);
    ASSERT_TRUE(archive->loadLayer("layer", loaded, error)) << error;
    EXPECT_EQ(loaded.tile_count(), 3U);
    EXPECT_TRUE(sameTiles(store, loaded));
}

TEST(CroptopFormat, WritesPreviewsAndZip64Records) {
    const auto composite = gradientStore(true);  // 300×270
    auto big = randomStore(PixelFormat::RGBA8, 5000, 700, 12, true);
    croptop::CroptopSaveInput input;
    input.manifest_json = textBytes("{}");
    input.layers.push_back({"big", &big});
    input.composite = &composite;
    input.force_zip64 = true;
    const auto bytes = saveToBytes(input);
    ASSERT_FALSE(bytes.empty());
    // EOCD fields are saturated and the ZIP64 locator precedes it.
    const auto end = bytes.size() - 22U;
    EXPECT_EQ(bytes[end + 10], 0xFF);
    EXPECT_EQ(std::memcmp(bytes.data() + end - 20U, "PK\x06\x07", 4), 0);

    std::string error;
    const auto archive = croptop::CroptopArchive::open(memorySource(bytes), error);
    ASSERT_NE(archive, nullptr) << error;
    TileStore loaded(PixelFormat::RGBA8, 5000, 700);
    ASSERT_TRUE(archive->loadLayer("big", loaded, error)) << error;
    EXPECT_TRUE(sameTiles(big, loaded));
    for (const auto* name : {"thumb.webp", "preview.webp"}) {
        std::vector<std::uint8_t> webp;
        ASSERT_TRUE(archive->readPreview(name, webp, error)) << error;
        const auto decoded = decodeWebpToBuffer(webp);
        ASSERT_TRUE(decoded.result.success) << decoded.result.error;
        EXPECT_EQ(decoded.result.raster.width, 300U) << "small composite keeps its size";
    }
}

TEST(CroptopFormat, PreviewDownscalesLargeCompositesByPowersOfTwo) {
    const auto composite = randomStore(PixelFormat::RGBA8, 2100, 300, 13, false);
    croptop::CroptopSaveInput input;
    input.manifest_json = textBytes("{}");
    input.composite = &composite;
    const auto bytes = saveToBytes(input);
    std::string error;
    const auto archive = croptop::CroptopArchive::open(memorySource(bytes), error);
    ASSERT_NE(archive, nullptr) << error;
    std::vector<std::uint8_t> thumb;
    std::vector<std::uint8_t> preview;
    ASSERT_TRUE(archive->readPreview("thumb.webp", thumb, error));
    ASSERT_TRUE(archive->readPreview("preview.webp", preview, error));
    const auto thumb_image = decodeWebpToBuffer(thumb);
    const auto preview_image = decodeWebpToBuffer(preview);
    EXPECT_EQ(thumb_image.result.raster.width, 263U);  // ceil(2100 / 8)
    EXPECT_EQ(thumb_image.result.raster.height, 38U);  // ceil(300 / 8)
    EXPECT_EQ(preview_image.result.raster.width, 1050U);  // 2100 / 2
}

TEST(CroptopFormat, RejectsInvalidSaveInput) {
    TileStore store(PixelFormat::RGBA8, 10, 10);
    croptop::CroptopSaveInput input;
    std::string error;
    EXPECT_TRUE(saveToBytes(input, &error).empty());  // No manifest.
    input.manifest_json = textBytes("{}");
    input.layers = {{"../escape", &store}};
    EXPECT_TRUE(saveToBytes(input, &error).empty());
    EXPECT_EQ(error, "invalid layer uuid");
    input.layers = {{"same", &store}, {"same", &store}};
    EXPECT_TRUE(saveToBytes(input, &error).empty());
    input.layers = {{"ok", nullptr}};
    EXPECT_TRUE(saveToBytes(input, &error).empty());
    TileStore too_wide(PixelFormat::RGBA8, 70000, 1);  // Wider than the reader's 65535 limit.
    input.layers = {{"wide", &too_wide}};
    EXPECT_TRUE(saveToBytes(input, &error).empty());
    EXPECT_EQ(error, "layer wide is larger than the format allows");
    input.layers = {{"ok", &store}};
    input.zstd_level = 0;
    EXPECT_TRUE(saveToBytes(input, &error).empty());
    input.zstd_level = 3;
    std::size_t calls = 0;
    const auto failing = croptop::saveCroptop(input, [&](std::span<const std::uint8_t>) { return ++calls < 3; });
    EXPECT_FALSE(failing.success);
    EXPECT_EQ(failing.error, "croptop sink failed");
}

TEST(CroptopFormat, EnforcesEntryLimitWhileCollectingTiles) {
    TileStore store(PixelFormat::RGBA8, 1024, 256);
    croptop::TileBuffer tile(store.tile_byte_size(), 0x7f);
    ASSERT_TRUE(store.writeTile({0, 0}, tile));
    ASSERT_TRUE(store.writeTile({1, 0}, tile));
    croptop::CroptopSaveInput input;
    input.manifest_json = textBytes("{}");
    input.layers.push_back({"limit-test", &store});
    input.max_entries = 4;  // mimetype + manifest + layer info + at most one tile.
    std::size_t sink_calls = 0;
    const auto result = croptop::saveCroptop(input, [&](std::span<const std::uint8_t>) {
        ++sink_calls;
        return true;
    });
    EXPECT_FALSE(result.success);
    EXPECT_NE(result.error.find("more tiles"), std::string::npos);
    EXPECT_EQ(sink_calls, 0U);
}

TEST(CroptopFormat, RejectsHostileLocalHeaderLengthsBySubtraction) {
    croptop::CroptopSaveInput input;
    const std::string manifest = "{}";
    input.manifest_json = textBytes(manifest);
    input.force_zip64 = true;
    const auto bytes = saveToBytes(input);
    ASSERT_FALSE(bytes.empty());
    const auto zip64_signature = std::array<std::uint8_t, 4>{0x50, 0x4b, 0x06, 0x06};
    const auto zip64 = std::search(bytes.begin(), bytes.end(), zip64_signature.begin(), zip64_signature.end());
    ASSERT_NE(zip64, bytes.end());
    const auto zip64_offset = static_cast<std::size_t>(zip64 - bytes.begin());
    const auto old_central_offset = static_cast<std::size_t>(croptop::io_detail::get64(bytes, zip64_offset + 48U));
    const auto central_size = static_cast<std::size_t>(croptop::io_detail::get64(bytes, zip64_offset + 40U));
    const auto entries = static_cast<std::size_t>(croptop::io_detail::get64(bytes, zip64_offset + 32U));
    ASSERT_GT(entries, 0U);
    ASSERT_LE(old_central_offset + central_size, zip64_offset);

    // Relocate the entire ZIP structure into the final few kilobytes of a virtual UINT64_MAX-byte
    // source. ZIP64 records and central local-header offsets become absolute virtual offsets.
    // Then corrupt the first local header so its name + extra lengths would wrap uint64_t if added.
    constexpr std::size_t kZip64OffsetExtraBytes = 12;
    const auto relocated_size = bytes.size() + entries * kZip64OffsetExtraBytes;
    const auto virtual_base = std::numeric_limits<std::uint64_t>::max() - relocated_size;
    std::vector<std::uint8_t> hostile(bytes.begin(), bytes.begin() + static_cast<std::ptrdiff_t>(old_central_offset));
    std::size_t source_at = old_central_offset;
    for (std::size_t entry = 0; entry < entries; ++entry) {
        ASSERT_LE(source_at + 46U, old_central_offset + central_size);
        const auto name_length = croptop::io_detail::get16(bytes, source_at + 28U);
        const auto extra_length = croptop::io_detail::get16(bytes, source_at + 30U);
        const auto comment_length = croptop::io_detail::get16(bytes, source_at + 32U);
        const auto record_size = 46U + name_length + extra_length + comment_length;
        ASSERT_LE(source_at + record_size, old_central_offset + central_size);
        const auto start = hostile.size();
        hostile.insert(hostile.end(), bytes.begin() + static_cast<std::ptrdiff_t>(source_at),
                       bytes.begin() + static_cast<std::ptrdiff_t>(source_at + record_size));
        const auto old_local_offset = croptop::io_detail::get32(bytes, source_at + 42U);
        ASSERT_NE(old_local_offset, 0xffffffffU);
        const auto relocated_local = virtual_base + old_local_offset;
        for (unsigned byte = 0; byte < 4U; ++byte) hostile[start + 42U + byte] = 0xffU;
        const auto grown_extra = static_cast<std::uint16_t>(extra_length + kZip64OffsetExtraBytes);
        hostile[start + 30U] = static_cast<std::uint8_t>(grown_extra);
        hostile[start + 31U] = static_cast<std::uint8_t>(grown_extra >> 8U);
        hostile.insert(hostile.begin() + static_cast<std::ptrdiff_t>(start + 46U + name_length + extra_length),
                       {0x01, 0x00, 0x08, 0x00});
        std::vector<std::uint8_t> offset_bytes;
        croptop::io_detail::put64(offset_bytes, relocated_local);
        hostile.insert(hostile.begin() + static_cast<std::ptrdiff_t>(start + 46U + name_length + extra_length + 4U),
                       offset_bytes.begin(), offset_bytes.end());
        source_at += record_size;
    }
    ASSERT_EQ(source_at, old_central_offset + central_size);
    const auto new_central_offset = virtual_base + old_central_offset;
    const auto new_zip64_offset = virtual_base + hostile.size();
    hostile.insert(hostile.end(), bytes.begin() + static_cast<std::ptrdiff_t>(source_at), bytes.end());
    // ZIP64 end record is first after the expanded central directory; update its central range.
    const auto relocated_zip64 = hostile.size() - (bytes.size() - zip64_offset);
    const auto new_central_size = central_size + entries * kZip64OffsetExtraBytes;
    for (unsigned byte = 0; byte < 8U; ++byte) {
        hostile[relocated_zip64 + 40U + byte] = static_cast<std::uint8_t>(new_central_size >> (byte * 8U));
        hostile[relocated_zip64 + 48U + byte] = static_cast<std::uint8_t>(new_central_offset >> (byte * 8U));
        hostile[relocated_zip64 + 56U + 8U + byte] = static_cast<std::uint8_t>(new_zip64_offset >> (byte * 8U));
    }
    ASSERT_EQ(virtual_base + hostile.size(), std::numeric_limits<std::uint64_t>::max());
    // The first local file header is "mimetype". Its malicious lengths make the unchecked sum wrap.
    hostile[26] = 0xff;
    hostile[27] = 0xff;
    hostile[28] = 0xff;
    hostile[29] = 0xff;
    auto read_at = [&hostile, virtual_base](std::uint64_t offset, std::span<std::uint8_t> out) {
        if (offset > std::numeric_limits<std::uint64_t>::max() - out.size()) return false;
        std::fill(out.begin(), out.end(), 0U);  // Sparse zero-filled virtual prefix before the ZIP bytes.
        if (offset >= virtual_base) {
            const auto relative = offset - virtual_base;
            if (relative > hostile.size() || out.size() > hostile.size() - relative) return false;
            std::memcpy(out.data(), hostile.data() + static_cast<std::size_t>(relative), out.size());
        } else if (offset + out.size() > virtual_base) {
            const auto prefix = static_cast<std::size_t>(virtual_base - offset);
            const auto count = out.size() - prefix;
            if (count > hostile.size()) return false;
            std::memcpy(out.data() + prefix, hostile.data(), count);
        }
        return true;
    };
    std::size_t maximum_read = 0;
    auto tracked_read = [read_at, &maximum_read](std::uint64_t offset, std::span<std::uint8_t> out) {
        if (offset >= std::numeric_limits<std::uint64_t>::max() - 1024U) {
            maximum_read = std::max(maximum_read, out.size());
        }
        return read_at(offset, out);
    };
    std::string error;
    croptop::CroptopReadSource source{std::numeric_limits<std::uint64_t>::max(), tracked_read};
    EXPECT_EQ(croptop::CroptopArchive::open(std::move(source), error), nullptr);
    EXPECT_NE(error.find("not a croptop archive"), std::string::npos) << error;
    EXPECT_LT(maximum_read, 1024U) << error;  // The claimed 128 KiB local header was never read or allocated.
}

TEST(CroptopFormat, LoadRejectsMismatchedDestinationsAndUnknownLayers) {
    auto store = randomStore(PixelFormat::RGBA16, 300, 300, 6, false);
    croptop::CroptopSaveInput input;
    input.manifest_json = textBytes("{}");
    input.layers.push_back({"layer", &store});
    const auto bytes = saveToBytes(input);
    std::string error;
    const auto archive = croptop::CroptopArchive::open(memorySource(bytes), error);
    ASSERT_NE(archive, nullptr) << error;
    TileStore wrong_format(PixelFormat::RGBA8, 300, 300);
    EXPECT_FALSE(archive->loadLayer("layer", wrong_format, error));
    TileStore wrong_size(PixelFormat::RGBA16, 301, 300);
    EXPECT_FALSE(archive->loadLayer("layer", wrong_size, error));
    EXPECT_FALSE(archive->loadLayer("missing", wrong_size, error));
    EXPECT_EQ(wrong_format.tile_count() + wrong_size.tile_count(), 0U);
}

TEST(CroptopFormat, OpenRejectsNonCroptopAndCorruptArchives) {
    std::string error;
    const std::vector<std::uint8_t> empty;
    EXPECT_EQ(croptop::CroptopArchive::open(memorySource(empty), error), nullptr);
    const std::vector<std::uint8_t> junk(4096, 0x41);
    EXPECT_EQ(croptop::CroptopArchive::open(memorySource(junk), error), nullptr);

    auto store = randomStore(PixelFormat::RGBA8, 600, 600, 7, false);
    croptop::CroptopSaveInput input;
    input.manifest_json = textBytes(R"({"formatVersion":1})");
    input.layers.push_back({"layer", &store});
    const auto bytes = saveToBytes(input);

    // Wrong mimetype is not a croptop file even though it is a valid ZIP.
    auto wrong_mime = bytes;
    wrong_mime[30 + 8] = 'X';  // First byte of the mimetype payload ("mimetype" name is 8 bytes).
    EXPECT_EQ(croptop::CroptopArchive::open(memorySource(wrong_mime), error), nullptr);

    // A flipped byte inside a tile payload is caught by the CRC check at load time.
    auto flipped = bytes;
    flipped[bytes.size() / 2] ^= 0x01U;
    if (const auto archive = croptop::CroptopArchive::open(memorySource(flipped), error)) {
        TileStore loaded(PixelFormat::RGBA8, 600, 600);
        EXPECT_FALSE(archive->loadLayer("layer", loaded, error));
    }

    for (std::size_t cut = 0; cut < bytes.size(); cut += bytes.size() / 97U + 1U) {
        const std::vector<std::uint8_t> truncated(bytes.begin(), bytes.begin() + static_cast<std::ptrdiff_t>(cut));
        EXPECT_EQ(croptop::CroptopArchive::open(memorySource(truncated), error), nullptr) << "cut " << cut;
    }

    std::mt19937 random(31);
    for (int iteration = 0; iteration < 1500; ++iteration) {
        auto mutated = bytes;
        // Bias mutations toward the headers and directory where parsing happens.
        for (int flips = 0; flips < 4; ++flips) {
            const auto region = random() % 3U;
            const auto at = region == 0U ? random() % 128U
                          : region == 1U ? mutated.size() - 1U - random() % std::min<std::size_t>(mutated.size(), 2048U)
                                         : random() % mutated.size();
            mutated[at] = static_cast<std::uint8_t>(random());
        }
        if (const auto archive = croptop::CroptopArchive::open(memorySource(mutated), error)) {
            std::vector<std::uint8_t> manifest;
            (void)archive->readManifest(manifest, error);
            for (const auto& uuid : archive->layerUuids()) {
                croptop::CroptopLayerInfo info;
                if (!archive->layerInfo(uuid, info, error) || info.width > 4096U || info.height > 4096U) continue;
                TileStore loaded(info.format, info.width, info.height);
                (void)archive->loadLayer(uuid, loaded, error);
            }
        }
    }
}

TEST(CroptopFormat, PreviewAveragesColourByAlpha) {
    // 1024×2 composite: the 512 px thumbnail halves it, so each output pixel averages a 2×2 block.
    TileStore composite(PixelFormat::RGBA8, 1024, 2);
    croptop::TileBuffer left(composite.tile_byte_size(), 0);
    for (std::uint32_t y = 0; y < 2; ++y) {
        for (std::uint32_t x = 0; x < croptop::kTileEdge; ++x) {
            auto* p = left.data() + (static_cast<std::size_t>(y) * croptop::kTileEdge + x) * 4U;
            // Even pixels: opaque red. Odd pixels: fully transparent green (must not tint the average).
            if (x % 2U == 0U) {
                p[0] = 255;
                p[3] = 255;
            } else {
                p[1] = 255;
            }
        }
    }
    ASSERT_TRUE(composite.writeTile({0, 0}, left));
    croptop::CroptopSaveInput input;
    input.manifest_json = textBytes("{}");
    input.composite = &composite;
    const auto bytes = saveToBytes(input);
    std::string error;
    const auto archive = croptop::CroptopArchive::open(memorySource(bytes), error);
    ASSERT_NE(archive, nullptr) << error;
    std::vector<std::uint8_t> thumb;
    ASSERT_TRUE(archive->readPreview("thumb.webp", thumb, error)) << error;
    const auto image = decodeWebpToBuffer(thumb);
    ASSERT_TRUE(image.result.success);
    ASSERT_EQ(image.result.raster.width, 512U);
    ASSERT_EQ(image.result.raster.height, 1U);
    // First output pixel covers one red and one clear pixel: red at ~half alpha (lossy tolerance).
    EXPECT_GT(image.rgba[0], 200);
    EXPECT_LT(image.rgba[1], 40);
    EXPECT_NEAR(image.rgba[3], 128, 12);
    // Sparse tiles on the right are transparent.
    EXPECT_EQ(image.rgba[(511U * 4U) + 3U], 0);
}

// ---- Recovery journal ---------------------------------------------------------------------------

class RecoveryJournalTest : public ::testing::Test {
protected:
    void SetUp() override {
        root_ = std::filesystem::temp_directory_path() /
                ("croptop-journal-" + std::to_string(::getpid()) + "-" +
                 ::testing::UnitTest::GetInstance()->current_test_info()->name());
        std::filesystem::remove_all(root_);
        dir_ = root_ / "doc-1";
    }
    void TearDown() override { std::filesystem::remove_all(root_); }

    static croptop::RecoverySnapshot snapshot(std::vector<std::pair<std::string, const TileStore*>> layers,
                                              std::string manifest = R"({"formatVersion":1})") {
        croptop::RecoverySnapshot result;
        result.metadata_json = R"({"name":"Doc"})";
        result.manifest_json = std::move(manifest);
        for (auto& [uuid, store] : layers) result.layers.push_back({uuid, *store});
        return result;
    }

    static std::vector<std::uint8_t> readState(const std::filesystem::path& path) {
        std::ifstream in(path, std::ios::binary);
        return {std::istreambuf_iterator<char>(in), std::istreambuf_iterator<char>()};
    }

    static std::uint32_t read32(const std::vector<std::uint8_t>& bytes, std::size_t offset) {
        return static_cast<std::uint32_t>(bytes[offset]) |
               (static_cast<std::uint32_t>(bytes[offset + 1U]) << 8U) |
               (static_cast<std::uint32_t>(bytes[offset + 2U]) << 16U) |
               (static_cast<std::uint32_t>(bytes[offset + 3U]) << 24U);
    }

    static void write32(std::vector<std::uint8_t>& bytes, std::size_t offset, std::uint32_t value) {
        for (unsigned i = 0; i < 4U; ++i) bytes[offset + i] = static_cast<std::uint8_t>(value >> (8U * i));
    }

    static void sealState(std::vector<std::uint8_t>& bytes) {
        write32(bytes, bytes.size() - 4U,
                croptop::io_detail::crc32Of(std::span<const std::uint8_t>(bytes).first(bytes.size() - 4U)));
    }

    std::size_t fileCount() const {
        std::size_t count = 0;
        for (const auto& entry : std::filesystem::recursive_directory_iterator(dir_)) count += entry.is_regular_file();
        return count;
    }

    void expectContent(const std::string& uuid, const TileStore& expected, const std::string& manifest) const {
        std::string error;
        const auto reader = croptop::RecoveryReader::open(dir_, error);
        ASSERT_NE(reader, nullptr) << error;
        EXPECT_EQ(reader->manifestJson(), manifest);
        TileStore loaded(expected.format(), expected.width(), expected.height());
        ASSERT_TRUE(reader->loadLayer(uuid, loaded, error)) << error;
        EXPECT_TRUE(sameTiles(expected, loaded));
    }

    std::filesystem::path root_;
    std::filesystem::path dir_;
};

TEST_F(RecoveryJournalTest, CommitsIncrementallyAndCollectsGarbage) {
    auto layer = randomStore(PixelFormat::RGBA8, 600, 600, 1, false);  // 9 tiles.
    const auto rgba16 = randomStore(PixelFormat::RGBA16, 300, 300, 2, true);
    croptop::RecoveryJournal journal(dir_);
    std::string error;
    ASSERT_TRUE(journal.commit(snapshot({{"a", &layer}, {"b", &rgba16}}), error)) << error;
    EXPECT_EQ(journal.lastStats().tiles_written, 9U + rgba16.tile_count());
    EXPECT_EQ(journal.lastStats().tiles_reused, 0U);

    ASSERT_TRUE(journal.commit(snapshot({{"a", &layer}, {"b", &rgba16}}), error)) << error;
    EXPECT_EQ(journal.lastStats().tiles_written, 0U) << "unchanged COW buffers are not rewritten";
    EXPECT_EQ(journal.lastStats().tiles_reused, 9U + rgba16.tile_count());

    // Edit one tile: only it is rewritten and its old file is deleted.
    croptop::TileBuffer edited(layer.tile_byte_size(), 0x42);
    ASSERT_TRUE(layer.writeTile({1, 1}, edited));
    const auto files_before = fileCount();
    ASSERT_TRUE(journal.commit(snapshot({{"a", &layer}, {"b", &rgba16}}, R"({"v":3})"), error)) << error;
    EXPECT_EQ(journal.lastStats().tiles_written, 1U);
    EXPECT_EQ(journal.lastStats().files_deleted, 1U);
    EXPECT_EQ(fileCount(), files_before);
    expectContent("a", layer, R"({"v":3})");
    expectContent("b", rgba16, R"({"v":3})");

    // Removing a layer removes its directory.
    ASSERT_TRUE(journal.commit(snapshot({{"a", &layer}}), error)) << error;
    EXPECT_FALSE(std::filesystem::exists(dir_ / "t" / "b"));
    std::string ignored;
    const auto reader = croptop::RecoveryReader::open(dir_, ignored);
    ASSERT_NE(reader, nullptr);
    EXPECT_EQ(reader->layerUuids(), std::vector<std::string>{"a"});
    EXPECT_EQ(reader->metadataJson(), R"({"name":"Doc"})");
    EXPECT_EQ(reader->generation(), 4U);
}

TEST_F(RecoveryJournalTest, ReadsLegacyV1StateWithoutWorkingIcc) {
    const auto layer = randomStore(PixelFormat::RGBA8, 32, 24, 1, false);
    croptop::RecoveryJournal journal(dir_);
    std::string error;
    ASSERT_TRUE(journal.commit(snapshot({{"legacy", &layer}}), error)) << error;
    auto state = readState(dir_ / "state.bin");
    ASSERT_GE(state.size(), 24U);
    ASSERT_EQ(read32(state, 4U), 2U);
    std::size_t offset = 16U;
    offset += 4U + read32(state, offset);
    offset += 4U + read32(state, offset);
    ASSERT_LE(offset + 4U, state.size() - 4U);
    state.erase(state.begin() + static_cast<std::ptrdiff_t>(offset), state.begin() + static_cast<std::ptrdiff_t>(offset + 4U));
    write32(state, 4U, 1U);
    sealState(state);
    {
        std::ofstream out(dir_ / "state.bin", std::ios::binary | std::ios::trunc);
        out.write(reinterpret_cast<const char*>(state.data()), static_cast<std::streamsize>(state.size()));
    }
    const auto reader = croptop::RecoveryReader::open(dir_, error);
    ASSERT_NE(reader, nullptr) << error;
    EXPECT_TRUE(reader->workingIcc().empty());
    EXPECT_EQ(reader->manifestJson(), R"({"formatVersion":1})");
}

TEST_F(RecoveryJournalTest, WorkingIccRoundTripsWithBoundsAndAtomicCommit) {
    const auto layer = randomStore(PixelFormat::RGBA8, 32, 24, 1, false);
    const std::vector<std::uint8_t> first{0, 1, 2, 0xff, 0x80};
    auto initial = snapshot({{"layer", &layer}}, "first");
    initial.working_icc = first;
    croptop::RecoveryJournal journal(dir_);
    std::string error;
    ASSERT_TRUE(journal.commit(initial, error)) << error;
    {
        const auto reader = croptop::RecoveryReader::open(dir_, error);
        ASSERT_NE(reader, nullptr) << error;
        EXPECT_EQ(reader->workingIcc(), first);
    }

    auto too_large = initial;
    too_large.working_icc.resize(static_cast<std::size_t>(croptop::kRecoveryMaxWorkingIccBytes) + 1U, 0x5a);
    EXPECT_FALSE(journal.commit(too_large, error));
    EXPECT_NE(error.find("size limit"), std::string::npos);
    {
        const auto reader = croptop::RecoveryReader::open(dir_, error);
        ASSERT_NE(reader, nullptr) << error;
        EXPECT_EQ(reader->workingIcc(), first) << "a rejected oversized snapshot must preserve the old commit";
    }

    auto replacement = snapshot({{"layer", &layer}}, "replacement");
    replacement.working_icc = {9, 8, 7};
    journal.fault_injector = [](croptop::RecoveryCommitStage stage) { return stage != croptop::RecoveryCommitStage::StateTempWritten; };
    EXPECT_FALSE(journal.commit(replacement, error));
    journal.fault_injector = {};
    {
        const auto reader = croptop::RecoveryReader::open(dir_, error);
        ASSERT_NE(reader, nullptr) << error;
        EXPECT_EQ(reader->manifestJson(), "first");
        EXPECT_EQ(reader->workingIcc(), first) << "failed publication must not pair the old manifest with new ICC bytes";
    }
}

TEST_F(RecoveryJournalTest, RejectsCorruptWorkingIccLengthAndCrc) {
    const auto layer = randomStore(PixelFormat::RGBA8, 32, 24, 1, false);
    auto value = snapshot({{"layer", &layer}});
    value.working_icc = {1, 2, 3, 4};
    croptop::RecoveryJournal journal(dir_);
    std::string error;
    ASSERT_TRUE(journal.commit(value, error)) << error;
    auto state = readState(dir_ / "state.bin");
    std::size_t offset = 16U;
    offset += 4U + read32(state, offset);
    offset += 4U + read32(state, offset);
    ASSERT_LE(offset + 4U, state.size() - 4U);
    const auto payload_offset = offset + 4U;

    auto oversized_length = state;
    write32(oversized_length, offset, static_cast<std::uint32_t>(croptop::kRecoveryMaxWorkingIccBytes + 1U));
    sealState(oversized_length);
    std::filesystem::create_directories(root_ / "bad-length");
    {
        std::ofstream out(root_ / "bad-length" / "state.bin", std::ios::binary | std::ios::trunc);
        out.write(reinterpret_cast<const char*>(oversized_length.data()), static_cast<std::streamsize>(oversized_length.size()));
    }
    EXPECT_EQ(croptop::RecoveryReader::open(root_ / "bad-length", error), nullptr);

    auto bad_crc = state;
    bad_crc[payload_offset] ^= 0x80U;
    std::filesystem::create_directories(root_ / "bad-crc");
    {
        std::ofstream out(root_ / "bad-crc" / "state.bin", std::ios::binary | std::ios::trunc);
        out.write(reinterpret_cast<const char*>(bad_crc.data()), static_cast<std::streamsize>(bad_crc.size()));
    }
    EXPECT_EQ(croptop::RecoveryReader::open(root_ / "bad-crc", error), nullptr);
}

TEST_F(RecoveryJournalTest, SerializesCancellationPredicateAcrossWorkers) {
    TileStore pixels(PixelFormat::RGBA8, 1024, 1024);
    croptop::TileBuffer tile(pixels.tile_byte_size(), 0x35);
    for (std::int32_t y = 0; y < 4; ++y) {
        for (std::int32_t x = 0; x < 4; ++x) ASSERT_TRUE(pixels.writeTile({x, y}, tile));
    }
    croptop::RecoveryJournal journal(dir_, 1, 4);
    std::atomic<bool> entered{false};
    std::atomic<bool> overlap{false};
    std::atomic<unsigned> calls{0};
    auto cancellation = [&] {
        if (entered.exchange(true)) overlap.store(true);
        std::this_thread::yield();
        calls.fetch_add(1);
        entered.store(false);
        return false;
    };
    std::string error;
    ASSERT_TRUE(journal.commit(snapshot({{"layer", &pixels}}), error, cancellation)) << error;
    EXPECT_FALSE(overlap.load());
    EXPECT_GT(calls.load(), 1U);
}

TEST_F(RecoveryJournalTest, CrashAtAnyStageLeavesACompleteCommit) {
    const auto first = randomStore(PixelFormat::RGBA8, 520, 300, 3, false);
    auto second = first;
    ASSERT_TRUE(second.writeTile({0, 0}, croptop::TileBuffer(second.tile_byte_size(), 0x11)));
    ASSERT_TRUE(second.writeTile({2, 1}, croptop::TileBuffer(second.tile_byte_size(), 0x22)));

    for (const auto stage : {croptop::RecoveryCommitStage::TilesWritten, croptop::RecoveryCommitStage::StateTempWritten,
                             croptop::RecoveryCommitStage::StateRenamed}) {
        std::filesystem::remove_all(dir_);
        std::string error;
        {
            croptop::RecoveryJournal journal(dir_);
            ASSERT_TRUE(journal.commit(snapshot({{"layer", &first}}, "A"), error)) << error;
            journal.fault_injector = [stage](croptop::RecoveryCommitStage at) { return at != stage; };
            (void)journal.commit(snapshot({{"layer", &second}}, "B"), error);
        }  // "Process dies" here.
        const bool renamed = stage == croptop::RecoveryCommitStage::StateRenamed;
        expectContent("layer", renamed ? second : first, renamed ? "B" : "A");

        // After a restart a fresh journal commits on top and cleans up orphans from the crash.
        croptop::RecoveryJournal restarted(dir_);
        ASSERT_TRUE(restarted.commit(snapshot({{"layer", &second}}, "C"), error)) << error;
        expectContent("layer", second, "C");
        EXPECT_FALSE(std::filesystem::exists(dir_ / "state.bin.tmp"));
        EXPECT_EQ(fileCount(), second.tile_count() + 1U) << "only live tiles and state.bin remain";
    }
}

TEST_F(RecoveryJournalTest, ListingAndDiscardIgnoreBrokenJournals) {
    const auto layer = randomStore(PixelFormat::A16, 300, 300, 4, false);
    std::string error;
    croptop::RecoveryJournal good(root_ / "doc-good");
    ASSERT_TRUE(good.commit(snapshot({{"mask", &layer}}), error)) << error;
    croptop::RecoveryJournal broken(root_ / "doc-broken");
    ASSERT_TRUE(broken.commit(snapshot({{"mask", &layer}}), error)) << error;
    std::filesystem::create_directories(root_ / "doc-empty");
    {
        std::fstream state(root_ / "doc-broken" / "state.bin", std::ios::in | std::ios::out | std::ios::binary);
        state.seekp(20);
        state.put('\x7f');
    }
    EXPECT_EQ(croptop::RecoveryReader::list(root_), std::vector<std::filesystem::path>{root_ / "doc-good"});
    EXPECT_EQ(croptop::RecoveryReader::open(root_ / "doc-broken", error), nullptr);

    // A damaged tile file is caught by its CRC when the layer loads.
    const auto reader = croptop::RecoveryReader::open(root_ / "doc-good", error);
    ASSERT_NE(reader, nullptr) << error;
    for (const auto& file : std::filesystem::recursive_directory_iterator(root_ / "doc-good" / "t")) {
        if (!file.is_regular_file()) continue;
        std::fstream tile(file.path(), std::ios::in | std::ios::out | std::ios::binary);
        tile.seekp(10);
        tile.put('\x01');
        break;
    }
    TileStore loaded(PixelFormat::A16, 300, 300);
    EXPECT_FALSE(reader->loadLayer("mask", loaded, error));

    ASSERT_TRUE(croptop::RecoveryJournal::discard(root_ / "doc-good", error)) << error;
    EXPECT_FALSE(std::filesystem::exists(root_ / "doc-good"));
    EXPECT_TRUE(croptop::RecoveryReader::list(root_).empty());
}

TEST_F(RecoveryJournalTest, RejectsInvalidSnapshotsAndSurvivesMalformedState) {
    const auto layer = randomStore(PixelFormat::RGBA8, 300, 300, 5, false);
    std::string error;
    croptop::RecoveryJournal journal(dir_);
    EXPECT_FALSE(journal.commit(snapshot({{"../x", &layer}}), error));
    EXPECT_FALSE(journal.commit(snapshot({{"same", &layer}, {"same", &layer}}), error));
    ASSERT_TRUE(journal.commit(snapshot({{"ok", &layer}}), error)) << error;

    std::vector<std::uint8_t> state;
    {
        std::ifstream in(dir_ / "state.bin", std::ios::binary);
        state.assign(std::istreambuf_iterator<char>(in), {});
    }
    // Mutate the body and re-seal the CRC so the parser itself is exercised.
    std::mt19937 random(77);
    for (int iteration = 0; iteration < 3000; ++iteration) {
        auto mutated = state;
        const auto body = mutated.size() - 4U;
        for (int flips = 0; flips < 3; ++flips) mutated[random() % body] = static_cast<std::uint8_t>(random());
        if (random() % 4U == 0U) mutated.resize(4U + random() % body);
        const auto crc = croptop::io_detail::crc32Of(std::span<const std::uint8_t>(mutated).first(mutated.size() - 4U));
        std::memcpy(mutated.data() + mutated.size() - 4U, &crc, 4U);
        {
            std::ofstream out(root_ / "state-fuzz.bin", std::ios::binary | std::ios::trunc);
            out.write(reinterpret_cast<const char*>(mutated.data()), static_cast<std::streamsize>(mutated.size()));
        }
        std::filesystem::create_directories(root_ / "fuzz");
        std::filesystem::copy_file(root_ / "state-fuzz.bin", root_ / "fuzz" / "state.bin",
                                   std::filesystem::copy_options::overwrite_existing);
        if (const auto reader = croptop::RecoveryReader::open(root_ / "fuzz", error)) {
            for (const auto& uuid : reader->layerUuids()) {
                croptop::CroptopLayerInfo info;
                if (!reader->layerInfo(uuid, info, error) || info.width > 4096U || info.height > 4096U) continue;
                TileStore loaded(info.format, info.width, info.height);
                (void)reader->loadLayer(uuid, loaded, error);
            }
        }
    }
}

TEST(AutosaveCadence, DueAfterIdleOrMaxIntervalAndRearmsAfterSnapshot) {
    using std::chrono::milliseconds;
    croptop::AutosaveCadence cadence;
    EXPECT_FALSE(cadence.due(milliseconds(100000)));
    EXPECT_FALSE(cadence.nextDue().has_value());
    cadence.onEdit(milliseconds(1000));
    EXPECT_FALSE(cadence.due(milliseconds(5999)));
    EXPECT_TRUE(cadence.due(milliseconds(6000)));
    cadence.onSnapshotTaken();
    EXPECT_FALSE(cadence.due(milliseconds(60000)));

    // Continuous editing every second never goes idle for 5 s, but a snapshot is due 60 s after
    // the first unsaved edit.
    for (int second = 100; second < 170; ++second) {
        cadence.onEdit(milliseconds(second * 1000));
        const bool due = cadence.due(milliseconds(second * 1000));
        EXPECT_EQ(due, second >= 160) << second;
        if (due) break;
    }
    EXPECT_EQ(*cadence.nextDue(), milliseconds(160000));
}

TEST_F(RecoveryJournalTest, AutosaveCoalescesSnapshotsAndFlushes) {
    croptop::JobPool pool(2);
    const auto layer = randomStore(PixelFormat::RGBA8, 2000, 1500, 8, false);
    {
        croptop::RecoveryAutosave autosave(pool, dir_);
        for (int version = 0; version < 40; ++version) {
            auto edited = layer;
            ASSERT_TRUE(edited.writeTile({version % 7, 0}, croptop::TileBuffer(edited.tile_byte_size(),
                                                                              static_cast<std::uint8_t>(version + 1))));
            autosave.submit(snapshot({{"layer", &edited}}, std::to_string(version)));
        }
        ASSERT_TRUE(autosave.flush(std::chrono::seconds(30)));
        const auto status = autosave.status();
        EXPECT_TRUE(status.last_error.empty()) << status.last_error;
        EXPECT_GE(status.commits, 1U);
        EXPECT_LT(status.commits, 40U) << "pending snapshots are coalesced, not queued";
        std::string error;
        const auto reader = croptop::RecoveryReader::open(dir_, error);
        ASSERT_NE(reader, nullptr) << error;
        EXPECT_EQ(reader->manifestJson(), "39") << "the newest snapshot wins";
        EXPECT_EQ(reader->generation(), status.generation);

        // Clean save: the journal disappears and a later edit starts a fresh, valid one.
        ASSERT_TRUE(autosave.discard(error)) << error;
        EXPECT_FALSE(std::filesystem::exists(dir_));
        autosave.submit(snapshot({{"layer", &layer}}, "after-save"));
        ASSERT_TRUE(autosave.flush(std::chrono::seconds(30)));
        expectContent("layer", layer, "after-save");
        EXPECT_EQ(croptop::RecoveryReader::list(root_), std::vector<std::filesystem::path>{dir_});
    }
}

TEST_F(RecoveryJournalTest, AutosaveSurvivesEvictionByHigherPriorityWork) {
    croptop::JobPool pool(1, 1);  // One worker, one queue slot.
    std::mutex mutex;
    std::condition_variable changed;
    bool blocker_started = false;
    bool release = false;
    ASSERT_TRUE(pool.submit(croptop::JobPriority::Interactive, [&](const croptop::JobContext&) {
        std::unique_lock lock(mutex);
        blocker_started = true;
        changed.notify_all();
        changed.wait(lock, [&] { return release; });
    }));
    {
        std::unique_lock lock(mutex);
        changed.wait(lock, [&] { return blocker_started; });
    }
    const auto layer = randomStore(PixelFormat::RGBA8, 300, 300, 9, false);
    croptop::RecoveryAutosave autosave(pool, dir_);
    autosave.submit(snapshot({{"layer", &layer}}, "kept"));  // Queued Background job fills the slot…
    ASSERT_TRUE(pool.submit(croptop::JobPriority::Interactive, [](const croptop::JobContext&) {}));  // …and is evicted.
    {
        const std::lock_guard lock(mutex);
        release = true;
    }
    changed.notify_all();
    ASSERT_TRUE(autosave.flush(std::chrono::seconds(10))) << "a dropped job is detected and rescheduled";
    expectContent("layer", layer, "kept");
}

// ---- TileStore mip reduction ------------------------------------------------------

/** Verbatim port of the pre-optimization per-sample reduction, reading level-1 through the public API. */
croptop::TileBuffer referenceMipTile(const TileStore& store, std::uint32_t level, croptop::TileCoordinate coordinate) {
    croptop::TileBuffer output(store.tile_byte_size(), 0);
    const auto format = store.format();
    const auto bpp = store.bytes_per_pixel();
    const auto source_width = store.mip_width(level - 1U);
    const auto source_height = store.mip_height(level - 1U);
    const auto output_width = store.mip_width(level);
    const auto output_height = store.mip_height(level);
    const auto channels = bpp / (format == PixelFormat::RGBA32F ? 4U
                                 : (format == PixelFormat::RGBA16 || format == PixelFormat::A16 ? 2U : 1U));
    const auto component_bytes = bpp / channels;
    auto sample = [&](std::uint32_t x, std::uint32_t y, std::size_t channel) -> double {
        if (x >= source_width || y >= source_height) return 0.0;
        const croptop::TileCoordinate c{static_cast<std::int32_t>(x / croptop::kTileEdge),
                                        static_cast<std::int32_t>(y / croptop::kTileEdge)};
        const auto source = level == 1U ? store.readTile(c) : store.readMipTile(level - 1U, c);
        if (!source) return 0.0;
        const auto offset = (static_cast<std::size_t>(y % croptop::kTileEdge) * croptop::kTileEdge + x % croptop::kTileEdge) * bpp +
                            channel * component_bytes;
        if (format == PixelFormat::RGBA32F) {
            float value = 0.0F;
            std::memcpy(&value, source->data() + offset, sizeof(value));
            return value;
        }
        if (component_bytes == 2U) {
            std::uint16_t value = 0;
            std::memcpy(&value, source->data() + offset, sizeof(value));
            return static_cast<double>(value) / 65535.0;
        }
        const double value = static_cast<double>((*source)[offset]) / 255.0;
        if (format == PixelFormat::RGBA8 && channel < 3U) {
            return value <= 0.04045 ? value / 12.92 : std::pow((value + 0.055) / 1.055, 2.4);
        }
        return value;
    };
    auto put = [&](std::size_t offset, std::size_t channel, double value) {
        if (format == PixelFormat::RGBA32F) {
            const auto converted = static_cast<float>(value);
            std::memcpy(output.data() + offset, &converted, sizeof(converted));
        } else if (component_bytes == 2U) {
            const auto converted = static_cast<std::uint16_t>(std::lround(std::clamp(value, 0.0, 1.0) * 65535.0));
            std::memcpy(output.data() + offset, &converted, sizeof(converted));
        } else {
            if (format == PixelFormat::RGBA8 && channel < 3U) {
                value = value <= 0.0031308 ? value * 12.92 : 1.055 * std::pow(value, 1.0 / 2.4) - 0.055;
            }
            output[offset] = static_cast<std::uint8_t>(std::lround(std::clamp(value, 0.0, 1.0) * 255.0));
        }
    };
    const auto origin_x = static_cast<std::uint32_t>(coordinate.x) * croptop::kTileEdge;
    const auto origin_y = static_cast<std::uint32_t>(coordinate.y) * croptop::kTileEdge;
    for (std::uint32_t ly = 0; ly < croptop::kTileEdge && origin_y + ly < output_height; ++ly) {
        for (std::uint32_t lx = 0; lx < croptop::kTileEdge && origin_x + lx < output_width; ++lx) {
            const auto ox = origin_x + lx;
            const auto oy = origin_y + ly;
            std::array<double, 4> sums{};
            double alpha_sum = 0.0;
            unsigned samples = 0;
            for (std::uint32_t dy = 0; dy < 2U; ++dy) {
                for (std::uint32_t dx = 0; dx < 2U; ++dx) {
                    const auto sx = ox * 2U + dx;
                    const auto sy = oy * 2U + dy;
                    if (sx >= source_width || sy >= source_height) continue;
                    const auto alpha = channels == 4U ? sample(sx, sy, 3U) : 1.0;
                    for (std::size_t channel = 0; channel < channels; ++channel) {
                        const auto value = sample(sx, sy, channel);
                        sums[channel] += channels == 4U && channel < 3U ? value * alpha : value;
                    }
                    alpha_sum += alpha;
                    ++samples;
                }
            }
            const auto pixel = (static_cast<std::size_t>(ly) * croptop::kTileEdge + lx) * bpp;
            for (std::size_t channel = 0; channel < channels; ++channel) {
                double average = samples == 0U ? 0.0 : sums[channel] / samples;
                if (channels == 4U && channel < 3U) {
                    const auto alpha = alpha_sum / samples;
                    average = alpha > 0.0 ? average / alpha : 0.0;
                }
                put(pixel + channel * component_bytes, channel, average);
            }
        }
    }
    return output;
}

/** Store whose tiles hold random bytes (every 8-bit value, random alphas, float bit patterns). */
TileStore noiseStore(PixelFormat format, std::uint32_t width, std::uint32_t height, std::uint32_t seed) {
    TileStore store(format, width, height);
    std::mt19937 random(seed);
    for (std::uint32_t y = 0; y * croptop::kTileEdge < height; ++y) {
        for (std::uint32_t x = 0; x * croptop::kTileEdge < width; ++x) {
            if ((x * 7U + y * 3U) % 5U == 1U) continue;  // Leave some tiles sparse.
            croptop::TileBuffer tile(store.tile_byte_size(), 0);
            if (format == PixelFormat::RGBA32F) {
                for (std::size_t i = 0; i < tile.size(); i += 4) {
                    const float value = static_cast<float>(random() % 3000U) / 1000.0F - 0.5F;  // Includes HDR/negatives.
                    std::memcpy(tile.data() + i, &value, 4);
                }
            } else {
                for (auto& byte : tile) byte = static_cast<std::uint8_t>(random());
            }
            EXPECT_TRUE(store.writeTile({static_cast<std::int32_t>(x), static_cast<std::int32_t>(y)}, tile));
        }
    }
    return store;
}

TEST(TileStoreMips, OptimizedReductionIsBitIdenticalToTheReference) {
    for (const auto format : {PixelFormat::RGBA8, PixelFormat::RGBA16, PixelFormat::RGBA32F, PixelFormat::A8, PixelFormat::A16}) {
        const auto store = noiseStore(format, 519, 301, 17U + static_cast<std::uint32_t>(format));
        for (std::uint32_t level = 1; level <= 3; ++level) {
            const auto columns = 1U + (store.mip_width(level) - 1U) / croptop::kTileEdge;
            const auto rows = 1U + (store.mip_height(level) - 1U) / croptop::kTileEdge;
            for (std::uint32_t y = 0; y < rows; ++y) {
                for (std::uint32_t x = 0; x < columns; ++x) {
                    const croptop::TileCoordinate c{static_cast<std::int32_t>(x), static_cast<std::int32_t>(y)};
                    const auto expected = referenceMipTile(store, level, c);
                    const auto actual = store.readMipTile(level, c);
                    const croptop::TileBuffer zero(store.tile_byte_size(), 0);
                    ASSERT_EQ(actual ? *actual : zero, expected)
                        << "format " << static_cast<int>(format) << " level " << level << " tile " << x << "," << y;
                }
            }
        }
    }
}

TEST(TileStoreMips, SrgbEncodingMatchesReferenceAcrossEveryByteBoundary) {
    // A 2×1 → 1×1 reduction of opaque pixels (a, b) averages their linear values; sweeping all pairs
    // hits every output byte and the neighbourhood of every rounding boundary.
    TileStore store(PixelFormat::RGBA8, 512, 256);
    croptop::TileBuffer left(store.tile_byte_size(), 0);
    croptop::TileBuffer right(store.tile_byte_size(), 0);
    for (std::uint32_t y = 0; y < 256; ++y) {
        for (std::uint32_t x = 0; x < 256; ++x) {
            for (auto* tile : {&left, &right}) {
                auto* p = tile->data() + (static_cast<std::size_t>(y) * croptop::kTileEdge + x) * 4U;
                p[0] = static_cast<std::uint8_t>(tile == &left ? x : y);
                p[1] = static_cast<std::uint8_t>(tile == &left ? y : x);
                p[2] = static_cast<std::uint8_t>(x ^ y);
                p[3] = static_cast<std::uint8_t>(tile == &left ? 255 : (x | 1U));
            }
        }
    }
    ASSERT_TRUE(store.writeTile({0, 0}, left));
    ASSERT_TRUE(store.writeTile({1, 0}, right));
    const auto actual = store.readMipTile(1, {0, 0});
    ASSERT_NE(actual, nullptr);
    EXPECT_EQ(*actual, referenceMipTile(store, 1, {0, 0}));
}

TEST(TileStoreMips, FastSrgbEncoderEqualsReferenceForEveryProbedDouble) {
    using croptop::detail::srgbMipEncodeForTesting;
    using croptop::detail::srgbMipEncodeReferenceForTesting;
    std::vector<double> probes = {-1.0, -0.0, 0.0, 1e-300, 0.0031308, 0.04045, 0.5, 1.0, 1.0 + 1e-12, 2.0, 1e9};
    // Both sides of every output-byte boundary.
    double previous = 0.0;
    for (int k = 1; k <= 255; ++k) {
        double low = previous;
        double high = 2.0;
        for (int step = 0; step < 200; ++step) {
            const double middle = low + (high - low) / 2.0;
            if (middle == low || middle == high) break;
            (srgbMipEncodeReferenceForTesting(middle) >= k ? high : low) = middle;
        }
        for (double v : {low, high, std::nextafter(high, 0.0), std::nextafter(high, 2.0)}) probes.push_back(v);
        previous = low;
    }
    // Both sides of every bucket edge used by the fast path.
    for (int bucket = 0; bucket <= 8192; ++bucket) {
        const double edge = static_cast<double>(bucket) / 8192.0;
        for (double v : {edge, std::nextafter(edge, 0.0), std::nextafter(edge, 2.0)}) probes.push_back(v);
    }
    std::mt19937_64 random(5);
    std::uniform_real_distribution<double> uniform(-0.05, 1.05);
    for (int i = 0; i < 2'000'000; ++i) probes.push_back(uniform(random));
    std::size_t mismatches = 0;
    for (const double v : probes) {
        if (srgbMipEncodeForTesting(v) != srgbMipEncodeReferenceForTesting(v)) {
            if (++mismatches <= 5) ADD_FAILURE() << "linear " << v;
        }
    }
    EXPECT_EQ(mismatches, 0U) << "over " << probes.size() << " probes";
}

TEST(TileStoreMips, WritesAndErasesInvalidateCachedLevels) {
    for (const auto format : {PixelFormat::RGBA8, PixelFormat::A16}) {
        auto store = noiseStore(format, 519, 301, 41U + static_cast<std::uint32_t>(format));
        const auto check_all = [&](const char* when) {
            for (std::uint32_t level = 1; level <= 2; ++level) {
                const auto columns = 1U + (store.mip_width(level) - 1U) / croptop::kTileEdge;
                const auto rows = 1U + (store.mip_height(level) - 1U) / croptop::kTileEdge;
                const croptop::TileBuffer zero(store.tile_byte_size(), 0);
                for (std::uint32_t y = 0; y < rows; ++y) {
                    for (std::uint32_t x = 0; x < columns; ++x) {
                        const croptop::TileCoordinate c{static_cast<std::int32_t>(x), static_cast<std::int32_t>(y)};
                        const auto actual = store.readMipTile(level, c);
                        ASSERT_EQ(actual ? *actual : zero, referenceMipTile(store, level, c))
                            << when << " format " << static_cast<int>(format) << " level " << level;
                    }
                }
            }
        };
        check_all("initial");  // Populates every cached level.
        croptop::TileBuffer bright(store.tile_byte_size(), 0xEE);
        ASSERT_TRUE(store.writeTile({2, 1}, bright));   // Right/bottom edge tile (partial).
        ASSERT_TRUE(store.writeTile({0, 0}, bright));
        check_all("after writes");
        ASSERT_TRUE(store.eraseTile({2, 1}));
        check_all("after erase");
        store.invalidateMipmaps({0, 0, 519, 301});
        check_all("after explicit invalidation");
    }
}

}  // namespace
