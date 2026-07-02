---
title: "Asynchronous Trace Event Streaming via `Channel` / `Flow`"
severity: "ENHANCEMENT"
status: "open"
---

# 🔵 [Severity: ENHANCEMENT]: Asynchronous Trace Event Streaming via `Channel` / `Flow`

**Target:** `io.mazewall.profiler.Profiler` and `ProfilerTraceListener`
**Context:** Captured trace events and stack traces are currently collected using `CopyOnWriteArrayList` and `ConcurrentHashMap`. The listener thread synchronously updates these collections, which can introduce latency in the "ACK loop" and increase the risk of deadlocks if the collections block.
**Needed:** Use Kotlin `Channel` or `Flow` to stream events.
1. The `ProfilerTraceListener` should send `TraceEvent` objects into a `Channel`.
2. The `BobCompiler` (or a background collector) can consume these events asynchronously.
3. This reduces the time spent by the listener thread in the critical section of the seccomp notify loop, improving profiling performance and decoupling event capture from analysis.
