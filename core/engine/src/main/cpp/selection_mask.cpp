#include "selection_mask.h"

#include <algorithm>
#include <cmath>
#include <cstring>
#include <limits>
#include <stdexcept>

namespace croptop::selection {
namespace {

constexpr std::uint32_t kSubRows = 16;
constexpr std::size_t kPixelBytes = 2;

std::uint32_t tileColumns(std::uint32_t width) { return 1U + (width - 1U) / kTileEdge; }
std::uint32_t tileRows(std::uint32_t height) { return 1U + (height - 1U) / kTileEdge; }

std::uint16_t readPixel(const TileBuffer& tile, std::uint32_t local_x, std::uint32_t local_y) {
    std::uint16_t value = 0;
    std::memcpy(&value, tile.data() + (static_cast<std::size_t>(local_y) * kTileEdge + local_x) * kPixelBytes,
                sizeof(value));
    return value;
}

void writePixel(TileBuffer& tile, std::uint32_t local_x, std::uint32_t local_y, std::uint16_t value) {
    std::memcpy(tile.data() + (static_cast<std::size_t>(local_y) * kTileEdge + local_x) * kPixelBytes, &value,
                sizeof(value));
}

bool allZero(const TileBuffer& tile) {
    return std::all_of(tile.begin(), tile.end(), [](std::uint8_t value) { return value == 0U; });
}

std::uint16_t toCoverage(double value) {
    return static_cast<std::uint16_t>(std::lround(std::clamp(value, 0.0, 1.0) * 65535.0));
}

/** Collects rows of coverage for x in [x_begin, x_end) into 256-row tile bands and writes nonzero tiles. */
class BandWriter final {
public:
    BandWriter(TileStore& out, std::uint32_t x_begin, std::uint32_t x_end)
        : out_(out), x_begin_(x_begin), x_end_(x_end), first_column_(x_begin / kTileEdge),
          last_column_((x_end - 1U) / kTileEdge),
          tiles_(last_column_ - first_column_ + 1U, TileBuffer(out.tile_byte_size(), 0)) {}

    void writeRow(std::uint32_t y, std::span<const std::uint16_t> row) {
        const auto band = y / kTileEdge;
        if (band != band_) {
            flush();
            band_ = band;
        }
        for (std::uint32_t x = x_begin_; x < x_end_; ++x) {
            const auto value = row[x - x_begin_];
            if (value == 0U) continue;
            writePixel(tiles_[x / kTileEdge - first_column_], x % kTileEdge, y % kTileEdge, value);
            dirty_ = true;
        }
    }

    void flush() {
        if (!dirty_) return;
        for (std::uint32_t column = first_column_; column <= last_column_; ++column) {
            auto& tile = tiles_[column - first_column_];
            if (!allZero(tile)) {
                if (!out_.writeTile({static_cast<std::int32_t>(column), static_cast<std::int32_t>(band_)}, tile)) {
                    throw std::runtime_error("could not store selection mask tile");
                }
                std::fill(tile.begin(), tile.end(), 0U);
            }
        }
        dirty_ = false;
    }

private:
    TileStore& out_;
    std::uint32_t x_begin_;
    std::uint32_t x_end_;
    std::uint32_t first_column_;
    std::uint32_t last_column_;
    std::vector<TileBuffer> tiles_;
    std::uint32_t band_ = std::numeric_limits<std::uint32_t>::max();
    bool dirty_ = false;
};

/** Per-row coverage accumulator: fractional end pixels plus a difference array for full runs. */
class RowCoverage final {
public:
    RowCoverage(std::uint32_t x_begin, std::uint32_t x_end)
        : x_begin_(x_begin), x_end_(x_end), fraction_(x_end - x_begin, 0.0), runs_(x_end - x_begin + 1U, 0.0) {}

    void clear() {
        std::fill(fraction_.begin(), fraction_.end(), 0.0);
        std::fill(runs_.begin(), runs_.end(), 0.0);
    }

