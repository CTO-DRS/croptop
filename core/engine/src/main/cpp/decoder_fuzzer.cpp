#include "image_decoder.h"
#include "gif_decoder.h"
#include "webp_codec.h"

#include <cstdint>
#include <span>

namespace {

constexpr std::uint32_t kMaxDimension = 2048;
constexpr std::uint64_t kMaxPixels = 4'194'304;
constexpr std::size_t kMaxRgba16RowBytes = static_cast<std::size_t>(kMaxDimension) * 8U;

bool discardRow(std::uint32_t y, std::span<const std::uint8_t> row) {
    return y < kMaxDimension && !row.empty() && row.size() <= kMaxRgba16RowBytes;
}

}  // namespace

extern "C" int LLVMFuzzerTestOneInput(const std::uint8_t* data, std::size_t size) {
    const auto encoded = std::span<const std::uint8_t>(data, size);
    const croptop::RgbaScanlineSink sink = discardRow;
    (void)croptop::decodeJpeg(encoded, sink, kMaxDimension, kMaxPixels);
    (void)croptop::decodePng(encoded, sink, kMaxDimension, kMaxPixels);
    (void)croptop::decodeBmp(encoded, sink, kMaxDimension, kMaxPixels);
    (void)croptop::decodeWebp(encoded, sink, kMaxDimension, kMaxPixels);
    (void)croptop::decodeGif(encoded, sink, kMaxDimension, kMaxPixels);
    return 0;
}
