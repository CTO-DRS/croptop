// Stable shape-record codec and JNI-owned record handles; document/history wiring stays in Kotlin.
#include "jni_shape_layer.h"

#include <algorithm>
#include <array>
#include <bit>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <cstring>
#include <limits>
#include <memory>
#include <string_view>
#include <stdexcept>
#include <unordered_set>
#include <utility>

#if defined(__ANDROID__)
#include <jni.h>
#endif

namespace croptop::shape::bridge {
namespace {

constexpr std::array<std::uint8_t, 4> kRecordMagic{'C', 'T', 'S', 'R'};
constexpr std::array<std::uint8_t, 4> kBatchMagic{'C', 'T', 'S', 'B'};
constexpr std::uint8_t kReserved8 = 0;

class Writer final {
  public:
    explicit Writer(std::size_t limit) : limit_(limit) {}
    bool raw(const void* source, std::size_t size) {
        if (size == 0) return true;
        if (size > limit_ - bytes_.size()) return false;
        const auto* first = static_cast<const std::uint8_t*>(source);
        bytes_.insert(bytes_.end(), first, first + size);
        return true;
    }
    bool u8(std::uint8_t value) { return raw(&value, sizeof(value)); }
    bool u16(std::uint16_t value) { return integer(value); }
    bool u32(std::uint32_t value) { return integer(value); }
    bool u64(std::uint64_t value) { return integer(value); }
    bool i32(std::int32_t value) { return integer(std::bit_cast<std::uint32_t>(value)); }
    bool i64(std::int64_t value) { return integer(std::bit_cast<std::uint64_t>(value)); }
    bool f32(float value) { return integer(std::bit_cast<std::uint32_t>(value)); }
    bool f64(double value) { return integer(std::bit_cast<std::uint64_t>(value)); }
    std::vector<std::uint8_t> take() { return std::move(bytes_); }

  private:
    template <typename T>
    bool integer(T value) {
        for (std::size_t i = 0; i < sizeof(T); ++i) {
            const auto byte = static_cast<std::uint8_t>((value >> (i * 8U)) & static_cast<T>(0xff));
            if (!u8(byte)) return false;
        }
        return true;
    }
    std::size_t limit_;
    std::vector<std::uint8_t> bytes_;
};

class Reader final {
  public:
    explicit Reader(std::span<const std::uint8_t> bytes) : bytes_(bytes) {}
    bool raw(void* target, std::size_t size) {
        if (size == 0) return true;
        if (size > bytes_.size() - offset_) return false;
        std::memcpy(target, bytes_.data() + offset_, size);
        offset_ += size;
        return true;
    }
    bool u8(std::uint8_t& value) { return raw(&value, sizeof(value)); }
    bool u16(std::uint16_t& value) { return integer(value); }
    bool u32(std::uint32_t& value) { return integer(value); }
    bool u64(std::uint64_t& value) { return integer(value); }
    bool i32(std::int32_t& value) {
        std::uint32_t bits{};
        if (!integer(bits)) return false;
        value = std::bit_cast<std::int32_t>(bits);
        return true;
    }
    bool i64(std::int64_t& value) {
        std::uint64_t bits{};
        if (!integer(bits)) return false;
        value = std::bit_cast<std::int64_t>(bits);
        return true;
    }
    bool f32(float& value) {
        std::uint32_t bits{};
        if (!integer(bits)) return false;
        value = std::bit_cast<float>(bits);
        return true;
    }
    bool f64(double& value) {
        std::uint64_t bits{};
        if (!integer(bits)) return false;
        value = std::bit_cast<double>(bits);
        return true;
    }
    bool done() const { return offset_ == bytes_.size(); }

