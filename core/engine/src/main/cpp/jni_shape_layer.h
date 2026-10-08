#pragma once

// Stable byte codec shared by JNI shape-layer operations and host tests.

#include <cstdint>
#include <span>
#include <string>
#include <vector>

#include "shape_layer.h"

namespace croptop::shape::bridge {

// V1 envelope mirrors VectorPathDraft metadata plus shape::ShapeRecord geometry/styles. The name is
// manifest metadata (not part of ShapeRecord); optional primitive bounds are retained verbatim.
struct Record final {
    std::string name;
    ShapeRecord shape;
};

inline constexpr std::uint16_t kWireVersion = 1;
inline constexpr std::size_t kMaxNameBytes = 1024;
inline constexpr std::size_t kMaxAnchors = 100000;
inline constexpr std::size_t kMaxSubpaths = 100000;
inline constexpr std::size_t kMaxRecordBytes = 8U * 1024U * 1024U;
inline constexpr std::size_t kMaxBatchBytes = 32U * 1024U * 1024U;
inline constexpr std::size_t kMaxRecordsPerBatch = 100000;

bool encodeRecord(const Record& record, std::vector<std::uint8_t>& bytes, std::string& error);
bool decodeRecord(std::span<const std::uint8_t> bytes, Record& record, std::string& error);
bool encodeBatch(std::span<const Record> records, std::vector<std::uint8_t>& bytes, std::string& error);
bool decodeBatch(std::span<const std::uint8_t> bytes, std::vector<Record>& records, std::string& error);
bool hitTestBatch(std::span<const std::uint8_t> bytes, vector::Point point, double tolerance, Hit& hit, std::string& error);

}  // namespace croptop::shape::bridge
