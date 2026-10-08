// Tests for the brush stroke stabilizer.
#include "stroke_stabilizer.h"

#include <gtest/gtest.h>

#include <cmath>
#include <cstdio>
#include <random>
#include <string>
#include <vector>

namespace {

namespace st = croptop::stroke;

std::vector<float> record(float x, float y, float pressure = 0.5F, float time = 0.0F) { return {x, y, pressure, 0, 0, 0, time}; }

std::vector<float> run(st::StabilizerSpec spec, const std::vector<float>& input, bool one_at_a_time = false) {
    st::Stabilizer stabilizer(spec);
    std::vector<float> out;
    std::string error;
    if (one_at_a_time) {
        for (std::size_t i = 0; i < input.size(); i += st::kStride) {
            EXPECT_TRUE(stabilizer.add(std::span<const float>(input).subspan(i, st::kStride), out, error)) << error;
        }
    } else {
        EXPECT_TRUE(stabilizer.add(input, out, error)) << error;
    }
    stabilizer.finish(out);
    return out;
}

std::vector<float> line(int from, int to, float y = 50.0F) {
    std::vector<float> points;
    for (int x = from; x <= to; ++x) {
        const auto r = record(static_cast<float>(x), y, 0.5F, x * 0.01F);
        points.insert(points.end(), r.begin(), r.end());
    }
    return points;
}

TEST(Stabilizer, DisabledStagesPassRecordsThrough) {
    const auto input = line(0, 30);
    EXPECT_EQ(run({}, input), input);
    std::string error;
    EXPECT_TRUE(st::Stabilizer::validate({}, error));
    EXPECT_FALSE(st::Stabilizer::validate({-1.0F, 0, 0}, error));
    EXPECT_FALSE(st::Stabilizer::validate({0, 65, 0}, error));
    EXPECT_FALSE(st::Stabilizer::validate({0, 0, 0.05F}, error));
    st::Stabilizer stabilizer({});
    std::vector<float> out;
    EXPECT_FALSE(stabilizer.add(std::vector<float>{1, 2, 3}, out, error));
    EXPECT_FALSE(stabilizer.add(std::vector<float>{1, 2, 3, 4, 5, 6, NAN}, out, error));
}

TEST(Stabilizer, LazyRopeLagsIgnoresJitterAndCatchesUp) {
    st::Stabilizer stabilizer({20.0F, 0, 0});
    std::vector<float> out;
    std::string error;
    ASSERT_TRUE(stabilizer.add(line(0, 100), out, error));
    ASSERT_GE(out.size(), st::kStride);
    EXPECT_NEAR(out[out.size() - st::kStride], 80.0F, 1e-4F) << "the brush trails the pointer by the rope length";
    // Jitter within the rope does not move the brush.
    const auto before = out.size();
    for (int i = 0; i < 20; ++i) {
        // Pointer wiggles between 90 and 100 px while the brush sits at 80: always within the 20 px rope.
        const auto r = record(i % 2 ? 100.0F : 90.0F, 50.0F);
        ASSERT_TRUE(stabilizer.add(r, out, error));
    }
    EXPECT_EQ(out.size(), before);
    stabilizer.finish(out);
    EXPECT_NEAR(out[out.size() - st::kStride], 100.0F, 1e-4F) << "finish catches up to the last pointer position";
}

TEST(Stabilizer, WeightedAverageSmoothsJitter) {
    std::mt19937 random(3);
    std::vector<float> noisy;
    for (int x = 0; x < 300; ++x) {
        const auto r = record(static_cast<float>(x), 50.0F + static_cast<float>(static_cast<int>(random() % 7) - 3));
        noisy.insert(noisy.end(), r.begin(), r.end());
    }
    const auto spread = [](const std::vector<float>& points, std::size_t skip) {
        double sum = 0, sum2 = 0;
        std::size_t n = 0;
        for (std::size_t i = skip * st::kStride; i < points.size(); i += st::kStride, ++n) {
            sum += points[i + 1];
            sum2 += points[i + 1] * points[i + 1];
        }
        const double mean = sum / n;
        return std::sqrt(sum2 / n - mean * mean);
    };
    const auto smoothed = run({0, 12, 0}, noisy);
    EXPECT_LT(spread(smoothed, 12), spread(noisy, 0) * 0.45);
}

TEST(Stabilizer, CatmullRomResamplesAtUniformSpacingThroughTheControls) {
    std::vector<float> circle;
    for (int degrees = 0; degrees <= 360; degrees += 30) {
        const double a = degrees * M_PI / 180;
        const auto r = record(static_cast<float>(200 + 100 * std::cos(a)), static_cast<float>(200 + 100 * std::sin(a)), degrees / 360.0F);
        circle.insert(circle.end(), r.begin(), r.end());
    }
    const auto out = run({0, 0, 2.0F}, circle);
    ASSERT_GT(out.size() / st::kStride, 250u);
    double worst_interior = 0.0, worst_any = 0.0;
    std::size_t worst_index = 0;
    const std::size_t count = out.size() / st::kStride;
    for (std::size_t i = st::kStride; i + st::kStride < out.size(); i += st::kStride) {
        const double gap = std::hypot(out[i] - out[i - st::kStride], out[i + 1] - out[i - st::kStride + 1]);
        ASSERT_NEAR(gap, 2.0, 0.05) << "uniform arc-length spacing";
        const double off = std::fabs(std::hypot(out[i] - 200, out[i + 1] - 200) - 100.0);
        if (off > worst_any) { worst_any = off; worst_index = i / st::kStride; }
        const std::size_t k = i / st::kStride;
        if (k > count / 12 && k < count - count / 12) worst_interior = std::max(worst_interior, off);
    }
    std::printf("[circle] points %zu worst radial error %.3f at %zu, interior %.3f\n", count, worst_any, worst_index, worst_interior);
    EXPECT_LT(worst_interior, 0.5) << "interior segments stay on the circle";
    // End segments use reflected phantom controls (straight continuation), so with samples 52 px apart
    // they can drift ~2 px; real strokes are sampled every few px (see the dense case below).
    EXPECT_LT(worst_any, 2.5);

    std::vector<float> dense;
    for (int degrees = 0; degrees <= 180; degrees += 5) {
        const double a = degrees * M_PI / 180;
        const auto r = record(static_cast<float>(200 + 100 * std::cos(a)), static_cast<float>(200 + 100 * std::sin(a)));
        dense.insert(dense.end(), r.begin(), r.end());
    }
    const auto fine = run({0, 0, 1.0F}, dense);
    double worst_dense = 0.0;
    for (std::size_t i = 0; i < fine.size(); i += st::kStride) {
        worst_dense = std::max(worst_dense, std::fabs(std::hypot(fine[i] - 200, fine[i + 1] - 200) - 100.0));
    }
    EXPECT_LT(worst_dense, 0.25) << "with realistic sample density the whole stroke stays on the curve";
    EXPECT_NEAR(out[0], 300.0F, 1e-4F);
    EXPECT_NEAR(out[out.size() - st::kStride], 300.0F, 1e-3F) << "ends on the last control point";
    // Pressure is interpolated along the stroke (monotonic here).
    for (std::size_t i = st::kStride; i + st::kStride < out.size(); i += st::kStride) {
        ASSERT_GE(out[i + 2] + 1e-5F, out[i - st::kStride + 2]);
    }
}

TEST(Stabilizer, IncrementalAndBatchInputProduceIdenticalOutput) {
    std::mt19937 random(9);
    std::vector<float> wobbly;
    for (int i = 0; i < 200; ++i) {
        const auto r = record(i * 1.7F, 100.0F + 30.0F * std::sin(i * 0.1F) + (random() % 5), 0.3F + 0.001F * i, i * 0.004F);
        wobbly.insert(wobbly.end(), r.begin(), r.end());
    }
    const st::StabilizerSpec spec{8.0F, 6, 1.5F};
    EXPECT_EQ(run(spec, wobbly, false), run(spec, wobbly, true));
}

}  // namespace
