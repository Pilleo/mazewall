---
title: "Inefficient Regex Compilation in `ContainmentViolationDetector`"
severity: "HIGH"
status: "resolved"
priority: 9
dependencies: []
component: "enforcer"
effort: "large"
github_issue: 69
---

# 🔴 [Severity: LOW]: Inefficient Regex Compilation in `ContainmentViolationDetector`

*   **Dimension:** Performance & Efficiency
*   **Target Area:** `enforcer/src/main/kotlin/io/mazewall/enforcer/ContainmentViolationDetector.kt` (specifically `DENIED_PHRASES`)
*   **Failure Hypothesis:** The `ContainmentViolationDetector` stores `DENIED_PHRASES` as an array of strings and checks them using `DENIED_PHRASES.any { msg.contains(it, ignoreCase = true) }`. Under heavy load (e.g. iterative profiling loops or logging intercepted exceptions), this causes redundant string allocations and linear substring scans across all messages.
*   **Context & Proof:** `contains(it, ignoreCase = true)` dynamically converts both strings or handles case-insensitive scanning inefficiently on every invocation. Compiling a single `Regex` pattern (e.g. `Regex("Operation not permitted|Permission denied|refusé|verweigert|negado", RegexOption.IGNORE_CASE)`) would allow the regex engine to construct an optimized DFA/NFA state machine and evaluate the message in a single pass.
*   **Cascading Risk Potential:** Low performance overhead, but adds unnecessary garbage collection pressure and CPU cycles during high-frequency exception trapping in Tier A profiling.
*   **Recommendation:** Refactor `DENIED_PHRASES` into a compiled `Regex` for optimal performance.
