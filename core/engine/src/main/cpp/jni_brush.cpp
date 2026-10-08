// Native incremental brush sessions and COW TileStore stamping.
#include <jni.h>
#include <android/log.h>

#include <algorithm>
#include <array>
#include <cmath>
#include <condition_variable>
#include <cstdint>
#include <deque>
#include <exception>
#include <memory>
#include <limits>
#include <mutex>
#include <numbers>
#include <optional>
#include <set>
#include <stdexcept>
#include <string>
#include <thread>
#include <utility>
#include <vector>

#include "brush_engine.h"
#include "brush_queue_budget.h"
#include "layer_mask_ops.h"
#include "heal.h"
#include "native_store_handle.h"
#include "pixel_plane.h"
#include "retouch.h"
#include "stroke_compositor.h"
#include "stroke_stabilizer.h"
#include "selection_mask.h"

namespace {

using croptop::brush::Dab;
using croptop::brush::DabGenerator;
using croptop::brush::Dynamics;
using croptop::brush::DynamicsInput;
using croptop::brush::Sample;
using croptop::brush::Settings;
using croptop::stroke::Stabilizer;
using croptop::stroke::StabilizerSpec;
using croptop::jni::NativeTileStore;

constexpr std::size_t kSampleStride = 7;
constexpr std::size_t kMaxCurvePoints = 128;
constexpr char kLogTag[] = "CropTopBrush";

struct NativeBrushSession final {
    NativeBrushSession(Settings settings, StabilizerSpec stabilizer_spec, bool wet_edges, std::uint32_t width, std::uint32_t height)
        : build_up(settings.build_up), wet_edges(wet_edges), width(width), height(height), generator(std::move(settings)), stabilizer(stabilizer_spec) {
        if (wet_edges) compositor = std::make_unique<croptop::brush::StrokeMaskCompositor>(width, height);
        if (!build_up) {
            if (!compositor) compositor = std::make_unique<croptop::brush::StrokeMaskCompositor>(width, height);
            rasterizer = std::thread([this] { rasterize(); });
        }
    }

    ~NativeBrushSession() {
        {
            std::lock_guard guard(mutex);
            stopping = true;
            pending.clear();
            budget.discardQueued();
        }
        ready.notify_all();
        if (rasterizer.joinable()) rasterizer.join();
    }

    void rasterize() {
        std::unique_lock guard(mutex);
        while (true) {
            ready.wait(guard, [this] { return stopping || !pending.empty(); });
            if (stopping) return;
            auto batch = std::move(pending.front());
            pending.pop_front();
            if (!budget.begin(batch.size())) {
                raster_error = "Brush raster queue accounting failed; stroke cancelled";
                pending.clear();
                budget.discardQueued();
                drained.notify_all();
                continue;
            }
            processing = true;
            guard.unlock();
            std::string error;
            for (const auto& dab : batch) {
                if (!compositor->accumulateDab(dab, error)) break;
            }
            guard.lock();
            if (!error.empty()) {
                raster_error = std::move(error);
                pending.clear();
                budget.discardQueued();
            }
            if (!budget.complete(batch.size())) {
                raster_error = "Brush raster queue accounting failed; stroke cancelled";
            }
            processing = false;
            drained.notify_all();
        }
    }

