#pragma once

// Sequential, bounded-memory batch wrapper around the Develop CPU reference.

#include <chrono>
#include <cstddef>
#include <cstdint>
#include <functional>
#include <span>
#include <string>
#include <string_view>
#include <vector>

#include "develop_pipeline.h"

namespace croptop::develop::batch {

struct Item final {
    std::string id;
    const TileStore* source = nullptr;  // Must remain alive until run() returns.
    pipeline::Settings settings;
    pipeline::Request request;
};

enum class ItemState : std::uint8_t { Succeeded, Failed, Cancelled, SinkRejected };

struct ItemSummary final {
    std::string id;
    ItemState state = ItemState::Failed;
    std::string error;
    std::uint32_t output_width = 0, output_height = 0;
    std::chrono::nanoseconds elapsed{};
};

struct Summary final {
    std::vector<ItemSummary> items;  // Same order as input, including unstarted items.
    bool cancelled = false;
    std::string error;
};

using CancelCheck = std::function<bool()>;
using Progress = std::function<bool(float)>;  // Monotonic batch fraction, [0, 1].
using ResultSink = std::function<bool(std::size_t, std::string_view, pipeline::Result&&)>;

/**
 * Runs requests in input order and keeps at most one rendered result alive at a time. The sink must
 * consume and release each successful result before returning to keep the batch memory-bounded.
 * Individual render failures are
 * recorded and later items continue; cancellation, progress rejection, or sink rejection stops the
 * batch and marks unstarted items Cancelled. Callers may submit this synchronous operation to
 * JobPool; pipeline kernels use each item's worker_threads setting.
 */
Summary run(std::span<const Item> items, const ResultSink& sink, const CancelCheck& cancelled = {},
            const Progress& progress = {});

}  // namespace croptop::develop::batch