    /** Adds weight × overlap([i, i+1), [a, b)) to every pixel i. */
    void addSpan(double a, double b, double weight) {
        a = std::max(a, static_cast<double>(x_begin_));
        b = std::min(b, static_cast<double>(x_end_));
        if (!(b > a)) return;
        const auto first = static_cast<std::uint32_t>(std::floor(a));
        const auto last = static_cast<std::uint32_t>(std::floor(b));
        if (first == last) {
            fraction_[first - x_begin_] += (b - a) * weight;
            return;
        }
        fraction_[first - x_begin_] += (static_cast<double>(first) + 1.0 - a) * weight;
        runs_[first + 1U - x_begin_] += weight;
        runs_[last - x_begin_] -= weight;
        if (last < x_end_) fraction_[last - x_begin_] += (b - static_cast<double>(last)) * weight;
    }

    /** Selects pixels whose centers lie in [a, b). */
    void addCenters(double a, double b) {
        const auto first = std::max<double>(std::ceil(a - 0.5), x_begin_);
        const auto end = std::min<double>(std::ceil(b - 0.5), x_end_);
        if (!(end > first)) return;
        runs_[static_cast<std::size_t>(first) - x_begin_] += 1.0;
        runs_[static_cast<std::size_t>(end) - x_begin_] -= 1.0;
    }

