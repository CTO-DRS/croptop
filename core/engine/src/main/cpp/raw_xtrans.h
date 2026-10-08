#pragma once

// Directional X-Trans demosaicing, structured like Frank Markesteijn's method:
// green is estimated along four directions (horizontal, vertical, both diagonals), each direction yields
// a full RGB candidate through colour differences, and every pixel takes the average of the candidates
// whose neighbourhood is most homogeneous. It is an independent implementation, not a port of dcraw's
// `xtrans_interpolate`.

#include <cstdint>
#include <span>

namespace croptop::raw::detail {

/**
 * Buffers are width × height (row-major); `colours` holds 0/1/2 (R/G/B) per site. Output is valid 9 px
 * inside the buffer edge (callers pass a bordered tile). Output spans must not alias the input.
 */
void xtransDirectional(std::span<const float> cfa, std::span<const std::uint8_t> colours, std::span<float> red, std::span<float> green,
                       std::span<float> blue, int width, int height);

}  // namespace croptop::raw::detail
