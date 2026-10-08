#include "avif_codec.h"

#include <avif/avif.h>

#include <algorithm>
#include <cmath>
#include <cstring>
#include <exception>
#include <memory>

namespace croptop::avif {
namespace {

struct ImageDeleter { void operator()(avifImage* p) const { avifImageDestroy(p); } };
struct EncoderDeleter { void operator()(avifEncoder* p) const { avifEncoderDestroy(p); } };
struct DecoderDeleter { void operator()(avifDecoder* p) const { avifDecoderDestroy(p); } };
using ImagePtr = std::unique_ptr<avifImage, ImageDeleter>;
using EncoderPtr = std::unique_ptr<avifEncoder, EncoderDeleter>;
using DecoderPtr = std::unique_ptr<avifDecoder, DecoderDeleter>;

bool isRgba(PixelFormat f) { return f == PixelFormat::RGBA8 || f == PixelFormat::RGBA16 || f == PixelFormat::RGBA32F; }

std::string message(const char* what, avifResult result) { return std::string(what) + ": " + avifResultToString(result); }

}  // namespace

bool isAvif(std::span<const std::uint8_t> bytes) {
    if (bytes.size() < 12 || std::memcmp(bytes.data() + 4, "ftyp", 4) != 0) return false;
    const auto size = (static_cast<std::size_t>(bytes[0]) << 24) | (static_cast<std::size_t>(bytes[1]) << 16) |
                      (static_cast<std::size_t>(bytes[2]) << 8) | bytes[3];
    const auto end = std::min(bytes.size(), std::max<std::size_t>(size, 16));
    for (std::size_t at = 8; at + 4 <= end; at += 4)
        if (std::memcmp(bytes.data() + at, "avif", 4) == 0 || std::memcmp(bytes.data() + at, "avis", 4) == 0) return true;
    return false;
}

std::optional<std::vector<std::uint8_t>> encode(const TileStore& pixels, const EncodeOptions& options, std::string& error) {
    if (!isRgba(pixels.format())) {
        error = "AVIF export needs an RGBA image";
        return std::nullopt;
    }
    if (options.quality < 0 || options.quality > 100 || options.speed < 0 || options.speed > 10 ||
        (options.bit_depth != 8 && options.bit_depth != 10 && options.bit_depth != 12)) {
        error = "AVIF export options out of range (quality 0-100, speed 0-10, depth 8/10/12)";
        return std::nullopt;
    }
    const auto w = pixels.width();
    const auto h = pixels.height();
    if (w == 0U || h == 0U || w > 65536U || h > 65536U) {
        error = "AVIF supports images up to 65536 px per side";
        return std::nullopt;
    }
    const bool lossless = options.quality == 100;
    try {
        ImagePtr image(avifImageCreate(w, h, static_cast<std::uint32_t>(options.bit_depth),
                                       lossless ? AVIF_PIXEL_FORMAT_YUV444 : AVIF_PIXEL_FORMAT_YUV420));
        if (!image) {
            error = "out of memory";
            return std::nullopt;
        }
        image->colorPrimaries = AVIF_COLOR_PRIMARIES_BT709;
        image->transferCharacteristics = AVIF_TRANSFER_CHARACTERISTICS_SRGB;
        image->matrixCoefficients = lossless ? AVIF_MATRIX_COEFFICIENTS_IDENTITY : AVIF_MATRIX_COEFFICIENTS_BT601;
        if (!options.icc_profile.empty()) {
            const auto result = avifImageSetProfileICC(image.get(), options.icc_profile.data(), options.icc_profile.size());
            if (result != AVIF_RESULT_OK) {
                error = message("could not attach the ICC profile", result);
                return std::nullopt;
            }
        }
        avifRGBImage rgb;
        avifRGBImageSetDefaults(&rgb, image.get());
        rgb.format = AVIF_RGB_FORMAT_RGBA;
        rgb.depth = pixels.format() == PixelFormat::RGBA8 ? 8U : 16U;
        rgb.alphaPremultiplied = AVIF_FALSE;
        if (auto result = avifRGBImageAllocatePixels(&rgb); result != AVIF_RESULT_OK) {
            error = message("could not allocate pixels", result);
            return std::nullopt;
        }
        struct RgbFree { avifRGBImage* p; ~RgbFree() { avifRGBImageFreePixels(p); } } rgb_free{&rgb};
        const auto bpp = pixels.bytes_per_pixel();
        for (std::uint32_t ty = 0; ty * kTileEdge < h; ++ty) {
            for (std::uint32_t tx = 0; tx * kTileEdge < w; ++tx) {
                const auto tile = pixels.readTile({static_cast<std::int32_t>(tx), static_cast<std::int32_t>(ty)});
                const auto rows = std::min<std::uint32_t>(kTileEdge, h - ty * kTileEdge);
                const auto cols = std::min<std::uint32_t>(kTileEdge, w - tx * kTileEdge);
                for (std::uint32_t y = 0; y < rows; ++y) {
                    auto* row = rgb.pixels + static_cast<std::size_t>(ty * kTileEdge + y) * rgb.rowBytes;
                    for (std::uint32_t x = 0; x < cols; ++x) {
                        const auto* src = tile ? tile->data() + (static_cast<std::size_t>(y) * kTileEdge + x) * bpp : nullptr;
                        auto* dst = row + static_cast<std::size_t>(tx * kTileEdge + x) * (rgb.depth == 8U ? 4U : 8U);
                        if (src == nullptr) {
                            std::memset(dst, 0, rgb.depth == 8U ? 4U : 8U);
                        } else if (pixels.format() == PixelFormat::RGBA8) {
                            std::memcpy(dst, src, 4U);
                        } else if (pixels.format() == PixelFormat::RGBA16) {
                            std::memcpy(dst, src, 8U);
                        } else {
                            for (int c = 0; c < 4; ++c) {
                                float v = 0.0F;
                                std::memcpy(&v, src + c * 4, 4);
                                const auto q = static_cast<std::uint16_t>(std::lround(std::clamp(std::isfinite(v) ? v : 0.0F, 0.0F, 1.0F) * 65535.0F));
                                std::memcpy(dst + c * 2, &q, 2);
                            }
                        }
                    }
                }
            }
        }
        if (auto result = avifImageRGBToYUV(image.get(), &rgb); result != AVIF_RESULT_OK) {
            error = message("colour conversion failed", result);
            return std::nullopt;
        }
        EncoderPtr encoder(avifEncoderCreate());
        if (!encoder) {
            error = "out of memory";
            return std::nullopt;
        }
        encoder->speed = options.speed;
        encoder->maxThreads = static_cast<int>(options.worker_threads == 0U ? 4U : std::min(options.worker_threads, 8U));
        encoder->quality = options.quality;
        encoder->qualityAlpha = options.quality;
        avifRWData output = AVIF_DATA_EMPTY;
        struct DataFree { avifRWData* p; ~DataFree() { avifRWDataFree(p); } } data_free{&output};
        if (auto result = avifEncoderAddImage(encoder.get(), image.get(), 1, AVIF_ADD_IMAGE_FLAG_SINGLE); result != AVIF_RESULT_OK) {
            error = message("encoding failed", result);
            return std::nullopt;
        }
        if (auto result = avifEncoderFinish(encoder.get(), &output); result != AVIF_RESULT_OK) {
            error = message("encoding failed", result);
            return std::nullopt;
        }
        return std::vector<std::uint8_t>(output.data, output.data + output.size);
    } catch (const std::exception&) {
        error = "not enough memory to encode AVIF";
        return std::nullopt;
    }
}

std::optional<Info> probe(std::span<const std::uint8_t> bytes, std::string& error) {
    if (!isAvif(bytes)) {
        error = "not an AVIF file";
        return std::nullopt;
    }
    DecoderPtr decoder(avifDecoderCreate());
    if (!decoder) {
        error = "out of memory";
        return std::nullopt;
    }
    if (auto result = avifDecoderSetIOMemory(decoder.get(), bytes.data(), bytes.size()); result != AVIF_RESULT_OK) {
        error = message("could not read the file", result);
        return std::nullopt;
    }
    if (auto result = avifDecoderParse(decoder.get()); result != AVIF_RESULT_OK) {
        error = message("could not parse the AVIF header", result);
        return std::nullopt;
    }
    const auto* image = decoder->image;
    return Info{image->width, image->height, static_cast<int>(image->depth), image->alphaPlane != nullptr || decoder->alphaPresent != 0};
}

std::optional<Decoded> decode(std::span<const std::uint8_t> bytes, std::string& error, std::uint64_t max_pixels) {
    if (!isAvif(bytes)) {
        error = "not an AVIF file";
        return std::nullopt;
    }
    try {
        DecoderPtr decoder(avifDecoderCreate());
        if (!decoder) {
            error = "out of memory";
            return std::nullopt;
        }
        decoder->maxThreads = 4;
        decoder->imageSizeLimit = static_cast<std::uint32_t>(std::min<std::uint64_t>(max_pixels, 0xFFFFFFFFULL));
        if (auto result = avifDecoderSetIOMemory(decoder.get(), bytes.data(), bytes.size()); result != AVIF_RESULT_OK) {
            error = message("could not read the file", result);
            return std::nullopt;
        }
        if (auto result = avifDecoderParse(decoder.get()); result != AVIF_RESULT_OK) {
            error = message("could not parse the AVIF header", result);
            return std::nullopt;
        }
        if (static_cast<std::uint64_t>(decoder->image->width) * decoder->image->height > max_pixels) {
            error = "AVIF image is larger than the pixel limit";
            return std::nullopt;
        }
        if (auto result = avifDecoderNextImage(decoder.get()); result != AVIF_RESULT_OK) {
            error = message("could not decode the image", result);
            return std::nullopt;
        }
        const auto* image = decoder->image;
        const bool deep = image->depth > 8U;
        avifRGBImage rgb;
        avifRGBImageSetDefaults(&rgb, image);
        rgb.format = AVIF_RGB_FORMAT_RGBA;
        rgb.depth = deep ? 16U : 8U;
        rgb.alphaPremultiplied = AVIF_FALSE;
        if (auto result = avifRGBImageAllocatePixels(&rgb); result != AVIF_RESULT_OK) {
            error = message("could not allocate pixels", result);
            return std::nullopt;
        }
        struct RgbFree { avifRGBImage* p; ~RgbFree() { avifRGBImageFreePixels(p); } } rgb_free{&rgb};
        if (auto result = avifImageYUVToRGB(image, &rgb); result != AVIF_RESULT_OK) {
            error = message("colour conversion failed", result);
            return std::nullopt;
        }
        const auto w = image->width;
        const auto h = image->height;
        Decoded out{TileStore(deep ? PixelFormat::RGBA16 : PixelFormat::RGBA8, w, h), {}, static_cast<int>(image->depth),
                    image->alphaPlane != nullptr};
        if (image->icc.size > 0) out.icc_profile.assign(image->icc.data, image->icc.data + image->icc.size);
        const auto bpp = out.pixels.bytes_per_pixel();
        for (std::uint32_t ty = 0; ty * kTileEdge < h; ++ty) {
            for (std::uint32_t tx = 0; tx * kTileEdge < w; ++tx) {
                TileBuffer tile(out.pixels.tile_byte_size(), 0);
                const auto rows = std::min<std::uint32_t>(kTileEdge, h - ty * kTileEdge);
                const auto cols = std::min<std::uint32_t>(kTileEdge, w - tx * kTileEdge);
                bool any = false;
                for (std::uint32_t y = 0; y < rows; ++y) {
                    const auto* src = rgb.pixels + static_cast<std::size_t>(ty * kTileEdge + y) * rgb.rowBytes + static_cast<std::size_t>(tx * kTileEdge) * bpp;
                    std::memcpy(tile.data() + static_cast<std::size_t>(y) * kTileEdge * bpp, src, static_cast<std::size_t>(cols) * bpp);
                    for (std::size_t i = 0; !any && i < static_cast<std::size_t>(cols) * bpp; ++i) any = src[i] != 0;
                }
                if (any && !out.pixels.writeTile({static_cast<std::int32_t>(tx), static_cast<std::int32_t>(ty)}, tile)) {
                    error = "could not store the decoded image";
                    return std::nullopt;
                }
            }
        }
        return out;
    } catch (const std::exception&) {
        error = "not enough memory to decode AVIF";
        return std::nullopt;
    }
}

}  // namespace croptop::avif
