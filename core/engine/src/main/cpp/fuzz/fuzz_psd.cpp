// LibFuzzer target: PSD/PSB record reader plus the layered import that builds the layer tree.
// Oracle: every decoded layer raster is canvas-sized.
#include <cstddef>
#include <cstdint>
#include <span>
#include <string>

#include "psd_import.h"
#include "psd_reader.h"

extern "C" int LLVMFuzzerTestOneInput(const std::uint8_t* data, std::size_t size) {
    const auto file = std::span<const std::uint8_t>(data, size);
    croptop::psd::Limits limits;
    limits.max_working_bytes = 64ULL << 20;
    limits.max_canvas_pixels = 1ULL << 22;
    std::string error;
    const auto doc = croptop::psd::read(file, error, limits);
    if (!doc) return 0;
    for (const auto& layer : doc->layers) {
        if (layer.pixels && (layer.pixels->width() != doc->width || layer.pixels->height() != doc->height)) __builtin_trap();
    }
    const croptop::TileStore pixel_template(croptop::PixelFormat::RGBA8, doc->width, doc->height);
    const croptop::TileStore mask_template(croptop::PixelFormat::A16, doc->width, doc->height);
    (void)croptop::psd::importLayers(file, pixel_template, &mask_template, error);
    return 0;
}
