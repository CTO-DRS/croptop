#include "upright.h"

#include <algorithm>
#include <cmath>
#include <numeric>
#include <random>

#include "pixel_plane.h"

namespace croptop::geometry {
namespace {

constexpr double kPi = 3.14159265358979323846;
constexpr double kDegrees = kPi / 180.0;
using Vec3 = std::array<double, 3>;

Vec3 cross(const Vec3& a, const Vec3& b) { return {a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]}; }
double dot(const Vec3& a, const Vec3& b) { return a[0] * b[0] + a[1] * b[1] + a[2] * b[2]; }
Vec3 normalize(const Vec3& v) {
    const double n = std::sqrt(dot(v, v));
    return n > 0.0 ? Vec3{v[0] / n, v[1] / n, v[2] / n} : v;
}

Matrix3 multiply(const Matrix3& a, const Matrix3& b) {
    Matrix3 r{};
    for (int i = 0; i < 3; ++i)
        for (int j = 0; j < 3; ++j)
            for (int k = 0; k < 3; ++k) r[static_cast<std::size_t>(i * 3 + j)] += a[static_cast<std::size_t>(i * 3 + k)] * b[static_cast<std::size_t>(k * 3 + j)];
    return r;
}
Vec3 transformPoint(const Matrix3& m, const Vec3& v) {
    return {m[0] * v[0] + m[1] * v[1] + m[2] * v[2], m[3] * v[0] + m[4] * v[1] + m[5] * v[2], m[6] * v[0] + m[7] * v[1] + m[8] * v[2]};
}

/** Rotation by `angle` about unit `axis` (Rodrigues). */
Matrix3 rotation(const Vec3& axis, double angle) {
    const double c = std::cos(angle), s = std::sin(angle), t = 1.0 - c;
    const double x = axis[0], y = axis[1], z = axis[2];
    return {t * x * x + c, t * x * y - s * z, t * x * z + s * y, t * x * y + s * z, t * y * y + c, t * y * z - s * x,
            t * x * z - s * y, t * y * z + s * x, t * z * z + c};
}

/** Smallest-eigenvalue eigenvector of a symmetric 3×3 (Jacobi). */
Vec3 smallestEigenvector(std::array<double, 9> a) {
    Matrix3 v{1, 0, 0, 0, 1, 0, 0, 0, 1};
    for (int sweep = 0; sweep < 50; ++sweep) {
        double off = std::fabs(a[1]) + std::fabs(a[2]) + std::fabs(a[5]);
        if (off < 1e-15) break;
        for (int p = 0; p < 2; ++p)
            for (int q = p + 1; q < 3; ++q) {
                const auto pq = static_cast<std::size_t>(p * 3 + q), pp = static_cast<std::size_t>(p * 4), qq = static_cast<std::size_t>(q * 4);
                if (std::fabs(a[pq]) < 1e-18) continue;
                const double theta = (a[qq] - a[pp]) / (2.0 * a[pq]);
                const double t = (theta >= 0 ? 1.0 : -1.0) / (std::fabs(theta) + std::sqrt(theta * theta + 1.0));
                const double c = 1.0 / std::sqrt(t * t + 1.0), s = t * c;
                for (int k = 0; k < 3; ++k) {
                    const auto kp = static_cast<std::size_t>(k * 3 + p), kq = static_cast<std::size_t>(k * 3 + q);
                    const double akp = a[kp], akq = a[kq];
                    a[kp] = c * akp - s * akq;
                    a[kq] = s * akp + c * akq;
                }
                for (int k = 0; k < 3; ++k) {
                    const auto pk = static_cast<std::size_t>(p * 3 + k), qk = static_cast<std::size_t>(q * 3 + k);
                    const double apk = a[pk], aqk = a[qk];
                    a[pk] = c * apk - s * aqk;
                    a[qk] = s * apk + c * aqk;
                }
                for (int k = 0; k < 3; ++k) {
                    const auto kp = static_cast<std::size_t>(k * 3 + p), kq = static_cast<std::size_t>(k * 3 + q);
                    const double vkp = v[kp], vkq = v[kq];
                    v[kp] = c * vkp - s * vkq;
                    v[kq] = s * vkp + c * vkq;
                }
            }
    }
    int best = 0;
    for (int i = 1; i < 3; ++i)
        if (a[static_cast<std::size_t>(i * 4)] < a[static_cast<std::size_t>(best * 4)]) best = i;
    return normalize({v[static_cast<std::size_t>(best)], v[static_cast<std::size_t>(3 + best)], v[static_cast<std::size_t>(6 + best)]});
}

struct Camera {
    double cx, cy, f;
    Vec3 toRay(double x, double y) const { return {(x - cx) / f, (y - cy) / f, 1.0}; }
    Matrix3 K() const { return {f, 0, cx, 0, f, cy, 0, 0, 1}; }
    Matrix3 Kinv() const { return {1 / f, 0, -cx / f, 0, 1 / f, -cy / f, 0, 0, 1}; }
    /** K·R·K⁻¹, then translated so the image centre maps to itself. */
    Matrix3 homography(const Matrix3& r) const {
        Matrix3 h = multiply(K(), multiply(r, Kinv()));
        const auto c = transformPoint(h, {cx, cy, 1.0});
        if (std::fabs(c[2]) < 1e-12) return h;
        const double dx = cx - c[0] / c[2], dy = cy - c[1] / c[2];
        const Matrix3 t{1, 0, dx, 0, 1, dy, 0, 0, 1};
        return multiply(t, h);
    }
};

Camera cameraFor(std::uint32_t w, std::uint32_t h, double focal) {
    return {w / 2.0, h / 2.0, focal > 0.0 ? focal : 1.2 * std::max(w, h)};
}

/** Homogeneous line through a segment, in camera (ray) coordinates. */
Vec3 lineOf(const Camera& cam, const Segment& s) { return normalize(cross(cam.toRay(s.x1, s.y1), cam.toRay(s.x2, s.y2))); }

/** Angle between a segment and the direction from its midpoint towards vanishing point v (ray coords). */
double deviation(const Camera& cam, const Segment& s, const Vec3& v) {
    const auto m = cam.toRay((s.x1 + s.x2) / 2, (s.y1 + s.y2) / 2);
    double dx = 0, dy = 0;
    if (std::fabs(v[2]) < 1e-9) {
        dx = v[0], dy = v[1];
    } else {
        dx = v[0] / v[2] - m[0], dy = v[1] / v[2] - m[1];
    }
    const double sx = s.x2 - s.x1, sy = s.y2 - s.y1;
    const double c = std::fabs(dx * sx + dy * sy) / (std::hypot(dx, dy) * std::hypot(sx, sy) + 1e-12);
    return std::acos(std::min(1.0, c));
}

/** Length-weighted RANSAC vanishing point of `group`, refined by least squares; nullopt with < 2 segments. */
std::optional<Vec3> vanishingPoint(const Camera& cam, const std::vector<Segment>& group) {
    if (group.size() < 2) return std::nullopt;
    std::vector<Vec3> lines;
    std::vector<double> weights;
    for (const auto& s : group) {
        lines.push_back(lineOf(cam, s));
        weights.push_back(s.length());
    }
    std::mt19937 random(12345);
    std::discrete_distribution<std::size_t> pick(weights.begin(), weights.end());
    constexpr double kInlier = 2.0 * kDegrees;
    double best_score = -1.0;
    Vec3 best{0, 1, 0};
    for (int it = 0; it < 400; ++it) {
        const auto a = pick(random), b = pick(random);
        if (a == b) continue;
        const auto v = normalize(cross(lines[a], lines[b]));
        if (dot(v, v) < 0.5) continue;
        double score = 0;
        for (std::size_t i = 0; i < group.size(); ++i)
            if (deviation(cam, group[i], v) < kInlier) score += weights[i];
        if (score > best_score) {
            best_score = score;
            best = v;
        }
    }
    std::array<double, 9> m{};
    double inlier_length = 0.0;
    for (std::size_t i = 0; i < group.size(); ++i) {
        if (deviation(cam, group[i], best) >= kInlier) continue;
        inlier_length += weights[i];
        for (int r = 0; r < 3; ++r)
            for (int c = 0; c < 3; ++c) m[static_cast<std::size_t>(r * 3 + c)] += weights[i] * lines[i][static_cast<std::size_t>(r)] * lines[i][static_cast<std::size_t>(c)];
    }
    if (inlier_length <= 0.0) return std::nullopt;
    return smallestEigenvector(m);
}

/** Length-weighted mean deviation (radians, signed) of a group from vertical (or horizontal). */
double meanTilt(const std::vector<Segment>& group, bool vertical) {
    double sum = 0, weight = 0;
    for (const auto& s : group) {
        double dx = s.x2 - s.x1, dy = s.y2 - s.y1;
        if (vertical) {
            if (dy < 0) dx = -dx, dy = -dy;
            sum += std::atan2(dx, dy) * s.length();
        } else {
            if (dx < 0) dx = -dx, dy = -dy;
            sum += std::atan2(dy, dx) * s.length();
        }
        weight += s.length();
    }
    return weight > 0 ? sum / weight : 0.0;
}

void split(const std::vector<Segment>& segments, std::vector<Segment>& verticals, std::vector<Segment>& horizontals, double tolerance) {
    for (const auto& s : segments) {
        const double angle = std::atan2(std::fabs(s.y2 - s.y1), std::fabs(s.x2 - s.x1));  // 0 = horizontal
        if (angle > kPi / 2 - tolerance) verticals.push_back(s);
        else if (angle < tolerance) horizontals.push_back(s);
    }
}

/** Rotation taking unit direction `d` onto `target` (both unit, sign of d chosen towards target). */
Matrix3 align(Vec3 d, const Vec3& target) {
    if (dot(d, target) < 0) d = {-d[0], -d[1], -d[2]};
    const auto axis = cross(d, target);
    const double s = std::sqrt(dot(axis, axis)), c = dot(d, target);
    if (s < 1e-12) return {1, 0, 0, 0, 1, 0, 0, 0, 1};
    return rotation(normalize(axis), std::atan2(s, c));
}

double angleOf(const Matrix3& r) { return std::acos(std::clamp((r[0] + r[4] + r[8] - 1.0) / 2.0, -1.0, 1.0)); }

/** Luminance (composited on white) of the image reduced by 2^level, as a dense float plane. */
std::vector<float> luminance(const TileStore& image, std::uint32_t level, std::uint32_t& w, std::uint32_t& h) {
    w = image.mip_width(level);
    h = image.mip_height(level);
    std::vector<float> out(static_cast<std::size_t>(w) * h, 1.0F);
    const auto bpp = image.bytes_per_pixel();
    for (std::uint32_t ty = 0; ty * kTileEdge < h; ++ty)
        for (std::uint32_t tx = 0; tx * kTileEdge < w; ++tx) {
            const TileCoordinate c{static_cast<std::int32_t>(tx), static_cast<std::int32_t>(ty)};
            const auto tile = level == 0 ? image.readTile(c) : image.readMipTile(level, c);
            if (!tile) continue;
            for (std::uint32_t y = 0; y < kTileEdge && ty * kTileEdge + y < h; ++y)
                for (std::uint32_t x = 0; x < kTileEdge && tx * kTileEdge + x < w; ++x) {
                    std::array<float, 4> px{};
                    paint::detail::readPixel(image.format(), tile->data() + (static_cast<std::size_t>(y) * kTileEdge + x) * bpp, px.data());
                    const float l = 0.2126F * px[0] + 0.7152F * px[1] + 0.0722F * px[2];
                    out[static_cast<std::size_t>(ty * kTileEdge + y) * w + tx * kTileEdge + x] = std::clamp(l, 0.0F, 1.0F) * px[3] + (1.0F - px[3]);
                }
        }
    return out;
}

}  // namespace

