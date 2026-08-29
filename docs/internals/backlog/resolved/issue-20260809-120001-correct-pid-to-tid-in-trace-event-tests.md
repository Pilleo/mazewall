---
title: "Correct PID to TID in TraceEvent test descriptions"
severity: "LOW"
status: "resolved"
priority: low
dependencies: []
component: "profiler"
target_modules:
  - ":profiler"
target_files:
  - "profiler/src/test/kotlin/io/mazewall/profiler/engine/TraceEventTest.kt"
effort: "small"
autonomy: "autonomous"
paperclip_issue_id: 456f59dd-822b-407a-9aa3-bbdad389c6b9
paperclip_identifier: MAZ-460
---

# 🟢 [Severity: LOW]: Correct PID to TID in TraceEvent test descriptions

**Context:** `TraceEventTest` names its behavioral-equality subject and assertion messages in terms of a process ID (PID), but `TraceEvent` carries a `Tid` and its equality contract deliberately ignores the thread ID. The typo makes the test describe a different identifier from the one exercised.

**Needed:** Rename the two test descriptions and their assertion messages from PID to TID/thread ID. Do not change the equality behavior. Run `./gradlew :profiler:test` to verify the terminology-only change.
