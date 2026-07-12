---
title: "High-Frequency Arena Allocation Overhead (MM Optimization)"
severity: "MEDIUM"
status: "open"
priority: 6
dependencies: []
component: "ffi"
effort: "medium"
autonomy: "supervised"
solution_approved: false
---


# 🟡 [Severity: LOW]: High-Frequency Arena Allocation Overhead (MM Optimization)

**Context:** The current `nativeScope` utility and profiler reactor loop create a new `Arena.ofConfined()` for every single operation (syscall resolution, polling, etc.). This puts unnecessary pressure on the JVM native allocator and GC.
**Needed:** Investigate "Scoped Arenas" using Kotlin context parameters or a `ThreadLocal` arena for high-frequency reactor loops. Reuse the same arena for all operations within a single task or notification lifecycle.

## Solution Options

### Option A
(To be filled)

---
**Chosen:** *(not yet approved — requires human decision)*

**Acceptance Criteria:**
- [ ]

**Implementation Hints:**
-
