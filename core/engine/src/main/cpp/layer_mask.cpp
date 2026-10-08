// Layer-mask coverage cache and clipping.
#include "layer_mask.h"

#include <algorithm>
#include <atomic>
#include <cmath>
#include <cstring>
#include <vector>

#include "develop_internal.h"
#include "pixel_plane.h"

namespace croptop::layermask {
namespace {

constexpr int kEdge = static_cast<int>(kTileEdge);
constexpr float kMaxLowResSigma = 16.0F;
std::atomic<std::uint64_t> g_mask_content_revision{1};

std::uint64_t nextMaskContentRevision() {
    return g_mask_content_revision.fetch_add(1, std::memory_order_relaxed);
}

bool in(float v, float lo, float hi) { return std::isfinite(v) && v >= lo && v <= hi; }

bool valid(const MaskProperties& p, std::string& error) {
    if (!in(p.density, 0, 1) || !in(p.feather, 0, 1000)) {
        error = "mask density must be 0–1 and feather 0–1000 px";
        return false;
    }
    return true;
}

std::uint16_t quantise(float v) { return static_cast<std::uint16_t>(paint::detail::unitClamp(v) * 65535.0F + 0.5F); }

std::shared_ptr<const TileBuffer> uniform(std::uint16_t value) {
    auto tile = std::make_shared<TileBuffer>(static_cast<std::size_t>(kEdge) * kEdge * 2);
    for (std::size_t i = 0; i < static_cast<std::size_t>(kEdge) * kEdge; ++i) std::memcpy(tile->data() + i * 2, &value, 2);
    return tile;
}

/** Reduction factor (power of two) keeping the blur's σ small on the reduced copy. */
int reductionFor(float sigma) {
    int f = 1;
    while (sigma / static_cast<float>(f) > kMaxLowResSigma && f < 256) f *= 2;
    return f;
}

/** Full-resolution border around a tile (a multiple of the reduction) covering the blur footprint. */
int borderFor(float sigma, int f) {
    const int b = static_cast<int>(std::ceil(3.0F * sigma)) + 1;
    return f * ((b + f - 1) / f) + f;
}

}  // namespace

MaskCache::MaskCache(const TileStore& mask, const MaskProperties& properties, float outside, float empty)
    : properties_(properties), outside_(outside), empty_(empty), content_revision_(nextMaskContentRevision()),
      properties_revision_(nextMaskContentRevision()), default_mask_revision_(nextMaskContentRevision()) {
    mask_.emplace(mask);
}

std::unique_ptr<MaskCache> MaskCache::create(const TileStore& mask, const MaskProperties& properties, float outside, std::string& error,
                                             float empty) {
    if (mask.format() != PixelFormat::A16) {
        error = "layer masks are A16";
        return nullptr;
    }
    if (!valid(properties, error)) return nullptr;
    if (!in(outside, 0, 1)) {
        error = "mask outside value must be 0–1";
        return nullptr;
    }
    if (!in(empty, 0, 1)) {
        error = "mask empty-tile value must be 0–1";
        return nullptr;
    }
    return std::unique_ptr<MaskCache>(new MaskCache(mask, properties, outside, empty));
}

int MaskCache::reach() const {
    const float sigma = properties_.feather / 2.0F;
    if (!properties_.enabled || sigma <= 0) return 0;
    const int f = reductionFor(sigma);
    return borderFor(sigma, f) + 2 * f;
}

std::uint64_t MaskCache::contentRevision() const {
    const std::lock_guard lock(mutex_);
    return content_revision_;
}

std::uint64_t MaskCache::contentRevision(PixelRect region) const {
    const std::lock_guard lock(mutex_);
    const auto clipped_left = std::clamp(region.left, 0, static_cast<std::int32_t>(mask_->width()));
    const auto clipped_top = std::clamp(region.top, 0, static_cast<std::int32_t>(mask_->height()));
    const auto clipped_right = std::clamp(region.right, 0, static_cast<std::int32_t>(mask_->width()));
    const auto clipped_bottom = std::clamp(region.bottom, 0, static_cast<std::int32_t>(mask_->height()));
    std::uint64_t revision = properties_revision_;
    if (clipped_right <= clipped_left || clipped_bottom <= clipped_top) return revision;
    const auto tx0 = clipped_left / kEdge, ty0 = clipped_top / kEdge;
    const auto tx1 = (clipped_right - 1) / kEdge, ty1 = (clipped_bottom - 1) / kEdge;
    for (auto y = ty0; y <= ty1; ++y) {
        for (auto x = tx0; x <= tx1; ++x) {
            const auto found = mask_tile_revisions_.find({x, y});
            const auto tile_revision = found == mask_tile_revisions_.end() ? default_mask_revision_ : found->second;
            revision ^= tile_revision + 0x9e3779b97f4a7c15ULL + (revision << 6U) + (revision >> 2U);
        }
    }
    return revision;
}

std::shared_ptr<const TileBuffer> MaskCache::compute(TileCoordinate c) const {
    const auto& p = properties_;
    if (!p.enabled) return uniform(65535);
    const auto& mask = *mask_;
    const auto effective = [&](float m) { return 1.0F - p.density * (1.0F - (p.inverted ? 1.0F - m : m)); };
    const float sigma = p.feather / 2.0F;
    if (sigma <= 0) {
        const auto source = mask.readTile(c);
        if (source == nullptr) return uniform(quantise(effective(empty_)));
        auto tile = std::make_shared<TileBuffer>(static_cast<std::size_t>(kEdge) * kEdge * 2);
        for (std::size_t i = 0; i < static_cast<std::size_t>(kEdge) * kEdge; ++i) {
            std::uint16_t v = 0;
            std::memcpy(&v, source->data() + i * 2, 2);
            const auto out = quantise(effective(v / 65535.0F));
            std::memcpy(tile->data() + i * 2, &out, 2);
        }
        return tile;
    }
    const int f = reductionFor(sigma);
    const int border = borderFor(sigma, f);
    const int side = kEdge + 2 * border;  // full-resolution region side
    const int low = side / f;
    const std::int64_t x0 = static_cast<std::int64_t>(c.x) * kEdge - border, y0 = static_cast<std::int64_t>(c.y) * kEdge - border;
    const auto width = static_cast<std::int64_t>(mask.width()), height = static_cast<std::int64_t>(mask.height());
    // Reduced region: block sums of (possibly inverted) mask values; beyond the canvas counts as `outside`.
    std::vector<float> plane(static_cast<std::size_t>(low) * low, 0.0F);
    const float outside = p.inverted ? 1.0F - outside_ : outside_;
    const float empty = p.inverted ? 1.0F - empty_ : empty_;  // inside the canvas where no tile is stored
    const float per = 1.0F / static_cast<float>(f * f);
    for (int ly = 0; ly < low; ++ly)
        for (int lx = 0; lx < low; ++lx) {
            // Fast path for whole blocks outside the canvas.
            const std::int64_t bx = x0 + static_cast<std::int64_t>(lx) * f, by = y0 + static_cast<std::int64_t>(ly) * f;
            if (bx + f <= 0 || by + f <= 0 || bx >= width || by >= height) plane[static_cast<std::size_t>(ly) * low + static_cast<std::size_t>(lx)] = outside;
        }
    // Inside the canvas: walk the stored tiles overlapping the region once each.
    const std::int64_t cx0 = std::max<std::int64_t>(0, x0), cy0 = std::max<std::int64_t>(0, y0);
    const std::int64_t cx1 = std::min<std::int64_t>(width, x0 + side), cy1 = std::min<std::int64_t>(height, y0 + side);
    for (std::int64_t ty = cy0 / kEdge; ty * kEdge < cy1; ++ty)
        for (std::int64_t tx = cx0 / kEdge; tx * kEdge < cx1; ++tx) {
            const auto source = mask.readTile({static_cast<std::int32_t>(tx), static_cast<std::int32_t>(ty)});
            const std::int64_t sx0 = std::max(cx0, tx * kEdge), sx1 = std::min(cx1, (tx + 1) * kEdge);
            const std::int64_t sy0 = std::max(cy0, ty * kEdge), sy1 = std::min(cy1, (ty + 1) * kEdge);
            for (std::int64_t y = sy0; y < sy1; ++y) {
                float* row = plane.data() + static_cast<std::size_t>((y - y0) / f) * static_cast<std::size_t>(low);
                for (std::int64_t x = sx0; x < sx1; ++x) {
                    float v = empty;
                    if (source != nullptr) {
                        std::uint16_t raw = 0;
                        std::memcpy(&raw, source->data() + (static_cast<std::size_t>(y - ty * kEdge) * kTileEdge + static_cast<std::size_t>(x - tx * kEdge)) * 2, 2);
                        v = p.inverted ? 1.0F - raw / 65535.0F : raw / 65535.0F;
                    }
                    row[(x - x0) / f] += v * per;
                }
            }
        }
    // Blocks straddling the canvas edge: add the outside share of their pixels.
    if (x0 < 0 || y0 < 0 || x0 + side > width || y0 + side > height)
        for (int ly = 0; ly < low; ++ly)
            for (int lx = 0; lx < low; ++lx) {
                const std::int64_t bx = x0 + static_cast<std::int64_t>(lx) * f, by = y0 + static_cast<std::int64_t>(ly) * f;
                if (bx + f <= 0 || by + f <= 0 || bx >= width || by >= height) continue;  // wholly outside: set above
                const std::int64_t ix = std::min(width, bx + f) - std::max<std::int64_t>(0, bx), iy = std::min(height, by + f) - std::max<std::int64_t>(0, by);
                const std::int64_t inside = ix * iy;
                if (inside < static_cast<std::int64_t>(f) * f) plane[static_cast<std::size_t>(ly) * low + static_cast<std::size_t>(lx)] += outside * per * static_cast<float>(f * f - inside);
            }
    std::vector<float> scratch;
    develop::detail::gaussian(plane, low, low, sigma / static_cast<float>(f), scratch);
    auto tile = std::make_shared<TileBuffer>(static_cast<std::size_t>(kEdge) * kEdge * 2);
    for (int y = 0; y < kEdge; ++y)
        for (int x = 0; x < kEdge; ++x) {
            float v;
            if (f == 1) {
                v = plane[static_cast<std::size_t>(y + border) * low + static_cast<std::size_t>(x + border)];
            } else {
                const float u = (static_cast<float>(x + border) + 0.5F) / static_cast<float>(f) - 0.5F, w = (static_cast<float>(y + border) + 0.5F) / static_cast<float>(f) - 0.5F;
                const int iu = std::clamp(static_cast<int>(std::floor(u)), 0, low - 2), iw = std::clamp(static_cast<int>(std::floor(w)), 0, low - 2);
                const float au = u - static_cast<float>(iu), aw = w - static_cast<float>(iw);
                const float* r0 = plane.data() + static_cast<std::size_t>(iw) * low;
                const float* r1 = r0 + low;
                v = (r0[iu] * (1 - au) + r0[iu + 1] * au) * (1 - aw) + (r1[iu] * (1 - au) + r1[iu + 1] * au) * aw;
            }
            const auto out = quantise(1.0F - p.density * (1.0F - v));
            std::memcpy(tile->data() + (static_cast<std::size_t>(y) * kTileEdge + static_cast<std::size_t>(x)) * 2, &out, 2);
        }
    return tile;
}

std::shared_ptr<const TileBuffer> MaskCache::tile(TileCoordinate coordinate) {
    {
        const std::lock_guard lock(mutex_);
        if (const auto it = cache_.find(coordinate); it != cache_.end()) return it->second;
    }
    auto computed = compute(coordinate);
    const std::lock_guard lock(mutex_);
    ++computed_;
    return cache_.emplace(coordinate, std::move(computed)).first->second;
}

std::shared_ptr<const TileBuffer> MaskCache::tileAtLevel(std::uint32_t level, TileCoordinate coordinate) {
    if (level == 0) return tile(coordinate);
    if (level > 12) level = 12;  // a 4096:1 reduction of any real canvas is a single pixel
    {
        const std::lock_guard lock(mutex_);
        if (const auto levels = level_cache_.find(level); levels != level_cache_.end()) {
            if (const auto it = levels->second.find(coordinate); it != levels->second.end()) return it->second;
        }
    }
    const std::int64_t n = std::int64_t{1} << level;
    const std::int64_t first_x = static_cast<std::int64_t>(coordinate.x) * n, first_y = static_cast<std::int64_t>(coordinate.y) * n;
    const std::int64_t last_x = (static_cast<std::int64_t>(mask_->width()) - 1) / kEdge, last_y = (static_cast<std::int64_t>(mask_->height()) - 1) / kEdge;
    std::vector<std::uint32_t> sum(static_cast<std::size_t>(kEdge) * kEdge, 0);
    std::vector<std::uint32_t> count(static_cast<std::size_t>(kEdge) * kEdge, 0);
    for (std::int64_t sy = first_y; sy < first_y + n && sy <= last_y; ++sy) {
        for (std::int64_t sx = first_x; sx < first_x + n && sx <= last_x; ++sx) {
            const auto source = tile({static_cast<std::int32_t>(sx), static_cast<std::int32_t>(sy)});
            for (int y = 0; y < kEdge; ++y) {
                const std::int64_t gy = sy * kEdge + y;
                if (gy >= mask_->height()) break;
                const std::int64_t oy = gy / n - static_cast<std::int64_t>(coordinate.y) * kEdge;
                for (int x = 0; x < kEdge; ++x) {
                    const std::int64_t gx = sx * kEdge + x;
                    if (gx >= mask_->width()) break;
                    const std::int64_t ox = gx / n - static_cast<std::int64_t>(coordinate.x) * kEdge;
                    std::uint16_t raw = 0;
                    std::memcpy(&raw, source->data() + (static_cast<std::size_t>(y) * kEdge + static_cast<std::size_t>(x)) * 2, 2);
                    const auto at = static_cast<std::size_t>(oy) * kEdge + static_cast<std::size_t>(ox);
                    sum[at] += raw;
                    ++count[at];
                }
            }
        }
    }
    auto reduced = std::make_shared<TileBuffer>(static_cast<std::size_t>(kEdge) * kEdge * 2, 0);
    for (std::size_t i = 0; i < sum.size(); ++i) {
        // Pixels past the canvas edge keep the mask's outside value, like the full-resolution tiles do.
        const std::uint16_t value = count[i] == 0 ? quantise(outside_) : static_cast<std::uint16_t>((sum[i] + count[i] / 2) / count[i]);
        std::memcpy(reduced->data() + i * 2, &value, 2);
    }
    const std::lock_guard lock(mutex_);
    return level_cache_[level].emplace(coordinate, std::move(reduced)).first->second;
}

bool MaskCache::update(const TileStore& mask, PixelRect dirty, std::string& error) {
    if (mask.format() != PixelFormat::A16 || mask.width() != mask_->width() || mask.height() != mask_->height()) {
        error = "the updated mask must be an A16 mask of the same size";
        return false;
    }
    const int grow = reach();
    const std::int64_t left = static_cast<std::int64_t>(dirty.left) - grow, top = static_cast<std::int64_t>(dirty.top) - grow;
    const std::int64_t right = static_cast<std::int64_t>(dirty.right) + grow, bottom = static_cast<std::int64_t>(dirty.bottom) + grow;
    const std::lock_guard lock(mutex_);
    mask_.reset();
    mask_.emplace(mask);
    content_revision_ = nextMaskContentRevision();
    const auto clipped_left = static_cast<std::int32_t>(std::clamp<std::int64_t>(left, 0, mask_->width()));
    const auto clipped_top = static_cast<std::int32_t>(std::clamp<std::int64_t>(top, 0, mask_->height()));
    const auto clipped_right = static_cast<std::int32_t>(std::clamp<std::int64_t>(right, 0, mask_->width()));
    const auto clipped_bottom = static_cast<std::int32_t>(std::clamp<std::int64_t>(bottom, 0, mask_->height()));
    if (clipped_right > clipped_left && clipped_bottom > clipped_top) {
        const auto tile_revision = nextMaskContentRevision();
        for (auto y = clipped_top / kEdge; y <= (clipped_bottom - 1) / kEdge; ++y) {
            for (auto x = clipped_left / kEdge; x <= (clipped_right - 1) / kEdge; ++x) {
                mask_tile_revisions_[{x, y}] = tile_revision;
            }
        }
    }
    level_cache_.clear();
    for (auto it = cache_.begin(); it != cache_.end();) {
        const std::int64_t tx0 = static_cast<std::int64_t>(it->first.x) * kEdge, ty0 = static_cast<std::int64_t>(it->first.y) * kEdge;
        const bool touched = tx0 < right && tx0 + kEdge > left && ty0 < bottom && ty0 + kEdge > top;
        it = touched ? cache_.erase(it) : std::next(it);
    }
    return true;
}

bool MaskCache::setProperties(const MaskProperties& properties, std::string& error) {
    if (!valid(properties, error)) return false;
    const std::lock_guard lock(mutex_);
    properties_ = properties;
    content_revision_ = nextMaskContentRevision();
    properties_revision_ = nextMaskContentRevision();
    cache_.clear();
    level_cache_.clear();
    return true;
}

std::size_t MaskCache::cachedTiles() const {
    const std::lock_guard lock(mutex_);
    return cache_.size();
}

std::uint64_t MaskCache::computedTiles() const {
    const std::lock_guard lock(mutex_);
    return computed_;
}

void applyCoverage(PixelFormat format, TileBuffer& rgba, const TileBuffer& coverage) {
    const auto bpp = format == PixelFormat::RGBA8 ? 4U : format == PixelFormat::RGBA16 ? 8U : 16U;
    for (std::size_t i = 0; i < static_cast<std::size_t>(kEdge) * kEdge; ++i) {
        std::uint16_t raw = 0;
        std::memcpy(&raw, coverage.data() + i * 2, 2);
        if (raw == 65535) continue;
        float px[4];
        paint::detail::readPixel(format, rgba.data() + i * bpp, px);
        px[3] *= raw / 65535.0F;
        paint::detail::writePixel(format, rgba.data() + i * bpp, px);
    }
}

void clipToBase(PixelFormat format, TileBuffer& rgba, PixelFormat base_format, const TileBuffer* base) {
    const auto bpp = format == PixelFormat::RGBA8 ? 4U : format == PixelFormat::RGBA16 ? 8U : 16U;
    const auto base_bpp = base_format == PixelFormat::RGBA8 ? 4U : base_format == PixelFormat::RGBA16 ? 8U : 16U;
    for (std::size_t i = 0; i < static_cast<std::size_t>(kEdge) * kEdge; ++i) {
        float px[4], under[4] = {0, 0, 0, 0};
        if (base != nullptr) paint::detail::readPixel(base_format, base->data() + i * base_bpp, under);
        paint::detail::readPixel(format, rgba.data() + i * bpp, px);
        px[3] *= paint::detail::unitClamp(under[3]);
        paint::detail::writePixel(format, rgba.data() + i * bpp, px);
    }
}

}  // namespace croptop::layermask
