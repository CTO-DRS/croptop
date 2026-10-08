#pragma once

#include "blend_modes.h"
#include "layer_mask.h"
#include "lut.h"
#include "tile_store.h"

#include <array>
#include <cstdint>
#include <functional>
#include <memory>
#include <mutex>
#include <span>
#include <unordered_map>
#include "blend_if.h"

#include <vector>

namespace croptop {

struct RenderLayer {
    std::uint64_t id;
    const TileStore* pixels;
    BlendMode blend_mode = BlendMode::Normal;
    std::uint8_t opacity = 255;
    std::uint8_t fill_opacity = 255;
    bool visible = true;
    /** Optional layer mask: its effective coverage (density, invert, feather, disabled) multiplies this layer's alpha. */
    std::shared_ptr<layermask::MaskCache> mask = nullptr;
    /**
     * Clipping-group member: the layer's alpha is multiplied by the alpha of the nearest layer below that is not
     * clipped (that base's own mask included). A hidden or empty base clips its group away.
     */
    bool clipped = false;
    /** Blend If sliders: the layer's alpha is scaled per pixel by its own and the backdrop's luminance. */
    BlendIf blend_if{};
    /**
     * Adjustment layer: instead of blending `pixels`, the LUT is applied to the composite below, weighted by
     * opacity × fill × mask coverage (and the clip base's alpha when clipped). `pixels` only supplies geometry.
     */
    std::shared_ptr<const color::Lut> adjustment = nullptr;
    /** Synthetic layers such as layer-style effects do not replace the layer's clipping-group alpha. */
    bool establishes_clip_base = true;
    /** Keeps a COW source snapshot alive when a RenderGraph outlives the request that supplied the raw pointer. */
    std::shared_ptr<const TileStore> pixels_owner = nullptr;
    /** Keeps synthetic source storage (for example a style-effect list) alive with the layer binding. */
    std::shared_ptr<const void> auxiliary_owner = nullptr;
};

struct GpuBlendLayer final {
    BlendMode mode = BlendMode::Normal;
    float opacity = 1.0F;
    std::uint32_t dissolve_seed = 0;
};

using GpuBlendStack = std::function<bool(std::span<const float> backdrop,
                                         std::span<const float> sources,
                                         std::span<const GpuBlendLayer> layers,
                                         /** Layer-major cumulative outputs, one RGBA tile per input layer. */
                                         std::span<float> output,
                                         std::uint32_t width,
                                         std::uint32_t height,
                                         bool linear_light,
                                         std::uint32_t origin_x,
                                         std::uint32_t origin_y,
                                         std::uint32_t coordinate_scale,
                                         std::uint32_t precision_bits)>;

/** Bottom-to-top compositor with cached cumulative CPU and GPU outputs per layer/tile. */
class RenderGraph final {
public:
    bool setLayers(std::vector<RenderLayer> layers, BlendSpace blend_space = BlendSpace::LinearLight);
    /** Replaces layer bindings while retaining entries validated by per-tile content and property revisions. */
    bool setLayersRetainingCache(std::vector<RenderLayer> layers, BlendSpace blend_space = BlendSpace::LinearLight);
    /** Optional Android GPU backend for RGBA8/16/32F stacks up to 64 layers; adjustment graphs use the CPU reference. */
    void setGpuBlendStack(GpuBlendStack blend_stack);
    /** Sets the document's virtual canvas background without allocating pixel-store tiles. */
    bool setBackground(std::array<float, 4> rgba);
    /** Composes one tile at a lazily generated 2^mip_level reduction. */
    std::shared_ptr<const TileBuffer> renderTile(TileCoordinate coordinate, std::uint32_t mip_level = 0);
    /** Invalidates cached outputs intersecting a half-open document-space dirty rectangle. */
    void invalidate(PixelRect dirty);
    void clearCache();
    std::size_t cachedTileCount() const;
    std::uint64_t cachedOutputHits() const;

private:
    static constexpr std::size_t kMaxCachedTiles = 64;
    bool replaceLayers(std::vector<RenderLayer> layers, BlendSpace blend_space, bool clear_cache);

    struct Key {
        std::uint64_t layer_id;
        TileCoordinate coordinate;
        std::uint32_t mip_level;
        std::uint64_t stack_revision;
        bool operator==(const Key&) const = default;
    };
    std::shared_ptr<const TileBuffer> cacheTileLocked(const Key& key, std::shared_ptr<const TileBuffer> tile);
    struct KeyHash {
        std::size_t operator()(const Key& key) const noexcept;
    };

    mutable std::mutex mutex_;
    std::vector<RenderLayer> layers_;
    GpuBlendStack gpu_blend_stack_;
    PixelFormat pixel_format_ = PixelFormat::RGBA8;
    BlendSpace blend_space_ = BlendSpace::LinearLight;
    std::array<float, 4> background_{};
    std::uint64_t generation_ = 0;
    std::uint64_t cached_output_hits_ = 0;
    std::unordered_map<Key, std::shared_ptr<const TileBuffer>, KeyHash> cache_;
};

}  // namespace croptop
