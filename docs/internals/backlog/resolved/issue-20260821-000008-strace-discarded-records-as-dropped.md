---
title: "Strace parser should mark discarded records as dropped to prevent false complete coverage"
severity: "LOW"
status: "resolved"
priority: low
dependencies: []
component: "profiler"
target_modules:
  - ":profiler"
target_files:
  - "profiler/src/main/kotlin/io/mazewall/profiler/compiler/StraceLogParser.kt"
effort: "small"
autonomy: "autonomous"
related_pr: 512
related_thread: 3819861572
---

# 🟡 [Severity: LOW]: Strace parser should mark discarded records as dropped

**Review (2026-08-21):** Still present. Path-bearing vs network quoting is already handled (`113002-restrict-quoted-paths-to-fs-syscalls`). This is **drop accounting**.

**Current tree:** `StraceLogParser.parse()` is `lineSequence().mapNotNull { parseLine(it) }`. `parseLine` returns null for empty lines, `+++`/`---` markers, and lines that do not look like a syscall. Truncated/unfinished (`<unfinished ...>`, `resumed>`) records are dropped with no counter. `DescendantStrace` can then report `droppedEvents=0` and `drainComplete=true`.

**Do not:**
- Treat `+++ exited +++` as a dropped syscall (noise, not a missed open).
- Fail the whole parse on one bad line without counting (operators need a drop count).
- Set `complete=true` when drops > 0.

**Do:**
1. Return observations **plus** a drop count (or a small result type) for records that look like syscalls but cannot be parsed (unfinished, unknown, truncated args).
2. Feed that count into `ProfilingCoverage.droppedEvents`. `droppedEvents > 0` already forces `complete=false`.
3. Keep ignoring genuine non-syscall noise (blank, `+++`, signals) without incrementing drops.

**Tests:** Log containing `openat(AT_FDCWD, "/tmp/a", O_RDONLY) = 3` plus an `<unfinished ...>` openat line → at least one drop, `complete=false`. Clean log → drops 0.

**Codex:** https://github.com/Pilleo/mazewall/pull/512#discussion_r3819861572
