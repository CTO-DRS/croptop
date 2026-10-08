#pragma once

// Develop local-mask generators, native core. All produce canvas-sized A16 coverage
// (sparse where empty) that combines with selection::combine (add / subtract / intersect) and blends
// per-mask adjustment subsets.
//   linearGradient: 1 at `start`, 0 at `end`, smoothstep across the band; perpendicular to start→end.
//   radial:         an ellipse (centre, radii, rotation°) that is 1 inside the inner region (1 − feather) and
//                   fades to 0 at its edge (smoothstep in normalised radius); `invert` swaps inside and out.
//   luminanceRange: from an RGBA image — 1 where Rec.709 luma lies in [low, high], fading to 0 over
//                   `smoothness` on each side (0–1 units).
//   brushDab:       adds (or erases) one round dab: size, feather (0–1 of the radius), flow (0–1) accumulates
//                   towards 1 as mask = mask + flow·f·(1 − mask) (erase: mask·(1 − flow·f)).

#include <cstdint>
#include <string>

#include "tile_store.h"

namespace croptop::develop {

struct Point final {
    double x = 0, y = 0;
};

TileStore linearGradient(std::uint32_t width, std::uint32_t height, Point start, Point end);

struct RadialSpec final {
    Point centre;
    double radius_x = 100, radius_y = 100;
    double rotation = 0;   // degrees
    double feather = 0.5;  // 0–1
    bool invert = false;
};
TileStore radial(std::uint32_t width, std::uint32_t height, const RadialSpec& spec);
// Validating overload: rejects non-finite values, non-positive radii, and feather outside [0, 1].
bool radial(std::uint32_t width, std::uint32_t height, const RadialSpec& spec, TileStore& out, std::string& error);

bool luminanceRange(const TileStore& image, float low, float high, float smoothness, TileStore& out, std::string& error);

struct Dab final {
    Point centre;
    double size = 50;      // diameter px
    double feather = 0.5;  // 0–1 of the radius
    double flow = 1.0;     // 0–1
    bool erase = false;
};
bool brushDab(TileStore& mask, const Dab& dab, std::string& error);

}  // namespace croptop::develop
