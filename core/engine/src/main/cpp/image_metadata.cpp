#include "image_metadata.h"

#include <algorithm>
#include <cmath>
#include <cstring>
#include <set>
#include <stdexcept>

namespace croptop::metadata {
namespace {

struct Truncated final : std::runtime_error {
    using std::runtime_error::runtime_error;
};

constexpr int kMaxIfds = 64;
constexpr int kMaxDepth = 4;

std::uint32_t be16(std::span<const std::uint8_t> d, std::size_t at) {
    if (at + 2 > d.size()) throw Truncated("truncated");
    return (std::uint32_t{d[at]} << 8) | d[at + 1];
}
std::uint32_t be32(std::span<const std::uint8_t> d, std::size_t at) {
    if (at + 4 > d.size()) throw Truncated("truncated");
    return (std::uint32_t{d[at]} << 24) | (std::uint32_t{d[at + 1]} << 16) | (std::uint32_t{d[at + 2]} << 8) | d[at + 3];
}

/** A JPEG's dimensions from its first SOF marker, or {0, 0}; `lossless` is set for SOF3 (raw CFA data). */
std::pair<std::uint32_t, std::uint32_t> jpegSize(std::span<const std::uint8_t> jpeg, bool* lossless = nullptr) {
    try {
        if (jpeg.size() < 4 || jpeg[0] != 0xFF || jpeg[1] != 0xD8) return {0, 0};
        std::size_t at = 2;
        while (at + 4 <= jpeg.size()) {
            if (jpeg[at] != 0xFF) return {0, 0};
            const auto marker = jpeg[at + 1];
            if (marker == 0xD8 || (marker >= 0xD0 && marker <= 0xD7) || marker == 0x01 || marker == 0xFF) {
                at += marker == 0xFF ? 1 : 2;
                continue;
            }
            const auto length = be16(jpeg, at + 2);
            if ((marker >= 0xC0 && marker <= 0xCF) && marker != 0xC4 && marker != 0xC8 && marker != 0xCC) {
                if (lossless != nullptr) *lossless = marker == 0xC3 || marker == 0xC7 || marker == 0xCB || marker == 0xCF;
                return {be16(jpeg, at + 7), be16(jpeg, at + 5)};
            }
            if (marker == 0xDA || length < 2) return {0, 0};
            at += 2 + length;
        }
    } catch (const Truncated&) {
    }
    return {0, 0};
}

/** TIFF structure parser over a block (offsets relative to its start). */
class Tiff final {
public:
    explicit Tiff(std::span<const std::uint8_t> block) : d_(block) {
        if (d_.size() < 8) throw Truncated("TIFF header too short");
        if (d_[0] == 'I' && d_[1] == 'I') little_ = true;
        else if (d_[0] == 'M' && d_[1] == 'M') little_ = false;
        else throw std::runtime_error("not a TIFF structure");
        const auto magic = u(2, 2);
        if (magic != 42) throw std::runtime_error(magic == 43 ? "BigTIFF metadata is not supported" : "not a TIFF structure");
        first_ = u(4, 4);
    }

    std::uint64_t u(std::uint64_t at, int bytes) const {
        if (at > d_.size() || static_cast<std::uint64_t>(bytes) > d_.size() - at) throw Truncated("value outside the TIFF block");
        std::uint64_t v = 0;
        for (int i = 0; i < bytes; ++i) v = (v << 8) | d_[static_cast<std::size_t>(at) + static_cast<std::size_t>(little_ ? bytes - 1 - i : i)];
        return v;
    }
    std::uint32_t first() const { return first_; }
    std::span<const std::uint8_t> data() const { return d_; }

