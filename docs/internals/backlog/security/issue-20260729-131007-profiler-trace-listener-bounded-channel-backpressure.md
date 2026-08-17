---
title: "Refactor Profiler Trace Listener to Use Bounded Channel Capacity and Backpressure Strategy"
severity: "HIGH"
status: "open"
priority: high
dependencies: []
component: "profiler"
target_modules:
  - ":profiler"
target_files:
  - "profiler/src/main/kotlin/io/mazewall/profiler/internal/ProfilerTraceListener.kt"
effort: "medium"
autonomy: "autonomous"
---

# 🔴 [Severity: HIGH]: Refactor Profiler Trace Listener to Use Bounded Channel Capacity and Backpressure Strategy

**Context:**
To decouple trace event reception from stack-trace/symbol analysis and minimize seccomp notify latency, `ProfilerTraceListener` utilizes a background thread to read events from the socket and write them asynchronously to a Kotlin `Channel<TraceEvent>`:
```kotlin
val eventChannel = Channel<TraceEvent>(Channel.UNLIMITED)
```
A second background collector thread drains this channel and appends events to lists and maps.

**The Vulnerability:**
The use of `Channel.UNLIMITED` bypasses all backpressure mechanics. If a multi-threaded sandboxed application performs highly concurrent, fast-loop system calls (e.g. `openat` or `stat` inside tight loops), the listener thread receives events faster than the collector can process them (due to locks or GC pauses). This causes memory consumption inside the JVM heap to grow without bounds, leading to severe Out Of Memory (OOM) errors and application-wide crashes (DoS).

**Needed:**
1. Refactor `ProfilerTraceListener`'s `eventChannel` to use a bounded channel capacity (such as a default of 1024 elements).
2. Configure a clean, non-blocking backpressure/buffer-overflow strategy. When the buffer is full, either:
   - For diagnostic trace modes: block the writing thread slightly to apply backpressure (which safely slows down the tracee thread in kernel space).
   - For telemetry/production telemetry: drop oldest/latest events and log warning flags to prevent JVM heap exhaustion.
3. Write a stress/concurrency unit test inside `ProfilerTraceListenerTest.kt` verifying that high-throughput event spikes do not cause memory leaks or unbounded allocations, and that backpressure or drop strategies behave deterministically.
