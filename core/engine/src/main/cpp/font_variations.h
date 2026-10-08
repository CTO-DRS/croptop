#pragma once

// TrueType variable fonts: the `fvar` axes, `avar` remapping, `gvar` glyph deltas (with IUP for
// points a tuple leaves out) and `HVAR` advance deltas, without FreeType/HarfBuzz. Every read is bounds-checked; malformed
// tables disable variation (the font renders at its default) instead of failing the font.

#include <cstdint>
#include <memory>
#include <span>
#include <string>
#include <utility>
#include <vector>

namespace croptop::text {

struct VariationAxis final {
    std::string tag;  // "wght", "wdth", ...
    float min = 0, def = 0, max = 0;
};

/** A simple glyph's outline as `gvar` needs it for IUP: contour end points and the original point coordinates. */
struct VariationOutline final {
    std::span<const std::uint16_t> contour_ends;
    std::span<const double> xs, ys;
};

class FontVariations final {
public:
    /** The `fvar` axes plus whichever of `avar`, `gvar` and `HVAR` exist; null when the font has no usable `fvar`. */
    static std::shared_ptr<const FontVariations> parse(std::span<const std::uint8_t> fvar, std::span<const std::uint8_t> avar,
                                                       std::span<const std::uint8_t> gvar, std::span<const std::uint8_t> hvar,
                                                       std::uint16_t glyph_count);

    const std::vector<VariationAxis>& axes() const { return axes_; }
    bool hasGlyphVariations() const { return !gvar_.empty(); }

    /** Normalised coordinates (−1…1 per axis, after `avar`) for user-space values by axis tag; missing axes use their default. */
    std::vector<float> normalize(std::span<const std::pair<std::string, float>> user) const;

    /**
     * Adds the glyph's deltas for `coords` to `dx`/`dy`, which must hold `point_count` entries: the outline (or component
     * offset) points followed by the four phantom points. `outline` enables IUP for tuples that name only some points.
     * Returns false when the glyph has no variation data.
     */
    bool glyphDeltas(std::uint16_t glyph, const std::vector<float>& coords, std::size_t point_count, const VariationOutline* outline,
                     std::vector<double>& dx, std::vector<double>& dy) const;

    /** Change of the glyph's advance width in font units: from `HVAR` when present, else from the `gvar` phantom points. */
    double advanceDelta(std::uint16_t glyph, const std::vector<float>& coords, std::size_t point_count) const;

private:
    FontVariations() = default;
    std::vector<VariationAxis> axes_;
    std::vector<std::vector<std::pair<float, float>>> segment_maps_;  // avar: (from, to) per axis, may be empty
    std::vector<std::uint8_t> gvar_, hvar_;
    std::uint16_t glyph_count_ = 0;
};

}  // namespace croptop::text
