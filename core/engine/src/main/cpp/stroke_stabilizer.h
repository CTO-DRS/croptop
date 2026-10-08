#pragma once

// Brush stroke stabilizer. Works on the engine's packed stroke records
// (x, y, pressure, tiltX, tiltY, orientation, timeSeconds — StrokeSession.add) and emits records in the
// same format, incrementally:
//   1. Lazy rope: the brush follows the pointer only when it is more than `rope_length` px away, and then
//      stays exactly that far behind (small jitter inside the rope is ignored).
//   2. Weighted average: a causal triangular-weighted mean of the last `window` rope positions.
//   3. Catmull-Rom resampling: output points at uniform arc length `spacing` along a Catmull-Rom spline
//      through the smoothed positions; the other fields are interpolated along each segment.
// Any stage is disabled by 0. finish() catches the brush up to the last pointer position and flushes
// the tail. Feeding records one at a time or in batches produces identical output.

#include <cstddef>
#include <array>
#include <deque>
#include <span>
#include <string>
#include <vector>

namespace croptop::stroke {

inline constexpr std::size_t kStride = 7;

struct StabilizerSpec final {
    float rope_length = 0.0F;  // px, 0–1000
    int window = 0;            // samples, 0–64
    float spacing = 0.0F;      // px, 0 = no resampling, else 0.1–1000
};

class Stabilizer final {
public:
    /** Returns false (and sets `error`) for out-of-range settings. */
    static bool validate(const StabilizerSpec& spec, std::string& error);
    explicit Stabilizer(StabilizerSpec spec);

    /** Consumes packed records; appends the records that are now final to `out`. */
    bool add(std::span<const float> records, std::vector<float>& out, std::string& error);
    /** Catches up to the last pointer position and emits the rest of the stroke. */
    void finish(std::vector<float>& out);

private:
    using Record = std::array<float, kStride>;
    void rope(const Record& pointer, std::vector<float>& out);
    void average(const Record& point, std::vector<float>& out);
    void spline(const Record& point, std::vector<float>& out);
    void emitSegment(const Record& p0, const Record& p1, const Record& p2, const Record& p3, std::vector<float>& out);

    StabilizerSpec spec_;
    bool has_brush_ = false;
    Record brush_{};
    Record last_pointer_{};
    std::deque<Record> recent_;    // weighted-average window
    std::vector<Record> controls_; // up to 4 spline control points
    double carry_ = 0.0;           // arc length since the last emitted point
};

}  // namespace croptop::stroke
