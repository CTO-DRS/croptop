// Layer transform sessions (Move / Free Transform) for engine-api TransformSession.
#include "transform_session.h"

#include <algorithm>
#include <cmath>
#include <cstring>
#include <limits>

#include "pixel_plane.h"

namespace croptop::xform {
namespace {

constexpr int kEdge = static_cast<int>(kTileEdge);

bool finiteMatrix(const Matrix& m) {
    return std::all_of(m.begin(), m.end(), [](double v) { return std::isfinite(v); });
}

bool isIdentity(const Matrix& m) {
    constexpr Matrix id{1, 0, 0, 0, 1, 0, 0, 0, 1};
    for (std::size_t i = 0; i < 9; ++i)
        if (std::abs(m[i] - id[i]) > 1e-12) return false;
    return true;
}

/** Splits the layer by the selection: floating = layer × m, remainder = layer × (1 − m) (alpha only). */
void split(const TileStore& layer, const TileStore& mask, TileStore& floating, TileStore& remainder) {
    const auto bpp = layer.bytes_per_pixel();
    for (const auto c : layer.tileCoordinates()) {
        const auto source = layer.readTile(c);
        if (!source) continue;
        const auto cov = mask.readTile(c);
        if (!cov) {
            remainder.writeTile(c, *source);
            continue;
        }
        TileBuffer f(layer.tile_byte_size(), 0), r(layer.tile_byte_size(), 0);
        bool any_f = false, any_r = false;
        for (std::size_t i = 0; i < static_cast<std::size_t>(kEdge) * kEdge; ++i) {
            std::uint16_t raw = 0;
            std::memcpy(&raw, cov->data() + i * 2, 2);
            float px[4];
            paint::detail::readPixel(layer.format(), source->data() + i * bpp, px);
            if (px[3] <= 0) continue;
            const float m = raw / 65535.0F;
            float a[4] = {px[0], px[1], px[2], px[3] * m}, b[4] = {px[0], px[1], px[2], px[3] * (1 - m)};
            if (a[3] > 0) {
                paint::detail::writePixel(layer.format(), f.data() + i * bpp, a);
                any_f = true;
            }
            if (b[3] > 0) {
                paint::detail::writePixel(layer.format(), r.data() + i * bpp, b);
                any_r = true;
            }
        }
        if (any_f) floating.writeTile(c, f);
        if (any_r) remainder.writeTile(c, r);
    }
}

std::optional<PixelRect> opaqueBounds(const TileStore& s) {
    std::int32_t x0 = std::numeric_limits<std::int32_t>::max(), y0 = x0, x1 = std::numeric_limits<std::int32_t>::min(), y1 = x1;
    const auto bpp = s.bytes_per_pixel();
    for (const auto c : s.tileCoordinates()) {
        const auto t = s.readTile(c);
        if (!t) continue;
        for (int y = 0; y < kEdge; ++y)
            for (int x = 0; x < kEdge; ++x) {
                float px[4];
                paint::detail::readPixel(s.format(), t->data() + (static_cast<std::size_t>(y) * kTileEdge + static_cast<std::size_t>(x)) * bpp, px);
                if (px[3] <= 0) continue;
                const std::int32_t gx = c.x * kEdge + x, gy = c.y * kEdge + y;
                x0 = std::min(x0, gx);
                y0 = std::min(y0, gy);
                x1 = std::max(x1, gx + 1);
                y1 = std::max(y1, gy + 1);
            }
    }
    if (x1 <= x0) return std::nullopt;
    return PixelRect{x0, y0, x1, y1};
}

/** An empty store sharing `like`'s configuration, tile pool and memory budget (a cleared COW copy). */
TileStore emptyLike(const TileStore& like) {
    TileStore out(like);
    out.clear();
    return out;
}

/** `over` composited on `under` (straight alpha, source-over), same size and format; the result takes `config`'s pool/budget. */
TileStore compositeOver(const TileStore& over, const TileStore* under, const TileStore& config) {
    TileStore out = emptyLike(config);
    if (under != nullptr)
        for (const auto c : under->tileCoordinates())
            if (const auto t = under->readTile(c)) out.writeTile(c, *t);
    const auto bpp = over.bytes_per_pixel();
    for (const auto c : over.tileCoordinates()) {
        const auto top = over.readTile(c);
        if (!top) continue;
        const auto existing = out.readTile(c);
        if (!existing) {
            out.writeTile(c, *top);
            continue;
        }
        TileBuffer tile = *existing;
        for (std::size_t i = 0; i < static_cast<std::size_t>(kEdge) * kEdge; ++i) {
            float s[4], d[4];
            paint::detail::readPixel(over.format(), top->data() + i * bpp, s);
            if (s[3] <= 0) continue;
            paint::detail::readPixel(over.format(), tile.data() + i * bpp, d);
            const float sa = paint::detail::unitClamp(s[3]), da = paint::detail::unitClamp(d[3]);
            const float oa = sa + da * (1 - sa);
            float o[4];
            for (int k = 0; k < 3; ++k) o[k] = oa > 0 ? (s[k] * sa + d[k] * da * (1 - sa)) / oa : 0.0F;
            o[3] = oa;
            paint::detail::writePixel(over.format(), tile.data() + i * bpp, o);
        }
        out.writeTile(c, tile);
    }
    return out;
}

/** Axis-aligned bounds of `r` mapped through `m` (projective), clamped to the canvas. */
std::optional<PixelRect> mappedBounds(const PixelRect& r, const Matrix& m, std::uint32_t width, std::uint32_t height) {
    double x0 = std::numeric_limits<double>::max(), y0 = x0, x1 = -x0, y1 = -x0;
    for (const auto& [x, y] : std::array<std::pair<double, double>, 4>{{{r.left, r.top}, {r.right, r.top}, {r.right, r.bottom}, {r.left, r.bottom}}}) {
        const double w = m[6] * x + m[7] * y + m[8];
        if (!(w > 0)) return PixelRect{0, 0, static_cast<std::int32_t>(width), static_cast<std::int32_t>(height)};
        const double px = (m[0] * x + m[1] * y + m[2]) / w, py = (m[3] * x + m[4] * y + m[5]) / w;
        x0 = std::min(x0, px);
        y0 = std::min(y0, py);
        x1 = std::max(x1, px);
        y1 = std::max(y1, py);
    }
    const auto clampTo = [](double v, double hi) { return static_cast<std::int32_t>(std::clamp(v, 0.0, hi)); };
    PixelRect out{clampTo(std::floor(x0) - 1, width), clampTo(std::floor(y0) - 1, height), clampTo(std::ceil(x1) + 1, width), clampTo(std::ceil(y1) + 1, height)};
    if (out.right <= out.left || out.bottom <= out.top) return std::nullopt;
    return out;
}

}  // namespace

std::unique_ptr<LayerTransform> LayerTransform::begin(const TileStore& layer, const TileStore* selection, std::string& error) {
    if (!paint::detail::isRgba(layer.format())) {
        error = "transforms apply to RGBA layers";
        return nullptr;
    }
    if (selection == nullptr) return std::unique_ptr<LayerTransform>(new LayerTransform(layer, layer, std::nullopt, opaqueBounds(layer)));
    if (selection->format() != PixelFormat::A16 || selection->width() != layer.width() || selection->height() != layer.height()) {
        error = "the selection must be an A16 mask of the layer's size";
        return nullptr;
    }
    // Both parts keep the layer's tile pool and memory budget.
    TileStore floating = emptyLike(layer), remainder = emptyLike(layer);
    split(layer, *selection, floating, remainder);
    const auto bounds = opaqueBounds(floating);
    return std::unique_ptr<LayerTransform>(new LayerTransform(layer, floating, std::move(remainder), bounds));
}

std::optional<TileStore> LayerTransform::preview(const Matrix& matrix, double scale, std::string& error, unsigned worker_threads) {
    if (!finiteMatrix(matrix) || !std::isfinite(scale) || scale <= 0 || scale > 1) {
        error = "transform preview needs a finite matrix and 0 < scale ≤ 1";
        return std::nullopt;
    }
    const auto w = static_cast<std::uint32_t>(std::ceil(floating_.width() * scale)), h = static_cast<std::uint32_t>(std::ceil(floating_.height() * scale));
    if (isIdentity(matrix)) {
        if (scale == 1.0) return original_;
        return resample::resize(original_, w, h, resample::Filter::Bilinear, error, worker_threads);
    }
    if (remainder_ && proxy_scale_ != scale) {
        proxy_remainder_.reset();
        auto scaled = resample::resize(*remainder_, w, h, resample::Filter::Bilinear, error);
        if (!scaled) return std::nullopt;
        proxy_remainder_.emplace(std::move(*scaled));
        proxy_scale_ = scale;
    }
    // Proxy = S · M: the transform in document space, then the uniform scale into the proxy.
    const Matrix sm{matrix[0] * scale, matrix[1] * scale, matrix[2] * scale, matrix[3] * scale, matrix[4] * scale, matrix[5] * scale, matrix[6], matrix[7], matrix[8]};
    auto moved = resample::warp(floating_, sm, w, h, resample::Filter::Bilinear, error, worker_threads);
    if (!moved) return std::nullopt;
    return compositeOver(*moved, proxy_remainder_ ? &*proxy_remainder_ : nullptr, *moved);
}

std::optional<Committed> LayerTransform::commit(const Matrix& matrix, std::string& error, resample::Filter filter, unsigned worker_threads) {
    if (!finiteMatrix(matrix)) {
        error = "transform matrix must be finite";
        return std::nullopt;
    }
    if (isIdentity(matrix)) return Committed{original_, floating_bounds_.value_or(PixelRect{0, 0, 0, 0})};
    std::optional<TileStore> moved;
    if (isIdentity(matrix)) {
        moved.emplace(floating_);
    } else {
        auto warped = resample::warp(floating_, matrix, floating_.width(), floating_.height(), filter, error, worker_threads);
        if (!warped) return std::nullopt;
        moved.emplace(std::move(*warped));
    }
    Committed out{compositeOver(*moved, remainder_ ? &*remainder_ : nullptr, floating_), {0, 0, 0, 0}};
    if (floating_bounds_) {
        PixelRect d = *floating_bounds_;
        if (const auto after = mappedBounds(*floating_bounds_, matrix, floating_.width(), floating_.height())) {
            d = {std::min(d.left, after->left), std::min(d.top, after->top), std::max(d.right, after->right), std::max(d.bottom, after->bottom)};
        }
        out.dirty = d;
    }
    return out;
}

}  // namespace croptop::xform
