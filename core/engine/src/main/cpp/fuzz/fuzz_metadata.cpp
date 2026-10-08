// LibFuzzer target: EXIF/XMP/IPTC extraction. Oracle: embedded previews lie inside the file.
#include <cstddef>
#include <cstdint>
#include <span>
#include <string>

#include "image_metadata.h"

extern "C" int LLVMFuzzerTestOneInput(const std::uint8_t* data, std::size_t size) {
    std::string error;
    const auto metadata = croptop::metadata::read(std::span<const std::uint8_t>(data, size), error);
    if (!metadata) return 0;
    for (const auto& preview : metadata->previews) {
        if (preview.offset > size || preview.length > size - preview.offset) __builtin_trap();
    }
    return 0;
}
