// LibFuzzer target: embedded ICC profiles, used both ways in a conversion and for a display LUT.
#include <cstddef>
#include <cstdint>
#include <span>
#include <string>

#include "color_management.h"

extern "C" int LLVMFuzzerTestOneInput(const std::uint8_t* data, std::size_t size) {
    namespace cms = croptop::cms;
    std::string error;
    const auto profile = cms::Profile::fromIcc(std::span<const std::uint8_t>(data, size), error);
    if (!profile || profile->model() != cms::ColorModel::RGB) return 0;
    const auto srgb = cms::Profile::builtin(cms::WorkingSpace::SRGB);
    croptop::TileStore layer(croptop::PixelFormat::RGBA16, 16, 16);
    croptop::TileBuffer tile(layer.tile_byte_size(), 0);
    for (std::size_t i = 0; i < tile.size(); ++i) tile[i] = static_cast<std::uint8_t>(i * 37);
    (void)layer.writeTile({0, 0}, tile);
    cms::ConvertOptions options;
    options.worker_threads = 1;
    for (const auto format : {croptop::PixelFormat::RGBA8, croptop::PixelFormat::RGBA16, croptop::PixelFormat::RGBA32F}) {
        (void)cms::convert(layer, *profile, srgb, format, options, error);
        (void)cms::convert(layer, srgb, *profile, format, options, error);
    }
    (void)cms::bakeDisplayLut(*profile, srgb, 5, {}, error);
    return 0;
}
