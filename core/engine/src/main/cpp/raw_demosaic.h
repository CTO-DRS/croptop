#pragma once

// Raw development front end: CFA normalisation and white balance, demosaicing
// (RCD / bilinear for Bayer, a fast method for X-Trans), highlight handling and the camera → working
// space matrix. Input is the sensor mosaic as LibRaw exposes it (uint16 photosites with black/white
// levels); output is a linear RGBA16 or RGBA32F layer. Work runs on 256² tiles with a border, in
// parallel, so a 45 MP raw never needs full-frame float planes.

#include <array>
#include <cstdint>
#include <functional>
#include <optional>
#include <span>
#include <string>

#include "tile_store.h"

namespace croptop::raw {

/** Colour filter layout: colours[(y % height) * width + (x % width)] with 0 = R, 1 = G, 2 = B. */
struct CfaPattern final {
    std::uint8_t width = 2;
    std::uint8_t height = 2;
    std::array<std::uint8_t, 36> colours{};

    static CfaPattern bayer(const char* layout);  // "RGGB", "BGGR", "GRBG" or "GBRG" (top-left 2×2)
    static CfaPattern xtrans();                   // Fujifilm X-Trans (as at LibRaw's xtrans[0][0])
    bool isBayer() const { return width == 2 && height == 2; }
    std::uint8_t at(std::int64_t x, std::int64_t y) const {
        if (width == 0 || height == 0 || width > 6 || height > 6) return 255;
        const auto px = ((x % width) + width) % width;
        const auto py = ((y % height) + height) % height;
        return colours[static_cast<std::size_t>(py * width + px)];
    }
};

struct CfaImage final {
    std::uint32_t width = 0;
    std::uint32_t height = 0;
    std::span<const std::uint16_t> photosites;  // width × height, row-major
    CfaPattern pattern;
    std::array<float, 3> black{};   // per colour (R, G, B)
    float white = 65535.0F;         // saturation level
};

enum class Demosaic : std::uint8_t {
    Bilinear,
    RCD,
    XTransFast,
    EdgeDirected,       // Hamilton-Adams style; not a full AMaZE implementation.
    XTransDirectional,  // Independent high-quality directional/homogeneity method, not a Markesteijn port.
};
enum class Highlights : std::uint8_t {
    Clip,   // clip every channel at the lowest saturated level: clipped areas turn neutral white
    Blend,  // rebuild clipped channels from the unclipped ones, blending to white as more channels clip
};

enum class OutputPrimaries : std::uint8_t { LinearSRGB, LinearP3, LinearProPhoto, LinearRec2020 };

struct DevelopSpec final {
    Demosaic method = Demosaic::RCD;  // RCD on Bayer; X-Trans defaults to directional HQ, with XTransFast for previews.
    std::array<float, 3> white_balance{1.0F, 1.0F, 1.0F};  // camera multipliers (R, G, B), > 0
    Highlights highlights = Highlights::Blend;
    /** Camera RGB (white-balanced) → output RGB, row-major. Identity keeps camera RGB. */
    std::array<double, 9> camera_to_output{1, 0, 0, 0, 1, 0, 0, 0, 1};
    PixelFormat output_format = PixelFormat::RGBA16;  // RGBA16 (clamped 0–1) or RGBA32F (unbounded)
    unsigned worker_threads = 0;
    std::function<bool(float)> progress;  // 0–1; return false to cancel
};

std::optional<TileStore> develop(const CfaImage& image, const DevelopSpec& spec, std::string& error);

/**
 * Camera → output matrix from a DNG/Adobe `ColorMatrix` (XYZ D65 → camera, row-major), normalised so
 * white-balanced camera neutral maps to output neutral (as dcraw/LibRaw do). ProPhoto (D50) output is
 * Bradford-adapted. Returns nullopt for a singular matrix.
 */
std::optional<std::array<double, 9>> cameraToOutput(const std::array<double, 9>& xyz_to_camera, OutputPrimaries output);

}  // namespace croptop::raw
