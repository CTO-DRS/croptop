// Variable TrueType fonts: fvar axes, avar, gvar deltas with IUP, HVAR advances. Uses the real variable
// fonts that ship with the host (Ubuntu); each test skips when its font is absent.
#include "font_variations.h"
#include "truetype_font.h"

#include <gtest/gtest.h>

#include <algorithm>
#include <cmath>
#include <cstdlib>
#include <filesystem>
#include <fstream>
#include <iterator>
#include <string>
#include <string_view>
#include <utility>
#include <vector>

namespace {

namespace text = croptop::text;
using Axes = std::vector<std::pair<std::string, float>>;

std::shared_ptr<const text::TrueTypeFont> loadFont(const std::string& path) {
    if (!std::filesystem::exists(path)) return nullptr;
    std::ifstream in(path, std::ios::binary);
    const std::vector<std::uint8_t> bytes((std::istreambuf_iterator<char>(in)), std::istreambuf_iterator<char>());
    std::string error;
    auto font = text::TrueTypeFont::load(bytes, error);
    EXPECT_TRUE(font) << error;
    return font;
}

constexpr const char* kUbuntu = "/usr/share/fonts/truetype/ubuntu/Ubuntu[wdth,wght].ttf";
constexpr const char* kUbuntuMono = "/usr/share/fonts/truetype/ubuntu/UbuntuMono[wght].ttf";

struct Extent {
    double width = 0, height = 0;
    bool valid = false;
};

Extent extentOf(const croptop::vector::Path& path) {
    Extent e;
    if (path.points().empty()) return e;
    double lo_x = 1e30, hi_x = -1e30, lo_y = 1e30, hi_y = -1e30;
    for (const auto& p : path.points()) {
        lo_x = std::min(lo_x, p.x);
        hi_x = std::max(hi_x, p.x);
        lo_y = std::min(lo_y, p.y);
        hi_y = std::max(hi_y, p.y);
    }
    e.width = hi_x - lo_x;
    e.height = hi_y - lo_y;
    e.valid = true;
    return e;
}

double inkWidth(const text::TrueTypeFont& font, char32_t cp) { return extentOf(font.outline(font.glyphFor(cp))).width; }

/** Net filled area of the outline in font units squared (outer contours minus holes). */
double inkArea(const text::TrueTypeFont& font, char32_t cp) {
    double sum = 0;
    for (const auto& line : croptop::vector::flatten(font.outline(font.glyphFor(cp)), 0.5)) {
        double a = 0;
        for (std::size_t i = 0; i < line.points.size(); ++i) {
            const auto& p = line.points[i];
            const auto& q = line.points[(i + 1) % line.points.size()];
            a += p.x * q.y - q.x * p.y;
        }
        sum += a / 2.0;
    }
    return std::abs(sum);
}

TEST(FontVariations, ReadsTheAxesOfAVariableFont) {
    const auto font = loadFont(kUbuntu);
    if (!font) GTEST_SKIP() << "no variable Ubuntu font on this host";
    const auto& axes = font->variationAxes();
    ASSERT_EQ(axes.size(), 2U);
    const auto find = [&](const std::string& tag) {
        return std::find_if(axes.begin(), axes.end(), [&](const text::VariationAxis& a) { return a.tag == tag; });
    };
    ASSERT_NE(find("wght"), axes.end());
    ASSERT_NE(find("wdth"), axes.end());
    EXPECT_FLOAT_EQ(find("wght")->min, 100.0F);
    EXPECT_FLOAT_EQ(find("wght")->def, 400.0F);
    EXPECT_FLOAT_EQ(find("wght")->max, 800.0F);
    EXPECT_FLOAT_EQ(find("wdth")->min, 75.0F);
    EXPECT_FALSE(font->isVaried());
}

TEST(FontVariations, StaticFontsHaveNoAxes) {
    const auto font = loadFont("/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf");
    if (!font) GTEST_SKIP() << "no DejaVu Sans on this host";
    EXPECT_TRUE(font->variationAxes().empty());
    const auto same = font->withVariation(Axes{{"wght", 900.0F}});
    EXPECT_FALSE(same->isVaried());
    EXPECT_EQ(same->advance(same->glyphFor('A')), font->advance(font->glyphFor('A')));
}

TEST(FontVariations, TheDefaultInstanceMatchesTheUnvariedFont) {
    const auto font = loadFont(kUbuntu);
    if (!font) GTEST_SKIP();
    const auto def = font->withVariation(Axes{{"wght", 400.0F}, {"wdth", 100.0F}});
    EXPECT_FALSE(def->isVaried()) << "default coordinates are the unvaried font";
    for (const char32_t cp : std::u32string_view(U"HgAa&")) {
        EXPECT_EQ(def->advance(def->glyphFor(cp)), font->advance(font->glyphFor(cp)));
    }
}

TEST(FontVariations, WeightWidensStrokesAndAdvances) {
    const auto font = loadFont(kUbuntu);
    if (!font) GTEST_SKIP();
    const auto light = font->withVariation(Axes{{"wght", 100.0F}});
    const auto bold = font->withVariation(Axes{{"wght", 800.0F}});
    ASSERT_TRUE(light->isVaried());
    ASSERT_TRUE(bold->isVaried());
    for (const char32_t cp : std::u32string_view(U"HOn")) {
        const auto g = font->glyphFor(cp);
        EXPECT_LT(light->advance(g), font->advance(g)) << static_cast<char>(cp) << ": a lighter weight is narrower";
        EXPECT_GT(bold->advance(g), font->advance(g)) << static_cast<char>(cp) << ": a bolder weight is wider";
        EXPECT_GT(inkWidth(*bold, cp), inkWidth(*light, cp));
        EXPECT_GT(inkArea(*bold, cp), inkArea(*light, cp) * 1.5) << static_cast<char>(cp) << ": heavier strokes cover much more area";
    }
}

TEST(FontVariations, WidthNarrowsGlyphs) {
    const auto font = loadFont(kUbuntu);
    if (!font) GTEST_SKIP();
    const auto narrow = font->withVariation(Axes{{"wdth", 75.0F}});
    for (const char32_t cp : std::u32string_view(U"HOn")) {
        const auto g = font->glyphFor(cp);
        EXPECT_LT(narrow->advance(g), font->advance(g) * 0.95) << static_cast<char>(cp);
        EXPECT_LT(inkWidth(*narrow, cp), inkWidth(*font, cp));
    }
}

TEST(FontVariations, ValuesOutsideTheRangeClampAndIntermediateValuesInterpolate) {
    const auto font = loadFont(kUbuntu);
    if (!font) GTEST_SKIP();
    const auto g = font->glyphFor('H');
    const auto max = font->withVariation(Axes{{"wght", 800.0F}});
    const auto over = font->withVariation(Axes{{"wght", 5000.0F}});
    EXPECT_EQ(over->advance(g), max->advance(g));
    const auto mid = font->withVariation(Axes{{"wght", 600.0F}});
    EXPECT_GT(mid->advance(g), font->advance(g));
    EXPECT_LT(mid->advance(g), max->advance(g));
    const auto unknown = font->withVariation(Axes{{"ital", 1.0F}});
    EXPECT_FALSE(unknown->isVaried()) << "an axis the font does not have changes nothing";
}

TEST(FontVariations, CompoundGlyphsFollowTheirComponents) {
    const auto font = loadFont(kUbuntu);
    if (!font) GTEST_SKIP();
    const auto bold = font->withVariation(Axes{{"wght", 800.0F}});
    // 'é' is a base glyph plus an accent in most fonts; its bold advance must grow and its outline must stay non-empty.
    const auto g = font->glyphFor(0x00E9);
    ASSERT_NE(g, 0);
    EXPECT_GT(bold->advance(g), font->advance(g));
    const auto a = extentOf(bold->outline(g));
    const auto b = extentOf(font->outline(g));
    ASSERT_TRUE(a.valid && b.valid);
    EXPECT_GT(a.width, b.width);
    EXPECT_GT(inkArea(*bold, 0x00E9), inkArea(*font, 0x00E9));
}

TEST(FontVariations, AMonospaceFontKeepsItsAdvancesWhileTheStrokesThicken) {
    const auto font = loadFont(kUbuntuMono);
    if (!font) GTEST_SKIP();
    const auto bold = font->withVariation(Axes{{"wght", 700.0F}});
    ASSERT_TRUE(bold->isVaried());
    for (const char32_t cp : std::u32string_view(U"HWi")) {
        const auto g = font->glyphFor(cp);
        EXPECT_EQ(bold->advance(g), font->advance(g)) << static_cast<char>(cp) << ": monospace";
    }
    EXPECT_GT(inkArea(*bold, 'i'), inkArea(*font, 'i')) << "bold strokes cover more area";
}

TEST(FontVariations, MalformedTablesAreIgnoredNotFatal) {
    const std::vector<std::uint8_t> junk(64, 0x7F);
    EXPECT_EQ(text::FontVariations::parse(junk, {}, {}, {}, 10), nullptr);
    EXPECT_EQ(text::FontVariations::parse({}, {}, {}, {}, 10), nullptr);
}

TEST(FontVariations, VariedTextRendersWithHeavierInkAndSetsTheLineWider) {
    const auto font = loadFont(kUbuntu);
    if (!font) GTEST_SKIP();
    struct Sample {
        const char* label;
        Axes axes;
    };
    const std::vector<Sample> samples = {{"wght 100", {{"wght", 100.0F}}}, {"wght 400", {}}, {"wght 800", {{"wght", 800.0F}}},
                                         {"wdth 75", {{"wdth", 75.0F}}}, {"wght 800 wdth 75", {{"wght", 800.0F}, {"wdth", 75.0F}}}};
    const std::u32string line = U"Hamburgefonts \u00E9\u00C5 0123";
    const std::string utf8 = "Hamburgefonts \xC3\xA9\xC3\x85 0123";
    croptop::TileStore sheet(croptop::PixelFormat::RGBA8, 900, 70 * static_cast<std::uint32_t>(samples.size()) + 10);
    std::vector<double> widths, inks;
    std::uint32_t row = 0;
    for (const auto& sample : samples) {
        const auto varied = font->withVariation(sample.axes);
        const text::TrueTypeShaper shaper(varied);
        text::ParagraphStyle style;
        style.size_px = 48;
        const auto layout = text::layout(utf8, style, 0, shaper);
        std::string error;
        ASSERT_TRUE(text::renderText(sheet, layout, *varied, {0, 0, 0, 1}, 10, 8 + 70 * row, error)) << error;
        float width = 0;
        for (const auto& g : shaper.shape(line, 48)) width += g.advance;
        widths.push_back(width);
        double ink = 0;
        for (std::uint32_t y = 8 + 70 * row; y < 8 + 70 * row + 66; ++y) {
            for (std::uint32_t x = 0; x < 900; ++x) {
                const auto t = sheet.readTile({static_cast<std::int32_t>(x / 256), static_cast<std::int32_t>(y / 256)});
                if (t) ink += t->data()[(static_cast<std::size_t>(y % 256) * 256 + x % 256) * 4 + 3] / 255.0;
            }
        }
        inks.push_back(ink);
        ++row;
    }
    EXPECT_LT(widths[0], widths[1]);
    EXPECT_LT(widths[1], widths[2]);
    EXPECT_LT(widths[3], widths[1]);
    EXPECT_LT(widths[4], widths[2]);
    EXPECT_LT(inks[0], inks[1]);
    EXPECT_LT(inks[1], inks[2]);
    if (const char* dump = std::getenv("CROPTOP_FONT_DUMP")) {
        std::ofstream ppm(std::filesystem::path(dump) / "font_variations.ppm", std::ios::binary);
        ppm << "P6\n" << sheet.width() << " " << sheet.height() << "\n255\n";
        for (std::uint32_t y = 0; y < sheet.height(); ++y) {
            for (std::uint32_t x = 0; x < sheet.width(); ++x) {
                const auto t = sheet.readTile({static_cast<std::int32_t>(x / 256), static_cast<std::int32_t>(y / 256)});
                const std::uint8_t a = t ? t->data()[(static_cast<std::size_t>(y % 256) * 256 + x % 256) * 4 + 3] : 0;
                const char v = static_cast<char>(255 - a);
                ppm.write(&v, 1);
                ppm.write(&v, 1);
                ppm.write(&v, 1);
            }
        }
    }
}

}  // namespace
