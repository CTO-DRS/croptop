#pragma once

#include <cstddef>
#include <cstdint>
#include <filesystem>
#include <memory>
#include <mutex>
#include <optional>
#include <string>
#include <unordered_map>
#include <vector>

namespace croptop {

/** Private, checksummed Zstandard scratch files for reconstructible or history tile payloads. */
class DiskScratchStore final {
public:
    explicit DiskScratchStore(std::filesystem::path directory);

    bool write(const std::string& key, const std::vector<std::uint8_t>& bytes);
    std::optional<std::vector<std::uint8_t>> read(const std::string& key) const;
    bool erase(const std::string& key);

private:
    std::filesystem::path pathFor(const std::string& key) const;

    std::filesystem::path directory_;
    mutable std::mutex mutex_;
    std::uint64_t temporary_sequence_ = 0;
};

/** Thread-safe byte-budgeted LRU. Unpinned entries spill to compressed scratch before eviction. */
class MemoryBudgetManager final {
public:
    MemoryBudgetManager(std::size_t budget_bytes, std::shared_ptr<DiskScratchStore> scratch);

    bool put(std::string key, std::vector<std::uint8_t> bytes);
    std::shared_ptr<const std::vector<std::uint8_t>> get(const std::string& key);
    bool erase(const std::string& key);
    void setBudget(std::size_t budget_bytes);
    void trimTo(std::size_t target_bytes);
    /** Reserve transient working memory while keeping resident tile data under the same budget. */
    bool reserveWorkingBytes(std::size_t bytes);
    void releaseWorkingBytes(std::size_t bytes);
    std::size_t budgetBytes() const;
    std::size_t residentBytes() const;
    std::size_t entryCount() const;
    bool isResident(const std::string& key) const;

private:
    struct Entry {
        std::shared_ptr<const std::vector<std::uint8_t>> data;
        std::uint64_t last_access = 0;
    };

    bool evictUntil(std::size_t target_bytes);

    mutable std::mutex mutex_;
    std::size_t budget_bytes_;
    std::size_t resident_bytes_ = 0;
    std::size_t reserved_working_bytes_ = 0;
    std::uint64_t sequence_ = 0;
    std::shared_ptr<DiskScratchStore> scratch_;
    std::unordered_map<std::string, Entry> entries_;
};

}  // namespace croptop
