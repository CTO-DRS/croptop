#include "croptop_format.h"

#include <algorithm>
#include <array>
#include <atomic>
#include <charconv>
#include <cstring>
#include <exception>
#include <limits>
#include <mutex>
#include <optional>
#include <set>
#include <thread>

#include <zlib.h>
#include <zstd.h>

#include "croptop_io_internal.h"
#include "tile_band_writer.h"
#include "webp_codec.h"

namespace croptop {
namespace {

using namespace io_detail;

constexpr std::uint32_t kLocalHeaderSignature = 0x04034b50U;
constexpr std::uint32_t kCentralHeaderSignature = 0x02014b50U;
constexpr std::uint32_t kEndSignature = 0x06054b50U;
constexpr std::uint32_t kZip64EndSignature = 0x06064b50U;
constexpr std::uint32_t kZip64LocatorSignature = 0x07064b50U;
constexpr std::uint16_t kUtf8Flag = 0x0800U;
constexpr std::uint16_t kDosDate = 0x0021U;  // 1980-01-01, fixed so saves are deterministic.
constexpr std::uint16_t kVersionZip64 = 45U;
constexpr std::uint16_t kVersionStored = 20U;
constexpr std::size_t kInfoBytes = 16U;
constexpr std::string_view kManifestName = "manifest.json";
constexpr std::string_view kIccName = "icc/working.icc";
constexpr std::size_t kTilesPerWorkerBatch = 16U;

EncodeResult encodeFailure(std::string message) {
    EncodeResult result{};
    result.error = std::move(message);
    return result;
}

std::string tilePrefix(std::string_view uuid) { return "tiles/" + std::string(uuid) + "/0/"; }
std::string infoName(std::string_view uuid) { return "tiles/" + std::string(uuid) + "/info"; }

/** Streams STORED entries and remembers the central directory. */
class ZipWriter final {
public:
    ZipWriter(const EncodedBytesSink& sink, bool force_zip64) : sink_(sink), force_zip64_(force_zip64) {}

    bool add(std::string_view name, std::span<const std::uint8_t> data) { return add(name, data, crc32Of(data)); }

    /** Adds an entry whose CRC-32 was already computed (tile frames are checksummed on workers). */
    bool add(std::string_view name, std::span<const std::uint8_t> data, std::uint32_t crc) {
        if (data.size() >= 0xFFFFFFFFULL || name.size() > 0xFFFFU) return false;
        const auto size = static_cast<std::uint32_t>(data.size());
        std::vector<std::uint8_t> header;
        header.reserve(30U + name.size());
        put32(header, kLocalHeaderSignature);
        put16(header, kVersionStored);
        put16(header, kUtf8Flag);
        put16(header, 0);  // STORED
        put16(header, 0);
        put16(header, kDosDate);
        put32(header, crc);
        put32(header, size);
        put32(header, size);
        put16(header, static_cast<std::uint16_t>(name.size()));
        put16(header, 0);
        header.insert(header.end(), name.begin(), name.end());

        const bool zip64_offset = offset_ >= 0xFFFFFFFFULL;
        put32(central_, kCentralHeaderSignature);
        put16(central_, static_cast<std::uint16_t>((3U << 8U) | kVersionZip64));  // Made by Unix, 4.5.
        put16(central_, zip64_offset ? kVersionZip64 : kVersionStored);
        put16(central_, kUtf8Flag);
        put16(central_, 0);
        put16(central_, 0);
        put16(central_, kDosDate);
        put32(central_, crc);
        put32(central_, size);
        put32(central_, size);
        put16(central_, static_cast<std::uint16_t>(name.size()));
        put16(central_, zip64_offset ? 12U : 0U);
        put16(central_, 0);  // Comment.
        put16(central_, 0);  // Disk.
        put16(central_, 0);  // Internal attributes.
        put32(central_, 0);  // External attributes.
        put32(central_, zip64_offset ? 0xFFFFFFFFU : static_cast<std::uint32_t>(offset_));
        central_.insert(central_.end(), name.begin(), name.end());
        if (zip64_offset) {
            put16(central_, 0x0001U);
            put16(central_, 8U);
            put64(central_, offset_);
        }
        ++entries_;
        return write(header) && write(data);
    }

