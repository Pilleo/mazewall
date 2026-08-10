---
title: "Replace timing sleeps in ProfilerTraceListenerTest with deterministic synchronization"
severity: "LOW"
status: "open"
priority: 8
dependencies: []
component: "profiler"
target_modules:
  - ":profiler"
target_files:
  - "profiler/src/test/kotlin/io/mazewall/profiler/internal/ProfilerTraceListenerTest.kt"
  - "profiler/src/main/kotlin/io/mazewall/profiler/internal/ProfilerTraceListener.kt"
effort: "small"
autonomy: "supervised"
---

# 🟢 [Severity: LOW]: Replace timing sleeps in ProfilerTraceListenerTest with deterministic synchronization

**Context:** `ProfilerTraceListenerTest` waits for worker cleanup with a fixed 100 ms sleep and polls asynchronous event collection in 10 ms increments. These assertions depend on scheduler timing, so a slow CI worker can fail despite correct behavior while every successful run incurs avoidable delay. The existing ready latch only proves startup, not worker termination or collector completion.

**Needed:** Expose or inject a package-internal deterministic completion signal for worker termination and event collection, without adding a dependency or changing the public API. Replace the fixed sleep and polling loop with bounded latch/future waits whose return values are asserted. Preserve the existing five-second JUnit timeouts and exact-once close assertions. Run the test repeatedly (for example, `./gradlew :profiler:test --tests '*ProfilerTraceListenerTest' --rerun-tasks` in a loop), followed by `./gradlew :profiler:check`.
