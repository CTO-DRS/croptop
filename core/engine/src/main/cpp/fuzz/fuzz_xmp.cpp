// LibFuzzer target: Develop XMP import. Oracle: write(read(x)) is a fixed point after one pass.
#include <cstddef>
#include <cstdint>
#include <string>
#include <string_view>

#include "develop_xmp.h"

extern "C" int LLVMFuzzerTestOneInput(const std::uint8_t* data, std::size_t size) {
    const std::string_view text(reinterpret_cast<const char*>(data), size);
    std::string error;
    (void)croptop::develop::readXmpPreset(text, error);
    const auto recipe = croptop::develop::readXmp(text, error);
    if (!recipe) return 0;
    const auto once = croptop::develop::writeXmp(*recipe);
    const auto again = croptop::develop::readXmp(once, error);
    if (!again || croptop::develop::writeXmp(*again) != once) __builtin_trap();
    return 0;
}