double Segment::length() const { return std::hypot(x2 - x1, y2 - y1); }

std::vector<Segment> detectSegments(const TileStore& image, std::string& error) {
    if (!paint::detail::isRgba(image.format()) || image.width() < 8 || image.height() < 8) {
        error = "upright needs an RGBA image of at least 8 × 8 px";
        return {};
    }
    std::uint32_t level = 0;
    while (level < 12 && std::max(image.mip_width(level), image.mip_height(level)) > 1200) ++level;
    std::uint32_t w = 0, h = 0;
    const auto lum = luminance(image, level, w, h);
    const double scale = static_cast<double>(image.width()) / w;
    const std::size_t n = static_cast<std::size_t>(w) * h;
    std::vector<float> magnitude(n, 0.0F), angle(n, 0.0F);
    float peak = 0.0F;
    for (std::uint32_t y = 1; y + 1 < h; ++y)
        for (std::uint32_t x = 1; x + 1 < w; ++x) {
            const auto at = [&](std::uint32_t xx, std::uint32_t yy) { return lum[static_cast<std::size_t>(yy) * w + xx]; };
            const float gx = (at(x + 1, y - 1) + 2 * at(x + 1, y) + at(x + 1, y + 1)) - (at(x - 1, y - 1) + 2 * at(x - 1, y) + at(x - 1, y + 1));
            const float gy = (at(x - 1, y + 1) + 2 * at(x, y + 1) + at(x + 1, y + 1)) - (at(x - 1, y - 1) + 2 * at(x, y - 1) + at(x + 1, y - 1));
            const auto i = static_cast<std::size_t>(y) * w + x;
            magnitude[i] = std::hypot(gx, gy);
            angle[i] = std::atan2(gx, -gy);  // level-line direction
            peak = std::max(peak, magnitude[i]);
        }
    const float threshold = std::max(0.1F, 0.08F * peak);
    // Seeds in decreasing gradient order (bucketed).
    constexpr int kBins = 1024;
    std::vector<std::vector<std::uint32_t>> bins(kBins);
    for (std::size_t i = 0; i < n; ++i)
        if (magnitude[i] > threshold) bins[static_cast<std::size_t>(std::min(kBins - 1, static_cast<int>(magnitude[i] / peak * (kBins - 1))))].push_back(static_cast<std::uint32_t>(i));
    std::vector<std::uint8_t> used(n, 0);
    std::vector<Segment> segments;
    const double diagonal = std::hypot(w, h);
    std::vector<std::uint32_t> region, stack;
    constexpr float kTolerance = static_cast<float>(22.5 * kDegrees);
    for (int b = kBins - 1; b >= 0; --b) {
        for (const auto seed : bins[static_cast<std::size_t>(b)]) {
            if (used[seed]) continue;
            region.clear();
            stack.assign(1, seed);
            used[seed] = 1;
            double sum_c = std::cos(angle[seed]), sum_s = std::sin(angle[seed]);
            float region_angle = angle[seed];
            while (!stack.empty()) {
                const auto i = stack.back();
                stack.pop_back();
                region.push_back(i);
                const std::uint32_t x = i % w, y = i / w;
                for (int dy = -1; dy <= 1; ++dy)
                    for (int dx = -1; dx <= 1; ++dx) {
                        if (!dx && !dy) continue;
                        const auto nx = static_cast<std::int64_t>(x) + dx, ny = static_cast<std::int64_t>(y) + dy;
                        if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue;
                        const auto j = static_cast<std::size_t>(ny) * w + static_cast<std::size_t>(nx);
                        if (used[j] || magnitude[j] <= threshold) continue;
                        float d = std::fabs(angle[j] - region_angle);
                        if (d > static_cast<float>(kPi)) d = static_cast<float>(2 * kPi) - d;
                        if (d > kTolerance) continue;
                        used[j] = 1;
                        stack.push_back(static_cast<std::uint32_t>(j));
                        sum_c += std::cos(angle[j]);
                        sum_s += std::sin(angle[j]);
                        region_angle = static_cast<float>(std::atan2(sum_s, sum_c));
                    }
            }
            if (region.size() < 12) continue;
            // Weighted PCA.
            double sw = 0, mx = 0, my = 0;
            for (const auto i : region) {
                const double wgt = magnitude[i];
                sw += wgt, mx += wgt * (i % w), my += wgt * (i / w);
            }
            mx /= sw, my /= sw;
            double sxx = 0, syy = 0, sxy = 0;
            for (const auto i : region) {
                const double wgt = magnitude[i], dx = (i % w) - mx, dy = (i / w) - my;
                sxx += wgt * dx * dx, syy += wgt * dy * dy, sxy += wgt * dx * dy;
            }
            const double theta = 0.5 * std::atan2(2 * sxy, sxx - syy);
            const double ux = std::cos(theta), uy = std::sin(theta);
            double lo = 1e30, hi = -1e30, wlo = 1e30, whi = -1e30;
            for (const auto i : region) {
                const double dx = (i % w) - mx, dy = (i / w) - my;
                const double along = dx * ux + dy * uy, across = -dx * uy + dy * ux;
                lo = std::min(lo, along), hi = std::max(hi, along), wlo = std::min(wlo, across), whi = std::max(whi, across);
            }
            const double length = hi - lo, width = whi - wlo + 1.0;
            if (length < std::max(10.0, 0.02 * diagonal) || length < 5.0 * width) continue;
            segments.push_back({(mx + 0.5 + lo * ux) * scale, (my + 0.5 + lo * uy) * scale, (mx + 0.5 + hi * ux) * scale, (my + 0.5 + hi * uy) * scale});
        }
    }
    return segments;
}

