#include "selection_modify.h"

#include <algorithm>
#include <array>
#include <cmath>
#include <cstring>
#include <exception>
#include <limits>
#include <vector>

#include "selection_internal.h"
#include "selection_mask.h"

namespace croptop::selection {
namespace {

using namespace detail;

std::optional<TileStore> copyToAllocator(const TileStore& allocation_like, const TileStore& source, std::string& error) {
    if (allocation_like.width() != source.width() || allocation_like.height() != source.height()) {
        error = "allocation template must match the selection dimensions";
        return std::nullopt;
    }
    TileStore out = allocation_like.emptyLike(PixelFormat::A16);
    for (const auto coordinate : source.tileCoordinates()) {
        const auto tile = source.readTile(coordinate);
        if (tile != nullptr && !out.writeTile(coordinate, *tile)) {
            error = "could not store modified selection in the document allocator";
            return std::nullopt;
        }
    }
    return out;
}

bool validate(const TileStore& selection, float radius, float max_radius, std::string& error) {
    if (selection.format() != PixelFormat::A16 || selection.width() == 0U || selection.height() == 0U) {
        error = "selection must be a non-empty A16 mask";
        return false;
    }
    if (!std::isfinite(radius) || radius < 0.0F || radius > max_radius) {
        error = "radius must be between 0 and " + std::to_string(static_cast<int>(max_radius)) + " px";
        return false;
    }
    return true;
}

/**
 * Whether the region's temporaries fit the working-memory limit: `bytes_per_pixel` of full planes plus
 * `line_bytes` per element of the longest side for each worker (the distance transform keeps row/column
 * buffers per worker thread).
 */
bool fits(Region region, std::uint64_t bytes_per_pixel, std::uint64_t line_bytes, const ModifyLimits& limits,
          std::string& error) {
    const auto longest = static_cast<std::uint64_t>(std::max(region.width(), region.height()));
    const auto workers = static_cast<std::uint64_t>(parallel::workerCount(0));
    const auto planes = region.pixels() * bytes_per_pixel;
    if (region.pixels() > limits.max_working_bytes / bytes_per_pixel || planes + longest * line_bytes * workers > limits.max_working_bytes) {
        error = "selection is too large to modify within the memory limit";
        return false;
    }
    return true;
}

std::uint16_t toCoverage(double value) {
    return static_cast<std::uint16_t>(std::lround(std::clamp(value, 0.0, 1.0) * 65535.0));
}

/** One separable box pass of odd `size` with zero padding, in place (horizontal then vertical). */
void boxBlur(std::vector<std::uint16_t>& plane, std::uint32_t width, std::uint32_t height, std::uint32_t size) {
    if (size <= 1U) return;
    const auto radius = static_cast<std::int64_t>(size / 2U);
    std::vector<std::uint16_t> row(width);
    for (std::uint32_t y = 0; y < height; ++y) {
        auto* line = plane.data() + static_cast<std::size_t>(y) * width;
        std::copy_n(line, width, row.data());
        std::uint64_t sum = 0;
        for (std::int64_t x = 0; x < std::min<std::int64_t>(radius, width); ++x) sum += row[static_cast<std::size_t>(x)];
        for (std::int64_t x = 0; x < width; ++x) {
            if (x + radius < width) sum += row[static_cast<std::size_t>(x + radius)];
            line[x] = static_cast<std::uint16_t>((sum + size / 2U) / size);
            if (x - radius >= 0) sum -= row[static_cast<std::size_t>(x - radius)];
        }
    }
    // Vertical: running column sums over a sliding window of rows (cache-friendly).
    std::vector<std::uint64_t> sums(width, 0);
    std::vector<std::uint16_t> window(static_cast<std::size_t>(width) * height);
    std::copy(plane.begin(), plane.end(), window.begin());
    for (std::int64_t y = 0; y < std::min<std::int64_t>(radius, height); ++y) {
        for (std::uint32_t x = 0; x < width; ++x) sums[x] += window[static_cast<std::size_t>(y) * width + x];
    }
    for (std::int64_t y = 0; y < height; ++y) {
        if (y + radius < height) {
            for (std::uint32_t x = 0; x < width; ++x) sums[x] += window[static_cast<std::size_t>(y + radius) * width + x];
        }
        auto* line = plane.data() + static_cast<std::size_t>(y) * width;
        for (std::uint32_t x = 0; x < width; ++x) line[x] = static_cast<std::uint16_t>((sums[x] + size / 2U) / size);
        if (y - radius >= 0) {
            for (std::uint32_t x = 0; x < width; ++x) sums[x] -= window[static_cast<std::size_t>(y - radius) * width + x];
        }
    }
}

std::vector<std::uint8_t> threshold(const std::vector<std::uint16_t>& plane, bool selected) {
    std::vector<std::uint8_t> out(plane.size());
    for (std::size_t i = 0; i < plane.size(); ++i) out[i] = (plane[i] >= kHalf) == selected ? 1U : 0U;
    return out;
}

/** Shared implementation of expand (distance to selected) and contract (distance to unselected). */
std::optional<TileStore> morph(const TileStore& selection, float radius, bool grow, std::string& error,
                               const ModifyLimits& limits, const TileStore* allocation_like = nullptr) {
    const auto bounds = selection::bounds(selection);
    if (!bounds || radius == 0.0F) return allocation_like == nullptr
        ? std::optional<TileStore>{selection} : copyToAllocator(*allocation_like, selection, error);
    const auto region = regionAround(selection, *bounds, grow ? static_cast<std::int64_t>(std::ceil(radius)) + 1 : 1);
    // Full planes: uint16 coverage (2) + binary source (1) + float distance field (4) = 7 bytes/pixel.
    // Line buffers over the longest side: in/out floats (8) + envelope v (8) + z floats (4) = 20 bytes.
    if (!fits(region, 7U, 20U, limits, error)) return std::nullopt;
    auto plane = extract(selection, region);
    const auto field = distanceField(region, threshold(plane, grow));
    for (std::size_t i = 0; i < plane.size(); ++i) {
        const auto distance = field[i] >= kInfinity / 2 ? static_cast<double>(kInfinity) : std::sqrt(static_cast<double>(field[i]));
        plane[i] = grow ? toCoverage(radius + 1.0 - distance) : toCoverage(distance - radius);
    }
    return allocation_like == nullptr ? replaceRegion(selection, region, plane, error)
                                      : replaceRegion(*allocation_like, selection, region, plane, error);
}

}  // namespace

std::optional<TileStore> feather(const TileStore& selection, float radius, std::string& error, const ModifyLimits& limits) {
    if (!validate(selection, radius, 1000.0F, error)) return std::nullopt;
    const auto bounds = selection::bounds(selection);
    if (!bounds || radius == 0.0F) return selection;
    const auto sizes = boxSizes(radius / 2.0);
    const auto reach = static_cast<std::int64_t>((sizes[0] + sizes[1] + sizes[2]) / 2U) + 1;
    const auto region = regionAround(selection, *bounds, reach);
    // Full planes: uint16 coverage (2) + vertical window copy (2). Lines: row copy (2) + column sums (8).
    if (!fits(region, 4U, 10U, limits, error)) return std::nullopt;
    auto plane = extract(selection, region);
    for (const auto size : sizes) boxBlur(plane, region.width(), region.height(), size);
    return replaceRegion(selection, region, plane, error);
}

std::optional<TileStore> expand(const TileStore& selection, float radius, std::string& error, const ModifyLimits& limits) {
    if (!validate(selection, radius, 500.0F, error)) return std::nullopt;
    return morph(selection, radius, true, error, limits);
}

std::optional<TileStore> contract(const TileStore& selection, float radius, std::string& error, const ModifyLimits& limits) {
    if (!validate(selection, radius, 500.0F, error)) return std::nullopt;
    return morph(selection, radius, false, error, limits);
}

std::optional<TileStore> border(const TileStore& selection, float width, std::string& error, const ModifyLimits& limits) {
    if (!validate(selection, width, 200.0F, error)) return std::nullopt;
    if (!selection::bounds(selection) || width == 0.0F) return emptyMask(selection);
    const auto outer = morph(selection, width / 2.0F, true, error, limits);
    const auto inner = outer ? morph(selection, width / 2.0F, false, error, limits) : std::nullopt;
    if (!outer || !inner) return std::nullopt;
    return combine(*outer, *inner, Op::Subtract);
}

std::optional<TileStore> smooth(const TileStore& selection, float radius, std::string& error, const ModifyLimits& limits) {
    if (!validate(selection, radius, 500.0F, error)) return std::nullopt;
    const auto bounds = selection::bounds(selection);
    const auto r = static_cast<std::int64_t>(std::lround(radius));
    if (!bounds || r == 0) return selection;
    const auto region = regionAround(selection, *bounds, r + 1);
    // Full planes: uint16 coverage (2) + uint32 integral image (4, one extra row and column).
    if (!fits(region, 6U, 8U, limits, error)) return std::nullopt;
    auto plane = extract(selection, region);
    const auto width = static_cast<std::int64_t>(region.width());
    const auto height = static_cast<std::int64_t>(region.height());
    std::vector<std::uint32_t> integral(static_cast<std::size_t>((width + 1) * (height + 1)), 0);
    const auto at = [&](std::int64_t x, std::int64_t y) -> std::uint32_t& {
        return integral[static_cast<std::size_t>(y * (width + 1) + x)];
    };
    for (std::int64_t y = 0; y < height; ++y) {
        std::uint32_t row = 0;
        for (std::int64_t x = 0; x < width; ++x) {
            row += plane[static_cast<std::size_t>(y * width + x)] >= kHalf ? 1U : 0U;
            at(x + 1, y + 1) = at(x + 1, y) + row;
        }
    }
    const auto canvas_width = static_cast<std::int64_t>(selection.width());
    const auto canvas_height = static_cast<std::int64_t>(selection.height());
    for (std::int64_t y = 0; y < height; ++y) {
        for (std::int64_t x = 0; x < width; ++x) {
            // Window in canvas coordinates; selected pixels only exist inside the region.
            const auto gx = x + region.x0;
            const auto gy = y + region.y0;
            const auto total = (std::min(gx + r + 1, canvas_width) - std::max<std::int64_t>(gx - r, 0)) *
                               (std::min(gy + r + 1, canvas_height) - std::max<std::int64_t>(gy - r, 0));
            const auto x0 = std::clamp<std::int64_t>(x - r, 0, width);
            const auto x1 = std::clamp<std::int64_t>(x + r + 1, 0, width);
            const auto y0 = std::clamp<std::int64_t>(y - r, 0, height);
            const auto y1 = std::clamp<std::int64_t>(y + r + 1, 0, height);
            const auto selected = static_cast<std::int64_t>(at(x1, y1)) - at(x0, y1) - at(x1, y0) + at(x0, y0);
            auto& value = plane[static_cast<std::size_t>(y * width + x)];
            if (selected * 2 > total) value = kFullySelected;
            else if (selected * 2 < total) value = 0;
            else value = value >= kHalf ? kFullySelected : 0;
        }
    }
    return replaceRegion(selection, region, plane, error);
}

std::optional<TileStore> feather(const TileStore& allocation_like, const TileStore& selection, float radius,
                                 std::string& error, const ModifyLimits& limits) {
    error.clear();
    if (selection.format() != PixelFormat::A16 || selection.width() == 0U || selection.height() == 0U) {
        error = "selection must be a non-empty A16 mask";
        return std::nullopt;
    }
    if (!std::isfinite(radius) || radius < 0.0F || radius > 1000.0F) {
        error = "radius must be between 0 and 1000 px";
        return std::nullopt;
    }
    if (allocation_like.width() != selection.width() || allocation_like.height() != selection.height()) {
        error = "allocation template must match the selection dimensions";
        return std::nullopt;
    }
    const auto bounds = selection::bounds(selection);
    if (!bounds || radius == 0.0F) return copyToAllocator(allocation_like, selection, error);
    const auto sizes = boxSizes(radius / 2.0);
    const auto reach = static_cast<std::int64_t>((sizes[0] + sizes[1] + sizes[2]) / 2U) + 1;
    const auto region = regionAround(selection, *bounds, reach);
    if (!fits(region, 4U, 10U, limits, error)) return std::nullopt;
    auto plane = extract(selection, region);
    for (const auto size : sizes) boxBlur(plane, region.width(), region.height(), size);
    return replaceRegion(allocation_like, selection, region, plane, error);
}

std::optional<TileStore> expand(const TileStore& allocation_like, const TileStore& selection, float radius,
                                std::string& error, const ModifyLimits& limits) {
    error.clear();
    if (allocation_like.width() != selection.width() || allocation_like.height() != selection.height()) {
        error = "allocation template must match the selection dimensions";
        return std::nullopt;
    }
    if (!validate(selection, radius, 500.0F, error)) return std::nullopt;
    return morph(selection, radius, true, error, limits, &allocation_like);
}

std::optional<TileStore> contract(const TileStore& allocation_like, const TileStore& selection, float radius,
                                  std::string& error, const ModifyLimits& limits) {
    error.clear();
    if (allocation_like.width() != selection.width() || allocation_like.height() != selection.height()) {
        error = "allocation template must match the selection dimensions";
        return std::nullopt;
    }
    if (!validate(selection, radius, 500.0F, error)) return std::nullopt;
    return morph(selection, radius, false, error, limits, &allocation_like);
}

std::optional<TileStore> border(const TileStore& allocation_like, const TileStore& selection, float width,
                                std::string& error, const ModifyLimits& limits) {
    error.clear();
    if (allocation_like.width() != selection.width() || allocation_like.height() != selection.height()) {
        error = "allocation template must match the selection dimensions";
        return std::nullopt;
    }
    if (!validate(selection, width, 200.0F, error)) return std::nullopt;
    if (!selection::bounds(selection) || width == 0.0F) {
        TileStore empty = allocation_like.emptyLike(PixelFormat::A16);
        return empty;
    }
    const auto outer = morph(selection, width / 2.0F, true, error, limits, &allocation_like);
    const auto inner = outer ? morph(*outer, width / 2.0F, false, error, limits, &allocation_like) : std::nullopt;
    if (!outer || !inner) return std::nullopt;
    return combine(allocation_like, *outer, *inner, Op::Subtract, &error);
}

std::optional<TileStore> smooth(const TileStore& allocation_like, const TileStore& selection, float radius,
                                std::string& error, const ModifyLimits& limits) {
    error.clear();
    if (allocation_like.width() != selection.width() || allocation_like.height() != selection.height()) {
        error = "allocation template must match the selection dimensions";
        return std::nullopt;
    }
    if (!validate(selection, radius, 500.0F, error)) return std::nullopt;
    const auto bounds = selection::bounds(selection);
    const auto r = static_cast<std::int64_t>(std::lround(radius));
    if (!bounds || r == 0) return copyToAllocator(allocation_like, selection, error);
    const auto region = regionAround(selection, *bounds, r + 1);
    if (!fits(region, 6U, 8U, limits, error)) return std::nullopt;
    auto plane = extract(selection, region);
    const auto width = static_cast<std::int64_t>(region.width());
    const auto height = static_cast<std::int64_t>(region.height());
    std::vector<std::uint32_t> integral(static_cast<std::size_t>((width + 1) * (height + 1)), 0);
    const auto at = [&](std::int64_t x, std::int64_t y) -> std::uint32_t& {
        return integral[static_cast<std::size_t>(y * (width + 1) + x)];
    };
    for (std::int64_t y = 0; y < height; ++y) {
        std::uint32_t row = 0;
        for (std::int64_t x = 0; x < width; ++x) {
            row += plane[static_cast<std::size_t>(y * width + x)] >= kHalf ? 1U : 0U;
            at(x + 1, y + 1) = at(x + 1, y) + row;
        }
    }
    const auto canvas_width = static_cast<std::int64_t>(selection.width());
    const auto canvas_height = static_cast<std::int64_t>(selection.height());
    for (std::int64_t y = 0; y < height; ++y) for (std::int64_t x = 0; x < width; ++x) {
        const auto gx = x + region.x0;
        const auto gy = y + region.y0;
        const auto total = (std::min(gx + r + 1, canvas_width) - std::max<std::int64_t>(gx - r, 0)) *
                           (std::min(gy + r + 1, canvas_height) - std::max<std::int64_t>(gy - r, 0));
        const auto x0 = std::clamp<std::int64_t>(x - r, 0, width);
        const auto x1 = std::clamp<std::int64_t>(x + r + 1, 0, width);
        const auto y0 = std::clamp<std::int64_t>(y - r, 0, height);
        const auto y1 = std::clamp<std::int64_t>(y + r + 1, 0, height);
        const auto selected = static_cast<std::int64_t>(at(x1, y1)) - at(x0, y1) - at(x1, y0) + at(x0, y0);
        auto& value = plane[static_cast<std::size_t>(y * width + x)];
        if (selected * 2 > total) value = kFullySelected;
        else if (selected * 2 < total) value = 0;
        else value = value >= kHalf ? kFullySelected : 0;
    }
    return replaceRegion(allocation_like, selection, region, plane, error);
}

}  // namespace croptop::selection
