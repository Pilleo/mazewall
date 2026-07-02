---
title: "Uncaught Native Exceptions Escaping BPF Installation"
severity: "HIGH"
status: "open"
---

# 🔴 [Severity: MEDIUM]: Uncaught Native Exceptions Escaping BPF Installation

*   **Dimension:** Micro-Implementation & FFM ABI Rigor
*   **Target Area:** `enforcer/src/main/kotlin/io/mazewall/seccomp/PureJavaBpfEngine.kt`
*   **Failure Hypothesis:** The `installInternal` method catches `Throwable`, but `nativeScope` or underlying FFM calls might throw non-standard errors (e.g., `LinkageError` if a native symbol is suddenly unresolved on an unsupported glibc version) that should perhaps not be caught indiscriminately, or should be wrapped in a more specific containment failure exception.
*   **Context & Proof:** Catching generic `Throwable` masks potentially critical JVM errors like `OutOfMemoryError` or `StackOverflowError`, wrapping them in `SeccompInstallationState.Failed`. While preventing a raw crash during installation is good, continuing application execution after an OOM might be dangerous if the application assumes the security boundary is up.
*   **Cascading Risk Potential:** Medium. Running an application in an inconsistent state after a critical JVM error.
*   **Recommendation:** Refine the catch block to specifically handle expected exceptions (e.g., `IllegalStateException`, `UnsupportedOperationException`, `IOException`) and let fatal errors (`Error`) propagate, or at least log them as FATAL before updating the state.
