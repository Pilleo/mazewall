#pragma once

#include "binding_manifest.hpp"

#include <cstdint>
#include <optional>
#include <string>
#include <string_view>

namespace mazewall::profiler {

/** Immutable, bounded native-binding admission profile parsed before the JVM starts. */
class CaptureProfile final {
public:
    [[nodiscard]] static constexpr CaptureProfile full() noexcept { return CaptureProfile(true, 0); }

    [[nodiscard]] static std::optional<CaptureProfile> parse(std::string_view specification) {
        if (specification == "full") return full();
        if (specification == "jdk25-unix-openat0") return CaptureProfile(false, bit(BindingKind::unix_openat0));
        if (specification.empty()) return std::nullopt;

        std::uint32_t selected = 0;
        std::size_t start = 0;
        while (start < specification.size()) {
            const auto end = specification.find('+', start);
            const auto name = specification.substr(start, end == std::string_view::npos ? specification.size() - start : end - start);
            const auto kind = BindingManifest::from_name(name);
            if (kind == BindingKind::unsupported) return std::nullopt;
            const auto selected_bit = bit(kind);
            if ((selected & selected_bit) != 0) return std::nullopt;
            selected |= selected_bit;
            if (end == std::string_view::npos) break;
            start = end + 1;
        }
        return selected == 0 ? std::nullopt : std::optional<CaptureProfile>(CaptureProfile(false, selected));
    }

    [[nodiscard]] constexpr bool is_full() const noexcept { return full_; }

    [[nodiscard]] constexpr bool admits(BindingKind kind) const noexcept {
        return kind != BindingKind::unsupported && (full_ || (selected_ & bit(kind)) != 0);
    }

    [[nodiscard]] std::string canonical_spec() const {
        if (full_) return "full";
        std::string result;
        for (const auto kind : BindingManifest::supported_bindings) {
            if (!admits(kind)) continue;
            if (!result.empty()) result += '+';
            result += BindingManifest::name(kind);
        }
        return result;
    }

private:
    constexpr CaptureProfile(bool full, std::uint32_t selected) noexcept : full_(full), selected_(selected) {}

    [[nodiscard]] static constexpr std::uint32_t bit(BindingKind kind) noexcept {
        return kind == BindingKind::unsupported ? 0U : 1U << BindingManifest::index(kind);
    }

    bool full_;
    std::uint32_t selected_;
};

} // namespace mazewall::profiler