  private:
    template <typename T>
    bool integer(T& value) {
        value = 0;
        for (std::size_t i = 0; i < sizeof(T); ++i) {
            std::uint8_t byte{};
            if (!u8(byte)) return false;
            value |= static_cast<T>(byte) << (i * 8U);
        }
        return true;
    }
    std::span<const std::uint8_t> bytes_;
    std::size_t offset_ = 0;
};

bool utf8Name(std::string_view value) {
    if (value.size() > kMaxNameBytes) return false;
    std::size_t i = 0;
    while (i < value.size()) {
        const auto c = static_cast<std::uint8_t>(value[i]);
        if (c == 0) return false;
        if (c < 0x80) {
            ++i;
            continue;
        }
        std::size_t count = c >= 0xf0 && c <= 0xf4 ? 4 : c >= 0xe0 && c <= 0xef ? 3 : c >= 0xc2 && c <= 0xdf ? 2 : 0;
        if (count == 0 || count > value.size() - i) return false;
        std::uint32_t code = c & (count == 2 ? 0x1fU : count == 3 ? 0x0fU : 0x07U);
        for (std::size_t j = 1; j < count; ++j) {
            const auto next = static_cast<std::uint8_t>(value[i + j]);
            if ((next & 0xc0U) != 0x80U) return false;
            code = (code << 6U) | (next & 0x3fU);
        }
        if ((count == 2 && code < 0x80U) || (count == 3 && code < 0x800U) || (count == 4 && code < 0x10000U) ||
            (code >= 0xd800U && code <= 0xdfffU) || code > 0x10ffffU)
            return false;
        i += count;
    }
    return true;
}

bool validPrimitive(const Primitive& p) {
    if (static_cast<unsigned>(p.kind) > static_cast<unsigned>(PrimitiveKind::Line)) return false;
    const std::array<double, 11> values{p.x, p.y, p.width, p.height, p.radii.top_left, p.radii.top_right, p.radii.bottom_right,
                                        p.radii.bottom_left, p.inner_ratio, p.rotation, static_cast<double>(p.sides)};
    return std::all_of(values.begin(), values.end(), [](double v) { return std::isfinite(v); }) && p.sides >= 3 && p.sides <= 64 &&
           std::abs(p.x) <= 1e7 && std::abs(p.y) <= 1e7 && p.width > 0 && p.height > 0 && p.width <= 2e7 && p.height <= 2e7 &&
           std::abs(p.x + p.width) <= 1e7 && std::abs(p.y + p.height) <= 1e7 &&
           p.inner_ratio >= 0.05 && p.inner_ratio <= 0.95 && p.radii.top_left >= 0 && p.radii.top_right >= 0 &&
           p.radii.bottom_right >= 0 && p.radii.bottom_left >= 0 && p.radii.top_left <= 10000 && p.radii.top_right <= 10000 &&
           p.radii.bottom_right <= 10000 && p.radii.bottom_left <= 10000 && p.rotation >= -180.0 && p.rotation < 180.0;
}

bool validRecord(const Record& record, std::string& error) {
    if (!utf8Name(record.name)) {
        error = "shape name must be valid UTF-8 without NUL and at most 1024 bytes";
        return false;
    }
    std::size_t anchors = 0;
    std::unordered_set<std::int64_t> anchor_ids;
    anchor_ids.reserve(std::min<std::size_t>(kMaxAnchors, 4096));
    if (record.shape.subpaths.size() > kMaxSubpaths) {
        error = "shape has too many subpaths";
        return false;
    }
    for (const auto& subpath : record.shape.subpaths) {
        if (subpath.anchors.size() > kMaxAnchors - anchors) {
            error = "shape has too many anchors";
            return false;
        }
        anchors += subpath.anchors.size();
        for (const auto& anchor : subpath.anchors) {
            if (static_cast<unsigned>(anchor.mode) > static_cast<unsigned>(AnchorMode::Symmetric)) {
                error = "anchor mode is unsupported";
                return false;
            }
            if (!anchor_ids.insert(anchor.id).second) {
                error = "anchor IDs must be unique within a shape";
                return false;
            }
        }
    }
    if (static_cast<unsigned>(record.shape.fill_rule) > static_cast<unsigned>(vector::FillRule::EvenOdd) ||
        static_cast<unsigned>(record.shape.stroke.style.join) > static_cast<unsigned>(vector::Join::Bevel) ||
        static_cast<unsigned>(record.shape.stroke.style.cap) > static_cast<unsigned>(vector::Cap::Square) ||
        static_cast<unsigned>(record.shape.stroke.style.align) > static_cast<unsigned>(vector::Align::Outside)) {
        error = "shape style enum is unsupported";
        return false;
    }
    if (record.shape.primitive && !validPrimitive(*record.shape.primitive)) {
        error = "primitive metadata is incomplete or out of range";
        return false;
    }
    if (!shape::validate(record.shape, error)) return false;
    return true;
}

template <std::size_t N>
bool magic(Reader& reader, const std::array<std::uint8_t, N>& expected) {
    std::array<std::uint8_t, N> got{};
    return reader.raw(got.data(), got.size()) && got == expected;
}

bool writeRecordBody(Writer& w, const Record& r) {
    const auto& s = r.shape;
    if (!w.i64(s.id) || !w.u8(static_cast<std::uint8_t>(s.fill_rule)) || !w.u8(s.fill.enabled ? 1 : 0) || !w.u8(s.stroke.enabled ? 1 : 0) ||
        !w.u8(s.primitive ? 1 : 0))
        return false;
    for (float v : s.fill.color)
        if (!w.f32(v)) return false;
    for (float v : s.stroke.color)
        if (!w.f32(v)) return false;
    const auto& st = s.stroke.style;
    if (!w.f64(st.width) || !w.u8(static_cast<std::uint8_t>(st.join)) || !w.u8(static_cast<std::uint8_t>(st.cap)) ||
        !w.u8(static_cast<std::uint8_t>(st.align)) || !w.u8(kReserved8) || !w.f64(st.miter_limit) || !w.f64(st.dash_offset) ||
        !w.u32(static_cast<std::uint32_t>(st.dash.size())))
        return false;
    for (double d : st.dash)
        if (!w.f64(d)) return false;
    if (!w.u8(s.stroke.arrow_start ? 1 : 0) || !w.u8(s.stroke.arrow_end ? 1 : 0) || !w.u16(0) || !w.f64(s.stroke.arrow_length) || !w.f64(s.stroke.arrow_width)) return false;
    if (s.primitive) {
        const auto& p = *s.primitive;
        if (!w.u8(static_cast<std::uint8_t>(p.kind)) || !w.u8(0) || !w.u16(0) || !w.f64(p.x) || !w.f64(p.y) || !w.f64(p.width) || !w.f64(p.height) ||
            !w.f64(p.radii.top_left) || !w.f64(p.radii.top_right) || !w.f64(p.radii.bottom_right) || !w.f64(p.radii.bottom_left) || !w.i32(p.sides) ||
            !w.f64(p.inner_ratio) || !w.f64(p.rotation))
            return false;
    }
    if (!w.u32(static_cast<std::uint32_t>(s.subpaths.size()))) return false;
    for (const auto& subpath : s.subpaths) {
        if (!w.u32(static_cast<std::uint32_t>(subpath.anchors.size())) || !w.u8(subpath.closed ? 1 : 0) || !w.u8(0) || !w.u16(0)) return false;
        for (const auto& a : subpath.anchors) {
            const double ix = a.incoming ? a.incoming->x : 0.0, iy = a.incoming ? a.incoming->y : 0.0;
            const double ox = a.outgoing ? a.outgoing->x : 0.0, oy = a.outgoing ? a.outgoing->y : 0.0;
            if (!w.i64(a.id) || !w.f64(a.point.x) || !w.f64(a.point.y) || !w.u8(static_cast<std::uint8_t>(a.mode)) ||
                !w.u8(a.incoming ? 1 : 0) || !w.u8(a.outgoing ? 1 : 0) || !w.raw("\0\0\0\0\0", 5) || !w.f64(ix) || !w.f64(iy) || !w.f64(ox) || !w.f64(oy))
                return false;
        }
    }
    return true;
}

bool readRecordBody(Reader& r, Record& record) {
    auto& s = record.shape;
    std::uint8_t fill_rule{}, fill_enabled{}, stroke_enabled{}, has_primitive{};
    if (!r.i64(s.id) || !r.u8(fill_rule) || !r.u8(fill_enabled) || !r.u8(stroke_enabled) || !r.u8(has_primitive) || fill_rule > 1 || fill_enabled > 1 ||
        stroke_enabled > 1 || has_primitive > 1)
        return false;
    s.fill_rule = static_cast<vector::FillRule>(fill_rule);
    s.fill.enabled = fill_enabled != 0;
    s.stroke.enabled = stroke_enabled != 0;
    for (float& v : s.fill.color)
        if (!r.f32(v)) return false;
    for (float& v : s.stroke.color)
        if (!r.f32(v)) return false;
    auto& st = s.stroke.style;
    std::uint8_t join{}, cap{}, align{}, reserved{};
    if (!r.f64(st.width) || !r.u8(join) || !r.u8(cap) || !r.u8(align) || !r.u8(reserved) || reserved != 0 || join > 2 || cap > 2 || align > 2 ||
        !r.f64(st.miter_limit) || !r.f64(st.dash_offset))
        return false;
    st.join = static_cast<vector::Join>(join);
    st.cap = static_cast<vector::Cap>(cap);
    st.align = static_cast<vector::Align>(align);
    std::uint32_t dash_count{};
    if (!r.u32(dash_count) || dash_count > 32) return false;
    st.dash.resize(dash_count);
    for (double& d : st.dash)
        if (!r.f64(d)) return false;
    std::uint8_t arrow_start{}, arrow_end{};
    std::uint16_t reserved16{};
    if (!r.u8(arrow_start) || !r.u8(arrow_end) || !r.u16(reserved16) || arrow_start > 1 || arrow_end > 1 || reserved16 != 0 ||
        !r.f64(s.stroke.arrow_length) || !r.f64(s.stroke.arrow_width))
        return false;
    s.stroke.arrow_start = arrow_start != 0;
    s.stroke.arrow_end = arrow_end != 0;
    if (has_primitive != 0) {
        Primitive p;
        std::uint8_t kind{}, reserved8{};
        if (!r.u8(kind) || !r.u8(reserved8) || !r.u16(reserved16) || reserved8 != 0 || reserved16 != 0 || kind > 5 || !r.f64(p.x) || !r.f64(p.y) ||
            !r.f64(p.width) || !r.f64(p.height) || !r.f64(p.radii.top_left) || !r.f64(p.radii.top_right) || !r.f64(p.radii.bottom_right) ||
            !r.f64(p.radii.bottom_left) || !r.i32(p.sides) || !r.f64(p.inner_ratio) || !r.f64(p.rotation))
            return false;
        p.kind = static_cast<PrimitiveKind>(kind);
        s.primitive = p;
    }
    std::uint32_t subpath_count{};
    if (!r.u32(subpath_count) || subpath_count > kMaxSubpaths) return false;
    s.subpaths.resize(subpath_count);
    std::size_t total_anchors = 0;
    for (auto& subpath : s.subpaths) {
        std::uint32_t count{};
        std::uint8_t closed{}, reserved8{};
        if (!r.u32(count) || count > kMaxAnchors - total_anchors || !r.u8(closed) || !r.u8(reserved8) || !r.u16(reserved16) || closed > 1 || reserved8 != 0 || reserved16 != 0)
            return false;
        total_anchors += count;
        subpath.closed = closed != 0;
        subpath.anchors.resize(count);
        for (auto& a : subpath.anchors) {
            std::uint8_t mode{}, incoming{}, outgoing{};
            std::array<std::uint8_t, 5> reserved{};
            double ix{}, iy{}, ox{}, oy{};
            if (!r.i64(a.id) || !r.f64(a.point.x) || !r.f64(a.point.y) || !r.u8(mode) || !r.u8(incoming) || !r.u8(outgoing) ||
                !r.raw(reserved.data(), reserved.size()) || !r.f64(ix) || !r.f64(iy) || !r.f64(ox) || !r.f64(oy) || mode > 2 || incoming > 1 || outgoing > 1 ||
                std::any_of(reserved.begin(), reserved.end(), [](auto v) { return v != 0; }))
                return false;
            if ((!incoming && (ix != 0.0 || iy != 0.0)) || (!outgoing && (ox != 0.0 || oy != 0.0))) return false;
            a.mode = static_cast<AnchorMode>(mode);
            if (incoming) a.incoming = vector::Point{ix, iy};
            if (outgoing) a.outgoing = vector::Point{ox, oy};
        }
    }
    return true;
}

}  // namespace

bool encodeRecord(const Record& record, std::vector<std::uint8_t>& bytes, std::string& error) {
    error.clear();
    if (!validRecord(record, error)) return false;
    Writer w(kMaxRecordBytes);
    const auto name_size = static_cast<std::uint16_t>(record.name.size());
    if (!w.raw(kRecordMagic.data(), kRecordMagic.size()) || !w.u16(kWireVersion) || !w.u16(0) || !w.u16(name_size) ||
        !w.raw(record.name.data(), record.name.size()) || !writeRecordBody(w, record)) {
        error = "encoded shape record exceeds the 8 MiB limit";
        return false;
    }
    bytes = w.take();
    return true;
}

bool decodeRecord(std::span<const std::uint8_t> bytes, Record& record, std::string& error) {
    error.clear();
    if (bytes.size() > kMaxRecordBytes || bytes.size() < 10) {
        error = "shape record byte length is invalid";
        return false;
    }
    Reader r(bytes);
    std::uint16_t version{}, reserved{}, name_size{};
    if (!magic(r, kRecordMagic) || !r.u16(version) || !r.u16(reserved) || !r.u16(name_size) || version != kWireVersion || reserved != 0 || name_size > kMaxNameBytes ||
        name_size > bytes.size()) {
        error = "shape record header is invalid or unsupported";
        return false;
    }
    Record candidate;
    candidate.name.resize(name_size);
    if (!r.raw(candidate.name.data(), name_size) || !utf8Name(candidate.name) || !readRecordBody(r, candidate) || !r.done()) {
        error = "shape record is truncated, malformed, or contains trailing data";
        return false;
    }
    if (!validRecord(candidate, error)) return false;
    record = std::move(candidate);
    return true;
}

bool encodeBatch(std::span<const Record> records, std::vector<std::uint8_t>& bytes, std::string& error) {
    error.clear();
    if (records.size() > kMaxRecordsPerBatch) {
        error = "shape batch has too many records";
        return false;
    }
    std::unordered_set<std::int64_t> record_ids;
    record_ids.reserve(records.size());
    for (const auto& record : records)
        if (!record_ids.insert(record.shape.id).second) {
            error = "shape record IDs must be unique within a batch";
            return false;
        }
    Writer w(kMaxBatchBytes);
    if (!w.raw(kBatchMagic.data(), kBatchMagic.size()) || !w.u16(kWireVersion) || !w.u16(0) || !w.u32(static_cast<std::uint32_t>(records.size()))) {
        error = "could not encode shape batch header";
        return false;
    }
    for (const auto& record : records) {
        std::vector<std::uint8_t> item;
        if (!encodeRecord(record, item, error)) return false;
        if (!w.u32(static_cast<std::uint32_t>(item.size())) || !w.raw(item.data(), item.size())) {
            error = "encoded shape batch exceeds the 32 MiB limit";
            return false;
        }
    }
    bytes = w.take();
    return true;
}

bool decodeBatch(std::span<const std::uint8_t> bytes, std::vector<Record>& records, std::string& error) {
    error.clear();
    if (bytes.size() > kMaxBatchBytes || bytes.size() < 12) {
        error = "shape batch byte length is invalid";
        return false;
    }
    Reader r(bytes);
    std::uint16_t version{}, reserved{};
    std::uint32_t count{};
    if (!magic(r, kBatchMagic) || !r.u16(version) || !r.u16(reserved) || !r.u32(count) || version != kWireVersion || reserved != 0 || count > kMaxRecordsPerBatch) {
        error = "shape batch header is invalid or unsupported";
        return false;
    }
    std::vector<Record> candidate;
    candidate.reserve(count);
    std::unordered_set<std::int64_t> record_ids;
    record_ids.reserve(count);
    for (std::uint32_t i = 0; i < count; ++i) {
        std::uint32_t size{};
        if (!r.u32(size) || size > kMaxRecordBytes) {
            error = "shape batch record length is invalid";
            return false;
        }
        // Reader deliberately exposes no subspan; retain a bounded copy and advance through raw.
        std::vector<std::uint8_t> item(size);
        if (!r.raw(item.data(), item.size())) {
            error = "shape batch record is truncated";
            return false;
        }
        Record record;
        if (!decodeRecord(item, record, error)) return false;
        if (!record_ids.insert(record.shape.id).second) {
            error = "shape batch contains duplicate record IDs";
            return false;
        }
        candidate.push_back(std::move(record));
    }
    if (!r.done()) {
        error = "shape batch has trailing data";
        return false;
    }
    records = std::move(candidate);
    return true;
}

bool hitTestBatch(std::span<const std::uint8_t> bytes, vector::Point point, double tolerance, Hit& hit, std::string& error) {
    error.clear();
    if (!std::isfinite(point.x) || !std::isfinite(point.y) || !std::isfinite(tolerance) || tolerance < 0 || tolerance > 1e6) {
        error = "direct-select coordinates or tolerance are invalid";
        return false;
    }
    std::vector<Record> records;
    if (!decodeBatch(bytes, records, error)) return false;
    std::vector<ShapeRecord> shapes;
    shapes.reserve(records.size());
    for (const auto& record : records) shapes.push_back(record.shape);
    hit = shape::hitTest(shapes, point, tolerance);
    return true;
}

}  // namespace croptop::shape::bridge

