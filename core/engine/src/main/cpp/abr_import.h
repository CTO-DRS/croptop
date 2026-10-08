#pragma once

// Photoshop brush (.abr) parser for ABR import. Input is untrusted: every count,
// size and nesting level is bounded, and a malformed brush is skipped with a warning where the file
// structure still allows it (else the whole parse fails with an error).
//
// Supported:
//   v1/v2  — sampled tips (raw or PackBits, 8-bit) and computed tips (diameter, roundness, angle,
//            hardness, spacing; v2 names are best effort).
//   v6–v10 — `8BIM samp` sampled tips (8-bit raw/PackBits, 16-bit raw) keyed by UUID, and the `8BIM desc`
//            action descriptor for preset name, computed/sampled tip, diameter, hardness, angle,
//            roundness, spacing and basic dynamics (size/opacity/flow controls and jitter, minimum
//            diameter). Without a descriptor, every tip becomes a sampled brush with default settings.
// Tips are 8-bit coverage (255 = full paint), row-major.

#include <cstdint>
#include <optional>
#include <span>
#include <string>
#include <vector>

namespace croptop::brush {

struct AbrTip final {
    std::string uuid;  // v6+ only
    std::uint32_t width = 0;
    std::uint32_t height = 0;
    std::vector<std::uint8_t> coverage;
};

/** Photoshop "Control" for a dynamic: 0 off, 1 fade, 2 pen pressure, 3 pen tilt, 4 stylus wheel, 5 rotation. */
struct AbrDynamic final {
    int control = 0;
    float jitter = 0.0F;   // 0–1
    float minimum = 0.0F;  // 0–1 (minimum diameter / opacity / flow)
};

struct AbrBrush final {
    std::string name;
    bool sampled = false;
    /** Index into AbrFile::tips for sampled brushes whose tip was found. */
    std::optional<std::size_t> tip;
    float diameter = 0.0F;   // px
    float hardness = 1.0F;   // 0–1 (computed tips)
    float angle = 0.0F;      // degrees
    float roundness = 1.0F;  // 0–1
    float spacing = 0.25F;   // fraction of diameter
    bool spacing_enabled = true;
    bool has_dynamics = false;
    AbrDynamic size;
    AbrDynamic opacity;
    AbrDynamic flow;
};

struct AbrFile final {
    int version = 0;
    std::vector<AbrTip> tips;
    std::vector<AbrBrush> brushes;
    std::vector<std::string> warnings;
};

struct AbrLimits final {
    std::size_t max_brushes = 10'000;
    std::uint32_t max_tip_edge = 5'000;
    std::uint64_t max_tip_bytes = 512ULL * 1024ULL * 1024ULL;  // all decoded tips together
};

std::optional<AbrFile> parseAbr(std::span<const std::uint8_t> data, std::string& error, const AbrLimits& limits = {});

}  // namespace croptop::brush
