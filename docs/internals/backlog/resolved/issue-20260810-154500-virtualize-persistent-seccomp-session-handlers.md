---
title: "Remove fixed-pool limit from persistent seccomp sessions"
severity: "MEDIUM"
status: "resolved"
priority: 7
dependencies: []
component: "profiler"
target_modules:
  - ":platform"
  - ":profiler"
target_files:
  - "platform/src/main/kotlin/io/mazewall/platform/seccomp/daemon/SeccompDaemonEngine.kt"
  - "profiler/src/test/kotlin/io/mazewall/profiler/engine/ProfilerDaemonTest.kt"
effort: "small"
autonomy: "autonomous"
---

# 🟠 [Severity: MEDIUM]: Remove fixed-pool limit from persistent seccomp sessions

**Context:** Each long-lived profiler session occupied one of 200 fixed platform-thread workers. The daemon rejected later healthy sessions based only on that implementation limit. Creating an unbounded platform thread per session removes the fixed ceiling but exhausts native threads in memory-constrained integration containers. Profiler daemon sessions can instead use virtual threads safely because this out-of-process observer never installs seccomp filters on its handler threads.

**Needed:** Keep the shared daemon engine’s bounded platform-thread default for enforcement, inject a virtual-thread-per-task executor only from the profiler daemon, disable the arbitrary connection limit only for profiling, and cover more than 200 virtual handlers with an automated regression test. **Resolved:** Profiling now starts more than 200 virtual handlers without allocating a platform thread per persistent session.
