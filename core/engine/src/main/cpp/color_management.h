#pragma once

// Colour management over Little CMS 2: ICC profiles, built-in working spaces,
// profile conversion of whole layers, display transforms baked to a 3D LUT for the GPU, soft proofing,
// gamut warning masks and Lab/CMYK readouts.
//
// Pixels are straight (non-premultiplied) RGBA; colour channels are converted and alpha is copied.
// Transforms are created with cmsFLAGS_NOCACHE so one transform may run on several threads at once.

#include <array>
#include <cstdint>
#include <memory>
#include <optional>
#include <span>
#include <string>
#include <vector>

#include "lut.h"
#include "tile_store.h"

namespace croptop::cms {

enum class WorkingSpace : std::uint8_t {
    SRGB,         // IEC 61966-2-1
    DisplayP3,    // P3 primaries, D65, sRGB tone curve
    AdobeRGB,     // Adobe RGB (1998): gamma 563/256
    ProPhoto,     // ROMM RGB: D50, gamma 1.8 with a linear toe
    LinearSRGB,   // sRGB primaries, gamma 1.0
    LinearP3,     // P3 primaries, gamma 1.0
    Rec2020,      // ITU-R BT.2020 primaries, D65, BT.709/2020 transfer curve
};

enum class ColorModel : std::uint8_t { RGB, Gray, CMYK, Lab, Other };

enum class Intent : std::uint8_t { Perceptual, RelativeColorimetric, Saturation, AbsoluteColorimetric };

/** An ICC profile. Movable, not copyable; the handle is released on destruction. */
class Profile final {
public:
    Profile(Profile&&) noexcept;
    Profile& operator=(Profile&&) noexcept;
    Profile(const Profile&) = delete;
    Profile& operator=(const Profile&) = delete;
    ~Profile();

    /** Parses an embedded/loaded ICC profile (≤ 64 MiB). Rejects malformed data with a message. */
    static std::optional<Profile> fromIcc(std::span<const std::uint8_t> bytes, std::string& error);
    static Profile builtin(WorkingSpace space);
    /** CIE L*a*b* (D50) — the reference for readouts. */
    static Profile lab();
    /**
     * A synthetic CMYK press profile for readouts, proofing and gamut warning when the user has not
     * installed one: simple GCR with the given total ink limit (200–400 %). It is an approximation, not
     * a characterised press condition; prefer a real profile (e.g. from the user's print provider).
     */
    static Profile genericCmyk(float total_ink_percent = 300.0F);

    ColorModel model() const;
    std::string description() const;
    /** Serialised ICC bytes, for embedding on export. */
    std::vector<std::uint8_t> iccBytes() const;

    void* handle() const { return handle_; }

private:
    explicit Profile(void* handle) : handle_(handle) {}
    void* handle_ = nullptr;
};

struct ConvertOptions final {
    Intent intent = Intent::RelativeColorimetric;
    bool black_point_compensation = true;
    unsigned worker_threads = 0;  // 0 = hardware concurrency
};

/**
 * Convert Profile: re-encodes every pixel of an RGB layer from `from` to `to` (both RGB profiles).
 * `output_format` may differ from the input (e.g. 8-bit sRGB → 16-bit ProPhoto); RGBA32F values are
 * not clamped, so linear working spaces keep out-of-range colours. Empty tiles stay empty.
 */
std::optional<TileStore> convert(const TileStore& layer, const Profile& from, const Profile& to, PixelFormat output_format,
                                 const ConvertOptions& options, std::string& error);

/**
 * The display transform (document profile → display profile, e.g. Display P3 on a wide-colour panel)
 * as a 3D LUT sampled on the document's encoded values, for the compositor's final GPU pass. With
 * `proof`, the LUT also simulates that output device (soft proof, Ctrl+Y), optionally painting
 * out-of-gamut colours with `gamut_warning_rgb` (Ctrl+Shift+Y).
 */
struct DisplayOptions final {
    Intent intent = Intent::RelativeColorimetric;
    bool black_point_compensation = true;
    const Profile* proof = nullptr;
    Intent proof_intent = Intent::RelativeColorimetric;
    bool gamut_warning = false;
    std::array<float, 3> gamut_warning_rgb{0.5F, 0.5F, 0.5F};  // Photoshop's default grey, display-encoded
};
std::optional<color::Lut> bakeDisplayLut(const Profile& document, const Profile& display, std::uint32_t size,
                                         const DisplayOptions& options, std::string& error);

/**
 * Runs packed `0xAARRGGBB` pixels (the viewport frame) through [lut] in place; alpha is kept. A one-colour cache makes flat areas
 * cheap, and the work is split over up to `threads` workers (0 = a sensible default).
 */
void applyLutToArgb(std::uint32_t* pixels, std::size_t count, const color::Lut& lut, unsigned threads = 0);

/** Gamut warning as an A16 mask: full coverage where the layer's colour cannot be reproduced by `proof`. */
std::optional<TileStore> gamutMask(const TileStore& layer, const Profile& document, const Profile& proof, Intent intent,
                                   std::string& error, unsigned worker_threads = 0);

/** Colour readouts (Info panel / colour picker) of one encoded document colour (0–1 per channel). */
struct Readout final {
    std::array<float, 3> lab{};   // L* 0–100, a*/b* about −128…127 (D50)
    std::array<float, 4> cmyk{};  // percent 0–100 through the given CMYK profile
    bool out_of_gamut = false;    // not reproducible by the CMYK profile
};

/**
 * Builds the readout transforms once (the gamut check is costly to set up), then answers per pointer
 * move in microseconds. `read` may be called from several threads.
 */
class ColorReadout final {
public:
    static std::optional<ColorReadout> create(const Profile& document, const Profile& cmyk, std::string& error);
    ColorReadout(ColorReadout&&) noexcept;
    ColorReadout& operator=(ColorReadout&&) noexcept;
    ~ColorReadout();
    /** Non-finite channels read as 0; channels are clamped to 0–1. */
    Readout read(std::array<float, 3> rgb) const;

private:
    struct State;
    explicit ColorReadout(std::unique_ptr<State> state);
    std::unique_ptr<State> state_;
};

}  // namespace croptop::cms
