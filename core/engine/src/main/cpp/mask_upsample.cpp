#include "mask_upsample.h"

#include <algorithm>
#include <cmath>
#include <cstring>
#include <exception>
#include <vector>

#include "parallel_util.h"

namespace croptop::selection {
namespace {

bool isRgba(PixelFormat f) { return f == PixelFormat::RGBA8 || f == PixelFormat::RGBA16 || f == PixelFormat::RGBA32F; }

std::vector<float> lumaPlane(const TileStore& image) {
    const auto w = image.width();
    const auto h = image.height();
    std::vector<float> out(static_cast<std::size_t>(w) * h, 0.0F);
    const auto bpp = image.bytes_per_pixel();
    for (std::uint32_t ty = 0; ty * kTileEdge < h; ++ty) {
        for (std::uint32_t tx = 0; tx * kTileEdge < w; ++tx) {
            const auto tile = image.readTile({static_cast<std::int32_t>(tx), static_cast<std::int32_t>(ty)});
            if (tile == nullptr) continue;
            const auto rows = std::min<std::uint32_t>(kTileEdge, h - ty * kTileEdge);
            const auto cols = std::min<std::uint32_t>(kTileEdge, w - tx * kTileEdge);
            for (std::uint32_t y = 0; y < rows; ++y) {
                for (std::uint32_t x = 0; x < cols; ++x) {
                    const auto* p = tile->data() + (static_cast<std::size_t>(y) * kTileEdge + x) * bpp;
                    float c[3];
                    for (int i = 0; i < 3; ++i) {
                        if (image.format() == PixelFormat::RGBA8) {
                            c[i] = p[i] / 255.0F;
                        } else if (image.format() == PixelFormat::RGBA16) {
                            std::uint16_t v = 0;
                            std::memcpy(&v, p + i * 2, 2);
                            c[i] = v / 65535.0F;
                        } else {
                            float v = 0.0F;
                            std::memcpy(&v, p + i * 4, 4);
                            c[i] = std::isfinite(v) ? std::clamp(v, 0.0F, 1.0F) : 0.0F;
                        }
                    }
                    out[static_cast<std::size_t>(ty * kTileEdge + y) * w + tx * kTileEdge + x] =
                        0.2126F * c[0] + 0.7152F * c[1] + 0.0722F * c[2];
                }
            }
        }
    }
    return out;
}

/** Box mean over a (2r+1)² window, window cut at the border (mean of the pixels inside). */
std::vector<float> boxMean(const std::vector<float>& in, std::uint32_t w, std::uint32_t h, std::uint32_t r) {
    std::vector<double> prefix(static_cast<std::size_t>(w + 1U) * (h + 1U), 0.0);
    const auto stride = static_cast<std::size_t>(w) + 1U;
    for (std::uint32_t y = 0; y < h; ++y) {
        double row = 0.0;
        for (std::uint32_t x = 0; x < w; ++x) {
            row += in[static_cast<std::size_t>(y) * w + x];
            prefix[(y + 1U) * stride + x + 1U] = prefix[y * stride + x + 1U] + row;
        }
    }
    std::vector<float> out(in.size());
    for (std::uint32_t y = 0; y < h; ++y) {
        const auto y0 = y > r ? y - r : 0U;
        const auto y1 = std::min(h, y + r + 1U);
        for (std::uint32_t x = 0; x < w; ++x) {
            const auto x0 = x > r ? x - r : 0U;
            const auto x1 = std::min(w, x + r + 1U);
            const auto sum = prefix[y1 * stride + x1] - prefix[y0 * stride + x1] - prefix[y1 * stride + x0] + prefix[y0 * stride + x0];
            out[static_cast<std::size_t>(y) * w + x] = static_cast<float>(sum / (static_cast<double>(x1 - x0) * (y1 - y0)));
        }
    }
    return out;
}

}  // namespace

std::optional<TileStore> guidedUpsample(const TileStore& guide, const TileStore& low_mask, const GuidedUpsampleSpec& spec,
                                        std::string& error) {
    const auto W = guide.width();
    const auto H = guide.height();
    const auto mw = low_mask.width();
    const auto mh = low_mask.height();
    if (!isRgba(guide.format()) || low_mask.format() != PixelFormat::A16 || mw == 0U || mh == 0U || mw > W || mh > H) {
        error = "Guided upsampling needs an RGBA guide and an A16 mask that is not larger than the guide";
        return std::nullopt;
    }
    if (spec.radius > 64U || !std::isfinite(spec.epsilon) || spec.epsilon <= 0.0F) {
        error = "Guided upsampling radius must be 0–64 and epsilon positive";
        return std::nullopt;
    }
    try {
        const auto luma = lumaPlane(guide);
        const auto n = static_cast<std::size_t>(mw) * mh;
        // Area-averaged guide at the mask's resolution.
        std::vector<float> low_i(n);
        for (std::uint32_t y = 0; y < mh; ++y) {
            const auto sy0 = static_cast<std::uint64_t>(y) * H / mh;
            const auto sy1 = std::max(sy0 + 1U, static_cast<std::uint64_t>(y + 1U) * H / mh);
            for (std::uint32_t x = 0; x < mw; ++x) {
                const auto sx0 = static_cast<std::uint64_t>(x) * W / mw;
                const auto sx1 = std::max(sx0 + 1U, static_cast<std::uint64_t>(x + 1U) * W / mw);
                double sum = 0.0;
                for (auto sy = sy0; sy < sy1; ++sy)
                    for (auto sx = sx0; sx < sx1; ++sx) sum += luma[sy * W + sx];
                low_i[static_cast<std::size_t>(y) * mw + x] = static_cast<float>(sum / static_cast<double>((sy1 - sy0) * (sx1 - sx0)));
            }
        }
        std::vector<float> p(n);
        for (std::uint32_t y = 0; y < mh; ++y) {
            const auto tile_row = y / kTileEdge;
            for (std::uint32_t tx = 0; tx * kTileEdge < mw; ++tx) {
                const auto tile = low_mask.readTile({static_cast<std::int32_t>(tx), static_cast<std::int32_t>(tile_row)});
                const auto cols = std::min<std::uint32_t>(kTileEdge, mw - tx * kTileEdge);
                for (std::uint32_t x = 0; x < cols; ++x) {
                    std::uint16_t v = 0;
                    if (tile != nullptr) std::memcpy(&v, tile->data() + (static_cast<std::size_t>(y % kTileEdge) * kTileEdge + x) * 2U, 2U);
                    p[static_cast<std::size_t>(y) * mw + tx * kTileEdge + x] = v / 65535.0F;
                }
            }
        }
        std::vector<float> ip(n), ii(n);
        for (std::size_t i = 0; i < n; ++i) {
            ip[i] = low_i[i] * p[i];
            ii[i] = low_i[i] * low_i[i];
        }
        const auto r = spec.radius;
        std::vector<float> a(n, 0.0F), b(p);
        if (r > 0U) {
        const auto mean_i = boxMean(low_i, mw, mh, r);
        const auto mean_p = boxMean(p, mw, mh, r);
        auto mean_ip = boxMean(ip, mw, mh, r);
        auto mean_ii = boxMean(ii, mw, mh, r);
        for (std::size_t i = 0; i < n; ++i) {
            const auto cov = mean_ip[i] - mean_i[i] * mean_p[i];
            const auto var = std::max(0.0F, mean_ii[i] - mean_i[i] * mean_i[i]);
            a[i] = cov / (var + spec.epsilon);
            b[i] = mean_p[i] - a[i] * mean_i[i];
        }
        a = boxMean(a, mw, mh, r);
        b = boxMean(b, mw, mh, r);
        }  // radius 0: no guide, the mask is only resized (a = 0, b = mask).

        auto out = guide.emptyLike(PixelFormat::A16);
        const auto tiles_x = (W + kTileEdge - 1U) / kTileEdge;
        const auto tiles_y = (H + kTileEdge - 1U) / kTileEdge;
        std::vector<TileBuffer> built(static_cast<std::size_t>(tiles_x) * tiles_y);
        const auto sx = static_cast<float>(mw) / static_cast<float>(W);
        const auto sy = static_cast<float>(mh) / static_cast<float>(H);
        parallel::parallelFor(built.size(), parallel::workerCount(0), [&](std::size_t index, std::size_t) {
            const auto tx = static_cast<std::uint32_t>(index % tiles_x);
            const auto ty = static_cast<std::uint32_t>(index / tiles_x);
            TileBuffer tile(out.tile_byte_size(), 0);
            const auto rows = std::min<std::uint32_t>(kTileEdge, H - ty * kTileEdge);
            const auto cols = std::min<std::uint32_t>(kTileEdge, W - tx * kTileEdge);
            bool any = false;
            for (std::uint32_t y = 0; y < rows; ++y) {
                const auto fy = std::clamp((static_cast<float>(ty * kTileEdge + y) + 0.5F) * sy - 0.5F, 0.0F, static_cast<float>(mh - 1U));
                const auto y0 = static_cast<std::uint32_t>(fy);
                const auto y1 = std::min(mh - 1U, y0 + 1U);
                const auto wy = fy - static_cast<float>(y0);
                for (std::uint32_t x = 0; x < cols; ++x) {
                    const auto X = tx * kTileEdge + x;
                    const auto fx = std::clamp((static_cast<float>(X) + 0.5F) * sx - 0.5F, 0.0F, static_cast<float>(mw - 1U));
                    const auto x0 = static_cast<std::uint32_t>(fx);
                    const auto x1 = std::min(mw - 1U, x0 + 1U);
                    const auto wx = fx - static_cast<float>(x0);
                    const auto lerp = [&](const std::vector<float>& v) {
                        const auto top = v[static_cast<std::size_t>(y0) * mw + x0] * (1.0F - wx) + v[static_cast<std::size_t>(y0) * mw + x1] * wx;
                        const auto bottom = v[static_cast<std::size_t>(y1) * mw + x0] * (1.0F - wx) + v[static_cast<std::size_t>(y1) * mw + x1] * wx;
                        return top * (1.0F - wy) + bottom * wy;
                    };
                    const auto q = std::clamp(lerp(a) * luma[static_cast<std::size_t>(ty * kTileEdge + y) * W + X] + lerp(b), 0.0F, 1.0F);
                    const auto value = static_cast<std::uint16_t>(std::lround(q * 65535.0F));
                    std::memcpy(tile.data() + (static_cast<std::size_t>(y) * kTileEdge + x) * 2U, &value, 2U);
                    any = any || value != 0U;
                }
            }
            if (any) built[index] = std::move(tile);
        });
        for (std::size_t index = 0; index < built.size(); ++index) {
            if (built[index].empty()) continue;
            const TileCoordinate coordinate{static_cast<std::int32_t>(index % tiles_x), static_cast<std::int32_t>(index / tiles_x)};
            if (!out.writeTile(coordinate, built[index])) {
                error = "could not store the upsampled mask";
                return std::nullopt;
            }
        }
        return out;
    } catch (const std::exception&) {
        error = "not enough memory to upsample the mask";
        return std::nullopt;
    }
}

}  // namespace croptop::selection
