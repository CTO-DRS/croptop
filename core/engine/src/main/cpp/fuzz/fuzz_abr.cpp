// LibFuzzer target: Photoshop .abr brush import. Oracle: tip references and tip sizes agree.
#include <cstddef>
#include <cstdint>
#include <span>
#include <string>

#include "abr_import.h"

extern "C" int LLVMFuzzerTestOneInput(const std::uint8_t* data, std::size_t size) {
    croptop::brush::AbrLimits limits;
    limits.max_tip_bytes = 64ULL << 20;
    std::string error;
    const auto file = croptop::brush::parseAbr(std::span<const std::uint8_t>(data, size), error, limits);
    if (!file) return 0;
    for (const auto& brush : file->brushes) {
        if (brush.tip && *brush.tip >= file->tips.size()) __builtin_trap();
    }
    for (const auto& tip : file->tips) {
        if (tip.coverage.size() != static_cast<std::size_t>(tip.width) * tip.height) __builtin_trap();
    }
    return 0;
}
