---
title: "Bound All Blocking Waits (Task Join, Daemon Shutdown Handshake)"
severity: "LOW"
status: "resolved"
priority: medium
component: "profiler"
target_modules:
  - ":profiler"
  - ":enforcer"
target_files:
  - "profiler/src/main/kotlin/io/mazewall/profiler/iterative/IterativeTaskExecutor.kt"
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/supervisor/SupervisorDaemonManager.kt"
effort: "small"
autonomy: "autonomous"
open_questions: false
dependencies: []
---

# 🟡 [Severity: LOW]: Bound All Blocking Waits

**Context:** Two unbounded/sleep-based waits remain after the triage-runner fix
(issue-20260823-143500 established the bounded-capture precedent):

1. `RealIterativeTaskExecutor.executeTask` calls `thread.join()` with no timeout; a workload that
   deadlocks under containment hangs profiling forever (the very scenario iterative profiling
   exists to debug).
2. `SupervisorDaemonManager.triggerDaemonShutdown` uses `Thread.sleep(SHUTDOWN_WAIT_MS=100)` and
   swallows all exceptions — shutdown success is unobservable and timing-dependent.

**Resolution (2026-08-23):** `RealIterativeTaskExecutor` gained `iterationTimeoutMs` (default 120s): bounded join → interrupt → 5s grace → hard check (`Thread.stop()` rejected: unusable on modern JDKs); timeout surfaces as distinct `IterativeTaskTimeoutException`. Both daemon shutdown paths replaced fixed sleeps with command-write + bounded liveness poll; exceptions logged instead of swallowed. Profiler worker join bounded at 60s with interrupt escalation. Remaining joins/sleeps are intentional EINTR backoffs / workload stress, documented.

**Needed:**
1. Replace `join()` with `join(timeout)` + `interrupt()` + documented escalation; surface timeout as
   a distinct profiler failure mode (not swallowed into generic error).
2. Replace daemon-shutdown sleep with an observable condition (socket close ACK from daemon, or
   process-liveness poll loop) while keeping the best-effort contract; keep exceptions logged, not
   ignored.
3. Sweep for remaining unbounded joins/waits in enforcer/profiler mains (`grep -rn "\.join()"`,
   `sleep(`) and either bound or justify each with a comment referencing this issue.