    struct Entry {
        std::uint16_t tag, type;
        std::uint32_t count, offset;  // offset of the value bytes
    };
    static int typeSize(std::uint16_t type) {
        switch (type) {
            case 1: case 2: case 6: case 7: return 1;
            case 3: case 8: return 2;
            case 4: case 9: case 11: case 13: return 4;
            case 5: case 10: case 12: return 8;
            default: return 0;
        }
    }
    std::vector<Entry> entries(std::uint32_t ifd, std::uint32_t& next) const {
        const auto count = u(ifd, 2);
        if (count > 1000) throw std::runtime_error("too many IFD entries");
        std::vector<Entry> out;
        for (std::uint32_t i = 0; i < count; ++i) {
            const std::uint64_t e = ifd + 2 + 12ULL * i;
            Entry entry{static_cast<std::uint16_t>(u(e, 2)), static_cast<std::uint16_t>(u(e + 2, 2)), static_cast<std::uint32_t>(u(e + 4, 4)), 0};
            const int size = typeSize(entry.type);
            if (size == 0 || entry.count > (1U << 24)) continue;
            const std::uint64_t bytes = std::uint64_t{entry.count} * static_cast<std::uint64_t>(size);
            entry.offset = bytes <= 4 ? static_cast<std::uint32_t>(e + 8) : static_cast<std::uint32_t>(u(e + 8, 4));
            out.push_back(entry);
        }
        next = static_cast<std::uint32_t>(u(ifd + 2 + 12ULL * count, 4));
        return out;
    }
    std::uint64_t integer(const Entry& e, std::uint32_t index = 0) const {
        const int size = typeSize(e.type);
        if (index >= e.count) throw Truncated("value index out of range");
        return u(e.offset + static_cast<std::uint64_t>(index) * static_cast<std::uint64_t>(size), size > 4 ? 4 : size);
    }
    std::optional<double> rational(const Entry& e, std::uint32_t index = 0) const {
        if ((e.type != 5 && e.type != 10) || index >= e.count) return std::nullopt;
        const auto at = e.offset + 8ULL * index;
        double numerator = static_cast<double>(u(at, 4)), denominator = static_cast<double>(u(at + 4, 4));
        if (e.type == 10) {
            numerator = static_cast<double>(static_cast<std::int32_t>(u(at, 4)));
            denominator = static_cast<double>(static_cast<std::int32_t>(u(at + 4, 4)));
        }
        if (denominator == 0.0) return std::nullopt;
        return numerator / denominator;
    }
    std::string text(const Entry& e) const {
        if (e.type != 2 && e.type != 7 && e.type != 1) return {};
        const auto n = std::min<std::uint32_t>(e.count, 1024);
        if (e.offset > d_.size() || n > d_.size() - e.offset) return {};
        std::string s(reinterpret_cast<const char*>(d_.data() + e.offset), n);
        s = s.substr(0, s.find('\0'));
        while (!s.empty() && s.back() == ' ') s.pop_back();
        return s;
    }

private:
    std::span<const std::uint8_t> d_;
    bool little_ = true;
    std::uint32_t first_ = 0;
};

/**
 * Walks a TIFF structure: IFD0 chain, SubIFDs, EXIF and GPS IFDs. `base` is the block's offset within the
 * file (previews are reported as file offsets).
 */
class Walker final {
public:
    Walker(const Tiff& tiff, std::uint64_t base, std::span<const std::uint8_t> file, Metadata& out)
        : tiff_(tiff), base_(base), file_(file), out_(out) {}

    void run() {
        std::uint32_t ifd = tiff_.first();
        int index = 0;
        while (ifd != 0 && visited_.size() < kMaxIfds) {
            const auto next = walk(ifd, Kind::Main, 0, index == 1);
            ++index;
            ifd = next;
        }
    }

private:
    enum class Kind { Main, Exif, Gps };