    bool build_up;
    bool wet_edges;
    std::uint32_t width;
    std::uint32_t height;
    std::mutex mutex;
    std::condition_variable ready;
    std::condition_variable drained;
    DabGenerator generator;
    Stabilizer stabilizer;
    std::vector<Dab> dabs;
    std::set<std::pair<std::int32_t, std::int32_t>> affected_tiles;
    std::unique_ptr<croptop::brush::StrokeMaskCompositor> compositor;
    std::deque<std::vector<Dab>> pending;
    std::thread rasterizer;
    croptop::brush::DabQueueBudget budget{4096};
    float stroke_opacity = 1.0F;
    bool has_dabs = false;
    bool has_samples = false;
    float start_x = 0.0F;
    float start_y = 0.0F;
    bool record_retouch_dabs = false;
    std::vector<Dab> retouch_dabs;
    bool processing = false;
    bool stopping = false;
    bool finished = false;
    std::string raster_error;
};

NativeBrushSession& requireSession(jlong handle) {
    auto* session = reinterpret_cast<NativeBrushSession*>(handle);
    if (session == nullptr) throw std::invalid_argument("brush session handle is null");
    return *session;
}

std::vector<croptop::TileCoordinate> affectedTiles(const NativeBrushSession& session, std::uint32_t width, std::uint32_t height) {
    std::set<std::pair<std::int32_t, std::int32_t>> coordinates;
    const auto edge = static_cast<std::int64_t>(croptop::kTileEdge);
    for (const auto& dab : session.dabs) {
        const double radius = dab.diameter * 0.5;
        const double radians = dab.angle * std::numbers::pi_v<double> / 180.0;
        const double cosine = std::cos(radians);
        const double sine = std::sin(radians);
        const double edge_pad = dab.hardness >= 1.0F ? 0.5 : 0.0;
        const double major = radius + edge_pad;
        const double minor = radius * dab.roundness + edge_pad;
        const double extent_x = std::hypot(major * cosine, minor * sine);
        const double extent_y = std::hypot(major * sine, minor * cosine);
        const auto raw_x0 = static_cast<std::int64_t>(std::floor(dab.x - extent_x));
        const auto raw_y0 = static_cast<std::int64_t>(std::floor(dab.y - extent_y));
        const auto raw_x1 = static_cast<std::int64_t>(std::ceil(dab.x + extent_x)) + 1;
        const auto raw_y1 = static_cast<std::int64_t>(std::ceil(dab.y + extent_y)) + 1;
        const auto x0 = std::clamp<std::int64_t>(raw_x0, 0, width);
        const auto y0 = std::clamp<std::int64_t>(raw_y0, 0, height);
        const auto x1 = std::clamp<std::int64_t>(raw_x1, 0, width);
        const auto y1 = std::clamp<std::int64_t>(raw_y1, 0, height);
        if (x0 >= x1 || y0 >= y1) continue;
        const auto tile_x0 = x0 / edge;
        const auto tile_y0 = y0 / edge;
        const auto tile_x1 = (x1 - 1) / edge;
        const auto tile_y1 = (y1 - 1) / edge;
        for (auto tile_y = tile_y0; tile_y <= tile_y1; ++tile_y) {
            for (auto tile_x = tile_x0; tile_x <= tile_x1; ++tile_x) {
                coordinates.emplace(static_cast<std::int32_t>(tile_x), static_cast<std::int32_t>(tile_y));
            }
        }
    }
    std::vector<croptop::TileCoordinate> result;
    result.reserve(coordinates.size());
    for (const auto& [x, y] : coordinates) result.push_back({x, y});
    return result;
}

void recordAffectedTiles(NativeBrushSession& session, const Dab& dab) {
    const double radius = dab.diameter * 0.5;
    const double radians = dab.angle * std::numbers::pi_v<double> / 180.0;
    const double cosine = std::cos(radians);
    const double sine = std::sin(radians);
    const double edge_pad = dab.hardness >= 1.0F ? 0.5 : 0.0;
    const double major = radius + edge_pad;
    const double minor = radius * dab.roundness + edge_pad;
    const double extent_x = std::hypot(major * cosine, minor * sine);
    const double extent_y = std::hypot(major * sine, minor * cosine);
    const auto clamp = [](double value, std::uint32_t dimension) {
        return std::clamp(value, 0.0, static_cast<double>(dimension));
    };
    const double x0 = clamp(std::floor(static_cast<double>(dab.x) - extent_x), session.width);
    const double y0 = clamp(std::floor(static_cast<double>(dab.y) - extent_y), session.height);
    const double x1 = clamp(std::ceil(static_cast<double>(dab.x) + extent_x) + 1.0, session.width);
    const double y1 = clamp(std::ceil(static_cast<double>(dab.y) + extent_y) + 1.0, session.height);
    if (x0 >= x1 || y0 >= y1) return;
    const auto edge = static_cast<double>(croptop::kTileEdge);
    const auto tile_x0 = static_cast<std::int32_t>(std::floor(x0 / edge));
    const auto tile_y0 = static_cast<std::int32_t>(std::floor(y0 / edge));
    const auto tile_x1 = static_cast<std::int32_t>(std::floor((x1 - 1.0) / edge));
    const auto tile_y1 = static_cast<std::int32_t>(std::floor((y1 - 1.0) / edge));
    for (auto tile_y = tile_y0; tile_y <= tile_y1; ++tile_y) {
        for (auto tile_x = tile_x0; tile_x <= tile_x1; ++tile_x) {
            session.affected_tiles.emplace(tile_x, tile_y);
        }
    }
}

std::vector<croptop::TileCoordinate> maskAffectedTiles(const NativeBrushSession& session) {
    std::vector<croptop::TileCoordinate> result;
    result.reserve(session.affected_tiles.size());
    for (const auto& [x, y] : session.affected_tiles) result.push_back({x, y});
    return result;
}

std::vector<float> readFloats(JNIEnv* env, jfloatArray array) {
    if (array == nullptr) throw std::invalid_argument("brush settings array is null");
    const auto length = env->GetArrayLength(array);
    std::vector<float> values(static_cast<std::size_t>(length));
    if (length > 0) env->GetFloatArrayRegion(array, 0, length, values.data());
    if (env->ExceptionCheck()) throw std::runtime_error("could not read brush settings");
    return values;
}

int integer(float value, int minimum, int maximum, const char* what) {
    if (!std::isfinite(value) || std::floor(value) != value || value < minimum || value > maximum) {
        throw std::invalid_argument(std::string("invalid brush ") + what);
    }
    return static_cast<int>(value);
}

struct DecodedSettings final {
    Settings brush;
    StabilizerSpec stabilizer;
    bool wet_edges = false;
};

DecodedSettings decodeSettings(const std::vector<float>& values) {
    if (values.size() < 17 || integer(values[0], 4, 4, "settings version") != 4) {
        throw std::invalid_argument("unsupported brush settings encoding");
    }
    DecodedSettings decoded;
    auto& settings = decoded.brush;
    settings.size = values[1];
    settings.hardness = values[2];
    settings.opacity = values[3];
    settings.flow = values[4];
    settings.spacing = values[5];
    settings.angle = values[6];
    settings.roundness = values[7];
    settings.size_jitter = values[8];
    settings.angle_jitter = values[9];
    settings.scatter = values[10];
    settings.scatter_both_axes = integer(values[11], 0, 1, "scatter mode") != 0;
    settings.count = integer(values[12], 1, 16, "dab count");
    settings.build_up = integer(values[13], 0, 1, "build-up mode") != 0;
    const float smoothing = values[14];
    const int smoothing_mode = integer(values[15], 0, 1, "smoothing mode");
    if (!std::isfinite(smoothing) || smoothing < 0.0F || smoothing > 1.0F) throw std::invalid_argument("smoothing must be between zero and one");
    if (smoothing_mode == 0) decoded.stabilizer.rope_length = std::min(1000.0F, smoothing * settings.size);
    else decoded.stabilizer.window = static_cast<int>(std::lround(smoothing * 16.0F));
    decoded.wet_edges = integer(values[16], 0, 1, "wet edges") != 0;

    std::size_t offset = 17;
    const auto readDynamics = [&values, &offset]() {
        if (offset + 3 > values.size()) throw std::invalid_argument("truncated brush dynamics");
        Dynamics dynamics;
        const int input = integer(values[offset++], 0, 3, "dynamics input");
        dynamics.input = static_cast<DynamicsInput>(input);
        dynamics.minimum = values[offset++];
        const int count = integer(values[offset++], 2, static_cast<int>(kMaxCurvePoints), "dynamics curve length");
        if (static_cast<std::size_t>(count) > (values.size() - offset) / 2) throw std::invalid_argument("truncated brush dynamics curve");
        dynamics.curve.clear();
        dynamics.curve.reserve(static_cast<std::size_t>(count));
        for (int index = 0; index < count; ++index) {
            dynamics.curve.push_back({values[offset], values[offset + 1]});
            offset += 2;
        }
        return dynamics;
    };
    settings.size_dynamics = readDynamics();
    settings.opacity_dynamics = readDynamics();
    settings.flow_dynamics = readDynamics();
    if (offset != values.size()) throw std::invalid_argument("unexpected trailing brush settings values");
    return decoded;
}

jstring errorString(JNIEnv* env, const std::exception& exception) {
    if (env->ExceptionCheck()) return nullptr;
    return env->NewStringUTF(exception.what());
}

croptop::TileStore composeRetouchMask(NativeBrushSession& session, const croptop::TileStore& geometry,
                                      const NativeTileStore* selection) {
    auto mask = geometry.emptyLike(croptop::PixelFormat::A16);
    std::string error;
    if (!session.compositor->composeMask(mask, session.stroke_opacity, error)) throw std::runtime_error(error);
    if (selection != nullptr) {
        for (const auto coordinate : mask.tileCoordinates()) {
            auto mask_tile = mask.readTile(coordinate);
            auto selection_tile = selection->pixels.readTile(coordinate);
            if (mask_tile == nullptr || selection_tile == nullptr) {
                if (!mask.eraseTile(coordinate)) throw std::runtime_error("could not clip retouch mask");
                continue;
            }
            auto clipped = *mask_tile;
            for (std::size_t i = 0; i < clipped.size(); i += sizeof(std::uint16_t)) {
                std::uint16_t coverage = 0, selected = 0;
                std::memcpy(&coverage, clipped.data() + i, sizeof(coverage));
                std::memcpy(&selected, selection_tile->data() + i, sizeof(selected));
                coverage = static_cast<std::uint16_t>((static_cast<std::uint32_t>(coverage) * selected + 32767U) / 65535U);
                std::memcpy(clipped.data() + i, &coverage, sizeof(coverage));
            }
            if (!mask.writeTile(coordinate, clipped)) throw std::runtime_error("could not store clipped retouch mask");
        }
    }
    return mask;
}

croptop::TileStore opaqueRetouchFootprint(const croptop::TileStore& mask) {
    auto footprint = mask;
    for (const auto coordinate : footprint.tileCoordinates()) {
        const auto tile = footprint.readTile(coordinate);
        if (tile == nullptr) continue;
        auto binary = *tile;
        for (std::size_t i = 0; i < binary.size(); i += sizeof(std::uint16_t)) {
            std::uint16_t value = 0;
            std::memcpy(&value, binary.data() + i, sizeof(value));
            value = value == 0 ? 0 : 65535;
            std::memcpy(binary.data() + i, &value, sizeof(value));
        }
        if (!footprint.writeTile(coordinate, binary)) throw std::runtime_error("could not prepare healing footprint");
    }
    return footprint;
}

void consumeBrushSamples(NativeBrushSession& session, std::span<const Sample> samples) {
    if (samples.empty()) return;
    std::string error;
    const std::size_t reserved = session.budget.reserved();
    const std::size_t max_generated = session.build_up ? std::numeric_limits<std::size_t>::max()
                                                       : (reserved >= 4096 ? 0 : 4096 - reserved);
    std::vector<Dab> generated;
    if (!session.build_up) generated.reserve(max_generated);
    if (!session.generator.add(samples, generated, error, max_generated)) {
        if (error == "Brush segment exceeded its available raster queue capacity") {
            error = "Brush raster queue exceeded its bounded capacity; stroke cancelled";
        }
        session.raster_error = error;
        session.pending.clear();
        session.budget.discardQueued();
        session.drained.notify_all();
        throw std::invalid_argument(error);
    }
    if (!session.build_up) {
        try {
            for (const auto& dab : generated) recordAffectedTiles(session, dab);
        } catch (...) {
            session.raster_error = "Could not track brush coverage tiles; stroke cancelled";
            session.pending.clear();
            session.budget.discardQueued();
            session.drained.notify_all();
            throw;
        }
    }
    if (generated.empty()) return;
    if (!session.has_dabs) session.stroke_opacity = generated.front().stroke_opacity;
    if (session.record_retouch_dabs) {
        if (session.retouch_dabs.size() + generated.size() > 65536U) {
            session.raster_error = "Retouch stroke exceeded its bounded dab capacity";
            throw std::invalid_argument(session.raster_error);
        }
        session.retouch_dabs.insert(session.retouch_dabs.end(), generated.begin(), generated.end());
    }
    session.has_dabs = true;
    if (session.build_up) {
        session.dabs.insert(session.dabs.end(), generated.begin(), generated.end());
        if (session.wet_edges) for (const auto& dab : generated) {
            if (!session.compositor->accumulateDab(dab, error)) throw std::runtime_error(error);
        }
        return;
    }
    // Push first: deque allocation can throw, and must not leave a phantom budget reservation.
    session.pending.push_back(std::move(generated));
    if (!session.budget.enqueue(session.pending.back().size())) {
        session.pending.pop_back();
        session.raster_error = "Brush raster queue exceeded its bounded capacity; stroke cancelled";
        session.pending.clear();
        session.budget.discardQueued();
        session.drained.notify_all();
        throw std::runtime_error(session.raster_error);
    }
    session.ready.notify_one();
}

void flushStabilizer(NativeBrushSession& session) {
    std::vector<float> packed;
    session.stabilizer.finish(packed);
    std::vector<Sample> samples;
    samples.reserve(packed.size() / kSampleStride);
    for (std::size_t i = 0; i < packed.size(); i += kSampleStride) {
        samples.push_back({packed[i], packed[i + 1], packed[i + 2], packed[i + 3], packed[i + 4], packed[i + 5], packed[i + 6]});
    }
    consumeBrushSamples(session, samples);
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_app_croptop_core_engine_NativeEngine_nativeCreateBrushSession(JNIEnv* env, jobject, jfloatArray settings_array, jint width, jint height) {
    try {
        if (width <= 0 || height <= 0) throw std::invalid_argument("brush canvas dimensions must be positive");
        auto decoded = decodeSettings(readFloats(env, settings_array));
        return reinterpret_cast<jlong>(new NativeBrushSession(std::move(decoded.brush), decoded.stabilizer, decoded.wet_edges, static_cast<std::uint32_t>(width),
                                                              static_cast<std::uint32_t>(height)));
    } catch (const std::exception& exception) {
        __android_log_print(ANDROID_LOG_ERROR, kLogTag, "Could not initialize native brush session: %s", exception.what());
        return 0;
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_app_croptop_core_engine_NativeEngine_nativeRecordRetouchDabs(JNIEnv* env, jobject, jlong handle) {
    try {
        auto& session = requireSession(handle);
        std::lock_guard guard(session.mutex);
        session.record_retouch_dabs = true;
        return nullptr;
    } catch (const std::exception& exception) {
        return errorString(env, exception);
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_app_croptop_core_engine_NativeEngine_nativeAddBrushSamples(JNIEnv* env, jobject, jlong session_handle, jfloatArray samples_array) {
    try {
        if (samples_array == nullptr) throw std::invalid_argument("brush sample array is null");
        const jsize length = env->GetArrayLength(samples_array);
        if (length % static_cast<jsize>(kSampleStride) != 0) throw std::invalid_argument("brush samples are not packed in seven-float records");
        std::vector<float> packed(static_cast<std::size_t>(length));
        if (length > 0) env->GetFloatArrayRegion(samples_array, 0, length, packed.data());
        if (env->ExceptionCheck()) return nullptr;
        auto& session = requireSession(session_handle);
        std::lock_guard guard(session.mutex);
        if (session.finished) throw std::invalid_argument("brush session is already finished");
        std::string error;
        if (!session.raster_error.empty()) throw std::runtime_error(session.raster_error);
        std::vector<float> stabilized;
        if (!session.stabilizer.add(packed, stabilized, error)) throw std::invalid_argument(error);
        std::vector<Sample> samples;
        samples.reserve(stabilized.size() / kSampleStride);
        for (std::size_t offset = 0; offset < stabilized.size(); offset += kSampleStride) {
            samples.push_back({stabilized[offset], stabilized[offset + 1], stabilized[offset + 2], stabilized[offset + 3],
                               stabilized[offset + 4], stabilized[offset + 5], stabilized[offset + 6]});
        }
        if (!session.has_samples && packed.size() >= kSampleStride) {
            session.start_x = packed[0];
            session.start_y = packed[1];
            session.has_samples = true;
        }
        consumeBrushSamples(session, samples);
        return nullptr;
    } catch (const std::exception& exception) {
        return errorString(env, exception);
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_app_croptop_core_engine_NativeEngine_nativeApplyBrushSession(JNIEnv* env, jobject, jlong baseline_handle, jlong store_handle,
                                                                  jlong session_handle, jfloatArray color_array, jboolean erase,
                                                                  jboolean preserve_alpha, jlong selection_mask_handle) {
    try {
        auto* baseline = reinterpret_cast<NativeTileStore*>(baseline_handle);
        auto* store = reinterpret_cast<NativeTileStore*>(store_handle);
        auto* selection_mask = reinterpret_cast<NativeTileStore*>(selection_mask_handle);
        if (baseline == nullptr || store == nullptr || baseline == store) {
            throw std::invalid_argument("brush baseline and target store handles must be distinct and non-null");
        }
        if (selection_mask != nullptr &&
            (selection_mask->pixels.format() != croptop::PixelFormat::A16 ||
             selection_mask->pixels.width() != baseline->pixels.width() ||
             selection_mask->pixels.height() != baseline->pixels.height())) {
            throw std::invalid_argument("brush selection mask must be a matching A16 document mask");
        }
        const auto color_values = readFloats(env, color_array);
        if (color_values.size() != 4) throw std::invalid_argument("brush color must contain four channels");
        for (const auto value : color_values) {
            if (!std::isfinite(value) || value < 0.0F || value > 1.0F) throw std::invalid_argument("brush color must be finite and between zero and one");
        }
        auto& session = requireSession(session_handle);
        std::unique_lock guard(session.mutex);
        if (session.finished) throw std::invalid_argument("brush session is already finished");
        flushStabilizer(session);
        if (!session.has_dabs) throw std::invalid_argument("brush stroke produced no dabs");
        session.finished = true;
        if (!session.raster_error.empty()) throw std::runtime_error(session.raster_error);
        if (!session.build_up) {
            session.drained.wait(guard, [&session] { return (!session.processing && session.pending.empty()) || !session.raster_error.empty(); });
            if (!session.raster_error.empty()) throw std::runtime_error(session.raster_error);
        }
        std::string error;
        if (session.build_up) {
            for (const auto& dab : session.dabs) {
                if (!croptop::brush::stamp(store->pixels, dab, color_values[0], color_values[1], color_values[2], color_values[3],
                                           erase == JNI_TRUE, preserve_alpha == JNI_TRUE, error)) {
                    throw std::runtime_error(error);
                }
            }
            if (selection_mask != nullptr) {
                const auto affected_tiles = affectedTiles(session, baseline->pixels.width(), baseline->pixels.height());
                if (!croptop::brush::StrokeMaskCompositor::applySelectionMask(
                        baseline->pixels, store->pixels, store->pixels, selection_mask->pixels, affected_tiles, error)) {
                    throw std::runtime_error(error);
                }
            }
            if (session.wet_edges && !session.compositor->applyWetEdges(store->pixels, 1.0F,
                    selection_mask == nullptr ? nullptr : &selection_mask->pixels, error)) throw std::runtime_error(error);
        } else {
            if (!session.compositor->compose(baseline->pixels, store->pixels, color_values.data(), session.stroke_opacity,
                                    erase == JNI_TRUE, preserve_alpha == JNI_TRUE, error,
                                    selection_mask == nullptr ? nullptr : &selection_mask->pixels)) {
                throw std::runtime_error(error);
            }
            if (session.wet_edges && !session.compositor->applyWetEdges(store->pixels, 1.0F,
                    selection_mask == nullptr ? nullptr : &selection_mask->pixels, error)) throw std::runtime_error(error);
        }
        return nullptr;
    } catch (const std::exception& exception) {
        return errorString(env, exception);
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_app_croptop_core_engine_NativeEngine_nativeApplyRetouchSession(JNIEnv* env, jobject, jlong baseline_handle, jlong store_handle,
                                                                    jlong session_handle, jboolean sharpen, jfloat strength,
                                                                    jboolean protect_detail, jlong selection_mask_handle) {
    try {
        auto* baseline = reinterpret_cast<NativeTileStore*>(baseline_handle);
        auto* store = reinterpret_cast<NativeTileStore*>(store_handle);
        auto* selection = reinterpret_cast<NativeTileStore*>(selection_mask_handle);
        if (baseline == nullptr || store == nullptr || baseline == store || !croptop::paint::detail::isRgba(baseline->pixels.format()) ||
            store->pixels.format() != baseline->pixels.format() || store->pixels.width() != baseline->pixels.width() ||
            store->pixels.height() != baseline->pixels.height()) {
            throw std::invalid_argument("retouch stores must be distinct, matching RGBA stores");
        }
        if (selection != nullptr && (selection->pixels.format() != croptop::PixelFormat::A16 ||
                                     selection->pixels.width() != baseline->pixels.width() || selection->pixels.height() != baseline->pixels.height())) {
            throw std::invalid_argument("retouch selection mask must be a matching A16 document mask");
        }
        if (!std::isfinite(strength) || strength < 0.0F || strength > 1.0F) {
            throw std::invalid_argument("retouch strength must be finite and between zero and one");
        }
        auto& session = requireSession(session_handle);
        std::unique_lock guard(session.mutex);
        if (session.finished) throw std::invalid_argument("brush session is already finished");
        flushStabilizer(session);
        if (!session.has_dabs) throw std::invalid_argument("retouch stroke produced no dabs");
        session.finished = true;
        if (session.build_up || session.compositor == nullptr) throw std::invalid_argument("retouch requires a non-build-up brush");
        if (!session.raster_error.empty()) throw std::runtime_error(session.raster_error);
        session.drained.wait(guard, [&session] { return (!session.processing && session.pending.empty()) || !session.raster_error.empty(); });
        if (!session.raster_error.empty()) throw std::runtime_error(session.raster_error);

        auto mask = baseline->pixels.emptyLike(croptop::PixelFormat::A16);
        std::string error;
        if (!session.compositor->composeMask(mask, session.stroke_opacity, error)) throw std::runtime_error(error);
        if (selection != nullptr) {
            for (const auto coordinate : mask.tileCoordinates()) {
                auto mask_tile = mask.readTile(coordinate);
                auto selection_tile = selection->pixels.readTile(coordinate);
                if (mask_tile == nullptr || selection_tile == nullptr) {
                    if (!mask.eraseTile(coordinate)) throw std::runtime_error("could not clip retouch mask");
                    continue;
                }
                auto clipped = *mask_tile;
                for (std::size_t i = 0; i < clipped.size(); i += sizeof(std::uint16_t)) {
                    std::uint16_t coverage = 0, selected = 0;
                    std::memcpy(&coverage, clipped.data() + i, sizeof(coverage));
                    std::memcpy(&selected, selection_tile->data() + i, sizeof(selected));
                    coverage = static_cast<std::uint16_t>((static_cast<std::uint32_t>(coverage) * selected + 32767U) / 65535U);
                    std::memcpy(clipped.data() + i, &coverage, sizeof(coverage));
                }
                if (!mask.writeTile(coordinate, clipped)) throw std::runtime_error("could not store clipped retouch mask");
            }
        }
        croptop::paint::FocusSpec spec;
        spec.sharpen = sharpen == JNI_TRUE;
        spec.strength = strength;
        spec.protect_detail = protect_detail == JNI_TRUE;
        if (!croptop::paint::blurSharpen(store->pixels, mask, spec, error)) throw std::runtime_error(error);
        return nullptr;
    } catch (const std::exception& exception) {
        return errorString(env, exception);
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_app_croptop_core_engine_NativeEngine_nativeApplyToneRetouchSession(JNIEnv* env, jobject, jlong baseline_handle, jlong store_handle,
                                                                       jlong session_handle, jboolean burn, jint range, jfloat exposure,
                                                                       jboolean protect_tones, jlong selection_mask_handle) {
    try {
        auto* baseline = reinterpret_cast<NativeTileStore*>(baseline_handle);
        auto* store = reinterpret_cast<NativeTileStore*>(store_handle);
        auto* selection = reinterpret_cast<NativeTileStore*>(selection_mask_handle);
        if (baseline == nullptr || store == nullptr || baseline == store || !croptop::paint::detail::isRgba(baseline->pixels.format()) ||
            store->pixels.format() != baseline->pixels.format() || store->pixels.width() != baseline->pixels.width() ||
            store->pixels.height() != baseline->pixels.height()) {
            throw std::invalid_argument("retouch stores must be distinct, matching RGBA stores");
        }
        if (range < 0 || range > 2) throw std::invalid_argument("tone range is invalid");
        if (selection != nullptr && (selection->pixels.format() != croptop::PixelFormat::A16 ||
                                     selection->pixels.width() != baseline->pixels.width() || selection->pixels.height() != baseline->pixels.height())) {
            throw std::invalid_argument("retouch selection mask must be a matching A16 document mask");
        }
        auto& session = requireSession(session_handle);
        std::unique_lock guard(session.mutex);
        if (session.finished) throw std::invalid_argument("brush session is already finished");
        flushStabilizer(session);
        if (!session.has_dabs) throw std::invalid_argument("retouch stroke produced no dabs");
        session.finished = true;
        if (session.build_up || session.compositor == nullptr) throw std::invalid_argument("retouch requires a non-build-up brush");
        if (!session.raster_error.empty()) throw std::runtime_error(session.raster_error);
        session.drained.wait(guard, [&session] { return (!session.processing && session.pending.empty()) || !session.raster_error.empty(); });
        if (!session.raster_error.empty()) throw std::runtime_error(session.raster_error);
        auto mask = baseline->pixels.emptyLike(croptop::PixelFormat::A16);
        std::string error;
        if (!session.compositor->composeMask(mask, session.stroke_opacity, error)) throw std::runtime_error(error);
        if (selection != nullptr) {
            for (const auto coordinate : mask.tileCoordinates()) {
                auto mask_tile = mask.readTile(coordinate);
                auto selection_tile = selection->pixels.readTile(coordinate);
                if (mask_tile == nullptr || selection_tile == nullptr) {
                    if (!mask.eraseTile(coordinate)) throw std::runtime_error("could not clip retouch mask");
                    continue;
                }
                auto clipped = *mask_tile;
                for (std::size_t i = 0; i < clipped.size(); i += sizeof(std::uint16_t)) {
                    std::uint16_t coverage = 0, selected = 0;
                    std::memcpy(&coverage, clipped.data() + i, sizeof(coverage));
                    std::memcpy(&selected, selection_tile->data() + i, sizeof(selected));
                    coverage = static_cast<std::uint16_t>((static_cast<std::uint32_t>(coverage) * selected + 32767U) / 65535U);
                    std::memcpy(clipped.data() + i, &coverage, sizeof(coverage));
                }
                if (!mask.writeTile(coordinate, clipped)) throw std::runtime_error("could not store clipped retouch mask");
            }
        }
        croptop::paint::DodgeBurnSpec spec;
        spec.burn = burn == JNI_TRUE;
        spec.range = static_cast<croptop::paint::ToneRange>(range);
        spec.exposure = exposure;
        spec.protect_tones = protect_tones == JNI_TRUE;
        if (!croptop::paint::dodgeBurn(store->pixels, mask, spec, error)) throw std::runtime_error(error);
        return nullptr;
    } catch (const std::exception& exception) {
        return errorString(env, exception);
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_app_croptop_core_engine_NativeEngine_nativeApplySpongeRetouchSession(JNIEnv* env, jobject, jlong baseline_handle, jlong store_handle,
                                                                          jlong session_handle, jboolean saturate, jfloat flow,
                                                                          jboolean vibrance, jlong selection_mask_handle) {
    try {
        auto* baseline = reinterpret_cast<NativeTileStore*>(baseline_handle);
        auto* store = reinterpret_cast<NativeTileStore*>(store_handle);
        auto* selection = reinterpret_cast<NativeTileStore*>(selection_mask_handle);
        if (baseline == nullptr || store == nullptr || baseline == store || !croptop::paint::detail::isRgba(baseline->pixels.format()) ||
            store->pixels.format() != baseline->pixels.format() || store->pixels.width() != baseline->pixels.width() ||
            store->pixels.height() != baseline->pixels.height()) {
            throw std::invalid_argument("retouch stores must be distinct, matching RGBA stores");
        }
        if (selection != nullptr && (selection->pixels.format() != croptop::PixelFormat::A16 ||
                                     selection->pixels.width() != baseline->pixels.width() || selection->pixels.height() != baseline->pixels.height())) {
            throw std::invalid_argument("retouch selection mask must be a matching A16 document mask");
        }
        auto& session = requireSession(session_handle);
        std::unique_lock guard(session.mutex);
        if (session.finished) throw std::invalid_argument("brush session is already finished");
        flushStabilizer(session);
        if (!session.has_dabs) throw std::invalid_argument("retouch stroke produced no dabs");
        session.finished = true;
        if (session.build_up || session.compositor == nullptr) throw std::invalid_argument("retouch requires a non-build-up brush");
        if (!session.raster_error.empty()) throw std::runtime_error(session.raster_error);
        session.drained.wait(guard, [&session] { return (!session.processing && session.pending.empty()) || !session.raster_error.empty(); });
        if (!session.raster_error.empty()) throw std::runtime_error(session.raster_error);
        auto mask = baseline->pixels.emptyLike(croptop::PixelFormat::A16);
        std::string error;
        if (!session.compositor->composeMask(mask, session.stroke_opacity, error)) throw std::runtime_error(error);
        if (selection != nullptr) {
            for (const auto coordinate : mask.tileCoordinates()) {
                auto mask_tile = mask.readTile(coordinate);
                auto selection_tile = selection->pixels.readTile(coordinate);
                if (mask_tile == nullptr || selection_tile == nullptr) {
                    if (!mask.eraseTile(coordinate)) throw std::runtime_error("could not clip retouch mask");
                    continue;
                }
                auto clipped = *mask_tile;
                for (std::size_t i = 0; i < clipped.size(); i += sizeof(std::uint16_t)) {
                    std::uint16_t coverage = 0, selected = 0;
                    std::memcpy(&coverage, clipped.data() + i, sizeof(coverage));
                    std::memcpy(&selected, selection_tile->data() + i, sizeof(selected));
                    coverage = static_cast<std::uint16_t>((static_cast<std::uint32_t>(coverage) * selected + 32767U) / 65535U);
                    std::memcpy(clipped.data() + i, &coverage, sizeof(coverage));
                }
                if (!mask.writeTile(coordinate, clipped)) throw std::runtime_error("could not store clipped retouch mask");
            }
        }
        croptop::paint::SpongeSpec spec;
        spec.saturate = saturate == JNI_TRUE;
        spec.flow = flow;
        spec.vibrance = vibrance == JNI_TRUE;
        if (!croptop::paint::sponge(store->pixels, mask, spec, error)) throw std::runtime_error(error);
        return nullptr;
    } catch (const std::exception& exception) {
        return errorString(env, exception);
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_app_croptop_core_engine_NativeEngine_nativeApplySpotRetouchSession(
    JNIEnv* env, jobject, jlong source_handle, jlong store_handle, jlong session_handle, jint kind,
    jlong selection_mask_handle) {
    try {
        auto* source = reinterpret_cast<NativeTileStore*>(source_handle);
        auto* store = reinterpret_cast<NativeTileStore*>(store_handle);
        auto* selection = reinterpret_cast<NativeTileStore*>(selection_mask_handle);
        if (source == nullptr || store == nullptr || source == store || !croptop::paint::detail::isRgba(store->pixels.format()) ||
            source->pixels.format() != store->pixels.format() || source->pixels.width() != store->pixels.width() ||
            source->pixels.height() != store->pixels.height() || kind < 0 || kind > 2) {
            throw std::invalid_argument("spot retouch requires distinct matching RGBA stores and a valid mode");
        }
        if (selection != nullptr && (selection->pixels.format() != croptop::PixelFormat::A16 ||
            selection->pixels.width() != store->pixels.width() || selection->pixels.height() != store->pixels.height())) {
            throw std::invalid_argument("spot retouch selection must be a matching A16 mask");
        }
        auto& session = requireSession(session_handle);
        std::unique_lock guard(session.mutex);
        if (session.finished) throw std::invalid_argument("brush session is already finished");
        flushStabilizer(session);
        if (!session.has_dabs) throw std::invalid_argument("retouch stroke produced no dabs");
        session.finished = true;
        if (session.build_up || session.compositor == nullptr) throw std::invalid_argument("retouch requires a non-build-up brush");
        session.drained.wait(guard, [&session] { return (!session.processing && session.pending.empty()) || !session.raster_error.empty(); });
        if (!session.raster_error.empty()) throw std::runtime_error(session.raster_error);
        auto mask = composeRetouchMask(session, store->pixels, selection);
        const auto bounds = croptop::selection::bounds(mask);
        if (!bounds) return nullptr;
        // The healing kernel expands its source exclusion. Run it on a private copy, then apply
        // the original coverage once so selection edges and brush opacity are preserved.
        auto footprint = opaqueRetouchFootprint(mask);
        auto effect = source->pixels;
        std::string error;
        if (!croptop::paint::spotHeal(effect, footprint, error, static_cast<croptop::paint::SpotHealType>(kind))) {
            throw std::runtime_error(error);
        }
        const auto result = croptop::paint::detail::readPlane(effect, bounds->left, bounds->top,
            bounds->right - bounds->left, bounds->bottom - bounds->top);
        if (!croptop::paint::detail::blendInto(store->pixels, mask, result, error)) throw std::runtime_error(error);
        return nullptr;
    } catch (const std::exception& exception) {
        return errorString(env, exception);
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_app_croptop_core_engine_NativeEngine_nativeApplySourceRetouchSession(
    JNIEnv* env, jobject, jlong source_handle, jlong store_handle, jlong session_handle,
    jboolean healing, jboolean merged_source, jfloat source_x, jfloat source_y, jlong selection_mask_handle, jboolean same_position) {
    try {
        auto* source = reinterpret_cast<NativeTileStore*>(source_handle);
        auto* store = reinterpret_cast<NativeTileStore*>(store_handle);
        auto* selection = reinterpret_cast<NativeTileStore*>(selection_mask_handle);
        if (source == nullptr || store == nullptr || source == store || !croptop::paint::detail::isRgba(store->pixels.format()) ||
            source->pixels.format() != store->pixels.format() || source->pixels.width() != store->pixels.width() ||
            source->pixels.height() != store->pixels.height() || !std::isfinite(source_x) || !std::isfinite(source_y)) {
            throw std::invalid_argument("source retouch requires distinct matching RGBA stores and a finite source point");
        }
        if (selection != nullptr && (selection->pixels.format() != croptop::PixelFormat::A16 ||
            selection->pixels.width() != store->pixels.width() || selection->pixels.height() != store->pixels.height())) {
            throw std::invalid_argument("source retouch selection must be a matching A16 mask");
        }
        auto& session = requireSession(session_handle);
        std::unique_lock guard(session.mutex);
        if (session.finished) throw std::invalid_argument("brush session is already finished");
        flushStabilizer(session);
        if (!session.has_dabs || !session.has_samples) throw std::invalid_argument("retouch stroke produced no dabs");
        session.finished = true;
        if (session.build_up || session.compositor == nullptr) throw std::invalid_argument("retouch requires a non-build-up brush");
        session.drained.wait(guard, [&session] { return (!session.processing && session.pending.empty()) || !session.raster_error.empty(); });
        if (!session.raster_error.empty()) throw std::runtime_error(session.raster_error);
        auto mask = composeRetouchMask(session, store->pixels, selection);
        std::string error;
        // History Brush reads the same place in an earlier state of the layer: no offset.
        const double offset_x = same_position == JNI_TRUE ? 0.0 : static_cast<double>(source_x) - session.start_x;
        const double offset_y = same_position == JNI_TRUE ? 0.0 : static_cast<double>(source_y) - session.start_y;
        if (healing && merged_source) {
            const auto bounds = croptop::selection::bounds(mask);
            if (!bounds) return nullptr;
            // Solve against the visible scene, not the potentially empty active layer.
            auto effect = source->pixels;
            const auto footprint = opaqueRetouchFootprint(mask);
            if (!croptop::paint::healingBlend(effect, source->pixels, offset_x, offset_y, footprint, error)) {
                throw std::runtime_error(error);
            }
            const auto result = croptop::paint::detail::readPlane(effect, bounds->left, bounds->top,
                bounds->right - bounds->left, bounds->bottom - bounds->top);
            if (!croptop::paint::detail::blendInto(store->pixels, mask, result, error)) throw std::runtime_error(error);
        } else {
            const bool applied = healing
                ? croptop::paint::healingBlend(store->pixels, source->pixels, offset_x, offset_y, mask, error)
                : croptop::paint::cloneStamp(store->pixels, source->pixels, offset_x, offset_y, mask, error);
            if (!applied) throw std::runtime_error(error);
        }
        return nullptr;
    } catch (const std::exception& exception) {
        return errorString(env, exception);
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_app_croptop_core_engine_NativeEngine_nativeApplySmudgeRetouchSession(JNIEnv* env, jobject, jlong baseline_handle, jlong store_handle,
                                                                         jlong session_handle, jfloat strength, jboolean finger_painting,
                                                                         jlong selection_mask_handle) {
    try {
        auto* baseline = reinterpret_cast<NativeTileStore*>(baseline_handle);
        auto* store = reinterpret_cast<NativeTileStore*>(store_handle);
        auto* selection = reinterpret_cast<NativeTileStore*>(selection_mask_handle);
        if (baseline == nullptr || store == nullptr || baseline == store || !croptop::paint::detail::isRgba(baseline->pixels.format()) ||
            store->pixels.format() != baseline->pixels.format() || store->pixels.width() != baseline->pixels.width() ||
            store->pixels.height() != baseline->pixels.height()) {
            throw std::invalid_argument("retouch stores must be distinct, matching RGBA stores");
        }
        if (selection != nullptr && (selection->pixels.format() != croptop::PixelFormat::A16 ||
                                     selection->pixels.width() != baseline->pixels.width() || selection->pixels.height() != baseline->pixels.height())) {
            throw std::invalid_argument("retouch selection mask must be a matching A16 document mask");
        }
        auto& session = requireSession(session_handle);
        std::unique_lock guard(session.mutex);
        if (session.finished) throw std::invalid_argument("brush session is already finished");
        flushStabilizer(session);
        if (!session.has_dabs || session.retouch_dabs.empty()) throw std::invalid_argument("retouch stroke produced no dabs");
        session.finished = true;
        if (session.build_up || session.compositor == nullptr) throw std::invalid_argument("retouch requires a non-build-up brush");
        if (!session.raster_error.empty()) throw std::runtime_error(session.raster_error);
        session.drained.wait(guard, [&session] { return (!session.processing && session.pending.empty()) || !session.raster_error.empty(); });
        if (!session.raster_error.empty()) throw std::runtime_error(session.raster_error);
        if (finger_painting) throw std::invalid_argument("finger painting requires a source colour contract");
        std::string error;
        Dab previous = session.retouch_dabs.front();
        for (const auto& dab : session.retouch_dabs) {
            auto mask = baseline->pixels.emptyLike(croptop::PixelFormat::A16);
            croptop::brush::StrokeMaskCompositor coverage(store->pixels.width(), store->pixels.height());
            if (!coverage.accumulateDab(dab, error) || !coverage.composeMask(mask, session.stroke_opacity, error)) throw std::runtime_error(error);
            if (selection != nullptr) {
                for (const auto coordinate : mask.tileCoordinates()) {
                    auto mask_tile = mask.readTile(coordinate);
                    auto selection_tile = selection->pixels.readTile(coordinate);
                    if (mask_tile == nullptr || selection_tile == nullptr) {
                        if (!mask.eraseTile(coordinate)) throw std::runtime_error("could not clip retouch mask");
                        continue;
                    }
                    auto clipped = *mask_tile;
                    for (std::size_t i = 0; i < clipped.size(); i += sizeof(std::uint16_t)) {
                        std::uint16_t coverage = 0, selected = 0;
                        std::memcpy(&coverage, clipped.data() + i, sizeof(coverage));
                        std::memcpy(&selected, selection_tile->data() + i, sizeof(selected));
                        coverage = static_cast<std::uint16_t>((static_cast<std::uint32_t>(coverage) * selected + 32767U) / 65535U);
                        std::memcpy(clipped.data() + i, &coverage, sizeof(coverage));
                    }
                    if (!mask.writeTile(coordinate, clipped)) throw std::runtime_error("could not store clipped retouch mask");
                }
            }
            croptop::paint::SmudgeSpec spec;
            spec.offset_x = static_cast<double>(dab.x - previous.x);
            spec.offset_y = static_cast<double>(dab.y - previous.y);
            spec.strength = strength;
            if (!croptop::paint::smudge(store->pixels, mask, spec, error)) throw std::runtime_error(error);
            previous = dab;
        }
        return nullptr;
    } catch (const std::exception& exception) {
        return errorString(env, exception);
    }
}

/**
 * Background Eraser: per recorded dab, erases the pixels near the sampled colour (see paint::backgroundErase). `sampling` is 0
 * (continuous: the colour under every dab centre), 1 (once: the first dab's) or 2 (the `swatch` colour); `protect`, when it has
 * three values, is a colour that is never erased.
 */
extern "C" JNIEXPORT jstring JNICALL
Java_app_croptop_core_engine_NativeEngine_nativeApplyBackgroundEraserSession(JNIEnv* env, jobject, jlong store_handle, jlong session_handle,
                                                                             jfloat tolerance, jboolean contiguous, jint sampling,
                                                                             jfloatArray swatch, jfloatArray protect, jlong selection_mask_handle) {
    try {
        auto* store = reinterpret_cast<NativeTileStore*>(store_handle);
        auto* selection = reinterpret_cast<NativeTileStore*>(selection_mask_handle);
        if (store == nullptr || !croptop::paint::detail::isRgba(store->pixels.format())) {
            throw std::invalid_argument("background eraser needs an RGBA layer");
        }
        if (selection != nullptr && (selection->pixels.format() != croptop::PixelFormat::A16 ||
                                     selection->pixels.width() != store->pixels.width() || selection->pixels.height() != store->pixels.height())) {
            throw std::invalid_argument("background eraser selection mask must be a matching A16 document mask");
        }
        if (sampling < 0 || sampling > 2) throw std::invalid_argument("unknown background eraser sampling mode");
        std::array<float, 3> swatch_rgb{};
        if (swatch != nullptr && env->GetArrayLength(swatch) >= 3) env->GetFloatArrayRegion(swatch, 0, 3, swatch_rgb.data());
        std::optional<std::array<float, 3>> protect_rgb;
        if (protect != nullptr && env->GetArrayLength(protect) >= 3) {
            std::array<float, 3> value{};
            env->GetFloatArrayRegion(protect, 0, 3, value.data());
            protect_rgb = value;
        }
        auto& session = requireSession(session_handle);
        std::unique_lock guard(session.mutex);
        if (session.finished) throw std::invalid_argument("brush session is already finished");
        flushStabilizer(session);
        if (!session.has_dabs || session.retouch_dabs.empty()) throw std::invalid_argument("retouch stroke produced no dabs");
        session.finished = true;
        if (session.build_up || session.compositor == nullptr) throw std::invalid_argument("retouch requires a non-build-up brush");
        session.drained.wait(guard, [&session] { return (!session.processing && session.pending.empty()) || !session.raster_error.empty(); });
        if (!session.raster_error.empty()) throw std::runtime_error(session.raster_error);
        std::string error;
        std::array<float, 3> current = swatch_rgb;
        bool have_sample = sampling == 2;
        for (const auto& dab : session.retouch_dabs) {
            if (sampling == 0 || (sampling == 1 && !have_sample)) {
                const auto probe = croptop::paint::detail::readPlane(store->pixels, static_cast<int>(std::floor(dab.x)), static_cast<int>(std::floor(dab.y)), 1, 1);
                if (probe.px[3] > 0.05F) {
                    current = {probe.px[0], probe.px[1], probe.px[2]};
                    have_sample = true;
                }
            }
            if (!have_sample) continue;  // nothing opaque under the hotspot yet
            auto mask = store->pixels.emptyLike(croptop::PixelFormat::A16);
            croptop::brush::StrokeMaskCompositor coverage(store->pixels.width(), store->pixels.height());
            if (!coverage.accumulateDab(dab, error) || !coverage.composeMask(mask, session.stroke_opacity, error)) throw std::runtime_error(error);
            if (selection != nullptr) {
                for (const auto coordinate : mask.tileCoordinates()) {
                    auto mask_tile = mask.readTile(coordinate);
                    auto selection_tile = selection->pixels.readTile(coordinate);
                    if (mask_tile == nullptr || selection_tile == nullptr) {
                        if (!mask.eraseTile(coordinate)) throw std::runtime_error("could not clip background eraser mask");
                        continue;
                    }
                    auto clipped = *mask_tile;
                    for (std::size_t i = 0; i < clipped.size(); i += sizeof(std::uint16_t)) {
                        std::uint16_t covered = 0, selected = 0;
                        std::memcpy(&covered, clipped.data() + i, sizeof(covered));
                        std::memcpy(&selected, selection_tile->data() + i, sizeof(selected));
                        covered = static_cast<std::uint16_t>((static_cast<std::uint32_t>(covered) * selected + 32767U) / 65535U);
                        std::memcpy(clipped.data() + i, &covered, sizeof(covered));
                    }
                    if (!mask.writeTile(coordinate, clipped)) throw std::runtime_error("could not store clipped background eraser mask");
                }
            }
            croptop::paint::BackgroundEraseSpec spec;
            spec.sample = current;
            spec.tolerance = tolerance;
            spec.contiguous = contiguous == JNI_TRUE;
            spec.hotspot_x = dab.x;
            spec.hotspot_y = dab.y;
            spec.protect = protect_rgb;
            if (!croptop::paint::backgroundErase(store->pixels, mask, spec, error)) throw std::runtime_error(error);
        }
        return nullptr;
    } catch (const std::exception& exception) {
        return errorString(env, exception);
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_app_croptop_core_engine_NativeEngine_nativeApplyMaskBrushSession(JNIEnv* env, jobject, jlong baseline_handle, jlong target_handle,
                                                                      jfloat empty_coverage, jlong session_handle, jfloat value,
                                                                      jfloat color_alpha, jboolean erase, jlong selection_handle) {
    try {
        auto* baseline = reinterpret_cast<NativeTileStore*>(baseline_handle);
        auto* target = reinterpret_cast<NativeTileStore*>(target_handle);
        auto* selection = reinterpret_cast<NativeTileStore*>(selection_handle);
        if (baseline == nullptr || target == nullptr || baseline == target) {
            throw std::invalid_argument("mask brush stores must be distinct and non-null");
        }
        if (baseline->pixels.format() != croptop::PixelFormat::A16 || target->pixels.format() != croptop::PixelFormat::A16 ||
            target->pixels.width() != baseline->pixels.width() || target->pixels.height() != baseline->pixels.height()) {
            throw std::invalid_argument("mask brush needs matching A16 baseline and target stores");
        }
        if (!std::isfinite(empty_coverage) || empty_coverage < 0.0F || empty_coverage > 1.0F ||
            !std::isfinite(value) || value < 0.0F || value > 1.0F ||
            !std::isfinite(color_alpha) || color_alpha < 0.0F || color_alpha > 1.0F) {
            throw std::invalid_argument("mask brush values must be finite and between zero and one");
        }
        if (selection != nullptr && (selection->pixels.format() != croptop::PixelFormat::A16 ||
                                     selection->pixels.width() != baseline->pixels.width() ||
                                     selection->pixels.height() != baseline->pixels.height())) {
            throw std::invalid_argument("mask brush selection must be a matching A16 document mask");
        }
        auto& session = requireSession(session_handle);
        std::unique_lock guard(session.mutex);
        if (session.finished) throw std::invalid_argument("brush session is already finished");
        flushStabilizer(session);
        session.finished = true;
        if (!session.has_dabs) throw std::invalid_argument("brush stroke produced no dabs");
        if (session.build_up) throw std::invalid_argument("Build-up brushes are not supported on layer masks yet");
        session.drained.wait(guard, [&session] {
            return (!session.processing && session.pending.empty()) || !session.raster_error.empty();
        });
        if (!session.raster_error.empty()) throw std::runtime_error(session.raster_error);
        std::string error;
        const croptop::layermask::MaskData original{baseline->pixels, empty_coverage};
        auto painted = croptop::layermask::paintStroke(original, *session.compositor, value,
                                                       session.stroke_opacity * (erase == JNI_TRUE ? 1.0F : color_alpha),
                                                       maskAffectedTiles(session),
                                                       selection == nullptr ? nullptr : &selection->pixels, error);
        if (!painted) throw std::runtime_error(error);
        target->pixels = std::move(painted->pixels);
        return nullptr;
    } catch (const std::exception& exception) {
        return errorString(env, exception);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_app_croptop_core_engine_NativeEngine_nativeDestroyBrushSession(JNIEnv*, jobject, jlong session_handle) {
    delete reinterpret_cast<NativeBrushSession*>(session_handle);
}
