---
title: "Drain consumer command evidence before normal process cleanup closes streams"
severity: "MEDIUM"
status: "resolved"
priority: high
dependencies: []
component: "testing"
target_modules: []
target_files:
  - "src/consumerVerification/kotlin/io/mazewall/verification/ConsumerProcessEvidence.kt"
  - "src/consumerVerification/kotlin/io/mazewall/verification/ConsumerProcessEvidenceTest.kt"
target_symbols: []
open_questions: false
needs_kernel: false
core_lock: true
effort: "small"
autonomy: "supervised"
has_side_effects: true
verify_cheap:
  - "./gradlew consumerHarnessTest --tests '*ConsumerProcessEvidenceTest' --console=plain --max-workers=1"
  - "./gradlew consumerHarnessTest --console=plain --max-workers=1"
---

# 🟡 [Severity: MEDIUM]: Drain consumer command evidence before normal process cleanup closes streams

**Context:**
PR548 same-head warm hosted attempt2 passed core and mutation memory qualification, but SpringBootPackagedAcceptanceTest failed at sourceIdentity line36 with ExecutionException caused by IOException at BufferedInputStream.java207. SourceIdentity uses short Git commands through ConsumerProcessEvidence. The shared harness awaits child completion using IsolatedProcessTester, whose cleanup always destroys/closes process streams before consumer finally joins asynchronous pumps. Reproduce delayed-pump/fast-exit race deterministically and drain normal-exit streams before closure without weakening timeout cleanup, primary failure preservation, evidence ownership or physical retirement. Do not retry blindly, swallow IOException or change Spring/runtime containment.

**Needed:**
1. Write an assertion-level deterministic RED for delayed output pumping after a fast child exits, preserving exact stdout/stderr and exit status.
2. Fix normal-completion drain ordering once in shared consumer harness; retain timeout/interrupt cleanup and all artifact/source ownership checks.
3. Run focused consumer tests, complete local gate and independent review, then requalify hosted native acceptance before merging548.

## Local implementation evidence

`2fd1ff5b` reproduces both fast-exit/delayed-reader stream-closed failures before
joining normal-completion pumps inside the unchanged lifecycle callback. Exact
stdout/stderr/combined bytes, zero/nonzero status, receipts and retirement are
asserted. The per-call pump seam remains internal to the test harness with the
original asynchronous default; no library/runtime API or native assertions change.

Review found interruption loss in final pump cleanup. `d1610d21` adds a deterministic
barrier-controlled RED/GREEN regression and restores the interrupt after bounded
cleanup. Focused7/full21 portable consumer tests and targeted ktlint pass; scoped
re-review marks the finding addressed, spec/quality PASS.

Complete local optional build with the accompanying test-fixture correction
`971053ab` passes271 tasks in4m30s. Hosted final-code acceptance is still pending;
the original warm failure is not treated as a pass or bypassed with a retry.

## Partial pump-acquisition follow-up — 2026-11-05

[Recursive review F-03](../../reviews/2026-11-05-state-complexity-review.md):
`ConsumerProcessEvidence.kt:91` assigns `pumps` only after both asynchronous copy
startups return. If stdout returns a live future and stderr startup throws, the
stdout owner is absent from the final join ledger at `:121`; diagnostic reads and
channel closure can race that pump. The shared process tester still owns/reaps the
child and propagates startup failure. This is not a newly proven orphaned child
or a false successful command, and does not undo the accepted normal-exit fixes.

Record each returned future in the local owned ledger immediately, before
starting another pump. No sealed command framework, new seam or public API is
needed. Preserve original failure, bounded cleanup, suppressed drain evidence,
child retirement and restored interruption.

Use the existing `startPump` seam: first startup returns a barrier-held real
reader/future, second throws a unique failure. Require cleanup to observe the
first handle before channels close; release/join deterministically and retain the
startup failure and any drain failure. Include first-fails/both-succeed and
interrupted/failed partial join controls. Preserve exact-byte, delayed-reader,
timeout and existing normal-completion checks. Do not exhaust common-pool threads.
Run the existing cheap checks and required implementation completion gate; this
review ran no pump failure reproduction or hosted qualification.

**Resolution**

Register each acquired pump immediately. The new regression showed that the original implementation performed zero joins after the second startup failed. On JDK 25, the focused consumer harness now joins the stdout future once, preserves the original startup failure and its suppressed drain failure, and confirms the child was reaped.

---
<!-- id: issue-20261020-083727-843-drain-consumer-command-evidence-before-normal-proc  file: issue-20261020-083727-843-drain-consumer-command-evidence-before-normal-proc.md -->