#if defined(__ANDROID__)
namespace {
using croptop::shape::Hit;
using croptop::shape::bridge::Record;
thread_local std::string g_shape_bridge_error;

template <typename Result, typename Fn>
Result guarded(Result failure, Fn&& fn) {
    g_shape_bridge_error.clear();
    try {
        return fn();
    } catch (const std::exception& e) {
        g_shape_bridge_error = e.what();
    } catch (...) {
        g_shape_bridge_error = "unknown native shape-layer error";
    }
    return failure;
}

std::vector<std::uint8_t> readBytes(JNIEnv* env, jbyteArray array) {
    if (array == nullptr) throw std::invalid_argument("shape record byte array is null");
    const jsize size = env->GetArrayLength(array);
    if (size < 0 || static_cast<std::size_t>(size) > croptop::shape::bridge::kMaxBatchBytes) throw std::invalid_argument("shape record byte array exceeds limit");
    std::vector<std::uint8_t> bytes(static_cast<std::size_t>(size));
    if (size > 0) env->GetByteArrayRegion(array, 0, size, reinterpret_cast<jbyte*>(bytes.data()));
    return bytes;
}

jbyteArray makeBytes(JNIEnv* env, const std::vector<std::uint8_t>& bytes) {
    if (bytes.size() > static_cast<std::size_t>(std::numeric_limits<jsize>::max())) throw std::length_error("shape record is too large for JNI");
    auto result = env->NewByteArray(static_cast<jsize>(bytes.size()));
    if (result != nullptr && !bytes.empty()) env->SetByteArrayRegion(result, 0, static_cast<jsize>(bytes.size()), reinterpret_cast<const jbyte*>(bytes.data()));
    return result;
}

}  // namespace

