#pragma once

// Native `.croptop` v1 container: a ZIP of STORED entries.
//
//   mimetype                         "application/vnd.croptop" (first entry, for sniffing)
//   manifest.json                    produced by Kotlin (core/io CroptopManifest), stored verbatim
//   icc/working.icc                  optional
//   thumb.webp, preview.webp         optional, composite box-downscaled by 2^n to <= 512 / 2048 px
//   tiles/<uuid>/info                16 bytes: "CTL1", format code, 3 reserved, u32 width, u32 height
//   tiles/<uuid>/0/<tx>_<ty>.zst     one zstd frame holding the raw base tile; empty tiles are omitted
//
// Writing never seeks, so the sink can be a SAF OutputStream. Atomic replacement of the destination
// file stays with the caller (the integration boundary).

#include <cstdint>
#include <functional>
#include <map>
#include <memory>
#include <span>
#include <string>
#include <string_view>
#include <vector>

#include "image_decoder.h"
#include "tile_store.h"

namespace croptop {

inline constexpr std::string_view kCroptopMimeType = "application/vnd.croptop";

struct CroptopLayerPixels final {
    /** Persistent format-level layer id: 1..64 of [A-Za-z0-9_-]. */
    std::string uuid;
    /** Layer pixels; must outlive the save call. */
    const TileStore* store = nullptr;
};

/**
 * A file embedded verbatim in the archive as `embedded/<id>/<name>` (a smart object's source image, a RAW file and its
 * settings). `id` follows the layer-uuid rules; `name` is 1-64 of [A-Za-z0-9._-] and not "." or "..". Stored uncompressed:
 * these are already compressed formats. Each file must be under 4 GiB.
 */
struct CroptopEmbeddedFile final {
    std::string id;
    std::string name;
    std::span<const std::uint8_t> bytes;  // must outlive the save call
};

struct CroptopSaveInput final {
    std::span<const std::uint8_t> manifest_json;
    std::vector<CroptopLayerPixels> layers;
    std::span<const std::uint8_t> working_icc;
    /** Files embedded as-is (smart-object sources); empty for a plain document. Older readers ignore them. */
    std::vector<CroptopEmbeddedFile> embedded;
    /** Optional flattened RGBA8/RGBA16 image for thumb.webp and preview.webp. */
    const TileStore* composite = nullptr;
    /**
     * zstd level (-7..-1 fast, 1..19). Level 1 is the default: on photo-like data it saves a
     * 24 MP / 10-layer document in ~1.3 s with 8 workers, while level 3 takes ~2.8 s for ~15% less size.
     */
    int zstd_level = 1;
    /** 0 = use hardware concurrency (capped at 8). */
    unsigned worker_threads = 0;
    /** Maximum total ZIP entries; defaults to the reader's default limit. */
    std::uint64_t max_entries = 2'000'000;
    /** Test hook: write ZIP64 end records even when the archive is small. */
    bool force_zip64 = false;
};

EncodeResult saveCroptop(const CroptopSaveInput& input, const EncodedBytesSink& sink);

/**
 * Random-access, bounded reads; a staged file descriptor with pread() is the intended backing.
 * read_at may be called concurrently from load workers, so it must be thread-safe (pread is).
 */
struct CroptopReadSource final {
    std::uint64_t size = 0;
    std::function<bool(std::uint64_t offset, std::span<std::uint8_t> out)> read_at;
};

struct CroptopLimits final {
    std::uint64_t max_entries = 2'000'000;
    std::uint64_t max_central_directory_bytes = 256ULL * 1024ULL * 1024ULL;
    std::uint64_t max_manifest_bytes = 64ULL * 1024ULL * 1024ULL;
    std::uint64_t max_icc_bytes = 16ULL * 1024ULL * 1024ULL;
    std::uint64_t max_preview_bytes = 64ULL * 1024ULL * 1024ULL;
    std::uint64_t max_embedded_bytes = 2ULL * 1024ULL * 1024ULL * 1024ULL;  // per embedded file
    std::uint32_t max_dimension = 65535;
};

struct CroptopLayerInfo final {
    PixelFormat format = PixelFormat::RGBA8;
    std::uint32_t width = 0;
    std::uint32_t height = 0;
};

class CroptopArchive final {
public:
    /** Parses and validates the central directory; returns nullptr and sets `error` on failure. */
    static std::unique_ptr<CroptopArchive> open(CroptopReadSource source, std::string& error,
                                                const CroptopLimits& limits = {});

    bool readManifest(std::vector<std::uint8_t>& out, std::string& error) const;
    /** Leaves `out` empty when the archive has no working profile. */
    bool readWorkingIcc(std::vector<std::uint8_t>& out, std::string& error) const;
    /** "thumb.webp" or "preview.webp"; false if absent. */
    bool readPreview(std::string_view name, std::vector<std::uint8_t>& out, std::string& error) const;

    /** "id/name" of every embedded file, sorted. */
    std::vector<std::string> embeddedFiles() const;
    /** Reads `embedded/<id>/<name>`; false with `error` if absent, unsafe or over `max_embedded_bytes`. */
    bool readEmbedded(std::string_view id, std::string_view name, std::vector<std::uint8_t>& out, std::string& error) const;

    /** UUIDs of every layer that has a tiles/<uuid>/info entry, sorted. */
    std::vector<std::string> layerUuids() const;
    bool layerInfo(std::string_view uuid, CroptopLayerInfo& info, std::string& error) const;
    /**
     * Loads a layer's base tiles into `destination`, which must match layerInfo(). Every tile name,
     * coordinate and frame size is validated before the first write; a later CRC or zstd failure
     * returns false with the destination partially written, so callers discard it on failure.
     */
    /** `worker_threads` = 0 uses hardware concurrency (capped at 8); read_at must be thread-safe. */
    bool loadLayer(std::string_view uuid, TileStore& destination, std::string& error,
                   unsigned worker_threads = 0) const;

    struct Entry {
        std::uint64_t local_header_offset = 0;
        std::uint64_t size = 0;
        std::uint32_t crc32 = 0;
    };

private:
    CroptopArchive(CroptopReadSource source, CroptopLimits limits) : source_(std::move(source)), limits_(limits) {}
    bool readEntry(std::string_view name, std::uint64_t max_bytes, std::vector<std::uint8_t>& out,
                   std::string& error) const;
    bool readEntry(const Entry& entry, std::uint64_t max_bytes, std::vector<std::uint8_t>& out,
                   std::string& error) const;

    CroptopReadSource source_;
    CroptopLimits limits_;
    std::uint64_t central_directory_offset_ = 0;
    std::map<std::string, Entry, std::less<>> entries_;
};

}  // namespace croptop
