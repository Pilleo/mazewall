---
title: "Unit-test FilterInstallationPlanner.verifyFilterDepth fail-closed budget"
severity: "MEDIUM"
status: "resolved"
priority: high
dependencies: []
component: "enforcer"
target_modules:
  - ":enforcer"
target_files:
  - "enforcer/src/test/kotlin/io/mazewall/enforcer/FilterInstallationPlannerTest.kt"
effort: "small"
autonomy: "autonomous"
---

# 🟡 [Severity: MEDIUM]: Unit-test FilterInstallationPlanner.verifyFilterDepth fail-closed budget

**Context:** The kernel allows at most 32 stacked seccomp filters. `FilterInstallationPlanner.verifyFilterDepth` throws `IllegalStateException` when `currentDepth >= 32` and logs a warning when `currentDepth > 10`. There is no unit test. A cleanup or side-effect refactor that swallows this throw would silently bypass the budget and is fail-open.

Constants (do not change them): `MAX_SECCOMP_FILTERS = 32`, `WARN_FILTERS_THRESHOLD = 10` in `FilterInstallationPlanner.kt`.

**Needed:**
1. Add tests only on `FilterInstallationPlannerTest`.
2. Do not change `MAX_SECCOMP_FILTERS` or `WARN_FILTERS_THRESHOLD`.
3. Do not assert logger text (warning at depth 11 is not a failure).

**New cases:**
- `verifyFilterDepth(0)` does not throw.
- `verifyFilterDepth(31)` does not throw.
- `verifyFilterDepth(32)` throws `IllegalStateException` whose message mentions `32`.
- `verifyFilterDepth(33)` throws `IllegalStateException`.
- `verifyFilterDepth(11)` does not throw (warn is not fail).

**Do not:**
- Catch the exception in production `installSeccompFilter` and continue.
- Raise or lower the cap to make tests prettier.

**Verify:** `./gradlew :enforcer:test --tests io.mazewall.enforcer.FilterInstallationPlannerTest`
