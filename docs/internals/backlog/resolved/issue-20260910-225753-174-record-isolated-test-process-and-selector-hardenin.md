---
title: "Record isolated test process and selector hardening evidence"
severity: "MEDIUM"
status: "resolved"
priority: high
dependencies: []
component: "core"
target_modules: ["buildSrc"]
target_files:
  - "src/sharedTest/kotlin/io/mazewall/IsolatedProcessTester.kt"
target_symbols:
  - "IsolatedProcessTester"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Record isolated test process and selector hardening evidence

**Context:**
RH-25: bounded descendant cleanup, interrupt preservation and inherited fresh-JVM selection require recorded regression evidence.

**Needed:**
1. Record implementing commits and executable regression evidence in the canonical hardening manifest.

**Resolution:** RH-25 are implemented by commits abd65a9a, 145cdb75. Regression owners: IsolatedProcessTesterLifecycleTest, VerificationActionsTest and TestHealthPluginFunctionalTest.

**Verification:** `./gradlew --no-daemon -p buildSrc check` and the 11-case isolated lifecycle suite passed. Real TestKit fixtures execute inherited and nested tests and fail unmatched selection. Descendant guarantees cover observed cooperative processes, not arbitrary reparenting.

The [canonical finding manifest](../../designs/core/post-review-hardening-evidence.md#liveness-and-boundary-review-rh) maps individual methods and gates. [Final integration evidence](../../designs/core/review-hardening-verification.md) distinguishes fresh execution from cached outputs and does not imply aggregate acceptance from this scoped resolution.

---
<!-- id: issue-20260910-225753-174-record-isolated-test-process-and-selector-hardenin  file: issue-20260910-225753-174-record-isolated-test-process-and-selector-hardenin.md -->
