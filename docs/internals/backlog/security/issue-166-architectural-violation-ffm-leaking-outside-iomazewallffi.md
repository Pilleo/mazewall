---
title: "Architectural Violation - FFM Leaking Outside `io.mazewall.ffi`"
severity: "HIGH"
status: "open"
priority: 9
dependencies: []
component: "enforcer"
effort: "small"
---

# 🔴 [Severity: MEDIUM]: Architectural Violation - FFM Leaking Outside `io.mazewall.ffi`

*   **Dimension:** Architectural Patterns Compliance (The Integrity View)
*   **Target Area:** Multiple modules, e.g. `LinuxNative.kt`, `Landlock.kt`, `SupervisorSessionHandler.kt`, `SupervisorDaemonManager.kt`, `SeccompInstallationState.kt`, etc.
*   **Hypothesis:** `docs/internals/architecture/architectural-map.md` strictly dictates that "all raw memory/FFM/Unsafe manipulations isolated to `io.mazewall.ffi`".
*   **Context & Proof:** `grep -rn "java.lang.foreign" enforcer/src/main/kotlin/io/mazewall/ | grep -v "/ffi/"` reveals extensive usage of `java.lang.foreign.MemorySegment`, `Arena`, and `ValueLayout` in high-level classes like `Landlock.kt`, `SupervisorSessionHandler.kt`, and `LinuxNative.kt`. This completely violates the ArchUnit architectural constraint.
*   **Recommendation:** Move all direct FFM memory allocations (`Arena.ofConfined().use { ... }`) and native struct manipulations into dedicated wrapper classes inside `io.mazewall.ffi`. The outer layers (`enforcer`, `landlock`, etc.) should only interact with safe Kotlin types (ByteArrays, Strings, domain objects).
