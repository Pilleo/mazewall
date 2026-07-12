---
title: "`ContainmentDesignSpec` test fails on systems without Landlock support"
severity: "HIGH"
status: "open"
priority: 6
dependencies: []
component: "enforcer"
effort: "small"
autonomy: "supervised"
solution_approved: false
---


# 🔴 [Severity: MEDIUM]: `ContainmentDesignSpec` test fails on systems without Landlock support

*   **Dimension:** Cascading Failure Analysis
*   **Target Area:** `enforcer/src/integrationTest/kotlin/io/mazewall/seccomp/ContainmentDesignSpec.kt` (specifically `"Pre-warmed JVM task runs successfully..."`)
*   **Failure Hypothesis:** The test instantiates `ContainedExecutors.wrap(executor, Policy.builder().build())`. Because the default policy allows `IO_URING_SETUP`, `ContainedExecutors` automatically triggers Landlock. If the kernel does not support Landlock, `Landlock.applyRuleset` throws an `UnsupportedOperationException`. The test fails because it only conditionally checks `Arch.current()` support but does not check or handle `Landlock.isSupported()`.
*   **Context & Proof:** The test execution log shows `java.util.concurrent.ExecutionException: java.lang.UnsupportedOperationException: Landlock is not supported on this kernel but FS rules were requested.` which originates from `handleUnsupportedLandlock`. Since tests are executed in a sandbox environment that lacks Landlock, this test deterministically fails, breaking the build.
*   **Cascading Risk Potential:** Medium. Breaks CI pipelines and test suites on environments lacking advanced kernel features.
*   **Recommendation:** Wrap the execution in an `Assumptions.assumeTrue(Landlock.isSupported())` or skip it natively. Wait, as an agent I cannot fix the source code, but the backlog must track this CI failure.

## Solution Options

### Option A
(To be filled)

---
**Chosen:** *(not yet approved — requires human decision)*

**Acceptance Criteria:**
- [ ]

**Implementation Hints:**
-
