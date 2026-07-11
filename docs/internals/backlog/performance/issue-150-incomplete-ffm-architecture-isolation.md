---
title: "Incomplete FFM Architecture Isolation"
severity: "HIGH"
status: "open"
priority: 9
dependencies: []
component: "enforcer"
effort: "small"
---

# 🔴 [Severity: LOW]: Incomplete FFM Architecture Isolation

*   **Dimension:** Architectural Patterns Compliance (The Integrity View)
*   **Target Area:** `enforcer/src/main/kotlin/io/mazewall/enforcer/supervisor/SupervisorInstaller.kt`
*   **Failure Hypothesis:** FFM (`java.lang.foreign`) MemorySegments and Arenas are bleeding outside the `io.mazewall.ffi` boundary.
*   **Context & Proof:** `JVMValidationListener.start` and `runValidationReactor` in `SupervisorInstaller.kt` directly use `Arena.ofShared()` and manipulate memory allocation logic for the `SupervisorResponseSegment`. According to `docs/internals/architecture/architectural-map.md#7-core-architectural-paradigms--patterns`, all raw memory/FFM manipulation must be isolated to `io.mazewall.ffi`.
*   **Recommendation:** Move the raw `Arena` and `SupervisorResponseSegment` lifecycle management into a dedicated class inside the `io.mazewall.ffi` package, exposing a safe, higher-level interface to the `enforcer.supervisor` package.
