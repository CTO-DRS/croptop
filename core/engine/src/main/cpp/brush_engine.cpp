// Incremental CPU reference for NativeEngine brush/eraser strokes.
#include "brush_engine.h"

#include <algorithm>
#include <array>
#include <cmath>
#include <cstring>
#include <limits>
#include <numbers>

#include "blend_modes.h"
#include "pixel_plane.h"

namespace croptop::brush {
namespace {

constexpr std::size_t kMaxDabs = 20'000;
constexpr std::size_t kMaxSamples = 1'000'000;
constexpr float kFullSpeed = 3000.0F;

bool validDynamics(const Dynamics& dynamics) {
    if (!std::isfinite(dynamics.minimum) || dynamics.curve.size() < 2) return false;
    float last_x = -std::numeric_limits<float>::infinity();
    for (const auto& point : dynamics.curve) {
        if (!std::isfinite(point.x) || !std::isfinite(point.y) || point.x <= last_x) return false;
        last_x = point.x;
    }
    return true;
}

bool validSettings(const Settings& settings) {
    return std::isfinite(settings.size) && settings.size >= 1.0F && settings.size <= 5000.0F &&
           std::isfinite(settings.hardness) && settings.hardness >= 0.0F && settings.hardness <= 1.0F &&
           std::isfinite(settings.opacity) && settings.opacity >= 0.0F && settings.opacity <= 1.0F &&
           std::isfinite(settings.flow) && settings.flow >= 0.0F && settings.flow <= 1.0F &&
           std::isfinite(settings.spacing) && settings.spacing >= 0.01F && settings.spacing <= 10.0F &&
           std::isfinite(settings.angle) && std::isfinite(settings.roundness) && settings.roundness > 0.0F &&
           settings.roundness <= 1.0F && std::isfinite(settings.size_jitter) && settings.size_jitter >= 0.0F &&
           settings.size_jitter <= 1.0F && std::isfinite(settings.angle_jitter) && settings.angle_jitter >= 0.0F &&
           settings.angle_jitter <= 1.0F && std::isfinite(settings.scatter) && settings.scatter >= 0.0F &&
           settings.scatter <= 10.0F && settings.count >= 1 && settings.count <= 16 &&
           validDynamics(settings.size_dynamics) && validDynamics(settings.opacity_dynamics) && validDynamics(settings.flow_dynamics);
}

void compileCurve(const std::vector<CurvePoint>& points, detail::CurveCache& cache) {
    cache.points = points;
    if (points.size() < 2) return;
    const auto n = points.size();
    cache.delta.resize(n - 1);
    cache.slope.resize(n - 1);
    cache.tangent.resize(n);
    for (std::size_t i = 0; i + 1 < n; ++i) {
        cache.delta[i] = points[i + 1].x - points[i].x;
        cache.slope[i] = (points[i + 1].y - points[i].y) / cache.delta[i];
    }
    cache.tangent.front() = cache.slope.front();
    cache.tangent.back() = cache.slope.back();
    for (std::size_t i = 1; i + 1 < n; ++i) {
        cache.tangent[i] = cache.slope[i - 1] * cache.slope[i] <= 0.0F ? 0.0F : (cache.slope[i - 1] + cache.slope[i]) * 0.5F;
    }
    for (std::size_t i = 0; i + 1 < n; ++i) {
        if (cache.slope[i] == 0.0F) {
            cache.tangent[i] = 0.0F;
            cache.tangent[i + 1] = 0.0F;
        } else {
            const float a = cache.tangent[i] / cache.slope[i];
            const float b = cache.tangent[i + 1] / cache.slope[i];
            const float h = a * a + b * b;
            if (h > 9.0F) {
                const float scale = 3.0F / std::sqrt(h);
                cache.tangent[i] = scale * a * cache.slope[i];
                cache.tangent[i + 1] = scale * b * cache.slope[i];
            }
        }
    }
}

float curveValue(const detail::CurveCache& cache, float x) {
    const auto& points = cache.points;
    if (x <= points.front().x) return points.front().y;
    if (x >= points.back().x) return points.back().y;
    const auto n = points.size();
    std::size_t segment = 0;
    while (segment + 1 < n && x > points[segment + 1].x) ++segment;
    const float t = (x - points[segment].x) / cache.delta[segment];
    const float t2 = t * t, t3 = t2 * t;
    const float h00 = 2.0F * t3 - 3.0F * t2 + 1.0F;
    const float h10 = t3 - 2.0F * t2 + t;
    const float h01 = -2.0F * t3 + 3.0F * t2;
    const float h11 = t3 - t2;
    return h00 * points[segment].y + h10 * cache.delta[segment] * cache.tangent[segment] + h01 * points[segment + 1].y +
           h11 * cache.delta[segment] * cache.tangent[segment + 1];
}

float dynamicsInput(DynamicsInput input, const Sample& sample, float speed) {
    switch (input) {
        case DynamicsInput::Off: return 1.0F;
        case DynamicsInput::Pressure: return std::clamp(sample.pressure, 0.0F, 1.0F);
        case DynamicsInput::Tilt: return 1.0F - std::clamp(std::hypot(sample.tilt_x, sample.tilt_y), 0.0F, 1.0F);
        case DynamicsInput::Velocity: return 1.0F - std::clamp(speed / kFullSpeed, 0.0F, 1.0F);
    }
    return 1.0F;
}

float factor(const Dynamics& dynamics, const detail::CurveCache& curve, const Sample& sample, float speed) {
    if (dynamics.input == DynamicsInput::Off) return 1.0F;
    const float value = std::clamp(curveValue(curve, dynamicsInput(dynamics.input, sample, speed)), 0.0F, 1.0F);
    return std::clamp(dynamics.minimum, 0.0F, 1.0F) + (1.0F - std::clamp(dynamics.minimum, 0.0F, 1.0F)) * value;
}

Sample interpolate(const Sample& a, const Sample& b, float t) {
    const auto lerp = [t](float x, float y) { return x + (y - x) * t; };
    return {lerp(a.x, b.x), lerp(a.y, b.y), lerp(a.pressure, b.pressure), lerp(a.tilt_x, b.tilt_x),
            lerp(a.tilt_y, b.tilt_y), lerp(a.orientation, b.orientation), lerp(a.time, b.time)};
}

double smoothstep(double t) {
    t = std::clamp(t, 0.0, 1.0);
    return t * t * (3.0 - 2.0 * t);
}

double srgbToLinear(double v) {
    return v <= 0.04045 ? v / 12.92 : std::pow((v + 0.055) / 1.055, 2.4);
}

}  // namespace

class DabGenerator::JavaRandom final {
public:
    explicit JavaRandom(std::int32_t seed) : seed_((static_cast<std::uint64_t>(seed) ^ kMultiplier) & kMask) {}

