// LibRaw front end; see raw_decode.h.
#include "raw_decode.h"

#include "parallel_util.h"

#include <libraw/libraw.h>

#include <algorithm>
#include <atomic>
#include <cmath>
#include <cstring>
#include <exception>
#include <memory>
#include <mutex>

namespace croptop::raw {
namespace {

constexpr std::uint32_t kMaxRawMemoryMb = 1536;

std::string trimmed(const char* text, std::size_t capacity) {
    const std::size_t length = ::strnlen(text, capacity);
    std::string value(text, length);
    while (!value.empty() && (value.back() == ' ' || value.back() == '\t' || value.back() == '\n')) value.pop_back();
    return value;
}

// dcraw's internal `flip` (bit 0: mirror X, bit 1: mirror Y, bit 2: transpose) → EXIF orientation.
int exifOrientation(int flip) {
    switch (flip) {
        case 0: return 1;
        case 1: return 2;
        case 2: return 4;
        case 3: return 3;
        case 4: return 5;
        case 5: return 8;
        case 6: return 6;
        case 7: return 7;
        default: return 1;
    }
}

RawMetadata metadataOf(const LibRaw& lr) {
    const auto& d = lr.imgdata;
    RawMetadata m;
    m.make = trimmed(d.idata.make, sizeof(d.idata.make));
    m.model = trimmed(d.idata.model, sizeof(d.idata.model));
    m.normalized_make = trimmed(d.idata.normalized_make, sizeof(d.idata.normalized_make));
    m.normalized_model = trimmed(d.idata.normalized_model, sizeof(d.idata.normalized_model));
    m.software = trimmed(d.idata.software, sizeof(d.idata.software));
    m.lens = trimmed(d.lens.Lens, sizeof(d.lens.Lens));
    if (m.lens.empty()) m.lens = trimmed(d.lens.makernotes.Lens, sizeof(d.lens.makernotes.Lens));
    m.iso = std::isfinite(d.other.iso_speed) ? d.other.iso_speed : 0.0F;
    m.shutter_seconds = std::isfinite(d.other.shutter) ? d.other.shutter : 0.0F;
    m.aperture = std::isfinite(d.other.aperture) ? d.other.aperture : 0.0F;
    m.focal_length_mm = std::isfinite(d.other.focal_len) ? d.other.focal_len : 0.0F;
    m.timestamp = static_cast<std::int64_t>(d.other.timestamp);
    m.orientation = exifOrientation(d.sizes.flip);
    m.is_dng = d.idata.dng_version != 0;
    m.raw_count = static_cast<int>(d.idata.raw_count);
    return m;
}

/** 0 = R, 1 = G, 2 = B from LibRaw's 0..3 (R, G1, B, G2); 255 when the site has no single colour. */
std::uint8_t colourOf(int libraw_colour) {
    switch (libraw_colour) {
        case 0: return 0;
        case 1:
        case 3: return 1;
        case 2: return 2;
        default: return 255;
    }
}

/** Why the mosaic path cannot take this raster; empty when it can. Valid after open(). */
std::string mosaicObstacle(LibRaw& lr) {
    const auto& d = lr.imgdata;
    if (d.idata.is_foveon != 0) return "Foveon sensors are not supported";
    if (d.idata.filters == 0 && d.idata.colors != 3) return "the raw is neither a colour-filter-array mosaic nor a 3-colour linear raster";
    if (d.idata.filters != 0 && d.idata.filters != 9U && d.idata.filters < 1000U) return "unsupported colour-filter layout";
    if (lr.get_internal_data_pointer()->internal_output_params.fuji_width != 0) return "rotated Super CCD sensors are not supported";
    if (d.sizes.width == 0 || d.sizes.height == 0) return "the file reports an empty image";
    const std::string desc(d.idata.cdesc, ::strnlen(d.idata.cdesc, sizeof(d.idata.cdesc)));
    if (desc != "RGBG" && desc != "RGB") return "unsupported colour filter colours (" + desc + ")";
    return {};
}

bool openBuffer(LibRaw& lr, std::span<const std::uint8_t> file, const RawLimits& limits, std::string& error) {
    if (file.empty() || file.size() > limits.max_file_bytes) {
        error = file.empty() ? "empty raw file" : "raw file is too large";
        return false;
    }
    lr.imgdata.rawparams.max_raw_memory_mb = kMaxRawMemoryMb;
    if (const int rc = lr.open_buffer(file.data(), file.size()); rc != LIBRAW_SUCCESS) {
        error = std::string("not a supported raw: ") + LibRaw::strerror(rc);
        return false;
    }
    const auto pixels = static_cast<std::uint64_t>(lr.imgdata.sizes.raw_width) * lr.imgdata.sizes.raw_height;
    if (pixels > limits.max_pixels) {
        error = "raw image is too large";
        return false;
    }
    return true;
}

CfaPattern patternOf(LibRaw& lr, bool xtrans, std::string& error) {
    CfaPattern pattern;
    pattern.width = pattern.height = xtrans ? 6 : 2;
    for (int y = 0; y < pattern.height; ++y) {
        for (int x = 0; x < pattern.width; ++x) {
            const auto colour = colourOf(lr.COLOR(y, x));
            if (colour == 255) {
                error = "colour filter array has a site without a single colour";
                return {};
            }
            pattern.colours[static_cast<std::size_t>(y * pattern.width + x)] = colour;
        }
    }
    return pattern;
}

/** Black level per colour: LibRaw's common black plus the per-channel and repeating-pattern offsets, averaged. */
std::array<float, 3> blackLevels(LibRaw& lr, const CfaPattern& pattern) {
    const auto& c = lr.imgdata.color;
    const unsigned pattern_w = c.cblack[4], pattern_h = c.cblack[5];
    const bool has_pattern = pattern_w > 0 && pattern_h > 0 && pattern_w * pattern_h + 6 <= LIBRAW_CBLACK_SIZE;
    std::array<double, 3> sum{};
    std::array<double, 3> count{};
    // 60 is a multiple of every repeat period LibRaw uses (1–6), so this covers whole periods.
    for (int y = 0; y < 60; ++y) {
        for (int x = 0; x < 60; ++x) {
            const int libraw_colour = lr.COLOR(y, x);
            const auto colour = colourOf(libraw_colour);
            if (colour == 255) continue;
            double black = static_cast<double>(c.black) + static_cast<double>(c.cblack[libraw_colour & 3]);
            if (has_pattern) black += static_cast<double>(c.cblack[6 + (static_cast<unsigned>(y) % pattern_h) * pattern_w + static_cast<unsigned>(x) % pattern_w]);
            sum[colour] += black;
            count[colour] += 1.0;
        }
    }
    (void)pattern;
    std::array<float, 3> out{};
    for (std::size_t i = 0; i < 3; ++i) out[i] = count[i] > 0.0 ? static_cast<float>(sum[i] / count[i]) : 0.0F;
    return out;
}

std::array<float, 3> whiteBalance(const LibRaw& lr, std::vector<std::string>& warnings) {
    const auto& c = lr.imgdata.color;
    for (const float* source : {c.cam_mul, c.pre_mul}) {
        const float g = source[3] > 0.0F && source[1] > 0.0F ? 0.5F * (source[1] + source[3]) : source[1];
        if (source[0] > 0.0F && g > 0.0F && source[2] > 0.0F && std::isfinite(source[0]) && std::isfinite(source[2]) && std::isfinite(g)) {
            return {source[0] / g, 1.0F, source[2] / g};
        }
    }
    warnings.push_back("the file has no as-shot white balance; using neutral multipliers");
    return {1.0F, 1.0F, 1.0F};
}

bool finiteNonZero(const float (*m)[3], std::array<double, 9>& out) {
    bool any = false;
    for (int row = 0; row < 3; ++row) {
        for (int col = 0; col < 3; ++col) {
            const float v = m[row][col];
            if (!std::isfinite(v)) return false;
            out[static_cast<std::size_t>(row * 3 + col)] = v;
            any = any || v != 0.0F;
        }
    }
    return any;
}

/**
 * XYZ (D65) → camera. LibRaw's own table matrix (`cam_xyz`) covers cameras it knows by name; a DNG from an unlisted camera or
 * phone carries its matrices in ColorMatrix1/2 (`dng_color`), so those are the fallback, preferring the D65 one (illuminant 21).
 */
std::optional<std::array<double, 9>> xyzToCamera(const LibRaw& lr) {
    std::array<double, 9> out{};
    const auto& c = lr.imgdata.color;
    if (finiteNonZero(c.cam_xyz, out)) return out;
    constexpr int kD65 = 21;
    const int order[2] = {c.dng_color[1].illuminant == kD65 || c.dng_color[0].illuminant != kD65 ? 1 : 0,
                          c.dng_color[1].illuminant == kD65 || c.dng_color[0].illuminant != kD65 ? 0 : 1};
    for (const int i : order) {
        if (finiteNonZero(c.dng_color[i].colormatrix, out)) return out;
    }
    return std::nullopt;
}

float saturationLevel(const LibRaw& lr) {
    const auto& c = lr.imgdata.color;
    float white = c.maximum > 0 ? static_cast<float>(c.maximum) : c.data_maximum > 0 ? static_cast<float>(c.data_maximum) : 65535.0F;
    // dcraw/LibRaw's adjust_maximum: a data maximum just under the nominal one is the real clipping point.
    if (c.data_maximum > 0 && static_cast<float>(c.data_maximum) < white && static_cast<float>(c.data_maximum) > 0.75F * white) {
        white = static_cast<float>(c.data_maximum);
    }
    return white;
}


/** Demosaiced camera RGB (Canon sRAW/mRAW, linear DNG): LibRaw exposes it as 3- or 4-component pixels with raw_width stride. */
std::optional<RawImage> decodeLinear(LibRaw& lr, std::string& error) {
    const auto& sizes = lr.imgdata.sizes;
    const auto& raster = lr.imgdata.rawdata;
    const bool four = raster.color4_image != nullptr;
    if (!four && raster.color3_image == nullptr) {
        error = "the linear raw has no 16-bit RGB raster";
        return std::nullopt;
    }
    const std::size_t stride = sizes.raw_width;
    if (static_cast<std::size_t>(sizes.left_margin) + sizes.width > stride || static_cast<std::size_t>(sizes.top_margin) + sizes.height > sizes.raw_height) {
        error = "the raw's active area lies outside its raster";
        return std::nullopt;
    }
    RawImage image;
    image.metadata = metadataOf(lr);
    image.width = sizes.width;
    image.height = sizes.height;
    const auto& c = lr.imgdata.color;
    for (std::size_t i = 0; i < 3; ++i) image.black[i] = static_cast<float>(c.black) + static_cast<float>(c.cblack[i]);
    image.white = saturationLevel(lr);
    image.as_shot_wb = whiteBalance(lr, image.warnings);
    image.xyz_to_camera = xyzToCamera(lr);
    if (!image.xyz_to_camera) image.warnings.push_back("the file has no camera colour matrix; colours stay in camera RGB");
    if (image.white <= *std::max_element(image.black.begin(), image.black.end())) {
        error = "the raw's white level is not above its black level";
        return std::nullopt;
    }
    image.linear_rgb.resize(static_cast<std::size_t>(image.width) * image.height * 3);
    for (std::uint32_t y = 0; y < image.height; ++y) {
        for (std::uint32_t x = 0; x < image.width; ++x) {
            const std::size_t at = (static_cast<std::size_t>(y) + sizes.top_margin) * stride + x + sizes.left_margin;
            const ushort* px = four ? raster.color4_image[at] : raster.color3_image[at];
            std::uint16_t* out = &image.linear_rgb[(static_cast<std::size_t>(y) * image.width + x) * 3];
            out[0] = px[0];
            out[1] = px[1];
            out[2] = px[2];
        }
    }
    return image;
}

}  // namespace

std::optional<TileStore> RawImage::developLinear(const DevelopSpec& spec, std::string& error) const {
    if (!isLinear()) {
        error = "not a linear raw";
        return std::nullopt;
    }
    const auto format = spec.output_format == PixelFormat::RGBA32F ? PixelFormat::RGBA32F : PixelFormat::RGBA16;
    try {
        TileStore out(format, width, height);
        const std::size_t tiles_x = (width + kTileEdge - 1) / kTileEdge, tiles_y = (height + kTileEdge - 1) / kTileEdge;
        std::atomic<bool> failed{false};
        std::atomic<std::size_t> done{0};
        std::mutex write_mutex;
        std::array<float, 3> scale{};
        for (std::size_t i = 0; i < 3; ++i) scale[i] = spec.white_balance[i] / (white - black[i]);
        const auto& m = spec.camera_to_output;
        parallel::parallelFor(tiles_x * tiles_y, parallel::workerCount(spec.worker_threads), [&](std::size_t index, std::size_t) {
            if (failed.load()) return;
            const auto tx = static_cast<std::uint32_t>(index % tiles_x), ty = static_cast<std::uint32_t>(index / tiles_x);
            TileBuffer tile(out.tile_byte_size(), 0);
            for (std::uint32_t ly = 0; ly < kTileEdge && ty * kTileEdge + ly < height; ++ly) {
                for (std::uint32_t lx = 0; lx < kTileEdge && tx * kTileEdge + lx < width; ++lx) {
                    const std::uint16_t* src = &linear_rgb[(static_cast<std::size_t>(ty * kTileEdge + ly) * width + tx * kTileEdge + lx) * 3];
                    float cam[3];
                    for (int i = 0; i < 3; ++i) cam[i] = std::max(0.0F, (static_cast<float>(src[i]) - black[static_cast<std::size_t>(i)]) * scale[static_cast<std::size_t>(i)]);
                    float rgb[3];
                    for (int r = 0; r < 3; ++r) {
                        rgb[r] = static_cast<float>(m[static_cast<std::size_t>(r * 3)] * cam[0] + m[static_cast<std::size_t>(r * 3 + 1)] * cam[1] +
                                                    m[static_cast<std::size_t>(r * 3 + 2)] * cam[2]);
                    }
                    const std::size_t p = static_cast<std::size_t>(ly) * kTileEdge + lx;
                    for (int r = 0; r < 3; ++r) {
                        if (format == PixelFormat::RGBA32F) {
                            std::memcpy(tile.data() + (p * 4 + static_cast<std::size_t>(r)) * sizeof(float), &rgb[r], sizeof(float));
                        } else {
                            const auto v = static_cast<std::uint16_t>(std::lround(std::clamp(rgb[r], 0.0F, 1.0F) * 65535.0F));
                            std::memcpy(tile.data() + (p * 4 + static_cast<std::size_t>(r)) * sizeof(std::uint16_t), &v, sizeof(v));
                        }
                    }
                    if (format == PixelFormat::RGBA32F) {
                        const float one = 1.0F;
                        std::memcpy(tile.data() + (p * 4 + 3) * sizeof(float), &one, sizeof(float));
                    } else {
                        const std::uint16_t one = 0xFFFF;
                        std::memcpy(tile.data() + (p * 4 + 3) * sizeof(std::uint16_t), &one, sizeof(one));
                    }
                }
            }
            {
                const std::lock_guard lock(write_mutex);
                if (!out.writeTile({static_cast<std::int32_t>(tx), static_cast<std::int32_t>(ty)}, tile)) failed = true;
            }
            const auto finished = done.fetch_add(1) + 1;
            if (spec.progress && !spec.progress(static_cast<float>(finished) / static_cast<float>(tiles_x * tiles_y))) failed = true;
        });
        if (failed) {
            error = "linear raw develop was cancelled or ran out of memory";
            return std::nullopt;
        }
        return out;
    } catch (const std::exception& exception) {
        error = std::string("linear raw develop failed: ") + exception.what();
        return std::nullopt;
    }
}

CfaImage RawImage::cfa() const {
    CfaImage image;
    image.width = width;
    image.height = height;
    image.photosites = photosites;
    image.pattern = pattern;
    image.black = black;
    image.white = white;
    return image;
}

DevelopSpec RawImage::developSpec(OutputPrimaries primaries) const {
    DevelopSpec spec;
    // Leave the method at its default: RCD on Bayer, and develop() picks the X-Trans method itself, so this compiles against
    // every revision of raw_demosaic.h (the X-Trans/AMaZE enumerators were renamed in the working tree).
    spec.white_balance = as_shot_wb;
    if (xyz_to_camera) {
        if (const auto matrix = cameraToOutput(*xyz_to_camera, primaries)) spec.camera_to_output = *matrix;
    }
    return spec;
}

std::optional<RawProbe> probe(std::span<const std::uint8_t> file, std::string& error, const RawLimits& limits) {
    try {
        const auto lr = std::make_unique<LibRaw>();
        if (!openBuffer(*lr, file, limits, error)) return std::nullopt;
        RawProbe result;
        result.metadata = metadataOf(*lr);
        result.width = lr->imgdata.sizes.width;
        result.height = lr->imgdata.sizes.height;
        result.raw_width = lr->imgdata.sizes.raw_width;
        result.raw_height = lr->imgdata.sizes.raw_height;
        result.xtrans = lr->imgdata.idata.filters == 9U;
        result.linear = lr->imgdata.idata.filters == 0;
        result.unsupported_reason = mosaicObstacle(*lr);
        result.decodable = result.unsupported_reason.empty();
        return result;
    } catch (const std::exception& exception) {
        error = std::string("raw probe failed: ") + exception.what();
        return std::nullopt;
    }
}

std::optional<RawImage> decode(std::span<const std::uint8_t> file, std::string& error, const RawLimits& limits) {
    try {
        const auto lr = std::make_unique<LibRaw>();
        if (!openBuffer(*lr, file, limits, error)) return std::nullopt;
        if (const auto obstacle = mosaicObstacle(*lr); !obstacle.empty()) {
            error = obstacle;
            return std::nullopt;
        }
        const auto& sizes = lr->imgdata.sizes;
        if (static_cast<std::uint64_t>(sizes.width) * sizes.height > limits.max_pixels) {
            error = "raw image is too large";
            return std::nullopt;
        }
        if (const int rc = lr->unpack(); rc != LIBRAW_SUCCESS) {
            error = std::string("raw decode failed: ") + LibRaw::strerror(rc);
            // Nikon Z bodies can record "High Efficiency" (TicoRAW) NEFs, which LibRaw cannot unpack: say so plainly.
            if (rc == LIBRAW_FILE_UNSUPPORTED && trimmed(lr->imgdata.idata.make, sizeof(lr->imgdata.idata.make)) == "Nikon") {
                error = "this Nikon NEF uses High Efficiency compression, which is not supported; set the camera to Lossless or Lossy compressed RAW";
            }
            return std::nullopt;
        }
        if (lr->imgdata.idata.filters == 0) return decodeLinear(*lr, error);
        const ushort* raw = lr->imgdata.rawdata.raw_image;
        if (raw == nullptr) {
            error = "the raw has no 16-bit mosaic raster (floating-point or multi-plane data)";
            return std::nullopt;
        }
        const std::size_t pitch = sizes.raw_pitch / sizeof(ushort);
        if (static_cast<std::size_t>(sizes.left_margin) + sizes.width > pitch || static_cast<std::size_t>(sizes.top_margin) + sizes.height > sizes.raw_height) {
            error = "the raw's active area lies outside its raster";
            return std::nullopt;
        }

        RawImage image;
        image.metadata = metadataOf(*lr);
        image.width = sizes.width;
        image.height = sizes.height;
        image.pattern = patternOf(*lr, lr->imgdata.idata.filters == 9U, error);
        if (!error.empty()) return std::nullopt;
        image.black = blackLevels(*lr, image.pattern);
        image.white = saturationLevel(*lr);
        image.as_shot_wb = whiteBalance(*lr, image.warnings);
        image.xyz_to_camera = xyzToCamera(*lr);
        if (!image.xyz_to_camera) image.warnings.push_back("the file has no camera colour matrix; colours stay in camera RGB");
        if (image.white <= *std::max_element(image.black.begin(), image.black.end())) {
            error = "the raw's white level is not above its black level";
            return std::nullopt;
        }

        image.photosites.resize(static_cast<std::size_t>(image.width) * image.height);
        for (std::uint32_t y = 0; y < image.height; ++y) {
            const ushort* row = raw + (static_cast<std::size_t>(y) + sizes.top_margin) * pitch + sizes.left_margin;
            std::copy(row, row + image.width, image.photosites.begin() + static_cast<std::ptrdiff_t>(static_cast<std::size_t>(y) * image.width));
        }
        return image;
    } catch (const std::exception& exception) {
        error = std::string("raw decode failed: ") + exception.what();
        return std::nullopt;
    }
}

std::optional<EmbeddedPreview> extractPreview(std::span<const std::uint8_t> file, std::string& error, std::uint32_t min_long_edge,
                                              const RawLimits& limits) {
    try {
        const auto lr = std::make_unique<LibRaw>();
        if (!openBuffer(*lr, file, limits, error)) return std::nullopt;
        const auto& list = lr->imgdata.thumbs_list;
        int best = -1;
        std::uint64_t best_area = 0;
        for (int i = 0; i < list.thumbcount && i < LIBRAW_THUMBNAIL_MAXCOUNT; ++i) {
            const auto& item = list.thumblist[i];
            if (item.tformat != LIBRAW_INTERNAL_THUMBNAIL_JPEG) continue;
            const auto area = static_cast<std::uint64_t>(item.twidth) * item.theight;
            if (std::max(item.twidth, item.theight) < min_long_edge) continue;
            if (area >= best_area) {
                best = i;
                best_area = area;
            }
        }
        const int rc = best >= 0 ? lr->unpack_thumb_ex(best) : lr->unpack_thumb();
        if (rc != LIBRAW_SUCCESS) {
            error = std::string("no embedded preview: ") + LibRaw::strerror(rc);
            return std::nullopt;
        }
        const auto& thumb = lr->imgdata.thumbnail;
        if (thumb.tformat != LIBRAW_THUMBNAIL_JPEG || thumb.thumb == nullptr || thumb.tlength < 4) {
            error = thumb.tformat == LIBRAW_THUMBNAIL_H265 ? "the embedded preview is HEVC, not JPEG"
                                                           : "the embedded preview is not a JPEG";
            return std::nullopt;
        }
        const auto* bytes = reinterpret_cast<const std::uint8_t*>(thumb.thumb);
        if (bytes[0] != 0xFF || bytes[1] != 0xD8) {
            error = "the embedded preview is not a JPEG";
            return std::nullopt;
        }
        if (std::max<std::uint32_t>(thumb.twidth, thumb.theight) < min_long_edge) {
            error = "the embedded preview is smaller than requested";
            return std::nullopt;
        }
        EmbeddedPreview preview;
        preview.jpeg.assign(bytes, bytes + thumb.tlength);
        preview.width = thumb.twidth;
        preview.height = thumb.theight;
        return preview;
    } catch (const std::exception& exception) {
        error = std::string("preview extraction failed: ") + exception.what();
        return std::nullopt;
    }
}

}  // namespace croptop::raw
