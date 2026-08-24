---
title: TraceEvent receives FFM values risking Use-After-Free due to Arena closure
type: issue
status: resolved
priority: high
labels:
- security
- enforcer
- ffm
- use-after-free
- memory-escape
component: enforcer
target_modules:
- :profiler
target_files:
- profiler/src/main/kotlin/io/mazewall/profiler/internal/ProfilerTraceListener.kt
github_issue: 327
paperclip_issue_id: cfc01917-4af7-4258-a321-94f77b807fe6
---

# Issue: FFM MemorySegment pointer escape in TraceEvent

## Context
The `mazewall` architecture requires that all `MemorySegment` objects stay within the `Arena` scope that created them. The ArchUnit rule `ProfilerArchitectureTest.kt` verifies that `TraceEvent` does not depend on `java.lang.foreign.*` or `io.mazewall.ffi.memory.*`.

## Verification & Resolution
Audited the profiler memory reading and event ingestion boundary across `TraceEvent`, `SyscallEvent`, `ProfilerTraceListener`, `TraceeMemoryReader`, and `ProfilerMemoryReader`:

1. **Heap Materialization**: `TraceeMemoryReader.Real` explicitly copies all off-heap buffers into JVM heap `ByteArray` instances via `MemorySegment.copy(localBuf.unwrap, ValueLayout.JAVA_BYTE, 0L, dest, 0, bytesRead)` and creates immutable Java `String` instances. No `MemorySegment` or `ManagedSegment` references escape the arena scope.
2. **Event Model Invariance**: All `TraceEvent` variants (`Generic`, `Open`, `Exec`, `Mmap`, `Socket`, `FsMutation`) and `SyscallEvent` hold strictly JVM heap primitive arrays (`LongArray`), collections (`List<Long>`, `List<String>`), and primitives (`Tid`, `String`).
3. **Stream Listener Boundary**: `ProfilerTraceListener` decodes binary socket messages over `DataInputStream` into JVM strings and heap data structures prior to dispatching onto the async `SharedFlow<TraceEvent>`, preventing any lifetime coupling with confined native arenas.
4. **Architectural Enforcement**: Static ArchUnit verification in `ProfilerArchitectureTest.kt` (`no FFM segments leak across trace event boundaries`) enforces that `TraceEvent` and `SyscallEvent` cannot reference `java.lang.foreign.*` or `io.mazewall.ffi.memory.*`.
