#pragma once

// GPU driver quirks table and device blocklist. Pure logic with no Vulkan types, so it is
// unit-testable on the host. The Vulkan layer fills `GpuInfo` from VkPhysicalDeviceProperties and applies the flags.
//
// Rules come from two places: a small built-in list (software rasterisers) and an optional text list shipped
// with the app or fetched later (`parseRules`), so a bad driver can be handled without a native release.
//
// Rule line (blank lines and `#` comments ignored), whitespace-separated key=value pairs:
//   vendor=0x13b5 device=0x1234 name=Mali-G52 driver_below=0x1000 driver_min=0x0800 api_below=0x401000 type=cpu
//   flags=no_compute_blend,single_frame_in_flight     (or `block` to send the device to the CPU fallback)
// `name` is a case-insensitive substring; every present key must match.

#include <cstdint>
#include <string>
#include <string_view>
#include <vector>

namespace croptop::gpu {

enum class DeviceType : std::uint8_t { Other, Integrated, Discrete, Virtual, Cpu };

struct GpuInfo final {
    std::uint32_t vendor_id = 0;
    std::uint32_t device_id = 0;
    std::uint32_t driver_version = 0;
    std::uint32_t api_version = 0;
    DeviceType type = DeviceType::Other;
    std::string name;
};

struct Quirks final {
    bool blocked = false;                  // do not use this device; fall back to the CPU renderer
    bool no_compute_blend = false;         // composite on the graphics queue / CPU instead of the compute blend pipeline
    bool single_frame_in_flight = false;   // serialise frames (drivers that mishandle overlapping submissions)
    bool avoid_float_storage = false;      // keep 16F/32F working images off storage-image paths
    bool software_renderer = false;        // SwiftShader / llvmpipe: correct but slow, lower the preview quality
};

struct Rule final {
    bool has_vendor = false, has_device = false, has_driver_below = false, has_driver_min = false, has_api_below = false, has_type = false;
    std::uint32_t vendor_id = 0, device_id = 0, driver_below = 0, driver_min = 0, api_below = 0;
    DeviceType type = DeviceType::Other;
    std::string name_lower;  // empty: any
    Quirks apply;
    bool matches(const GpuInfo& info) const;
};

/** Parses a rule list. Malformed lines are skipped; each one adds a message to `warnings`. */
std::vector<Rule> parseRules(std::string_view text, std::vector<std::string>* warnings = nullptr);

/** Built-in rules (software rasterisers). */
const std::vector<Rule>& builtInRules();

/** Merges the flags of every matching rule, built-ins first then `extra`. */
Quirks quirksFor(const GpuInfo& info, const std::vector<Rule>& extra = {});

}  // namespace croptop::gpu
