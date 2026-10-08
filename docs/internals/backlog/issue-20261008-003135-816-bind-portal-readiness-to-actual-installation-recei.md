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

**Round1 independent review corrections (I1–I3, M1; base d1c24309):**
- I1 supersedes the earlier shutdown-hook retirement claim: genuine unsupervised baseline receipts remain statically strongly rooted for the complete OS process lifetime. No hook close/clear; OS death is not Java retirement or supervised-lease proof. Real disposable native child holds guest shutdown work while a non-daemon guest executes, checks the actual receipt identity remains retained, and is physically reaped by the parent.
- I2 cached files and the exact classpath are checked against complete per-entry content of the unchanged verified ZIP, both on cache reuse and before generation capture. Replaced/missing/extra entries reject. Regression also compiles a different valid service implementation with identical actual manifest/schema and leaves original ZIP/digest/marker unchanged; zero child launches after replacement rejection. This stable association gap is fixed independently of deferred concurrent C4 pinning.
- I3 acquisition deadline/active authority covers host measurement. Finite text/count/depth/entry/byte bounds and interruptible FileChannel reads run in an explicitly registered physical capture owner. Timeout/cancel results do not certify physical I/O completion; non-interruptible work remains owned and prevents Closed until retry. Only startup caller launches after a final authority check; late capture completion has no child-launch effect. Tests cover expiry/cancel during blocked capture, actual digest/traversal cancellation, inactive entry, bounds and cleanup ownership.
- M1 new receipt finalization uses existing fatal-first identity-preserving CleanupFailures.merge for startup and execution, with ordinary-primary/fatal-retirement regression and existing inverse coverage.
- Targeted red: run-3de9063379a1. Final full focused owning/native/Detekt/ratchet gate: run-faaf52845a65. Additional real-cache-generation plus retained prepared-operation fixture gate: run-282446aabf19. Final revised commit/range/counts/full-hook evidence are in the task-1 report round1 appendix. Backlog index remains coordinator-owned.
- I3 cancellation self-review: FileChannel native close can synchronously wait on I/O inside Thread.interrupt. A real AbstractInterruptibleChannel held-close test failed by startup-result timeout (run-6cbe421fb042). Cancellation callbacks now have separately registered asynchronous physical ownership; both source and cancellation threads must actually terminate/join before the owner retires. This closes the control-plane blocking-cancel gap without asserting that one completion latch is thread/process death.

---
<!-- id: issue-20261008-003135-816-bind-portal-readiness-to-actual-installation-recei  file: issue-20261008-003135-816-bind-portal-readiness-to-actual-installation-recei.md -->
