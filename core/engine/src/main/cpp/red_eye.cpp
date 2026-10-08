#include "red_eye.h"

#include <algorithm>
#include <cmath>
#include <exception>
#include <vector>

#include "pixel_plane.h"
#include "selection_mask.h"

namespace croptop::paint {
namespace {

using namespace detail;

float smoothstep(float edge0, float edge1, float x) {
    const float t = std::clamp((x - edge0) / (edge1 - edge0), 0.0F, 1.0F);
    return t * t * (3.0F - 2.0F * t);
}

}  // namespace

bool redEye(TileStore& dst, const TileStore& region, const RedEyeSpec& spec, std::string& error) {
    for (const float v : {spec.pupil_size, spec.darken}) {
        if (!std::isfinite(v) || v < 0.0F || v > 1.0F) {
            error = "pupil size and darken amount must be 0–1";
            return false;
        }
    }
    if (!validate(dst, region, error)) return false;
    const auto bounds = selection::bounds(region);
    if (!bounds) return true;
    try {
        auto plane = readPlane(dst, bounds->left, bounds->top, bounds->right - bounds->left, bounds->bottom - bounds->top);
        const auto coverage = readCoverage(region, plane.x0, plane.y0, plane.w, plane.h);
        const auto count = static_cast<std::size_t>(plane.w) * plane.h;
        const float threshold = 0.5F - 0.35F * spec.pupil_size;
        std::vector<float> weight(count, 0.0F);
        for (std::size_t i = 0; i < count; ++i) {
            if (coverage[i] == 0U) continue;
            const float* c = plane.px.data() + i * 4U;
            if (c[0] < 0.1F) continue;
            const float redness = (c[0] - std::max(c[1], c[2])) / c[0];
            weight[i] = smoothstep(threshold - 0.1F, threshold + 0.1F, redness);
        }
        // Largest 4-connected blob of clearly red pixels.
        std::vector<int> label(count, -1);
        std::vector<std::size_t> stack;
        int best_label = -1;
        std::size_t best_size = 0;
        int next_label = 0;
        for (std::size_t seed = 0; seed < count; ++seed) {
            if (weight[seed] <= 0.5F || label[seed] >= 0) continue;
            std::size_t size = 0;
            stack.push_back(seed);
            label[seed] = next_label;
            while (!stack.empty()) {
                const auto i = stack.back();
                stack.pop_back();
                ++size;
                const int x = static_cast<int>(i % static_cast<std::size_t>(plane.w));
                const int y = static_cast<int>(i / static_cast<std::size_t>(plane.w));
                const int neighbours[4][2] = {{x + 1, y}, {x - 1, y}, {x, y + 1}, {x, y - 1}};
                for (const auto& n : neighbours) {
                    if (n[0] < 0 || n[1] < 0 || n[0] >= plane.w || n[1] >= plane.h) continue;
                    const auto j = static_cast<std::size_t>(n[1]) * plane.w + n[0];
                    if (weight[j] <= 0.5F || label[j] >= 0) continue;
                    label[j] = next_label;
                    stack.push_back(j);
                }
            }
            if (size > best_size) {
                best_size = size;
                best_label = next_label;
            }
            ++next_label;
        }
        if (best_label < 0) return true;
        // Keep the blob and a 2 px rim (its soft edge); drop other red areas.
        constexpr int kRim = 2;
        std::vector<std::uint8_t> keep(count, 0);
        for (int y = 0; y < plane.h; ++y)
            for (int x = 0; x < plane.w; ++x) {
                if (label[static_cast<std::size_t>(y) * plane.w + x] != best_label) continue;
                for (int dy = -kRim; dy <= kRim; ++dy)
                    for (int dx = -kRim; dx <= kRim; ++dx) {
                        const int qx = x + dx, qy = y + dy;
                        if (qx >= 0 && qy >= 0 && qx < plane.w && qy < plane.h) keep[static_cast<std::size_t>(qy) * plane.w + qx] = 1;
                    }
            }
        for (std::size_t i = 0; i < count; ++i) {
            if (!keep[i] || weight[i] <= 0.0F) continue;
            float* c = plane.px.data() + i * 4U;
            const float neutral = 0.5F * (c[1] + c[2]) * (1.0F - 0.8F * spec.darken);
            for (int k = 0; k < 3; ++k) c[k] += weight[i] * (neutral - c[k]);
        }
        return blendInto(dst, region, plane, error);
    } catch (const std::exception&) {
        error = "could not allocate red eye buffers";
        return false;
    }
}

}  // namespace croptop::paint
