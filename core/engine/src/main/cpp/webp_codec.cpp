#include "webp_codec.h"

#include <algorithm>
#include <cmath>
#include <cstring>
#include <exception>
#include <memory>
#include <new>
#include <string>
#include <vector>

#include <webp/decode.h>
#include <webp/encode.h>
#include <webp/mux.h>

#include "tile_band_writer.h"
#include "tile_store.h"

namespace croptop {
namespace {

constexpr std::uint32_t kWebpMaxDimension = 16383;
constexpr std::size_t kSinkChunkBytes = 64U * 1024U;

DecodeResult decodeFailure(const char* message) {
    DecodeResult result{};
    result.error = message;
    return result;
}

EncodeResult encodeFailure(std::string message) {
    EncodeResult result{};
    result.error = std::move(message);
    return result;
}

/** Reads and bounds-checks a still WebP header without allocating. Returns nullptr on success. */
const char* probeWebp(std::span<const std::uint8_t> encoded, std::uint32_t max_dimension, std::uint64_t max_pixels,
                      std::uint64_t max_working_bytes, std::uint32_t& width, std::uint32_t& height) {
    WebPBitstreamFeatures features{};
    if (WebPGetFeatures(encoded.data(), encoded.size(), &features) != VP8_STATUS_OK) return "not a valid WebP stream";
    if (features.has_animation != 0) return "animated WebP is not supported";
    if (features.width <= 0 || features.height <= 0) return "WebP has invalid dimensions";
    width = static_cast<std::uint32_t>(features.width);
    height = static_cast<std::uint32_t>(features.height);
    const auto pixels = static_cast<std::uint64_t>(width) * height;
    if (width > max_dimension || height > max_dimension || pixels > max_pixels) {
        return "WebP dimensions exceed the decode limit";
    }
    if (pixels * 4U > max_working_bytes) return "WebP frame exceeds the working memory limit";
    return nullptr;
}

int writeToVector(const std::uint8_t* data, std::size_t size, const WebPPicture* picture) {
    auto* out = static_cast<std::vector<std::uint8_t>*>(picture->custom_ptr);
    try {
        out->insert(out->end(), data, data + size);
    } catch (const std::exception&) {
        return 0;
    }
    return 1;
}

struct PictureDeleter {
    void operator()(WebPPicture* picture) const {
        WebPPictureFree(picture);
        delete picture;
    }
};

struct MuxDeleter {
    void operator()(WebPMux* mux) const { WebPMuxDelete(mux); }
};

/** Copies RGBA8/RGBA16 tiles straight into the picture's ARGB plane; sparse tiles become transparent. */
void fillArgb(const TileStore& source, WebPPicture& picture, const std::array<float, 4>& background) {
    const auto width = source.width();
    const auto height = source.height();
    const bool sixteen = source.format() == PixelFormat::RGBA16;
    const auto source_bpp = source.bytes_per_pixel();
    const auto channel = [sixteen](const std::uint8_t* pixel, int index) -> std::uint32_t {
        if (!sixteen) return pixel[index];
        std::uint16_t value;
        std::memcpy(&value, pixel + index * 2, sizeof(value));
        return (static_cast<std::uint32_t>(value) * 255U + 32767U) / 65535U;
    };
    const auto tile_columns = 1U + (width - 1U) / kTileEdge;
    const auto tile_rows = 1U + (height - 1U) / kTileEdge;
    for (std::uint32_t tile_y = 0; tile_y < tile_rows; ++tile_y) {
        for (std::uint32_t tile_x = 0; tile_x < tile_columns; ++tile_x) {
            const auto x0 = tile_x * kTileEdge;
            const auto y0 = tile_y * kTileEdge;
            const auto tile_width = std::min<std::uint32_t>(kTileEdge, width - x0);
            const auto tile_height = std::min<std::uint32_t>(kTileEdge, height - y0);
            const auto tile = source.readTile({static_cast<std::int32_t>(tile_x), static_cast<std::int32_t>(tile_y)});
            for (std::uint32_t local_y = 0; local_y < tile_height; ++local_y) {
                auto* out = picture.argb + (static_cast<std::size_t>(y0) + local_y) * picture.argb_stride + x0;
                const auto* in = tile == nullptr ? nullptr : tile->data() + static_cast<std::size_t>(local_y) * kTileEdge * source_bpp;
                for (std::uint32_t x = 0; x < tile_width; ++x) {
                    std::array<float, 4> rgba{};
                    if (in != nullptr) {
                        for (std::size_t component = 0; component < 4; ++component) {
                            rgba[component] = static_cast<float>(channel(in, static_cast<int>(component))) / 255.0F;
                        }
                        in += source_bpp;
                    }
                    const auto alpha = rgba[3] + background[3] * (1.0F - rgba[3]);
                    std::array<std::uint32_t, 4> output{};
                    output[3] = static_cast<std::uint32_t>(std::lround(alpha * 255.0F));
                    for (std::size_t component = 0; component < 3; ++component) {
                        const auto value = alpha > 0.0F
                            ? (rgba[component] * rgba[3] + background[component] * background[3] * (1.0F - rgba[3])) / alpha
                            : 0.0F;
                        output[component] = static_cast<std::uint32_t>(std::lround(std::clamp(value, 0.0F, 1.0F) * 255.0F));
                    }
                    out[x] = (output[3] << 24U) | (output[0] << 16U) | (output[1] << 8U) | output[2];
                }
            }
        }
    }
}

}  // namespace

std::uint64_t estimateWebpEncodeBytes(std::uint32_t width, std::uint32_t height, bool lossless,
                                      std::uint64_t metadata_bytes) {
    constexpr std::uint64_t kLossyBytesPerPixel = 30;
    constexpr std::uint64_t kLosslessBytesPerPixel = 44;
    constexpr std::uint64_t kFixedOverhead = 4ULL * 1024ULL * 1024ULL;
    const auto pixels = static_cast<std::uint64_t>(width) * height;
    return pixels * (lossless ? kLosslessBytesPerPixel : kLossyBytesPerPixel) + kFixedOverhead + 2U * metadata_bytes;
}

DecodeResult decodeWebp(std::span<const std::uint8_t> encoded, const RgbaScanlineSink& sink,
                        std::uint32_t max_dimension, std::uint64_t max_pixels, std::uint64_t max_working_bytes) {
    if (!sink) return decodeFailure("WebP decode requires a scanline sink");
    std::uint32_t width = 0;
    std::uint32_t height = 0;
    if (const auto* error = probeWebp(encoded, max_dimension, max_pixels, max_working_bytes, width, height)) {
        return decodeFailure(error);
    }

    const auto stride = static_cast<std::size_t>(width) * 4U;
    std::vector<std::uint8_t> pixels;
    try {
        pixels.resize(stride * height);
    } catch (const std::exception&) {
        return decodeFailure("could not allocate WebP frame");
    }

    WebPDecoderConfig config;
    if (WebPInitDecoderConfig(&config) == 0) return decodeFailure("libwebp version mismatch");
    config.output.colorspace = MODE_RGBA;
    config.output.is_external_memory = 1;
    config.output.u.RGBA.rgba = pixels.data();
    config.output.u.RGBA.stride = static_cast<int>(stride);
    config.output.u.RGBA.size = pixels.size();
    const auto status = WebPDecode(encoded.data(), encoded.size(), &config);
    WebPFreeDecBuffer(&config.output);
    if (status != VP8_STATUS_OK) return decodeFailure("WebP bitstream is corrupt or truncated");

    for (std::uint32_t y = 0; y < height; ++y) {
        if (!sink(y, std::span<const std::uint8_t>(pixels.data() + stride * y, stride))) {
            return decodeFailure("WebP scanline sink rejected a row");
        }
    }
    DecodeResult result{};
    result.success = true;
    result.raster = {width, height, "webp", 8};
    return result;
}

DecodeResult decodeWebpToTiles(std::span<const std::uint8_t> encoded, TileStore& destination,
                               std::uint32_t max_dimension, std::uint64_t max_pixels,
                               std::uint64_t max_working_bytes, std::uint8_t exif_orientation) {
    if (destination.format() != PixelFormat::RGBA8) return decodeFailure("WebP requires an RGBA8 destination tile store");
    if (exif_orientation < 1 || exif_orientation > 8) return decodeFailure("EXIF orientation must be between 1 and 8");
    std::uint32_t width = 0;
    std::uint32_t height = 0;
    if (const auto* error = probeWebp(encoded, max_dimension, max_pixels, max_working_bytes, width, height)) {
        return decodeFailure(error);
    }
    const bool swaps_axes = exif_orientation >= 5;
    if ((swaps_axes ? height : width) != destination.width() || (swaps_axes ? width : height) != destination.height()) {
        return decodeFailure("WebP dimensions do not match tile store");
    }
    detail::TileBandWriter writer(destination, exif_orientation);
    auto result = decodeWebp(encoded, [&](std::uint32_t y, std::span<const std::uint8_t> row) {
        return writer.write(y, row);
    }, max_dimension, max_pixels, max_working_bytes);
    if (result.success && !writer.complete()) return decodeFailure("WebP dimensions do not match tile store");
    return result;
}

EncodeResult encodeWebpToSink(const TileStore& source, const EncodedBytesSink& sink,
                              const WebpEncodeOptions& options) {
    if ((source.format() != PixelFormat::RGBA8 && source.format() != PixelFormat::RGBA16) || !sink) {
        return encodeFailure("WebP encoding requires an RGBA8/RGBA16 store and a sink");
    }
    const auto width = source.width();
    const auto height = source.height();
    if (width == 0 || height == 0 || width > kWebpMaxDimension || height > kWebpMaxDimension) {
        return encodeFailure("WebP dimensions must be 1..16383 per side");
    }
    if (estimateWebpEncodeBytes(width, height, options.lossless,
                                options.icc_profile.size() + options.exif_tiff.size()) > options.max_working_bytes) {
        return encodeFailure("WebP encode exceeds the working memory limit");
    }
    if (!std::isfinite(options.quality) || options.quality < 0.0f || options.quality > 100.0f ||
        options.method < 0 || options.method > 6 ||
        std::any_of(options.canvas_background.begin(), options.canvas_background.end(), [](float value) {
            return !std::isfinite(value) || value < 0.0F || value > 1.0F;
        })) {
        return encodeFailure("WebP quality must be 0..100 and method 0..6");
    }

    WebPConfig config;
    if (WebPConfigInit(&config) == 0) return encodeFailure("libwebp version mismatch");
    config.quality = options.quality;
    config.method = options.method;
    config.lossless = options.lossless ? 1 : 0;
    if (WebPValidateConfig(&config) == 0) return encodeFailure("invalid WebP encoder configuration");

    std::unique_ptr<WebPPicture, PictureDeleter> picture(new (std::nothrow) WebPPicture);
    if (picture == nullptr || WebPPictureInit(picture.get()) == 0) return encodeFailure("libwebp version mismatch");
    picture->use_argb = 1;
    picture->width = static_cast<int>(width);
    picture->height = static_cast<int>(height);
    if (WebPPictureAlloc(picture.get()) == 0) return encodeFailure("could not allocate WebP source picture");
    fillArgb(source, *picture, options.canvas_background);

    std::vector<std::uint8_t> encoded;
    picture->writer = writeToVector;
    picture->custom_ptr = &encoded;
    if (WebPEncode(&config, picture.get()) == 0) {
        return encodeFailure("WebP encoder failed (libwebp error " + std::to_string(picture->error_code) + ")");
    }
    picture.reset();

    WebPData assembled{};
    std::span<const std::uint8_t> output(encoded);
    if (!options.icc_profile.empty() || !options.exif_tiff.empty()) {
        std::unique_ptr<WebPMux, MuxDeleter> mux(WebPMuxNew());
        if (mux == nullptr) return encodeFailure("could not allocate WebP muxer");
        const WebPData image{encoded.data(), encoded.size()};
        const WebPData icc{options.icc_profile.data(), options.icc_profile.size()};
        const WebPData exif{options.exif_tiff.data(), options.exif_tiff.size()};
        if (WebPMuxSetImage(mux.get(), &image, 0) != WEBP_MUX_OK ||
            (!options.icc_profile.empty() && WebPMuxSetChunk(mux.get(), "ICCP", &icc, 0) != WEBP_MUX_OK) ||
            (!options.exif_tiff.empty() && WebPMuxSetChunk(mux.get(), "EXIF", &exif, 0) != WEBP_MUX_OK) ||
            WebPMuxAssemble(mux.get(), &assembled) != WEBP_MUX_OK) {
            WebPDataClear(&assembled);
            return encodeFailure("could not add WebP metadata chunks");
        }
        output = std::span<const std::uint8_t>(assembled.bytes, assembled.size);
    }
    bool sink_ok = true;
    for (std::size_t offset = 0; sink_ok && offset < output.size(); offset += kSinkChunkBytes) {
        sink_ok = sink(output.subspan(offset, std::min(kSinkChunkBytes, output.size() - offset)));
    }
    WebPDataClear(&assembled);
    if (!sink_ok) return encodeFailure("encoded WebP sink failed");
    EncodeResult result{};
    result.success = true;
    return result;
}

}  // namespace croptop