    bool finish() {
        const auto central_offset = offset_;
        const auto central_size = static_cast<std::uint64_t>(central_.size());
        if (!write(central_)) return false;
        const bool zip64 = force_zip64_ || entries_ >= 0xFFFFU || central_offset >= 0xFFFFFFFFULL ||
                           central_size >= 0xFFFFFFFFULL;
        std::vector<std::uint8_t> tail;
        if (zip64) {
            const auto zip64_end_offset = offset_;
            put32(tail, kZip64EndSignature);
            put64(tail, 44U);
            put16(tail, kVersionZip64);
            put16(tail, kVersionZip64);
            put32(tail, 0);
            put32(tail, 0);
            put64(tail, entries_);
            put64(tail, entries_);
            put64(tail, central_size);
            put64(tail, central_offset);
            put32(tail, kZip64LocatorSignature);
            put32(tail, 0);
            put64(tail, zip64_end_offset);
            put32(tail, 1);
        }
        put32(tail, kEndSignature);
        put16(tail, 0);
        put16(tail, 0);
        put16(tail, zip64 ? 0xFFFFU : static_cast<std::uint16_t>(entries_));
        put16(tail, zip64 ? 0xFFFFU : static_cast<std::uint16_t>(entries_));
        put32(tail, zip64 ? 0xFFFFFFFFU : static_cast<std::uint32_t>(central_size));
        put32(tail, zip64 ? 0xFFFFFFFFU : static_cast<std::uint32_t>(central_offset));
        put16(tail, 0);
        return write(tail);
    }

    bool sinkFailed() const { return sink_failed_; }

private:
    bool write(std::span<const std::uint8_t> bytes) {
        if (bytes.empty()) return true;
        if (!sink_(bytes)) {
            sink_failed_ = true;
            return false;
        }
        offset_ += bytes.size();
        return true;
    }

