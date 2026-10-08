#include "lut.h"

#include <algorithm>
#include <atomic>
#include <cctype>
#include <cmath>
#include <cstdlib>
#include <cstring>
#include <exception>

#include "parallel_util.h"
#include "pixel_plane.h"

namespace croptop::color {
namespace {

constexpr std::uint32_t kMax1d = 65536;
constexpr std::uint32_t kMax3d = 129;

/** Splits text into lines and whitespace-separated tokens, dropping comments. */
class Lines final {
public:
    explicit Lines(std::string_view text) : text_(text) {}

    /** Next line with at least one token (comments after '#' removed); false at the end. */
    bool next(std::vector<std::string_view>& tokens) {
        while (pos_ < text_.size()) {
            auto end = text_.find_first_of("\r\n", pos_);
            if (end == std::string_view::npos) end = text_.size();
            auto line = text_.substr(pos_, end - pos_);
            pos_ = end + 1;
            ++number_;
            if (const auto hash = line.find('#'); hash != std::string_view::npos) line = line.substr(0, hash);
            tokens.clear();
            std::size_t i = 0;
            while (i < line.size()) {
                while (i < line.size() && std::isspace(static_cast<unsigned char>(line[i]))) ++i;
                const auto start = i;
                if (i < line.size() && line[i] == '"') {  // quoted title
                    const auto close = line.find('"', i + 1);
                    i = close == std::string_view::npos ? line.size() : close + 1;
                } else {
                    while (i < line.size() && !std::isspace(static_cast<unsigned char>(line[i]))) ++i;
                }
                if (i > start) tokens.push_back(line.substr(start, i - start));
            }
            if (!tokens.empty()) return true;
        }
        return false;
    }
    std::size_t line() const { return number_; }

private:
    std::string_view text_;
    std::size_t pos_ = 0;
    std::size_t number_ = 0;
};

bool number(std::string_view token, double& out) {
    if (token.empty() || token.size() > 64) return false;
    char buffer[65];
    std::memcpy(buffer, token.data(), token.size());
    buffer[token.size()] = '\0';
    char* end = nullptr;
    out = std::strtod(buffer, &end);
    return end == buffer + token.size() && std::isfinite(out);
}

bool integer(std::string_view token, std::uint32_t& out) {
    double v = 0.0;
    if (!number(token, v) || v < 0.0 || v > 4.0e9 || v != std::floor(v)) return false;
    out = static_cast<std::uint32_t>(v);
    return true;
}

std::string at(const Lines& lines, const std::string& message) { return "line " + std::to_string(lines.line()) + ": " + message; }

float clamp01(float v) { return std::clamp(v, 0.0F, 1.0F); }

/** Tetrahedral interpolation in a red-fastest RGB table of size n³, at normalised coordinates. */
std::array<float, 3> tetrahedral(const std::vector<float>& table, std::uint32_t n, float r, float g, float b) {
    const float scale = static_cast<float>(n - 1U);
    const float fr = clamp01(r) * scale, fg = clamp01(g) * scale, fb = clamp01(b) * scale;
    const auto ir = std::min(static_cast<std::uint32_t>(fr), n - 2U);
    const auto ig = std::min(static_cast<std::uint32_t>(fg), n - 2U);
    const auto ib = std::min(static_cast<std::uint32_t>(fb), n - 2U);
    const float dr = fr - static_cast<float>(ir), dg = fg - static_cast<float>(ig), db = fb - static_cast<float>(ib);
    const auto node = [&](std::uint32_t a, std::uint32_t c, std::uint32_t e) {
        return table.data() + ((static_cast<std::size_t>(e) * n + c) * n + a) * 3U;
    };
    const float* c000 = node(ir, ig, ib);
    const float* c111 = node(ir + 1U, ig + 1U, ib + 1U);
    const float* first = nullptr;
    const float* second = nullptr;
    float w1 = 0.0F, w2 = 0.0F, w3 = 0.0F;  // weights of c000→first, first→second, second→c111
    if (dr >= dg) {
        if (dg >= db) {  // r ≥ g ≥ b
            first = node(ir + 1U, ig, ib);
            second = node(ir + 1U, ig + 1U, ib);
            w1 = dr, w2 = dg, w3 = db;
        } else if (dr >= db) {  // r ≥ b > g
            first = node(ir + 1U, ig, ib);
            second = node(ir + 1U, ig, ib + 1U);
            w1 = dr, w2 = db, w3 = dg;
        } else {  // b > r ≥ g
            first = node(ir, ig, ib + 1U);
            second = node(ir + 1U, ig, ib + 1U);
            w1 = db, w2 = dr, w3 = dg;
        }
    } else {
        if (db > dg) {  // b > g > r
            first = node(ir, ig, ib + 1U);
            second = node(ir, ig + 1U, ib + 1U);
            w1 = db, w2 = dg, w3 = dr;
        } else if (db > dr) {  // g ≥ b > r
            first = node(ir, ig + 1U, ib);
            second = node(ir, ig + 1U, ib + 1U);
            w1 = dg, w2 = db, w3 = dr;
        } else {  // g > r ≥ b
            first = node(ir, ig + 1U, ib);
            second = node(ir + 1U, ig + 1U, ib);
            w1 = dg, w2 = dr, w3 = db;
        }
    }
    std::array<float, 3> out{};
    for (int c = 0; c < 3; ++c) {
        out[static_cast<std::size_t>(c)] = c000[c] + w1 * (first[c] - c000[c]) + w2 * (second[c] - first[c]) + w3 * (c111[c] - second[c]);
    }
    return out;
}

bool looksLike3dl(std::string_view text) {
    Lines lines(text);
    std::vector<std::string_view> tokens;
    while (lines.next(tokens)) {
        if (tokens[0] == "3DMESH" || tokens[0] == "Mesh") return true;
        // A .cube file opens with keywords (LUT_*_SIZE precedes its data); a .3dl opens with its mesh row.
        double v = 0.0;
        return number(tokens[0], v);
    }
    return false;
}

}  // namespace

std::array<float, 3> Lut::apply(std::array<float, 3> rgb) const {
    if (size_1d >= 2U) {
        for (int c = 0; c < 3; ++c) {
            const auto ci = static_cast<std::size_t>(c);
            const float span = domain_max_1d[ci] - domain_min_1d[ci];
            const float t = clamp01((rgb[ci] - domain_min_1d[ci]) / span) * static_cast<float>(size_1d - 1U);
            const auto i = std::min(static_cast<std::uint32_t>(t), size_1d - 2U);
            const float f = t - static_cast<float>(i);
            rgb[ci] = table_1d[i * 3U + ci] + f * (table_1d[(i + 1U) * 3U + ci] - table_1d[i * 3U + ci]);
        }
    }
    if (size_3d >= 2U) {
        std::array<float, 3> t{};
        for (std::size_t c = 0; c < 3; ++c) t[c] = (rgb[c] - domain_min_3d[c]) / (domain_max_3d[c] - domain_min_3d[c]);
        rgb = tetrahedral(table_3d, size_3d, t[0], t[1], t[2]);
    }
    return rgb;
}

std::vector<float> Lut::toTexture() const {
    std::vector<float> texels;
    if (size_3d < 2U) return texels;
    const auto count = static_cast<std::size_t>(size_3d) * size_3d * size_3d;
    texels.resize(count * 4U);
    for (std::size_t i = 0; i < count; ++i) {
        std::copy_n(table_3d.data() + i * 3U, 3, texels.data() + i * 4U);
        texels[i * 4U + 3U] = 1.0F;
    }
    return texels;
}

std::optional<Lut> parseCube(std::string_view text, std::string& error) {
    Lut lut;
    Lines lines(text);
    std::vector<std::string_view> tokens;
    std::vector<float> rows;
    bool range_1d_seen = false;
    try {
        while (lines.next(tokens)) {
            const auto key = tokens[0];
            double first = 0.0;
            if (number(key, first)) {
                if (tokens.size() != 3) {
                    error = at(lines, "a data row needs three values");
                    return std::nullopt;
                }
                const std::size_t expected = static_cast<std::size_t>(lut.size_1d) + static_cast<std::size_t>(lut.size_3d) * lut.size_3d * lut.size_3d;
                if (rows.size() / 3U >= expected) {
                    error = at(lines, "more data rows than the declared size");
                    return std::nullopt;
                }
                for (const auto token : tokens) {
                    double v = 0.0;
                    if (!number(token, v) || std::fabs(v) > 1.0e6) {
                        error = at(lines, "invalid number");
                        return std::nullopt;
                    }
                    rows.push_back(static_cast<float>(v));
                }
                continue;
            }
            if (!rows.empty()) {
                error = at(lines, "keyword after the data rows");
                return std::nullopt;
            }
            if (key == "TITLE") {
                if (tokens.size() > 1) {
                    auto title = tokens[1];
                    if (title.size() >= 2 && title.front() == '"' && title.back() == '"') title = title.substr(1, title.size() - 2);
                    lut.title = std::string(title.substr(0, 256));
                }
            } else if (key == "LUT_1D_SIZE" || key == "LUT_3D_SIZE") {
                std::uint32_t size = 0;
                const bool is3d = key == "LUT_3D_SIZE";
                if (tokens.size() != 2 || !integer(tokens[1], size) || size < 2U || size > (is3d ? kMax3d : kMax1d)) {
                    error = at(lines, is3d ? "LUT_3D_SIZE must be 2–129" : "LUT_1D_SIZE must be 2–65536");
                    return std::nullopt;
                }
                (is3d ? lut.size_3d : lut.size_1d) = size;
            } else if (key == "DOMAIN_MIN" || key == "DOMAIN_MAX") {
                if (tokens.size() != 4) {
                    error = at(lines, "domain needs three values");
                    return std::nullopt;
                }
                std::array<float, 3> v{};
                for (std::size_t c = 0; c < 3; ++c) {
                    double d = 0.0;
                    if (!number(tokens[c + 1], d)) {
                        error = at(lines, "invalid domain value");
                        return std::nullopt;
                    }
                    v[c] = static_cast<float>(d);
                }
                // DOMAIN_* applies to every table unless a per-table input range overrides it.
                if (key == "DOMAIN_MIN") {
                    lut.domain_min_3d = v;
                    if (!range_1d_seen) lut.domain_min_1d = v;
                } else {
                    lut.domain_max_3d = v;
                    if (!range_1d_seen) lut.domain_max_1d = v;
                }
            } else if (key == "LUT_1D_INPUT_RANGE" || key == "LUT_3D_INPUT_RANGE") {
                double lo = 0.0, hi = 0.0;
                if (tokens.size() != 3 || !number(tokens[1], lo) || !number(tokens[2], hi)) {
                    error = at(lines, "input range needs two values");
                    return std::nullopt;
                }
                const std::array<float, 3> low{static_cast<float>(lo), static_cast<float>(lo), static_cast<float>(lo)};
                const std::array<float, 3> high{static_cast<float>(hi), static_cast<float>(hi), static_cast<float>(hi)};
                if (key == "LUT_1D_INPUT_RANGE") {
                    lut.domain_min_1d = low;
                    lut.domain_max_1d = high;
                    range_1d_seen = true;
                } else {
                    lut.domain_min_3d = low;
                    lut.domain_max_3d = high;
                }
            } else {
                // Unknown keywords (vendor extensions such as LUT_IN_VIDEO_RANGE) are ignored.
            }
        }
    } catch (const std::bad_alloc&) {
        error = "not enough memory for the LUT";
        return std::nullopt;
    }
    if (lut.size_1d == 0U && lut.size_3d == 0U) {
        error = "no LUT_1D_SIZE or LUT_3D_SIZE";
        return std::nullopt;
    }
    for (std::size_t c = 0; c < 3; ++c) {
        if (!(lut.domain_max_1d[c] > lut.domain_min_1d[c]) || !(lut.domain_max_3d[c] > lut.domain_min_3d[c])) {
            error = "the domain maximum must exceed the minimum";
            return std::nullopt;
        }
    }
    const std::size_t count_1d = lut.size_1d;
    const std::size_t count_3d = static_cast<std::size_t>(lut.size_3d) * lut.size_3d * lut.size_3d;
    if (rows.size() != (count_1d + count_3d) * 3U) {
        error = "expected " + std::to_string(count_1d + count_3d) + " data rows, found " + std::to_string(rows.size() / 3U);
        return std::nullopt;
    }
    lut.table_1d.assign(rows.begin(), rows.begin() + static_cast<std::ptrdiff_t>(count_1d * 3U));
    lut.table_3d.assign(rows.begin() + static_cast<std::ptrdiff_t>(count_1d * 3U), rows.end());
    return lut;
}

std::optional<Lut> parse3dl(std::string_view text, std::string& error) {
    Lines lines(text);
    std::vector<std::string_view> tokens;
    std::vector<std::uint32_t> mesh;
    std::uint32_t out_bits = 0;
    std::vector<std::uint32_t> values;
    std::size_t expected = 0;
    try {
        while (lines.next(tokens)) {
            if (tokens[0] == "3DMESH") continue;
            if (tokens[0] == "Mesh") {
                std::uint32_t in_bits = 0;
                if (tokens.size() != 3 || !integer(tokens[1], in_bits) || !integer(tokens[2], out_bits) || out_bits < 8 || out_bits > 16) {
                    error = at(lines, "Mesh needs input and output bit depths (output 8–16)");
                    return std::nullopt;
                }
                continue;
            }
            if (mesh.empty()) {
                if (tokens.size() < 2 || tokens.size() > kMax3d) {
                    error = at(lines, "the mesh row must list 2–129 input values");
                    return std::nullopt;
                }
                for (const auto token : tokens) {
                    std::uint32_t v = 0;
                    if (!integer(token, v) || (!mesh.empty() && v <= mesh.back())) {
                        error = at(lines, "mesh values must be increasing integers");
                        return std::nullopt;
                    }
                    mesh.push_back(v);
                }
                expected = mesh.size() * mesh.size() * mesh.size();
                values.reserve(expected * 3U);
                continue;
            }
            if (tokens.size() != 3) {
                error = at(lines, "a data row needs three values");
                return std::nullopt;
            }
            if (values.size() / 3U >= expected) {
                error = at(lines, "more data rows than the mesh defines");
                return std::nullopt;
            }
            for (const auto token : tokens) {
                std::uint32_t v = 0;
                if (!integer(token, v) || v > 65535U) {
                    error = at(lines, "values must be integers 0–65535");
                    return std::nullopt;
                }
                values.push_back(v);
            }
        }
    } catch (const std::bad_alloc&) {
        error = "not enough memory for the LUT";
        return std::nullopt;
    }
    if (mesh.empty()) {
        error = "no mesh row";
        return std::nullopt;
    }
    if (values.size() != expected * 3U) {
        error = "expected " + std::to_string(expected) + " data rows, found " + std::to_string(values.size() / 3U);
        return std::nullopt;
    }
    // Non-uniform meshes are rare; the table is treated as uniform over the mesh's span.
    const auto n = static_cast<std::uint32_t>(mesh.size());
    const auto peak = *std::max_element(values.begin(), values.end());
    if (out_bits == 0U) out_bits = peak <= 1023U ? 10U : peak <= 4095U ? 12U : 16U;
    const float scale = 1.0F / static_cast<float>((1U << out_bits) - 1U);
    Lut lut;
    lut.size_3d = n;
    lut.table_3d.resize(values.size());
    // .3dl rows run blue fastest (r outer, g, b inner); store red fastest.
    for (std::uint32_t r = 0; r < n; ++r)
        for (std::uint32_t g = 0; g < n; ++g)
            for (std::uint32_t b = 0; b < n; ++b) {
                const auto src = ((static_cast<std::size_t>(r) * n + g) * n + b) * 3U;
                const auto dst = ((static_cast<std::size_t>(b) * n + g) * n + r) * 3U;
                for (std::size_t c = 0; c < 3; ++c) lut.table_3d[dst + c] = static_cast<float>(values[src + c]) * scale;
            }
    const float in_max = static_cast<float>(mesh.back());
    const float in_scale = in_max <= 1023.0F ? 1023.0F : in_max <= 4095.0F ? 4095.0F : 65535.0F;
    lut.domain_min_3d.fill(static_cast<float>(mesh.front()) / in_scale);
    lut.domain_max_3d.fill(in_max / in_scale);
    return lut;
}

std::optional<Lut> parseLut(std::string_view text, std::string_view file_name, std::string& error) {
    const auto dot = file_name.rfind('.');
    std::string extension;
    if (dot != std::string_view::npos) {
        for (const char c : file_name.substr(dot + 1)) extension += static_cast<char>(std::tolower(static_cast<unsigned char>(c)));
    }
    if (extension == "cube") return parseCube(text, error);
    if (extension == "3dl") return parse3dl(text, error);
    return looksLike3dl(text) ? parse3dl(text, error) : parseCube(text, error);
}

bool applyLut(TileStore& target, const Lut& lut, float strength, const TileStore* mask, std::string& error, unsigned worker_threads) {
    if (!paint::detail::isRgba(target.format())) {
        error = "LUTs apply to RGBA layers";
        return false;
    }
    if (!std::isfinite(strength) || strength < 0.0F || strength > 1.0F) {
        error = "strength must be 0–1";
        return false;
    }
    if (mask != nullptr && (mask->format() != PixelFormat::A16 || mask->width() != target.width() || mask->height() != target.height())) {
        error = "the mask must be an A16 mask of the layer's size";
        return false;
    }
    if ((lut.size_1d < 2U && lut.size_3d < 2U) || lut.table_1d.size() != lut.size_1d * 3ULL ||
        lut.table_3d.size() != static_cast<std::size_t>(lut.size_3d) * lut.size_3d * lut.size_3d * 3U) {
        error = "the LUT is empty or inconsistent";
        return false;
    }
    if (strength == 0.0F) return true;
    try {
        const auto coordinates = target.tileCoordinates();
        const auto bpp = target.bytes_per_pixel();
        std::atomic<bool> failed{false};
        parallel::parallelFor(coordinates.size(), parallel::workerCount(worker_threads), [&](std::size_t item, std::size_t) {
            const auto coordinate = coordinates[item];
            const auto mask_tile = mask != nullptr ? mask->readTile(coordinate) : nullptr;
            if (mask != nullptr && mask_tile == nullptr) return;
            auto tile = *target.readTile(coordinate);
            for (std::size_t i = 0; i < kTileEdge * kTileEdge; ++i) {
                float amount = strength;
                if (mask_tile != nullptr) {
                    std::uint16_t raw = 0;
                    std::memcpy(&raw, mask_tile->data() + i * 2U, 2U);
                    if (raw == 0U) continue;
                    amount *= raw / 65535.0F;
                }
                auto* p = tile.data() + i * bpp;
                std::array<float, 4> px{};
                paint::detail::readPixel(target.format(), p, px.data());
                if (px[3] <= 0.0F) continue;
                const auto mapped = lut.apply({px[0], px[1], px[2]});
                for (std::size_t c = 0; c < 3; ++c) px[c] += amount * (mapped[c] - px[c]);
                paint::detail::writePixel(target.format(), p, px.data());
            }
            if (!target.writeTile(coordinate, tile)) failed.store(true);
        });
        if (failed.load()) {
            error = "could not store the graded pixels";
            return false;
        }
        return true;
    } catch (const std::exception&) {
        error = "could not allocate LUT buffers";
        return false;
    }
}

}  // namespace croptop::color
