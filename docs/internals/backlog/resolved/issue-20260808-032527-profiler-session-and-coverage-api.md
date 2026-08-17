---
title: "Introduce Isolated Profiler Sessions and Structured Coverage"
severity: "MEDIUM"
status: resolved
priority: 9
dependencies: []
component: "profiler"
target_modules:
  - ":profiler"
target_files:
  - "profiler/src/main/kotlin/io/mazewall/profiler/Profiler.kt"
  - "profiler/src/main/kotlin/io/mazewall/profiler/ProfilingResult.kt"
  - "profiler/src/main/kotlin/io/mazewall/profiler/iterative/IterativeProfiler.kt"
  - "profiler/src/main/kotlin/io/mazewall/profiler/BillOfBehavior.kt"
effort: "large"
autonomy: "supervised"
---

# 🟡 [Severity: MEDIUM]: Introduce Isolated Profiler Sessions and Structured Coverage

**Context:** Profiling uses global daemon/listener and iterative-profiler configuration, exposes mutable recent logs, and divides strategies across separate APIs. Results do not make scope, io_uring visibility, child/background-thread coverage, event loss, stack attribution or incomplete draining prominent. This makes the concise `profile` call easier to use than its evidence is to interpret safely.

**Needed:** Add an owned `MazewallProfiler` session API with `ProfileStrategy.AUTO` and immutable snapshots. Extend results with structured coverage, selected-strategy rationale, environment tuple, warnings, dropped events and completion state. Permit partial results only through explicit options and prohibit direct enforcement-policy generation from incomplete results without a deliberate override. Add concurrent-session, repeated-test, daemon-failure and incomplete-drain tests.
