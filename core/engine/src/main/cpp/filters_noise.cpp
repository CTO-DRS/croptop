#include "filters_noise.h"

#include <algorithm>
#include <array>
#include <cmath>
#include <vector>

#include "filters_internal.h"

namespace croptop::filters {
namespace {

using namespace detail;

constexpr double kDegrees = 3.14159265358979323846 / 180.0;

std::uint32_t hash(std::uint32_t x, std::uint32_t y, std::uint32_t channel, std::uint32_t seed) {
    std::uint32_t h = x * 0x8DA6B343U ^ y * 0xD8163841U ^ channel * 0xCB1AB31FU ^ seed * 0x165667B1U;
    h ^= h >> 15;
    h *= 0x2C1B3C6DU;
    h ^= h >> 12;
    h *= 0x297A2D39U;
    h ^= h >> 15;
    return h;
}

/** Uniform in [0, 1). */
float unit(std::uint32_t h) { return static_cast<float>(h >> 8) * (1.0F / 16777216.0F); }

/** Standard normal from two hashes (Box–Muller). */
float gaussian(std::uint32_t a, std::uint32_t b) {
    const float u1 = std::max(unit(a), 1e-7F), u2 = unit(b);
    return std::sqrt(-2.0F * std::log(u1)) * std::cos(6.2831853F * u2);
}

float luma(const Pixel& p) { return 0.2126F * p[0] + 0.7152F * p[1] + 0.0722F * p[2]; }

bool unitRange(float v, float low, float high) { return std::isfinite(v) && v >= low && v <= high; }

/** Per-channel sliding-histogram median over a (2r+1)² square, for one output tile from its window. */
class MedianTile final {
public:
    explicit MedianTile(int r) : r_(r) {}

    /** Fills `out` (premultiplied) for the window's centre rectangle [left, right) × [top, bottom). */
    void run(const Window& window, int left, int top, int right, int bottom, std::vector<Pixel>& out) {
        const int w = right - left;
        out.assign(static_cast<std::size_t>(w) * static_cast<std::size_t>(bottom - top), Pixel{});
        const int count = (2 * r_ + 1) * (2 * r_ + 1);
        for (int y = top; y < bottom; ++y) {
            for (auto& h : histogram_) h.fill(0);
            // Initial window at x = left.
            for (int dy = -r_; dy <= r_; ++dy)
                for (int dx = -r_; dx <= r_; ++dx) add(window.at(left + dx, y + dy), 1);
            for (int x = left; x < right; ++x) {
                if (x > left) {
                    for (int dy = -r_; dy <= r_; ++dy) {
                        add(window.at(x - r_ - 1, y + dy), -1);
                        add(window.at(x + r_, y + dy), 1);
                    }
                }
                auto& result = out[static_cast<std::size_t>(y - top) * static_cast<std::size_t>(w) + static_cast<std::size_t>(x - left)];
                for (std::size_t c = 0; c < 4; ++c) result[c] = medianOf(c, count);
            }
        }
    }

private:
    void add(const float* p, int delta) {
        for (std::size_t c = 0; c < 4; ++c) {
            const float v = std::clamp(p[c], 0.0F, 1.0F) * 255.0F;
            const auto bin = static_cast<std::size_t>(std::min(255.0F, v));
            histogram_[c][bin] += delta;
            fraction_[c][bin] += static_cast<float>(delta) * (v - static_cast<float>(bin));
        }
    }
    float medianOf(std::size_t c, int count) const {
        const int half = count / 2;
        int seen = 0;
        for (std::size_t bin = 0; bin < 256; ++bin) {
            seen += histogram_[c][bin];
            if (seen > half) {
                // Refine inside the bin with the mean offset of its members.
                const int n = histogram_[c][bin];
                const float offset = n > 0 ? std::clamp(fraction_[c][bin] / static_cast<float>(n), 0.0F, 0.999F) : 0.0F;
                return (static_cast<float>(bin) + offset) / 255.0F;
            }
        }
        return 1.0F;
    }

