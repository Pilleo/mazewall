#pragma once

#include <array>
#include <cstddef>
#include <cstdint>

namespace mazewall::profiler {

/**
 * Fixed-capacity nesting state for one native thread.
 *
 * The caller owns one instance in thread-local storage. It performs no allocation and a failed
 * leave never restores an older active invocation, which is the native equivalent of the replay
 * machine's no-resurrection rule.
 */
class InvocationRegistry final {
public:
    struct Scope final {
        std::uint64_t id = 0;
        std::uint64_t parent_id = 0;
        std::uint64_t execution_id = 0;
        std::size_t depth = 0;

        [[nodiscard]] bool valid() const noexcept { return id != 0; }
    };

    [[nodiscard]] Scope enter(std::uint64_t id, std::uint64_t execution_id = 1) noexcept {
        if (id == 0 || execution_id == 0 || depth_ == parents_.size()) {
            return {};
        }
        if (depth_ != 0 && current_execution_id_ != execution_id) {
            clear();
            return {};
        }
        const Scope scope{id, current_id_, execution_id, depth_};
        parents_[depth_++] = current_id_;
        current_id_ = id;
        current_execution_id_ = execution_id;
        return scope;
    }

    [[nodiscard]] bool leave(const Scope& scope) noexcept {
        if (!scope.valid() || depth_ == 0 || scope.depth != depth_ - 1 || current_id_ != scope.id ||
            current_execution_id_ != scope.execution_id) {
            clear();
            return false;
        }
        current_id_ = parents_[--depth_];
        if (depth_ == 0) current_execution_id_ = 0;
        return true;
    }

    /** Rebinds the current scope to a canonical context without changing nesting. */
    [[nodiscard]] bool replace_current(Scope& scope, std::uint64_t replacement) noexcept {
        if (!scope.valid() || replacement == 0 || current_id_ != scope.id) {
            clear();
            return false;
        }
        current_id_ = replacement;
        scope.id = replacement;
        return true;
    }

    [[nodiscard]] std::uint64_t current_id() const noexcept { return current_id_; }

    [[nodiscard]] bool matches_current_execution(std::uint64_t execution_id) const noexcept {
        return execution_id != 0 && current_execution_id_ == execution_id;
    }

    void invalidate() noexcept { clear(); }

private:
    void clear() noexcept {
        current_id_ = 0;
        current_execution_id_ = 0;
        depth_ = 0;
    }

    std::array<std::uint64_t, 64> parents_{};
    std::size_t depth_ = 0;
    std::uint64_t current_id_ = 0;
    std::uint64_t current_execution_id_ = 0;
};

} // namespace mazewall::profiler
