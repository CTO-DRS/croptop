#include "develop_masks.h"

#include <algorithm>
#include <cmath>
#include <cstring>
#include <functional>

#include "parallel_util.h"
#include "pixel_plane.h"

namespace croptop::develop {
namespace {

double smoothstep(double t) {
    t = std::clamp(t, 0.0, 1.0);
    return t * t * (3.0 - 2.0 * t);
}

/** Fills the pixel ROI from value(x + 0.5, y + 0.5); tiles outside it stay sparse. */
void generateInto(TileStore& out, const std::function<double(double, double)>& value,
                  std::uint32_t x0 = 0, std::uint32_t y0 = 0, std::uint32_t x1 = 0, std::uint32_t y1 = 0) {
    const auto width = out.width(), height = out.height();
    if (x1 == 0) x1 = width;
    if (y1 == 0) y1 = height;
    if (!width || !height || x0 >= x1 || y0 >= y1) return;
    const auto columns = (width + kTileEdge - 1) / kTileEdge, rows = (height + kTileEdge - 1) / kTileEdge;
    const auto tx0 = x0 / kTileEdge, ty0 = y0 / kTileEdge;
    const auto tx1 = std::min(columns, (x1 - 1) / kTileEdge + 1), ty1 = std::min(rows, (y1 - 1) / kTileEdge + 1);
    const auto roiColumns = tx1 - tx0, roiRows = ty1 - ty0;
    parallel::parallelFor(static_cast<std::size_t>(roiColumns) * roiRows, parallel::workerCount(0), [&](std::size_t item, std::size_t) {
        const auto tx = tx0 + static_cast<std::uint32_t>(item % roiColumns), ty = ty0 + static_cast<std::uint32_t>(item / roiColumns);
        TileBuffer tile(out.tile_byte_size(), 0);
        bool any = false;
        for (std::uint32_t ly = 0; ly < kTileEdge && ty * kTileEdge + ly < height; ++ly)
            for (std::uint32_t lx = 0; lx < kTileEdge && tx * kTileEdge + lx < width; ++lx) {
                const auto px = tx * kTileEdge + lx, py = ty * kTileEdge + ly;
                if (px < x0 || py < y0 || px >= x1 || py >= y1) continue;
                const double v = std::clamp(value(px + 0.5, py + 0.5), 0.0, 1.0);
                if (v <= 0.0) continue;
                const auto q = static_cast<std::uint16_t>(v * 65535.0 + 0.5);
                std::memcpy(tile.data() + (static_cast<std::size_t>(ly) * kTileEdge + lx) * 2U, &q, 2U);
                any = true;
            }
        if (any) (void)out.writeTile({static_cast<std::int32_t>(tx), static_cast<std::int32_t>(ty)}, tile);
    });
}

TileStore generate(std::uint32_t width, std::uint32_t height, const std::function<double(double, double)>& value,
                   std::uint32_t x0 = 0, std::uint32_t y0 = 0, std::uint32_t x1 = 0, std::uint32_t y1 = 0) {
    TileStore out(PixelFormat::A16, width, height);
    generateInto(out, value, x0, y0, x1, y1);
    return out;
}

}  // namespace

TileStore linearGradient(std::uint32_t width, std::uint32_t height, Point start, Point end) {
    const double dx = end.x - start.x, dy = end.y - start.y, l2 = dx * dx + dy * dy;
    return generate(width, height, [&](double x, double y) {
        if (!(l2 > 1e-12) || !std::isfinite(l2)) return 0.0;
        const double t = ((x - start.x) * dx + (y - start.y) * dy) / l2;
        return 1.0 - smoothstep(t);
    });
}

TileStore radial(std::uint32_t width, std::uint32_t height, const RadialSpec& s) {
    TileStore out(PixelFormat::A16, width, height);
    std::string error;
    (void)radial(width, height, s, out, error);
    return out;
}

bool radial(std::uint32_t width, std::uint32_t height, const RadialSpec& s, TileStore& out, std::string& error) {
    if (!width || !height) {
        error = "radial mask dimensions are invalid";
        return false;
    }
    if (!std::isfinite(s.centre.x) || !std::isfinite(s.centre.y) || !std::isfinite(s.radius_x) || !std::isfinite(s.radius_y) ||
        !std::isfinite(s.rotation) || !std::isfinite(s.feather) || s.radius_x <= 0.0 || s.radius_y <= 0.0 || s.feather < 0.0 || s.feather > 1.0) {
        error = "radial mask needs finite centre/rotation, positive radii, and feather in [0, 1]";
        return false;
    }
    if (out.format() != PixelFormat::A16 || out.width() != width || out.height() != height) {
        error = "radial output must be an A16 mask of the requested size";
        return false;
    }
    error.clear();
    out.clear();
    const double rx = s.radius_x, ry = s.radius_y;
    const double a = s.rotation * 3.14159265358979323846 / 180.0, c = std::cos(a), sn = std::sin(a);
    if (!std::isfinite(a) || !std::isfinite(c) || !std::isfinite(sn)) { out.clear(); error = "radial rotation is out of range"; return false; }
    const double inner = 1.0 - s.feather;
    auto value = [&](double x, double y) {
        const double px = x - s.centre.x, py = y - s.centre.y;
        const double u = (c * px + sn * py) / rx, v = (-sn * px + c * py) / ry;
        const double r = std::sqrt(u * u + v * v);
        const double inside = r <= inner ? 1.0 : 1.0 - smoothstep((r - inner) / std::max(1e-6, 1.0 - inner));
        return s.invert ? 1.0 - inside : inside;
    };
    if (s.invert) {
        generateInto(out, value);
        return true;
    }
    const double ex = std::hypot(rx * c, ry * sn), ey = std::hypot(rx * sn, ry * c);
    // Clip in floating point before conversion; huge finite, off-canvas ellipses remain safe.
    const double left = std::clamp(s.centre.x - ex - 1.0, 0.0, static_cast<double>(width));
    const double top = std::clamp(s.centre.y - ey - 1.0, 0.0, static_cast<double>(height));
    const double right = std::clamp(s.centre.x + ex + 1.0, 0.0, static_cast<double>(width));
    const double bottom = std::clamp(s.centre.y + ey + 1.0, 0.0, static_cast<double>(height));
    const auto x0 = static_cast<std::uint32_t>(std::floor(left)), y0 = static_cast<std::uint32_t>(std::floor(top));
    const auto x1 = static_cast<std::uint32_t>(std::ceil(right)), y1 = static_cast<std::uint32_t>(std::ceil(bottom));
    generateInto(out, value, x0, y0, x1, y1);
    return true;
}

bool luminanceRange(const TileStore& image, float low, float high, float smoothness, TileStore& out, std::string& error) {
    error.clear();
    if (!paint::detail::isRgba(image.format())) {
        error = "range masks need an RGBA image";
        return false;
    }
    if (!std::isfinite(low) || !std::isfinite(high) || !std::isfinite(smoothness) || low > high || smoothness < 0.0F) {
        error = "range must satisfy low ≤ high with smoothness ≥ 0";
        return false;
    }
    if (out.format() != PixelFormat::A16 || out.width() != image.width() || out.height() != image.height()) {
        error = "the output must be an A16 mask of the image's size";
        return false;
    }
    out.clear();
    const auto bpp = image.bytes_per_pixel();
    for (const auto c : image.tileCoordinates()) {
        const auto src = image.readTile(c);
        if (!src) { out.clear(); error = "could not read image tile"; return false; }
        TileBuffer tile(out.tile_byte_size(), 0);
        bool any = false;
        for (std::size_t i = 0; i < kTileEdge * kTileEdge; ++i) {
            std::array<float, 4> px{};
            paint::detail::readPixel(image.format(), src->data() + i * bpp, px.data());
            if (!std::isfinite(px[0]) || !std::isfinite(px[1]) || !std::isfinite(px[2]) || !std::isfinite(px[3])) {
                out.clear(); error = "range mask image contains non-finite pixels"; return false;
            }
            const double l = 0.2126 * px[0] + 0.7152 * px[1] + 0.0722 * px[2];
            double v = 1.0;
            if (l < low) v = smoothness > 0 ? 1.0 - smoothstep((low - l) / smoothness) : 0.0;
            if (l > high) v = smoothness > 0 ? 1.0 - smoothstep((l - high) / smoothness) : 0.0;
            v *= std::clamp(px[3], 0.0F, 1.0F);
            if (v <= 0.0) continue;
            const auto q = static_cast<std::uint16_t>(v * 65535.0 + 0.5);
            std::memcpy(tile.data() + i * 2U, &q, 2U);
            any = true;
        }
        if (any && !out.writeTile(c, tile)) {
            out.clear(); error = "could not store the mask";
            return false;
        }
    }
    return true;
}

bool brushDab(TileStore& mask, const Dab& d, std::string& error) {
    error.clear();
    if (mask.format() != PixelFormat::A16) {
        error = "brush dabs paint into an A16 mask";
        return false;
    }
    if (!std::isfinite(d.centre.x) || !std::isfinite(d.centre.y) || !std::isfinite(d.size) || d.size <= 0 || d.size > 10000 || !std::isfinite(d.feather) ||
        !std::isfinite(d.flow)) {
        error = "dab needs a finite centre and a size of 0–10000 px";
        return false;
    }
    const double r = d.size / 2, hard = r * (1.0 - std::clamp(d.feather, 0.0, 1.0)), flow = std::clamp(d.flow, 0.0, 1.0);
    if (!mask.width() || !mask.height()) return true;
    const double left = d.centre.x - r, top = d.centre.y - r, right = d.centre.x + r, bottom = d.centre.y + r;
    if (right <= 0.0 || bottom <= 0.0 || left >= static_cast<double>(mask.width()) || top >= static_cast<double>(mask.height())) return true;
    const auto x0 = static_cast<std::int64_t>(std::floor(std::clamp(left, 0.0, static_cast<double>(mask.width()))));
    const auto y0 = static_cast<std::int64_t>(std::floor(std::clamp(top, 0.0, static_cast<double>(mask.height()))));
    const auto x1 = static_cast<std::int64_t>(std::ceil(std::clamp(right, 0.0, static_cast<double>(mask.width()))));
    const auto y1 = static_cast<std::int64_t>(std::ceil(std::clamp(bottom, 0.0, static_cast<double>(mask.height()))));
    for (std::int64_t ty = y0 / static_cast<int>(kTileEdge); ty * static_cast<std::int64_t>(kTileEdge) < y1; ++ty)
        for (std::int64_t tx = x0 / static_cast<int>(kTileEdge); tx * static_cast<std::int64_t>(kTileEdge) < x1; ++tx) {
            const auto tile_x = static_cast<std::int32_t>(tx), tile_y = static_cast<std::int32_t>(ty);
            const auto existing = mask.readTile({tile_x, tile_y});
            TileBuffer tile = existing ? *existing : TileBuffer(mask.tile_byte_size(), 0);
            bool changed = false;
            for (std::int64_t y = std::max(y0, ty * static_cast<std::int64_t>(kTileEdge)); y < std::min(y1, (ty + 1) * static_cast<std::int64_t>(kTileEdge)); ++y)
                for (std::int64_t x = std::max(x0, tx * static_cast<std::int64_t>(kTileEdge)); x < std::min(x1, (tx + 1) * static_cast<std::int64_t>(kTileEdge)); ++x) {
                    const double dist = std::hypot(x + 0.5 - d.centre.x, y + 0.5 - d.centre.y);
                    if (dist >= r) continue;
                    const double f = dist <= hard ? 1.0 : 1.0 - smoothstep((dist - hard) / std::max(1e-6, r - hard));
                    auto* p = tile.data() + (static_cast<std::size_t>(y - ty * static_cast<std::int64_t>(kTileEdge)) * kTileEdge + static_cast<std::size_t>(x - tx * static_cast<std::int64_t>(kTileEdge))) * 2U;
                    std::uint16_t raw = 0;
                    std::memcpy(&raw, p, 2U);
                    double m = raw / 65535.0;
                    m = d.erase ? m * (1.0 - flow * f) : m + flow * f * (1.0 - m);
                    const auto q = static_cast<std::uint16_t>(std::clamp(m, 0.0, 1.0) * 65535.0 + 0.5);
                    std::memcpy(p, &q, 2U);
                    changed = true;
                }
            if (changed && !mask.writeTile({tile_x, tile_y}, tile)) {
                error = "could not store the mask";
                return false;
            }
        }
    return true;
}

}  // namespace croptop::develop