    float nextFloat() { return static_cast<float>(next(24)) * (1.0F / 16777216.0F); }

private:
    int next(int bits) {
        seed_ = (seed_ * kMultiplier + kAddend) & kMask;
        return static_cast<int>(seed_ >> (48 - bits));
    }

    static constexpr std::uint64_t kMultiplier = 0x5DEECE66DULL;
    static constexpr std::uint64_t kAddend = 0xBULL;
    static constexpr std::uint64_t kMask = (1ULL << 48U) - 1ULL;
    std::uint64_t seed_;
};

DabGenerator::DabGenerator(Settings settings, std::int32_t seed) : settings_(std::move(settings)), random_(std::make_unique<JavaRandom>(seed)) {
    compileCurve(settings_.size_dynamics.curve, curves_[0]);
    compileCurve(settings_.opacity_dynamics.curve, curves_[1]);
    compileCurve(settings_.flow_dynamics.curve, curves_[2]);
}
DabGenerator::~DabGenerator() = default;

bool DabGenerator::stamp(const Sample& sample, float speed, std::vector<Dab>& out, std::string& error, std::size_t max_output_dabs) {
    const float base_diameter = settings_.size * factor(settings_.size_dynamics, curves_[0], sample, speed);
    const float opacity = factor(settings_.opacity_dynamics, curves_[1], sample, speed);
    const float flow = settings_.flow * factor(settings_.flow_dynamics, curves_[2], sample, speed);
    for (int index = 0; index < settings_.count; ++index) {
        if (out.size() >= max_output_dabs) {
            error = "Brush segment exceeded its available raster queue capacity";
            return false;
        }
        if (dab_count_ >= kMaxDabs) {
            error = "Brush stroke exceeded the 20,000-dab safety limit";
            return false;
        }
        const float diameter = std::max(0.5F, base_diameter * (1.0F - settings_.size_jitter * random_->nextFloat()));
        float x = sample.x, y = sample.y;
        if (settings_.scatter > 0.0F) {
            const float radius = settings_.scatter * diameter * (random_->nextFloat() * 2.0F - 1.0F);
            if (settings_.scatter_both_axes) {
                const float angle = random_->nextFloat() * 2.0F * std::numbers::pi_v<float>;
                x += radius * std::cos(angle);
                y += radius * std::sin(angle);
            } else {
                y += radius;
            }
        }
        const float angle = settings_.angle + settings_.angle_jitter * 180.0F * (random_->nextFloat() * 2.0F - 1.0F);
        out.push_back({x, y, diameter, angle, settings_.roundness, settings_.hardness, std::clamp(opacity, 0.0F, 1.0F),
                       std::clamp(flow, 0.0F, 1.0F), settings_.opacity});
        ++dab_count_;
    }
    return true;
}

bool DabGenerator::segment(const Sample& next, std::vector<Dab>& out, std::string& error, std::size_t max_output_dabs) {
    const float dx = next.x - previous_.x, dy = next.y - previous_.y;
    const float length = std::hypot(dx, dy);
    if (length <= 0.0F) {
        previous_ = next;
        return true;
    }
    const float dt = next.time - previous_.time;
    const float speed = dt > 0.0F ? length / dt : 0.0F;
    float distance = 0.0F;
    while (true) {
        const Sample here = interpolate(previous_, next, distance / length);
        const float step = std::max(0.5F, settings_.size * factor(settings_.size_dynamics, curves_[0], here, speed) * settings_.spacing);
        const float needed = step - carry_;
        if (distance + needed > length) {
            carry_ += length - distance;
            break;
        }
        distance += needed;
        carry_ = 0.0F;
        if (!stamp(interpolate(previous_, next, distance / length), speed, out, error, max_output_dabs)) return false;
    }
    previous_ = next;
    return true;
}

bool DabGenerator::add(std::span<const Sample> samples, std::vector<Dab>& out, std::string& error, std::size_t max_output_dabs) {
    if (!validSettings(settings_)) {
        error = "brush settings are invalid";
        return false;
    }
    if (samples.size() > kMaxSamples - sample_count_) {
        error = "brush stroke has too many samples";
        return false;
    }
    for (const auto& sample : samples) {
        if (!std::isfinite(sample.x) || !std::isfinite(sample.y) || std::abs(sample.x) > 1.0e7F || std::abs(sample.y) > 1.0e7F ||
            !std::isfinite(sample.pressure) || !std::isfinite(sample.tilt_x) || !std::isfinite(sample.tilt_y) ||
            !std::isfinite(sample.orientation) || !std::isfinite(sample.time)) {
            error = "brush samples must contain finite, in-range coordinates";
            return false;
        }
        if (!has_previous_) {
            previous_ = sample;
            has_previous_ = true;
            if (!stamp(sample, 0.0F, out, error, max_output_dabs)) return false;
        } else if (!segment(sample, out, error, max_output_dabs)) {
            return false;
        }
        ++sample_count_;
    }
    return true;
}

bool stamp(TileStore& target, const Dab& dab, float red, float green, float blue, float alpha, bool erase, bool preserve_alpha,
           std::string& error) {
    if (!paint::detail::isRgba(target.format())) {
        error = "brush painting needs an RGBA target";
        return false;
    }
    if (!std::isfinite(dab.x) || !std::isfinite(dab.y) || !std::isfinite(dab.diameter) || dab.diameter <= 0.0F || dab.diameter > 10000.0F ||
        !std::isfinite(dab.angle) || std::abs(dab.x) > 1.0e7F || std::abs(dab.y) > 1.0e7F || !std::isfinite(dab.roundness) ||
        dab.roundness <= 0.0F || dab.roundness > 1.0F ||
        !std::isfinite(dab.hardness) || dab.hardness < 0.0F || dab.hardness > 1.0F || !std::isfinite(dab.opacity) || dab.opacity < 0.0F ||
        dab.opacity > 1.0F || !std::isfinite(dab.flow) || dab.flow < 0.0F || dab.flow > 1.0F ||
        !std::isfinite(dab.stroke_opacity) || dab.stroke_opacity < 0.0F || dab.stroke_opacity > 1.0F ||
        !std::isfinite(red) || !std::isfinite(green) || !std::isfinite(blue) || !std::isfinite(alpha) ||
        red < 0.0F || red > 1.0F || green < 0.0F || green > 1.0F || blue < 0.0F || blue > 1.0F || alpha < 0.0F || alpha > 1.0F) {
        error = "brush dab and color values must be finite and in range";
        return false;
    }
    if ((dab.opacity == 0.0F || dab.flow == 0.0F || dab.stroke_opacity == 0.0F) || (!erase && alpha == 0.0F) || target.width() == 0U ||
        target.height() == 0U) return true;
    if (erase && preserve_alpha) {
        error = "erasing is unavailable when transparency is locked";
        return false;
    }

    const double radius = dab.diameter * 0.5;
    const double radians = dab.angle * std::numbers::pi_v<double> / 180.0;
    const double cosine = std::cos(radians), sine = std::sin(radians);
    const double edge_pad = dab.hardness >= 1.0F ? 0.5 : 0.0;
    const double major = radius + edge_pad;
    const double minor = radius * dab.roundness + edge_pad;
    const double extent_x = std::hypot(major * cosine, minor * sine);
    const double extent_y = std::hypot(major * sine, minor * cosine);
    // Exact rotated ellipse bounds, expanded by half a pixel for hard-edge AA.
    const auto raw_x0 = static_cast<long long>(std::floor(dab.x - extent_x));
    const auto raw_y0 = static_cast<long long>(std::floor(dab.y - extent_y));
    const auto raw_x1 = static_cast<long long>(std::ceil(dab.x + extent_x)) + 1LL;
    const auto raw_y1 = static_cast<long long>(std::ceil(dab.y + extent_y)) + 1LL;
    const int x0 = static_cast<int>(std::clamp(raw_x0, 0LL, static_cast<long long>(target.width())));
    const int y0 = static_cast<int>(std::clamp(raw_y0, 0LL, static_cast<long long>(target.height())));
    const int x1 = static_cast<int>(std::clamp(raw_x1, 0LL, static_cast<long long>(target.width())));
    const int y1 = static_cast<int>(std::clamp(raw_y1, 0LL, static_cast<long long>(target.height())));
    if (x0 >= x1 || y0 >= y1) return true;

    const double hard_radius = radius * dab.hardness;
    const double feather_width = std::max(1e-6, radius - hard_radius);
    const double edge_radius = radius + edge_pad;
    std::array<float, 4> source{red, green, blue, alpha};
    if (target.format() == PixelFormat::RGBA32F) {
        source[0] = static_cast<float>(srgbToLinear(red));
        source[1] = static_cast<float>(srgbToLinear(green));
        source[2] = static_cast<float>(srgbToLinear(blue));
    }
    const auto bpp = target.bytes_per_pixel();
    const int edge = static_cast<int>(kTileEdge);
    const int tile_x0 = x0 / edge, tile_y0 = y0 / edge;
    const int tile_x1 = (x1 - 1) / edge, tile_y1 = (y1 - 1) / edge;
    for (int tile_y = tile_y0; tile_y <= tile_y1; ++tile_y) {
        for (int tile_x = tile_x0; tile_x <= tile_x1; ++tile_x) {
            const TileCoordinate coordinate{tile_x, tile_y};
            const auto old_tile = target.readTile(coordinate);
            if (erase && old_tile == nullptr) continue;
            TileBuffer tile = old_tile ? *old_tile : TileBuffer(target.tile_byte_size(), 0);
            bool changed = false;
            const int begin_x = std::max(x0, tile_x * edge), end_x = std::min(x1, (tile_x + 1) * edge);
            const int begin_y = std::max(y0, tile_y * edge), end_y = std::min(y1, (tile_y + 1) * edge);
            for (int y = begin_y; y < end_y; ++y) {
                for (int x = begin_x; x < end_x; ++x) {
                    const double px = x + 0.5 - dab.x, py = y + 0.5 - dab.y;
                    const double local_x = cosine * px + sine * py;
                    const double local_y = (-sine * px + cosine * py) / dab.roundness;
                    const double distance_squared = local_x * local_x + local_y * local_y;
                    if (distance_squared >= edge_radius * edge_radius) continue;
                    const double distance = std::sqrt(distance_squared);
                    const double coverage = edge_pad > 0.0
                        ? std::clamp(radius + 0.5 - distance, 0.0, 1.0)
                        : distance <= hard_radius ? 1.0 : 1.0 - smoothstep((distance - hard_radius) / feather_width);
                    const float amount =
                        std::clamp(dab.opacity * dab.flow * dab.stroke_opacity * static_cast<float>(coverage), 0.0F, 1.0F);
                    if (amount <= 0.0F) continue;
                    auto* pixel = tile.data() + (static_cast<std::size_t>(y - tile_y * edge) * kTileEdge + static_cast<std::size_t>(x - tile_x * edge)) * bpp;
                    std::array<float, 4> result{};
                    paint::detail::readPixel(target.format(), pixel, result.data());
                    if (erase) {
                        result[3] *= 1.0F - amount;
                        if (result[3] <= 0.0F) result = {0.0F, 0.0F, 0.0F, 0.0F};
                    } else if (preserve_alpha) {
                        if (result[3] <= 0.0F) continue;
                        const float source_amount = std::clamp(amount * alpha, 0.0F, 1.0F);
                        for (std::size_t channel = 0; channel < 3; ++channel) {
                            result[channel] += (source[channel] - result[channel]) * source_amount;
                        }
                    } else {
                        result = croptop::blendRgbaF(result, source, croptop::BlendMode::Normal, amount);
                    }
                    paint::detail::writePixel(target.format(), pixel, result.data());
                    changed = true;
                }
            }
            if (!changed) continue;
            if (std::all_of(tile.begin(), tile.end(), [](std::uint8_t value) { return value == 0; })) {
                if (!target.eraseTile(coordinate)) {
                    error = "could not release erased brush tile";
                    return false;
                }
            } else if (!target.writeTile(coordinate, tile)) {
                error = "could not store brush pixels";
                return false;
            }
        }
    }
    return true;
}

}  // namespace croptop::brush
