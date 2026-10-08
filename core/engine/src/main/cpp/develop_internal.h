#pragma once

// Helpers shared by the develop CPU reference stages (develop_tone, develop_detail).

#include <algorithm>
#include <array>
#include <cmath>
#include <cstdint>
#include <memory>
#include <vector>

#include "pixel_plane.h"
#include "tile_store.h"

namespace croptop::develop::detail {

/** Three box passes approximating a Gaussian of `sigma` (Kovesi's box sizes). Returns the radii. */
inline std::array<int, 3> boxRadii(float sigma) {
    const float ideal = std::sqrt(12.0F * sigma * sigma / 3.0F + 1.0F);
    int wl = static_cast<int>(std::floor(ideal));
    if (wl % 2 == 0) --wl;
    const int wu = wl + 2;
    const float m_ideal = (12.0F * sigma * sigma - 3.0F * wl * wl - 12.0F * wl - 9.0F) / (-4.0F * wl - 4.0F);
    const int m = static_cast<int>(std::lround(m_ideal));
    std::array<int, 3> radii{};
    for (int i = 0; i < 3; ++i) radii[static_cast<std::size_t>(i)] = ((i < m ? wl : wu) - 1) / 2;
    return radii;
}

inline void boxPass(const float* in, float* out, int n, int stride, int r) {
    // Sliding window with clamped edges.
    double sum = 0;
    for (int k = -r; k <= r; ++k) sum += in[std::clamp(k, 0, n - 1) * stride];
    const double norm = 1.0 / (2 * r + 1);
    for (int i = 0; i < n; ++i) {
        out[i * stride] = static_cast<float>(sum * norm);
        sum += in[std::min(i + r + 1, n - 1) * stride] - in[std::max(i - r, 0) * stride];
    }
}

inline void gaussian(std::vector<float>& plane, int w, int h, float sigma, std::vector<float>& scratch) {
    scratch.resize(plane.size());
    if (sigma < 3.5F) {
        // Small radii: an exact separable kernel (three boxes degenerate to one narrow box here).
        const int r = std::max(1, static_cast<int>(std::ceil(3.0F * sigma)));
        std::vector<float> k(static_cast<std::size_t>(2 * r + 1));
        float sum = 0;
        for (int i = -r; i <= r; ++i) sum += k[static_cast<std::size_t>(i + r)] = std::exp(-0.5F * static_cast<float>(i * i) / (sigma * sigma));
        for (auto& v : k) v /= sum;
        // Same summation order per pixel as the plain loops (acc starts at 0, taps −r … r), so results are bit-identical; only the
        // clamping is hoisted out of the interior and the vertical pass walks rows instead of columns.
        for (int y = 0; y < h; ++y) {
            const float* in = plane.data() + static_cast<std::size_t>(y) * w;
            float* out = scratch.data() + static_cast<std::size_t>(y) * w;
            const int interior_end = w - r;  // x in [r, w − r) never clamps
            for (int x = 0; x < w; ++x) {
                float acc = 0;
                if (x >= r && x < interior_end) {
                    const float* p = in + x - r;
                    for (int i = 0; i <= 2 * r; ++i) acc += k[static_cast<std::size_t>(i)] * p[i];
                } else {
                    for (int i = -r; i <= r; ++i) acc += k[static_cast<std::size_t>(i + r)] * in[std::clamp(x + i, 0, w - 1)];
                }
                out[x] = acc;
            }
        }
        for (int y = 0; y < h; ++y) {
            float* out = plane.data() + static_cast<std::size_t>(y) * w;
            std::fill(out, out + w, 0.0F);
            for (int i = -r; i <= r; ++i) {
                const float* row = scratch.data() + static_cast<std::size_t>(std::clamp(y + i, 0, h - 1)) * w;
                const float weight = k[static_cast<std::size_t>(i + r)];
                for (int x = 0; x < w; ++x) out[x] += weight * row[x];
            }
        }
        return;
    }
    for (const int r : boxRadii(sigma)) {
        if (r <= 0) continue;
        for (int y = 0; y < h; ++y) boxPass(plane.data() + static_cast<std::size_t>(y) * w, scratch.data() + static_cast<std::size_t>(y) * w, w, 1, r);
        for (int x = 0; x < w; ++x) boxPass(scratch.data() + x, plane.data() + x, h, w, r);
    }
}

/** Fetches pixels of a store around one tile (3×3 tiles cached), with coordinates clamped to the image. */
class Neighbourhood {
public:
    Neighbourhood(const TileStore& s, std::int64_t tx, std::int64_t ty) : s_(s), tx_(tx), ty_(ty) {
        for (int dy = -1; dy <= 1; ++dy)
            for (int dx = -1; dx <= 1; ++dx) tiles_[static_cast<std::size_t>((dy + 1) * 3 + dx + 1)] = s.readTile({static_cast<std::int32_t>(tx + dx), static_cast<std::int32_t>(ty + dy)});
    }
    void read(std::int64_t x, std::int64_t y, float* out) const {
        x = std::clamp<std::int64_t>(x, 0, s_.width() - 1);
        y = std::clamp<std::int64_t>(y, 0, s_.height() - 1);
        const std::int64_t dx = x / kTileEdge - tx_, dy = y / kTileEdge - ty_;
        const auto& tile = tiles_[static_cast<std::size_t>((dy + 1) * 3 + dx + 1)];
        if (tile == nullptr) {
            out[0] = out[1] = out[2] = out[3] = 0.0F;
            return;
        }
        paint::detail::readPixel(s_.format(), tile->data() + (static_cast<std::size_t>(y % kTileEdge) * kTileEdge + static_cast<std::size_t>(x % kTileEdge)) * s_.bytes_per_pixel(), out);
    }

private:
    const TileStore& s_;
    std::int64_t tx_, ty_;
    std::array<std::shared_ptr<const TileBuffer>, 9> tiles_;
};


}  // namespace croptop::develop::detail
