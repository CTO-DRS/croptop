#include "filters_blur.h"

#include <algorithm>
#include <array>
#include <atomic>
#include <cmath>
#include <cstring>
#include <exception>
#include <functional>
#include <optional>
#include <vector>

#include "filters_internal.h"
#include "parallel_util.h"
#include "pixel_plane.h"
#include "selection_internal.h"
#include "selection_mask.h"

namespace croptop::filters {
namespace {

using namespace detail;

/** Produces the filtered pixel from the original (straight) and the filter's output (premultiplied). */
using Combine = std::function<Pixel(const Pixel& original, const Pixel& filtered_premultiplied)>;

constexpr int kBandTiles = 1;   // output band width for the streaming engine, in tiles
constexpr int kMinChunkTiles = 4;  // output rows per streaming task, in tiles (at least 8 × pad rows)

/** An empty Combine means "replace with the filtered value" (the fast path for plain blurs). */
Combine replaceWithFiltered() { return {}; }

/** Horizontal box pass of width w over `count` pixels, in place (source padded by w/2 on each side). */
void boxRow(std::vector<float>& row, int count, int w, std::vector<float>& scratch) {
    const int r = w / 2;
    scratch.resize(static_cast<std::size_t>(count - 2 * r) * 4U);
    std::array<double, 4> sum{};
    for (int i = 0; i < w; ++i)
        for (int c = 0; c < 4; ++c) sum[static_cast<std::size_t>(c)] += row[static_cast<std::size_t>(i) * 4U + static_cast<std::size_t>(c)];
    const double inv = 1.0 / w;
    for (int i = 0; i < count - 2 * r; ++i) {
        for (int c = 0; c < 4; ++c) scratch[static_cast<std::size_t>(i) * 4U + static_cast<std::size_t>(c)] = static_cast<float>(sum[static_cast<std::size_t>(c)] * inv);
        if (i + w < count) {
            for (int c = 0; c < 4; ++c) {
                sum[static_cast<std::size_t>(c)] += row[static_cast<std::size_t>(i + w) * 4U + static_cast<std::size_t>(c)] -
                                                    row[static_cast<std::size_t>(i) * 4U + static_cast<std::size_t>(c)];
            }
        }
    }
    row.swap(scratch);
}

/**
 * One streaming vertical box stage: push rows in order; emits the mean of the last w rows. Running sums are
 * float for speed and recomputed exactly from the ring every 64 rows so rounding never accumulates.
 */
class VerticalBox final {
public:
    VerticalBox(int w, int columns)
        : w_(w), columns_(columns), ring_(static_cast<std::size_t>(w) * columns * 4U), sum_(static_cast<std::size_t>(columns) * 4U, 0.0F) {}