UprightResult uprightFromSegments(std::uint32_t width, std::uint32_t height, const std::vector<Segment>& segments, const UprightSpec& spec) {
    UprightResult result;
    const auto cam = cameraFor(width, height, spec.focal_length_px);
    const auto& source = spec.mode == UprightMode::Guided ? spec.guides : segments;
    result.segments = source.size();
    std::vector<Segment> verticals, horizontals;
    split(source, verticals, horizontals, (spec.mode == UprightMode::Guided ? 45.0 : 30.0) * kDegrees);
    const auto vertical = vanishingPoint(cam, verticals);
    const auto horizontal = vanishingPoint(cam, horizontals);
    result.vertical_found = vertical.has_value();
    result.horizontal_found = horizontal.has_value();
    Matrix3 r{1, 0, 0, 0, 1, 0, 0, 0, 1};
    const auto level = [&]() {
        double total_v = 0, total_h = 0;
        for (const auto& s : verticals) total_v += s.length();
        for (const auto& s : horizontals) total_h += s.length();
        if (total_v == 0 && total_h == 0) return Matrix3{1, 0, 0, 0, 1, 0, 0, 0, 1};
        // With y down, a positive rotation about the view axis turns content clockwise. A positive vertical tilt
        // (going down, x grows) leans counter-clockwise, so it needs +tilt; a positive horizontal tilt (going
        // right, y grows) leans clockwise, so it needs −tilt.
        const double a = total_v >= total_h ? meanTilt(verticals, true) : -meanTilt(horizontals, false);
        return rotation({0, 0, 1}, a);
    };
    UprightMode mode = spec.mode;
    if (mode == UprightMode::Guided) mode = vertical ? (horizontal ? UprightMode::Full : UprightMode::Vertical) : UprightMode::Level;
    if (mode == UprightMode::Auto) {
        if (vertical && angleOf(align(*vertical, {0, 1, 0})) <= 25.0 * kDegrees) {
            mode = UprightMode::Vertical;
        } else {
            mode = UprightMode::Level;
            result.note = vertical ? "vertical correction exceeds 25°; levelled instead" : "no vertical lines found; levelled instead";
        }
    }
    if ((mode == UprightMode::Vertical || mode == UprightMode::Full) && !vertical) {
        result.note = "not enough vertical lines; levelled instead";
        mode = UprightMode::Level;
    }
    switch (mode) {
        case UprightMode::Level: r = level(); break;
        case UprightMode::Vertical:
        case UprightMode::Full: {
            r = align(*vertical, {0, 1, 0});
            if (mode == UprightMode::Full) {
                if (horizontal) {
                    auto d = transformPoint(r, *horizontal);
                    d[1] = 0.0;
                    if (dot(d, d) > 1e-12) r = multiply(align(normalize(d), {1, 0, 0}), r);
                } else {
                    result.note = "not enough horizontal lines; verticals corrected only";
                }
            }
            break;
        }
        default: break;
    }
    result.tilt_degrees = angleOf(r) / kDegrees;
    result.matrix = cam.homography(r);
    return result;
}

