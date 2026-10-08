#include "resample.h"

#include <algorithm>
#include <array>
#include <atomic>
#include <cmath>
#include <cstring>
#include <deque>
#include <exception>
#include <vector>

#include "parallel_util.h"
#include "scratch_store.h"

namespace croptop::resample {
namespace {

bool isRgba(PixelFormat format) {
    return format == PixelFormat::RGBA8 || format == PixelFormat::RGBA16 || format == PixelFormat::RGBA32F;
}

std::size_t channelsOf(PixelFormat format) { return isRgba(format) ? 4U : 1U; }

std::uint32_t tileCount(std::uint32_t size) { return size == 0U ? 0U : 1U + (size - 1U) / kTileEdge; }

double srgbToLinear(double v) { return v <= 0.04045 ? v / 12.92 : std::pow((v + 0.055) / 1.055, 2.4); }
double linearToSrgb(double v) { return v <= 0.0031308 ? v * 12.92 : 1.055 * std::pow(v, 1.0 / 2.4) - 0.055; }

const std::array<float, 256>& decode8() {
    static const auto table = [] {
        std::array<float, 256> t{};
        for (std::size_t i = 0; i < t.size(); ++i) t[i] = static_cast<float>(srgbToLinear(i / 255.0));
        return t;
    }();
    return table;
}

const std::vector<float>& decode16() {
    static const auto table = [] {
        std::vector<float> t(65536);
        for (std::size_t i = 0; i < t.size(); ++i) t[i] = static_cast<float>(srgbToLinear(i / 65535.0));
        return t;
    }();
    return table;
}

/** Linear [0, 1] quantized to 1/65535 → sRGB byte (error far below one 8-bit code). */
const std::vector<std::uint8_t>& encode8() {
    static const auto table = [] {
        std::vector<std::uint8_t> t(65536);
        for (std::size_t i = 0; i < t.size(); ++i) {
            t[i] = static_cast<std::uint8_t>(std::lround(std::clamp(linearToSrgb(i / 65535.0), 0.0, 1.0) * 255.0));
        }
        return t;
    }();
    return table;
}

// ---- Kernels ----

double kernelRadius(Filter filter) {
    switch (filter) {
        case Filter::Nearest: return 0.5;
        case Filter::Bilinear: return 1.0;
        case Filter::Bicubic: return 2.0;
        case Filter::Lanczos3: return 3.0;
    }
    return 1.0;
}

double sinc(double x) {
    if (std::fabs(x) < 1e-8) return 1.0;
    const auto px = M_PI * x;
    return std::sin(px) / px;
}

double kernel(Filter filter, double x) {
    x = std::fabs(x);
    switch (filter) {
        case Filter::Nearest: return x < 0.5 ? 1.0 : 0.0;
        case Filter::Bilinear: return x < 1.0 ? 1.0 - x : 0.0;
        case Filter::Bicubic: {  // Catmull-Rom (a = -0.5).
            constexpr double a = -0.5;
            if (x < 1.0) return ((a + 2.0) * x - (a + 3.0)) * x * x + 1.0;
            if (x < 2.0) return ((a * x - 5.0 * a) * x + 8.0 * a) * x - 4.0 * a;
            return 0.0;
        }
        case Filter::Lanczos3: return x < 3.0 ? sinc(x) * sinc(x / 3.0) : 0.0;
    }
    return 0.0;
}

/** Kernel sampled at 4096 points per unit (linear interpolation); avoids sin() in inner loops. */
class KernelTable final {
public:
    explicit KernelTable(Filter filter) : radius_(kernelRadius(filter)) {
        values_.resize(static_cast<std::size_t>(radius_ * kSteps) + 2U);
        for (std::size_t i = 0; i < values_.size(); ++i) values_[i] = static_cast<float>(kernel(filter, static_cast<double>(i) / kSteps));
    }
    float at(double x) const {
        x = std::fabs(x) * kSteps;
        if (x >= radius_ * kSteps) return 0.0F;
        const auto i = static_cast<std::size_t>(x);
        const auto t = static_cast<float>(x - static_cast<double>(i));
        return values_[i] + (values_[i + 1U] - values_[i]) * t;
    }

private:
    static constexpr double kSteps = 4096.0;
    double radius_;
    std::vector<float> values_;
};

struct Contribution {
    std::uint32_t first = 0;
    std::vector<float> weights;
};

/** Normalized taps for each output sample; the kernel widens by the scale when minifying. */
std::vector<Contribution> contributions(std::uint32_t in, std::uint32_t out, Filter filter) {
    std::vector<Contribution> result(out);
    const double scale = static_cast<double>(in) / out;
    const double stretch = std::max(1.0, scale);
    const double support = kernelRadius(filter) * stretch;
    for (std::uint32_t o = 0; o < out; ++o) {
        const double center = (o + 0.5) * scale - 0.5;
        const auto first = static_cast<std::int64_t>(std::floor(center - support)) + 1;
        const auto last = static_cast<std::int64_t>(std::floor(center + support));
        std::vector<double> weights;
        double sum = 0.0;
        for (auto i = first; i <= last; ++i) {
            const auto w = kernel(filter, (static_cast<double>(i) - center) / stretch);
            weights.push_back(w);
            sum += w;
        }
        // Clamp indices to the edge by folding out-of-range taps onto the border sample.
        const auto clamped_first = static_cast<std::uint32_t>(std::clamp<std::int64_t>(first, 0, in - 1));
        const auto clamped_last = static_cast<std::uint32_t>(std::clamp<std::int64_t>(last, 0, in - 1));
        auto& c = result[o];
        c.first = clamped_first;
        c.weights.assign(clamped_last - clamped_first + 1U, 0.0F);
        for (std::size_t k = 0; k < weights.size(); ++k) {
            const auto index = std::clamp<std::int64_t>(first + static_cast<std::int64_t>(k), 0, in - 1);
            c.weights[static_cast<std::size_t>(index) - clamped_first] += static_cast<float>(sum != 0.0 ? weights[k] / sum : 0.0);
        }
    }
    return result;
}

// ---- Pixel conversion ----

/** One stored pixel as premultiplied linear values (4 floats for RGBA, 1 for masks). */
void decodePixel(PixelFormat format, const std::uint8_t* p, float* out) {
    switch (format) {
        case PixelFormat::RGBA8: {
            const float a = p[3] / 255.0F;
            for (int c = 0; c < 3; ++c) out[c] = decode8()[p[c]] * a;
            out[3] = a;
            break;
        }
        case PixelFormat::RGBA16: {
            std::array<std::uint16_t, 4> v{};
            std::memcpy(v.data(), p, 8);
            const float a = v[3] / 65535.0F;
            for (int c = 0; c < 3; ++c) out[c] = decode16()[v[c]] * a;
            out[3] = a;
            break;
        }
        case PixelFormat::RGBA32F: {
            std::array<float, 4> v{};
            std::memcpy(v.data(), p, 16);
            const float a = std::isfinite(v[3]) ? std::clamp(v[3], 0.0F, 1.0F) : 0.0F;
            for (int c = 0; c < 3; ++c) out[c] = (std::isfinite(v[c]) ? v[c] : 0.0F) * a;
            out[3] = a;
            break;
        }
        case PixelFormat::A8: out[0] = p[0] / 255.0F; break;
        case PixelFormat::A16: {
            std::uint16_t v = 0;
            std::memcpy(&v, p, 2);
            out[0] = v / 65535.0F;
            break;
        }
    }
}

/** Row y of `source` as premultiplied linear values (4 or 1 floats per pixel). */
void loadRow(const TileStore& source, std::uint32_t y, std::vector<float>& row) {
    const auto width = source.width();
    const auto channels = channelsOf(source.format());
    row.assign(static_cast<std::size_t>(width) * channels, 0.0F);
    const auto bpp = source.bytes_per_pixel();
    for (std::uint32_t tx = 0; tx < tileCount(width); ++tx) {
        const auto tile = source.readTile({static_cast<std::int32_t>(tx), static_cast<std::int32_t>(y / kTileEdge)});
        if (tile == nullptr) continue;
        const auto* base = tile->data() + static_cast<std::size_t>(y % kTileEdge) * kTileEdge * bpp;
        const auto columns = std::min<std::uint32_t>(kTileEdge, width - tx * kTileEdge);
        for (std::uint32_t lx = 0; lx < columns; ++lx) {
            const auto* p = base + static_cast<std::size_t>(lx) * bpp;
            auto* out = row.data() + (static_cast<std::size_t>(tx) * kTileEdge + lx) * channels;
            decodePixel(source.format(), p, out);
        }
    }
}

/** Writes one premultiplied-linear pixel into `out` in the store's format. */
void storePixel(PixelFormat format, const float* v, std::uint8_t* out) {
    const auto unit = [](float x) { return std::clamp(x, 0.0F, 1.0F); };
    if (format == PixelFormat::A8) {
        out[0] = static_cast<std::uint8_t>(std::lround(unit(v[0]) * 255.0F));
        return;
    }
    if (format == PixelFormat::A16) {
        const auto value = static_cast<std::uint16_t>(std::lround(unit(v[0]) * 65535.0F));
        std::memcpy(out, &value, 2);
        return;
    }
    float a = unit(v[3]);
    // Alpha that quantizes to zero is fully transparent: store transparent black, not un-premultiplied
    // ringing noise, so invisible pixels stay zero and empty tiles stay sparse.
    if ((format == PixelFormat::RGBA8 && std::lround(a * 255.0F) == 0) ||
        (format == PixelFormat::RGBA16 && std::lround(a * 65535.0F) == 0) || (format == PixelFormat::RGBA32F && a <= 0.0F)) {
        std::memset(out, 0, format == PixelFormat::RGBA8 ? 4U : format == PixelFormat::RGBA16 ? 8U : 16U);
        return;
    }
    std::array<float, 3> rgb{};
    for (int c = 0; c < 3; ++c) {
        // Ringing filters can overshoot; premultiplied colour may not exceed alpha for integer formats.
        const float premultiplied = format == PixelFormat::RGBA32F ? std::max(v[c], 0.0F) : std::clamp(v[c], 0.0F, a);
        rgb[static_cast<std::size_t>(c)] = a > 0.0F ? premultiplied / a : 0.0F;
    }
    switch (format) {
        case PixelFormat::RGBA8:
            for (int c = 0; c < 3; ++c) out[c] = encode8()[static_cast<std::size_t>(std::lround(unit(rgb[c]) * 65535.0F))];
            out[3] = static_cast<std::uint8_t>(std::lround(a * 255.0F));
            break;
        case PixelFormat::RGBA16: {
            std::array<std::uint16_t, 4> value{};
            for (int c = 0; c < 3; ++c) {
                value[static_cast<std::size_t>(c)] =
                    static_cast<std::uint16_t>(std::lround(std::clamp(linearToSrgb(unit(rgb[c])), 0.0, 1.0) * 65535.0));
            }
            value[3] = static_cast<std::uint16_t>(std::lround(a * 65535.0F));
            std::memcpy(out, value.data(), 8);
            break;
        }
        case PixelFormat::RGBA32F: {
            const std::array<float, 4> value{rgb[0], rgb[1], rgb[2], a};
            std::memcpy(out, value.data(), 16);
            break;
        }
        default: break;
    }
}

bool allZero(const TileBuffer& tile) {
    return std::all_of(tile.begin(), tile.end(), [](std::uint8_t b) { return b == 0U; });
}

/** Byte-exact geometric remap: out(x, y) = source(map(x, y)) or transparent. */
template <typename Map>
TileStore remap(const TileStore& source, std::uint32_t width, std::uint32_t height, Map&& map) {
    TileStore out = source.emptyLike(source.format(), width, height);
    const auto bpp = source.bytes_per_pixel();
    for (std::uint32_t ty = 0; ty < tileCount(height); ++ty) {
        for (std::uint32_t tx = 0; tx < tileCount(width); ++tx) {
            TileBuffer tile(out.tile_byte_size(), 0);
            std::shared_ptr<const TileBuffer> cached;
            TileCoordinate cached_coordinate{-1, -1};
            const auto rows = std::min<std::uint32_t>(kTileEdge, height - ty * kTileEdge);
            const auto columns = std::min<std::uint32_t>(kTileEdge, width - tx * kTileEdge);
            for (std::uint32_t ly = 0; ly < rows; ++ly) {
                for (std::uint32_t lx = 0; lx < columns; ++lx) {
                    std::int64_t sx = 0;
                    std::int64_t sy = 0;
                    if (!map(tx * kTileEdge + lx, ty * kTileEdge + ly, sx, sy)) continue;
                    if (sx < 0 || sy < 0 || sx >= source.width() || sy >= source.height()) continue;
                    const TileCoordinate c{static_cast<std::int32_t>(sx / kTileEdge), static_cast<std::int32_t>(sy / kTileEdge)};
                    if (!(c == cached_coordinate)) {
                        cached = source.readTile(c);
                        cached_coordinate = c;
                    }
                    if (cached == nullptr) continue;
                    std::memcpy(tile.data() + (static_cast<std::size_t>(ly) * kTileEdge + lx) * bpp,
                                cached->data() + (static_cast<std::size_t>(sy % kTileEdge) * kTileEdge + sx % kTileEdge) * bpp, bpp);
                }
            }
            if (!allZero(tile) && !out.writeTile({static_cast<std::int32_t>(tx), static_cast<std::int32_t>(ty)}, tile)) {
                throw std::runtime_error("could not allocate geometry output tile");
            }
        }
    }
    return out;
}

}  // namespace

std::optional<TileStore> resize(const TileStore& source, std::uint32_t width, std::uint32_t height, Filter filter,
                                std::string& error, unsigned worker_threads) {
    if (width == 0U || height == 0U || width > 65535U || height > 65535U || source.width() == 0U || source.height() == 0U) {
        error = "image size must be 1–65535 px per side";
        return std::nullopt;
    }
    if (filter == Filter::Nearest) {
        try {
            const double sx = static_cast<double>(source.width()) / width;
            const double sy = static_cast<double>(source.height()) / height;
            return remap(source, width, height, [&](std::uint32_t x, std::uint32_t y, std::int64_t& ox, std::int64_t& oy) {
                ox = std::min<std::int64_t>(static_cast<std::int64_t>((x + 0.5) * sx), source.width() - 1);
                oy = std::min<std::int64_t>(static_cast<std::int64_t>((y + 0.5) * sy), source.height() - 1);
                return true;
            });
        } catch (const std::exception&) {
            error = "could not allocate nearest-neighbor output tiles";
            return std::nullopt;
        }
    }
    std::vector<Contribution> horizontal;
    std::vector<Contribution> vertical;
    try {
        horizontal = contributions(source.width(), width, filter);
        vertical = contributions(source.height(), height, filter);
    } catch (const std::exception&) {
        error = "could not allocate resampling filters";
        return std::nullopt;
    }
    try {
        const auto channels = channelsOf(source.format());
        const auto bpp = source.bytes_per_pixel();
        TileStore out = source.emptyLike(source.format(), width, height);
        std::atomic<bool> tile_write_failed{false};
        parallel::parallelFor(tileCount(height), parallel::workerCount(worker_threads), [&](std::size_t band, std::size_t) {
            // Horizontally resampled source rows needed by this band, oldest first.
            std::deque<std::pair<std::uint32_t, std::vector<float>>> cache;
            std::vector<float> source_row;
            std::vector<float> out_row(static_cast<std::size_t>(width) * channels);
            std::vector<TileBuffer> tiles(tileCount(width), TileBuffer(out.tile_byte_size(), 0));
            const auto first_row = static_cast<std::uint32_t>(band) * kTileEdge;
            const auto last_row = std::min<std::uint32_t>(first_row + kTileEdge, height);
            const auto filtered = [&](std::uint32_t y) -> const std::vector<float>& {
                for (const auto& [index, row] : cache) {
                    if (index == y) return row;
                }
                loadRow(source, y, source_row);
                std::vector<float> row(static_cast<std::size_t>(width) * channels, 0.0F);
                for (std::uint32_t x = 0; x < width; ++x) {
                    const auto& c = horizontal[x];
                    float* dst = row.data() + static_cast<std::size_t>(x) * channels;
                    for (std::size_t k = 0; k < c.weights.size(); ++k) {
                        const float* src = source_row.data() + (static_cast<std::size_t>(c.first) + k) * channels;
                        for (std::size_t ch = 0; ch < channels; ++ch) dst[ch] += c.weights[k] * src[ch];
                    }
                }
                cache.emplace_back(y, std::move(row));
                return cache.back().second;
            };
            for (auto y = first_row; y < last_row; ++y) {
                const auto& c = vertical[y];
                while (!cache.empty() && cache.front().first < c.first) cache.pop_front();
                std::fill(out_row.begin(), out_row.end(), 0.0F);
                for (std::size_t k = 0; k < c.weights.size(); ++k) {
                    const auto& row = filtered(c.first + static_cast<std::uint32_t>(k));
                    const float w = c.weights[k];
                    for (std::size_t i = 0; i < out_row.size(); ++i) out_row[i] += w * row[i];
                }
                for (std::uint32_t x = 0; x < width; ++x) {
                    storePixel(source.format(), out_row.data() + static_cast<std::size_t>(x) * channels,
                               tiles[x / kTileEdge].data() + (static_cast<std::size_t>(y % kTileEdge) * kTileEdge + x % kTileEdge) * bpp);
                }
            }
            for (std::uint32_t tx = 0; tx < tiles.size(); ++tx) {
                if (!allZero(tiles[tx])) {
                    if (!out.writeTile({static_cast<std::int32_t>(tx), static_cast<std::int32_t>(band)}, tiles[tx])) {
                        tile_write_failed.store(true, std::memory_order_relaxed);
                    }
                }
            }
        });
        if (tile_write_failed.load(std::memory_order_relaxed)) {
            error = "could not allocate resampling output tile";
            return std::nullopt;
        }
        return out;
    } catch (const std::exception&) {
        error = "could not allocate resampling buffers";
        return std::nullopt;
    }
}

TileStore flip(const TileStore& source, bool horizontal) {
    const auto w = static_cast<std::int64_t>(source.width());
    const auto h = static_cast<std::int64_t>(source.height());
    return remap(source, source.width(), source.height(), [&](std::uint32_t x, std::uint32_t y, std::int64_t& sx, std::int64_t& sy) {
        sx = horizontal ? w - 1 - x : x;
        sy = horizontal ? y : h - 1 - y;
        return true;
    });
}

TileStore rotate90(const TileStore& source, int quarter_turns) {
    const auto turns = ((quarter_turns % 4) + 4) % 4;
    const auto w = static_cast<std::int64_t>(source.width());
    const auto h = static_cast<std::int64_t>(source.height());
    const bool swap = turns % 2 == 1;
    return remap(source, swap ? source.height() : source.width(), swap ? source.width() : source.height(),
                 [&](std::uint32_t x, std::uint32_t y, std::int64_t& sx, std::int64_t& sy) {
                     switch (turns) {
                         case 0: sx = x; sy = y; break;
                         case 1: sx = y; sy = h - 1 - x; break;          // Clockwise 90°.
                         case 2: sx = w - 1 - x; sy = h - 1 - y; break;
                         default: sx = w - 1 - y; sy = x; break;         // Counter-clockwise 90°.
                     }
                     return true;
                 });
}

std::optional<TileStore> canvasSize(const TileStore& source, std::uint32_t width, std::uint32_t height, std::int64_t dx,
                                    std::int64_t dy, std::string& error) {
    if (width == 0U || height == 0U || width > 65535U || height > 65535U) {
        error = "canvas size must be 1–65535 px per side";
        return std::nullopt;
    }
    return remap(source, width, height, [&](std::uint32_t x, std::uint32_t y, std::int64_t& sx, std::int64_t& sy) {
        sx = static_cast<std::int64_t>(x) - dx;
        sy = static_cast<std::int64_t>(y) - dy;
        return true;
    });
}

namespace {

using Matrix = std::array<double, 9>;

std::optional<Matrix> invert3(const Matrix& m) {
    const auto a = m[0], b = m[1], c = m[2], d = m[3], e = m[4], f = m[5], g = m[6], h = m[7], i = m[8];
    const auto c0 = e * i - f * h;
    const auto c1 = f * g - d * i;
    const auto c2 = d * h - e * g;
    const auto det = a * c0 + b * c1 + c * c2;
    if (!std::isfinite(det) || std::fabs(det) < 1e-12) return std::nullopt;
    const auto s = 1.0 / det;
    return Matrix{c0 * s, (c * h - b * i) * s, (b * f - c * e) * s, c1 * s, (a * i - c * g) * s, (c * d - a * f) * s,
                  c2 * s, (b * g - a * h) * s, (a * e - b * d) * s};
}

bool project(const Matrix& m, double x, double y, double& ox, double& oy) {
    const auto w = m[6] * x + m[7] * y + m[8];
    if (!(w > 1e-12)) return false;
    ox = (m[0] * x + m[1] * y + m[2]) / w;
    oy = (m[3] * x + m[4] * y + m[5]) / w;
    return std::isfinite(ox) && std::isfinite(oy);
}

/** Source pixel fetch with a small tile cache; outside the source reads as transparent. */
class Fetcher final {
public:
    explicit Fetcher(const TileStore& source) : source_(source), channels_(channelsOf(source.format())) {}

