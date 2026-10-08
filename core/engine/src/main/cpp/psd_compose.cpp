#include "psd_compose.h"

#include <algorithm>
#include <cstring>
#include <exception>
#include <memory>

#include "pixel_plane.h"

namespace croptop::psd {
namespace {

struct Node {
    const Layer* layer = nullptr;  // null for the root
    bool group = false;
    std::vector<std::unique_ptr<Node>> children;  // bottom to top
};

/** Builds the group tree from the bottom-to-top record list. */
std::unique_ptr<Node> buildTree(const Document& doc, std::vector<std::string>& warnings) {
    auto root = std::make_unique<Node>();
    std::vector<Node*> stack{root.get()};
    for (const auto& layer : doc.layers) {
        if (layer.kind == LayerKind::GroupDivider) {
            auto group = std::make_unique<Node>();
            group->group = true;
            Node* raw = group.get();
            stack.back()->children.push_back(std::move(group));
            stack.push_back(raw);
        } else if (layer.kind == LayerKind::GroupFolder) {
            if (stack.size() == 1) {
                warnings.push_back("a group folder without its divider was treated as an empty group");
                auto group = std::make_unique<Node>();
                group->group = true;
                group->layer = &layer;
                stack.back()->children.push_back(std::move(group));
                continue;
            }
            stack.back()->layer = &layer;
            stack.pop_back();
        } else {
            auto node = std::make_unique<Node>();
            node->layer = &layer;
            stack.back()->children.push_back(std::move(node));
        }
    }
    if (stack.size() > 1) warnings.push_back("unterminated layer groups were closed at the top");
    return root;
}

/**
 * dst ← blend(dst, src × coverage), where coverage = opacity × (mask) × (clip alpha). `mask` is A16; `clip` is an
 * RGBA store whose alpha limits src. Only tiles present in src are visited.
 */
void composite(TileStore& dst, const TileStore& src, BlendMode mode, float opacity, const TileStore* mask, const TileStore* clip) {
    if (opacity <= 0.0F) return;
    const auto dst_bpp = dst.bytes_per_pixel(), src_bpp = src.bytes_per_pixel();
    for (const auto c : src.tileCoordinates()) {
        const auto s = src.readTile(c);
        const auto m = mask != nullptr ? mask->readTile(c) : nullptr;
        if (mask != nullptr && m == nullptr) continue;
        const auto k = clip != nullptr ? clip->readTile(c) : nullptr;
        if (clip != nullptr && k == nullptr) continue;
        const auto existing = dst.readTile(c);
        TileBuffer out = existing ? *existing : TileBuffer(dst.tile_byte_size(), 0);
        bool changed = false;
        for (std::size_t i = 0; i < kTileEdge * kTileEdge; ++i) {
            BlendPixelF sp{}, bp{};
            paint::detail::readPixel(src.format(), s->data() + i * src_bpp, sp.data());
            if (sp[3] <= 0.0F) continue;
            if (m != nullptr) {
                std::uint16_t raw = 0;
                std::memcpy(&raw, m->data() + i * 2U, 2U);
                sp[3] *= raw / 65535.0F;
            }
            if (k != nullptr) {
                BlendPixelF kp{};
                paint::detail::readPixel(clip->format(), k->data() + i * clip->bytes_per_pixel(), kp.data());
                sp[3] *= kp[3];
            }
            if (sp[3] <= 0.0F) continue;
            paint::detail::readPixel(dst.format(), out.data() + i * dst_bpp, bp.data());
            const auto result = blendRgbaF(bp, sp, mode, opacity);
            paint::detail::writePixel(dst.format(), out.data() + i * dst_bpp, result.data());
            changed = true;
        }
        if (changed && !dst.writeTile(c, out)) throw std::runtime_error("could not store the composite");
    }
}

/** dst ← lerp(before, dst, amount × mask) per pixel (pass-through group opacity and mask). */
void lerpTowards(TileStore& dst, const TileStore& before, float amount, const TileStore* mask) {
    if (amount >= 1.0F && mask == nullptr) return;
    const auto bpp = dst.bytes_per_pixel();
    auto coordinates = dst.tileCoordinates();
    for (const auto c : before.tileCoordinates()) coordinates.push_back(c);
    std::sort(coordinates.begin(), coordinates.end(), [](const TileCoordinate& a, const TileCoordinate& b) { return a.y != b.y ? a.y < b.y : a.x < b.x; });
    coordinates.erase(std::unique(coordinates.begin(), coordinates.end()), coordinates.end());
    for (const auto c : coordinates) {
        const auto d = dst.readTile(c), b = before.readTile(c);
        const auto m = mask != nullptr ? mask->readTile(c) : nullptr;
        TileBuffer out = d ? *d : TileBuffer(dst.tile_byte_size(), 0);
        for (std::size_t i = 0; i < kTileEdge * kTileEdge; ++i) {
            float t = amount;
            if (mask != nullptr) {
                std::uint16_t raw = 0;
                if (m) std::memcpy(&raw, m->data() + i * 2U, 2U);
                t *= raw / 65535.0F;
            }
            std::array<float, 4> dp{}, bp{};
            if (d) paint::detail::readPixel(dst.format(), d->data() + i * bpp, dp.data());
            if (b) paint::detail::readPixel(before.format(), b->data() + i * bpp, bp.data());
            // Interpolate premultiplied so transparent backdrops do not tint the result.
            std::array<float, 4> out_px{};
            out_px[3] = bp[3] + t * (dp[3] - bp[3]);
            for (std::size_t k = 0; k < 3; ++k) {
                const float premultiplied = bp[k] * bp[3] + t * (dp[k] * dp[3] - bp[k] * bp[3]);
                out_px[k] = out_px[3] > 1e-6F ? premultiplied / out_px[3] : 0.0F;
            }
            paint::detail::writePixel(dst.format(), out.data() + i * bpp, out_px.data());
        }
        if (!dst.writeTile(c, out)) throw std::runtime_error("could not store the composite");
    }
}

/** Multiplies a store's alpha by an A16 mask (isolated group masks). */
void applyMask(TileStore& image, const TileStore& mask) {
    const auto bpp = image.bytes_per_pixel();
    for (const auto c : image.tileCoordinates()) {
        const auto m = mask.readTile(c);
        TileBuffer out = *image.readTile(c);
        for (std::size_t i = 0; i < kTileEdge * kTileEdge; ++i) {
            std::uint16_t raw = 0;
            if (m) std::memcpy(&raw, m->data() + i * 2U, 2U);
            std::array<float, 4> px{};
            paint::detail::readPixel(image.format(), out.data() + i * bpp, px.data());
            px[3] *= raw / 65535.0F;
            paint::detail::writePixel(image.format(), out.data() + i * bpp, px.data());
        }
        if (!image.writeTile(c, out)) throw std::runtime_error("could not store the composite");
    }
}

class Composer final {
public:
    Composer(const Document& doc, std::vector<std::string>& warnings) : doc_(doc), warnings_(warnings) {}

