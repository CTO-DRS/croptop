#include "selection_quick.h"

#include <algorithm>
#include <array>
#include <cmath>
#include <cstring>

#include "selection_mask.h"
#include "selection_modify.h"

namespace croptop::selection {
namespace {

constexpr std::size_t kMaxPrototypes = 64;
constexpr double kEdgeThreshold = 12.0;
constexpr std::int64_t kMaxRoiSide = 2048;

bool supportedSource(const TileStore& source) {
    return source.format() == PixelFormat::RGBA8 || source.format() == PixelFormat::RGBA16 ||
           source.format() == PixelFormat::RGBA32F;
}

/** sRGB-encoded RGBA in [0, 1] for one pixel (0 outside stored tiles). */
std::array<double, 4> readRgba(const TileStore& source, std::int64_t x, std::int64_t y,
                               std::shared_ptr<const TileBuffer>& tile, TileCoordinate& cached, bool& loaded) {
    const TileCoordinate coordinate{static_cast<std::int32_t>(x / kTileEdge), static_cast<std::int32_t>(y / kTileEdge)};
    if (!loaded || !(coordinate == cached)) {
        tile = source.readTile(coordinate);
        cached = coordinate;
        loaded = true;
    }
    std::array<double, 4> out{};
    if (tile == nullptr) return out;
    const auto index = static_cast<std::size_t>(y % kTileEdge) * kTileEdge + static_cast<std::size_t>(x % kTileEdge);
    switch (source.format()) {
        case PixelFormat::RGBA8:
            for (std::size_t c = 0; c < 4; ++c) out[c] = (*tile)[index * 4U + c] / 255.0;
            break;
        case PixelFormat::RGBA16:
            for (std::size_t c = 0; c < 4; ++c) {
                std::uint16_t v = 0;
                std::memcpy(&v, tile->data() + index * 8U + c * 2U, 2U);
                out[c] = v / 65535.0;
            }
            break;
        default:
            for (std::size_t c = 0; c < 4; ++c) {
                float v = 0.0F;
                std::memcpy(&v, tile->data() + index * 16U + c * 4U, 4U);
                out[c] = std::isfinite(v) ? std::clamp(static_cast<double>(v), 0.0, 1.0) : 0.0;
            }
            break;
    }
    return out;
}

}  // namespace

QuickSelector::QuickSelector(const TileStore& source, const TileStore& before, QuickSelectSpec spec)
    : source_(source), before_(before), stroke_(before.emptyLike(PixelFormat::A16)), current_(before),
      spec_(spec) {}

std::unique_ptr<QuickSelector> QuickSelector::begin(const TileStore& source, const TileStore& before, QuickSelectSpec spec,
                                                    std::string& error) {
    if (!supportedSource(source) || before.format() != PixelFormat::A16 || source.width() != before.width() ||
        source.height() != before.height() || source.width() == 0U || source.height() == 0U) {
        error = "Quick Selection needs an RGBA source and an A16 selection of the same size";
        return nullptr;
    }
    if (!std::isfinite(spec.diameter) || spec.diameter < 1.0F || spec.diameter > 5000.0F) {
        error = "brush size must be 1–5000 px";
        return nullptr;
    }
    return std::unique_ptr<QuickSelector>(new QuickSelector(source, before, spec));
}

bool QuickSelector::add(std::span<const float> points, std::string& error) {
    if (allocation_failed_) {
        error = "could not store Quick Selection stroke tile";
        return false;
    }
    if (points.size() % 2U != 0U) {
        error = "points must be x,y pairs";
        return false;
    }
    // Validate the complete call before touching stroke/model state. Otherwise a valid prefix
    // followed by an invalid coordinate would leave a partial dab behind after returning false.
    for (std::size_t i = 0; i < points.size(); i += 2U) {
        if (!std::isfinite(points[i]) || !std::isfinite(points[i + 1U])) {
            error = "points must be finite";
            return false;
        }
    }
    for (std::size_t i = 0; i < points.size(); i += 2U) {
        dab(points[i], points[i + 1U]);
        if (allocation_failed_) {
            error = "could not store Quick Selection stroke tile";
            return false;
        }
    }
    const auto combined = combine(before_, stroke_, spec_.subtract ? Op::Subtract : Op::Add);
    if (!combined) {
        error = "could not combine the stroke";
        return false;
    }
    current_ = std::move(*combined);
    return true;
}

void QuickSelector::dab(double cx, double cy) {
    const auto width = static_cast<std::int64_t>(source_.width());
    const auto height = static_cast<std::int64_t>(source_.height());
    const auto radius = std::max(0.5, spec_.diameter / 2.0);
    const auto reach = std::min<std::int64_t>(static_cast<std::int64_t>(std::ceil(radius * 4.0)) + 8, kMaxRoiSide / 2);
    // A dab this far outside the image cannot intersect its ROI. Check in floating point before
    // converting the caller's finite (but possibly enormous) coordinate to an integer.
    if (cx < -static_cast<double>(reach + 1) || cy < -static_cast<double>(reach + 1) ||
        cx > static_cast<double>(width + reach) || cy > static_cast<double>(height + reach)) {
        return;
    }
    const auto x0 = std::clamp<std::int64_t>(static_cast<std::int64_t>(std::floor(cx)) - reach, 0, width);
    const auto y0 = std::clamp<std::int64_t>(static_cast<std::int64_t>(std::floor(cy)) - reach, 0, height);
    const auto x1 = std::clamp<std::int64_t>(static_cast<std::int64_t>(std::floor(cx)) + reach + 1, 0, width);
    const auto y1 = std::clamp<std::int64_t>(static_cast<std::int64_t>(std::floor(cy)) + reach + 1, 0, height);
    if (x1 <= x0 || y1 <= y0) return;
    const auto roi_w = static_cast<std::size_t>(x1 - x0);
    const auto roi_h = static_cast<std::size_t>(y1 - y0);

    // Lab and alpha for the region of interest.
    std::vector<color::Lab> lab(roi_w * roi_h);
    std::vector<double> alpha(roi_w * roi_h);
    std::shared_ptr<const TileBuffer> tile;
    TileCoordinate cached{0, 0};
    bool loaded = false;
    for (std::size_t y = 0; y < roi_h; ++y) {
        for (std::size_t x = 0; x < roi_w; ++x) {
            const auto p = readRgba(source_, x0 + static_cast<std::int64_t>(x), y0 + static_cast<std::int64_t>(y), tile, cached, loaded);
            lab[y * roi_w + x] = color::toLab(p[0], p[1], p[2]);
            alpha[y * roi_w + x] = p[3];
        }
    }
    const auto in_brush = [&](std::size_t x, std::size_t y) {
        const auto dx = static_cast<double>(x0) + static_cast<double>(x) + 0.5 - cx;
        const auto dy = static_cast<double>(y0) + static_cast<double>(y) + 0.5 - cy;
        return dx * dx + dy * dy <= radius * radius;
    };

    // Update the colour model with this dab.
    color::Lab mean{0.0, 0.0, 0.0};
    std::size_t count = 0;
    for (std::size_t y = 0; y < roi_h; ++y) {
        for (std::size_t x = 0; x < roi_w; ++x) {
            if (!in_brush(x, y)) continue;
            const auto& c = lab[y * roi_w + x];
            mean.l += c.l;
            mean.a += c.a;
            mean.b += c.b;
            ++count;
        }
    }
    if (count == 0U) return;
    mean = {mean.l / count, mean.a / count, mean.b / count};
    for (std::size_t y = 0; y < roi_h; ++y) {
        for (std::size_t x = 0; x < roi_w; ++x) {
            if (!in_brush(x, y)) continue;
            const auto d = color::deltaE(lab[y * roi_w + x], mean);
            spread_sum_ += d * d;
            ++spread_count_;
        }
    }
    if (prototypes_.size() == kMaxPrototypes) prototypes_.erase(prototypes_.begin() + 1);  // Keep the first dab.
    prototypes_.push_back(mean);
    tolerance_ = std::clamp(1.5 * std::sqrt(spread_sum_ / static_cast<double>(spread_count_)) + 6.0, 6.0, 30.0);

    // Edge strength: Sobel magnitude of L*, normalized to L* units per pixel.
    const auto l_at = [&](std::int64_t x, std::int64_t y) {
        x = std::clamp<std::int64_t>(x, 0, static_cast<std::int64_t>(roi_w) - 1);
        y = std::clamp<std::int64_t>(y, 0, static_cast<std::int64_t>(roi_h) - 1);
        return lab[static_cast<std::size_t>(y) * roi_w + static_cast<std::size_t>(x)].l;
    };
    const auto edge = [&](std::size_t xs, std::size_t ys) {
        const auto x = static_cast<std::int64_t>(xs);
        const auto y = static_cast<std::int64_t>(ys);
        const auto gx = (l_at(x + 1, y - 1) + 2 * l_at(x + 1, y) + l_at(x + 1, y + 1)) -
                        (l_at(x - 1, y - 1) + 2 * l_at(x - 1, y) + l_at(x - 1, y + 1));
        const auto gy = (l_at(x - 1, y + 1) + 2 * l_at(x, y + 1) + l_at(x + 1, y + 1)) -
                        (l_at(x - 1, y - 1) + 2 * l_at(x, y - 1) + l_at(x + 1, y - 1));
        return std::sqrt(gx * gx + gy * gy) / 8.0;
    };
    const auto similar = [&](std::size_t i) {
        if (alpha[i] <= 0.0) return false;
        double best = 1e9;
        for (const auto& p : prototypes_) best = std::min(best, color::deltaE(lab[i], p));
        return best <= tolerance_;
    };

    // Grow from the brush disc.
    std::vector<std::uint8_t> state(roi_w * roi_h, 0);  // 1 = queued/selected
    std::vector<std::size_t> queue;
    for (std::size_t y = 0; y < roi_h; ++y) {
        for (std::size_t x = 0; x < roi_w; ++x) {
            if (!in_brush(x, y)) continue;
            state[y * roi_w + x] = 1;
            queue.push_back(y * roi_w + x);
        }
    }
    for (std::size_t head = 0; head < queue.size(); ++head) {
        const auto i = queue[head];
        const auto x = i % roi_w;
        const auto y = i / roi_w;
        // Strong-edge pixels are leaves: selected when their colour matches, but growth does not
        // continue through them, so similar-looking gradients cannot leak across an edge.
        if (!in_brush(x, y) && edge(x, y) >= kEdgeThreshold) continue;
        const std::array<std::pair<std::int64_t, std::int64_t>, 4> neighbours = {{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}};
        for (const auto& [dx, dy] : neighbours) {
            const auto nx = static_cast<std::int64_t>(x) + dx;
            const auto ny = static_cast<std::int64_t>(y) + dy;
            if (nx < 0 || ny < 0 || nx >= static_cast<std::int64_t>(roi_w) || ny >= static_cast<std::int64_t>(roi_h)) continue;
            const auto n = static_cast<std::size_t>(ny) * roi_w + static_cast<std::size_t>(nx);
            if (state[n] != 0U || !similar(n)) continue;
            state[n] = 1;
            queue.push_back(n);
        }
    }

    // Merge the grown region into the stroke mask.
    for (std::int64_t ty = y0 / kTileEdge; ty * static_cast<std::int64_t>(kTileEdge) < y1; ++ty) {
        for (std::int64_t tx = x0 / kTileEdge; tx * static_cast<std::int64_t>(kTileEdge) < x1; ++tx) {
            const TileCoordinate coordinate{static_cast<std::int32_t>(tx), static_cast<std::int32_t>(ty)};
            auto existing = stroke_.readTile(coordinate);
            TileBuffer merged = existing ? *existing : TileBuffer(stroke_.tile_byte_size(), 0);
            bool changed = false;
            for (std::int64_t ly = 0; ly < static_cast<std::int64_t>(kTileEdge); ++ly) {
                const auto gy = ty * kTileEdge + ly;
                if (gy < y0 || gy >= y1) continue;
                for (std::int64_t lx = 0; lx < static_cast<std::int64_t>(kTileEdge); ++lx) {
                    const auto gx = tx * kTileEdge + lx;
                    if (gx < x0 || gx >= x1) continue;
                    if (state[static_cast<std::size_t>(gy - y0) * roi_w + static_cast<std::size_t>(gx - x0)] == 0U) continue;
                    const std::uint16_t full = kFullySelected;
                    std::memcpy(merged.data() + static_cast<std::size_t>(ly * kTileEdge + lx) * 2U, &full, 2U);
                    changed = true;
                }
            }
            if (changed && !stroke_.writeTile(coordinate, merged)) {
                allocation_failed_ = true;
                return;
            }
        }
    }
}

std::optional<TileStore> QuickSelector::finish(std::string& error) const {
    if (allocation_failed_) {
        error = "could not store Quick Selection stroke tile";
        return std::nullopt;
    }
    if (!spec_.enhance_edge) return current_;
    const auto softened = feather(stroke_, 1.5F, error);
    if (!softened) return std::nullopt;
    return combine(before_, *softened, spec_.subtract ? Op::Subtract : Op::Add);
}

}  // namespace croptop::selection
