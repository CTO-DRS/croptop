// Path booleans through Clipper2; see vector_boolean.h.
#include "vector_boolean.h"

#include <clipper2/clipper.h>

#include <cmath>

namespace croptop::vector {
namespace {

constexpr int kPrecision = 3;  // decimal digits kept: 1/1000 px

bool toPaths(const Path& path, double tolerance, Clipper2Lib::PathsD& out, std::string& error) {
    for (const auto& polyline : flatten(path, tolerance)) {
        if (polyline.points.size() < 3) continue;
        Clipper2Lib::PathD contour;
        contour.reserve(polyline.points.size());
        for (const auto& p : polyline.points) {
            if (!std::isfinite(p.x) || !std::isfinite(p.y)) {
                error = "a path has a non-finite coordinate";
                return false;
            }
            contour.emplace_back(p.x, p.y);
        }
        out.push_back(std::move(contour));
    }
    return true;
}

Path fromPaths(const Clipper2Lib::PathsD& paths) {
    Path out;
    for (const auto& contour : paths) {
        if (contour.size() < 3) continue;
        out.moveTo({contour.front().x, contour.front().y});
        for (std::size_t i = 1; i < contour.size(); ++i) out.lineTo({contour[i].x, contour[i].y});
        out.close();
    }
    return out;
}

Clipper2Lib::FillRule ruleOf(FillRule rule) { return rule == FillRule::EvenOdd ? Clipper2Lib::FillRule::EvenOdd : Clipper2Lib::FillRule::NonZero; }

}  // namespace

std::optional<Path> combine(const Path& subject, FillRule subject_rule, const Path& clip, FillRule clip_rule, BoolOp op,
                            std::string& error, double tolerance) {
    if (!(tolerance > 0.0)) {
        error = "tolerance must be positive";
        return std::nullopt;
    }
    Clipper2Lib::PathsD a, b;
    if (!toPaths(subject, tolerance, a, error) || !toPaths(clip, tolerance, b, error)) return std::nullopt;
    // Clipper takes one fill rule per call; normalise each operand under its own rule first when they differ.
    if (subject_rule != clip_rule) {
        a = Clipper2Lib::Union(a, ruleOf(subject_rule), kPrecision);
        b = Clipper2Lib::Union(b, ruleOf(clip_rule), kPrecision);
    }
    const auto rule = subject_rule != clip_rule ? Clipper2Lib::FillRule::NonZero : ruleOf(subject_rule);
    Clipper2Lib::PathsD result;
    switch (op) {
        case BoolOp::Unite: result = Clipper2Lib::Union(a, b, rule, kPrecision); break;
        case BoolOp::Subtract: result = Clipper2Lib::Difference(a, b, rule, kPrecision); break;
        case BoolOp::Intersect: result = Clipper2Lib::Intersect(a, b, rule, kPrecision); break;
        case BoolOp::Exclude: result = Clipper2Lib::Xor(a, b, rule, kPrecision); break;
    }
    return fromPaths(result);
}

std::optional<Path> simplify(const Path& path, FillRule rule, std::string& error, double tolerance) {
    if (!(tolerance > 0.0)) {
        error = "tolerance must be positive";
        return std::nullopt;
    }
    Clipper2Lib::PathsD paths;
    if (!toPaths(path, tolerance, paths, error)) return std::nullopt;
    return fromPaths(Clipper2Lib::Union(paths, ruleOf(rule), kPrecision));
}

std::optional<Path> offset(const Path& path, FillRule rule, double delta, Join join, double miter_limit, std::string& error,
                           double tolerance) {
    if (!std::isfinite(delta) || !(tolerance > 0.0) || !(miter_limit >= 1.0)) {
        error = "offset needs a finite delta, a positive tolerance and a miter limit of at least 1";
        return std::nullopt;
    }
    Clipper2Lib::PathsD paths;
    if (!toPaths(path, tolerance, paths, error)) return std::nullopt;
    paths = Clipper2Lib::Union(paths, ruleOf(rule), kPrecision);
    const auto join_type = join == Join::Round ? Clipper2Lib::JoinType::Round : join == Join::Bevel ? Clipper2Lib::JoinType::Bevel : Clipper2Lib::JoinType::Miter;
    return fromPaths(Clipper2Lib::InflatePaths(paths, delta, join_type, Clipper2Lib::EndType::Polygon, miter_limit, kPrecision, tolerance));
}

double netArea(const Path& path, double tolerance) {
    double area = 0.0;
    for (const auto& polyline : flatten(path, tolerance)) {
        double twice = 0.0;
        const auto& pts = polyline.points;
        for (std::size_t i = 0; i < pts.size(); ++i) {
            const auto& p = pts[i];
            const auto& q = pts[(i + 1) % pts.size()];
            twice += p.x * q.y - q.x * p.y;
        }
        area += twice / 2.0;
    }
    return area;
}

}  // namespace croptop::vector
