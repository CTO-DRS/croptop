// LibFuzzer target: .cube/.3dl LUT parsing. The first byte picks the file extension.
// Oracle: a parsed LUT maps an in-range color to finite values.
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <string>
#include <string_view>

#include "lut.h"

extern "C" int LLVMFuzzerTestOneInput(const std::uint8_t* data, std::size_t size) {
    if (size == 0) return 0;
    const std::string_view text(reinterpret_cast<const char*>(data) + 1, size - 1);
    constexpr std::string_view kNames[] = {"a.cube", "a.3dl", "a"};
    std::string error;
    const auto lut = croptop::color::parseLut(text, kNames[data[0] % 3], error);
    if (!lut) return 0;
    const auto v = lut->apply({0.5F, 0.1F, 0.9F});
    if (!std::isfinite(v[0]) || !std::isfinite(v[1]) || !std::isfinite(v[2])) __builtin_trap();
    return 0;
}
