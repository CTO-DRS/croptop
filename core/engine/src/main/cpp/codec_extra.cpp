// TIFF and PSD/PSB import helpers (probe + decode into a caller-created store).
#include "codec_extra.h"

#include <algorithm>
#include <cstring>

#include "pixel_plane.h"
#include "psd_compose.h"
#include "psd_reader.h"
#include "psd_writer.h"
#include "tiff_codec.h"

namespace croptop::codec {
namespace {

constexpr std::uint32_t kMaxSide = 300000;

class Bytes final {
public:
    Bytes(std::span<const std::uint8_t> b, bool little) : b_(b), little_(little) {}
    bool has(std::uint64_t offset, std::uint64_t n) const { return offset <= b_.size() && n <= b_.size() - offset; }
    std::uint64_t uint(std::uint64_t offset, int n) const {
        std::uint64_t v = 0;
        for (int i = 0; i < n; ++i) {
            const std::uint64_t byte = b_[static_cast<std::size_t>(offset) + static_cast<std::size_t>(little_ ? i : n - 1 - i)];
            v |= byte << (8 * i);
        }
        return v;
    }

private:
    std::span<const std::uint8_t> b_;
    bool little_;
};

std::optional<Probe> probeTiff(std::span<const std::uint8_t> bytes, std::string& error) {
    const bool little = bytes[0] == 'I';
    const Bytes r(bytes, little);
    const bool big = r.uint(2, 2) == 43;
    if (big && (!r.has(4, 12) || r.uint(4, 2) != 8)) {
        error = "unsupported BigTIFF header";
        return std::nullopt;
    }
    const std::uint64_t ifd = big ? r.uint(8, 8) : r.uint(4, 4);
    const int count_size = big ? 8 : 2, entry_size = big ? 20 : 12, value_size = big ? 8 : 4;
    if (!r.has(ifd, static_cast<std::uint64_t>(count_size))) {
        error = "TIFF directory offset is out of range";
        return std::nullopt;
    }
    const std::uint64_t entries = r.uint(ifd, count_size);
    if (entries > 4096 || !r.has(ifd + static_cast<std::uint64_t>(count_size), entries * static_cast<std::uint64_t>(entry_size))) {
        error = "TIFF directory is truncated";
        return std::nullopt;
    }
    std::uint64_t width = 0, height = 0, bits = 1, sample_format = 1;
    for (std::uint64_t e = 0; e < entries; ++e) {
        const std::uint64_t at = ifd + static_cast<std::uint64_t>(count_size) + e * static_cast<std::uint64_t>(entry_size);
        const auto tag = r.uint(at, 2), type = r.uint(at + 2, 2);
        const auto count = r.uint(at + 4, big ? 8 : 4);
        const std::uint64_t value_at = at + 4 + static_cast<std::uint64_t>(big ? 8 : 4);
        const int unit = type == 3 ? 2 : type == 4 ? 4 : type == 16 ? 8 : 0;  // SHORT, LONG, LONG8
        if (unit == 0 || count == 0) continue;
        // The first value: inline when it fits, else at the pointed-to offset (BitsPerSample with 3–4 samples).
        std::uint64_t where = value_at;
        if (static_cast<std::uint64_t>(unit) * count > static_cast<std::uint64_t>(value_size)) {
            where = r.uint(value_at, value_size);
            if (!r.has(where, static_cast<std::uint64_t>(unit))) continue;
        }
        const auto value = r.uint(where, unit);
        if (tag == 256) width = value;
        else if (tag == 257) height = value;
        else if (tag == 258) bits = value;
        else if (tag == 339) sample_format = value;
    }
    if (width == 0 || height == 0 || width > kMaxSide || height > kMaxSide) {
        error = "TIFF image size is missing or out of range";
        return std::nullopt;
    }
    Probe p{ExtraFormat::Tiff, static_cast<std::uint32_t>(width), static_cast<std::uint32_t>(height), PixelFormat::RGBA8};
    if (sample_format == 3) p.pixel_format = PixelFormat::RGBA32F;
    else if (bits == 16) p.pixel_format = PixelFormat::RGBA16;
    return p;
}

std::optional<Probe> probePsd(std::span<const std::uint8_t> bytes, std::string& error) {
    if (bytes.size() < 26) {
        error = "PSD header is truncated";
        return std::nullopt;
    }
    const Bytes r(bytes, false);
    const auto version = r.uint(4, 2);
    const auto height = r.uint(14, 4), width = r.uint(18, 4), depth = r.uint(22, 2);
    if ((version != 1 && version != 2) || width == 0 || height == 0 || width > kMaxSide || height > kMaxSide || (version == 1 && (width > 30000 || height > 30000))) {
        error = "PSD header is invalid or out of range";
        return std::nullopt;
    }
    if (depth != 1 && depth != 8 && depth != 16 && depth != 32) {
        error = "PSD bit depth is unsupported";
        return std::nullopt;
    }
    return Probe{ExtraFormat::Psd, static_cast<std::uint32_t>(width), static_cast<std::uint32_t>(height),
                 depth == 32 ? PixelFormat::RGBA32F : depth == 16 ? PixelFormat::RGBA16 : PixelFormat::RGBA8};
}

/** Copies `source` into `destination` (same size), converting the sample format when they differ. */
bool copyInto(const TileStore& source, TileStore& destination, std::string& error) {
    if (source.width() != destination.width() || source.height() != destination.height()) {
        error = "decoded image size does not match the destination";
        return false;
    }
    if (!paint::detail::isRgba(destination.format())) {
        error = "the destination must be an RGBA store";
        return false;
    }
    // Stage every tile first so a failure never leaves the destination half written.
    std::vector<std::pair<TileCoordinate, TileBuffer>> staged;
    for (const auto c : source.tileCoordinates()) {
        const auto tile = source.readTile(c);
        if (tile == nullptr) continue;
        if (source.format() == destination.format()) {
            staged.emplace_back(c, *tile);
            continue;
        }
        TileBuffer out(destination.tile_byte_size(), 0);
        const auto sb = source.bytes_per_pixel(), db = destination.bytes_per_pixel();
        for (std::size_t i = 0; i < static_cast<std::size_t>(kTileEdge) * kTileEdge; ++i) {
            float px[4];
            paint::detail::readPixel(source.format(), tile->data() + i * sb, px);
            paint::detail::writePixel(destination.format(), out.data() + i * db, px);
        }
        staged.emplace_back(c, std::move(out));
    }
    destination.clear();
    for (const auto& [c, tile] : staged)
        if (!destination.writeTile(c, tile)) {
            error = "could not store decoded pixels";
            return false;
        }
    return true;
}

}  // namespace

ExtraFormat detect(std::span<const std::uint8_t> bytes) {
    if (bytes.size() >= 4 && ((bytes[0] == 'I' && bytes[1] == 'I' && (bytes[2] == 42 || bytes[2] == 43) && bytes[3] == 0) ||
                              (bytes[0] == 'M' && bytes[1] == 'M' && bytes[2] == 0 && (bytes[3] == 42 || bytes[3] == 43))))
        return ExtraFormat::Tiff;
    if (bytes.size() >= 4 && std::memcmp(bytes.data(), "8BPS", 4) == 0) return ExtraFormat::Psd;
    return ExtraFormat::Unknown;
}

std::optional<Probe> probe(std::span<const std::uint8_t> bytes, std::string& error) {
    switch (detect(bytes)) {
        case ExtraFormat::Tiff:
            if (bytes.size() < 16) {
                error = "TIFF header is truncated";
                return std::nullopt;
            }
            return probeTiff(bytes, error);
        case ExtraFormat::Psd: return probePsd(bytes, error);
        case ExtraFormat::Unknown: break;
    }
    error = "not a TIFF or PSD file";
    return std::nullopt;
}

DecodeResult decodeToTiles(std::span<const std::uint8_t> bytes, TileStore& destination, std::vector<std::string>* warnings,
                           std::vector<std::uint8_t>* icc_profile) {
    DecodeResult result;
    if (icc_profile != nullptr) icc_profile->clear();
    std::string error;
    const auto format = detect(bytes);
    if (format == ExtraFormat::Tiff) {
        auto image = tiff::decode(bytes, error);
        if (!image) {
            result.error = error;
            return result;
        }
        if (image->icc_profile.size() > (16U << 20U)) {
            result.error = "TIFF ICC profile exceeds the 16 MiB limit";
            return result;
        }
        if (warnings != nullptr) warnings->insert(warnings->end(), image->warnings.begin(), image->warnings.end());
        if (image->pages > 1 && warnings != nullptr) warnings->push_back("multi-page TIFF: only the first page was opened");
        if (!copyInto(image->pixels, destination, error)) {
            result.error = error;
            return result;
        }
        if (icc_profile != nullptr) *icc_profile = std::move(image->icc_profile);
        result.raster = {image->pixels.width(), image->pixels.height(), "tiff",
                         static_cast<std::uint8_t>(image->pixels.format() == PixelFormat::RGBA8 ? 8 : image->pixels.format() == PixelFormat::RGBA16 ? 16 : 32)};
    } else if (format == ExtraFormat::Psd) {
        auto document = psd::read(bytes, error);
        if (!document) {
            result.error = error;
            return result;
        }
        if (document->icc_profile.size() > (16U << 20U)) {
            result.error = "PSD ICC profile exceeds the 16 MiB limit";
            return result;
        }
        if (warnings != nullptr) warnings->insert(warnings->end(), document->warnings.begin(), document->warnings.end());
        std::optional<TileStore> flat;
        // A placeholder merged image (saved without "Maximize compatibility") is rebuilt from the layers.
        if (document->composite && (document->real_merged_data || document->layers.empty())) {
            flat.emplace(*document->composite);
        } else {
            std::vector<std::string> compose_warnings;
            auto composed = psd::compose(*document, error, &compose_warnings);
            if (!composed) {
                result.error = error;
                return result;
            }
            flat.emplace(std::move(*composed));
            if (warnings != nullptr) {
                warnings->push_back(document->composite ? "the file was saved without Maximize Compatibility; the composite was rebuilt from its layers"
                                                        : "the file has no merged image; the composite was rebuilt from its layers");
                warnings->insert(warnings->end(), compose_warnings.begin(), compose_warnings.end());
            }
        }
        if (!copyInto(*flat, destination, error)) {
            result.error = error;
            return result;
        }
        if (icc_profile != nullptr) *icc_profile = std::move(document->icc_profile);
        result.raster = {document->width, document->height, document->psb ? "psb" : "psd", static_cast<std::uint8_t>(document->depth)};
    } else {
        result.error = "not a TIFF or PSD file";
        return result;
    }
    result.success = true;
    return result;
}

std::optional<std::vector<std::uint8_t>> encodeTiff(const TileStore& pixels, std::span<const std::uint8_t> icc_profile, bool deflate, std::string& error) {
    if (!paint::detail::isRgba(pixels.format())) {
        error = "TIFF export needs an RGBA image";
        return std::nullopt;
    }
    tiff::EncodeOptions options;
    options.deflate = deflate;
    options.icc_profile.assign(icc_profile.begin(), icc_profile.end());
    return tiff::encode(pixels, error, options);
}

std::optional<std::vector<std::uint8_t>> encodePsd(const TileStore& pixels, std::span<const std::uint8_t> icc_profile, std::string& error) {
    if (!paint::detail::isRgba(pixels.format())) {
        error = "PSD export needs an RGBA image";
        return std::nullopt;
    }
    psd::Document document;
    document.width = pixels.width();
    document.height = pixels.height();
    document.depth = pixels.format() == PixelFormat::RGBA8 ? 8 : pixels.format() == PixelFormat::RGBA16 ? 16 : 32;
    document.psb = pixels.width() > 30000 || pixels.height() > 30000;
    document.icc_profile.assign(icc_profile.begin(), icc_profile.end());
    psd::Layer layer;
    layer.name = "Layer 1";
    layer.left = 0;
    layer.top = 0;
    layer.right = static_cast<std::int32_t>(pixels.width());
    layer.bottom = static_cast<std::int32_t>(pixels.height());
    layer.pixels.emplace(pixels);
    document.layers.push_back(std::move(layer));
    document.composite.emplace(pixels);
    psd::WriteOptions options;
    options.force_psb = document.psb;
    return psd::write(document, error, options);
}

std::optional<std::vector<std::uint8_t>> encodePsdLayered(std::vector<psd::Layer> layers, const TileStore& composite,
                                                          std::span<const std::uint8_t> icc_profile, std::string& error) {
    if (!paint::detail::isRgba(composite.format())) {
        error = "PSD export needs an RGBA composite";
        return std::nullopt;
    }
    if (layers.empty()) {
        error = "a layered PSD needs at least one layer";
        return std::nullopt;
    }
    int open_groups = 0;
    for (const auto& layer : layers) {
        if (layer.kind == psd::LayerKind::GroupDivider) {
            ++open_groups;
        } else if (layer.kind == psd::LayerKind::GroupFolder && --open_groups < 0) {
            error = "a group folder has no matching divider";
            return std::nullopt;
        }
        if (layer.pixels) {
            if (layer.pixels->format() != composite.format() || layer.pixels->width() != composite.width() ||
                layer.pixels->height() != composite.height()) {
                error = "layer '" + layer.name + "' does not match the composite's size and depth";
                return std::nullopt;
            }
        }
        if (layer.mask && (layer.mask->coverage.format() != PixelFormat::A16 || layer.mask->coverage.width() != composite.width() ||
                           layer.mask->coverage.height() != composite.height())) {
            error = "the mask of layer '" + layer.name + "' must be an A16 store of the canvas size";
            return std::nullopt;
        }
    }
    if (open_groups != 0) {
        error = "a group divider has no matching folder";
        return std::nullopt;
    }
    psd::Document document;
    document.width = composite.width();
    document.height = composite.height();
    document.depth = composite.format() == PixelFormat::RGBA8 ? 8 : composite.format() == PixelFormat::RGBA16 ? 16 : 32;
    document.psb = composite.width() > 30000 || composite.height() > 30000;
    document.icc_profile.assign(icc_profile.begin(), icc_profile.end());
    document.layers = std::move(layers);
    document.composite.emplace(composite);
    psd::WriteOptions options;
    options.force_psb = document.psb;
    return psd::write(document, error, options);
}

}  // namespace croptop::codec
