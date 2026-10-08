#include "shape_render_adapter.h"

#include <memory>
#include <new>
#include <stdexcept>
#include <utility>
#include <vector>

#include "jni_shape_layer.h"
#include "shape_layer.h"
#include "tile_store.h"

namespace croptop::shape::bridge {

bool renderBatchToTileStore(std::span<const std::uint8_t> encoded_batch, TileStore& target, std::string& error) {
    error.clear();
    if (target.width() == 0 || target.height() == 0 || target.format() != PixelFormat::RGBA8) {
        error = "shape cache must be a non-empty RGBA8 tile store";
        return false;
    }
    if (encoded_batch.size() > kMaxBatchBytes) {
        error = "shape batch exceeds the 32 MiB limit";
        return false;
    }

    try {
        std::vector<Record> records;
        if (!decodeBatch(encoded_batch, records, error)) return false;
        std::vector<ShapeRecord> shapes;
        shapes.reserve(records.size());
        for (auto& record : records) shapes.push_back(std::move(record.shape));

        if (!shape::render(shapes, target, error)) return false;
        return true;
    } catch (const std::bad_alloc&) {
        error = "insufficient memory to render shape layer";
    } catch (const std::exception& exception) {
        error = exception.what();
    } catch (...) {
        error = "unknown shape-layer rendering failure";
    }
    return false;
}

}  // namespace croptop::shape::bridge
