// Raw development front end.
#include "raw_demosaic.h"
#include "raw_amaze.h"
#include "raw_xtrans.h"

#include <algorithm>
#include <atomic>
#include <cmath>
#include <cstring>
#include <vector>

#include "parallel_util.h"
#include "pixel_plane.h"

namespace croptop::raw {
namespace {

constexpr int kBorder = 12;  // RCD reads up to ±4 px per stage across several dependent stages
constexpr float kEps = 1e-5F;

using Matrix = std::array<double, 9>;

Matrix multiply(const Matrix& a, const Matrix& b) {
    Matrix m{};
    for (int r = 0; r < 3; ++r)
        for (int c = 0; c < 3; ++c)
            for (int k = 0; k < 3; ++k) m[static_cast<std::size_t>(r * 3 + c)] += a[static_cast<std::size_t>(r * 3 + k)] * b[static_cast<std::size_t>(k * 3 + c)];
    return m;
}

std::optional<Matrix> invert(const Matrix& a) {
    const double det = a[0] * (a[4] * a[8] - a[5] * a[7]) - a[1] * (a[3] * a[8] - a[5] * a[6]) + a[2] * (a[3] * a[7] - a[4] * a[6]);
    if (!std::isfinite(det) || std::abs(det) < 1e-12) return std::nullopt;
    const double k = 1.0 / det;
    return Matrix{(a[4] * a[8] - a[5] * a[7]) * k, (a[2] * a[7] - a[1] * a[8]) * k, (a[1] * a[5] - a[2] * a[4]) * k,
                  (a[5] * a[6] - a[3] * a[8]) * k, (a[0] * a[8] - a[2] * a[6]) * k, (a[2] * a[3] - a[0] * a[5]) * k,
                  (a[3] * a[7] - a[4] * a[6]) * k, (a[1] * a[6] - a[0] * a[7]) * k, (a[0] * a[4] - a[1] * a[3]) * k};
}

/** RGB → XYZ for the given primaries and white (xy chromaticities). */
Matrix rgbToXyz(const std::array<double, 8>& xy) {
    const auto column = [&](int i) {
        const double x = xy[static_cast<std::size_t>(i * 2)], y = xy[static_cast<std::size_t>(i * 2 + 1)];
        return std::array<double, 3>{x / y, 1.0, (1.0 - x - y) / y};
    };
    const auto r = column(0), g = column(1), b = column(2), w = column(3);
    const Matrix p{r[0], g[0], b[0], r[1], g[1], b[1], r[2], g[2], b[2]};
    const auto inv = *invert(p);
    std::array<double, 3> s{};
    for (int i = 0; i < 3; ++i) s[static_cast<std::size_t>(i)] = inv[static_cast<std::size_t>(i * 3)] * w[0] + inv[static_cast<std::size_t>(i * 3 + 1)] * w[1] + inv[static_cast<std::size_t>(i * 3 + 2)] * w[2];
    return Matrix{p[0] * s[0], p[1] * s[1], p[2] * s[2], p[3] * s[0], p[4] * s[1], p[5] * s[2], p[6] * s[0], p[7] * s[1], p[8] * s[2]};
}

/** Bradford adaptation matrix from white `from` to white `to` (XYZ, Y = 1). */
Matrix bradford(const std::array<double, 3>& from, const std::array<double, 3>& to) {
    const Matrix m{0.8951, 0.2664, -0.1614, -0.7502, 1.7135, 0.0367, 0.0389, -0.0685, 1.0296};
    const auto mi = *invert(m);
    std::array<double, 3> a{}, b{};
    for (int i = 0; i < 3; ++i) {
        for (int k = 0; k < 3; ++k) {
            a[static_cast<std::size_t>(i)] += m[static_cast<std::size_t>(i * 3 + k)] * from[static_cast<std::size_t>(k)];
            b[static_cast<std::size_t>(i)] += m[static_cast<std::size_t>(i * 3 + k)] * to[static_cast<std::size_t>(k)];
        }
    }
    const Matrix d{b[0] / a[0], 0, 0, 0, b[1] / a[1], 0, 0, 0, b[2] / a[2]};
    return multiply(mi, multiply(d, m));
}

/** Per-tile working buffers (reused by one worker across tiles). */
struct Scratch {
    std::vector<float> cfa, lpf, vh, pq, rgb[3];
    std::vector<std::uint8_t> colour;
    void resize(std::size_t n) {
        for (auto* v : {&cfa, &lpf, &vh, &pq, &rgb[0], &rgb[1], &rgb[2]}) v->assign(n, 0.0F);
        colour.assign(n, 0);
    }
};

// ── Demosaicing on one buffer (bw × bh, row stride bw) ─────────────────────────────────────────

void bilinear(Scratch& s, int bw, int bh) {
    for (int y = 1; y < bh - 1; ++y) {
        for (int x = 1; x < bw - 1; ++x) {
            const int i = y * bw + x;
            float sum[3] = {0, 0, 0};
            int count[3] = {0, 0, 0};
            for (int dy = -1; dy <= 1; ++dy)
                for (int dx = -1; dx <= 1; ++dx) {
                    const int j = i + dy * bw + dx;
                    sum[s.colour[static_cast<std::size_t>(j)]] += s.cfa[static_cast<std::size_t>(j)];
                    ++count[s.colour[static_cast<std::size_t>(j)]];
                }
            for (int c = 0; c < 3; ++c) {
                s.rgb[c][static_cast<std::size_t>(i)] = s.colour[static_cast<std::size_t>(i)] == c ? s.cfa[static_cast<std::size_t>(i)]
                                                        : count[c] > 0                             ? sum[c] / static_cast<float>(count[c])
                                                                                                   : 0.0F;
            }
        }
    }
}

inline float intp(float a, float b, float c) { return a * (b - c) + c; }

/**
 * Ratio Corrected Demosaicing (Luis Sanz Rodríguez, RCD v2.3 structure): local vertical/horizontal
 * discrimination from squared high-pass responses, a CFA low-pass for ratio-corrected green estimates,
 * gradient-weighted cardinal/diagonal colour-difference interpolation, and neighbourhood-refined
 * direction choice. Valid output shrinks by 8 px from the buffer edge.
 */
void rcd(Scratch& s, int bw, int bh) {
    const int w1 = bw, w2 = 2 * bw, w3 = 3 * bw, w4 = 4 * bw;
    const float* cfa = s.cfa.data();
    float* vh = s.vh.data();
    float* pq = s.pq.data();
    float* lpf = s.lpf.data();
    float* rgb[3] = {s.rgb[0].data(), s.rgb[1].data(), s.rgb[2].data()};
    const auto colour = [&](int i) { return s.colour[static_cast<std::size_t>(i)]; };
    const std::size_t n = static_cast<std::size_t>(bw) * bh;
    for (int c = 0; c < 3; ++c) std::fill(rgb[c], rgb[c] + n, 0.0F);
    for (std::size_t i = 0; i < n; ++i) rgb[s.colour[i]][i] = cfa[i];

    // Step 1: vertical/horizontal discrimination.
    std::vector<float> hpv(n, 0.0F), hph(n, 0.0F);
    for (int y = 3; y < bh - 3; ++y)
        for (int x = 3; x < bw - 3; ++x) {
            const int i = y * bw + x;
            const float v = (cfa[i - w3] - cfa[i - w1] - cfa[i + w1] + cfa[i + w3]) - 3.0F * (cfa[i - w2] + cfa[i + w2]) + 6.0F * cfa[i];
            const float h = (cfa[i - 3] - cfa[i - 1] - cfa[i + 1] + cfa[i + 3]) - 3.0F * (cfa[i - 2] + cfa[i + 2]) + 6.0F * cfa[i];
            hpv[static_cast<std::size_t>(i)] = v * v;
            hph[static_cast<std::size_t>(i)] = h * h;
        }
    for (int y = 4; y < bh - 4; ++y)
        for (int x = 4; x < bw - 4; ++x) {
            const int i = y * bw + x;
            const float v_stat = std::max(kEps, hpv[static_cast<std::size_t>(i - w1)] + hpv[static_cast<std::size_t>(i)] + hpv[static_cast<std::size_t>(i + w1)]);
            const float h_stat = std::max(kEps, hph[static_cast<std::size_t>(i - 1)] + hph[static_cast<std::size_t>(i)] + hph[static_cast<std::size_t>(i + 1)]);
            vh[i] = v_stat / (v_stat + h_stat);
        }

    // Step 2: CFA low-pass (all three colours mixed) at every site.
    for (int y = 1; y < bh - 1; ++y)
        for (int x = 1; x < bw - 1; ++x) {
            const int i = y * bw + x;
            lpf[i] = 0.25F * cfa[i] + 0.125F * (cfa[i - w1] + cfa[i + w1] + cfa[i - 1] + cfa[i + 1]) +
                     0.0625F * (cfa[i - w1 - 1] + cfa[i - w1 + 1] + cfa[i + w1 - 1] + cfa[i + w1 + 1]);
        }

    // Step 3: green at red/blue sites.
    for (int y = 5; y < bh - 5; ++y)
        for (int x = 5; x < bw - 5; ++x) {
            const int i = y * bw + x;
            if (colour(i) == 1) continue;
            const float cfai = cfa[i];
            const float n_grad = kEps + std::abs(cfa[i - w1] - cfa[i + w1]) + std::abs(cfai - cfa[i - w2]) + std::abs(cfa[i - w1] - cfa[i - w3]) + std::abs(cfa[i - w2] - cfa[i - w4]);
            const float s_grad = kEps + std::abs(cfa[i + w1] - cfa[i - w1]) + std::abs(cfai - cfa[i + w2]) + std::abs(cfa[i + w1] - cfa[i + w3]) + std::abs(cfa[i + w2] - cfa[i + w4]);
            const float w_grad = kEps + std::abs(cfa[i - 1] - cfa[i + 1]) + std::abs(cfai - cfa[i - 2]) + std::abs(cfa[i - 1] - cfa[i - 3]) + std::abs(cfa[i - 2] - cfa[i - 4]);
            const float e_grad = kEps + std::abs(cfa[i + 1] - cfa[i - 1]) + std::abs(cfai - cfa[i + 2]) + std::abs(cfa[i + 1] - cfa[i + 3]) + std::abs(cfa[i + 2] - cfa[i + 4]);
            const float n_est = cfa[i - w1] * (lpf[i] + lpf[i]) / (kEps + lpf[i] + lpf[i - w2]);
            const float s_est = cfa[i + w1] * (lpf[i] + lpf[i]) / (kEps + lpf[i] + lpf[i + w2]);
            const float w_est = cfa[i - 1] * (lpf[i] + lpf[i]) / (kEps + lpf[i] + lpf[i - 2]);
            const float e_est = cfa[i + 1] * (lpf[i] + lpf[i]) / (kEps + lpf[i] + lpf[i + 2]);
            const float v_est = (s_grad * n_est + n_grad * s_est) / (n_grad + s_grad);
            const float h_est = (w_grad * e_est + e_grad * w_est) / (e_grad + w_grad);
            const float central = vh[i];
            const float around = 0.25F * (vh[i - w1 - 1] + vh[i - w1 + 1] + vh[i + w1 - 1] + vh[i + w1 + 1]);
            const float disc = std::abs(0.5F - central) < std::abs(0.5F - around) ? around : central;
            rgb[1][i] = std::max(0.0F, intp(disc, h_est, v_est));
        }

    // Step 4.1: diagonal discrimination at red/blue sites.
    std::vector<float> hpp(n, 0.0F), hpq(n, 0.0F);
    for (int y = 3; y < bh - 3; ++y)
        for (int x = 3; x < bw - 3; ++x) {
            const int i = y * bw + x;
            const float p = (cfa[i - w3 - 3] - cfa[i - w1 - 1] - cfa[i + w1 + 1] + cfa[i + w3 + 3]) - 3.0F * (cfa[i - w2 - 2] + cfa[i + w2 + 2]) + 6.0F * cfa[i];
            const float q = (cfa[i - w3 + 3] - cfa[i - w1 + 1] - cfa[i + w1 - 1] + cfa[i + w3 - 3]) - 3.0F * (cfa[i - w2 + 2] + cfa[i + w2 - 2]) + 6.0F * cfa[i];
            hpp[static_cast<std::size_t>(i)] = p * p;
            hpq[static_cast<std::size_t>(i)] = q * q;
        }
    for (int y = 4; y < bh - 4; ++y)
        for (int x = 4; x < bw - 4; ++x) {
            const int i = y * bw + x;
            const float p_stat = std::max(kEps, hpp[static_cast<std::size_t>(i - w1 - 1)] + hpp[static_cast<std::size_t>(i)] + hpp[static_cast<std::size_t>(i + w1 + 1)]);
            const float q_stat = std::max(kEps, hpq[static_cast<std::size_t>(i - w1 + 1)] + hpq[static_cast<std::size_t>(i)] + hpq[static_cast<std::size_t>(i + w1 - 1)]);
            pq[i] = p_stat / (p_stat + q_stat);
        }

    // Step 4.2: red at blue sites and blue at red sites (diagonal colour differences).
    for (int y = 6; y < bh - 6; ++y)
        for (int x = 6; x < bw - 6; ++x) {
            const int i = y * bw + x;
            if (colour(i) == 1) continue;
            const int c = 2 - colour(i);
            float* ch = rgb[c];
            const float* g = rgb[1];
            const float central = pq[i];
            const float around = 0.25F * (pq[i - w1 - 1] + pq[i - w1 + 1] + pq[i + w1 - 1] + pq[i + w1 + 1]);
            const float disc = std::abs(0.5F - central) < std::abs(0.5F - around) ? around : central;
            const float nw_grad = kEps + std::abs(ch[i - w1 - 1] - ch[i + w1 + 1]) + std::abs(ch[i - w1 - 1] - ch[i - w3 - 3]) + std::abs(g[i] - g[i - w2 - 2]);
            const float ne_grad = kEps + std::abs(ch[i - w1 + 1] - ch[i + w1 - 1]) + std::abs(ch[i - w1 + 1] - ch[i - w3 + 3]) + std::abs(g[i] - g[i - w2 + 2]);
            const float sw_grad = kEps + std::abs(ch[i + w1 - 1] - ch[i - w1 + 1]) + std::abs(ch[i + w1 - 1] - ch[i + w3 - 3]) + std::abs(g[i] - g[i + w2 - 2]);
            const float se_grad = kEps + std::abs(ch[i + w1 + 1] - ch[i - w1 - 1]) + std::abs(ch[i + w1 + 1] - ch[i + w3 + 3]) + std::abs(g[i] - g[i + w2 + 2]);
            const float nw_est = ch[i - w1 - 1] - g[i - w1 - 1];
            const float ne_est = ch[i - w1 + 1] - g[i - w1 + 1];
            const float sw_est = ch[i + w1 - 1] - g[i + w1 - 1];
            const float se_est = ch[i + w1 + 1] - g[i + w1 + 1];
            const float p_est = (nw_grad * se_est + se_grad * nw_est) / (nw_grad + se_grad);
            const float q_est = (ne_grad * sw_est + sw_grad * ne_est) / (ne_grad + sw_grad);
            ch[i] = std::max(0.0F, g[i] + intp(disc, q_est, p_est));
        }

    // Step 4.3: red and blue at green sites (cardinal colour differences).
    for (int y = 7; y < bh - 7; ++y)
        for (int x = 7; x < bw - 7; ++x) {
            const int i = y * bw + x;
            if (colour(i) != 1) continue;
            const float central = vh[i];
            const float around = 0.25F * (vh[i - w1 - 1] + vh[i - w1 + 1] + vh[i + w1 - 1] + vh[i + w1 + 1]);
            const float disc = std::abs(0.5F - central) < std::abs(0.5F - around) ? around : central;
            const float* g = rgb[1];
            for (const int c : {0, 2}) {
                float* ch = rgb[c];
                const float n_grad = kEps + std::abs(g[i] - g[i - w2]) + std::abs(ch[i - w1] - ch[i + w1]) + std::abs(ch[i - w1] - ch[i - w3]);
                const float s_grad = kEps + std::abs(g[i] - g[i + w2]) + std::abs(ch[i + w1] - ch[i - w1]) + std::abs(ch[i + w1] - ch[i + w3]);
                const float w_grad = kEps + std::abs(g[i] - g[i - 2]) + std::abs(ch[i - 1] - ch[i + 1]) + std::abs(ch[i - 1] - ch[i - 3]);
                const float e_grad = kEps + std::abs(g[i] - g[i + 2]) + std::abs(ch[i + 1] - ch[i - 1]) + std::abs(ch[i + 1] - ch[i + 3]);
                const float n_est = ch[i - w1] - g[i - w1];
                const float s_est = ch[i + w1] - g[i + w1];
                const float w_est = ch[i - 1] - g[i - 1];
                const float e_est = ch[i + 1] - g[i + 1];
                const float v_est = (n_grad * s_est + s_grad * n_est) / (n_grad + s_grad);
                const float h_est = (e_grad * w_est + w_grad * e_est) / (e_grad + w_grad);
                ch[i] = std::max(0.0F, g[i] + intp(disc, h_est, v_est));
            }
        }
}

/**
 * Fast X-Trans: green everywhere from the 3×3 greens (every X-Trans site has green neighbours), then red
 * and blue by distance-weighted colour differences (C − G) from the same-colour sites within 5×5.
 */
void xtransFast(Scratch& s, int bw, int bh) {
    const std::size_t n = static_cast<std::size_t>(bw) * bh;
    const float* cfa = s.cfa.data();
    float* g = s.rgb[1].data();
    for (std::size_t i = 0; i < n; ++i) g[i] = s.colour[i] == 1 ? cfa[i] : 0.0F;
    for (int y = 1; y < bh - 1; ++y)
        for (int x = 1; x < bw - 1; ++x) {
            const int i = y * bw + x;
            if (s.colour[static_cast<std::size_t>(i)] == 1) continue;
            float sum = 0, weight = 0;
            for (int dy = -1; dy <= 1; ++dy)
                for (int dx = -1; dx <= 1; ++dx) {
                    const int j = i + dy * bw + dx;
                    if (s.colour[static_cast<std::size_t>(j)] != 1) continue;
                    const float wgt = (dx == 0 || dy == 0) ? 2.0F : 1.0F;  // edge-adjacent greens are closer
                    sum += wgt * cfa[j];
                    weight += wgt;
                }
            g[i] = weight > 0 ? sum / weight : cfa[i];
        }
    for (const int c : {0, 2}) {
        float* ch = s.rgb[c].data();
        for (int y = 3; y < bh - 3; ++y)
            for (int x = 3; x < bw - 3; ++x) {
                const int i = y * bw + x;
                if (s.colour[static_cast<std::size_t>(i)] == c) {
                    ch[i] = cfa[i];
                    continue;
                }
                float sum = 0, weight = 0;
                for (int radius = 2; radius <= 3 && weight == 0; ++radius)
                    for (int dy = -radius; dy <= radius; ++dy)
                        for (int dx = -radius; dx <= radius; ++dx) {
                            const int j = i + dy * bw + dx;
                            if (s.colour[static_cast<std::size_t>(j)] != c) continue;
                            const float wgt = 1.0F / static_cast<float>(1 + dx * dx + dy * dy);
                            sum += wgt * (cfa[j] - g[j]);
                            weight += wgt;
                        }
                ch[i] = std::max(0.0F, g[i] + (weight > 0 ? sum / weight : 0.0F));
            }
    }
}

// ── Highlights (dcraw's blend in an opponent space) ─────────────────────────────────────────────

void blendHighlights(float* rgb, float clip) {
    if (rgb[0] <= clip && rgb[1] <= clip && rgb[2] <= clip) return;
    static constexpr float kTrans[3][3] = {{1, 1, 1}, {1.7320508F, -1.7320508F, 0}, {-1, -1, 2}};
    static constexpr float kInverse[3][3] = {{1, 0.8660254F, -0.5F}, {1, -0.8660254F, -0.5F}, {1, 0, 1}};
    float cam[2][3], lab[2][3], sum[2];
    for (int c = 0; c < 3; ++c) {
        cam[0][c] = rgb[c];
        cam[1][c] = std::min(rgb[c], clip);
    }
    for (int i = 0; i < 2; ++i) {
        for (int c = 0; c < 3; ++c) {
            lab[i][c] = 0;
            for (int j = 0; j < 3; ++j) lab[i][c] += kTrans[c][j] * cam[i][j];
        }
        sum[i] = lab[i][1] * lab[i][1] + lab[i][2] * lab[i][2];
    }
    // Keep the unclipped luminance and hue, but only as much chroma as the clipped colour has.
    const float ratio = sum[0] > 0 ? std::sqrt(sum[1] / sum[0]) : 0.0F;
    lab[0][1] *= ratio;
    lab[0][2] *= ratio;
    for (int c = 0; c < 3; ++c) {
        float v = 0;
        for (int j = 0; j < 3; ++j) v += kInverse[c][j] * lab[0][j];
        rgb[c] = std::max(0.0F, v / 3.0F);
    }
}

}  // namespace

CfaPattern CfaPattern::bayer(const char* layout) {
    CfaPattern p;
    const char* selected = layout;
    if (selected == nullptr || selected[0] == '\0') selected = "RGGB";
    // Only accept the four 2×2 Bayer layouts. In particular, do not read four bytes
    // from an arbitrary short C string or silently interpret unknown letters as green.
    bool supported = false;
    if (std::strlen(selected) == 4) {
        for (const char* candidate : {"RGGB", "BGGR", "GRBG", "GBRG"}) {
            if (std::strncmp(selected, candidate, 4) == 0) {
                supported = true;
                break;
            }
        }
    }
    if (!supported) {
        p.colours.fill(255);
        return p;
    }
    for (int i = 0; i < 4; ++i) {
        const char ch = selected[i];
        p.colours[static_cast<std::size_t>(i)] = ch == 'R' ? 0 : ch == 'B' ? 2 : 1;
    }
    return p;
}

CfaPattern CfaPattern::xtrans() {
    CfaPattern p;
    p.width = 6;
    p.height = 6;
    static constexpr std::uint8_t kLayout[36] = {1, 1, 0, 1, 1, 2, 1, 1, 2, 1, 1, 0, 2, 0, 1, 0, 2, 1,
                                                 1, 1, 2, 1, 1, 0, 1, 1, 0, 1, 1, 2, 0, 2, 1, 2, 0, 1};
    std::copy(std::begin(kLayout), std::end(kLayout), p.colours.begin());
    return p;
}

std::optional<std::array<double, 9>> cameraToOutput(const std::array<double, 9>& xyz_to_camera, OutputPrimaries output) {
    const std::array<double, 3> d65{0.95047, 1.0, 1.08883}, d50{0.96422, 1.0, 0.82521};
    Matrix xyz_from_out;
    switch (output) {
        case OutputPrimaries::LinearSRGB: xyz_from_out = rgbToXyz({0.64, 0.33, 0.30, 0.60, 0.15, 0.06, 0.3127, 0.3290}); break;
        case OutputPrimaries::LinearP3: xyz_from_out = rgbToXyz({0.680, 0.320, 0.265, 0.690, 0.150, 0.060, 0.3127, 0.3290}); break;
        case OutputPrimaries::LinearRec2020: xyz_from_out = rgbToXyz({0.708, 0.292, 0.170, 0.797, 0.131, 0.046, 0.3127, 0.3290}); break;
        case OutputPrimaries::LinearProPhoto:
            // ROMM is defined at D50; the camera matrix is D65, so adapt ProPhoto's primaries to D65 first.
            xyz_from_out = multiply(bradford(d50, d65), rgbToXyz({0.7347, 0.2653, 0.1596, 0.8404, 0.0366, 0.0001, 0.3457, 0.3585}));
            break;
    }
    // camera ← output, rows normalised so output white → white-balanced camera (1, 1, 1).
    Matrix cam_from_out = multiply(xyz_to_camera, xyz_from_out);
    for (int r = 0; r < 3; ++r) {
        const double sum = cam_from_out[static_cast<std::size_t>(r * 3)] + cam_from_out[static_cast<std::size_t>(r * 3 + 1)] + cam_from_out[static_cast<std::size_t>(r * 3 + 2)];
        if (!std::isfinite(sum) || std::abs(sum) < 1e-12) return std::nullopt;
        for (int c = 0; c < 3; ++c) cam_from_out[static_cast<std::size_t>(r * 3 + c)] /= sum;
    }
    return invert(cam_from_out);
}

std::optional<TileStore> develop(const CfaImage& image, const DevelopSpec& spec, std::string& error) {
    const auto w = image.width, h = image.height;
    if (w < 16U || h < 16U || image.photosites.size() != static_cast<std::size_t>(w) * h) {
        error = "raw image must be at least 16×16 with one photosite per pixel";
        return std::nullopt;
    }
    const auto& pattern = image.pattern;
    if (!((pattern.width == 2 && pattern.height == 2) || (pattern.width == 6 && pattern.height == 6))) {
        error = "CFA pattern must be 2×2 (Bayer) or 6×6 (X-Trans)";
        return std::nullopt;
    }
    bool has[3] = {false, false, false};
    for (int i = 0; i < pattern.width * pattern.height; ++i) {
        if (pattern.colours[static_cast<std::size_t>(i)] > 2) {
            error = "CFA colours must be 0 (R), 1 (G) or 2 (B)";
            return std::nullopt;
        }
        has[pattern.colours[static_cast<std::size_t>(i)]] = true;
    }
    if (!has[0] || !has[1] || !has[2]) {
        error = "CFA pattern must contain red, green and blue";
        return std::nullopt;
    }
    for (int c = 0; c < 3; ++c) {
        if (!std::isfinite(image.black[static_cast<std::size_t>(c)]) || image.black[static_cast<std::size_t>(c)] < 0.0F ||
            !(image.white > image.black[static_cast<std::size_t>(c)] + 1.0F) || !std::isfinite(spec.white_balance[static_cast<std::size_t>(c)]) ||
            spec.white_balance[static_cast<std::size_t>(c)] <= 0.0F || spec.white_balance[static_cast<std::size_t>(c)] > 64.0F) {
            error = "black/white levels and white balance must be finite with white > black and multipliers in (0, 64]";
            return std::nullopt;
        }
    }
    if (!std::isfinite(image.white) || std::any_of(spec.camera_to_output.begin(), spec.camera_to_output.end(), [](double v) { return !std::isfinite(v); })) {
        error = "levels and colour matrix must be finite";
        return std::nullopt;
    }
    if (spec.output_format != PixelFormat::RGBA16 && spec.output_format != PixelFormat::RGBA32F) {
        error = "raw output must be RGBA16 or RGBA32F";
        return std::nullopt;
    }
    if ((spec.method != Demosaic::Bilinear && spec.method != Demosaic::RCD && spec.method != Demosaic::XTransFast &&
         spec.method != Demosaic::EdgeDirected && spec.method != Demosaic::XTransDirectional) ||
        (spec.highlights != Highlights::Clip && spec.highlights != Highlights::Blend)) {
        error = "unsupported demosaic or highlight mode";
        return std::nullopt;
    }
    if (pattern.isBayer() && spec.method == Demosaic::XTransDirectional) {
        error = "X-Trans directional demosaicing requires a 6×6 X-Trans pattern";
        return std::nullopt;
    }
    if (!pattern.isBayer() && spec.method == Demosaic::EdgeDirected) {
        error = "EdgeDirected Bayer demosaicing requires a Bayer pattern";
        return std::nullopt;
    }
    const Demosaic method = pattern.isBayer()
                                ? (spec.method == Demosaic::XTransFast ? Demosaic::RCD : spec.method)
                                : (spec.method == Demosaic::XTransFast ? Demosaic::XTransFast : Demosaic::XTransDirectional);

    // Normalise multipliers so the weakest channel is 1: each channel then saturates at wb[c] / min.
    const float wb_min = std::min({spec.white_balance[0], spec.white_balance[1], spec.white_balance[2]});
    std::array<float, 3> scale{};
    for (int c = 0; c < 3; ++c) {
        scale[static_cast<std::size_t>(c)] = spec.white_balance[static_cast<std::size_t>(c)] / wb_min /
                                             (image.white - image.black[static_cast<std::size_t>(c)]);
    }
    std::array<float, 9> matrix{};
    for (int i = 0; i < 9; ++i) matrix[static_cast<std::size_t>(i)] = static_cast<float>(spec.camera_to_output[static_cast<std::size_t>(i)]);

    TileStore out(spec.output_format, w, h);
    const std::uint32_t columns = 1U + (w - 1U) / kTileEdge, rows = 1U + (h - 1U) / kTileEdge;
    const std::size_t tiles = static_cast<std::size_t>(columns) * rows;
    const unsigned workers = parallel::workerCount(spec.worker_threads);
    std::vector<Scratch> scratch(workers);
    std::atomic<std::size_t> done{0};
    std::atomic<bool> cancelled{false}, failed{false};
    const int bw = static_cast<int>(kTileEdge) + 2 * kBorder;
    const std::size_t bn = static_cast<std::size_t>(bw) * bw;
    const auto wrap = [](std::int64_t v, std::int64_t size, std::int64_t period) {
        // Out-of-image sites borrow the nearest in-image site of the same CFA phase.
        while (v < 0) v += period;
        while (v >= size) v -= period;
        return v;
    };

    parallel::parallelFor(tiles, workers, [&](std::size_t index, std::size_t worker) {
        if (cancelled.load()) return;
        auto& s = scratch[worker];
        if (s.cfa.size() != bn) s.resize(bn);
        const auto tx = static_cast<std::int64_t>(index % columns), ty = static_cast<std::int64_t>(index / columns);
        const std::int64_t ox = tx * kTileEdge - kBorder, oy = ty * kTileEdge - kBorder;
        for (int y = 0; y < bw; ++y) {
            const std::int64_t gy = wrap(oy + y, h, pattern.height);
            const std::uint16_t* row = image.photosites.data() + static_cast<std::size_t>(gy) * w;
            for (int x = 0; x < bw; ++x) {
                const std::int64_t gx = wrap(ox + x, w, pattern.width);
                const auto c = pattern.at(gx, gy);
                const auto i = static_cast<std::size_t>(y * bw + x);
                s.colour[i] = c;
                s.cfa[i] = std::max(0.0F, (static_cast<float>(row[gx]) - image.black[c]) * scale[c]);
            }
        }
        switch (method) {
            case Demosaic::Bilinear: bilinear(s, bw, bw); break;
            case Demosaic::RCD: rcd(s, bw, bw); break;
            case Demosaic::XTransFast: xtransFast(s, bw, bw); break;
            case Demosaic::EdgeDirected: detail::amaze(std::span<const float>(s.cfa), std::span<const std::uint8_t>(s.colour),
                                                       std::span<float>(s.rgb[0]), std::span<float>(s.rgb[1]),
                                                       std::span<float>(s.rgb[2]), bw, bw); break;
            case Demosaic::XTransDirectional:
                detail::xtransDirectional(std::span<const float>(s.cfa), std::span<const std::uint8_t>(s.colour),
                                          std::span<float>(s.rgb[0]), std::span<float>(s.rgb[1]),
                                          std::span<float>(s.rgb[2]), bw, bw);
                break;
        }
        TileBuffer tile(out.tile_byte_size(), 0);
        const auto bpp = out.bytes_per_pixel();
        const std::int64_t x0 = tx * kTileEdge, y0 = ty * kTileEdge;
        for (int y = 0; y < static_cast<int>(kTileEdge) && y0 + y < h; ++y) {
            for (int x = 0; x < static_cast<int>(kTileEdge) && x0 + x < w; ++x) {
                const auto i = static_cast<std::size_t>((y + kBorder) * bw + x + kBorder);
                float px[4] = {s.rgb[0][i], s.rgb[1][i], s.rgb[2][i], 1.0F};
                if (spec.highlights == Highlights::Clip) {
                    for (int c = 0; c < 3; ++c) px[c] = std::min(px[c], 1.0F);
                } else {
                    blendHighlights(px, 1.0F);
                }
                const float r = px[0], g = px[1], b = px[2];
                for (int c = 0; c < 3; ++c) px[c] = matrix[static_cast<std::size_t>(c * 3)] * r + matrix[static_cast<std::size_t>(c * 3 + 1)] * g + matrix[static_cast<std::size_t>(c * 3 + 2)] * b;
                paint::detail::writePixel(spec.output_format, tile.data() + (static_cast<std::size_t>(y) * kTileEdge + static_cast<std::size_t>(x)) * bpp, px);
            }
        }
        // TileStore writes are internally serialised, so workers store their tiles directly.
        if (!out.writeTile({static_cast<std::int32_t>(tx), static_cast<std::int32_t>(ty)}, tile)) {
            failed.store(true);
            cancelled.store(true);
            return;
        }
        const auto finished = done.fetch_add(1) + 1;
        if (worker == 0 && spec.progress && !spec.progress(static_cast<float>(finished) / static_cast<float>(tiles))) cancelled.store(true);
    });
    if (cancelled.load()) {
        error = failed.load() ? "could not store developed pixels" : "cancelled";
        return std::nullopt;
    }
    if (spec.progress) spec.progress(1.0F);
    return out;
}

}  // namespace croptop::raw
