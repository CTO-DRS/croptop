#pragma once

// SRGB (D65) to CIELAB conversion shared by the colour-based selection tools.

#include <cmath>

namespace croptop::selection::color {

/** sRGB-encoded channel in [0, 1] to linear light. */
inline double srgbToLinear(double v) { return v <= 0.04045 ? v / 12.92 : std::pow((v + 0.055) / 1.055, 2.4); }

struct Lab {
    double l;
    double a;
    double b;
};

inline Lab toLab(double r, double g, double b) {
    r = srgbToLinear(r);
    g = srgbToLinear(g);
    b = srgbToLinear(b);
    const auto x = (0.4124564 * r + 0.3575761 * g + 0.1804375 * b) / 0.95047;
    const auto y = 0.2126729 * r + 0.7151522 * g + 0.0721750 * b;
    const auto z = (0.0193339 * r + 0.1191920 * g + 0.9503041 * b) / 1.08883;
    const auto f = [](double t) {
        constexpr double kDelta = 6.0 / 29.0;
        return t > kDelta * kDelta * kDelta ? std::cbrt(t) : t / (3.0 * kDelta * kDelta) + 4.0 / 29.0;
    };
    const auto fx = f(x);
    const auto fy = f(y);
    const auto fz = f(z);
    return {116.0 * fy - 16.0, 500.0 * (fx - fy), 200.0 * (fy - fz)};
}

/** Euclidean ΔE76. */
inline double deltaE(const Lab& a, const Lab& b) {
    return std::sqrt((a.l - b.l) * (a.l - b.l) + (a.a - b.a) * (a.a - b.a) + (a.b - b.b) * (a.b - b.b));
}

}  // namespace croptop::selection::color
