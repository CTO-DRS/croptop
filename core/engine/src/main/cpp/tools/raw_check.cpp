// Host CLI for camera-raw compatibility checks: opens one raw file the way the app does and
// prints a single JSON object with the result. tools/raw_compat.py drives it over the sample files of core/raw/CAMERAS.md.
//   croptop_raw_check [--quick] FILE     --quick: probe + embedded preview only (no demosaic/develop)
#include <chrono>
#include <cstdint>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <fstream>
#include <iterator>
#include <string>
#include <vector>

#include "raw_open.h"

namespace {

using Clock = std::chrono::steady_clock;

double millisSince(Clock::time_point start) { return std::chrono::duration<double, std::milli>(Clock::now() - start).count(); }

std::string escaped(const std::string& text) {
    std::string out;
    for (const unsigned char c : text) {
        if (c == '"' || c == '\\') {
            out += '\\';
            out += static_cast<char>(c);
        } else if (c < 0x20) {
            char buffer[8];
            std::snprintf(buffer, sizeof(buffer), "\\u%04x", c);
            out += buffer;
        } else {
            out += static_cast<char>(c);
        }
    }
    return out;
}

void field(const char* key, const std::string& value, bool& first) {
    std::printf("%s\"%s\":\"%s\"", first ? "" : ",", key, escaped(value).c_str());
    first = false;
}
void field(const char* key, double value, bool& first) {
    std::printf("%s\"%s\":%.4g", first ? "" : ",", key, value);
    first = false;
}
void field(const char* key, std::uint32_t value, bool& first) {
    std::printf("%s\"%s\":%u", first ? "" : ",", key, value);
    first = false;
}
void field(const char* key, bool value, bool& first) {
    std::printf("%s\"%s\":%s", first ? "" : ",", key, value ? "true" : "false");
    first = false;
}

}  // namespace

int main(int argc, char** argv) {
    bool quick = false;
    const char* path = nullptr;
    for (int i = 1; i < argc; ++i) {
        if (std::strcmp(argv[i], "--quick") == 0) quick = true;
        else path = argv[i];
    }
    if (path == nullptr) {
        std::fprintf(stderr, "usage: croptop_raw_check [--quick] FILE\n");
        return 2;
    }
    std::ifstream file(path, std::ios::binary);
    const std::vector<std::uint8_t> bytes((std::istreambuf_iterator<char>(file)), std::istreambuf_iterator<char>());
    bool first = true;
    std::printf("{");
    field("bytes", static_cast<double>(bytes.size()), first);
    std::string error;
    auto start = Clock::now();
    const auto probe = croptop::raw::probe(bytes, error);
    if (!probe) {
        field("ok", false, first);
        field("stage", std::string("probe"), first);
        field("error", error, first);
        std::printf("}\n");
        return 1;
    }
    field("probeMs", millisSince(start), first);
    field("make", probe->metadata.make, first);
    field("model", probe->metadata.model, first);
    field("lens", probe->metadata.lens, first);
    field("width", probe->width, first);
    field("height", probe->height, first);
    field("xtrans", probe->xtrans, first);
    field("linear", probe->linear, first);
    field("isDng", probe->metadata.is_dng, first);
    field("decodable", probe->decodable, first);
    field("unsupportedReason", probe->unsupported_reason, first);

    error.clear();
    start = Clock::now();
    const auto preview = croptop::raw::extractPreview(bytes, error, 0);
    field("previewOk", preview.has_value(), first);
    if (preview) {
        field("previewMs", millisSince(start), first);
        field("previewWidth", preview->width, first);
        field("previewHeight", preview->height, first);
    } else {
        field("previewError", error, first);
    }

    int exit_code = 0;
    if (!quick && probe->decodable) {
        error.clear();
        start = Clock::now();
        croptop::raw::OpenOptions options;
        options.sixteen_bit = false;
        const auto opened = croptop::raw::openFlattened(bytes, options, error);
        field("openOk", opened.has_value(), first);
        if (opened) {
            field("openMs", millisSince(start), first);
            field("openWidth", opened->pixels.width(), first);
            field("openHeight", opened->pixels.height(), first);
            // Mean brightness of a coarse grid: a black or blown-out result means a level or white-balance failure.
            double sum = 0;
            std::size_t samples = 0;
            for (std::uint32_t y = 16; y < opened->pixels.height(); y += opened->pixels.height() / 16 + 1) {
                for (std::uint32_t x = 16; x < opened->pixels.width(); x += opened->pixels.width() / 16 + 1) {
                    const auto tile = opened->pixels.readTile({static_cast<std::int32_t>(x / croptop::kTileEdge), static_cast<std::int32_t>(y / croptop::kTileEdge)});
                    if (tile == nullptr) continue;
                    const std::size_t at = (static_cast<std::size_t>(y % croptop::kTileEdge) * croptop::kTileEdge + x % croptop::kTileEdge) * 4;
                    sum += ((*tile)[at] + (*tile)[at + 1] + (*tile)[at + 2]) / (3.0 * 255.0);
                    ++samples;
                }
            }
            const double mean = samples > 0 ? sum / static_cast<double>(samples) : 0.0;
            field("meanBrightness", mean, first);
            if (mean < 0.02 || mean > 0.98) exit_code = 3;
        } else {
            field("openError", error, first);
            exit_code = 2;
        }
    }
    field("ok", exit_code == 0, first);
    std::printf("}\n");
    return exit_code;
}
