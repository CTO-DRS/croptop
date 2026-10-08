#include "gradient_fill.h"

#include <algorithm>
#include <array>
#include <cmath>
#include <cstring>
#include <mutex>

#include "parallel_util.h"
#include "selection_color.h"
#include "selection_mask.h"

namespace croptop::paint {
namespace {

double srgbToLinear(double v) { return v <= 0.04045 ? v / 12.92 : std::pow((v + 0.055) / 1.055, 2.4); }
double linearToSrgb(double v) { return v <= 0.0031308 ? v * 12.92 : 1.055 * std::pow(v, 1.0 / 2.4) - 0.055; }

bool isRgba(PixelFormat f) { return f == PixelFormat::RGBA8 || f == PixelFormat::RGBA16 || f == PixelFormat::RGBA32F; }

/** Deterministic triangular noise in (−1, 1) for pixel (x, y) and channel c. */
double triangularNoise(std::uint32_t x, std::uint32_t y, std::uint32_t c) {
    auto hash = [](std::uint64_t v) {
        v ^= v >> 33U;
        v *= 0xff51afd7ed558ccdULL;
        v ^= v >> 33U;
        v *= 0xc4ceb9fe1a85ec53ULL;
        v ^= v >> 33U;
        return v;
    };
    const auto h = hash((static_cast<std::uint64_t>(x) << 32U) ^ (static_cast<std::uint64_t>(y) << 3U) ^ c);
    const double u1 = static_cast<double>(h & 0xffffffffULL) / 4294967296.0;
    const double u2 = static_cast<double>(h >> 32U) / 4294967296.0;
    return u1 - u2;
}

/** Straight-alpha pixel in the store's own encoding (encoded for RGBA8/16, linear for RGBA32F). */
std::array<double, 4> readPixel(PixelFormat format, const std::uint8_t* p) {
    std::array<double, 4> v{};
    for (std::size_t c = 0; c < 4; ++c) {
        if (format == PixelFormat::RGBA8) {
            v[c] = p[c] / 255.0;
        } else if (format == PixelFormat::RGBA16) {
            std::uint16_t x = 0;
            std::memcpy(&x, p + c * 2U, 2U);
            v[c] = x / 65535.0;
        } else {
            float x = 0.0F;
            std::memcpy(&x, p + c * 4U, 4U);
            v[c] = std::isfinite(x) ? x : 0.0;
        }
    }
    return v;
}

void writePixel(PixelFormat format, std::uint8_t* p, const std::array<double, 4>& v, bool dither, std::uint32_t x, std::uint32_t y) {
    for (std::size_t c = 0; c < 4; ++c) {
        if (format == PixelFormat::RGBA32F) {
            const auto value = static_cast<float>(c == 3 ? std::clamp(v[c], 0.0, 1.0) : std::max(v[c], 0.0));
            std::memcpy(p + c * 4U, &value, 4U);
            continue;
        }
        const double scale = format == PixelFormat::RGBA8 ? 255.0 : 65535.0;
        // Dither encoded colour channels only. Perturbing alpha makes fully opaque stops
        // translucent on some pixels and creates visible speckles on transparent layers.
        const double noise = dither && c < 3 ? triangularNoise(x, y, static_cast<std::uint32_t>(c)) : 0.0;
        const auto q = static_cast<long>(std::lround(std::clamp(v[c] * scale + noise, 0.0, scale)));
        if (format == PixelFormat::RGBA8) {
            p[c] = static_cast<std::uint8_t>(q);
        } else {
            const auto value = static_cast<std::uint16_t>(q);
            std::memcpy(p + c * 2U, &value, 2U);
        }
    }
}

/** Source-over of straight-alpha colour `src` (alpha `a`) onto straight-alpha `dst`. */
std::array<double, 4> sourceOver(const std::array<double, 4>& dst, const std::array<double, 3>& src, double a) {
    const double out_a = a + dst[3] * (1.0 - a);
    std::array<double, 4> out{0.0, 0.0, 0.0, out_a};
    if (out_a <= 0.0) return out;
    for (std::size_t c = 0; c < 3; ++c) out[c] = (src[c] * a + dst[c] * dst[3] * (1.0 - a)) / out_a;
    return out;
}

/**
 * Calls paint(x, y, pixel, coverage) for every pixel of the listed tiles. Coverage (0–1) comes from the
 * matching tile of `coverage_source` (fetched once per tile), or is 1 without one.
 */
template <typename Paint>
void paintTiles(TileStore& target, const std::vector<TileCoordinate>& tiles, const TileStore* coverage_source,
                unsigned workers, Paint&& paint) {
    const auto bpp = target.bytes_per_pixel();
    parallel::parallelFor(tiles.size(), parallel::workerCount(workers), [&](std::size_t item, std::size_t) {
        const auto coordinate = tiles[item];
        std::shared_ptr<const TileBuffer> coverage_tile;
        if (coverage_source != nullptr) {
            coverage_tile = coverage_source->readTile(coordinate);
            if (coverage_tile == nullptr) return;  // Nothing selected in this tile.
        }
        const auto existing = target.readTile(coordinate);
        TileBuffer tile = existing ? *existing : TileBuffer(target.tile_byte_size(), 0);
        bool changed = false;
        const auto x0 = static_cast<std::uint32_t>(coordinate.x) * kTileEdge;
        const auto y0 = static_cast<std::uint32_t>(coordinate.y) * kTileEdge;
        const auto rows = std::min<std::uint32_t>(kTileEdge, target.height() - y0);
        const auto cols = std::min<std::uint32_t>(kTileEdge, target.width() - x0);
        for (std::uint32_t ly = 0; ly < rows; ++ly) {
            for (std::uint32_t lx = 0; lx < cols; ++lx) {
                auto* p = tile.data() + (static_cast<std::size_t>(ly) * kTileEdge + lx) * bpp;
                double coverage = 1.0;
                if (coverage_tile != nullptr) {
                    std::uint16_t v = 0;
                    std::memcpy(&v, coverage_tile->data() + (static_cast<std::size_t>(ly) * kTileEdge + lx) * 2U, 2U);
                    coverage = v / 65535.0;
                }
                changed = paint(x0 + lx, y0 + ly, p, coverage) || changed;
            }
        }
        if (changed) (void)target.writeTile(coordinate, tile);
    });
}

std::vector<TileCoordinate> allTiles(const TileStore& store, const TileStore* mask) {
    std::vector<TileCoordinate> tiles;
    if (mask != nullptr) {
        tiles = mask->tileCoordinates();  // Unselected tiles are never painted.
        return tiles;
    }
    for (std::uint32_t ty = 0; ty * kTileEdge < store.height(); ++ty) {
        for (std::uint32_t tx = 0; tx * kTileEdge < store.width(); ++tx) {
            tiles.push_back({static_cast<std::int32_t>(tx), static_cast<std::int32_t>(ty)});
        }
    }
    return tiles;
}

bool validMask(const TileStore& target, const TileStore* mask) {
    return mask == nullptr || (mask->format() == PixelFormat::A16 && mask->width() == target.width() && mask->height() == target.height());
}

}  // namespace

bool renderGradient(TileStore& target, const GradientSpec& spec, const TileStore* mask, std::string& error) {
    if (!isRgba(target.format()) || target.width() == 0U || target.height() == 0U || !validMask(target, mask)) {
        error = "gradient needs an RGBA layer and an optional A16 selection of the same size";
        return false;
    }
    const double dx = spec.end.x - spec.start.x;
    const double dy = spec.end.y - spec.start.y;
    const double length2 = dx * dx + dy * dy;
    if (!std::isfinite(length2) || length2 < 1e-12 || spec.colors.empty() || !std::isfinite(spec.opacity) ||
        spec.opacity < 0.0F || spec.opacity > 1.0F) {
        error = "gradient needs distinct finite endpoints, at least one colour stop and opacity 0–1";
        return false;
    }
    auto colors = spec.colors;
    auto opacities = spec.opacities;
    for (const auto& c : colors) {
        if (!std::isfinite(c.position) || !std::isfinite(c.r) || !std::isfinite(c.g) || !std::isfinite(c.b)) {
            error = "gradient stops must be finite";
            return false;
        }
    }
    for (const auto& o : opacities) {
        if (!std::isfinite(o.position) || !std::isfinite(o.opacity)) {
            error = "gradient stops must be finite";
            return false;
        }
    }
    std::stable_sort(colors.begin(), colors.end(), [](const ColorStop& a, const ColorStop& b) { return a.position < b.position; });
    std::stable_sort(opacities.begin(), opacities.end(), [](const OpacityStop& a, const OpacityStop& b) { return a.position < b.position; });
    const double length = std::sqrt(length2);
    const double ux = dx / length;
    const double uy = dy / length;
    const bool linear_light = spec.interpolation == Interpolation::LinearLight;

    const auto parameter = [&](double px, double py) {
        const double rx = px - spec.start.x;
        const double ry = py - spec.start.y;
        double t = 0.0;
        switch (spec.shape) {
            case GradientShape::Linear: t = (rx * dx + ry * dy) / length2; break;
            case GradientShape::Radial: t = std::hypot(rx, ry) / length; break;
            case GradientShape::Reflected: t = std::fabs(rx * dx + ry * dy) / length2; break;
            case GradientShape::Diamond: t = (std::fabs(rx * ux + ry * uy) + std::fabs(-rx * uy + ry * ux)) / length; break;
            case GradientShape::Angle: {
                // Clockwise on screen (y down) from the start→end direction.
                double angle = std::atan2(rx * uy - ry * ux, rx * ux + ry * uy);
                angle = -angle;
                if (angle < 0.0) angle += 2.0 * M_PI;
                t = angle / (2.0 * M_PI);
                break;
            }
        }
        t = std::clamp(t, 0.0, 1.0);
        return spec.reverse ? 1.0 - t : t;
    };
    const auto colorAt = [&](double t) {
        std::array<double, 3> out{colors.front().r, colors.front().g, colors.front().b};
        if (t >= colors.back().position) {
            out = {colors.back().r, colors.back().g, colors.back().b};
        } else if (t > colors.front().position) {
            const auto next = std::upper_bound(colors.begin(), colors.end(), t,
                                               [](double v, const ColorStop& s) { return v < s.position; });
            const auto& b = *next;
            const auto& a = *(next - 1);
            const double span = b.position - a.position;
            const double f = span > 0.0 ? (t - a.position) / span : 1.0;
            const std::array<double, 3> ca{a.r, a.g, a.b};
            const std::array<double, 3> cb{b.r, b.g, b.b};
            for (std::size_t c = 0; c < 3; ++c) {
                out[c] = linear_light ? linearToSrgb(srgbToLinear(ca[c]) + f * (srgbToLinear(cb[c]) - srgbToLinear(ca[c])))
                                      : ca[c] + f * (cb[c] - ca[c]);
            }
        }
        for (auto& v : out) v = std::clamp(v, 0.0, 1.0);
        return out;
    };
    const auto opacityAt = [&](double t) {
        if (opacities.empty()) return 1.0;
        if (t <= opacities.front().position) return static_cast<double>(std::clamp(opacities.front().opacity, 0.0F, 1.0F));
        if (t >= opacities.back().position) return static_cast<double>(std::clamp(opacities.back().opacity, 0.0F, 1.0F));
        const auto next = std::upper_bound(opacities.begin(), opacities.end(), t,
                                           [](double v, const OpacityStop& s) { return v < s.position; });
        const auto& b = *next;
        const auto& a = *(next - 1);
        const double span = b.position - a.position;
        const double f = span > 0.0 ? (t - a.position) / span : 1.0;
        return std::clamp(a.opacity + f * (b.opacity - a.opacity), 0.0, 1.0);
    };
    const auto format = target.format();
    try {
        paintTiles(target, allTiles(target, mask), mask, spec.worker_threads,
                   [&](std::uint32_t x, std::uint32_t y, std::uint8_t* p, double coverage) {
            if (coverage <= 0.0) return false;
            const double t = parameter(x + 0.5, y + 0.5);
            auto color = colorAt(t);
            if (format == PixelFormat::RGBA32F) {
                for (auto& v : color) v = srgbToLinear(v);
            }
            const double alpha = opacityAt(t) * spec.opacity * coverage;
            if (alpha <= 0.0) return false;
            writePixel(format, p, sourceOver(readPixel(format, p), color, alpha), spec.dither, x, y);
            return true;
        });
    } catch (const std::exception&) {
        error = "could not paint the gradient";
        return false;
    }
    return true;
}

bool bucketFill(TileStore& target, const TileStore& sample_source, std::int64_t x, std::int64_t y, Rgba color, int tolerance,
                bool contiguous, const TileStore* mask, float opacity, std::string& error) {
    if (!isRgba(target.format()) || !validMask(target, mask) || sample_source.width() != target.width() ||
        sample_source.height() != target.height()) {
        error = "paint bucket needs an RGBA layer, a matching sample source and an optional A16 selection";
        return false;
    }
    if (!std::isfinite(opacity) || opacity < 0.0F || opacity > 1.0F || !std::isfinite(color.r) || !std::isfinite(color.g) ||
        !std::isfinite(color.b) || !std::isfinite(color.a)) {
        error = "paint bucket colour and opacity must be finite, opacity 0–1";
        return false;
    }
    auto region = selection::magicWand(sample_source, {x, y, tolerance, contiguous}, error);
    if (!region) return false;
    if (mask != nullptr) {
        auto limited = selection::combine(*region, *mask, selection::Op::Intersect);
        if (!limited) {
            error = "paint bucket selection does not match the layer";
            return false;
        }
        region.reset();
        region.emplace(std::move(*limited));
    }
    std::array<double, 3> fill{std::clamp<double>(color.r, 0.0, 1.0), std::clamp<double>(color.g, 0.0, 1.0),
                               std::clamp<double>(color.b, 0.0, 1.0)};
    if (target.format() == PixelFormat::RGBA32F) {
        for (auto& v : fill) v = srgbToLinear(v);
    }
    const double fill_alpha = std::clamp<double>(color.a, 0.0, 1.0) * opacity;
    const auto format = target.format();
    const auto& area = *region;
    try {
        paintTiles(target, area.tileCoordinates(), &area, 0, [&](std::uint32_t px, std::uint32_t py, std::uint8_t* p, double coverage) {
            const double alpha = coverage * fill_alpha;
            if (alpha <= 0.0) return false;
            writePixel(format, p, sourceOver(readPixel(format, p), fill, alpha), false, px, py);
            return true;
        });
    } catch (const std::exception&) {
        error = "could not fill";
        return false;
    }
    return true;
}

}  // namespace croptop::paint