    int r_;
    std::array<std::array<int, 256>, 4> histogram_{};
    std::array<std::array<float, 256>, 4> fraction_{};
};

/**
 * Tile engine for filters that need a whole tile at once (median, mosaic): `produce(source, window, left, top,
 * right, bottom, out)` reads only the unfiltered `source` snapshot and writes premultiplied results for the rectangle; `combine` mixes original and result.
 */
bool tileWise(TileStore& layer, const TileStore* mask, int pad,
              const std::function<void(const TileStore& source, const Window&, int, int, int, int, std::vector<Pixel>&)>& produce,
              const std::function<Pixel(const Pixel& original, const Pixel& result)>& combine, std::string& error,
              unsigned worker_threads) {
    if (!validate(layer, mask, error)) return false;
    const auto bounds = region(layer, mask);
    if (!bounds) return true;
    const TileStore source(layer);
    const int tx0 = bounds->left / kEdge, ty0 = bounds->top / kEdge;
    const int tx1 = (bounds->right - 1) / kEdge, ty1 = (bounds->bottom - 1) / kEdge;
    const auto columns = static_cast<std::size_t>(tx1 - tx0 + 1);
    const auto bpp = layer.bytes_per_pixel();
    std::atomic<bool> failed{false};
    try {
        parallel::parallelFor(columns * static_cast<std::size_t>(ty1 - ty0 + 1), parallel::workerCount(worker_threads), [&](std::size_t item, std::size_t) {
            const TileCoordinate coordinate{tx0 + static_cast<int>(item % columns), ty0 + static_cast<int>(item / columns)};
            const auto mask_tile = mask != nullptr ? mask->readTile(coordinate) : nullptr;
            if (mask != nullptr && mask_tile == nullptr) return;
            const int left = std::max(bounds->left, coordinate.x * kEdge), top = std::max(bounds->top, coordinate.y * kEdge);
            const int right = std::min(bounds->right, (coordinate.x + 1) * kEdge), bottom = std::min(bounds->bottom, (coordinate.y + 1) * kEdge);
            RowReader reader(source);
            Window window;
            window.build(reader, left, top, right, bottom, pad);
            std::vector<Pixel> results;
            produce(source, window, left, top, right, bottom, results);
            const auto existing = source.readTile(coordinate);
            TileBuffer tile = existing ? *existing : TileBuffer(layer.tile_byte_size(), 0);
            for (int y = top; y < bottom; ++y) {
                for (int x = left; x < right; ++x) {
                    const auto local = static_cast<std::size_t>(y % kEdge) * kTileEdge + static_cast<std::size_t>(x % kEdge);
                    float coverage = 1.0F;
                    if (mask_tile != nullptr) {
                        std::uint16_t raw = 0;
                        std::memcpy(&raw, mask_tile->data() + local * 2U, 2U);
                        if (raw == 0U) continue;
                        coverage = raw / 65535.0F;
                    }
                    auto* p = tile.data() + local * bpp;
                    Pixel original{};
                    readPixel(layer.format(), p, original.data());
                    const auto& produced = results[static_cast<std::size_t>(y - top) * static_cast<std::size_t>(right - left) + static_cast<std::size_t>(x - left)];
                    const Pixel result = combine(original, unpremultiply(produced));
                    Pixel mixed{};
                    for (std::size_t c = 0; c < 4; ++c) mixed[c] = original[c] + coverage * (result[c] - original[c]);
                    writePixel(layer.format(), p, mixed.data());
                }
            }
            if (!layer.writeTile(coordinate, tile)) failed.store(true);
        });
    } catch (const std::exception&) {
        error = "could not allocate filter buffers";
        return false;
    }
    if (failed.load()) error = "could not store filtered pixels";
    return !failed.load();
}

Pixel premultiply(Pixel p) {
    for (std::size_t c = 0; c < 3; ++c) p[c] *= p[3];
    return p;
}

}  // namespace

bool addNoise(TileStore& layer, float amount, NoiseDistribution distribution, bool monochromatic, std::uint32_t seed,
              const TileStore* mask, std::string& error, unsigned worker_threads) {
    if (!unitRange(amount, 0.0F, 4.0F)) {
        error = "amount must be 0–4 (0–400 %)";
        return false;
    }
    const float a = amount / 4.0F;
    return pointwise(
        layer, mask, 0,
        [&](Taps& taps, int x, int y) {
            Pixel p = unpremultiply(taps.window.texel(x, y));
            const auto ux = static_cast<std::uint32_t>(x), uy = static_cast<std::uint32_t>(y);
            for (std::uint32_t c = 0; c < 3; ++c) {
                const std::uint32_t channel = monochromatic ? 0U : c;
                const float n = distribution == NoiseDistribution::Uniform
                                    ? (2.0F * unit(hash(ux, uy, channel, seed)) - 1.0F) * a
                                    : gaussian(hash(ux, uy, channel, seed), hash(ux, uy, channel + 8U, seed)) * a * 0.5F;
                p[c] += n;
            }
            return premultiply(p);
        },
        error, worker_threads);
}

bool filmGrain(TileStore& layer, float intensity, float size, std::uint32_t seed, const TileStore* mask, std::string& error,
               unsigned worker_threads) {
    if (!unitRange(intensity, 0.0F, 1.0F) || !unitRange(size, 1.0F, 64.0F)) {
        error = "intensity must be 0–1 and size 1–64 px";
        return false;
    }
    const auto lattice = [&](int i, int j) { return 2.0F * unit(hash(static_cast<std::uint32_t>(i), static_cast<std::uint32_t>(j), 3U, seed)) - 1.0F; };
    return pointwise(
        layer, mask, 0,
        [&](Taps& taps, int x, int y) {
            Pixel p = unpremultiply(taps.window.texel(x, y));
            const float gx = (static_cast<float>(x) + 0.5F) / size, gy = (static_cast<float>(y) + 0.5F) / size;
            const int i = static_cast<int>(std::floor(gx)), j = static_cast<int>(std::floor(gy));
            const float fx = gx - static_cast<float>(i), fy = gy - static_cast<float>(j);
            const float n = (lattice(i, j) * (1 - fx) + lattice(i + 1, j) * fx) * (1 - fy) + (lattice(i, j + 1) * (1 - fx) + lattice(i + 1, j + 1) * fx) * fy;
            const float l = std::clamp(luma(p), 0.0F, 1.0F);
            const float delta = n * intensity * 0.25F * (0.25F + 3.0F * l * (1.0F - l));
            for (std::size_t c = 0; c < 3; ++c) p[c] += delta;
            return premultiply(p);
        },
        error, worker_threads);
}

bool median(TileStore& layer, std::uint32_t radius, const TileStore* mask, std::string& error, unsigned worker_threads) {
    if (radius < 1U || radius > 100U) {
        error = "radius must be 1–100 px";
        return false;
    }
    const int r = static_cast<int>(radius);
    return tileWise(
        layer, mask, r,
        [r](const TileStore&, const Window& window, int left, int top, int right, int bottom, std::vector<Pixel>& out) {
            MedianTile(r).run(window, left, top, right, bottom, out);
        },
        [](const Pixel&, const Pixel& result) { return result; }, error, worker_threads);
}

bool dustAndScratches(TileStore& layer, std::uint32_t radius, float threshold, const TileStore* mask, std::string& error,
                      unsigned worker_threads) {
    if (radius < 1U || radius > 100U || !unitRange(threshold, 0.0F, 255.0F)) {
        error = "radius must be 1–100 px and threshold 0–255";
        return false;
    }
    const int r = static_cast<int>(radius);
    const float limit = threshold / 255.0F;
    return tileWise(
        layer, mask, r,
        [r](const TileStore&, const Window& window, int left, int top, int right, int bottom, std::vector<Pixel>& out) {
            MedianTile(r).run(window, left, top, right, bottom, out);
        },
        [limit](const Pixel& original, const Pixel& result) {
            Pixel out = original;
            for (std::size_t c = 0; c < 4; ++c)
                if (std::fabs(result[c] - original[c]) > limit) out[c] = result[c];
            return out;
        },
        error, worker_threads);
}

bool reduceNoise(TileStore& layer, float strength, float preserve_details, const TileStore* mask, std::string& error,
                 unsigned worker_threads) {
    if (!unitRange(strength, 0.0F, 10.0F) || !unitRange(preserve_details, 0.0F, 1.0F)) {
        error = "strength must be 0–10 and preserve details 0–1";
        return false;
    }
    if (strength == 0.0F) return validate(layer, mask, error);
    constexpr int kRadius = 3;
    const float range = 0.02F * strength;
    const float inv_range = 1.0F / (2.0F * range * range);
    return pointwise(
        layer, mask, kRadius,
        [&](Taps& taps, int x, int y) {
            const auto centre = taps.window.texel(x, y);
            Pixel sum{};
            float weight = 0.0F;
            for (int dy = -kRadius; dy <= kRadius; ++dy) {
                for (int dx = -kRadius; dx <= kRadius; ++dx) {
                    const float* q = taps.window.at(x + dx, y + dy);
                    float d2 = 0.0F;
                    for (std::size_t c = 0; c < 3; ++c) d2 += (q[c] - centre[c]) * (q[c] - centre[c]);
                    const float spatial = static_cast<float>(dx * dx + dy * dy) / (2.0F * 2.0F * 2.0F);
                    const float w = std::exp(-d2 * inv_range - spatial);
                    for (std::size_t c = 0; c < 4; ++c) sum[c] += w * q[c];
                    weight += w;
                }
            }
            Pixel out{};
            for (std::size_t c = 0; c < 4; ++c) {
                const float smooth = sum[c] / weight;
                out[c] = smooth + preserve_details * (centre[c] - smooth);
            }
            return out;
        },
        error, worker_threads);
}

bool mosaic(TileStore& layer, std::uint32_t cell, const TileStore* mask, std::string& error, unsigned worker_threads) {
    if (cell < 2U || cell > 200U) {
        error = "cell size must be 2–200 px";
        return false;
    }
    const int size = static_cast<int>(cell);
    const int width = static_cast<int>(layer.width()), height = static_cast<int>(layer.height());
    return tileWise(
        layer, mask, 0,
        [&](const TileStore& source, const Window&, int left, int top, int right, int bottom, std::vector<Pixel>& out) {
            // Each cell's mean over its (clipped) extent, read from the unfiltered snapshot (other tiles are
            // being written concurrently); computed once per tile.
            out.assign(static_cast<std::size_t>(right - left) * static_cast<std::size_t>(bottom - top), Pixel{});
            Sampler sampler(source);
            for (int cy = (top / size) * size; cy < bottom; cy += size) {
                for (int cx = (left / size) * size; cx < right; cx += size) {
                    Pixel mean{};
                    int n = 0;
                    for (int y = cy; y < std::min(cy + size, height); ++y)
                        for (int x = cx; x < std::min(cx + size, width); ++x) {
                            const auto p = sampler.texel(x, y);
                            for (std::size_t c = 0; c < 4; ++c) mean[c] += p[c];
                            ++n;
                        }
                    for (auto& v : mean) v /= static_cast<float>(std::max(1, n));
                    for (int y = std::max(cy, top); y < std::min(cy + size, bottom); ++y)
                        for (int x = std::max(cx, left); x < std::min(cx + size, right); ++x)
                            out[static_cast<std::size_t>(y - top) * static_cast<std::size_t>(right - left) + static_cast<std::size_t>(x - left)] = mean;
                }
            }
        },
        [](const Pixel&, const Pixel& result) { return result; }, error, worker_threads);
}

bool crystallize(TileStore& layer, std::uint32_t cell, std::uint32_t seed, const TileStore* mask, std::string& error,
                 unsigned worker_threads) {
    if (cell < 3U || cell > 300U) {
        error = "cell size must be 3–300 px";
        return false;
    }
    const float size = static_cast<float>(cell);
    const auto seedPoint = [&](int i, int j) {
        const auto ui = static_cast<std::uint32_t>(i), uj = static_cast<std::uint32_t>(j);
        return std::array<float, 2>{(static_cast<float>(i) + unit(hash(ui, uj, 5U, seed))) * size,
                                    (static_cast<float>(j) + unit(hash(ui, uj, 6U, seed))) * size};
    };
    return pointwise(
        layer, mask, -1,
        [&](Taps& taps, int x, int y) {
            const float px = static_cast<float>(x) + 0.5F, py = static_cast<float>(y) + 0.5F;
            const int ci = static_cast<int>(std::floor(px / size)), cj = static_cast<int>(std::floor(py / size));
            float best = 3.0e38F;
            std::array<float, 2> nearest{px, py};
            for (int j = cj - 1; j <= cj + 1; ++j)
                for (int i = ci - 1; i <= ci + 1; ++i) {
                    const auto s = seedPoint(i, j);
                    const float d = (s[0] - px) * (s[0] - px) + (s[1] - py) * (s[1] - py);
                    if (d < best) {
                        best = d;
                        nearest = s;
                    }
                }
            return taps.layer.texel(static_cast<int>(nearest[0]), static_cast<int>(nearest[1]));
        },
        error, worker_threads);
}

bool colorHalftone(TileStore& layer, std::uint32_t max_radius, std::array<float, 3> angles, const TileStore* mask,
                   std::string& error, unsigned worker_threads) {
    if (max_radius < 4U || max_radius > 127U || !std::all_of(angles.begin(), angles.end(), [](float a) { return std::isfinite(a); })) {
        error = "max radius must be 4–127 px and the angles finite";
        return false;
    }
    const double pitch = 2.0 * max_radius;
    std::array<std::array<double, 2>, 3> axes{};  // unit x axis of each channel's grid (y axis is its normal)
    for (std::size_t c = 0; c < 3; ++c) axes[c] = {std::cos(angles[c] * kDegrees), -std::sin(angles[c] * kDegrees)};
    return pointwise(
        layer, mask, -1,
        [&](Taps& taps, int x, int y) {
            const double px = x + 0.5, py = y + 0.5;
            Pixel out = unpremultiply(taps.layer.texel(x, y));
            for (std::size_t c = 0; c < 3; ++c) {
                const double ux = axes[c][0], uy = axes[c][1];
                // Grid coordinates along the rotated axes.
                const double u = (px * ux + py * uy) / pitch, v = (-px * uy + py * ux) / pitch;
                const double cu = std::floor(u) + 0.5, cv = std::floor(v) + 0.5;
                const double centre_x = (cu * ux - cv * uy) * pitch, centre_y = (cu * uy + cv * ux) * pitch;
                const auto sample = unpremultiply(taps.layer.bilinear(centre_x, centre_y));
                const double value = std::clamp(static_cast<double>(sample[c]), 0.0, 1.0);
                // Dot area = value × cell area → radius = pitch·sqrt(value/π), capped at the cell's corner.
                const double dot = pitch * std::sqrt(value / 3.14159265358979323846);
                const double distance = std::hypot(px - centre_x, py - centre_y);
                out[c] = static_cast<float>(std::clamp(dot - distance + 0.5, 0.0, 1.0));
            }
            return premultiply(out);
        },
        error, worker_threads);
}

}  // namespace croptop::filters
