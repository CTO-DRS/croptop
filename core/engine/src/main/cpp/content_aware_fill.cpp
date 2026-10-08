#include "content_aware_fill.h"

#include <algorithm>
#include <array>
#include <atomic>
#include <barrier>
#include <cmath>
#include <cstddef>
#include <cstring>
#include <exception>
#include <random>
#include <thread>
#include <vector>

#include "parallel_util.h"
#include "selection_internal.h"
#include "selection_mask.h"

namespace croptop::paint {
namespace {

constexpr int kRadius = 3;  // 7×7 patches.
constexpr int kRotatedRadius = 5;  // a rotated 7×7 patch stays within ±5 px (3·√2 rounded)
constexpr std::uint16_t kHalfCoverage = 32768;
constexpr float kEstimateWeight = 0.25F;  // patch-distance weight of hole pixels vs known pixels

/** Discrete patch transforms (rotations, optionally mirrored) for rotation/mirror adaptation; transform 0 is the
 * identity. Each maps a target-patch offset (dx, dy) to a source offset (a·dx + b·dy, c·dx + d·dy). */
struct Rotations {
    std::vector<std::array<double, 4>> matrix;
    std::vector<std::array<int, 2 * (2 * kRadius + 1) * (2 * kRadius + 1)>> offsets;  // per transform: (ox, oy) per cell
    int count() const { return static_cast<int>(offsets.size()); }
    void build(const std::vector<double>& degrees, bool mirror) {
        for (int flip = 0; flip <= (mirror ? 1 : 0); ++flip) {
            for (const double deg : degrees) {
                const double a = deg * 3.14159265358979323846 / 180.0;
                const double c = std::cos(a), s = std::sin(a), f = flip != 0 ? -1.0 : 1.0;
                const std::array<double, 4> m{f * c, -s, f * s, c};  // rotate after mirroring x
                matrix.push_back(m);
                std::array<int, 2 * (2 * kRadius + 1) * (2 * kRadius + 1)> table{};
                std::size_t k = 0;
                for (int dy = -kRadius; dy <= kRadius; ++dy)
                    for (int dx = -kRadius; dx <= kRadius; ++dx) {
                        table[k++] = static_cast<int>(std::lround(m[0] * dx + m[1] * dy));
                        table[k++] = static_cast<int>(std::lround(m[2] * dx + m[3] * dy));
                    }
                offsets.push_back(table);
            }
        }
    }
};

struct Level {
    int w = 0;
    int h = 0;
    std::vector<float> px;              // RGBA, 4 floats per pixel (current estimate in the hole)
    std::vector<std::uint8_t> hole;     // 1 = unknown
    std::vector<std::uint8_t> usable;   // 1 = known and allowed as source content
    std::vector<std::uint8_t> target;   // 1 = patch centre overlapping the hole
    std::vector<std::array<int, 2>> target_spans;  // row-major [first target x, one past last]; bitmap checks preserve disjoint runs
    std::vector<std::uint8_t> source;   // 1 = patch centre whose whole patch is usable
    std::vector<int> sources;           // indices of source centres
    std::vector<int> nnf;               // per target: source centre index
    std::vector<float> dist;
    std::vector<float> adapted;         // colour-adapted RGBA (separate so PatchMatch keeps matching raw texture)
    std::vector<std::uint8_t> xf;          // per target: rotation index into `rot`
    std::vector<std::uint8_t> source_rot;  // 1 = usable for rotated patches (everything within ±5 px usable)
    const Rotations* rot = nullptr;
    std::size_t index(int x, int y) const { return static_cast<std::size_t>(y) * static_cast<std::size_t>(w) + static_cast<std::size_t>(x); }
};

/** Source pixel for target-patch cell (dx, dy) under transform t of source centre s. */
inline std::size_t sourcePixel(const Level& L, int s, int t, int dx, int dy) {
    if (t == 0) return static_cast<std::size_t>(s + dy * L.w + dx);
    const auto& table = L.rot->offsets[static_cast<std::size_t>(t)];
    const auto k = static_cast<std::size_t>(((dy + kRadius) * (2 * kRadius + 1) + (dx + kRadius)) * 2);
    return static_cast<std::size_t>(s + table[k + 1] * L.w + table[k]);
}

inline bool validSource(const Level& L, int s, int t) {
    return t == 0 ? L.source[static_cast<std::size_t>(s)] != 0U : L.source_rot[static_cast<std::size_t>(s)] != 0U;
}

/** Integral-image box count: out[c] = number of `flag` pixels in the (2r+1)² window (clipped). */
std::vector<int> boxCount(const std::vector<std::uint8_t>& flag, int w, int h, int r) {
    std::vector<int> integral(static_cast<std::size_t>(w + 1) * static_cast<std::size_t>(h + 1), 0);
    for (int y = 0; y < h; ++y) {
        int row = 0;
        for (int x = 0; x < w; ++x) {
            row += flag[static_cast<std::size_t>(y) * w + x];
            integral[static_cast<std::size_t>(y + 1) * (w + 1) + (x + 1)] = integral[static_cast<std::size_t>(y) * (w + 1) + (x + 1)] + row;
        }
    }
    std::vector<int> out(static_cast<std::size_t>(w) * h);
    for (int y = 0; y < h; ++y) {
        for (int x = 0; x < w; ++x) {
            const int x0 = std::max(0, x - r), x1 = std::min(w, x + r + 1), y0 = std::max(0, y - r), y1 = std::min(h, y + r + 1);
            out[static_cast<std::size_t>(y) * w + x] = integral[static_cast<std::size_t>(y1) * (w + 1) + x1] - integral[static_cast<std::size_t>(y0) * (w + 1) + x1] -
                                                      integral[static_cast<std::size_t>(y1) * (w + 1) + x0] + integral[static_cast<std::size_t>(y0) * (w + 1) + x0];
        }
    }
    return out;
}

bool prepare(Level& level, const std::function<bool()>& keep_running) {
    const auto n = static_cast<std::size_t>(level.w) * level.h;
    std::vector<std::uint8_t> unusable(n);
    for (std::size_t i = 0; i < n; ++i) unusable[i] = level.usable[i] ? 0U : 1U;
    const auto bad = boxCount(unusable, level.w, level.h, kRadius);
    const bool rotating = level.rot != nullptr && level.rot->count() > 1;
    const auto bad_rot = rotating ? boxCount(unusable, level.w, level.h, kRotatedRadius) : std::vector<int>{};
    level.source_rot.assign(n, 0);
    const auto holes = boxCount(level.hole, level.w, level.h, kRadius);
    level.target.assign(n, 0);
    level.target_spans.assign(static_cast<std::size_t>(level.h), {0, 0});
    level.source.assign(n, 0);
    level.sources.clear();
    for (int y = 0; y < level.h; ++y) {
        if ((y & 31) == 0 && !keep_running()) return false;
        int target_begin = level.w;
        int target_end = 0;
        for (int x = 0; x < level.w; ++x) {
            const auto i = level.index(x, y);
            level.target[i] = holes[i] > 0 ? 1U : 0U;
            if (level.target[i]) {
                target_begin = std::min(target_begin, x);
                target_end = x + 1;
            }
            const bool inside = x >= kRadius && y >= kRadius && x < level.w - kRadius && y < level.h - kRadius;
            if (inside && bad[i] == 0) {
                level.source[i] = 1;
                level.sources.push_back(static_cast<int>(i));
            }
            const bool inside_rot = x >= kRotatedRadius && y >= kRotatedRadius && x < level.w - kRotatedRadius && y < level.h - kRotatedRadius;
            if (rotating && inside_rot && bad_rot[i] == 0) level.source_rot[i] = 1;
        }
        if (target_begin < target_end) level.target_spans[static_cast<std::size_t>(y)] = {target_begin, target_end};
    }
    level.nnf.assign(n, -1);
    level.dist.assign(n, 0.0F);
    level.xf.assign(n, 0);
    return keep_running();
}

float patchDistance(const Level& L, int tx, int ty, int s, int t, float best) {
    float sum = 0.0F;
    const auto width = static_cast<std::size_t>(L.w);
    const int first_dy = std::max(-kRadius, -ty);
    const int end_dy = std::min(kRadius, L.h - 1 - ty) + 1;
    const int first_dx = std::max(-kRadius, -tx);
    const int end_dx = std::min(kRadius, L.w - 1 - tx) + 1;
    const auto source_center = static_cast<std::ptrdiff_t>(s);
    for (int dy = first_dy; dy < end_dy; ++dy) {
        const auto target_row = static_cast<std::size_t>(ty + dy) * width + static_cast<std::size_t>(tx);
        const auto source_row = source_center + static_cast<std::ptrdiff_t>(dy) * L.w;
        for (int dx = first_dx; dx < end_dx; ++dx) {
            const auto target_index = target_row + static_cast<std::size_t>(dx);
            const auto source_index = t == 0 ? static_cast<std::size_t>(source_row + dx) : sourcePixel(L, s, t, dx, dy);
            const float* a = L.px.data() + target_index * 4U;
            const float* b = L.px.data() + source_index * 4U;
            // Known pixels anchor the match; the hole's current estimate counts less, so a fill that is
            // self-consistent but misaligned with the boundary loses to one that continues it.
            const float weight = L.hole[target_index] ? kEstimateWeight : 1.0F;
            for (int c = 0; c < 4; ++c) {
                const float d = a[c] - b[c];
                sum += weight * d * d;
            }
        }
        if (sum >= best) return sum;  // Early exit.
    }
    return sum;
}

void patchMatchPass(Level& L, int iteration, std::uint32_t seed, unsigned workers) {
    const bool forward = iteration % 2 == 0;
    const int bands = static_cast<int>(std::max(1U, workers)) * 4;
    const int band_height = std::max(1, (L.h + bands - 1) / bands);
    const int search_start = std::max(L.w, L.h);
    parallel::parallelFor(static_cast<std::size_t>(bands), workers, [&](std::size_t band, std::size_t) {
        const int y_begin = static_cast<int>(band) * band_height;
        const int y_end = std::min(L.h, y_begin + band_height);
        std::mt19937 random(seed * 7919U + static_cast<std::uint32_t>(iteration) * 104729U + static_cast<std::uint32_t>(band));
        const int step = forward ? 1 : -1;
        for (int yi = 0; yi < y_end - y_begin; ++yi) {
            const int y = forward ? y_begin + yi : y_end - 1 - yi;
            const auto row = static_cast<std::size_t>(y);
            const int first_x = L.target_spans[row][0];
            const int end_x = L.target_spans[row][1];
            const auto row_base = row * static_cast<std::size_t>(L.w);
            for (int xi = 0; xi < end_x - first_x; ++xi) {
                const int x = forward ? first_x + xi : end_x - 1 - xi;
                const auto i = row_base + static_cast<std::size_t>(x);
                if (!L.target[i]) continue;
                int best = L.nnf[i];
                int best_t = L.xf[i];
                float best_dist = L.dist[i];
                const int transforms = L.rot != nullptr ? L.rot->count() : 1;
                const auto consider = [&](int candidate, int t) {
                    if (candidate < 0 || candidate >= L.w * L.h || (candidate == best && t == best_t) || !validSource(L, candidate, t)) return;
                    const float d = patchDistance(L, x, y, candidate, t, best_dist);
                    if (d < best_dist) {
                        best_dist = d;
                        best = candidate;
                        best_t = t;
                    }
                };
                // A step of (sx, sy) in the target maps to the neighbour's source rotated by its transform.
                const auto shifted = [&](int s, int t, int stepx, int stepy) {
                    int ox = stepx, oy = stepy;
                    if (t != 0) {
                        const auto& m = L.rot->matrix[static_cast<std::size_t>(t)];
                        ox = static_cast<int>(std::lround(m[0] * stepx + m[1] * stepy));
                        oy = static_cast<int>(std::lround(m[2] * stepx + m[3] * stepy));
                    }
                    const int nx = s % L.w + ox, ny = s / L.w + oy;
                    return (nx < 0 || ny < 0 || nx >= L.w || ny >= L.h) ? -1 : ny * L.w + nx;
                };
                // Propagation from the previous neighbours in scan order (same band only: no races).
                const int px = x - step;
                if (px >= 0 && px < L.w && L.target[L.index(px, y)] && L.nnf[L.index(px, y)] >= 0) {
                    const auto n = L.index(px, y);
                    consider(shifted(L.nnf[n], L.xf[n], step, 0), L.xf[n]);
                }
                const int py = y - step;
                if (py >= y_begin && py < y_end && L.target[L.index(x, py)] && L.nnf[L.index(x, py)] >= 0) {
                    const auto n = L.index(x, py);
                    consider(shifted(L.nnf[n], L.xf[n], 0, step), L.xf[n]);
                }
                // Random search with an exponentially shrinking window.
                bool alternate = false;
                for (int radius = search_start; radius >= 1; radius /= 2) {
                    const int bx = best % L.w;
                    const int by = best / L.w;
                    std::uniform_int_distribution<int> offset(-radius, radius);
                    const int cx = std::clamp(bx + offset(random), 0, L.w - 1);
                    const int cy = std::clamp(by + offset(random), 0, L.h - 1);
                    // With rotations, every other probe also tries a random transform.
                    int t = best_t;
                    if (transforms > 1 && (alternate = !alternate)) t = std::uniform_int_distribution<int>(0, transforms - 1)(random);
                    consider(cy * L.w + cx, t);
                }
                L.nnf[i] = best;
                L.xf[i] = static_cast<std::uint8_t>(best_t);
                L.dist[i] = best_dist;
            }
        }
    });
}

/** Recomputes every target's patch distance for its current match (parallel over rows). */
void refreshDistances(Level& L, unsigned workers) {
    parallel::parallelFor(static_cast<std::size_t>(L.h), workers, [&](std::size_t row, std::size_t) {
        const int y = static_cast<int>(row);
        const int first_x = L.target_spans[row][0];
        const int end_x = L.target_spans[row][1];
        const auto row_base = row * static_cast<std::size_t>(L.w);
        for (int x = first_x; x < end_x; ++x) {
            const auto i = row_base + static_cast<std::size_t>(x);
            if (L.target[i]) L.dist[i] = patchDistance(L, x, y, L.nnf[i], L.xf[i], 1e30F);
        }
    });
}

/**
 * Re-estimates hole pixels as the mean of the overlapping source patches (gather: no write conflicts).
 * With `best`, each pixel instead takes its value from the overlapping patch with the lowest distance:
 * the final pass uses it so fine texture (grain, noise) stays sharp instead of averaging to a blur.
 */
void vote(Level& L, unsigned workers, bool best = false) {
    std::vector<float> next = L.px;
    parallel::parallelFor(static_cast<std::size_t>(L.h), workers, [&](std::size_t row, std::size_t) {
        const int y = static_cast<int>(row);
        for (int x = 0; x < L.w; ++x) {
            const auto p = L.index(x, y);
            if (!L.hole[p]) continue;
            std::array<double, 4> sum{};
            double weight = 0.0;
            float best_distance = 3.0e38F;
            for (int dy = -kRadius; dy <= kRadius; ++dy) {
                for (int dx = -kRadius; dx <= kRadius; ++dx) {
                    const int tx = x - dx;
                    const int ty = y - dy;
                    if (tx < 0 || ty < 0 || tx >= L.w || ty >= L.h) continue;
                    const auto t = L.index(tx, ty);
                    if (!L.target[t] || L.nnf[t] < 0) continue;
                    const int s = L.nnf[t];
                    const float* c = L.px.data() + sourcePixel(L, s, L.xf[t], dx, dy) * 4U;
                    if (best) {
                        if (L.dist[t] >= best_distance) continue;
                        best_distance = L.dist[t];
                        for (int k = 0; k < 4; ++k) sum[static_cast<std::size_t>(k)] = c[k];
                        weight = 1.0;
                        continue;
                    }
                    for (int k = 0; k < 4; ++k) sum[static_cast<std::size_t>(k)] += c[k];
                    weight += 1.0;
                }
            }
            if (weight > 0.0) {
                for (int k = 0; k < 4; ++k) next[p * 4U + static_cast<std::size_t>(k)] = static_cast<float>(sum[static_cast<std::size_t>(k)] / weight);
            }
        }
    });
    L.px.swap(next);
}

/**
 * Colour adaptation: solves a Poisson equation over the hole so the fill keeps its texture gradients while
 * agreeing with the known pixels around the hole. Guidance: v_pq = F(p) − F(q) of the voted fill F between
 * hole pixels, and S(p) − S(q) of p's matched source patch across the hole's edge. Red-black SOR, parallel over rows; run coarse to fine so each finer
 * level only has to remove high-frequency error.
 */
bool adaptColors(Level& L, int iterations, unsigned workers, const std::function<bool()>& completed_iteration) {
    auto& f_all = L.adapted;
    const auto n = static_cast<std::size_t>(L.w) * static_cast<std::size_t>(L.h);
    // Only the hole's bounding box is visited.
    int bx0 = L.w, by0 = L.h, bx1 = -1, by1 = -1;
    for (int y = 0; y < L.h; ++y) {
        for (int x = 0; x < L.w; ++x) {
            if (!L.hole[L.index(x, y)]) continue;
            bx0 = std::min(bx0, x);
            by0 = std::min(by0, y);
            bx1 = std::max(bx1, x);
            by1 = std::max(by1, y);
        }
    }
    if (bx1 < 0) return true;
    const auto continue_after_iteration = [&] {
        if (!completed_iteration) return true;
        try {
            return completed_iteration();
        } catch (...) {
            return false;
        }
    };
    std::vector<float> guidance(n * 4U, 0.0F);
    std::vector<std::uint8_t> neighbours(n, 0);
    const std::array<std::pair<int, int>, 4> offsets{{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}};
    for (int y = by0; y <= by1; ++y) {
        for (int x = bx0; x <= bx1; ++x) {
            const auto p = L.index(x, y);
            if (!L.hole[p] || L.nnf[p] < 0) continue;
            const int s = L.nnf[p];
            const int sx = s % L.w;
            const int sy = s / L.w;
            (void)sy;
            for (const auto& [dx, dy] : offsets) {
                const int qx = x + dx, qy = y + dy;
                if (qx < 0 || qy < 0 || qx >= L.w || qy >= L.h) continue;
                // Between two hole pixels, keep the voted fill's own gradient: it is conservative, whereas
                // per-pixel match gradients disagree on noisy texture and the solve would drift. Across the
                // hole's edge, use the match's gradient (what the fill would continue into), so the edge step
                // to the known pixel is removed. p's match is a valid source centre, so its ±1 neighbours are
                // inside the level.
                const bool interior = L.hole[L.index(qx, qy)] != 0U;
                const float* sp = L.px.data() + (interior ? p : L.index(sx, sy)) * 4U;
                const float* sq = L.px.data() + (interior ? L.index(qx, qy) : sourcePixel(L, s, L.xf[p], dx, dy)) * 4U;
                for (int c = 0; c < 4; ++c) guidance[p * 4U + static_cast<std::size_t>(c)] += sp[c] - sq[c];
                ++neighbours[p];
            }
        }
    }
    constexpr float kOmega = 1.85F;
    const auto updateRows = [&](unsigned worker, unsigned worker_count, int colour) {
        for (int y = by0 + static_cast<int>(worker); y <= by1; y += static_cast<int>(worker_count)) {
            for (int x = bx0 + (y + bx0 + colour) % 2; x <= bx1; x += 2) {
                const auto p = L.index(x, y);
                if (!L.hole[p] || neighbours[p] == 0) continue;
                std::array<float, 4> sum{};
                for (const auto& [dx, dy] : offsets) {
                    const int qx = x + dx, qy = y + dy;
                    if (qx < 0 || qy < 0 || qx >= L.w || qy >= L.h) continue;
                    const float* q = f_all.data() + L.index(qx, qy) * 4U;
                    for (int c = 0; c < 4; ++c) sum[static_cast<std::size_t>(c)] += q[c];
                }
                float* f = f_all.data() + p * 4U;
                for (int c = 0; c < 4; ++c) {
                    const float target = (sum[static_cast<std::size_t>(c)] + guidance[p * 4U + static_cast<std::size_t>(c)]) /
                                         neighbours[p];
                    f[c] += kOmega * (target - f[c]);
                }
            }
        }
    };

    // Keep workers alive across the SOR sweeps. Spawning a fresh set for each color pass made a
    // 200-iteration coarse solve create thousands of short-lived threads on Android.
    const auto requested_workers = std::min<unsigned>(workers, static_cast<unsigned>(by1 - by0 + 1));
    if (requested_workers <= 1U) {
        for (int it = 0; it < iterations; ++it) {
            for (int colour = 0; colour < 2; ++colour) updateRows(0, 1, colour);
            if (!continue_after_iteration()) return false;
        }
        return true;
    }

    std::barrier phase_gate(static_cast<std::ptrdiff_t>(requested_workers));
    std::atomic<bool> start_workers{false};
    std::atomic<bool> cancelled{false};
    std::vector<parallel::JoiningThread> threads;
    threads.reserve(requested_workers - 1U);
    unsigned worker_count = requested_workers;
    for (unsigned worker = 1; worker < requested_workers; ++worker) {
        try {
            threads.emplace_back([&, worker] {
                while (!start_workers.load(std::memory_order_acquire)) std::this_thread::yield();
                for (int it = 0; it < iterations; ++it) {
                    for (int colour = 0; colour < 2; ++colour) {
                        updateRows(worker, worker_count, colour);
                        phase_gate.arrive_and_wait();
                        if (cancelled.load(std::memory_order_acquire)) return;
                    }
                }
            });
        } catch (...) {
            const auto missing = requested_workers - static_cast<unsigned>(threads.size()) - 1U;
            for (unsigned i = 0; i < missing; ++i) phase_gate.arrive_and_drop();
            worker_count = static_cast<unsigned>(threads.size()) + 1U;
            break;
        }
    }
    start_workers.store(true, std::memory_order_release);
    for (int it = 0; it < iterations; ++it) {
        for (int colour = 0; colour < 2; ++colour) {
            updateRows(0, worker_count, colour);
            if (colour == 1 && !continue_after_iteration()) cancelled.store(true, std::memory_order_release);
            phase_gate.arrive_and_wait();
            if (cancelled.load(std::memory_order_acquire)) break;
        }
        if (cancelled.load(std::memory_order_acquire)) break;
    }
    return !cancelled.load(std::memory_order_acquire);
}

/** Initial estimate at the coarsest level: repeatedly average known 8-neighbours into the hole. */
void onionPeel(Level& L) {
    std::vector<std::uint8_t> known(L.hole.size());
    for (std::size_t i = 0; i < known.size(); ++i) known[i] = L.hole[i] ? 0U : 1U;
    bool changed = true;
    while (changed) {
        changed = false;
        std::vector<std::uint8_t> newly(known.size(), 0);
        for (int y = 0; y < L.h; ++y) {
            for (int x = 0; x < L.w; ++x) {
                const auto i = L.index(x, y);
                if (known[i]) continue;
                std::array<double, 4> sum{};
                int count = 0;
                for (int dy = -1; dy <= 1; ++dy) {
                    for (int dx = -1; dx <= 1; ++dx) {
                        const int nx = x + dx, ny = y + dy;
                        if ((dx == 0 && dy == 0) || nx < 0 || ny < 0 || nx >= L.w || ny >= L.h) continue;
                        const auto j = L.index(nx, ny);
                        if (!known[j]) continue;
                        for (int k = 0; k < 4; ++k) sum[static_cast<std::size_t>(k)] += L.px[j * 4U + static_cast<std::size_t>(k)];
                        ++count;
                    }
                }
                if (count == 0) continue;
                for (int k = 0; k < 4; ++k) L.px[i * 4U + static_cast<std::size_t>(k)] = static_cast<float>(sum[static_cast<std::size_t>(k)] / count);
                newly[i] = 1;
                changed = true;
            }
        }
        for (std::size_t i = 0; i < known.size(); ++i) known[i] |= newly[i];
    }
}

Level downsample(const Level& f) {
    Level c;
    c.w = std::max(1, (f.w + 1) / 2);
    c.h = std::max(1, (f.h + 1) / 2);
    const auto n = static_cast<std::size_t>(c.w) * c.h;
    c.px.assign(n * 4U, 0.0F);
    c.hole.assign(n, 0);
    c.usable.assign(n, 1);
    for (int y = 0; y < c.h; ++y) {
        for (int x = 0; x < c.w; ++x) {
            std::array<double, 4> sum{};
            int known = 0;
            bool any_hole = false;
            bool all_usable = true;
            for (int dy = 0; dy < 2; ++dy) {
                for (int dx = 0; dx < 2; ++dx) {
                    const int fx = std::min(f.w - 1, 2 * x + dx), fy = std::min(f.h - 1, 2 * y + dy);
                    const auto i = f.index(fx, fy);
                    any_hole = any_hole || f.hole[i];
                    all_usable = all_usable && f.usable[i];
                    if (f.hole[i]) continue;
                    for (int k = 0; k < 4; ++k) sum[static_cast<std::size_t>(k)] += f.px[i * 4U + static_cast<std::size_t>(k)];
                    ++known;
                }
            }
            const auto i = c.index(x, y);
            c.hole[i] = any_hole ? 1U : 0U;
            c.usable[i] = (!any_hole && all_usable) ? 1U : 0U;
            if (known > 0) {
                for (int k = 0; k < 4; ++k) c.px[i * 4U + static_cast<std::size_t>(k)] = static_cast<float>(sum[static_cast<std::size_t>(k)] / known);
            }
        }
    }
    return c;
}

bool isRgba(PixelFormat f) { return f == PixelFormat::RGBA8 || f == PixelFormat::RGBA16 || f == PixelFormat::RGBA32F; }

void readPixel(PixelFormat format, const std::uint8_t* p, float* out) {
    for (std::size_t c = 0; c < 4; ++c) {
        if (format == PixelFormat::RGBA8) {
            out[c] = p[c] / 255.0F;
        } else if (format == PixelFormat::RGBA16) {
            std::uint16_t v = 0;
            std::memcpy(&v, p + c * 2U, 2U);
            out[c] = v / 65535.0F;
        } else {
            float v = 0.0F;
            std::memcpy(&v, p + c * 4U, 4U);
            out[c] = std::isfinite(v) ? v : 0.0F;
        }
    }
}

void writePixel(PixelFormat format, std::uint8_t* p, const float* v) {
    for (std::size_t c = 0; c < 4; ++c) {
        if (format == PixelFormat::RGBA8) {
            p[c] = static_cast<std::uint8_t>(std::lround(std::clamp(v[c], 0.0F, 1.0F) * 255.0F));
        } else if (format == PixelFormat::RGBA16) {
            const auto q = static_cast<std::uint16_t>(std::lround(std::clamp(v[c], 0.0F, 1.0F) * 65535.0F));
            std::memcpy(p + c * 2U, &q, 2U);
        } else {
            std::memcpy(p + c * 4U, &v[c], 4U);
        }
    }
}

}  // namespace

std::optional<TileStore> contentAwareFill(const TileStore& image, const TileStore& hole, const TileStore* sampling,
                                          const ContentAwareFillSpec& spec, std::string& error) {
    const auto matches = [&](const TileStore& m) {
        return m.format() == PixelFormat::A16 && m.width() == image.width() && m.height() == image.height();
    };
    if (!isRgba(image.format()) || !matches(hole) || (sampling != nullptr && !matches(*sampling)) || spec.iterations < 1 ||
        spec.iterations > 50) {
        error = "Content-Aware Fill needs an RGBA layer, matching A16 hole/sampling masks and 1–50 iterations";
        return std::nullopt;
    }
    const auto hole_bounds = selection::bounds(hole);
    if (!hole_bounds) return image;
    const auto hole_w = hole_bounds->right - hole_bounds->left;
    const auto hole_h = hole_bounds->bottom - hole_bounds->top;
    const auto margin = spec.margin != 0U ? static_cast<std::int64_t>(spec.margin) : std::max<std::int64_t>(64, std::max(hole_w, hole_h) / 2);
    const auto region = selection::detail::regionAround(hole, *hole_bounds, margin);
    // px (16) + hole/usable/target/source (4) + nnf/dist (8) + box counts during setup (12) + vote copy (16).
    if (region.pixels() > spec.max_working_bytes / 56U) {
        error = "the area to fill is too large for the memory limit";
        return std::nullopt;
    }
    const auto workers = parallel::workerCount(spec.worker_threads);
    bool progress_failed = false;
    const auto report = [&](float fraction) {
        try {
            return !spec.progress || spec.progress(fraction);
        } catch (...) {
            progress_failed = true;
            return false;
        }
    };
    try {
        // Level 0: the region at full resolution.
        Level base;
        base.w = static_cast<int>(region.width());
        base.h = static_cast<int>(region.height());
        const auto n = static_cast<std::size_t>(base.w) * base.h;
        base.px.assign(n * 4U, 0.0F);
        base.hole.assign(n, 0);
        base.usable.assign(n, 1);
        const auto hole_plane = selection::detail::extract(hole, region);
        std::vector<std::uint16_t> sample_plane;
        if (sampling != nullptr) sample_plane = selection::detail::extract(*sampling, region);
        const auto bpp = image.bytes_per_pixel();
        for (std::uint32_t ty = region.y0 / kTileEdge; ty * kTileEdge < region.y1; ++ty) {
            for (std::uint32_t tx = region.x0 / kTileEdge; tx * kTileEdge < region.x1; ++tx) {
                const auto tile = image.readTile({static_cast<std::int32_t>(tx), static_cast<std::int32_t>(ty)});
                if (tile == nullptr) continue;
                const auto rows = std::min<std::uint32_t>(kTileEdge, region.y1 - ty * kTileEdge);
                const auto cols = std::min<std::uint32_t>(kTileEdge, region.x1 - tx * kTileEdge);
                for (std::uint32_t y = 0; y < rows; ++y) {
                    for (std::uint32_t x = 0; x < cols; ++x) {
                        const auto i = static_cast<std::size_t>(ty * kTileEdge + y - region.y0) * base.w + (tx * kTileEdge + x - region.x0);
                        readPixel(image.format(), tile->data() + (static_cast<std::size_t>(y) * kTileEdge + x) * bpp, base.px.data() + i * 4U);
                    }
                }
            }
        }
        for (std::size_t i = 0; i < n; ++i) {
            base.hole[i] = hole_plane[i] >= kHalfCoverage ? 1U : 0U;
            base.usable[i] = (!base.hole[i] && (sampling == nullptr || sample_plane[i] >= kHalfCoverage)) ? 1U : 0U;
        }

        // Pyramid down to a ~16 px hole.
        std::vector<Level> levels;
        levels.push_back(std::move(base));
        int hole_extent = static_cast<int>(std::max(hole_w, hole_h));
        while (hole_extent > 16 && levels.back().w > 64 && levels.back().h > 64) {
            levels.push_back(downsample(levels.back()));
            hole_extent = (hole_extent + 1) / 2;
        }
        Rotations rotations;
        switch (spec.rotation_adaptation) {
            case RotationAdaptation::None: rotations.build({0}, spec.mirror_adaptation); break;
            case RotationAdaptation::Low: rotations.build({0, -15, -7.5, 7.5, 15}, spec.mirror_adaptation); break;
            case RotationAdaptation::Medium: rotations.build({0, -45, -30, -15, 15, 30, 45}, spec.mirror_adaptation); break;
            case RotationAdaptation::High: rotations.build({0, -90, -75, -60, -45, -30, -15, 15, 30, 45, 60, 75, 90}, spec.mirror_adaptation); break;
            case RotationAdaptation::Full: {
                std::vector<double> all{0};
                for (int d = 15; d < 360; d += 15) all.push_back(d);
                rotations.build(all, spec.mirror_adaptation);
                break;
            }
        }
        for (auto& level : levels) {
            level.rot = &rotations;
            if (!prepare(level, [&] { return report(0.0F); })) {
                error = "cancelled";
                return std::nullopt;
            }
        }
        for (const auto& level : levels) {
            if (level.sources.empty()) {
                error = "there is not enough area to sample from";
                return std::nullopt;
            }
        }

        onionPeel(levels.back());
        int total_units = 0;
        for (std::size_t l = 0; l < levels.size(); ++l) {
            total_units += l + 1 == levels.size() ? spec.iterations : std::max(2, spec.iterations / 2);
            if (spec.color_adaptation) total_units += l + 1 == levels.size() ? 200 : 25;
        }
        int done_units = 0;
        std::mt19937 init_random(spec.seed);
        for (int l = static_cast<int>(levels.size()) - 1; l >= 0; --l) {
            auto& L = levels[static_cast<std::size_t>(l)];
            const bool coarsest = l == static_cast<int>(levels.size()) - 1;
            if (!coarsest) {
                const auto& C = levels[static_cast<std::size_t>(l + 1)];
                // Upsample the coarse estimate into this level's hole, then its nearest-neighbour field.
                for (int y = 0; y < L.h; ++y) {
                    if ((y & 31) == 0 && !report(static_cast<float>(done_units) / static_cast<float>(total_units))) {
                        error = "cancelled";
                        return std::nullopt;
                    }
                    for (int x = 0; x < L.w; ++x) {
                        const auto i = L.index(x, y);
                        if (L.hole[i]) {
                            // Centre-aligned bilinear upsampling keeps the coarse fill's phase (nearest copies are
                            // half a pixel off and blocky, which lets a wrongly aligned match win at this level).
                            const float fx = std::clamp((x + 0.5F) / 2.0F - 0.5F, 0.0F, static_cast<float>(C.w - 1));
                            const float fy = std::clamp((y + 0.5F) / 2.0F - 0.5F, 0.0F, static_cast<float>(C.h - 1));
                            const int x0 = static_cast<int>(fx), y0 = static_cast<int>(fy);
                            const int x1 = std::min(C.w - 1, x0 + 1), y1 = std::min(C.h - 1, y0 + 1);
                            const float ax = fx - static_cast<float>(x0), ay = fy - static_cast<float>(y0);
                            for (int c = 0; c < 4; ++c) {
                                const auto v = [&](int cx, int cy) { return C.px[C.index(cx, cy) * 4U + static_cast<std::size_t>(c)]; };
                                L.px[i * 4U + static_cast<std::size_t>(c)] =
                                    (1 - ay) * ((1 - ax) * v(x0, y0) + ax * v(x1, y0)) + ay * ((1 - ax) * v(x0, y1) + ax * v(x1, y1));
                            }
                        }
                    }
                }
                // Seed matches in a separate span pass so the dense upsample loop and target traversal stay bounded independently.
                for (int y = 0; y < L.h; ++y) {
                    if ((y & 31) == 0 && !report(static_cast<float>(done_units) / static_cast<float>(total_units))) {
                        error = "cancelled";
                        return std::nullopt;
                    }
                    const auto row = static_cast<std::size_t>(y);
                    const int first_x = L.target_spans[row][0];
                    const int end_x = L.target_spans[row][1];
                    const auto row_base = row * static_cast<std::size_t>(L.w);
                    for (int x = first_x; x < end_x; ++x) {
                        const auto i = row_base + static_cast<std::size_t>(x);
                        if (!L.target[i]) continue;
                        const auto ci = C.index(std::min(C.w - 1, x / 2), std::min(C.h - 1, y / 2));
                        int candidate = -1;
                        const int coarse_t = C.xf.empty() ? 0 : C.xf[ci];
                        if (C.nnf[ci] >= 0) {
                            // The child's offset from its 2×2 block centre maps through the patch transform.
                            double ox = x % 2 - 0.5, oy = y % 2 - 0.5;
                            if (coarse_t != 0) {
                                const auto& m = L.rot->matrix[static_cast<std::size_t>(coarse_t)];
                                const double rx = m[0] * ox + m[1] * oy;
                                oy = m[2] * ox + m[3] * oy;
                                ox = rx;
                            }
                            const int sx = std::clamp(static_cast<int>(std::floor((C.nnf[ci] % C.w) * 2 + 1 + ox)), 0, L.w - 1);
                            const int sy = std::clamp(static_cast<int>(std::floor((C.nnf[ci] / C.w) * 2 + 1 + oy)), 0, L.h - 1);
                            candidate = sy * L.w + sx;
                        }
                        int t = coarse_t;
                        if (candidate < 0 || !validSource(L, candidate, t)) {
                            std::uniform_int_distribution<std::size_t> pick(0, L.sources.size() - 1);
                            candidate = L.sources[pick(init_random)];
                            t = 0;
                        }
                        L.nnf[i] = candidate;
                        L.xf[i] = static_cast<std::uint8_t>(t);
                    }
                }
            } else {
                std::uniform_int_distribution<std::size_t> pick(0, L.sources.size() - 1);
                for (int y = 0; y < L.h; ++y) {
                    if ((y & 31) == 0 && !report(static_cast<float>(done_units) / static_cast<float>(total_units))) {
                        error = "cancelled";
                        return std::nullopt;
                    }
                    const auto row = static_cast<std::size_t>(y);
                    const int first_x = L.target_spans[row][0];
                    const int end_x = L.target_spans[row][1];
                    const auto row_base = row * static_cast<std::size_t>(L.w);
                    for (int x = first_x; x < end_x; ++x) {
                        const auto i = row_base + static_cast<std::size_t>(x);
                        if (L.target[i]) L.nnf[i] = L.sources[pick(init_random)];
                    }
                }
            }
            refreshDistances(L, workers);
            const int iterations = coarsest ? spec.iterations : std::max(2, spec.iterations / 2);
            for (int it = 0; it < iterations; ++it) {
                patchMatchPass(L, it, spec.seed + static_cast<std::uint32_t>(l) * 1000U, workers);
                vote(L, workers, l == 0 && it + 1 == iterations);
                // Distances changed with the estimate; refresh so the next pass compares fairly.
                refreshDistances(L, workers);
                ++done_units;
                if (!report(static_cast<float>(done_units) / static_cast<float>(total_units))) {
                    error = "cancelled";
                    return std::nullopt;
                }
            }
            if (spec.color_adaptation) {
                L.adapted = L.px;
                if (!coarsest) {
                    // Start from the coarser level's adapted solution; only high-frequency error remains.
                    const auto& C = levels[static_cast<std::size_t>(l + 1)];
                    for (int y = 0; y < L.h; ++y) {
                        if ((y & 31) == 0 && !report(static_cast<float>(done_units) / static_cast<float>(total_units))) {
                            error = "cancelled";
                            return std::nullopt;
                        }
                        for (int x = 0; x < L.w; ++x) {
                            const auto i = L.index(x, y);
                            if (!L.hole[i]) continue;
                            const auto ci = C.index(std::min(C.w - 1, x / 2), std::min(C.h - 1, y / 2));
                            for (int c = 0; c < 4; ++c) {
                                // Coarse adaptation offset applied to this level's raw estimate.
                                L.adapted[i * 4U + static_cast<std::size_t>(c)] +=
                                    C.adapted[ci * 4U + static_cast<std::size_t>(c)] - C.px[ci * 4U + static_cast<std::size_t>(c)];
                            }
                        }
                    }
                }
                if (!adaptColors(L, coarsest ? 200 : 25, workers, [&] {
                        ++done_units;
                        return report(static_cast<float>(done_units) / static_cast<float>(total_units));
                    })) {
                    error = "cancelled";
                    return std::nullopt;
                }
            }
        }

        // Write the filled hole back, blended by coverage.
        const auto& result = levels.front();
        const auto& output = spec.color_adaptation ? result.adapted : result.px;
        TileStore out(image);
        for (std::uint32_t ty = region.y0 / kTileEdge; ty * kTileEdge < region.y1; ++ty) {
            if (!report(static_cast<float>(done_units) / static_cast<float>(total_units))) {
                error = "cancelled";
                return std::nullopt;
            }
            for (std::uint32_t tx = region.x0 / kTileEdge; tx * kTileEdge < region.x1; ++tx) {
                const TileCoordinate coordinate{static_cast<std::int32_t>(tx), static_cast<std::int32_t>(ty)};
                const auto hole_tile = hole.readTile(coordinate);
                if (hole_tile == nullptr) continue;
                const auto existing = image.readTile(coordinate);
                TileBuffer tile = existing ? *existing : TileBuffer(image.tile_byte_size(), 0);
                bool changed = false;
                const auto rows = std::min<std::uint32_t>(kTileEdge, region.y1 - ty * kTileEdge);
                const auto cols = std::min<std::uint32_t>(kTileEdge, region.x1 - tx * kTileEdge);
                for (std::uint32_t y = 0; y < rows; ++y) {
                    for (std::uint32_t x = 0; x < cols; ++x) {
                        std::uint16_t coverage_raw = 0;
                        std::memcpy(&coverage_raw, hole_tile->data() + (static_cast<std::size_t>(y) * kTileEdge + x) * 2U, 2U);
                        if (coverage_raw == 0U) continue;
                        const float coverage = coverage_raw / 65535.0F;
                        const auto i = static_cast<std::size_t>(ty * kTileEdge + y - region.y0) * result.w + (tx * kTileEdge + x - region.x0);
                        auto* p = tile.data() + (static_cast<std::size_t>(y) * kTileEdge + x) * bpp;
                        std::array<float, 4> original{};
                        readPixel(image.format(), p, original.data());
                        std::array<float, 4> mixed{};
                        for (std::size_t k = 0; k < 4; ++k) mixed[k] = original[k] + coverage * (output[i * 4U + k] - original[k]);
                        writePixel(image.format(), p, mixed.data());
                        changed = true;
                    }
                }
                if (changed && !out.writeTile(coordinate, tile)) {
                    error = "could not store the filled pixels";
                    return std::nullopt;
                }
            }
        }
        return out;
    } catch (const std::exception&) {
        error = progress_failed ? "cancelled" : "could not allocate Content-Aware Fill buffers";
        return std::nullopt;
    }
}

}  // namespace croptop::paint
