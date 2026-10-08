// LibFuzzer target: camera-raw files through LibRaw (probe, embedded preview, mosaic decode). LibRaw
// parses dozens of maker-specific formats from untrusted files, so this is the widest decode surface we ship.
#include <cstddef>
#include <cstdint>
#include <span>
#include <string>

#include "raw_decode.h"

extern "C" int LLVMFuzzerTestOneInput(const std::uint8_t* data, std::size_t size) {
    const auto file = std::span<const std::uint8_t>(data, size);
    croptop::raw::RawLimits limits;
    limits.max_pixels = 1ULL << 22;  // keep each iteration fast; the limit logic itself is unit-tested
    limits.max_file_bytes = 1ULL << 22;
    std::string error;
    (void)croptop::raw::extractPreview(file, error, 0, limits);
    const auto probe = croptop::raw::probe(file, error, limits);
    if (!probe || !probe->decodable) return 0;
    const auto image = croptop::raw::decode(file, error, limits);
    if (!image) return 0;
    if (image->photosites.size() != static_cast<std::size_t>(image->width) * image->height) __builtin_trap();
    return 0;
}
