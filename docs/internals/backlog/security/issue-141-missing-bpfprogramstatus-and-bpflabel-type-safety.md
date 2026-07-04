---
title: "Missing `BpfProgram<Status>` and `BpfLabel` Type-Safety"
severity: "HIGH"
status: "open"
---

# 🔴 [Severity: MEDIUM]: Missing `BpfProgram<Status>` and `BpfLabel` Type-Safety

*   **Dimension:** Verification via Types & Compiler Features
*   **Target Area:** `enforcer/src/main/kotlin/io/mazewall/seccomp/BpfProgram.kt`
*   **Failure Hypothesis:** The `BpfProgram.builder()` uses a builder pattern, but it might lack compile-time guarantees (phantom types) to ensure that `checkArch` is called *before* `loadSyscallNr`, or that `build()` is only called when the program is in a complete state.
*   **Context & Proof:** If a developer modifies `BpfFilter.kt` and accidentally reorders the builder calls, the resulting BPF program might be structurally invalid (e.g., trying to load arguments before checking the architecture), leading to kernel rejection (`EINVAL`) only at runtime.
*   **Cascading Risk Potential:** Medium. Increases the risk of regressions during refactoring.
*   **Recommendation:** Leverage Kotlin's type system (e.g., Phantom Types or a Type-State pattern) to enforce the builder sequence at compile time, matching the architectural constraints outlined in the design documents.
