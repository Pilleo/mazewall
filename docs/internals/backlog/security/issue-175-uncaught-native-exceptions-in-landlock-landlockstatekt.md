---
title: "Uncaught Native Exceptions in Landlock `LandlockState.kt`"
severity: "HIGH"
status: "open"
priority: 9
dependencies: []
component: "enforcer"
effort: "small"
---

# 🔴 [Severity: MEDIUM]: Uncaught Native Exceptions in Landlock `LandlockState.kt`

*   **Target Area:** `enforcer/src/main/kotlin/io/mazewall/landlock/LandlockState.kt`
*   **Hypothesis:** If allocating rulesets fails, does it leak FDs?
*   **Context & Proof:** `Landlock` uses FDs. If it crashes mid-setup, FD must be closed.
*   **Recommendation:** Verify `use` is thoroughly applied or manual close happens on error paths.
