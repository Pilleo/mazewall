---
title: "Count each unparsed connect as incomplete"
severity: "MEDIUM"
status: "open"
priority: medium
dependencies: []
component: "profiler"
target_modules:
  - ":profiler"
target_files:
  - "profiler/src/main/kotlin/io/mazewall/profiler/ProfilingCoverage.kt"
effort: "small"
autonomy: "autonomous"
related_pr: 512
related_thread: 3823789286
---

# 🟡 [Severity: MEDIUM]: Count each unparsed connect as incomplete

**Review (2026-08-21):** Still present. IPv6 **parsed** connects already round-trip (`113001-preserve-portless-ipv6` resolved). This is the **per-event** hole.

**Current tree:**
```
unparsedConnect =
  observations.any { Syscall named CONNECT } &&
  observations.none { it is ProfileObservation.Connect }
```
One parsed `Connect(127.0.0.1:80)` makes `none` false. A sibling `Syscall("CONNECT")` from AF_UNIX (or a failed parse) is ignored. `complete` can stay true.

**Do not:**
- Require every connect to be INET. AF_UNIX still must make coverage incomplete (or become a first-class observation).
- Flip the global flag only when **all** connects fail to parse.
- Count OPEN/other syscalls.

**Do:**
1. For each `Syscall` observation named `CONNECT`, require a matching `ProfileObservation.Connect` (or a dedicated Unix-connect type). Any leftover CONNECT syscall → warning + `complete=false`.
2. Keep the existing “CONNECT observed, zero Connect records” warning as the all-unparsed case.

**Tests:** Observations = `[Connect(1.2.3.4:80), Syscall("CONNECT")]` → `complete=false` and a warning. Only `Connect(...)` → not incomplete for this reason. Only `Syscall("CONNECT")` → incomplete (already).

**Codex:** https://github.com/Pilleo/mazewall/pull/512#discussion_r3823789286
