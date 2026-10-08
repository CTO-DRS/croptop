#pragma once

// Photoshop document reader, native parser only. Input is untrusted: sizes, counts and
// nesting are bounded, and a damaged layer is skipped with a warning where the structure still allows it.
//
// Supported: PSD (v1) and PSB (v2); RGB, Grayscale and CMYK (converted naively to RGB, with a warning);
// 8/16/32-bit (32-bit documents are linear light, stored as RGBA32F); RAW, RLE (PackBits) and ZIP with or
// without prediction; layer records (name incl. `luni`, bounds, blend mode, opacity, fill `iOpa`, clipping,
// visibility, transparency lock), groups (`lsct`/`lsdk` folder and divider records), user masks (−2 channel,
// with default colour and disabled flag), 16/32-bit layer data inside `Lr16`/`Lr32`, the merged composite,
// and the ICC profile resource (1039). Other additional-layer-info blocks (text `TySh`, smart objects,
// effects `lfx2`, adjustment layers…) are kept as raw keyed blobs for best-effort follow-ups.
// Layer pixels are placed on canvas-sized sparse TileStores; pixels outside the canvas are dropped (warned).

#include <array>
#include <cstdint>
#include <optional>
#include <span>
#include <string>
#include <vector>

#include "blend_modes.h"
#include "tile_store.h"

namespace croptop::psd {

/** Four Blend If groups (gray, red, green, blue), each {0, 0, 255, 255} for this layer and the underlying layers: nothing hidden. */
inline constexpr std::array<std::uint8_t, 32> kDefaultBlendRanges = [] {
    std::array<std::uint8_t, 32> a{};
    for (std::size_t i = 0; i < a.size(); i += 4) {
        a[i + 2] = 255;
        a[i + 3] = 255;
    }
    return a;
}();

/**
 * File order is bottom to top: a group appears as its GroupDivider (the bounding "</Layer group>" marker), then
 * its children, then its GroupFolder record, which carries the group's name, blend mode, opacity and state.
 */
enum class LayerKind : std::uint8_t { Pixel, GroupFolder, GroupDivider };

struct Mask final {
    TileStore coverage;  // A16, canvas-sized
    std::uint8_t default_color = 0;  // coverage outside the mask's rectangle (0 or 255)
    bool disabled = false;
    bool linked = true;  // moves with the layer
};

struct AdditionalInfo final {
    std::string key;  // e.g. "TySh", "lfx2", "SoLd", "levl"
    std::vector<std::uint8_t> data;
};

struct Layer final {
    std::string name;
    LayerKind kind = LayerKind::Pixel;
    bool group_open = true;       // GroupFolder only
    bool pass_through = false;    // group blend mode "pass"
    BlendMode mode = BlendMode::Normal;
    std::uint8_t opacity = 255;
    std::uint8_t fill = 255;
    bool clipped = false;
    bool visible = true;
    bool lock_transparency = false;
    /**
     * Blend If as 4 groups of 8: composite gray, then red, green, blue; each is this layer [black lo, black hi, white lo, white hi]
     * followed by the underlying layers' four values (the order of Photoshop's blending-range data).
     */
    std::array<std::uint8_t, 32> blend_ranges = kDefaultBlendRanges;
    std::int32_t top = 0, left = 0, bottom = 0, right = 0;  // layer bounds in document pixels
    std::optional<TileStore> pixels;  // RGBA, canvas-sized (Pixel layers with content)
    std::optional<Mask> mask;
    std::vector<AdditionalInfo> extra;
};

enum class ColorMode : std::uint16_t { Bitmap = 0, Grayscale = 1, Indexed = 2, Rgb = 3, Cmyk = 4, Multichannel = 7, Duotone = 8, Lab = 9 };

struct Document final {
    bool psb = false;
    std::uint32_t width = 0;
    std::uint32_t height = 0;
    std::uint16_t depth = 8;
    ColorMode color_mode = ColorMode::Rgb;
    std::vector<std::uint8_t> icc_profile;
    float global_light_angle = 120.0F;     // resource 1037 (effects with "use global light")
    float global_light_altitude = 30.0F;   // resource 1049
    std::vector<Layer> layers;  // file order: bottom to top
    std::optional<TileStore> composite;  // the merged image
    /** Resource 1057 hasRealMergedData: false when saved without "Maximize compatibility" (the merged image is a placeholder). */
    bool real_merged_data = true;
    std::vector<std::string> warnings;
};

struct Limits final {
    std::uint32_t max_dimension = 300'000;
    std::uint64_t max_canvas_pixels = 1ULL << 30;   // 1 G px
    std::uint64_t max_working_bytes = 1ULL << 30;   // decode buffers for one layer
    std::uint32_t max_layers = 10'000;
    bool read_composite = true;
};

std::optional<Document> read(std::span<const std::uint8_t> file, std::string& error, const Limits& limits = {});

}  // namespace croptop::psd
