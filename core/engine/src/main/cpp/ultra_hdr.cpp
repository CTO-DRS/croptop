#include "ultra_hdr.h"

#include <ultrahdr_api.h>

#include <algorithm>
#include <cmath>
#include <cstring>
#include <exception>
#include <memory>

namespace croptop::ultrahdr {
namespace {

struct EncoderDeleter { void operator()(uhdr_codec_private_t* p) const { uhdr_release_encoder(p); } };

/** IEEE binary16 with round-to-nearest-even; saturates at the largest finite half. */
std::uint16_t toHalf(float value) {
    if (!std::isfinite(value)) value = 0.0F;
    std::uint32_t bits;
    std::memcpy(&bits, &value, 4);
    const std::uint32_t sign = (bits >> 16) & 0x8000U;
    const auto magnitude = std::fabs(value);
    if (magnitude >= 65504.0F) return static_cast<std::uint16_t>(sign | 0x7BFFU);
    if (magnitude < 6.103515625e-05F) {  // subnormal half
        const auto scaled = static_cast<std::uint32_t>(std::lround(magnitude * 16777216.0F));  // 2^24
        return static_cast<std::uint16_t>(sign | scaled);
    }
    const auto exponent = static_cast<std::int32_t>((bits >> 23) & 0xFFU) - 127 + 15;
    std::uint32_t mantissa = bits & 0x7FFFFFU;
    std::uint32_t half = (static_cast<std::uint32_t>(exponent) << 10) | (mantissa >> 13);
    const std::uint32_t remainder = mantissa & 0x1FFFU;
    if (remainder > 0x1000U || (remainder == 0x1000U && (half & 1U) != 0U)) ++half;  // carry into the exponent is correct
    return static_cast<std::uint16_t>(sign | half);
}

float fromHalf(std::uint16_t h) {
    const std::uint32_t sign = (h & 0x8000U) << 16;
    const std::uint32_t exponent = (h >> 10) & 0x1FU;
    const std::uint32_t mantissa = h & 0x3FFU;
    std::uint32_t bits;
    if (exponent == 0U) {
        if (mantissa == 0U) {
            bits = sign;
        } else {  // subnormal: normalise
            std::uint32_t e = 127U - 15U + 1U, m = mantissa;
            while ((m & 0x400U) == 0U) { m <<= 1; --e; }
            bits = sign | (e << 23) | ((m & 0x3FFU) << 13);
        }
    } else if (exponent == 31U) {
        bits = sign | 0x7F800000U | (mantissa << 13);
    } else {
        bits = sign | ((exponent + 127U - 15U) << 23) | (mantissa << 13);
    }
    float out;
    std::memcpy(&out, &bits, 4);
    return out;
}

struct DecoderDeleter { void operator()(uhdr_codec_private_t* p) const { uhdr_release_decoder(p); } };

std::string detail(const uhdr_error_info_t& info) {
    return info.has_detail ? info.detail : "libultrahdr error " + std::to_string(static_cast<int>(info.error_code));
}

}  // namespace

bool isUltraHdr(std::span<const std::uint8_t> bytes) {
    if (bytes.size() < 4 || bytes.size() > 0x7FFFFFFFU) return false;
    return is_uhdr_image(const_cast<std::uint8_t*>(bytes.data()), static_cast<int>(bytes.size())) != 0;
}

std::optional<std::vector<std::uint8_t>> encode(const TileStore& linear_hdr, const TileStore* sdr, const EncodeOptions& options,
                                                std::string& error) {
    if (linear_hdr.format() != PixelFormat::RGBA32F) {
        error = "Ultra HDR export needs a linear RGBA32F image";
        return std::nullopt;
    }
    const auto w = linear_hdr.width();
    const auto h = linear_hdr.height();
    if (w < 8U || h < 8U || w > 16384U || h > 16384U) {
        error = "Ultra HDR export supports images from 8 to 16384 px per side";
        return std::nullopt;
    }
    if (sdr != nullptr && (sdr->format() != PixelFormat::RGBA8 || sdr->width() != w || sdr->height() != h)) {
        error = "the SDR base image must be RGBA8 and the same size as the HDR image";
        return std::nullopt;
    }
    if (options.base_quality < 1 || options.base_quality > 100 || options.gain_map_quality < 1 || options.gain_map_quality > 100 ||
        !(options.target_peak_nits >= 203.0F && options.target_peak_nits <= 10000.0F)) {
        error = "Ultra HDR options out of range";
        return std::nullopt;
    }
    try {
        std::vector<std::uint16_t> hdr(static_cast<std::size_t>(w) * h * 4U, 0);
        std::vector<std::uint8_t> base;
        if (sdr != nullptr) base.assign(static_cast<std::size_t>(w) * h * 4U, 0);
        for (std::uint32_t ty = 0; ty * kTileEdge < h; ++ty) {
            for (std::uint32_t tx = 0; tx * kTileEdge < w; ++tx) {
                const TileCoordinate coordinate{static_cast<std::int32_t>(tx), static_cast<std::int32_t>(ty)};
                const auto tile = linear_hdr.readTile(coordinate);
                const auto sdr_tile = sdr != nullptr ? sdr->readTile(coordinate) : nullptr;
                const auto rows = std::min<std::uint32_t>(kTileEdge, h - ty * kTileEdge);
                const auto cols = std::min<std::uint32_t>(kTileEdge, w - tx * kTileEdge);
                for (std::uint32_t y = 0; y < rows; ++y) {
                    const auto row = static_cast<std::size_t>(ty * kTileEdge + y) * w + static_cast<std::size_t>(tx) * kTileEdge;
                    for (std::uint32_t x = 0; x < cols; ++x) {
                        auto* out = hdr.data() + (row + x) * 4U;
                        if (tile != nullptr) {
                            const auto* src = tile->data() + (static_cast<std::size_t>(y) * kTileEdge + x) * 16U;
                            for (int c = 0; c < 3; ++c) {
                                float v;
                                std::memcpy(&v, src + c * 4, 4);
                                out[c] = toHalf(std::clamp(v, 0.0F, 10000.0F / 203.0F));
                            }
                        }
                        out[3] = 0x3C00U;  // alpha 1.0
                        if (sdr != nullptr) {
                            auto* b = base.data() + (row + x) * 4U;
                            if (sdr_tile != nullptr) std::memcpy(b, sdr_tile->data() + (static_cast<std::size_t>(y) * kTileEdge + x) * 4U, 3U);
                            b[3] = 255;
                        }
                    }
                }
            }
        }
        std::unique_ptr<uhdr_codec_private_t, EncoderDeleter> encoder(uhdr_create_encoder());
        if (!encoder) {
            error = "out of memory";
            return std::nullopt;
        }
        uhdr_raw_image_t hdr_image{};
        hdr_image.fmt = UHDR_IMG_FMT_64bppRGBAHalfFloat;
        hdr_image.cg = UHDR_CG_BT_709;
        hdr_image.ct = UHDR_CT_LINEAR;
        hdr_image.range = UHDR_CR_FULL_RANGE;
        hdr_image.w = w;
        hdr_image.h = h;
        hdr_image.planes[UHDR_PLANE_PACKED] = hdr.data();
        hdr_image.stride[UHDR_PLANE_PACKED] = w;
        auto check = [&](const uhdr_error_info_t& info) {
            if (info.error_code == UHDR_CODEC_OK) return true;
            error = detail(info);
            return false;
        };
        if (!check(uhdr_enc_set_raw_image(encoder.get(), &hdr_image, UHDR_HDR_IMG))) return std::nullopt;
        if (sdr != nullptr) {
            uhdr_raw_image_t sdr_image{};
            sdr_image.fmt = UHDR_IMG_FMT_32bppRGBA8888;
            sdr_image.cg = UHDR_CG_BT_709;
            sdr_image.ct = UHDR_CT_SRGB;
            sdr_image.range = UHDR_CR_FULL_RANGE;
            sdr_image.w = w;
            sdr_image.h = h;
            sdr_image.planes[UHDR_PLANE_PACKED] = base.data();
            sdr_image.stride[UHDR_PLANE_PACKED] = w;
            if (!check(uhdr_enc_set_raw_image(encoder.get(), &sdr_image, UHDR_SDR_IMG))) return std::nullopt;
        }
        if (!check(uhdr_enc_set_quality(encoder.get(), options.base_quality, UHDR_BASE_IMG)) ||
            !check(uhdr_enc_set_quality(encoder.get(), options.gain_map_quality, UHDR_GAIN_MAP_IMG)) ||
            !check(uhdr_enc_set_using_multi_channel_gainmap(encoder.get(), options.multi_channel_gain_map ? 1 : 0)) ||
            !check(uhdr_enc_set_target_display_peak_brightness(encoder.get(), options.target_peak_nits)))
            return std::nullopt;
        if (!options.exif.empty()) {
            uhdr_mem_block_t exif{const_cast<std::uint8_t*>(options.exif.data()), options.exif.size(), options.exif.size()};
            if (!check(uhdr_enc_set_exif_data(encoder.get(), &exif))) return std::nullopt;
        }
        if (!check(uhdr_encode(encoder.get()))) return std::nullopt;
        const auto* stream = uhdr_get_encoded_stream(encoder.get());
        if (stream == nullptr || stream->data == nullptr) {
            error = "libultrahdr produced no output";
            return std::nullopt;
        }
        const auto* data = static_cast<const std::uint8_t*>(stream->data);
        return std::vector<std::uint8_t>(data, data + stream->data_sz);
    } catch (const std::exception&) {
        error = "not enough memory for Ultra HDR export";
        return std::nullopt;
    }
}

std::optional<TileStore> decode(std::span<const std::uint8_t> bytes, std::string& error, std::uint64_t max_pixels) {
    if (!isUltraHdr(bytes)) {
        error = "not an Ultra HDR JPEG";
        return std::nullopt;
    }
    try {
        std::unique_ptr<uhdr_codec_private_t, DecoderDeleter> decoder(uhdr_create_decoder());
        if (!decoder) {
            error = "out of memory";
            return std::nullopt;
        }
        uhdr_compressed_image_t input{};
        input.data = const_cast<std::uint8_t*>(bytes.data());
        input.data_sz = bytes.size();
        input.capacity = bytes.size();
        input.cg = UHDR_CG_UNSPECIFIED;
        input.ct = UHDR_CT_UNSPECIFIED;
        input.range = UHDR_CR_UNSPECIFIED;
        auto check = [&](const uhdr_error_info_t& info) {
            if (info.error_code == UHDR_CODEC_OK) return true;
            error = detail(info);
            return false;
        };
        if (!check(uhdr_dec_set_image(decoder.get(), &input)) || !check(uhdr_dec_set_out_img_format(decoder.get(), UHDR_IMG_FMT_64bppRGBAHalfFloat)) ||
            !check(uhdr_dec_set_out_color_transfer(decoder.get(), UHDR_CT_LINEAR)) ||
            !check(uhdr_dec_set_out_max_display_boost(decoder.get(), 10000.0F / 203.0F)) || !check(uhdr_dec_probe(decoder.get())))
            return std::nullopt;
        const auto w = static_cast<std::uint64_t>(std::max(0, uhdr_dec_get_image_width(decoder.get())));
        const auto h = static_cast<std::uint64_t>(std::max(0, uhdr_dec_get_image_height(decoder.get())));
        if (w == 0U || h == 0U || w * h > max_pixels) {
            error = "Ultra HDR image is empty or larger than the pixel limit";
            return std::nullopt;
        }
        if (!check(uhdr_decode(decoder.get()))) return std::nullopt;
        const auto* image = uhdr_get_decoded_image(decoder.get());
        if (image == nullptr || image->fmt != UHDR_IMG_FMT_64bppRGBAHalfFloat || image->w != w || image->h != h) {
            error = "libultrahdr returned an unexpected image";
            return std::nullopt;
        }
        TileStore out(PixelFormat::RGBA32F, static_cast<std::uint32_t>(w), static_cast<std::uint32_t>(h));
        for (std::uint32_t ty = 0; ty * kTileEdge < h; ++ty) {
            for (std::uint32_t tx = 0; tx * kTileEdge < w; ++tx) {
                TileBuffer tile(out.tile_byte_size(), 0);
                const auto rows = std::min<std::uint32_t>(kTileEdge, static_cast<std::uint32_t>(h) - ty * kTileEdge);
                const auto cols = std::min<std::uint32_t>(kTileEdge, static_cast<std::uint32_t>(w) - tx * kTileEdge);
                for (std::uint32_t y = 0; y < rows; ++y) {
                    const auto* row = static_cast<const std::uint16_t*>(image->planes[UHDR_PLANE_PACKED]) +
                                      (static_cast<std::size_t>(ty * kTileEdge + y) * image->stride[UHDR_PLANE_PACKED] + static_cast<std::size_t>(tx) * kTileEdge) * 4U;
                    for (std::uint32_t x = 0; x < cols; ++x) {
                        float px[4] = {fromHalf(row[x * 4]), fromHalf(row[x * 4 + 1]), fromHalf(row[x * 4 + 2]), 1.0F};
                        for (auto& v : px) if (!std::isfinite(v)) v = 0.0F;
                        std::memcpy(tile.data() + (static_cast<std::size_t>(y) * kTileEdge + x) * 16U, px, 16U);
                    }
                }
                if (!out.writeTile({static_cast<std::int32_t>(tx), static_cast<std::int32_t>(ty)}, tile)) {
                    error = "could not store the decoded image";
                    return std::nullopt;
                }
            }
        }
        return out;
    } catch (const std::exception&) {
        error = "not enough memory to decode Ultra HDR";
        return std::nullopt;
    }
}

}  // namespace croptop::ultrahdr
