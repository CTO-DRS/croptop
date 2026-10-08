// LibFuzzer target: baseline TIFF decode (tiff::decode) with bounded pixel and working memory.
#include <cstddef>
#include <cstdint>
#include <span>
#include <string>

#include "tiff_codec.h"

extern "C" int LLVMFuzzerTestOneInput(const std::uint8_t* data, std::size_t size) {
    croptop::tiff::Limits limits;
    limits.max_pixels = 1ULL << 22;
    limits.max_working_bytes = 64ULL << 20;
    std::string error;
    (void)croptop::tiff::decode(std::span<const std::uint8_t>(data, size), error, limits);
    return 0;
}
