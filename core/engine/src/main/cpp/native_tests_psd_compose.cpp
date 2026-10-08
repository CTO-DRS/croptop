// Tests for the PSD layer-stack CPU compositor.
#include "psd_compose.h"
#include "psd_writer.h"

#include <gtest/gtest.h>

#include <array>
#include <cstring>
#include <functional>
#include <string>

#include "pixel_plane.h"

namespace {

namespace ps = croptop::psd;
using croptop::BlendMode;
using croptop::PixelFormat;
using croptop::TileStore;
using Rgbaf = std::array<float, 4>;

constexpr std::uint32_t kW = 64, kH = 64;

TileStore fill(const std::function<Rgbaf(std::uint32_t, std::uint32_t)>& f) {
    TileStore s(PixelFormat::RGBA8, kW, kH);
    croptop::TileBuffer t(s.tile_byte_size(), 0);
    bool any = false;
    for (std::uint32_t y = 0; y < kH; ++y)
        for (std::uint32_t x = 0; x < kW; ++x) {
            const auto c = f(x, y);
            if (c[3] <= 0.0F) continue;
            croptop::paint::detail::writePixel(s.format(), t.data() + (static_cast<std::size_t>(y) * 256 + x) * 4, c.data());
            any = true;
        }
    if (any) {
        EXPECT_TRUE(s.writeTile({0, 0}, t));
    }
    return s;
}

TileStore solid(Rgbaf c) {
    return fill([c](std::uint32_t, std::uint32_t) { return c; });
}

Rgbaf at(const TileStore& s, std::uint32_t x, std::uint32_t y) {
    Rgbaf c{};
    const auto t = s.readTile({0, 0});
    if (t) croptop::paint::detail::readPixel(s.format(), t->data() + (static_cast<std::size_t>(y) * 256 + x) * 4, c.data());
    return c;
}

ps::Layer pixelLayer(const std::string& name, TileStore pixels, BlendMode mode = BlendMode::Normal, std::uint8_t opacity = 255) {
    ps::Layer l;
    l.name = name;
    l.mode = mode;
    l.opacity = opacity;
    l.pixels.emplace(std::move(pixels));
    return l;
}

ps::Document doc() {
    ps::Document d;
    d.width = kW;
    d.height = kH;
    d.depth = 8;
    return d;
}

TileStore composeOk(const ps::Document& d) {
    std::string error;
    auto out = ps::compose(d, error);
    EXPECT_TRUE(out) << error;
    return out ? std::move(*out) : TileStore(PixelFormat::RGBA8, kW, kH);
}

void expectRgb(const Rgbaf& a, Rgbaf b, float tolerance = 0.01F) {
    for (int c = 0; c < 4; ++c) EXPECT_NEAR(a[static_cast<std::size_t>(c)], b[static_cast<std::size_t>(c)], tolerance) << "channel " << c;
}

TEST(PsdCompose, BlendsOpacityModesAndHiddenLayers) {
    auto d = doc();
    d.layers.push_back(pixelLayer("bg", solid({1, 0, 0, 1})));
    d.layers.push_back(pixelLayer("blue", solid({0, 0, 1, 1}), BlendMode::Normal, 128));
    expectRgb(at(composeOk(d), 5, 5), {0.498F, 0, 0.502F, 1});
    d.layers[1].mode = BlendMode::Multiply;
    d.layers[1].opacity = 255;
    expectRgb(at(composeOk(d), 5, 5), {0, 0, 0, 1});
    d.layers[1].visible = false;
    expectRgb(at(composeOk(d), 5, 5), {1, 0, 0, 1});
}

TEST(PsdCompose, FillHidesContentButNotEffects) {
    auto d = doc();
    d.layers.push_back(pixelLayer("bg", solid({1, 1, 1, 1})));
    auto square = pixelLayer("square", fill([](std::uint32_t x, std::uint32_t y) { return (x >= 20 && x < 44 && y >= 20 && y < 44) ? Rgbaf{0, 0, 1, 1} : Rgbaf{}; }));
    square.fill = 0;
    // A red outside stroke via lfx2 is exercised in psd_layer_data; here: fill 0 hides the content.
    d.layers.push_back(std::move(square));
    expectRgb(at(composeOk(d), 30, 30), {1, 1, 1, 1});
    d.layers[1].fill = 255;
    expectRgb(at(composeOk(d), 30, 30), {0, 0, 1, 1});
}

TEST(PsdCompose, MasksAndClipping) {
    auto d = doc();
    d.layers.push_back(pixelLayer("bg", solid({1, 1, 1, 1})));
    auto base = pixelLayer("base", fill([](std::uint32_t x, std::uint32_t) { return x < 32 ? Rgbaf{0, 0, 0, 1} : Rgbaf{}; }));
    d.layers.push_back(std::move(base));
    auto clipped = pixelLayer("green", solid({0, 1, 0, 1}));
    clipped.clipped = true;
    d.layers.push_back(std::move(clipped));
    const auto out = composeOk(d);
    {
        SCOPED_TRACE("inside the base");
        expectRgb(at(out, 10, 10), {0, 1, 0, 1});
    }
    {
        SCOPED_TRACE("clipped away outside the base");
        expectRgb(at(out, 50, 10), {1, 1, 1, 1});
    }
    // A mask hiding the top half of the base also clips its clipped layer.
    TileStore mask(PixelFormat::A16, kW, kH);
    croptop::TileBuffer t(mask.tile_byte_size(), 0);
    for (std::uint32_t y = 32; y < kH; ++y)
        for (std::uint32_t x = 0; x < kW; ++x) {
            const std::uint16_t v = 65535;
            std::memcpy(t.data() + (static_cast<std::size_t>(y) * 256 + x) * 2, &v, 2);
        }
    ASSERT_TRUE(mask.writeTile({0, 0}, t));
    d.layers[1].mask.emplace(ps::Mask{std::move(mask), 0, false, true});
    const auto masked = composeOk(d);
    {
        SCOPED_TRACE("masked base clips its clipped layer too");
        expectRgb(at(masked, 10, 10), {1, 1, 1, 1});
    }
    expectRgb(at(masked, 10, 50), {0, 1, 0, 1});
}

TEST(PsdCompose, PassThroughAndIsolatedGroupsDiffer) {
    // Background grey 0.5; inside the group: a white layer over half, then a multiply black layer over all.
    const auto build = [](bool pass_through) {
        auto d = doc();
        d.layers.push_back(pixelLayer("bg", solid({0.5F, 0.5F, 0.5F, 1})));
        ps::Layer divider;
        divider.kind = ps::LayerKind::GroupDivider;
        d.layers.push_back(divider);
        d.layers.push_back(pixelLayer("white", fill([](std::uint32_t x, std::uint32_t) { return x < 32 ? Rgbaf{1, 1, 1, 1} : Rgbaf{}; })));
        d.layers.push_back(pixelLayer("black multiply", solid({0.5F, 0.5F, 0.5F, 1}), BlendMode::Multiply));
        ps::Layer folder;
        folder.kind = ps::LayerKind::GroupFolder;
        folder.pass_through = pass_through;
        folder.mode = BlendMode::Normal;
        d.layers.push_back(folder);
        return d;
    };
    const auto through = composeOk(build(true)), isolated = composeOk(build(false));
    // Pass-through: the multiply also darkens the background on the right (0.5 × 0.5).
    EXPECT_NEAR(at(through, 50, 10)[0], 0.25F, 0.01F);
    // Isolated: on the right the group is only the multiply layer over transparency (itself, 0.5), drawn normally.
    EXPECT_NEAR(at(isolated, 50, 10)[0], 0.5F, 0.01F);
    EXPECT_NEAR(at(through, 10, 10)[0], 0.5F, 0.01F);
    EXPECT_NEAR(at(isolated, 10, 10)[0], 0.5F, 0.01F);
}

TEST(PsdCompose, AdjustmentLayersAffectWhatIsBeneathInTheirGroup) {
    auto d = doc();
    d.layers.push_back(pixelLayer("bg", solid({0.2F, 0.2F, 0.2F, 1})));
    ps::Layer invert;
    invert.name = "invert";
    invert.extra.push_back({"nvrt", {}});
    invert.opacity = 128;
    d.layers.push_back(invert);
    EXPECT_NEAR(at(composeOk(d), 5, 5)[0], 0.5F, 0.01F) << "half-strength invert of 0.2";
    // Inside an isolated group, the adjustment only sees the group's own content (transparent here).
    auto grouped = doc();
    grouped.layers.push_back(pixelLayer("bg", solid({0.2F, 0.2F, 0.2F, 1})));
    ps::Layer divider;
    divider.kind = ps::LayerKind::GroupDivider;
    grouped.layers.push_back(divider);
    ps::Layer inv;
    inv.extra.push_back({"nvrt", {}});
    grouped.layers.push_back(inv);
    ps::Layer folder;
    folder.kind = ps::LayerKind::GroupFolder;
    folder.pass_through = false;
    grouped.layers.push_back(folder);
    EXPECT_NEAR(at(composeOk(grouped), 5, 5)[0], 0.2F, 0.01F);
}

TEST(PsdCompose, MatchesAfterAPsdRoundTrip) {
    auto d = doc();
    d.layers.push_back(pixelLayer("bg", fill([](std::uint32_t x, std::uint32_t y) { return Rgbaf{x / 63.0F, y / 63.0F, 0.3F, 1}; })));
    d.layers.push_back(pixelLayer("screen", fill([](std::uint32_t x, std::uint32_t) { return x > 20 ? Rgbaf{0.2F, 0.4F, 0.9F, 0.7F} : Rgbaf{}; }), BlendMode::Screen, 200));
    const auto direct = composeOk(d);
    d.composite.emplace(direct);
    std::string error;
    const auto bytes = ps::write(d, error);
    ASSERT_TRUE(bytes) << error;
    const auto back = ps::read(*bytes, error);
    ASSERT_TRUE(back) << error;
    const auto again = composeOk(*back);
    for (std::uint32_t y = 0; y < kH; y += 7)
        for (std::uint32_t x = 0; x < kW; x += 5) expectRgb(at(again, x, y), at(direct, x, y), 1.5F / 255);
}

}  // namespace
