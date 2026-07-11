---
title: "Missing ArchUnit test for FFM architecture boundary violations"
severity: "HIGH"
status: "open"
priority: 9
dependencies: []
component: "enforcer"
effort: "small"
---

# 🔴 [Severity: HIGH]: Missing ArchUnit test for FFM architecture boundary violations

*   **Target Area:** `enforcer/src/test/kotlin/io/mazewall/ArchitectureTest.kt`
*   **Hypothesis:** `docs/internals/architecture/architectural-map.md` states "ArchUnit Isolation: all raw memory/FFM/Unsafe manipulations isolated to `io.mazewall.ffi`."
*   **Context & Proof:** As noted in "Architectural Violation - FFM Leaking Outside `io.mazewall.ffi`", there is extensive usage of `java.lang.foreign` outside of the FFM boundary packages. Currently, `ArchitectureTest.kt` does not have an overarching rule checking `noClasses().that().resideOutsideOfPackage("io.mazewall.ffi..").should().dependOnClassesThat().resideInAPackage("java.lang.foreign..")`. Such a test should be added, but it would currently fail.
*   **Recommendation:** Implement the ArchUnit test and incrementally refactor `Landlock.kt`, `SupervisorSessionHandler.kt`, `LinuxNative.kt`, etc., so they rely entirely on `io.mazewall.ffi` safe types.
