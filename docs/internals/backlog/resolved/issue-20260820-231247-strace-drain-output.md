---
title: "Drain child output while waiting for strace"
severity: "MEDIUM"
status: "open"
priority: medium
component: "profiler"
dependencies: []
target_modules:
  - ":profiler"
target_files:
  - "profiler/src/main/kotlin/io/mazewall/profiler/collector/StraceCollector.kt"
effort: "medium"
autonomy: "autonomous"
---

# Drain Child Output While Waiting for Strace

**Source:** Codex PR review comment (P2)
**Created:** 2026-08-20

## Problem

When a traced workload writes more than the OS pipe capacity to stdout or stderr, both streams remain unread until after `waitFor()`, so the child blocks on its output pipe and the collector waits forever for it to exit. This affects descendant-strace profiling of verbose workloads.

## Impact

- Collector hangs waiting for process that is blocked on output
- Profiling timeout for verbose workloads
- Incomplete strace data collection

## Solution

Consume both stdout and stderr streams concurrently or redirect them before waiting for the process.

## Related Files

- `profiler/src/main/kotlin/io/mazewall/profiler/collector/StraceCollector.kt` - Line 68
