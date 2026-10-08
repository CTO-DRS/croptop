#pragma once

#include "blend_if.h"

// Shared opaque JNI handle definitions for the native engine translation units.
#include <cstddef>
#include <cstdint>
#include <memory>
#include <string>
#include <utility>

#include "layer_mask.h"
#include "lut.h"
#include "scratch_store.h"
#include "tile_store.h"

namespace croptop::jni {

class StyleAttachment;  // layer_style_attachment.h

struct NativeTileStore final {
    NativeTileStore(PixelFormat format, std::uint32_t width, std::uint32_t height,
                    std::shared_ptr<TilePool> tile_pool, std::shared_ptr<MemoryBudgetManager> memory_budget)
        : pixels(format, width, height, std::move(tile_pool), std::move(memory_budget)) {}
    explicit NativeTileStore(const TileStore& source) : pixels(source) {}
    explicit NativeTileStore(TileStore&& source) : pixels(std::move(source)) {}
    /** Clone for a history snapshot or render pass: same pixels (COW) and the same mask/clipping state. */
    explicit NativeTileStore(const NativeTileStore& source)
        : pixels(source.pixels), mask(source.mask), clipped(source.clipped), blend_if(source.blend_if), adjustment(source.adjustment), styles(source.styles) {}

    TileStore pixels;
    /** Optional layer mask (shared: snapshots see the cache the layer had when they were taken). */
    std::shared_ptr<layermask::MaskCache> mask;
    /** Clipping group member (Layer ▸ Create Clipping Mask). */
    bool clipped = false;
    /** Blend If sliders (Layer ▸ Blending Options); see RenderLayer::blend_if. */
    BlendIf blend_if{};
    /** Adjustment layer LUT (the store itself stays empty); see RenderLayer::adjustment. */
    std::shared_ptr<const color::Lut> adjustment;
    /** Layer styles (Layer ▸ Layer Style); composites draw its effects around the pixels. */
    std::shared_ptr<StyleAttachment> styles;
};

struct NativeMemoryBudget final {
    NativeMemoryBudget(const std::string& scratch_path, std::size_t budget_bytes)
        : scratch(std::make_shared<DiskScratchStore>(scratch_path)),
          manager(std::make_shared<MemoryBudgetManager>(budget_bytes, scratch)),
          tile_pool(std::make_shared<TilePool>()) {}

    std::shared_ptr<DiskScratchStore> scratch;
    std::shared_ptr<MemoryBudgetManager> manager;
    std::shared_ptr<TilePool> tile_pool;
};

}  // namespace croptop::jni