    std::uint32_t walk(std::uint32_t ifd, Kind kind, int depth, bool thumbnail_ifd) {
        if (depth > kMaxDepth || !visited_.insert(ifd).second || visited_.size() > kMaxIfds) return 0;
        std::uint32_t next = 0;
        std::vector<Tiff::Entry> entries;
        try {
            entries = tiff_.entries(ifd, next);
        } catch (const std::exception& e) {
            out_.warnings.push_back(std::string("metadata directory skipped: ") + e.what());
            return 0;
        }
        auto& exif = exif_();
        std::uint64_t compression = 0, jpeg_offset = 0, jpeg_length = 0, strip_offset = 0, strip_length = 0;
        std::uint32_t strips = 0;
        std::optional<std::string> lat_ref, lon_ref;
        std::optional<double> lat, lon, alt;
        std::uint64_t alt_ref = 0;
        for (const auto& e : entries) {
            try {
                if (kind == Kind::Gps) {
                    const auto dms = [&]() -> std::optional<double> {
                        const auto d = tiff_.rational(e, 0), m = tiff_.rational(e, 1), s = tiff_.rational(e, 2);
                        if (!d || !m || !s) return std::nullopt;
                        return *d + *m / 60.0 + *s / 3600.0;
                    };
                    switch (e.tag) {
                        case 1: lat_ref = tiff_.text(e); break;
                        case 2: lat = dms(); break;
                        case 3: lon_ref = tiff_.text(e); break;
                        case 4: lon = dms(); break;
                        case 5: alt_ref = tiff_.integer(e); break;
                        case 6: alt = tiff_.rational(e); break;
                        default: break;
                    }
                    continue;
                }
                switch (e.tag) {
                    case 256: if (depth == 0 && !thumbnail_ifd && !out_.width) out_.width = static_cast<std::uint32_t>(tiff_.integer(e)); break;
                    case 257: if (depth == 0 && !thumbnail_ifd && !out_.height) out_.height = static_cast<std::uint32_t>(tiff_.integer(e)); break;
                    case 259: compression = tiff_.integer(e); break;
                    case 271: if (exif.make.empty()) exif.make = tiff_.text(e); break;
                    case 272: if (exif.model.empty()) exif.model = tiff_.text(e); break;
                    case 273: strips = e.count; strip_offset = tiff_.integer(e); break;
                    case 274: if (!thumbnail_ifd && depth == 0) exif.orientation = static_cast<int>(tiff_.integer(e)); break;
                    case 279: strip_length = tiff_.integer(e); break;
                    case 305: if (exif.software.empty()) exif.software = tiff_.text(e); break;
                    case 306: if (exif.date_time_original.empty()) exif.date_time_original = tiff_.text(e); break;
                    case 315: exif.artist = tiff_.text(e); break;
                    case 513: jpeg_offset = tiff_.integer(e); break;
                    case 514: jpeg_length = tiff_.integer(e); break;
                    case 33432: exif.copyright = tiff_.text(e); break;
                    case 33434: exif.exposure_time = tiff_.rational(e); break;
                    case 33437: exif.f_number = tiff_.rational(e); break;
                    case 34855: exif.iso = static_cast<std::uint32_t>(tiff_.integer(e)); break;
                    case 36867: exif.date_time_original = tiff_.text(e); break;
                    case 37380: exif.exposure_bias = tiff_.rational(e); break;
                    case 37385: exif.flash = static_cast<std::uint32_t>(tiff_.integer(e)); break;
                    case 37386: exif.focal_length = tiff_.rational(e); break;
                    case 40962: exif.pixel_width = static_cast<std::uint32_t>(tiff_.integer(e)); break;
                    case 40963: exif.pixel_height = static_cast<std::uint32_t>(tiff_.integer(e)); break;
                    case 41989: exif.focal_length_35mm = static_cast<std::uint32_t>(tiff_.integer(e)); break;
                    case 42036: exif.lens_model = tiff_.text(e); break;
                    case 330:  // SubIFDs
                        for (std::uint32_t i = 0; i < std::min<std::uint32_t>(e.count, 16); ++i) walk(static_cast<std::uint32_t>(tiff_.integer(e, i)), Kind::Main, depth + 1, false);
                        break;
                    case 34665: walk(static_cast<std::uint32_t>(tiff_.integer(e)), Kind::Exif, depth + 1, false); break;
                    case 34853: walk(static_cast<std::uint32_t>(tiff_.integer(e)), Kind::Gps, depth + 1, false); break;
                    default: break;
                }
            } catch (const std::exception&) {
                // A bad value skips that tag only.
            }
        }
        if (kind == Kind::Gps) {
            if (lat) exif.gps_latitude = (lat_ref && *lat_ref == "S") ? -*lat : *lat;
            if (lon) exif.gps_longitude = (lon_ref && *lon_ref == "W") ? -*lon : *lon;
            if (alt) exif.gps_altitude = alt_ref == 1 ? -*alt : *alt;
        }
        // Embedded JPEG previews.
        if (jpeg_offset != 0 && jpeg_length != 0) addPreview(jpeg_offset, jpeg_length, thumbnail_ifd ? PreviewKind::ExifThumbnail : PreviewKind::EmbeddedJpeg);
        // Any single-strip JPEG directory is a candidate (CR2 keeps a full-size one in IFD0); lossless JPEG
        // (raw sensor data) is filtered out in addPreview.
        if ((compression == 6 || compression == 7) && strips == 1 && strip_length != 0) {
            addPreview(strip_offset, strip_length, thumbnail_ifd ? PreviewKind::ExifThumbnail : PreviewKind::EmbeddedJpeg);
        }
        return next;
    }