std::optional<UprightResult> upright(const TileStore& image, const UprightSpec& spec, std::string& error) {
    if (!paint::detail::isRgba(image.format())) {
        error = "upright needs an RGBA image";
        return std::nullopt;
    }
    std::vector<Segment> segments;
    if (spec.mode != UprightMode::Guided) {
        segments = detectSegments(image, error);
        if (!error.empty()) return std::nullopt;
    }
    return uprightFromSegments(image.width(), image.height(), segments, spec);
}

Matrix3 manualTransform(std::uint32_t width, std::uint32_t height, const ManualTransform& spec) {
    const auto cam = cameraFor(width, height, spec.focal_length_px);
    const auto clampSlider = [](double v) { return std::isfinite(v) ? std::clamp(v, -100.0, 100.0) : 0.0; };
    Matrix3 r = multiply(rotation({1, 0, 0}, clampSlider(spec.vertical) * 0.3 * kDegrees), rotation({0, 1, 0}, clampSlider(spec.horizontal) * 0.3 * kDegrees));
    r = multiply(rotation({0, 0, 1}, (std::isfinite(spec.rotate) ? std::clamp(spec.rotate, -45.0, 45.0) : 0.0) * kDegrees), r);
    Matrix3 h = cam.homography(r);
    const double s = (std::isfinite(spec.scale) ? std::clamp(spec.scale, 10.0, 400.0) : 100.0) / 100.0;
    const double aspect = 1.0 + clampSlider(spec.aspect) / 200.0;
    const double ox = clampSlider(spec.x_offset) / 100.0 * cam.cx, oy = clampSlider(spec.y_offset) / 100.0 * cam.cy;
    // Scale about the centre (aspect stretches x against y), then offset.
    const Matrix3 post{s * aspect, 0, cam.cx - s * aspect * cam.cx + ox, 0, s / aspect, cam.cy - s / aspect * cam.cy + oy, 0, 0, 1};
    return multiply(post, h);
}

