#pragma once

// Photoshop document writer, native encoder only. Takes the same psd::Document the
// reader produces, so documents round-trip:
//   - always RGB mode at the document depth (8/16/32-bit; 32-bit pixels are written as linear floats);
//   - PSB (version 2) when a side exceeds 30,000 px or `force_psb`;
//   - each layer's record is cropped to its non-transparent bounds and each mask to where it differs from its
//     default colour; names are written as Pascal (MacRoman-safe ASCII) plus `luni`, fill as `iOpa`, groups as
//     `lsct` folder/divider records, and `extra` blocks are passed through untouched;
//   - 8-bit layer data sits in the layer-info section; 16/32-bit layer data in a tail `Lr16`/`Lr32` block (as
//     Photoshop writes it);
//   - channels are RLE for 8-bit and ZIP with prediction for 16/32-bit by default;
//   - the merged `composite` (required; the caller renders it) is written with its alpha channel (negative
//     layer count), RLE-compressed.

#include <cstdint>
#include <optional>
#include <string>
#include <vector>

#include "psd_reader.h"

namespace croptop::psd {

enum class Compression : std::uint8_t { Automatic, Raw, Rle, Zip, ZipPredicted };

struct WriteOptions final {
    Compression compression = Compression::Automatic;
    bool force_psb = false;
    std::uint64_t max_output_bytes = 4ULL << 30;
};

std::optional<std::vector<std::uint8_t>> write(const Document& document, std::string& error, const WriteOptions& options = {});

}  // namespace croptop::psd
