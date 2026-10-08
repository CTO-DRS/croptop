#pragma once

// Layer styles attached to a layer's NativeTileStore (like its mask and adjustment LUT), so every
// composite (viewport, sampling, flatten/export) draws the effects without Kotlin-side effect stores. Effects are
// rendered on demand and cached against the layer's tile identity; clones share the attachment and its cache.

#include <memory>
#include <mutex>
#include <optional>
#include <utility>
#include <vector>

#include "layer_styles.h"
#include "render_graph.h"
#include "tile_store.h"

namespace croptop::jni {

struct NativeTileStore;

class StyleAttachment final {
public:
    using Effects = std::vector<styles::EffectLayer>;

    /** [pattern] (RGBA) is owned here and wired into the pattern overlay, so the attachment outlives the caller's store. */
    explicit StyleAttachment(styles::LayerStyles settings, std::optional<TileStore> pattern = std::nullopt)
        : pattern_(std::move(pattern)), settings_(std::move(settings)) {
        settings_.pattern_overlay.pattern = pattern_ ? &*pattern_ : nullptr;
        if (settings_.pattern_overlay.pattern == nullptr) settings_.pattern_overlay.enabled = false;
    }

    const styles::LayerStyles& settings() const { return settings_; }

    /** The effects for `pixels`, re-rendered only when its tiles changed; null if rendering failed. */
    std::shared_ptr<const Effects> effectsFor(const TileStore& pixels);

private:
    const std::optional<TileStore> pattern_;
    styles::LayerStyles settings_;
    std::mutex mutex_;
    std::vector<std::pair<TileCoordinate, std::weak_ptr<const TileBuffer>>> fingerprint_;
    std::shared_ptr<const Effects> effects_;
};

/**
 * Appends `base` to `layers`; when `source` carries styles, its below-content effects go first and the rest after,
 * each with the layer's opacity, visibility, mask and clipping (fill opacity applies to the content only).
 * `keep` holds the effect stores alive while `layers` is used.
 */
void appendStyledLayer(std::vector<RenderLayer>& layers, RenderLayer base, const NativeTileStore& source,
                       std::vector<std::shared_ptr<const StyleAttachment::Effects>>& keep);

}  // namespace croptop::jni
