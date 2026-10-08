#include "recovery_autosave.h"

#include <utility>

namespace croptop {

AutosaveCadence::AutosaveCadence(std::chrono::milliseconds idle, std::chrono::milliseconds max_interval)
    : idle_(idle), max_interval_(max_interval) {}

void AutosaveCadence::onEdit(std::chrono::milliseconds now) {
    if (!dirty_) first_edit_ = now;
    dirty_ = true;
    last_edit_ = now;
}

void AutosaveCadence::onSnapshotTaken() { dirty_ = false; }

bool AutosaveCadence::due(std::chrono::milliseconds now) const {
    const auto next = nextDue();
    return next.has_value() && now >= *next;
}

std::optional<std::chrono::milliseconds> AutosaveCadence::nextDue() const {
    if (!dirty_) return std::nullopt;
    return std::min(last_edit_ + idle_, first_edit_ + max_interval_);
}

RecoveryAutosave::RecoveryAutosave(JobPool& pool, std::filesystem::path dir, int zstd_level)
    : pool_(pool), dir_(dir), zstd_level_(zstd_level), journal_(std::move(dir), zstd_level, 1) {
    status_.generation = journal_.generation();
}

RecoveryAutosave::~RecoveryAutosave() {
    std::unique_lock lock(mutex_);
    stopping_ = true;
    pending_.reset();
    if (handle_) handle_->cancel();
    (void)waitIdleLocked(lock, std::chrono::steady_clock::time_point::max());
}

void RecoveryAutosave::submit(RecoverySnapshot snapshot) {
    const std::lock_guard lock(mutex_);
    if (stopping_) return;
    pending_ = std::move(snapshot);
    reapDroppedJobLocked();
    scheduleLocked();
}

void RecoveryAutosave::scheduleLocked() {
    if (running_ || !pending_ || stopping_) return;
    const auto token = ++token_;
    running_ = true;
    started_ = false;
    handle_ = pool_.submit(JobPriority::Background, [this, token](const JobContext& context) { run(context, token); });
    // The pool refused the job (queue full of higher-priority work): keep the snapshot pending; the
    // next submit() or flush() retries.
    if (!handle_) running_ = false;
}

void RecoveryAutosave::reapDroppedJobLocked() {
    if (running_ && !started_ && handle_ && handle_->cancellationRequested()) {
        running_ = false;
        handle_.reset();
        ++token_;  // A stale start after this point exits without touching state.
        idle_.notify_all();
    }
}

bool RecoveryAutosave::waitIdleLocked(std::unique_lock<std::mutex>& lock,
                                      std::chrono::steady_clock::time_point deadline) {
    while (running_) {
        reapDroppedJobLocked();
        if (!running_) break;
        const auto now = std::chrono::steady_clock::now();
        if (now >= deadline) return false;
        idle_.wait_until(lock, std::min(deadline, now + std::chrono::milliseconds(20)));
    }
    return true;
}

void RecoveryAutosave::run(const JobContext& context, std::uint64_t token) {
    {
        const std::lock_guard lock(mutex_);
        if (token != token_) return;
        started_ = true;
    }
    while (true) {
        std::optional<RecoverySnapshot> snapshot;
        {
            const std::lock_guard lock(mutex_);
            if (stopping_ || context.cancellationRequested() || !pending_) {
                running_ = false;
                handle_.reset();
                idle_.notify_all();
                return;
            }
            snapshot = std::move(pending_);
            pending_.reset();
        }
        std::string error;
        const bool committed = journal_.commit(*snapshot, error, [&] { return context.cancellationRequested(); });
        snapshot.reset();  // Release the COW tile references before waiting for more work.
        const std::lock_guard lock(mutex_);
        if (committed) {
            ++status_.commits;
            status_.generation = journal_.generation();
            status_.last_error.clear();
            status_.last_warning = journal_.lastStats().warning;
        } else {
            status_.last_error = error;
        }
    }
}

bool RecoveryAutosave::flush(std::chrono::milliseconds timeout) {
    std::unique_lock lock(mutex_);
    const auto deadline = std::chrono::steady_clock::now() + timeout;
    while (pending_ || running_) {
        reapDroppedJobLocked();
        scheduleLocked();
        if (!running_ && pending_) {
            // The pool keeps refusing Background work; retry until the deadline.
            if (std::chrono::steady_clock::now() >= deadline) return false;
            idle_.wait_for(lock, std::chrono::milliseconds(20));
            continue;
        }
        if (!waitIdleLocked(lock, deadline)) return false;
    }
    return status_.last_error.empty();
}

bool RecoveryAutosave::discard(std::string& error) {
    std::unique_lock lock(mutex_);
    pending_.reset();
    if (handle_) handle_->cancel();
    (void)waitIdleLocked(lock, std::chrono::steady_clock::time_point::max());
    const bool removed = RecoveryJournal::discard(dir_, error);
    // Forget which tiles the old journal wrote: later edits start a fresh journal from scratch.
    journal_ = RecoveryJournal(dir_, zstd_level_, 1);
    status_ = {};
    return removed;
}

RecoveryAutosave::Status RecoveryAutosave::status() const {
    const std::lock_guard lock(mutex_);
    return status_;
}

}  // namespace croptop
