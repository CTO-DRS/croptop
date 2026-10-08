// Ordered native batch rendering over the CPU Develop reference.
#include "develop_batch.h"

#include <algorithm>
#include <cmath>
#include <exception>
#include <utility>

namespace croptop::develop::batch {
namespace {

bool requestCancel(const CancelCheck& cancelled) {
    if (!cancelled) return false;
    try {
        return cancelled();
    } catch (...) {
        return true;
    }
}

bool emitProgress(const Progress& progress, float value) {
    if (!progress) return true;
    try {
        return progress(value);
    } catch (...) {
        return false;
    }
}

void markUnstarted(Summary& summary, std::size_t from) {
    for (std::size_t i = from; i < summary.items.size(); ++i) {
        summary.items[i].state = ItemState::Cancelled;
        summary.items[i].error = "batch cancelled before item started";
    }
}

}  // namespace

Summary run(std::span<const Item> items, const ResultSink& sink, const CancelCheck& cancelled, const Progress& progress) {
    Summary summary;
    summary.items.reserve(items.size());
    for (const auto& item : items) {
        ItemSummary item_summary;
        item_summary.id = item.id;
        summary.items.push_back(std::move(item_summary));
    }
    if (!sink && !items.empty()) {
        summary.error = "batch needs a result sink";
        for (auto& item : summary.items) {
            item.state = ItemState::Failed;
            item.error = summary.error;
        }
        return summary;
    }
    if (!emitProgress(progress, 0.0F)) {
        summary.cancelled = true;
        summary.error = "batch progress callback cancelled";
        markUnstarted(summary, 0);
        return summary;
    }
    if (items.empty()) {
        if (!emitProgress(progress, 1.0F)) {
            summary.cancelled = true;
            summary.error = "batch progress callback cancelled";
        }
        return summary;
    }

    float last_progress = 0.0F;
    for (std::size_t i = 0; i < items.size(); ++i) {
        auto& item_summary = summary.items[i];
        if (requestCancel(cancelled)) {
            summary.cancelled = true;
            summary.error = "batch cancelled";
            markUnstarted(summary, i);
            break;
        }
        const auto& item = items[i];
        if (item.source == nullptr) {
            item_summary.state = ItemState::Failed;
            item_summary.error = "batch item has no source image";
        } else {
            auto request = item.request;
            const auto original_progress = request.progress;
            bool progress_cancelled = false;
            request.progress = [&](float local) {
                if (requestCancel(cancelled)) {
                    progress_cancelled = true;
                    return false;
                }
                if (original_progress && !original_progress(local)) {
                    progress_cancelled = true;
                    return false;
                }
                const float overall = (static_cast<float>(i) + std::clamp(local, 0.0F, 1.0F)) / static_cast<float>(items.size());
                const float monotonic = std::max(last_progress, overall);
                if (!emitProgress(progress, monotonic)) {
                    progress_cancelled = true;
                    return false;
                }
                last_progress = monotonic;
                return true;
            };
            std::string error;
            auto result = pipeline::render(*item.source, item.settings, request, error);
            if (!result) {
                item_summary.state = progress_cancelled || error == "cancelled" || requestCancel(cancelled) ? ItemState::Cancelled : ItemState::Failed;
                item_summary.error = error.empty() && item_summary.state == ItemState::Cancelled ? "batch cancelled" : error;
                if (item_summary.state == ItemState::Cancelled) {
                    summary.cancelled = true;
                    summary.error = item_summary.error;
                    markUnstarted(summary, i + 1);
                    break;
                }
            } else {
                item_summary.output_width = result->width;
                item_summary.output_height = result->height;
                item_summary.elapsed = result->elapsed;
                if (requestCancel(cancelled)) {
                    item_summary.state = ItemState::Cancelled;
                    item_summary.error = "batch cancelled after item render";
                    summary.cancelled = true;
                    summary.error = item_summary.error;
                    markUnstarted(summary, i + 1);
                    break;
                }
                try {
                    if (!sink(i, item.id, std::move(*result))) {
                        item_summary.state = ItemState::SinkRejected;
                        item_summary.error = "batch result sink rejected an item";
                        summary.cancelled = true;
                        summary.error = item_summary.error;
                        markUnstarted(summary, i + 1);
                        break;
                    }
                } catch (const std::exception& exception) {
                    item_summary.state = ItemState::SinkRejected;
                    item_summary.error = exception.what();
                    summary.cancelled = true;
                    summary.error = item_summary.error;
                    markUnstarted(summary, i + 1);
                    break;
                } catch (...) {
                    item_summary.state = ItemState::SinkRejected;
                    item_summary.error = "batch result sink threw an unknown exception";
                    summary.cancelled = true;
                    summary.error = item_summary.error;
                    markUnstarted(summary, i + 1);
                    break;
                }
                item_summary.state = ItemState::Succeeded;
            }
        }
        const float completed = static_cast<float>(i + 1) / static_cast<float>(items.size());
        if (!emitProgress(progress, std::max(last_progress, completed))) {
            summary.cancelled = true;
            summary.error = "batch progress callback cancelled";
            markUnstarted(summary, i + 1);
            break;
        }
        last_progress = std::max(last_progress, completed);
    }
    return summary;
}

}  // namespace croptop::develop::batch
