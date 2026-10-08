// Bounded hostile-input harness for the native .croptop archive reader.
#include "croptop_format.h"

#include <algorithm>
#include <cstddef>
#include <cstdint>
#include <span>
#include <string>
#include <utility>
#include <vector>

namespace {

constexpr std::size_t kMaxInputBytes = 1U * 1024U * 1024U;
constexpr std::uint32_t kMaxLayerDimension = 256;
constexpr std::size_t kMaxLoadedLayers = 8;

}  // namespace

extern "C" int LLVMFuzzerTestOneInput(const std::uint8_t* data, std::size_t size) {
    if (size == 0U || size > kMaxInputBytes) return 0;

    const auto bytes = std::span<const std::uint8_t>(data, size);
    croptop::CroptopReadSource source{
        static_cast<std::uint64_t>(bytes.size()),
        [bytes](std::uint64_t offset, std::span<std::uint8_t> output) {
            if (offset > bytes.size() || output.size() > bytes.size() - static_cast<std::size_t>(offset)) return false;
            std::copy_n(bytes.data() + static_cast<std::size_t>(offset), output.size(), output.data());
            return true;
        },
    };

    croptop::CroptopLimits limits;
    limits.max_entries = 64;
    limits.max_central_directory_bytes = 512U * 1024U;
    limits.max_manifest_bytes = 64U * 1024U;
    limits.max_icc_bytes = 16U * 1024U;
    limits.max_preview_bytes = 64U * 1024U;
    limits.max_embedded_bytes = 64U * 1024U;
    limits.max_dimension = kMaxLayerDimension;

    std::string error;
    const auto archive = croptop::CroptopArchive::open(std::move(source), error, limits);
    if (!archive) return 0;

    std::vector<std::uint8_t> manifest;
    if (archive->readManifest(manifest, error) && manifest.size() > limits.max_manifest_bytes) __builtin_trap();

    std::vector<std::uint8_t> icc;
    if (archive->readWorkingIcc(icc, error) && icc.size() > limits.max_icc_bytes) __builtin_trap();

    for (const auto* preview_name : {"thumb.webp", "preview.webp"}) {
        std::vector<std::uint8_t> preview;
        if (archive->readPreview(preview_name, preview, error) && preview.size() > limits.max_preview_bytes) __builtin_trap();
    }

    for (const auto& name : archive->embeddedFiles()) {
        const auto slash = name.find('/');
        std::vector<std::uint8_t> embedded;
        if (archive->readEmbedded(name.substr(0, slash), name.substr(slash + 1), embedded, error) && embedded.size() > limits.max_embedded_bytes) {
            __builtin_trap();
        }
    }

    const auto uuids = archive->layerUuids();
    if (uuids.size() > kMaxLoadedLayers) return 0;
    for (const auto& uuid : uuids) {
        croptop::CroptopLayerInfo info;
        if (!archive->layerInfo(uuid, info, error)) continue;
        if (info.width == 0U || info.height == 0U || info.width > kMaxLayerDimension || info.height > kMaxLayerDimension) {
            continue;
        }
        croptop::TileStore destination(info.format, info.width, info.height);
        (void)archive->loadLayer(uuid, destination, error, 1);
    }
    return 0;
}
