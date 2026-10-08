#include "render_graph.h"

#include "pixel_plane.h"

#include <algorithm>
#include <cmath>
#include <cstring>
#include <limits>
#include <unordered_set>

namespace croptop {
namespace {

std::mutex& gpuStackDispatchMutex() {
    static std::mutex mutex;
    return mutex;
}

std::uint64_t stackContentRevision(const std::vector<RenderLayer>& layers, TileCoordinate coordinate,
                                  std::uint32_t mip_level, BlendSpace blend_space,
                                  std::size_t layer_count = std::numeric_limits<std::size_t>::max()) {
    std::uint64_t revision = 0x6a09e667f3bcc909ULL;
    const auto mix = [&revision](std::uint64_t value) {
        revision ^= value + 0x9e3779b97f4a7c15ULL + (revision << 6U) + (revision >> 2U);
    };
    mix(static_cast<std::uint64_t>(blend_space));
    const auto scale = std::uint64_t{1} << mip_level;
    const auto left = static_cast<std::uint64_t>(coordinate.x) * kTileEdge * scale;
    const auto top = static_cast<std::uint64_t>(coordinate.y) * kTileEdge * scale;
    const auto right = std::min<std::uint64_t>(layers.front().pixels->width(), left + kTileEdge * scale);
    const auto bottom = std::min<std::uint64_t>(layers.front().pixels->height(), top + kTileEdge * scale);
    const PixelRect source_region{static_cast<std::int32_t>(left), static_cast<std::int32_t>(top),
                                  static_cast<std::int32_t>(right), static_cast<std::int32_t>(bottom)};
    for (std::size_t index = 0; index < std::min(layer_count, layers.size()); ++index) {
        const auto& layer = layers[index];
        mix(layer.id);
        mix(layer.pixels->contentRevision(source_region));
        mix(layer.mask != nullptr ? layer.mask->contentRevision(source_region) : 0U);
        mix(static_cast<std::uint64_t>(layer.blend_mode));
        mix(layer.opacity);
        mix(layer.fill_opacity);
        mix(layer.visible ? 1U : 0U);
        mix(layer.clipped ? 1U : 0U);
        mix(layer.establishes_clip_base ? 1U : 0U);
        const auto mix_range = [&](const BlendIfRange& range) {
            mix((static_cast<std::uint64_t>(range.black_lo) << 24U) | (static_cast<std::uint64_t>(range.black_hi) << 16U) |
                (static_cast<std::uint64_t>(range.white_lo) << 8U) | range.white_hi);
        };
        mix_range(layer.blend_if.self);
        mix_range(layer.blend_if.under);
        for (std::size_t c = 0; c < 3; ++c) {
            mix_range(layer.blend_if.channel_self[c]);
            mix_range(layer.blend_if.channel_under[c]);
        }
        mix(static_cast<std::uint64_t>(reinterpret_cast<std::uintptr_t>(layer.adjustment.get())));
    }
    return revision;
}

}  // namespace

std::size_t RenderGraph::KeyHash::operator()(const Key& key) const noexcept {
    const auto coord_hash = TileCoordinateHash{}(key.coordinate);
    const auto layer_hash = std::hash<std::uint64_t>{}(key.layer_id);
    const auto mip_hash = std::hash<std::uint32_t>{}(key.mip_level);
    const auto stack_hash = std::hash<std::uint64_t>{}(key.stack_revision);
    return layer_hash ^ (coord_hash + 0x9e3779b97f4a7c15ULL + (layer_hash << 6U) + (layer_hash >> 2U)) ^
           (mip_hash + 0x9e3779b97f4a7c15ULL + (coord_hash << 6U) + (coord_hash >> 2U)) ^
           (stack_hash + 0x9e3779b97f4a7c15ULL + (layer_hash << 6U) + (stack_hash >> 2U));
}

std::shared_ptr<const TileBuffer> RenderGraph::cacheTileLocked(const Key& key,
                                                               std::shared_ptr<const TileBuffer> tile) {
    if (const auto existing = cache_.find(key); existing != cache_.end()) {
        return existing->second;
    }
    if (cache_.size() >= kMaxCachedTiles) cache_.erase(cache_.begin());
    return cache_.emplace(key, std::move(tile)).first->second;
}

bool RenderGraph::setLayers(std::vector<RenderLayer> layers, BlendSpace blend_space) {
    return replaceLayers(std::move(layers), blend_space, true);
}

bool RenderGraph::setLayersRetainingCache(std::vector<RenderLayer> layers, BlendSpace blend_space) {
    return replaceLayers(std::move(layers), blend_space, false);
}

bool RenderGraph::replaceLayers(std::vector<RenderLayer> layers, BlendSpace blend_space, bool clear_cache) {
    std::unordered_set<std::uint64_t> ids;
    std::uint32_t width = 0;
    std::uint32_t height = 0;
    PixelFormat pixel_format = PixelFormat::RGBA8;
    for (const auto& layer : layers) {
        if (layer.pixels == nullptr || (layer.pixels_owner != nullptr && layer.pixels_owner.get() != layer.pixels) ||
            !ids.insert(layer.id).second) return false;
        const auto format = layer.pixels->format();
        if (format != PixelFormat::RGBA8 && format != PixelFormat::RGBA16 && format != PixelFormat::RGBA32F) return false;
        if (width == 0) {
            width = layer.pixels->width();
            height = layer.pixels->height();
            pixel_format = format;
        } else if (layer.pixels->width() != width || layer.pixels->height() != height) {
            return false;
        } else if (format != pixel_format) {
            return false;
        }
    }
    const std::lock_guard lock(mutex_);
    layers_ = std::move(layers);
    pixel_format_ = pixel_format;
    blend_space_ = blend_space;
    if (clear_cache) cache_.clear();
    ++generation_;
    return true;
}

void RenderGraph::setGpuBlendStack(GpuBlendStack blend_stack) {
    const std::lock_guard lock(mutex_);
    gpu_blend_stack_ = std::move(blend_stack);
    cache_.clear();
    ++generation_;
}

bool RenderGraph::setBackground(std::array<float, 4> rgba) {
    if (std::any_of(rgba.begin(), rgba.end(), [](float value) { return !std::isfinite(value) || value < 0.0F || value > 1.0F; })) {
        return false;
    }
    const std::lock_guard lock(mutex_);
    if (background_ == rgba) return true;
    background_ = rgba;
    cache_.clear();
    ++generation_;
    return true;
}

std::shared_ptr<const TileBuffer> RenderGraph::renderTile(TileCoordinate coordinate, std::uint32_t mip_level) {
    if (coordinate.x < 0 || coordinate.y < 0 || mip_level > 31U) return nullptr;
    std::vector<RenderLayer> layers;
    BlendSpace blend_space;
    GpuBlendStack gpu_blend_stack;
    PixelFormat pixel_format;
    std::array<float, 4> background;
    std::uint64_t generation;
    {
        const std::lock_guard lock(mutex_);
        layers = layers_;
        blend_space = blend_space_;
        gpu_blend_stack = gpu_blend_stack_;
        pixel_format = pixel_format_;
        background = background_;
        generation = generation_;
    }
    if (layers.empty()) return nullptr;
    const auto stack_revision = stackContentRevision(layers, coordinate, mip_level, blend_space);
    const auto level_width = layers.front().pixels->mip_width(mip_level);
    const auto level_height = layers.front().pixels->mip_height(mip_level);
    const auto tile_origin_x = static_cast<std::uint64_t>(coordinate.x) * kTileEdge;
    const auto tile_origin_y = static_cast<std::uint64_t>(coordinate.y) * kTileEdge;
    if (tile_origin_x >= level_width || tile_origin_y >= level_height) return nullptr;
    const auto valid_width = static_cast<std::uint32_t>(std::min<std::uint64_t>(
        kTileEdge, static_cast<std::uint64_t>(level_width) - tile_origin_x));
    const auto valid_height = static_cast<std::uint32_t>(std::min<std::uint64_t>(
        kTileEdge, static_cast<std::uint64_t>(level_height) - tile_origin_y));
    const auto reduction = std::uint64_t{1} << mip_level;
    std::shared_ptr<const TileBuffer> backdrop;
    if (background[3] > 0.0F) {
        auto base = std::make_shared<TileBuffer>(layers.front().pixels->tile_byte_size(), 0);
        const auto bytes_per_pixel = layers.front().pixels->bytes_per_pixel();
        for (std::size_t offset = 0; offset < base->size(); offset += bytes_per_pixel) {
            if (pixel_format == PixelFormat::RGBA8) {
                for (std::size_t channel = 0; channel < 4; ++channel) {
                    (*base)[offset + channel] = static_cast<std::uint8_t>(std::lround(background[channel] * 255.0F));
                }
            } else if (pixel_format == PixelFormat::RGBA16) {
                for (std::size_t channel = 0; channel < 4; ++channel) {
                    const auto value = static_cast<std::uint16_t>(std::lround(background[channel] * 65535.0F));
                    std::memcpy(base->data() + offset + channel * sizeof(value), &value, sizeof(value));
                }
            } else {
                std::memcpy(base->data() + offset, background.data(), sizeof(float) * background.size());
            }
        }
        backdrop = std::move(base);
    }
    const Key key{layers.back().id, coordinate, mip_level, stack_revision};
    {
        const std::lock_guard lock(mutex_);
        if (generation_ != generation) return nullptr;
        if (stackContentRevision(layers, coordinate, mip_level, blend_space) != stack_revision) return nullptr;
        if (const auto found = cache_.find(key); found != cache_.end()) {
            ++cached_output_hits_;
            return found->second;
        }
    }
    constexpr std::size_t kMaxGpuStackLayers = 64;
    const bool gpu_eligible = gpu_blend_stack != nullptr &&
        (pixel_format == PixelFormat::RGBA8 || pixel_format == PixelFormat::RGBA16 ||
         pixel_format == PixelFormat::RGBA32F) &&
        layers.size() <= kMaxGpuStackLayers &&
        std::all_of(layers.begin(), layers.end(), [](const RenderLayer& layer) {
        return layer.adjustment == nullptr && !layer.blend_if.active();  // blend-if reads the running backdrop: CPU only
    });
    if (gpu_eligible) {
        const std::lock_guard gpu_lock(gpuStackDispatchMutex());
        {
            const std::lock_guard lock(mutex_);
            if (generation_ != generation) return nullptr;
            if (stackContentRevision(layers, coordinate, mip_level, blend_space) != stack_revision) return nullptr;
            if (const auto found = cache_.find(key); found != cache_.end()) {
                ++cached_output_hits_;
                return found->second;
            }
        }
        constexpr std::size_t kPixelsPerTile = static_cast<std::size_t>(kTileEdge) * kTileEdge;
        constexpr std::size_t kTileFloatCount = kPixelsPerTile * 4U;
        std::size_t cached_layer_count = 0;
        std::shared_ptr<const TileBuffer> cached_prefix;
        {
            const std::lock_guard lock(mutex_);
            for (std::size_t count = layers.size() - 1U; count > 0; --count) {
                const Key prefix_key{layers[count - 1U].id, coordinate, mip_level,
                                     stackContentRevision(layers, coordinate, mip_level, blend_space, count)};
                if (const auto found = cache_.find(prefix_key); found != cache_.end()) {
                    cached_layer_count = count;
                    cached_prefix = found->second;
                    ++cached_output_hits_;
                    break;
                }
            }
        }
        std::vector<float> gpu_backdrop(kPixelsPerTile * 4U, 0.0F);
        std::vector<float> gpu_sources(kPixelsPerTile * 4U * layers.size(), 0.0F);
        const auto suffix_layer_count = layers.size() - cached_layer_count;
        std::vector<float> gpu_output(kTileFloatCount * suffix_layer_count, 0.0F);
        std::vector<GpuBlendLayer> gpu_layers;
        gpu_layers.reserve(layers.size());
        std::shared_ptr<const TileBuffer> gpu_clip_base;
        if (cached_prefix != nullptr) {
            for (std::size_t pixel = 0; pixel < kPixelsPerTile; ++pixel) {
                paint::detail::readPixel(pixel_format,
                    cached_prefix->data() + pixel * layers.front().pixels->bytes_per_pixel(),
                    gpu_backdrop.data() + pixel * 4U);
            }
        } else if (backdrop != nullptr) {
            for (std::size_t pixel = 0; pixel < kPixelsPerTile; ++pixel) {
                paint::detail::readPixel(pixel_format, backdrop->data() + pixel * layers.front().pixels->bytes_per_pixel(),
                                         gpu_backdrop.data() + pixel * 4U);
            }
        }
        for (std::size_t layer_index = 0; layer_index < layers.size(); ++layer_index) {
            const auto& layer = layers[layer_index];
            const auto opacity = static_cast<float>(layer.opacity) * static_cast<float>(layer.fill_opacity) /
                                 (255.0F * 255.0F);
            gpu_layers.push_back({layer.blend_mode, layer.visible ? opacity : 0.0F,
                                  static_cast<std::uint32_t>(layer.id ^ (layer.id >> 32U))});
            auto source = layer.visible ? layer.pixels->readMipTile(mip_level, coordinate) : nullptr;
            if (source != nullptr && (layer.mask != nullptr || layer.clipped)) {
                auto shaped = std::make_shared<TileBuffer>(*source);
                if (layer.mask != nullptr) {
                    const auto mask = layer.mask->tileAtLevel(mip_level, coordinate);
                    if (mask == nullptr) return nullptr;
                    layermask::applyCoverage(pixel_format, *shaped, *mask);
                }
                if (layer.clipped) layermask::clipToBase(pixel_format, *shaped, pixel_format, gpu_clip_base.get());
                source = std::move(shaped);
            }
            if (!layer.clipped && layer.establishes_clip_base) gpu_clip_base = source;
            if (source == nullptr) continue;
            const auto source_offset = layer_index * kPixelsPerTile * 4U;
            const auto bpp = layer.pixels->bytes_per_pixel();
            for (std::size_t pixel = 0; pixel < kPixelsPerTile; ++pixel) {
                paint::detail::readPixel(pixel_format, source->data() + pixel * bpp,
                                         gpu_sources.data() + source_offset + pixel * 4U);
            }
        }
        const auto origin_x = static_cast<std::uint32_t>(tile_origin_x * reduction);
        const auto origin_y = static_cast<std::uint32_t>(tile_origin_y * reduction);
        const auto source_suffix = std::span<const float>(gpu_sources).subspan(cached_layer_count * kTileFloatCount);
        const auto layer_suffix = std::span<const GpuBlendLayer>(gpu_layers).subspan(cached_layer_count);
        if (gpu_blend_stack(gpu_backdrop, source_suffix, layer_suffix, gpu_output,
                            kTileEdge, kTileEdge,
                            pixel_format == PixelFormat::RGBA8 && blend_space == BlendSpace::LinearLight,
                            origin_x, origin_y, static_cast<std::uint32_t>(reduction),
                            pixel_format == PixelFormat::RGBA8 ? 8U :
                            pixel_format == PixelFormat::RGBA16 ? 16U : 32U)) {
            const auto bpp = layers.front().pixels->bytes_per_pixel();
            const std::lock_guard lock(mutex_);
            if (generation_ != generation) return nullptr;
            if (stackContentRevision(layers, coordinate, mip_level, blend_space) != stack_revision) return nullptr;
            std::shared_ptr<const TileBuffer> composed;
            for (std::size_t suffix_index = 0; suffix_index < suffix_layer_count; ++suffix_index) {
                auto prefix_tile = std::make_shared<TileBuffer>(layers.front().pixels->tile_byte_size(), 0);
                const auto output_offset = suffix_index * kTileFloatCount;
                for (std::size_t pixel = 0; pixel < kPixelsPerTile; ++pixel) {
                    paint::detail::writePixel(pixel_format, prefix_tile->data() + pixel * bpp,
                                              gpu_output.data() + output_offset + pixel * 4U);
                }
                const auto layer_index = cached_layer_count + suffix_index;
                const Key prefix_key{layers[layer_index].id, coordinate, mip_level,
                    stackContentRevision(layers, coordinate, mip_level, blend_space, layer_index + 1U)};
                for (auto it = cache_.begin(); it != cache_.end();) {
                    const auto& existing = it->first;
                    const bool superseded = existing.layer_id == prefix_key.layer_id &&
                                            existing.coordinate == prefix_key.coordinate &&
                                            existing.mip_level == prefix_key.mip_level &&
                                            existing.stack_revision != prefix_key.stack_revision;
                    it = superseded ? cache_.erase(it) : std::next(it);
                }
                composed = cacheTileLocked(prefix_key, std::move(prefix_tile));
            }
            return composed;
        }
    }
    // The shaped (masked) tile of the nearest layer below that is not clipped: what a clipping group clips to.
    std::shared_ptr<const TileBuffer> clip_base;
    for (std::size_t layer_index = 0; layer_index < layers.size(); ++layer_index) {
        const auto& layer = layers[layer_index];
        const Key node_key{layer.id, coordinate, mip_level,
                           stackContentRevision(layers, coordinate, mip_level, blend_space, layer_index + 1)};
        {
            const std::lock_guard lock(mutex_);
            if (generation_ != generation) return nullptr;
            if (const auto found = cache_.find(node_key); found != cache_.end()) {
                ++cached_output_hits_;
                backdrop = found->second;
                // A cached node has already been composited. Continue scanning source tiles only
                // to maintain the clip-base required by later clipped layers.
                if (layer.adjustment == nullptr && !layer.clipped && layer.establishes_clip_base) {
                    clip_base = layer.visible ? layer.pixels->readMipTile(mip_level, coordinate) : nullptr;
                    if (clip_base != nullptr && layer.mask != nullptr) {
                        auto shaped = std::make_shared<TileBuffer>(*clip_base);
                        layermask::applyCoverage(pixel_format, *shaped, *layer.mask->tileAtLevel(mip_level, coordinate));
                        clip_base = std::move(shaped);
                    }
                }
                continue;
            }
        }
        if (layer.adjustment != nullptr) {
            // Adjustment layer: recolour what lies below through the LUT; alpha is unchanged.
            if (layer.visible && backdrop != nullptr) {
                auto adjusted = std::make_shared<TileBuffer>(*backdrop);
                const auto coverage = layer.mask != nullptr ? layer.mask->tileAtLevel(mip_level, coordinate) : nullptr;
                const auto weight = static_cast<float>(layer.opacity) * static_cast<float>(layer.fill_opacity) / (255.0F * 255.0F);
                const auto bpp = layer.pixels->bytes_per_pixel();
                for (std::uint32_t y = 0; y < valid_height; ++y) {
                    for (std::uint32_t x = 0; x < valid_width; ++x) {
                        const auto pixel = static_cast<std::size_t>(y) * kTileEdge + x;
                        float w = weight;
                        if (coverage != nullptr) {
                            std::uint16_t raw = 0;
                            std::memcpy(&raw, coverage->data() + pixel * 2U, 2U);
                            w *= static_cast<float>(raw) / 65535.0F;
                        }
                        if (layer.clipped) {
                            float base[4] = {0, 0, 0, 0};
                            if (clip_base != nullptr) paint::detail::readPixel(pixel_format, clip_base->data() + pixel * bpp, base);
                            w *= std::clamp(base[3], 0.0F, 1.0F);
                        }
                        if (w <= 0.0F) continue;
                        float px[4];
                        paint::detail::readPixel(pixel_format, adjusted->data() + pixel * bpp, px);
                        const auto mapped = layer.adjustment->apply({px[0], px[1], px[2]});
                        for (std::size_t c = 0; c < 3; ++c) px[c] += w * (mapped[c] - px[c]);
                        paint::detail::writePixel(pixel_format, adjusted->data() + pixel * bpp, px);
                    }
                }
                backdrop = std::move(adjusted);
            }
            continue;
        }
        auto source = layer.visible ? layer.pixels->readMipTile(mip_level, coordinate) : nullptr;
        if (source != nullptr && (layer.mask != nullptr || layer.clipped)) {
            auto shaped = std::make_shared<TileBuffer>(*source);
            if (layer.mask != nullptr) layermask::applyCoverage(pixel_format, *shaped, *layer.mask->tileAtLevel(mip_level, coordinate));
            if (layer.clipped) layermask::clipToBase(pixel_format, *shaped, pixel_format, clip_base.get());
            source = std::move(shaped);
        }
        if (!layer.clipped && layer.establishes_clip_base) clip_base = source;
        if (layer.visible && source != nullptr && layer.blend_if.active()) {
            // Blend If scales the layer's alpha by its own and the backdrop's luminance; the clip base stays unscaled.
            auto weighted = std::make_shared<TileBuffer>(*source);
            const auto bpp = layer.pixels->bytes_per_pixel();
            for (std::uint32_t y = 0; y < valid_height; ++y) {
                for (std::uint32_t x = 0; x < valid_width; ++x) {
                    const auto pixel = static_cast<std::size_t>(y) * kTileEdge + x;
                    float src[4];
                    paint::detail::readPixel(pixel_format, weighted->data() + pixel * bpp, src);
                    float under[4] = {0.0F, 0.0F, 0.0F, 0.0F};
                    if (backdrop != nullptr) paint::detail::readPixel(pixel_format, backdrop->data() + pixel * bpp, under);
                    const float w = layer.blend_if.weight(src, under);
                    if (w >= 1.0F) continue;
                    src[3] *= w;
                    paint::detail::writePixel(pixel_format, weighted->data() + pixel * bpp, src);
                }
            }
            source = std::move(weighted);
        }
        std::shared_ptr<const TileBuffer> output = backdrop;
        if (layer.visible && source != nullptr) {
            auto composed = std::make_shared<TileBuffer>(layer.pixels->tile_byte_size(), 0);
            if (backdrop != nullptr) std::memcpy(composed->data(), backdrop->data(), composed->size());
            const auto opacity = static_cast<float>(layer.opacity) * static_cast<float>(layer.fill_opacity) /
                                 (255.0F * 255.0F);
            if (pixel_format == PixelFormat::RGBA8) {
                const auto byte_opacity = static_cast<std::uint8_t>(std::lround(opacity * 255.0F));
                for (std::uint32_t y = 0; y < valid_height; ++y) {
                    for (std::uint32_t x = 0; x < valid_width; ++x) {
                        const auto offset = (static_cast<std::size_t>(y) * kTileEdge + x) * sizeof(BlendPixel8);
                        BlendPixel8 backdrop_pixel{};
                        BlendPixel8 source_pixel{};
                        std::memcpy(backdrop_pixel.data(), composed->data() + offset, sizeof(BlendPixel8));
                        std::memcpy(source_pixel.data(), source->data() + offset, sizeof(BlendPixel8));
                        const auto pixel_x = static_cast<std::uint32_t>((tile_origin_x + x) * reduction);
                        const auto pixel_y = static_cast<std::uint32_t>((tile_origin_y + y) * reduction);
                        const auto result = blendRgba8(
                            backdrop_pixel, source_pixel, layer.blend_mode, byte_opacity, pixel_x, pixel_y,
                            static_cast<std::uint32_t>(layer.id ^ (layer.id >> 32U)), blend_space);
                        std::memcpy(composed->data() + offset, result.data(), sizeof(BlendPixel8));
                    }
                }
            } else {
                const auto pixel_bytes = pixel_format == PixelFormat::RGBA32F ? sizeof(BlendPixelF) : 8U;
                for (std::uint32_t y = 0; y < valid_height; ++y) {
                    for (std::uint32_t x = 0; x < valid_width; ++x) {
                        const auto offset = (static_cast<std::size_t>(y) * kTileEdge + x) * pixel_bytes;
                        BlendPixelF backdrop_pixel{};
                        BlendPixelF source_pixel{};
                        if (pixel_format == PixelFormat::RGBA32F) {
                            std::memcpy(backdrop_pixel.data(), composed->data() + offset, sizeof(BlendPixelF));
                            std::memcpy(source_pixel.data(), source->data() + offset, sizeof(BlendPixelF));
                        } else {
                            for (std::size_t c = 0; c < 4; ++c) {
                                std::uint16_t backdrop16 = 0;
                                std::uint16_t source16 = 0;
                                std::memcpy(&backdrop16, composed->data() + offset + c * sizeof(backdrop16), sizeof(backdrop16));
                                std::memcpy(&source16, source->data() + offset + c * sizeof(source16), sizeof(source16));
                                backdrop_pixel[c] = static_cast<float>(backdrop16) / 65535.0F;
                                source_pixel[c] = static_cast<float>(source16) / 65535.0F;
                            }
                        }
                        const auto pixel_x = static_cast<std::uint32_t>((tile_origin_x + x) * reduction);
                        const auto pixel_y = static_cast<std::uint32_t>((tile_origin_y + y) * reduction);
                        const auto result = blendRgbaF(
                            backdrop_pixel, source_pixel, layer.blend_mode, opacity, pixel_x, pixel_y,
                            static_cast<std::uint32_t>(layer.id ^ (layer.id >> 32U)));
                        if (pixel_format == PixelFormat::RGBA32F) {
                            std::memcpy(composed->data() + offset, result.data(), sizeof(BlendPixelF));
                        } else {
                            for (std::size_t c = 0; c < 4; ++c) {
                                const auto encoded = static_cast<std::uint16_t>(std::lround(
                                    std::clamp(result[c], 0.0F, 1.0F) * 65535.0F));
                                std::memcpy(composed->data() + offset + c * sizeof(encoded), &encoded, sizeof(encoded));
                            }
                        }
                    }
                }
            }
            output = std::move(composed);
        }
        backdrop = std::move(output);
        if (backdrop != nullptr) {
            const std::lock_guard lock(mutex_);
            if (generation_ != generation) return nullptr;
            if (stackContentRevision(layers, coordinate, mip_level, blend_space) != stack_revision) return nullptr;
            for (auto it = cache_.begin(); it != cache_.end();) {
                const auto& existing = it->first;
                const bool superseded = existing.layer_id == node_key.layer_id &&
                                        existing.coordinate == node_key.coordinate &&
                                        existing.mip_level == node_key.mip_level &&
                                        existing.stack_revision != node_key.stack_revision;
                it = superseded ? cache_.erase(it) : std::next(it);
            }
            cacheTileLocked(node_key, backdrop);
        }
    }
    if (backdrop == nullptr) backdrop = std::make_shared<const TileBuffer>(layers.front().pixels->tile_byte_size(), 0);
    const std::lock_guard lock(mutex_);
    if (generation_ != generation) return nullptr;
    if (stackContentRevision(layers, coordinate, mip_level, blend_space) != stack_revision) return nullptr;
    for (auto it = cache_.begin(); it != cache_.end();) {
        const auto& existing = it->first;
        const bool superseded = existing.layer_id == key.layer_id && existing.coordinate == key.coordinate &&
                                existing.mip_level == key.mip_level && existing.stack_revision != stack_revision;
        it = superseded ? cache_.erase(it) : std::next(it);
    }
    return cacheTileLocked(key, std::move(backdrop));
}

void RenderGraph::invalidate(PixelRect dirty) {
    if (dirty.right <= dirty.left || dirty.bottom <= dirty.top) return;
    const std::lock_guard lock(mutex_);
    ++generation_;
    for (auto it = cache_.begin(); it != cache_.end();) {
        const auto reduction = std::int64_t{1} << it->first.mip_level;
        const auto first_x = std::max<std::int64_t>(0, (dirty.left / reduction) / kTileEdge);
        const auto first_y = std::max<std::int64_t>(0, (dirty.top / reduction) / kTileEdge);
        const auto last_x = std::max<std::int64_t>(0, ((dirty.right - 1) / reduction) / kTileEdge);
        const auto last_y = std::max<std::int64_t>(0, ((dirty.bottom - 1) / reduction) / kTileEdge);
        const auto tile = it->first.coordinate;
        if (tile.x >= first_x && tile.x <= last_x && tile.y >= first_y && tile.y <= last_y) it = cache_.erase(it);
        else ++it;
    }
}

void RenderGraph::clearCache() {
    const std::lock_guard lock(mutex_);
    cache_.clear();
    ++generation_;
}

std::size_t RenderGraph::cachedTileCount() const {
    const std::lock_guard lock(mutex_);
    return cache_.size();
}

std::uint64_t RenderGraph::cachedOutputHits() const {
    const std::lock_guard lock(mutex_);
    return cached_output_hits_;
}

}  // namespace croptop
