#pragma once

// Symmetry painting: replicates generated brush dabs across a symmetry
// frame, so every copy shares the stroke's spacing, jitter and scatter (they come out mirrored exactly) and
// the tip orientation follows each copy (a reflection maps angle θ to 2φ − θ, a rotation adds α).
//   Vertical / Horizontal / Diagonal: one mirror line through the centre (at 90°, 0° and 45° plus the
//   frame rotation). Dual axis: both vertical and horizontal mirrors (4 copies). Radial: N rotations.
//   Mandala: N rotations, each also mirrored (2N copies, a kaleidoscope).
// Copies are emitted per input dab (copy 0 is the original), so all copies progress together and a
// build-up or wet-edge compositor sees the same order in each.

#include <cstdint>
#include <span>
#include <string>
#include <vector>

#include "brush_engine.h"

namespace croptop::brush {

enum class SymmetryType : std::uint8_t { Off, Vertical, Horizontal, DualAxis, Diagonal, Radial, Mandala };

struct SymmetrySpec final {
    SymmetryType type = SymmetryType::Off;
    float center_x = 0, center_y = 0;  // layer pixels
    float rotation = 0;                // frame rotation, degrees
    int segments = 6;                  // Radial / Mandala: 2–36
};

/** Number of copies per dab (1 when off). */
int symmetryCopies(const SymmetrySpec& spec);

/** Appends every symmetric copy of each input dab to `out`. False (with `error`) for an invalid spec. */
bool symmetrize(const SymmetrySpec& spec, std::span<const Dab> dabs, std::vector<Dab>& out, std::string& error);

}  // namespace croptop::brush
