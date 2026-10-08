#include "image_metrics.h"

#include <png.h>

#include <algorithm>
#include <array>
#include <cmath>
#include <stdexcept>
#include <string>
#include <vector>

namespace croptop {
namespace {

struct Lab {
    double l;
    double a;
    double b;
};

double srgbToLinear(double value) {
    value /= 255.0;
    return value <= 0.04045 ? value / 12.92 : std::pow((value + 0.055) / 1.055, 2.4);
}

double labCurve(double value) {
    constexpr double delta = 6.0 / 29.0;
    const double delta3 = delta * delta * delta;
    return value > delta3 ? std::cbrt(value) : value / (3.0 * delta * delta) + 4.0 / 29.0;
}

Lab toLab(Rgba8 color) {
    const double r = srgbToLinear(color.r);
    const double g = srgbToLinear(color.g);
    const double b = srgbToLinear(color.b);
    const double x = (0.4124564 * r + 0.3575761 * g + 0.1804375 * b) / 0.95047;
    const double y = 0.2126729 * r + 0.7151522 * g + 0.0721750 * b;
    const double z = (0.0193339 * r + 0.1191920 * g + 0.9503041 * b) / 1.08883;
    const double fx = labCurve(x);
    const double fy = labCurve(y);
    const double fz = labCurve(z);
    return {116.0 * fy - 16.0, 500.0 * (fx - fy), 200.0 * (fy - fz)};
}

std::vector<Rgba8> readPng(const std::filesystem::path& path, png_uint_32& width, png_uint_32& height) {
    png_image image{};
    image.version = PNG_IMAGE_VERSION;
    const auto filename = path.string();
    if (png_image_begin_read_from_file(&image, filename.c_str()) == 0) {
        throw std::runtime_error("Cannot read PNG " + filename + ": " + image.message);
    }
    image.format = PNG_FORMAT_RGBA;
    std::vector<Rgba8> pixels(PNG_IMAGE_SIZE(image) / sizeof(Rgba8));
    if (png_image_finish_read(&image, nullptr, pixels.data(), 0, nullptr) == 0) {
        const std::string message = image.message;
        png_image_free(&image);
        throw std::runtime_error("Cannot decode PNG " + filename + ": " + message);
    }
    width = image.width;
    height = image.height;
    png_image_free(&image);
    return pixels;
}

void writePng(const std::filesystem::path& path, png_uint_32 width, png_uint_32 height, const std::vector<Rgba8>& pixels) {
    png_image image{};
    image.version = PNG_IMAGE_VERSION;
    image.width = width;
    image.height = height;
    image.format = PNG_FORMAT_RGBA;
    const auto filename = path.string();
    if (png_image_write_to_file(&image, filename.c_str(), 0, pixels.data(), 0, nullptr) == 0) {
        throw std::runtime_error("Cannot write diff PNG " + filename + ": " + image.message);
    }
}

}  // namespace

double deltaE2000(Rgba8 lhs, Rgba8 rhs) {
    constexpr double pi = 3.14159265358979323846;
    const Lab first = toLab(lhs);
    const Lab second = toLab(rhs);
    const double chroma1 = std::hypot(first.a, first.b);
    const double chroma2 = std::hypot(second.a, second.b);
    const double mean_chroma = (chroma1 + chroma2) / 2.0;
    const double mean_chroma7 = std::pow(mean_chroma, 7.0);
    const double g = 0.5 * (1.0 - std::sqrt(mean_chroma7 / (mean_chroma7 + std::pow(25.0, 7.0))));
    const double a1_prime = (1.0 + g) * first.a;
    const double a2_prime = (1.0 + g) * second.a;
    const double c1_prime = std::hypot(a1_prime, first.b);
    const double c2_prime = std::hypot(a2_prime, second.b);
    const auto hue = [](double a, double b) {
        if (a == 0.0 && b == 0.0) return 0.0;
        double angle = std::atan2(b, a) * 180.0 / pi;
        return angle < 0.0 ? angle + 360.0 : angle;
    };
    const double h1_prime = hue(a1_prime, first.b);
    const double h2_prime = hue(a2_prime, second.b);
    const double delta_l = second.l - first.l;
    const double delta_c = c2_prime - c1_prime;
    double delta_h_angle = h2_prime - h1_prime;
    if (c1_prime * c2_prime == 0.0) delta_h_angle = 0.0;
    else if (delta_h_angle > 180.0) delta_h_angle -= 360.0;
    else if (delta_h_angle < -180.0) delta_h_angle += 360.0;
    const double delta_h = 2.0 * std::sqrt(c1_prime * c2_prime) * std::sin(delta_h_angle * pi / 360.0);
    const double mean_l = (first.l + second.l) / 2.0;
    const double mean_c = (c1_prime + c2_prime) / 2.0;
    double mean_h = h1_prime + h2_prime;
    if (c1_prime * c2_prime == 0.0) mean_h = h1_prime + h2_prime;
    else if (std::abs(h1_prime - h2_prime) <= 180.0) mean_h /= 2.0;
    else if (mean_h < 360.0) mean_h = (mean_h + 360.0) / 2.0;
    else mean_h = (mean_h - 360.0) / 2.0;
    const double t = 1.0 - 0.17 * std::cos((mean_h - 30.0) * pi / 180.0) +
                     0.24 * std::cos(2.0 * mean_h * pi / 180.0) +
                     0.32 * std::cos((3.0 * mean_h + 6.0) * pi / 180.0) -
                     0.20 * std::cos((4.0 * mean_h - 63.0) * pi / 180.0);
    const double delta_theta = 30.0 * std::exp(-std::pow((mean_h - 275.0) / 25.0, 2.0));
    const double mean_c7 = std::pow(mean_c, 7.0);
    const double rc = 2.0 * std::sqrt(mean_c7 / (mean_c7 + std::pow(25.0, 7.0)));
    const double sl = 1.0 + 0.015 * std::pow(mean_l - 50.0, 2.0) /
                                std::sqrt(20.0 + std::pow(mean_l - 50.0, 2.0));
    const double sc = 1.0 + 0.045 * mean_c;
    const double sh = 1.0 + 0.015 * mean_c * t;
    const double rt = -std::sin(2.0 * delta_theta * pi / 180.0) * rc;
    const double l_term = delta_l / sl;
    const double c_term = delta_c / sc;
    const double h_term = delta_h / sh;
    return std::sqrt(std::max(0.0, l_term * l_term + c_term * c_term + h_term * h_term + rt * c_term * h_term));
}

GoldenImageReport comparePng(const std::filesystem::path& expected_path,
                             const std::filesystem::path& actual_path,
                             const std::filesystem::path& diff_output,
                             double threshold) {
    if (!std::isfinite(threshold) || threshold < 0.0) throw std::invalid_argument("Delta E threshold must be finite and non-negative");
    png_uint_32 expected_width = 0;
    png_uint_32 expected_height = 0;
    png_uint_32 actual_width = 0;
    png_uint_32 actual_height = 0;
    const auto expected = readPng(expected_path, expected_width, expected_height);
    const auto actual = readPng(actual_path, actual_width, actual_height);
    if (expected_width != actual_width || expected_height != actual_height) {
        throw std::runtime_error("Golden image dimensions differ: expected " + std::to_string(expected_width) + "x" +
                                 std::to_string(expected_height) + ", got " + std::to_string(actual_width) + "x" +
                                 std::to_string(actual_height));
    }
    GoldenImageReport report{expected_width, expected_height};
    std::vector<Rgba8> diff(expected.size());
    double total_delta = 0.0;
    for (std::size_t i = 0; i < expected.size(); ++i) {
        const double delta = deltaE2000(expected[i], actual[i]);
        total_delta += delta;
        report.max_delta_e = std::max(report.max_delta_e, delta);
        if (delta > threshold) ++report.pixels_over_threshold;
        const auto intensity = static_cast<std::uint8_t>(std::clamp(std::lround(delta * 16.0), 0L, 255L));
        diff[i] = {intensity, 0, 0, 255};
    }
    report.mean_delta_e = total_delta / static_cast<double>(expected.size());
    writePng(diff_output, report.width, report.height, diff);
    return report;
}

}  // namespace croptop
