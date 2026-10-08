#pragma once

// Helpers shared by croptop_format.cpp and recovery_journal.cpp; not a public API.

#include <algorithm>
#include <atomic>
#include <charconv>
#include <cstddef>
#include <cstdint>
#include <optional>
#include <span>
#include <string_view>
#include <thread>
#include <utility>
#include <vector>

#include <zlib.h>

#include "parallel_util.h"
#include "tile_store.h"

namespace croptop::io_detail {

using croptop::parallel::JoiningThread;
using croptop::parallel::parallelFor;
using croptop::parallel::workerCount;

inline void put16(std::vector<std::uint8_t>& out, std::uint16_t value) {
    out.push_back(static_cast<std::uint8_t>(value));
    out.push_back(static_cast<std::uint8_t>(value >> 8U));
}

inline void put32(std::vector<std::uint8_t>& out, std::uint32_t value) {
    put16(out, static_cast<std::uint16_t>(value));
    put16(out, static_cast<std::uint16_t>(value >> 16U));
}

inline void put64(std::vector<std::uint8_t>& out, std::uint64_t value) {
    put32(out, static_cast<std::uint32_t>(value));
    put32(out, static_cast<std::uint32_t>(value >> 32U));
}

inline std::uint16_t get16(std::span<const std::uint8_t> bytes, std::size_t offset) {
    return static_cast<std::uint16_t>(bytes[offset] | (bytes[offset + 1U] << 8U));
}

inline std::uint32_t get32(std::span<const std::uint8_t> bytes, std::size_t offset) {
    return static_cast<std::uint32_t>(get16(bytes, offset)) | (static_cast<std::uint32_t>(get16(bytes, offset + 2U)) << 16U);
}

inline std::uint64_t get64(std::span<const std::uint8_t> bytes, std::size_t offset) {
    return static_cast<std::uint64_t>(get32(bytes, offset)) | (static_cast<std::uint64_t>(get32(bytes, offset + 4U)) << 32U);
}

inline std::uint32_t crc32Of(std::span<const std::uint8_t> bytes) {
    uLong crc = crc32(0L, Z_NULL, 0);
    std::size_t offset = 0;
    while (offset < bytes.size()) {
        const auto chunk = static_cast<uInt>(std::min<std::size_t>(bytes.size() - offset, 1U << 30U));
        crc = crc32(crc, bytes.data() + offset, chunk);
        offset += chunk;
    }
    return static_cast<std::uint32_t>(crc);
}

inline std::uint8_t formatCode(PixelFormat format) {
    switch (format) {
        case PixelFormat::RGBA8: return 1;
        case PixelFormat::RGBA16: return 2;
        case PixelFormat::RGBA32F: return 3;
        case PixelFormat::A8: return 4;
        case PixelFormat::A16: return 5;
    }
    return 0;
}

inline std::optional<PixelFormat> formatFromCode(std::uint8_t code) {
    switch (code) {
        case 1: return PixelFormat::RGBA8;
        case 2: return PixelFormat::RGBA16;
        case 3: return PixelFormat::RGBA32F;
        case 4: return PixelFormat::A8;
        case 5: return PixelFormat::A16;
        default: return std::nullopt;
    }
}

inline bool validUuid(std::string_view uuid) {
    return !uuid.empty() && uuid.size() <= 64U && std::all_of(uuid.begin(), uuid.end(), [](char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '-' || c == '_';
    });
}

/** Parses a canonical non-negative decimal (no sign, no leading zeros). */
inline std::optional<std::uint32_t> parseIndex(std::string_view text) {
    if (text.empty() || text.size() > 9U || (text.size() > 1U && text.front() == '0')) return std::nullopt;
    std::uint32_t value = 0;
    const auto [end, error] = std::from_chars(text.data(), text.data() + text.size(), value);
    if (error != std::errc() || end != text.data() + text.size()) return std::nullopt;
    return value;
}

inline bool allZero(const TileBuffer& tile) {
    return std::all_of(tile.begin(), tile.end(), [](std::uint8_t value) { return value == 0U; });
}

}  // namespace croptop::io_detail
