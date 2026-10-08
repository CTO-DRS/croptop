#include "stroke_stabilizer.h"

#include <algorithm>
#include <cmath>

namespace croptop::stroke {
namespace {

using Record = std::array<float, kStride>;

Record lerp(const Record& a, const Record& b, double t) {
    Record out{};
    for (std::size_t i = 0; i < kStride; ++i) out[i] = static_cast<float>(a[i] + (b[i] - a[i]) * t);
    return out;
}

void append(std::vector<float>& out, const Record& r) { out.insert(out.end(), r.begin(), r.end()); }

/** Phantom control beyond `end`, mirroring `neighbour` (keeps the end tangent instead of flattening it). */
Record reflect(const Record& end, const Record& neighbour) {
    Record r = end;
    r[0] = 2.0F * end[0] - neighbour[0];
    r[1] = 2.0F * end[1] - neighbour[1];
    return r;
}

double catmullRom(double p0, double p1, double p2, double p3, double t) {
    const double t2 = t * t;
    const double t3 = t2 * t;
    return 0.5 * ((2.0 * p1) + (-p0 + p2) * t + (2.0 * p0 - 5.0 * p1 + 4.0 * p2 - p3) * t2 + (-p0 + 3.0 * p1 - 3.0 * p2 + p3) * t3);
}

}  // namespace

bool Stabilizer::validate(const StabilizerSpec& spec, std::string& error) {
    const bool ok = std::isfinite(spec.rope_length) && spec.rope_length >= 0.0F && spec.rope_length <= 1000.0F &&
                    spec.window >= 0 && spec.window <= 64 && std::isfinite(spec.spacing) &&
                    (spec.spacing == 0.0F || (spec.spacing >= 0.1F && spec.spacing <= 1000.0F));
    if (!ok) error = "stabilizer settings are out of range";
    return ok;
}

Stabilizer::Stabilizer(StabilizerSpec spec) : spec_(spec) {}

bool Stabilizer::add(std::span<const float> records, std::vector<float>& out, std::string& error) {
    if (records.size() % kStride != 0U) {
        error = "stroke records must be packed in groups of 7";
        return false;
    }
    if (!std::all_of(records.begin(), records.end(), [](float v) { return std::isfinite(v); })) {
        error = "stroke records must be finite";
        return false;
    }
    for (std::size_t i = 0; i < records.size(); i += kStride) {
        Record r{};
        std::copy_n(records.begin() + static_cast<std::ptrdiff_t>(i), kStride, r.begin());
        last_pointer_ = r;
        rope(r, out);
    }
    return true;
}

void Stabilizer::rope(const Record& pointer, std::vector<float>& out) {
    if (!has_brush_ || spec_.rope_length <= 0.0F) {
        has_brush_ = true;
        brush_ = pointer;
        average(brush_, out);
        return;
    }
    const double dx = pointer[0] - brush_[0];
    const double dy = pointer[1] - brush_[1];
    const double distance = std::hypot(dx, dy);
    if (distance <= spec_.rope_length) return;  // Inside the rope: the brush stays put.
    const double pull = (distance - spec_.rope_length) / distance;
    Record next = pointer;  // Pressure, tilt and time follow the pointer.
    next[0] = static_cast<float>(brush_[0] + dx * pull);
    next[1] = static_cast<float>(brush_[1] + dy * pull);
    brush_ = next;
    average(brush_, out);
}

void Stabilizer::average(const Record& point, std::vector<float>& out) {
    if (spec_.window <= 1) {
        spline(point, out);
        return;
    }
    recent_.push_back(point);
    if (recent_.size() > static_cast<std::size_t>(spec_.window)) recent_.pop_front();
    // Triangular weights: the newest sample weighs most.
    Record smoothed = point;
    double sx = 0.0;
    double sy = 0.0;
    double total = 0.0;
    for (std::size_t i = 0; i < recent_.size(); ++i) {
        const double w = static_cast<double>(i + 1U);
        sx += recent_[i][0] * w;
        sy += recent_[i][1] * w;
        total += w;
    }
    smoothed[0] = static_cast<float>(sx / total);
    smoothed[1] = static_cast<float>(sy / total);
    spline(smoothed, out);
}

void Stabilizer::spline(const Record& point, std::vector<float>& out) {
    if (spec_.spacing <= 0.0F) {
        append(out, point);
        return;
    }
    if (!controls_.empty() && controls_.back()[0] == point[0] && controls_.back()[1] == point[1]) return;
    if (controls_.empty()) {
        controls_.push_back(point);  // Placeholder p0, replaced by a reflection once the next point arrives.
        controls_.push_back(point);
        append(out, point);
        return;
    }
    if (controls_.size() == 2U) controls_[0] = reflect(controls_[1], point);
    controls_.push_back(point);
    if (controls_.size() == 4U) {
        emitSegment(controls_[0], controls_[1], controls_[2], controls_[3], out);
        controls_.erase(controls_.begin());
    }
}

void Stabilizer::emitSegment(const Record& p0, const Record& p1, const Record& p2, const Record& p3, std::vector<float>& out) {
    // Walk the segment in small steps, emitting a record every `spacing` of arc length.
    constexpr int kSteps = 32;
    double previous_x = p1[0];
    double previous_y = p1[1];
    for (int step = 1; step <= kSteps; ++step) {
        const double t = static_cast<double>(step) / kSteps;
        const double x = catmullRom(p0[0], p1[0], p2[0], p3[0], t);
        const double y = catmullRom(p0[1], p1[1], p2[1], p3[1], t);
        double piece = std::hypot(x - previous_x, y - previous_y);
        double piece_start = 0.0;
        while (carry_ + (piece - piece_start) >= spec_.spacing) {
            const double advance = spec_.spacing - carry_;
            piece_start += advance;
            const double f = piece > 0.0 ? piece_start / piece : 1.0;
            const double segment_t = (step - 1 + f) / kSteps;
            Record r = lerp(p1, p2, segment_t);
            r[0] = static_cast<float>(previous_x + (x - previous_x) * f);
            r[1] = static_cast<float>(previous_y + (y - previous_y) * f);
            append(out, r);
            carry_ = 0.0;
        }
        carry_ += piece - piece_start;
        previous_x = x;
        previous_y = y;
    }
}

void Stabilizer::finish(std::vector<float>& out) {
    if (!has_brush_) return;
    // Catch up: the rope and the average both converge on the final pointer position.
    if (spec_.rope_length > 0.0F && (brush_[0] != last_pointer_[0] || brush_[1] != last_pointer_[1])) {
        brush_ = last_pointer_;
        average(brush_, out);
    }
    if (spec_.window > 1) {
        for (int i = 1; i < spec_.window; ++i) average(brush_, out);
    }
    if (spec_.spacing > 0.0F && controls_.size() >= 2U) {
        // Close the spline: repeat the last point as the final control.
        const auto last = controls_.back();
        if (controls_.size() == 2U) {
            // Only one real point so far: it was emitted when it arrived.
        } else {
            controls_.push_back(reflect(last, controls_[controls_.size() - 2]));
            emitSegment(controls_[0], controls_[1], controls_[2], controls_[3], out);
            controls_.erase(controls_.begin());
        }
        // Always end exactly on the final position.
        if (out.size() < kStride || out[out.size() - kStride] != last[0] || out[out.size() - kStride + 1] != last[1]) append(out, last);
        controls_.clear();
    }
    carry_ = 0.0;
}

}  // namespace croptop::stroke
