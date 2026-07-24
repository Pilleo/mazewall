---
title: "Memory Segment Lifetime Leak in Async Profiler Events"
severity: "HIGH"
status: "resolved"
priority: 9
dependencies: []
component: "profiler"
effort: "small"
autonomy: "supervised"
solution_approved: false
blast_radius: "medium"
reversible: true
github_issue: 225
---

# 🔴 [Severity: LOW]: Memory Segment Lifetime Leak in Async Profiler Events

**Context:**
**Hypothesis:** The `ProfilerSessionHandler` receives events and creates detached FFM `MemorySegment` objects for trace elements. If these segments are passed to background logging threads or asynchronous channels without an explicit lifecycle scope (like an `Arena.ofConfined().use { ... }` block binding the entire trace lifecycle), the garbage collector must finalize the native memory, causing high GC pressure or native memory leaks under heavy profiling loads.

`ProfilerDaemon` uses a persistent `Arena.ofShared()` for some operations, but `process_vm_readv` strings are copied into JVM `String` objects, avoiding direct memory segment escapes. However, if any internal structs (like `seccomp_data` slices) are accidentally retained by the `TraceEvent` objects, they would escape their confined arenas.


**Needed:**
1. Document the strict requirement that all FFM `MemorySegment` data must be materialized into JVM heap objects before crossing the `TraceEvent` boundary into the compiler/logger.

## Solution Options

### Option A — Refactor implementation
Implement the recommendation described in the Needed section to resolve the issue directly. Target area: ``io.mazewall.profiler.engine.ProfilerSessionHandler``
**Pros:** Resolves the root cause of the issue.
**Cons:** Requires careful implementation and testing.
**Risk:** MEDIUM
**Effort:** small

---
**Chosen:** *(not yet approved — requires human decision)*

**Acceptance Criteria:**
- [ ] Tests verify the fix works as expected.
- [ ] Issue is fully resolved in the codebase.

**Implementation Hints:**
- Ensure you read existing tests and implementation carefully before modifying code.
