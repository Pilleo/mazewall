---
title: "Silent Fallback by default behavior evaluation"
severity: "HIGH"
status: "open"
---

# 🔴 [Severity: LOW]: Silent Fallback by default behavior evaluation

*   **Target Area:** `enforcer/src/main/kotlin/io/mazewall/Platform.kt` and usages of `Platform.configuredFallback()`
*   **Hypothesis:** If Landlock or Seccomp is missing, does the system securely fail, or does it bypass containment silently by default?
*   **Context & Proof:** `Platform.configuredFallback()` checks `io.mazewall.fallback` properties and defaults to `Platform.FallbackBehavior.FAIL` if not set. `ContainedExecutors.kt` and `Landlock.kt` correctly call this method and throw an `UnsupportedOperationException` if `FAIL` is the configured behavior.
*   **Recommendation:** The fallback behavior is secure by default (fail-closed) and correctly implemented.
