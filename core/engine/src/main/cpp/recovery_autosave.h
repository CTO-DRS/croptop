#pragma once

// Scheduling around RecoveryJournal: when to snapshot, and running commits
// on the engine JobPool at Background priority with bounded, coalesced pending work.

#include <chrono>
#include <condition_variable>
#include <cstdint>
#include <filesystem>
#include <mutex>
#include <optional>
#include <string>

#include "job_system.h"
#include "recovery_journal.h"

namespace croptop {

/**
 * Pure autosave timing (no clock of its own, so it is deterministic in tests): a snapshot is due
 * `idle` after the last edit, or `max_interval` after the first unsaved edit during continuous
 * editing, whichever comes first.
 */
class AutosaveCadence final {
public:
    explicit AutosaveCadence(std::chrono::milliseconds idle = std::chrono::seconds(5),
                             std::chrono::milliseconds max_interval = std::chrono::seconds(60));

    void onEdit(std::chrono::milliseconds now);
    /** Call when a snapshot of the current state was handed to the journal; later edits re-arm. */
    void onSnapshotTaken();
    bool dirty() const { return dirty_; }
    bool due(std::chrono::milliseconds now) const;
    /** When the next snapshot becomes due, or nullopt while clean; use it to arm a timer. */
    std::optional<std::chrono::milliseconds> nextDue() const;

private:
    std::chrono::milliseconds idle_;
    std::chrono::milliseconds max_interval_;
    bool dirty_ = false;
    std::chrono::milliseconds first_edit_{0};
    std::chrono::milliseconds last_edit_{0};
};

/**
 * Runs journal commits for one document on a JobPool at Background priority. At most one commit
 * runs and at most one snapshot waits: submitting while a snapshot is pending replaces it, so edits
 * during a slow commit never queue unbounded COW snapshots. Commits poll the job's cancellation
 * between tiles. Destroy it before the JobPool.
 */
class RecoveryAutosave final {
public:
    struct Status final {
        std::uint64_t generation = 0;
        std::size_t commits = 0;
        std::string last_error;
        std::string last_warning;
    };

    RecoveryAutosave(JobPool& pool, std::filesystem::path dir, int zstd_level = 1);
    ~RecoveryAutosave();
    RecoveryAutosave(const RecoveryAutosave&) = delete;
    RecoveryAutosave& operator=(const RecoveryAutosave&) = delete;

    /** Queues the latest snapshot, replacing any snapshot that has not started yet. */
    void submit(RecoverySnapshot snapshot);
    /**
     * Waits until every submitted snapshot is committed (e.g. when the app goes to the background).
     * Returns false on timeout or if the last commit failed.
     */
    bool flush(std::chrono::milliseconds timeout);
    /**
     * After a clean save or an intentional close: drops pending work, cancels, waits and deletes the
     * journal. Later submits start a new journal.
     */
    bool discard(std::string& error);
    Status status() const;

private:
    void scheduleLocked();
    /** A queued job that the pool evicted or pruned never runs; detect it and allow rescheduling. */
    void reapDroppedJobLocked();
    /** Waits (polling for dropped jobs) until no job runs; returns false at the deadline. */
    bool waitIdleLocked(std::unique_lock<std::mutex>& lock, std::chrono::steady_clock::time_point deadline);
    void run(const JobContext& context, std::uint64_t token);

    JobPool& pool_;
    std::filesystem::path dir_;
    int zstd_level_;
    RecoveryJournal journal_;
    mutable std::mutex mutex_;
    std::condition_variable idle_;
    std::optional<RecoverySnapshot> pending_;
    bool running_ = false;
    bool started_ = false;
    bool stopping_ = false;
    std::uint64_t token_ = 0;
    std::optional<JobHandle> handle_;
    Status status_;
};

}  // namespace croptop
