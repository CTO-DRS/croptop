// LibFuzzer target: TrueType/CFF fonts through outlines, kerning, GSUB and mark shaping.
#include <cstddef>
#include <cstdint>
#include <span>
#include <string>
#include <vector>

#include "truetype_font.h"

extern "C" int LLVMFuzzerTestOneInput(const std::uint8_t* data, std::size_t size) {
    std::string error;
    const auto font = croptop::text::TrueTypeFont::load(std::span<const std::uint8_t>(data, size), error);
    if (!font) return 0;
    for (const std::uint32_t c : {0x41U, 0x67U, 0x3A9U, 0x416U, 0x1F600U, 0x20U}) {
        const auto glyph = font->glyphFor(c);
        (void)croptop::vector::flatten(font->outline(glyph), 1.0);
        (void)font->advance(glyph);
        (void)font->kerning(glyph, font->glyphFor(0x56));
    }
    std::vector<std::uint16_t> glyphs;
    std::vector<std::uint32_t> clusters;
    font->mapAndSubstitute(U"office بتب لا", glyphs, clusters);
    const croptop::text::TrueTypeShaper shaper(font);
    (void)shaper.shape(U"é بِس office", 24.0F);
    return 0;
}
