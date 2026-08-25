---
title: "Do not use container PID 1 to identify the initial user namespace"
severity: "MEDIUM"
status: "resolved"
priority: medium
component: "profiler"
dependencies: []
target_modules:
  - ":profiler"
target_files:
  - "profiler/src/main/kotlin/io/mazewall/profiler/EbpfLoad.kt"
effort: "medium"
autonomy: "autonomous"
---

# Do Not Use Container PID 1 to Identify the Initial User Namespace

**Source:** Codex PR review comment (P2)
**Created:** 2026-08-20

## Problem

Inside a rootless container, `/proc/1/ns/user` normally refers to the container's PID 1, which can share the caller's non-initial user namespace. `Files.isSameFile` then returns true and bypasses the `uid_map` fallback, so namespaced capability bits can make `probe()` report `EbpfLoad.Available` even though the tracing eBPF capability requirement in the initial host namespace is not met.

## Impact

- Incorrect eBPF capability detection in containers
- Tracing eBPF load succeeds when it should not
- Security bypass in containerized environments

## Solution

Determine initial-namespace membership from host-provided evidence rather than comparing against the container-visible PID 1.

## Related Files

- `profiler/src/main/kotlin/io/mazewall/profiler/EbpfLoad.kt` - Line 75