    void fetch(std::int64_t x, std::int64_t y, float* out) {
        for (std::size_t c = 0; c < channels_; ++c) out[c] = 0.0F;
        if (x < 0 || y < 0 || x >= source_.width() || y >= source_.height()) return;
        const TileCoordinate coordinate{static_cast<std::int32_t>(x / kTileEdge), static_cast<std::int32_t>(y / kTileEdge)};
        auto& slot = slots_[(static_cast<std::size_t>(coordinate.x) & 1U) | ((static_cast<std::size_t>(coordinate.y) & 1U) << 1U)];
        if (!slot.valid || !(slot.coordinate == coordinate)) {
            slot.tile = source_.readTile(coordinate);
            slot.coordinate = coordinate;
            slot.valid = true;
        }
        if (slot.tile == nullptr) return;
        decodePixel(source_.format(),
                    slot.tile->data() + (static_cast<std::size_t>(y % kTileEdge) * kTileEdge + static_cast<std::size_t>(x % kTileEdge)) *
                                            source_.bytes_per_pixel(),
                    out);
    }

private:
    struct Slot {
        TileCoordinate coordinate{0, 0};
        std::shared_ptr<const TileBuffer> tile;
        bool valid = false;
    };
    const TileStore& source_;
    std::size_t channels_;
    std::array<Slot, 4> slots_{};
};

/**
 * Decoded (premultiplied linear) copy of the source rectangle an output tile can read: the inverse image
 * of the tile's corners plus the filter support. at() returns nullptr outside it (callers fall back to
 * per-pixel fetches), and zeros for pixels outside the source.
 */
class SourceWindow final {
public:
    void build(const TileStore& source, const Matrix& inverse, std::uint32_t x0, std::uint32_t y0, std::uint32_t x1,
               std::uint32_t y1, double radius, double max_stretch, std::size_t channels) {
        valid_ = false;
        if (x1 <= x0 || y1 <= y0) return;
        double left = 1e300, top = 1e300, right = -1e300, bottom = -1e300;
        for (const auto& [px, py] : {std::pair<double, double>{x0, y0}, {x1, y0}, {x0, y1}, {x1, y1}}) {
            double sx = 0.0;
            double sy = 0.0;
            if (!project(inverse, px, py, sx, sy)) return;
            left = std::min(left, sx);
            top = std::min(top, sy);
            right = std::max(right, sx);
            bottom = std::max(bottom, sy);
        }
        const double margin = radius * max_stretch + 2.0;
        const auto clamp_to = [](double v, double limit) { return std::clamp(v, -1.0, limit + 1.0); };
        x_ = static_cast<std::int64_t>(std::floor(clamp_to(left - margin, source.width())));
        y_ = static_cast<std::int64_t>(std::floor(clamp_to(top - margin, source.height())));
        const auto x_end = static_cast<std::int64_t>(std::ceil(clamp_to(right + margin, source.width())));
        const auto y_end = static_cast<std::int64_t>(std::ceil(clamp_to(bottom + margin, source.height())));
        width_ = x_end - x_;
        height_ = y_end - y_;
        constexpr std::int64_t kMaxWindowPixels = 4'000'000;
        if (width_ <= 0 || height_ <= 0 || width_ * height_ > kMaxWindowPixels) return;
        channels_ = channels;
        data_.assign(static_cast<std::size_t>(width_ * height_) * channels, 0.0F);
        const auto bpp = source.bytes_per_pixel();
        const auto sx0 = std::max<std::int64_t>(x_, 0);
        const auto sy0 = std::max<std::int64_t>(y_, 0);
        const auto sx1 = std::min<std::int64_t>(x_end, source.width());
        const auto sy1 = std::min<std::int64_t>(y_end, source.height());
        for (auto ty = sy0 / kTileEdge; ty * static_cast<std::int64_t>(kTileEdge) < sy1; ++ty) {
            for (auto tx = sx0 / kTileEdge; tx * static_cast<std::int64_t>(kTileEdge) < sx1; ++tx) {
                const auto tile = source.readTile({static_cast<std::int32_t>(tx), static_cast<std::int32_t>(ty)});
                if (tile == nullptr) continue;
                const auto row_begin = std::max<std::int64_t>(sy0, ty * kTileEdge);
                const auto row_end = std::min<std::int64_t>(sy1, (ty + 1) * kTileEdge);
                const auto col_begin = std::max<std::int64_t>(sx0, tx * kTileEdge);
                const auto col_end = std::min<std::int64_t>(sx1, (tx + 1) * kTileEdge);
                for (auto y = row_begin; y < row_end; ++y) {
                    for (auto x = col_begin; x < col_end; ++x) {
                        decodePixel(source.format(),
                                    tile->data() + (static_cast<std::size_t>(y % kTileEdge) * kTileEdge + static_cast<std::size_t>(x % kTileEdge)) * bpp,
                                    data_.data() + (static_cast<std::size_t>(y - y_) * static_cast<std::size_t>(width_) +
                                                    static_cast<std::size_t>(x - x_)) * channels);
                    }
                }
            }
        }
        valid_ = true;
    }

