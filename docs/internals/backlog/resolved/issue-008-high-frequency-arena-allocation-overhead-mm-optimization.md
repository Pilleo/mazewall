---
title: "High-Frequency Arena Allocation Overhead (MM Optimization)"
severity: "MEDIUM"
status: "resolved"
priority: medium
dependencies: []
component: "ffi"
effort: "medium"
github_issue: 259
paperclip_issue_id: e021ec64-4511-4fe0-9303-b0c893106f98
paperclip_identifier: MAZ-213
---

# 🟡 [Severity: LOW]: High-Frequency Arena Allocation Overhead (MM Optimization)

**Context:** The current `nativeScope` utility and profiler reactor loop create a new `Arena.ofConfined()` for every single operation (syscall resolution, polling, etc.). This puts unnecessary pressure on the JVM native allocator and GC.
**Needed:** Investigate "Scoped Arenas" using Kotlin context parameters or a `ThreadLocal` arena for high-frequency reactor loops. Reuse the same arena for all operations within a single task or notification lifecycle.
