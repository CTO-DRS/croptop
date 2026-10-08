// LibFuzzer target: the shape-layer JNI byte codec (Kotlin hands these bytes to native code).
// Oracle: encode(decode(x)) decodes again and re-encodes to the same bytes.
#include <cstddef>
#include <cstdint>
#include <span>
#include <string>
#include <vector>

#include "jni_shape_layer.h"

namespace bridge = croptop::shape::bridge;

extern "C" int LLVMFuzzerTestOneInput(const std::uint8_t* data, std::size_t size) {
    const auto bytes = std::span<const std::uint8_t>(data, size);
    std::string error;
    croptop::shape::Hit hit;
    (void)bridge::hitTestBatch(bytes, {12.5, 7.25}, 4.0, hit, error);
    std::vector<bridge::Record> records;
    if (!bridge::decodeBatch(bytes, records, error)) return 0;
    std::vector<std::uint8_t> once;
    if (!bridge::encodeBatch(records, once, error)) return 0;
    std::vector<bridge::Record> again;
    std::vector<std::uint8_t> twice;
    if (!bridge::decodeBatch(once, again, error) || !bridge::encodeBatch(again, twice, error) || twice != once) __builtin_trap();
    return 0;
}
