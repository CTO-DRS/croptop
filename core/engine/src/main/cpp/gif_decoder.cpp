#include "gif_decoder.h"

#include "tile_band_writer.h"
#include "tile_store.h"

#include <algorithm>
#include <array>
#include <cstring>
#include <exception>
#include <optional>
#include <vector>

namespace croptop {
namespace {

constexpr std::size_t kMaxCodes = 4096;

DecodeResult failure(const char* message) {
    DecodeResult result{};
    result.error = message;
    return result;
}

class Reader final {
public:
    explicit Reader(std::span<const std::uint8_t> bytes) : bytes_(bytes) {}

    std::optional<std::uint8_t> byte() {
        if (offset_ >= bytes_.size()) return std::nullopt;
        return bytes_[offset_++];
    }

    std::optional<std::uint16_t> little16() {
        if (bytes_.size() - offset_ < 2U) return std::nullopt;
        const auto value = static_cast<std::uint16_t>(bytes_[offset_] | (bytes_[offset_ + 1U] << 8U));
        offset_ += 2U;
        return value;
    }

    std::optional<std::span<const std::uint8_t>> take(std::size_t count) {
        if (bytes_.size() - offset_ < count) return std::nullopt;
        const auto span = bytes_.subspan(offset_, count);
        offset_ += count;
        return span;
    }

    /** Skips a data sub-block chain; false if it runs past the end. */
    bool skipSubBlocks() {
        while (true) {
            const auto size = byte();
            if (!size) return false;
            if (*size == 0U) return true;
            if (!take(*size)) return false;
        }
    }

private:
    std::span<const std::uint8_t> bytes_;
    std::size_t offset_ = 0;
};

/** Streams bits LSB-first out of GIF data sub-blocks without concatenating them. */
class SubBlockBits final {
public:
    explicit SubBlockBits(Reader& reader) : reader_(reader) {}

    /** Returns the next code, or nullopt when the sub-block chain ends or is truncated. */
    std::optional<std::uint32_t> read(std::uint32_t bits) {
        while (bit_count_ < bits) {
            if (block_remaining_ == 0U) {
                if (ended_) return std::nullopt;
                const auto size = reader_.byte();
                if (!size) {
                    truncated_ = true;
                    return std::nullopt;
                }
                if (*size == 0U) {
                    ended_ = true;
                    return std::nullopt;
                }
                block_remaining_ = *size;
            }
            const auto next = reader_.byte();
            if (!next) {
                truncated_ = true;
                return std::nullopt;
            }
            --block_remaining_;
            buffer_ |= static_cast<std::uint32_t>(*next) << bit_count_;
            bit_count_ += 8U;
        }
        const auto code = buffer_ & ((1U << bits) - 1U);
        buffer_ >>= bits;
        bit_count_ -= bits;
        return code;
    }

