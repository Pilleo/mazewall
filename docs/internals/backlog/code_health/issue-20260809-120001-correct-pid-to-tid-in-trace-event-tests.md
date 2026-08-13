---
title: "Correct PID to TID in TraceEvent test descriptions"
severity: "LOW"
status: "open"
priority: 2
dependencies: []
component: "profiler"
target_modules:
  - ":profiler"
target_files:
  - "profiler/src/test/kotlin/io/mazewall/profiler/engine/TraceEventTest.kt"
effort: "small"
autonomy: "autonomous"
---

# 🟢 [Severity: LOW]: Correct PID to TID in TraceEvent test descriptions

**Context:** `TraceEventTest` names its behavioral-equality subject and assertion messages in terms of a process ID (PID), but `TraceEvent` carries a `Tid` and its equality contract deliberately ignores the thread ID. The typo makes the test describe a different identifier from the one exercised.

**Needed:** Rename the two test descriptions and their assertion messages from PID to TID/thread ID. Do not change the equality behavior. Run `./gradlew :profiler:test` to verify the terminology-only change.
