// Tests for the depth-driven Lens Blur.
#include "lens_blur.h"

#include <gtest/gtest.h>

#include <cmath>
#include <cstdint>
#include <cstring>
#include <atomic>
#include <chrono>
#include <filesystem>
#include <functional>
#include <memory>
#include <stdexcept>
#include <utility>

#include "pixel_plane.h"
#include "scratch_store.h"

namespace {

using croptop::PixelFormat;
using croptop::TileStore;
namespace lb = croptop::lensblur;

constexpr float kPi = 3.14159265358979323846F;

class TemporaryScratchDirectory final {
public:
    explicit TemporaryScratchDirectory(const char* prefix) {
        static std::atomic<std::uint64_t> sequence{0};
        const auto root = std::filesystem::temp_directory_path();
        for (int attempt = 0; attempt < 32; ++attempt) {
            path_ = root / (std::string(prefix) + "-" +
                            std::to_string(std::chrono::steady_clock::now().time_since_epoch().count()) + "-" +
                            std::to_string(sequence.fetch_add(1, std::memory_order_relaxed)));
            std::error_code error;
            if (std::filesystem::create_directory(path_, error)) return;
            if (error && error != std::errc::file_exists) {
                throw std::runtime_error("Could not create isolated Lens Blur scratch directory: " + error.message());
            }
        }
        throw std::runtime_error("Could not reserve an isolated Lens Blur scratch directory");
    }

    ~TemporaryScratchDirectory() {
        std::error_code ignored;
        std::filesystem::remove_all(path_, ignored);
    }

