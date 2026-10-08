#pragma once

// Photoshop action-descriptor parser (the "Objc" structures inside PSD `lfx2`, `vibA`, `blwh`,
// `CgEd`, ABR `desc`…). Untrusted input: nesting depth, item and list counts are bounded by the data.
// Values keep their type code; numbers (doub, UntF, long, comp) land in `number`, strings/units/enum
// values/class ids in `text`.

#include <cstdint>
#include <optional>
#include <span>
#include <string>
#include <vector>

namespace croptop::psd {

struct DescValue final {
    std::string type;  // "Objc", "VlLs", "doub", "UntF", "long", "comp", "bool", "TEXT", "enum", "type", "tdta"…
    double number = 0.0;
    std::string text;       // TEXT value, UntF unit ("#Pxl"), enum value, class id (Objc)
    std::string enum_type;  // enum: its type id
    bool flag = false;
    std::vector<std::uint8_t> raw;  // tdta
    std::vector<std::string> keys;  // Objc items (parallel to values)
    std::vector<DescValue> values;
    std::vector<DescValue> list;    // VlLs

    const DescValue* find(const std::string& key) const;
    double number_or(const std::string& key, double fallback) const;
    bool flag_or(const std::string& key, bool fallback) const;
    std::string text_or(const std::string& key, const std::string& fallback = {}) const;
};

/** Parses one descriptor (class name, class id, items) at the start of `data`; `consumed` gets its length. */
std::optional<DescValue> parseDescriptor(std::span<const std::uint8_t> data, std::string& error, std::size_t* consumed = nullptr);

}  // namespace croptop::psd
