---
title: "Make the KILL_THREAD kernel oracle reject survival and setup failures"
severity: "MEDIUM"
status: "open"
priority: medium
dependencies: []
component: "core"
target_modules: [":enforcer"]
target_files:
  - "enforcer/src/integrationTest/kotlin/io/mazewall/seccomp/SeccompDifferentialVerdictTest.kt"
target_symbols:
  - "SeccompDifferentialVerdictTest"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Make the KILL_THREAD kernel oracle reject survival and setup failures

**Context:**
Repository-wide review, 2026-11-04: [TEST-02](../reviews/2026-11-04-repository-review-findings.md#test-02). The source review and reproductions below are not release qualification or implementation approval. Historical resolved issues remain archived; this is their newly scoped residual where applicable.

**Category:** confirmed source defect; control-flow reproduction. **Confidence:** high. **Release boundary:** core Seccomp fatal-action qualification. **Status:** NEW defect in a previously resolved assurance contract.

**Trigger/evidence:** In `enforcer/src/integrationTest/kotlin/io/mazewall/seccomp/SeccompDifferentialVerdictTest.kt:308–320`, a normal child return reaches `error("Kill-probe child survived; seccomp did not enforce KILL_THREAD")`. The immediately surrounding `catch (IllegalStateException)` catches that assertion. Its message does not contain `PROBE_OK`, so the final `assertFalse` passes. The same branch accepts the harness's unrelated nonzero exit or timeout exception.

`src/sharedTest/kotlin/io/mazewall/IsolatedProcessTester.kt:162–164,177–185` inherits child IO and throws an exception containing status/argv, **not captured child stdout**. Therefore the final assertion never observes the child marker it purports to inspect. The child prints that marker on survival (`SeccompKillProbeChild.kt:40–65`).

**Effect:** This native test can pass with no demonstrated fatal enforcement, and can misclassify a probe/setup failure or hung child as proof of termination. `15-KillOracleControlFlow.java` reproduced all three accepting paths using the same try/catch/assertion structure; it did not install a filter or run the real kernel child.

**Smallest complete fix:** Keep the survival assertion outside any catch that accepts expected probe termination. Require an explicitly qualified termination observation, distinguish setup/timeout/cleanup failure, and use an unfiltered control plus post-install readiness receipt as in the nearby x32 test (`SeccompDifferentialVerdictTest.kt:55–69`). Qualify the genuine KILL_THREAD outcome: if this JVM does not supply a stable process exit for thread-only death, use an appropriate thread-death oracle rather than upgrading a timeout to success.

**Regression/qualification:** A normal-return/ALLOW child, an exit-2-before-install child and a timed-out child must each make the parent fail. Only the actual qualified fatal outcome after readiness may pass. The native lead owns the genuine Linux execution.

**Mapping/dependencies:** `docs/internals/backlog/resolved/issue-20260823-172000-forked-child-kill-verdict-probes.md:34–44` claims the parent proves death before `PROBE_OK`/checks fatal status; current code does not. Related resolved `.../issue-20260823-171500-differential-kernel-vs-simulator-bpf-tests.md:32–39` counts this archetype as kernel consensus. No active issue specifically tracks this accepting-survivor path. Independent of TEST-01; both must be repaired before their respective evidence is trusted.

**Needed:**
1. Implement only the bounded correction described above, first reproducing its trigger with the stated negative and positive controls. Preserve all ownership, failure and compatibility invariants; run owning focused checks and the project completion gate before closure. Keep native, hosted and published-artifact qualification separate from source-only or isolated evidence.

---
<!-- id: issue-20261103-235256-949-make-the-kill-thread-kernel-oracle-reject-survival  file: issue-20261103-235256-949-make-the-kill-thread-kernel-oracle-reject-survival.md -->
