---
title: "Unreliable Test Teardown for Mocked Native Engines"
severity: "HIGH"
status: "open"
priority: 9
dependencies: []
component: "enforcer"
effort: "small"
---

# 🔴 [Severity: MEDIUM]: Unreliable Test Teardown for Mocked Native Engines

*   **Target Area:** `enforcer/src/test/kotlin/io/mazewall/landlock/LandlockCoverageTest.kt` and `enforcer/src/test/kotlin/io/mazewall/LinuxNativeCoverageTest.kt`
*   **Hypothesis:** `LinuxNative.setEngine(mock)` is used extensively to inject mock kernel behaviors. However, `LinuxNative.resetToDefault()` or an equivalent teardown is missing in many of these tests.
*   **Context & Proof:** `grep -rn "LinuxNative.setEngine" enforcer/src/test/kotlin/io/mazewall/` shows 15 usages, but `LinuxNative.resetToDefault` has only 2 occurrences (and `LinuxNative.setEngine(RealNativeEngine)` appears once in `NativeEngineTest.kt`). In `LandlockCoverageTest.kt` and `LinuxNativeCoverageTest.kt`, there is no `@AfterEach` method or `finally` block ensuring the engine is reset. If one of these tests fails or throws an exception midway, the static `LinuxNative` singleton will remain mocked for all subsequent tests running in the same JVM, causing spurious test failures across the suite.
*   **Recommendation:** Add an `@AfterEach` block in all test classes that use `LinuxNative.setEngine` to unconditionally call `LinuxNative.resetToDefault()`, ensuring global state is properly isolated between tests.
