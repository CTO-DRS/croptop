#include "recovery_journal.h"

#include <algorithm>
#include <cerrno>
#include <cstring>
#include <exception>
#include <mutex>
#include <set>
#include <system_error>

#include <fcntl.h>
#include <sys/stat.h>
#include <unistd.h>
#include <zstd.h>

#include "croptop_io_internal.h"

namespace croptop {
namespace {

using namespace io_detail;
namespace fs = std::filesystem;

constexpr char kStateMagic[4] = {'C', 'T', 'R', 'J'};
constexpr std::uint32_t kStateVersion = 2;
constexpr std::uint32_t kOldestStateVersion = 1;
constexpr std::string_view kStateName = "state.bin";
constexpr std::string_view kStateTempName = "state.bin.tmp";
constexpr std::size_t kTileRecordBytes = 24U;

fs::path tilePath(const fs::path& dir, std::string_view uuid, std::int32_t x, std::int32_t y, std::uint64_t generation) {
    return dir / "t" / std::string(uuid) /
           (std::to_string(x) + "_" + std::to_string(y) + "." + std::to_string(generation) + ".zst");
}

/** Writes the whole buffer and fsyncs it; the file is created or truncated. */
bool writeDurably(const fs::path& path, std::span<const std::uint8_t> bytes) {
    const int fd = ::open(path.c_str(), O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0600);
    if (fd < 0) return false;
    std::size_t written = 0;
    while (written < bytes.size()) {
        const auto result = ::write(fd, bytes.data() + written, bytes.size() - written);
        if (result < 0 && errno == EINTR) continue;
        if (result <= 0) {
            ::close(fd);
            return false;
        }
        written += static_cast<std::size_t>(result);
    }
    const bool synced = ::fsync(fd) == 0;
    return ::close(fd) == 0 && synced;
}

bool syncDirectory(const fs::path& path) {
    const int fd = ::open(path.c_str(), O_RDONLY | O_DIRECTORY | O_CLOEXEC);
    if (fd < 0) return false;
    const bool synced = ::fsync(fd) == 0;
    ::close(fd);
    return synced;
}

bool readFile(const fs::path& path, std::uint64_t max_bytes, std::vector<std::uint8_t>& out) {
    const int fd = ::open(path.c_str(), O_RDONLY | O_CLOEXEC);
    if (fd < 0) return false;
    struct stat info {};
    if (::fstat(fd, &info) != 0 || info.st_size < 0 || static_cast<std::uint64_t>(info.st_size) > max_bytes) {
        ::close(fd);
        return false;
    }
    try {
        out.resize(static_cast<std::size_t>(info.st_size));
    } catch (const std::exception&) {
        ::close(fd);
        return false;
    }
    std::size_t read_total = 0;
    while (read_total < out.size()) {
        const auto result = ::read(fd, out.data() + read_total, out.size() - read_total);
        if (result < 0 && errno == EINTR) continue;
        if (result <= 0) break;
        read_total += static_cast<std::size_t>(result);
    }
    ::close(fd);
    return read_total == out.size();
}

/** Bounds-checked little-endian cursor over state.bin. */
class Cursor final {
public:
    explicit Cursor(std::span<const std::uint8_t> bytes) : bytes_(bytes) {}
    bool has(std::size_t count) const { return bytes_.size() - offset_ >= count; }
    std::uint8_t u8() { return bytes_[offset_++]; }
    std::uint32_t u32() {
        const auto value = get32(bytes_, offset_);
        offset_ += 4U;
        return value;
    }
    std::uint64_t u64() {
        const auto value = get64(bytes_, offset_);
        offset_ += 8U;
        return value;
    }
    std::string text(std::size_t count) {
        std::string value(reinterpret_cast<const char*>(bytes_.data() + offset_), count);
        offset_ += count;
        return value;
    }
    std::vector<std::uint8_t> bytes(std::size_t count) {
        std::vector<std::uint8_t> value(bytes_.begin() + static_cast<std::ptrdiff_t>(offset_),
                                        bytes_.begin() + static_cast<std::ptrdiff_t>(offset_ + count));
        offset_ += count;
        return value;
    }
    bool atEnd() const { return offset_ == bytes_.size(); }

private:
    std::span<const std::uint8_t> bytes_;
    std::size_t offset_ = 0;
};

struct PendingTile {
    std::size_t layer = 0;
    TileCoordinate coordinate{};
    std::shared_ptr<const TileBuffer> buffer;
    bool reused = false;
    std::uint64_t generation = 0;
    std::uint32_t size = 0;
    std::uint32_t crc32 = 0;
};

bool sameBuffer(const std::weak_ptr<const TileBuffer>& previous, const std::shared_ptr<const TileBuffer>& current) {
    return !previous.expired() && !previous.owner_before(current) && !current.owner_before(previous);
}

/** Parses "<x>_<y>.<generation>.zst"; returns false for anything else. */
bool parseTileFileName(std::string_view name, std::int32_t& x, std::int32_t& y, std::uint64_t& generation) {
    if (!name.ends_with(".zst")) return false;
    name.remove_suffix(4);
    const auto dot = name.rfind('.');
    const auto underscore = name.find('_');
    if (dot == std::string_view::npos || underscore == std::string_view::npos || underscore > dot) return false;
    const auto px = parseIndex(name.substr(0, underscore));
    const auto py = parseIndex(name.substr(underscore + 1U, dot - underscore - 1U));
    const auto generation_text = name.substr(dot + 1U);
    if (!px || !py || generation_text.empty() || generation_text.size() > 19U) return false;
    std::uint64_t value = 0;
    const auto [end, error] = std::from_chars(generation_text.data(), generation_text.data() + generation_text.size(), value);
    if (error != std::errc() || end != generation_text.data() + generation_text.size()) return false;
    x = static_cast<std::int32_t>(*px);
    y = static_cast<std::int32_t>(*py);
    generation = value;
    return true;
}

}  // namespace

RecoveryJournal::RecoveryJournal(std::filesystem::path dir, int zstd_level, unsigned worker_threads)
    : dir_(std::move(dir)), zstd_level_(zstd_level), worker_threads_(std::max(1U, worker_threads)) {
    // Continue the generation sequence of an existing journal so new files never collide with
    // files the committed state references. Unknown buffers are rewritten on the first commit.
    std::string ignored;
    if (const auto reader = RecoveryReader::open(dir_, ignored)) generation_ = reader->generation();
}

bool RecoveryJournal::commit(const RecoverySnapshot& snapshot, std::string& error,
                             const std::function<bool()>& cancelled) {
    stats_ = {};
    const auto fail = [&](std::string message) {
        stats_ = {};
        error = std::move(message);
        return false;
    };
    if (snapshot.metadata_json.size() > 0xFFFFFFFFULL || snapshot.manifest_json.size() > 0xFFFFFFFFULL) {
        return fail("recovery metadata is too large");
    }
    if (snapshot.working_icc.size() > kRecoveryMaxWorkingIccBytes) return fail("recovery ICC profile exceeds its size limit");
    std::set<std::string_view> uuids;
    for (const auto& layer : snapshot.layers) {
        if (!validUuid(layer.uuid)) return fail("invalid layer uuid");
        if (!uuids.insert(layer.uuid).second) return fail("duplicate layer uuid " + layer.uuid);
    }
    if (zstd_level_ < -7 || zstd_level_ > 19 || zstd_level_ == 0) return fail("zstd level must be -7..-1 or 1..19");
    const auto generation = generation_ + 1U;

    std::vector<PendingTile> tiles;
    try {
        std::error_code ec;
        fs::create_directories(dir_ / "t", ec);
        if (ec) return fail("could not create journal directory: " + ec.message());
        for (std::size_t index = 0; index < snapshot.layers.size(); ++index) {
            const auto& layer = snapshot.layers[index];
            fs::create_directories(dir_ / "t" / layer.uuid, ec);
            if (ec) return fail("could not create layer directory: " + ec.message());
            auto coordinates = layer.pixels.tileCoordinates();
            std::sort(coordinates.begin(), coordinates.end(), [](TileCoordinate a, TileCoordinate b) {
                return a.y != b.y ? a.y < b.y : a.x < b.x;
            });
            for (const auto coordinate : coordinates) {
                auto buffer = layer.pixels.readTile(coordinate);
                if (buffer == nullptr || allZero(*buffer)) continue;
                PendingTile tile{index, coordinate, std::move(buffer)};
                const auto found = written_.find({layer.uuid, coordinate.x, coordinate.y});
                if (found != written_.end() && sameBuffer(found->second.buffer, tile.buffer)) {
                    tile.reused = true;
                    tile.generation = found->second.generation;
                    tile.size = found->second.size;
                    tile.crc32 = found->second.crc32;
                }
                tiles.push_back(std::move(tile));
            }
        }

        // Compress and durably write changed tiles.
        std::vector<std::size_t> changed;
        for (std::size_t index = 0; index < tiles.size(); ++index) {
            if (!tiles[index].reused) changed.push_back(index);
        }
        std::atomic<bool> failed{false};
        std::mutex error_mutex;
        std::mutex cancellation_mutex;
        std::string worker_error;
        std::vector<std::unique_ptr<ZSTD_CCtx, decltype(&ZSTD_freeCCtx)>> contexts;
        for (unsigned i = 0; i < worker_threads_; ++i) contexts.emplace_back(nullptr, ZSTD_freeCCtx);
        parallelFor(changed.size(), worker_threads_, [&](std::size_t item, std::size_t worker) {
            if (failed) return;
            bool is_cancelled = false;
            if (cancelled) {
                const std::lock_guard cancellation_lock(cancellation_mutex);
                is_cancelled = cancelled();
            }
            if (is_cancelled) {
                const std::lock_guard lock(error_mutex);
                if (!failed.exchange(true)) worker_error = "journal commit cancelled";
                return;
            }
            auto& tile = tiles[changed[item]];
            const auto report = [&](const char* message) {
                const std::lock_guard lock(error_mutex);
                if (!failed.exchange(true)) worker_error = message;
            };
            auto& context = contexts[worker];
            if (context == nullptr) context.reset(ZSTD_createCCtx());
            std::vector<std::uint8_t> frame;
            try {
                frame.resize(ZSTD_compressBound(tile.buffer->size()));
            } catch (const std::exception&) {
                return report("could not allocate tile frame");
            }
            const auto written = context == nullptr ? 0U
                : ZSTD_compressCCtx(context.get(), frame.data(), frame.size(), tile.buffer->data(), tile.buffer->size(),
                                    zstd_level_);
            if (context == nullptr || ZSTD_isError(written) != 0U) return report("could not compress tile");
            frame.resize(written);
            tile.generation = generation;
            tile.size = static_cast<std::uint32_t>(frame.size());
            tile.crc32 = crc32Of(frame);
            const auto& uuid = snapshot.layers[tile.layer].uuid;
            if (!writeDurably(tilePath(dir_, uuid, tile.coordinate.x, tile.coordinate.y, generation), frame)) {
                return report("could not write tile file");
            }
        });
        if (failed) return fail(worker_error);
        for (const auto& uuid : uuids) {
            if (!syncDirectory(dir_ / "t" / std::string(uuid))) return fail("could not sync layer directory");
        }
        if (!syncDirectory(dir_ / "t")) return fail("could not sync tile directory");
        if (fault_injector && !fault_injector(RecoveryCommitStage::TilesWritten)) return fail("injected fault");

        // Commit record.
        std::vector<std::uint8_t> state(kStateMagic, kStateMagic + 4);
        put32(state, kStateVersion);
        put64(state, generation);
        put32(state, static_cast<std::uint32_t>(snapshot.metadata_json.size()));
        state.insert(state.end(), snapshot.metadata_json.begin(), snapshot.metadata_json.end());
        put32(state, static_cast<std::uint32_t>(snapshot.manifest_json.size()));
        state.insert(state.end(), snapshot.manifest_json.begin(), snapshot.manifest_json.end());
        put32(state, static_cast<std::uint32_t>(snapshot.working_icc.size()));
        state.insert(state.end(), snapshot.working_icc.begin(), snapshot.working_icc.end());
        put32(state, static_cast<std::uint32_t>(snapshot.layers.size()));
        std::size_t next_tile = 0;
        for (std::size_t index = 0; index < snapshot.layers.size(); ++index) {
            const auto& layer = snapshot.layers[index];
            state.push_back(static_cast<std::uint8_t>(layer.uuid.size()));
            state.insert(state.end(), layer.uuid.begin(), layer.uuid.end());
            state.push_back(formatCode(layer.pixels.format()));
            put32(state, layer.pixels.width());
            put32(state, layer.pixels.height());
            const auto first = next_tile;
            while (next_tile < tiles.size() && tiles[next_tile].layer == index) ++next_tile;
            put32(state, static_cast<std::uint32_t>(next_tile - first));
            for (auto t = first; t < next_tile; ++t) {
                put32(state, static_cast<std::uint32_t>(tiles[t].coordinate.x));
                put32(state, static_cast<std::uint32_t>(tiles[t].coordinate.y));
                put64(state, tiles[t].generation);
                put32(state, tiles[t].size);
                put32(state, tiles[t].crc32);
            }
        }
        put32(state, crc32Of(state));
        std::map<TileKey, WrittenTile> referenced;
        for (const auto& tile : tiles) {
            referenced[{snapshot.layers[tile.layer].uuid, tile.coordinate.x, tile.coordinate.y}] =
                WrittenTile{tile.buffer, tile.generation, tile.size, tile.crc32};
            (tile.reused ? stats_.tiles_reused : stats_.tiles_written) += 1U;
        }
        if (cancelled) {
            const std::lock_guard cancellation_lock(cancellation_mutex);
            if (cancelled()) return fail("journal commit cancelled");
        }
        // Publication starts here and is not cancelled midway.
        const auto temp = dir_ / kStateTempName;
        if (!writeDurably(temp, state)) return fail("could not write journal state");
        if (fault_injector && !fault_injector(RecoveryCommitStage::StateTempWritten)) return fail("injected fault");
        fs::rename(temp, dir_ / kStateName, ec);
        if (ec) return fail("could not commit journal state: " + ec.message());
        // The rename is the commit point: from here on the new generation is current even if the
        // directory fsync or the cleanup below fails, so later commits never reuse its file names.
        generation_ = generation;
        written_ = std::move(referenced);
        if (!syncDirectory(dir_)) stats_.warning = "could not sync journal directory after commit";

        if (fault_injector && !fault_injector(RecoveryCommitStage::StateRenamed)) return true;

        fs::remove(temp, ec);
        for (const auto& layer_dir : fs::directory_iterator(dir_ / "t", ec)) {
            const auto uuid = layer_dir.path().filename().string();
            const bool live_layer = uuids.contains(uuid);
            std::error_code remove_ec;
            if (!live_layer) {
                const auto removed = fs::remove_all(layer_dir.path(), remove_ec);
                if (!remove_ec) stats_.files_deleted += static_cast<std::size_t>(removed);
                continue;
            }
            for (const auto& file : fs::directory_iterator(layer_dir.path(), remove_ec)) {
                std::int32_t x = 0;
                std::int32_t y = 0;
                std::uint64_t file_generation = 0;
                const auto found = parseTileFileName(file.path().filename().string(), x, y, file_generation)
                    ? written_.find({uuid, x, y}) : written_.end();
                if (found == written_.end() || found->second.generation != file_generation) {
                    if (fs::remove(file.path(), remove_ec)) ++stats_.files_deleted;
                    else if (remove_ec) stats_.warning = "orphan cleanup incomplete: " + remove_ec.message();
                }
            }
        }
        if (ec) stats_.warning = "orphan cleanup incomplete: " + ec.message();
    } catch (const std::exception& exception) {
        // Before the rename nothing was published; after it the commit stands and only cleanup failed.
        if (generation_ != generation) return fail(std::string("journal commit failed: ") + exception.what());
        stats_.warning = std::string("orphan cleanup incomplete: ") + exception.what();
    }
    return true;
}

bool RecoveryJournal::discard(const std::filesystem::path& dir, std::string& error) {
    std::error_code ec;
    fs::remove_all(dir, ec);
    if (ec) {
        error = "could not discard journal: " + ec.message();
        return false;
    }
    return true;
}

std::unique_ptr<RecoveryReader> RecoveryReader::open(const std::filesystem::path& dir, std::string& error,
                                                     const RecoveryLimits& limits) {
    std::vector<std::uint8_t> bytes;
    if (!readFile(dir / kStateName, limits.max_state_bytes, bytes)) {
        error = "journal has no readable commit";
        return nullptr;
    }
    if (bytes.size() < 4U + 4U + 8U + 4U || std::memcmp(bytes.data(), kStateMagic, 4) != 0 ||
        get32(bytes, bytes.size() - 4U) != crc32Of(std::span<const std::uint8_t>(bytes).first(bytes.size() - 4U))) {
        error = "journal commit is corrupt";
        return nullptr;
    }
    std::unique_ptr<RecoveryReader> reader(new RecoveryReader());
    reader->dir_ = dir;
    Cursor cursor(std::span<const std::uint8_t>(bytes).first(bytes.size() - 4U));
    const auto corrupt = [&] {
        error = "journal commit is malformed";
        return nullptr;
    };
    try {
        (void)cursor.u32();  // Magic, already checked.
        const auto state_version = cursor.u32();
        if (state_version < kOldestStateVersion || state_version > kStateVersion) {
            error = "journal was written by an unsupported version";
            return nullptr;
        }
        reader->generation_ = cursor.u64();
        for (auto* text : {&reader->metadata_json_, &reader->manifest_json_}) {
            if (!cursor.has(4U)) return corrupt();
            const auto length = cursor.u32();
            if (!cursor.has(length)) return corrupt();
            *text = cursor.text(length);
        }
        if (state_version >= 2U) {
            if (!cursor.has(4U)) return corrupt();
            const auto icc_length = cursor.u32();
            if (icc_length > kRecoveryMaxWorkingIccBytes || !cursor.has(icc_length)) return corrupt();
            reader->working_icc_ = cursor.bytes(icc_length);
        }
        if (!cursor.has(4U)) return corrupt();
        const auto layer_count = cursor.u32();
        std::uint64_t total_tiles = 0;
        for (std::uint32_t l = 0; l < layer_count; ++l) {
            if (!cursor.has(1U)) return corrupt();
            const auto uuid_length = cursor.u8();
            if (!cursor.has(uuid_length + 1U + 12U)) return corrupt();
            auto uuid = cursor.text(uuid_length);
            const auto format = formatFromCode(cursor.u8());
            Layer layer;
            layer.info.width = cursor.u32();
            layer.info.height = cursor.u32();
            const auto tile_count = cursor.u32();
            if (!validUuid(uuid) || !format || layer.info.width == 0U || layer.info.height == 0U ||
                layer.info.width > limits.max_dimension || layer.info.height > limits.max_dimension) {
                return corrupt();
            }
            layer.info.format = *format;
            total_tiles += tile_count;
            if (total_tiles > limits.max_tiles || !cursor.has(static_cast<std::size_t>(tile_count) * kTileRecordBytes)) {
                return corrupt();
            }
            const auto columns = 1U + (layer.info.width - 1U) / kTileEdge;
            const auto rows = 1U + (layer.info.height - 1U) / kTileEdge;
            const TileStore probe(layer.info.format, 1, 1);
            const auto max_frame = ZSTD_compressBound(probe.tile_byte_size());
            std::set<std::pair<std::int32_t, std::int32_t>> seen;
            layer.tiles.reserve(tile_count);
            for (std::uint32_t t = 0; t < tile_count; ++t) {
                Tile tile;
                tile.x = static_cast<std::int32_t>(cursor.u32());
                tile.y = static_cast<std::int32_t>(cursor.u32());
                tile.generation = cursor.u64();
                tile.size = cursor.u32();
                tile.crc32 = cursor.u32();
                if (tile.x < 0 || tile.y < 0 || static_cast<std::uint32_t>(tile.x) >= columns ||
                    static_cast<std::uint32_t>(tile.y) >= rows || tile.generation == 0U ||
                    tile.generation > reader->generation_ || tile.size == 0U || tile.size > max_frame ||
                    !seen.insert({tile.x, tile.y}).second) {
                    return corrupt();
                }
                layer.tiles.push_back(tile);
            }
            if (!reader->layers_.emplace(std::move(uuid), std::move(layer)).second) return corrupt();
        }
        if (!cursor.atEnd()) return corrupt();
    } catch (const std::exception&) {
        error = "could not allocate journal index";
        return nullptr;
    }
    return reader;
}

std::vector<std::filesystem::path> RecoveryReader::list(const std::filesystem::path& root) {
    std::vector<std::filesystem::path> journals;
    std::error_code ec;
    for (const auto& entry : fs::directory_iterator(root, ec)) {
        std::string ignored;
        if (!entry.is_directory(ec)) continue;
        const auto reader = open(entry.path(), ignored);
        if (reader != nullptr && reader->verifyTiles(ignored)) journals.push_back(entry.path());
    }
    std::sort(journals.begin(), journals.end());
    return journals;
}

bool RecoveryReader::verifyTiles(std::string& error) const {
    std::vector<std::uint8_t> frame;
    for (const auto& [uuid, layer] : layers_) {
        for (const auto& record : layer.tiles) {
            if (!readFile(tilePath(dir_, uuid, record.x, record.y, record.generation), record.size, frame) ||
                frame.size() != record.size || crc32Of(frame) != record.crc32) {
                error = "journal tile " + uuid + " " + std::to_string(record.x) + "_" + std::to_string(record.y) +
                        " is missing or corrupt";
                return false;
            }
        }
    }
    return true;
}

std::vector<std::string> RecoveryReader::layerUuids() const {
    std::vector<std::string> uuids;
    for (const auto& [uuid, layer] : layers_) uuids.push_back(uuid);
    return uuids;
}

bool RecoveryReader::layerInfo(std::string_view uuid, CroptopLayerInfo& info, std::string& error) const {
    const auto found = layers_.find(uuid);
    if (found == layers_.end()) {
        error = "journal has no layer " + std::string(uuid);
        return false;
    }
    info = found->second.info;
    return true;
}

bool RecoveryReader::loadLayer(std::string_view uuid, TileStore& destination, std::string& error) const {
    const auto found = layers_.find(uuid);
    if (found == layers_.end()) {
        error = "journal has no layer " + std::string(uuid);
        return false;
    }
    const auto& layer = found->second;
    if (destination.format() != layer.info.format || destination.width() != layer.info.width ||
        destination.height() != layer.info.height) {
        error = "destination store does not match journal layer";
        return false;
    }
    const auto tile_bytes = destination.tile_byte_size();
    std::unique_ptr<ZSTD_DCtx, decltype(&ZSTD_freeDCtx)> context(ZSTD_createDCtx(), ZSTD_freeDCtx);
    if (context == nullptr) {
        error = "could not allocate zstd decoder";
        return false;
    }
    std::vector<std::uint8_t> frame;
    TileBuffer tile;
    try {
        tile.resize(tile_bytes);
    } catch (const std::exception&) {
        error = "could not allocate tile buffer";
        return false;
    }
    for (const auto& record : layer.tiles) {
        if (!readFile(tilePath(dir_, uuid, record.x, record.y, record.generation), record.size, frame) ||
            frame.size() != record.size || crc32Of(frame) != record.crc32) {
            error = "journal tile is missing or corrupt";
            return false;
        }
        if (ZSTD_getFrameContentSize(frame.data(), frame.size()) != tile_bytes) {
            error = "journal tile has the wrong size";
            return false;
        }
        const auto written = ZSTD_decompressDCtx(context.get(), tile.data(), tile.size(), frame.data(), frame.size());
        if (ZSTD_isError(written) != 0U || written != tile_bytes || !destination.writeTile({record.x, record.y}, tile)) {
            error = "journal tile is corrupt";
            return false;
        }
    }
    return true;
}

}  // namespace croptop
