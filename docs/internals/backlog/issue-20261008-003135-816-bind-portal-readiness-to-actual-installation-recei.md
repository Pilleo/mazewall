---
title: "Bind portal readiness to actual installation receipt and launch generation"
severity: "HIGH"
status: "open"
priority: high
dependencies: []
component: "portals"
target_modules: [":portals:runtime", ":portals:worker"]
target_files:
  - "portals/runtime/src/main/kotlin/io/mazewall/portal/ProcessBroker.kt"
target_symbols:
  - "ProcessBroker"
open_questions: false
---

# 🟡 [Severity: HIGH]: Bind portal readiness to actual installation receipt and launch generation

**Context:**
Parent permit and ACK precede containment, but final readiness uses a stdout sentinel and discards the actual required worker installation receipt.

**Needed:**
1. Require a strict bounded socket ready record from independently measured worker inputs after actual required process-wide receipt validation and dispatcher construction; retain receipt through physical execution end.

**Implementation evidence (fleet/20261008-portal):**
- Bounded canonical format-1 ready control record, exact session/PID and input/manifest/runtime generation; host no longer admits workers from stdout sentinel.
- Child validates independently measured requested generation before containment/guest; group metadata is required rather than missing-manifest fallback.
- Actual receipt validates required newly applied process-wide Seccomp/filesystem stages and static policy before dispatcher construction; process shutdown owner retains it beyond loop/main return and startup failure until actual JVM shutdown, including guest non-daemon thread lifetimes. Synchronous driver embeddings retire at their execution end.
- Meaningful incomplete-receipt red observed against base; focused owning and disposable-worker tests, including P2 cancellation/end coverage, passed during implementation. Final revision and logs are recorded in the task-1 fleet report.
- Final focused gate: 127 runtime unit tests, 45 worker unit tests and 29 named actual-worker integration tests; zero failures/errors/skips. Both module `detektMain` and root `detektWarningRatchet` passed. Tool logs: `/home/leanid/.local/state/agent-output/run-395a1399ba95/`; XML/count/scope verification: `run-4612bfa47513`. No full build or Docker gate was run; coordinator owns those combined acceptance checks.
- Operator launch/environment profiles and C4 physical-object pinning are separate unclosed P3 gates, outside this issue slice. Coordinator owns backlog index registration/archival after review.
- Physical-process follow-up: main-return-retirement mutation made both new actual-receipt lifecycle tests fail (run-9570d6ae5c3b); retaining the exact receipt until shutdown passed 127 runtime / 47 worker / 29 integration tests, both module Detekt analyses and warning ratchet (run-367d619cea58), zero owning skips/failures/errors.

---
<!-- id: issue-20261008-003135-816-bind-portal-readiness-to-actual-installation-recei  file: issue-20261008-003135-816-bind-portal-readiness-to-actual-installation-recei.md -->
