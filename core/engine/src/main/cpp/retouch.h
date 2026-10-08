#pragma once

// Toning and focus brush pixel math, per dab. `mask` is the dab's A16 coverage
// footprint at the layer's size; each call computes the tool's full effect on the covered pixels and
// blends it in by coverage (so soft brush edges and flow come from the mask). Colours are processed in
// the stored encoding; alpha is kept except where noted. Spacing, dynamics, sessions and history belong
// to the stroke engine (the retouch stroke contract, `beginRetouch`).
//   dodgeBurn:   gamma lift (dodge) or drop (burn), weighted by tonal range on Rec.709 luma: shadows
//                (1−L)², midtones 4L(1−L), highlights L². γ = 1/(1 + e·w) for dodge, 1 + e·w for burn, where e
//                is the exposure (0–1). With `protect_tones` the gamma applies to luma only and RGB is
//                scaled by the luma ratio (limited so no channel clips), keeping hue and saturation.
//   sponge:      scales chroma around luma by 1 ± flow. With `vibrance`, saturation boosts less-saturated
//                colours more (1 + flow·(1 − s)) and is limited so no channel clips; desaturation removes
//                strong colours first (1 − flow·s), where s = max − min.
//   blurSharpen: blur mixes towards a 3×3 binomial blur (premultiplied, so transparent neighbours don't
//                darken edges) by `strength`; sharpen adds 1.5·strength·(c − blur). With
//                `protect_detail`, sharpened values are clamped to the 3×3 neighbourhood's range (no halos
//                or noise blow-up).
//   smudge:      mixes each covered pixel towards the layer at p − offset (the previous dab position), by
//                `strength`, premultiplied; `finger_color` (finger painting: set it for the stroke's first
//                dab) mixes towards that colour instead.

#include <array>
#include <cstdint>
#include <optional>
#include <string>

#include "tile_store.h"

namespace croptop::paint {

enum class ToneRange : std::uint8_t { Shadows, Midtones, Highlights };

struct DodgeBurnSpec final {
    bool burn = false;
    ToneRange range = ToneRange::Midtones;
    float exposure = 0.5F;  // 0–1
    bool protect_tones = true;
};

struct SpongeSpec final {
    bool saturate = false;
    float flow = 0.5F;  // 0–1
    bool vibrance = true;
};

struct FocusSpec final {
    bool sharpen = false;
    float strength = 0.5F;  // 0–1
    bool protect_detail = true;
};

struct SmudgeSpec final {
    double offset_x = 0.0;  // current dab position − previous dab position, px
    double offset_y = 0.0;
    float strength = 0.5F;  // 0–1
    std::optional<std::array<float, 4>> finger_color;  // straight RGBA, 0–1
};

/**
 * Background Eraser, one dab: pixels under the footprint whose colour is near `sample` are erased to transparency, weighted by
 * the dab's coverage. `tolerance` (0–1) is the share of the RGB distance range that is erased completely; the next half as
 * far again fades out. `contiguous` only erases pixels connected to the hotspot through erasable pixels (so an island of the
 * same colour behind a different one survives). `protect` colours (within tolerance/2) are never erased. Straight RGB, 0–1.
 */
struct BackgroundEraseSpec final {
    std::array<float, 3> sample{};
    float tolerance = 0.3F;
    bool contiguous = true;
    double hotspot_x = 0.0, hotspot_y = 0.0;  // document pixels: the dab centre
    std::optional<std::array<float, 3>> protect;
};

bool backgroundErase(TileStore& dst, const TileStore& mask, const BackgroundEraseSpec& spec, std::string& error);

bool dodgeBurn(TileStore& dst, const TileStore& mask, const DodgeBurnSpec& spec, std::string& error);
bool sponge(TileStore& dst, const TileStore& mask, const SpongeSpec& spec, std::string& error);
bool blurSharpen(TileStore& dst, const TileStore& mask, const FocusSpec& spec, std::string& error);
bool smudge(TileStore& dst, const TileStore& mask, const SmudgeSpec& spec, std::string& error);

}  // namespace croptop::paint
