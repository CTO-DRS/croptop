#include "retouch.h"

#include <algorithm>
#include <array>
#include <cmath>
#include <exception>
#include <vector>

#include "pixel_plane.h"
#include "selection_mask.h"

namespace croptop::paint {
namespace {

using namespace detail;

constexpr float kLumaR = 0.2126F, kLumaG = 0.7152F, kLumaB = 0.0722F;

float luma(const float* c) { return kLumaR * c[0] + kLumaG * c[1] + kLumaB * c[2]; }

bool unitParameter(float value, const char* name, std::string& error) {
    if (!std::isfinite(value) || value < 0.0F || value > 1.0F) {
        error = std::string(name) + " must be 0–1";
        return false;
    }
    return true;
}

/** Applies `effect(in, out)` over the mask bounds (with `pad` px of context) and blends it in by coverage. */
template <typename Effect>
bool applyEffect(TileStore& dst, const TileStore& mask, int pad, std::string& error, Effect&& effect) {
    if (!validate(dst, mask, error)) return false;
    const auto bounds = selection::bounds(mask);
    if (!bounds) return true;
    try {
        const auto input = readPlane(dst, bounds->left - pad, bounds->top - pad, bounds->right - bounds->left + 2 * pad,
                                     bounds->bottom - bounds->top + 2 * pad);
        Plane output{bounds->left, bounds->top, bounds->right - bounds->left, bounds->bottom - bounds->top, {}};
        output.px.resize(static_cast<std::size_t>(output.w) * output.h * 4U);
        if (!effect(input, output)) return false;
        return blendInto(dst, mask, output, error);
    } catch (const std::exception&) {
        error = "could not allocate retouch buffers";
        return false;
    }
}

float toneWeight(ToneRange range, float l) {
    l = std::clamp(l, 0.0F, 1.0F);
    switch (range) {
        case ToneRange::Shadows: return (1.0F - l) * (1.0F - l);
        case ToneRange::Highlights: return l * l;
        case ToneRange::Midtones: break;
    }
    return 4.0F * l * (1.0F - l);
}

/** 3×3 binomial blur of premultiplied colour at (x, y) of `p` (which must have a 1 px border). */
std::array<float, 4> blurPremultiplied(const Plane& p, int x, int y) {
    static constexpr std::array<float, 3> kTaps{0.25F, 0.5F, 0.25F};
    std::array<float, 4> sum{};
    for (int dy = -1; dy <= 1; ++dy) {
        for (int dx = -1; dx <= 1; ++dx) {
            const float weight = kTaps[static_cast<std::size_t>(dx + 1)] * kTaps[static_cast<std::size_t>(dy + 1)];
            const float* q = p.at(x + dx, y + dy);
            for (int c = 0; c < 3; ++c) sum[static_cast<std::size_t>(c)] += weight * q[c] * q[3];
            sum[3] += weight * q[3];
        }
    }
    return sum;
}

}  // namespace

bool dodgeBurn(TileStore& dst, const TileStore& mask, const DodgeBurnSpec& spec, std::string& error) {
    if (!unitParameter(spec.exposure, "exposure", error)) return false;
    return applyEffect(dst, mask, 0, error, [&](const Plane& in, Plane& out) {
        for (std::size_t i = 0; i < out.px.size(); i += 4U) {
            const float* c = in.px.data() + i;
            float* o = out.px.data() + i;
            std::copy_n(c, 4, o);
            const float l = std::max(0.0F, luma(c));
            const float amount = spec.exposure * toneWeight(spec.range, l);
            const float gamma = spec.burn ? 1.0F + amount : 1.0F / (1.0F + amount);
            if (spec.protect_tones) {
                const float target = std::pow(l, gamma);
                if (l <= 1e-6F) {
                    for (int k = 0; k < 3; ++k) o[k] = c[k] + target - l;
                    continue;
                }
                float ratio = target / l;
                const float peak = std::max({c[0], c[1], c[2]});
                if (ratio > 1.0F && peak <= 1.0F && peak * ratio > 1.0F) ratio = 1.0F / peak;
                for (int k = 0; k < 3; ++k) o[k] = c[k] * ratio;
            } else {
                for (int k = 0; k < 3; ++k) o[k] = std::pow(std::max(0.0F, c[k]), gamma);
            }
        }
        return true;
    });
}

bool sponge(TileStore& dst, const TileStore& mask, const SpongeSpec& spec, std::string& error) {
    if (!unitParameter(spec.flow, "flow", error)) return false;
    return applyEffect(dst, mask, 0, error, [&](const Plane& in, Plane& out) {
        for (std::size_t i = 0; i < out.px.size(); i += 4U) {
            const float* c = in.px.data() + i;
            float* o = out.px.data() + i;
            std::copy_n(c, 4, o);
            const float l = luma(c);
            const float saturation = std::clamp(std::max({c[0], c[1], c[2]}) - std::min({c[0], c[1], c[2]}), 0.0F, 1.0F);
            float factor = 1.0F;
            if (spec.saturate) {
                factor = 1.0F + spec.flow * (spec.vibrance ? 1.0F - saturation : 1.0F);
                if (spec.vibrance) {
                    // Limit the boost so no channel leaves [0, 1].
                    for (int k = 0; k < 3; ++k) {
                        const float d = c[k] - l;
                        if (d > 1e-6F) factor = std::min(factor, (1.0F - l) / d);
                        if (d < -1e-6F) factor = std::min(factor, -l / d);
                    }
                    factor = std::max(factor, 1.0F);
                }
            } else {
                factor = 1.0F - spec.flow * (spec.vibrance ? saturation : 1.0F);
            }
            for (int k = 0; k < 3; ++k) o[k] = l + (c[k] - l) * factor;
        }
        return true;
    });
}

bool blurSharpen(TileStore& dst, const TileStore& mask, const FocusSpec& spec, std::string& error) {
    if (!unitParameter(spec.strength, "strength", error)) return false;
    return applyEffect(dst, mask, 1, error, [&](const Plane& in, Plane& out) {
        for (int y = 0; y < out.h; ++y) {
            for (int x = 0; x < out.w; ++x) {
                const float* c = in.at(x + 1, y + 1);
                float* o = out.at(x, y);
                const auto blur = blurPremultiplied(in, x + 1, y + 1);
                if (!spec.sharpen) {
                    std::array<float, 4> premultiplied{};
                    for (int k = 0; k < 3; ++k) premultiplied[static_cast<std::size_t>(k)] = c[k] * c[3];
                    premultiplied[3] = c[3];
                    for (int k = 0; k < 4; ++k) {
                        premultiplied[static_cast<std::size_t>(k)] += spec.strength * (blur[static_cast<std::size_t>(k)] - premultiplied[static_cast<std::size_t>(k)]);
                    }
                    const float alpha = premultiplied[3];
                    for (int k = 0; k < 3; ++k) o[k] = alpha > 1e-6F ? premultiplied[static_cast<std::size_t>(k)] / alpha : c[k];
                    o[3] = alpha;
                    continue;
                }
                o[3] = c[3];
                for (int k = 0; k < 3; ++k) {
                    const float reference = blur[3] > 1e-6F ? blur[static_cast<std::size_t>(k)] / blur[3] : c[k];
                    float v = c[k] + 1.5F * spec.strength * (c[k] - reference);
                    if (spec.protect_detail) {
                        float lo = c[k], hi = c[k];
                        for (int dy = -1; dy <= 1; ++dy)
                            for (int dx = -1; dx <= 1; ++dx) {
                                const float* q = in.at(x + 1 + dx, y + 1 + dy);
                                if (q[3] <= 0.0F) continue;
                                lo = std::min(lo, q[k]);
                                hi = std::max(hi, q[k]);
                            }
                        v = std::clamp(v, lo, hi);
                    }
                    o[k] = v;
                }
            }
        }
        return true;
    });
}

bool backgroundErase(TileStore& dst, const TileStore& mask, const BackgroundEraseSpec& spec, std::string& error) {
    if (!unitParameter(spec.tolerance, "tolerance", error)) return false;
    for (const float v : spec.sample) {
        if (!std::isfinite(v)) {
            error = "background eraser sample colour must be finite";
            return false;
        }
    }
    if (!std::isfinite(spec.hotspot_x) || !std::isfinite(spec.hotspot_y)) {
        error = "background eraser hotspot must be finite";
        return false;
    }
    if (!validate(dst, mask, error)) return false;
    const auto bounds = selection::bounds(mask);
    if (!bounds) return true;
    try {
        const int w = bounds->right - bounds->left, h = bounds->bottom - bounds->top;
        auto plane = readPlane(dst, bounds->left, bounds->top, w, h);
        const auto coverage = readCoverage(mask, bounds->left, bounds->top, w, h);
        constexpr float kMaxDistance = 1.7320508F;  // sqrt(3): the RGB cube's diagonal
        const float full = spec.tolerance * kMaxDistance;
        const float fade = full * 1.5F;
        const auto weightAt = [&](const float* px) {
            if (px[3] <= 0.0F) return 0.0F;  // already transparent
            const float dr = px[0] - spec.sample[0], dg = px[1] - spec.sample[1], db = px[2] - spec.sample[2];
            const float distance = std::sqrt(dr * dr + dg * dg + db * db);
            if (spec.protect) {
                const float pr = px[0] - (*spec.protect)[0], pg = px[1] - (*spec.protect)[1], pb = px[2] - (*spec.protect)[2];
                if (std::sqrt(pr * pr + pg * pg + pb * pb) <= full * 0.5F) return 0.0F;
            }
            if (distance <= full) return 1.0F;
            if (distance >= fade || fade <= full) return 0.0F;
            return 1.0F - (distance - full) / (fade - full);
        };
        std::vector<float> weight(static_cast<std::size_t>(w) * h, 0.0F);
        for (int y = 0; y < h; ++y) {
            for (int x = 0; x < w; ++x) {
                const std::size_t i = static_cast<std::size_t>(y) * w + x;
                if (coverage[i] != 0U) weight[i] = weightAt(plane.at(x, y));
            }
        }
        if (spec.contiguous) {
            // Keep only the pixels reachable from the hotspot through pixels that would be erased at all.
            const int hx = std::clamp(static_cast<int>(std::floor(spec.hotspot_x)) - bounds->left, 0, w - 1);
            const int hy = std::clamp(static_cast<int>(std::floor(spec.hotspot_y)) - bounds->top, 0, h - 1);
            std::vector<std::uint8_t> reached(weight.size(), 0);
            std::vector<std::pair<int, int>> stack;
            // Start at the hotspot, or at the nearest erasable pixel when the dab centre itself is not erasable.
            int sx = -1, sy = -1;
            float best = 1e30F;
            for (int y = 0; y < h; ++y) {
                for (int x = 0; x < w; ++x) {
                    if (weight[static_cast<std::size_t>(y) * w + x] <= 0.0F) continue;
                    const float d = static_cast<float>((x - hx) * (x - hx) + (y - hy) * (y - hy));
                    if (d < best) best = d, sx = x, sy = y;
                }
            }
            if (sx >= 0) {
                stack.emplace_back(sx, sy);
                reached[static_cast<std::size_t>(sy) * w + sx] = 1;
            }
            while (!stack.empty()) {
                const auto [x, y] = stack.back();
                stack.pop_back();
                static constexpr int dx[4] = {1, -1, 0, 0}, dy[4] = {0, 0, 1, -1};
                for (int k = 0; k < 4; ++k) {
                    const int nx = x + dx[k], ny = y + dy[k];
                    if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue;
                    const std::size_t n = static_cast<std::size_t>(ny) * w + nx;
                    if (reached[n] != 0 || weight[n] <= 0.0F) continue;
                    reached[n] = 1;
                    stack.emplace_back(nx, ny);
                }
            }
            for (std::size_t i = 0; i < weight.size(); ++i) {
                if (reached[i] == 0) weight[i] = 0.0F;
            }
        }
        for (int y = 0; y < h; ++y) {
            for (int x = 0; x < w; ++x) plane.at(x, y)[3] *= 1.0F - weight[static_cast<std::size_t>(y) * w + x];
        }
        return blendInto(dst, mask, plane, error);
    } catch (const std::exception&) {
        error = "could not allocate background eraser buffers";
        return false;
    }
}

bool smudge(TileStore& dst, const TileStore& mask, const SmudgeSpec& spec, std::string& error) {
    if (!unitParameter(spec.strength, "strength", error)) return false;
    if (!std::isfinite(spec.offset_x) || !std::isfinite(spec.offset_y)) {
        error = "smudge offset must be finite";
        return false;
    }
    if (spec.finger_color) {
        for (const float v : *spec.finger_color) {
            if (!std::isfinite(v)) {
                error = "finger painting colour must be finite";
                return false;
            }
        }
    }
    if (!validate(dst, mask, error)) return false;
    const auto bounds = selection::bounds(mask);
    if (!bounds) return true;
    try {
        const auto source = sourcePlane(dst, *bounds, 0, -spec.offset_x, -spec.offset_y);
        auto result = readPlane(dst, bounds->left, bounds->top, source.w, source.h);
        for (std::size_t i = 0; i < result.px.size(); i += 4U) {
            float* o = result.px.data() + i;
            const float* s = spec.finger_color ? spec.finger_color->data() : source.px.data() + i;
            const float alpha = o[3] + spec.strength * (s[3] - o[3]);
            for (int k = 0; k < 3; ++k) {
                const float premultiplied = o[k] * o[3] + spec.strength * (s[k] * s[3] - o[k] * o[3]);
                o[k] = alpha > 1e-6F ? premultiplied / alpha : o[k];
            }
            o[3] = alpha;
        }
        return blendInto(dst, mask, result, error);
    } catch (const std::exception&) {
        error = "could not allocate smudge buffers";
        return false;
    }
}

}  // namespace croptop::paint
