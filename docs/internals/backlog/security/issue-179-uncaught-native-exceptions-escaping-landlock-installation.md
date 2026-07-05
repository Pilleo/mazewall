---
title: "Uncaught Native Exceptions Escaping Landlock Installation"
severity: "HIGH"
status: "open"
priority: 9
dependencies: []
component: "enforcer"
effort: "small"
---

# 🔴 [Severity: MEDIUM]: Uncaught Native Exceptions Escaping Landlock Installation

*   **Target Area:** `enforcer/src/main/kotlin/io/mazewall/landlock/LandlockState.kt`
*   **Hypothesis:** `LandlockSession.applyRuleset` handles FFM resources but may not correctly propagate or contain exceptions during intermediate installation phases.
*   **Context & Proof:** In `LandlockSession.applyRuleset`, `nativeScope` and `try-catch` are used. However, if `Landlock.createRuleset` throws an unexpected runtime exception (e.g., an FFM `IllegalStateException` due to memory alignment issues on a weird kernel, rather than a managed `SyscallResult.Error`), the exception bypasses the standard `state = LandlockState.Failed(err)` setting because it's outside the inner `try` block that wraps `added.restrictSelf(processWide)`.
*   **Recommendation:** Wrap the entire logic from `state = LandlockState.CreatingRuleset(abi)` onwards inside a comprehensive `try-catch` block that correctly transitions the state to `LandlockState.Failed(e)` for any `Throwable`, ensuring the failure state is strictly recorded before throwing.
