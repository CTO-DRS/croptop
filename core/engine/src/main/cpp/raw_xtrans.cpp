// Directional X-Trans demosaicing.
#include "raw_xtrans.h"

#include <algorithm>
#include <array>
#include <cmath>
#include <limits>
#include <vector>

namespace croptop::raw::detail {
namespace {

constexpr std::array<std::array<int, 2>, 4> kDirections{{{1, 0}, {0, 1}, {1, 1}, {1, -1}}};

}  // namespace

void xtransDirectional(std::span<const float> cfa, std::span<const std::uint8_t> colours, std::span<float> red, std::span<float> green,
                       std::span<float> blue, int width, int height) {
    if (width < 20 || height < 20) return;
    const auto w = static_cast<std::size_t>(width);
    const auto h = static_cast<std::size_t>(height);
    if (w > std::numeric_limits<std::size_t>::max() / h) return;
    const auto n = w * h;
    if (cfa.size() != n || colours.size() != n || red.size() != n || green.size() != n || blue.size() != n) return;
    const auto at = [w](int x, int y) { return static_cast<std::size_t>(y) * w + static_cast<std::size_t>(x); };

    // True when the colour map repeats every 6 pixels in both axes (a real X-Trans sensor); enables phase tables.
    bool periodic = width >= 24 && height >= 24;
    for (std::size_t y = 0; periodic && y < h; ++y)
        for (std::size_t x = 0; x < w; ++x)
            if (colours[y * w + x] != colours[(y % 6) * w + x % 6] && (y >= 6 || x >= 6)) {
                periodic = false;
                break;
            }
    // Per direction: a full RGB candidate.
    std::array<std::array<std::vector<float>, 3>, 4> cand;
    for (auto& d : cand)
        for (auto& plane : d) plane.assign(n, 0.0F);

    for (int d = 0; d < 4; ++d) {
        const int dx = kDirections[static_cast<std::size_t>(d)][0], dy = kDirections[static_cast<std::size_t>(d)][1];
        auto& g = cand[static_cast<std::size_t>(d)][1];
        // A. Green along the direction: linear interpolation between the nearest greens on either side.
        for (int y = 3; y < height - 3; ++y)
            for (int x = 3; x < width - 3; ++x) {
                const auto i = at(x, y);
                if (colours[i] == 1) {
                    g[i] = cfa[i];
                    continue;
                }
                float plus = 0, minus = 0;
                int kp = 0, km = 0;
                for (int k = 1; k <= 3 && kp == 0; ++k)
                    if (colours[at(x + k * dx, y + k * dy)] == 1) {
                        plus = cfa[at(x + k * dx, y + k * dy)];
                        kp = k;
                    }
                for (int k = 1; k <= 3 && km == 0; ++k)
                    if (colours[at(x - k * dx, y - k * dy)] == 1) {
                        minus = cfa[at(x - k * dx, y - k * dy)];
                        km = k;
                    }
                if (kp > 0 && km > 0) {
                    g[i] = (plus * static_cast<float>(km) + minus * static_cast<float>(kp)) / static_cast<float>(kp + km);
                } else if (kp > 0 || km > 0) {
                    g[i] = kp > 0 ? plus : minus;
                } else {
                    float sum = 0;
                    int count = 0;
                    for (int oy = -1; oy <= 1; ++oy)
                        for (int ox = -1; ox <= 1; ++ox)
                            if (colours[at(x + ox, y + oy)] == 1) {
                                sum += cfa[at(x + ox, y + oy)];
                                ++count;
                            }
                    g[i] = count > 0 ? sum / static_cast<float>(count) : cfa[i];
                }
            }
        // B. Red and blue from colour differences (C − G) of the nearest same-colour sites, favouring
        //    samples that lie along this candidate's direction.
        const float dlen = std::hypot(static_cast<float>(dx), static_cast<float>(dy));
        // The weight depends only on the offset: tabulate it once per direction.
        std::array<float, 25> weights{};
        for (int oy = -2; oy <= 2; ++oy)
            for (int ox = -2; ox <= 2; ++ox) {
                if (ox == 0 && oy == 0) continue;
                const float dist2 = static_cast<float>(ox * ox + oy * oy);
                const float align = std::abs(static_cast<float>(ox * dx + oy * dy)) / (std::sqrt(dist2) * dlen);
                weights[static_cast<std::size_t>((oy + 2) * 5 + ox + 2)] = (1.0F + 2.0F * align * align) / (1.0F + dist2);
            }
        for (const int c : {0, 2}) {
            auto& ch = cand[static_cast<std::size_t>(d)][static_cast<std::size_t>(c)];
            // Colour-difference plane, so the inner loop is one load per contributing site.
            std::vector<float> diff(n, 0.0F);
            for (std::size_t j = 0; j < n; ++j) diff[j] = colours[j] == c ? cfa[j] - g[j] : 0.0F;
            // Contributing offsets and weights per 6×6 phase (only when the mosaic really is 6-periodic).
            struct Site { std::ptrdiff_t offset; float weight; };
            std::array<std::vector<Site>, 36> sites;
            if (periodic) {
                for (int py = 0; py < 6; ++py)
                    for (int px = 0; px < 6; ++px)
                        for (int oy = -2; oy <= 2; ++oy)
                            for (int ox = -2; ox <= 2; ++ox)
                                if (colours[at(6 + px + ox, 6 + py + oy)] == c && !(ox == 0 && oy == 0))
                                    sites[static_cast<std::size_t>(py * 6 + px)].push_back(
                                        {static_cast<std::ptrdiff_t>(oy) * static_cast<std::ptrdiff_t>(w) + ox, weights[static_cast<std::size_t>((oy + 2) * 5 + ox + 2)]});
            }
            for (int y = 5; y < height - 5; ++y)
                for (int x = 5; x < width - 5; ++x) {
                    const auto i = at(x, y);
                    if (colours[i] == c) {
                        ch[i] = cfa[i];
                        continue;
                    }
                    float sum = 0, weight = 0;
                    if (periodic) {
                        for (const auto& site : sites[static_cast<std::size_t>((y % 6) * 6 + x % 6)]) {
                            sum += site.weight * diff[static_cast<std::size_t>(static_cast<std::ptrdiff_t>(i) + site.offset)];
                            weight += site.weight;
                        }
                    } else {
                        for (int oy = -2; oy <= 2; ++oy)
                            for (int ox = -2; ox <= 2; ++ox) {
                                const auto j = at(x + ox, y + oy);
                                if (colours[j] != c) continue;
                                const float w = weights[static_cast<std::size_t>((oy + 2) * 5 + ox + 2)];
                                sum += w * (cfa[j] - g[j]);
                                weight += w;
                            }
                    }
                    ch[i] = std::max(0.0F, g[i] + (weight > 0 ? sum / weight : 0.0F));
                }
        }
    }

    // C. Homogeneity: each candidate's perceptual derivative along its own direction.
    const auto encode = [](float v) { return std::sqrt(std::max(0.0F, v)); };
    std::array<std::vector<float>, 4> drv;
    for (int d = 0; d < 4; ++d) {
        drv[static_cast<std::size_t>(d)].assign(n, 1e30F);
        const int dx = kDirections[static_cast<std::size_t>(d)][0], dy = kDirections[static_cast<std::size_t>(d)][1];
        const auto& cd = cand[static_cast<std::size_t>(d)];
        const auto lab = [&](std::size_t i) {
            const float r = encode(cd[0][i]), g = encode(cd[1][i]), b = encode(cd[2][i]);
            return std::array<float, 3>{0.25F * (r + 2.0F * g + b), r - g, b - g};
        };
        for (int y = 6; y < height - 6; ++y)
            for (int x = 6; x < width - 6; ++x) {
                const auto c0 = lab(at(x, y)), cp = lab(at(x + dx, y + dy)), cm = lab(at(x - dx, y - dy));
                float sum = 0;
                for (int k = 0; k < 3; ++k) {
                    const float a = c0[static_cast<std::size_t>(k)] - cp[static_cast<std::size_t>(k)], b = c0[static_cast<std::size_t>(k)] - cm[static_cast<std::size_t>(k)];
                    sum += a * a + b * b;
                }
                drv[static_cast<std::size_t>(d)][at(x, y)] = sum;
            }
    }
    std::array<std::vector<float>, 4> homo;
    for (auto& h : homo) h.assign(n, 0.0F);
    {
        // Row-pointer form of the 3×3 count so the compiler can vectorise across x (same values as the indexed loops).
        std::vector<float> threshold(w);
        for (int y = 7; y < height - 7; ++y) {
            for (int x = 7; x < width - 7; ++x) {
                const auto i = at(x, y);
                float tr = std::min(std::min(drv[0][i], drv[1][i]), std::min(drv[2][i], drv[3][i]));
                threshold[static_cast<std::size_t>(x)] = tr * 8.0F + 1e-12F;
            }
            for (std::size_t d = 0; d < 4; ++d) {
                const float* above = drv[d].data() + at(0, y - 1);
                const float* mid = drv[d].data() + at(0, y);
                const float* below = drv[d].data() + at(0, y + 1);
                float* out = homo[d].data() + at(0, y);
                for (int x = 7; x < width - 7; ++x) {
                    const float tr = threshold[static_cast<std::size_t>(x)];
                    out[x] = static_cast<float>((above[x - 1] <= tr) + (above[x] <= tr) + (above[x + 1] <= tr) + (mid[x - 1] <= tr) + (mid[x] <= tr) +
                                                (mid[x + 1] <= tr) + (below[x - 1] <= tr) + (below[x] <= tr) + (below[x + 1] <= tr));
                }
            }
        }
    }

    // 5×5 sums of the homogeneity counts as two separable passes (small integers: exact in float, so order does not matter).
    std::array<std::vector<float>, 4> hsum;
    {
        std::vector<float> row_sum(n);
        for (std::size_t d = 0; d < 4; ++d) {
            hsum[d].assign(n, 0.0F);
            for (int y = 7; y < height - 7; ++y) {
                const float* in = homo[d].data() + at(0, y);
                float* out = row_sum.data() + at(0, y);
                for (int x = 9; x < width - 9; ++x) out[x] = in[x - 2] + in[x - 1] + in[x] + in[x + 1] + in[x + 2];
            }
            for (int y = 9; y < height - 9; ++y) {
                const float* r0 = row_sum.data() + at(0, y - 2);
                const float* r1 = row_sum.data() + at(0, y - 1);
                const float* r2 = row_sum.data() + at(0, y);
                const float* r3 = row_sum.data() + at(0, y + 1);
                const float* r4 = row_sum.data() + at(0, y + 2);
                float* out = hsum[d].data() + at(0, y);
                for (int x = 9; x < width - 9; ++x) out[x] = r0[x] + r1[x] + r2[x] + r3[x] + r4[x];
            }
        }
    }

    // D. Per pixel: average the candidates whose 5×5 homogeneity is within 1/8 of the best.
    for (int y = 0; y < height; ++y)
        for (int x = 0; x < width; ++x) {
            const auto i = at(x, y);
            if (y < 9 || y >= height - 9 || x < 9 || x >= width - 9) {
                // Candidate planes are only populated from a 5 px inset for red/blue. Extend
                // the nearest complete candidate across the output rim instead of copying zeros.
                const int sx = std::clamp(x, 5, width - 6);
                const int sy = std::clamp(y, 5, height - 6);
                const auto source = at(sx, sy);
                red[i] = cand[0][0][source];
                green[i] = cand[0][1][source];
                blue[i] = cand[0][2][source];
                continue;
            }
            const std::array<float, 4> hm{hsum[0][i], hsum[1][i], hsum[2][i], hsum[3][i]};
            const float best = *std::max_element(hm.begin(), hm.end());
            float rgb[3] = {0, 0, 0};
            int used = 0;
            for (int d = 0; d < 4; ++d) {
                if (hm[static_cast<std::size_t>(d)] < best - best / 8.0F) continue;
                for (int c = 0; c < 3; ++c) rgb[c] += cand[static_cast<std::size_t>(d)][static_cast<std::size_t>(c)][i];
                ++used;
            }
            red[i] = rgb[0] / static_cast<float>(used);
            green[i] = rgb[1] / static_cast<float>(used);
            blue[i] = rgb[2] / static_cast<float>(used);
        }
}

}  // namespace croptop::raw::detail
