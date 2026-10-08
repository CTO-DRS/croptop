// Symmetry painting.
#include "stroke_symmetry.h"

#include <cmath>

namespace croptop::brush {
namespace {

constexpr double kPi = 3.14159265358979323846;

/** A linear map about the centre plus what it does to a dab angle (degrees). */
struct Copy final {
    double a = 1, b = 0, c = 0, d = 1;  // [a b; c d]
    bool reflect = false;
    double angle = 0;                   // rotation α (degrees) or mirror line φ (degrees)
};

Copy rotation(double degrees) {
    const double r = degrees * kPi / 180.0;
    return {std::cos(r), -std::sin(r), std::sin(r), std::cos(r), false, degrees};
}

Copy mirror(double line_degrees) {
    const double r = 2.0 * line_degrees * kPi / 180.0;
    return {std::cos(r), std::sin(r), std::sin(r), -std::cos(r), true, line_degrees};
}

std::vector<Copy> copiesOf(const SymmetrySpec& s) {
    const double f = s.rotation;
    switch (s.type) {
        case SymmetryType::Off: return {rotation(0)};
        case SymmetryType::Vertical: return {rotation(0), mirror(f + 90)};
        case SymmetryType::Horizontal: return {rotation(0), mirror(f)};
        case SymmetryType::Diagonal: return {rotation(0), mirror(f + 45)};
        case SymmetryType::DualAxis: return {rotation(0), mirror(f + 90), mirror(f), rotation(180)};
        case SymmetryType::Radial:
        case SymmetryType::Mandala: {
            std::vector<Copy> copies;
            const double step = 360.0 / s.segments;
            for (int k = 0; k < s.segments; ++k) copies.push_back(rotation(k * step));
            // Mandala: each wedge is also mirrored about its own centre line.
            if (s.type == SymmetryType::Mandala)
                for (int k = 0; k < s.segments; ++k) copies.push_back(mirror(f + k * step / 2.0 + 90));
            return copies;
        }
    }
    return {rotation(0)};
}

bool valid(const SymmetrySpec& s, std::string& error) {
    if (!std::isfinite(s.center_x) || !std::isfinite(s.center_y) || !std::isfinite(s.rotation) || std::abs(s.center_x) > 1e7F || std::abs(s.center_y) > 1e7F) {
        error = "symmetry centre and rotation must be finite";
        return false;
    }
    if ((s.type == SymmetryType::Radial || s.type == SymmetryType::Mandala) && (s.segments < 2 || s.segments > 36)) {
        error = "radial symmetry needs 2–36 segments";
        return false;
    }
    return true;
}

}  // namespace

int symmetryCopies(const SymmetrySpec& spec) {
    std::string ignored;
    return valid(spec, ignored) ? static_cast<int>(copiesOf(spec).size()) : 1;
}

bool symmetrize(const SymmetrySpec& spec, std::span<const Dab> dabs, std::vector<Dab>& out, std::string& error) {
    if (!valid(spec, error)) return false;
    const auto copies = copiesOf(spec);
    out.reserve(out.size() + dabs.size() * copies.size());
    for (const auto& dab : dabs)
        for (std::size_t k = 0; k < copies.size(); ++k) {
            if (k == 0) {
                out.push_back(dab);
                continue;
            }
            const auto& m = copies[k];
            const double vx = static_cast<double>(dab.x) - spec.center_x, vy = static_cast<double>(dab.y) - spec.center_y;
            Dab copy = dab;
            copy.x = static_cast<float>(spec.center_x + m.a * vx + m.b * vy);
            copy.y = static_cast<float>(spec.center_y + m.c * vx + m.d * vy);
            double angle = m.reflect ? 2.0 * m.angle - dab.angle : dab.angle + m.angle;
            angle = std::fmod(angle, 360.0);
            if (angle > 180.0) angle -= 360.0;
            if (angle <= -180.0) angle += 360.0;
            copy.angle = static_cast<float>(angle);
            out.push_back(copy);
        }
    return true;
}

}  // namespace croptop::brush