extern "C" JNIEXPORT jstring JNICALL Java_app_croptop_core_engine_NativeShapeLayerBridge_nativeLastError(JNIEnv* env, jobject) {
    return env->NewStringUTF(g_shape_bridge_error.c_str());
}

extern "C" JNIEXPORT jlong JNICALL Java_app_croptop_core_engine_NativeShapeLayerBridge_nativeCreate(JNIEnv* env, jobject, jbyteArray encoded) {
    return guarded<jlong>(0, [&] {
        auto bytes = readBytes(env, encoded);
        Record record;
        std::string error;
        if (!croptop::shape::bridge::decodeRecord(bytes, record, error)) throw std::invalid_argument(error);
        return reinterpret_cast<jlong>(new Record(std::move(record)));
    });
}

extern "C" JNIEXPORT jboolean JNICALL Java_app_croptop_core_engine_NativeShapeLayerBridge_nativeUpdate(JNIEnv* env, jobject, jlong handle, jbyteArray encoded) {
    return guarded<jboolean>(JNI_FALSE, [&] {
        auto* current = reinterpret_cast<Record*>(handle);
        if (current == nullptr) throw std::invalid_argument("shape record handle is null");
        auto bytes = readBytes(env, encoded);
        Record replacement;
        std::string error;
        if (!croptop::shape::bridge::decodeRecord(bytes, replacement, error)) throw std::invalid_argument(error);
        *current = std::move(replacement);
        return JNI_TRUE;
    });
}

