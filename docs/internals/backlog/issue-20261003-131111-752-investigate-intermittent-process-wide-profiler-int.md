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

Recurrence during current-installation verifier evidence correction: full serial
Docker gate failed the process-wide profiler subprocess and logged listener ACK
`errno=32` (`run-64e6eb2197cd`). The unchanged full serial rerun passed in 65s
(`run-21b6e1f2382c`). Root cause remains unproven; no profiler behavior, assertion,
timeout or skip was changed.

Recurrence during owned-runtime C7 acceptance: serial full Docker run
`run-2bed8968d1cb` failed the same process-wide profiler subprocess with ACK
`errno=32`; unchanged full rerun `run-48eca4d8ffb5` passed in 53s. Earlier C7
Docker run `run-527b0e17a1b2` also passed. No profiler fix or timing diagnosis is
claimed and no assertion/timeout/skip was changed.

Recurrence retained during the 2026-10-18 HotSpot follow-up documentation gate:
`.superpowers/sdd/2026-10-17-hotspot-production-loss-binding/task4-evidence/commit-attempt-1.full-hook.log`
has three socket-deletion `FileSystemException: Function not implemented` stacks
at **752/788/823**, with suppressed directory-deletion failures at **773/809/844**.
The pre-follow-up baseline
`.superpowers/sdd/2026-10-17-hotspot-production-loss-binding/final-fix-full-build-hook.log`
already has the same three socket failures at **751/787/822**, with suppressed
directories at **772/808/843**. Both gates passed; zero JUnit failures do not prove
successful endpoint retirement.

The paths are `/tmp/mazewall-profiler-…/profiler.sock`, owned by the USER_NOTIF
`ProfilerDaemonManager`, with the call chain `RealProcessLauncher.deleteIfExists`
→ `PrivateUnixEndpoint.close` → `ChildProcessResources.close` →
`ChildStartupTransaction`'s `mazewall-child-shutdown` hook. These owners are
unchanged across the HotSpot follow-up. Endpoint close propagates deletion errors;
endpointClosed/retired are set only after successful cleanup. The hook-thread
exception therefore records **real inherited endpoint-retirement failure** and
possible leftover paths, not HotSpot owner success or an expected negative fixture.
The log alone does not inventory every retired child/resource or prove current
path absence. Root cause, including any relationship to ACK timing, remains
unproved. Keep this issue open; no reproduction, cleanup action, syscall allowance,
error suppression or runtime repair is part of this documentation correction.

---
<!-- id: issue-20261003-131111-752-investigate-intermittent-process-wide-profiler-int  file: issue-20261003-131111-752-investigate-intermittent-process-wide-profiler-int.md -->
