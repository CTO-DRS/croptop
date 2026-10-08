#pragma once

#include <cstddef>
#include <cstdint>
#include <memory>
#include <mutex>
#include <string>
#include <unordered_map>
#include <utility>
#include <vector>

namespace croptop {

class MemoryBudgetManager;

inline constexpr std::uint32_t kTileEdge = 256;
using TileBuffer = std::vector<std::uint8_t>;

enum class PixelFormat : std::uint8_t { RGBA8, RGBA16, RGBA32F, A8, A16 };

struct TileCoordinate {
    std::int32_t x;
    std::int32_t y;
    bool operator==(const TileCoordinate&) const = default;
};

struct TileCoordinateHash {
    std::size_t operator()(TileCoordinate coordinate) const noexcept;
};

/** Half-open document-space rectangle used to invalidate derived tile data. */
struct PixelRect {
    std::int32_t left;
    std::int32_t top;
    std::int32_t right;
    std::int32_t bottom;
};

/** Reuses fixed-size zeroed buffers. Live buffers return to a bounded idle cache on release. */
class TilePool final : public std::enable_shared_from_this<TilePool> {
public:
    explicit TilePool(std::size_t max_cached_bytes = 16U * 1024U * 1024U);
    ~TilePool();

    std::shared_ptr<TileBuffer> acquire(std::size_t bytes);
    void trim();
    std::size_t active_bytes() const;
    std::size_t cached_bytes() const;

private:
    void release(TileBuffer* buffer);

    const std::size_t max_cached_bytes_;
    mutable std::mutex mutex_;
    std::vector<TileBuffer*> cached_;
    std::size_t active_bytes_ = 0;
    std::size_t cached_bytes_ = 0;
};

/** Sparse, immutable-on-read layer pixels. Copying a store shares tiles until writeTile replaces one. */
class TileStore final {
public:
    TileStore(PixelFormat format, std::uint32_t width, std::uint32_t height,
              std::shared_ptr<TilePool> pool = std::make_shared<TilePool>(),
              std::shared_ptr<MemoryBudgetManager> memory_budget = nullptr);
    TileStore(const TileStore& other);
    TileStore& operator=(const TileStore& other);

    PixelFormat format() const { return format_; }
    std::uint32_t width() const { return width_; }
    std::uint32_t height() const { return height_; }
    /** Opaque content revision shared by identical COW snapshots and replaced after every mutation. */
    std::uint64_t contentRevision() const;
    /** Opaque revision for the base tiles intersecting a document-space region. */
    std::uint64_t contentRevision(PixelRect region) const;
    /** Creates an empty store with this store's dimensions, tile pool, and memory-budget manager. */
    TileStore emptyLike(PixelFormat format) const;
    /** Creates an empty store with these dimensions and this store's allocator configuration. */
    TileStore emptyLike(PixelFormat format, std::uint32_t width, std::uint32_t height) const;
    /** The document-wide allocator shared by this store and its derived outputs, if configured. */
    std::shared_ptr<MemoryBudgetManager> memoryBudgetManager() const { return memory_budget_; }
    std::size_t bytes_per_pixel() const;
    std::size_t tile_byte_size() const;
    std::size_t tile_count() const {
        const std::lock_guard lock(mutex_);
        return tiles_.size() + managed_tiles_.size();
    }
    std::size_t resident_bytes() const;
    std::size_t mip_resident_bytes() const;
    /** Drop reconstructible mips and unused zeroed tile buffers under memory pressure. */
    void trimCaches();

    std::shared_ptr<const TileBuffer> readTile(TileCoordinate coordinate) const;
    /** Non-empty base tiles currently represented by this sparse store (including spilled tiles). */
    std::vector<TileCoordinate> tileCoordinates() const;
    /** Lazily builds and caches a tile at a 2^level reduction; level 0 is readTile(). */
    std::shared_ptr<const TileBuffer> readMipTile(std::uint32_t level, TileCoordinate coordinate) const;
    std::uint32_t mip_width(std::uint32_t level) const;
    std::uint32_t mip_height(std::uint32_t level) const;
    void invalidateMipmaps(PixelRect dirty);
    bool writeTile(TileCoordinate coordinate, const TileBuffer& pixels);
    bool eraseTile(TileCoordinate coordinate);
    /** Bilinearly translates RGBA pixels, clips at the canvas edges, and atomically replaces this store. */
    bool translate(float dx, float dy);
    void clear();

private:
    using TileMap = std::unordered_map<TileCoordinate, std::shared_ptr<const TileBuffer>, TileCoordinateHash>;

    struct ManagedTileRef {
        ManagedTileRef(std::string tile_key, std::shared_ptr<MemoryBudgetManager> tile_manager)
            : key(std::move(tile_key)), manager(std::move(tile_manager)) {}
        std::string key;
        std::shared_ptr<MemoryBudgetManager> manager;
        ~ManagedTileRef();
    };

    bool contains(TileCoordinate coordinate) const;
    std::uint64_t tileContentRevisionLocked(TileCoordinate coordinate) const;
    std::shared_ptr<const TileBuffer> buildMipTile(std::uint32_t level, TileCoordinate coordinate) const;
    bool containsMip(std::uint32_t level, TileCoordinate coordinate) const;

    // Geometry and allocator ownership are fixed at construction. Keeping these
    // immutable makes lock-free metadata reads safe while tile maps are copied.
    const PixelFormat format_;
    const std::uint32_t width_;
    const std::uint32_t height_;
    const std::uint32_t columns_;
    const std::uint32_t rows_;
    const std::shared_ptr<TilePool> pool_;
    const std::shared_ptr<MemoryBudgetManager> memory_budget_;
    TileMap tiles_;
    std::unordered_map<TileCoordinate, std::shared_ptr<ManagedTileRef>, TileCoordinateHash> managed_tiles_;
    mutable std::unordered_map<std::uint32_t, TileMap> mip_tiles_;
    mutable std::unordered_map<std::uint32_t, std::unordered_map<TileCoordinate, std::shared_ptr<ManagedTileRef>, TileCoordinateHash>> managed_mip_tiles_;
    std::uint64_t store_id_ = 0;
    std::uint64_t content_revision_ = 0;
    std::uint64_t empty_content_revision_ = 0;
    std::unordered_map<TileCoordinate, std::uint64_t, TileCoordinateHash> tile_content_revisions_;
    std::uint64_t tile_sequence_ = 0;
    mutable std::recursive_mutex mutex_;
};

}  // namespace croptop
