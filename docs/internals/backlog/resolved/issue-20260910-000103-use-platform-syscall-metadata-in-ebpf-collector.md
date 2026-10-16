---
title: "Use platform syscall metadata in eBPF collector"
severity: "HIGH"
status: "resolved"
priority: high
dependencies: []
component: "profiler"
target_modules: ["profiler", "platform"]
target_files: ["profiler/src/main/kotlin/io/mazewall/profiler/collector/LiveEbpfCollector.kt"]
target_symbols: ["ProfileObservationEbpfCollector"]
open_questions: false
---

# Use platform syscall metadata in eBPF collector

**Context:** A small hard-coded x86 syscall table mislabelled records on AArch64 and hid unknowns.

**Needed:** Reverse-map through platform `Arch` metadata and retain unknown numbers explicitly.

**Resolution:** The observation adapter accepts an architecture, resolves all `Syscall` entries
through platform metadata, and returns `ObservedSyscall.Unknown("syscall:<nr>")` when unmapped.

---
<!-- id: issue-20260910-000103-use-platform-syscall-metadata-in-ebpf-collector  file: issue-20260910-000103-use-platform-syscall-metadata-in-ebpf-collector.md -->
