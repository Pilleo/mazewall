---
title: "Keep autonomous HotSpot deadline enforcement active through finishing"
severity: "MEDIUM"
status: "resolved"
priority: medium
dependencies: []
component: "profiler"
target_modules: [":profiler"]
target_files:
  - "profiler/src/main/kotlin/io/mazewall/profiler/internal/hotspot/HotSpotSessionDriver.kt"
target_symbols:
  - "timerActive"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Keep autonomous HotSpot deadline enforcement active through finishing

**Context:**
Repository-wide review, 2026-11-04: [HS09-04](../../reviews/2026-11-04-repository-review-findings.md#hs09-04). The source review and reproductions below are not release qualification or implementation approval. Historical resolved issues remain archived; this is their newly scoped residual where applicable.

**P2 · lifecycle/resource bound · reproduced, high confidence · optional Kotlin host supervision. NEW.**

- **Trigger/evidence:** The timer loops only while `callbacks.timerActive()` (`HotSpotCollectorWorkers.kt:50–55`). `HotSpotSessionDriver.kt:51–55` returns false for `Finishing`. At automatic session expiry, a Tick changes Running to Finishing and requests stop (`HotSpotSessionMachine.kt:96–105,76–85`), then the timer exits. The next tick would fail the expired stop deadline and issue Abort (`25–39,110–120`), but none occurs without later caller activity or another child callback.
- **Reproduction:** Exact current worker/driver/process code with a synthetic Process (no OS child or native resources) emits valid start/ready, accepts `stop\n` and otherwise remains silent/alive. With a 1s session and 100ms stop bound, after 1.25s the snapshot is `Finishing`, child alive, Abort never called, ownership pending. An explicit later close immediately drives failure/abort and retires it. See retained log.
- **Effect:** A collector that hangs during automatic stop remains alive indefinitely while the profiling workload/caller continues, despite the advertised finite session bound. Requiring callers eventually to close sessions does not enforce that bound while they are still running their workload.
- **Smallest complete fix:** Keep autonomous deadline pulses active through Finishing so timeout invokes the existing bounded abort path even when the caller has not entered finish/close. Preserve failed outcome and truthful retained ownership; do not claim failed/blocked pipes retired or silently turn automatic expiry into success.
- **Regression/qualification:** After ready, advance past automatic session/stop expiry without calling finish, snapshot-driven pulses or explicit close; require Abort within the fixed bound. Then explicitly close/retry to check actual join/close ownership and original failure preservation. Existing stop-deadline tests all call finish and miss this case (`HotSpotCollectorProcessTest.kt:52–64`).
- **Mapping/dependencies:** NEW regression gap against resolved supervision milestone `issue-20261004-145136-992...` and its stated finite timer. No change to native stop budgets, wrappers or ownership semantics is needed.

**Needed:**
1. Implement only the bounded correction described above, first reproducing its trigger with the stated negative and positive controls. Preserve all ownership, failure and compatibility invariants; run owning focused checks and the project completion gate before closure. Keep native, hosted and published-artifact qualification separate from source-only or isolated evidence.

**Resolution**

A real JDK 25 fixture reproduced a collector staying alive after automatic session expiry. Deadline pulses now continue until the process and both reader workers report completion, or until the session fails. The timer then relinquishes its lease so checked stream retirement can finish. The regression observed autonomous process exit before any caller cleanup, then verified the retained deadline failure by identity and successful release of the remaining owners. The full HotSpot test group, Profiler lint and the Detekt warning ratchet passed.

---
<!-- id: issue-20261103-235251-822-keep-autonomous-hotspot-deadline-enforcement-activ  file: issue-20261103-235251-822-keep-autonomous-hotspot-deadline-enforcement-activ.md -->
