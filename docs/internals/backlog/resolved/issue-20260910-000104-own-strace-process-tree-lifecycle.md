---
title: "Own strace process tree lifecycle"
severity: "HIGH"
status: "resolved"
priority: high
dependencies: []
component: "profiler"
target_modules: ["profiler"]
target_files: ["profiler/src/main/kotlin/io/mazewall/profiler/collector/StraceCollector.kt"]
target_symbols: ["StraceProcessTree"]
open_questions: false
---

# Own strace process tree lifecycle

**Context:** Timeout or interruption could leave strace or descendant JVM processes and streams alive.

**Needed:** Terminate descendants and parent, reap within a bound, close output resources, and preserve interruption.

**Resolution:** Strace output is redirected to owned temporary files and `StraceProcessTree` performs
bounded graceful then forced tree termination on timeout, failure, and interruption while restoring
the caller's interrupt status.

---
<!-- id: issue-20260910-000104-own-strace-process-tree-lifecycle  file: issue-20260910-000104-own-strace-process-tree-lifecycle.md -->
