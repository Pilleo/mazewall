---
title: "Investigate intermittent process-wide profiler integration capture failure"
severity: "MEDIUM"
status: "open"
priority: high
dependencies: []
component: "profiler"
target_modules: []
target_files:
  - "profiler/src/integrationTest/kotlin/io/mazewall/profiler/ProcessWideProfilerIntegrationTest.kt"
target_symbols:
  - "ProcessWideProfilerIntegrationTest"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Investigate intermittent process-wide profiler integration capture failure

**Context:**
An unchanged JVM merge-gate test missed the background /etc/hostname observation, with profiler ACK errno=32 and ENOSYS shutdown cleanup; focused and full retries passed without code changes during the agentless metadata fix.

**Needed:**
1. Reproduce with retained listener/daemon timing and child logs; establish whether shutdown ordering or the observation deadline causes the missed event before changing behavior.

Evidence: `./gradlew build` failed `:profiler:integrationTest` with the background
hostname assertion, listener ACK `errno=32`, and shutdown deletion reporting
`Function not implemented`. The unchanged focused command
`./gradlew :profiler:integrationTest --tests io.mazewall.profiler.api.ProcessWideProfilerIntegrationTest`
passed, followed by an unchanged successful `./gradlew build`. Root cause is not
established; no fix or deterministic timing diagnosis is claimed. Agentless Go
patches are outside this JVM test's dependency graph.

Recurrence during inline C3 plain-backend capture verification: full local Docker
`test integrationTest integrationTestFreshJvm` missed `/etc/hostname`, reported ACK
`errno=32`, and logged `Function not implemented` during shutdown cleanup. Retained
tool log: `run-4aa254ac4356`. An unchanged full Docker rerun (`run-4e95f01a89f1`)
passed in 46s. The matching symptom remains an open investigation; no change to
profiler behavior, assertions, deadlines, or skips was made for this recurrence.

---
<!-- id: issue-20261003-131111-752-investigate-intermittent-process-wide-profiler-int  file: issue-20261003-131111-752-investigate-intermittent-process-wide-profiler-int.md -->
