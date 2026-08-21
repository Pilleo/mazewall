---
title: "Profiler Trace Listener Unbounded Channel DoS / OOM Vulnerability"
severity: "HIGH"
status: "resolved"
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

# 🔴 [Severity: HIGH]: Profiler Trace Listener Unbounded Channel DoS / OOM Vulnerability

**Context:**
To decouple trace event capture from analysis and minimize seccomp notify loop latency, `ProfilerTraceListener` publishes events asynchronously through a Kotlin `Channel<TraceEvent>` and a background `collector` thread running `runBlocking`.
However, the `eventChannel` utilizes `Channel.UNLIMITED` capacity:
```kotlin
val eventChannel = Channel<TraceEvent>(Channel.UNLIMITED)
```
In a highly-concurrent, high-throughput JVM execution environment, a massive flood of intercepted system calls can generate trace events faster than the single-threaded collector is able to drain and process them (such as appending to synchronized lists, computing maps, or performing path cache checks). This unbounded channel allows trace events to accumulate infinitely in JVM heap memory, bypassing standard structured concurrency backpressure mechanisms and exposing the sandboxed JVM or profiler process to severe Denial of Service (DoS) and Out Of Memory (OOM) crashes.

**Needed:**
1. Refactor `ProfilerTraceListener` to utilize a bounded channel capacity (e.g. `Channel<TraceEvent>(1024)`) instead of `Channel.UNLIMITED`.
2. Introduce a proper, non-blocking backpressure strategy for queueing events: if the channel is full, log a warning or drop events gracefully under trace mode, or apply backpressure to preserve stability under heavy profiling workload scenarios.
3. Verify the changes using a stress/throughput unit test in `ProfilerTraceListenerTest.kt`.

**Resolution:** Duplicate of 131007. Same `TraceEventQueue` bound + drop-newest.
