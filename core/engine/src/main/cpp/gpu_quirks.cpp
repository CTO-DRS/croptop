#include "gpu_quirks.h"

#include <algorithm>
#include <cctype>
#include <charconv>
#include <string>

namespace croptop::gpu {
namespace {

std::string lower(std::string_view s) {
    std::string out(s);
    std::transform(out.begin(), out.end(), out.begin(), [](unsigned char c) { return static_cast<char>(std::tolower(c)); });
    return out;
}

bool parseNumber(std::string_view s, std::uint32_t& out) {
    int base = 10;
    if (s.size() > 2 && s[0] == '0' && (s[1] == 'x' || s[1] == 'X')) {
        s.remove_prefix(2);
        base = 16;
    }
    unsigned long long v = 0;
    const auto [end, ec] = std::from_chars(s.data(), s.data() + s.size(), v, base);
    if (ec != std::errc{} || end != s.data() + s.size() || v > 0xFFFFFFFFULL) return false;
    out = static_cast<std::uint32_t>(v);
    return true;
}

bool parseFlags(std::string_view list, Quirks& q) {
    while (!list.empty()) {
        const auto comma = list.find(',');
        const auto flag = list.substr(0, comma);
        list = comma == std::string_view::npos ? std::string_view{} : list.substr(comma + 1);
        if (flag == "block") q.blocked = true;
        else if (flag == "no_compute_blend") q.no_compute_blend = true;
        else if (flag == "single_frame_in_flight") q.single_frame_in_flight = true;
        else if (flag == "avoid_float_storage") q.avoid_float_storage = true;
        else if (flag == "software_renderer") q.software_renderer = true;
        else return false;
    }
    return true;
}

}  // namespace

bool Rule::matches(const GpuInfo& info) const {
    if (has_vendor && info.vendor_id != vendor_id) return false;
    if (has_device && info.device_id != device_id) return false;
    if (has_driver_below && info.driver_version >= driver_below) return false;
    if (has_driver_min && info.driver_version < driver_min) return false;
    if (has_api_below && info.api_version >= api_below) return false;
    if (has_type && info.type != type) return false;
    if (!name_lower.empty() && lower(info.name).find(name_lower) == std::string::npos) return false;
    return true;
}

std::vector<Rule> parseRules(std::string_view text, std::vector<std::string>* warnings) {
    std::vector<Rule> rules;
    std::size_t line_number = 0;
    while (!text.empty()) {
        const auto newline = text.find('\n');
        auto line = text.substr(0, newline);
        text = newline == std::string_view::npos ? std::string_view{} : text.substr(newline + 1);
        ++line_number;
        if (const auto hash = line.find('#'); hash != std::string_view::npos) line = line.substr(0, hash);
        Rule rule;
        bool any = false, ok = true, has_flags = false;
        while (ok) {
            while (!line.empty() && std::isspace(static_cast<unsigned char>(line.front()))) line.remove_prefix(1);
            if (line.empty()) break;
            auto end = line.find_first_of(" \t\r");
            const auto token = line.substr(0, end);
            line = end == std::string_view::npos ? std::string_view{} : line.substr(end);
            any = true;
            const auto eq = token.find('=');
            if (eq == std::string_view::npos) {
                ok = false;
                break;
            }
            const auto key = token.substr(0, eq);
            const auto value = token.substr(eq + 1);
            any = true;
            if (key == "vendor") ok = parseNumber(value, rule.vendor_id), rule.has_vendor = true;
            else if (key == "device") ok = parseNumber(value, rule.device_id), rule.has_device = true;
            else if (key == "driver_below") ok = parseNumber(value, rule.driver_below), rule.has_driver_below = true;
            else if (key == "driver_min") ok = parseNumber(value, rule.driver_min), rule.has_driver_min = true;
            else if (key == "api_below") ok = parseNumber(value, rule.api_below), rule.has_api_below = true;
            else if (key == "name") rule.name_lower = lower(value), ok = !value.empty();
            else if (key == "type") {
                rule.has_type = true;
                if (value == "cpu") rule.type = DeviceType::Cpu;
                else if (value == "integrated") rule.type = DeviceType::Integrated;
                else if (value == "discrete") rule.type = DeviceType::Discrete;
                else if (value == "virtual") rule.type = DeviceType::Virtual;
                else ok = false;
            } else if (key == "flags") {
                ok = parseFlags(value, rule.apply);
                has_flags = true;
            } else ok = false;
        }
        if (!any) continue;
        if (!ok || !has_flags) {
            if (warnings != nullptr) warnings->push_back("line " + std::to_string(line_number) + ": invalid GPU rule");
            continue;
        }
        rules.push_back(std::move(rule));
    }
    return rules;
}

const std::vector<Rule>& builtInRules() {
    static const std::vector<Rule> rules = parseRules(
        "name=swiftshader flags=software_renderer\n"
        "name=llvmpipe flags=software_renderer\n"
        "type=cpu flags=software_renderer\n");
    return rules;
}

Quirks quirksFor(const GpuInfo& info, const std::vector<Rule>& extra) {
    Quirks result;
    const auto merge = [&](const Rule& rule) {
        if (!rule.matches(info)) return;
        result.blocked = result.blocked || rule.apply.blocked;
        result.no_compute_blend = result.no_compute_blend || rule.apply.no_compute_blend;
        result.single_frame_in_flight = result.single_frame_in_flight || rule.apply.single_frame_in_flight;
        result.avoid_float_storage = result.avoid_float_storage || rule.apply.avoid_float_storage;
        result.software_renderer = result.software_renderer || rule.apply.software_renderer;
    };
    for (const auto& rule : builtInRules()) merge(rule);
    for (const auto& rule : extra) merge(rule);
    return result;
}

}  // namespace croptop::gpu
