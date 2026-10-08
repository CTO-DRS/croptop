#pragma once

// The GPU-with-CPU-fallback entry for the develop tone → colour stages. The develop pipeline calls
// this instead of applyTone/applyColor when it has a compute device; every stage falls back to the CPU reference on its own, so a
// setting the shader does not cover (tone texture) or a device failure never fails a develop.

#include <optional>
#include <string>

#include "develop_color.h"
#include "develop_tone.h"
#include "gpu_compute.h"

namespace croptop::develop {

struct GpuStageReport final {
    bool tone_on_gpu = false;
    bool color_on_gpu = false;
    std::string fallback_reason;  // why a stage ran on the CPU, empty when nothing fell back
};

/** `gpu` may be null (CPU only). The result is what applyTone then applyColor would return, to GPU-float tolerance. */
std::optional<TileStore> applyToneAndColor(gpu::Compute* gpu, const TileStore& linear, const ToneSettings& tone, const ColorSettings& color,
                                           GpuStageReport* report, std::string& error);

}  // namespace croptop::develop
