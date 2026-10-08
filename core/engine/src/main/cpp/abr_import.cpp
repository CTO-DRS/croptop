#include "abr_import.h"

#include "psd_descriptor.h"

#include <algorithm>
#include <bit>
#include <cmath>
#include <cstring>
#include <map>
#include <memory>
#include <stdexcept>

namespace croptop::brush {
namespace {

struct Truncated final : std::runtime_error {
    using std::runtime_error::runtime_error;
};

/** Big-endian bounds-checked cursor over [begin, end) of the file. */
class Reader final {
public:
    Reader(std::span<const std::uint8_t> data, std::size_t begin, std::size_t end) : data_(data), pos_(begin), end_(end) {}

    std::size_t pos() const { return pos_; }
    std::size_t end() const { return end_; }
    std::size_t remaining() const { return end_ - pos_; }
    void seek(std::size_t pos) {
        if (pos > end_) throw Truncated("seek past the end");
        pos_ = pos;
    }
    void skip(std::size_t n) { seek(require(n) + n); }
    std::uint8_t u8() { return data_[require(1)]; }
    std::uint16_t u16() {
        const auto p = require(2);
        return static_cast<std::uint16_t>((data_[p] << 8) | data_[p + 1]);
    }
    std::uint32_t u32() {
        const auto p = require(4);
        return (static_cast<std::uint32_t>(data_[p]) << 24) | (static_cast<std::uint32_t>(data_[p + 1]) << 16) |
               (static_cast<std::uint32_t>(data_[p + 2]) << 8) | data_[p + 3];
    }
    std::int16_t i16() { return static_cast<std::int16_t>(u16()); }
    std::int32_t i32() { return static_cast<std::int32_t>(u32()); }
    std::uint64_t u64() {
        const std::uint64_t high = u32();
        return (high << 32) | u32();
    }
    double f64() { return std::bit_cast<double>(u64()); }
    std::span<const std::uint8_t> bytes(std::size_t n) {
        const auto p = require(n);
        return data_.subspan(p, n);
    }
    /** Reader over the next n bytes (advancing past them). */
    Reader sub(std::size_t n) {
        const auto p = require(n);
        return Reader(data_, p, p + n);
    }
    std::string ascii(std::size_t n) {
        const auto b = bytes(n);
        return {b.begin(), b.end()};
    }
    /** uint32 UTF-16 code-unit count, then UTF-16BE; returns UTF-8 without a trailing NUL. */
    std::string unicode() {
        const auto count = u32();
        if (count > remaining() / 2) throw Truncated("unicode string past the end");
        std::string out;
        for (std::uint32_t i = 0; i < count; ++i) {
            std::uint32_t c = u16();
            if (c >= 0xD800 && c < 0xDC00 && i + 1 < count) {
                const std::uint32_t low = u16();
                ++i;
                c = (low >= 0xDC00 && low < 0xE000) ? 0x10000 + ((c - 0xD800) << 10) + (low - 0xDC00) : 0xFFFD;
            } else if (c >= 0xD800 && c < 0xE000) {
                c = 0xFFFD;
            }
            if (c == 0) continue;
            if (c < 0x80) {
                out += static_cast<char>(c);
            } else if (c < 0x800) {
                out += static_cast<char>(0xC0 | (c >> 6));
                out += static_cast<char>(0x80 | (c & 0x3F));
            } else if (c < 0x10000) {
                out += static_cast<char>(0xE0 | (c >> 12));
                out += static_cast<char>(0x80 | ((c >> 6) & 0x3F));
                out += static_cast<char>(0x80 | (c & 0x3F));
            } else {
                out += static_cast<char>(0xF0 | (c >> 18));
                out += static_cast<char>(0x80 | ((c >> 12) & 0x3F));
                out += static_cast<char>(0x80 | ((c >> 6) & 0x3F));
                out += static_cast<char>(0x80 | (c & 0x3F));
            }
        }
        return out;
    }

private:
    std::size_t require(std::size_t n) {
        if (n > end_ - pos_) throw Truncated("unexpected end of brush data");
        const auto p = pos_;
        pos_ += n;
        return p;
    }

