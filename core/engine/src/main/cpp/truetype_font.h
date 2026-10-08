#pragma once

// TrueType font reader and text rendering, without FreeType/HarfBuzz.
// Reads TrueType and OpenType fonts (sfnt 0x00010000/'true' with glyf outlines, or 'OTTO' with CFF outlines
// via cff_outlines.h) and font collections ('ttcf', by face index): head, hhea, maxp, cmap (formats 4 and 12,
// Unicode subtables), hmtx, loca/glyf (simple + compound glyphs, nesting ≤ 8) or CFF, and kern (format 0).
// Every table read is bounds-checked; malformed data fails `load` or yields empty glyphs. Hinting and
// GSUB/GPOS shaping are out of scope (HarfBuzz/FreeType integration).

#include <array>
#include <cstdint>
#include <memory>
#include <optional>
#include <span>
#include <string>
#include <vector>

#include "cff_outlines.h"
#include "font_variations.h"
#include "opentype_gsub.h"
#include "opentype_kerning.h"
#include "opentype_marks.h"
#include "text_layout.h"
#include "tile_store.h"
#include "vector_path.h"

namespace croptop::text {

class TrueTypeFont final {
public:
    /** Parses a font file (≤ 64 MiB); the bytes are copied. */
    static std::shared_ptr<const TrueTypeFont> load(std::span<const std::uint8_t> bytes, std::string& error, std::uint32_t face = 0);
    /** Number of faces in a collection (1 for a plain font file, 0 if unreadable). */
    static std::uint32_t faceCount(std::span<const std::uint8_t> bytes);
    bool hasCffOutlines() const { return cff_.has_value(); }

    /** Variable-font axes (`fvar`); empty for a static font (or a CFF one, whose CFF2 variations are not supported). */
    const std::vector<VariationAxis>& variationAxes() const;
    /**
     * A copy of this font at the given axis values (by tag, e.g. {"wght", 700}); axes not named keep their default and values are
     * clamped to each axis range. For a static font the copy is identical. Outlines and advances follow `gvar`/`HVAR`.
     */
    std::shared_ptr<const TrueTypeFont> withVariation(std::span<const std::pair<std::string, float>> axes) const;
    bool isVaried() const { return !coords_.empty(); }

    std::uint16_t unitsPerEm() const { return units_per_em_; }
    std::int16_t ascender() const { return ascender_; }    // font units, positive
    std::int16_t descender() const { return descender_; }  // font units, negative
    std::int16_t lineGap() const { return line_gap_; }
    std::uint16_t glyphCount() const { return glyph_count_; }

    std::uint16_t glyphFor(char32_t code_point) const;  // 0 (.notdef) when unmapped
    std::uint16_t advance(std::uint16_t glyph) const;   // font units
    std::int16_t kerning(std::uint16_t left, std::uint16_t right) const;  // font units (GPOS 'kern', else kern format 0)
    bool hasGposKerning() const { return gpos_.has_value(); }
    bool hasGsub() const { return gsub_.has_value(); }
    const GposMarks* marks() const { return marks_ ? &*marks_ : nullptr; }
    /**
     * Maps a run of code points to glyphs and applies GSUB: Arabic contextual forms (isol/init/medi/fina),
     * then required and standard ligatures (rlig, liga). `clusters` receives each glyph's code-point index.
     */
    void mapAndSubstitute(std::u32string_view run, std::vector<std::uint16_t>& glyphs, std::vector<std::uint32_t>& clusters) const;
    /** Outline in font units, y up (as stored). Empty for spaces, invalid or missing glyphs. */
    vector::Path outline(std::uint16_t glyph) const;

private:
    TrueTypeFont() = default;
    std::vector<std::uint8_t> data_;
    std::uint16_t units_per_em_ = 1000, glyph_count_ = 0, long_hor_metrics_ = 0;
    std::int16_t ascender_ = 0, descender_ = 0, line_gap_ = 0;
    bool long_loca_ = false;
    std::optional<CffOutlines> cff_;
    std::shared_ptr<const FontVariations> variations_;
    std::vector<float> coords_;  // normalised axis coordinates; empty = default instance
    std::optional<GposKerning> gpos_;
    std::optional<GsubTable> gsub_;
    std::optional<GposMarks> marks_;
    std::vector<std::uint16_t> isol_, init_, medi_, fina_, rlig_, liga_;
    std::size_t cmap_ = 0, cmap_format_ = 0, hmtx_ = 0, loca_ = 0, glyf_ = 0, glyf_size_ = 0, loca_size_ = 0, kern_ = 0, kern_size_ = 0;
    void appendOutline(vector::Path& path, std::uint16_t glyph, const std::array<double, 6>& transform, int depth) const;
    /** Outline points (or components for a compound glyph) of a TrueType glyph, without the four phantom points. */
    std::size_t glyphPointCount(std::uint16_t glyph) const;
};

/** A layout `Shaper` backed by a TrueType font: cmap lookup, advances and pair kerning (no GSUB/GPOS). */
class TrueTypeShaper final : public Shaper {
public:
    explicit TrueTypeShaper(std::shared_ptr<const TrueTypeFont> font) : font_(std::move(font)) {}
    std::vector<ShapedGlyph> shape(std::u32string_view run, float size_px) const override;
    float ascent(float size_px) const override;
    float descent(float size_px) const override;
    const TrueTypeFont& font() const { return *font_; }

private:
    std::shared_ptr<const TrueTypeFont> font_;
};

/**
 * Font fallback: each code point is shaped with the first font that maps it (the first font's .notdef when
 * none does). The font index is encoded in the glyph id's high 16 bits (`glyph = font << 16 | gid`); render
 * with the font-list `renderText` overload. Kerning applies only between neighbours from the same font.
 */
class FallbackShaper final : public Shaper {
public:
    explicit FallbackShaper(std::vector<std::shared_ptr<const TrueTypeFont>> fonts) : fonts_(std::move(fonts)) {}
    std::vector<ShapedGlyph> shape(std::u32string_view run, float size_px) const override;
    float ascent(float size_px) const override;   // the tallest font's
    float descent(float size_px) const override;  // the deepest font's
    const std::vector<std::shared_ptr<const TrueTypeFont>>& fonts() const { return fonts_; }

private:
    std::vector<std::shared_ptr<const TrueTypeFont>> fonts_;
};

/** Renders a layout shaped by a FallbackShaper over `fonts` (font index in each glyph id's high 16 bits). */
bool renderText(TileStore& layer, const Layout& layout, const std::vector<std::shared_ptr<const TrueTypeFont>>& fonts,
                std::array<float, 4> color, double origin_x, double origin_y, std::string& error);

/**
 * Rasterises laid-out text into `layer` (RGBA8/16/32F), source-over in the given straight colour, with the
 * text box's top-left corner at (origin_x, origin_y). Every glyph is drawn from `font`, so the layout must come
 * from a TrueTypeShaper for that font (glyph ids are font glyph indices).
 */
bool renderText(TileStore& layer, const Layout& layout, const TrueTypeFont& font, std::array<float, 4> color, double origin_x,
                double origin_y, std::string& error);

}  // namespace croptop::text
