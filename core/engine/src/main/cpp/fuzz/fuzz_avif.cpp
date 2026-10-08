// LibFuzzer target: AVIF probe and decode (libavif + libaom) with a small pixel limit.
#include <cstddef>
#include <cstdint>
#include <span>
#include <string>

#include "avif_codec.h"

extern "C" int LLVMFuzzerTestOneInput(const std::uint8_t* data, std::size_t size) {
    const std::span<const std::uint8_t> bytes(data, size);
    std::string error;
    (void)croptop::avif::probe(bytes, error);
    (void)croptop::avif::decode(bytes, error, 1ULL << 20);
    return 0;
}
