#pragma once

// Metadata and fast-thumbnail extraction, native parsing only; input is untrusted and
// bounded (IFD count, entries, nesting, loops).
// Containers: JPEG (APP1 EXIF and XMP, APP13 Photoshop IRB → IPTC-NAA, SOF dimensions), TIFF-based files
// including TIFF/DNG and TIFF-based RAWs (CR2, NEF, ARW…: IFD chain, SubIFDs, EXIF and GPS IFDs), and PNG
// (`eXIf`, XMP in `iTXt`). Previews are byte ranges of embedded JPEGs (the EXIF thumbnail, JPEG-compressed
// reduced-resolution IFDs, JPEGInterchangeFormat blocks), verified by their SOI marker and sized from their
// SOF, largest first — decode them with the JPEG decoder for instant Library thumbnails.

#include <cstdint>
#include <optional>
#include <span>
#include <string>
#include <vector>

namespace croptop::metadata {

struct Exif final {
    std::string make, model, lens_model, software, artist, copyright;
    std::string date_time_original;  // "YYYY:MM:DD HH:MM:SS" as stored (DateTimeOriginal, else DateTime)
    std::optional<double> exposure_time;     // seconds
    std::optional<double> f_number;
    std::optional<double> exposure_bias;     // EV
    std::optional<double> focal_length;      // mm
    std::optional<std::uint32_t> focal_length_35mm;
    std::optional<std::uint32_t> iso;
    std::optional<std::uint32_t> flash;
    std::optional<int> orientation;          // 1–8
    std::optional<std::uint32_t> pixel_width, pixel_height;
    std::optional<double> gps_latitude, gps_longitude;  // signed degrees
    std::optional<double> gps_altitude;                 // metres, negative below sea level
};

enum class PreviewKind : std::uint8_t { ExifThumbnail, EmbeddedJpeg };

struct Preview final {
    PreviewKind kind = PreviewKind::EmbeddedJpeg;
    std::uint64_t offset = 0;  // byte range of the JPEG within the file
    std::uint64_t length = 0;
    std::uint32_t width = 0, height = 0;  // from the JPEG's SOF (0 if unknown)
};

struct Metadata final {
    std::string container;  // "jpeg", "tiff", "png"
    std::optional<std::uint32_t> width, height;  // of the main image when the container states it
    std::optional<Exif> exif;
    std::vector<std::uint8_t> xmp;   // raw XMP packet
    std::vector<std::uint8_t> iptc;  // raw IPTC-NAA records
    std::vector<Preview> previews;   // largest first
    std::vector<std::string> warnings;
};

std::optional<Metadata> read(std::span<const std::uint8_t> file, std::string& error);

}  // namespace croptop::metadata
