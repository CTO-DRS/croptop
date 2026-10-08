#pragma once

// Brush/eraser rasterization primitives. A stroke owns one generator and one target snapshot;
// callers serialize TileStore writes with rendering and publish the COW result at commit.

#include <cstdint>
#include <array>
#include <memory>
#include <limits>
#include <span>
#include <string>
#include <vector>

#include "tile_store.h"

namespace croptop::brush {

enum class DynamicsInput : std::uint8_t { Off, Pressure, Tilt, Velocity };

struct CurvePoint final {
    float x = 0;
    float y = 0;
};

namespace detail {
struct CurveCache final {
    std::vector<CurvePoint> points;
    std::vector<float> delta;
    std::vector<float> slope;
    std::vector<float> tangent;
};
}  // namespace detail

struct Dynamics final {
    DynamicsInput input = DynamicsInput::Off;
    std::vector<CurvePoint> curve{{0, 0}, {1, 1}};
    float minimum = 0;
};

struct Settings final {
    float size = 45;
    float hardness = 0.8F;
    float opacity = 1;
    float flow = 1;
    float spacing = 0.25F;
    float angle = 0;
    float roundness = 1;
    Dynamics size_dynamics{DynamicsInput::Pressure};
    Dynamics opacity_dynamics;
    Dynamics flow_dynamics;
    float size_jitter = 0;
    float angle_jitter = 0;
    float scatter = 0;
    bool scatter_both_axes = false;
    int count = 1;
    bool build_up = false;
};

struct Sample final {
    float x = 0;
    float y = 0;
    float pressure = 1;
    float tilt_x = 0;
    float tilt_y = 0;
    float orientation = 0;
    float time = 0;
};

struct Dab final {
    float x = 0;
    float y = 0;
    float diameter = 0;
    float angle = 0;
    float roundness = 1;
    float hardness = 1;
    /** Opacity dynamics, separated from the stroke-wide opacity ceiling. */
    float opacity = 1;
    /** Base flow multiplied by the flow dynamics for this dab. */
    float flow = 1;
    /** Brush opacity setting applied once by StrokeMaskCompositor when not building up. */
    float stroke_opacity = 1;
    bool operator==(const Dab&) const = default;
};

/** Incremental, chunk-invariant translation of core:model BrushDabs. */
class DabGenerator final {
public:
    explicit DabGenerator(Settings settings, std::int32_t seed = 1);
    ~DabGenerator();
    DabGenerator(const DabGenerator&) = delete;
    DabGenerator& operator=(const DabGenerator&) = delete;

    /** Appends dabs for new ordered samples. The first sample is stamped once per stroke. */
    bool add(std::span<const Sample> samples, std::vector<Dab>& out, std::string& error,
             std::size_t max_output_dabs = std::numeric_limits<std::size_t>::max());
    std::size_t sampleCount() const { return sample_count_; }
    bool buildUp() const { return settings_.build_up; }

private:
    class JavaRandom;
    bool stamp(const Sample& sample, float speed, std::vector<Dab>& out, std::string& error, std::size_t max_output_dabs);
    bool segment(const Sample& next, std::vector<Dab>& out, std::string& error, std::size_t max_output_dabs);

    Settings settings_;
    std::array<detail::CurveCache, 3> curves_;
    std::unique_ptr<JavaRandom> random_;
    Sample previous_{};
    bool has_previous_ = false;
    float carry_ = 0;
    std::size_t sample_count_ = 0;
    std::size_t dab_count_ = 0;
};

/** Stamp one antialiased round/elliptical dab into an RGBA layer. Color is straight sRGB.
 * Selection clipping is applied once to the completed build-up stroke, not per dab.
 */
bool stamp(TileStore& target, const Dab& dab, float red, float green, float blue, float alpha, bool erase, bool preserve_alpha,
          std::string& error);

}  // namespace croptop::brush
