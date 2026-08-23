---
title: "Verify SECCOMP_RET_KILL_* Verdicts in Forked Children, Not the Test JVM"
severity: "LOW"
status: "resolved"
priority: medium
component: "testing"
target_modules:
  - ":enforcer"
target_files:
  - "enforcer/src/integrationTest/kotlin/io/mazewall/seccomp"
effort: "small"
autonomy: "supervised"
open_questions: false
dependencies:
  - "issue-20260823-171500"
---

# 🟡 [Severity: LOW]: Verify SECCOMP_RET_KILL_* Verdicts in Forked Children, Not the Test JVM

**Context:** The differential kernel-vs-simulator suite
(issue-20260823-171500) needs to probe policies whose predicted verdict is `SECCOMP_RET_KILL_THREAD`
or `SECCOMP_RET_KILL_PROCESS`. Executing such a probe on the test JVM's own thread kills the worker
or the whole executor — exactly the failure mode that made issue-20260823-140500 look like infra
flakiness instead of a deterministic signal.

**Resolution (2026-08-23):** Investigated; decision made by evidence.
- Raw POSIX `fork()` inside the test JVM is REJECTED: a forked child of a multithreaded JVM may
  only execute async-signal-safe code until exec; installing filters / running probes before exec
  is undefined behavior with FFM arenas and JIT compiler threads.
- A dedicated native fork+exec harness was rejected: build complexity with no benefit over the
  existing mechanism.
- CHOSEN: fresh child JVM via the existing `IsolatedProcessTester` pattern (already hardened
  against inherited JVMCI env). Implemented in `SeccompDifferentialVerdictTest.kill_thread archetype`:
  the child (`SeccompKillProbeChild`) installs a KILL_THREAD filter, runs a liveness probe, then
  triggers the killed syscall; the parent asserts the child died before printing PROBE_OK.
- `@NeedsFreshJvm` tagging is NOT required for kill probes themselves (each probe is already its
  own process); it IS required for in-JVM archetypes that permanently restrict worker threads.

**Needed:**
1. For KILL_* probes, fork a child process (POSIX fork via FFM or a spawned helper JVM), have it
   install the filter and execute the probe syscall; assert from the parent on waitpid status being
   `SIGSYS` (and correct `si_code`: `CLD_KILLED` vs `SYS_SECCOMP` details where available).
2. Prefer reusing an existing spawn helper (e.g. the `IsolatedProcessTester` pattern) over adding a
   new mechanism.
3. Keep probe latency bounded: per-child timeout with `destroyForcibly` fallback so a broken filter
   cannot hang the suite.