    void children(const Node& group, TileStore& canvas) {
        const TileStore* clip_base = nullptr;
        for (const auto& child : group.children) {
            const Layer* layer = child->layer;
            if (child->group) {
                if (layer != nullptr && !layer->visible) continue;
                groupNode(*child, canvas);
                clip_base = nullptr;
                continue;
            }
            if (layer == nullptr || !layer->visible) continue;
            const bool clipped = layer->clipped && clip_base != nullptr;
            if (!layer->clipped) clip_base = nullptr;
            const float opacity = layer->opacity / 255.0F, fill = layer->fill / 255.0F;
            const TileStore* mask = layer->mask && !layer->mask->disabled ? &layer->mask->coverage : nullptr;
            std::vector<std::string> ignored;
            if (const auto adjustment = adjustmentOf(*layer, ignored)) {
                if (layer->clipped) warnings_.push_back("clipped adjustment layer \"" + layer->name + "\" applied unclipped");
                std::string error;
                if (!adjust::apply(canvas, *adjustment, mask, opacity * fill, error)) warnings_.push_back("adjustment \"" + layer->name + "\": " + error);
                continue;
            }
            if (!layer->pixels) continue;
            // Effects.
            std::vector<styles::EffectLayer> effects;
            if (const auto styles = effectsOf(*layer, {doc_.global_light_angle, doc_.global_light_altitude}, ignored)) {
                std::string error;
                if (auto rendered = styles::render(*layer->pixels, *styles, error)) effects = std::move(*rendered);
                else warnings_.push_back("effects of \"" + layer->name + "\": " + error);
            }
            const TileStore* clip = clipped ? clip_base : nullptr;
            for (const auto& e : effects)
                if (e.below_content) composite(canvas, e.pixels, e.mode, e.opacity * opacity, nullptr, clip);
            composite(canvas, *layer->pixels, layer->mode, opacity * fill, mask, clip);
            for (const auto& e : effects)
                if (!e.below_content) composite(canvas, e.pixels, e.mode, e.opacity * opacity, nullptr, clip);
            if (!layer->clipped) {
                // This layer's own alpha (with its mask) is the base for clipped layers above it.
                owned_bases_.push_back(std::make_unique<TileStore>(*layer->pixels));
                if (mask != nullptr) applyMask(*owned_bases_.back(), *mask);
                clip_base = owned_bases_.back().get();
            }
        }
    }

    void groupNode(const Node& group, TileStore& canvas) {
        const Layer* props = group.layer;
        const float opacity = props != nullptr ? props->opacity / 255.0F : 1.0F;
        const TileStore* mask = props != nullptr && props->mask && !props->mask->disabled ? &props->mask->coverage : nullptr;
        const bool pass_through = props == nullptr || props->pass_through;
        if (pass_through) {
            const TileStore before(canvas);
            children(group, canvas);
            lerpTowards(canvas, before, opacity, mask);
            return;
        }
        TileStore isolated(canvas.format(), canvas.width(), canvas.height());
        children(group, isolated);
        if (mask != nullptr) applyMask(isolated, *mask);
        composite(canvas, isolated, props->mode, opacity, nullptr, nullptr);
    }

private:
    const Document& doc_;
    std::vector<std::string>& warnings_;
    std::vector<std::unique_ptr<TileStore>> owned_bases_;
};

}  // namespace

std::optional<TileStore> compose(const Document& document, std::string& error, std::vector<std::string>* warnings) {
    std::vector<std::string> local;
    auto& notes = warnings != nullptr ? *warnings : local;
    if (document.width == 0 || document.height == 0) {
        error = "the document has no size";
        return std::nullopt;
    }
    const PixelFormat format = document.depth == 8 ? PixelFormat::RGBA8 : document.depth == 16 ? PixelFormat::RGBA16 : PixelFormat::RGBA32F;
    try {
        TileStore canvas(format, document.width, document.height);
        const auto tree = buildTree(document, notes);
        Composer composer(document, notes);
        composer.children(*tree, canvas);
        return canvas;
    } catch (const std::bad_alloc&) {
        error = "not enough memory to composite the document";
    } catch (const std::exception& e) {
        error = e.what();
    }
    return std::nullopt;
}

}  // namespace croptop::psd
