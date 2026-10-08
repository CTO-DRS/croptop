#pragma once

#include <array>
#include <cstdint>
#include <span>
#include <vector>

namespace croptop {

enum class BlendMode : std::uint8_t {
    Normal, Dissolve, Darken, Multiply, ColorBurn, LinearBurn, DarkerColor,
    Lighten, Screen, ColorDodge, LinearDodge, LighterColor, Overlay, SoftLight,
    HardLight, VividLight, LinearLight, PinLight, HardMix, Difference, Exclusion,
    Subtract, Divide, Hue, Saturation, Color, Luminosity
};
enum class BlendSpace : std::uint8_t { GammaEncoded, LinearLight };

using BlendPixel8 = std::array<std::uint8_t, 4>;
using BlendPixelF = std::array<float, 4>;

/** Reference source-over blend. Channels use encoded 8-bit values; alpha is straight. */
BlendPixel8 blendRgba8(BlendPixel8 backdrop, BlendPixel8 source, BlendMode mode,
                       std::uint8_t opacity = 255, std::uint32_t pixel_x = 0,
                       std::uint32_t pixel_y = 0, std::uint32_t dissolve_seed = 0,
                       BlendSpace blend_space = BlendSpace::GammaEncoded) noexcept;
/** Floating reference; caller supplies pixels in the chosen blend space (for example linear light). */
BlendPixelF blendRgbaF(BlendPixelF backdrop, BlendPixelF source, BlendMode mode,
                       float opacity = 1.0F, std::uint32_t pixel_x = 0,
                       std::uint32_t pixel_y = 0, std::uint32_t dissolve_seed = 0) noexcept;

struct Rgba8LayerTile {
    std::span<const BlendPixel8> pixels;
    BlendMode mode = BlendMode::Normal;
    std::uint8_t opacity = 255;
    std::uint8_t fill_opacity = 255;
    bool visible = true;
};

/** CPU reference for a bottom-to-top layer stack. Output is replaced only on valid input. */
bool compositeRgba8Tile(std::span<const Rgba8LayerTile> layers,
                        std::span<BlendPixel8> output, std::uint32_t tile_x = 0,
                        std::uint32_t tile_y = 0, std::uint32_t dissolve_seed = 0,
                        BlendSpace blend_space = BlendSpace::GammaEncoded) noexcept;

}  // namespace croptop
