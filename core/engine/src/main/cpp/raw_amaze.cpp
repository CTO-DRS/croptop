// Internal implementation for the public EdgeDirected Bayer mode (Hamilton-Adams style).
#include "raw_amaze.h"

#include <algorithm>
#include <cmath>

namespace croptop::raw::detail {
namespace {
constexpr float kEps = 1e-5F;
}

void amaze(std::span<const float> cfa, std::span<const std::uint8_t> colours, std::span<float> red, std::span<float> green,
           std::span<float> blue, int width, int height) {
    const auto n = static_cast<std::size_t>(width) * static_cast<std::size_t>(height);
    if (width < 5 || height < 5 || cfa.size() != n || colours.size() != n || red.size() != n || green.size() != n || blue.size() != n) return;
    const auto colour = [&](int i) { return colours[static_cast<std::size_t>(i)]; };
    float* rgb[3] = {red.data(), green.data(), blue.data()};
    std::fill(red.begin(), red.end(), 0.0F);
    std::fill(green.begin(), green.end(), 0.0F);
    std::fill(blue.begin(), blue.end(), 0.0F);
    for (std::size_t i = 0; i < n; ++i) {
        if (colours[i] > 2) return;
        rgb[colours[i]][i] = cfa[i];
    }

    // Green at red/blue photosites: corrected horizontal and vertical means,
    // blended by inverse local gradient to follow edges without color zippering.
    for (int y = 2; y < height - 2; ++y) {
        for (int x = 2; x < width - 2; ++x) {
            const int i = y * width + x;
            if (colour(i) == 1) continue;
            const float horizontal = 0.5F * (cfa[static_cast<std::size_t>(i - 1)] + cfa[static_cast<std::size_t>(i + 1)]) +
                                     0.25F * (2.0F * cfa[static_cast<std::size_t>(i)] - cfa[static_cast<std::size_t>(i - 2)] - cfa[static_cast<std::size_t>(i + 2)]);
            const float vertical = 0.5F * (cfa[static_cast<std::size_t>(i - width)] + cfa[static_cast<std::size_t>(i + width)]) +
                                   0.25F * (2.0F * cfa[static_cast<std::size_t>(i)] - cfa[static_cast<std::size_t>(i - 2 * width)] -
                                            cfa[static_cast<std::size_t>(i + 2 * width)]);
            const float gh = std::abs(cfa[static_cast<std::size_t>(i - 1)] - cfa[static_cast<std::size_t>(i + 1)]) +
                             0.5F * std::abs(2.0F * cfa[static_cast<std::size_t>(i)] - cfa[static_cast<std::size_t>(i - 2)] -
                                             cfa[static_cast<std::size_t>(i + 2)]);
            const float gv = std::abs(cfa[static_cast<std::size_t>(i - width)] - cfa[static_cast<std::size_t>(i + width)]) +
                             0.5F * std::abs(2.0F * cfa[static_cast<std::size_t>(i)] - cfa[static_cast<std::size_t>(i - 2 * width)] -
                                             cfa[static_cast<std::size_t>(i + 2 * width)]);
            const float wh = 1.0F / (kEps + gh), wv = 1.0F / (kEps + gv);
            green[static_cast<std::size_t>(i)] = std::max(0.0F, (wh * horizontal + wv * vertical) / (wh + wv));
        }
    }

    // Missing red/blue at green photosites: follow the CFA-native horizontal
    // or vertical axis and interpolate signed chroma differences (C - G).
    for (int y = 2; y < height - 2; ++y) {
        for (int x = 2; x < width - 2; ++x) {
            const int i = y * width + x;
            if (colour(i) != 1) continue;
            for (const int c : {0, 2}) {
                float sum = 0.0F, weights = 0.0F;
                for (const int offset : {-1, 1}) {
                    const int j = i + offset;
                    if (colour(j) == c) {
                        const int far = i + 2 * offset;
                        const float weight = 1.0F / (kEps + std::abs(green[static_cast<std::size_t>(j)] - green[static_cast<std::size_t>(i)]) +
                                                      0.5F * std::abs(green[static_cast<std::size_t>(far)] - green[static_cast<std::size_t>(i)]));
                        sum += weight * (cfa[static_cast<std::size_t>(j)] - green[static_cast<std::size_t>(j)]);
                        weights += weight;
                    }
                }
                for (const int offset : {-width, width}) {
                    const int j = i + offset;
                    if (colour(j) == c) {
                        const int far = i + 2 * offset;
                        const float weight = 1.0F / (kEps + std::abs(green[static_cast<std::size_t>(j)] - green[static_cast<std::size_t>(i)]) +
                                                      0.5F * std::abs(green[static_cast<std::size_t>(far)] - green[static_cast<std::size_t>(i)]));
                        sum += weight * (cfa[static_cast<std::size_t>(j)] - green[static_cast<std::size_t>(j)]);
                        weights += weight;
                    }
                }
                if (weights > 0.0F) rgb[c][static_cast<std::size_t>(i)] = std::max(0.0F, green[static_cast<std::size_t>(i)] + sum / weights);
            }
        }
    }

    // Opposite chroma at red/blue photosites comes from diagonal C-G differences.
    for (int y = 2; y < height - 2; ++y) {
        for (int x = 2; x < width - 2; ++x) {
            const int i = y * width + x;
            if (colour(i) == 1) continue;
            const int c = 2 - colour(i);
            float sum = 0.0F, weights = 0.0F;
            for (const int dy : {-1, 1}) {
                for (const int dx : {-1, 1}) {
                    const int j = i + dy * width + dx;
                    if (colour(j) != c) continue;
                    const float weight = 1.0F / (kEps + std::abs(green[static_cast<std::size_t>(j)] - green[static_cast<std::size_t>(i)]));
                    sum += weight * (cfa[static_cast<std::size_t>(j)] - green[static_cast<std::size_t>(j)]);
                    weights += weight;
                }
            }
            if (weights > 0.0F) rgb[c][static_cast<std::size_t>(i)] = std::max(0.0F, green[static_cast<std::size_t>(i)] + sum / weights);
        }
    }
}

}  // namespace croptop::raw::detail
