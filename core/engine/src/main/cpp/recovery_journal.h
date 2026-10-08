#pragma once

// Incremental crash-recovery journal ("Autosave").
//
// <dir>/state.bin                     commit record: metadata JSON, manifest JSON, layer table and
//                                     tile table, protected by a trailing CRC-32. Replaced atomically
//                                     (temp file, fsync, rename, directory fsync) after all tiles it
//                                     references are durable.
// <dir>/t/<uuid>/<tx>_<ty>.<gen>.zst  immutable zstd tile frames. A tile is rewritten only when its
//                                     buffer changed since the last commit (TileStore copy-on-write
//                                     identity), under the new generation, so a committed state never
//                                     points at a file that a later commit overwrites.
//
// A crash at any point leaves either the previous or the new commit readable. Files no commit
// references are deleted after the next successful commit.

#include <cstdint>
#include <filesystem>
#include <functional>
#include <map>
#include <memory>
#include <string>
#include <string_view>
#include <tuple>
#include <vector>

#include "croptop_format.h"
#include "tile_store.h"

namespace croptop {

inline constexpr std::uint64_t kRecoveryMaxWorkingIccBytes = 16ULL * 1024ULL * 1024ULL;

struct RecoveryLayer final {
    /** Persistent format-level uuid, as in the manifest ([A-Za-z0-9_-]{1,64}). */
    std::string uuid;
    /** A copy-on-write snapshot of the layer, taken on the document thread (cheap: tiles are shared). */
    TileStore pixels;
};

struct RecoverySnapshot final {
    /** Kotlin-owned recovery metadata (name, size, last edit, source uri, …), stored verbatim. */
    std::string metadata_json;
    /** The CroptopManifest JSON for the document at this point, stored verbatim. */
    std::string manifest_json;
    /** Optional working ICC payload. Bounded by kRecoveryMaxWorkingIccBytes and committed atomically with state.bin. */
    std::vector<std::uint8_t> working_icc;
    std::vector<RecoveryLayer> layers;
};

struct RecoveryCommitStats final {
    std::size_t tiles_written = 0;
    std::size_t tiles_reused = 0;
    std::size_t files_deleted = 0;
    /**
     * Set when the commit is durable (state.bin renamed) but a later best-effort step failed: the
     * directory fsync after the rename, or orphan cleanup, which the next commit retries.
     */
    std::string warning;
};

/** Points where tests may simulate a crash; returning false from the hook stops the commit there. */
enum class RecoveryCommitStage { TilesWritten, StateTempWritten, StateRenamed };

class RecoveryJournal final {
public:
    /** `dir` is created on the first commit. Commits must not run concurrently on one journal. */
    explicit RecoveryJournal(std::filesystem::path dir, int zstd_level = 1, unsigned worker_threads = 1);

    /**
     * Writes changed tiles, then publishes the new state. `cancelled` is polled between tiles and once
     * more before publication starts; parallel worker polls are serialized, so the callback need not
     * support concurrent invocation. It may be called on different worker threads. After publication
     * starts, the commit-point sequence always runs to the end.
     * Returns false only when the new state was not published (the previous commit stays current).
     */
    bool commit(const RecoverySnapshot& snapshot, std::string& error, const std::function<bool()>& cancelled = {});
    const RecoveryCommitStats& lastStats() const { return stats_; }
    std::uint64_t generation() const { return generation_; }

    /** Test hook, see RecoveryCommitStage. */
    std::function<bool(RecoveryCommitStage)> fault_injector;

    /** Removes the journal directory (after a clean save, close, or user discard). */
    static bool discard(const std::filesystem::path& dir, std::string& error);

private:
    struct WrittenTile {
        std::weak_ptr<const TileBuffer> buffer;
        std::uint64_t generation = 0;
        std::uint32_t size = 0;
        std::uint32_t crc32 = 0;
    };
    using TileKey = std::tuple<std::string, std::int32_t, std::int32_t>;

    std::filesystem::path dir_;
    int zstd_level_;
    unsigned worker_threads_;
    std::uint64_t generation_ = 0;
    std::map<TileKey, WrittenTile> written_;
    RecoveryCommitStats stats_;
};

struct RecoveryLimits final {
    std::uint64_t max_state_bytes = 256ULL * 1024ULL * 1024ULL;
    std::uint64_t max_tiles = 4'000'000;
    std::uint32_t max_dimension = 65535;
};

/** Reads the last committed state of a journal directory. */
class RecoveryReader final {
public:
    static std::unique_ptr<RecoveryReader> open(const std::filesystem::path& dir, std::string& error,
                                                const RecoveryLimits& limits = {});

    /**
     * Journal directories under `root` whose commit is valid and whose every referenced tile file has
     * the recorded size and CRC-32 (verifyTiles), sorted by name. Safe to present for recovery.
     */
    static std::vector<std::filesystem::path> list(const std::filesystem::path& root);

    /** Reads every referenced tile file and checks its size and CRC-32. */
    bool verifyTiles(std::string& error) const;

    const std::string& metadataJson() const { return metadata_json_; }
    const std::string& manifestJson() const { return manifest_json_; }
    const std::vector<std::uint8_t>& workingIcc() const { return working_icc_; }
    std::uint64_t generation() const { return generation_; }
    std::vector<std::string> layerUuids() const;
    bool layerInfo(std::string_view uuid, CroptopLayerInfo& info, std::string& error) const;
    /** Same contract as CroptopArchive::loadLayer: validate first, discard the store on failure. */
    bool loadLayer(std::string_view uuid, TileStore& destination, std::string& error) const;

private:
    struct Tile {
        std::int32_t x = 0;
        std::int32_t y = 0;
        std::uint64_t generation = 0;
        std::uint32_t size = 0;
        std::uint32_t crc32 = 0;
    };
    struct Layer {
        CroptopLayerInfo info;
        std::vector<Tile> tiles;
    };

    std::filesystem::path dir_;
    std::string metadata_json_;
    std::string manifest_json_;
    std::vector<std::uint8_t> working_icc_;
    std::uint64_t generation_ = 0;
    std::map<std::string, Layer, std::less<>> layers_;
};

}  // namespace croptop