    void addPreview(std::uint64_t offset, std::uint64_t length, PreviewKind kind) {
        const std::uint64_t start = base_ + offset;
        if (start > file_.size() || length > file_.size() - start || length < 4) return;
        const auto jpeg = file_.subspan(static_cast<std::size_t>(start), static_cast<std::size_t>(length));
        if (jpeg[0] != 0xFF || jpeg[1] != 0xD8) return;
        for (const auto& p : out_.previews)
            if (p.offset == start) return;
        bool lossless = false;
        const auto [w, h] = jpegSize(jpeg, &lossless);
        if (lossless) return;
        out_.previews.push_back({kind, start, length, w, h});
    }

    Exif& exif_() {
        if (!out_.exif) out_.exif.emplace();
        return *out_.exif;
    }

    const Tiff& tiff_;
    std::uint64_t base_;
    std::span<const std::uint8_t> file_;
    Metadata& out_;
    std::set<std::uint32_t> visited_;
};

void readExifBlock(std::span<const std::uint8_t> file, std::uint64_t base, std::uint64_t length, Metadata& out) {
    try {
        const Tiff tiff(file.subspan(static_cast<std::size_t>(base), static_cast<std::size_t>(length)));
        Walker(tiff, base, file, out).run();
    } catch (const std::exception& e) {
        out.warnings.push_back(std::string("EXIF could not be read: ") + e.what());
    }
}

void readJpeg(std::span<const std::uint8_t> file, Metadata& out) {
    out.container = "jpeg";
    std::size_t at = 2;
    static const char kXmp[] = "http://ns.adobe.com/xap/1.0/";
    while (at + 4 <= file.size()) {
        if (file[at] != 0xFF) {
            out.warnings.push_back("unexpected data between JPEG segments");
            break;
        }
        const auto marker = file[at + 1];
        if (marker == 0xFF) {
            ++at;
            continue;
        }
        if (marker == 0xD8 || (marker >= 0xD0 && marker <= 0xD7) || marker == 0x01) {
            at += 2;
            continue;
        }
        if (marker == 0xD9 || marker == 0xDA) break;
        const auto length = be16(file, at + 2);
        if (length < 2 || at + 2 + length > file.size()) throw Truncated("JPEG segment past the end");
        const std::size_t payload = at + 4, size = length - 2U;
        if (marker == 0xE1 && size >= 6 && std::memcmp(file.data() + payload, "Exif\0\0", 6) == 0 && !out.exif) {
            readExifBlock(file, payload + 6, size - 6, out);
        } else if (marker == 0xE1 && size > sizeof(kXmp) && std::memcmp(file.data() + payload, kXmp, sizeof(kXmp)) == 0 && out.xmp.empty()) {
            out.xmp.assign(file.begin() + static_cast<std::ptrdiff_t>(payload + sizeof(kXmp)), file.begin() + static_cast<std::ptrdiff_t>(payload + size));
        } else if (marker == 0xED && size >= 14 && std::memcmp(file.data() + payload, "Photoshop 3.0\0", 14) == 0) {
            // Photoshop image resources: 8BIM blocks; 0x0404 holds IPTC-NAA.
            std::size_t p = payload + 14;
            const std::size_t end = payload + size;
            while (p + 12 <= end && std::memcmp(file.data() + p, "8BIM", 4) == 0) {
                const auto id = be16(file, p + 4);
                const std::size_t name_length = file[p + 6];
                std::size_t q = p + 7 + name_length + ((name_length + 1) % 2);
                if (q + 4 > end) break;
                const auto block = be32(file, q);
                q += 4;
                if (block > end - q) break;
                if (id == 0x0404) out.iptc.assign(file.begin() + static_cast<std::ptrdiff_t>(q), file.begin() + static_cast<std::ptrdiff_t>(q + block));
                p = q + block + (block % 2);
            }
        } else if (marker >= 0xC0 && marker <= 0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC && size >= 5 && !out.width) {
            out.height = be16(file, payload + 1);
            out.width = be16(file, payload + 3);
        }
        at += 2 + length;
    }
}

void readPng(std::span<const std::uint8_t> file, Metadata& out) {
    out.container = "png";
    std::size_t at = 8;
    int chunks = 0;
    while (at + 12 <= file.size() && ++chunks < 100000) {
        const auto length = be32(file, at);
        const auto* type = file.data() + at + 4;
        if (length > file.size() - at - 12) throw Truncated("PNG chunk past the end");
        const std::size_t data = at + 8;
        if (std::memcmp(type, "IHDR", 4) == 0 && length >= 8) {
            out.width = be32(file, data);
            out.height = be32(file, data + 4);
        } else if (std::memcmp(type, "eXIf", 4) == 0 && !out.exif) {
            readExifBlock(file, data, length, out);
        } else if (std::memcmp(type, "iTXt", 4) == 0 && out.xmp.empty()) {
            static const char kKey[] = "XML:com.adobe.xmp";
            if (length > sizeof(kKey) + 4 && std::memcmp(file.data() + data, kKey, sizeof(kKey)) == 0) {
                // keyword\0, compression flag, method, language\0, translated keyword\0, text (uncompressed only).
                std::size_t p = data + sizeof(kKey);
                const std::size_t end = data + length;
                const bool compressed = file[p] != 0;
                p += 2;
                for (int skip = 0; skip < 2 && p < end; ++skip) {
                    while (p < end && file[p] != 0) ++p;
                    ++p;
                }
                if (!compressed && p <= end) out.xmp.assign(file.begin() + static_cast<std::ptrdiff_t>(p), file.begin() + static_cast<std::ptrdiff_t>(end));
            }
        } else if (std::memcmp(type, "IEND", 4) == 0) {
            break;
        }
        at = data + length + 4;
    }
}

}  // namespace

std::optional<Metadata> read(std::span<const std::uint8_t> file, std::string& error) {
    Metadata out;
    try {
        if (file.size() >= 3 && file[0] == 0xFF && file[1] == 0xD8 && file[2] == 0xFF) {
            readJpeg(file, out);
        } else if (file.size() >= 8 && std::memcmp(file.data(), "\x89PNG\r\n\x1a\n", 8) == 0) {
            readPng(file, out);
        } else if (file.size() >= 8 && ((file[0] == 'I' && file[1] == 'I') || (file[0] == 'M' && file[1] == 'M'))) {
            out.container = "tiff";
            const Tiff tiff(file);
            Walker(tiff, 0, file, out).run();
        } else {
            error = "unsupported container (JPEG, PNG and TIFF-based files are supported)";
            return std::nullopt;
        }
    } catch (const std::exception& e) {
        if (out.container.empty()) {
            error = e.what();
            return std::nullopt;
        }
        out.warnings.push_back(std::string("the file is truncated or damaged: ") + e.what());
    }
    std::sort(out.previews.begin(), out.previews.end(), [](const Preview& a, const Preview& b) {
        return std::uint64_t{a.width} * a.height > std::uint64_t{b.width} * b.height;
    });
    return out;
}

}  // namespace croptop::metadata
