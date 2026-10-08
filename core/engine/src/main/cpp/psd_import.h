#pragma once

// Layered PSD/PSB opening: turns psd::read's flat, bottom-to-top record list into the layer tree a
// document builds its LayerNodes from (Photoshop panel order, top to bottom, groups with children). Every
// pixel store is created from a caller-supplied template store (the new document's background), so layers
// share the document's tile pool and memory budget and take its depth.
//   Pixel layers, and text and smart-object layers via their stored rasters, carry pixels (and a mask).
//   Adjustment layers carry their Photoshop key (levl, curv, hue2, …) and no pixels; the caller decides whether
//   to build an adjustment layer or skip it with a warning.
// Blend modes are Photoshop's 4-character keys ("norm", "mul ", "pass" for pass-through groups), which
// core:model BlendMode.psdKey maps exactly.

#include <array>
#include <cstdint>
#include <optional>
#include <span>
#include <string>
#include <vector>

#include "psd_reader.h"
#include "tile_store.h"

namespace croptop::psd {

enum class ImportKind : std::uint8_t { Pixel, Group, Text, SmartObject, Adjustment };

/** Editable content of a type layer (`TySh`): the first run's style only. */
struct ImportedText final {
    bool present = false;
    std::string text;
    std::string font;           // PostScript name of the first font, e.g. "Arial-BoldMT"
    double size = 0;            // font size × the layer transform's vertical scale (document pixels)
    double x = 0, y = 0;        // transform translation: the baseline origin of point text
    int align = 0;              // 0 left, 1 right, 2 center (Photoshop Justification)
    float color[3] = {0, 0, 0}; // first run fill (sRGB 0..1)
    double box_width = 0;       // paragraph text box width; 0 = point text
    double rotation = 0;        // degrees, clockwise on screen
};

struct ImportedLayer final {
    ImportKind kind = ImportKind::Pixel;
    std::string name;
    std::string blend_key = "norm";
    std::uint8_t opacity = 255, fill = 255;
    bool visible = true, clipped = false, lock_transparency = false, group_open = true;
    std::array<std::uint8_t, 32> blend_ranges = psd::kDefaultBlendRanges;  // Blend If: gray, red, green, blue; each this layer then underlying
    std::optional<TileStore> pixels;     // canvas-sized, template's format/pool/budget
    std::optional<TileStore> mask;       // A16 coverage, canvas-sized
    std::uint8_t mask_default = 0;       // coverage outside the mask rectangle
    bool mask_disabled = false;
    std::string adjustment_key;          // Adjustment layers
    std::vector<std::uint8_t> adjustment_data;  // that block's raw payload (settings), when at most 64 KiB
    std::vector<float> styles;           // layer effects (`lfx2`) in the layer_styles_codec layout; empty when none
    ImportedText text;                   // Text layers
    std::vector<ImportedLayer> children; // Group layers, top to bottom
};

struct ImportedDocument final {
    std::uint32_t width = 0, height = 0;
    std::uint16_t depth = 8;
    std::vector<std::uint8_t> icc_profile;
    std::vector<ImportedLayer> layers;  // top to bottom
    std::vector<std::string> warnings;
};

/**
 * Reads the layers of a PSD/PSB. `pixel_template` must be an RGBA store of the document's size (its format,
 * tile pool and memory budget are reused; its pixels are ignored); `mask_template` likewise an A16 store (or
 * null to create masks with default settings). A file without layers yields one Pixel layer from the merged
 * image.
 */
std::optional<ImportedDocument> importLayers(std::span<const std::uint8_t> file, const TileStore& pixel_template, const TileStore* mask_template,
                                             std::string& error);

/** The layer tree as JSON (for the JNI bridge); `handles` receives each store in order of appearance, and
 * the JSON refers to them by index ("pixels": i, "mask": j, −1 when absent). */
std::string toJson(const ImportedDocument& document, std::vector<const TileStore*>& handles);

}  // namespace croptop::psd
