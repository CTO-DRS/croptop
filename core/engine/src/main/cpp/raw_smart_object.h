#pragma once

// "Open as RAW smart object": a smart object whose contents are a camera raw plus Develop settings.
// The raw file bytes stay untouched; changing the settings re-develops the source while the object's transform stack,
// smart filters and filter mask are kept, so Develop edits stay re-editable under later Photoshop-style edits.

#include <cstdint>
#include <memory>
#include <optional>
#include <string>
#include <string_view>
#include <vector>

#include "croptop_format.h"
#include "raw_open.h"
#include "smart_object.h"
#include "tile_store.h"

namespace croptop::raw {

class RawSmartObject final {
public:
    /** Develops the raw once with `options`. nullopt with `error` for unsupported files or pipeline failures. */
    static std::optional<RawSmartObject> create(std::shared_ptr<const std::vector<std::uint8_t>> file, OpenOptions options,
                                                std::string& error, const RawLimits& limits = {});

    /**
     * Re-develops the source with new settings; transforms, filters and the filter mask are kept. On failure the object
     * is unchanged and `error` says why.
     */
    bool setOptions(OpenOptions options, std::string& error);

    const OpenOptions& options() const { return options_; }
    const RawMetadata& metadata() const { return metadata_; }
    const std::vector<std::string>& warnings() const { return warnings_; }
    /** The raw file as embedded (for saving into a document). */
    const std::vector<std::uint8_t>& file() const { return *file_; }

    /** Transform stack, smart filters and filter mask; edit them freely, the developed source is replaced by setOptions only. */
    smart::SmartObject& object() { return *object_; }
    const smart::SmartObject& object() const { return *object_; }

    /** Names of the two files a document embeds for this object (see toEmbedded / fromArchive). */
    static constexpr const char* kEmbeddedFileName = "original.raw";
    static constexpr const char* kEmbeddedSettingsName = "settings.xmp";

    /** The Develop settings as an XMP sidecar (Lightroom-readable), the second embedded file. */
    std::string settingsXmp() const { return sidecarXmp(options_); }

    /** Both embedded files for `id`, ready for `CroptopSaveInput.embedded`; `settings` must outlive the save call. */
    std::vector<croptop::CroptopEmbeddedFile> toEmbedded(const std::string& id, const std::string& settings) const;

    /**
     * Rebuilds the object from `embedded/<id>/original.raw` and `settings.xmp` of an opened archive. Transforms, filters and
     * the filter mask live in the document manifest, so the caller restores them on `object()`.
     */
    static std::optional<RawSmartObject> fromArchive(const croptop::CroptopArchive& archive, std::string_view id, std::string& error,
                                                     const RawLimits& limits = {});

    /** Renders into a document of the given size (also "Rasterize"). */
    std::optional<TileStore> render(std::uint32_t document_width, std::uint32_t document_height, const smart::RenderOptions& options,
                                    std::string& error) const;

private:
    RawSmartObject(std::shared_ptr<const std::vector<std::uint8_t>> file, OpenOptions options, RawLimits limits, OpenedRaw opened);

    std::shared_ptr<const std::vector<std::uint8_t>> file_;
    OpenOptions options_;
    RawLimits limits_;
    RawMetadata metadata_;
    std::vector<std::string> warnings_;
    std::unique_ptr<smart::SmartObject> object_;
};

}  // namespace croptop::raw