    const EncodedBytesSink& sink_;
    bool force_zip64_;
    bool sink_failed_ = false;
    std::uint64_t offset_ = 0;
    std::uint64_t entries_ = 0;
    std::vector<std::uint8_t> central_;
};

struct TileJob {
    std::size_t layer = 0;
    TileCoordinate coordinate{};
};

struct CompressedTile {
    bool empty = true;
    bool failed = false;
    std::uint32_t crc = 0;
    std::vector<std::uint8_t> frame;
};

/**
 * Box-downscales the composite by the smallest power of two whose result fits `max_side`, in one
 * pass over the base tiles (no mip cache): each output pixel averages alpha, and colour weighted by
 * alpha, over its source block. Only one source tile row and one accumulator row are held at a time.
 */
std::optional<TileStore> previewStore(const TileStore& composite, std::uint32_t max_side) {
    std::uint32_t factor = 1;
    while (((composite.width() + factor - 1U) / factor > max_side ||
            (composite.height() + factor - 1U) / factor > max_side) && factor < (1U << 30U)) {
        factor *= 2U;
    }
    const auto width = (composite.width() + factor - 1U) / factor;
    const auto height = (composite.height() + factor - 1U) / factor;
    const bool sixteen = composite.format() == PixelFormat::RGBA16;
    const auto bpp = composite.bytes_per_pixel();
    const auto tile_columns = 1U + (composite.width() - 1U) / kTileEdge;
    TileStore preview(PixelFormat::RGBA8, width, height);
    detail::TileBandWriter writer(preview);
    std::vector<std::uint64_t> sums(static_cast<std::size_t>(width) * 5U);  // a*r, a*g, a*b, a, count
    std::vector<std::uint8_t> row(static_cast<std::size_t>(width) * 4U);
    std::vector<std::shared_ptr<const TileBuffer>> tiles(tile_columns);
    std::int64_t loaded_tile_row = -1;
    const auto sample = [&](const std::uint8_t* pixel, int channel) -> std::uint32_t {
        if (!sixteen) return pixel[channel];
        std::uint16_t value;
        std::memcpy(&value, pixel + channel * 2, sizeof(value));
        return (static_cast<std::uint32_t>(value) * 255U + 32767U) / 65535U;
    };
    for (std::uint32_t out_y = 0; out_y < height; ++out_y) {
        std::fill(sums.begin(), sums.end(), 0U);
        const auto source_end = std::min<std::uint64_t>(composite.height(), static_cast<std::uint64_t>(out_y + 1U) * factor);
        for (auto y = static_cast<std::uint64_t>(out_y) * factor; y < source_end; ++y) {
            const auto tile_row = static_cast<std::int64_t>(y / kTileEdge);
            if (tile_row != loaded_tile_row) {
                for (std::uint32_t x = 0; x < tile_columns; ++x) {
                    tiles[x] = composite.readTile({static_cast<std::int32_t>(x), static_cast<std::int32_t>(tile_row)});
                }
                loaded_tile_row = tile_row;
            }
            for (std::uint32_t tile_x = 0; tile_x < tile_columns; ++tile_x) {
                const auto x0 = tile_x * kTileEdge;
                const auto count = std::min<std::uint32_t>(kTileEdge, composite.width() - x0);
                const auto& tile = tiles[tile_x];
                if (tile == nullptr) {
                    for (std::uint32_t x = x0; x < x0 + count; ++x) sums[static_cast<std::size_t>(x / factor) * 5U + 4U] += 1U;
                    continue;
                }
                const auto* pixel = tile->data() + static_cast<std::size_t>(y % kTileEdge) * kTileEdge * bpp;
                for (std::uint32_t x = x0; x < x0 + count; ++x, pixel += bpp) {
                    auto* sum = sums.data() + static_cast<std::size_t>(x / factor) * 5U;
                    const auto alpha = sample(pixel, 3);
                    sum[0] += alpha * sample(pixel, 0);
                    sum[1] += alpha * sample(pixel, 1);
                    sum[2] += alpha * sample(pixel, 2);
                    sum[3] += alpha;
                    sum[4] += 1U;
                }
            }
        }
        for (std::uint32_t x = 0; x < width; ++x) {
            const auto* sum = sums.data() + static_cast<std::size_t>(x) * 5U;
            auto* out = row.data() + static_cast<std::size_t>(x) * 4U;
            for (int channel = 0; channel < 3; ++channel) {
                out[channel] = sum[3] == 0U ? 0U : static_cast<std::uint8_t>((sum[channel] + sum[3] / 2U) / sum[3]);
            }
            out[3] = static_cast<std::uint8_t>((sum[3] + sum[4] / 2U) / sum[4]);
        }
        if (!writer.write(out_y, row)) return std::nullopt;
    }
    return preview;
}

std::vector<std::uint8_t> encodeWebpBytes(const TileStore& store, std::string& error) {
    std::vector<std::uint8_t> encoded;
    WebpEncodeOptions options;
    options.quality = 82.0F;
    const auto result = encodeWebpToSink(store, [&](std::span<const std::uint8_t> bytes) {
        encoded.insert(encoded.end(), bytes.begin(), bytes.end());
        return true;
    }, options);
    if (!result.success) {
        error = result.error;
        encoded.clear();
    }
    return encoded;
}

struct Previews {
    std::vector<std::uint8_t> thumb;
    std::vector<std::uint8_t> preview;
    std::string error;
};

/** preview.webp (<= 2048) from the composite, then thumb.webp (<= 512) from the preview. */
Previews encodePreviews(const TileStore& composite) {
    Previews previews;
    try {
        const auto preview = previewStore(composite, 2048U);
        const auto thumb = preview ? previewStore(*preview, 512U) : std::nullopt;
        if (!preview || !thumb) {
            previews.error = "could not build preview";
            return previews;
        }
        previews.preview = encodeWebpBytes(*preview, previews.error);
        if (!previews.preview.empty()) previews.thumb = encodeWebpBytes(*thumb, previews.error);
    } catch (const std::exception&) {
        previews.error = "could not allocate preview";
    }
    return previews;
}

bool validEmbeddedName(std::string_view name) {
    if (name.empty() || name.size() > 64U || name == "." || name == "..") return false;
    return std::all_of(name.begin(), name.end(), [](char c) {
        return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '.' || c == '_' || c == '-';
    });
}

std::string embeddedEntryName(std::string_view id, std::string_view name) {
    return "embedded/" + std::string(id) + "/" + std::string(name);
}

}  // namespace

EncodeResult saveCroptop(const CroptopSaveInput& input, const EncodedBytesSink& sink) {
    if (!sink) return encodeFailure("croptop save requires a sink");
    if (input.manifest_json.empty()) return encodeFailure("croptop save requires a manifest");
    if (input.zstd_level < -7 || input.zstd_level > 19 || input.zstd_level == 0) {
        return encodeFailure("zstd level must be -7..-1 or 1..19");
    }
    std::set<std::string_view> seen;
    for (const auto& layer : input.layers) {
        if (!validUuid(layer.uuid)) return encodeFailure("invalid layer uuid");
        if (layer.store == nullptr) return encodeFailure("layer " + layer.uuid + " has no pixel store");
        if (!seen.insert(layer.uuid).second) return encodeFailure("duplicate layer uuid " + layer.uuid);
    }
    std::set<std::string> embedded_seen;
    for (const auto& file : input.embedded) {
        if (!validUuid(file.id) || !validEmbeddedName(file.name)) return encodeFailure("invalid embedded file id or name");
        if (file.bytes.size() >= 0xFFFFFFFFULL) return encodeFailure("embedded file " + file.name + " is too large");
        if (file.bytes.size() > CroptopLimits{}.max_embedded_bytes) return encodeFailure("embedded file " + file.name + " exceeds the reader limit");
        if (!embedded_seen.insert(embeddedEntryName(file.id, file.name)).second) return encodeFailure("duplicate embedded file");
    }
    if (input.composite != nullptr && input.composite->format() != PixelFormat::RGBA8 &&
        input.composite->format() != PixelFormat::RGBA16) {
        return encodeFailure("composite must be RGBA8 or RGBA16");
    }

    try {
        // Collect every tile first so the archive is rejected before anything is written if the
        // reader could not open it (entry count or layer size over CroptopLimits).
        const CroptopLimits reader_limits;
        const auto max_entries = std::min(input.max_entries, reader_limits.max_entries);
        const std::uint64_t fixed_entries = 2U + (input.working_icc.empty() ? 0U : 1U) +
                                            (input.composite != nullptr ? 2U : 0U) + input.layers.size() + input.embedded.size();
        if (fixed_entries > max_entries) {
            return encodeFailure("document has more entries than a croptop archive can hold");
        }
        const auto remaining_tile_entries = max_entries - fixed_entries;
        std::vector<TileJob> jobs;
        for (std::size_t layer = 0; layer < input.layers.size(); ++layer) {
            const auto& store = *input.layers[layer].store;
            if (store.width() == 0U || store.height() == 0U || store.width() > reader_limits.max_dimension ||
                store.height() > reader_limits.max_dimension) {
                return encodeFailure("layer " + input.layers[layer].uuid + " is larger than the format allows");
            }
            auto coordinates = store.tileCoordinates();
            std::sort(coordinates.begin(), coordinates.end(), [](TileCoordinate a, TileCoordinate b) {
                return a.y != b.y ? a.y < b.y : a.x < b.x;
            });
            for (const auto coordinate : coordinates) {
                if (jobs.size() >= remaining_tile_entries) {
                    return encodeFailure("document has more tiles than a croptop archive can hold");
                }
                jobs.push_back({layer, coordinate});
            }
        }

        ZipWriter zip(sink, input.force_zip64);
        const auto mime = std::span<const std::uint8_t>(reinterpret_cast<const std::uint8_t*>(kCroptopMimeType.data()),
                                                        kCroptopMimeType.size());
        const auto fail = [&](const char* message) {
            return encodeFailure(zip.sinkFailed() ? "croptop sink failed" : message);
        };
        if (!zip.add("mimetype", mime) || !zip.add(kManifestName, input.manifest_json)) return fail("could not write manifest");
        if (!input.working_icc.empty() && !zip.add(kIccName, input.working_icc)) return fail("could not write ICC profile");
        // Previews are built on their own thread while tiles compress and are written after them;
        // readers locate entries through the central directory, so entry order does not matter.
        Previews previews;
        JoiningThread preview_thread;
        if (input.composite != nullptr) {
            preview_thread = JoiningThread([&previews, composite = input.composite] { previews = encodePreviews(*composite); });
        }

        for (const auto& file : input.embedded) {
            if (!zip.add(embeddedEntryName(file.id, file.name), file.bytes)) return fail("could not write an embedded file");
        }
        for (const auto& layer : input.layers) {
            std::vector<std::uint8_t> info = {'C', 'T', 'L', '1', formatCode(layer.store->format()), 0, 0, 0};
            put32(info, layer.store->width());
            put32(info, layer.store->height());
            if (!zip.add(infoName(layer.uuid), info)) return fail("could not write layer info");
        }

        // Compress in bounded windows on worker threads, then write each window in order.
        const auto workers = workerCount(input.worker_threads);
        const auto window = static_cast<std::size_t>(workers) * kTilesPerWorkerBatch;
        std::vector<CompressedTile> results(std::min(window, jobs.size()));
        std::vector<std::unique_ptr<ZSTD_CCtx, decltype(&ZSTD_freeCCtx)>> contexts;  // One per worker.
        for (unsigned i = 0; i < workers; ++i) contexts.emplace_back(nullptr, ZSTD_freeCCtx);
        for (std::size_t begin = 0; begin < jobs.size(); begin += window) {
            const auto count = std::min(window, jobs.size() - begin);
            parallelFor(count, workers, [&](std::size_t index, std::size_t worker) {
                auto& result = results[index];
                result.empty = true;
                result.failed = false;
                const auto& job = jobs[begin + index];
                const auto tile = input.layers[job.layer].store->readTile(job.coordinate);
                if (tile == nullptr || allZero(*tile)) return;
                result.empty = false;
                auto& context = contexts[worker];
                if (context == nullptr) context.reset(ZSTD_createCCtx());
                try {
                    result.frame.resize(ZSTD_compressBound(tile->size()));
                } catch (const std::exception&) {
                    result.failed = true;
                    return;
                }
                const auto written = context == nullptr ? 0U
                    : ZSTD_compressCCtx(context.get(), result.frame.data(), result.frame.size(), tile->data(),
                                        tile->size(), input.zstd_level);
                if (context == nullptr || ZSTD_isError(written) != 0U) {
                    result.failed = true;
                    return;
                }
                result.frame.resize(written);
                result.crc = crc32Of(result.frame);
            });
            for (std::size_t index = 0; index < count; ++index) {
                const auto& result = results[index];
                if (result.failed) return encodeFailure("could not compress tile");
                if (result.empty) continue;
                const auto& job = jobs[begin + index];
                const auto name = tilePrefix(input.layers[job.layer].uuid) + std::to_string(job.coordinate.x) + "_" +
                                  std::to_string(job.coordinate.y) + ".zst";
                if (!zip.add(name, result.frame, result.crc)) return fail("could not write tile");
            }
        }
        if (preview_thread.joinable()) {
            preview_thread.join();
            if (previews.thumb.empty() || previews.preview.empty()) {
                return encodeFailure("could not encode previews: " + previews.error);
            }
            if (!zip.add("thumb.webp", previews.thumb) || !zip.add("preview.webp", previews.preview)) {
                return fail("could not write preview");
            }
        }
        if (!zip.finish()) return fail("could not write central directory");
    } catch (const std::exception&) {
        return encodeFailure("could not allocate croptop save buffers");
    }
    EncodeResult result{};
    result.success = true;
    return result;
}

std::unique_ptr<CroptopArchive> CroptopArchive::open(CroptopReadSource source, std::string& error,
                                                     const CroptopLimits& limits) {
    error.clear();
    if (!source.read_at) {
        error = "croptop open requires a reader";
        return nullptr;
    }
    const auto size = source.size;
    if (size < 22U) {
        error = "not a croptop archive";
        return nullptr;
    }
    std::unique_ptr<CroptopArchive> archive(new CroptopArchive(std::move(source), limits));
    const auto read = [&](std::uint64_t offset, std::size_t count, std::vector<std::uint8_t>& out) {
        if (offset > size || count > size - offset) return false;
        try {
            out.resize(count);
        } catch (const std::exception&) {
            return false;
        }
        return archive->source_.read_at(offset, out);
    };

    try {
        // End of central directory: the last 22 bytes, or earlier if a comment follows.
        const auto tail_size = static_cast<std::size_t>(std::min<std::uint64_t>(size, 22U + 0xFFFFU));
        std::vector<std::uint8_t> tail;
        if (!read(size - tail_size, tail_size, tail)) {
            error = "could not read archive tail";
            return nullptr;
        }
        std::optional<std::size_t> end_at;
        for (std::size_t at = tail_size - 22U;; --at) {
            if (get32(tail, at) == kEndSignature && get16(tail, at + 20U) == tail_size - at - 22U) {
                end_at = at;
                break;
            }
            if (at == 0U) break;
        }
        if (!end_at) {
            error = "archive has no end of central directory";
            return nullptr;
        }
        const auto end_offset = size - tail_size + *end_at;
        std::uint64_t entry_count = get16(tail, *end_at + 10U);
        std::uint64_t central_size = get32(tail, *end_at + 12U);
        std::uint64_t central_offset = get32(tail, *end_at + 16U);
        if (get16(tail, *end_at + 4U) != 0U || get16(tail, *end_at + 6U) != 0U ||
            get16(tail, *end_at + 8U) != entry_count) {
            error = "multi-disk archives are not supported";
            return nullptr;
        }
        const bool saturated = entry_count == 0xFFFFU || central_size == 0xFFFFFFFFU || central_offset == 0xFFFFFFFFU;
        std::uint64_t directory_end = end_offset;
        if (end_offset >= 20U) {
            std::vector<std::uint8_t> locator;
            if (!read(end_offset - 20U, 20U, locator)) {
                error = "could not read ZIP64 locator";
                return nullptr;
            }
            if (get32(locator, 0) == kZip64LocatorSignature) {
                const auto zip64_offset = get64(locator, 8U);
                std::vector<std::uint8_t> record;
                // All bounds use checked subtraction; end_offset >= 20 here.
                if (zip64_offset > end_offset - 20U || end_offset - 20U - zip64_offset < 56U ||
                    !read(zip64_offset, 56U, record) || get32(record, 0) != kZip64EndSignature ||
                    get32(record, 16U) != 0U || get32(record, 20U) != 0U || get64(record, 24U) != get64(record, 32U)) {
                    error = "invalid ZIP64 end of central directory";
                    return nullptr;
                }
                entry_count = get64(record, 32U);
                central_size = get64(record, 40U);
                central_offset = get64(record, 48U);
                directory_end = zip64_offset;
            } else if (saturated) {
                error = "ZIP64 archive has no locator";
                return nullptr;
            }
        }
        if (entry_count > limits.max_entries || central_size > limits.max_central_directory_bytes ||
            central_offset > directory_end || central_size != directory_end - central_offset) {
            error = "central directory is out of bounds";
            return nullptr;
        }
        archive->central_directory_offset_ = central_offset;

        std::vector<std::uint8_t> directory;
        if (!read(central_offset, static_cast<std::size_t>(central_size), directory)) {
            error = "could not read central directory";
            return nullptr;
        }
        std::size_t at = 0;
        for (std::uint64_t index = 0; index < entry_count; ++index) {
            if (directory.size() - at < 46U || get32(directory, at) != kCentralHeaderSignature) {
                error = "corrupt central directory";
                return nullptr;
            }
            const auto flags = get16(directory, at + 8U);
            const auto method = get16(directory, at + 10U);
            const auto crc = get32(directory, at + 16U);
            std::uint64_t compressed = get32(directory, at + 20U);
            std::uint64_t uncompressed = get32(directory, at + 24U);
            const auto name_length = get16(directory, at + 28U);
            const auto extra_length = get16(directory, at + 30U);
            const auto comment_length = get16(directory, at + 32U);
            std::uint64_t local_offset = get32(directory, at + 42U);
            const auto record_size = 46U + static_cast<std::size_t>(name_length) + extra_length + comment_length;
            if (directory.size() - at < record_size) {
                error = "corrupt central directory";
                return nullptr;
            }
            if ((flags & 0x0001U) != 0U || method != 0U) {
                error = "encrypted or compressed ZIP entries are not supported";
                return nullptr;
            }
            std::string name(reinterpret_cast<const char*>(directory.data() + at + 46U), name_length);
            // ZIP64 extended information: fields appear only for saturated values, in this order.
            std::size_t extra = at + 46U + name_length;
            const auto extra_end = extra + extra_length;
            while (extra + 4U <= extra_end) {
                const auto id = get16(directory, extra);
                const auto length = get16(directory, extra + 2U);
                if (extra + 4U + length > extra_end) {
                    error = "corrupt ZIP extra field";
                    return nullptr;
                }
                if (id == 0x0001U) {
                    std::size_t field = extra + 4U;
                    const auto field_end = field + length;
                    for (auto* value : {&uncompressed, &compressed, &local_offset}) {
                        if (*value != 0xFFFFFFFFU) continue;
                        if (field + 8U > field_end) {
                            error = "corrupt ZIP64 extra field";
                            return nullptr;
                        }
                        *value = get64(directory, field);
                        field += 8U;
                    }
                }
                extra += 4U + length;
            }
            if (compressed != uncompressed) {
                error = "stored entry sizes disagree";
                return nullptr;
            }
            if (name.empty() || name.front() == '/' || name.find('\\') != std::string::npos ||
                name.find("..") != std::string::npos || name.find('\0') != std::string::npos) {
                error = "unsafe entry name";
                return nullptr;
            }
            if (local_offset >= central_offset || uncompressed > central_offset - local_offset) {
                error = "entry " + name + " is out of bounds";
                return nullptr;
            }
            if (!archive->entries_.emplace(std::move(name), Entry{local_offset, uncompressed, crc}).second) {
                error = "duplicate entry name";
                return nullptr;
            }
            at += record_size;
        }

        std::vector<std::uint8_t> mime;
        if (!archive->readEntry("mimetype", 64U, mime, error) ||
            std::string_view(reinterpret_cast<const char*>(mime.data()), mime.size()) != kCroptopMimeType) {
            error = "not a croptop archive";
            return nullptr;
        }
        if (!archive->entries_.contains(kManifestName)) {
            error = "croptop archive has no manifest";
            return nullptr;
        }
    } catch (const std::exception&) {
        error = "could not allocate archive index";
        return nullptr;
    }
    return archive;
}

bool CroptopArchive::readEntry(std::string_view name, std::uint64_t max_bytes, std::vector<std::uint8_t>& out,
                               std::string& error) const {
    const auto found = entries_.find(name);
    if (found == entries_.end()) {
        error = "missing entry " + std::string(name);
        return false;
    }
    return readEntry(found->second, max_bytes, out, error);
}

bool CroptopArchive::readEntry(const Entry& entry, std::uint64_t max_bytes, std::vector<std::uint8_t>& out,
                               std::string& error) const {
    if (entry.size > max_bytes) {
        error = "entry exceeds its size limit";
        return false;
    }
    std::array<std::uint8_t, 30> header{};
    if (entry.local_header_offset > central_directory_offset_ ||
        central_directory_offset_ - entry.local_header_offset < header.size() ||
        !source_.read_at(entry.local_header_offset, header) || get32(header, 0) != kLocalHeaderSignature ||
        get16(header, 8U) != 0U) {
        error = "corrupt local entry header";
        return false;
    }
    const auto bytes_before_name = static_cast<std::uint64_t>(header.size());
    const auto remaining = central_directory_offset_ - entry.local_header_offset;
    if (bytes_before_name > remaining) {
        error = "entry data is out of bounds";
        return false;
    }
    const auto after_header = remaining - bytes_before_name;
    const auto name_length = static_cast<std::uint64_t>(get16(header, 26U));
    const auto extra_length = static_cast<std::uint64_t>(get16(header, 28U));
    if (name_length > after_header || extra_length > after_header - name_length) {
        error = "entry data is out of bounds";
        return false;
    }
    const auto data_offset = entry.local_header_offset + bytes_before_name + name_length + extra_length;
    if (data_offset > central_directory_offset_ || entry.size > central_directory_offset_ - data_offset) {
        error = "entry data is out of bounds";
        return false;
    }
    try {
        out.resize(static_cast<std::size_t>(entry.size));
    } catch (const std::exception&) {
        error = "could not allocate entry buffer";
        return false;
    }
    if (!source_.read_at(data_offset, out)) {
        error = "could not read entry data";
        return false;
    }
    if (crc32Of(out) != entry.crc32) {
        error = "entry CRC mismatch";
        return false;
    }
    return true;
}

bool CroptopArchive::readManifest(std::vector<std::uint8_t>& out, std::string& error) const {
    return readEntry(kManifestName, limits_.max_manifest_bytes, out, error);
}

bool CroptopArchive::readWorkingIcc(std::vector<std::uint8_t>& out, std::string& error) const {
    out.clear();
    if (!entries_.contains(kIccName)) return true;
    return readEntry(kIccName, limits_.max_icc_bytes, out, error);
}

bool CroptopArchive::readPreview(std::string_view name, std::vector<std::uint8_t>& out, std::string& error) const {
    if (name != "thumb.webp" && name != "preview.webp") {
        error = "unknown preview entry";
        return false;
    }
    return readEntry(name, limits_.max_preview_bytes, out, error);
}

std::vector<std::string> CroptopArchive::embeddedFiles() const {
    std::vector<std::string> names;
    constexpr std::string_view kPrefix = "embedded/";
    for (const auto& [name, entry] : entries_) {
        if (!name.starts_with(kPrefix)) continue;
        const auto rest = std::string_view(name).substr(kPrefix.size());
        const auto slash = rest.find('/');
        if (slash == std::string_view::npos || !validUuid(rest.substr(0, slash)) || !validEmbeddedName(rest.substr(slash + 1))) continue;
        names.emplace_back(rest);
    }
    return names;
}

bool CroptopArchive::readEmbedded(std::string_view id, std::string_view name, std::vector<std::uint8_t>& out, std::string& error) const {
    if (!validUuid(id) || !validEmbeddedName(name)) {
        error = "invalid embedded file id or name";
        return false;
    }
    return readEntry(embeddedEntryName(id, name), limits_.max_embedded_bytes, out, error);
}

std::vector<std::string> CroptopArchive::layerUuids() const {
    std::vector<std::string> uuids;
    for (const auto& [name, entry] : entries_) {
        constexpr std::string_view kPrefix = "tiles/";
        constexpr std::string_view kSuffix = "/info";
        if (name.size() <= kPrefix.size() + kSuffix.size() || !name.starts_with(kPrefix) || !name.ends_with(kSuffix)) {
            continue;
        }
        auto uuid = name.substr(kPrefix.size(), name.size() - kPrefix.size() - kSuffix.size());
        if (validUuid(uuid)) uuids.push_back(std::move(uuid));
    }
    return uuids;
}

bool CroptopArchive::layerInfo(std::string_view uuid, CroptopLayerInfo& info, std::string& error) const {
    if (!validUuid(uuid)) {
        error = "invalid layer uuid";
        return false;
    }
    std::vector<std::uint8_t> bytes;
    if (!readEntry(infoName(uuid), kInfoBytes, bytes, error)) return false;
    const auto format = bytes.size() == kInfoBytes ? formatFromCode(bytes[4]) : std::nullopt;
    if (!format || std::memcmp(bytes.data(), "CTL1", 4) != 0) {
        error = "corrupt layer info";
        return false;
    }
    info = {*format, get32(bytes, 8U), get32(bytes, 12U)};
    if (info.width == 0U || info.height == 0U || info.width > limits_.max_dimension ||
        info.height > limits_.max_dimension) {
        error = "layer dimensions are out of range";
        return false;
    }
    return true;
}

bool CroptopArchive::loadLayer(std::string_view uuid, TileStore& destination, std::string& error,
                               unsigned worker_threads) const {
    CroptopLayerInfo info;
    if (!layerInfo(uuid, info, error)) return false;
    if (destination.format() != info.format || destination.width() != info.width ||
        destination.height() != info.height) {
        error = "destination store does not match layer info";
        return false;
    }
    const auto columns = 1U + (info.width - 1U) / kTileEdge;
    const auto rows = 1U + (info.height - 1U) / kTileEdge;
    const auto tile_bytes = destination.tile_byte_size();
    const auto prefix = tilePrefix(uuid);

    // Validate every tile entry before the first write.
    std::vector<std::pair<TileCoordinate, const Entry*>> tiles;
    for (auto it = entries_.lower_bound(prefix); it != entries_.end() && it->first.starts_with(prefix); ++it) {
        const std::string_view rest = std::string_view(it->first).substr(prefix.size());
        const auto underscore = rest.find('_');
        if (!rest.ends_with(".zst") || underscore == std::string_view::npos) {
            error = "unexpected entry " + it->first;
            return false;
        }
        const auto x = parseIndex(rest.substr(0, underscore));
        const auto y = parseIndex(rest.substr(underscore + 1U, rest.size() - underscore - 5U));
        if (!x || !y || *x >= columns || *y >= rows) {
            error = "tile entry out of range: " + it->first;
            return false;
        }
        if (it->second.size > ZSTD_compressBound(tile_bytes)) {
            error = "tile entry is too large: " + it->first;
            return false;
        }
        tiles.push_back({{static_cast<std::int32_t>(*x), static_cast<std::int32_t>(*y)}, &it->second});
    }

    // Each worker reads, checks and decompresses its tiles with its own buffers; writeTile is
    // thread-safe. The first failure stops further work and is reported.
    std::atomic<bool> failed{false};
    std::mutex error_mutex;
    const auto fail = [&](std::string message) {
        const std::lock_guard lock(error_mutex);
        if (!failed.exchange(true)) error = std::move(message);
    };
    const auto workers = workerCount(worker_threads);
    std::atomic<std::size_t> next{0};
    const auto run = [&] {
        std::unique_ptr<ZSTD_DCtx, decltype(&ZSTD_freeDCtx)> context(ZSTD_createDCtx(), ZSTD_freeDCtx);
        std::vector<std::uint8_t> frame;
        TileBuffer tile;
        try {
            tile.resize(tile_bytes);
        } catch (const std::exception&) {
            fail("could not allocate tile buffer");
            return;
        }
        if (context == nullptr) {
            fail("could not allocate zstd decoder");
            return;
        }
        std::string local_error;
        for (auto index = next.fetch_add(1); index < tiles.size() && !failed; index = next.fetch_add(1)) {
            const auto& [coordinate, entry] = tiles[index];
            if (!readEntry(*entry, entry->size, frame, local_error)) return fail(local_error);
            if (ZSTD_getFrameContentSize(frame.data(), frame.size()) != tile_bytes) {
                return fail("tile frame has the wrong size");
            }
            const auto written = ZSTD_decompressDCtx(context.get(), tile.data(), tile.size(), frame.data(), frame.size());
            if (ZSTD_isError(written) != 0U || written != tile_bytes) return fail("corrupt tile frame");
            if (!destination.writeTile(coordinate, tile)) return fail("could not store tile");
        }
    };
    {
        std::vector<JoiningThread> threads;
        for (std::size_t t = 1; t < std::min<std::size_t>(workers, tiles.size()); ++t) threads.emplace_back(run);
        run();
    }
    return !failed;
}

}  // namespace croptop
