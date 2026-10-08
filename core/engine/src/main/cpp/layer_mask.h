#pragma once

// Layer masks and clipping for the compositor, as a per-tile service the render graph
// can sample. A MaskCache turns an A16 layer mask plus its properties into effective coverage tiles:
//   coverage = 1 − density · (1 − feather(invert?(mask)))   (density 0 = no masking, 1 = the mask as is)
//   feather(r): Gaussian with σ = r / 2 (Select ▸ Modify ▸ Feather's convention); pixels beyond the canvas
//   take the mask's outside value (a layer mask's default colour). Large radii blur a 2^k-reduced copy and
//   upsample, so cost per tile stays bounded.
// Tiles are computed lazily and cached; `update(mask, dirty)` drops only the tiles the edit can reach (the
// dirty rectangle grown by the feather footprint), so mask painting redraws dirty tiles only. A disabled
// mask yields full coverage. Clipping groups: a clipped layer's alpha is multiplied by the base layer's
// (already masked) alpha, `clipToBase`.

#include <cstdint>
#include <memory>
#include <mutex>
#include <optional>
#include <string>
#include <unordered_map>

#include "tile_store.h"

namespace croptop::layermask {

struct MaskProperties final {
    bool enabled = true;
    bool inverted = false;
    float density = 1.0F;  // 0–1
    float feather = 0.0F;  // 0–1000 px
};

class MaskCache final {
public:
    /**
     * `mask` must be A16 (copied, COW). `outside` is the coverage beyond the canvas (0 black, 1 white). `empty` is the
     * coverage of canvas tiles the store has no pixels for: 0 for a mask that starts hidden, 1 for Reveal All, which
     * then costs no memory until it is painted.
     */
    static std::unique_ptr<MaskCache> create(const TileStore& mask, const MaskProperties& properties, float outside, std::string& error,
                                             float empty = 0.0F);

    /** Effective A16 coverage of a tile (never null). Safe to call from several render threads at once. */
    std::shared_ptr<const TileBuffer> tile(TileCoordinate coordinate);
    /**
     * Coverage for a tile of the 2^level reduction the render graph composes previews at: the box average of the
     * effective full-resolution coverage under each reduced pixel (never null; level 0 is `tile()`). Cached per
     * level and dropped by `update()` / `setProperties()`.
     */
    std::shared_ptr<const TileBuffer> tileAtLevel(std::uint32_t level, TileCoordinate coordinate);
    /** Opaque cache token changed whenever source mask pixels or effective coverage properties change. */
    std::uint64_t contentRevision() const;
    /** Revision for coverage changes that can affect this document-space region. */
    std::uint64_t contentRevision(PixelRect region) const;
    /**
     * Replaces the mask after an edit inside `dirty`; drops only the cached tiles that edit can change.
     * Not concurrent with `tile()` (callers already serialise edits with rendering).
     */
    bool update(const TileStore& mask, PixelRect dirty, std::string& error);
    /** New properties invalidate every cached tile (not concurrent with `tile()`, like `update`). */
    bool setProperties(const MaskProperties& properties, std::string& error);

    /** COW copy of the unprocessed A16 mask pixels for document edits such as Invert or Apply. */
    TileStore maskPixels() const { return *mask_; }
    /** Coverage for unmaterialized mask tiles (0 hidden, 1 revealed). */
    float emptyCoverage() const { return empty_; }

    std::size_t cachedTiles() const;
    std::uint64_t computedTiles() const;  // tiles actually computed so far (for dirty-region checks)
    /** How far (px) a mask change reaches in coverage: the feather footprint. */
    int reach() const;

private:
    MaskCache(const TileStore& mask, const MaskProperties& p, float outside, float empty);
    std::shared_ptr<const TileBuffer> compute(TileCoordinate coordinate) const;
    std::optional<TileStore> mask_;
    MaskProperties properties_;
    float outside_;
    float empty_;
    mutable std::mutex mutex_;
    std::unordered_map<TileCoordinate, std::shared_ptr<const TileBuffer>, TileCoordinateHash> cache_;
    std::unordered_map<std::uint32_t, std::unordered_map<TileCoordinate, std::shared_ptr<const TileBuffer>, TileCoordinateHash>> level_cache_;
    std::uint64_t computed_ = 0;
    std::uint64_t content_revision_ = 0;
    std::uint64_t properties_revision_ = 0;
    std::uint64_t default_mask_revision_ = 0;
    std::unordered_map<TileCoordinate, std::uint64_t, TileCoordinateHash> mask_tile_revisions_;
};

/** Multiplies an RGBA tile's alpha by A16 coverage (straight alpha: colour is left alone). */
void applyCoverage(PixelFormat format, TileBuffer& rgba, const TileBuffer& coverage);

/** Clipping: multiplies a clipped layer tile's alpha by the base layer tile's alpha (null base = clipped away). */
void clipToBase(PixelFormat format, TileBuffer& rgba, PixelFormat base_format, const TileBuffer* base);

}  // namespace croptop::layermask
