#pragma once

#include <cstdint>
#include <filesystem>

namespace croptop {

struct Rgba8 {
    std::uint8_t r;
    std::uint8_t g;
    std::uint8_t b;
    std::uint8_t a;
};

struct GoldenImageReport {
    std::uint32_t width = 0;
    std::uint32_t height = 0;
    double mean_delta_e = 0.0;
    double max_delta_e = 0.0;
    std::uint64_t pixels_over_threshold = 0;
};

double deltaE2000(Rgba8 lhs, Rgba8 rhs);
GoldenImageReport comparePng(
    const std::filesystem::path& expected,
    const std::filesystem::path& actual,
    const std::filesystem::path& diff_output,
    double threshold = 1.0);

}  // namespace croptop
