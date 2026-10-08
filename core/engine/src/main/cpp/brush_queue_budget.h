// Bounded dab accounting for asynchronous native brush sessions.
#pragma once

#include <cstddef>

namespace croptop::brush {

class DabQueueBudget final {
public:
    explicit DabQueueBudget(std::size_t limit) : limit_(limit) {}

    bool enqueue(std::size_t count) {
        if (reserved() > limit_ || count > limit_ - reserved()) return false;
        queued_ += count;
        return true;
    }

    bool begin(std::size_t count) {
        if (count > queued_) return false;
        queued_ -= count;
        in_flight_ += count;
        return true;
    }

    bool complete(std::size_t count) {
        if (count > in_flight_) return false;
        in_flight_ -= count;
        return true;
    }

    void discardQueued() { queued_ = 0; }

    std::size_t queued() const { return queued_; }
    std::size_t inFlight() const { return in_flight_; }
    std::size_t reserved() const { return queued_ + in_flight_; }

private:
    std::size_t limit_;
    std::size_t queued_ = 0;
    std::size_t in_flight_ = 0;
};

}  // namespace croptop::brush
