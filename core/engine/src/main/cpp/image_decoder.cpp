#include "image_decoder.h"
#include "tile_store.h"
#include "tile_band_writer.h"

#include <algorithm>
#include <array>
#include <bit>
#include <csetjmp>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <memory>
#include <limits>
#include <unordered_map>

#include <jerror.h>
#include <jpeglib.h>
#include <png.h>

namespace croptop {
namespace {

struct DecodeState final {
    bool success;
    std::uint32_t width;
    std::uint32_t height;
    std::uint8_t bit_depth;
    char format[8];
    char error[256];
};

DecodeState failed(const char* message) {
    DecodeState state{};
    std::snprintf(state.error, sizeof(state.error), "%s", message);
    return state;
}

DecodeResult publicResult(const DecodeState& state) {
    DecodeResult result{};
    result.success = state.success;
    result.raster = {state.width, state.height, state.format, state.bit_depth};
    result.error = state.error;
    return result;
}

bool validDimensions(std::uint32_t width, std::uint32_t height, std::uint32_t max_dimension, std::uint64_t max_pixels) {
    return width > 0 && height > 0 && width <= max_dimension && height <= max_dimension &&
           static_cast<std::uint64_t>(width) * height <= max_pixels;
}


std::uint16_t little16(std::span<const std::uint8_t> bytes, std::size_t offset) {
    return static_cast<std::uint16_t>(bytes[offset]) |
           static_cast<std::uint16_t>(static_cast<std::uint16_t>(bytes[offset + 1U]) << 8U);
}

std::uint32_t little32(std::span<const std::uint8_t> bytes, std::size_t offset) {
    return static_cast<std::uint32_t>(bytes[offset]) |
           (static_cast<std::uint32_t>(bytes[offset + 1U]) << 8U) |
           (static_cast<std::uint32_t>(bytes[offset + 2U]) << 16U) |
           (static_cast<std::uint32_t>(bytes[offset + 3U]) << 24U);
}

DecodeState decodeBmpRows(std::span<const std::uint8_t> encoded, const RgbaScanlineSink& sink,
                          std::uint32_t max_dimension, std::uint64_t max_pixels) {
    if (encoded.size() < 54U || encoded[0] != 'B' || encoded[1] != 'M') return failed("invalid BMP header");
    const auto pixel_offset = little32(encoded, 10U);
    const auto header_size = little32(encoded, 14U);
    if (header_size < 40U || static_cast<std::uint64_t>(14U) + header_size > encoded.size()) {
        return failed("unsupported or truncated BMP header");
    }
    const auto width_signed = std::bit_cast<std::int32_t>(little32(encoded, 18U));
    const auto height_signed = std::bit_cast<std::int32_t>(little32(encoded, 22U));
    const auto planes = little16(encoded, 26U);
    const auto bits_per_pixel = little16(encoded, 28U);
    const auto compression = little32(encoded, 30U);
    const bool rle8 = bits_per_pixel == 8U && compression == 1U;
    const bool rle4 = bits_per_pixel == 4U && compression == 2U;
    const bool bitfields = compression == 3U || compression == 6U;
    const bool rgb16 = bits_per_pixel == 16U && compression == 0U;
    const bool rgb24or32 = (bits_per_pixel == 24U || bits_per_pixel == 32U) && compression == 0U;
    const bool indexed = compression == 0U && (bits_per_pixel == 1U || bits_per_pixel == 4U || bits_per_pixel == 8U);
    if (width_signed <= 0 || height_signed == 0 || height_signed == std::numeric_limits<std::int32_t>::min() ||
        planes != 1U || (!rgb24or32 && !rgb16 && !indexed && !rle8 && !rle4 &&
                         !(bitfields && (bits_per_pixel == 16U || bits_per_pixel == 32U))) ||
        (compression != 0U && !rle8 && !rle4 && !bitfields) || ((rle8 || rle4) && height_signed < 0)) {
        return failed("unsupported BMP dimensions, depth or compression");
    }
    const auto width = static_cast<std::uint32_t>(width_signed);
    const auto height = static_cast<std::uint32_t>(height_signed < 0 ? -static_cast<std::int64_t>(height_signed)
                                                                    : height_signed);
    if (!validDimensions(width, height, max_dimension, max_pixels)) return failed("BMP dimensions exceed decoder limits");

    if (rle8 || rle4) {
        const auto colors_used = little32(encoded, 46U);
        const auto maximum_palette_entries = rle4 ? 16U : 256U;
        const auto palette_entries = colors_used == 0 ? maximum_palette_entries : colors_used;
        const auto palette_start = static_cast<std::uint64_t>(14U) + header_size;
        const auto palette_end = palette_start + static_cast<std::uint64_t>(palette_entries) * 4U;
        if (palette_entries == 0 || palette_entries > maximum_palette_entries || palette_end > pixel_offset ||
            pixel_offset > encoded.size()) {
            return failed("invalid BMP RLE palette or pixel offset");
        }
        std::vector<std::array<std::uint8_t, 4>> palette(palette_entries);
        for (std::uint32_t index = 0; index < palette_entries; ++index) {
            const auto offset = static_cast<std::size_t>(palette_start) + static_cast<std::size_t>(index) * 4U;
            palette[index] = {encoded[offset + 2U], encoded[offset + 1U], encoded[offset], 255U};
        }
        try {
            std::vector<std::uint8_t> rgba(static_cast<std::size_t>(width) * 4U);
            const auto reset_row = [&] {
                for (std::uint32_t x = 0; x < width; ++x) {
                    std::copy(palette[0].begin(), palette[0].end(), rgba.begin() + static_cast<std::ptrdiff_t>(x * 4U));
                }
            };
            reset_row();
            std::uint32_t x = 0;
            std::uint32_t y = 0;
            std::size_t cursor = pixel_offset;
            bool ended = false;
            const auto flush_row = [&]() {
                if (y >= height || !sink(height - 1U - y, rgba)) return false;
                ++y;
                x = 0;
                reset_row();
                return true;
            };
            const auto write_index = [&](std::uint8_t index) {
                if (index >= palette_entries || x >= width || y >= height) return false;
                std::copy(palette[index].begin(), palette[index].end(),
                          rgba.begin() + static_cast<std::ptrdiff_t>(x * 4U));
                ++x;
                return true;
            };
            while (cursor + 1U < encoded.size() && !ended) {
                const auto count = encoded[cursor++];
                const auto value = encoded[cursor++];
                if (count != 0) {
                    if (y >= height || count > width - x) return failed("BMP RLE run exceeds row bounds");
                    for (std::uint32_t run = 0; run < count; ++run) {
                        const auto index = rle4 ? static_cast<std::uint8_t>((run & 1U) == 0 ? value >> 4U : value & 0x0fU)
                                                : value;
                        if (!write_index(index)) return failed("BMP RLE palette index is out of range");
                    }
                    continue;
                }
                if (value == 0) {
                    if (!flush_row()) return failed("BMP RLE8 row sink failed or row count exceeded image bounds");
                } else if (value == 1) {
                    ended = true;
                } else if (value == 2) {
                    if (cursor + 1U >= encoded.size()) return failed("truncated BMP RLE delta command");
                    const auto dx = encoded[cursor++];
                    const auto dy = encoded[cursor++];
                    if (dx > width - x || dy >= height - y) return failed("BMP RLE delta exceeds image bounds");
                    for (std::uint32_t skipped = 0; skipped < dy; ++skipped) {
                        if (!flush_row()) return failed("BMP RLE delta row sink failed");
                    }
                    x += dx;
                } else {
                    const auto absolute_count = static_cast<std::uint32_t>(value);
                    const auto data_bytes = rle4 ? (absolute_count + 1U) / 2U : absolute_count;
                    if (absolute_count > width - x || data_bytes > encoded.size() - cursor) {
                        return failed("BMP RLE absolute run exceeds input or row bounds");
                    }
                    for (std::uint32_t pixel = 0; pixel < absolute_count; ++pixel) {
                        const auto packed = encoded[cursor + (rle4 ? pixel / 2U : pixel)];
                        const auto index = rle4 ? static_cast<std::uint8_t>((pixel & 1U) == 0 ? packed >> 4U : packed & 0x0fU)
                                                : packed;
                        if (!write_index(index)) return failed("BMP RLE palette index is out of range");
                    }
                    cursor += data_bytes;
                    if ((data_bytes & 1U) != 0) {
                        if (cursor >= encoded.size()) return failed("truncated BMP RLE absolute-run padding");
                        ++cursor;
                    }
                }
            }
            if (!ended) return failed("BMP RLE stream has no end-of-bitmap marker");
            while (y < height) {
                if (!flush_row()) return failed("BMP RLE sink failed while completing image rows");
            }
        } catch (...) {
            return failed("BMP RLE row allocation or sink failed");
        }
        DecodeState state{};
        state.success = true;
        state.width = width;
        state.height = height;
        state.bit_depth = 8;
        std::snprintf(state.format, sizeof(state.format), "bmp");
        return state;
    }

    if (indexed) {
        const auto maximum_palette_entries = 1U << bits_per_pixel;
        const auto colors_used = little32(encoded, 46U);
        const auto palette_entries = colors_used == 0 ? maximum_palette_entries : colors_used;
        const auto palette_start = static_cast<std::uint64_t>(14U) + header_size;
        const auto palette_end = palette_start + static_cast<std::uint64_t>(palette_entries) * 4U;
        if (palette_entries == 0 || palette_entries > maximum_palette_entries || palette_end > pixel_offset ||
            pixel_offset > encoded.size()) {
            return failed("invalid indexed BMP palette or pixel offset");
        }
        const auto row_stride = ((static_cast<std::uint64_t>(width) * bits_per_pixel + 31U) / 32U) * 4U;
        const auto pixel_end = static_cast<std::uint64_t>(pixel_offset) + row_stride * height;
        if (pixel_end > encoded.size()) return failed("truncated indexed BMP pixels");
        std::vector<std::array<std::uint8_t, 4>> palette(palette_entries);
        for (std::uint32_t index = 0; index < palette_entries; ++index) {
            const auto offset = static_cast<std::size_t>(palette_start) + static_cast<std::size_t>(index) * 4U;
            palette[index] = {encoded[offset + 2U], encoded[offset + 1U], encoded[offset], 255U};
        }
        try {
            std::vector<std::uint8_t> rgba(static_cast<std::size_t>(width) * 4U);
            for (std::uint32_t y = 0; y < height; ++y) {
                const auto source_y = height_signed < 0 ? y : height - 1U - y;
                const auto* source = encoded.data() + pixel_offset + static_cast<std::size_t>(row_stride * source_y);
                for (std::uint32_t x = 0; x < width; ++x) {
                    const auto packed = source[(static_cast<std::uint64_t>(x) * bits_per_pixel) / 8U];
                    const auto shift = bits_per_pixel == 1U ? 7U - (x & 7U) :
                                       bits_per_pixel == 4U ? ((x & 1U) == 0 ? 4U : 0U) : 0U;
                    const auto index = static_cast<std::uint32_t>((packed >> shift) & (maximum_palette_entries - 1U));
                    if (index >= palette_entries) return failed("indexed BMP palette index is out of range");
                    std::copy(palette[index].begin(), palette[index].end(),
                              rgba.begin() + static_cast<std::ptrdiff_t>(x * 4U));
                }
                if (!sink(y, rgba)) return failed("indexed BMP scanline sink rejected row");
            }
        } catch (...) {
            return failed("indexed BMP row allocation or sink failed");
        }
        DecodeState state{};
        state.success = true;
        state.width = width;
        state.height = height;
        state.bit_depth = 8;
        std::snprintf(state.format, sizeof(state.format), "bmp");
        return state;
    }

    std::array<std::uint32_t, 4> channel_masks{};
    bool has_alpha_mask = false;
    if (bitfields) {
        const auto mask_count = compression == 6U ? 4U : 3U;
        std::uint64_t masks_start = 14U + header_size;
        if (header_size >= 52U) {
            masks_start = 14U + 40U;
            if (header_size >= 56U && compression == 6U) has_alpha_mask = true;
            else if (compression == 6U) return failed("BMP alpha bitfields require a V3-or-later DIB header");
        } else if (pixel_offset < masks_start + static_cast<std::uint64_t>(mask_count) * 4U) {
            return failed("truncated BMP bitfield masks");
        }
        if (masks_start + static_cast<std::uint64_t>(mask_count) * 4U > encoded.size() ||
            masks_start + static_cast<std::uint64_t>(mask_count) * 4U > pixel_offset) {
            return failed("BMP bitfield masks exceed the pixel offset");
        }
        for (std::uint32_t channel = 0; channel < mask_count; ++channel) {
            channel_masks[channel] = little32(encoded, static_cast<std::size_t>(masks_start) + channel * 4U);
        }
        has_alpha_mask = mask_count == 4U;
    } else if (rgb16) {
        channel_masks = {0x7c00U, 0x03e0U, 0x001fU, 0};
    }
    if (rgb16 || bitfields) {
        const auto valid_mask = bits_per_pixel == 16U ? 0x0000ffffU : 0xffffffffU;
        std::uint32_t occupied = 0;
        for (std::uint32_t channel = 0; channel < (has_alpha_mask ? 4U : 3U); ++channel) {
            const auto mask = channel_masks[channel];
            if (mask == 0 || (mask & ~valid_mask) != 0 || (occupied & mask) != 0) {
                return failed("invalid or overlapping BMP channel masks");
            }
            const auto shift = std::countr_zero(mask);
            const auto range = mask >> shift;
            if ((range & (range + 1U)) != 0) return failed("BMP channel mask is not contiguous");
            occupied |= mask;
        }
    }

    const auto source_bytes_per_pixel = bits_per_pixel / 8U;
    const auto row_stride = ((static_cast<std::uint64_t>(width) * bits_per_pixel + 31U) / 32U) * 4U;
    const auto pixel_end = static_cast<std::uint64_t>(pixel_offset) + row_stride * height;
    if (pixel_offset < 14U + header_size || pixel_end > encoded.size()) return failed("truncated BMP pixel data");

    bool has_alpha = false;
    if (bits_per_pixel == 32U && !bitfields) {
        for (std::uint32_t y = 0; y < height && !has_alpha; ++y) {
            const auto* row = encoded.data() + pixel_offset + static_cast<std::size_t>(row_stride * y);
            for (std::uint32_t x = 0; x < width; ++x) {
                if (row[static_cast<std::size_t>(x) * 4U + 3U] != 0) {
                    has_alpha = true;
                    break;
                }
            }
        }
    }

    try {
        std::vector<std::uint8_t> rgba(static_cast<std::size_t>(width) * 4U);
        for (std::uint32_t y = 0; y < height; ++y) {
            const auto source_y = height_signed < 0 ? y : height - 1U - y;
            const auto* source = encoded.data() + pixel_offset + static_cast<std::size_t>(row_stride * source_y);
            for (std::uint32_t x = 0; x < width; ++x) {
                const auto source_index = static_cast<std::size_t>(x) * source_bytes_per_pixel;
                const auto target_index = static_cast<std::size_t>(x) * 4U;
                if (rgb16 || bitfields) {
                    const auto sample = bits_per_pixel == 16U ? little16(encoded, pixel_offset +
                        static_cast<std::size_t>(row_stride * source_y) + source_index) :
                        little32(encoded, pixel_offset + static_cast<std::size_t>(row_stride * source_y) + source_index);
                    for (std::uint32_t channel = 0; channel < 3U; ++channel) {
                        const auto mask = channel_masks[channel];
                        const auto shift = std::countr_zero(mask);
                        const auto range = mask >> shift;
                        const auto value = (sample & mask) >> shift;
                        rgba[target_index + channel] = static_cast<std::uint8_t>(
                            (static_cast<std::uint64_t>(value) * 255U + range / 2U) / range);
                    }
                    rgba[target_index + 3U] = has_alpha_mask
                        ? static_cast<std::uint8_t>(
                              (static_cast<std::uint64_t>((sample & channel_masks[3]) >> std::countr_zero(channel_masks[3])) *
                                   255U +
                               (channel_masks[3] >> std::countr_zero(channel_masks[3])) / 2U) /
                              (channel_masks[3] >> std::countr_zero(channel_masks[3])))
                        : 255U;
                } else {
                    rgba[target_index] = source[source_index + 2U];
                    rgba[target_index + 1U] = source[source_index + 1U];
                    rgba[target_index + 2U] = source[source_index];
                    rgba[target_index + 3U] = bits_per_pixel == 32U && has_alpha ? source[source_index + 3U] : 255U;
                }
            }
            if (!sink(y, rgba)) return failed("BMP scanline sink rejected decoded row");
        }
    } catch (...) {
        return failed("BMP scanline sink or row allocation failed");
    }
    DecodeState state{};
    state.success = true;
    state.width = width;
    state.height = height;
    state.bit_depth = 8;
    std::snprintf(state.format, sizeof(state.format), "bmp");
    return state;
}

struct JpegError final {
    jpeg_error_mgr manager;
    std::jmp_buf jump;
    char message[JMSG_LENGTH_MAX];
};

struct JpegWork final {
    jpeg_decompress_struct decoder;
    JpegError error;
    std::uint8_t* row;
    std::uint8_t* rgba;
    bool created;
};

extern "C" void jpegFailure(j_common_ptr common) {
    auto* error = reinterpret_cast<JpegError*>(common->err);
    (*common->err->format_message)(common, error->message);
    std::longjmp(error->jump, 1);
}

extern "C" void jpegQuietOutput(j_common_ptr) {}

void destroyJpeg(JpegWork* work) {
    if (work->created) jpeg_destroy_decompress(&work->decoder);
    std::free(work->row);
    std::free(work->rgba);
    std::free(work);
}

DecodeState decodeJpegRows(std::span<const std::uint8_t> encoded, const RgbaScanlineSink& sink,
                           std::uint32_t max_dimension, std::uint64_t max_pixels) {
    auto* work = static_cast<JpegWork*>(std::calloc(1, sizeof(JpegWork)));
    if (work == nullptr) return failed("not enough memory to initialize JPEG decoder");
    work->decoder.err = jpeg_std_error(&work->error.manager);
    work->error.manager.error_exit = jpegFailure;
    work->error.manager.output_message = jpegQuietOutput;
    if (setjmp(work->error.jump) != 0) {
        if (work->created) jpeg_destroy_decompress(&work->decoder);
        std::free(work->row);
        std::free(work->rgba);
        auto state = failed(work->error.message[0] == '\0' ? "invalid JPEG data" : work->error.message);
        std::free(work);
        return state;
    }

    jpeg_create_decompress(&work->decoder);
    work->created = true;
    jpeg_mem_src(&work->decoder, encoded.data(), static_cast<unsigned long>(encoded.size()));
    if (jpeg_read_header(&work->decoder, TRUE) != JPEG_HEADER_OK) {
        destroyJpeg(work);
        return failed("invalid JPEG header");
    }
    const auto source_width = static_cast<std::uint32_t>(work->decoder.image_width);
    const auto source_height = static_cast<std::uint32_t>(work->decoder.image_height);
    if (!validDimensions(source_width, source_height, max_dimension, max_pixels)) {
        destroyJpeg(work);
        return failed("JPEG dimensions exceed decoder limits");
    }
    work->decoder.out_color_space = JCS_RGB;
    jpeg_start_decompress(&work->decoder);
    const auto width = static_cast<std::uint32_t>(work->decoder.output_width);
    const auto height = static_cast<std::uint32_t>(work->decoder.output_height);
    if (work->decoder.output_components != 3 || !validDimensions(width, height, max_dimension, max_pixels)) {
        destroyJpeg(work);
        return failed("JPEG color layout exceeds decoder limits");
    }

    work->row = static_cast<std::uint8_t*>(std::malloc(static_cast<std::size_t>(width) * 3U));
    work->rgba = static_cast<std::uint8_t*>(std::malloc(static_cast<std::size_t>(width) * 4U));
    if (work->row == nullptr || work->rgba == nullptr) {
        destroyJpeg(work);
        return failed("not enough memory for JPEG scanline");
    }
    while (work->decoder.output_scanline < work->decoder.output_height) {
        JSAMPROW row_pointer = work->row;
        const auto y = static_cast<std::uint32_t>(work->decoder.output_scanline);
        if (jpeg_read_scanlines(&work->decoder, &row_pointer, 1) != 1) ERREXIT(&work->decoder, JERR_INPUT_EOF);
        for (std::uint32_t x = 0; x < width; ++x) {
            work->rgba[x * 4U] = work->row[x * 3U];
            work->rgba[x * 4U + 1U] = work->row[x * 3U + 1U];
            work->rgba[x * 4U + 2U] = work->row[x * 3U + 2U];
            work->rgba[x * 4U + 3U] = 255;
        }
        try {
            if (!sink(y, {work->rgba, static_cast<std::size_t>(width) * 4U})) {
                destroyJpeg(work);
                return failed("JPEG scanline sink rejected decoded row");
            }
        } catch (...) {
            destroyJpeg(work);
            return failed("JPEG scanline sink failed");
        }
    }
    jpeg_finish_decompress(&work->decoder);
    destroyJpeg(work);
    DecodeState state{};
    state.success = true;
    state.width = width;
    state.height = height;
    state.bit_depth = 8;
    std::snprintf(state.format, sizeof(state.format), "jpeg");
    return state;
}

struct PngReader final {
    const std::uint8_t* bytes;
    std::size_t length;
    std::size_t offset;
    char message[128];
};

struct PngWork final {
    png_structp decoder;
    png_infop info;
    PngReader reader;
    std::uint8_t* rgba;
};

void pngFailure(png_structp png, png_const_charp message) {
    auto* reader = static_cast<PngReader*>(png_get_error_ptr(png));
    if (reader != nullptr && message != nullptr) std::snprintf(reader->message, sizeof(reader->message), "%s", message);
    png_longjmp(png, 1);
}

void pngWarning(png_structp, png_const_charp) {}

void pngRead(png_structp png, png_bytep output, png_size_t count) {
    auto* reader = static_cast<PngReader*>(png_get_io_ptr(png));
    if (count > reader->length - reader->offset) {
        png_error(png, "truncated PNG input");
    }
    std::memcpy(output, reader->bytes + reader->offset, count);
    reader->offset += count;
}

void destroyPng(PngWork* work) {
    png_destroy_read_struct(&work->decoder, &work->info, nullptr);
    std::free(work->rgba);
    std::free(work);
}

DecodeState decodePngRows(std::span<const std::uint8_t> encoded, const RgbaScanlineSink& sink,
                          std::uint32_t max_dimension, std::uint64_t max_pixels) {
    auto* work = static_cast<PngWork*>(std::calloc(1, sizeof(PngWork)));
    if (work == nullptr) return failed("not enough memory to initialize PNG decoder");
    work->reader = {encoded.data(), encoded.size(), 0, {}};
    work->decoder = png_create_read_struct(PNG_LIBPNG_VER_STRING, &work->reader, pngFailure, pngWarning);
    if (work->decoder == nullptr) {
        std::free(work);
        return failed("unable to initialize PNG decoder");
    }
    work->info = png_create_info_struct(work->decoder);
    if (work->info == nullptr) {
        png_destroy_read_struct(&work->decoder, nullptr, nullptr);
        std::free(work);
        return failed("unable to initialize PNG metadata");
    }
    if (setjmp(png_jmpbuf(work->decoder)) != 0) {
        auto state = failed(work->reader.message[0] == '\0' ? "invalid PNG data" : work->reader.message);
        destroyPng(work);
        return state;
    }

    png_set_read_fn(work->decoder, &work->reader, pngRead);
    png_read_info(work->decoder, work->info);
    const auto width = png_get_image_width(work->decoder, work->info);
    const auto height = png_get_image_height(work->decoder, work->info);
    if (!validDimensions(width, height, max_dimension, max_pixels)) {
        destroyPng(work);
        return failed("PNG dimensions exceed decoder limits");
    }
    if (png_get_interlace_type(work->decoder, work->info) != PNG_INTERLACE_NONE) {
        destroyPng(work);
        return failed("interlaced PNG is not supported by the streaming decoder");
    }

    const auto color_type = png_get_color_type(work->decoder, work->info);
    const auto bit_depth = png_get_bit_depth(work->decoder, work->info);
    if (color_type == PNG_COLOR_TYPE_PALETTE) png_set_palette_to_rgb(work->decoder);
    if (color_type == PNG_COLOR_TYPE_GRAY && bit_depth < 8) png_set_expand_gray_1_2_4_to_8(work->decoder);
    if (png_get_valid(work->decoder, work->info, PNG_INFO_tRNS) != 0) png_set_tRNS_to_alpha(work->decoder);
    if (color_type == PNG_COLOR_TYPE_GRAY || color_type == PNG_COLOR_TYPE_GRAY_ALPHA) png_set_gray_to_rgb(work->decoder);
    if ((color_type & PNG_COLOR_MASK_ALPHA) == 0 && png_get_valid(work->decoder, work->info, PNG_INFO_tRNS) == 0) {
        png_set_filler(work->decoder, bit_depth == 16 ? 0xffff : 0xff, PNG_FILLER_AFTER);
    }
    png_read_update_info(work->decoder, work->info);
    const auto output_bit_depth = png_get_bit_depth(work->decoder, work->info);
    const auto bytes_per_sample = output_bit_depth == 16 ? 2U : 1U;
    if (png_get_channels(work->decoder, work->info) != 4 ||
        png_get_rowbytes(work->decoder, work->info) != static_cast<png_size_t>(width) * 4U * bytes_per_sample) {
        destroyPng(work);
        return failed("PNG could not be converted to RGBA8 scanlines");
    }

    work->rgba = static_cast<std::uint8_t*>(
        std::malloc(static_cast<std::size_t>(width) * 4U * bytes_per_sample));
    if (work->rgba == nullptr) {
        destroyPng(work);
        return failed("not enough memory for PNG scanline");
    }
    for (std::uint32_t y = 0; y < height; ++y) {
        png_read_row(work->decoder, work->rgba, nullptr);
        if (output_bit_depth == 16) {
            const auto row_bytes = static_cast<std::size_t>(width) * 4U * bytes_per_sample;
            for (std::size_t offset = 0; offset < row_bytes; offset += 2U) {
                std::swap(work->rgba[offset], work->rgba[offset + 1U]);
            }
        }
        try {
            if (!sink(y, {work->rgba, static_cast<std::size_t>(width) * 4U * bytes_per_sample})) {
                destroyPng(work);
                return failed("PNG scanline sink rejected decoded row");
            }
        } catch (...) {
            destroyPng(work);
            return failed("PNG scanline sink failed");
        }
    }
    png_read_end(work->decoder, work->info);
    destroyPng(work);
    DecodeState state{};
    state.success = true;
    state.width = width;
    state.height = height;
    state.bit_depth = static_cast<std::uint8_t>(output_bit_depth);
    std::snprintf(state.format, sizeof(state.format), "png");
    return state;
}

}  // namespace

DecodeResult decodeJpeg(std::span<const std::uint8_t> encoded, const RgbaScanlineSink& sink,
                        std::uint32_t max_dimension, std::uint64_t max_pixels) {
    if (encoded.empty() || !sink) return publicResult(failed("JPEG input and scanline sink are required"));
    return publicResult(decodeJpegRows(encoded, sink, max_dimension, max_pixels));
}

DecodeResult decodePng(std::span<const std::uint8_t> encoded, const RgbaScanlineSink& sink,
                       std::uint32_t max_dimension, std::uint64_t max_pixels) {
    if (encoded.empty() || !sink) return publicResult(failed("PNG input and scanline sink are required"));
    return publicResult(decodePngRows(encoded, sink, max_dimension, max_pixels));
}

DecodeResult decodeBmp(std::span<const std::uint8_t> encoded, const RgbaScanlineSink& sink,
                       std::uint32_t max_dimension, std::uint64_t max_pixels) {
    if (encoded.empty() || !sink) return publicResult(failed("BMP input and scanline sink are required"));
    return publicResult(decodeBmpRows(encoded, sink, max_dimension, max_pixels));
}

DecodeResult decodeJpegToTiles(std::span<const std::uint8_t> encoded, TileStore& destination,
                               std::uint32_t max_dimension, std::uint64_t max_pixels, std::uint8_t orientation) {
    if (destination.format() != PixelFormat::RGBA8) {
        return publicResult(failed("JPEG requires an RGBA8 destination tile store"));
    }
    if (orientation < 1 || orientation > 8) return publicResult(failed("EXIF orientation must be between 1 and 8"));
    detail::TileBandWriter writer(destination, orientation);
    auto result = decodeJpeg(encoded, [&](std::uint32_t y, std::span<const std::uint8_t> row) {
        return writer.write(y, row);
    }, max_dimension, max_pixels);
    if (result.success && !writer.complete()) return publicResult(failed("JPEG dimensions do not match tile store"));
    return result;
}

DecodeResult decodePngToTiles(std::span<const std::uint8_t> encoded, TileStore& destination,
                              std::uint32_t max_dimension, std::uint64_t max_pixels, std::uint8_t orientation) {
    if (destination.format() != PixelFormat::RGBA8 && destination.format() != PixelFormat::RGBA16) {
        return publicResult(failed("PNG requires an RGBA8 or RGBA16 destination tile store"));
    }
    if (orientation < 1 || orientation > 8) return publicResult(failed("EXIF orientation must be between 1 and 8"));
    detail::TileBandWriter writer(destination, orientation);
    auto result = decodePng(encoded, [&](std::uint32_t y, std::span<const std::uint8_t> row) {
        return writer.write(y, row);
    }, max_dimension, max_pixels);
    if (result.success && !writer.complete()) return publicResult(failed("PNG dimensions do not match tile store"));
    if (result.success && result.raster.bit_depth != destination.bytes_per_pixel() * 2U) {
        return publicResult(failed("PNG sample depth does not match tile store"));
    }
    return result;
}

DecodeResult decodeBmpToTiles(std::span<const std::uint8_t> encoded, TileStore& destination,
                              std::uint32_t max_dimension, std::uint64_t max_pixels) {
    if (destination.format() != PixelFormat::RGBA8) {
        return publicResult(failed("BMP requires an RGBA8 destination tile store"));
    }
    const bool reverse_rows = encoded.size() >= 34U && encoded[0] == 'B' && encoded[1] == 'M' &&
                              (little32(encoded, 30U) == 1U || little32(encoded, 30U) == 2U);
    detail::TileBandWriter writer(destination, 1, reverse_rows);
    auto result = decodeBmp(encoded, [&](std::uint32_t y, std::span<const std::uint8_t> row) {
        return writer.write(y, row);
    }, max_dimension, max_pixels);
    if (result.success && !writer.complete()) return publicResult(failed("BMP dimensions do not match tile store"));
    return result;
}

namespace {

struct PngWriteState final {
    const EncodedBytesSink* sink = nullptr;
    bool sink_failed = false;
};

void writePngBytes(png_structp png, png_bytep data, png_size_t size) {
    auto* state = static_cast<PngWriteState*>(png_get_io_ptr(png));
    try {
        if (!(*state->sink)(std::span<const std::uint8_t>(data, size))) {
            state->sink_failed = true;
            return;
        }
    } catch (...) {
        state->sink_failed = true;
        return;
    }
}

void flushPngBytes(png_structp) {}

}  // namespace

EncodeResult encodePngFromTiles(const TileStore& source, int compression_level, const PngEncodeMetadata& metadata) {
    EncodeResult result{};
    try {
        const EncodedBytesSink sink = [&result](std::span<const std::uint8_t> bytes) {
            result.encoded.insert(result.encoded.end(), bytes.begin(), bytes.end());
            return true;
        };
        auto streamed = encodePngToSink(source, sink, compression_level, metadata);
        result.success = streamed.success;
        result.error = std::move(streamed.error);
        if (!result.success) result.encoded.clear();
    } catch (const std::exception&) {
        result.success = false;
        result.encoded.clear();
        result.error = "could not allocate encoded PNG output";
    }
    return result;
}

EncodeResult encodePngToSink(const TileStore& source, const EncodedBytesSink& sink, int compression_level,
                             const PngEncodeMetadata& metadata) {
    EncodeResult result{};
    if ((source.format() != PixelFormat::RGBA8 && source.format() != PixelFormat::RGBA16) ||
        source.width() == 0 || source.height() == 0 || !sink || compression_level < 0 || compression_level > 9) {
        result.error = "PNG encoding requires a non-empty RGBA8/RGBA16 store and compression level 0..9";
        return result;
    }

    // This local is read on both sides of libpng calls that can longjmp to the setjmp below.
    volatile int bit_depth = source.format() == PixelFormat::RGBA8 ? 8 : 16;
    const std::size_t bytes_per_pixel = source.bytes_per_pixel();
    const auto row_bytes = static_cast<std::size_t>(source.width()) * bytes_per_pixel;
    std::vector<std::uint8_t> row;
    try {
        row.assign(row_bytes, 0);
    } catch (const std::exception&) {
        result.error = "could not allocate PNG scanline";
        return result;
    }
    png_structp png = png_create_write_struct(PNG_LIBPNG_VER_STRING, nullptr, nullptr, nullptr);
    if (png == nullptr) {
        result.error = "could not allocate PNG writer";
        return result;
    }
    png_infop info = png_create_info_struct(png);
    if (info == nullptr) {
        png_destroy_write_struct(&png, nullptr);
        result.error = "could not allocate PNG metadata";
        return result;
    }

    // Keep state mutated between setjmp/longjmp on the heap: modified automatic
    // variables become indeterminate after longjmp. The pointer itself is stable.
    auto write_state = std::make_unique<PngWriteState>();
    write_state->sink = &sink;
    if (setjmp(png_jmpbuf(png)) != 0) {
        png_destroy_write_struct(&png, &info);
        result.error = write_state->sink_failed ? "encoded PNG sink failed" : "PNG encoder failed";
        return result;
    }

    png_set_write_fn(png, write_state.get(), writePngBytes, flushPngBytes);
    png_set_compression_level(png, compression_level);
    png_set_IHDR(png, info, source.width(), source.height(), bit_depth, PNG_COLOR_TYPE_RGBA,
                 PNG_INTERLACE_NONE, PNG_COMPRESSION_TYPE_DEFAULT, PNG_FILTER_TYPE_DEFAULT);
    if (!metadata.icc_profile.empty() && metadata.icc_profile.size() <= std::numeric_limits<png_uint_32>::max()) {
        png_set_iCCP(png, info, "Embedded ICC profile", PNG_COMPRESSION_TYPE_BASE, metadata.icc_profile.data(),
                     static_cast<png_uint_32>(metadata.icc_profile.size()));
    }
    if (!metadata.exif_tiff.empty() && metadata.exif_tiff.size() <= std::numeric_limits<png_uint_32>::max()) {
        png_set_eXIf_1(png, info, static_cast<png_uint_32>(metadata.exif_tiff.size()),
                       const_cast<png_bytep>(metadata.exif_tiff.data()));
    }
    png_write_info(png, info);
    if (write_state->sink_failed) {
        png_destroy_write_struct(&png, &info);
        result.error = "encoded PNG sink failed";
        return result;
    }
    if (bit_depth == 16) png_set_swap(png);

    if (std::any_of(metadata.canvas_background.begin(), metadata.canvas_background.end(), [](float value) {
            return !std::isfinite(value) || value < 0.0F || value > 1.0F;
        })) {
        png_destroy_write_struct(&png, &info);
        result.error = "PNG canvas background must contain finite normalized channels";
        return result;
    }

    const auto tile_columns = 1U + (source.width() - 1U) / kTileEdge;
    for (std::uint32_t y = 0; y < source.height(); ++y) {
        const auto tile_y = static_cast<std::int32_t>(y / kTileEdge);
        const auto tile_row_offset = static_cast<std::size_t>(y % kTileEdge) * kTileEdge * bytes_per_pixel;
        for (std::uint32_t tile_x = 0; tile_x < tile_columns; ++tile_x) {
            const auto x = tile_x * kTileEdge;
            const auto pixels = std::min<std::uint32_t>(kTileEdge, source.width() - x);
            const auto segment_bytes = static_cast<std::size_t>(pixels) * bytes_per_pixel;
            auto* destination = row.data() + static_cast<std::size_t>(x) * bytes_per_pixel;
            {
                auto tile = source.readTile({static_cast<std::int32_t>(tile_x), tile_y});
                if (tile == nullptr) {
                    std::memset(destination, 0, segment_bytes);
                } else {
                    std::memcpy(destination, tile->data() + tile_row_offset, segment_bytes);
                }
            }
        }
        if (metadata.canvas_background[3] > 0.0F) {
            if (bit_depth == 8) {
                const auto bg_alpha = metadata.canvas_background[3];
                for (std::uint32_t x = 0; x < source.width(); ++x) {
                    auto* pixel = row.data() + static_cast<std::size_t>(x) * 4U;
                    const auto source_alpha = static_cast<float>(pixel[3]) / 255.0F;
                    const auto output_alpha = source_alpha + bg_alpha * (1.0F - source_alpha);
                    for (std::size_t channel = 0; channel < 3; ++channel) {
                        const auto source_value = static_cast<float>(pixel[channel]) / 255.0F;
                        const auto value = output_alpha > 0.0F
                            ? (source_value * source_alpha + metadata.canvas_background[channel] * bg_alpha * (1.0F - source_alpha)) / output_alpha
                            : 0.0F;
                        pixel[channel] = static_cast<std::uint8_t>(std::lround(std::clamp(value, 0.0F, 1.0F) * 255.0F));
                    }
                    pixel[3] = static_cast<std::uint8_t>(std::lround(output_alpha * 255.0F));
                }
            } else {
                const auto bg_alpha = metadata.canvas_background[3];
                for (std::uint32_t x = 0; x < source.width(); ++x) {
                    auto* pixel = row.data() + static_cast<std::size_t>(x) * 8U;
                    std::array<std::uint16_t, 4> rgba{};
                    std::memcpy(rgba.data(), pixel, sizeof(rgba));
                    const auto source_alpha = static_cast<float>(rgba[3]) / 65535.0F;
                    const auto output_alpha = source_alpha + bg_alpha * (1.0F - source_alpha);
                    for (std::size_t channel = 0; channel < 3; ++channel) {
                        const auto source_value = static_cast<float>(rgba[channel]) / 65535.0F;
                        const auto value = output_alpha > 0.0F
                            ? (source_value * source_alpha + metadata.canvas_background[channel] * bg_alpha * (1.0F - source_alpha)) / output_alpha
                            : 0.0F;
                        rgba[channel] = static_cast<std::uint16_t>(std::lround(std::clamp(value, 0.0F, 1.0F) * 65535.0F));
                    }
                    rgba[3] = static_cast<std::uint16_t>(std::lround(output_alpha * 65535.0F));
                    std::memcpy(pixel, rgba.data(), sizeof(rgba));
                }
            }
        }
        png_write_row(png, row.data());
        if (write_state->sink_failed) {
            png_destroy_write_struct(&png, &info);
            result.error = "encoded PNG sink failed";
            return result;
        }
    }
    png_write_end(png, info);
    if (write_state->sink_failed) {
        png_destroy_write_struct(&png, &info);
        result.error = "encoded PNG sink failed";
        return result;
    }
    png_destroy_write_struct(&png, &info);
    result.success = true;
    return result;
}

namespace {

struct JpegEncodeError final {
    jpeg_error_mgr manager;
    std::jmp_buf jump;
    char message[JMSG_LENGTH_MAX];
};

struct JpegEncodeDestination final {
    jpeg_destination_mgr manager;
    const EncodedBytesSink* sink;
    std::uint8_t buffer[16 * 1024];
    bool failed;
};

struct JpegEncodeWork final {
    JpegEncodeDestination destination;
    std::uint8_t* row;
};

void jpegEncodeFailure(j_common_ptr common) {
    auto* error = reinterpret_cast<JpegEncodeError*>(common->err);
    (*common->err->format_message)(common, error->message);
    std::longjmp(error->jump, 1);
}

void jpegInitDestination(j_compress_ptr compressor) {
    auto* destination = reinterpret_cast<JpegEncodeDestination*>(compressor->dest);
    destination->manager.next_output_byte = destination->buffer;
    destination->manager.free_in_buffer = sizeof(destination->buffer);
}

boolean jpegEmptyDestination(j_compress_ptr compressor) {
    auto* destination = reinterpret_cast<JpegEncodeDestination*>(compressor->dest);
    if (!destination->failed) {
        try {
            destination->failed = !(*destination->sink)(destination->buffer);
        } catch (...) {
            destination->failed = true;
        }
    }
    destination->manager.next_output_byte = destination->buffer;
    destination->manager.free_in_buffer = sizeof(destination->buffer);
    return TRUE;
}

void jpegFinishDestination(j_compress_ptr compressor) {
    auto* destination = reinterpret_cast<JpegEncodeDestination*>(compressor->dest);
    const auto remaining = sizeof(destination->buffer) - destination->manager.free_in_buffer;
    if (remaining != 0 && !destination->failed) {
        try {
            destination->failed = !(*destination->sink)({destination->buffer, remaining});
        } catch (...) {
            destination->failed = true;
        }
    }
}

}  // namespace

EncodeResult encodeJpegToSink(const TileStore& source, const EncodedBytesSink& sink, int quality,
                              const JpegEncodeMetadata& metadata) {
    EncodeResult result{};
    if ((source.format() != PixelFormat::RGBA8 && source.format() != PixelFormat::RGBA16) || !sink ||
        source.width() == 0 || source.height() == 0 || source.width() > 32768 || source.height() > 32768 ||
        static_cast<std::uint64_t>(source.width()) * source.height() > 500'000'000ULL || quality < 1 || quality > 100 ||
        std::any_of(metadata.canvas_background.begin(), metadata.canvas_background.end(), [](float value) {
            return !std::isfinite(value) || value < 0.0F || value > 1.0F;
        })) {
        result.error = "JPEG encoding requires bounded RGBA8/RGBA16 tiles and quality 1..100";
        return result;
    }

    auto* work = static_cast<JpegEncodeWork*>(std::calloc(1, sizeof(JpegEncodeWork)));
    if (work == nullptr) {
        result.error = "could not allocate JPEG encoder state";
        return result;
    }
    work->destination.sink = &sink;
    work->row = static_cast<std::uint8_t*>(std::malloc(static_cast<std::size_t>(source.width()) * 3U));
    if (work->row == nullptr) {
        std::free(work);
        result.error = "could not allocate JPEG scanline";
        return result;
    }

    jpeg_compress_struct compressor{};
    JpegEncodeError error{};
    compressor.err = jpeg_std_error(&error.manager);
    error.manager.error_exit = jpegEncodeFailure;
    if (setjmp(error.jump) != 0) {
        jpeg_destroy_compress(&compressor);
        std::free(work->row);
        std::free(work);
        result.error = error.message[0] == '\0' ? "JPEG encoder failed" : error.message;
        return result;
    }

    jpeg_create_compress(&compressor);
    work->destination.manager.init_destination = jpegInitDestination;
    work->destination.manager.empty_output_buffer = jpegEmptyDestination;
    work->destination.manager.term_destination = jpegFinishDestination;
    compressor.dest = &work->destination.manager;
    compressor.image_width = source.width();
    compressor.image_height = source.height();
    compressor.input_components = 3;
    compressor.in_color_space = JCS_RGB;
    jpeg_set_defaults(&compressor);
    jpeg_set_quality(&compressor, quality, TRUE);
    jpeg_start_compress(&compressor, TRUE);
    if (metadata.exif_tiff.size() > 65527U || metadata.icc_profile.size() > 255U * 65519U) {
        jpeg_abort_compress(&compressor);
        jpeg_destroy_compress(&compressor);
        std::free(work->row);
        std::free(work);
        result.error = "JPEG metadata exceeds APP marker limits";
        return result;
    }
    if (!metadata.exif_tiff.empty()) {
        std::array<JOCTET, 6> exif_signature{'E', 'x', 'i', 'f', 0, 0};
        std::array<JOCTET, 65533> exif_marker{};
        std::copy(exif_signature.begin(), exif_signature.end(), exif_marker.begin());
        std::copy(metadata.exif_tiff.begin(), metadata.exif_tiff.end(), exif_marker.begin() + 6);
        jpeg_write_marker(&compressor, JPEG_APP0 + 1, exif_marker.data(),
                          static_cast<unsigned int>(metadata.exif_tiff.size() + exif_signature.size()));
    }
    if (!metadata.icc_profile.empty()) {
        constexpr std::array<JOCTET, 12> icc_signature{'I', 'C', 'C', '_', 'P', 'R', 'O', 'F', 'I', 'L', 'E', 0};
        constexpr std::size_t kIccChunk = 65519U;
        std::array<JOCTET, 65533> icc_marker{};
        const auto chunk_count = (metadata.icc_profile.size() + kIccChunk - 1U) / kIccChunk;
        for (std::size_t chunk_index = 0; chunk_index < chunk_count; ++chunk_index) {
            const auto offset = chunk_index * kIccChunk;
            const auto count = std::min(kIccChunk, metadata.icc_profile.size() - offset);
            std::copy(icc_signature.begin(), icc_signature.end(), icc_marker.begin());
            icc_marker[12] = static_cast<JOCTET>(chunk_index + 1U);
            icc_marker[13] = static_cast<JOCTET>(chunk_count);
            std::copy_n(metadata.icc_profile.begin() + static_cast<std::ptrdiff_t>(offset), count, icc_marker.begin() + 14);
            jpeg_write_marker(&compressor, JPEG_APP0 + 2, icc_marker.data(), static_cast<unsigned int>(14U + count));
        }
    }

    const auto bytes_per_pixel = source.bytes_per_pixel();
    const auto tile_columns = 1U + (source.width() - 1U) / kTileEdge;
    for (std::uint32_t y = 0; y < source.height() && !work->destination.failed; ++y) {
        const auto tile_y = static_cast<std::int32_t>(y / kTileEdge);
        const auto tile_row_offset = static_cast<std::size_t>(y % kTileEdge) * kTileEdge * bytes_per_pixel;
        for (std::uint32_t tile_x = 0; tile_x < tile_columns; ++tile_x) {
            const auto x = tile_x * kTileEdge;
            const auto pixels = std::min<std::uint32_t>(kTileEdge, source.width() - x);
            {
                auto tile = source.readTile({static_cast<std::int32_t>(tile_x), tile_y});
                for (std::uint32_t pixel = 0; pixel < pixels; ++pixel) {
                    const auto source_offset = tile_row_offset + static_cast<std::size_t>(pixel) * bytes_per_pixel;
                    const auto output_offset = static_cast<std::size_t>(x + pixel) * 3U;
                    std::array<float, 3> channels{};
                    float alpha = 0.0F;
                    if (tile != nullptr) {
                        if (source.format() == PixelFormat::RGBA8) {
                            for (std::size_t channel = 0; channel < 3; ++channel) {
                                channels[channel] = static_cast<float>(tile->at(source_offset + channel)) / 255.0F;
                            }
                            alpha = static_cast<float>(tile->at(source_offset + 3U)) / 255.0F;
                        } else {
                            std::array<std::uint16_t, 4> rgba{};
                            std::memcpy(rgba.data(), tile->data() + source_offset, sizeof(rgba));
                            alpha = static_cast<float>(rgba[3]) / 65535.0F;
                            for (std::size_t channel = 0; channel < 3; ++channel) {
                                channels[channel] = static_cast<float>(rgba[channel]) / 65535.0F;
                            }
                        }
                    }
                    const auto background_alpha = metadata.canvas_background[3];
                    for (std::size_t channel = 0; channel < 3; ++channel) {
                        const auto value = channels[channel] * alpha +
                            metadata.canvas_background[channel] * background_alpha * (1.0F - alpha);
                        work->row[output_offset + channel] = static_cast<std::uint8_t>(
                            std::lround(std::clamp(value, 0.0F, 1.0F) * 255.0F));
                    }
                }
            }
        }
        JSAMPROW row = work->row;
        if (jpeg_write_scanlines(&compressor, &row, 1) != 1) {
            work->destination.failed = true;
            break;
        }
    }
    if (work->destination.failed) {
        jpeg_abort_compress(&compressor);
        jpeg_destroy_compress(&compressor);
        std::free(work->row);
        std::free(work);
        result.error = "encoded JPEG sink failed";
        return result;
    }
    jpeg_finish_compress(&compressor);
    const auto sink_failed = work->destination.failed;
    jpeg_destroy_compress(&compressor);
    std::free(work->row);
    std::free(work);
    if (sink_failed) {
        result.error = "encoded JPEG sink failed";
        return result;
    }
    result.success = true;
    return result;
}

}  // namespace croptop
