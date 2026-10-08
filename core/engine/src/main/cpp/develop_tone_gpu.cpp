#include "develop_tone_gpu.h"

#include <algorithm>
#include <array>
#include <cstring>
#include <memory>
#include <vector>

#include "develop_tone_comp_spv.h"
#include "pixel_plane.h"

namespace croptop::develop {
namespace {

constexpr std::size_t kTilePixels = static_cast<std::size_t>(kTileEdge) * kTileEdge;  // 65536: the shader splits its index at bit 16
constexpr std::size_t kBatchTiles = 32;

}  // namespace

std::optional<TileStore> applyToneGpu(gpu::Compute& gpu, const TileStore& linear, const ToneSettings& t, std::string& error) {
    if (linear.format() != PixelFormat::RGBA16 && linear.format() != PixelFormat::RGBA32F) {
        error = "develop tone needs a linear RGBA16 or RGBA32F layer";
        return std::nullopt;
    }
    if (!validateToneSettings(t, error)) return std::nullopt;
    if (t.isIdentity()) return linear;
    if (t.texture != 0.0F) {
        error = "texture is not available on the GPU path";
        return std::nullopt;
    }
    const auto plan = makeTonePlan(linear, t, error);
    if (!plan) return std::nullopt;

    const auto n = static_cast<std::size_t>(plan->low_width) * static_cast<std::size_t>(plan->low_height);
    std::vector<float> planes(std::max<std::size_t>(1, n * 6), 0.0F);
    const std::array<const std::vector<float>*, 6> sources{&plan->haze_a, &plan->haze_b, &plan->base_a, &plan->base_b, &plan->detail_a, &plan->detail_b};
    for (std::size_t i = 0; i < sources.size(); ++i) {
        if (sources[i]->size() == n) std::copy(sources[i]->begin(), sources[i]->end(), planes.begin() + static_cast<std::ptrdiff_t>(i * n));
    }
    std::vector<float> luts(2050, 0.0F);
    if (!plan->parametric.empty()) std::copy(plan->parametric.begin(), plan->parametric.end(), luts.begin());
    if (!plan->point_lut.empty()) std::copy(plan->point_lut.begin(), plan->point_lut.end(), luts.begin() + 1025);

    std::array<float, 64> params{};
    params[0] = static_cast<float>(plan->low_width);
    params[1] = static_cast<float>(plan->low_height);
    params[2] = 1.0F / static_cast<float>(plan->block);
    params[3] = t.dehaze;
    params[4] = t.exposure;
    params[5] = t.contrast;
    params[6] = t.whites;
    params[7] = t.blacks;
    params[8] = t.shadows;
    params[9] = t.highlights;
    params[10] = t.clarity;
    params[11] = t.vibrance;
    params[12] = t.saturation;
    for (int c = 0; c < 3; ++c) {
        params[static_cast<std::size_t>(13 + c)] = t.luminance[static_cast<std::size_t>(c)];
        params[static_cast<std::size_t>(16 + c)] = plan->air[static_cast<std::size_t>(c)];
    }
    params[19] = plan->dehaze_positive ? 1.0F : 0.0F;
    params[20] = plan->local_tone ? 1.0F : 0.0F;
    params[21] = plan->clarity ? 1.0F : 0.0F;
    params[22] = plan->parametric.empty() ? 0.0F : 1.0F;
    params[23] = plan->point_curve ? 1.0F : 0.0F;

    // With dehaze the CPU pass also develops absent (transparent) tiles; otherwise they stay absent.
    std::vector<TileCoordinate> coordinates;
    if (plan->dehaze_positive) {
        const auto columns = (linear.width() + kTileEdge - 1) / kTileEdge, rows = (linear.height() + kTileEdge - 1) / kTileEdge;
        for (std::uint32_t y = 0; y < rows; ++y)
            for (std::uint32_t x = 0; x < columns; ++x) coordinates.push_back({static_cast<std::int32_t>(x), static_cast<std::int32_t>(y)});
    } else {
        coordinates = linear.tileCoordinates();
    }
    const auto bpp = linear.bytes_per_pixel();
    TileStore out(linear.format(), linear.width(), linear.height());
    std::vector<float> in_pixels, out_pixels;
    for (std::size_t first = 0; first < coordinates.size(); first += kBatchTiles) {
        const auto count = std::min(kBatchTiles, coordinates.size() - first);
        in_pixels.assign(count * kTilePixels * 4U, 0.0F);
        std::vector<std::uint32_t> origins(count * 2U);
        std::vector<std::shared_ptr<const TileBuffer>> sources_tiles(count);
        for (std::size_t k = 0; k < count; ++k) {
            const auto c = coordinates[first + k];
            origins[k * 2U] = static_cast<std::uint32_t>(c.x);
            origins[k * 2U + 1U] = static_cast<std::uint32_t>(c.y);
            sources_tiles[k] = linear.readTile(c);
            if (sources_tiles[k] == nullptr) continue;
            for (std::size_t p = 0; p < kTilePixels; ++p) paint::detail::readPixel(linear.format(), sources_tiles[k]->data() + p * bpp, &in_pixels[(k * kTilePixels + p) * 4U]);
        }
        out_pixels.assign(in_pixels.size(), 0.0F);
        const auto pixels = static_cast<std::uint32_t>(count * kTilePixels);
        const std::array<gpu::Binding, 6> bindings{
            gpu::Binding{in_pixels.data(), nullptr, in_pixels.size() * sizeof(float)},
            gpu::Binding{nullptr, out_pixels.data(), out_pixels.size() * sizeof(float)},
            gpu::Binding{params.data(), nullptr, params.size() * sizeof(float)},
            gpu::Binding{planes.data(), nullptr, planes.size() * sizeof(float)},
            gpu::Binding{luts.data(), nullptr, luts.size() * sizeof(float)},
            gpu::Binding{origins.data(), nullptr, origins.size() * sizeof(std::uint32_t)},
        };
        const std::uint32_t groups = (pixels + 255U) / 256U;
        const std::uint32_t gx = std::min(groups, 65535U);
        const std::uint32_t gy = (groups + gx - 1U) / gx;
        const auto* push = reinterpret_cast<const std::uint8_t*>(&pixels);
        if (!gpu.dispatch(kDevelopToneShaderSpirv, bindings, std::span<const std::uint8_t>(push, sizeof(pixels)), gx, gy, 1, error)) return std::nullopt;
        for (std::size_t k = 0; k < count; ++k) {
            TileBuffer tile(out.tile_byte_size(), 0);
            for (std::size_t p = 0; p < kTilePixels; ++p) paint::detail::writePixel(out.format(), tile.data() + p * bpp, &out_pixels[(k * kTilePixels + p) * 4U]);
            if (!out.writeTile(coordinates[first + k], tile)) {
                error = "could not store developed pixels";
                return std::nullopt;
            }
        }
    }
    return out;
}

}  // namespace croptop::develop
