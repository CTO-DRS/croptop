// Standalone per-stroke coverage and paint/erase compositor.
#include "stroke_compositor.h"

#include <algorithm>
#include <array>
#include <cmath>
#include <cstring>
#include <limits>
#include <new>
#include <numbers>

#include "brush_engine.h"
#include "pixel_plane.h"

namespace croptop::brush {
namespace {

float srgbToLinear(float value) {
    return value <= 0.04045F ? value / 12.92F : std::pow((value + 0.055F) / 1.055F, 2.4F);
}

bool validRgba(const float color[4]) {
    if (color == nullptr) return false;
    for (int channel = 0; channel < 4; ++channel) {
        if (!std::isfinite(color[channel]) || color[channel] < 0.0F || color[channel] > 1.0F) return false;
    }
    return true;
}

void addCoverage(std::uint8_t* pixel, float flow_coverage) {
    std::uint16_t old_coverage = 0;
    std::memcpy(&old_coverage, pixel, sizeof(old_coverage));
    const float old_value = old_coverage / 65535.0F;
    const float combined = 1.0F - (1.0F - old_value) * (1.0F - flow_coverage);
    const auto quantized = static_cast<std::uint16_t>(std::clamp(combined, 0.0F, 1.0F) * 65535.0F + 0.5F);
    std::memcpy(pixel, &quantized, sizeof(quantized));
}

}  // namespace

StrokeMaskCompositor::StrokeMaskCompositor(std::uint32_t width, std::uint32_t height)
    : width_(width), height_(height) {}

TileBuffer* StrokeMaskCompositor::mutableTile(TileCoordinate coordinate, std::string& error) {
    auto tile = coverage_tiles_.find(coordinate);
    if (tile != coverage_tiles_.end()) return &tile->second;
    constexpr std::size_t tile_bytes = static_cast<std::size_t>(kTileEdge) * kTileEdge * sizeof(std::uint16_t);
    if (allocated_bytes_ > kMaximumMaskBytes - tile_bytes) {
        error = "stroke mask exceeded its 64 MiB safety limit";
        return nullptr;
    }
    try {
        tile = coverage_tiles_.try_emplace(coordinate, tile_bytes, 0).first;
    } catch (const std::bad_alloc&) {
        error = "could not allocate stroke mask tile";
        return nullptr;
    }
    allocated_bytes_ += tile_bytes;
    return &tile->second;
}

bool StrokeMaskCompositor::accumulate(std::int32_t x, std::int32_t y, float flow_coverage, std::string& error) {
    if (x < 0 || y < 0 || x >= static_cast<std::int64_t>(width_) || y >= static_cast<std::int64_t>(height_) ||
        !std::isfinite(flow_coverage) || flow_coverage < 0.0F || flow_coverage > 1.0F) {
        error = "stroke mask coordinates and flow coverage must be in range";
        return false;
    }
    if (flow_coverage == 0.0F) return true;

    const TileCoordinate coordinate{x / static_cast<std::int32_t>(kTileEdge), y / static_cast<std::int32_t>(kTileEdge)};
    auto* tile = mutableTile(coordinate, error);
    if (tile == nullptr) return false;
    auto* pixel = tile->data() + (static_cast<std::size_t>(y % static_cast<std::int32_t>(kTileEdge)) * kTileEdge +
                                  static_cast<std::size_t>(x % static_cast<std::int32_t>(kTileEdge))) * sizeof(std::uint16_t);
    addCoverage(pixel, flow_coverage);
    return true;
}

bool StrokeMaskCompositor::accumulateDab(const Dab& dab, std::string& error) {
    if (!std::isfinite(dab.x) || !std::isfinite(dab.y) || std::abs(dab.x) > 1.0e7F || std::abs(dab.y) > 1.0e7F ||
        !std::isfinite(dab.diameter) || dab.diameter <= 0.0F || dab.diameter > 10000.0F || !std::isfinite(dab.angle) ||
        !std::isfinite(dab.roundness) || dab.roundness <= 0.0F || dab.roundness > 1.0F || !std::isfinite(dab.hardness) ||
        dab.hardness < 0.0F || dab.hardness > 1.0F || !std::isfinite(dab.opacity) || dab.opacity < 0.0F || dab.opacity > 1.0F ||
        !std::isfinite(dab.flow) || dab.flow < 0.0F || dab.flow > 1.0F || !std::isfinite(dab.stroke_opacity) ||
        dab.stroke_opacity < 0.0F || dab.stroke_opacity > 1.0F) {
        error = "brush dab geometry and flow must be finite and in range";
        return false;
    }
    if (dab.opacity == 0.0F || dab.flow == 0.0F || width_ == 0U || height_ == 0U) return true;

    const double radius = dab.diameter * 0.5;
    const double radians = dab.angle * std::numbers::pi_v<double> / 180.0;
    const double cosine = std::cos(radians), sine = std::sin(radians);
    const double edge_pad = dab.hardness >= 1.0F ? 0.5 : 0.0;
    const double major = radius + edge_pad;
    const double minor = radius * dab.roundness + edge_pad;
    const double extent_x = std::hypot(major * cosine, minor * sine);
    const double extent_y = std::hypot(major * sine, minor * cosine);
    const auto clampBound = [](double value, std::uint32_t dimension) {
        const auto integer = static_cast<std::int64_t>(value);
        const auto maximum = std::min<std::uint64_t>(dimension, static_cast<std::uint64_t>(std::numeric_limits<std::int32_t>::max()));
        return static_cast<std::int32_t>(std::clamp(integer, std::int64_t{0}, static_cast<std::int64_t>(maximum)));
    };
    const int x0 = clampBound(std::floor(dab.x - extent_x), width_);
    const int y0 = clampBound(std::floor(dab.y - extent_y), height_);
    const int x1 = clampBound(std::ceil(dab.x + extent_x) + 1.0, width_);
    const int y1 = clampBound(std::ceil(dab.y + extent_y) + 1.0, height_);
    if (x0 >= x1 || y0 >= y1) return true;

    const double hard_radius = radius * dab.hardness;
    const double feather_width = std::max(1e-6, radius - hard_radius);
    const float dab_flow = std::clamp(dab.opacity * dab.flow, 0.0F, 1.0F);
    const int edge = static_cast<int>(kTileEdge);
    const double edge_radius = radius + edge_pad;
    for (int tile_y = y0 / edge; tile_y <= (y1 - 1) / edge; ++tile_y) {
        const int begin_y = std::max(y0, tile_y * edge), end_y = std::min(y1, (tile_y + 1) * edge);
        for (int tile_x = x0 / edge; tile_x <= (x1 - 1) / edge; ++tile_x) {
            const int begin_x = std::max(x0, tile_x * edge), end_x = std::min(x1, (tile_x + 1) * edge);
            auto* tile = mutableTile({tile_x, tile_y}, error);
            if (tile == nullptr) return false;
            for (int y = begin_y; y < end_y; ++y) {
                for (int x = begin_x; x < end_x; ++x) {
                    const double px = x + 0.5 - dab.x, py = y + 0.5 - dab.y;
                    const double local_x = cosine * px + sine * py;
                    const double local_y = (-sine * px + cosine * py) / dab.roundness;
                    const double distance_squared = local_x * local_x + local_y * local_y;
                    if (distance_squared >= edge_radius * edge_radius) continue;
                    const double distance = std::sqrt(distance_squared);
                    double coverage = 1.0;
                    if (edge_pad > 0.0) {
                        coverage = std::clamp(radius + 0.5 - distance, 0.0, 1.0);
                    } else if (distance >= radius) {
                        continue;
                    } else if (distance > hard_radius) {
                        const double t = std::clamp((distance - hard_radius) / feather_width, 0.0, 1.0);
                        coverage = 1.0 - t * t * (3.0 - 2.0 * t);
                    }
                    const auto index = static_cast<std::size_t>(y - tile_y * edge) * kTileEdge +
                                       static_cast<std::size_t>(x - tile_x * edge);
                    addCoverage(tile->data() + index * sizeof(std::uint16_t), dab_flow * static_cast<float>(coverage));
                }
            }
        }
    }
    return true;
}

bool StrokeMaskCompositor::compose(const TileStore& baseline, TileStore& target, const float color[4], float opacity, bool erase,
                                  bool preserve_alpha, std::string& error, const TileStore* selection_mask) const {
    if (&baseline == &target || !paint::detail::isRgba(baseline.format()) || target.format() != baseline.format() ||
        target.width() != baseline.width() || target.height() != baseline.height() || width_ != baseline.width() ||
        height_ != baseline.height()) {
        error = "stroke composition needs distinct, matching RGBA baseline and target stores";
        return false;
    }
    if (!validRgba(color) || !std::isfinite(opacity) || opacity < 0.0F || opacity > 1.0F) {
        error = "stroke color and opacity must be finite and in range";
        return false;
    }
    if (selection_mask != nullptr &&
        (selection_mask->format() != PixelFormat::A16 || selection_mask->width() != baseline.width() ||
         selection_mask->height() != baseline.height())) {
        error = "stroke selection mask must be a matching A16 document mask";
        return false;
    }
    if (erase && preserve_alpha) {
        error = "erasing is unavailable when transparency is locked";
        return false;
    }
    if (!erase && (opacity == 0.0F || color[3] == 0.0F)) return true;

    std::array<float, 4> source{color[0], color[1], color[2], color[3]};
    if (baseline.format() == PixelFormat::RGBA32F) {
        source[0] = srgbToLinear(source[0]);
        source[1] = srgbToLinear(source[1]);
        source[2] = srgbToLinear(source[2]);
    }

    const auto bpp = baseline.bytes_per_pixel();
    for (const auto& [coordinate, mask_tile] : coverage_tiles_) {
        const auto selection_tile = selection_mask == nullptr ? nullptr : selection_mask->readTile(coordinate);
        if (selection_mask != nullptr && selection_tile == nullptr) continue;
        const auto base_tile = baseline.readTile(coordinate);
        TileBuffer output_tile = base_tile ? *base_tile : TileBuffer(target.tile_byte_size(), 0);
        bool changed = false;
        std::size_t covered_pixels = 0;
        const auto tile_x = static_cast<std::uint32_t>(coordinate.x) * kTileEdge;
        const auto tile_y = static_cast<std::uint32_t>(coordinate.y) * kTileEdge;
        if (tile_x >= baseline.width() || tile_y >= baseline.height()) continue;
        const auto valid_width = std::min(kTileEdge, baseline.width() - tile_x);
        const auto valid_height = std::min(kTileEdge, baseline.height() - tile_y);
        const auto valid_pixels = static_cast<std::size_t>(valid_width) * valid_height;
        for (std::uint32_t local_y = 0; local_y < kTileEdge; ++local_y) {
            const std::uint32_t y = static_cast<std::uint32_t>(coordinate.y) * kTileEdge + local_y;
            if (y >= baseline.height()) break;
            for (std::uint32_t local_x = 0; local_x < kTileEdge; ++local_x) {
                const std::uint32_t x = static_cast<std::uint32_t>(coordinate.x) * kTileEdge + local_x;
                if (x >= baseline.width()) break;
                const auto index = (static_cast<std::size_t>(local_y) * kTileEdge + local_x);
                std::uint16_t raw_coverage = 0;
                std::memcpy(&raw_coverage, mask_tile.data() + index * sizeof(raw_coverage), sizeof(raw_coverage));
                if (raw_coverage == 0U) continue;

                float selection_coverage = 1.0F;
                if (selection_tile != nullptr) {
                    std::uint16_t raw_selection = 0;
                    std::memcpy(&raw_selection, selection_tile->data() + index * sizeof(raw_selection), sizeof(raw_selection));
                    if (raw_selection == 0U) continue;
                    selection_coverage = raw_selection / 65535.0F;
                }

                const float amount = std::clamp((raw_coverage / 65535.0F) * opacity * selection_coverage, 0.0F, 1.0F);
                if (amount == 0.0F) continue;
                ++covered_pixels;
                auto* pixel = output_tile.data() + index * bpp;
                std::array<float, 4> result{};
                paint::detail::readPixel(baseline.format(), pixel, result.data());
                if (erase) {
                    result[3] *= 1.0F - amount;
                    if (result[3] <= 0.0F) result = {0.0F, 0.0F, 0.0F, 0.0F};
                } else if (preserve_alpha) {
                    if (result[3] <= 0.0F) continue;
                    const float mix = std::clamp(amount * source[3], 0.0F, 1.0F);
                    for (std::size_t channel = 0; channel < 3; ++channel) {
                        result[channel] += (source[channel] - result[channel]) * mix;
                    }
                } else {
                    const float source_alpha = std::clamp(amount * source[3], 0.0F, 1.0F);
                    const float result_alpha = source_alpha + result[3] * (1.0F - source_alpha);
                    if (result_alpha <= 0.0F) {
                        result = {0.0F, 0.0F, 0.0F, 0.0F};
                    } else {
                        for (std::size_t channel = 0; channel < 3; ++channel) {
                            result[channel] = (source[channel] * source_alpha + result[channel] * result[3] * (1.0F - source_alpha)) /
                                              result_alpha;
                        }
                        result[3] = result_alpha;
                    }
                }
                paint::detail::writePixel(target.format(), pixel, result.data());
                changed = true;
            }
        }
        if (!changed) continue;
        // Non-erase composition cannot turn a covered pixel into an empty tile.
        // For erasing, only scan for all-zero storage when every in-canvas pixel
        // was covered; ordinary partial erases should not trigger another full
        // tile pass just to decide whether a sparse tile can be released.
        if (erase && covered_pixels == valid_pixels &&
            std::all_of(output_tile.begin(), output_tile.end(), [](std::uint8_t value) { return value == 0U; })) {
            if (!target.eraseTile(coordinate)) {
                error = "could not release empty stroke tile";
                return false;
            }
        } else if (!target.writeTile(coordinate, output_tile)) {
            error = "could not store composed stroke pixels";
            return false;
        }
    }
    return true;
}

bool StrokeMaskCompositor::composeMask(TileStore& target, float opacity, std::string& error) const {
    if (target.format() != PixelFormat::A16 || target.width() != width_ || target.height() != height_ ||
        !std::isfinite(opacity) || opacity < 0.0F || opacity > 1.0F) {
        error = "stroke mask target must be a matching A16 store";
        return false;
    }
    try {
        for (const auto& [coordinate, source] : coverage_tiles_) {
            TileBuffer tile = source;
            if (opacity < 1.0F) {
                for (std::size_t i = 0; i + sizeof(std::uint16_t) <= tile.size(); i += sizeof(std::uint16_t)) {
                    std::uint16_t value = 0;
                    std::memcpy(&value, tile.data() + i, sizeof(value));
                    value = static_cast<std::uint16_t>(std::clamp(value * opacity, 0.0F, 65535.0F) + 0.5F);
                    std::memcpy(tile.data() + i, &value, sizeof(value));
                }
            }
            if (!target.writeTile(coordinate, tile)) {
                error = "could not store stroke mask";
                return false;
            }
        }
        return true;
    } catch (const std::exception&) {
        error = "could not allocate stroke mask";
        return false;
    }
}

bool StrokeMaskCompositor::applyWetEdges(TileStore& target, float strength, const TileStore* selection_mask, std::string& error) const {
    if (!paint::detail::isRgba(target.format()) || target.width() != width_ || target.height() != height_ ||
        !std::isfinite(strength) || strength < 0.0F || strength > 1.0F ||
        (selection_mask != nullptr && (selection_mask->format() != PixelFormat::A16 || selection_mask->width() != width_ ||
                                       selection_mask->height() != height_))) {
        error = "wet edge target, strength, or selection mask is invalid";
        return false;
    }
    const auto coverageAt = [this](std::int64_t x, std::int64_t y) {
        if (x < 0 || y < 0 || x >= width_ || y >= height_) return 0.0F;
        const TileCoordinate coordinate{static_cast<std::int32_t>(x / kTileEdge), static_cast<std::int32_t>(y / kTileEdge)};
        const auto found = coverage_tiles_.find(coordinate);
        if (found == coverage_tiles_.end()) return 0.0F;
        const auto index = (static_cast<std::size_t>(y % kTileEdge) * kTileEdge + static_cast<std::size_t>(x % kTileEdge)) * sizeof(std::uint16_t);
        std::uint16_t value = 0;
        std::memcpy(&value, found->second.data() + index, sizeof(value));
        return value / 65535.0F;
    };
    const auto bpp = target.bytes_per_pixel();
    for (const auto& [coordinate, mask_tile] : coverage_tiles_) {
        auto output = target.readTile(coordinate);
        if (output == nullptr) continue;
        const auto selection = selection_mask == nullptr ? nullptr : selection_mask->readTile(coordinate);
        if (selection_mask != nullptr && selection == nullptr) continue;
        TileBuffer changed = *output;
        const auto origin_x = static_cast<std::int64_t>(coordinate.x) * kTileEdge;
        const auto origin_y = static_cast<std::int64_t>(coordinate.y) * kTileEdge;
        const auto valid_width = std::min<std::uint32_t>(kTileEdge, width_ - static_cast<std::uint32_t>(origin_x));
        const auto valid_height = std::min<std::uint32_t>(kTileEdge, height_ - static_cast<std::uint32_t>(origin_y));
        bool modified = false;
        for (std::uint32_t y = 0; y < valid_height; ++y) for (std::uint32_t x = 0; x < valid_width; ++x) {
            const auto index = static_cast<std::size_t>(y) * kTileEdge + x;
            const auto coverage = coverageAt(origin_x + x, origin_y + y);
            if (coverage <= 0.0F) continue;
            float selection_coverage = 1.0F;
            if (selection != nullptr) {
                std::uint16_t selected = 0;
                std::memcpy(&selected, selection->data() + index * sizeof(selected), sizeof(selected));
                if (selected == 0) continue;
                selection_coverage = selected / 65535.0F;
            }
            const auto neighbor = std::min({coverageAt(origin_x + x - 1, origin_y + y), coverageAt(origin_x + x + 1, origin_y + y),
                                            coverageAt(origin_x + x, origin_y + y - 1), coverageAt(origin_x + x, origin_y + y + 1)});
            const float rim = std::clamp(coverage - neighbor, 0.0F, 1.0F);
            if (rim <= 0.0F) continue;
            auto* pixel = changed.data() + index * bpp;
            std::array<float, 4> rgba{};
            paint::detail::readPixel(target.format(), pixel, rgba.data());
            const float darken = std::clamp(1.0F - strength * rim * selection_coverage * 0.55F, 0.0F, 1.0F);
            for (std::size_t c = 0; c < 3; ++c) rgba[c] *= darken;
            paint::detail::writePixel(target.format(), pixel, rgba.data());
            modified = true;
        }
        if (modified && !target.writeTile(coordinate, changed)) {
            error = "could not store wet edge pixels";
            return false;
        }
    }
    return true;
}

bool StrokeMaskCompositor::applySelectionMask(const TileStore& baseline, const TileStore& painted, TileStore& target,
                                              const TileStore& selection_mask,
                                              std::span<const TileCoordinate> affected_tiles, std::string& error) {
    if (&baseline == &target || !paint::detail::isRgba(baseline.format()) || painted.format() != baseline.format() ||
        target.format() != baseline.format() || painted.width() != baseline.width() || target.width() != baseline.width() ||
        painted.height() != baseline.height() || target.height() != baseline.height() || selection_mask.format() != PixelFormat::A16 ||
        selection_mask.width() != baseline.width() || selection_mask.height() != baseline.height()) {
        error = "selection clipping needs matching RGBA stores and an A16 mask; target must not be the baseline";
        return false;
    }

    const auto bpp = baseline.bytes_per_pixel();
    for (const auto coordinate : affected_tiles) {
        const auto tile_x = static_cast<std::int64_t>(coordinate.x) * kTileEdge;
        const auto tile_y = static_cast<std::int64_t>(coordinate.y) * kTileEdge;
        if (coordinate.x < 0 || coordinate.y < 0 || tile_x >= baseline.width() || tile_y >= baseline.height()) {
            error = "selection clipping tile coordinate is outside the document";
            return false;
        }

        const auto base_tile = baseline.readTile(coordinate);
        const auto painted_tile = painted.readTile(coordinate);
        const auto selection_tile = selection_mask.readTile(coordinate);
        if (selection_tile == nullptr) {
            if (base_tile != nullptr) {
                if (!target.writeTile(coordinate, *base_tile)) {
                    error = "could not restore baseline outside the selection";
                    return false;
                }
            } else if (!target.eraseTile(coordinate)) {
                error = "could not clear pixels outside the selection";
                return false;
            }
            continue;
        }

        TileBuffer output = base_tile ? *base_tile : TileBuffer(target.tile_byte_size(), 0);
        const auto valid_width = std::min(kTileEdge, baseline.width() - static_cast<std::uint32_t>(tile_x));
        const auto valid_height = std::min(kTileEdge, baseline.height() - static_cast<std::uint32_t>(tile_y));
        for (std::uint32_t y = 0; y < valid_height; ++y) {
            for (std::uint32_t x = 0; x < valid_width; ++x) {
                const auto index = static_cast<std::size_t>(y) * kTileEdge + x;
                std::uint16_t raw_coverage = 0;
                std::memcpy(&raw_coverage, selection_tile->data() + index * sizeof(raw_coverage), sizeof(raw_coverage));
                if (raw_coverage == 0U) continue;
                auto* output_pixel = output.data() + index * bpp;
                const auto* painted_pixel = painted_tile == nullptr ? nullptr : painted_tile->data() + index * bpp;
                if (raw_coverage == 65535U) {
                    if (painted_pixel == nullptr) std::memset(output_pixel, 0, bpp);
                    else std::memcpy(output_pixel, painted_pixel, bpp);
                    continue;
                }

                std::array<float, 4> base{};
                std::array<float, 4> result{};
                paint::detail::readPixel(baseline.format(), output_pixel, base.data());
                if (painted_pixel != nullptr) paint::detail::readPixel(painted.format(), painted_pixel, result.data());
                const float mix = raw_coverage / 65535.0F;
                const float alpha = base[3] + (result[3] - base[3]) * mix;
                for (std::size_t channel = 0; channel < 3; ++channel) {
                    const float base_premultiplied = base[channel] * base[3];
                    const float painted_premultiplied = result[channel] * result[3];
                    const float premultiplied = base_premultiplied + (painted_premultiplied - base_premultiplied) * mix;
                    result[channel] = alpha > 0.0F ? premultiplied / alpha : 0.0F;
                }
                result[3] = alpha;
                paint::detail::writePixel(target.format(), output_pixel, result.data());
            }
        }

        if (std::all_of(output.begin(), output.end(), [](std::uint8_t value) { return value == 0U; })) {
            if (!target.eraseTile(coordinate)) {
                error = "could not release empty selection-clipped tile";
                return false;
            }
        } else if (!target.writeTile(coordinate, output)) {
            error = "could not store selection-clipped pixels";
            return false;
        }
    }
    return true;
}

}  // namespace croptop::brush
