---
title: "Mark discarded strace records as dropped"
severity: "MEDIUM"
status: "resolved"
priority: medium
component: "profiler"
dependencies: []
target_modules:
  - ":profiler"
target_files:
  - "profiler/src/main/kotlin/io/mazewall/profiler/collector/StraceCollector.kt"
  - "profiler/src/main/kotlin/io/mazewall/profiler/compiler/StraceLogParser.kt"
effort: "small"
autonomy: "autonomous"
---

# Mark Discarded Strace Records as Dropped

**Source:** Codex PR review comment (P2)
**Created:** 2026-08-20

## Problem

When a successful strace run contains a truncated, resumed, unsupported, or otherwise unparsable syscall record, `StraceLogParser.parse()` silently removes it via `mapNotNull`, but this drain still reports zero drops and completion. `DescendantStrace.profile()` can consequently produce `coverage.complete=true` and allow policy compilation even though an observed syscall or path was omitted.

## Impact

- Incomplete Bills of Behavior being marked as complete
- Policies generated from incomplete profiles
- Silent data loss in profiling

## Solution

Propagate parser diagnostics or fail the drain rather than certifying the partial log.

## Related Files

- `profiler/src/main/kotlin/io/mazewall/profiler/collector/StraceCollector.kt`
- `profiler/src/main/kotlin/io/mazewall/profiler/compiler/StraceLogParser.kt`
