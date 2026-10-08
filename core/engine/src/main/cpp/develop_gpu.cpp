#include "develop_gpu.h"

#include "develop_color_gpu.h"
#include "develop_tone_gpu.h"

namespace croptop::develop {

std::optional<TileStore> applyToneAndColor(gpu::Compute* gpu, const TileStore& linear, const ToneSettings& tone, const ColorSettings& color,
                                           GpuStageReport* report, std::string& error) {
    GpuStageReport local;
    GpuStageReport& r = report != nullptr ? *report : local;
    r = {};
    const auto note = [&](const std::string& what) { r.fallback_reason += (r.fallback_reason.empty() ? "" : "; ") + what; };

    std::optional<TileStore> toned;
    if (gpu != nullptr) {
        std::string gpu_error;
        toned = applyToneGpu(*gpu, linear, tone, gpu_error);
        if (toned) {
            r.tone_on_gpu = !tone.isIdentity();
        } else {
            note("tone: " + gpu_error);
        }
    }
    if (!toned) {
        toned = applyTone(linear, tone, error);
        if (!toned) return std::nullopt;
    }

    std::optional<TileStore> colored;
    if (gpu != nullptr) {
        std::string gpu_error;
        colored = applyColorGpu(*gpu, *toned, color, gpu_error);
        if (colored) {
            r.color_on_gpu = !color.isIdentity();
        } else {
            note("colour: " + gpu_error);
        }
    }
    if (!colored) {
        colored = applyColor(*toned, color, error);
        if (!colored) return std::nullopt;
    }
    return colored;
}

}  // namespace croptop::develop
