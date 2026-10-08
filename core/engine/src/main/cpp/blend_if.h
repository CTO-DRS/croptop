// Blend If (Layer Style ▸ Blending Options): per-pixel visibility driven by the layer's own luminance and the luminance of
// what lies beneath it. Each slider pair is a black point (lo, hi) and a white point (lo, hi) on 0–255; between lo and hi
// the weight ramps linearly (the "split" sliders). Default {0,0,255,255} keeps everything.
#pragma once

#include <algorithm>
#include <array>
#include <cstdint>

namespace croptop {

struct BlendIfRange {
    std::uint8_t black_lo = 0;
    std::uint8_t black_hi = 0;
    std::uint8_t white_lo = 255;
    std::uint8_t white_hi = 255;

    bool isDefault() const { return black_lo == 0 && black_hi == 0 && white_lo == 255 && white_hi == 255; }
    bool operator==(const BlendIfRange&) const = default;

    /** Weight 0–1 for a luminance value in 0–1. */
    float weight(float value) const {
        const float v = std::clamp(value, 0.0F, 1.0F) * 255.0F;
        const float bl = black_lo;
        const float bh = std::max(black_hi, black_lo);
        const float wl = white_lo;
        const float wh = std::max(white_hi, white_lo);
        float up = 1.0F;
        if (v < bh) up = v <= bl ? 0.0F : (v - bl) / (bh - bl);
        float down = 1.0F;
        if (v > wl) down = v >= wh ? 0.0F : (wh - v) / (wh - wl);
        return up * down;
    }
};

/** Rec.601 luma of encoded RGB (what Photoshop's Gray channel uses). */
inline float blendIfLuma(float r, float g, float b) { return 0.299F * r + 0.587F * g + 0.114F * b; }

/**
 * Gray (luminance) ranges plus one this-layer/underlying pair for each of R, G and B. A pixel's weight is the product of all
 * eight, like Photoshop's Blend If with a channel picked per slider.
 */
struct BlendIf {
    BlendIfRange self;
    BlendIfRange under;
    std::array<BlendIfRange, 3> channel_self{};
    std::array<BlendIfRange, 3> channel_under{};

    bool channelsActive() const {
        for (std::size_t c = 0; c < 3; ++c) {
            if (!channel_self[c].isDefault() || !channel_under[c].isDefault()) return true;
        }
        return false;
    }
    bool active() const { return !self.isDefault() || !under.isDefault() || channelsActive(); }
    bool operator==(const BlendIf&) const = default;

    /** Combined weight 0–1 for encoded RGB of the layer pixel ([src]) and of the backdrop beneath it ([under_rgb]). */
    float weight(const float src[3], const float under_rgb[3]) const {
        float w = self.weight(blendIfLuma(src[0], src[1], src[2])) * under.weight(blendIfLuma(under_rgb[0], under_rgb[1], under_rgb[2]));
        for (std::size_t c = 0; c < 3; ++c) w *= channel_self[c].weight(src[c]) * channel_under[c].weight(under_rgb[c]);
        return w;
    }
};

}  // namespace croptop
