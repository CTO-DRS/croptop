#include "selection_color.h"

#include <algorithm>
#include <atomic>
#include <cmath>
#include <cstring>
#include <exception>
#include <mutex>

#include "color_lab.h"
#include "parallel_util.h"
#include "selection_mask.h"

namespace croptop::selection {
namespace {

using color::Lab;
using color::toLab;
using Rgba16 = std::array<std::uint16_t, 4>;

bool supportedSource(const TileStore& source) {
    return (source.format() == PixelFormat::RGBA8 || source.format() == PixelFormat::RGBA16 ||
            source.format() == PixelFormat::RGBA32F) &&
           source.width() > 0U && source.height() > 0U;
}

/** Reads pixel `index` (0..65535 within a tile) of any supported format as 16-bit RGBA. */
Rgba16 readPixel(const TileBuffer& tile, PixelFormat format, std::size_t index) {
    Rgba16 out{};
    switch (format) {
        case PixelFormat::RGBA8:
            for (std::size_t c = 0; c < 4; ++c) out[c] = static_cast<std::uint16_t>(tile[index * 4U + c] * 257U);
            break;
        case PixelFormat::RGBA16:
            std::memcpy(out.data(), tile.data() + index * 8U, 8U);
            break;
        case PixelFormat::RGBA32F:
            for (std::size_t c = 0; c < 4; ++c) {
                float value = 0.0F;
                std::memcpy(&value, tile.data() + index * 16U + c * 4U, 4U);
                out[c] = static_cast<std::uint16_t>(std::lround(std::clamp(std::isfinite(value) ? value : 0.0F, 0.0F, 1.0F) * 65535.0F));
            }
            break;
        default: break;
    }
    return out;
}

/** Row-oriented pixel access with a one-tile cache (flood fill walks along rows). */
class PixelAccess final {
public:
    explicit PixelAccess(const TileStore& source) : source_(source) {}
    Rgba16 at(std::uint32_t x, std::uint32_t y) {
        const TileCoordinate coordinate{static_cast<std::int32_t>(x / kTileEdge), static_cast<std::int32_t>(y / kTileEdge)};
        if (!(coordinate == cached_) || !loaded_) {
            tile_ = source_.readTile(coordinate);
            cached_ = coordinate;
            loaded_ = true;
        }
        if (tile_ == nullptr) return {};
        return readPixel(*tile_, source_.format(), static_cast<std::size_t>(y % kTileEdge) * kTileEdge + x % kTileEdge);
    }

private:
    const TileStore& source_;
    std::shared_ptr<const TileBuffer> tile_;
    TileCoordinate cached_{-1, -1};
    bool loaded_ = false;
};

/** Converts a list of set bits into an A16 mask (65535 where set). */
std::optional<TileStore> maskFromBits(const TileStore& allocation_like, const std::vector<std::uint64_t>& bits,
                                      std::string& error) {
    const auto width = allocation_like.width();
    const auto height = allocation_like.height();
    TileStore out = allocation_like.emptyLike(PixelFormat::A16);
    const auto bit = [&](std::uint64_t i) { return (bits[i / 64U] >> (i % 64U)) & 1U; };
    for (std::uint32_t ty = 0; ty * kTileEdge < height; ++ty) {
        for (std::uint32_t tx = 0; tx * kTileEdge < width; ++tx) {
            TileBuffer tile;
            const auto rows = std::min<std::uint32_t>(kTileEdge, height - ty * kTileEdge);
            const auto columns = std::min<std::uint32_t>(kTileEdge, width - tx * kTileEdge);
            for (std::uint32_t y = 0; y < rows; ++y) {
                for (std::uint32_t x = 0; x < columns; ++x) {
                    const auto index = static_cast<std::uint64_t>(ty * kTileEdge + y) * width + tx * kTileEdge + x;
                    if (!bit(index)) continue;
                    if (tile.empty()) tile.assign(out.tile_byte_size(), 0);
                    const std::uint16_t full = kFullySelected;
                    std::memcpy(tile.data() + (static_cast<std::size_t>(y) * kTileEdge + x) * 2U, &full, 2U);
                }
            }
            if (!tile.empty() && !out.writeTile({static_cast<std::int32_t>(tx), static_cast<std::int32_t>(ty)}, tile)) {
                error = "could not store Magic Wand selection tile";
                return std::nullopt;
            }
        }
    }
    return out;
}

/** 0 below `a0`, rising to 1 at `a1`, 1 until `b1`, falling to 0 at `b0`. */
double trapezoid(double v, double a0, double a1, double b1, double b0) {
    if (v <= a0 || v >= b0) return 0.0;
    if (v < a1) return (v - a0) / (a1 - a0);
    if (v <= b1) return 1.0;
    return (b0 - v) / (b0 - b1);
}

struct Hsv {
    double hue;  // degrees [0, 360)
    double saturation;
};

Hsv toHsv(double r, double g, double b) {
    const auto max = std::max({r, g, b});
    const auto min = std::min({r, g, b});
    const auto chroma = max - min;
    double hue = 0.0;
    if (chroma > 0.0) {
        if (max == r) hue = 60.0 * std::fmod((g - b) / chroma + 6.0, 6.0);
        else if (max == g) hue = 60.0 * ((b - r) / chroma + 2.0);
        else hue = 60.0 * ((r - g) / chroma + 4.0);
    }
    return {hue, max > 0.0 ? chroma / max : 0.0};
}

double hueDistance(double a, double b) {
    const auto d = std::fabs(a - b);
    return std::min(d, 360.0 - d);
}

double colorRangeCoverage(const ColorRangeSpec& spec, const std::vector<Lab>& samples, double r, double g, double b) {
    switch (spec.target) {
        case ColorRangeTarget::Sampled: {
            const auto lab = toLab(r, g, b);
            double nearest = 1e9;
            for (const auto& s : samples) {
                nearest = std::min(nearest, std::sqrt((lab.l - s.l) * (lab.l - s.l) + (lab.a - s.a) * (lab.a - s.a) +
                                                      (lab.b - s.b) * (lab.b - s.b)));
            }
            if (spec.fuzziness == 0) return nearest < 1.0 ? 1.0 : 0.0;
            const auto full = spec.fuzziness / 4.0;
            const auto zero = spec.fuzziness / 2.0;
            return nearest <= full ? 1.0 : std::max(0.0, (zero - nearest) / (zero - full));
        }
        case ColorRangeTarget::Reds:
        case ColorRangeTarget::Yellows:
        case ColorRangeTarget::Greens:
        case ColorRangeTarget::Cyans:
        case ColorRangeTarget::Blues:
        case ColorRangeTarget::Magentas: {
            const auto center = 60.0 * (static_cast<int>(spec.target) - static_cast<int>(ColorRangeTarget::Reds));
            const auto hsv = toHsv(r, g, b);
            const auto distance = hueDistance(hsv.hue, center);
            const auto hue_weight = distance <= 15.0 ? 1.0 : std::max(0.0, (45.0 - distance) / 30.0);
            const auto saturation_weight = std::clamp((hsv.saturation - 0.08) / 0.12, 0.0, 1.0);
            return hue_weight * saturation_weight;
        }
        case ColorRangeTarget::Highlights: return trapezoid(toLab(r, g, b).l / 100.0, 0.65, 0.75, 2.0, 3.0);
        case ColorRangeTarget::Midtones: return trapezoid(toLab(r, g, b).l / 100.0, 0.25, 0.35, 0.65, 0.75);
        case ColorRangeTarget::Shadows: return trapezoid(toLab(r, g, b).l / 100.0, -2.0, -1.0, 0.25, 0.35);
        case ColorRangeTarget::SkinTones: {
            const auto hsv = toHsv(r, g, b);
            const auto hue = hsv.hue > 180.0 ? hsv.hue - 360.0 : hsv.hue;
            return trapezoid(hue, 0.0, 5.0, 40.0, 50.0) * trapezoid(hsv.saturation, 0.08, 0.15, 0.65, 0.8) *
                   trapezoid(toLab(r, g, b).l / 100.0, 0.15, 0.25, 0.9, 0.97);
        }
    }
    return 0.0;
}

}  // namespace

std::optional<TileStore> magicWand(const TileStore& source, const MagicWandSpec& spec, std::string& error) {
    if (!supportedSource(source)) {
        error = "Magic Wand needs an RGBA8, RGBA16 or RGBA32F source";
        return std::nullopt;
    }
    if (spec.tolerance < 0 || spec.tolerance > 255) {
        error = "tolerance must be 0–255";
        return std::nullopt;
    }
    const auto width = source.width();
    const auto height = source.height();
    if (spec.x < 0 || spec.y < 0 || spec.x >= width || spec.y >= height) return emptyMask(source);
    const auto pixels = static_cast<std::uint64_t>(width) * height;
    std::vector<std::uint64_t> selected;
    try {
        selected.assign(static_cast<std::size_t>((pixels + 63U) / 64U), 0U);
    } catch (const std::exception&) {
        error = "could not allocate Magic Wand working set";
        return std::nullopt;
    }
    const auto tolerance = static_cast<int>(spec.tolerance) * 257;
    PixelAccess access(source);
    const auto seed = access.at(static_cast<std::uint32_t>(spec.x), static_cast<std::uint32_t>(spec.y));
    const auto matches = [&](const Rgba16& p) {
        for (std::size_t c = 0; c < 4; ++c) {
            if (std::abs(static_cast<int>(p[c]) - static_cast<int>(seed[c])) > tolerance) return false;
        }
        return true;
    };
    const auto index = [&](std::uint32_t x, std::uint32_t y) { return static_cast<std::uint64_t>(y) * width + x; };
    const auto is_set = [&](std::uint64_t i) { return ((selected[i / 64U] >> (i % 64U)) & 1U) != 0U; };
    const auto set = [&](std::uint64_t i) { selected[i / 64U] |= std::uint64_t{1} << (i % 64U); };

    if (!spec.contiguous) {
        for (std::uint32_t y = 0; y < height; ++y) {
            for (std::uint32_t x = 0; x < width; ++x) {
                if (matches(access.at(x, y))) set(index(x, y));
            }
        }
        return maskFromBits(source, selected, error);
    }
    // Scanline flood fill (4-connected). A pixel is set when it matched, so `selected` doubles as visited.
    struct Span {
        std::uint32_t y;
        std::uint32_t x_left;
        std::uint32_t x_right;  // inclusive
    };
    std::vector<Span> stack;
    const auto fill_row = [&](std::uint32_t x, std::uint32_t y) -> Span {
        auto left = x;
        auto right = x;
        set(index(x, y));
        while (left > 0U && !is_set(index(left - 1U, y)) && matches(access.at(left - 1U, y))) set(index(--left, y));
        while (right + 1U < width && !is_set(index(right + 1U, y)) && matches(access.at(right + 1U, y))) set(index(++right, y));
        return {y, left, right};
    };
    stack.push_back(fill_row(static_cast<std::uint32_t>(spec.x), static_cast<std::uint32_t>(spec.y)));
    while (!stack.empty()) {
        const auto span = stack.back();
        stack.pop_back();
        for (const int dy : {-1, 1}) {
            if ((dy < 0 && span.y == 0U) || (dy > 0 && span.y + 1U >= height)) continue;
            const auto y = static_cast<std::uint32_t>(static_cast<std::int64_t>(span.y) + dy);
            for (auto x = span.x_left; x <= span.x_right; ++x) {
                if (is_set(index(x, y)) || !matches(access.at(x, y))) continue;
                const auto filled = fill_row(x, y);
                stack.push_back(filled);
                x = filled.x_right;
            }
        }
    }
    return maskFromBits(source, selected, error);
}

std::optional<TileStore> colorRange(const TileStore& source, const ColorRangeSpec& spec, std::string& error) {
    if (!supportedSource(source)) {
        error = "Color Range needs an RGBA8, RGBA16 or RGBA32F source";
        return std::nullopt;
    }
    if (static_cast<std::uint8_t>(spec.target) > static_cast<std::uint8_t>(ColorRangeTarget::SkinTones)) {
        error = "unknown Color Range target";
        return std::nullopt;
    }
    if (spec.fuzziness < 0 || spec.fuzziness > 200) {
        error = "fuzziness must be 0–200";
        return std::nullopt;
    }
    if (spec.target == ColorRangeTarget::Sampled && spec.samples.empty()) {
        error = "Sampled Colors needs at least one sample";
        return std::nullopt;
    }
    std::vector<Lab> samples;
    for (const auto& s : spec.samples) {
        if (!std::isfinite(s[0]) || !std::isfinite(s[1]) || !std::isfinite(s[2])) {
            error = "samples must be finite";
            return std::nullopt;
        }
        samples.push_back(toLab(std::clamp(s[0], 0.0F, 1.0F), std::clamp(s[1], 0.0F, 1.0F), std::clamp(s[2], 0.0F, 1.0F)));
    }
    const auto width = source.width();
    const auto height = source.height();
    const auto columns = 1U + (width - 1U) / kTileEdge;
    const auto rows = 1U + (height - 1U) / kTileEdge;
    TileStore out = source.emptyLike(PixelFormat::A16);
    std::mutex out_mutex;
    std::atomic<bool> write_failed{false};
    parallel::parallelFor(static_cast<std::size_t>(columns) * rows, parallel::workerCount(spec.worker_threads),
                          [&](std::size_t item, std::size_t) {
        if (write_failed.load(std::memory_order_relaxed)) return;
        const auto tx = static_cast<std::uint32_t>(item % columns);
        const auto ty = static_cast<std::uint32_t>(item / columns);
        const TileCoordinate coordinate{static_cast<std::int32_t>(tx), static_cast<std::int32_t>(ty)};
        const auto tile = source.readTile(coordinate);
        if (tile == nullptr && !spec.invert) return;  // Transparent pixels are never selected.
        TileBuffer result(out.tile_byte_size(), 0);
        bool any = false;
        const auto tile_rows = std::min<std::uint32_t>(kTileEdge, height - ty * kTileEdge);
        const auto tile_columns = std::min<std::uint32_t>(kTileEdge, width - tx * kTileEdge);
        for (std::uint32_t y = 0; y < tile_rows; ++y) {
            for (std::uint32_t x = 0; x < tile_columns; ++x) {
                const auto i = static_cast<std::size_t>(y) * kTileEdge + x;
                double coverage = 0.0;
                if (tile != nullptr) {
                    const auto p = readPixel(*tile, source.format(), i);
                    const auto alpha = p[3] / 65535.0;
                    if (alpha > 0.0) {
                        coverage = alpha * colorRangeCoverage(spec, samples, p[0] / 65535.0, p[1] / 65535.0, p[2] / 65535.0);
                    }
                }
                if (spec.invert) coverage = 1.0 - coverage;
                const auto value = static_cast<std::uint16_t>(std::lround(std::clamp(coverage, 0.0, 1.0) * 65535.0));
                if (value == 0U) continue;
                std::memcpy(result.data() + i * 2U, &value, 2U);
                any = true;
            }
        }
        if (!any) return;
        const std::lock_guard lock(out_mutex);
        if (!out.writeTile(coordinate, result)) write_failed.store(true, std::memory_order_relaxed);
    });
    if (write_failed.load(std::memory_order_relaxed)) {
        error = "could not store Color Range selection tile";
        return std::nullopt;
    }
    return out;
}

}  // namespace croptop::selection