extern "C" JNIEXPORT jbyteArray JNICALL Java_app_croptop_core_engine_NativeShapeLayerBridge_nativeEncode(JNIEnv* env, jobject, jlong handle) {
    return guarded<jbyteArray>(nullptr, [&] {
        const auto* record = reinterpret_cast<const Record*>(handle);
        if (record == nullptr) throw std::invalid_argument("shape record handle is null");
        std::vector<std::uint8_t> bytes;
        std::string error;
        if (!croptop::shape::bridge::encodeRecord(*record, bytes, error)) throw std::invalid_argument(error);
        return makeBytes(env, bytes);
    });
}

extern "C" JNIEXPORT void JNICALL Java_app_croptop_core_engine_NativeShapeLayerBridge_nativeDestroy(JNIEnv*, jobject, jlong handle) {
    g_shape_bridge_error.clear();
    delete reinterpret_cast<Record*>(handle);
}

extern "C" JNIEXPORT jdoubleArray JNICALL Java_app_croptop_core_engine_NativeShapeLayerBridge_nativeHitTest(JNIEnv* env, jobject, jbyteArray encoded,
                                                                                                               jdouble x, jdouble y, jdouble tolerance) {
    return guarded<jdoubleArray>(nullptr, [&] {
        auto bytes = readBytes(env, encoded);
        Hit hit;
        std::string error;
        if (!croptop::shape::bridge::hitTestBatch(bytes, {x, y}, tolerance, hit, error)) throw std::invalid_argument(error);
        const std::array<jdouble, 5> values{static_cast<jdouble>(hit.part), static_cast<jdouble>(hit.record), static_cast<jdouble>(hit.subpath),
                                            static_cast<jdouble>(hit.anchor), hit.distance};
        auto result = env->NewDoubleArray(static_cast<jsize>(values.size()));
        if (result != nullptr) env->SetDoubleArrayRegion(result, 0, static_cast<jsize>(values.size()), values.data());
        return result;
    });
}
#endif
