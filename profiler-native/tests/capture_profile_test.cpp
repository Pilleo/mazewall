#include "capture_profile.hpp"

int main() {
    using mazewall::profiler::BindingKind;
    using mazewall::profiler::CaptureProfile;

    const auto full = CaptureProfile::parse("full");
    if (!full.has_value() || !full->is_full() || !full->admits(BindingKind::net_accept)) return 1;

    const auto legacy = CaptureProfile::parse("jdk25-unix-openat0");
    if (!legacy.has_value() || legacy->admits(BindingKind::unix_open0) || !legacy->admits(BindingKind::unix_openat0)) return 2;

    const auto selected = CaptureProfile::parse("unix-openat0+unix-open0");
    if (!selected.has_value() || selected->canonical_spec() != "unix-open0+unix-openat0") return 3;
    if (!selected->admits(BindingKind::unix_open0) || !selected->admits(BindingKind::unix_openat0)) return 4;
    if (selected->admits(BindingKind::file_read0) || selected->admits(BindingKind::unsupported)) return 5;

    if (CaptureProfile::parse("").has_value()) return 6;
    if (CaptureProfile::parse("unix-open0+unix-open0").has_value()) return 7;
    if (CaptureProfile::parse("unknown").has_value()) return 8;
    if (CaptureProfile::parse("unsupported").has_value()) return 9;
}