    const float* at(std::int64_t x, std::int64_t y) const {
        if (!valid_ || x < x_ || y < y_ || x >= x_ + width_ || y >= y_ + height_) return nullptr;
        return data_.data() + (static_cast<std::size_t>(y - y_) * static_cast<std::size_t>(width_) + static_cast<std::size_t>(x - x_)) * channels_;
    }

private:
    bool valid_ = false;
    std::int64_t x_ = 0;
    std::int64_t y_ = 0;
    std::int64_t width_ = 0;
    std::int64_t height_ = 0;
    std::size_t channels_ = 4;
    std::vector<float> data_;
};

}  // namespace

std::optional<TileStore> warp(const TileStore& source, const std::array<double, 9>& matrix, std::uint32_t width,
                              std::uint32_t height, Filter filter, std::string& error, unsigned worker_threads) {
    if (width == 0U || height == 0U || width > 65535U || height > 65535U || source.width() == 0U || source.height() == 0U) {
        error = "output size must be 1–65535 px per side";
        return std::nullopt;
    }
    if (!std::all_of(matrix.begin(), matrix.end(), [](double v) { return std::isfinite(v); })) {
        error = "transform matrix must be finite";
        return std::nullopt;
    }
    const auto inverse = invert3(matrix);
    if (!inverse) {
        error = "transform matrix is singular";
        return std::nullopt;
    }
    double left = 1e300, top = 1e300, right = -1e300, bottom = -1e300;
    for (const auto& [cx, cy] : {std::pair<double, double>{0, 0}, {double(source.width()), 0},
                                 {0, double(source.height())}, {double(source.width()), double(source.height())}}) {
        double x = 0.0;
        double y = 0.0;
        if (!project(matrix, cx, cy, x, y)) {
            error = "perspective transform sends the layer through the horizon";
            return std::nullopt;
        }
        left = std::min(left, x);
        top = std::min(top, y);
        right = std::max(right, x);
        bottom = std::max(bottom, y);
    }
    TileStore out = source.emptyLike(source.format(), width, height);
    const auto clamp_to = [](double v, std::uint32_t limit) {
        return static_cast<std::uint32_t>(std::clamp(v, 0.0, static_cast<double>(limit)));
    };
    const auto x0 = clamp_to(std::floor(left) - 2.0, width);
    const auto y0 = clamp_to(std::floor(top) - 2.0, height);
    const auto x1 = clamp_to(std::ceil(right) + 2.0, width);
    const auto y1 = clamp_to(std::ceil(bottom) + 2.0, height);
    if (x1 <= x0 || y1 <= y0) return out;
    const auto first_tx = x0 / kTileEdge;
    const auto first_ty = y0 / kTileEdge;
    const auto columns = (x1 - 1U) / kTileEdge - first_tx + 1U;
    const auto rows = (y1 - 1U) / kTileEdge - first_ty + 1U;
    const auto channels = channelsOf(source.format());
    const auto bpp = source.bytes_per_pixel();
    const double radius = kernelRadius(filter);
    const KernelTable table(filter);
    constexpr double kMaxStretch = 16.0;
    try {
        parallel::parallelFor(static_cast<std::size_t>(columns) * rows, parallel::workerCount(worker_threads),
                              [&](std::size_t item, std::size_t) {
            const auto tx = first_tx + static_cast<std::uint32_t>(item % columns);
            const auto ty = first_ty + static_cast<std::uint32_t>(item / columns);
            Fetcher fetcher(source);
            std::vector<float> wx;
            std::vector<float> wy;
            TileBuffer tile(out.tile_byte_size(), 0);
            // Decode the source window this output tile reads once, so taps are plain array reads.
            SourceWindow window;
            if (filter != Filter::Nearest) {
                const auto tile_x0 = std::max(tx * kTileEdge, x0);
                const auto tile_y0 = std::max(ty * kTileEdge, y0);
                const auto tile_x1 = std::min(tx * kTileEdge + kTileEdge, x1);
                const auto tile_y1 = std::min(ty * kTileEdge + kTileEdge, y1);
                window.build(source, *inverse, tile_x0, tile_y0, tile_x1, tile_y1, radius, kMaxStretch, channels);
            }
            std::array<float, 4> accum{};
            std::array<float, 4> sample{};
            for (std::uint32_t ly = 0; ly < kTileEdge; ++ly) {
                const auto y = ty * kTileEdge + ly;
                if (y < y0 || y >= y1) continue;
                for (std::uint32_t lx = 0; lx < kTileEdge; ++lx) {
                    const auto x = tx * kTileEdge + lx;
                    if (x < x0 || x >= x1) continue;
                    double sx = 0.0, sy = 0.0;
                    if (!project(*inverse, x + 0.5, y + 0.5, sx, sy)) continue;
                    accum.fill(0.0F);
                    if (filter == Filter::Nearest) {
                        fetcher.fetch(static_cast<std::int64_t>(std::floor(sx)), static_cast<std::int64_t>(std::floor(sy)), accum.data());
                    } else {
                        // Local minification from the inverse map's Jacobian (finite differences).
                        double ax = 0.0, ay = 0.0, bx = 0.0, by = 0.0;
                        if (!project(*inverse, x + 1.5, y + 0.5, ax, ay) || !project(*inverse, x + 0.5, y + 1.5, bx, by)) continue;
                        const double stretch_x = std::clamp(std::max(std::fabs(ax - sx), std::fabs(bx - sx)), 1.0, kMaxStretch);
                        const double stretch_y = std::clamp(std::max(std::fabs(ay - sy), std::fabs(by - sy)), 1.0, kMaxStretch);
                        const double cx = sx - 0.5;
                        const double cy = sy - 0.5;
                        const auto fx0 = static_cast<std::int64_t>(std::floor(cx - radius * stretch_x)) + 1;
                        const auto fx1 = static_cast<std::int64_t>(std::floor(cx + radius * stretch_x));
                        const auto fy0 = static_cast<std::int64_t>(std::floor(cy - radius * stretch_y)) + 1;
                        const auto fy1 = static_cast<std::int64_t>(std::floor(cy + radius * stretch_y));
                        // Separable weights: evaluate each axis once per pixel, not once per tap.
                        wx.clear();
                        wy.clear();
                        for (auto qx = fx0; qx <= fx1; ++qx) wx.push_back(table.at((qx - cx) / stretch_x));
                        for (auto qy = fy0; qy <= fy1; ++qy) wy.push_back(table.at((qy - cy) / stretch_y));
                        double weight_sum = 0.0;
                        for (std::size_t j = 0; j < wy.size(); ++j) {
                            if (wy[j] == 0.0F) continue;
                            for (std::size_t i = 0; i < wx.size(); ++i) {
                                const float w = wy[j] * wx[i];
                                if (w == 0.0F) continue;
                                weight_sum += w;
                                const auto qx = fx0 + static_cast<std::int64_t>(i);
                                const auto qy = fy0 + static_cast<std::int64_t>(j);
                                const float* texel = window.at(qx, qy);
                                if (texel == nullptr) {
                                    fetcher.fetch(qx, qy, sample.data());
                                    texel = sample.data();
                                }
                                for (std::size_t c = 0; c < channels; ++c) accum[c] += w * texel[c];
                            }
                        }
                        if (weight_sum == 0.0) continue;
                        for (std::size_t c = 0; c < channels; ++c) accum[c] = static_cast<float>(accum[c] / weight_sum);
                    }
                    storePixel(source.format(), accum.data(), tile.data() + (static_cast<std::size_t>(ly) * kTileEdge + lx) * bpp);
                }
            }
            if (!allZero(tile)) (void)out.writeTile({static_cast<std::int32_t>(tx), static_cast<std::int32_t>(ty)}, tile);
        });
    } catch (const std::exception&) {
        error = "could not allocate warp buffers";
        return std::nullopt;
    }
    if (auto budget = out.memoryBudgetManager()) budget->trimTo(budget->budgetBytes());
    return out;
}

}  // namespace croptop::resample