    const std::filesystem::path& path() const { return path_; }

private:
    std::filesystem::path path_;
};

TileStore fill(PixelFormat format, int w, int h, const std::function<std::array<float, 4>(int, int)>& pixel,
               std::shared_ptr<croptop::TilePool> pool = std::make_shared<croptop::TilePool>(),
               std::shared_ptr<croptop::MemoryBudgetManager> budget = nullptr) {
    TileStore s(format, static_cast<std::uint32_t>(w), static_cast<std::uint32_t>(h), std::move(pool), std::move(budget));
    for (std::int32_t ty = 0; ty * 256 < h; ++ty)
        for (std::int32_t tx = 0; tx * 256 < w; ++tx) {
            croptop::TileBuffer t(s.tile_byte_size(), 0);
            for (int y = 0; y < 256; ++y)
                for (int x = 0; x < 256; ++x) {
                    const int gx = tx * 256 + x, gy = ty * 256 + y;
                    if (gx >= w || gy >= h) continue;
                    const auto v = pixel(gx, gy);
                    croptop::paint::detail::writePixel(format, t.data() + (static_cast<std::size_t>(y) * 256 + x) * s.bytes_per_pixel(), v.data());
                }
            EXPECT_TRUE(s.writeTile({tx, ty}, t));
        }
    return s;
}

TileStore depthMap(int w, int h, const std::function<float(int, int)>& d,
                   std::shared_ptr<croptop::TilePool> pool = std::make_shared<croptop::TilePool>(),
                   std::shared_ptr<croptop::MemoryBudgetManager> budget = nullptr) {
    TileStore s(PixelFormat::A16, static_cast<std::uint32_t>(w), static_cast<std::uint32_t>(h), std::move(pool), std::move(budget));
    for (std::int32_t ty = 0; ty * 256 < h; ++ty)
        for (std::int32_t tx = 0; tx * 256 < w; ++tx) {
            croptop::TileBuffer t(s.tile_byte_size(), 0);
            for (int y = 0; y < 256; ++y)
                for (int x = 0; x < 256; ++x) {
                    const int gx = tx * 256 + x, gy = ty * 256 + y;
                    if (gx >= w || gy >= h) continue;
                    const auto v = static_cast<std::uint16_t>(std::clamp(d(gx, gy), 0.0F, 1.0F) * 65535.0F + 0.5F);
                    std::memcpy(t.data() + (static_cast<std::size_t>(y) * 256 + x) * 2, &v, 2);
                }
            EXPECT_TRUE(s.writeTile({tx, ty}, t));
        }
    return s;
}

std::array<float, 4> at(const TileStore& s, int x, int y) {
    const auto tile = s.readTile({x / 256, y / 256});
    std::array<float, 4> v{};
    if (tile) croptop::paint::detail::readPixel(s.format(), tile->data() + (static_cast<std::size_t>(y % 256) * 256 + x % 256) * s.bytes_per_pixel(), v.data());
    return v;
}

float hash(int x, int y) {
    std::uint32_t h = static_cast<std::uint32_t>(x) * 374761393U + static_cast<std::uint32_t>(y) * 668265263U;
    h = (h ^ (h >> 13)) * 1274126177U;
    return static_cast<float>((h ^ (h >> 16)) & 0xFFFFU) / 65535.0F;
}

TileStore texture(int w, int h) {
    return fill(PixelFormat::RGBA8, w, h, [](int x, int y) {
        const float v = 0.2F + 0.6F * hash(x / 2, y / 2);
        return std::array<float, 4>{v, 0.5F * v + 0.2F, 0.4F, 1.0F};
    });
}

double variance(const TileStore& s, int x0, int x1, int y0, int y1) {
    double sum = 0, sq = 0;
    int n = 0;
    for (int y = y0; y < y1; ++y)
        for (int x = x0; x < x1; ++x) {
            const double v = at(s, x, y)[0];
            sum += v;
            sq += v * v;
            ++n;
        }
    return sq / n - (sum / n) * (sum / n);
}

TEST(LensBlur, ZeroRadiusIsIdentityAndOpaqueStaysOpaque) {
    const auto layer = texture(300, 200);
    std::string error;
    lb::LensBlurSpec spec;
    spec.radius = 0;
    const auto same = lb::lensBlur(layer, nullptr, spec, error);
    ASSERT_TRUE(same) << error;
    for (int y = 0; y < 200; y += 3)
        for (int x = 0; x < 300; x += 3)
            for (int k = 0; k < 4; ++k) ASSERT_NEAR(at(*same, x, y)[static_cast<std::size_t>(k)], at(layer, x, y)[static_cast<std::size_t>(k)], 1.0F / 255.0F);
    spec.radius = 30;
    const auto blurred = lb::lensBlur(layer, nullptr, spec, error);
    ASSERT_TRUE(blurred) << error;
    for (int y = 0; y < 200; y += 5)
        for (int x = 0; x < 300; x += 5) ASSERT_NEAR(at(*blurred, x, y)[3], 1.0F, 1e-6F);
    EXPECT_LT(variance(*blurred, 40, 260, 40, 160), 0.1 * variance(layer, 40, 260, 40, 160));
}

TEST(LensBlur, OutputsReuseSourceMemoryBudgets) {
    TemporaryScratchDirectory layer_scratch("croptop-lens-blur-layer-budget");
    auto layer_pool = std::make_shared<croptop::TilePool>();
    auto layer_budget = std::make_shared<croptop::MemoryBudgetManager>(8U * 1024U * 1024U,
        std::make_shared<croptop::DiskScratchStore>(layer_scratch.path()));
    const auto layer = fill(PixelFormat::RGBA8, 32, 32, [](int x, int y) {
        const float value = x == 16 && y == 16 ? 1.0F : 0.0F;
        return std::array<float, 4>{value, value, value, value};
    }, layer_pool, layer_budget);
    const auto layer_entries_before = layer_budget->entryCount();
    std::string error;
    lb::LensBlurSpec spec;
    spec.radius = 0;
    const auto blurred = lb::lensBlur(layer, nullptr, spec, error);
    ASSERT_TRUE(blurred) << error;
    EXPECT_GT(layer_budget->entryCount(), layer_entries_before) << "blur output should use the source memory budget";

    TemporaryScratchDirectory depth_scratch("croptop-lens-blur-depth-budget");
    auto depth_pool = std::make_shared<croptop::TilePool>();
    auto depth_budget = std::make_shared<croptop::MemoryBudgetManager>(8U * 1024U * 1024U,
        std::make_shared<croptop::DiskScratchStore>(depth_scratch.path()));
    const auto depth = depthMap(32, 32, [](int, int) { return 0.5F; }, depth_pool, depth_budget);
    const auto depth_entries_before = depth_budget->entryCount();
    const auto mask = lb::depthRangeMask(depth, 0.25F, 0.75F, 0, error);
    ASSERT_TRUE(mask) << error;
    EXPECT_GT(depth_budget->entryCount(), depth_entries_before) << "depth-range output should use the depth memory budget";
}

TEST(LensBlur, OutputsReuseSourceTilePools) {
    auto layer_pool = std::make_shared<croptop::TilePool>();
    const auto layer = fill(PixelFormat::RGBA8, 32, 32, [](int x, int y) {
        const float value = x == 16 && y == 16 ? 1.0F : 0.0F;
        return std::array<float, 4>{value, value, value, value};
    }, layer_pool);
    const auto layer_pool_bytes_before = layer_pool->active_bytes();
    std::string error;
    lb::LensBlurSpec spec;
    spec.radius = 0;
    const auto blurred = lb::lensBlur(layer, nullptr, spec, error);
    ASSERT_TRUE(blurred) << error;
    EXPECT_GT(layer_pool->active_bytes(), layer_pool_bytes_before) << "blur output should allocate through the source TilePool";

    auto depth_pool = std::make_shared<croptop::TilePool>();
    const auto depth = depthMap(32, 32, [](int, int) { return 0.5F; }, depth_pool);
    const auto depth_pool_bytes_before = depth_pool->active_bytes();
    const auto mask = lb::depthRangeMask(depth, 0.25F, 0.75F, 0, error);
    ASSERT_TRUE(mask) << error;
    EXPECT_GT(depth_pool->active_bytes(), depth_pool_bytes_before) << "depth-range output should allocate through the depth TilePool";
}

TEST(LensBlur, BokehTakesTheIrisShape) {
    // One bright point on black (linear float, so energy is exact).
    const auto point = fill(PixelFormat::RGBA32F, 160, 160, [](int x, int y) {
        const float v = x == 80 && y == 80 ? 1000.0F : 0.0F;
        return std::array<float, 4>{v, v, v, 1.0F};
    });
    std::string error;
    for (const int blades : {0, 6}) {
        lb::LensBlurSpec spec;
        spec.radius = 20;
        spec.iris.blades = blades;
        const auto out = lb::lensBlur(point, nullptr, spec, error);
        ASSERT_TRUE(out) << error;
        double energy = 0;
        for (int y = 0; y < 160; ++y)
            for (int x = 0; x < 160; ++x) energy += at(*out, x, y)[0];
        EXPECT_NEAR(energy, 1000.0, 20.0) << "blades " << blades;
        // Extent along a ray: the last radius still above half the centre's level.
        const float centre = at(*out, 80, 80)[0];
        const auto extent = [&](float angle) {
            float last = 0;
            for (float r = 0; r < 30; r += 0.25F) {
                const auto v = at(*out, 80 + static_cast<int>(std::lround(r * std::cos(angle))), 80 + static_cast<int>(std::lround(r * std::sin(angle))))[0];
                if (v > 0.5F * centre) last = r;
            }
            return last;
        };
        const float vertex = extent(0), side = extent(kPi / 6);
        EXPECT_NEAR(vertex, 20.0F, 2.0F) << "blades " << blades;
        if (blades == 6) {
            EXPECT_NEAR(side / vertex, std::cos(kPi / 6), 0.06F) << "hexagon: flat sides at cos 30° of the vertex radius";
        } else {
            EXPECT_NEAR(side / vertex, 1.0F, 0.06F) << "circle";
        }
    }
}

TEST(LensBlur, DepthDecidesWhatBlursAndNearSpreadsOverSharp) {
    const int w = 320, h = 220;
    const auto layer = texture(w, h);
    // Left half at the focal plane (0.5), right half far (1.0).
    const auto depth = depthMap(w, h, [&](int x, int) { return x < w / 2 ? 0.5F : 1.0F; });
    std::string error;
    lb::LensBlurSpec spec;
    spec.radius = 16;
    spec.focal_distance = 0.5F;
    const auto out = lb::lensBlur(layer, &depth, spec, error);
    ASSERT_TRUE(out) << error;
    // Sharp side untouched right up to the boundary: far blur never bleeds onto content in front.
    for (int y = 20; y < 200; y += 2)
        for (int x = 4; x < w / 2 - 1; x += 2) ASSERT_NEAR(at(*out, x, y)[0], at(layer, x, y)[0], 1.5F / 255.0F) << x << ", " << y;
    EXPECT_LT(variance(*out, w / 2 + 20, w - 20, 20, 200), 0.2 * variance(layer, w / 2 + 20, w - 20, 20, 200));

    // A near red square (depth 0) in front of sharp grey (depth 0.5): its blur spreads outward over the grey.
    const auto scene = fill(PixelFormat::RGBA8, w, h, [&](int x, int y) {
        const bool square = x >= 120 && x < 200 && y >= 70 && y < 150;
        return square ? std::array<float, 4>{1.0F, 0.0F, 0.0F, 1.0F} : std::array<float, 4>{0.5F, 0.5F, 0.5F, 1.0F};
    });
    const auto near_depth = depthMap(w, h, [&](int x, int y) { return x >= 120 && x < 200 && y >= 70 && y < 150 ? 0.0F : 0.5F; });
    const auto spread = lb::lensBlur(scene, &near_depth, spec, error);
    ASSERT_TRUE(spread) << error;
    const auto outside = at(*spread, 204, 110);  // 5 px outside the square
    EXPECT_GT(outside[0] - outside[1], 0.1F) << "red bleeds over the sharp background";
    EXPECT_NEAR(at(*spread, 160, 20)[0], 0.5F, 1.5F / 255.0F) << "far from the square the background stays sharp";
    EXPECT_NEAR(outside[3], 1.0F, 1e-6F) << "no transparent halo";
}

TEST(LensBlur, SpecularHighlightsBrightenBokehAndNoiseIsSeeded) {
    const auto layer = fill(PixelFormat::RGBA8, 120, 120, [](int x, int y) {
        const float v = (x - 60) * (x - 60) + (y - 60) * (y - 60) <= 4 ? 1.0F : 0.1F;
        return std::array<float, 4>{v, v, v, 1.0F};
    });
    std::string error;
    lb::LensBlurSpec plain;
    plain.radius = 12;
    lb::LensBlurSpec specular = plain;
    specular.specular_brightness = 1.0F;
    specular.specular_threshold = 0.8F;
    const auto a = lb::lensBlur(layer, nullptr, plain, error), b = lb::lensBlur(layer, nullptr, specular, error);
    ASSERT_TRUE(a && b) << error;
    EXPECT_GT(at(*b, 66, 60)[0], at(*a, 66, 60)[0] + 0.15F) << "boosted highlights make a brighter bokeh disk";
    EXPECT_NEAR(at(*b, 5, 5)[0], at(*a, 5, 5)[0], 1.5F / 255.0F) << "dark areas are unaffected";

    lb::LensBlurSpec noisy = plain;
    noisy.noise = 0.5F;
    const auto n1 = lb::lensBlur(layer, nullptr, noisy, error), n2 = lb::lensBlur(layer, nullptr, noisy, error);
    ASSERT_TRUE(n1 && n2) << error;
    double diff = 0;
    for (int x = 0; x < 120; ++x) {
        EXPECT_EQ(at(*n1, x, 10), at(*n2, x, 10)) << "deterministic";
        diff += std::abs(at(*n1, x, 10)[0] - at(*a, x, 10)[0]);
        EXPECT_FLOAT_EQ(at(*n1, x, 10)[0], at(*n1, x, 10)[1]) << "monochromatic";
    }
    EXPECT_GT(diff / 120, 0.005);
}

TEST(LensBlur, DepthRangeMaskAndValidation) {
    const auto depth = depthMap(100, 10, [](int x, int) { return static_cast<float>(x) / 99.0F; });
    std::string error;
    const auto mask = lb::depthRangeMask(depth, 0.4F, 0.6F, 0.1F, error);
    ASSERT_TRUE(mask) << error;
    const auto value = [&](int x) {
        const auto t = mask->readTile({0, 0});
        std::uint16_t v = 0;
        if (t) std::memcpy(&v, t->data() + static_cast<std::size_t>(x) * 2, 2);
        return v / 65535.0F;
    };
    EXPECT_FLOAT_EQ(value(50), 1.0F);
    EXPECT_FLOAT_EQ(value(10), 0.0F);
    EXPECT_GT(value(36), 0.0F);
    EXPECT_LT(value(36), 1.0F);
    EXPECT_FALSE(lb::depthRangeMask(depth, 0.7F, 0.6F, 0, error));

    const auto layer = texture(64, 64);
    lb::LensBlurSpec bad;
    bad.radius = 150;
    EXPECT_FALSE(lb::lensBlur(layer, nullptr, bad, error));
    bad = {};
    bad.iris.blades = 2;
    EXPECT_FALSE(lb::lensBlur(layer, nullptr, bad, error));
    EXPECT_FALSE(lb::lensBlur(layer, &depth, {}, error)) << "size mismatch";
    TileStore mask16(PixelFormat::A16, 64, 64);
    EXPECT_FALSE(lb::lensBlur(mask16, nullptr, {}, error));
    lb::LensBlurSpec cancel;
    cancel.progress = [](float) { return false; };
    EXPECT_FALSE(lb::lensBlur(layer, nullptr, cancel, error));
    EXPECT_EQ(error, "cancelled");
}

}  // namespace