    std::span<const std::uint8_t> data_;
    std::size_t pos_;
    std::size_t end_;
};

struct Budget final {
    const AbrLimits& limits;
    std::uint64_t tip_bytes = 0;
};

/** PackBits rows (per-row uint16 byte counts first) or raw 8-bit samples into width × height coverage. */
std::vector<std::uint8_t> readSamples(Reader& in, std::uint32_t width, std::uint32_t height, int depth, int compression,
                                      Budget& budget) {
    if (width == 0 || height == 0 || width > budget.limits.max_tip_edge || height > budget.limits.max_tip_edge) {
        throw std::runtime_error("tip size is out of range");
    }
    const auto pixels = static_cast<std::uint64_t>(width) * height;
    if (budget.tip_bytes + pixels > budget.limits.max_tip_bytes) throw std::runtime_error("tips exceed the memory limit");
    std::vector<std::uint8_t> out(static_cast<std::size_t>(pixels));
    if (depth == 16) {
        if (compression != 0) throw std::runtime_error("compressed 16-bit tips are not supported");
        if (pixels * 2 > in.remaining()) throw Truncated("16-bit tip past the end");
        for (auto& v : out) v = static_cast<std::uint8_t>((in.u16() + 128U) / 257U);
    } else if (depth == 8) {
        if (compression == 0) {
            const auto raw = in.bytes(static_cast<std::size_t>(pixels));
            std::copy(raw.begin(), raw.end(), out.begin());
        } else if (compression == 1) {
            if (static_cast<std::uint64_t>(height) * 2 > in.remaining()) throw Truncated("row counts past the end");
            std::vector<std::uint16_t> counts(height);
            for (auto& c : counts) c = in.u16();
            for (std::uint32_t y = 0; y < height; ++y) {
                Reader row = in.sub(counts[y]);
                std::uint8_t* dst = out.data() + static_cast<std::size_t>(y) * width;
                std::uint32_t x = 0;
                while (row.remaining() > 0 && x < width) {
                    const auto n = static_cast<std::int8_t>(row.u8());
                    if (n >= 0) {
                        const auto run = std::min<std::uint32_t>(static_cast<std::uint32_t>(n) + 1U, width - x);
                        const auto literal = row.bytes(static_cast<std::size_t>(n) + 1U);
                        std::copy_n(literal.begin(), run, dst + x);
                        x += run;
                    } else if (n != -128) {
                        const auto run = std::min<std::uint32_t>(static_cast<std::uint32_t>(1 - n), width - x);
                        std::fill_n(dst + x, run, row.u8());
                        x += run;
                    }
                }
            }
        } else {
            throw std::runtime_error("unknown tip compression");
        }
    } else {
        throw std::runtime_error("unsupported tip bit depth");
    }
    budget.tip_bytes += pixels;
    return out;
}

// ---- v1 / v2 --------------------------------------------------------------------------------------------

AbrBrush parseSampledV1V2(Reader& r, int version, AbrFile& file, Budget& budget) {
    AbrBrush brush;
    brush.sampled = true;
    r.skip(4);  // miscellaneous
    brush.spacing = r.u16() / 100.0F;
    if (version == 2) brush.name = r.unicode();
    r.skip(1);  // anti-aliasing
    r.skip(8);  // short bounds
    const auto top = r.i32(), left = r.i32(), bottom = r.i32(), right = r.i32();
    const auto depth = r.i16();
    const auto compression = r.u8();
    const auto width = static_cast<std::int64_t>(right) - left, height = static_cast<std::int64_t>(bottom) - top;
    if (width <= 0 || height <= 0 || width > 65535 || height > 65535) throw std::runtime_error("tip bounds are invalid");
    AbrTip tip;
    tip.width = static_cast<std::uint32_t>(width);
    tip.height = static_cast<std::uint32_t>(height);
    tip.coverage = readSamples(r, tip.width, tip.height, depth, compression, budget);
    brush.diameter = static_cast<float>(std::max(width, height));
    brush.tip = file.tips.size();
    file.tips.push_back(std::move(tip));
    return brush;
}

AbrBrush parseComputedV1V2(Reader& r, int version) {
    AbrBrush brush;
    r.skip(4);  // miscellaneous
    brush.spacing = r.u16() / 100.0F;
    if (version == 2) brush.name = r.unicode();
    brush.diameter = r.u16();
    brush.roundness = std::clamp(r.u16() / 100.0F, 0.0F, 1.0F);
    brush.angle = r.i16();
    brush.hardness = std::clamp(r.u16() / 100.0F, 0.0F, 1.0F);
    return brush;
}

void parseV1V2(Reader& in, int version, AbrFile& file, Budget& budget) {
    const auto count = in.u16();
    for (std::uint32_t index = 0; index < count; ++index) {
        const auto type = in.u16();
        const auto size = in.u32();
        if (size > in.remaining()) throw Truncated("brush record past the end");
        Reader record = in.sub(size);
        if (file.brushes.size() >= budget.limits.max_brushes) {
            file.warnings.push_back("brush limit reached; the rest were skipped");
            return;
        }
        try {
            AbrBrush brush;
            if (type == 1) {
                brush = parseComputedV1V2(record, version);
            } else if (type == 2) {
                brush = parseSampledV1V2(record, version, file, budget);
            } else {
                file.warnings.push_back("brush " + std::to_string(index + 1) + ": unknown type skipped");
                continue;
            }
            if (brush.name.empty()) brush.name = (brush.sampled ? "Sampled Brush " : "Brush ") + std::to_string(index + 1);
            file.brushes.push_back(std::move(brush));
        } catch (const std::exception& e) {
            file.warnings.push_back("brush " + std::to_string(index + 1) + " skipped: " + e.what());
        }
    }
}

// ---- v6+ action descriptor ---------------------------------------------------------------------------

using psd::DescValue;

float percent(const DescValue* v, float fallback) {
    return v == nullptr ? fallback : std::clamp(static_cast<float>(v->number / 100.0), 0.0F, 1.0F);
}

void readDynamic(const DescValue& brush, const char* variation_key, AbrDynamic& out) {
    const auto* variation = brush.find(variation_key);
    if (variation == nullptr) return;
    if (const auto* control = variation->find("bVTy")) out.control = std::clamp(static_cast<int>(control->number), 0, 5);
    out.jitter = percent(variation->find("jitter"), 0.0F);
    out.minimum = percent(variation->find("Mnm "), out.minimum);
}

AbrBrush brushFromPreset(const DescValue& preset, const std::map<std::string, std::size_t>& tips_by_uuid) {
    AbrBrush out;
    if (const auto* name = preset.find("Nm  ")) out.name = name->text;
    const auto* tip = preset.find("Brsh");
    if (tip != nullptr && tip->type == "Objc") {
        out.sampled = tip->text == "sampledBrush";
        if (const auto* v = tip->find("Dmtr")) out.diameter = std::clamp(static_cast<float>(v->number), 0.0F, 100000.0F);
        out.hardness = percent(tip->find("Hrdn"), 1.0F);
        if (const auto* v = tip->find("Angl")) out.angle = static_cast<float>(std::fmod(v->number, 360.0));
        out.roundness = percent(tip->find("Rndn"), 1.0F);
        if (const auto* v = tip->find("Spcn")) out.spacing = std::clamp(static_cast<float>(v->number / 100.0), 0.01F, 10.0F);
        if (const auto* v = tip->find("Intr")) out.spacing_enabled = v->flag;
        if (const auto* v = tip->find("sampledData")) {
            out.sampled = true;
            const auto found = tips_by_uuid.find(v->text);
            if (found != tips_by_uuid.end()) out.tip = found->second;
        }
    }
    if (const auto* v = preset.find("useTipDynamics")) out.has_dynamics = v->flag;
    readDynamic(preset, "szVr", out.size);
    out.size.minimum = percent(preset.find("minimumDiameter"), out.size.minimum);
    readDynamic(preset, "opVr", out.opacity);
    readDynamic(preset, "prVr", out.flow);
    return out;
}

// ---- v6+ -------------------------------------------------------------------------------------------------

void parseSamples(Reader& section, int subversion, AbrFile& file, Budget& budget) {
    while (section.remaining() >= 4) {
        const auto size = section.u32();
        const auto padded = (static_cast<std::uint64_t>(size) + 3U) & ~std::uint64_t{3};
        if (size > section.remaining()) throw Truncated("sampled tip past the end");
        Reader tip_reader = section.sub(size);
        section.skip(static_cast<std::size_t>(std::min<std::uint64_t>(padded - size, section.remaining())));
        if (file.tips.size() >= budget.limits.max_brushes) {
            file.warnings.push_back("tip limit reached; the rest were skipped");
            return;
        }
        try {
            AbrTip tip;
            const auto header_start = tip_reader.pos();
            const auto uuid_length = tip_reader.u8();
            tip.uuid = tip_reader.ascii(uuid_length);
            tip_reader.seek(header_start + (subversion == 1 ? 47U : 301U));
            const auto top = tip_reader.i32(), left = tip_reader.i32(), bottom = tip_reader.i32(), right = tip_reader.i32();
            const auto depth = tip_reader.i16();
            const auto compression = tip_reader.u8();
            const auto width = static_cast<std::int64_t>(right) - left, height = static_cast<std::int64_t>(bottom) - top;
            if (width <= 0 || height <= 0 || width > 65535 || height > 65535) throw std::runtime_error("tip bounds are invalid");
            tip.width = static_cast<std::uint32_t>(width);
            tip.height = static_cast<std::uint32_t>(height);
            tip.coverage = readSamples(tip_reader, tip.width, tip.height, depth, compression, budget);
            file.tips.push_back(std::move(tip));
        } catch (const std::exception& e) {
            file.warnings.push_back(std::string("sampled tip skipped: ") + e.what());
        }
    }
}

void parseV6(Reader& in, AbrFile& file, Budget& budget) {
    const auto subversion = in.u16();
    if (subversion != 1 && subversion != 2) throw std::runtime_error("unsupported ABR sub-version");
    std::optional<Reader> descriptor;
    while (in.remaining() >= 12) {
        if (in.ascii(4) != "8BIM") {
            // Some writers pad sections; resynchronise on the next signature within 3 bytes.
            bool found = false;
            const auto back = in.pos() - 4;
            for (std::size_t pad = 1; pad <= 3 && back + pad + 4 <= in.end(); ++pad) {
                in.seek(back + pad);
                if (in.ascii(4) == "8BIM") {
                    found = true;
                    break;
                }
            }
            if (!found) break;
        }
        const auto key = in.ascii(4);
        const auto length = in.u32();
        if (length > in.remaining()) throw Truncated("section past the end");
        Reader section = in.sub(length);
        if (key == "samp") {
            parseSamples(section, subversion, file, budget);
        } else if (key == "desc") {
            descriptor = section;
        }
    }
    std::map<std::string, std::size_t> tips_by_uuid;
    for (std::size_t i = 0; i < file.tips.size(); ++i) tips_by_uuid.emplace(file.tips[i].uuid, i);
    if (descriptor) {
        try {
            std::string parse_error;
            const auto root = psd::parseDescriptor(descriptor->bytes(descriptor->remaining()), parse_error);
            if (!root) throw std::runtime_error(parse_error);
            for (std::size_t k = 0; k < root->keys.size(); ++k) {
                const auto& key = root->keys[k];
                const auto& value = root->values[k];
                if (key != "Brsh" || value.type != "VlLs") continue;
                for (const auto& preset : value.list) {
                    if (preset.type != "Objc") continue;
                    if (file.brushes.size() >= budget.limits.max_brushes) {
                        file.warnings.push_back("brush limit reached; the rest were skipped");
                        break;
                    }
                    auto brush = brushFromPreset(preset, tips_by_uuid);
                    if (brush.sampled && !brush.tip) file.warnings.push_back("\"" + brush.name + "\": its sampled tip is missing");
                    if (brush.sampled && brush.tip && brush.diameter <= 0.0F) {
                        const auto& tip = file.tips[*brush.tip];
                        brush.diameter = static_cast<float>(std::max(tip.width, tip.height));
                    }
                    if (brush.name.empty()) brush.name = "Brush " + std::to_string(file.brushes.size() + 1);
                    file.brushes.push_back(std::move(brush));
                }
            }
            return;
        } catch (const std::exception& e) {
            file.brushes.clear();
            file.warnings.push_back(std::string("brush settings could not be read (") + e.what() + "); tips imported with defaults");
        }
    }
    for (std::size_t i = 0; i < file.tips.size() && file.brushes.size() < budget.limits.max_brushes; ++i) {
        AbrBrush brush;
        brush.name = "Sampled Brush " + std::to_string(i + 1);
        brush.sampled = true;
        brush.tip = i;
        brush.diameter = static_cast<float>(std::max(file.tips[i].width, file.tips[i].height));
        file.brushes.push_back(std::move(brush));
    }
}

}  // namespace

std::optional<AbrFile> parseAbr(std::span<const std::uint8_t> data, std::string& error, const AbrLimits& limits) {
    AbrFile file;
    Budget budget{limits};
    try {
        Reader in(data, 0, data.size());
        file.version = in.u16();
        switch (file.version) {
            case 1:
            case 2: parseV1V2(in, file.version, file, budget); break;
            case 6:
            case 7:
            case 10: parseV6(in, file, budget); break;
            default: error = "unsupported ABR version " + std::to_string(file.version); return std::nullopt;
        }
    } catch (const std::bad_alloc&) {
        error = "not enough memory to import brushes";
        return std::nullopt;
    } catch (const std::exception& e) {
        if (file.brushes.empty()) {
            error = std::string("not a readable brush file: ") + e.what();
            return std::nullopt;
        }
        file.warnings.push_back(std::string("file is truncated: ") + e.what());
    }
    if (file.brushes.empty()) {
        error = "the file contains no brushes";
        return std::nullopt;
    }
    return file;
}

}  // namespace croptop::brush
