#include "heal.h"

#include <algorithm>
#include <array>
#include <cmath>
#include <cstring>
#include <exception>
#include <vector>

#include "content_aware_fill.h"
#include "parallel_util.h"
#include "pixel_plane.h"
#include "selection_internal.h"
#include "selection_mask.h"
#include "selection_modify.h"
#include "selection_transform.h"

namespace croptop::paint {
namespace {

using namespace detail;

/**
 * Membrane interpolation: fills `values` at unknown cells with the harmonic function matching the known
 * cells, coarse to fine (a small cascadic multigrid), then red-black SOR sweeps at this resolution.
 */
void membrane(int w, int h, const std::vector<std::uint8_t>& unknown, std::vector<float>& values, unsigned workers) {
    if (w > 48 && h > 48) {
        const int cw = (w + 1) / 2, ch = (h + 1) / 2;
        std::vector<std::uint8_t> coarse_unknown(static_cast<std::size_t>(cw) * ch, 1);
        std::vector<float> coarse(static_cast<std::size_t>(cw) * ch * 4U, 0.0F);
        for (int y = 0; y < ch; ++y) {
            for (int x = 0; x < cw; ++x) {
                std::array<float, 4> sum{};
                int known = 0;
                for (int dy = 0; dy < 2; ++dy) {
                    for (int dx = 0; dx < 2; ++dx) {
                        const int fx = std::min(w - 1, 2 * x + dx), fy = std::min(h - 1, 2 * y + dy);
                        const auto i = static_cast<std::size_t>(fy) * w + fx;
                        if (unknown[i]) continue;
                        for (int c = 0; c < 4; ++c) sum[static_cast<std::size_t>(c)] += values[i * 4U + static_cast<std::size_t>(c)];
                        ++known;
                    }
                }
                const auto ci = static_cast<std::size_t>(y) * cw + x;
                if (known > 0) {
                    coarse_unknown[ci] = 0;
                    for (int c = 0; c < 4; ++c) coarse[ci * 4U + static_cast<std::size_t>(c)] = sum[static_cast<std::size_t>(c)] / known;
                }
            }
        }
        membrane(cw, ch, coarse_unknown, coarse, workers);
        for (int y = 0; y < h; ++y) {
            for (int x = 0; x < w; ++x) {
                const auto i = static_cast<std::size_t>(y) * w + x;
                if (!unknown[i]) continue;
                const auto ci = static_cast<std::size_t>(std::min(ch - 1, y / 2)) * cw + std::min(cw - 1, x / 2);
                std::copy_n(coarse.data() + ci * 4U, 4, values.data() + i * 4U);
            }
        }
    }
    const int sweeps = (w > 48 && h > 48) ? 30 : 400;
    // Brush dabs are small: spawning workers for every sweep would cost more than the sweep itself.
    if (static_cast<std::int64_t>(w) * h < 512 * 512) workers = 1;
    constexpr float kOmega = 1.85F;
    for (int s = 0; s < sweeps; ++s) {
        for (int colour = 0; colour < 2; ++colour) {
            parallel::parallelFor(static_cast<std::size_t>(h), workers, [&](std::size_t row, std::size_t) {
                const int y = static_cast<int>(row);
                for (int x = (y + colour) % 2; x < w; x += 2) {
                    const auto i = static_cast<std::size_t>(y) * w + x;
                    if (!unknown[i]) continue;
                    std::array<float, 4> sum{};
                    int count = 0;
                    const std::array<std::pair<int, int>, 4> offsets{{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}};
                    for (const auto& [dx, dy] : offsets) {
                        const int qx = x + dx, qy = y + dy;
                        if (qx < 0 || qy < 0 || qx >= w || qy >= h) continue;
                        const auto q = static_cast<std::size_t>(qy) * w + qx;
                        for (int c = 0; c < 4; ++c) sum[static_cast<std::size_t>(c)] += values[q * 4U + static_cast<std::size_t>(c)];
                        ++count;
                    }
                    if (count == 0) continue;
                    for (int c = 0; c < 4; ++c) {
                        auto& v = values[i * 4U + static_cast<std::size_t>(c)];
                        v += kOmega * (sum[static_cast<std::size_t>(c)] / count - v);
                    }
                }
            });
        }
    }
}

/** Content-aware fill of `hole` written back into `dst` (only tiles the hole touches). */
bool fillInto(TileStore& dst, const TileStore& hole, const TileStore* sampling, const ContentAwareFillSpec& spec,
              std::string& error) {
    auto filled = contentAwareFill(dst, hole, sampling, spec, error);
    if (!filled) return false;
    for (const auto coordinate : hole.tileCoordinates()) {
        const auto tile = filled->readTile(coordinate);
        if (tile != nullptr && !dst.writeTile(coordinate, *tile)) {
            error = "could not store healed pixels";
            return false;
        }
    }
    return true;
}

}  // namespace

bool cloneStamp(TileStore& dst, const TileStore& src, double offset_x, double offset_y, const TileStore& mask, std::string& error) {
    if (!validate(dst, mask, error)) return false;
    if (src.format() != dst.format() || !std::isfinite(offset_x) || !std::isfinite(offset_y)) {
        error = "clone source must match the layer format and the offset must be finite";
        return false;
    }
    const auto bounds = selection::bounds(mask);
    if (!bounds) return true;
    try {
        return blendInto(dst, mask, sourcePlane(src, *bounds, 0, offset_x, offset_y), error);
    } catch (const std::exception&) {
        error = "could not allocate clone buffers";
        return false;
    }
}

bool healingBlend(TileStore& dst, const TileStore& src, double offset_x, double offset_y, const TileStore& mask,
                  std::string& error, unsigned worker_threads) {
    if (!validate(dst, mask, error)) return false;
    if (src.format() != dst.format() || !std::isfinite(offset_x) || !std::isfinite(offset_y)) {
        error = "healing source must match the layer format and the offset must be finite";
        return false;
    }
    const auto bounds = selection::bounds(mask);
    if (!bounds) return true;
    try {
        // One pixel of known ring around the mask supplies the boundary tone.
        auto source = sourcePlane(src, *bounds, 1, offset_x, offset_y);
        const auto target = readPlane(dst, source.x0, source.y0, source.w, source.h);
        const auto workers = parallel::workerCount(worker_threads);
        const auto coverage = readCoverage(mask, source.x0, source.y0, source.w, source.h);
        std::vector<std::uint8_t> unknown(static_cast<std::size_t>(source.w) * source.h, 0);
        std::vector<float> correction(unknown.size() * 4U, 0.0F);
        for (int y = 0; y < source.h; ++y) {
            for (int x = 0; x < source.w; ++x) {
                const auto i = static_cast<std::size_t>(y) * source.w + x;
                const bool inside = coverage[i] > 0U;
                unknown[i] = inside ? 1U : 0U;
                if (!inside) {
                    for (int c = 0; c < 4; ++c) correction[i * 4U + static_cast<std::size_t>(c)] = target.at(x, y)[c] - source.at(x, y)[c];
                }
            }
        }
        membrane(source.w, source.h, unknown, correction, workers);
        for (std::size_t i = 0; i < unknown.size(); ++i) {
            for (int c = 0; c < 4; ++c) source.px[i * 4U + static_cast<std::size_t>(c)] += correction[i * 4U + static_cast<std::size_t>(c)];
        }
        return blendInto(dst, mask, source, error);
    } catch (const std::exception&) {
        error = "could not allocate healing buffers";
        return false;
    }
}

bool spotHeal(TileStore& dst, const TileStore& mask, std::string& error, SpotHealType type) {
    if (!validate(dst, mask, error)) return false;
    const auto bounds = selection::bounds(mask);
    if (!bounds) return true;
    // Grow the spot slightly so its anti-aliased rim (partly blemish) is not used as a source.
    auto hole = selection::expand(mask, 2.0F, error);
    if (!hole) return false;
    const auto hb = *selection::bounds(*hole);
    const int size = std::max(hb.right - hb.left, hb.bottom - hb.top);
    if (type == SpotHealType::ContentAware) {
        ContentAwareFillSpec spec;
        spec.margin = static_cast<std::uint32_t>(std::max(16, size));
        spec.iterations = 4;
        return fillInto(dst, *hole, nullptr, spec, error);
    }
    try {
        const TileStore snapshot(dst);
        if (type == SpotHealType::ProximityMatch) {
            // Score candidate offsets by how well the ring around the spot matches the ring around the
            // candidate; the heal then takes the candidate's texture and the spot's tone.
            const int ring = std::max(3, size / 4);
            const int reach = 2 * (size + ring);  // covers the largest candidate offset, 1.5 × (size + ring)
            const auto area = readPlane(dst, hb.left - ring - reach, hb.top - ring - reach, hb.right - hb.left + 2 * (ring + reach),
                                        hb.bottom - hb.top + 2 * (ring + reach));
            const auto inside = readCoverage(*hole, area.x0, area.y0, area.w, area.h);
            std::vector<std::pair<int, int>> band;
            for (int y = hb.top - ring; y < hb.bottom + ring; ++y)
                for (int x = hb.left - ring; x < hb.right + ring; ++x)
                    if (inside[static_cast<std::size_t>(y - area.y0) * area.w + (x - area.x0)] == 0U) band.emplace_back(x, y);
            double best = 1e300;
            int best_x = 0, best_y = 0;
            constexpr double kPi = 3.14159265358979323846;
            for (const double scale : {1.0, 1.5}) {
                for (int k = 0; k < 16; ++k) {
                    const double angle = k * kPi / 8.0;
                    const int ox = static_cast<int>(std::lround(std::cos(angle) * scale * (size + ring)));
                    const int oy = static_cast<int>(std::lround(std::sin(angle) * scale * (size + ring)));
                    // The candidate (spot footprint plus ring) must stay on the canvas.
                    if (hb.left - ring + ox < 0 || hb.top - ring + oy < 0 || hb.right + ring + ox > static_cast<int>(dst.width()) ||
                        hb.bottom + ring + oy > static_cast<int>(dst.height())) {
                        continue;
                    }
                    double ssd = 0.0;
                    for (const auto& [x, y] : band) {
                        const float* a = area.at(x - area.x0, y - area.y0);
                        const float* b = area.at(x + ox - area.x0, y + oy - area.y0);
                        for (int c = 0; c < 4; ++c) ssd += (a[c] - b[c]) * (a[c] - b[c]);
                    }
                    if (ssd < best) {
                        best = ssd;
                        best_x = ox;
                        best_y = oy;
                    }
                }
            }
            if (best == 1e300) {
                // Spot as large as the canvas: nothing nearby fits, so fill instead.
                ContentAwareFillSpec spec;
                spec.iterations = 4;
                return fillInto(dst, *hole, nullptr, spec, error);
            }
            return healingBlend(dst, snapshot, best_x, best_y, *hole, error);
        }
        // CreateTexture: tile random 8 px patches of the surroundings over the spot, then heal from them.
        constexpr int kPatch = 8;
        const int reach = std::max(2 * kPatch, size) + 1;
        const auto area = readPlane(dst, hb.left - reach, hb.top - reach, hb.right - hb.left + 2 * reach, hb.bottom - hb.top + 2 * reach);
        const auto inside = readCoverage(*hole, area.x0, area.y0, area.w, area.h);
        const auto usable = [&](int x, int y) {  // top-left of a patch, area coordinates
            if (x < 0 || y < 0 || x + kPatch > area.w || y + kPatch > area.h) return false;
            if (area.x0 + x < 0 || area.y0 + y < 0 || area.x0 + x + kPatch > static_cast<int>(dst.width()) ||
                area.y0 + y + kPatch > static_cast<int>(dst.height())) {
                return false;
            }
            for (int dy = 0; dy < kPatch; ++dy)
                for (int dx = 0; dx < kPatch; ++dx)
                    if (inside[static_cast<std::size_t>(y + dy) * area.w + (x + dx)] != 0U) return false;
            return true;
        };
        std::vector<std::pair<int, int>> sources;
        for (int y = 0; y + kPatch <= area.h; y += 2)
            for (int x = 0; x + kPatch <= area.w; x += 2)
                if (usable(x, y)) sources.emplace_back(x, y);
        if (sources.empty()) {
            ContentAwareFillSpec spec;
            spec.iterations = 4;
            return fillInto(dst, *hole, nullptr, spec, error);
        }
        // The texture also covers the heal's 1 px boundary ring, so the heal matches its tone to the spot's
        // surroundings (with the original ring as source, the correction would be zero).
        Plane texture{hb.left - 1, hb.top - 1, hb.right - hb.left + 2, hb.bottom - hb.top + 2, {}};
        texture.px.resize(static_cast<std::size_t>(texture.w) * texture.h * 4U);
        std::uint32_t state = 0x9E3779B9U ^ (static_cast<std::uint32_t>(hb.left) * 73856093U) ^ (static_cast<std::uint32_t>(hb.top) * 19349663U);
        for (int by = 0; by < texture.h; by += kPatch) {
            for (int bx = 0; bx < texture.w; bx += kPatch) {
                state = state * 1664525U + 1013904223U;
                const auto& [sx, sy] = sources[(state >> 8) % sources.size()];
                for (int dy = 0; dy < kPatch && by + dy < texture.h; ++dy)
                    for (int dx = 0; dx < kPatch && bx + dx < texture.w; ++dx)
                        std::copy_n(area.at(sx + dx, sy + dy), 4, texture.at(bx + dx, by + dy));
            }
        }
        TileStore source(snapshot);
        if (!writePlane(source, texture)) {
            error = "could not store the healing texture";
            return false;
        }
        return healingBlend(dst, source, 0, 0, *hole, error);
    } catch (const std::exception&) {
        error = "could not allocate spot healing buffers";
        return false;
    }
}

bool patch(TileStore& dst, const TileStore& region, double offset_x, double offset_y, PatchMode mode, std::string& error) {
    if (!validate(dst, region, error)) return false;
    if (!std::isfinite(offset_x) || !std::isfinite(offset_y)) {
        error = "patch offset must be finite";
        return false;
    }
    if (mode == PatchMode::Normal) {
        const TileStore source(dst);  // Copy-on-write snapshot: the source area is read before healing.
        return healingBlend(dst, source, offset_x, offset_y, region, error);
    }
    // Content-aware: sample only the patch's source area (the working margin must reach it).
    auto sampling = selection::transform(region, {1, 0, offset_x, 0, 1, offset_y, 0, 0, 1}, error);
    if (!sampling) return false;
    ContentAwareFillSpec spec;
    spec.margin = static_cast<std::uint32_t>(std::min(1.0e6, std::ceil(std::max(std::abs(offset_x), std::abs(offset_y)))) + 16);
    return fillInto(dst, region, &*sampling, spec, error);
}

}  // namespace croptop::paint
