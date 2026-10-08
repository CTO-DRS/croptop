// LibFuzzer target: the TIFF/PSD open path NativeEngine.open uses (probe, then decode into tiles).
#include <cstddef>
#include <cstdint>
#include <span>
#include <string>

#include "codec_extra.h"

extern "C" int LLVMFuzzerTestOneInput(const std::uint8_t* data, std::size_t size) {
    const auto bytes = std::span<const std::uint8_t>(data, size);
    std::string error;
    const auto probe = croptop::codec::probe(bytes, error);
    if (!probe || probe->width == 0 || probe->height == 0) return 0;
    if (static_cast<std::uint64_t>(probe->width) * probe->height > (1ULL << 22)) return 0;
    croptop::TileStore destination(probe->pixel_format, probe->width, probe->height);
    (void)croptop::codec::decodeToTiles(bytes, destination);
    return 0;
}
