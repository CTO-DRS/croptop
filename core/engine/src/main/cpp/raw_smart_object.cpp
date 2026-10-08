// RAW smart object; see raw_smart_object.h.
#include "raw_smart_object.h"

#include <span>
#include <utility>

namespace croptop::raw {

RawSmartObject::RawSmartObject(std::shared_ptr<const std::vector<std::uint8_t>> file, OpenOptions options, RawLimits limits, OpenedRaw opened)
    : file_(std::move(file)),
      options_(std::move(options)),
      limits_(limits),
      metadata_(std::move(opened.metadata)),
      warnings_(std::move(opened.warnings)) {
    object_ = std::make_unique<smart::SmartObject>(smart::convertToSmartObject(opened.pixels));
}

std::optional<RawSmartObject> RawSmartObject::create(std::shared_ptr<const std::vector<std::uint8_t>> file, OpenOptions options,
                                                     std::string& error, const RawLimits& limits) {
    if (file == nullptr || file->empty()) {
        error = "empty raw file";
        return std::nullopt;
    }
    auto opened = openFlattened(*file, options, error, limits);
    if (!opened) return std::nullopt;
    return RawSmartObject(std::move(file), std::move(options), limits, std::move(*opened));
}

bool RawSmartObject::setOptions(OpenOptions options, std::string& error) {
    auto opened = openFlattened(*file_, options, error, limits_);
    if (!opened) return false;
    // A new developed source has its own tile pool, and a TileStore cannot be assigned across pools: build the replacement
    // smart object and carry the editable parts over by move.
    smart::SmartObject next = smart::convertToSmartObject(opened->pixels);
    next.transforms = std::move(object_->transforms);
    next.filters = std::move(object_->filters);
    next.filter_mask_enabled = object_->filter_mask_enabled;
    if (object_->filter_mask) next.filter_mask.emplace(std::move(*object_->filter_mask));
    object_ = std::make_unique<smart::SmartObject>(std::move(next));
    options_ = std::move(options);
    metadata_ = std::move(opened->metadata);
    warnings_ = std::move(opened->warnings);
    return true;
}

std::vector<croptop::CroptopEmbeddedFile> RawSmartObject::toEmbedded(const std::string& id, const std::string& settings) const {
    const std::span<const std::uint8_t> xmp(reinterpret_cast<const std::uint8_t*>(settings.data()), settings.size());
    return {{id, kEmbeddedFileName, *file_}, {id, kEmbeddedSettingsName, xmp}};
}

std::optional<RawSmartObject> RawSmartObject::fromArchive(const croptop::CroptopArchive& archive, std::string_view id, std::string& error,
                                                          const RawLimits& limits) {
    auto file = std::make_shared<std::vector<std::uint8_t>>();
    if (!archive.readEmbedded(id, kEmbeddedFileName, *file, error)) return std::nullopt;
    OpenOptions options;
    std::vector<std::uint8_t> xmp;
    if (archive.readEmbedded(id, kEmbeddedSettingsName, xmp, error)) {
        auto parsed = optionsFromXmp(std::string_view(reinterpret_cast<const char*>(xmp.data()), xmp.size()), error);
        if (!parsed) return std::nullopt;
        options = std::move(*parsed);
    } else {
        error.clear();  // no settings saved: develop with the defaults
    }
    return create(std::move(file), std::move(options), error, limits);
}

std::optional<TileStore> RawSmartObject::render(std::uint32_t document_width, std::uint32_t document_height,
                                                const smart::RenderOptions& options, std::string& error) const {
    return smart::render(*object_, document_width, document_height, options, error);
}

}  // namespace croptop::raw
