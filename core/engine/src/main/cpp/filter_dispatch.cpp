#include "filter_dispatch.h"

#include <array>
#include <cmath>

#include "filters_blur.h"
#include "filters_distort.h"
#include "filters_noise.h"

namespace croptop::filters {
namespace {

constexpr std::array<int, kFilterCount> kCounts{1, 1, 3, 1, 2, 4, 2, 6, 4, 1, 1, 1, 4, 2, 1, 3, 3, 0, 3, 2, 3, 3, 8, 2, 3, 2, 1, 2, 2, 1, 1, 4, 4};

}  // namespace

int filterParameterCount(int id) { return id >= 0 && id < kFilterCount ? kCounts[static_cast<std::size_t>(id)] : -1; }

bool applyFilter(TileStore& layer, int id, std::span<const float> p, const TileStore* map, const TileStore* mask, std::uint32_t seed,
                 unsigned threads, std::string& error) {
    const int expected = filterParameterCount(id);
    if (expected < 0) {
        error = "unknown filter id";
        return false;
    }
    if (p.size() != static_cast<std::size_t>(expected)) {
        error = "filter " + std::to_string(id) + " expects " + std::to_string(expected) + " parameters";
        return false;
    }
    for (const float v : p) {
        if (!std::isfinite(v)) {
            error = "filter parameters must be finite";
            return false;
        }
    }
    // Parameters that are counts, sizes or flags must be whole numbers.
    const auto whole = [&](std::size_t i) { return p[i] >= 0.0F && p[i] == std::floor(p[i]) && p[i] < 1e6F; };
    const auto flag = [&](std::size_t i) { return p[i] == 0.0F || p[i] == 1.0F; };
    bool ok = true;
    switch (id) {
        case 1: case 26: case 29: case 30: ok = whole(0); break;
        case 5: ok = flag(0); break;
        case 6: case 27: ok = whole(0); break;
        case 8: ok = flag(3); break;
        case 12: ok = flag(2) && flag(3); break;
        case 13: ok = whole(1) && p[1] <= 2.0F; break;
        case 14: ok = flag(0); break;
        case 15: ok = flag(2); break;
        case 18: ok = whole(0) && whole(2); break;
        case 24: ok = flag(1) && flag(2); break;
        case 31: ok = whole(0); break;
        case 32: ok = whole(3) && p[3] <= 3.0F; break;
        default: break;
    }
    if (!ok) {
        error = "filter " + std::to_string(id) + " has an invalid whole-number or flag parameter";
        return false;
    }
    const auto u = [&](std::size_t i) { return static_cast<std::uint32_t>(p[i]); };
    switch (id) {
        case 0: return gaussianBlur(layer, p[0], mask, error, threads);
        case 1: return boxBlur(layer, u(0), mask, error, threads);
        case 2: return unsharpMask(layer, p[0], p[1], p[2], mask, error, threads);
        case 3: return highPass(layer, p[0], mask, error, threads);
        case 4: return motionBlur(layer, p[0], p[1], mask, error, threads);
        case 5: return radialBlur(layer, p[0] == 0.0F ? RadialMode::Spin : RadialMode::Zoom, p[1], p[2], p[3], mask, error, threads);
        case 6: return surfaceBlur(layer, u(0), p[1], mask, error, threads);
        case 7: {
            TiltShiftSpec spec;
            spec.centre_x = p[0];
            spec.centre_y = p[1];
            spec.angle = p[2];
            spec.focus = p[3];
            spec.transition = p[4];
            spec.blur = p[5];
            return tiltShift(layer, spec, mask, error, threads);
        }
        case 8: {
            SmartSharpenSpec spec;
            spec.amount = p[0];
            spec.radius = p[1];
            spec.reduce_noise = p[2];
            spec.remove = p[3] == 0.0F ? SharpenRemove::Gaussian : SharpenRemove::Lens;
            return smartSharpen(layer, spec, mask, error, threads);
        }
        case 9: return twirl(layer, p[0], mask, error, threads);
        case 10: return pinch(layer, p[0], mask, error, threads);
        case 11: return spherize(layer, p[0], mask, error, threads);
        case 12: return wave(layer, p[0], p[1], p[2] != 0.0F, p[3] != 0.0F, mask, error, threads);
        case 13: return ripple(layer, p[0], static_cast<RippleSize>(static_cast<int>(p[1])), mask, error, threads);
        case 14: return polarCoordinates(layer, p[0] == 0.0F ? PolarMode::RectangularToPolar : PolarMode::PolarToRectangular, mask, error, threads);
        case 15:
            if (map == nullptr) {
                error = "Displace needs a displacement map";
                return false;
            }
            return displace(layer, *map, p[0], p[1], p[2] != 0.0F, mask, error, threads);
        case 16: return emboss(layer, p[0], p[1], p[2], mask, error, threads);
        case 17: return findEdges(layer, mask, error, threads);
        case 18: return glowingEdges(layer, static_cast<int>(p[0]), p[1], static_cast<int>(p[2]), mask, error, threads);
        case 19: return oilPaint(layer, p[0], p[1], mask, error, threads);
        case 20: return diffuseGlow(layer, p[0], p[1], p[2], seed, mask, error, threads);
        case 21: return vignette(layer, p[0], p[1], p[2], mask, error, threads);
        case 22: return clouds(layer, {p[0], p[1], p[2], p[3]}, {p[4], p[5], p[6], p[7]}, seed, mask, error, threads);
        case 23: return chromaticAberration(layer, p[0], p[1], mask, error, threads);
        case 24: return addNoise(layer, p[0], p[1] == 0.0F ? NoiseDistribution::Uniform : NoiseDistribution::Gaussian, p[2] != 0.0F, seed, mask, error, threads);
        case 25: return filmGrain(layer, p[0], p[1], seed, mask, error, threads);
        case 26: return median(layer, u(0), mask, error, threads);
        case 27: return dustAndScratches(layer, u(0), p[1], mask, error, threads);
        case 28: return reduceNoise(layer, p[0], p[1], mask, error, threads);
        case 29: return mosaic(layer, u(0), mask, error, threads);
        case 30: return crystallize(layer, u(0), seed, mask, error, threads);
        case 31: return colorHalftone(layer, u(0), {p[1], p[2], p[3]}, mask, error, threads);
        case 32: return lensFlare(layer, p[0], p[1], p[2], static_cast<int>(p[3]), mask, error, threads);
        default: break;
    }
    error = "unknown filter id";
    return false;
}

}  // namespace croptop::filters
