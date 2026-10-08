#include "liquify.h"

#include <algorithm>
#include <atomic>
#include <cmath>
#include <cstring>
#include <exception>

#include "parallel_util.h"
#include "pixel_plane.h"
#include "selection_mask.h"

namespace croptop::paint {
namespace {

using namespace detail;

constexpr double kTwirlRadians = 0.2;
constexpr double kScaleStep = 0.1;
/** Source pixels read per render block before falling back to reading block by block per pixel. */
constexpr std::int64_t kMaxWindowPixels = 4'000'000;
constexpr int kBlock = 32;

bool unit(float v) { return std::isfinite(v) && v >= 0.0F && v <= 1.0F; }

std::array<float, 4> catmullRomWeights(double t) {
    const double t2 = t * t, t3 = t2 * t;
    return {static_cast<float>(-0.5 * t3 + t2 - 0.5 * t), static_cast<float>(1.5 * t3 - 2.5 * t2 + 1.0),
            static_cast<float>(-1.5 * t3 + 2.0 * t2 + 0.5 * t), static_cast<float>(0.5 * t3 - 0.5 * t2)};
}

}  // namespace

LiquifyMesh::LiquifyMesh(std::uint32_t width, std::uint32_t height, std::uint32_t spacing)
    : width_(std::max(1U, width)),
      height_(std::max(1U, height)),
      spacing_(std::clamp(spacing, 1U, 64U)),
      columns_((width_ + spacing_ - 1U) / spacing_ + 1U),
      rows_((height_ + spacing_ - 1U) / spacing_ + 1U),
      field_(static_cast<std::size_t>(columns_) * rows_ * 2U, 0.0F) {}

std::array<float, 2> LiquifyMesh::node(std::int64_t i, std::int64_t j) const {
    i = std::clamp<std::int64_t>(i, 0, columns_ - 1);
    j = std::clamp<std::int64_t>(j, 0, rows_ - 1);
    const auto k = (static_cast<std::size_t>(j) * columns_ + static_cast<std::size_t>(i)) * 2U;
    return {field_[k], field_[k + 1U]};
}

std::array<float, 2> LiquifyMesh::sample(const std::vector<float>& field, std::uint32_t columns, std::uint32_t rows,
                                         std::uint32_t spacing, double x, double y) {
    const double gx = std::clamp(x / spacing, 0.0, static_cast<double>(columns - 1U));
    const double gy = std::clamp(y / spacing, 0.0, static_cast<double>(rows - 1U));
    // The constructor guarantees at least 2 × 2 nodes.
    const auto i = std::min(static_cast<std::uint32_t>(gx), columns - 2U);
    const auto j = std::min(static_cast<std::uint32_t>(gy), rows - 2U);
    const auto tx = static_cast<float>(gx - i), ty = static_cast<float>(gy - j);
    const auto at = [&](std::uint32_t a, std::uint32_t b, int c) {
        a = std::min(a, columns - 1U);
        b = std::min(b, rows - 1U);
        return field[(static_cast<std::size_t>(b) * columns + a) * 2U + static_cast<std::size_t>(c)];
    };
    std::array<float, 2> out{};
    for (int c = 0; c < 2; ++c) {
        const float top = at(i, j, c) + tx * (at(i + 1U, j, c) - at(i, j, c));
        const float bottom = at(i, j + 1U, c) + tx * (at(i + 1U, j + 1U, c) - at(i, j + 1U, c));
        out[static_cast<std::size_t>(c)] = top + ty * (bottom - top);
    }
    return out;
}

std::array<float, 2> LiquifyMesh::displacementAt(double x, double y) const {
    return sample(field_, columns_, rows_, spacing_, x, y);
}

bool LiquifyMesh::setField(std::vector<float> field) {
    if (field.size() != field_.size()) return false;
    for (auto& v : field) {
        if (!std::isfinite(v)) v = 0.0F;
    }
    field_ = std::move(field);
    return true;
}

bool LiquifyMesh::isIdentity() const {
    return std::all_of(field_.begin(), field_.end(), [](float v) { return v == 0.0F; });
}

void LiquifyMesh::reconstructAll(float amount) {
    if (!std::isfinite(amount)) return;
    const float keep = 1.0F - std::clamp(amount, 0.0F, 1.0F);
    for (auto& v : field_) v *= keep;
}

bool LiquifyMesh::setFreezeMask(const TileStore* mask, std::string& error) {
    if (mask == nullptr) {
        frozen_.clear();
        return true;
    }
    if (mask->format() != PixelFormat::A16 || mask->width() != width_ || mask->height() != height_) {
        error = "freeze mask must be an A16 mask of the document's size";
        return false;
    }
    std::vector<float> frozen(static_cast<std::size_t>(columns_) * rows_, 0.0F);
    for (std::uint32_t j = 0; j < rows_; ++j) {
        for (std::uint32_t i = 0; i < columns_; ++i) {
            const auto x = std::min<std::int64_t>(static_cast<std::int64_t>(i) * spacing_, width_ - 1);
            const auto y = std::min<std::int64_t>(static_cast<std::int64_t>(j) * spacing_, height_ - 1);
            frozen[static_cast<std::size_t>(j) * columns_ + i] = selection::coverageAt(*mask, x, y) / 65535.0F;
        }
    }
    frozen_ = std::move(frozen);
    return true;
}

bool LiquifyMesh::apply(LiquifyTool tool, const LiquifyBrush& brush, std::string& error) {
    if (!std::isfinite(brush.x) || !std::isfinite(brush.y) || !std::isfinite(brush.dx) || !std::isfinite(brush.dy) ||
        std::abs(brush.x) > 1.0e9 || std::abs(brush.y) > 1.0e9 || std::abs(brush.dx) > 1.0e6 || std::abs(brush.dy) > 1.0e6 ||
        !std::isfinite(brush.size) || brush.size < 1.0 || brush.size > 15000.0 || !unit(brush.density) ||
        !unit(brush.pressure) || !unit(brush.rate)) {
        error = "liquify brush needs a finite position and motion, size 1–15000 px, and density, pressure and rate 0–1";
        return false;
    }
    const double radius = brush.size / 2.0;
    const double exponent = 1.0 + 2.0 * brush.density;
    const double strength = static_cast<double>(brush.pressure) * brush.rate;
    if (strength <= 0.0) return true;
    const auto i0 = static_cast<std::int64_t>(std::max(0.0, std::floor((brush.x - radius) / spacing_)));
    const auto j0 = static_cast<std::int64_t>(std::max(0.0, std::floor((brush.y - radius) / spacing_)));
    const auto i1 = std::min<std::int64_t>(columns_ - 1, static_cast<std::int64_t>(std::ceil((brush.x + radius) / spacing_)));
    const auto j1 = std::min<std::int64_t>(rows_ - 1, static_cast<std::int64_t>(std::ceil((brush.y + radius) / spacing_)));
    if (i1 < i0 || j1 < j0) return true;
    try {
        // New values are computed from the unchanged field, then written back (a dab reads the old field).
        const auto span = static_cast<std::size_t>(i1 - i0 + 1);
        std::vector<float> updated(span * static_cast<std::size_t>(j1 - j0 + 1) * 2U);
        std::vector<std::uint8_t> touched(updated.size() / 2U, 0);
        for (auto j = j0; j <= j1; ++j) {
            for (auto i = i0; i <= i1; ++i) {
                const double px = static_cast<double>(i) * spacing_;
                const double py = static_cast<double>(j) * spacing_;
                const double rx = px - brush.x, ry = py - brush.y;
                const double t2 = (rx * rx + ry * ry) / (radius * radius);
                if (t2 >= 1.0) continue;
                double w = strength * std::pow(1.0 - t2, exponent);
                if (!frozen_.empty()) w *= 1.0 - frozen_[static_cast<std::size_t>(j) * columns_ + static_cast<std::size_t>(i)];
                if (w <= 0.0) continue;
                const auto k = static_cast<std::size_t>(j - j0) * span + static_cast<std::size_t>(i - i0);
                const auto current = node(i, j);
                std::array<float, 2> result = current;
                double qx = px, qy = py;  // p′
                bool compose = true;
                switch (tool) {
                    case LiquifyTool::ForwardWarp:
                        qx = px - w * brush.dx;
                        qy = py - w * brush.dy;
                        break;
                    case LiquifyTool::PushLeft:
                        qx = px - w * brush.dy;
                        qy = py + w * brush.dx;
                        break;
                    case LiquifyTool::TwirlClockwise:
                    case LiquifyTool::TwirlCounterClockwise: {
                        const double angle = (tool == LiquifyTool::TwirlClockwise ? -1.0 : 1.0) * w * kTwirlRadians;
                        const double c = std::cos(angle), s = std::sin(angle);
                        qx = brush.x + c * rx - s * ry;
                        qy = brush.y + s * rx + c * ry;
                        break;
                    }
                    case LiquifyTool::Pucker:
                    case LiquifyTool::Bloat: {
                        const double scale = tool == LiquifyTool::Pucker ? 1.0 + w * kScaleStep : 1.0 - w * kScaleStep;
                        qx = brush.x + rx * scale;
                        qy = brush.y + ry * scale;
                        break;
                    }
                    case LiquifyTool::Reconstruct:
                        compose = false;
                        result = {static_cast<float>(current[0] * (1.0 - w)), static_cast<float>(current[1] * (1.0 - w))};
                        break;
                    case LiquifyTool::Smooth: {
                        compose = false;
                        std::array<double, 2> sum{};
                        for (int dj = -1; dj <= 1; ++dj)
                            for (int di = -1; di <= 1; ++di) {
                                const auto n = node(i + di, j + dj);
                                sum[0] += n[0];
                                sum[1] += n[1];
                            }
                        for (int c = 0; c < 2; ++c) {
                            result[static_cast<std::size_t>(c)] = static_cast<float>(
                                current[static_cast<std::size_t>(c)] + w * (sum[static_cast<std::size_t>(c)] / 9.0 - current[static_cast<std::size_t>(c)]));
                        }
                        break;
                    }
                }
                if (compose) {
                    const auto previous = sample(field_, columns_, rows_, spacing_, qx, qy);
                    result = {static_cast<float>(qx - px + previous[0]), static_cast<float>(qy - py + previous[1])};
                }
                updated[k * 2U] = result[0];
                updated[k * 2U + 1U] = result[1];
                touched[k] = 1;
            }
        }
        for (auto j = j0; j <= j1; ++j) {
            for (auto i = i0; i <= i1; ++i) {
                const auto k = static_cast<std::size_t>(j - j0) * span + static_cast<std::size_t>(i - i0);
                if (!touched[k]) continue;
                const auto f = (static_cast<std::size_t>(j) * columns_ + static_cast<std::size_t>(i)) * 2U;
                field_[f] = updated[k * 2U];
                field_[f + 1U] = updated[k * 2U + 1U];
            }
        }
    } catch (const std::exception&) {
        error = "could not allocate liquify buffers";
        return false;
    }
    return true;
}

std::optional<TileStore> LiquifyMesh::render(const TileStore& source, std::string& error, unsigned worker_threads,
                                              const TileStore* selection_mask) const {
    if (!isRgba(source.format()) || source.width() != width_ || source.height() != height_) {
        error = "liquify renders an RGBA layer of the mesh's size";
        return std::nullopt;
    }
    if (selection_mask != nullptr &&
        (selection_mask->format() != PixelFormat::A16 || selection_mask->width() != width_ || selection_mask->height() != height_)) {
        error = "liquify selection must be an A16 mask of the mesh's size";
        return std::nullopt;
    }
    try {
        TileStore out(source);  // Tiles the field leaves alone stay shared with the source.
        const auto tile_columns = (width_ + kTileEdge - 1U) / kTileEdge;
        const auto tile_rows = (height_ + kTileEdge - 1U) / kTileEdge;
        const auto bpp = source.bytes_per_pixel();
        std::atomic<bool> failed{false};
        parallel::parallelFor(static_cast<std::size_t>(tile_columns) * tile_rows, parallel::workerCount(worker_threads),
                              [&](std::size_t item, std::size_t) {
            const auto tx = static_cast<std::int32_t>(item % tile_columns);
            const auto ty = static_cast<std::int32_t>(item / tile_columns);
            const int tile_x0 = tx * static_cast<int>(kTileEdge), tile_y0 = ty * static_cast<int>(kTileEdge);
            const int tile_x1 = std::min<int>(tile_x0 + static_cast<int>(kTileEdge), static_cast<int>(width_));
            const int tile_y1 = std::min<int>(tile_y0 + static_cast<int>(kTileEdge), static_cast<int>(height_));
            const auto source_tile = source.readTile({tx, ty});
            const auto mask_tile = selection_mask != nullptr ? selection_mask->readTile({tx, ty}) : nullptr;
            const auto nodeRange = [&](int x0, int y0, int x1, int y1, std::array<float, 4>& range) {
                const auto i0 = static_cast<std::int64_t>(std::floor((x0 + 0.5) / spacing_));
                const auto i1 = static_cast<std::int64_t>(std::floor((x1 - 0.5) / spacing_)) + 1;
                const auto j0 = static_cast<std::int64_t>(std::floor((y0 + 0.5) / spacing_));
                const auto j1 = static_cast<std::int64_t>(std::floor((y1 - 0.5) / spacing_)) + 1;
                range = {0.0F, 0.0F, 0.0F, 0.0F};  // min dx, max dx, min dy, max dy
                bool moved = false;
                for (auto j = j0; j <= j1; ++j)
                    for (auto i = i0; i <= i1; ++i) {
                        const auto d = node(i, j);
                        if (d[0] != 0.0F || d[1] != 0.0F) moved = true;
                        range = {std::min(range[0], d[0]), std::max(range[1], d[0]), std::min(range[2], d[1]), std::max(range[3], d[1])};
                    }
                return moved;
            };
            std::array<float, 4> range{};
            if (!nodeRange(tile_x0, tile_y0, tile_x1, tile_y1, range)) return;
            TileBuffer tile(out.tile_byte_size(), 0);
            for (int by = tile_y0; by < tile_y1; by += kBlock) {
                for (int bx = tile_x0; bx < tile_x1; bx += kBlock) {
                    const int bx1 = std::min(bx + kBlock, tile_x1), by1 = std::min(by + kBlock, tile_y1);
                    nodeRange(bx, by, bx1, by1, range);
                    const auto wx0 = static_cast<std::int64_t>(std::floor(bx + range[0])) - 2;
                    const auto wy0 = static_cast<std::int64_t>(std::floor(by + range[2])) - 2;
                    const auto wx1 = static_cast<std::int64_t>(std::ceil(bx1 + range[1])) + 3;
                    const auto wy1 = static_cast<std::int64_t>(std::ceil(by1 + range[3])) + 3;
                    const bool windowed = (wx1 - wx0) * (wy1 - wy0) <= kMaxWindowPixels;
                    Plane window;
                    if (windowed) {
                        window = readPlane(source, static_cast<int>(wx0), static_cast<int>(wy0), static_cast<int>(wx1 - wx0),
                                           static_cast<int>(wy1 - wy0));
                        for (std::size_t i = 0; i < window.px.size(); i += 4U)
                            for (int c = 0; c < 3; ++c) window.px[i + static_cast<std::size_t>(c)] *= window.px[i + 3U];
                    }
                    for (int y = by; y < by1; ++y) {
                        for (int x = bx; x < bx1; ++x) {
                            const auto d = sample(field_, columns_, rows_, spacing_, x + 0.5, y + 0.5);
                            const double sx = x + 0.5 + d[0] - 0.5, sy = y + 0.5 + d[1] - 0.5;
                            const auto ix = static_cast<std::int64_t>(std::floor(sx)), iy = static_cast<std::int64_t>(std::floor(sy));
                            const auto wxs = catmullRomWeights(sx - ix), wys = catmullRomWeights(sy - iy);
                            Plane local;
                            const Plane* from = &window;
                            if (!windowed) {
                                local = readPlane(source, static_cast<int>(ix - 1), static_cast<int>(iy - 1), 4, 4);
                                for (std::size_t i = 0; i < local.px.size(); i += 4U)
                                    for (int c = 0; c < 3; ++c) local.px[i + static_cast<std::size_t>(c)] *= local.px[i + 3U];
                                from = &local;
                            }
                            std::array<float, 4> v{};
                            for (int m = 0; m < 4; ++m) {
                                for (int n = 0; n < 4; ++n) {
                                    const float w = wys[static_cast<std::size_t>(m)] * wxs[static_cast<std::size_t>(n)];
                                    const float* texel = from->at(static_cast<int>(ix - 1 + n - from->x0), static_cast<int>(iy - 1 + m - from->y0));
                                    for (int c = 0; c < 4; ++c) v[static_cast<std::size_t>(c)] += w * texel[c];
                                }
                            }
                            v[3] = std::clamp(v[3], 0.0F, 1.0F);
                            for (int c = 0; c < 3; ++c) v[static_cast<std::size_t>(c)] = v[3] > 1e-6F ? v[static_cast<std::size_t>(c)] / v[3] : 0.0F;
                            if (selection_mask != nullptr) {
                                std::uint16_t raw_coverage = 0;
                                if (mask_tile != nullptr) {
                                    std::memcpy(&raw_coverage, mask_tile->data() +
                                        (static_cast<std::size_t>(y - tile_y0) * kTileEdge + static_cast<std::size_t>(x - tile_x0)) * 2U, 2U);
                                }
                                const auto* original_bytes = source_tile != nullptr
                                    ? source_tile->data() + (static_cast<std::size_t>(y - tile_y0) * kTileEdge + static_cast<std::size_t>(x - tile_x0)) * bpp
                                    : nullptr;
                                if (raw_coverage == 0U) {
                                    if (original_bytes != nullptr) {
                                        std::memcpy(tile.data() + (static_cast<std::size_t>(y - tile_y0) * kTileEdge + static_cast<std::size_t>(x - tile_x0)) * bpp,
                                                    original_bytes, bpp);
                                    }
                                    continue;
                                }
                                const float coverage = raw_coverage / 65535.0F;
                                std::array<float, 4> original{};
                                if (original_bytes != nullptr) readPixel(source.format(), original_bytes, original.data());
                                const float mixed_alpha = original[3] + coverage * (v[3] - original[3]);
                                for (int c = 0; c < 3; ++c) {
                                    const auto channel = static_cast<std::size_t>(c);
                                    const float mixed_premultiplied = original[channel] * original[3] + coverage * (v[channel] * v[3] - original[channel] * original[3]);
                                    v[channel] = mixed_alpha > 1e-6F ? mixed_premultiplied / mixed_alpha : 0.0F;
                                }
                                v[3] = mixed_alpha;
                            }
                            writePixel(source.format(),
                                       tile.data() + (static_cast<std::size_t>(y - tile_y0) * kTileEdge + static_cast<std::size_t>(x - tile_x0)) * bpp,
                                       v.data());
                        }
                    }
                }
            }
            if (!out.writeTile({tx, ty}, tile)) failed.store(true);
        });
        if (failed.load()) {
            error = "could not store liquified pixels";
            return std::nullopt;
        }
        return out;
    } catch (const std::exception&) {
        error = "could not allocate liquify buffers";
        return std::nullopt;
    }
}

}  // namespace croptop::paint
