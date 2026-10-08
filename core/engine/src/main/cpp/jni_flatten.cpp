// Full-resolution flatten of a layer stack into a new store, through the same RenderGraph the viewport
// uses (blend modes in linear light, opacity/fill, visibility, store-attached masks and clipping), so exports and
// Merge/Stamp results match the canvas. Inputs mirror nativeRenderCompositePreview; the result takes the first
// store's format, tile pool and memory budget. Returns 0 on failure (see nativeFlattenLastError).
#include <jni.h>

#include <array>
#include <memory>
#include <stdexcept>
#include <string>
#include <vector>

#include "layer_style_attachment.h"
#include "jni_parallel.h"
#include "native_store_handle.h"
#include "render_graph.h"

namespace {
using croptop::jni::NativeTileStore;
thread_local std::string g_flatten_error;
}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_app_croptop_core_engine_NativeEngine_nativeFlattenLayers(JNIEnv* env, jobject, jlongArray store_handles, jlongArray layer_ids,
                                                               jintArray blend_modes, jintArray opacities, jintArray fill_opacities,
                                                               jbooleanArray visibility, jfloatArray background_color) {
    g_flatten_error.clear();
    try {
        if (store_handles == nullptr || layer_ids == nullptr || blend_modes == nullptr || opacities == nullptr || fill_opacities == nullptr ||
            visibility == nullptr || background_color == nullptr || env->GetArrayLength(background_color) != 4) {
            throw std::invalid_argument("flatten arguments are missing");
        }
        const auto count = env->GetArrayLength(store_handles);
        if (count <= 0 || count > 2000 || env->GetArrayLength(layer_ids) != count || env->GetArrayLength(blend_modes) != count ||
            env->GetArrayLength(opacities) != count || env->GetArrayLength(fill_opacities) != count || env->GetArrayLength(visibility) != count) {
            throw std::invalid_argument("flatten arrays must have one entry per layer");
        }
        const auto n = static_cast<std::size_t>(count);
        std::vector<jlong> handles(n), ids(n);
        std::vector<jint> modes(n), opacity(n), fill(n);
        std::vector<jboolean> visible(n);
        std::array<jfloat, 4> background{};
        env->GetLongArrayRegion(store_handles, 0, count, handles.data());
        env->GetLongArrayRegion(layer_ids, 0, count, ids.data());
        env->GetIntArrayRegion(blend_modes, 0, count, modes.data());
        env->GetIntArrayRegion(opacities, 0, count, opacity.data());
        env->GetIntArrayRegion(fill_opacities, 0, count, fill.data());
        env->GetBooleanArrayRegion(visibility, 0, count, visible.data());
        env->GetFloatArrayRegion(background_color, 0, 4, background.data());
        if (env->ExceptionCheck()) return 0;

        std::vector<std::unique_ptr<NativeTileStore>> snapshots;
        std::vector<croptop::RenderLayer> layers;
        std::vector<std::shared_ptr<const croptop::jni::StyleAttachment::Effects>> effects;
        snapshots.reserve(n);
        layers.reserve(n);
        for (std::size_t i = 0; i < n; ++i) {
            const auto* source = reinterpret_cast<const NativeTileStore*>(handles[i]);
            if (source == nullptr || ids[i] == 0 || modes[i] < 0 || modes[i] > 26 || opacity[i] < 0 || opacity[i] > 255 || fill[i] < 0 || fill[i] > 255) {
                throw std::invalid_argument("invalid layer in flatten input");
            }
            if (i > 0 && (source->pixels.width() != snapshots.front()->pixels.width() || source->pixels.height() != snapshots.front()->pixels.height())) {
                throw std::invalid_argument("all layers must have the document's size");
            }
            snapshots.push_back(std::make_unique<NativeTileStore>(source->pixels));
            croptop::RenderLayer layer{static_cast<std::uint64_t>(ids[i]), &snapshots.back()->pixels, static_cast<croptop::BlendMode>(modes[i]),
                                       static_cast<std::uint8_t>(opacity[i]), static_cast<std::uint8_t>(fill[i]), visible[i] == JNI_TRUE};
            layer.mask = source->mask;
            layer.clipped = source->clipped;
            layer.blend_if = source->blend_if;
            layer.adjustment = source->adjustment;
            croptop::jni::appendStyledLayer(layers, std::move(layer), *source, effects);
        }
        const auto& first = snapshots.front()->pixels;
        auto output = std::make_unique<NativeTileStore>(first.emptyLike(first.format()));
        croptop::RenderGraph graph;
        if (!graph.setLayers(std::move(layers), croptop::BlendSpace::LinearLight)) throw std::invalid_argument("the layer stack could not be composed");
        if (!graph.setBackground({background[0], background[1], background[2], background[3]})) throw std::invalid_argument("invalid background colour");
        const auto columns = 1U + (first.width() - 1U) / croptop::kTileEdge;
        const auto rows = 1U + (first.height() - 1U) / croptop::kTileEdge;
        const croptop::TileBuffer empty(output->pixels.tile_byte_size(), 0);
        const auto tile_count = static_cast<std::size_t>(columns) * rows;
        croptop::jni::parallelRenderBatches(
            tile_count, 64U, croptop::JobPriority::Background,
            [&](std::size_t tile_index) {
                const auto tx = static_cast<std::uint32_t>(tile_index % columns);
                const auto ty = static_cast<std::uint32_t>(tile_index / columns);
                const croptop::TileCoordinate coordinate{static_cast<std::int32_t>(tx), static_cast<std::int32_t>(ty)};
                const auto tile = graph.renderTile(coordinate, 0);
                if (tile == nullptr) throw std::runtime_error("could not compose a tile");
                // Keep the result sparse: fully transparent tiles are not stored.
                if (*tile != empty && !output->pixels.writeTile(coordinate, *tile)) {
                    throw std::runtime_error("could not store a flattened tile");
                }
            },
            [&] { graph.clearCache(); });
        return reinterpret_cast<jlong>(output.release());
    } catch (const std::exception& e) {
        g_flatten_error = e.what();
    } catch (...) {
        g_flatten_error = "unknown flatten failure";
    }
    return 0;
}

extern "C" JNIEXPORT jstring JNICALL Java_app_croptop_core_engine_NativeEngine_nativeFlattenLastError(JNIEnv* env, jobject) {
    return env->NewStringUTF(g_flatten_error.c_str());
}