    void emit(std::vector<std::uint16_t>& row) const {
        double run = 0.0;
        for (std::size_t i = 0; i < fraction_.size(); ++i) {
            run += runs_[i];
            row[i] = toCoverage(run + fraction_[i]);
        }
    }

private:
    std::uint32_t x_begin_;
    std::uint32_t x_end_;
    std::vector<double> fraction_;
    std::vector<double> runs_;
};

struct Extent {
    std::uint32_t x_begin = 0;
    std::uint32_t x_end = 0;
    std::uint32_t y_begin = 0;
    std::uint32_t y_end = 0;
    bool empty() const { return x_end <= x_begin || y_end <= y_begin; }
};

Extent clampExtent(double left, double top, double right, double bottom, std::uint32_t width, std::uint32_t height) {
    const auto clamp_to = [](double value, std::uint32_t limit) {
        if (!(value > 0.0)) return 0U;
        if (value >= static_cast<double>(limit)) return limit;
        return static_cast<std::uint32_t>(value);
    };
    return {clamp_to(std::floor(left), width), clamp_to(std::ceil(right), width), clamp_to(std::floor(top), height),
            clamp_to(std::ceil(bottom), height)};
}

/**
 * Rasterizes a shape given as horizontal spans per sub-scanline. `spans(y, out)` fills [a, b) pairs for
 * the horizontal line at document y.
 */
template <typename Spans>
TileStore rasterizeSpans(TileStore out, Extent extent, bool antialias, Spans&& spans) {
    const auto width = out.width();
    const auto height = out.height();
    if (width == 0U || height == 0U || extent.empty()) return out;
    BandWriter writer(out, extent.x_begin, extent.x_end);
    RowCoverage coverage(extent.x_begin, extent.x_end);
    std::vector<std::uint16_t> row(extent.x_end - extent.x_begin);
    std::vector<std::pair<double, double>> scratch;
    for (auto y = extent.y_begin; y < extent.y_end; ++y) {
        coverage.clear();
        if (antialias) {
            for (std::uint32_t sub = 0; sub < kSubRows; ++sub) {
                scratch.clear();
                spans(static_cast<double>(y) + (static_cast<double>(sub) + 0.5) / kSubRows, scratch);
                for (const auto& [a, b] : scratch) coverage.addSpan(a, b, 1.0 / kSubRows);
            }
        } else {
            scratch.clear();
            spans(static_cast<double>(y) + 0.5, scratch);
            for (const auto& [a, b] : scratch) coverage.addCenters(a, b);
        }
        coverage.emit(row);
        writer.writeRow(y, row);
    }
    writer.flush();
    return out;
}

bool finite(RectF rect) {
    return std::isfinite(rect.left) && std::isfinite(rect.top) && std::isfinite(rect.right) && std::isfinite(rect.bottom);
}

RectF normalized(RectF rect) {
    return {std::min(rect.left, rect.right), std::min(rect.top, rect.bottom), std::max(rect.left, rect.right),
            std::max(rect.top, rect.bottom)};
}

/** Applies `combine(a, b)` to every in-document pixel of one tile. */
template <typename Combine>
TileBuffer combineTile(const TileBuffer* a, const TileBuffer* b, std::size_t bytes, Combine&& combine) {
    TileBuffer out(bytes, 0);
    for (std::uint32_t y = 0; y < kTileEdge; ++y) {
        for (std::uint32_t x = 0; x < kTileEdge; ++x) {
            const std::uint16_t va = a == nullptr ? 0 : readPixel(*a, x, y);
            const std::uint16_t vb = b == nullptr ? 0 : readPixel(*b, x, y);
            writePixel(out, x, y, combine(va, vb));
        }
    }
    return out;
}

bool putTile(TileStore& store, TileCoordinate coordinate, const TileBuffer& tile) {
    if (allZero(tile)) return store.eraseTile(coordinate);
    return store.writeTile(coordinate, tile);
}

}  // namespace

TileStore emptyMask(std::uint32_t width, std::uint32_t height) { return TileStore(PixelFormat::A16, width, height); }
TileStore emptyMask(const TileStore& allocation_like) { return allocation_like.emptyLike(PixelFormat::A16); }

TileStore fullMask(std::uint32_t width, std::uint32_t height) {
    return rasterizeRect(width, height, {0.0, 0.0, static_cast<double>(width), static_cast<double>(height)}, false);
}

TileStore fullMask(const TileStore& allocation_like) {
    return rasterizeRect(allocation_like,
                         {0.0, 0.0, static_cast<double>(allocation_like.width()),
                          static_cast<double>(allocation_like.height())},
                         false);
}

TileStore rasterizeRect(std::uint32_t width, std::uint32_t height, RectF rect, bool antialias) {
    return rasterizeRect(TileStore(PixelFormat::A16, width, height), rect, antialias);
}

TileStore rasterizeRect(const TileStore& allocation_like, RectF rect, bool antialias) {
    auto out = emptyMask(allocation_like);
    const auto width = out.width();
    const auto height = out.height();
    if (!finite(rect) || width == 0U || height == 0U) return out;
    rect = normalized(rect);
    if (!antialias) {
        // Pixels whose centers are inside.
        rect = {std::ceil(rect.left - 0.5), std::ceil(rect.top - 0.5), std::ceil(rect.right - 0.5),
                std::ceil(rect.bottom - 0.5)};
    }
    const auto extent = clampExtent(rect.left, rect.top, rect.right, rect.bottom, width, height);
    if (extent.empty()) return out;
    BandWriter writer(out, extent.x_begin, extent.x_end);
    std::vector<double> horizontal(extent.x_end - extent.x_begin);
    for (auto x = extent.x_begin; x < extent.x_end; ++x) {
        horizontal[x - extent.x_begin] = std::max(0.0, std::min(rect.right, x + 1.0) - std::max(rect.left, static_cast<double>(x)));
    }
    std::vector<std::uint16_t> row(horizontal.size());
    for (auto y = extent.y_begin; y < extent.y_end; ++y) {
        const auto vertical = std::max(0.0, std::min(rect.bottom, y + 1.0) - std::max(rect.top, static_cast<double>(y)));
        for (std::size_t i = 0; i < row.size(); ++i) row[i] = toCoverage(vertical * horizontal[i]);
        writer.writeRow(y, row);
    }
    writer.flush();
    return out;
}

TileStore rasterizeEllipse(std::uint32_t width, std::uint32_t height, RectF bounds, bool antialias) {
    return rasterizeEllipse(TileStore(PixelFormat::A16, width, height), bounds, antialias);
}

TileStore rasterizeEllipse(const TileStore& allocation_like, RectF bounds, bool antialias) {
    const auto width = allocation_like.width();
    const auto height = allocation_like.height();
    if (!finite(bounds)) return emptyMask(allocation_like);
    bounds = normalized(bounds);
    const auto cx = (bounds.left + bounds.right) / 2.0;
    const auto cy = (bounds.top + bounds.bottom) / 2.0;
    const auto rx = (bounds.right - bounds.left) / 2.0;
    const auto ry = (bounds.bottom - bounds.top) / 2.0;
    if (!(rx > 0.0) || !(ry > 0.0)) return emptyMask(allocation_like);
    return rasterizeSpans(emptyMask(allocation_like),
                          clampExtent(bounds.left, bounds.top, bounds.right, bounds.bottom, width, height),
                          antialias, [&](double y, std::vector<std::pair<double, double>>& out) {
                              const auto dy = (y - cy) / ry;
                              if (dy * dy >= 1.0) return;
                              const auto half = rx * std::sqrt(1.0 - dy * dy);
                              out.emplace_back(cx - half, cx + half);
                          });
}

TileStore rasterizePolygon(std::uint32_t width, std::uint32_t height, std::span<const Point> points, bool antialias) {
    return rasterizePolygon(TileStore(PixelFormat::A16, width, height), points, antialias);
}

TileStore rasterizePolygon(const TileStore& allocation_like, std::span<const Point> points, bool antialias) {
    const auto width = allocation_like.width();
    const auto height = allocation_like.height();
    if (points.size() < 3U || !std::all_of(points.begin(), points.end(), [](Point p) {
            return std::isfinite(p.x) && std::isfinite(p.y);
        })) {
        return emptyMask(allocation_like);
    }
    struct Edge {
        double y_top;
        double y_bottom;
        double x_at_top;
        double slope;  // dx/dy
    };
    std::vector<Edge> edges;
    edges.reserve(points.size());
    double min_x = points[0].x, max_x = points[0].x, min_y = points[0].y, max_y = points[0].y;
    for (std::size_t i = 0; i < points.size(); ++i) {
        const auto p = points[i];
        const auto q = points[(i + 1U) % points.size()];
        min_x = std::min(min_x, p.x);
        max_x = std::max(max_x, p.x);
        min_y = std::min(min_y, p.y);
        max_y = std::max(max_y, p.y);
        if (p.y == q.y) continue;  // Horizontal edges never cross a scanline.
        const auto& top = p.y < q.y ? p : q;
        const auto& bottom = p.y < q.y ? q : p;
        edges.push_back({top.y, bottom.y, top.x, (bottom.x - top.x) / (bottom.y - top.y)});
    }
    std::sort(edges.begin(), edges.end(), [](const Edge& a, const Edge& b) { return a.y_top < b.y_top; });
    // Active edge list: scanlines are visited in increasing y, so edges enter in sorted order and
    // leave once the scanline passes their bottom (half-open [top, bottom) avoids double vertices).
    std::size_t next_edge = 0;
    std::vector<const Edge*> active;
    std::vector<double> crossings;
    return rasterizeSpans(emptyMask(allocation_like), clampExtent(min_x, min_y, max_x, max_y, width, height), antialias,
                          [&](double y, std::vector<std::pair<double, double>>& out) {
                              while (next_edge < edges.size() && edges[next_edge].y_top <= y) {
                                  active.push_back(&edges[next_edge++]);
                              }
                              active.erase(std::remove_if(active.begin(), active.end(),
                                                          [y](const Edge* edge) { return edge->y_bottom <= y; }),
                                           active.end());
                              crossings.clear();
                              for (const auto* edge : active) {
                                  if (edge->y_top <= y) crossings.push_back(edge->x_at_top + (y - edge->y_top) * edge->slope);
                              }
                              std::sort(crossings.begin(), crossings.end());
                              for (std::size_t i = 0; i + 1U < crossings.size(); i += 2U) {
                                  out.emplace_back(crossings[i], crossings[i + 1U]);
                              }
                          });
}

std::optional<TileStore> combine(const TileStore& selection, const TileStore& shape, Op op, std::string* error) {
    if (error != nullptr) error->clear();
    if (selection.format() != PixelFormat::A16 || shape.format() != PixelFormat::A16) {
        if (error != nullptr) *error = "selection masks must use A16 format";
        return std::nullopt;
    }
    if (selection.width() != shape.width() || selection.height() != shape.height()) {
        if (error != nullptr) *error = "selection masks must have matching dimensions";
        return std::nullopt;
    }
    const auto write_failed = [&] {
        if (error != nullptr) *error = "could not store combined selection tile";
        return std::optional<TileStore>{};
    };
    const auto bytes = selection.tile_byte_size();
    switch (op) {
        case Op::Replace: {
            TileStore out = selection.emptyLike(PixelFormat::A16);
            for (const auto coordinate : shape.tileCoordinates()) {
                const auto tile = shape.readTile(coordinate);
                if (tile != nullptr && !out.writeTile(coordinate, *tile)) return write_failed();
            }
            return out;
        }
        case Op::Add: {
            TileStore out(selection);
            for (const auto coordinate : shape.tileCoordinates()) {
                const auto b = shape.readTile(coordinate);
                const auto a = selection.readTile(coordinate);
                if (b == nullptr) continue;
                if (a == nullptr) {
                    if (!out.writeTile(coordinate, *b)) return write_failed();
                    continue;
                }
                if (!putTile(out, coordinate, combineTile(a.get(), b.get(), bytes, [](std::uint16_t x, std::uint16_t y) {
                    return std::max(x, y);
                }))) return write_failed();
            }
            return out;
        }
        case Op::Subtract: {
            TileStore out(selection);
            for (const auto coordinate : shape.tileCoordinates()) {
                const auto a = selection.readTile(coordinate);
                const auto b = shape.readTile(coordinate);
                if (a == nullptr || b == nullptr) continue;
                if (!putTile(out, coordinate, combineTile(a.get(), b.get(), bytes, [](std::uint16_t x, std::uint16_t y) {
                    return std::min<std::uint16_t>(x, static_cast<std::uint16_t>(kFullySelected - y));
                }))) return write_failed();
            }
            return out;
        }
        case Op::Intersect: {
            TileStore out = selection.emptyLike(PixelFormat::A16);
            for (const auto coordinate : selection.tileCoordinates()) {
                const auto a = selection.readTile(coordinate);
                const auto b = shape.readTile(coordinate);
                if (a == nullptr || b == nullptr) continue;
                if (!putTile(out, coordinate, combineTile(a.get(), b.get(), bytes, [](std::uint16_t x, std::uint16_t y) {
                    return std::min(x, y);
                }))) return write_failed();
            }
            return out;
        }
    }
    if (error != nullptr) *error = "unknown selection combine operation";
    return std::nullopt;
}

std::optional<TileStore> combine(const TileStore& allocation_like, const TileStore& selection, const TileStore& shape,
                                 Op op, std::string* error) {
    if (error != nullptr) error->clear();
    if (selection.format() != PixelFormat::A16 || shape.format() != PixelFormat::A16) {
        if (error != nullptr) *error = "selection masks must use A16 format";
        return std::nullopt;
    }
    if (selection.width() != shape.width() || selection.height() != shape.height() ||
        allocation_like.width() != selection.width() || allocation_like.height() != selection.height()) {
        if (error != nullptr) *error = "selection masks and allocation template must have matching dimensions";
        return std::nullopt;
    }
    const auto write_failed = [&] {
        if (error != nullptr) *error = "could not store combined selection tile";
        return std::optional<TileStore>{};
    };
    const auto copy_into = [&](TileStore& out, const TileStore& source) {
        for (const auto coordinate : source.tileCoordinates()) {
            const auto tile = source.readTile(coordinate);
            if (tile != nullptr && !out.writeTile(coordinate, *tile)) return false;
        }
        return true;
    };
    const auto bytes = selection.tile_byte_size();
    TileStore out = allocation_like.emptyLike(PixelFormat::A16);
    switch (op) {
        case Op::Replace:
            if (!copy_into(out, shape)) return write_failed();
            return out;
        case Op::Add:
        case Op::Subtract:
            if (!copy_into(out, selection)) return write_failed();
            for (const auto coordinate : shape.tileCoordinates()) {
                const auto b = shape.readTile(coordinate);
                const auto a = selection.readTile(coordinate);
                if (b == nullptr) continue;
                if (a == nullptr) {
                    if (op == Op::Add && !out.writeTile(coordinate, *b)) return write_failed();
                    continue;
                }
                const auto merged = op == Op::Add
                    ? combineTile(a.get(), b.get(), bytes, [](std::uint16_t x, std::uint16_t y) { return std::max(x, y); })
                    : combineTile(a.get(), b.get(), bytes, [](std::uint16_t x, std::uint16_t y) {
                          return std::min<std::uint16_t>(x, static_cast<std::uint16_t>(kFullySelected - y));
                      });
                if (!putTile(out, coordinate, merged)) return write_failed();
            }
            return out;
        case Op::Intersect:
            for (const auto coordinate : selection.tileCoordinates()) {
                const auto a = selection.readTile(coordinate);
                const auto b = shape.readTile(coordinate);
                if (a == nullptr || b == nullptr) continue;
                if (!putTile(out, coordinate, combineTile(a.get(), b.get(), bytes,
                        [](std::uint16_t x, std::uint16_t y) { return std::min(x, y); }))) return write_failed();
            }
            return out;
    }
    if (error != nullptr) *error = "unknown selection combine operation";
    return std::nullopt;
}

std::optional<TileStore> invert(const TileStore& selection, std::string* error) {
    if (error != nullptr) error->clear();
    if (selection.format() != PixelFormat::A16 || selection.width() == 0U || selection.height() == 0U) {
        if (error != nullptr) {
            *error = selection.format() != PixelFormat::A16 ? "selection mask must use A16 format"
                                                            : "selection mask dimensions must be positive";
        }
        return std::nullopt;
    }
    TileStore out = selection.emptyLike(PixelFormat::A16);
    for (std::uint32_t ty = 0; ty < tileRows(selection.height()); ++ty) {
        for (std::uint32_t tx = 0; tx < tileColumns(selection.width()); ++tx) {
            const TileCoordinate coordinate{static_cast<std::int32_t>(tx), static_cast<std::int32_t>(ty)};
            const auto source = selection.readTile(coordinate);
            TileBuffer tile(selection.tile_byte_size(), 0);
            const auto columns = std::min<std::uint32_t>(kTileEdge, selection.width() - tx * kTileEdge);
            const auto rows = std::min<std::uint32_t>(kTileEdge, selection.height() - ty * kTileEdge);
            for (std::uint32_t y = 0; y < rows; ++y) {
                for (std::uint32_t x = 0; x < columns; ++x) {
                    const std::uint16_t value = source == nullptr ? 0 : readPixel(*source, x, y);
                    writePixel(tile, x, y, static_cast<std::uint16_t>(kFullySelected - value));
                }
            }
            if (!putTile(out, coordinate, tile)) {
                if (error != nullptr) *error = "could not store inverted selection tile";
                return std::nullopt;
            }
        }
    }
    return out;
}

std::optional<TileStore> invert(const TileStore& allocation_like, const TileStore& selection, std::string* error) {
    if (error != nullptr) error->clear();
    if (selection.format() != PixelFormat::A16 || selection.width() == 0U || selection.height() == 0U) {
        if (error != nullptr) *error = selection.format() != PixelFormat::A16 ? "selection mask must use A16 format"
                                                                                 : "selection mask dimensions must be positive";
        return std::nullopt;
    }
    if (allocation_like.width() != selection.width() || allocation_like.height() != selection.height()) {
        if (error != nullptr) *error = "allocation template must match selection dimensions";
        return std::nullopt;
    }
    TileStore out = allocation_like.emptyLike(PixelFormat::A16);
    for (std::uint32_t ty = 0; ty < tileRows(selection.height()); ++ty) {
        for (std::uint32_t tx = 0; tx < tileColumns(selection.width()); ++tx) {
            const TileCoordinate coordinate{static_cast<std::int32_t>(tx), static_cast<std::int32_t>(ty)};
            const auto source = selection.readTile(coordinate);
            TileBuffer tile(selection.tile_byte_size(), 0);
            const auto columns = std::min<std::uint32_t>(kTileEdge, selection.width() - tx * kTileEdge);
            const auto rows = std::min<std::uint32_t>(kTileEdge, selection.height() - ty * kTileEdge);
            for (std::uint32_t y = 0; y < rows; ++y) {
                for (std::uint32_t x = 0; x < columns; ++x) {
                    const auto value = source == nullptr ? 0 : readPixel(*source, x, y);
                    writePixel(tile, x, y, static_cast<std::uint16_t>(kFullySelected - value));
                }
            }
            if (!putTile(out, coordinate, tile)) {
                if (error != nullptr) *error = "could not store inverted selection tile";
                return std::nullopt;
            }
        }
    }
    return out;
}

std::optional<PixelRect> bounds(const TileStore& selection) {
    std::optional<PixelRect> result;
    for (const auto coordinate : selection.tileCoordinates()) {
        const auto tile = selection.readTile(coordinate);
        if (tile == nullptr) continue;
        for (std::uint32_t y = 0; y < kTileEdge; ++y) {
            for (std::uint32_t x = 0; x < kTileEdge; ++x) {
                if (readPixel(*tile, x, y) == 0U) continue;
                const auto gx = coordinate.x * static_cast<std::int32_t>(kTileEdge) + static_cast<std::int32_t>(x);
                const auto gy = coordinate.y * static_cast<std::int32_t>(kTileEdge) + static_cast<std::int32_t>(y);
                if (!result) result = PixelRect{gx, gy, gx + 1, gy + 1};
                result->left = std::min(result->left, gx);
                result->top = std::min(result->top, gy);
                result->right = std::max(result->right, gx + 1);
                result->bottom = std::max(result->bottom, gy + 1);
            }
        }
    }
    return result;
}

bool isEmpty(const TileStore& selection) {
    for (const auto coordinate : selection.tileCoordinates()) {
        const auto tile = selection.readTile(coordinate);
        if (tile != nullptr && !allZero(*tile)) return false;
    }
    return true;
}

std::uint16_t coverageAt(const TileStore& selection, std::int64_t x, std::int64_t y) {
    if (x < 0 || y < 0 || x >= selection.width() || y >= selection.height()) return 0;
    const auto tile = selection.readTile({static_cast<std::int32_t>(x / kTileEdge), static_cast<std::int32_t>(y / kTileEdge)});
    return tile == nullptr ? 0 : readPixel(*tile, static_cast<std::uint32_t>(x % kTileEdge), static_cast<std::uint32_t>(y % kTileEdge));
}

ContourResult contour(const TileStore& selection, PixelRect region, std::uint32_t mip_level, std::uint16_t threshold,
                      std::size_t max_segments) {
    ContourResult result;
    if (selection.format() != PixelFormat::A16 || mip_level > 30U) return result;
    const auto width = selection.mip_width(mip_level);
    const auto height = selection.mip_height(mip_level);
    const auto scale = std::int64_t{1} << mip_level;
    // Region in level coordinates, widened by one pixel so edges on its border are found.
    const auto to_level_floor = [&](std::int64_t v) { return v < 0 ? -1 : v / scale; };
    const auto to_level_ceil = [&](std::int64_t v) { return v <= 0 ? 0 : (v + scale - 1) / scale; };
    const auto x0 = std::max<std::int64_t>(0, to_level_floor(region.left));
    const auto y0 = std::max<std::int64_t>(0, to_level_floor(region.top));
    const auto x1 = std::min<std::int64_t>(width, to_level_ceil(region.right));
    const auto y1 = std::min<std::int64_t>(height, to_level_ceil(region.bottom));
    if (x1 <= x0 || y1 <= y0) return result;

    // inside[] covers [x0-1, x1] × [y0-1, y1] so boundary checks never leave the grid.
    const auto grid_width = static_cast<std::size_t>(x1 - x0 + 2);
    const auto grid_height = static_cast<std::size_t>(y1 - y0 + 2);
    std::vector<std::uint8_t> inside(grid_width * grid_height, 0);
    // Load the one-pixel margin from the real mask too, so a selection that continues past the region
    // does not produce edges on the region border.
    const auto load_y0 = std::max<std::int64_t>(0, y0 - 1);
    const auto load_y1 = std::min<std::int64_t>(height, y1 + 1);
    const auto load_x0 = std::max<std::int64_t>(0, x0 - 1);
    const auto load_x1 = std::min<std::int64_t>(width, x1 + 1);
    for (std::int64_t y = load_y0; y < load_y1; ++y) {
        std::shared_ptr<const TileBuffer> tile;
        std::int64_t loaded_column = -1;
        for (std::int64_t x = load_x0; x < load_x1; ++x) {
            if (x / kTileEdge != loaded_column) {
                loaded_column = x / kTileEdge;
                const TileCoordinate c{static_cast<std::int32_t>(loaded_column), static_cast<std::int32_t>(y / kTileEdge)};
                tile = mip_level == 0U ? selection.readTile(c) : selection.readMipTile(mip_level, c);
            }
            if (tile != nullptr && readPixel(*tile, static_cast<std::uint32_t>(x % kTileEdge),
                                             static_cast<std::uint32_t>(y % kTileEdge)) >= threshold) {
                inside[static_cast<std::size_t>(y - y0 + 1) * grid_width + static_cast<std::size_t>(x - x0 + 1)] = 1;
            }
        }
    }
    const auto at = [&](std::int64_t x, std::int64_t y) {
        return inside[static_cast<std::size_t>(y - y0 + 1) * grid_width + static_cast<std::size_t>(x - x0 + 1)] != 0;
    };
    const auto doc_x = [&](std::int64_t x) {
        return static_cast<std::int32_t>(std::min<std::int64_t>(x * scale, selection.width()));
    };
    const auto doc_y = [&](std::int64_t y) {
        return static_cast<std::int32_t>(std::min<std::int64_t>(y * scale, selection.height()));
    };
    const auto push = [&](Segment segment) {
        if (result.segments.size() >= max_segments) {
            result.truncated = true;
            return false;
        }
        result.segments.push_back(segment);
        return true;
    };
    // Horizontal edges on the line between rows y-1 and y.
    for (auto y = y0; y <= y1; ++y) {
        std::int64_t run_start = -1;
        for (auto x = x0; x <= x1; ++x) {
            const bool edge = x < x1 && at(x, y - 1) != at(x, y);
            if (edge && run_start < 0) run_start = x;
            if (!edge && run_start >= 0) {
                if (!push({doc_x(run_start), doc_y(y), doc_x(x), doc_y(y)})) return result;
                run_start = -1;
            }
        }
    }
    // Vertical edges on the line between columns x-1 and x.
    for (auto x = x0; x <= x1; ++x) {
        std::int64_t run_start = -1;
        for (auto y = y0; y <= y1; ++y) {
            const bool edge = y < y1 && at(x - 1, y) != at(x, y);
            if (edge && run_start < 0) run_start = y;
            if (!edge && run_start >= 0) {
                if (!push({doc_x(x), doc_y(run_start), doc_x(x), doc_y(y)})) return result;
                run_start = -1;
            }
        }
    }
    return result;
}

}  // namespace croptop::selection
