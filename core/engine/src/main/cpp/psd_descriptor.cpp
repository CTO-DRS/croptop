#include "psd_descriptor.h"

#include <bit>
#include <cmath>
#include <stdexcept>

namespace croptop::psd {
namespace {

constexpr int kMaxDepth = 32;
constexpr std::uint32_t kMaxItems = 100'000;

class Cursor final {
public:
    explicit Cursor(std::span<const std::uint8_t> d) : d_(d) {}
    std::size_t pos() const { return pos_; }
    std::size_t remaining() const { return d_.size() - pos_; }
    std::span<const std::uint8_t> bytes(std::uint64_t n) {
        if (n > remaining()) throw std::runtime_error("descriptor data past the end");
        const auto s = d_.subspan(pos_, static_cast<std::size_t>(n));
        pos_ += static_cast<std::size_t>(n);
        return s;
    }
    std::uint8_t u8() { return bytes(1)[0]; }
    std::uint32_t u32() {
        const auto b = bytes(4);
        return (std::uint32_t{b[0]} << 24) | (std::uint32_t{b[1]} << 16) | (std::uint32_t{b[2]} << 8) | b[3];
    }
    std::uint64_t u64() {
        const std::uint64_t high = u32();
        return (high << 32) | u32();
    }
    std::string ascii(std::size_t n) {
        const auto b = bytes(n);
        return {b.begin(), b.end()};
    }
    std::string unicode() {
        const auto count = u32();
        if (count > remaining() / 2) throw std::runtime_error("descriptor string past the end");
        std::string out;
        for (std::uint32_t i = 0; i < count; ++i) {
            const auto b = bytes(2);
            std::uint32_t c = (std::uint32_t{b[0]} << 8) | b[1];
            if (c >= 0xD800 && c < 0xDC00 && i + 1 < count) {
                const auto l = bytes(2);
                const std::uint32_t low = (std::uint32_t{l[0]} << 8) | l[1];
                ++i;
                c = (low >= 0xDC00 && low < 0xE000) ? 0x10000 + ((c - 0xD800) << 10) + (low - 0xDC00) : 0xFFFD;
            } else if (c >= 0xD800 && c < 0xE000) {
                c = 0xFFFD;
            }
            if (c == 0) continue;
            if (c < 0x80) out += static_cast<char>(c);
            else if (c < 0x800) out += static_cast<char>(0xC0 | (c >> 6)), out += static_cast<char>(0x80 | (c & 0x3F));
            else if (c < 0x10000) out += static_cast<char>(0xE0 | (c >> 12)), out += static_cast<char>(0x80 | ((c >> 6) & 0x3F)), out += static_cast<char>(0x80 | (c & 0x3F));
            else out += static_cast<char>(0xF0 | (c >> 18)), out += static_cast<char>(0x80 | ((c >> 12) & 0x3F)), out += static_cast<char>(0x80 | ((c >> 6) & 0x3F)), out += static_cast<char>(0x80 | (c & 0x3F));
        }
        return out;
    }
    std::string key() {
        const auto length = u32();
        if (length > 256) throw std::runtime_error("descriptor key too long");
        return ascii(length == 0 ? 4 : length);
    }

private:
    std::span<const std::uint8_t> d_;
    std::size_t pos_ = 0;
};

DescValue readObject(Cursor& c, int depth);

DescValue readValue(Cursor& c, const std::string& type, int depth) {
    if (depth > kMaxDepth) throw std::runtime_error("descriptor nesting too deep");
    DescValue v;
    if (type == "Objc" || type == "GlbO") {
        v = readObject(c, depth + 1);
    } else if (type == "VlLs") {
        const auto count = c.u32();
        if (count > kMaxItems || count > c.remaining() / 4) throw std::runtime_error("descriptor list too long");
        v.list.reserve(count);
        for (std::uint32_t i = 0; i < count; ++i) v.list.push_back(readValue(c, c.ascii(4), depth + 1));
    } else if (type == "doub") {
        v.number = std::bit_cast<double>(c.u64());
    } else if (type == "UntF") {
        v.text = c.ascii(4);
        v.number = std::bit_cast<double>(c.u64());
    } else if (type == "long") {
        v.number = static_cast<std::int32_t>(c.u32());
    } else if (type == "comp") {
        v.number = static_cast<double>(static_cast<std::int64_t>(c.u64()));
    } else if (type == "bool") {
        v.flag = c.u8() != 0;
    } else if (type == "TEXT") {
        v.text = c.unicode();
    } else if (type == "enum") {
        v.enum_type = c.key();
        v.text = c.key();
    } else if (type == "type" || type == "GlbC") {
        c.unicode();
        v.text = c.key();
    } else if (type == "tdta") {
        const auto b = c.bytes(c.u32());
        v.raw.assign(b.begin(), b.end());
    } else if (type == "alis" || type == "Pth ") {
        c.bytes(c.u32());
    } else if (type == "UnFl") {
        v.text = c.ascii(4);
        const auto count = c.u32();
        if (count > c.remaining() / 8) throw std::runtime_error("unit list past the end");
        for (std::uint32_t i = 0; i < count; ++i) {
            DescValue item;
            item.type = "doub";
            item.number = std::bit_cast<double>(c.u64());
            v.list.push_back(item);
        }
    } else if (type == "obj ") {
        // References: a count of typed reference items; only their structure is skipped.
        const auto count = c.u32();
        if (count > 64) throw std::runtime_error("reference too long");
        for (std::uint32_t i = 0; i < count; ++i) {
            const auto kind = c.ascii(4);
            if (kind == "prop") { c.unicode(); c.key(); c.key(); }
            else if (kind == "Clss") { c.unicode(); c.key(); }
            else if (kind == "Enmr") { c.unicode(); c.key(); c.key(); c.key(); }
            else if (kind == "rele") { c.unicode(); c.key(); c.u32(); }
            else if (kind == "Idnt" || kind == "indx") { c.u32(); }
            else if (kind == "name") { c.unicode(); c.key(); c.unicode(); }
            else throw std::runtime_error("unsupported reference item");
        }
    } else {
        throw std::runtime_error("unsupported descriptor value type '" + type + "'");
    }
    if (!std::isfinite(v.number)) v.number = 0.0;
    v.type = type;
    return v;
}

DescValue readObject(Cursor& c, int depth) {
    if (depth > kMaxDepth) throw std::runtime_error("descriptor nesting too deep");
    DescValue v;
    v.type = "Objc";
    c.unicode();  // class name
    v.text = c.key();
    const auto count = c.u32();
    if (count > kMaxItems || count > c.remaining() / 8) throw std::runtime_error("descriptor has too many items");
    v.keys.reserve(count);
    v.values.reserve(count);
    for (std::uint32_t i = 0; i < count; ++i) {
        v.keys.push_back(c.key());
        const auto type = c.ascii(4);
        v.values.push_back(readValue(c, type, depth));
    }
    return v;
}

}  // namespace

const DescValue* DescValue::find(const std::string& key) const {
    for (std::size_t i = 0; i < keys.size(); ++i)
        if (keys[i] == key) return &values[i];
    return nullptr;
}
double DescValue::number_or(const std::string& key, double fallback) const {
    const auto* v = find(key);
    return v != nullptr ? v->number : fallback;
}
bool DescValue::flag_or(const std::string& key, bool fallback) const {
    const auto* v = find(key);
    return v != nullptr && v->type == "bool" ? v->flag : fallback;
}
std::string DescValue::text_or(const std::string& key, const std::string& fallback) const {
    const auto* v = find(key);
    return v != nullptr ? v->text : fallback;
}

std::optional<DescValue> parseDescriptor(std::span<const std::uint8_t> data, std::string& error, std::size_t* consumed) {
    try {
        Cursor c(data);
        auto v = readObject(c, 0);
        if (consumed != nullptr) *consumed = c.pos();
        return v;
    } catch (const std::bad_alloc&) {
        error = "not enough memory for the descriptor";
    } catch (const std::exception& e) {
        error = e.what();
    }
    return std::nullopt;
}

}  // namespace croptop::psd