    /** Returns true when `out` holds a new output row (after the first w rows). */
    bool push(const float* row, std::vector<float>& out) {
        const auto n = static_cast<std::size_t>(columns_) * 4U;
        float* slot = ring_.data() + static_cast<std::size_t>(count_ % w_) * n;
        if (count_ >= w_) {
            for (std::size_t i = 0; i < n; ++i) sum_[i] += row[i] - slot[i];
        } else {
            for (std::size_t i = 0; i < n; ++i) sum_[i] += row[i];
        }
        std::copy_n(row, n, slot);
        ++count_;
        if (count_ < w_) return false;
        if (count_ % 64 == 0) {
            std::fill(sum_.begin(), sum_.end(), 0.0F);
            for (int k = 0; k < w_; ++k) {
                const float* r = ring_.data() + static_cast<std::size_t>(k) * n;
                for (std::size_t i = 0; i < n; ++i) sum_[i] += r[i];
            }
        }
        out.resize(n);
        const float inv = 1.0F / static_cast<float>(w_);
        for (std::size_t i = 0; i < n; ++i) out[i] = sum_[i] * inv;
        return true;
    }

private:
    int w_;
    int columns_;
    int count_ = 0;
    std::vector<float> ring_;
    std::vector<float> sum_;
};

/**
 * Separable box-chain filter (boxes of the given odd widths, horizontal then vertical), streamed per band of
 * tile columns; `combine` produces each output pixel from the original and the filtered value.
 */
bool separable(TileStore& layer, const std::vector<int>& widths, const TileStore* mask, const Combine& combine, std::string& error,
               unsigned worker_threads) {
    if (!validate(layer, mask, error)) return false;
    const auto bounds = region(layer, mask);
    if (!bounds) return true;
    int pad = 0;
    for (const int w : widths) pad += w / 2;
    const int first_tile_x = bounds->left / kEdge, last_tile_x = (bounds->right - 1) / kEdge;
    const int bands = (last_tile_x - first_tile_x) / kBandTiles + 1;
    // Tasks are (band, chunk of tile rows) so work balances across cores; each chunk re-reads `pad` rows
    // above and below to warm up its vertical stages.
    const int first_tile_y = bounds->top / kEdge, last_tile_y = (bounds->bottom - 1) / kEdge;
    const int chunk_tiles = std::max(kMinChunkTiles, (8 * pad + kEdge - 1) / kEdge);
    const int chunks = (last_tile_y - first_tile_y) / chunk_tiles + 1;
    const auto bpp = layer.bytes_per_pixel();
    const TileStore source(layer);  // Reads come from the unfiltered snapshot while bands write results.
    std::atomic<bool> failed{false};
    try {
        parallel::parallelFor(static_cast<std::size_t>(bands) * static_cast<std::size_t>(chunks), parallel::workerCount(worker_threads),
                              [&](std::size_t task, std::size_t) {
            const auto band = task % static_cast<std::size_t>(bands);
            const auto chunk = static_cast<int>(task / static_cast<std::size_t>(bands));
            const int y_begin = std::max(bounds->top, (first_tile_y + chunk * chunk_tiles) * kEdge);
            const int y_end = std::min(bounds->bottom, (first_tile_y + (chunk + 1) * chunk_tiles) * kEdge);
            const int tile_x0 = first_tile_x + static_cast<int>(band) * kBandTiles;
            const int tile_x1 = std::min(last_tile_x, tile_x0 + kBandTiles - 1);
            const int x0 = std::max(bounds->left, tile_x0 * kEdge);
            const int x1 = std::min(bounds->right, (tile_x1 + 1) * kEdge);
            const int columns = x1 - x0;
            const auto band_tiles = static_cast<std::size_t>(tile_x1 - tile_x0 + 1);
            RowReader reader(source);
            std::vector<VerticalBox> stages;
            for (const int w : widths) stages.emplace_back(w, columns);
            std::vector<std::vector<float>> stage_out(widths.size());
            std::vector<float> row, scratch, originals;
            std::vector<TileBuffer> tiles;
            std::vector<std::shared_ptr<const TileBuffer>> mask_tiles;
            int tile_row = -1;
            const auto flush = [&]() {
                for (std::size_t t = 0; t < tiles.size(); ++t) {
                    if (tiles[t].empty()) continue;
                    if (!layer.writeTile({tile_x0 + static_cast<int>(t), tile_row}, tiles[t])) failed.store(true);
                }
                tiles.clear();
            };
            const auto emit = [&](int y, const float* filtered_row) {
                if (y / kEdge != tile_row) {
                    flush();
                    tile_row = y / kEdge;
                    tiles.assign(band_tiles, TileBuffer());
                    mask_tiles.assign(band_tiles, nullptr);
                    for (std::size_t t = 0; t < band_tiles; ++t) {
                        const TileCoordinate coordinate{tile_x0 + static_cast<int>(t), tile_row};
                        if (mask != nullptr) {
                            mask_tiles[t] = mask->readTile(coordinate);
                            if (mask_tiles[t] == nullptr) continue;
                        }
                        const auto existing = source.readTile(coordinate);
                        tiles[t] = existing ? *existing : TileBuffer(layer.tile_byte_size(), 0);
                    }
                }
                for (int sx = x0; sx < x1;) {
                    const auto t = static_cast<std::size_t>(sx / kEdge - tile_x0);
                    const int ex = std::min(x1, (sx / kEdge + 1) * kEdge);
                    if (tiles[t].empty()) {
                        sx = ex;
                        continue;
                    }
                    auto* base = tiles[t].data() + (static_cast<std::size_t>(y % kEdge) * kTileEdge + static_cast<std::size_t>(sx % kEdge)) * bpp;
                    originals.resize(static_cast<std::size_t>(ex - sx) * 4U);
                    decodeSpan(layer.format(), base, originals.data(), static_cast<std::size_t>(ex - sx), false);
                    for (int x = sx; x < ex; ++x) {
                        const auto i = static_cast<std::size_t>(x - sx);
                        float coverage = 1.0F;
                        if (mask != nullptr) {
                            std::uint16_t raw = 0;
                            std::memcpy(&raw, mask_tiles[t]->data() + (static_cast<std::size_t>(y % kEdge) * kTileEdge + static_cast<std::size_t>(x % kEdge)) * 2U, 2U);
                            if (raw == 0U) continue;
                            coverage = raw / 65535.0F;
                        }
                        Pixel original{};
                        std::copy_n(originals.data() + i * 4U, 4, original.data());
                        Pixel filtered{};
                        std::copy_n(filtered_row + static_cast<std::size_t>(x - x0) * 4U, 4, filtered.data());
                        const Pixel result = combine ? combine(original, filtered) : unpremultiply(filtered);
                        Pixel mixed{};
                        for (std::size_t c = 0; c < 4; ++c) mixed[c] = original[c] + coverage * (result[c] - original[c]);
                        writePixel(layer.format(), base + i * bpp, mixed.data());
                    }
                    sx = ex;
                }
            };
            // Rows stream top to bottom: horizontal box passes per row, then the vertical stages. Every
            // vertical stage delays its output by (w − 1) rows, so the chain's output row is yin − pad.
            for (int yin = y_begin - pad; yin < y_end + pad; ++yin) {
                row.resize(static_cast<std::size_t>(columns + 2 * pad) * 4U);
                reader.read(yin, x0 - pad, columns + 2 * pad, row.data());
                int count = columns + 2 * pad;
                for (const int w : widths) {
                    boxRow(row, count, w, scratch);
                    count -= 2 * (w / 2);
                }
                // The horizontal passes consumed exactly `pad` columns on each side.
                const float* current = row.data();
                bool ready = true;
                for (std::size_t k = 0; k < stages.size(); ++k) {
                    if (!stages[k].push(current, stage_out[k])) {
                        ready = false;
                        break;
                    }
                    current = stage_out[k].data();
                }
                if (ready) emit(yin - pad, current);
            }
            flush();
        });
    } catch (const std::exception&) {
        error = "could not allocate filter buffers";
        return false;
    }
    return !failed.load();
}

std::vector<int> gaussianWidths(float radius) {
    const auto sizes = selection::detail::boxSizes(radius);
    return {static_cast<int>(sizes[0]), static_cast<int>(sizes[1]), static_cast<int>(sizes[2])};
}

bool radiusOk(float radius, float low, float high, std::string& error) {
    if (!std::isfinite(radius) || radius < low || radius > high) {
        error = "radius is out of range";
        return false;
    }
    return true;
}

}  // namespace

bool gaussianBlur(TileStore& layer, float radius, const TileStore* mask, std::string& error, unsigned worker_threads) {
    if (!radiusOk(radius, 0.0F, 1000.0F, error)) return false;
    if (radius < 0.3F) return validate(layer, mask, error);
    return separable(layer, gaussianWidths(radius), mask, replaceWithFiltered(), error, worker_threads);
}

bool boxBlur(TileStore& layer, std::uint32_t radius, const TileStore* mask, std::string& error, unsigned worker_threads) {
    if (radius > 2000U) {
        error = "radius is out of range";
        return false;
    }
    if (radius == 0U) return validate(layer, mask, error);
    return separable(layer, {static_cast<int>(2U * radius + 1U)}, mask, replaceWithFiltered(), error, worker_threads);
}

bool unsharpMask(TileStore& layer, float amount, float radius, float threshold, const TileStore* mask, std::string& error,
                 unsigned worker_threads) {
    if (!radiusOk(radius, 0.1F, 1000.0F, error)) return false;
    if (!std::isfinite(amount) || amount < 0.0F || amount > 5.0F || !std::isfinite(threshold) || threshold < 0.0F || threshold > 255.0F) {
        error = "amount must be 0–5 and threshold 0–255";
        return false;
    }
    const float limit = threshold / 255.0F;
    return separable(layer, gaussianWidths(radius), mask,
                     [amount, limit](const Pixel& original, const Pixel& filtered) {
                         const auto blurred = unpremultiply(filtered);
                         Pixel out = original;
                         for (std::size_t c = 0; c < 3; ++c) {
                             const float difference = original[c] - blurred[c];
                             if (std::fabs(difference) > limit) out[c] = original[c] + amount * difference;
                         }
                         return out;
                     },
                     error, worker_threads);
}

bool highPass(TileStore& layer, float radius, const TileStore* mask, std::string& error, unsigned worker_threads) {
    if (!radiusOk(radius, 0.1F, 1000.0F, error)) return false;
    return separable(layer, gaussianWidths(radius), mask,
                     [](const Pixel& original, const Pixel& filtered) {
                         const auto blurred = unpremultiply(filtered);
                         Pixel out = original;
                         for (std::size_t c = 0; c < 3; ++c) out[c] = original[c] - blurred[c] + 0.5F;
                         return out;
                     },
                     error, worker_threads);
}

bool motionBlur(TileStore& layer, float angle, float distance, const TileStore* mask, std::string& error, unsigned worker_threads) {
    if (!std::isfinite(angle) || angle < -360.0F || angle > 360.0F || !std::isfinite(distance) || distance < 1.0F || distance > 2000.0F) {
        error = "angle must be −360…360° and distance 1–2000 px";
        return false;
    }
    constexpr double kDegrees = 3.14159265358979323846 / 180.0;
    const double dx = std::cos(angle * kDegrees), dy = -std::sin(angle * kDegrees);  // y down
    const int samples = std::max(2, static_cast<int>(std::ceil(distance)) + 1);
    // Up to 512 px the whole line stays inside a per-tile window; longer lines sample the layer directly.
    const bool windowed = distance <= 512.0F;
    return pointwise(
        layer, mask, windowed ? static_cast<int>(std::ceil(distance / 2.0F)) + 2 : -1,
        [&](Taps& taps, int x, int y) {
            Pixel sum{};
            for (int i = 0; i < samples; ++i) {
                const double t = (static_cast<double>(i) / (samples - 1) - 0.5) * distance;
                const double sx = x + 0.5 + t * dx, sy = y + 0.5 + t * dy;
                const auto p = windowed ? taps.window.bilinear(sx, sy) : taps.layer.bilinear(sx, sy);
                for (std::size_t c = 0; c < 4; ++c) sum[c] += p[c];
            }
            for (auto& v : sum) v /= static_cast<float>(samples);
            return sum;
        },
        error, worker_threads);
}

bool radialBlur(TileStore& layer, RadialMode mode, float amount, double centre_x, double centre_y, const TileStore* mask,
                std::string& error, unsigned worker_threads) {
    if (!std::isfinite(amount) || amount < 1.0F || amount > 100.0F || !std::isfinite(centre_x) || !std::isfinite(centre_y)) {
        error = "amount must be 1–100 and the centre finite";
        return false;
    }
    constexpr double kDegrees = 3.14159265358979323846 / 180.0;
    const double sweep = amount * 0.72 * kDegrees;  // Spin: total arc angle
    const double zoom = amount * 0.005;             // Zoom: fraction of the distance to the centre
    return pointwise(
        layer, mask, -1,
        [&](Taps& taps, int x, int y) {
            Sampler& sampler = taps.layer;
            const double rx = x + 0.5 - centre_x, ry = y + 0.5 - centre_y;
            const double rho = std::hypot(rx, ry);
            const double extent = mode == RadialMode::Spin ? rho * sweep : rho * zoom;  // path length in px
            // Samples every ~2 px along the path (bilinear taps overlap), at most 32 ("good" quality).
            const int samples = std::clamp(static_cast<int>(std::ceil(extent / 2.0)) + 1, 1, 32);
            if (samples == 1) return sampler.bilinear(x + 0.5, y + 0.5);
            Pixel sum{};
            // Spin walks the arc by repeated rotation (one sincos pair per pixel instead of one per sample).
            const double step_angle = sweep / (samples - 1);
            const double step_c = std::cos(step_angle), step_s = std::sin(step_angle);
            double vx = std::cos(-sweep / 2.0) * rx - std::sin(-sweep / 2.0) * ry;
            double vy = std::sin(-sweep / 2.0) * rx + std::cos(-sweep / 2.0) * ry;
            for (int i = 0; i < samples; ++i) {
                const double t = static_cast<double>(i) / (samples - 1) - 0.5;
                double px = 0.0, py = 0.0;
                if (mode == RadialMode::Spin) {
                    px = centre_x + vx;
                    py = centre_y + vy;
                    const double next_x = step_c * vx - step_s * vy;
                    vy = step_s * vx + step_c * vy;
                    vx = next_x;
                } else {
                    const double scale = 1.0 - (t + 0.5) * zoom;  // towards the centre
                    px = centre_x + rx * scale;
                    py = centre_y + ry * scale;
                }
                const auto p = sampler.bilinear(px, py);
                for (std::size_t c = 0; c < 4; ++c) sum[c] += p[c];
            }
            for (auto& v : sum) v /= static_cast<float>(samples);
            return sum;
        },
        error, worker_threads);
}

bool surfaceBlur(TileStore& layer, std::uint32_t radius, float threshold, const TileStore* mask, std::string& error,
                 unsigned worker_threads) {
    if (radius < 1U || radius > 100U || !std::isfinite(threshold) || threshold < 2.0F || threshold > 255.0F) {
        error = "radius must be 1–100 px and threshold 2–255 levels";
        return false;
    }
    const int r = static_cast<int>(radius);
    const int step = std::max(1, (2 * r + 1 + 8) / 9);  // ≤ 9 × 9 taps at any radius
    const float falloff = 1.0F / (2.5F * threshold / 255.0F);
    return pointwise(
        layer, mask, r,
        [&](Taps& taps, int x, int y) {
            const Window& sampler = taps.window;
            const auto centre = sampler.texel(x, y);
            Pixel sum{};
            std::array<float, 4> weight{};
            for (int dy = -r; dy <= r; dy += step) {
                for (int dx = -r; dx <= r; dx += step) {
                    const auto q = sampler.texel(x + dx, y + dy);
                    for (std::size_t c = 0; c < 4; ++c) {
                        const float w = std::max(0.0F, 1.0F - std::fabs(q[c] - centre[c]) * falloff);
                        sum[c] += w * q[c];
                        weight[c] += w;
                    }
                }
            }
            for (std::size_t c = 0; c < 4; ++c) sum[c] = weight[c] > 0.0F ? sum[c] / weight[c] : centre[c];
            return sum;
        },
        error, worker_threads);
}

bool tiltShift(TileStore& layer, const TiltShiftSpec& spec, const TileStore* mask, std::string& error, unsigned worker_threads) {
    if (!std::isfinite(spec.centre_x) || !std::isfinite(spec.centre_y) || !std::isfinite(spec.angle) || !std::isfinite(spec.focus) ||
        !std::isfinite(spec.transition) || !std::isfinite(spec.blur) || spec.focus < 0.0F || spec.transition < 1.0F || spec.blur < 0.0F ||
        spec.blur > 500.0F) {
        error = "tilt-shift needs a finite centre and angle, focus ≥ 0, transition ≥ 1 px and blur 0–500 px";
        return false;
    }
    if (!validate(layer, mask, error)) return false;
    if (spec.blur < 0.3F) return true;
    const auto bounds = region(layer, mask);
    if (!bounds) return true;
    try {
        // Blur levels σ/4, σ/2, σ; each pixel interpolates between the two levels around its own σ. The
        // levels are pixel-aligned with the layer, so each output tile reads the same tile of every level.
        const std::array<float, 4> stops{0.0F, spec.blur / 4.0F, spec.blur / 2.0F, spec.blur};
        std::vector<TileStore> levels;
        levels.reserve(4);
        levels.emplace_back(layer);
        for (std::size_t i = 1; i < 4; ++i) {
            levels.emplace_back(layer);
            if (stops[i] >= 0.3F && !gaussianBlur(levels.back(), stops[i], nullptr, error, worker_threads)) return false;
        }
        constexpr double kDegrees = 3.14159265358979323846 / 180.0;
        // Unit normal of the focus line; the line runs at `angle` degrees counter-clockwise (y up).
        const double nx = std::sin(spec.angle * kDegrees), ny = std::cos(spec.angle * kDegrees);
        const int tx0 = bounds->left / kEdge, ty0 = bounds->top / kEdge;
        const int tx1 = (bounds->right - 1) / kEdge, ty1 = (bounds->bottom - 1) / kEdge;
        const auto columns = static_cast<std::size_t>(tx1 - tx0 + 1);
        const auto bpp = layer.bytes_per_pixel();
        std::atomic<bool> failed{false};
        parallel::parallelFor(columns * static_cast<std::size_t>(ty1 - ty0 + 1), parallel::workerCount(worker_threads), [&](std::size_t item, std::size_t) {
            const TileCoordinate coordinate{tx0 + static_cast<int>(item % columns), ty0 + static_cast<int>(item / columns)};
            const auto mask_tile = mask != nullptr ? mask->readTile(coordinate) : nullptr;
            if (mask != nullptr && mask_tile == nullptr) return;
            std::array<std::shared_ptr<const TileBuffer>, 4> tiles;
            for (std::size_t i = 0; i < 4; ++i) tiles[i] = levels[i].readTile(coordinate);
            TileBuffer out = tiles[0] ? *tiles[0] : TileBuffer(layer.tile_byte_size(), 0);
            bool changed = false;
            for (int ly = 0; ly < kEdge; ++ly) {
                const int y = coordinate.y * kEdge + ly;
                if (y < bounds->top || y >= bounds->bottom) continue;
                for (int lx = 0; lx < kEdge; ++lx) {
                    const int x = coordinate.x * kEdge + lx;
                    if (x < bounds->left || x >= bounds->right) continue;
                    const auto local = static_cast<std::size_t>(ly) * kTileEdge + static_cast<std::size_t>(lx);
                    float coverage = 1.0F;
                    if (mask_tile != nullptr) {
                        std::uint16_t raw = 0;
                        std::memcpy(&raw, mask_tile->data() + local * 2U, 2U);
                        if (raw == 0U) continue;
                        coverage = raw / 65535.0F;
                    }
                    const double distance = std::fabs((x + 0.5 - spec.centre_x) * nx + (y + 0.5 - spec.centre_y) * ny);
                    const auto sigma = static_cast<float>(spec.blur * std::clamp((distance - spec.focus) / spec.transition, 0.0, 1.0));
                    if (sigma <= 0.0F) continue;
                    std::size_t hi = 1;
                    while (hi < 3 && stops[hi] < sigma) ++hi;
                    const float t = (sigma - stops[hi - 1]) / (stops[hi] - stops[hi - 1]);
                    const auto sample = [&](std::size_t level) {
                        Pixel p{};
                        if (tiles[level] != nullptr) readPixel(layer.format(), tiles[level]->data() + local * bpp, p.data());
                        for (int c = 0; c < 3; ++c) p[static_cast<std::size_t>(c)] *= p[3];
                        return p;
                    };
                    const auto a = sample(hi - 1), b = sample(hi);
                    Pixel blended{};
                    for (std::size_t c = 0; c < 4; ++c) blended[c] = a[c] + t * (b[c] - a[c]);
                    const auto result = unpremultiply(blended);
                    Pixel original{};
                    if (tiles[0] != nullptr) readPixel(layer.format(), tiles[0]->data() + local * bpp, original.data());
                    Pixel mixed{};
                    for (std::size_t c = 0; c < 4; ++c) mixed[c] = original[c] + coverage * (result[c] - original[c]);
                    writePixel(layer.format(), out.data() + local * bpp, mixed.data());
                    changed = true;
                }
            }
            if (changed && !layer.writeTile(coordinate, out)) failed.store(true);
        });
        if (failed.load()) {
            error = "could not store filtered pixels";
            return false;
        }
        return true;
    } catch (const std::exception&) {
        error = "could not allocate tilt-shift buffers";
        return false;
    }
}

bool smartSharpen(TileStore& layer, const SmartSharpenSpec& spec, const TileStore* mask, std::string& error, unsigned worker_threads) {
    if (!std::isfinite(spec.amount) || spec.amount < 0.0F || spec.amount > 5.0F || !std::isfinite(spec.radius) || spec.radius < 0.1F ||
        spec.radius > 64.0F || !std::isfinite(spec.reduce_noise) || spec.reduce_noise < 0.0F || spec.reduce_noise > 1.0F) {
        error = "smart sharpen needs amount 0–5, radius 0.1–64 px and noise reduction 0–1";
        return false;
    }
    if (!validate(layer, mask, error)) return false;
    try {
        TileStore blurred(layer);
        const bool ok = spec.remove == SharpenRemove::Gaussian
                            ? gaussianBlur(blurred, spec.radius, nullptr, error, worker_threads)
                            : boxBlur(blurred, static_cast<std::uint32_t>(std::max(1.0F, std::round(spec.radius))), nullptr, error, worker_threads);
        if (!ok) return false;
        // Detail below the noise floor is attenuated quadratically; results are held within the 3×3
        // neighbourhood's range widened by 10 % of it (halo control).
        const float noise = spec.reduce_noise * 0.08F;
        return pointwise(
            layer, mask, 1,
            [&](Taps& taps, int x, int y) {
                const Window& sampler = taps.window;
                const auto centre = sampler.texel(x, y);
                const auto soft = taps.aux.texel(x, y);
                Pixel lo = centre, hi = centre;
                for (int dy = -1; dy <= 1; ++dy)
                    for (int dx = -1; dx <= 1; ++dx) {
                        const auto q = sampler.texel(x + dx, y + dy);
                        for (std::size_t c = 0; c < 4; ++c) {
                            lo[c] = std::min(lo[c], q[c]);
                            hi[c] = std::max(hi[c], q[c]);
                        }
                    }
                Pixel out = centre;
                for (std::size_t c = 0; c < 3; ++c) {
                    float detail = centre[c] - soft[c];
                    if (noise > 0.0F && std::fabs(detail) < noise) detail *= (detail / noise) * (detail / noise);
                    const float margin = 0.1F * (hi[c] - lo[c]);
                    out[c] = std::clamp(centre[c] + spec.amount * detail, lo[c] - margin, hi[c] + margin);
                }
                return out;
            },
            error, worker_threads, &blurred);
    } catch (const std::exception&) {
        error = "could not allocate smart sharpen buffers";
        return false;
    }
}

}  // namespace croptop::filters
