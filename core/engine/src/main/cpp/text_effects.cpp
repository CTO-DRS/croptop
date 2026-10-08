// Text effects: Warp Text and type on a path.
#include "text_effects.h"

#include <algorithm>
#include <cmath>
#include <cstring>

#include "pixel_plane.h"
#include "truetype_font.h"

namespace croptop::text {
namespace {

constexpr double kPi = 3.14159265358979323846;

/** The style's envelope on normalised coordinates (u right, v up, both in [−1, 1]); k = width / height. */
std::array<double, 2> envelope(WarpStyle style, double u, double v, double b, double k) {
    const auto arc = [&](double uu, double vv) -> std::array<double, 2> {
        if (std::abs(b) < 1e-6) return {uu, vv};
        const double radius = k / (b * kPi / 2.0), angle = uu * b * kPi / 2.0;
        return {(radius + vv) * std::sin(angle) / k, (radius + vv) * std::cos(angle) - radius};
    };
    const auto lerp = [](const std::array<double, 2>& a, const std::array<double, 2>& c, double t) -> std::array<double, 2> {
        return {a[0] + (c[0] - a[0]) * t, a[1] + (c[1] - a[1]) * t};
    };
    const double hump = 1.0 - u * u;
    switch (style) {
        case WarpStyle::None: return {u, v};
        case WarpStyle::Arc: return arc(u, v);
        case WarpStyle::ArcLower: return lerp({u, v}, arc(u, v), (1.0 - v) / 2.0);
        case WarpStyle::ArcUpper: return lerp({u, v}, arc(u, v), (1.0 + v) / 2.0);
        case WarpStyle::Arch: return {u, v + b * 0.6 * hump};
        case WarpStyle::Bulge: return {u, v * (1.0 + b * 0.6 * hump)};
        case WarpStyle::ShellLower: return {u, v - b * 0.6 * hump * (1.0 - v) / 2.0};
        case WarpStyle::ShellUpper: return {u, v + b * 0.6 * hump * (1.0 + v) / 2.0};
        case WarpStyle::Flag: return {u, v + b * std::sin(kPi * u)};
        case WarpStyle::Wave: return {u, v + b * std::sin(kPi * u + v * kPi / 2.0)};
        case WarpStyle::Fish: return {u, v * (1.0 + b * 0.6 * hump * (1.0 - u) / 2.0)};
        case WarpStyle::Rise: return {u, v + b * 0.5 * u};
        case WarpStyle::Fisheye: {
            // Radial lens in true proportions: the longer side spans −1…1 (else wide text bands vertically).
            const double m = std::max(k, 1.0), U = u * k / m, V = v / m;
            const double r2 = U * U + V * V;
            const double scale = r2 < 1.0 ? 1.0 + b * 0.5 * (1.0 - r2) : 1.0;
            return {u * scale, v * scale};
        }
        case WarpStyle::Inflate: return {u * (1.0 + b * 0.3 * (1.0 - v * v)), v * (1.0 + b * 0.3 * hump)};
        case WarpStyle::Squeeze: return {u * (1.0 - b * 0.3 * (1.0 - v * v)), v * (1.0 + b * 0.3 * hump)};
        case WarpStyle::Twist: {
            // Rotation in true proportions (rotating stretched coordinates would shear wide text apart).
            const double m = std::max(k, 1.0), U = u * k / m, V = v / m;
            const double r = std::min(1.0, std::sqrt(U * U + V * V)), angle = b * kPi * (1.0 - r) * 0.5;
            const double c = std::cos(angle), s = std::sin(angle);
            return {(U * c - V * s) * m / k, (U * s + V * c) * m};
        }
    }
    return {u, v};
}

}  // namespace

bool fillPath(TileStore& layer, const vector::Path& path, std::array<float, 4> color, std::string& error) {
    if (!paint::detail::isRgba(layer.format())) {
        error = "text renders into an RGBA layer";
        return false;
    }
    if (std::any_of(color.begin(), color.end(), [](float v) { return !std::isfinite(v) || v < 0.0F || v > 1.0F; })) {
        error = "text colour must be 0–1";
        return false;
    }
    if (path.empty()) return true;
    const auto coverage = vector::rasterize(path, layer.width(), layer.height(), vector::FillRule::NonZero);
    const auto bpp = layer.bytes_per_pixel();
    for (const auto& c : coverage.tileCoordinates()) {
        const auto mask = coverage.readTile(c);
        const auto existing = layer.readTile(c);
        TileBuffer tile = existing ? *existing : TileBuffer(layer.tile_byte_size(), 0);
        for (std::size_t p = 0; p < static_cast<std::size_t>(kTileEdge) * kTileEdge; ++p) {
            std::uint16_t k = 0;
            std::memcpy(&k, mask->data() + p * 2, 2);
            if (k == 0) continue;
            float px[4];
            paint::detail::readPixel(layer.format(), tile.data() + p * bpp, px);
            const float a = color[3] * (k / 65535.0F), out_a = a + px[3] * (1.0F - a);
            float out[4] = {0, 0, 0, out_a};
            if (out_a > 0)
                for (int ch = 0; ch < 3; ++ch) out[ch] = (color[static_cast<std::size_t>(ch)] * a + px[ch] * px[3] * (1.0F - a)) / out_a;
            paint::detail::writePixel(layer.format(), tile.data() + p * bpp, out);
        }
        if (!layer.writeTile(c, tile)) {
            error = "could not store text pixels";
            return false;
        }
    }
    return true;
}

vector::Path layoutOutlines(const Layout& layout, const TrueTypeFont& font, double origin_x, double origin_y) {
    vector::Path all;
    for (const auto& line : layout.lines)
        for (const auto& g : line.glyphs) {
            const double s = g.size / font.unitsPerEm();
            all.append(font.outline(static_cast<std::uint16_t>(g.glyph & 0xFFFFU)).transformed({s, 0, origin_x + g.x, 0, -s, origin_y + line.baseline + g.y_offset, 0, 0, 1}));
        }
    return all;
}

vector::Path warp(const vector::Path& path, const WarpSpec& spec, double tolerance) {
    if (spec.style == WarpStyle::None || path.empty()) return path;
    const double b = std::isfinite(spec.bend) ? std::clamp(static_cast<double>(spec.bend), -1.0, 1.0) : 0.0;
    const double hd = std::isfinite(spec.horizontal_distortion) ? std::clamp(static_cast<double>(spec.horizontal_distortion), -1.0, 1.0) : 0.0;
    const double vd = std::isfinite(spec.vertical_distortion) ? std::clamp(static_cast<double>(spec.vertical_distortion), -1.0, 1.0) : 0.0;
    const auto polylines = vector::flatten(path, std::max(0.01, tolerance));
    double x0 = 1e300, y0 = 1e300, x1 = -1e300, y1 = -1e300;
    for (const auto& poly : polylines)
        for (const auto& p : poly.points) x0 = std::min(x0, p.x), y0 = std::min(y0, p.y), x1 = std::max(x1, p.x), y1 = std::max(y1, p.y);
    if (!(x1 > x0) || !(y1 > y0)) return path;
    const double cx = (x0 + x1) / 2, cy = (y0 + y1) / 2, hw = (x1 - x0) / 2, hh = (y1 - y0) / 2;
    const auto map = [&](vector::Point p) -> vector::Point {
        double u = (p.x - cx) / hw, v = -(p.y - cy) / hh, k = hw / hh;
        if (spec.vertical) {  // warp along the vertical axis: swap roles
            std::swap(u, v);
            k = 1.0 / k;
        }
        auto [wu, wv] = envelope(spec.style, u, v, b, k);
        const double du = wu * (1.0 + vd * wv * 0.5), dv = wv * (1.0 + hd * wu * 0.5);  // vertical/horizontal distortion
        wu = du;
        wv = dv;
        if (spec.vertical) std::swap(wu, wv);
        return {cx + wu * hw, cy - wv * hh};
    };
    // The envelope is non-linear: straight edges must bend along their whole length, so long segments are
    // subdivided (≤ 2 px, at most 256 pieces each) before mapping.
    vector::Path out;
    const auto emit = [&](vector::Point a, vector::Point c) {
        const double len = std::hypot(c.x - a.x, c.y - a.y);
        const int pieces = std::clamp(static_cast<int>(std::ceil(len / 2.0)), 1, 256);
        for (int k = 1; k <= pieces; ++k) {
            const double t = static_cast<double>(k) / pieces;
            out.lineTo(map({a.x + (c.x - a.x) * t, a.y + (c.y - a.y) * t}));
        }
    };
    for (const auto& poly : polylines) {
        if (poly.points.empty()) continue;
        out.moveTo(map(poly.points.front()));
        for (std::size_t i = 1; i < poly.points.size(); ++i) emit(poly.points[i - 1], poly.points[i]);
        if (poly.closed && poly.points.size() > 1) emit(poly.points.back(), poly.points.front());
        if (poly.closed) out.close();
    }
    return out;
}

vector::Path textOnPath(const Layout& layout, const TrueTypeFont& font, const vector::Path& path, double start, double tolerance) {
    vector::Path out;
    if (layout.lines.empty()) return out;
    const auto polylines = vector::flatten(path, std::max(0.01, tolerance));
    if (polylines.empty() || polylines.front().points.size() < 2) return out;
    auto points = polylines.front().points;
    if (polylines.front().closed) points.push_back(points.front());
    std::vector<double> length(points.size(), 0.0);
    for (std::size_t i = 1; i < points.size(); ++i) length[i] = length[i - 1] + std::hypot(points[i].x - points[i - 1].x, points[i].y - points[i - 1].y);
    const double total = length.back();
    if (!(total > 0)) return out;
    const double offset = std::isfinite(start) ? start : 0.0;
    for (const auto& g : layout.lines.front().glyphs) {
        const double s = g.size / font.unitsPerEm();
        const double centre = offset + g.x + g.advance / 2.0;
        if (centre < 0 || centre > total) continue;
        const std::size_t seg = static_cast<std::size_t>(std::upper_bound(length.begin(), length.end(), centre) - length.begin());
        const std::size_t i = std::clamp<std::size_t>(seg, 1, points.size() - 1);
        const double span = length[i] - length[i - 1];
        const double t = span > 0 ? (centre - length[i - 1]) / span : 0.0;
        const double px = points[i - 1].x + (points[i].x - points[i - 1].x) * t, py = points[i - 1].y + (points[i].y - points[i - 1].y) * t;
        const double angle = std::atan2(points[i].y - points[i - 1].y, points[i].x - points[i - 1].x);
        const double c = std::cos(angle), sn = std::sin(angle), tx = -g.advance / 2.0, ty = g.y_offset;
        // T(point) · R(tangent) · [scale, flip y, centre on the advance, baseline offset]
        out.append(font.outline(static_cast<std::uint16_t>(g.glyph & 0xFFFFU))
                       .transformed({c * s, sn * s, c * tx - sn * ty + px, sn * s, -c * s, sn * tx + c * ty + py, 0, 0, 1}));
    }
    return out;
}

}  // namespace croptop::text
