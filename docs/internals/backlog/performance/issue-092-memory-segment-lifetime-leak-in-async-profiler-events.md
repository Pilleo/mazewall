---
title: "Memory Segment Lifetime Leak in Async Profiler Events"
severity: "HIGH"
status: "open"
priority: 9
dependencies: []
component: "profiler"
effort: "small"
---

# 🔴 [Severity: LOW]: Memory Segment Lifetime Leak in Async Profiler Events

*   **Dimension:** Memory Lifetimes & Escapes
*   **Target Area:** `io.mazewall.profiler.engine.ProfilerSessionHandler`
*   **Failure Hypothesis:** The `ProfilerSessionHandler` receives events and creates detached FFM `MemorySegment` objects for trace elements. If these segments are passed to background logging threads or asynchronous channels without an explicit lifecycle scope (like an `Arena.ofConfined().use { ... }` block binding the entire trace lifecycle), the garbage collector must finalize the native memory, causing high GC pressure or native memory leaks under heavy profiling loads.
*   **Context & Proof:** `ProfilerDaemon` uses a persistent `Arena.ofShared()` for some operations, but `process_vm_readv` strings are copied into JVM `String` objects, avoiding direct memory segment escapes. However, if any internal structs (like `seccomp_data` slices) are accidentally retained by the `TraceEvent` objects, they would escape their confined arenas.
*   **Cascading Risk Potential:** Low. The current implementation aggressively converts native data to immutable Kotlin classes (`String`, `Syscall`), so segments don't escape. However, any future optimization attempting to zero-copy `TraceEvent` data could introduce critical memory safety bugs.
*   **Recommendation:** Document the strict requirement that all FFM `MemorySegment` data must be materialized into JVM heap objects before crossing the `TraceEvent` boundary into the compiler/logger.
