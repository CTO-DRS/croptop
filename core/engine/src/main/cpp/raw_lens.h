#pragma once

// Lens-profile auto-detect for camera raws: looks the shot's camera and lens up in a
// lensfun-format database (lens_profile.h) from the metadata LibRaw read, and interpolates the calibration for the
// recorded focal length and aperture. Nothing is applied here: the result feeds lens::apply, or is shown as
// "detected profile" in the Develop optics panel.

#include <optional>
#include <string>

#include "lens_profile.h"
#include "raw_decode.h"

namespace croptop::raw {

struct DetectedLensProfile final {
    lens::Correction correction;
    lens::ApplyOptions apply;        // image_crop_factor set from the camera entry (1 when the camera is unknown)
    std::string lens_maker;          // database spelling
    std::string lens_model;
    bool camera_known = false;       // false: the crop factor is a guess and distortion rescaling may be off
};

/**
 * Finds the profile for `metadata`'s lens. nullopt with `reason` when the file names no lens or the database has no
 * such lens. Missing focal length / aperture fall back to the calibration nearest the lens's shortest focal length.
 */
std::optional<DetectedLensProfile> detectLensProfile(const lens::Database& database, const RawMetadata& metadata,
                                                     std::string& reason);

}  // namespace croptop::raw
