#include "refine_edge.h"

#include <algorithm>
#include <array>
#include <cmath>
#include <cstring>
#include <exception>
#include <vector>

#include "parallel_util.h"
#include "selection_internal.h"
#include "selection_mask.h"

namespace croptop::selection {
namespace {

using namespace detail;

bool isRgba(PixelFormat f) { return f == PixelFormat::RGBA8 || f == PixelFormat::RGBA16 || f == PixelFormat::RGBA32F; }

/** Encoded RGBA in [0, 1] (RGBA32F clamped) for every pixel of `region`. */
std::vector<float> extractRgba(const TileStore& image, Region region) {
    std::vector<float> out(static_cast<std::size_t>(region.pixels()) * 4U, 0.0F);
    const auto bpp = image.bytes_per_pixel();
    for (std::uint32_t ty = region.y0 / kTileEdge; ty * kTileEdge < region.y1; ++ty) {
        for (std::uint32_t tx = region.x0 / kTileEdge; tx * kTileEdge < region.x1; ++tx) {
            const auto tile = image.readTile({static_cast<std::int32_t>(tx), static_cast<std::int32_t>(ty)});
            if (tile == nullptr) continue;
            const auto rows = std::min<std::uint32_t>(kTileEdge, region.y1 - ty * kTileEdge);
            const auto cols = std::min<std::uint32_t>(kTileEdge, region.x1 - tx * kTileEdge);
            for (std::uint32_t y = 0; y < rows; ++y) {
                for (std::uint32_t x = 0; x < cols; ++x) {
                    const auto* p = tile->data() + (static_cast<std::size_t>(y) * kTileEdge + x) * bpp;
                    auto* o = out.data() + ((static_cast<std::size_t>(ty * kTileEdge + y - region.y0) * region.width()) +
                                            (tx * kTileEdge + x - region.x0)) * 4U;
                    for (std::size_t c = 0; c < 4; ++c) {
                        if (image.format() == PixelFormat::RGBA8) {
                            o[c] = p[c] / 255.0F;
                        } else if (image.format() == PixelFormat::RGBA16) {
                            std::uint16_t v = 0;
                            std::memcpy(&v, p + c * 2U, 2U);
                            o[c] = v / 65535.0F;
                        } else {
                            float v = 0.0F;
                            std::memcpy(&v, p + c * 4U, 4U);
                            o[c] = std::isfinite(v) ? std::clamp(v, 0.0F, 1.0F) : 0.0F;
                        }
                    }
                }
            }
        }
    }
    return out;
}

/** Box mean over a (2r+1)² window with edge clamping (the window is cut at the region border). */
void boxMean(std::vector<float>& plane, std::uint32_t width, std::uint32_t height, std::uint32_t r) {
    if (r == 0U) return;
    const auto workers = parallel::workerCount(0);
    struct Scratch {
        std::vector<double> prefix;
        std::vector<float> line;
    };
    std::vector<Scratch> scratch(workers);
    for (auto& s : scratch) {
        s.prefix.resize(std::max(width, height) + 1U);
        s.line.resize(std::max(width, height));
    }
    parallel::parallelFor(height, workers, [&](std::size_t y, std::size_t worker) {
        auto& s = scratch[worker];
        auto* row = plane.data() + y * width;
        s.prefix[0] = 0.0;
        for (std::uint32_t x = 0; x < width; ++x) s.prefix[x + 1U] = s.prefix[x] + row[x];
        for (std::uint32_t x = 0; x < width; ++x) {
            const auto a = x >= r ? x - r : 0U;
            const auto b = std::min(width, x + r + 1U);
            s.line[x] = static_cast<float>((s.prefix[b] - s.prefix[a]) / (b - a));
        }
        std::copy_n(s.line.data(), width, row);
    });
    // Vertical pass in chunks of columns so every read walks along a row (cache-friendly).
    constexpr std::uint32_t kChunk = 64;
    const auto chunks = (width + kChunk - 1U) / kChunk;
    std::vector<std::vector<double>> column_prefix(workers, std::vector<double>((static_cast<std::size_t>(height) + 1U) * kChunk));
    parallel::parallelFor(chunks, workers, [&](std::size_t chunk, std::size_t worker) {
        auto& prefix = column_prefix[worker];
        const auto x0 = static_cast<std::uint32_t>(chunk) * kChunk;
        const auto count = std::min(kChunk, width - x0);
        std::fill_n(prefix.begin(), count, 0.0);
        for (std::uint32_t y = 0; y < height; ++y) {
            const auto* row = plane.data() + static_cast<std::size_t>(y) * width + x0;
            const auto* previous = prefix.data() + static_cast<std::size_t>(y) * kChunk;
            auto* next = prefix.data() + static_cast<std::size_t>(y + 1U) * kChunk;
            for (std::uint32_t c = 0; c < count; ++c) next[c] = previous[c] + row[c];
        }
        for (std::uint32_t y = 0; y < height; ++y) {
            const auto a = y >= r ? y - r : 0U;
            const auto b = std::min(height, y + r + 1U);
            const auto* pa = prefix.data() + static_cast<std::size_t>(a) * kChunk;
            const auto* pb = prefix.data() + static_cast<std::size_t>(b) * kChunk;
            auto* row = plane.data() + static_cast<std::size_t>(y) * width + x0;
            const double inverse = 1.0 / (b - a);
            for (std::uint32_t c = 0; c < count; ++c) row[c] = static_cast<float>((pb[c] - pa[c]) * inverse);
        }
    });
}

/** Gaussian-like blur (three box passes) with clamped edges. */
void gaussian(std::vector<float>& plane, std::uint32_t width, std::uint32_t height, double sigma) {
    if (sigma <= 0.0) return;
    for (const auto size : boxSizes(sigma)) boxMean(plane, width, height, size / 2U);
}

}  // namespace

std::optional<TileStore> refine(const TileStore& guide, const TileStore& mask, const RefineEdgeSpec& spec, std::string& error) {
    if (!isRgba(guide.format()) || mask.format() != PixelFormat::A16 || guide.width() != mask.width() ||
        guide.height() != mask.height() || mask.width() == 0U || mask.height() == 0U) {
        error = "Refine Edge needs an RGBA image and an A16 selection of the same size";
        return std::nullopt;
    }
    const auto in_range = [](float v, float lo, float hi) { return std::isfinite(v) && v >= lo && v <= hi; };
    if (!in_range(spec.radius, 0.0F, 250.0F) || !in_range(spec.smooth, 0.0F, 100.0F) || !in_range(spec.feather, 0.0F, 250.0F) ||
        !in_range(spec.contrast, 0.0F, 100.0F) || !in_range(spec.shift_edge, -100.0F, 100.0F)) {
        error = "Refine Edge settings are out of range";
        return std::nullopt;
    }
    const auto bounds = selection::bounds(mask);
    if (!bounds) return mask;
    const auto blur_sigma = std::max(spec.smooth / 20.0, spec.feather / 2.0);
    const auto margin = static_cast<std::int64_t>(std::ceil(3.0 * spec.radius + 3.0 * blur_sigma)) + 4;
    const auto region = regionAround(mask, *bounds, margin);
    // Planes: mask u16 (2) + guide RGBA (16) + ~15 float work planes (60) + two distance fields (8) + flags (2).
    if (region.pixels() > spec.max_working_bytes / 88U) {
        error = "selection is too large to refine within the memory limit";
        return std::nullopt;
    }
    try {
        const auto w = region.width();
        const auto h = region.height();
        const auto n = static_cast<std::size_t>(region.pixels());
        auto plane = extract(mask, region);
        const auto rgba = extractRgba(guide, region);
        std::vector<float> m(n);
        std::vector<float> luma(n);
        for (std::size_t i = 0; i < n; ++i) {
            m[i] = plane[i] / 65535.0F;
            luma[i] = 0.2126F * rgba[i * 4] + 0.7152F * rgba[i * 4 + 1] + 0.0722F * rgba[i * 4 + 2];
        }

        // 1. Edge band from the exact distance to the 50% contour (either side).
        std::vector<std::uint8_t> inside(n);
        std::vector<std::uint8_t> outside(n);
        for (std::size_t i = 0; i < n; ++i) {
            inside[i] = plane[i] >= kHalf ? 1U : 0U;
            outside[i] = inside[i] == 0U ? 1U : 0U;
        }
        const auto to_inside = distanceField(region, inside);
        const auto to_outside = distanceField(region, outside);
        std::vector<float> band_weight(n, 0.0F);
        for (std::uint32_t y = 0; y < h; ++y) {
            for (std::uint32_t x = 0; x < w; ++x) {
                const auto i = static_cast<std::size_t>(y) * w + x;
                // Distance to the 50% contour: inside pixels measure to the nearest outside pixel and vice versa.
                const auto d = std::sqrt(inside[i] != 0U ? to_outside[i] : to_inside[i]);
                double radius = spec.radius;
                if (spec.smart_radius) {
                    const auto at = [&](std::int64_t xx, std::int64_t yy) {
                        xx = std::clamp<std::int64_t>(xx, 0, w - 1);
                        yy = std::clamp<std::int64_t>(yy, 0, h - 1);
                        return luma[static_cast<std::size_t>(yy) * w + static_cast<std::size_t>(xx)];
                    };
                    const auto gx = at(x + 1, y) - at(static_cast<std::int64_t>(x) - 1, y);
                    const auto gy = at(x, y + 1) - at(x, static_cast<std::int64_t>(y) - 1);
                    const auto edge = std::clamp(std::sqrt(gx * gx + gy * gy) * 2.0F, 0.0F, 1.0F);
                    radius *= 0.25 + 0.75 * (1.0 - edge);
                }
                // Soft band edge (1 px) so the refined region blends into the untouched mask.
                band_weight[i] = static_cast<float>(std::clamp(radius + 0.5 - d, 0.0, 1.0));
            }
        }

        // 2. Colour-projection matting inside the band. Confident foreground / background are the pixels
        //    beyond the band on either side; their local mean colours F and B (window ≈ radius) define a
        //    colour line, and each band pixel's alpha is its projection onto it:
        //    α = (I − B)·(F − B) / |F − B|². Where F and B are too similar to tell apart, the mask stays.
        if (spec.radius > 0.0F) {
            const auto sample_window = static_cast<std::uint32_t>(std::max(4.0F, 2.0F * spec.radius));
            std::vector<float> fg_weight(n), bg_weight(n);
            std::array<std::vector<float>, 3> fg_sum, bg_sum;
            for (auto& v : fg_sum) v.assign(n, 0.0F);
            for (auto& v : bg_sum) v.assign(n, 0.0F);
            for (std::size_t i = 0; i < n; ++i) {
                // Foreground colour is sampled just inside the edge (the selection's interior is trusted);
                // background colour only beyond the band, because mixed strands live near the edge outside.
                fg_weight[i] = inside[i] != 0U && to_outside[i] >= 9.0F ? 1.0F : 0.0F;
                bg_weight[i] = inside[i] == 0U && band_weight[i] == 0.0F ? 1.0F : 0.0F;
                for (std::size_t c = 0; c < 3; ++c) {
                    fg_sum[c][i] = rgba[i * 4 + c] * fg_weight[i];
                    bg_sum[c][i] = rgba[i * 4 + c] * bg_weight[i];
                }
            }
            boxMean(fg_weight, w, h, sample_window);
            boxMean(bg_weight, w, h, sample_window);
            for (std::size_t c = 0; c < 3; ++c) {
                boxMean(fg_sum[c], w, h, sample_window);
                boxMean(bg_sum[c], w, h, sample_window);
            }
            std::vector<float> projected = m;
            for (std::size_t i = 0; i < n; ++i) {
                if (band_weight[i] == 0.0F || fg_weight[i] <= 0.0F || bg_weight[i] <= 0.0F) continue;
                double dot = 0.0;
                double length2 = 0.0;
                for (std::size_t c = 0; c < 3; ++c) {
                    const double f = fg_sum[c][i] / fg_weight[i];
                    const double b = bg_sum[c][i] / bg_weight[i];
                    dot += (rgba[i * 4 + c] - b) * (f - b);
                    length2 += (f - b) * (f - b);
                }
                constexpr double kMinSeparation = 0.02 * 0.02;  // ~5/255 per channel.
                if (length2 < kMinSeparation) continue;
                projected[i] = static_cast<float>(std::clamp(dot / length2, 0.0, 1.0));
            }
            // A small guided filter on luminance cleans projection noise while keeping thin structure.
            constexpr std::uint32_t kGuideWindow = 2;
            constexpr float kEps = 1e-4F;
            std::vector<float> mean_i = luma, mean_p = projected, corr_ii(n), corr_ip(n);
            for (std::size_t i = 0; i < n; ++i) {
                corr_ii[i] = luma[i] * luma[i];
                corr_ip[i] = luma[i] * projected[i];
            }
            boxMean(mean_i, w, h, kGuideWindow);
            boxMean(mean_p, w, h, kGuideWindow);
            boxMean(corr_ii, w, h, kGuideWindow);
            boxMean(corr_ip, w, h, kGuideWindow);
            for (std::size_t i = 0; i < n; ++i) {
                const auto var = corr_ii[i] - mean_i[i] * mean_i[i];
                const auto cov = corr_ip[i] - mean_i[i] * mean_p[i];
                const auto a = cov / (var + kEps);
                corr_ii[i] = a;
                corr_ip[i] = mean_p[i] - a * mean_i[i];
            }
            boxMean(corr_ii, w, h, kGuideWindow);
            boxMean(corr_ip, w, h, kGuideWindow);
            for (std::size_t i = 0; i < n; ++i) {
                const auto q = std::clamp(corr_ii[i] * luma[i] + corr_ip[i], 0.0F, 1.0F);
                m[i] = m[i] + band_weight[i] * (q - m[i]);
            }
        }

        // 3. Smooth, feather, contrast, shift edge.
        gaussian(m, w, h, spec.smooth / 20.0);
        gaussian(m, w, h, spec.feather / 2.0);
        const auto steepness = 1.0F + 9.0F * spec.contrast / 100.0F;
        const auto gamma = spec.shift_edge >= 0.0F ? 1.0 / (1.0 + spec.shift_edge / 50.0) : 1.0 - spec.shift_edge / 50.0;
        for (std::size_t i = 0; i < n; ++i) {
            auto v = std::clamp((m[i] - 0.5F) * steepness + 0.5F, 0.0F, 1.0F);
            if (spec.shift_edge != 0.0F && v > 0.0F && v < 1.0F) v = static_cast<float>(std::pow(v, gamma));
            plane[i] = static_cast<std::uint16_t>(std::lround(v * 65535.0F));
        }
        return replaceRegion(mask, region, plane, error);
    } catch (const std::exception&) {
        error = "could not allocate Refine Edge buffers";
        return std::nullopt;
    }
}

std::optional<TileStore> decontaminate(const TileStore& image, const TileStore& refined_mask, float amount, std::string& error) {
    if (!isRgba(image.format()) || refined_mask.format() != PixelFormat::A16 || image.width() != refined_mask.width() ||
        image.height() != refined_mask.height() || !std::isfinite(amount) || amount < 0.0F || amount > 1.0F) {
        error = "Decontaminate Colors needs an RGBA image, a matching A16 mask and an amount of 0–1";
        return std::nullopt;
    }
    const auto bounds = selection::bounds(refined_mask);
    if (!bounds || amount == 0.0F) return image;
    constexpr std::uint32_t kWindow = 8;
    const auto region = regionAround(refined_mask, *bounds, kWindow + 2);
    try {
        const auto w = region.width();
        const auto h = region.height();
        const auto n = static_cast<std::size_t>(region.pixels());
        const auto plane = extract(refined_mask, region);
        auto rgba = extractRgba(image, region);
        // Local means of confident foreground (α > 0.95) and background (α < 0.05) colours.
        std::vector<float> fg_weight(n), bg_weight(n);
        std::array<std::vector<float>, 3> fg_sum, bg_sum;
        for (auto& v : fg_sum) v.assign(n, 0.0F);
        for (auto& v : bg_sum) v.assign(n, 0.0F);
        for (std::size_t i = 0; i < n; ++i) {
            const auto alpha = plane[i] / 65535.0F;
            fg_weight[i] = alpha > 0.95F ? 1.0F : 0.0F;
            bg_weight[i] = alpha < 0.05F ? 1.0F : 0.0F;
            for (std::size_t c = 0; c < 3; ++c) {
                fg_sum[c][i] = rgba[i * 4 + c] * fg_weight[i];
                bg_sum[c][i] = rgba[i * 4 + c] * bg_weight[i];
            }
        }
        boxMean(fg_weight, w, h, kWindow);
        boxMean(bg_weight, w, h, kWindow);
        for (std::size_t c = 0; c < 3; ++c) {
            boxMean(fg_sum[c], w, h, kWindow);
            boxMean(bg_sum[c], w, h, kWindow);
        }
        for (std::size_t i = 0; i < n; ++i) {
            const auto alpha = plane[i] / 65535.0F;
            if (alpha <= 0.0F || alpha >= 1.0F) continue;
            for (std::size_t c = 0; c < 3; ++c) {
                const auto observed = rgba[i * 4 + c];
                const auto fallback = fg_weight[i] > 0.0F ? fg_sum[c][i] / fg_weight[i] : observed;
                float foreground = fallback;
                if (bg_weight[i] > 0.0F && alpha > 0.1F) {
                    const auto background = bg_sum[c][i] / bg_weight[i];
                    const auto solved = std::clamp((observed - (1.0F - alpha) * background) / alpha, 0.0F, 1.0F);
                    // Low alpha makes the solve unstable: lean on the local foreground mean there.
                    const auto trust = std::clamp((alpha - 0.1F) / 0.2F, 0.0F, 1.0F);
                    foreground = fallback + trust * (solved - fallback);
                }
                rgba[i * 4 + c] = observed + amount * (foreground - observed);
            }
        }
        // Write the region back in the image's format (alpha untouched).
        TileStore out(image);
        const auto bpp = image.bytes_per_pixel();
        for (std::uint32_t ty = region.y0 / kTileEdge; ty * kTileEdge < region.y1; ++ty) {
            for (std::uint32_t tx = region.x0 / kTileEdge; tx * kTileEdge < region.x1; ++tx) {
                const TileCoordinate coordinate{static_cast<std::int32_t>(tx), static_cast<std::int32_t>(ty)};
                const auto source = image.readTile(coordinate);
                if (source == nullptr) continue;
                TileBuffer tile = *source;
                const auto rows = std::min<std::uint32_t>(kTileEdge, region.y1 - ty * kTileEdge);
                const auto cols = std::min<std::uint32_t>(kTileEdge, region.x1 - tx * kTileEdge);
                for (std::uint32_t y = 0; y < rows; ++y) {
                    for (std::uint32_t x = 0; x < cols; ++x) {
                        const auto i = static_cast<std::size_t>(ty * kTileEdge + y - region.y0) * w + (tx * kTileEdge + x - region.x0);
                        // Only soft pixels change; everything else keeps its exact stored value (HDR floats too).
                        if (plane[i] == 0U || plane[i] == kFullySelected) continue;
                        auto* p = tile.data() + (static_cast<std::size_t>(y) * kTileEdge + x) * bpp;
                        for (std::size_t c = 0; c < 3; ++c) {
                            const auto v = std::clamp(rgba[i * 4 + c], 0.0F, 1.0F);
                            if (image.format() == PixelFormat::RGBA8) {
                                p[c] = static_cast<std::uint8_t>(std::lround(v * 255.0F));
                            } else if (image.format() == PixelFormat::RGBA16) {
                                const auto value = static_cast<std::uint16_t>(std::lround(v * 65535.0F));
                                std::memcpy(p + c * 2U, &value, 2U);
                            } else {
                                std::memcpy(p + c * 4U, &v, 4U);
                            }
                        }
                    }
                }
                if (!out.writeTile(coordinate, tile)) {
                    error = "could not store decontaminated colours";
                    return std::nullopt;
                }
            }
        }
        (void)h;
        return out;
    } catch (const std::exception&) {
        error = "could not allocate Decontaminate Colors buffers";
        return std::nullopt;
    }
}

}  // namespace croptop::selection
