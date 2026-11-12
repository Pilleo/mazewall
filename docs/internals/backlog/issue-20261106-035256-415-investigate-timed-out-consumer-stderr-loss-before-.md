---
title: "Investigate timed-out consumer stderr loss before evidence drain"
severity: "HIGH"
status: "open"
priority: high
dependencies: []
component: "core"
target_modules: []
target_files:
  - "src/consumerVerification/kotlin/io/mazewall/verification/ConsumerProcessEvidence.kt"
  - "src/consumerVerification/kotlin/io/mazewall/verification/ConsumerProcessEvidenceTest.kt"
  - "src/sharedTest/kotlin/io/mazewall/IsolatedProcessTester.kt"
  - "src/test/kotlin/io/mazewall/IsolatedProcessTesterLifecycleTest.kt"
target_symbols:
  - "ConsumerProcessEvidence"
open_questions: false
---

# 🟡 [Severity: HIGH]: Investigate timed-out consumer stderr loss before evidence drain

**Context:**
Isolated full-gate006 executes47 consumer cases and fails timeout evidence test: expected partial stderr newline, actual empty. Stdout, timedOut/reaped flags and transition order passed. Process.destroy may close pipes before delayed pumps drain, but exact failed-child progress is unknown. Root failure outside D2 source scope; preserve failed XML and do not retry-to-green.

**Needed:**
1. Authorize narrow test-harness scope, reproduce with real child and bounded delayed-pump barriers, distinguish child progress from pre-drain stream closure, then apply smallest verified ownership correction without increasing timeouts.

## Authorized investigation

The operator approved the narrow harness correction. A real child writes stdout
and stderr before atomically publishing a readiness marker. Readers run only at
the post-retirement pump join. The compiling regression failed with empty stdout
despite confirmed parent/descendant exit and an unrelated process remaining alive.
Evidence: `consumer-timeout-fix-001/timeout-delayed-readers-red-001*` in the D2 packet.

Local JDK 25 source confirms `Process.destroy()` closes its streams after signalling;
the acquired process handle can signal without closing those streams. The private
shared lifecycle implementation is the correction boundary. Original gate 006
child progress remains unknown; this controlled regression establishes the closure
defect independently.

The private JDK lifecycle now signals the acquired process handle for both graceful
and forced retirement. Delayed-reader cases cover both, retain exact separate
stdout/stderr, reject suppressed pump failures, and verify physical tree retirement
and unrelated-process survival. Reverting only the forced signal reproduced empty
stdout; restoring the correction passed. The interruption regression now verifies
retained streams rather than expecting an artificial closed-pipe failure.

Focused verification passed 49 consumer harness tests and 50 root tests (one
existing root skip), with zero failures/errors; consumer/shared source-set formatting
and root test static analysis passed. Evidence is in `consumer-timeout-fix-001/`.
Later isolated builds reused the successful49-case consumer harness results; the
one-worker012 gate freshly passed all10 consumer build-logic cases, then failed
independent enforcer integration blockers. No complete build or issue closure is
claimed from these focused/reused results.

---
<!-- id: issue-20261106-035256-415-investigate-timed-out-consumer-stderr-loss-before-  file: issue-20261106-035256-415-investigate-timed-out-consumer-stderr-loss-before-.md -->
