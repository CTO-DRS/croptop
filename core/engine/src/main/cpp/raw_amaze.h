#pragma once

#include <cstdint>
#include <span>

namespace croptop::raw::detail {

/**
 * Independent edge-directed adaptive Bayer interpolation (publicly named EdgeDirected). This version
 * implements Hamilton-Adams-style green and colour-difference reconstruction; it does not implement
 * RawTherapee AMaZE's adaptive-ratio, aliasing-control or homogeneity-refinement stages.
 * Each span contains width*height scratch pixels and output spans are disjoint.
 */
void amaze(std::span<const float> cfa, std::span<const std::uint8_t> colours, std::span<float> red, std::span<float> green,
           std::span<float> blue, int width, int height);

}  // namespace croptop::raw::detail
