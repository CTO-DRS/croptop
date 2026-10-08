#pragma once

// Lens-profile correction from lensfun-format databases (version 1 XML), without
// linking lensfun: parse <lens>/<camera> entries, look a lens up by maker/model, interpolate its
// calibration for the shot (focal length, aperture), and correct distortion, transverse chromatic
// aberration and vignetting in one inverse-mapped pass.
//
// Coordinates follow lensfun: distortion and TCA coefficients use Hugin units — r = 1 at half the shorter
// side of the *calibration* sensor (the lens entry's crop factor and aspect ratio). PTLENS/POLY3 are
// evaluated in PanoTools form on x = r / d (d = 1 − a − b − c, resp. 1 − k1), which is what lensfun's
// focal-length rescaling reduces to, so the image scale at the centre is unchanged. Vignetting (pa) uses
// r = 1 at the calibration sensor's corner.

#include <array>
#include <optional>
#include <string>
#include <string_view>
#include <vector>

#include "tile_store.h"

namespace croptop::lens {

enum class DistortionModel : std::uint8_t { None, Poly3, Poly5, PTLens };
enum class TcaModel : std::uint8_t { None, Linear, Poly3 };

struct DistortionCalibration final {
    float focal = 0;
    DistortionModel model = DistortionModel::None;
    std::array<float, 3> k{};  // poly3: k1; poly5: k1, k2; ptlens: a, b, c
};

struct TcaCalibration final {
    float focal = 0;
    TcaModel model = TcaModel::None;
    std::array<float, 3> red{1, 0, 0};   // linear: {kr}; poly3: {vr, cr, br}
    std::array<float, 3> blue{1, 0, 0};  // linear: {kb}; poly3: {vb, cb, bb}
};

struct VignettingCalibration final {
    float focal = 0, aperture = 0, distance = 0;
    std::array<float, 3> k{};  // pa: k1, k2, k3
};

struct LensEntry final {
    std::string maker, model, mount;
    float crop_factor = 1.0F;
    float aspect_ratio = 1.5F;  // long side / short side of the calibration sensor
    std::vector<DistortionCalibration> distortion;
    std::vector<TcaCalibration> tca;
    std::vector<VignettingCalibration> vignetting;
};

struct CameraEntry final {
    std::string maker, model, mount;
    float crop_factor = 1.0F;
};

struct Database final {
    std::vector<LensEntry> lenses;
    std::vector<CameraEntry> cameras;

    /** Adds the entries of one lensfun XML file (≤ 32 MiB). Malformed entries are skipped. */
    bool addXml(std::string_view xml, std::string& error);
    /** Best match by maker and model (case/space-insensitive; all model tokens must appear). */
    const LensEntry* findLens(std::string_view maker, std::string_view model) const;
    const CameraEntry* findCamera(std::string_view maker, std::string_view model) const;
};

/** Calibration interpolated for one shot. */
struct Correction final {
    DistortionCalibration distortion;
    TcaCalibration tca;
    std::optional<VignettingCalibration> vignetting;
    float calibration_crop = 1.0F, calibration_aspect = 1.5F;
};

/** Interpolates linearly in focal length (and in aperture for vignetting); clamps outside the calibrated range. */
Correction interpolate(const LensEntry& lens, float focal, float aperture);

struct ApplyOptions final {
    float image_crop_factor = 1.0F;  // of the camera that took the image
    bool distortion = true, tca = true, vignetting = true;
    unsigned worker_threads = 0;
};

/** Returns the corrected layer (RGBA8/16/32F); samples outside the source are transparent. */
std::optional<TileStore> apply(const TileStore& layer, const Correction& correction, const ApplyOptions& options, std::string& error);

}  // namespace croptop::lens
