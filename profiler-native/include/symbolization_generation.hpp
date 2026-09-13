#pragma once

#include <atomic>
#include <cstdint>

namespace mazewall::profiler {

/** Lock-free admission guard for one stable installed symbolization generation. */
class SymbolizationGeneration final {
public:
    enum class State : std::uint8_t {
        stable,
        replacement_in_progress,
        external_replacement_unproven,
    };

    struct Lease final {
        std::uint64_t value = 0;

        [[nodiscard]] bool valid() const noexcept { return value != 0; }
    };

    [[nodiscard]] Lease begin_capture() const noexcept {
        if (state_.load(std::memory_order_acquire) != State::stable) return {};
        const auto value = generation_.load(std::memory_order_acquire);
        if (state_.load(std::memory_order_acquire) != State::stable) return {};
        return Lease{value};
    }

    [[nodiscard]] bool validate(Lease lease) const noexcept {
        return lease.valid() &&
            state_.load(std::memory_order_acquire) == State::stable &&
            generation_.load(std::memory_order_acquire) == lease.value;
    }

    void begin_replacement(bool external) noexcept {
        state_.store(external ? State::external_replacement_unproven : State::replacement_in_progress,
            std::memory_order_release);
    }

    void complete_replacement() noexcept {
        if (state_.load(std::memory_order_acquire) != State::replacement_in_progress) return;
        generation_.fetch_add(1, std::memory_order_acq_rel);
        state_.store(State::stable, std::memory_order_release);
    }

    void resynchronize() noexcept {
        generation_.fetch_add(1, std::memory_order_acq_rel);
        state_.store(State::stable, std::memory_order_release);
    }

    [[nodiscard]] State state() const noexcept { return state_.load(std::memory_order_acquire); }

private:
    std::atomic<std::uint64_t> generation_{1};
    std::atomic<State> state_{State::stable};
};

} // namespace mazewall::profiler
