// LibFuzzer target: Ultra HDR detection and decode (libultrahdr + libjpeg-turbo) with a small pixel limit.
#include <cstddef>
#include <cstdint>
#include <span>
#include <string>

#include "ultra_hdr.h"

extern "C" int LLVMFuzzerTestOneInput(const std::uint8_t* data, std::size_t size) {
    const std::span<const std::uint8_t> bytes(data, size);
    if (!croptop::ultrahdr::isUltraHdr(bytes)) return 0;
    std::string error;
    (void)croptop::ultrahdr::decode(bytes, error, 1ULL << 20);
    return 0;
}
