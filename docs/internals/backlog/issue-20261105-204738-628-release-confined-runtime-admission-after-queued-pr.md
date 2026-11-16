---
title: "Release confined runtime admission after queued preparation cancellation"
severity: "MEDIUM"
status: "open"
priority: high
dependencies: []
component: "core"
target_modules: ["enforcer"]
target_files:
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/api/execution/ManagedRuntime.kt"
target_symbols:
  - "ManagedRuntime"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Release confined runtime admission after queued preparation cancellation

**Context:**
D2 runtime-cancelled-queue-red-001 reproduces cancelled-before-start preparation skipping the Callable finally: actual drain remains Incomplete with one live manager and no owned domain after the queue empties.

**Needed:**
1. Move admission release to the original queued task run boundary so cancellation before Callable execution still settles it, then verify repeated actual drain and in-flight cancellation without replacement owners.

## Verification in progress

Compiling RED001 observed Incomplete(liveWorkers=1, cleanupFailure=null) instead of
Complete after cancellation before Callable execution. Admission release now occurs
in the original queued FutureTask.run finally, including cancelled tasks. It stays
counted until the same manager actually passes the task. Runtime-races GREEN001
passed47/47 cases, including seven actual-runtime cases, and the connected228-case
selection passed without skips/errors/failures. Independent D2 review and the
combined whole-B4 gate remain required before resolution.

## Candidate verification

`runtime-cancelled-queue-red-001` reproduced the cancelled-queue admission leak; `runtime-races-green-static-001` passed 47/47, including the cancellation control.

Candidate gates in `b4-d2-implementation-001`: connected enforcer 268/268, platform 474/474 and profiler 32/32, with no skips or failures; static004 ratchet reported zero added warnings and no baseline growth. Rootful007 passed 8/8 and successor helper-regression-rootful001 passed 18/18; both postflight input manifests matched (1,829 checks each). Negative cases with injected effects are controlled tests, not proof of naturally occurring faults. Whole-B4 build002 was pending in the ledger; this issue remains open for controller settlement.

---
<!-- id: issue-20261105-204738-628-release-confined-runtime-admission-after-queued-pr  file: issue-20261105-204738-628-release-confined-runtime-admission-after-queued-pr.md -->
