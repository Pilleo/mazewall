---
title: "Unreliable Test Teardown for Mocked Native Engines"
severity: "HIGH"
status: "resolved"
priority: 9
dependencies: []
component: "enforcer"
effort: "small"
autonomy: "supervised"
solution_approved: false
blast_radius: "medium"
reversible: true
github_issue: 126
---

# 🔴 [Severity: MEDIUM]: Unreliable Test Teardown for Mocked Native Engines

**Context:**
**Hypothesis:** `LinuxNative.setEngine(mock)` is used extensively to inject mock kernel behaviors. However, `LinuxNative.resetToDefault()` or an equivalent teardown is missing in many of these tests.

`grep -rn "LinuxNative.setEngine" enforcer/src/test/kotlin/io/mazewall/` shows 15 usages, but `LinuxNative.resetToDefault` has only 2 occurrences (and `LinuxNative.setEngine(RealNativeEngine)` appears once in `NativeEngineTest.kt`). In `LandlockCoverageTest.kt` and `LinuxNativeCoverageTest.kt`, there is no `@AfterEach` method or `finally` block ensuring the engine is reset. If one of these tests fails or throws an exception midway, the static `LinuxNative` singleton will remain mocked for all subsequent tests running in the same JVM, causing spurious test failures across the suite.


**Needed:**
1. Add an `@AfterEach` block in all test classes that use `LinuxNative.setEngine` to unconditionally call `LinuxNative.resetToDefault()`, ensuring global state is properly isolated between tests.

## Solution Options

### Option A — Refactor implementation
Implement the recommendation described in the Needed section to resolve the issue directly. Target area: ``enforcer/src/test/kotlin/io/mazewall/landlock/LandlockCoverageTest.kt` and `enforcer/src/test/kotlin/io/mazewall/LinuxNativeCoverageTest.kt``
**Pros:** Resolves the root cause of the issue.
**Cons:** Requires careful implementation and testing.
**Risk:** MEDIUM
**Effort:** small

---
**Chosen:** *(not yet approved — requires human decision)*

**Acceptance Criteria:**
- [ ] Tests verify the fix works as expected.
- [ ] Issue is fully resolved in the codebase.

**Implementation Hints:**
- Ensure you read existing tests and implementation carefully before modifying code.
