// See layer_style_attachment.h.
#include "layer_style_attachment.h"

#include <algorithm>
#include <cmath>
#include <string>

#include "native_store_handle.h"

namespace croptop::jni {

std::shared_ptr<const StyleAttachment::Effects> StyleAttachment::effectsFor(const TileStore& pixels) {
    auto coordinates = pixels.tileCoordinates();
    std::sort(coordinates.begin(), coordinates.end(), [](const TileCoordinate& a, const TileCoordinate& b) {
        return a.y != b.y ? a.y < b.y : a.x < b.x;
    });
    std::vector<std::pair<TileCoordinate, std::shared_ptr<const TileBuffer>>> current;
    current.reserve(coordinates.size());
    for (const auto& coordinate : coordinates) current.emplace_back(coordinate, pixels.readTile(coordinate));

    const std::lock_guard lock(mutex_);
    bool same = effects_ != nullptr && fingerprint_.size() == current.size();
    for (std::size_t i = 0; same && i < current.size(); ++i) {
        same = fingerprint_[i].first == current[i].first && fingerprint_[i].second.lock() == current[i].second;
    }
    if (same) return effects_;
    std::string error;
    auto rendered = styles::render(pixels, settings_, error);
    if (!rendered) return nullptr;
    effects_ = std::make_shared<const Effects>(std::move(*rendered));
    fingerprint_.clear();
    fingerprint_.reserve(current.size());
    for (auto& [coordinate, buffer] : current) fingerprint_.emplace_back(coordinate, buffer);
    return effects_;
}

void appendStyledLayer(std::vector<RenderLayer>& layers, RenderLayer base, const NativeTileStore& source,
                       std::vector<std::shared_ptr<const StyleAttachment::Effects>>& keep) {
    const auto effects = source.styles != nullptr && source.adjustment == nullptr ? source.styles->effectsFor(source.pixels) : nullptr;
    if (effects == nullptr || effects->empty()) {
        layers.push_back(std::move(base));
        return;
    }
    keep.push_back(effects);
    // Effect ids only need to be unique within one graph: tag them with the top bit plus the layer id and index.
    const auto effectLayer = [&](const styles::EffectLayer& effect, std::size_t index) {
        RenderLayer layer{(1ULL << 63) | (base.id << 5) | static_cast<std::uint64_t>(index), &effect.pixels, effect.mode,
                          static_cast<std::uint8_t>(std::lround(std::clamp(effect.opacity, 0.0F, 1.0F) * static_cast<float>(base.opacity))), 255,
                          base.visible};
        layer.mask = base.mask;
        layer.clipped = base.clipped;
        layer.establishes_clip_base = false;
        layer.auxiliary_owner = effects;
        return layer;
    };
    for (std::size_t i = 0; i < effects->size(); ++i) {
        if ((*effects)[i].below_content) layers.push_back(effectLayer((*effects)[i], i));
    }
    const auto mask = base.mask;
    const auto clipped = base.clipped;
    layers.push_back(std::move(base));
    for (std::size_t i = 0; i < effects->size(); ++i) {
        if (!(*effects)[i].below_content) {
            auto layer = effectLayer((*effects)[i], i);
            layer.mask = mask;
            layer.clipped = clipped;
            layers.push_back(std::move(layer));
        }
    }
}

}  // namespace croptop::jni
