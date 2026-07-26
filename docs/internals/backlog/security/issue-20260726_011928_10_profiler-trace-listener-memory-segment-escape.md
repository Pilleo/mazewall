---
title: TraceEvent receives FFM values risking Use-After-Free due to Arena closure
type: issue
status: open
priority: 8
labels: ["security", "enforcer", "ffm", "use-after-free", "memory-escape"]
component: enforcer
target_modules: [":profiler"]
target_files: ["io.mazewall.profiler.internal.ProfilerTraceListener.kt"]
github_issue: 327
---

# Issue: FFM MemorySegment pointer escape in TraceEvent

## Context
The `mazewall` architecture requires that all `MemorySegment` objects stay within the `Arena` scope that created them. The ArchUnit rule `ProfilerArchitectureTest.kt` verifies that `TraceEvent` does not depend on `java.lang.foreign.*`.

## The Bug
In `ProfilerTraceListener.kt`, when memory paths or arguments are passed, they must be fully materialized into JVM primitive arrays or standard strings before the arena that wraps them is closed. If `ProfilerTraceListener` resolves strings via `process_vm_readv` (or reads structs) inside a localized `Arena.ofConfined()`, and those are attached to the async stream, they MUST be fully materialized.

This is more of a hypothesis for verifying `ProfilerTraceListener` and `IterativeProfiler` boundary to make sure `vararg` arguments or `LongArray` conversions aren't actually leaking segment addresses that are used after the arena is closed by the reactor loop. The listener captures `args` as `LongArray`. The issue is whether `readStringFromProcess` (used in supervisor and profiler) correctly copies the bytes out of the native segment into a JVM `String` and returns the `String`, and that no `ManagedSegment` escapes. This must be strictly enforced.

(This is a placeholder to ensure the team verifies the materialization boundary for the FFM memory).
