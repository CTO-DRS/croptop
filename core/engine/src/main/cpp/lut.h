#pragma once

// Colour lookup tables: .cube and .3dl parsing plus a CPU reference for applying
// them (the GPU path samples toTexture()).
//   .cube (Adobe/Resolve): TITLE, LUT_1D_SIZE (2–65536), LUT_3D_SIZE (2–129), DOMAIN_MIN/MAX,
//         LUT_1D_INPUT_RANGE / LUT_3D_INPUT_RANGE; red varies fastest in the 3D table. A file with both a
//         1D and a 3D table applies the 1D table first (a shaper).
//   .3dl (Autodesk/Lustre): a mesh line of N input values (uniform), then N³ integer RGB rows with blue
//         varying fastest; output depth from a "Mesh <in> <out>" line or else the largest value
//         (≤1023 → 10-bit, ≤4095 → 12-bit, ≤65535 → 16-bit).
// Inputs outside the domain are clamped. 3D lookups use tetrahedral interpolation (exact for functions
// that are linear per channel); 1D lookups are linear. LUTs apply to the stored values (sRGB-encoded for
// RGBA8/16, as-is for RGBA32F); alpha is kept.

#include <array>
#include <cstdint>
#include <optional>
#include <string>
#include <string_view>
#include <vector>

#include "tile_store.h"

namespace croptop::color {

struct Lut final {
    std::string title;
    std::uint32_t size_1d = 0;
    std::vector<float> table_1d;  // size_1d × RGB
    std::array<float, 3> domain_min_1d{0.0F, 0.0F, 0.0F};
    std::array<float, 3> domain_max_1d{1.0F, 1.0F, 1.0F};
    std::uint32_t size_3d = 0;
    std::vector<float> table_3d;  // size_3d³ × RGB, red fastest
    std::array<float, 3> domain_min_3d{0.0F, 0.0F, 0.0F};
    std::array<float, 3> domain_max_3d{1.0F, 1.0F, 1.0F};

    std::array<float, 3> apply(std::array<float, 3> rgb) const;
    /** size_3d³ RGBA32F texels (x = red, y = green, z = blue; alpha 1) for a GPU 3D texture; empty without a 3D table. */
    std::vector<float> toTexture() const;
};

std::optional<Lut> parseCube(std::string_view text, std::string& error);
std::optional<Lut> parse3dl(std::string_view text, std::string& error);
/** Picks the parser by the file name's extension (.cube / .3dl), else by sniffing the text. */
std::optional<Lut> parseLut(std::string_view text, std::string_view file_name, std::string& error);

/** Applies `lut` to an RGBA layer, blended by `strength` (0–1) × the optional A16 mask's coverage. */
bool applyLut(TileStore& target, const Lut& lut, float strength, const TileStore* mask, std::string& error,
              unsigned worker_threads = 0);

}  // namespace croptop::color
