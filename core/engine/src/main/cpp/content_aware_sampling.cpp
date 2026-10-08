#include "content_aware_sampling.h"

#include <cmath>

namespace croptop::paint {
namespace {

constexpr std::size_t kMaxStrokes = 64;
constexpr std::size_t kMaxPoints = 20000;

}  // namespace

std::optional<TileStore> applyStrokesToMask(const TileStore& like, const TileStore& base, std::span<const SamplingStroke> strokes,
                                            std::string& error) {
    if (strokes.size() > kMaxStrokes) {
        error = "too many sampling strokes";
        return std::nullopt;
    }
    std::size_t total = 0;
    for (const auto& stroke : strokes) {
        total += stroke.points.size();
        if (!std::isfinite(stroke.radius) || stroke.radius < 0.5 || stroke.radius > 4096.0) {
            error = "sampling brush size is out of range";
            return std::nullopt;
        }
        for (const auto& p : stroke.points) {
            if (!std::isfinite(p.x) || !std::isfinite(p.y)) {
                error = "sampling stroke has a non-finite point";
                return std::nullopt;
            }
        }
    }
    if (total > kMaxPoints) {
        error = "sampling strokes have too many points";
        return std::nullopt;
    }
    try {
        TileStore mask = base;
        for (const auto& stroke : strokes) {
            const auto op = stroke.add ? selection::Op::Add : selection::Op::Subtract;
            const auto stamp = [&](const TileStore& shape) {
                auto next = selection::combine(like, mask, shape, op, &error);
                if (!next) return false;
                mask = std::move(*next);
                return true;
            };
            const double r = stroke.radius;
            for (std::size_t i = 0; i < stroke.points.size(); ++i) {
                const auto& p = stroke.points[i];
                if (!stamp(selection::rasterizeEllipse(like, {p.x - r, p.y - r, p.x + r, p.y + r}, true))) return std::nullopt;
                if (i == 0) continue;
                const auto& q = stroke.points[i - 1];
                const double dx = p.x - q.x;
                const double dy = p.y - q.y;
                const double length = std::hypot(dx, dy);
                if (length < 1e-6) continue;
                const double nx = -dy / length * r;
                const double ny = dx / length * r;
                const selection::Point quad[4] = {{q.x + nx, q.y + ny}, {p.x + nx, p.y + ny}, {p.x - nx, p.y - ny}, {q.x - nx, q.y - ny}};
                if (!stamp(selection::rasterizePolygon(like, quad, true))) return std::nullopt;
            }
        }
        return mask;
    } catch (const std::exception& failure) {
        error = failure.what();
        return std::nullopt;
    }
}

std::optional<TileStore> buildSamplingMask(const TileStore& like, SamplingBase base, std::span<const SamplingStroke> strokes,
                                           std::string& error) {
    try {
        const auto start = base == SamplingBase::All ? selection::fullMask(like) : selection::emptyMask(like);
        return applyStrokesToMask(like, start, strokes, error);
    } catch (const std::exception& failure) {
        error = failure.what();
        return std::nullopt;
    }
}

}  // namespace croptop::paint
