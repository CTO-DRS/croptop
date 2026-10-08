#include "blend_modes.h"

#include <algorithm>
#include <array>
#include <cmath>

namespace croptop {
namespace {
constexpr std::uint32_t kBlendTileEdge = 256;
using Rgb = std::array<double, 3>;

double clamp01(double value) {
    return std::isfinite(value) ? std::clamp(value, 0.0, 1.0) : 0.0;
}

double srgbToLinear(double value) {
    return value <= 0.04045 ? value / 12.92 : std::pow((value + 0.055) / 1.055, 2.4);
}

double dissolveThreshold(std::uint32_t x, std::uint32_t y, std::uint32_t seed) {
    std::uint32_t value = x * 0x9e3779b9U ^ y * 0x85ebca6bU ^ seed;
    value ^= value >> 16U;
    value *= 0x7feb352dU;
    value ^= value >> 15U;
    value *= 0x846ca68bU;
    value ^= value >> 16U;
    return static_cast<double>(value) / 4294967296.0;
}

/** sRGB byte -> linear, exactly srgbToLinear(byte / 255), without a pow() per pixel. */
const std::array<double, 256>& linearOfByte() {
    static const auto table = [] {
        std::array<double, 256> t{};
        for (int i = 0; i < 256; ++i) t[static_cast<std::size_t>(i)] = srgbToLinear(i / 255.0);
        return t;
    }();
    return table;
}

/**
 * Linear value -> nearest sRGB byte, exactly lround(sRGB-encode(v) * 255) (checked against the pow() formula on 20 M values): the encoding is monotonic, so byte k is chosen
 * while v is below the linear value of the boundary (k + 0.5) / 255. Binary search over 255 precomputed boundaries.
 */
std::uint8_t byteOfLinear(double linear) {
    static const auto boundaries = [] {
        std::array<double, 255> t{};
        for (int k = 0; k < 255; ++k) t[static_cast<std::size_t>(k)] = srgbToLinear((k + 0.5) / 255.0);
        return t;
    }();
    return static_cast<std::uint8_t>(std::upper_bound(boundaries.begin(), boundaries.end(), linear) - boundaries.begin());
}

Rgb toRgb(BlendPixel8 pixel) {
    return {pixel[0] / 255.0, pixel[1] / 255.0, pixel[2] / 255.0};
}

double luminance(Rgb c) { return 0.3 * c[0] + 0.59 * c[1] + 0.11 * c[2]; }
double saturation(Rgb c) { return *std::max_element(c.begin(), c.end()) - *std::min_element(c.begin(), c.end()); }

Rgb clipColor(Rgb c) {
    const double l = luminance(c);
    const double n = *std::min_element(c.begin(), c.end());
    const double x = *std::max_element(c.begin(), c.end());
    if (n < 0.0) for (double& v : c) v = l + ((v - l) * l) / (l - n);
    if (x > 1.0) for (double& v : c) v = l + ((v - l) * (1.0 - l)) / (x - l);
    return c;
}

Rgb setLum(Rgb c, double l) {
    const double delta = l - luminance(c);
    for (double& v : c) v += delta;
    return clipColor(c);
}

Rgb setSat(Rgb c, double s) {
    std::array<int, 3> order{0, 1, 2};
    std::sort(order.begin(), order.end(), [&c](int a, int b) { return c[a] < c[b]; });
    const int low = order[0], mid = order[1], high = order[2];
    if (c[high] > c[low]) {
        c[mid] = ((c[mid] - c[low]) * s) / (c[high] - c[low]);
        c[high] = s;
    } else {
        c[mid] = c[high] = 0.0;
    }
    c[low] = 0.0;
    return c;
}

double channel(double b, double s, BlendMode mode) {
    switch (mode) {
        case BlendMode::Normal: case BlendMode::Dissolve: return s;
        case BlendMode::Darken: return std::min(b, s);
        case BlendMode::Multiply: return b * s;
        case BlendMode::ColorBurn: return s <= 0.0 ? 0.0 : 1.0 - std::min(1.0, (1.0 - b) / s);
        case BlendMode::LinearBurn: return std::max(0.0, b + s - 1.0);
        case BlendMode::DarkerColor: return b;
        case BlendMode::Lighten: return std::max(b, s);
        case BlendMode::Screen: return b + s - b * s;
        case BlendMode::ColorDodge: return s >= 1.0 ? 1.0 : std::min(1.0, b / (1.0 - s));
        case BlendMode::LinearDodge: return std::min(1.0, b + s);
        case BlendMode::LighterColor: return b;
        case BlendMode::Overlay: return b <= 0.5 ? 2.0 * b * s : 1.0 - 2.0 * (1.0 - b) * (1.0 - s);
        case BlendMode::SoftLight:
            return s <= 0.5 ? b - (1.0 - 2.0 * s) * b * (1.0 - b)
                : b + (2.0 * s - 1.0) * ((b <= 0.25 ? ((16.0 * b - 12.0) * b + 4.0) * b : std::sqrt(b)) - b);
        case BlendMode::HardLight: return s <= 0.5 ? 2.0 * b * s : 1.0 - 2.0 * (1.0 - b) * (1.0 - s);
        case BlendMode::VividLight:
            return s <= 0.5 ? (s <= 0.0 ? 0.0 : 1.0 - std::min(1.0, (1.0 - b) / (2.0 * s)))
                : (s >= 1.0 ? 1.0 : std::min(1.0, b / (2.0 * (1.0 - s))));
        case BlendMode::LinearLight: return clamp01(b + 2.0 * s - 1.0);
        case BlendMode::PinLight: return s <= 0.5 ? std::min(b, 2.0 * s) : std::max(b, 2.0 * s - 1.0);
        case BlendMode::HardMix: return (b + 2.0 * s - 1.0) < 0.5 ? 0.0 : 1.0;
        case BlendMode::Difference: return std::abs(b - s);
        case BlendMode::Exclusion: return b + s - 2.0 * b * s;
        case BlendMode::Subtract: return std::max(0.0, b - s);
        case BlendMode::Divide: return s <= 0.0 ? 1.0 : std::min(1.0, b / s);
        case BlendMode::Hue: case BlendMode::Saturation: case BlendMode::Color: case BlendMode::Luminosity:
            return s;
    }
    return s;
}

Rgb blend(Rgb b, Rgb s, BlendMode mode) {
    if (mode == BlendMode::Hue) return setLum(setSat(s, saturation(b)), luminance(b));
    if (mode == BlendMode::Saturation) return setLum(setSat(b, saturation(s)), luminance(b));
    if (mode == BlendMode::Color) return setLum(s, luminance(b));
    if (mode == BlendMode::Luminosity) return setLum(b, luminance(s));
    if (mode == BlendMode::DarkerColor || mode == BlendMode::LighterColor) {
        const bool source_wins = mode == BlendMode::DarkerColor
            ? luminance(s) < luminance(b) : luminance(s) > luminance(b);
        return source_wins ? s : b;
    }
    for (int i = 0; i < 3; ++i) b[i] = clamp01(channel(b[i], s[i], mode));
    return b;
}

}  // namespace

BlendPixel8 blendRgba8(BlendPixel8 backdrop, BlendPixel8 source, BlendMode mode, std::uint8_t opacity,
                       std::uint32_t pixel_x, std::uint32_t pixel_y, std::uint32_t dissolve_seed,
                       BlendSpace blend_space) noexcept {
    const double ab = backdrop[3] / 255.0;
    double as = (source[3] / 255.0) * (opacity / 255.0);
    if (mode == BlendMode::Dissolve) {
        if (dissolveThreshold(pixel_x, pixel_y, dissolve_seed) >= as) return backdrop;
        source[3] = 255;
        as = 1.0;
        mode = BlendMode::Normal;
    }
    const double ao = as + ab * (1.0 - as);
    if (ao <= 0.0) return {0, 0, 0, 0};
    Rgb b = toRgb(backdrop), s = toRgb(source);
    if (blend_space == BlendSpace::LinearLight) {
        const auto& lut = linearOfByte();
        for (int i = 0; i < 3; ++i) {
            b[i] = lut[backdrop[static_cast<std::size_t>(i)]];
            s[i] = lut[source[static_cast<std::size_t>(i)]];
        }
    }
    const Rgb mixed = blend(b, s, mode);
    BlendPixel8 out{};
    for (int i = 0; i < 3; ++i) {
        const double premultiplied = (1.0 - as) * ab * b[i] + (1.0 - ab) * as * s[i] + ab * as * mixed[i];
        const auto value = clamp01(premultiplied / ao);
        out[i] = blend_space == BlendSpace::LinearLight ? byteOfLinear(value) : static_cast<std::uint8_t>(std::lround(value * 255.0));
    }
    out[3] = static_cast<std::uint8_t>(std::lround(ao * 255.0));
    return out;
}

BlendPixelF blendRgbaF(BlendPixelF backdrop, BlendPixelF source, BlendMode mode, float opacity,
                       std::uint32_t pixel_x, std::uint32_t pixel_y, std::uint32_t dissolve_seed) noexcept {
    const double ab = clamp01(backdrop[3]);
    double as = clamp01(source[3]) * clamp01(opacity);
    if (mode == BlendMode::Dissolve) {
        if (dissolveThreshold(pixel_x, pixel_y, dissolve_seed) >= as) return backdrop;
        source[3] = 1.0F;
        as = 1.0;
        mode = BlendMode::Normal;
    }
    const double ao = as + ab * (1.0 - as);
    if (ao <= 0.0) return {0.0F, 0.0F, 0.0F, 0.0F};
    if (mode == BlendMode::Normal) {
        BlendPixelF out{};
        for (int i = 0; i < 3; ++i) {
            const double b = std::isfinite(backdrop[i]) ? backdrop[i] : 0.0;
            const double s = std::isfinite(source[i]) ? source[i] : 0.0;
            out[i] = static_cast<float>(((1.0 - as) * ab * b + as * s) / ao);
        }
        out[3] = static_cast<float>(ao);
        return out;
    }
    const Rgb b{clamp01(backdrop[0]), clamp01(backdrop[1]), clamp01(backdrop[2])};
    const Rgb s{clamp01(source[0]), clamp01(source[1]), clamp01(source[2])};
    const Rgb mixed = blend(b, s, mode);
    BlendPixelF out{};
    for (int i = 0; i < 3; ++i) {
        const double premultiplied = (1.0 - as) * ab * b[i] + (1.0 - ab) * as * s[i] + ab * as * mixed[i];
        out[i] = static_cast<float>(clamp01(premultiplied / ao));
    }
    out[3] = static_cast<float>(ao);
    return out;
}

bool compositeRgba8Tile(std::span<const Rgba8LayerTile> layers,
                        std::span<BlendPixel8> output, std::uint32_t tile_x,
                        std::uint32_t tile_y, std::uint32_t dissolve_seed,
                        BlendSpace blend_space) noexcept {
    for (const auto& layer : layers) {
        if (layer.visible && layer.pixels.size() != output.size()) return false;
    }
    for (std::size_t pixel = 0; pixel < output.size(); ++pixel) {
        BlendPixel8 result{0, 0, 0, 0};
        for (const auto& layer : layers) {
            if (!layer.visible) continue;
            const auto opacity = static_cast<std::uint8_t>(
                (static_cast<unsigned>(layer.opacity) * layer.fill_opacity + 127U) / 255U);
            const auto pixel_x = tile_x * kBlendTileEdge + static_cast<std::uint32_t>(pixel % kBlendTileEdge);
            const auto pixel_y = tile_y * kBlendTileEdge + static_cast<std::uint32_t>(pixel / kBlendTileEdge);
            result = blendRgba8(result, layer.pixels[pixel], layer.mode, opacity, pixel_x, pixel_y,
                                dissolve_seed, blend_space);
        }
        output[pixel] = result;
    }
    return true;
}

}  // namespace croptop
