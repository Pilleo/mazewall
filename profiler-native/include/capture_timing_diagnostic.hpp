#pragma once

#include <atomic>
#include <cstdint>
#include <limits>

namespace mazewall::profiler {

// Capture clocks are read only on the explicitly enabled diagnostic path.
struct CaptureClockReading {
    std::uint64_t wallNs;
    std::uint64_t cpuNs;
    int cpu;
    std::uint64_t voluntarySwitches;
    std::uint64_t involuntarySwitches;
    bool valid;
};

struct CaptureTimingSnapshot {
    std::uint64_t attempted;
    std::uint64_t completed;
    std::uint64_t invalid;
    std::uint64_t elapsedNs;
    std::uint64_t cpuNs;
    std::uint64_t migrations;
    std::uint64_t voluntarySwitches;
    std::uint64_t involuntarySwitches;
};

class CaptureTimingDiagnostic {
public:
    struct Start {
        bool enabled;
        CaptureClockReading reading;
    };

    template <typename Reader>
    Start begin(bool enabled, Reader&& read) noexcept {
        if (!enabled) return {false, {}};
        attempted_.fetch_add(1, std::memory_order_relaxed);
        return {true, read()};
    }

    template <typename Reader>
    void finish(Start start, Reader&& read) noexcept {
        if (!start.enabled) return;
        const auto end = read();
        if (!start.reading.valid || !end.valid || end.wallNs < start.reading.wallNs ||
            end.cpuNs < start.reading.cpuNs || end.voluntarySwitches < start.reading.voluntarySwitches ||
            end.involuntarySwitches < start.reading.involuntarySwitches) {
            invalid_.fetch_add(1, std::memory_order_relaxed);
            return;
        }
        const auto elapsed = end.wallNs - start.reading.wallNs;
        const auto cpu = end.cpuNs - start.reading.cpuNs;
        const auto voluntary = end.voluntarySwitches - start.reading.voluntarySwitches;
        const auto involuntary = end.involuntarySwitches - start.reading.involuntarySwitches;
        const auto migration = start.reading.cpu == end.cpu ? 0u : 1u;
        if (!add(elapsedNs_, elapsed) || !add(cpuNs_, cpu) || !add(voluntarySwitches_, voluntary) ||
            !add(involuntarySwitches_, involuntary) || !add(migrations_, migration)) {
            invalid_.fetch_add(1, std::memory_order_relaxed);
            return;
        }
        completed_.fetch_add(1, std::memory_order_relaxed);
    }

    CaptureTimingSnapshot snapshot() const noexcept {
        return {attempted_.load(std::memory_order_relaxed), completed_.load(std::memory_order_relaxed),
                invalid_.load(std::memory_order_relaxed), elapsedNs_.load(std::memory_order_relaxed),
                cpuNs_.load(std::memory_order_relaxed), migrations_.load(std::memory_order_relaxed),
                voluntarySwitches_.load(std::memory_order_relaxed),
                involuntarySwitches_.load(std::memory_order_relaxed)};
    }

private:
    static bool add(std::atomic<std::uint64_t>& counter, std::uint64_t delta) noexcept {
        auto current = counter.load(std::memory_order_relaxed);
        while (true) {
            if (delta > std::numeric_limits<std::uint64_t>::max() - current) return false;
            if (counter.compare_exchange_weak(current, current + delta, std::memory_order_relaxed)) return true;
        }
    }

    std::atomic<std::uint64_t> attempted_{0};
    std::atomic<std::uint64_t> completed_{0};
    std::atomic<std::uint64_t> invalid_{0};
    std::atomic<std::uint64_t> elapsedNs_{0};
    std::atomic<std::uint64_t> cpuNs_{0};
    std::atomic<std::uint64_t> migrations_{0};
    std::atomic<std::uint64_t> voluntarySwitches_{0};
    std::atomic<std::uint64_t> involuntarySwitches_{0};
};

} // namespace mazewall::profiler
