#include "tile_store.h"
#include "scratch_store.h"

#include <algorithm>
#include <array>
#include <atomic>
#include <cmath>
#include <cstring>
#include <stdexcept>
#include <tuple>
#include <unordered_set>

namespace croptop {
namespace {

std::atomic<std::uint64_t> g_store_id{1};
std::atomic<std::uint64_t> g_content_revision{1};

std::uint64_t nextStoreId() {
    return g_store_id.fetch_add(1, std::memory_order_relaxed);
}

std::uint64_t nextContentRevision() {
    return g_content_revision.fetch_add(1, std::memory_order_relaxed);
}

// Mip reduction helpers. They reproduce the reference per-sample math bit for bit (same conversions,
// same accumulation order); only the tile lookups and sRGB transfer evaluations are hoisted.
double srgbToLinear(double value) {
    return value <= 0.04045 ? value / 12.92 : std::pow((value + 0.055) / 1.055, 2.4);
}

double linearToSrgb(double value) {
    return value <= 0.0031308 ? value * 12.92 : 1.055 * std::pow(value, 1.0 / 2.4) - 0.055;
}

std::uint8_t srgbByteReference(double linear) {
    return static_cast<std::uint8_t>(std::lround(std::clamp(linearToSrgb(linear), 0.0, 1.0) * 255.0));
}

const std::array<double, 256>& srgbDecodeTable() {
    static const auto table = [] {
        std::array<double, 256> values{};
        for (std::size_t i = 0; i < values.size(); ++i) values[i] = srgbToLinear(static_cast<double>(i) / 255.0);
        return values;
    }();
    return table;
}

/**
 * thresholds[k - 1] is the smallest non-negative double whose reference encoding is >= k. The encoded
 * byte is monotonic in the linear value, so counting thresholds <= v reproduces srgbByteReference(v)
 * exactly. Each threshold is found by bisection over the ordered bit patterns of doubles in [0, 2].
 */
const std::array<double, 255>& srgbEncodeThresholds() {
    static const auto table = [] {
        std::array<double, 255> thresholds{};
        const auto bits = [](double value) {
            std::uint64_t pattern = 0;
            std::memcpy(&pattern, &value, sizeof(pattern));
            return pattern;
        };
        const auto from_bits = [](std::uint64_t pattern) {
            double value = 0.0;
            std::memcpy(&value, &pattern, sizeof(value));
            return value;
        };
        for (std::size_t k = 1; k <= thresholds.size(); ++k) {
            auto low = bits(0.0);
            auto high = bits(2.0);
            while (low < high) {
                const auto middle = low + (high - low) / 2U;
                if (srgbByteReference(from_bits(middle)) >= k) high = middle;
                else low = middle + 1U;
            }
            thresholds[k - 1U] = from_bits(low);
        }
        return thresholds;
    }();
    return table;
}

constexpr std::size_t kSrgbEncodeBuckets = 8192;

/** For bucket b (linear values in [b / N, (b + 1) / N)), the byte of the bucket's lower bound. */
const std::array<std::uint8_t, kSrgbEncodeBuckets>& srgbEncodeBucketStarts() {
    static const auto table = [] {
        const auto& thresholds = srgbEncodeThresholds();
        std::array<std::uint8_t, kSrgbEncodeBuckets> starts{};
        for (std::size_t bucket = 0; bucket < starts.size(); ++bucket) {
            const auto lower = static_cast<double>(bucket) / static_cast<double>(kSrgbEncodeBuckets);
            starts[bucket] = static_cast<std::uint8_t>(
                std::upper_bound(thresholds.begin(), thresholds.end(), lower) - thresholds.begin());
        }
        return starts;
    }();
    return table;
}

/** Equals srgbByteReference(linear) for every double: bucket start, then step over exact thresholds. */
std::uint8_t encodeSrgbByte(double linear) {
    if (!(linear > 0.0)) return 0;
    if (linear >= 1.0) return 255;
    const auto& thresholds = srgbEncodeThresholds();
    const auto bucket = static_cast<std::size_t>(linear * static_cast<double>(kSrgbEncodeBuckets));
    std::size_t byte = srgbEncodeBucketStarts()[std::min(bucket, kSrgbEncodeBuckets - 1U)];
    // linear * N may round up into the next bucket, so correct in both directions.
    while (byte > 0U && thresholds[byte - 1U] > linear) --byte;
    while (byte < thresholds.size() && thresholds[byte] <= linear) ++byte;
    return static_cast<std::uint8_t>(byte);
}

/**
 * Builds one output tile of a 2× box reduction from the (up to) four source tiles that cover it.
 * `sources[sy][sx]` may be null (empty source tile: every channel reads as zero but still counts).
 */
template <PixelFormat Format>
void reduceMipTile(const std::array<std::array<std::shared_ptr<const TileBuffer>, 2>, 2>& sources, TileBuffer& output,
                   std::uint32_t origin_x, std::uint32_t origin_y, std::uint32_t source_width,
                   std::uint32_t source_height, std::uint32_t output_width, std::uint32_t output_height) {
    constexpr std::size_t kChannels = Format == PixelFormat::A8 || Format == PixelFormat::A16 ? 1U : 4U;
    constexpr std::size_t kComponentBytes = Format == PixelFormat::RGBA32F ? 4U
        : (Format == PixelFormat::RGBA16 || Format == PixelFormat::A16 ? 2U : 1U);
    constexpr std::size_t kPixelBytes = kChannels * kComponentBytes;
    const auto& decode = srgbDecodeTable();
    const auto load = [&](const std::uint8_t* pixel, std::size_t channel) -> double {
        const auto* component = pixel + channel * kComponentBytes;
        if constexpr (Format == PixelFormat::RGBA32F) {
            float value = 0.0F;
            std::memcpy(&value, component, sizeof(value));
            return value;
        } else if constexpr (kComponentBytes == 2U) {
            std::uint16_t value = 0;
            std::memcpy(&value, component, sizeof(value));
            return static_cast<double>(value) / 65535.0;
        } else if constexpr (Format == PixelFormat::RGBA8) {
            return channel < 3U ? decode[*component] : static_cast<double>(*component) / 255.0;
        } else {
            return static_cast<double>(*component) / 255.0;
        }
    };
    const auto store = [&](std::uint8_t* component, std::size_t channel, double value) {
        if constexpr (Format == PixelFormat::RGBA32F) {
            const auto converted = static_cast<float>(value);
            std::memcpy(component, &converted, sizeof(converted));
        } else if constexpr (kComponentBytes == 2U) {
            const auto converted = static_cast<std::uint16_t>(std::lround(std::clamp(value, 0.0, 1.0) * 65535.0));
            std::memcpy(component, &converted, sizeof(converted));
        } else if constexpr (Format == PixelFormat::RGBA8) {
            *component = channel < 3U ? encodeSrgbByte(value)
                                      : static_cast<std::uint8_t>(std::lround(std::clamp(value, 0.0, 1.0) * 255.0));
        } else {
            *component = static_cast<std::uint8_t>(std::lround(std::clamp(value, 0.0, 1.0) * 255.0));
        }
    };
    static constexpr std::array<std::uint8_t, kPixelBytes> kZeroPixel{};

    for (std::uint32_t local_y = 0; local_y < kTileEdge && origin_y + local_y < output_height; ++local_y) {
        for (std::uint32_t local_x = 0; local_x < kTileEdge && origin_x + local_x < output_width; ++local_x) {
            std::array<double, 4> sums{};
            double alpha_sum = 0.0;
            unsigned samples = 0;
            for (std::uint32_t dy = 0; dy < 2U; ++dy) {
                for (std::uint32_t dx = 0; dx < 2U; ++dx) {
                    const auto source_x = (origin_x + local_x) * 2U + dx;
                    const auto source_y = (origin_y + local_y) * 2U + dy;
                    if (source_x >= source_width || source_y >= source_height) continue;
                    const auto tile_x = local_x * 2U + dx;
                    const auto tile_y = local_y * 2U + dy;
                    const auto& source = sources[tile_y / kTileEdge][tile_x / kTileEdge];
                    const auto* pixel = source == nullptr ? kZeroPixel.data()
                        : source->data() + (static_cast<std::size_t>(tile_y % kTileEdge) * kTileEdge + tile_x % kTileEdge) *
                                               kPixelBytes;
                    const auto alpha = kChannels == 4U ? load(pixel, 3U) : 1.0;
                    for (std::size_t channel = 0; channel < kChannels; ++channel) {
                        const auto value = load(pixel, channel);
                        sums[channel] += kChannels == 4U && channel < 3U ? value * alpha : value;
                    }
                    alpha_sum += alpha;
                    ++samples;
                }
            }
            auto* output_pixel = output.data() + (static_cast<std::size_t>(local_y) * kTileEdge + local_x) * kPixelBytes;
            for (std::size_t channel = 0; channel < kChannels; ++channel) {
                double average = samples == 0U ? 0.0 : sums[channel] / samples;
                if (kChannels == 4U && channel < 3U) {
                    const auto alpha = alpha_sum / samples;
                    average = alpha > 0.0 ? average / alpha : 0.0;
                }
                store(output_pixel + channel * kComponentBytes, channel, average);
            }
        }
    }
}

}  // namespace

namespace detail {
// Test hooks for the exact sRGB mip encoder (declared only by the host tests; not public API).
std::uint8_t srgbMipEncodeForTesting(double linear) { return encodeSrgbByte(linear); }
std::uint8_t srgbMipEncodeReferenceForTesting(double linear) { return srgbByteReference(linear); }
}  // namespace detail

std::size_t TileCoordinateHash::operator()(TileCoordinate coordinate) const noexcept {
    const auto x = static_cast<std::uint32_t>(coordinate.x);
    const auto y = static_cast<std::uint32_t>(coordinate.y);
    const std::uint64_t packed = (static_cast<std::uint64_t>(x) << 32U) | y;
    return std::hash<std::uint64_t>{}(packed);
}

TilePool::TilePool(std::size_t max_cached_bytes) : max_cached_bytes_(max_cached_bytes) {}

TilePool::~TilePool() {
    for (auto* buffer : cached_) delete buffer;
}

std::shared_ptr<TileBuffer> TilePool::acquire(std::size_t bytes) {
    TileBuffer* buffer = nullptr;
    {
        const std::lock_guard lock(mutex_);
        const auto found = std::find_if(cached_.begin(), cached_.end(), [bytes](const TileBuffer* candidate) {
            return candidate->size() == bytes;
        });
        if (found != cached_.end()) {
            buffer = *found;
            cached_bytes_ -= buffer->size();
            cached_.erase(found);
        } else {
            buffer = new TileBuffer(bytes);
        }
        active_bytes_ += bytes;
    }
    std::fill(buffer->begin(), buffer->end(), 0);
    const auto owner = shared_from_this();
    return std::shared_ptr<TileBuffer>(buffer, [owner](TileBuffer* released) { owner->release(released); });
}

void TilePool::trim() {
    const std::lock_guard lock(mutex_);
    for (auto* buffer : cached_) delete buffer;
    cached_.clear();
    cached_bytes_ = 0;
}

std::size_t TilePool::active_bytes() const {
    const std::lock_guard lock(mutex_);
    return active_bytes_;
}

std::size_t TilePool::cached_bytes() const {
    const std::lock_guard lock(mutex_);
    return cached_bytes_;
}

void TilePool::release(TileBuffer* buffer) {
    const std::lock_guard lock(mutex_);
    active_bytes_ -= buffer->size();
    if (buffer->size() <= max_cached_bytes_ - std::min(cached_bytes_, max_cached_bytes_)) {
        std::fill(buffer->begin(), buffer->end(), 0);
        cached_bytes_ += buffer->size();
        cached_.push_back(buffer);
    } else {
        delete buffer;
    }
}

TileStore::TileStore(PixelFormat format, std::uint32_t width, std::uint32_t height,
                     std::shared_ptr<TilePool> pool, std::shared_ptr<MemoryBudgetManager> memory_budget)
    : format_(format), width_(width), height_(height),
      columns_(width == 0 ? 0 : 1U + (width - 1U) / kTileEdge),
      rows_(height == 0 ? 0 : 1U + (height - 1U) / kTileEdge), pool_(std::move(pool)),
      memory_budget_(std::move(memory_budget)), store_id_(nextStoreId()), content_revision_(nextContentRevision()),
      empty_content_revision_(nextContentRevision()) {
    if (width_ == 0 || height_ == 0) throw std::invalid_argument("TileStore dimensions must be positive");
    if (pool_ == nullptr) throw std::invalid_argument("TileStore requires a tile pool");
    (void)bytes_per_pixel();  // Validate the pixel format once at construction.
}

TileStore TileStore::emptyLike(PixelFormat format) const {
    return TileStore(format, width_, height_, pool_, memory_budget_);
}

TileStore TileStore::emptyLike(PixelFormat format, std::uint32_t width, std::uint32_t height) const {
    return TileStore(format, width, height, pool_, memory_budget_);
}

TileStore::TileStore(const TileStore& other)
    : format_(other.format_), width_(other.width_), height_(other.height_), columns_(other.columns_), rows_(other.rows_),
      pool_(other.pool_), memory_budget_(other.memory_budget_), store_id_(nextStoreId()) {
    const std::lock_guard lock(other.mutex_);
    content_revision_ = other.content_revision_;
    empty_content_revision_ = other.empty_content_revision_;
    tile_content_revisions_ = other.tile_content_revisions_;
    tiles_ = other.tiles_;
    managed_tiles_ = other.managed_tiles_;
    mip_tiles_ = other.mip_tiles_;
    managed_mip_tiles_ = other.managed_mip_tiles_;
}

TileStore& TileStore::operator=(const TileStore& other) {
    if (this == &other) return *this;
    if (format_ != other.format_ || width_ != other.width_ || height_ != other.height_ || pool_ != other.pool_ ||
        memory_budget_ != other.memory_budget_) {
        throw std::invalid_argument("TileStore assignment requires identical immutable configuration");
    }
    const std::scoped_lock lock(mutex_, other.mutex_);
    tiles_ = other.tiles_;
    managed_tiles_ = other.managed_tiles_;
    mip_tiles_ = other.mip_tiles_;
    managed_mip_tiles_ = other.managed_mip_tiles_;
    store_id_ = nextStoreId();
    content_revision_ = other.content_revision_;
    empty_content_revision_ = other.empty_content_revision_;
    tile_content_revisions_ = other.tile_content_revisions_;
    tile_sequence_ = 0;
    return *this;
}

TileStore::ManagedTileRef::~ManagedTileRef() {
    if (manager) (void)manager->erase(key);
}

std::size_t TileStore::bytes_per_pixel() const {
    switch (format_) {
        case PixelFormat::RGBA8:
        case PixelFormat::A8: return 1U * (format_ == PixelFormat::RGBA8 ? 4U : 1U);
        case PixelFormat::RGBA16: return 8U;
        case PixelFormat::RGBA32F: return 16U;
        case PixelFormat::A16: return 2U;
    }
    throw std::invalid_argument("Unsupported tile pixel format");
}

std::uint64_t TileStore::contentRevision() const {
    const std::lock_guard lock(mutex_);
    return content_revision_;
}

std::uint64_t TileStore::tileContentRevisionLocked(TileCoordinate coordinate) const {
    const auto found = tile_content_revisions_.find(coordinate);
    return found == tile_content_revisions_.end() ? empty_content_revision_ : found->second;
}

std::uint64_t TileStore::contentRevision(PixelRect region) const {
    const std::lock_guard lock(mutex_);
    const auto left = std::clamp<std::int64_t>(region.left, 0, width_);
    const auto top = std::clamp<std::int64_t>(region.top, 0, height_);
    const auto right = std::clamp<std::int64_t>(region.right, 0, width_);
    const auto bottom = std::clamp<std::int64_t>(region.bottom, 0, height_);
    if (right <= left || bottom <= top) return 0;
    const auto tx0 = static_cast<std::int32_t>(left / kTileEdge);
    const auto ty0 = static_cast<std::int32_t>(top / kTileEdge);
    const auto tx1 = static_cast<std::int32_t>((right - 1) / kTileEdge);
    const auto ty1 = static_cast<std::int32_t>((bottom - 1) / kTileEdge);
    std::uint64_t revision = 0x6a09e667f3bcc909ULL;
    for (auto y = ty0; y <= ty1; ++y) {
        for (auto x = tx0; x <= tx1; ++x) {
            const auto coordinate = TileCoordinate{x, y};
            revision ^= tileContentRevisionLocked(coordinate) + 0x9e3779b97f4a7c15ULL +
                        (revision << 6U) + (revision >> 2U);
        }
    }
    return revision;
}

std::size_t TileStore::tile_byte_size() const {
    return static_cast<std::size_t>(kTileEdge) * kTileEdge * bytes_per_pixel();
}

bool TileStore::contains(TileCoordinate coordinate) const {
    return coordinate.x >= 0 && coordinate.y >= 0 &&
           static_cast<std::uint32_t>(coordinate.x) < columns_ &&
           static_cast<std::uint32_t>(coordinate.y) < rows_;
}

std::uint32_t TileStore::mip_width(std::uint32_t level) const {
    auto size = width_;
    while (level-- > 0U && size > 1U) size = (size + 1U) / 2U;
    return size;
}

std::uint32_t TileStore::mip_height(std::uint32_t level) const {
    auto size = height_;
    while (level-- > 0U && size > 1U) size = (size + 1U) / 2U;
    return size;
}

bool TileStore::containsMip(std::uint32_t level, TileCoordinate coordinate) const {
    if (level == 0U) return contains(coordinate);
    const auto width = mip_width(level);
    const auto height = mip_height(level);
    const auto columns = 1U + (width - 1U) / kTileEdge;
    const auto rows = 1U + (height - 1U) / kTileEdge;
    return coordinate.x >= 0 && coordinate.y >= 0 &&
           static_cast<std::uint32_t>(coordinate.x) < columns &&
           static_cast<std::uint32_t>(coordinate.y) < rows;
}

std::shared_ptr<const TileBuffer> TileStore::readMipTile(std::uint32_t level, TileCoordinate coordinate) const {
    const std::lock_guard lock(mutex_);
    if (level == 0U) return readTile(coordinate);
    if (level > 31U || !containsMip(level, coordinate)) return nullptr;
    if (memory_budget_) {
        auto& level_tiles = managed_mip_tiles_[level];
        auto found = level_tiles.find(coordinate);
        if (found != level_tiles.end()) {
            auto restored = memory_budget_->get(found->second->key);
            if (restored && restored->size() == tile_byte_size()) return restored;
            // Mips are derived cache data. A missing or malformed scratch entry must not make an
            // otherwise readable document tile permanently disappear; drop the stale entry and
            // regenerate it from the base tiles below.
            level_tiles.erase(found);
        }
        auto tile = buildMipTile(level, coordinate);
        if (std::all_of(tile->begin(), tile->end(), [](std::uint8_t value) { return value == 0U; })) return nullptr;
        const auto key = "s" + std::to_string(store_id_) + "_m" + std::to_string(level) + "_" +
                         std::to_string(coordinate.x) + "_" + std::to_string(coordinate.y);
        if (!memory_budget_->put(key, *tile)) return nullptr;
        level_tiles.emplace(coordinate, std::make_shared<ManagedTileRef>(key, memory_budget_));
        return memory_budget_->get(key);
    }
    auto& level_tiles = mip_tiles_[level];
    const auto found = level_tiles.find(coordinate);
    if (found != level_tiles.end()) return found->second;
    auto tile = buildMipTile(level, coordinate);
    const auto is_empty = std::all_of(tile->begin(), tile->end(), [](std::uint8_t value) { return value == 0U; });
    if (is_empty) return nullptr;
    level_tiles.emplace(coordinate, tile);
    return tile;
}

std::shared_ptr<const TileBuffer> TileStore::buildMipTile(std::uint32_t level, TileCoordinate coordinate) const {
    auto output = pool_->acquire(tile_byte_size());
    // The four level-1 tiles covering this output tile are fetched once (building them first if
    // needed) instead of once per sample.
    std::array<std::array<std::shared_ptr<const TileBuffer>, 2>, 2> sources;
    for (std::int32_t sy = 0; sy < 2; ++sy) {
        for (std::int32_t sx = 0; sx < 2; ++sx) {
            const TileCoordinate source{coordinate.x * 2 + sx, coordinate.y * 2 + sy};
            sources[static_cast<std::size_t>(sy)][static_cast<std::size_t>(sx)] =
                level == 1U ? readTile(source) : readMipTile(level - 1U, source);
        }
    }
    if (!sources[0][0] && !sources[0][1] && !sources[1][0] && !sources[1][1]) return output;
    const auto origin_x = static_cast<std::uint32_t>(coordinate.x) * kTileEdge;
    const auto origin_y = static_cast<std::uint32_t>(coordinate.y) * kTileEdge;
    const auto args = std::make_tuple(std::cref(sources), std::ref(*output), origin_x, origin_y, mip_width(level - 1U),
                                      mip_height(level - 1U), mip_width(level), mip_height(level));
    switch (format_) {
        case PixelFormat::RGBA8: std::apply(reduceMipTile<PixelFormat::RGBA8>, args); break;
        case PixelFormat::RGBA16: std::apply(reduceMipTile<PixelFormat::RGBA16>, args); break;
        case PixelFormat::RGBA32F: std::apply(reduceMipTile<PixelFormat::RGBA32F>, args); break;
        case PixelFormat::A8: std::apply(reduceMipTile<PixelFormat::A8>, args); break;
        case PixelFormat::A16: std::apply(reduceMipTile<PixelFormat::A16>, args); break;
    }
    return output;
}

std::size_t TileStore::mip_resident_bytes() const {
    const std::lock_guard lock(mutex_);
    std::size_t count = 0;
    for (const auto& [level, tiles] : mip_tiles_) {
        (void)level;
        count += tiles.size();
    }
    if (memory_budget_) {
        for (const auto& [level, tiles] : managed_mip_tiles_) {
            (void)level;
            for (const auto& [coordinate, tile] : tiles) {
                (void)coordinate;
                if (memory_budget_->isResident(tile->key)) ++count;
            }
        }
    }
    return count * tile_byte_size();
}

void TileStore::trimCaches() {
    const std::lock_guard lock(mutex_);
    mip_tiles_.clear();
    managed_mip_tiles_.clear();
    pool_->trim();
}

void TileStore::invalidateMipmaps(PixelRect dirty) {
    const std::lock_guard lock(mutex_);
    const auto left = std::max(dirty.left, 0);
    const auto top = std::max(dirty.top, 0);
    const auto right = std::min<std::int64_t>(dirty.right, width_);
    const auto bottom = std::min<std::int64_t>(dirty.bottom, height_);
    if (left >= right || top >= bottom) return;
    for (auto& [level, tiles] : mip_tiles_) {
        const auto divisor = std::uint64_t{1} << level;
        const auto first_x = static_cast<std::uint64_t>(left) / divisor / kTileEdge;
        const auto last_x = (static_cast<std::uint64_t>(right - 1) / divisor) / kTileEdge;
        const auto first_y = static_cast<std::uint64_t>(top) / divisor / kTileEdge;
        const auto last_y = (static_cast<std::uint64_t>(bottom - 1) / divisor) / kTileEdge;
        for (auto it = tiles.begin(); it != tiles.end();) {
            const auto x = static_cast<std::uint64_t>(it->first.x);
            const auto y = static_cast<std::uint64_t>(it->first.y);
            if (x >= first_x && x <= last_x && y >= first_y && y <= last_y) it = tiles.erase(it);
            else ++it;
        }
    }
    for (auto& [level, tiles] : managed_mip_tiles_) {
        const auto divisor = std::uint64_t{1} << level;
        const auto first_x = static_cast<std::uint64_t>(left) / divisor / kTileEdge;
        const auto last_x = (static_cast<std::uint64_t>(right - 1) / divisor) / kTileEdge;
        const auto first_y = static_cast<std::uint64_t>(top) / divisor / kTileEdge;
        const auto last_y = (static_cast<std::uint64_t>(bottom - 1) / divisor) / kTileEdge;
        for (auto it = tiles.begin(); it != tiles.end();) {
            const auto x = static_cast<std::uint64_t>(it->first.x);
            const auto y = static_cast<std::uint64_t>(it->first.y);
            if (x >= first_x && x <= last_x && y >= first_y && y <= last_y) it = tiles.erase(it);
            else ++it;
        }
    }
}

std::shared_ptr<const TileBuffer> TileStore::readTile(TileCoordinate coordinate) const {
    const std::lock_guard lock(mutex_);
    const auto managed = managed_tiles_.find(coordinate);
    if (managed != managed_tiles_.end()) return managed->second->manager->get(managed->second->key);
    const auto found = tiles_.find(coordinate);
    return found == tiles_.end() ? nullptr : found->second;
}

std::vector<TileCoordinate> TileStore::tileCoordinates() const {
    const std::lock_guard lock(mutex_);
    std::vector<TileCoordinate> coordinates;
    coordinates.reserve(tiles_.size() + managed_tiles_.size());
    for (const auto& [coordinate, tile] : tiles_) {
        (void)tile;
        coordinates.push_back(coordinate);
    }
    for (const auto& [coordinate, tile] : managed_tiles_) {
        (void)tile;
        coordinates.push_back(coordinate);
    }
    return coordinates;
}

bool TileStore::translate(float dx, float dy) {
    const std::lock_guard lock(mutex_);
    if (!std::isfinite(dx) || !std::isfinite(dy)) return false;
    if (format_ != PixelFormat::RGBA8 && format_ != PixelFormat::RGBA16 && format_ != PixelFormat::RGBA32F) return false;
    if (dx == 0.0F && dy == 0.0F) return true;
    if (dx <= -static_cast<float>(width_) || dx >= static_cast<float>(width_) ||
        dy <= -static_cast<float>(height_) || dy >= static_cast<float>(height_)) {
        clear();
        return true;
    }

    const auto source_coordinates = tileCoordinates();
    if (source_coordinates.empty()) {
        clear();
        return true;
    }
    const std::unordered_set<TileCoordinate, TileCoordinateHash> source_coordinate_set(
        source_coordinates.begin(), source_coordinates.end());
    std::unordered_set<TileCoordinate, TileCoordinateHash> output_coordinates;
    const auto max_x = static_cast<std::int32_t>(columns_) - 1;
    const auto max_y = static_cast<std::int32_t>(rows_) - 1;
    for (const auto coordinate : source_coordinates) {
        const auto x0 = static_cast<double>(coordinate.x) * kTileEdge;
        const auto y0 = static_cast<double>(coordinate.y) * kTileEdge;
        const auto x1 = std::min<double>(x0 + kTileEdge, width_);
        const auto y1 = std::min<double>(y0 + kTileEdge, height_);
        const auto left = std::max<std::int64_t>(0, static_cast<std::int64_t>(std::floor(x0 + dx)) - 1);
        const auto top = std::max<std::int64_t>(0, static_cast<std::int64_t>(std::floor(y0 + dy)) - 1);
        const auto right = std::min<std::int64_t>(width_, static_cast<std::int64_t>(std::ceil(x1 + dx)) + 1);
        const auto bottom = std::min<std::int64_t>(height_, static_cast<std::int64_t>(std::ceil(y1 + dy)) + 1);
        if (left >= right || top >= bottom) continue;
        const auto first_x = static_cast<std::int32_t>(left / kTileEdge);
        const auto last_x = static_cast<std::int32_t>((right - 1) / kTileEdge);
        const auto first_y = static_cast<std::int32_t>(top / kTileEdge);
        const auto last_y = static_cast<std::int32_t>((bottom - 1) / kTileEdge);
        for (auto ty = first_y; ty <= last_y && ty <= max_y; ++ty) {
            for (auto tx = first_x; tx <= last_x && tx <= max_x; ++tx) output_coordinates.insert({tx, ty});
        }
    }

    TileStore shifted(format_, width_, height_, pool_, memory_budget_);
    const auto component_bytes = bytes_per_pixel() / 4U;
    auto read_component = [&](const TileBuffer& tile, std::size_t offset) -> double {
        if (format_ == PixelFormat::RGBA32F) {
            float value = 0.0F;
            std::memcpy(&value, tile.data() + offset, sizeof(value));
            return value;
        }
        if (component_bytes == 2U) {
            std::uint16_t value = 0;
            std::memcpy(&value, tile.data() + offset, sizeof(value));
            return static_cast<double>(value) / 65535.0;
        }
        return static_cast<double>(tile[offset]) / 255.0;
    };
    auto write_component = [&](TileBuffer& tile, std::size_t offset, double value) {
        if (format_ == PixelFormat::RGBA32F) {
            const auto converted = static_cast<float>(value);
            std::memcpy(tile.data() + offset, &converted, sizeof(converted));
        } else if (component_bytes == 2U) {
            const auto converted = static_cast<std::uint16_t>(std::lround(std::clamp(value, 0.0, 1.0) * 65535.0));
            std::memcpy(tile.data() + offset, &converted, sizeof(converted));
        } else {
            tile[offset] = static_cast<std::uint8_t>(std::lround(std::clamp(value, 0.0, 1.0) * 255.0));
        }
    };

    for (const auto output_coordinate : output_coordinates) {
        auto output = pool_->acquire(tile_byte_size());
        std::unordered_map<TileCoordinate, std::shared_ptr<const TileBuffer>, TileCoordinateHash> source_cache;
        bool source_read_failed = false;
        auto component_at = [&](std::int64_t x, std::int64_t y, std::size_t channel) -> double {
            if (x < 0 || y < 0 || x >= width_ || y >= height_) return 0.0;
            const TileCoordinate coordinate{static_cast<std::int32_t>(x / kTileEdge), static_cast<std::int32_t>(y / kTileEdge)};
            auto found = source_cache.find(coordinate);
            if (found == source_cache.end()) found = source_cache.emplace(coordinate, readTile(coordinate)).first;
            if (!found->second && source_coordinate_set.contains(coordinate)) source_read_failed = true;
            if (!found->second) return 0.0;
            const auto pixel = (static_cast<std::size_t>(y % kTileEdge) * kTileEdge + static_cast<std::size_t>(x % kTileEdge)) * 4U;
            return read_component(*found->second, pixel + channel * component_bytes);
        };

        const auto origin_x = static_cast<std::int64_t>(output_coordinate.x) * kTileEdge;
        const auto origin_y = static_cast<std::int64_t>(output_coordinate.y) * kTileEdge;
        const auto valid_width = std::min<std::int64_t>(kTileEdge, static_cast<std::int64_t>(width_) - origin_x);
        const auto valid_height = std::min<std::int64_t>(kTileEdge, static_cast<std::int64_t>(height_) - origin_y);
        for (std::int64_t local_y = 0; local_y < valid_height; ++local_y) {
            for (std::int64_t local_x = 0; local_x < valid_width; ++local_x) {
                const auto source_x = static_cast<double>(origin_x + local_x) - dx;
                const auto source_y = static_cast<double>(origin_y + local_y) - dy;
                const auto x0 = static_cast<std::int64_t>(std::floor(source_x));
                const auto y0 = static_cast<std::int64_t>(std::floor(source_y));
                const auto fx = source_x - static_cast<double>(x0);
                const auto fy = source_y - static_cast<double>(y0);
                const std::array<double, 4> weights{
                    (1.0 - fx) * (1.0 - fy), fx * (1.0 - fy), (1.0 - fx) * fy, fx * fy};
                const std::array<std::pair<std::int64_t, std::int64_t>, 4> points{{
                    {x0, y0}, {x0 + 1, y0}, {x0, y0 + 1}, {x0 + 1, y0 + 1}}};
                double alpha = 0.0;
                std::array<double, 3> premultiplied{};
                for (std::size_t i = 0; i < points.size(); ++i) {
                    const auto a = component_at(points[i].first, points[i].second, 3U);
                    alpha += a * weights[i];
                    for (std::size_t channel = 0; channel < 3U; ++channel) {
                        premultiplied[channel] += component_at(points[i].first, points[i].second, channel) * a * weights[i];
                    }
                }
                if (alpha <= 0.0) continue;
                const auto pixel = (static_cast<std::size_t>(local_y) * kTileEdge + static_cast<std::size_t>(local_x)) * 4U;
                for (std::size_t channel = 0; channel < 3U; ++channel) {
                    write_component(*output, pixel + channel * component_bytes, premultiplied[channel] / alpha);
                }
                write_component(*output, pixel + 3U * component_bytes, alpha);
            }
        }
        if (source_read_failed) return false;
        if (!shifted.writeTile(output_coordinate, *output)) return false;
    }
    *this = shifted;
    return true;
}

std::size_t TileStore::resident_bytes() const {
    const std::lock_guard lock(mutex_);
    std::size_t resident = tiles_.size() * tile_byte_size();
    if (memory_budget_) {
        for (const auto& [coordinate, tile] : managed_tiles_) {
            (void)coordinate;
            if (memory_budget_->isResident(tile->key)) resident += tile_byte_size();
        }
    }
    return resident;
}

bool TileStore::writeTile(TileCoordinate coordinate, const TileBuffer& pixels) {
    const std::lock_guard lock(mutex_);
    if (!contains(coordinate) || pixels.size() != tile_byte_size()) return false;
    if (std::all_of(pixels.begin(), pixels.end(), [](std::uint8_t value) { return value == 0; })) {
        tiles_.erase(coordinate);
        managed_tiles_.erase(coordinate);
        invalidateMipmaps({coordinate.x * static_cast<std::int32_t>(kTileEdge),
                           coordinate.y * static_cast<std::int32_t>(kTileEdge),
                           (coordinate.x + 1) * static_cast<std::int32_t>(kTileEdge),
                           (coordinate.y + 1) * static_cast<std::int32_t>(kTileEdge)});
        content_revision_ = nextContentRevision();
        tile_content_revisions_[coordinate] = nextContentRevision();
        return true;
    }
    if (memory_budget_) {
        const auto key = "s" + std::to_string(store_id_) + "_t" + std::to_string(++tile_sequence_);
        if (!memory_budget_->put(key, pixels)) {
            (void)memory_budget_->erase(key);
            return false;
        }
        tiles_.erase(coordinate);
        managed_tiles_[coordinate] = std::make_shared<ManagedTileRef>(key, memory_budget_);
        invalidateMipmaps({coordinate.x * static_cast<std::int32_t>(kTileEdge),
                           coordinate.y * static_cast<std::int32_t>(kTileEdge),
                           (coordinate.x + 1) * static_cast<std::int32_t>(kTileEdge),
                           (coordinate.y + 1) * static_cast<std::int32_t>(kTileEdge)});
        content_revision_ = nextContentRevision();
        tile_content_revisions_[coordinate] = nextContentRevision();
        return true;
    }
    auto tile = pool_->acquire(pixels.size());
    std::memcpy(tile->data(), pixels.data(), pixels.size());
    tiles_[coordinate] = std::move(tile);
    invalidateMipmaps({coordinate.x * static_cast<std::int32_t>(kTileEdge),
                       coordinate.y * static_cast<std::int32_t>(kTileEdge),
                       (coordinate.x + 1) * static_cast<std::int32_t>(kTileEdge),
                       (coordinate.y + 1) * static_cast<std::int32_t>(kTileEdge)});
    content_revision_ = nextContentRevision();
    tile_content_revisions_[coordinate] = nextContentRevision();
    return true;
}

bool TileStore::eraseTile(TileCoordinate coordinate) {
    const std::lock_guard lock(mutex_);
    if (!contains(coordinate)) return false;
    const bool erased_tile = tiles_.erase(coordinate) != 0;
    const bool erased_managed_tile = managed_tiles_.erase(coordinate) != 0;
    const bool erased = erased_tile || erased_managed_tile;
    invalidateMipmaps({coordinate.x * static_cast<std::int32_t>(kTileEdge),
                       coordinate.y * static_cast<std::int32_t>(kTileEdge),
                       (coordinate.x + 1) * static_cast<std::int32_t>(kTileEdge),
                       (coordinate.y + 1) * static_cast<std::int32_t>(kTileEdge)});
    if (erased) {
        content_revision_ = nextContentRevision();
        tile_content_revisions_[coordinate] = nextContentRevision();
    }
    return true;
}

void TileStore::clear() {
    const std::lock_guard lock(mutex_);
    const bool had_pixels = !tiles_.empty() || !managed_tiles_.empty();
    if (had_pixels) {
        for (const auto& [coordinate, tile] : tiles_) {
            (void)tile;
            tile_content_revisions_[coordinate] = nextContentRevision();
        }
        for (const auto& [coordinate, tile] : managed_tiles_) {
            (void)tile;
            tile_content_revisions_[coordinate] = nextContentRevision();
        }
    }
    tiles_.clear();
    managed_tiles_.clear();
    mip_tiles_.clear();
    if (had_pixels) content_revision_ = nextContentRevision();
}

}  // namespace croptop
