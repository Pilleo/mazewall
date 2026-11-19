---
title: "Keep published profiler events awaiting ACK during pass-through requests"
severity: "MEDIUM"
status: "open"
priority: medium
dependencies: []
component: "profiler"
target_modules: [":profiler"]
target_files:
  - "profiler/src/main/kotlin/io/mazewall/profiler/internal/engine/ProfilerAckOutcome.kt"
target_symbols:
  - "ProfilerAckOutcome"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Keep published profiler events awaiting ACK during pass-through requests

**Context:**
Repository-wide review, 2026-11-04: [PN05-04](../reviews/2026-11-04-repository-review-findings.md#pn05-04). The source review and reproductions below are not release qualification or implementation approval. Historical resolved issues remain archived; this is their newly scoped residual where applicable.

**Category/confidence/boundary:** confirmed protocol ordering defect; high confidence in ordering, native symptom unqualified; optional profiler, especially wrapped/live process-wide sessions.

**Trigger and evidence:** close a session while a complete event frame is in flight but its reader has not yet finished capture/queueing or sent 0xAC. `profiler/src/main/kotlin/io/mazewall/profiler/internal/ProfilerTraceListener.kt:239–245` immediately sends 0x54 from the closing thread. `profiler/src/main/kotlin/io/mazewall/profiler/internal/engine/ProfilerAckOutcome.kt:25–28` returns `PassThrough` for 0x54 **without requiring ACK**. `profiler/src/main/kotlin/io/mazewall/profiler/internal/engine/ProfilerSessionHandler.kt:185–194` then offers CONTINUE and `platform/src/main/kotlin/io/mazewall/platform/seccomp/daemon/SeccompSessionHandler.kt:243–247` writes the normal pass-through terminal record and closes the control socket.

**Effect:** a published observation's tracee can resume before its required stack/capture ACK. The reader's later legitimate ACK can hit the already-closed endpoint and mark a graceful shutdown incomplete. This violates the ordinary ACK-before-CONTINUE contract for accepted published events. It is a concrete race schedule, **not** an attribution of historical ACK errno32 to this cause. The one terminal kernel reply lease itself is still respected.

**Smallest correction/check:** latch a pending pass-through request, but keep the current event awaiting its one ACK under the original deadline; only then CONTINUE and enter pass-through. An explicitly aborted observation must not be certified as a normal drain. Add a deterministic reactor test delivering 0x54 before 0xAC: assert no reply/terminal until ACK, then exactly one CONTINUE, one terminal and no further publication. Qualify close-during-event with an owned native fixture.

**Tracking/dependencies:** **NEW residual** adjacent to resolved `issue-20260810-defer-profiler-shutdown-until-notification-continue.md` (SHUTDOWN/CONTINUE callback order) and `issue-20261015-172121-857-preserve-truthful-profiler-completion-across-termi.md` (terminal evidence). No dedicated pass-through-before-ACK item found. Must retain terminal framing, no-double-reply and late-completion checks while changing the reducer.

**Needed:**
1. Implement only the bounded correction described above, first reproducing its trigger with the stated negative and positive controls. Preserve all ownership, failure and compatibility invariants; run owning focused checks and the project completion gate before closure. Keep native, hosted and published-artifact qualification separate from source-only or isolated evidence.

---
<!-- id: issue-20261103-235247-255-keep-published-profiler-events-awaiting-ack-during  file: issue-20261103-235247-255-keep-published-profiler-events-awaiting-ack-during.md -->
