#include "develop_color_gpu.h"

#include <algorithm>
#include <array>
#include <cstring>
#include <vector>

#include "develop_color_comp_spv.h"
#include "pixel_plane.h"

namespace croptop::develop {
namespace {

constexpr std::size_t kTilePixels = static_cast<std::size_t>(kTileEdge) * kTileEdge;
constexpr std::size_t kBatchTiles = 32;

std::array<float, 64> flatten(const ColorPlan& plan, bool clamp_negative) {
    std::array<float, 64> p{};
    std::copy(plan.into.begin(), plan.into.end(), p.begin());
    std::copy(plan.from_srgb.begin(), plan.from_srgb.end(), p.begin() + 9);
    p[18] = plan.mixing ? 1.0F : 0.0F;
    p[19] = plan.grading ? 1.0F : 0.0F;
    p[20] = plan.mono ? 1.0F : 0.0F;
    p[21] = plan.look_chroma;
    p[22] = plan.tint;
    p[23] = plan.split;
    p[24] = plan.spread;
    p[25] = clamp_negative ? 1.0F : 0.0F;
    std::copy(plan.hue.begin(), plan.hue.end(), p.begin() + 26);
    std::copy(plan.saturation.begin(), plan.saturation.end(), p.begin() + 34);
    std::copy(plan.luminance.begin(), plan.luminance.end(), p.begin() + 42);
    std::copy(plan.shadows.begin(), plan.shadows.end(), p.begin() + 50);
    std::copy(plan.midtones.begin(), plan.midtones.end(), p.begin() + 53);
    std::copy(plan.highlights.begin(), plan.highlights.end(), p.begin() + 56);
    std::copy(plan.global.begin(), plan.global.end(), p.begin() + 59);
    return p;
}

}  // namespace

std::optional<TileStore> applyColorGpu(gpu::Compute& gpu, const TileStore& linear, const ColorSettings& settings, std::string& error) {
    bool identity = false;
    const auto plan = makeColorPlan(settings, linear.format(), identity, error);
    if (identity) return linear;
    if (!plan) return std::nullopt;
    const auto params = flatten(*plan, linear.format() == PixelFormat::RGBA16);
    const auto coordinates = linear.tileCoordinates();
    const auto bpp = linear.bytes_per_pixel();
    TileStore out(linear.format(), linear.width(), linear.height());
    std::vector<float> in_pixels, out_pixels;
    for (std::size_t first = 0; first < coordinates.size(); first += kBatchTiles) {
        const auto count = std::min(kBatchTiles, coordinates.size() - first);
        in_pixels.assign(count * kTilePixels * 4U, 0.0F);
        std::vector<std::shared_ptr<const TileBuffer>> sources(count);
        for (std::size_t t = 0; t < count; ++t) {
            sources[t] = linear.readTile(coordinates[first + t]);
            if (sources[t] == nullptr) continue;
            for (std::size_t p = 0; p < kTilePixels; ++p) paint::detail::readPixel(linear.format(), sources[t]->data() + p * bpp, &in_pixels[(t * kTilePixels + p) * 4U]);
        }
        out_pixels.assign(in_pixels.size(), 0.0F);
        const auto pixels = static_cast<std::uint32_t>(count * kTilePixels);
        const std::array<gpu::Binding, 3> bindings{
            gpu::Binding{in_pixels.data(), nullptr, in_pixels.size() * sizeof(float)},
            gpu::Binding{nullptr, out_pixels.data(), out_pixels.size() * sizeof(float)},
            gpu::Binding{params.data(), nullptr, params.size() * sizeof(float)},
        };
        const std::uint32_t groups = (pixels + 255U) / 256U;
        // Keep gx within the common 65535 limit; the shader derives the linear index from gl_NumWorkGroups.x.
        const std::uint32_t gx = std::min(groups, 65535U);
        const std::uint32_t gy = (groups + gx - 1U) / gx;
        const auto* push_bytes = reinterpret_cast<const std::uint8_t*>(&pixels);
        if (!gpu.dispatch(kDevelopColorShaderSpirv, bindings, std::span<const std::uint8_t>(push_bytes, sizeof(pixels)), gx, gy, 1, error)) return std::nullopt;
        for (std::size_t t = 0; t < count; ++t) {
            if (sources[t] == nullptr) continue;
            TileBuffer tile(out.tile_byte_size(), 0);
            for (std::size_t p = 0; p < kTilePixels; ++p) paint::detail::writePixel(out.format(), tile.data() + p * bpp, &out_pixels[(t * kTilePixels + p) * 4U]);
            if (!out.writeTile(coordinates[first + t], tile)) {
                error = "could not store developed pixels";
                return std::nullopt;
            }
        }
    }
    return out;
}

}  // namespace croptop::develop
