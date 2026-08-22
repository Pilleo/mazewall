---
title: "Start the eBPF collector before invoking the workload"
severity: "HIGH"
status: "open"
priority: high
dependencies: []
component: "profiler"
target_modules:
  - ":profiler"
target_files:
  - "profiler/src/main/kotlin/io/mazewall/profiler/MazewallProfiler.kt"
effort: "small"
autonomy: "autonomous"
related_pr: 512
related_thread: 3823789271
---

# 🔴 [Severity: HIGH]: Start the eBPF collector before invoking the workload

**Review (2026-08-21):** Still present. Duplicate `issue-20260821-000001-ebpf-collector-before-workload` is closed.

**Current tree:** `MazewallProfiler.profileEbpfOnly()` does `val value = block()` **then** `drainEbpf()`. `drainEbpf` constructs `EbpfCollector` and calls `start()`. For `ProfileStrategy.EBPF` without a usable log, the workload therefore runs completely unobserved; `start()` then fails and the caller sees `IncompleteProfileException` after side effects already happened.

**Do not:**
- Swallow collector setup failure and still return `coverage.complete=true`.
- Run `block()` inside a `try` that treats a failed collector as “empty profile, compile anyway”.
- “Fix” this by documenting that EBPF-only is best-effort.

**Do:**
1. Validate `ebpfEventLog` / live-attach capability and call `EbpfCollector.start()` (or equivalent) **before** `block()`.
2. If start fails, do not invoke `block()`. Throw the incomplete/setup exception first.
3. Keep `drainComplete=false` for recorded sidecar logs (already true); this issue is only ordering.

**Tests:** Fake collector whose `start()` throws; assert the workload lambda is never entered. Opposite: start succeeds, lambda runs, observations include events that occurred during the lambda.

**Codex:** https://github.com/Pilleo/mazewall/pull/512#discussion_r3823789271