    /** Consumes the rest of the chain so parsing can continue after the image data. */
    bool finish() {
        if (truncated_) return false;
        if (!ended_) {
            if (block_remaining_ > 0U && !reader_.take(block_remaining_)) return false;
            if (!reader_.skipSubBlocks()) return false;
        }
        return true;
    }

private:
    Reader& reader_;
    std::uint32_t buffer_ = 0;
    std::uint32_t bit_count_ = 0;
    std::uint32_t block_remaining_ = 0;
    bool ended_ = false;
    bool truncated_ = false;
};

/** Decodes exactly `output.size()` indices; trailing codes after the frame is full are ignored. */
bool decodeLzw(SubBlockBits& bits, std::uint32_t min_code_size, std::span<std::uint8_t> output) {
    const std::uint32_t clear = 1U << min_code_size;
    const std::uint32_t end = clear + 1U;
    std::array<std::uint16_t, kMaxCodes> prefix{};
    std::array<std::uint8_t, kMaxCodes> suffix{};
    std::array<std::uint8_t, kMaxCodes> first{};
    std::array<std::uint16_t, kMaxCodes> length{};
    for (std::uint32_t code = 0; code < clear; ++code) {
        suffix[code] = static_cast<std::uint8_t>(code);
        first[code] = static_cast<std::uint8_t>(code);
        length[code] = 1;
    }

    std::uint32_t code_size = min_code_size + 1U;
    std::uint32_t next_code = end + 1U;
    std::optional<std::uint32_t> previous;
    std::size_t written = 0;
    while (written < output.size()) {
        const auto code = bits.read(code_size);
        if (!code) return false;
        if (*code == clear) {
            code_size = min_code_size + 1U;
            next_code = end + 1U;
            previous.reset();
            continue;
        }
        if (*code == end) return false;  // Frame ended before all pixels were produced.

        if (!previous) {
            if (*code >= clear) return false;
        } else if (*code <= next_code && next_code < kMaxCodes) {
            // New entry = previous + first(code); for code == next_code (KwKwK) that is first(previous).
            const auto first_of_code = *code == next_code ? first[*previous] : first[*code];
            prefix[next_code] = static_cast<std::uint16_t>(*previous);
            suffix[next_code] = first_of_code;
            first[next_code] = first[*previous];
            length[next_code] = static_cast<std::uint16_t>(length[*previous] + 1U);
            ++next_code;
        } else if (*code >= next_code) {
            return false;  // Reference to an entry that does not exist (codes are < 4096, so a full table never needs one).
        }

        // Write the string backwards into place, clipping to the frame size.
        const std::size_t string_length = length[*code];
        const std::size_t available = output.size() - written;
        std::size_t position = string_length;
        for (std::uint32_t walk = *code;;) {
            --position;
            if (position < available) output[written + position] = suffix[walk];
            if (length[walk] == 1U) break;
            walk = prefix[walk];
        }
        written += std::min(string_length, available);

        previous = *code;
        if (next_code == (1U << code_size) && code_size < 12U) ++code_size;
    }
    return true;
}

/** Maps a displayed row to its position in interlaced storage (passes: 0+8n, 4+8n, 2+4n, 1+2n). */
std::uint32_t storedInterlacedRow(std::uint32_t display_row, std::uint32_t height) {
    const auto rows = [height](std::uint32_t start, std::uint32_t step) {
        return start >= height ? 0U : (height - start + step - 1U) / step;
    };
    if (display_row % 8U == 0U) return display_row / 8U;
    if (display_row % 8U == 4U) return rows(0, 8) + display_row / 8U;
    if (display_row % 4U == 2U) return rows(0, 8) + rows(4, 8) + display_row / 4U;
    return rows(0, 8) + rows(4, 8) + rows(2, 4) + display_row / 2U;
}

}  // namespace

DecodeResult decodeGif(std::span<const std::uint8_t> encoded, const RgbaScanlineSink& sink,
                       std::uint32_t max_dimension, std::uint64_t max_pixels, std::uint64_t max_working_bytes) {
    if (!sink) return failure("GIF decode requires a scanline sink");
    Reader reader(encoded);
    const auto signature = reader.take(6);
    if (!signature || (std::memcmp(signature->data(), "GIF87a", 6) != 0 &&
                       std::memcmp(signature->data(), "GIF89a", 6) != 0)) {
        return failure("not a GIF stream");
    }
    const auto screen_width = reader.little16();
    const auto screen_height = reader.little16();
    const auto screen_flags = reader.byte();
    if (!screen_width || !screen_height || !screen_flags || !reader.byte() || !reader.byte()) {
        return failure("GIF header is truncated");
    }
    const std::uint32_t width = *screen_width;
    const std::uint32_t height = *screen_height;
    if (width == 0U || height == 0U || width > max_dimension || height > max_dimension ||
        static_cast<std::uint64_t>(width) * height > max_pixels) {
        return failure("GIF dimensions are empty or exceed the decode limit");
    }
    std::span<const std::uint8_t> global_palette;
    if ((*screen_flags & 0x80U) != 0U) {
        const auto palette = reader.take(3U * (2U << (*screen_flags & 0x07U)));
        if (!palette) return failure("GIF global palette is truncated");
        global_palette = *palette;
    }

    std::optional<std::uint8_t> transparent_index;
    while (true) {
        const auto introducer = reader.byte();
        if (!introducer) return failure("GIF ends before the first image");
        if (*introducer == 0x3BU) return failure("GIF has no image");
        if (*introducer == 0x21U) {
            const auto label = reader.byte();
            if (!label) return failure("GIF extension is truncated");
            if (*label == 0xF9U) {
                const auto size = reader.byte();
                const auto body = size ? reader.take(*size) : std::nullopt;
                if (!body || *size < 4U) return failure("GIF graphic control extension is malformed");
                transparent_index = ((*body)[0] & 0x01U) != 0U ? std::optional<std::uint8_t>((*body)[3]) : std::nullopt;
            }
            if (!reader.skipSubBlocks()) return failure("GIF extension is truncated");
            continue;
        }
        if (*introducer != 0x2CU) return failure("GIF contains an unknown block");
        break;
    }

    const auto left = reader.little16();
    const auto top = reader.little16();
    const auto frame_width = reader.little16();
    const auto frame_height = reader.little16();
    const auto frame_flags = reader.byte();
    if (!left || !top || !frame_width || !frame_height || !frame_flags) {
        return failure("GIF image descriptor is truncated");
    }
    if (*frame_width == 0U || *frame_height == 0U ||
        static_cast<std::uint64_t>(*frame_width) * *frame_height > max_pixels) {
        return failure("GIF frame is empty or exceeds the decode limit");
    }
    if (static_cast<std::uint64_t>(*frame_width) * *frame_height > max_working_bytes) {
        return failure("GIF frame exceeds the working memory limit");
    }
    std::span<const std::uint8_t> palette = global_palette;
    if ((*frame_flags & 0x80U) != 0U) {
        const auto local = reader.take(3U * (2U << (*frame_flags & 0x07U)));
        if (!local) return failure("GIF local palette is truncated");
        palette = *local;
    }
    if (palette.empty()) return failure("GIF frame has no palette");
    const auto min_code_size = reader.byte();
    if (!min_code_size || *min_code_size < 2U || *min_code_size > 8U) return failure("GIF LZW code size is invalid");

    std::vector<std::uint8_t> indices;
    std::vector<std::uint8_t> row;
    try {
        indices.resize(static_cast<std::size_t>(*frame_width) * *frame_height);
        row.resize(static_cast<std::size_t>(width) * 4U);
    } catch (const std::exception&) {
        return failure("could not allocate GIF frame");
    }
    SubBlockBits bits(reader);
    if (!decodeLzw(bits, *min_code_size, indices)) return failure("GIF image data is corrupt or truncated");
    if (!bits.finish()) return failure("GIF image data is truncated");

    const std::size_t palette_entries = palette.size() / 3U;
    for (const auto index : indices) {
        if (index >= palette_entries && (!transparent_index || index != *transparent_index)) {
            return failure("GIF pixel index is outside the palette");
        }
    }

    const bool interlaced = (*frame_flags & 0x40U) != 0U;
    const std::uint32_t frame_left = *left;
    const std::uint32_t frame_top = *top;
    for (std::uint32_t y = 0; y < height; ++y) {
        std::fill(row.begin(), row.end(), 0);
        if (y >= frame_top && y - frame_top < *frame_height && frame_left < width) {
            const auto frame_y = y - frame_top;
            // Interlaced data is stored pass by pass; find which stored row holds display row frame_y.
            const auto stored = interlaced ? storedInterlacedRow(frame_y, *frame_height) : frame_y;
            const auto* source = indices.data() + static_cast<std::size_t>(stored) * *frame_width;
            const auto visible = std::min<std::uint32_t>(*frame_width, width - frame_left);
            for (std::uint32_t x = 0; x < visible; ++x) {
                const auto index = source[x];
                if (transparent_index && index == *transparent_index) continue;
                auto* pixel = row.data() + (static_cast<std::size_t>(frame_left) + x) * 4U;
                std::memcpy(pixel, palette.data() + static_cast<std::size_t>(index) * 3U, 3U);
                pixel[3] = 0xFFU;
            }
        }
        if (!sink(y, row)) return failure("GIF scanline sink rejected a row");
    }

    DecodeResult result{};
    result.success = true;
    result.raster = {width, height, "gif", 8};
    return result;
}

DecodeResult decodeGifToTiles(std::span<const std::uint8_t> encoded, TileStore& destination,
                              std::uint32_t max_dimension, std::uint64_t max_pixels,
                              std::uint64_t max_working_bytes) {
    if (destination.format() != PixelFormat::RGBA8) return failure("GIF requires an RGBA8 destination tile store");
    // Preflight the logical screen against the store; decodeGif only calls the sink after the whole
    // frame decoded, so any later failure leaves the destination untouched too.
    if (encoded.size() < 10U || (std::memcmp(encoded.data(), "GIF87a", 6) != 0 &&
                                 std::memcmp(encoded.data(), "GIF89a", 6) != 0)) {
        return failure("not a GIF stream");
    }
    const std::uint32_t width = encoded[6] | (encoded[7] << 8U);
    const std::uint32_t height = encoded[8] | (encoded[9] << 8U);
    if (width != destination.width() || height != destination.height()) {
        return failure("GIF dimensions do not match tile store");
    }
    detail::TileBandWriter writer(destination);
    auto result = decodeGif(encoded, [&](std::uint32_t y, std::span<const std::uint8_t> row) {
        return writer.write(y, row);
    }, max_dimension, max_pixels, max_working_bytes);
    if (result.success && !writer.complete()) return failure("GIF dimensions do not match tile store");
    return result;
}

}  // namespace croptop
