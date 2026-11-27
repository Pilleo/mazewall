---
title: "Align packaged consumer matrix deadline with owned child budgets"
severity: "MEDIUM"
status: "resolved"
priority: medium
dependencies: []
component: "build"
target_modules: []
target_files:
  - "src/consumerVerification/kotlin/io/mazewall/verification/PackagedConsumerMatrixTest.kt"
  - "src/consumerVerification/kotlin/io/mazewall/verification/PackagedConsumerMatrixHarnessTest.kt"
  - "src/consumerVerification/kotlin/io/mazewall/verification/SharedVerificationLock.kt"
  - "build.gradle.kts"
  - "tests/consumers/README.md"
target_symbols:
  - "PackagedConsumerMatrixTest"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Align packaged consumer matrix deadline with owned child budgets

## Resolution — 2026-11-26

The approved scope is a single JDK 25 run for `consumerCheck`. The check has a 10-minute same-thread deadline, a one-minute shared lock wait, and a six-minute consumer command budget. The child uses the existing owned command runner for receipts and cleanup and runs with `--no-daemon` to prevent reuse of a Gradle daemon outside its process tree. [Implementation plan](../../../superpowers/plans/2026-11-25-packaged-consumer-matrix-deadline.md)

- The deadline test failed before the fix and failed again when only the override was removed. All 14 focused tests passed with the fix, covering the selected JDK, nonzero exits, lock contention, timeout receipts, and cleanup of an owned launcher and descendant.
- The clean-project and strict repeat runs against staged local `0.1.0-candidate.e60255ae` publications completed in 63 and 66 seconds. Both used JDK 25 for Java and Kotlin and stable Kotlin 2.4.20. Each recorded one successful JDK 25 child command. Dependency locks and artifact hashes matched across runs. Candidate locks were reviewed and kept in the disposable checkout.
- Consumer source lint and `./gradlew build` passed. The full build included the confined Docker kernel lane.

## Original finding

**Context:**
Repository-wide review, 2026-11-04: [TEST-04](../../reviews/2026-11-04-repository-review-findings.md#test-04). The source review and reproductions below are not release qualification or implementation approval. Historical resolved issues remain archived; this is their newly scoped residual where applicable.

**Category:** confirmed source-level budget conflict; no slow matrix was run. **Confidence:** high. **Release boundary:** independent core Java/stable-Kotlin/JDK22+25 consumer qualification. **Status:** NEW sibling of the recent scoped cache-test deadline correction.

**Trigger/evidence:** A valid cold matrix or lock wait takes more than two minutes. Root applies `mazewall.quality-conventions` (`build.gradle.kts:12–15`), which applies test conventions (`buildSrc/src/main/kotlin/mazewall.quality-conventions.gradle.kts:5–7`). Every Test gets JUnit's **2m**, separate-thread method default (`mazewall.test-conventions.gradle.kts:11–15`).

`src/consumerVerification/kotlin/io/mazewall/verification/PackagedConsumerMatrixTest.kt:13–14` has no scoped override. It performs preflight commands and both cleaned JDK rows sequentially (`:23–71`), while `command` admits **15 minutes per command** (`:89–95`); its shared lock can itself wait 15 minutes (`SharedVerificationLock.kt:31–36`). Root `consumerCheck` supplies no contrary deadline (`build.gradle.kts:344–356`).

**Effect:** Legitimate work still inside its declared bounded child/lock budget is interrupted by the unrelated method default. The matrix's advertised command budgets are not effective. This can fail cold compatibility acceptance before its second JDK row, rather than detecting a consumer incompatibility. Its private launcher-only cleanup also bypasses the stronger shared consumer process-tree path; do not treat interruption as confirmation all nested Gradle work retired.

**Smallest complete fix:** Give this long-running matrix an explicit SAME_THREAD deadline covering its chosen lock/command budgets plus cleanup, as done for the other long consumer tests. Keep individual child bounds. Reuse the existing shared owned-command helper instead of leaving a matrix-specific launcher-only cleanup path if retaining nested work ownership is part of the correction; do not increase the global two-minute default.

**Regression/qualification:** A controlled host-only delayed phase must reach the next row within the intended scoped budget; a true child deadline must still fail and retire observed descendants. Then qualify an actual cold and strict repeat two-JDK matrix with exact artifacts and logs. No blind unchanged retry is evidence of a fix.

**Mapping/dependencies:** Resolved `docs/internals/backlog/resolved/issue-20261102-070428-309-align-independent-consumer-cache-verification-dead.md` fixes **only** `IndependentConsumerCacheTest` and explicitly preserves the shared default; that fix is present in current work. It does not fix this matrix. Existing `issue-20261008-001530-668-stable-packaged-consumer-compatibility-gates.md` owns final-source packaged compatibility, but does not describe this deadline defect. Complete this before requalifying that matrix for a new producer.

**Needed:**
1. Implement only the bounded correction described above, first reproducing its trigger with the stated negative and positive controls. Preserve all ownership, failure and compatibility invariants; run owning focused checks and the project completion gate before closure. Keep native, hosted and published-artifact qualification separate from source-only or isolated evidence.

---
<!-- id: issue-20261103-235257-865-align-packaged-consumer-matrix-deadline-with-owned  file: issue-20261103-235257-865-align-packaged-consumer-matrix-deadline-with-owned.md -->
