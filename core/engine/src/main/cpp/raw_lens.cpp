// Lens-profile auto-detect for camera raws; see raw_lens.h.
#include "raw_lens.h"

#include <algorithm>

namespace croptop::raw {

std::optional<DetectedLensProfile> detectLensProfile(const lens::Database& database, const RawMetadata& metadata,
                                                     std::string& reason) {
    if (metadata.lens.empty()) {
        reason = "the file does not name its lens";
        return std::nullopt;
    }
    const std::string& maker = !metadata.normalized_make.empty() ? metadata.normalized_make : metadata.make;
    const lens::LensEntry* entry = database.findLens(maker, metadata.lens);
    // Third-party lenses on a body ("Sigma 35mm f/1.4 DG HSM | A" on a Canon) carry their own maker in the name.
    if (entry == nullptr) entry = database.findLens({}, metadata.lens);
    if (entry == nullptr) {
        reason = "no profile for '" + metadata.lens + "' in the lens database";
        return std::nullopt;
    }
    float focal = metadata.focal_length_mm;
    if (focal <= 0.0F) {
        focal = entry->distortion.empty() ? 0.0F : entry->distortion.front().focal;
        if (focal <= 0.0F && !entry->tca.empty()) focal = entry->tca.front().focal;
        if (focal <= 0.0F && !entry->vignetting.empty()) focal = entry->vignetting.front().focal;
    }
    DetectedLensProfile detected;
    detected.correction = lens::interpolate(*entry, focal, metadata.aperture);
    detected.lens_maker = entry->maker;
    detected.lens_model = entry->model;
    if (const auto* camera = database.findCamera(maker, metadata.model); camera != nullptr && camera->crop_factor > 0.0F) {
        detected.apply.image_crop_factor = camera->crop_factor;
        detected.camera_known = true;
    }
    return detected;
}

}  // namespace croptop::raw
