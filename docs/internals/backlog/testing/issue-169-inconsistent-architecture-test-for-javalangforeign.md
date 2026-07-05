---
title: "Inconsistent Architecture Test for `java.lang.foreign`"
severity: "HIGH"
status: "open"
priority: 9
dependencies: []
component: "enforcer"
effort: "small"
---

# 🔴 [Severity: LOW]: Inconsistent Architecture Test for `java.lang.foreign`

*   **Dimension:** Architectural Patterns Compliance (The Integrity View)
*   **Target Area:** `enforcer/src/test/kotlin/io/mazewall/ArchitectureTest.kt`
*   **Hypothesis:** The ArchUnit tests ban `java.lang.foreign.MemorySegment.reinterpret`, `Arena.ofAuto`, and `MemorySegment.get`, but they do not generally ban the import and usage of `java.lang.foreign` outside of `io.mazewall.ffi`.
*   **Context & Proof:** `grep -rn "java.lang.foreign" enforcer/src/main/kotlin/io/mazewall/ | grep -v "/ffi/"` returns many hits. The `ArchitectureTest.kt` lacks a strict package boundary check for the `java.lang.foreign` package.
*   **Recommendation:** Add an ArchUnit test: `noClasses().that().resideOutsideOfPackage("io.mazewall.ffi..").should().dependOnClassesThat().resideInAPackage("java.lang.foreign..")` to enforce the constraint defined in `architectural_map.md`.
