---
title: "Propagate profiler listener handshake failure before installation readiness"
severity: "MEDIUM"
status: "open"
priority: medium
dependencies: []
component: "profiler"
target_modules: [":profiler", ":enforcer"]
target_files:
  - "profiler/src/main/kotlin/io/mazewall/profiler/internal/ProfilerTraceListener.kt"
target_symbols:
  - "ProfilerTraceListener"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Propagate profiler listener handshake failure before installation readiness

**Context:**
Repository-wide review, 2026-11-04: [PN05-06](../reviews/2026-11-04-repository-review-findings.md#pn05-06). The source review and reproductions below are not release qualification or implementation approval. Historical resolved issues remain archived; this is their newly scoped residual where applicable.

**Category/confidence/boundary:** confirmed source initialization-protocol defect; high confidence; optional profiler setup / shared installer callback contract. No full runtime repro in this lane.

**Trigger and evidence:** daemon sends a non-0xAC initial byte or closes/errors before its initial ACK. `profiler/src/main/kotlin/io/mazewall/profiler/internal/ProfilerTraceListener.kt:341–353` rejects/throws but unconditionally counts down `readyLatch` in finally. `enforcer/src/main/kotlin/io/mazewall/ffi/networking/SupervisorSeccompNotifInstaller.kt:111–132` treats that latch alone as successful listener initialization and advances `Ready`. It does not receive the reader failure. `ProfilerSession.kt:107–110` records installation success, and `ProfilerService.kt:81–85` may execute an already-loaded compute/side-effect workload despite the failed collector. Optional TSYNC is also reached after the false readiness (`SupervisorSeccompNotifInstaller.kt:324–332`).

**Effect:** parser rejection is terminal for the reader but not for the installing caller. Setup failure is hidden until later native I/O or incomplete coverage; user work can run after failed setup, and process-wide synchronization can proceed with a dead listener. The final profile remains conservatively incomplete; this is not a false-complete finding.

**Smallest correction/check:** convey a typed success/failure with the initialization signal; only successful handshake may release Ready/TSYNC/user work. Preserve unconditional waiter wakeup on failure. Add a connected fake-stream installer test for invalid byte, EOF and native read failure, asserting the same failure reaches the caller, no workload runs and no TSYNC is attempted. Keep bounded handshake/irreversible-stage cleanup intact.

**Tracking/dependencies:** **NEW residual** beyond lifecycle-hardening plan Task 4 and resolved deterministic listener lifecycle. Existing rejection/invalid-TID tests only establish reader termination, not installer failure propagation. Coordinate with PN05-05 rather than introduce a public callback API or duplicate owner.

**Needed:**
1. Implement only the bounded correction described above, first reproducing its trigger with the stated negative and positive controls. Preserve all ownership, failure and compatibility invariants; run owning focused checks and the project completion gate before closure. Keep native, hosted and published-artifact qualification separate from source-only or isolated evidence.

---
<!-- id: issue-20261103-235247-666-propagate-profiler-listener-handshake-failure-befo  file: issue-20261103-235247-666-propagate-profiler-listener-handshake-failure-befo.md -->
