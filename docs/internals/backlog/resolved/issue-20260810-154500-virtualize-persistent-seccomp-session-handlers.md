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

**Context:** Each long-lived profiler session occupied one of 200 fixed platform-thread workers. The daemon rejected later healthy sessions based only on that implementation limit. An elastic platform-thread executor removes the fixed worker ceiling. Virtual threads are prohibited by the production architecture rule and blocking FFM downcalls may pin their carriers; neither thread-per-session approach is equivalent to a multiplexed `epoll` reactor.

**Needed:** Replace the fixed pool with an elastic daemon-thread executor, remove the arbitrary active-session rejection, cover more than 200 concurrent handlers with an automated regression test, and document why virtual threads are not used. **Resolved:** The executor now starts more than 200 concurrent handlers and the daemon no longer rejects the 201st active session.
