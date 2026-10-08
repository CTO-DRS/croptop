// Lens-profile correction from lensfun-format databases.
#include "lens_profile.h"

#include <algorithm>
#include <atomic>
#include <cctype>
#include <cmath>
#include <map>

#include "parallel_util.h"
#include "pixel_plane.h"

namespace croptop::lens {
namespace {

constexpr std::size_t kMaxXml = 32U << 20U;
const double kFullFrameDiagonal = std::hypot(36.0, 24.0);

/** Locale-independent decimal parser; nullopt unless the whole string is a finite number. */
std::optional<float> number(std::string_view s) {
    while (!s.empty() && std::isspace(static_cast<unsigned char>(s.front()))) s.remove_prefix(1);
    while (!s.empty() && std::isspace(static_cast<unsigned char>(s.back()))) s.remove_suffix(1);
    if (s.empty() || s.size() > 48) return std::nullopt;
    std::size_t i = 0;
    double sign = 1, value = 0;
    if (s[i] == '+' || s[i] == '-') sign = s[i++] == '-' ? -1 : 1;
    bool digits = false;
    while (i < s.size() && std::isdigit(static_cast<unsigned char>(s[i]))) {
        value = value * 10 + (s[i++] - '0');
        digits = true;
    }
    if (i < s.size() && s[i] == '.') {
        double scale = 0.1;
        for (++i; i < s.size() && std::isdigit(static_cast<unsigned char>(s[i])); ++i, scale *= 0.1) {
            value += (s[i] - '0') * scale;
            digits = true;
        }
    }
    if (i < s.size() && (s[i] == 'e' || s[i] == 'E')) {
        ++i;
        int esign = 1, exponent = 0;
        if (i < s.size() && (s[i] == '+' || s[i] == '-')) esign = s[i++] == '-' ? -1 : 1;
        bool edigits = false;
        while (i < s.size() && std::isdigit(static_cast<unsigned char>(s[i])) && exponent < 400) {
            exponent = exponent * 10 + (s[i++] - '0');
            edigits = true;
        }
        if (!edigits) return std::nullopt;
        value *= std::pow(10.0, esign * exponent);
    }
    if (!digits || i != s.size()) return std::nullopt;
    const auto result = static_cast<float>(sign * value);
    return std::isfinite(result) ? std::optional<float>(result) : std::nullopt;
}

std::string normalise(std::string_view s) {
    std::string out;
    bool space = false;
    for (const char c : s) {
        if (std::isspace(static_cast<unsigned char>(c))) {
            space = !out.empty();
            continue;
        }
        if (space) out += ' ';
        space = false;
        out += static_cast<char>(std::tolower(static_cast<unsigned char>(c)));
    }
    return out;
}

/**
 * Name tokens for matching. Runs of letters and runs of numbers (keeping the '.' and '-' inside them, as in "12-50" or
 * "3.5-6.3") are separate tokens, so "f/2.8", "F2.8" and "f2.8" all give {"f", "2.8"}, "55-250mm" gives {"55-250",
 * "mm"} and "M.Zuiko" gives {"m", "zuiko"}. Camera and lens strings from raw files and from the database spell the same
 * optic differently; comparing tokens instead of characters absorbs that.
 */
std::vector<std::string> tokens(std::string_view s) {
    std::vector<std::string> out;
    const auto text = normalise(s);
    std::size_t i = 0;
    const auto digit = [&](std::size_t k) { return k < text.size() && std::isdigit(static_cast<unsigned char>(text[k])) != 0; };
    const auto letter = [&](std::size_t k) { return k < text.size() && std::isalpha(static_cast<unsigned char>(text[k])) != 0; };
    while (i < text.size()) {
        std::size_t j = i;
        if (letter(i)) {
            while (letter(j)) ++j;
        } else if (digit(i)) {
            while (digit(j) || ((text[j] == '.' || text[j] == '-') && digit(j + 1))) ++j;
        } else {
            ++i;
            continue;
        }
        out.push_back(text.substr(i, j - i));
        i = j;
    }
    return out;
}

/** True when one maker's tokens begin the other's: "Nikon" ~ "Nikon Corporation", "Olympus" ~ "Olympus Imaging Corp.". */
bool sameMaker(const std::vector<std::string>& a, const std::vector<std::string>& b) {
    if (a.empty() || b.empty()) return true;
    const auto& shorter = a.size() <= b.size() ? a : b;
    const auto& longer = a.size() <= b.size() ? b : a;
    return std::equal(shorter.begin(), shorter.end(), longer.begin());
}

/** Attributes of one start tag body (`name="v" name2='w'`), values entity-light (lensfun uses plain numbers). */
std::map<std::string, std::string, std::less<>> attributes(std::string_view tag) {
    std::map<std::string, std::string, std::less<>> out;
    std::size_t i = 0;
    while (i < tag.size() && out.size() < 64) {
        while (i < tag.size() && (std::isspace(static_cast<unsigned char>(tag[i])) || tag[i] == '/')) ++i;
        const std::size_t a = i;
        while (i < tag.size() && (std::isalnum(static_cast<unsigned char>(tag[i])) || tag[i] == '-' || tag[i] == '_' || tag[i] == ':')) ++i;
        if (i == a) break;
        const std::string key(tag.substr(a, i - a));
        while (i < tag.size() && std::isspace(static_cast<unsigned char>(tag[i]))) ++i;
        if (i >= tag.size() || tag[i] != '=') break;
        ++i;
        while (i < tag.size() && std::isspace(static_cast<unsigned char>(tag[i]))) ++i;
        if (i >= tag.size() || (tag[i] != '"' && tag[i] != '\'')) break;
        const char quote = tag[i++];
        const auto close = tag.find(quote, i);
        if (close == std::string_view::npos) break;
        out[key] = std::string(tag.substr(i, close - i));
        i = close + 1;
    }
    return out;
}

/** Text of the first `<name>` child (preferring one without a lang attribute, then lang="en"). */
std::string childText(std::string_view block, std::string_view name) {
    std::string best;
    int best_rank = 99;
    const std::string open = "<" + std::string(name), close = "</" + std::string(name) + ">";
    for (std::size_t p = block.find(open); p != std::string_view::npos; p = block.find(open, p + 1)) {
        const auto after = p + open.size();
        if (after >= block.size() || (block[after] != '>' && block[after] != ' ')) continue;
        const auto gt = block.find('>', after);
        const auto end = gt == std::string_view::npos ? gt : block.find(close, gt);
        if (end == std::string_view::npos) break;
        const auto attrs = attributes(block.substr(after, gt - after));
        const auto lang = attrs.find("lang");
        const int rank = lang == attrs.end() ? 0 : lang->second == "en" ? 1 : 2;
        if (rank < best_rank) {
            best_rank = rank;
            best = std::string(block.substr(gt + 1, end - gt - 1));
        }
    }
    return normalise(best).empty() ? std::string{} : std::string(best);
}

float attr(const std::map<std::string, std::string, std::less<>>& a, std::string_view key, float fallback) {
    const auto it = a.find(key);
    if (it == a.end()) return fallback;
    return number(it->second).value_or(fallback);
}

/** Each `<tag ...>...</tag>` block (bounded count). */
std::vector<std::string_view> blocks(std::string_view xml, std::string_view tag) {
    std::vector<std::string_view> out;
    const std::string open = "<" + std::string(tag), close = "</" + std::string(tag) + ">";
    for (std::size_t p = xml.find(open); p != std::string_view::npos && out.size() < 100000;) {
        const auto after = p + open.size();
        if (after < xml.size() && (xml[after] == '>' || std::isspace(static_cast<unsigned char>(xml[after])))) {
            const auto end = xml.find(close, after);
            if (end == std::string_view::npos) break;
            out.push_back(xml.substr(after, end - after));
            p = xml.find(open, end + close.size());
        } else {
            p = xml.find(open, after);
        }
    }
    return out;
}

/** Self-closing calibration tags such as `<distortion model="ptlens" focal="50" a=".." />`. */
std::vector<std::map<std::string, std::string, std::less<>>> calibrationTags(std::string_view block, std::string_view tag) {
    std::vector<std::map<std::string, std::string, std::less<>>> out;
    const std::string open = "<" + std::string(tag);
    for (std::size_t p = block.find(open); p != std::string_view::npos && out.size() < 4096; p = block.find(open, p + 1)) {
        const auto after = p + open.size();
        if (after >= block.size() || !std::isspace(static_cast<unsigned char>(block[after]))) continue;
        const auto gt = block.find('>', after);
        if (gt == std::string_view::npos) break;
        out.push_back(attributes(block.substr(after, gt - after)));
    }
    return out;
}

float aspectOf(const std::string& text) {
    if (text.empty()) return 1.5F;
    const auto colon = text.find(':');
    float a = 1.5F;
    if (colon != std::string::npos) {
        const auto w = number(std::string_view(text).substr(0, colon)), h = number(std::string_view(text).substr(colon + 1));
        if (w && h && *w > 0 && *h > 0) a = *w / *h;
    } else if (const auto v = number(text)) {
        a = *v;
    }
    if (!(a > 0)) return 1.5F;
    return std::clamp(a < 1.0F ? 1.0F / a : a, 1.0F, 4.0F);
}

template <typename T>
std::pair<const T*, const T*> bracket(const std::vector<T>& sorted, float focal, float& t) {
    t = 0;
    if (sorted.empty()) return {nullptr, nullptr};
    if (focal <= sorted.front().focal) return {&sorted.front(), &sorted.front()};
    if (focal >= sorted.back().focal) return {&sorted.back(), &sorted.back()};
    for (std::size_t i = 0; i + 1 < sorted.size(); ++i) {
        if (focal >= sorted[i].focal && focal <= sorted[i + 1].focal) {
            const float span = sorted[i + 1].focal - sorted[i].focal;
            t = span > 0 ? (focal - sorted[i].focal) / span : 0.0F;
            return {&sorted[i], &sorted[i + 1]};
        }
    }
    return {&sorted.back(), &sorted.back()};
}

std::array<float, 3> lerp3(const std::array<float, 3>& a, const std::array<float, 3>& b, float t) {
    return {a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t, a[2] + (b[2] - a[2]) * t};
}

/** Source radius (Hugin units) for corrected radius r. */
double distort(const DistortionCalibration& d, double r) {
    switch (d.model) {
        case DistortionModel::None: return r;
        case DistortionModel::Poly3: {
            const double k1 = d.k[0], s = 1.0 - k1, x = r / s;
            return x * (s + k1 * x * x);
        }
        case DistortionModel::Poly5: return r * (1.0 + d.k[0] * r * r + d.k[1] * r * r * r * r);
        case DistortionModel::PTLens: {
            const double a = d.k[0], b = d.k[1], c = d.k[2], s = 1.0 - a - b - c, x = r / s;
            return x * (a * x * x * x + b * x * x + c * x + s);
        }
    }
    return r;
}

double tcaScale(TcaModel model, const std::array<float, 3>& k, double r) {
    switch (model) {
        case TcaModel::None: return 1.0;
        case TcaModel::Linear: return k[0];
        case TcaModel::Poly3: return k[2] * r * r + k[1] * r + k[0];  // v + c·r + b·r²
    }
    return 1.0;
}

}  // namespace

bool Database::addXml(std::string_view xml, std::string& error) {
    if (xml.size() > kMaxXml) {
        error = "lens database file is too large";
        return false;
    }
    if (xml.find("<lensdatabase") == std::string_view::npos) {
        error = "not a lensfun database";
        return false;
    }
    for (const auto block : blocks(xml, "camera")) {
        CameraEntry c;
        c.maker = childText(block, "maker");
        c.model = childText(block, "model");
        c.mount = childText(block, "mount");
        c.crop_factor = number(childText(block, "cropfactor")).value_or(1.0F);
        if (c.model.empty() || !(c.crop_factor > 0.1F && c.crop_factor < 20.0F)) continue;
        cameras.push_back(std::move(c));
    }
    for (const auto block : blocks(xml, "lens")) {
        LensEntry l;
        l.maker = childText(block, "maker");
        l.model = childText(block, "model");
        l.mount = childText(block, "mount");
        l.crop_factor = number(childText(block, "cropfactor")).value_or(1.0F);
        l.aspect_ratio = aspectOf(childText(block, "aspect-ratio"));
        if (l.model.empty() || !(l.crop_factor > 0.1F && l.crop_factor < 20.0F)) continue;
        for (const auto& a : calibrationTags(block, "distortion")) {
            DistortionCalibration d;
            d.focal = attr(a, "focal", 0);
            const auto model = a.count("model") != 0U ? a.at("model") : std::string{};
            if (model == "poly3") {
                d.model = DistortionModel::Poly3;
                d.k = {attr(a, "k1", 0), 0, 0};
            } else if (model == "poly5") {
                d.model = DistortionModel::Poly5;
                d.k = {attr(a, "k1", 0), attr(a, "k2", 0), 0};
            } else if (model == "ptlens") {
                d.model = DistortionModel::PTLens;
                d.k = {attr(a, "a", 0), attr(a, "b", 0), attr(a, "c", 0)};
            } else {
                continue;
            }
            // The PanoTools forms divide by d = 1 − Σk; reject degenerate calibrations.
            const float d_term = d.model == DistortionModel::Poly3 ? 1.0F - d.k[0] : d.model == DistortionModel::PTLens ? 1.0F - d.k[0] - d.k[1] - d.k[2] : 1.0F;
            if (d.focal > 0 && std::abs(d_term) > 0.05F && std::all_of(d.k.begin(), d.k.end(), [](float v) { return std::abs(v) < 10.0F; })) l.distortion.push_back(d);
        }
        for (const auto& a : calibrationTags(block, "tca")) {
            TcaCalibration t;
            t.focal = attr(a, "focal", 0);
            const auto model = a.count("model") != 0U ? a.at("model") : std::string{};
            if (model == "linear") {
                t.model = TcaModel::Linear;
                t.red = {attr(a, "kr", 1), 0, 0};
                t.blue = {attr(a, "kb", 1), 0, 0};
            } else if (model == "poly3") {
                t.model = TcaModel::Poly3;
                t.red = {attr(a, "vr", 1), attr(a, "cr", 0), attr(a, "br", 0)};
                t.blue = {attr(a, "vb", 1), attr(a, "cb", 0), attr(a, "bb", 0)};
            } else {
                continue;
            }
            if (t.focal > 0 && std::abs(t.red[0] - 1.0F) < 0.1F && std::abs(t.blue[0] - 1.0F) < 0.1F) l.tca.push_back(t);
        }
        for (const auto& a : calibrationTags(block, "vignetting")) {
            if (a.count("model") == 0U || a.at("model") != "pa") continue;
            VignettingCalibration v{attr(a, "focal", 0), attr(a, "aperture", 0), attr(a, "distance", 0), {attr(a, "k1", 0), attr(a, "k2", 0), attr(a, "k3", 0)}};
            if (v.focal > 0 && v.aperture > 0 && std::all_of(v.k.begin(), v.k.end(), [](float x) { return std::abs(x) < 10.0F; })) l.vignetting.push_back(v);
        }
        const auto by_focal = [](const auto& x, const auto& y) { return x.focal < y.focal; };
        std::sort(l.distortion.begin(), l.distortion.end(), by_focal);
        std::sort(l.tca.begin(), l.tca.end(), by_focal);
        lenses.push_back(std::move(l));
    }
    return true;
}

namespace {

template <typename Entry>
const Entry* bestMatch(const std::vector<Entry>& entries, std::string_view maker, std::string_view model) {
    const auto want_maker = tokens(maker);
    auto want = tokens(model);
    // Raw files often prefix the lens with the maker ("OLYMPUS M.12-50mm ..."); the database keeps it in <maker>.
    want.erase(std::remove_if(want.begin(), want.end(), [&](const std::string& t) {
                   return std::find(want_maker.begin(), want_maker.end(), t) != want_maker.end();
               }), want.end());
    if (want.empty()) return nullptr;
    const Entry* best = nullptr;
    double best_score = 0;
    for (const auto& e : entries) {
        if (!sameMaker(want_maker, tokens(e.maker))) continue;
        const auto have = tokens(e.model);
        std::size_t matched = 0;
        for (const auto& w : want) matched += std::find(have.begin(), have.end(), w) != have.end() ? 1U : 0U;
        if (matched != want.size()) continue;  // every requested token must be present
        const double score = static_cast<double>(matched) / static_cast<double>(have.size());
        if (score > best_score) {
            best_score = score;
            best = &e;
        }
    }
    return best;
}

}  // namespace

const LensEntry* Database::findLens(std::string_view maker, std::string_view model) const { return bestMatch(lenses, maker, model); }
const CameraEntry* Database::findCamera(std::string_view maker, std::string_view model) const { return bestMatch(cameras, maker, model); }

Correction interpolate(const LensEntry& lens, float focal, float aperture) {
    Correction c;
    c.calibration_crop = lens.crop_factor;
    c.calibration_aspect = lens.aspect_ratio;
    float t = 0;
    if (const auto [a, b] = bracket(lens.distortion, focal, t); a != nullptr) {
        c.distortion = a->model == b->model ? DistortionCalibration{focal, a->model, lerp3(a->k, b->k, t)} : (t < 0.5F ? *a : *b);
    }
    if (const auto [a, b] = bracket(lens.tca, focal, t); a != nullptr) {
        c.tca = a->model == b->model ? TcaCalibration{focal, a->model, lerp3(a->red, b->red, t), lerp3(a->blue, b->blue, t)} : (t < 0.5F ? *a : *b);
    }
    if (!lens.vignetting.empty()) {
        // Use the farthest calibrated distance, then interpolate in focal length and aperture.
        float far = 0;
        for (const auto& v : lens.vignetting) far = std::max(far, v.distance);
        std::vector<VignettingCalibration> set;
        for (const auto& v : lens.vignetting)
            if (v.distance == far) set.push_back(v);
        std::vector<float> focals;
        for (const auto& v : set) focals.push_back(v.focal);
        std::sort(focals.begin(), focals.end());
        focals.erase(std::unique(focals.begin(), focals.end()), focals.end());
        const auto atFocal = [&](float f) {
            std::vector<VignettingCalibration> row;
            for (const auto& v : set)
                if (v.focal == f) row.push_back(v);
            std::sort(row.begin(), row.end(), [](const auto& x, const auto& y) { return x.aperture < y.aperture; });
            if (aperture <= row.front().aperture) return row.front().k;
            if (aperture >= row.back().aperture) return row.back().k;
            for (std::size_t i = 0; i + 1 < row.size(); ++i)
                if (aperture >= row[i].aperture && aperture <= row[i + 1].aperture) {
                    const float span = row[i + 1].aperture - row[i].aperture;
                    return lerp3(row[i].k, row[i + 1].k, span > 0 ? (aperture - row[i].aperture) / span : 0.0F);
                }
            return row.back().k;
        };
        std::array<float, 3> k{};
        if (focal <= focals.front()) {
            k = atFocal(focals.front());
        } else if (focal >= focals.back()) {
            k = atFocal(focals.back());
        } else {
            for (std::size_t i = 0; i + 1 < focals.size(); ++i)
                if (focal >= focals[i] && focal <= focals[i + 1]) {
                    k = lerp3(atFocal(focals[i]), atFocal(focals[i + 1]), (focal - focals[i]) / (focals[i + 1] - focals[i]));
                    break;
                }
        }
        c.vignetting = VignettingCalibration{focal, aperture, far, k};
    }
    return c;
}

std::optional<TileStore> apply(const TileStore& layer, const Correction& c, const ApplyOptions& o, std::string& error) {
    if (!paint::detail::isRgba(layer.format())) {
        error = "lens correction needs an RGBA layer";
        return std::nullopt;
    }
    if (!std::isfinite(o.image_crop_factor) || o.image_crop_factor < 0.1F || o.image_crop_factor > 20.0F || !(c.calibration_crop > 0.1F) ||
        !(c.calibration_aspect >= 1.0F)) {
        error = "crop factors must be within 0.1–20";
        return std::nullopt;
    }
    const double W = layer.width(), H = layer.height();
    const double cx = W * 0.5, cy = H * 0.5;
    const double mm_per_px = kFullFrameDiagonal / o.image_crop_factor / std::hypot(W, H);
    const double hugin_mm = kFullFrameDiagonal / c.calibration_crop / std::hypot(static_cast<double>(c.calibration_aspect), 1.0) / 2.0;
    const double corner_mm = kFullFrameDiagonal / c.calibration_crop / 2.0;
    const double to_hugin = mm_per_px / hugin_mm, to_corner = mm_per_px / corner_mm;
    const bool use_distortion = o.distortion && c.distortion.model != DistortionModel::None;
    const bool use_tca = o.tca && c.tca.model != TcaModel::None;
    const bool use_vignetting = o.vignetting && c.vignetting.has_value();

    // Source position (pixels) of each channel for corrected pixel centre (x, y).
    const auto sourceOf = [&](double x, double y, std::array<std::array<double, 2>, 3>& src, double& vignette) {
        const double dx = x - cx, dy = y - cy;
        const double r = std::hypot(dx, dy) * to_hugin;
        double scale = 1.0;
        if (use_distortion && r > 0) scale = distort(c.distortion, r) / r;
        const double gx = dx * scale, gy = dy * scale;
        const double rg = r * scale;
        for (int ch = 0; ch < 3; ++ch) {
            double s = 1.0;
            if (use_tca && ch != 1) s = tcaScale(c.tca.model, ch == 0 ? c.tca.red : c.tca.blue, rg);
            src[static_cast<std::size_t>(ch)] = {cx + gx * s, cy + gy * s};
        }
        vignette = 1.0;
        if (use_vignetting) {
            const double rv = std::hypot(gx, gy) * to_corner, r2 = rv * rv;
            const auto& k = c.vignetting->k;
            vignette = 1.0 + k[0] * r2 + k[1] * r2 * r2 + k[2] * r2 * r2 * r2;
        }
    };

    TileStore out(layer.format(), layer.width(), layer.height());
    const auto columns = static_cast<std::size_t>((layer.width() + kTileEdge - 1) / kTileEdge);
    const auto rows = static_cast<std::size_t>((layer.height() + kTileEdge - 1) / kTileEdge);
    std::atomic<bool> failed{false};
    parallel::parallelFor(columns * rows, parallel::workerCount(o.worker_threads), [&](std::size_t index, std::size_t) {
        if (failed.load()) return;
        const auto tx = static_cast<std::int64_t>(index % columns), ty = static_cast<std::int64_t>(index / columns);
        const std::int64_t x0 = tx * kTileEdge, y0 = ty * kTileEdge;
        const std::int64_t x1 = std::min<std::int64_t>(x0 + kTileEdge, layer.width()), y1 = std::min<std::int64_t>(y0 + kTileEdge, layer.height());
        // Source window: the map is monotonic in radius, so the tile border bounds it (sampled every 8 px).
        double sx0 = 1e30, sy0 = 1e30, sx1 = -1e30, sy1 = -1e30;
        std::array<std::array<double, 2>, 3> src{};
        double vig = 1.0;
        const auto extend = [&](double x, double y) {
            sourceOf(x, y, src, vig);
            for (const auto& p : src) {
                if (!std::isfinite(p[0]) || !std::isfinite(p[1])) continue;
                sx0 = std::min(sx0, p[0]);
                sy0 = std::min(sy0, p[1]);
                sx1 = std::max(sx1, p[0]);
                sy1 = std::max(sy1, p[1]);
            }
        };
        for (std::int64_t k = x0; k <= x1; k += 8) {
            extend(static_cast<double>(k), static_cast<double>(y0));
            extend(static_cast<double>(k), static_cast<double>(y1));
        }
        for (std::int64_t k = y0; k <= y1; k += 8) {
            extend(static_cast<double>(x0), static_cast<double>(k));
            extend(static_cast<double>(x1), static_cast<double>(k));
        }
        extend(static_cast<double>(x1), static_cast<double>(y1));
        if (cx >= static_cast<double>(x0) && cx <= static_cast<double>(x1) && cy >= static_cast<double>(y0) && cy <= static_cast<double>(y1)) extend(cx, cy);
        const int wx0 = static_cast<int>(std::clamp(std::floor(sx0) - 3.0, -1.0, W)), wy0 = static_cast<int>(std::clamp(std::floor(sy0) - 3.0, -1.0, H));
        const int wx1 = static_cast<int>(std::clamp(std::ceil(sx1) + 3.0, -1.0, W + 1)), wy1 = static_cast<int>(std::clamp(std::ceil(sy1) + 3.0, -1.0, H + 1));
        if (wx1 <= wx0 || wy1 <= wy0) return;  // maps entirely outside: transparent tile
        // Normal profiles displace by a few percent; a pathological map could span the whole image, so the
        // dense window is capped and anything larger falls back to direct tile reads.
        const bool dense = static_cast<std::int64_t>(wx1 - wx0) * (wy1 - wy0) <= 2048LL * 2048LL;
        const auto plane = dense ? paint::detail::readPlane(layer, wx0, wy0, wx1 - wx0, wy1 - wy0) : paint::detail::Plane{};
        std::shared_ptr<const TileBuffer> cached;
        std::int64_t cached_key = -1;
        const auto direct = [&](int px, int py, float* out) {
            out[0] = out[1] = out[2] = out[3] = 0.0F;
            if (px < 0 || py < 0 || px >= static_cast<int>(W) || py >= static_cast<int>(H)) return;
            const std::int64_t key = static_cast<std::int64_t>(py / static_cast<int>(kTileEdge)) * 65536 + px / static_cast<int>(kTileEdge);
            if (key != cached_key) {
                cached = layer.readTile({px / static_cast<std::int32_t>(kTileEdge), py / static_cast<std::int32_t>(kTileEdge)});
                cached_key = key;
            }
            if (cached) paint::detail::readPixel(layer.format(), cached->data() + (static_cast<std::size_t>(py % kTileEdge) * kTileEdge + static_cast<std::size_t>(px % kTileEdge)) * layer.bytes_per_pixel(), out);
        };
        const auto sample = [&](double x, double y, int ch, float& alpha) {
            // Bilinear at pixel-centre coordinates.
            const double fx = x - 0.5 - wx0, fy = y - 0.5 - wy0;
            if (!(std::abs(fx) < 1e7 && std::abs(fy) < 1e7)) {  // also rejects NaN from degenerate profiles
                alpha = 0.0F;
                return 0.0F;
            }
            const int ix = static_cast<int>(std::floor(fx)), iy = static_cast<int>(std::floor(fy));
            const float ax = static_cast<float>(fx - ix), ay = static_cast<float>(fy - iy);
            float value = 0, a = 0;
            for (int j = 0; j < 2; ++j)
                for (int i = 0; i < 2; ++i) {
                    const int px = ix + i, py = iy + j;
                    const float w = (i ? ax : 1 - ax) * (j ? ay : 1 - ay);
                    float buffer[4];
                    const float* p = buffer;
                    if (dense) {
                        if (px < 0 || py < 0 || px >= plane.w || py >= plane.h) continue;
                        p = plane.at(px, py);
                    } else {
                        direct(px + wx0, py + wy0, buffer);
                    }
                    value += w * p[ch] * p[3];  // premultiplied so transparent neighbours do not darken edges
                    a += w * p[3];
                }
            alpha = a;
            return a > 0 ? value / a : 0.0F;
        };
        TileBuffer tile(out.tile_byte_size(), 0);
        const auto bpp = out.bytes_per_pixel();
        for (std::int64_t y = y0; y < y1; ++y)
            for (std::int64_t x = x0; x < x1; ++x) {
                sourceOf(static_cast<double>(x) + 0.5, static_cast<double>(y) + 0.5, src, vig);
                float px[4], alpha[3];
                for (int ch = 0; ch < 3; ++ch) px[ch] = sample(src[static_cast<std::size_t>(ch)][0], src[static_cast<std::size_t>(ch)][1], ch, alpha[ch]);
                px[3] = alpha[1];
                const float gain = vig > 0.05 ? static_cast<float>(1.0 / vig) : 20.0F;
                for (int ch = 0; ch < 3; ++ch) px[ch] *= gain;
                paint::detail::writePixel(out.format(), tile.data() + (static_cast<std::size_t>(y - y0) * kTileEdge + static_cast<std::size_t>(x - x0)) * bpp, px);
            }
        if (!out.writeTile({static_cast<std::int32_t>(tx), static_cast<std::int32_t>(ty)}, tile)) failed.store(true);
    });
    if (failed.load()) {
        error = "could not store corrected pixels";
        return std::nullopt;
    }
    return out;
}

}  // namespace croptop::lens