double focalLengthPixels(double focal_35mm, std::uint32_t width, std::uint32_t height) {
    if (!std::isfinite(focal_35mm) || focal_35mm < 4.0 || focal_35mm > 2000.0 || width == 0 || height == 0) return 0.0;
    constexpr double kFullFrameDiagonal = 43.266615305567875;  // √(36² + 24²) mm
    return focal_35mm / kFullFrameDiagonal * std::hypot(static_cast<double>(width), static_cast<double>(height));
}

Rect suggestedCrop(std::uint32_t width, std::uint32_t height, const Matrix3& m) {
    const double w = width, h = height, cx = w / 2, cy = h / 2;
    std::array<std::array<double, 2>, 4> quad{};
    const std::array<std::array<double, 2>, 4> corners{{{0, 0}, {w, 0}, {w, h}, {0, h}}};
    for (std::size_t i = 0; i < 4; ++i) {
        const auto p = transformPoint(m, {corners[i][0], corners[i][1], 1});
        if (p[2] <= 1e-12) return {cx, cy, cx, cy};
        quad[i] = {p[0] / p[2], p[1] / p[2]};
    }
    double orientation = 0;
    for (std::size_t i = 0; i < 4; ++i) {
        const auto& a = quad[i];
        const auto& b = quad[(i + 1) % 4];
        orientation += a[0] * b[1] - b[0] * a[1];
    }
    const auto inside = [&](double x, double y) {
        for (std::size_t i = 0; i < 4; ++i) {
            const auto& a = quad[i];
            const auto& b = quad[(i + 1) % 4];
            const double c = (b[0] - a[0]) * (y - a[1]) - (b[1] - a[1]) * (x - a[0]);
            if (orientation > 0 ? c < -1e-9 : c > 1e-9) return false;
        }
        return true;
    };
    double lo = 0.0, hi = 1.0;
    for (int it = 0; it < 50; ++it) {
        const double s = (lo + hi) / 2, hw = w * s / 2, hh = h * s / 2;
        if (inside(cx - hw, cy - hh) && inside(cx + hw, cy - hh) && inside(cx + hw, cy + hh) && inside(cx - hw, cy + hh)) lo = s;
        else hi = s;
    }
    return {cx - w * lo / 2, cy - h * lo / 2, cx + w * lo / 2, cy + h * lo / 2};
}

}  // namespace croptop::geometry
