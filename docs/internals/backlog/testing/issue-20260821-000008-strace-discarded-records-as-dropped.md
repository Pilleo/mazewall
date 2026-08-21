---
title: "Strace parser should mark discarded records as dropped to prevent false complete coverage"
severity: "LOW"
status: "open"
priority: low
dependencies: []
component: "profiler"
target_modules:
  - ":profiler"
target_files:
  - "profiler/src/main/kotlin/io/mazewall/profiler/compiler/StraceLogParser.kt"
effort: "small"
autonomy: "autonomous"
---

# 🟡 [Severity: LOW]: Strace parser should mark discarded records as dropped

**Context:** When `StraceLogParser.parse()` encounters truncated, resumed, unsupported, or otherwise unparsable syscall records, it silently removes them via `mapNotNull`. This means the drain reports zero drops and completion, even though observed syscalls or paths were omitted. `DescendantStrace.profile()` can consequently produce `coverage.complete=true` and allow policy compilation despite the partial log.

**Needed:** Propagate parser diagnostics or fail the drain rather than certifying the partial log. Options:
1. Track discarded records in `StraceLogParser` and return drop count
2. Make `parse()` throw or return a result type that includes drop information
3. Update `DescendantStrace.profile()` to check for and propagate drop counts

**Codex PR Comment:** https://github.com/Pilleo/mazewall/pull/512#discussion_r3819861572
