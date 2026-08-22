---
title: "Drain child output while waiting for strace"
severity: "MEDIUM"
status: "open"
priority: high
dependencies: []
component: "profiler"
target_modules:
  - ":profiler"
target_files:
  - "profiler/src/main/kotlin/io/mazewall/profiler/collector/StraceCollector.kt"
effort: "medium"
autonomy: "autonomous"
related_pr: 512
related_thread: PRRC_kwDOScnnEM7kBdY
---

# 🟠 [Severity: MEDIUM]: Drain child output while waiting for strace

**Context:** When a traced workload writes more than the OS pipe capacity to stdout or stderr, both streams remain unread until after `waitFor()`, so the child blocks on its output pipe and the collector waits forever for it to exit. This affects descendant-strace profiling of verbose workloads.

**Problem:**
- Child process writes > OS pipe capacity to stdout/stderr
- Streams remain unread until after waitFor()
- Child blocks on output pipe write
- Collector waits forever for child to exit
- Deadlock situation

**Impact:**
- Descendant-strace profiling hangs on verbose workloads
- No timeout or recovery mechanism
- Operator must manually kill the process

**Needed:**
1. Consume both stdout and stderr streams concurrently with tracing
2. Or redirect streams before waiting for the process
3. Add timeout to prevent infinite blocking

**Codex PR Comment:** https://github.com/Pilleo/mazewall/pull/512#discussion_r3825587194
