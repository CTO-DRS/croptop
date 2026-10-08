// Panorama stitching.
#include "panorama.h"

#include <algorithm>
#include <atomic>
#include <bit>
#include <cmath>
#include <complex>
#include <limits>
#include <numeric>
#include <queue>

#include "filters_internal.h"
#include "parallel_util.h"
#include "pixel_plane.h"

namespace croptop::panorama {
namespace {

using Mat3 = std::array<double, 9>;
using Vec2 = std::array<double, 2>;
using filters::detail::Pixel;

constexpr double kPi = 3.14159265358979323846;
constexpr int kWorkingSide = 1200;
constexpr int kBorder = 24;           // keypoints keep clear of the edge (rotated BRIEF patch)
constexpr std::size_t kMaxImages = 32;
constexpr double kMaxCanvasPixels = 200e6;
constexpr double kMaxCanvasSide = 32768;

constexpr Mat3 kIdentity{1, 0, 0, 0, 1, 0, 0, 0, 1};

Mat3 multiply(const Mat3& a, const Mat3& b) {
    Mat3 m{};
    for (int r = 0; r < 3; ++r)
        for (int c = 0; c < 3; ++c)
            for (int k = 0; k < 3; ++k) m[static_cast<std::size_t>(r * 3 + c)] += a[static_cast<std::size_t>(r * 3 + k)] * b[static_cast<std::size_t>(k * 3 + c)];
    return m;
}

std::optional<Mat3> invert(const Mat3& m) {
    const double a = m[0], b = m[1], c = m[2], d = m[3], e = m[4], f = m[5], g = m[6], h = m[7], i = m[8];
    const double A = e * i - f * h, B = -(d * i - f * g), C = d * h - e * g;
    const double det = a * A + b * B + c * C;
    if (!std::isfinite(det) || std::abs(det) < 1e-300) return std::nullopt;
    const double s = 1.0 / det;
    return Mat3{A * s, -(b * i - c * h) * s, (b * f - c * e) * s, B * s, (a * i - c * g) * s, -(a * f - c * d) * s, C * s, -(a * h - b * g) * s, (a * e - b * d) * s};
}

/** Projects (x, y); returns the homogeneous w (the result is meaningless when w ≤ 0). */
double project(const Mat3& m, double x, double y, double& ox, double& oy) {
    const double w = m[6] * x + m[7] * y + m[8];
    ox = (m[0] * x + m[1] * y + m[2]) / w;
    oy = (m[3] * x + m[4] * y + m[5]) / w;
    return w;
}

std::uint64_t splitmix(std::uint64_t& state) {
    std::uint64_t z = (state += 0x9E3779B97F4A7C15ULL);
    z = (z ^ (z >> 30)) * 0xBF58476D1CE4E5B9ULL;
    z = (z ^ (z >> 27)) * 0x94D049BB133111EBULL;
    return z ^ (z >> 31);
}

float linearToSrgb(float v) { return v <= 0.0031308F ? v * 12.92F : 1.055F * std::pow(v, 1.0F / 2.4F) - 0.055F; }

// ---- Working gray ---------------------------------------------------------------------------------------

struct Gray final {
    int w = 0, h = 0, factor = 1;
    double scale_x = 1, scale_y = 1;  // full-resolution pixels per pixel of this image
    std::vector<float> v;  // 0…255
    float at(int x, int y) const { return v[static_cast<std::size_t>(y) * w + x]; }
};

/** Box-reduced luminance (premultiplied over black) with a power-of-two factor ≤ 256. */
Gray workingGray(const TileStore& image) {
    Gray g;
    const int W = static_cast<int>(image.width()), H = static_cast<int>(image.height());
    while (std::max(W, H) / g.factor > kWorkingSide && g.factor < 256) g.factor *= 2;
    g.w = (W + g.factor - 1) / g.factor;
    g.h = (H + g.factor - 1) / g.factor;
    g.scale_x = g.scale_y = g.factor;
    g.v.assign(static_cast<std::size_t>(g.w) * g.h, 0.0F);
    const bool linear = image.format() == PixelFormat::RGBA32F;
    const auto coordinates = image.tileCoordinates();
    const auto bpp = image.bytes_per_pixel();
    // The factor divides the tile edge, so each working pixel belongs to one tile: no races.
    parallel::parallelFor(coordinates.size(), parallel::workerCount(0), [&](std::size_t index, std::size_t) {
        const auto c = coordinates[index];
        const auto tile = image.readTile(c);
        if (tile == nullptr) return;
        for (int ly = 0; ly < static_cast<int>(kTileEdge); ++ly)
            for (int lx = 0; lx < static_cast<int>(kTileEdge); ++lx) {
                const int x = c.x * static_cast<int>(kTileEdge) + lx, y = c.y * static_cast<int>(kTileEdge) + ly;
                if (x >= W || y >= H) continue;
                float px[4];
                paint::detail::readPixel(image.format(), tile->data() + (static_cast<std::size_t>(ly) * kTileEdge + static_cast<std::size_t>(lx)) * bpp, px);
                if (linear)
                    for (int k = 0; k < 3; ++k) px[k] = linearToSrgb(paint::detail::unitClamp(px[k]));
                const float a = paint::detail::unitClamp(px[3]);
                const float l = (0.299F * paint::detail::unitClamp(px[0]) + 0.587F * paint::detail::unitClamp(px[1]) + 0.114F * paint::detail::unitClamp(px[2])) * a;
                g.v[static_cast<std::size_t>(y / g.factor) * g.w + static_cast<std::size_t>(x / g.factor)] += l * 255.0F;
            }
    });
    for (int y = 0; y < g.h; ++y)
        for (int x = 0; x < g.w; ++x) {
            const int bw = std::min(g.factor, W - x * g.factor), bh = std::min(g.factor, H - y * g.factor);
            g.v[static_cast<std::size_t>(y) * g.w + x] /= static_cast<float>(bw * bh);
        }
    return g;
}

std::vector<float> gaussianBlur(const Gray& g, float sigma) {
    const int r = static_cast<int>(std::ceil(3.0F * sigma));
    std::vector<float> k(static_cast<std::size_t>(2 * r + 1));
    float sum = 0;
    for (int i = -r; i <= r; ++i) sum += k[static_cast<std::size_t>(i + r)] = std::exp(-0.5F * static_cast<float>(i * i) / (sigma * sigma));
    for (auto& v : k) v /= sum;
    std::vector<float> tmp(g.v.size()), out(g.v.size());
    for (int y = 0; y < g.h; ++y)
        for (int x = 0; x < g.w; ++x) {
            float acc = 0;
            for (int i = -r; i <= r; ++i) acc += k[static_cast<std::size_t>(i + r)] * g.at(std::clamp(x + i, 0, g.w - 1), y);
            tmp[static_cast<std::size_t>(y) * g.w + x] = acc;
        }
    for (int y = 0; y < g.h; ++y)
        for (int x = 0; x < g.w; ++x) {
            float acc = 0;
            for (int i = -r; i <= r; ++i) acc += k[static_cast<std::size_t>(i + r)] * tmp[static_cast<std::size_t>(std::clamp(y + i, 0, g.h - 1)) * g.w + x];
            out[static_cast<std::size_t>(y) * g.w + x] = acc;
        }
    return out;
}

// ---- ORB-style features ------------------------------------------------------------------------------

constexpr std::array<std::array<int, 2>, 16> kCircle{{{0, -3}, {1, -3}, {2, -2}, {3, -1}, {3, 0}, {3, 1}, {2, 2}, {1, 3}, {0, 3}, {-1, 3}, {-2, 2}, {-3, 1}, {-3, 0}, {-3, -1}, {-2, -2}, {-1, -3}}};

bool nineContiguous(std::uint32_t mask) {
    std::uint32_t m = mask | (mask << 16U), run = m;
    for (int k = 1; k < 9; ++k) run &= m >> static_cast<unsigned>(k);
    return run != 0;
}

/** 256 BRIEF test pairs (x1, y1, x2, y2) from an isotropic Gaussian (σ = 31/5), within ±13 px. */
const std::array<std::array<std::int8_t, 4>, 256>& briefPattern() {
    static const auto pattern = [] {
        std::array<std::array<std::int8_t, 4>, 256> p{};
        std::uint64_t state = 0x0DDB1A5E5BADDEEDULL;
        const auto gauss = [&] {
            const double u1 = (static_cast<double>(splitmix(state) >> 11) + 1.0) / 9007199254740993.0;
            const double u2 = static_cast<double>(splitmix(state) >> 11) / 9007199254740992.0;
            return std::sqrt(-2.0 * std::log(u1)) * std::cos(2.0 * kPi * u2) * (31.0 / 5.0);
        };
        for (auto& pair : p)
            for (auto& v : pair) v = static_cast<std::int8_t>(std::clamp(static_cast<int>(std::lround(gauss())), -13, 13));
        return p;
    }();
    return pattern;
}

detail::Features detectOn(const Gray& g, std::size_t max_points) {
    detail::Features out;
    if (g.w <= 2 * kBorder || g.h <= 2 * kBorder) return out;
    constexpr float kThreshold = 12.0F;
    std::vector<float> score(g.v.size(), 0.0F);
    for (int y = kBorder; y < g.h - kBorder; ++y)
        for (int x = kBorder; x < g.w - kBorder; ++x) {
            const float p = g.at(x, y);
            std::uint32_t brighter = 0, darker = 0;
            for (std::size_t k = 0; k < 16; ++k) {
                const float q = g.at(x + kCircle[k][0], y + kCircle[k][1]);
                if (q > p + kThreshold) brighter |= 1U << k;
                if (q < p - kThreshold) darker |= 1U << k;
            }
            if (std::popcount(brighter) < 9 && std::popcount(darker) < 9) continue;
            if (!nineContiguous(brighter) && !nineContiguous(darker)) continue;
            // Harris response over a 7×7 window.
            double xx = 0, yy = 0, xy = 0;
            for (int dy = -3; dy <= 3; ++dy)
                for (int dx = -3; dx <= 3; ++dx) {
                    const double ix = (g.at(x + dx + 1, y + dy) - g.at(x + dx - 1, y + dy)) * 0.5, iy = (g.at(x + dx, y + dy + 1) - g.at(x + dx, y + dy - 1)) * 0.5;
                    xx += ix * ix;
                    yy += iy * iy;
                    xy += ix * iy;
                }
            const double r = xx * yy - xy * xy - 0.04 * (xx + yy) * (xx + yy);
            if (r > 0) score[static_cast<std::size_t>(y) * g.w + x] = static_cast<float>(r);
        }
    struct Candidate {
        float score;
        int x, y;
    };
    // 3×3 non-maximum suppression, then the best per grid cell (spread), then the best overall.
    constexpr int kCells = 8;
    std::vector<std::vector<Candidate>> cells(kCells * kCells);
    for (int y = kBorder; y < g.h - kBorder; ++y)
        for (int x = kBorder; x < g.w - kBorder; ++x) {
            const float s = score[static_cast<std::size_t>(y) * g.w + x];
            if (s <= 0) continue;
            bool peak = true;
            for (int dy = -1; dy <= 1 && peak; ++dy)
                for (int dx = -1; dx <= 1; ++dx) {
                    if (dx == 0 && dy == 0) continue;
                    const float n = score[static_cast<std::size_t>(y + dy) * g.w + static_cast<std::size_t>(x + dx)];
                    if (n > s || (n == s && (dy < 0 || (dy == 0 && dx < 0)))) {
                        peak = false;
                        break;
                    }
                }
            if (peak) cells[static_cast<std::size_t>(std::min(kCells - 1, y * kCells / g.h) * kCells + std::min(kCells - 1, x * kCells / g.w))].push_back({s, x, y});
        }
    const std::size_t per_cell = (max_points * 3 / 2 + kCells * kCells - 1) / (kCells * kCells);
    std::vector<Candidate> kept;
    for (auto& cell : cells) {
        std::sort(cell.begin(), cell.end(), [](const Candidate& a, const Candidate& b) { return a.score > b.score || (a.score == b.score && (a.y < b.y || (a.y == b.y && a.x < b.x))); });
        kept.insert(kept.end(), cell.begin(), cell.begin() + static_cast<std::ptrdiff_t>(std::min(per_cell, cell.size())));
    }
    std::sort(kept.begin(), kept.end(), [](const Candidate& a, const Candidate& b) { return a.score > b.score || (a.score == b.score && (a.y < b.y || (a.y == b.y && a.x < b.x))); });
    if (kept.size() > max_points) kept.resize(max_points);

    const auto smooth = gaussianBlur(g, 2.0F);
    const auto& pattern = briefPattern();
    for (const auto& c : kept) {
        // Orientation from the intensity centroid within radius 15.
        double m10 = 0, m01 = 0;
        for (int dy = -15; dy <= 15; ++dy)
            for (int dx = -15; dx <= 15; ++dx) {
                if (dx * dx + dy * dy > 225) continue;
                const int sx = std::clamp(c.x + dx, 0, g.w - 1), sy = std::clamp(c.y + dy, 0, g.h - 1);
                const double v = g.at(sx, sy);
                m10 += dx * v;
                m01 += dy * v;
            }
        const double angle = std::atan2(m01, m10), cs = std::cos(angle), sn = std::sin(angle);
        std::array<std::uint8_t, 32> d{};
        for (std::size_t bit = 0; bit < 256; ++bit) {
            const auto& t = pattern[bit];
            const auto rot = [&](int px, int py) {
                const int rx = static_cast<int>(std::lround(cs * px - sn * py)), ry = static_cast<int>(std::lround(sn * px + cs * py));
                return smooth[static_cast<std::size_t>(std::clamp(c.y + ry, 0, g.h - 1)) * g.w + static_cast<std::size_t>(std::clamp(c.x + rx, 0, g.w - 1))];
            };
            if (rot(t[0], t[1]) < rot(t[2], t[3])) d[bit / 8] |= static_cast<std::uint8_t>(1U << (bit % 8));
        }
        out.points.push_back({static_cast<float>((c.x + 0.5) * g.scale_x), static_cast<float>((c.y + 0.5) * g.scale_y)});
        out.descriptors.push_back(d);
    }
    return out;
}

/** The next pyramid level: ≈ 1/1.3 the size (lightly blurred, then bilinear). */
Gray shrink(const Gray& g) {
    Gray blurred = g;
    blurred.v = gaussianBlur(g, 0.6F);
    Gray next;
    next.factor = g.factor;
    next.w = std::max(1, static_cast<int>(g.w / 1.3));
    next.h = std::max(1, static_cast<int>(g.h / 1.3));
    const double rx = static_cast<double>(g.w) / next.w, ry = static_cast<double>(g.h) / next.h;
    next.scale_x = g.scale_x * rx;
    next.scale_y = g.scale_y * ry;
    next.v.resize(static_cast<std::size_t>(next.w) * next.h);
    for (int y = 0; y < next.h; ++y)
        for (int x = 0; x < next.w; ++x) {
            const double sx = std::clamp((x + 0.5) * rx - 0.5, 0.0, g.w - 1.0), sy = std::clamp((y + 0.5) * ry - 0.5, 0.0, g.h - 1.0);
            const int ix = std::min(static_cast<int>(sx), g.w - 2 < 0 ? 0 : g.w - 2), iy = std::min(static_cast<int>(sy), g.h - 2 < 0 ? 0 : g.h - 2);
            const int jx = std::min(ix + 1, g.w - 1), jy = std::min(iy + 1, g.h - 1);
            const float fx = static_cast<float>(sx - ix), fy = static_cast<float>(sy - iy);
            next.v[static_cast<std::size_t>(y) * next.w + x] = (blurred.at(ix, iy) * (1 - fx) + blurred.at(jx, iy) * fx) * (1 - fy) + (blurred.at(ix, jy) * (1 - fx) + blurred.at(jx, jy) * fx) * fy;
        }
    return next;
}

/** Features over a 4-level scale pyramid (×1.3 per level); the point budget is shared by level area. */
detail::Features detectPyramid(const Gray& g, std::size_t max_points) {
    constexpr int kLevels = 4;
    std::vector<Gray> levels{g};
    for (int k = 1; k < kLevels; ++k) levels.push_back(shrink(levels.back()));
    double total = 0;
    for (const auto& l : levels) total += static_cast<double>(l.w) * l.h;
    detail::Features out;
    for (const auto& l : levels) {
        const auto budget = static_cast<std::size_t>(std::ceil(static_cast<double>(max_points) * l.w * l.h / total));
        auto f = detectOn(l, budget);
        out.points.insert(out.points.end(), f.points.begin(), f.points.end());
        out.descriptors.insert(out.descriptors.end(), f.descriptors.begin(), f.descriptors.end());
    }
    return out;
}

int hamming(const std::array<std::uint8_t, 32>& a, const std::array<std::uint8_t, 32>& b) {
    int d = 0;
    for (std::size_t i = 0; i < 32; i += 8) {
        std::uint64_t x = 0, y = 0;
        for (std::size_t k = 0; k < 8; ++k) {
            x |= static_cast<std::uint64_t>(a[i + k]) << (8 * k);
            y |= static_cast<std::uint64_t>(b[i + k]) << (8 * k);
        }
        d += std::popcount(x ^ y);
    }
    return d;
}

std::vector<std::pair<std::uint32_t, std::uint32_t>> match(const detail::Features& a, const detail::Features& b) {
    const auto best = [](const detail::Features& from, const detail::Features& to, std::vector<int>& index, std::vector<int>& distance, std::vector<int>& second) {
        index.assign(from.descriptors.size(), -1);
        distance.assign(from.descriptors.size(), 1 << 20);
        second.assign(from.descriptors.size(), 1 << 20);
        for (std::size_t i = 0; i < from.descriptors.size(); ++i)
            for (std::size_t j = 0; j < to.descriptors.size(); ++j) {
                const int d = hamming(from.descriptors[i], to.descriptors[j]);
                if (d < distance[i]) {
                    second[i] = distance[i];
                    distance[i] = d;
                    index[i] = static_cast<int>(j);
                } else if (d < second[i]) {
                    second[i] = d;
                }
            }
    };
    std::vector<int> ai, ad, as, bi, bd, bs;
    best(a, b, ai, ad, as);
    best(b, a, bi, bd, bs);
    std::vector<std::pair<std::uint32_t, std::uint32_t>> matches;
    for (std::size_t i = 0; i < ai.size(); ++i) {
        if (ai[i] < 0 || ad[i] > 80 || static_cast<double>(ad[i]) >= 0.8 * as[i]) continue;
        if (bi[static_cast<std::size_t>(ai[i])] != static_cast<int>(i)) continue;
        matches.emplace_back(static_cast<std::uint32_t>(i), static_cast<std::uint32_t>(ai[i]));
    }
    return matches;
}

// ---- Models and RANSAC --------------------------------------------------------------------------------

enum class Model : std::uint8_t { Homography, Similarity, Translation };

bool solve8(std::array<std::array<double, 9>, 8>& a) {  // augmented 8×9, partial pivoting
    for (int col = 0; col < 8; ++col) {
        int pivot = col;
        for (int r = col + 1; r < 8; ++r)
            if (std::abs(a[static_cast<std::size_t>(r)][static_cast<std::size_t>(col)]) > std::abs(a[static_cast<std::size_t>(pivot)][static_cast<std::size_t>(col)])) pivot = r;
        if (std::abs(a[static_cast<std::size_t>(pivot)][static_cast<std::size_t>(col)]) < 1e-12) return false;
        std::swap(a[static_cast<std::size_t>(col)], a[static_cast<std::size_t>(pivot)]);
        for (int r = 0; r < 8; ++r) {
            if (r == col) continue;
            const double f = a[static_cast<std::size_t>(r)][static_cast<std::size_t>(col)] / a[static_cast<std::size_t>(col)][static_cast<std::size_t>(col)];
            if (f == 0) continue;
            for (int c = col; c < 9; ++c) a[static_cast<std::size_t>(r)][static_cast<std::size_t>(c)] -= f * a[static_cast<std::size_t>(col)][static_cast<std::size_t>(c)];
        }
    }
    for (int r = 0; r < 8; ++r) a[static_cast<std::size_t>(r)][8] /= a[static_cast<std::size_t>(r)][static_cast<std::size_t>(r)];
    return true;
}

/** Least-squares model mapping src → dst over the listed correspondences (Hartley-normalised for H). */
std::optional<Mat3> fit(Model model, const std::vector<Vec2>& src, const std::vector<Vec2>& dst, const std::vector<std::uint32_t>& use) {
    const auto n = use.size();
    if (n == 0) return std::nullopt;
    if (model == Model::Translation) {
        double tx = 0, ty = 0;
        for (const auto i : use) {
            tx += dst[i][0] - src[i][0];
            ty += dst[i][1] - src[i][1];
        }
        return Mat3{1, 0, tx / static_cast<double>(n), 0, 1, ty / static_cast<double>(n), 0, 0, 1};
    }
    if (model == Model::Similarity) {
        std::complex<double> ms, md;
        for (const auto i : use) {
            ms += std::complex<double>(src[i][0], src[i][1]);
            md += std::complex<double>(dst[i][0], dst[i][1]);
        }
        ms /= static_cast<double>(n);
        md /= static_cast<double>(n);
        std::complex<double> num;
        double den = 0;
        for (const auto i : use) {
            const auto s = std::complex<double>(src[i][0], src[i][1]) - ms, d = std::complex<double>(dst[i][0], dst[i][1]) - md;
            num += d * std::conj(s);
            den += std::norm(s);
        }
        if (den < 1e-12) return std::nullopt;
        const auto a = num / den, b = md - a * ms;
        return Mat3{a.real(), -a.imag(), b.real(), a.imag(), a.real(), b.imag(), 0, 0, 1};
    }
    if (n < 4) return std::nullopt;
    const auto normaliser = [&](const std::vector<Vec2>& p) {
        double cx = 0, cy = 0;
        for (const auto i : use) {
            cx += p[i][0];
            cy += p[i][1];
        }
        cx /= static_cast<double>(n);
        cy /= static_cast<double>(n);
        double spread = 0;
        for (const auto i : use) spread += std::hypot(p[i][0] - cx, p[i][1] - cy);
        spread /= static_cast<double>(n);
        const double s = spread > 1e-12 ? std::sqrt(2.0) / spread : 1.0;
        return Mat3{s, 0, -s * cx, 0, s, -s * cy, 0, 0, 1};
    };
    const auto ts = normaliser(src), td = normaliser(dst);
    std::array<std::array<double, 9>, 8> ata{};
    for (const auto i : use) {
        const double x = ts[0] * src[i][0] + ts[2], y = ts[4] * src[i][1] + ts[5];
        const double u = td[0] * dst[i][0] + td[2], v = td[4] * dst[i][1] + td[5];
        const std::array<double, 8> r1{x, y, 1, 0, 0, 0, -u * x, -u * y}, r2{0, 0, 0, x, y, 1, -v * x, -v * y};
        for (int a = 0; a < 8; ++a) {
            for (int b = 0; b < 8; ++b)
                ata[static_cast<std::size_t>(a)][static_cast<std::size_t>(b)] += r1[static_cast<std::size_t>(a)] * r1[static_cast<std::size_t>(b)] + r2[static_cast<std::size_t>(a)] * r2[static_cast<std::size_t>(b)];
            ata[static_cast<std::size_t>(a)][8] += r1[static_cast<std::size_t>(a)] * u + r2[static_cast<std::size_t>(a)] * v;
        }
    }
    if (!solve8(ata)) return std::nullopt;
    const Mat3 hn{ata[0][8], ata[1][8], ata[2][8], ata[3][8], ata[4][8], ata[5][8], ata[6][8], ata[7][8], 1};
    const auto td_inv = invert(td);
    if (!td_inv) return std::nullopt;
    auto h = multiply(*td_inv, multiply(hn, ts));
    if (std::abs(h[8]) < 1e-15) return std::nullopt;
    for (auto& v : h) v /= h[8];
    for (const auto v : h)
        if (!std::isfinite(v)) return std::nullopt;
    return h;
}

struct Estimate final {
    Mat3 model = kIdentity;
    std::vector<std::uint32_t> inliers;  // indices into the correspondence lists
};

std::vector<std::uint32_t> inliersOf(const Mat3& m, const std::vector<Vec2>& src, const std::vector<Vec2>& dst, double threshold) {
    std::vector<std::uint32_t> in;
    for (std::uint32_t i = 0; i < src.size(); ++i) {
        double x, y;
        if (project(m, src[i][0], src[i][1], x, y) <= 0) continue;
        if ((x - dst[i][0]) * (x - dst[i][0]) + (y - dst[i][1]) * (y - dst[i][1]) < threshold * threshold) in.push_back(i);
    }
    return in;
}

std::optional<Estimate> ransac(Model model, const std::vector<Vec2>& src, const std::vector<Vec2>& dst, double threshold, std::uint64_t seed) {
    const std::size_t k = model == Model::Homography ? 4 : model == Model::Similarity ? 2 : 1;
    if (src.size() < k + 2) return std::nullopt;
    std::uint64_t state = seed;
    Estimate best;
    std::size_t iterations = 2000;
    for (std::size_t it = 0; it < iterations; ++it) {
        std::vector<std::uint32_t> sample;
        while (sample.size() < k) {
            const auto i = static_cast<std::uint32_t>(splitmix(state) % src.size());
            if (std::find(sample.begin(), sample.end(), i) == sample.end()) sample.push_back(i);
        }
        if (model == Model::Homography) {
            // Reject near-collinear samples on either side.
            bool degenerate = false;
            for (const auto* p : {&src, &dst})
                for (std::size_t a = 0; a < 4 && !degenerate; ++a) {
                    const auto& p0 = (*p)[sample[a]];
                    const auto& p1 = (*p)[sample[(a + 1) % 4]];
                    const auto& p2 = (*p)[sample[(a + 2) % 4]];
                    if (std::abs((p1[0] - p0[0]) * (p2[1] - p0[1]) - (p1[1] - p0[1]) * (p2[0] - p0[0])) < 1.0) degenerate = true;
                }
            if (degenerate) continue;
        }
        const auto m = fit(model, src, dst, sample);
        if (!m) continue;
        auto in = inliersOf(*m, src, dst, threshold);
        if (in.size() > best.inliers.size()) {
            best.model = *m;
            best.inliers = std::move(in);
            const double w = static_cast<double>(best.inliers.size()) / static_cast<double>(src.size());
            const double fail = 1.0 - std::pow(w, static_cast<double>(k));
            if (fail <= 1e-12) break;
            iterations = std::min<std::size_t>(2000, std::max<std::size_t>(it + 1, static_cast<std::size_t>(std::ceil(std::log(1e-3) / std::log(fail)))));
        }
    }
    if (best.inliers.size() < k + 2) return std::nullopt;
    for (int refine = 0; refine < 3; ++refine) {
        const auto m = fit(model, src, dst, best.inliers);
        if (!m) break;
        auto in = inliersOf(*m, src, dst, threshold);
        if (in.size() < best.inliers.size()) break;
        best.model = *m;
        best.inliers = std::move(in);
    }
    return best;
}

/** Focal length (pixels) from a homography between centred image coordinates (Szeliski & Shum). */
std::optional<double> focalFrom(const Mat3& h) {
    const auto estimate = [](double d1, double d2, double v1, double v2) -> std::optional<double> {
        if (v1 < v2) std::swap(v1, v2);
        if (v1 > 0 && v2 > 0) return std::sqrt(std::abs(d1) > std::abs(d2) ? v1 : v2);
        if (v1 > 0) return std::sqrt(v1);
        return std::nullopt;
    };
    double d1 = h[6] * h[7], d2 = (h[7] - h[6]) * (h[7] + h[6]);
    const auto f1 = (d1 != 0 && d2 != 0) ? estimate(d1, d2, -(h[0] * h[1] + h[3] * h[4]) / d1, (h[0] * h[0] + h[3] * h[3] - h[1] * h[1] - h[4] * h[4]) / d2) : std::nullopt;
    d1 = h[0] * h[3] + h[1] * h[4];
    d2 = h[0] * h[0] + h[1] * h[1] - h[3] * h[3] - h[4] * h[4];
    const auto f0 = (d1 != 0 && d2 != 0) ? estimate(d1, d2, -h[2] * h[5] / d1, (h[5] * h[5] - h[2] * h[2]) / d2) : std::nullopt;
    if (f0 && f1) return std::sqrt(*f0 * *f1);
    return std::nullopt;
}

// ---- Per-image projection -----------------------------------------------------------------------------

struct Placement final {
    double width = 0, height = 0, focal = 0;  // focal > 0: cylindrical
    Mat3 to_canvas = kIdentity, from_canvas = kIdentity;  // image model space ↔ canvas model space

