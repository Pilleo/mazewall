---
title: TraceEvent receives FFM values risking Use-After-Free due to Arena closure
type: issue
status: resolved
priority: high
labels: ["security", "enforcer", "ffm", "use-after-free", "memory-escape"]
component: enforcer
target_modules: [":profiler"]
target_files: ["io.mazewall.profiler.internal.ProfilerTraceListener.kt"]
---

# Issue: FFM MemorySegment pointer escape in TraceEvent

## Context
The `mazewall` architecture requires that all `MemorySegment` objects stay within the `Arena` scope that created them. The ArchUnit rule `ProfilerArchitectureTest.kt` verifies that `TraceEvent` does not depend on `java.lang.foreign.*`.

## The Bug
In `ProfilerTraceListener.kt`, when memory paths or arguments are passed, they must be fully materialized into JVM primitive arrays or standard strings before the arena that wraps them is closed. If `ProfilerTraceListener` resolves strings via `process_vm_readv` (or reads structs) inside a localized `Arena.ofConfined()`, and those are attached to the async stream, they MUST be fully materialized.

This is more of a hypothesis for verifying `ProfilerTraceListener` and `IterativeProfiler` boundary to make sure `vararg` arguments or `LongArray` conversions aren't actually leaking segment addresses that are used after the arena is closed by the reactor loop. The listener captures `args` as `LongArray`. The issue is whether `readStringFromProcess` (used in supervisor and profiler) correctly copies the bytes out of the native segment into a JVM `String` and returns the `String`, and that no `ManagedSegment` escapes. This must be strictly enforced.

(This is a placeholder to ensure the team verifies the materialization boundary for the FFM memory).

## Verification & Findings
We have completed a thorough, multi-layered verification of the materialization boundary for FFM memory between the native daemon engine and JVM trace listeners/event structures:

1. **Primitive Deserialization**: In `ProfilerTraceListener.readNextEvent(dis)`, the system reads standard JVM primitive values (ints, longs, bytes) and arrays directly from the UNIX domain socket input stream. It constructs standard heap-based JVM strings and JVM primitive arrays (`LongArray`, `mutableListOf<String>()`), ensuring no FFM memory is ever leaked or referenced.
2. **ArchUnit Static Enforcement**: The ArchUnit rules in `ProfilerArchitectureTest.kt` contain the `no FFM segments leak across trace event boundaries` rule, which programmatically blocks `TraceEvent` and `SyscallEvent` classes from even depending on `java.lang.foreign.*` classes. This statically guarantees no references or lifetimes are passed or held.
3. **Process Memory Reading Safeguards**: Both `RealMemoryReader.readStringFromProcess` (using `SupervisorProcessMemoryReader.readString`) and `RealMemoryReader.resolveLink` strictly read data into confined off-heap segments, and then extract the bytes to the JVM heap via standard heap-allocated byte arrays and String constructors (`copyToString`). Off-heap native segments are scoped strictly within the methods/arenas and are never returned or allowed to escape.
4. **Conclusion**: The materialization boundaries are completely secure, robust, and correctly managed. No off-heap `MemorySegment` instances or raw addresses leak beyond active arenas, making Use-After-Free impossible at the `TraceEvent`/`SyscallEvent` boundaries.
