#include "scratch_store.h"

#include <zstd.h>

#include <algorithm>
#include <atomic>
#include <fstream>
#include <limits>
#include <stdexcept>

namespace croptop {
namespace {

bool validKey(const std::string& key) {
    return !key.empty() && key.size() <= 128U &&
           std::all_of(key.begin(), key.end(), [](unsigned char value) {
               return (value >= 'a' && value <= 'z') || (value >= 'A' && value <= 'Z') ||
                      (value >= '0' && value <= '9') || value == '-' || value == '_';
           });
}

constexpr std::uint64_t kMaximumScratchEntry = 1024ULL * 1024ULL * 1024ULL;

}  // namespace

DiskScratchStore::DiskScratchStore(std::filesystem::path directory) : directory_(std::move(directory)) {
    std::error_code error;
    std::filesystem::create_directories(directory_, error);
    if (error) throw std::runtime_error("Could not create scratch directory: " + error.message());
}

std::filesystem::path DiskScratchStore::pathFor(const std::string& key) const {
    if (!validKey(key)) throw std::invalid_argument("Scratch keys may contain only letters, digits, '-' and '_'");
    return directory_ / (key + ".zst");
}

bool DiskScratchStore::write(const std::string& key, const std::vector<std::uint8_t>& bytes) {
    std::filesystem::path destination;
    try {
        destination = pathFor(key);
    } catch (const std::invalid_argument&) {
        return false;
    }
    const auto bound = ZSTD_compressBound(bytes.size());
    std::vector<std::uint8_t> compressed(bound);
    auto* context = ZSTD_createCCtx();
    if (context == nullptr) return false;
    const auto checksum_result = ZSTD_CCtx_setParameter(context, ZSTD_c_checksumFlag, 1);
    const auto compressed_size = ZSTD_isError(checksum_result)
        ? checksum_result
        : ZSTD_compress2(context, compressed.data(), compressed.size(), bytes.data(), bytes.size());
    ZSTD_freeCCtx(context);
    if (ZSTD_isError(compressed_size)) return false;
    compressed.resize(compressed_size);

    std::lock_guard lock(mutex_);
    const auto temporary = directory_ / (key + ".tmp-" + std::to_string(++temporary_sequence_));
    {
        std::ofstream stream(temporary, std::ios::binary | std::ios::trunc);
        if (!stream) return false;
        stream.write(reinterpret_cast<const char*>(compressed.data()), static_cast<std::streamsize>(compressed.size()));
        stream.flush();
        if (!stream) {
            stream.close();
            std::error_code ignored;
            std::filesystem::remove(temporary, ignored);
            return false;
        }
    }
    std::error_code error;
    std::filesystem::rename(temporary, destination, error);
    if (error) {
        std::filesystem::remove(temporary, error);
        return false;
    }
    return true;
}

std::optional<std::vector<std::uint8_t>> DiskScratchStore::read(const std::string& key) const {
    std::filesystem::path source;
    try {
        source = pathFor(key);
    } catch (const std::invalid_argument&) {
        return std::nullopt;
    }
    std::lock_guard lock(mutex_);
    std::error_code error;
    const auto compressed_size = std::filesystem::file_size(source, error);
    if (error || compressed_size == 0U || compressed_size > std::numeric_limits<std::size_t>::max()) return std::nullopt;
    std::vector<std::uint8_t> compressed(static_cast<std::size_t>(compressed_size));
    {
        std::ifstream stream(source, std::ios::binary);
        if (!stream) return std::nullopt;
        stream.read(reinterpret_cast<char*>(compressed.data()), static_cast<std::streamsize>(compressed.size()));
        if (!stream) return std::nullopt;
    }
    const auto content_size = ZSTD_getFrameContentSize(compressed.data(), compressed.size());
    if (content_size == ZSTD_CONTENTSIZE_ERROR || content_size == ZSTD_CONTENTSIZE_UNKNOWN ||
        content_size > kMaximumScratchEntry || content_size > std::numeric_limits<std::size_t>::max()) {
        return std::nullopt;
    }
    std::vector<std::uint8_t> output(static_cast<std::size_t>(content_size));
    const auto result = ZSTD_decompress(output.data(), output.size(), compressed.data(), compressed.size());
    if (ZSTD_isError(result) || result != output.size()) return std::nullopt;
    return output;
}

bool DiskScratchStore::erase(const std::string& key) {
    std::filesystem::path path;
    try {
        path = pathFor(key);
    } catch (const std::invalid_argument&) {
        return false;
    }
    std::lock_guard lock(mutex_);
    std::error_code error;
    const bool removed = std::filesystem::remove(path, error);
    return !error && (removed || !std::filesystem::exists(path));
}

MemoryBudgetManager::MemoryBudgetManager(std::size_t budget_bytes, std::shared_ptr<DiskScratchStore> scratch)
    : budget_bytes_(budget_bytes), scratch_(std::move(scratch)) {
    if (!scratch_) throw std::invalid_argument("MemoryBudgetManager requires a scratch store");
}

bool MemoryBudgetManager::put(std::string key, std::vector<std::uint8_t> bytes) {
    if (key.empty()) return false;
    std::lock_guard lock(mutex_);
    auto& entry = entries_[key];
    if (entry.data) resident_bytes_ -= entry.data->size();
    entry.data = std::make_shared<const std::vector<std::uint8_t>>(std::move(bytes));
    entry.last_access = ++sequence_;
    resident_bytes_ += entry.data->size();
    // Preserve a write if readers pin every candidate or scratch is temporarily unavailable.
    // This can exceed the soft budget briefly; later puts/reads and trim callbacks retry eviction.
    const auto cache_budget = budget_bytes_ > reserved_working_bytes_ ? budget_bytes_ - reserved_working_bytes_ : 0U;
    (void)evictUntil(cache_budget);
    return true;
}

std::shared_ptr<const std::vector<std::uint8_t>> MemoryBudgetManager::get(const std::string& key) {
    std::lock_guard lock(mutex_);
    const auto found = entries_.find(key);
    if (found == entries_.end()) return nullptr;
    found->second.last_access = ++sequence_;
    if (found->second.data) return found->second.data;
    const auto restored = scratch_->read(key);
    if (!restored) return nullptr;
    auto restored_data = std::make_shared<const std::vector<std::uint8_t>>(*restored);
    found->second.data = restored_data;
    resident_bytes_ += restored_data->size();
    const auto cache_budget = budget_bytes_ > reserved_working_bytes_ ? budget_bytes_ - reserved_working_bytes_ : 0U;
    (void)evictUntil(cache_budget);
    // A read must succeed even when pinned entries leave no room in the cache.
    return found->second.data ? found->second.data : restored_data;
}

bool MemoryBudgetManager::erase(const std::string& key) {
    std::lock_guard lock(mutex_);
    const auto found = entries_.find(key);
    if (found == entries_.end()) return scratch_->erase(key);
    if (found->second.data) resident_bytes_ -= found->second.data->size();
    entries_.erase(found);
    return scratch_->erase(key);
}

void MemoryBudgetManager::setBudget(std::size_t budget_bytes) {
    std::lock_guard lock(mutex_);
    budget_bytes_ = budget_bytes;
    const auto cache_budget = budget_bytes_ > reserved_working_bytes_ ? budget_bytes_ - reserved_working_bytes_ : 0U;
    (void)evictUntil(cache_budget);
}

void MemoryBudgetManager::trimTo(std::size_t target_bytes) {
    std::lock_guard lock(mutex_);
    const auto cache_budget = target_bytes > reserved_working_bytes_ ? target_bytes - reserved_working_bytes_ : 0U;
    (void)evictUntil(cache_budget);
}

bool MemoryBudgetManager::reserveWorkingBytes(std::size_t bytes) {
    std::lock_guard lock(mutex_);
    if (bytes > budget_bytes_ || reserved_working_bytes_ > budget_bytes_ - bytes) return false;
    const auto next_reserved = reserved_working_bytes_ + bytes;
    if (!evictUntil(budget_bytes_ - next_reserved)) return false;
    reserved_working_bytes_ = next_reserved;
    return true;
}

void MemoryBudgetManager::releaseWorkingBytes(std::size_t bytes) {
    std::lock_guard lock(mutex_);
    if (bytes > reserved_working_bytes_) return;
    reserved_working_bytes_ -= bytes;
    (void)evictUntil(budget_bytes_ - reserved_working_bytes_);
}

std::size_t MemoryBudgetManager::budgetBytes() const {
    std::lock_guard lock(mutex_);
    return budget_bytes_;
}

std::size_t MemoryBudgetManager::residentBytes() const {
    std::lock_guard lock(mutex_);
    return resident_bytes_;
}

std::size_t MemoryBudgetManager::entryCount() const {
    std::lock_guard lock(mutex_);
    return entries_.size();
}

bool MemoryBudgetManager::isResident(const std::string& key) const {
    std::lock_guard lock(mutex_);
    const auto found = entries_.find(key);
    return found != entries_.end() && found->second.data != nullptr;
}

bool MemoryBudgetManager::evictUntil(std::size_t target_bytes) {
    while (resident_bytes_ > target_bytes) {
        auto candidate = entries_.end();
        for (auto it = entries_.begin(); it != entries_.end(); ++it) {
            if (!it->second.data || it->second.data.use_count() > 1) continue;
            if (candidate == entries_.end() || it->second.last_access < candidate->second.last_access) candidate = it;
        }
        if (candidate == entries_.end()) return false;
        if (!scratch_->write(candidate->first, *candidate->second.data)) return false;
        resident_bytes_ -= candidate->second.data->size();
        candidate->second.data.reset();
    }
    return true;
}

}  // namespace croptop