    Vec2 toModel(double x, double y) const {
        if (focal <= 0) return {x, y};
        const double xc = x - width / 2, yc = y - height / 2;
        return {focal * std::atan2(xc, focal), focal * yc / std::hypot(xc, focal)};
    }
    bool fromModel(double u, double v, double& x, double& y) const {
        if (focal <= 0) {
            x = u;
            y = v;
            return true;
        }
        const double theta = u / focal;
        if (std::abs(theta) >= kPi / 2 - 1e-3) return false;
        const double xc = focal * std::tan(theta);
        x = xc + width / 2;
        y = v * std::hypot(xc, focal) / focal + height / 2;
        return true;
    }
    /** Canvas model point → source pixel; false when it falls outside the image. */
    bool source(double cx, double cy, double& x, double& y) const {
        double u, v;
        if (project(from_canvas, cx, cy, u, v) <= 0) return false;
        if (!fromModel(u, v, x, y)) return false;
        return x >= 0 && y >= 0 && x <= width && y <= height;
    }
};

float tent(double t) { return static_cast<float>(std::clamp(2.0 * std::min(t, 1.0 - t), 0.0, 1.0)); }

}  // namespace

namespace detail {
Features detect(const TileStore& image, std::size_t max_points) { return detectPyramid(workingGray(image), max_points); }
}  // namespace detail

std::optional<StitchResult> stitch(std::span<const TileStore* const> images, const StitchOptions& options, std::string& error) {
    if (images.size() < 2 || images.size() > kMaxImages) {
        error = "panoramas need 2–32 images";
        return std::nullopt;
    }
    for (const auto* image : images)
        if (image == nullptr || !paint::detail::isRgba(image->format())) {
            error = "panorama images must be RGBA layers";
            return std::nullopt;
        }
    const auto report = [&](float p) { return !options.progress || options.progress(p); };
    const auto cancelled = [&] {
        error = "cancelled";
        return std::nullopt;
    };
    const std::size_t n = images.size();

    // 1. Features.
    std::vector<Gray> grays;
    for (const auto* image : images) grays.push_back(workingGray(*image));
    std::vector<detail::Features> features(n);
    parallel::parallelFor(n, parallel::workerCount(options.worker_threads), [&](std::size_t i, std::size_t) { features[i] = detectPyramid(grays[i], 2000); });
    if (!report(0.15F)) return cancelled();

    // 2. Pairwise matches and homographies.
    struct Pair final {
        std::size_t a = 0, b = 0;
        std::vector<std::pair<std::uint32_t, std::uint32_t>> matches;
        Mat3 model = kIdentity;  // a → b in the projection's model space
        std::size_t inliers = 0;
        bool accepted = false;
        std::optional<double> focal;
        std::vector<std::pair<Vec2, Vec2>> support;  // inlier correspondences in model space (≤ 200)
    };
    std::vector<Pair> pairs;
    for (std::size_t a = 0; a < n; ++a)
        for (std::size_t b = a + 1; b < n; ++b) pairs.push_back({a, b, {}, kIdentity, 0, false, std::nullopt, {}});
    const auto threshold = [&](const Pair& p) { return 2.5 * std::max(grays[p.a].factor, grays[p.b].factor); };
    const auto accept = [](std::size_t inliers, std::size_t matches) { return inliers >= 15 && static_cast<double>(inliers) > 8.0 + 0.3 * static_cast<double>(matches); };
    const auto pointsOf = [&](const Pair& p, const Placement* pa, const Placement* pb, std::vector<Vec2>& src, std::vector<Vec2>& dst) {
        src.clear();
        dst.clear();
        for (const auto& [i, j] : p.matches) {
            const auto& s = features[p.a].points[i];
            const auto& d = features[p.b].points[j];
            src.push_back(pa ? pa->toModel(s[0], s[1]) : Vec2{s[0], s[1]});
            dst.push_back(pb ? pb->toModel(d[0], d[1]) : Vec2{d[0], d[1]});
        }
    };
    const auto keepSupport = [](Pair& p, const std::vector<Vec2>& src, const std::vector<Vec2>& dst, const std::vector<std::uint32_t>& inliers) {
        p.support.clear();
        const std::size_t stride = std::max<std::size_t>(1, inliers.size() / 200);
        for (std::size_t k = 0; k < inliers.size(); k += stride) p.support.emplace_back(src[inliers[k]], dst[inliers[k]]);
    };
    const bool want_homography = options.projection != Projection::Reposition;
    parallel::parallelFor(pairs.size(), parallel::workerCount(options.worker_threads), [&](std::size_t index, std::size_t) {
        auto& p = pairs[index];
        p.matches = match(features[p.a], features[p.b]);
        std::vector<Vec2> src, dst;
        pointsOf(p, nullptr, nullptr, src, dst);
        const auto model = want_homography ? Model::Homography : Model::Translation;
        const auto e = ransac(model, src, dst, threshold(p), 0x5EED0000ULL + index);
        if (!e || !accept(e->inliers.size(), p.matches.size())) return;
        if (model == Model::Homography) {
            // Plausibility: the corners stay in front, and the area changes by less than 5×.
            const double w = images[p.a]->width(), h = images[p.a]->height();
            const std::array<Vec2, 4> corners{{{0, 0}, {w, 0}, {w, h}, {0, h}}};
            std::array<Vec2, 4> mapped{};
            for (std::size_t c = 0; c < 4; ++c)
                if (project(e->model, corners[c][0], corners[c][1], mapped[c][0], mapped[c][1]) <= 0) return;
            double area = 0;
            for (std::size_t c = 0; c < 4; ++c) area += mapped[c][0] * mapped[(c + 1) % 4][1] - mapped[(c + 1) % 4][0] * mapped[c][1];
            area /= 2;
            const double ratio = area / (w * h);
            if (!(ratio > 0.2 && ratio < 5)) return;
            // Focal estimate on centred coordinates.
            const Mat3 ca{1, 0, w / 2, 0, 1, h / 2, 0, 0, 1};
            const Mat3 cb_inv{1, 0, -static_cast<double>(images[p.b]->width()) / 2, 0, 1, -static_cast<double>(images[p.b]->height()) / 2, 0, 0, 1};
            p.focal = focalFrom(multiply(cb_inv, multiply(e->model, ca)));
        }
        p.model = e->model;
        p.inliers = e->inliers.size();
        p.accepted = true;
        keepSupport(p, src, dst, e->inliers);
    });
    if (!report(0.35F)) return cancelled();

    // 3. Projection.
    std::vector<Placement> placement(n);
    for (std::size_t i = 0; i < n; ++i) {
        placement[i].width = images[i]->width();
        placement[i].height = images[i]->height();
    }
    const auto relinkCylindrical = [&](double focal) {
        for (auto& pl : placement) pl.focal = focal;
        parallel::parallelFor(pairs.size(), parallel::workerCount(options.worker_threads), [&](std::size_t index, std::size_t) {
            auto& p = pairs[index];
            if (p.matches.empty()) return;
            std::vector<Vec2> src, dst;
            pointsOf(p, &placement[p.a], &placement[p.b], src, dst);
            const auto e = ransac(Model::Similarity, src, dst, threshold(p), 0xC7100000ULL + index);
            p.accepted = e && accept(e->inliers.size(), p.matches.size());
            if (p.accepted) {
                p.model = e->model;
                p.inliers = e->inliers.size();
                keepSupport(p, src, dst, e->inliers);
            }
        });
    };

    // Spanning tree over accepted pairs, rooted at its centre; returns the placed set.
    const auto place = [&]() {
        std::vector<std::vector<std::pair<std::size_t, std::size_t>>> adjacency(n);  // (neighbour, pair index)
        for (std::size_t k = 0; k < pairs.size(); ++k)
            if (pairs[k].accepted) {
                adjacency[pairs[k].a].emplace_back(pairs[k].b, k);
                adjacency[pairs[k].b].emplace_back(pairs[k].a, k);
            }
        // Largest connected component (ties: the one containing the lowest index).
        std::vector<int> component(n, -1);
        std::vector<std::size_t> sizes;
        for (std::size_t s = 0; s < n; ++s) {
            if (component[s] >= 0) continue;
            const int id = static_cast<int>(sizes.size());
            std::size_t size = 0;
            std::vector<std::size_t> stack{s};
            component[s] = id;
            while (!stack.empty()) {
                const auto v = stack.back();
                stack.pop_back();
                ++size;
                for (const auto& [u, k] : adjacency[v])
                    if (component[u] < 0) {
                        component[u] = id;
                        stack.push_back(u);
                    }
            }
            sizes.push_back(size);
        }
        const int main = static_cast<int>(std::max_element(sizes.begin(), sizes.end()) - sizes.begin());
        // Maximum spanning tree (Prim) inside it.
        std::vector<int> parent(n, -1), parent_pair(n, -1);
        std::vector<bool> in_tree(n, false);
        std::size_t start = 0;
        while (component[start] != main) ++start;
        in_tree[start] = true;
        using Edge = std::tuple<std::size_t, std::size_t, std::size_t, std::size_t>;  // inliers, from, to, pair
        std::priority_queue<Edge> frontier;
        for (const auto& [u, k] : adjacency[start]) frontier.emplace(pairs[k].inliers, start, u, k);
        while (!frontier.empty()) {
            const auto [w, from, to, k] = frontier.top();
            frontier.pop();
            (void)w;
            if (in_tree[to]) continue;
            in_tree[to] = true;
            parent[to] = static_cast<int>(from);
            parent_pair[to] = static_cast<int>(k);
            for (const auto& [u, kk] : adjacency[to])
                if (!in_tree[u]) frontier.emplace(pairs[kk].inliers, to, u, kk);
        }
        // Tree adjacency and its centre (minimum eccentricity in hops).
        std::vector<std::vector<std::pair<std::size_t, std::size_t>>> tree(n);
        for (std::size_t v = 0; v < n; ++v)
            if (parent[v] >= 0) {
                tree[v].emplace_back(static_cast<std::size_t>(parent[v]), static_cast<std::size_t>(parent_pair[v]));
                tree[static_cast<std::size_t>(parent[v])].emplace_back(v, static_cast<std::size_t>(parent_pair[v]));
            }
        const auto bfs = [&](std::size_t root, std::vector<int>& hops, std::vector<int>& via_parent, std::vector<int>& via_pair) {
            hops.assign(n, -1);
            via_parent.assign(n, -1);
            via_pair.assign(n, -1);
            std::queue<std::size_t> q;
            q.push(root);
            hops[root] = 0;
            while (!q.empty()) {
                const auto v = q.front();
                q.pop();
                for (const auto& [u, k] : tree[v])
                    if (hops[u] < 0) {
                        hops[u] = hops[v] + 1;
                        via_parent[u] = static_cast<int>(v);
                        via_pair[u] = static_cast<int>(k);
                        q.push(u);
                    }
            }
        };
        std::size_t root = start;
        int best_ecc = std::numeric_limits<int>::max();
        std::vector<int> hops, vp, vk;
        for (std::size_t v = 0; v < n; ++v) {
            if (!in_tree[v]) continue;
            bfs(v, hops, vp, vk);
            const int ecc = *std::max_element(hops.begin(), hops.end());
            if (ecc < best_ecc) {
                best_ecc = ecc;
                root = v;
            }
        }
        bfs(root, hops, vp, vk);
        // Compose image → root transforms in BFS order.
        std::vector<std::size_t> order;
        for (std::size_t v = 0; v < n; ++v)
            if (hops[v] >= 0) order.push_back(v);
        std::sort(order.begin(), order.end(), [&](std::size_t a, std::size_t b) { return hops[a] < hops[b]; });
        std::vector<bool> placed(n, false);
        for (const auto v : order) {
            if (v == root) {
                placement[v].to_canvas = kIdentity;
            } else {
                const auto& p = pairs[static_cast<std::size_t>(vk[v])];
                const auto up = static_cast<std::size_t>(vp[v]);
                Mat3 child_to_parent = p.model;  // a → b
                if (p.a != v) {
                    const auto inv = invert(p.model);
                    if (!inv) continue;
                    child_to_parent = *inv;
                }
                placement[v].to_canvas = multiply(placement[up].to_canvas, child_to_parent);
            }
            placed[v] = true;
        }
        return std::pair{placed, root};
    };

    // Global linear refinement (a bundle adjustment for the linear models): every image's similarity
    // (a·z + b, complex) or translation is solved jointly over the inliers of all accepted pairs — not
    // just the spanning tree — with the anchor fixed, which spreads pairwise errors instead of chaining them.
    const auto refineLinear = [&](const std::vector<bool>& placed_set, std::size_t anchor, bool similarity) {
        std::vector<int> slot(n, -1);
        int unknown_images = 0;
        for (std::size_t i = 0; i < n; ++i)
            if (placed_set[i] && i != anchor) slot[i] = unknown_images++;
        if (unknown_images == 0) return;
        const int per = similarity ? 4 : 2;
        const auto m = static_cast<std::size_t>(unknown_images * per);
        std::vector<std::vector<double>> normal(m, std::vector<double>(m + 1, 0.0));
        // Current parameters (for the anchor's fixed values and a weak prior).
        const auto params = [&](std::size_t i) {
            const auto& t = placement[i].to_canvas;
            return std::array<double, 4>{t[0], t[3], t[2], t[5]};  // a.re, a.im, b.re, b.im
        };
        // Residual rows r = Σ coeff·x + constant for the unknowns of images i and j.
        const auto addRow = [&](std::vector<std::pair<std::size_t, double>>& coeffs, double constant) {
            for (const auto& [u, cu] : coeffs) {
                for (const auto& [v, cv] : coeffs) normal[u][v] += cu * cv;
                normal[u][m] -= cu * constant;
            }
        };
        for (const auto& p : pairs) {
            if (!p.accepted || !placed_set[p.a] || !placed_set[p.b]) continue;
            for (const auto& [pa, pb] : p.support) {
                // T_a(pa) − T_b(pb) = 0, real and imaginary parts.
                for (int part = 0; part < 2; ++part) {
                    std::vector<std::pair<std::size_t, double>> coeffs;
                    double constant = 0;
                    const auto term = [&](std::size_t image, const Vec2& z, double sign) {
                        // Re(a·z + b) = ar·x − ai·y + br;  Im(a·z + b) = ar·y + ai·x + bi.
                        const double ca_r = part == 0 ? z[0] : z[1], ca_i = part == 0 ? -z[1] : z[0];
                        if (slot[image] < 0) {
                            const auto q = params(image);
                            if (similarity) constant += sign * (ca_r * q[0] + ca_i * q[1]);
                            else constant += sign * (part == 0 ? z[0] : z[1]);
                            constant += sign * (part == 0 ? q[2] : q[3]);
                            return;
                        }
                        const auto base = static_cast<std::size_t>(slot[image] * per);
                        if (similarity) {
                            coeffs.emplace_back(base + 0, sign * ca_r);
                            coeffs.emplace_back(base + 1, sign * ca_i);
                            coeffs.emplace_back(base + 2 + static_cast<std::size_t>(part), sign);
                        } else {
                            constant += sign * (part == 0 ? z[0] : z[1]);
                            coeffs.emplace_back(base + static_cast<std::size_t>(part), sign);
                        }
                    };
                    term(p.a, pa, 1.0);
                    term(p.b, pb, -1.0);
                    addRow(coeffs, constant);
                }
            }
        }
        // Weak prior toward the tree solution keeps the system well posed.
        for (std::size_t i = 0; i < n; ++i) {
            if (slot[i] < 0) continue;
            const auto q = params(i);
            const auto base = static_cast<std::size_t>(slot[i] * per);
            for (int k = 0; k < per; ++k) {
                const double target = similarity ? q[static_cast<std::size_t>(k)] : q[static_cast<std::size_t>(2 + k)];
                const double w = similarity && k < 2 ? 1e2 : 1e-4;
                normal[base + static_cast<std::size_t>(k)][base + static_cast<std::size_t>(k)] += w;
                normal[base + static_cast<std::size_t>(k)][m] += w * target;
            }
        }
        for (std::size_t c = 0; c < m; ++c) {
            std::size_t pivot = c;
            for (std::size_t r = c + 1; r < m; ++r)
                if (std::abs(normal[r][c]) > std::abs(normal[pivot][c])) pivot = r;
            if (std::abs(normal[pivot][c]) < 1e-12) return;  // keep the tree solution
            std::swap(normal[c], normal[pivot]);
            for (std::size_t r = 0; r < m; ++r) {
                if (r == c || normal[r][c] == 0) continue;
                const double f = normal[r][c] / normal[c][c];
                for (std::size_t k = c; k <= m; ++k) normal[r][k] -= f * normal[c][k];
            }
        }
        for (std::size_t i = 0; i < n; ++i) {
            if (slot[i] < 0) continue;
            const auto base = static_cast<std::size_t>(slot[i] * per);
            const auto value = [&](std::size_t k) { return normal[base + k][m] / normal[base + k][base + k]; };
            if (similarity) {
                const double ar = value(0), ai = value(1), br = value(2), bi = value(3);
                if (!std::isfinite(ar) || !std::isfinite(ai) || !std::isfinite(br) || !std::isfinite(bi)) continue;
                placement[i].to_canvas = Mat3{ar, -ai, br, ai, ar, bi, 0, 0, 1};
            } else {
                const double br = value(0), bi = value(1);
                if (!std::isfinite(br) || !std::isfinite(bi)) continue;
                placement[i].to_canvas = Mat3{1, 0, br, 0, 1, bi, 0, 0, 1};
            }
        }
    };

    Projection projection = options.projection;
    double focal = 0;
    auto [placed, root] = place();
    if (projection == Projection::Reposition) refineLinear(placed, root, false);
    const auto countPlaced = [](const std::vector<bool>& p) { return static_cast<std::size_t>(std::count(p.begin(), p.end(), true)); };
    if (countPlaced(placed) < 2) {
        error = "the images do not overlap enough to stitch";
        return std::nullopt;
    }

    // Canvas bounds from each placed image's border samples; false when a perspective blows up.
    struct Bounds {
        double x0 = std::numeric_limits<double>::max(), y0 = std::numeric_limits<double>::max(), x1 = -std::numeric_limits<double>::max(),
               y1 = -std::numeric_limits<double>::max();
    };
    std::vector<Bounds> image_bounds(n);
    const auto measure = [&](Bounds& total) {
        total = {};
        for (std::size_t i = 0; i < n; ++i) {
            if (!placed[i]) continue;
            auto& pl = placement[i];
            // Normalise so the image centre has w = 1.
            const auto centre = pl.toModel(pl.width / 2, pl.height / 2);
            double cx, cy;
            const double wc = project(pl.to_canvas, centre[0], centre[1], cx, cy);
            if (!(wc > 0) || !std::isfinite(wc)) return false;
            for (auto& v : pl.to_canvas) v /= wc;
            Bounds b;
            constexpr int kSamples = 16;
            for (int s = 0; s <= kSamples; ++s) {
                const double t = static_cast<double>(s) / kSamples;
                for (const Vec2 p : {Vec2{t * pl.width, 0}, Vec2{t * pl.width, pl.height}, Vec2{0, t * pl.height}, Vec2{pl.width, t * pl.height}}) {
                    const auto m = pl.toModel(p[0], p[1]);
                    double x, y;
                    if (project(pl.to_canvas, m[0], m[1], x, y) < 0.1 || !std::isfinite(x) || !std::isfinite(y)) return false;
                    b.x0 = std::min(b.x0, x);
                    b.y0 = std::min(b.y0, y);
                    b.x1 = std::max(b.x1, x);
                    b.y1 = std::max(b.y1, y);
                }
            }
            image_bounds[i] = b;
            total.x0 = std::min(total.x0, b.x0);
            total.y0 = std::min(total.y0, b.y0);
            total.x1 = std::max(total.x1, b.x1);
            total.y1 = std::max(total.y1, b.y1);
            const auto inv = invert(pl.to_canvas);
            if (!inv) return false;
            pl.from_canvas = *inv;
        }
        return true;
    };
    double source_area = 0;
    for (std::size_t i = 0; i < n; ++i)
        if (placed[i]) source_area += placement[i].width * placement[i].height;
    Bounds canvas;
    bool ok = measure(canvas);
    const auto area = [&] { return (canvas.x1 - canvas.x0) * (canvas.y1 - canvas.y0); };
    if (projection == Projection::Auto) {
        projection = ok && area() < 3.0 * source_area ? Projection::Perspective : Projection::Cylindrical;
    }
    if (projection == Projection::Cylindrical) {
        std::vector<double> focals;
        for (const auto& p : pairs)
            if (p.accepted && p.focal && std::isfinite(*p.focal)) focals.push_back(*p.focal);
        double reference = 0;
        for (std::size_t i = 0; i < n; ++i) reference = std::max(reference, std::max(placement[i].width, placement[i].height));
        if (!focals.empty()) {
            std::nth_element(focals.begin(), focals.begin() + static_cast<std::ptrdiff_t>(focals.size() / 2), focals.end());
            focal = focals[focals.size() / 2];
        }
        if (!(focal > 0.3 * reference && focal < 20.0 * reference)) focal = reference;  // ≈ 53° across the long side
        relinkCylindrical(focal);
        std::tie(placed, root) = place();
        refineLinear(placed, root, true);
        if (countPlaced(placed) < 2) {
            error = "the images do not overlap enough to stitch";
            return std::nullopt;
        }
        source_area = 0;
        for (std::size_t i = 0; i < n; ++i)
            if (placed[i]) source_area += placement[i].width * placement[i].height;
        ok = measure(canvas);
    }
    if (!ok) {
        error = "the images span too wide a view for a flat panorama; use Cylindrical";
        return std::nullopt;
    }
    const double canvas_w = std::ceil(canvas.x1) - std::floor(canvas.x0), canvas_h = std::ceil(canvas.y1) - std::floor(canvas.y0);
    if (!(canvas_w >= 1 && canvas_h >= 1) || canvas_w > kMaxCanvasSide || canvas_h > kMaxCanvasSide || canvas_w * canvas_h > kMaxCanvasPixels) {
        error = projection == Projection::Perspective ? "the perspective panorama would be too large; use Cylindrical" : "the panorama would be too large";
        return std::nullopt;
    }
    const double origin_x = std::floor(canvas.x0), origin_y = std::floor(canvas.y0);
    const auto width = static_cast<std::uint32_t>(canvas_w), height = static_cast<std::uint32_t>(canvas_h);

    // 4. Gains from the overlaps.
    std::vector<float> gains(n, 1.0F);
    if (options.exposure_compensation) {
        std::vector<std::vector<double>> count(n, std::vector<double>(n, 0)), sum(n, std::vector<double>(n, 0));
        const double step = std::max(4.0, std::sqrt(canvas_w * canvas_h / 40000.0));
        std::vector<filters::detail::Sampler> samplers;
        samplers.reserve(n);
        for (std::size_t i = 0; i < n; ++i) samplers.emplace_back(*images[i]);
        std::vector<std::pair<std::size_t, double>> covering;
        for (double y = step / 2; y < canvas_h; y += step)
            for (double x = step / 2; x < canvas_w; x += step) {
                covering.clear();
                for (std::size_t i = 0; i < n; ++i) {
                    if (!placed[i]) continue;
                    double sx, sy;
                    if (!placement[i].source(x + origin_x, y + origin_y, sx, sy)) continue;
                    const auto p = samplers[i].bilinear(sx, sy);
                    if (p[3] < 0.5F) continue;
                    covering.emplace_back(i, 255.0 * (0.299 * p[0] + 0.587 * p[1] + 0.114 * p[2]) / p[3]);
                }
                for (const auto& [i, vi] : covering)
                    for (const auto& [j, vj] : covering) {
                        if (i == j) continue;
                        count[i][j] += 1;
                        sum[i][j] += vi;
                        (void)vj;
                    }
            }
        constexpr double kSigmaN = 10.0, kSigmaG = 0.1;
        std::vector<std::vector<double>> a(n, std::vector<double>(n + 1, 0));
        for (std::size_t i = 0; i < n; ++i) {
            a[i][i] += 1e-6;
            a[i][n] += 1e-6;
            for (std::size_t j = 0; j < n; ++j) {
                if (count[i][j] <= 0) continue;
                const double nij = count[i][j], iij = sum[i][j] / nij, iji = sum[j][i] / count[j][i];
                a[i][i] += nij * (2 * iij * iij / (kSigmaN * kSigmaN) + 1 / (kSigmaG * kSigmaG));
                a[i][j] -= nij * 2 * iij * iji / (kSigmaN * kSigmaN);
                a[i][n] += nij / (kSigmaG * kSigmaG);
            }
        }
        // Gaussian elimination (small, SPD).
        bool solved = true;
        for (std::size_t c = 0; c < n && solved; ++c) {
            std::size_t pivot = c;
            for (std::size_t r = c + 1; r < n; ++r)
                if (std::abs(a[r][c]) > std::abs(a[pivot][c])) pivot = r;
            if (std::abs(a[pivot][c]) < 1e-12) {
                solved = false;
                break;
            }
            std::swap(a[c], a[pivot]);
            for (std::size_t r = 0; r < n; ++r) {
                if (r == c) continue;
                const double f = a[r][c] / a[c][c];
                for (std::size_t k = c; k <= n; ++k) a[r][k] -= f * a[c][k];
            }
        }
        if (solved)
            for (std::size_t i = 0; i < n; ++i) {
                const double g = a[i][n] / a[i][i];
                gains[i] = placed[i] && std::isfinite(g) ? static_cast<float>(std::clamp(g, 0.25, 4.0)) : 1.0F;
            }
    }
    if (!report(0.4F)) return cancelled();

    // 5. Render.
    std::size_t first = 0;
    while (!placed[first]) ++first;
    TileStore out(images[first]->format(), width, height);
    const int edge = static_cast<int>(kTileEdge);
    const int columns = static_cast<int>((width + kTileEdge - 1) / kTileEdge), rows = static_cast<int>((height + kTileEdge - 1) / kTileEdge);
    std::atomic<bool> failed{false}, stop{false};
    std::atomic<std::size_t> done{0};
    const auto total_tiles = static_cast<std::size_t>(columns) * static_cast<std::size_t>(rows);
    parallel::parallelFor(total_tiles, parallel::workerCount(options.worker_threads), [&](std::size_t index, std::size_t worker) {
        if (stop.load()) return;
        const int tx = static_cast<int>(index % static_cast<std::size_t>(columns)), ty = static_cast<int>(index / static_cast<std::size_t>(columns));
        const double tile_x0 = origin_x + tx * edge, tile_y0 = origin_y + ty * edge;
        std::vector<std::size_t> overlapping;
        for (std::size_t i = 0; i < n; ++i) {
            if (!placed[i]) continue;
            const auto& b = image_bounds[i];
            if (b.x1 < tile_x0 - 1 || b.y1 < tile_y0 - 1 || b.x0 > tile_x0 + edge + 1 || b.y0 > tile_y0 + edge + 1) continue;
            overlapping.push_back(i);
        }
        if (!overlapping.empty()) {
            std::vector<filters::detail::Sampler> samplers;
            samplers.reserve(overlapping.size());
            for (const auto i : overlapping) samplers.emplace_back(*images[i]);
            TileBuffer tile(out.tile_byte_size(), 0);
            const auto bpp = out.bytes_per_pixel();
            bool any = false;
            for (int y = 0; y < edge; ++y) {
                if (ty * edge + y >= static_cast<int>(height)) break;
                for (int x = 0; x < edge; ++x) {
                    if (tx * edge + x >= static_cast<int>(width)) break;
                    const double cx = tile_x0 + x + 0.5, cy = tile_y0 + y + 0.5;
                    double acc[4] = {0, 0, 0, 0}, total = 0, best_weight = -1;
                    Pixel best{};
                    for (std::size_t k = 0; k < overlapping.size(); ++k) {
                        const auto i = overlapping[k];
                        double sx, sy;
                        if (!placement[i].source(cx, cy, sx, sy)) continue;
                        const auto p = samplers[k].bilinear(sx, sy);
                        const double w = 1e-3 + tent(sx / placement[i].width) * tent(sy / placement[i].height);
                        const float g = gains[i];
                        const Pixel c{p[0] * g, p[1] * g, p[2] * g, p[3]};
                        if (options.blend) {
                            for (int ch = 0; ch < 4; ++ch) acc[ch] += w * c[static_cast<std::size_t>(ch)];
                            total += w;
                        } else if (w > best_weight) {
                            best_weight = w;
                            best = c;
                        }
                    }
                    Pixel value{};
                    if (options.blend) {
                        if (total <= 0) continue;
                        for (int ch = 0; ch < 4; ++ch) value[static_cast<std::size_t>(ch)] = static_cast<float>(acc[ch] / total);
                    } else {
                        if (best_weight < 0) continue;
                        value = best;
                    }
                    if (value[3] <= 0) continue;
                    const auto straight = filters::detail::unpremultiply(value);
                    paint::detail::writePixel(out.format(), tile.data() + (static_cast<std::size_t>(y) * kTileEdge + static_cast<std::size_t>(x)) * bpp, straight.data());
                    any = true;
                }
            }
            if (any && !out.writeTile({tx, ty}, tile)) {
                failed.store(true);
                stop.store(true);
            }
        }
        const auto finished = done.fetch_add(1) + 1;
        if (worker == 0 && options.progress && !options.progress(0.4F + 0.6F * static_cast<float>(finished) / static_cast<float>(total_tiles))) stop.store(true);
    });
    if (stop.load()) {
        error = failed.load() ? "could not store the panorama" : "cancelled";
        return std::nullopt;
    }
    if (!report(1.0F)) return cancelled();

    StitchResult result{std::move(out), projection, {}, {}, gains, projection == Projection::Cylindrical ? focal : 0.0, root, origin_x, origin_y};
    for (std::size_t i = 0; i < n; ++i) (placed[i] ? result.placed : result.dropped).push_back(i);
    return result;
}

}  // namespace croptop::panorama
