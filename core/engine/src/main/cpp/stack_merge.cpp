// HDR merge and focus stacking.
#include "stack_merge.h"

#include <algorithm>
#include <atomic>
#include <cmath>
#include <cstdint>
#include <numeric>

#include "develop_internal.h"
#include "parallel_util.h"
#include "pixel_plane.h"

namespace croptop::stack {
namespace {

constexpr int kMaxLevels = 6;  // shifts up to ±63 px keep every shifted read inside the 3×3 tile neighbourhood
constexpr float kMaxSmoothing = 16.0F;
constexpr std::size_t kMaxFrames = 16;

float srgbToLinear(float v) { return v <= 0.04045F ? v / 12.92F : std::pow((v + 0.055F) / 1.055F, 2.4F); }
float linearToSrgb(float v) { return v <= 0.0031308F ? v * 12.92F : 1.055F * std::pow(v, 1.0F / 2.4F) - 0.055F; }

/** sRGB decode by table (exact for 8- and 16-bit samples, which land on its 65536 steps). */
float decodeSrgb(float encoded) {
    static const std::vector<float> table = [] {
        std::vector<float> t(65536);
        for (std::size_t i = 0; i < t.size(); ++i) t[i] = srgbToLinear(static_cast<float>(i) / 65535.0F);
        return t;
    }();
    return table[static_cast<std::size_t>(std::lround(paint::detail::unitClamp(encoded) * 65535.0F))];
}

float smoothstep(float e0, float e1, float x) {
    const float t = std::clamp((x - e0) / (e1 - e0), 0.0F, 1.0F);
    return t * t * (3.0F - 2.0F * t);
}

// ---- Median threshold bitmap alignment (Ward 2003) --------------------------------------------------------

struct Gray final {
    int w = 0, h = 0;
    std::vector<std::uint8_t> v;
};

/** Runs `work(y0, y1)` over row bands in parallel. */
template <typename Work>
void forRows(int h, Work&& work) {
    constexpr int kBand = 64;
    const auto bands = static_cast<std::size_t>((h + kBand - 1) / kBand);
    parallel::parallelFor(bands, parallel::workerCount(0), [&](std::size_t band, std::size_t) {
        const int y0 = static_cast<int>(band) * kBand;
        work(y0, std::min(h, y0 + kBand));
    });
}

/**
 * Binomial [1 2 1]² twice (σ ≈ 1): suppresses pixel noise and fine texture that differs between frames
 * (defocus, sensor noise) so the threshold bitmaps follow the shared structure.
 */
void smooth(Gray& g) {
    std::vector<std::uint8_t> copy(g.v.size());
    for (int pass = 0; pass < 2; ++pass) {
        forRows(g.h, [&](int y0, int y1) {  // horizontal: g.v → copy
            for (int y = y0; y < y1; ++y) {
                const auto* in = g.v.data() + static_cast<std::size_t>(y) * g.w;
                auto* out = copy.data() + static_cast<std::size_t>(y) * g.w;
                for (int x = 0; x < g.w; ++x) out[x] = static_cast<std::uint8_t>((in[std::max(0, x - 1)] + 2 * in[x] + in[std::min(g.w - 1, x + 1)] + 2) / 4);
            }
        });
        forRows(g.h, [&](int y0, int y1) {  // vertical: copy → g.v
            for (int y = y0; y < y1; ++y) {
                const auto* up = copy.data() + static_cast<std::size_t>(std::max(0, y - 1)) * g.w;
                const auto* mid = copy.data() + static_cast<std::size_t>(y) * g.w;
                const auto* down = copy.data() + static_cast<std::size_t>(std::min(g.h - 1, y + 1)) * g.w;
                auto* out = g.v.data() + static_cast<std::size_t>(y) * g.w;
                for (int x = 0; x < g.w; ++x) out[x] = static_cast<std::uint8_t>((up[x] + 2 * mid[x] + down[x] + 2) / 4);
            }
        });
    }
}

/** Display-referred 8-bit luminance (MTB only needs a monotonic response). */
Gray grayOf(const TileStore& image) {
    Gray g{static_cast<int>(image.width()), static_cast<int>(image.height()), {}};
    g.v.assign(static_cast<std::size_t>(g.w) * g.h, 0);
    const bool linear = image.format() == PixelFormat::RGBA32F;
    const auto coordinates = image.tileCoordinates();
    parallel::parallelFor(coordinates.size(), parallel::workerCount(0), [&](std::size_t index, std::size_t) {
        const auto c = coordinates[index];
        const auto tile = image.readTile(c);
        if (tile == nullptr) return;
        const auto bpp = image.bytes_per_pixel();
        const int x0 = c.x * static_cast<int>(kTileEdge), y0 = c.y * static_cast<int>(kTileEdge);
        const int x1 = std::min(g.w, x0 + static_cast<int>(kTileEdge)), y1 = std::min(g.h, y0 + static_cast<int>(kTileEdge));
        for (int y = y0; y < y1; ++y)
            for (int x = x0; x < x1; ++x) {
                float px[4];
                paint::detail::readPixel(image.format(), tile->data() + (static_cast<std::size_t>(y - y0) * kTileEdge + static_cast<std::size_t>(x - x0)) * bpp, px);
                if (linear)
                    for (int k = 0; k < 3; ++k) px[k] = linearToSrgb(paint::detail::unitClamp(px[k]));
                const float l = (54.0F * paint::detail::unitClamp(px[0]) + 183.0F * paint::detail::unitClamp(px[1]) + 19.0F * paint::detail::unitClamp(px[2])) / 256.0F;
                g.v[static_cast<std::size_t>(y) * g.w + x] = static_cast<std::uint8_t>(std::lround(l * 255.0F));
            }
    });
    smooth(g);
    return g;
}

Gray halve(const Gray& g) {
    Gray h{std::max(1, g.w / 2), std::max(1, g.h / 2), {}};
    h.v.resize(static_cast<std::size_t>(h.w) * h.h);
    for (int y = 0; y < h.h; ++y)
        for (int x = 0; x < h.w; ++x) {
            const int sx = std::min(2 * x, g.w - 1), sy = std::min(2 * y, g.h - 1);
            const int sx1 = std::min(sx + 1, g.w - 1), sy1 = std::min(sy + 1, g.h - 1);
            const unsigned sum = g.v[static_cast<std::size_t>(sy) * g.w + sx] + g.v[static_cast<std::size_t>(sy) * g.w + sx1] +
                                 g.v[static_cast<std::size_t>(sy1) * g.w + sx] + g.v[static_cast<std::size_t>(sy1) * g.w + sx1];
            h.v[static_cast<std::size_t>(y) * h.w + x] = static_cast<std::uint8_t>((sum + 2) / 4);
        }
    return h;
}

/** Per pixel: bit 0 = above the threshold, bit 1 = far enough from it to count (Ward's exclusion bitmap). */
using Bitmap = std::vector<std::uint8_t>;

/** Threshold at the `percentile` of the histogram (Ward's MTB uses the median, 0.5). */
Bitmap bitmapOf(const Gray& g, double percentile) {
    std::array<std::size_t, 256> histogram{};
    for (const auto v : g.v) ++histogram[v];
    std::size_t seen = 0;
    int level = 0;
    for (; level < 255; ++level) {
        seen += histogram[static_cast<std::size_t>(level)];
        if (static_cast<double>(seen) >= percentile * static_cast<double>(g.v.size())) break;
    }
    Bitmap b(g.v.size());
    forRows(g.h, [&](int y0, int y1) {
        for (auto i = static_cast<std::size_t>(y0) * g.w; i < static_cast<std::size_t>(y1) * g.w; ++i)
            b[i] = static_cast<std::uint8_t>((g.v[i] > level ? 1 : 0) | (std::abs(static_cast<int>(g.v[i]) - level) > 4 ? 2 : 0));
    });
    return b;
}

/**
 * The percentile to threshold a pair at. Percentiles pick the same scene points in any exposure, but the
 * median is useless when either frame has clipped (or crushed) half of its pixels, as in wide brackets:
 * take the middle of the range that is well exposed in both.
 */
double pairPercentile(const Gray& a, const Gray& b) {
    const auto fractions = [](const Gray& g) {
        std::size_t black = 0, white = 0;
        for (const auto v : g.v) {
            black += v <= 6 ? 1U : 0U;
            white += v >= 249 ? 1U : 0U;
        }
        const auto n = static_cast<double>(std::max<std::size_t>(1, g.v.size()));
        return std::pair{static_cast<double>(black) / n, static_cast<double>(white) / n};
    };
    const auto [black_a, white_a] = fractions(a);
    const auto [black_b, white_b] = fractions(b);
    const double lo = std::max(black_a, black_b), hi = 1.0 - std::max(white_a, white_b);
    return hi > lo ? std::clamp(0.5 * (lo + hi), 0.02, 0.98) : 0.5;
}

/** Disagreeing pixels between reference and frame shifted by (dx, dy) over their overlap. */
std::uint64_t mismatch(const Bitmap& a, const Bitmap& b, int w, int h, int dx, int dy) {
    std::atomic<std::uint64_t> total{0};
    const int x0 = std::max(0, dx), x1 = std::min(w, w + dx);
    forRows(h, [&](int r0, int r1) {
        std::uint64_t count = 0;
        for (int y = std::max(r0, dy); y < std::min(r1, h + dy); ++y) {
            const auto* pa = a.data() + static_cast<std::size_t>(y) * w;
            const auto* pb = b.data() + static_cast<std::size_t>(y - dy) * w - dx;
            for (int x = x0; x < x1; ++x) {
                const unsigned both = static_cast<unsigned>(pa[x] & pb[x]);
                count += ((static_cast<unsigned>(pa[x] ^ pb[x]) & 1U) & (both >> 1U));
            }
        }
        total.fetch_add(count);
    });
    return total.load();
}

Offset alignGray(const Gray& reference, const Gray& frame, int level, double percentile) {
    Offset base;
    if (level > 0 && reference.w >= 16 && reference.h >= 16) {
        base = alignGray(halve(reference), halve(frame), level - 1, percentile);
        base.dx *= 2;
        base.dy *= 2;
    }
    const auto a = bitmapOf(reference, percentile), b = bitmapOf(frame, percentile);
    Offset best = base;
    std::uint64_t best_error = UINT64_MAX;
    for (int dy = -1; dy <= 1; ++dy)
        for (int dx = -1; dx <= 1; ++dx) {
            const int sx = base.dx + dx, sy = base.dy + dy;
            if (std::abs(sx) >= reference.w || std::abs(sy) >= reference.h) continue;
            // Normalise by the overlap so large shifts are not favoured for covering fewer pixels.
            const double overlap = static_cast<double>(reference.w - std::abs(sx)) * (reference.h - std::abs(sy));
            const auto error = static_cast<std::uint64_t>(static_cast<double>(mismatch(a, b, reference.w, reference.h, sx, sy)) *
                                                          (static_cast<double>(reference.w) * reference.h / overlap));
            if (error < best_error || (error == best_error && std::abs(sx) + std::abs(sy) < std::abs(best.dx) + std::abs(best.dy))) {
                best_error = error;
                best = {sx, sy};
            }
        }
    return best;
}

bool sameGeometry(const TileStore& a, const TileStore& b) { return a.width() == b.width() && a.height() == b.height(); }

/** Tile grid of the output (every tile, including empty ones in the frames). */
std::vector<TileCoordinate> allTiles(std::uint32_t width, std::uint32_t height) {
    std::vector<TileCoordinate> tiles;
    const auto columns = (width + kTileEdge - 1) / kTileEdge, rows = (height + kTileEdge - 1) / kTileEdge;
    tiles.reserve(static_cast<std::size_t>(columns) * rows);
    for (std::uint32_t y = 0; y < rows; ++y)
        for (std::uint32_t x = 0; x < columns; ++x) tiles.push_back({static_cast<std::int32_t>(x), static_cast<std::int32_t>(y)});
    return tiles;
}

/** Runs `work(tile, worker)` over every output tile with progress / cancellation; false when cancelled. */
template <typename Work>
bool forEachTile(const std::vector<TileCoordinate>& tiles, unsigned workers, const std::function<bool(float)>& progress, Work&& work) {
    std::atomic<bool> cancelled{false}, reported_final{false};
    std::atomic<std::size_t> done{0};
    parallel::parallelFor(tiles.size(), parallel::workerCount(workers), [&](std::size_t index, std::size_t worker) {
        if (cancelled.load()) return;
        if (!work(tiles[index])) {
            cancelled.store(true);
            return;
        }
        const auto finished = done.fetch_add(1) + 1;
        if (worker == 0 && progress) {
            if (finished == tiles.size()) reported_final.store(true);
            if (!progress(static_cast<float>(finished) / static_cast<float>(tiles.size()))) cancelled.store(true);
        }
    });
    if (!cancelled.load() && !reported_final.load() && progress && !progress(1.0F)) cancelled.store(true);
    return !cancelled.load();
}

std::vector<Offset> alignAll(std::span<const TileStore* const> frames, std::size_t reference, bool align) {
    std::vector<Offset> offsets(frames.size());
    if (!align || frames.size() < 2) return offsets;
    const auto ref = grayOf(*frames[reference]);
    for (std::size_t i = 0; i < frames.size(); ++i)
        if (i != reference) {
            const auto gray = grayOf(*frames[i]);
            offsets[i] = alignGray(ref, gray, kMaxLevels, pairPercentile(ref, gray));
        }
    return offsets;
}

}  // namespace

Offset alignMtb(const TileStore& reference, const TileStore& frame, int levels) {
    if (!sameGeometry(reference, frame) || !paint::detail::isRgba(reference.format()) || !paint::detail::isRgba(frame.format())) return {};
    const auto a = grayOf(reference), b = grayOf(frame);
    return alignGray(a, b, std::clamp(levels, 0, kMaxLevels), pairPercentile(a, b));
}

std::optional<TileStore> mergeHdr(std::span<const HdrFrame> frames, const MergeOptions& options, std::string& error) {
    if (frames.empty() || frames.size() > kMaxFrames) {
        error = "HDR merge needs 1–16 frames";
        return std::nullopt;
    }
    for (const auto& f : frames) {
        if (f.image == nullptr || !paint::detail::isRgba(f.image->format()) || !sameGeometry(*f.image, *frames[0].image)) {
            error = "HDR frames must be RGBA layers of one size";
            return std::nullopt;
        }
        if (!std::isfinite(f.exposure) || f.exposure <= 0.0F) {
            error = "HDR exposures must be positive";
            return std::nullopt;
        }
    }
    // Reference: the middle exposure (best overlap with both ends for alignment, and the output's scale).
    std::vector<std::size_t> order(frames.size());
    std::iota(order.begin(), order.end(), 0);
    std::stable_sort(order.begin(), order.end(), [&](std::size_t a, std::size_t b) { return frames[a].exposure < frames[b].exposure; });
    const std::size_t reference = order[order.size() / 2];
    std::vector<const TileStore*> images;
    for (const auto& f : frames) images.push_back(f.image);
    const auto offsets = alignAll(images, reference, options.align);
    const std::size_t darkest = order.front(), brightest = order.back();

    const auto width = frames[0].image->width(), height = frames[0].image->height();
    TileStore out(PixelFormat::RGBA32F, width, height);
    const auto tiles = allTiles(width, height);
    std::atomic<bool> failed{false};
    const bool finished = forEachTile(tiles, options.worker_threads, options.progress, [&](TileCoordinate c) {
        std::vector<develop::detail::Neighbourhood> around;
        around.reserve(frames.size());
        for (const auto& f : frames) around.emplace_back(*f.image, c.x, c.y);
        TileBuffer tile(out.tile_byte_size(), 0);
        const auto bpp = out.bytes_per_pixel();
        const std::int64_t x0 = static_cast<std::int64_t>(c.x) * kTileEdge, y0 = static_cast<std::int64_t>(c.y) * kTileEdge;
        for (int y = 0; y < static_cast<int>(kTileEdge); ++y)
            for (int x = 0; x < static_cast<int>(kTileEdge); ++x) {
                const std::int64_t gx = x0 + x, gy = y0 + y;
                if (gx >= width || gy >= height) continue;
                double sum[4] = {0, 0, 0, 0}, total = 0;
                float fallback_bright[4] = {0, 0, 0, 0}, fallback_dark[4] = {0, 0, 0, 0};
                float reference_level = 0;
                for (std::size_t i = 0; i < frames.size(); ++i) {
                    const std::int64_t sx = gx - offsets[i].dx, sy = gy - offsets[i].dy;
                    const bool inside = sx >= 0 && sy >= 0 && sx < width && sy < height;
                    if (!inside && i != reference) continue;
                    float px[4];
                    around[i].read(sx, sy, px);
                    float encoded[3], linear[3];
                    for (int k = 0; k < 3; ++k) {
                        if (frames[i].image->format() == PixelFormat::RGBA32F) {
                            linear[k] = std::isfinite(px[k]) ? std::max(0.0F, px[k]) : 0.0F;
                            encoded[k] = linearToSrgb(std::min(1.0F, linear[k]));
                        } else {
                            encoded[k] = paint::detail::unitClamp(px[k]);
                            linear[k] = decodeSrgb(encoded[k]);
                        }
                    }
                    // Hat weight on the brightest channel: clipped or near-black samples carry no information.
                    const float peak = std::max({encoded[0], encoded[1], encoded[2]});
                    const float w = smoothstep(0.02F, 0.15F, peak) * (1.0F - smoothstep(0.85F, 0.98F, peak));
                    const float scale = frames[reference].exposure / frames[i].exposure;
                    float radiance[4] = {linear[0] * scale, linear[1] * scale, linear[2] * scale, paint::detail::unitClamp(px[3])};
                    if (i == reference) reference_level = peak;
                    if (i == darkest) std::copy_n(radiance, 4, fallback_dark);
                    if (i == brightest) std::copy_n(radiance, 4, fallback_bright);
                    for (int k = 0; k < 4; ++k) sum[k] += static_cast<double>(w) * radiance[k];
                    total += w;
                }
                float value[4];
                if (total > 1e-3) {
                    for (int k = 0; k < 4; ++k) value[k] = static_cast<float>(sum[k] / total);
                } else {
                    // Every sample clipped (take the shortest exposure) or buried in black (take the longest).
                    std::copy_n(reference_level > 0.5F ? fallback_dark : fallback_bright, 4, value);
                }
                paint::detail::writePixel(out.format(), tile.data() + (static_cast<std::size_t>(y) * kTileEdge + static_cast<std::size_t>(x)) * bpp, value);
            }
        if (!out.writeTile(c, tile)) {
            failed.store(true);
            return false;
        }
        return true;
    });
    if (!finished) {
        error = failed.load() ? "could not store merged pixels" : "cancelled";
        return std::nullopt;
    }
    return out;
}

std::optional<TileStore> focusStack(std::span<const TileStore* const> frames, const FocusOptions& options, std::string& error) {
    if (frames.empty() || frames.size() > kMaxFrames) {
        error = "focus stacking needs 1–16 frames";
        return std::nullopt;
    }
    for (const auto* f : frames)
        if (f == nullptr || !paint::detail::isRgba(f->format()) || !sameGeometry(*f, *frames[0]) || f->format() != frames[0]->format()) {
            error = "focus frames must be RGBA layers of one size and format";
            return std::nullopt;
        }
    if (!std::isfinite(options.smoothing) || options.smoothing < 0.0F || options.smoothing > kMaxSmoothing) {
        error = "focus smoothing must be 0–16 px";
        return std::nullopt;
    }
    const std::size_t reference = frames.size() / 2;
    const auto offsets = alignAll(frames, reference, options.align);
    const auto format = frames[0]->format();
    const auto width = frames[0]->width(), height = frames[0]->height();
    TileStore out(format, width, height);
    const auto tiles = allTiles(width, height);
    // Region: tile + blur border + 1 px for the Laplacian (always within the neighbouring tiles).
    const int border = static_cast<int>(std::ceil(3.0F * options.smoothing)) + 1;
    const int edge = static_cast<int>(kTileEdge);
    const int rw = edge + 2 * border;
    std::atomic<bool> failed{false};
    const bool finished = forEachTile(tiles, options.worker_threads, options.progress, [&](TileCoordinate c) {
        const std::int64_t x0 = static_cast<std::int64_t>(c.x) * kTileEdge, y0 = static_cast<std::int64_t>(c.y) * kTileEdge;
        const std::size_t pixels = static_cast<std::size_t>(edge) * edge;
        std::vector<double> sum(pixels * 4, 0.0), total(pixels, 0.0);
        std::vector<float> gray(static_cast<std::size_t>(rw + 2) * (rw + 2)), energy(static_cast<std::size_t>(rw) * rw), scratch;
        std::vector<float> colour(pixels * 4);
        for (std::size_t i = 0; i < frames.size(); ++i) {
            const develop::detail::Neighbourhood around(*frames[i], c.x, c.y);
            const std::int64_t ox = x0 - border - 1 - offsets[i].dx, oy = y0 - border - 1 - offsets[i].dy;
            for (int y = 0; y < rw + 2; ++y)
                for (int x = 0; x < rw + 2; ++x) {
                    float px[4];
                    around.read(ox + x, oy + y, px);
                    const float l = 0.2126F * px[0] + 0.7152F * px[1] + 0.0722F * px[2];
                    gray[static_cast<std::size_t>(y) * (rw + 2) + x] = std::isfinite(l) ? l : 0.0F;
                    const int tx = x - border - 1, ty = y - border - 1;
                    if (tx >= 0 && ty >= 0 && tx < edge && ty < edge) std::copy_n(px, 4, colour.data() + (static_cast<std::size_t>(ty) * edge + tx) * 4);
                }
            for (int y = 0; y < rw; ++y)
                for (int x = 0; x < rw; ++x) {
                    const auto at = [&](int dx, int dy) { return gray[static_cast<std::size_t>(y + 1 + dy) * (rw + 2) + static_cast<std::size_t>(x + 1 + dx)]; };
                    const float lap = 4.0F * at(0, 0) - at(-1, 0) - at(1, 0) - at(0, -1) - at(0, 1);
                    energy[static_cast<std::size_t>(y) * rw + x] = lap * lap;
                }
            if (options.smoothing > 0.0F) develop::detail::gaussian(energy, rw, rw, options.smoothing, scratch);
            for (int y = 0; y < edge; ++y)
                for (int x = 0; x < edge; ++x) {
                    const auto p = static_cast<std::size_t>(y) * edge + x;
                    // Sharpness to a high power: the sharpest frame dominates, near-ties blend smoothly.
                    const double e = static_cast<double>(energy[static_cast<std::size_t>(y + border) * rw + static_cast<std::size_t>(x + border)]) + 1e-9;
                    const double w = e * e;
                    for (int k = 0; k < 4; ++k) sum[p * 4 + k] += w * colour[p * 4 + k];
                    total[p] += w;
                }
        }
        TileBuffer tile(out.tile_byte_size(), 0);
        const auto bpp = out.bytes_per_pixel();
        for (std::size_t p = 0; p < pixels; ++p) {
            float value[4];
            for (int k = 0; k < 4; ++k) value[k] = static_cast<float>(sum[p * 4 + k] / total[p]);
            paint::detail::writePixel(format, tile.data() + p * bpp, value);
        }
        if (!out.writeTile(c, tile)) {
            failed.store(true);
            return false;
        }
        return true;
    });
    if (!finished) {
        error = failed.load() ? "could not store stacked pixels" : "cancelled";
        return std::nullopt;
    }
    return out;
}

}  // namespace croptop::stack
