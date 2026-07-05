---
title: "`SbobParser` Production Crashes due to Syntactic Subpath Pruning of Unresolved/Symlinked Paths"
severity: "HIGH"
status: "open"
priority: 9
dependencies: []
component: "profiler"
effort: "small"
---

# 🔴 [Severity: HIGH]: `SbobParser` Production Crashes due to Syntactic Subpath Pruning of Unresolved/Symlinked Paths

*   **Dimension:** Cascading Failure Analysis (The Systems View)
*   **Target Area:** `profiler/src/main/kotlin/io/mazewall/profiler/compiler/SbobParser.kt`
*   **Failure Hypothesis:** The `SbobParser` aggregates file paths. If a path contains unresolved components (`.`, `..`) or symlinks, simple string-prefix matching can incorrectly classify one path as a subset of another.
*   **Context & Proof:** Consider `allowedFsReadPaths`. If the profiler observed access to `/opt/app/../etc/passwd` and `/opt/app`, string prefix logic might prune `/opt/app/../etc/passwd` because it starts with `/opt/app`. The resulting policy would only allow `/opt/app`, and when the application tries to access the `passwd` file, it will be denied.
*   **Cascading Risk Potential:** High (Policy Generation). Leads to incomplete policies that cause application crashes in production.
*   **Recommendation:** `SbobParser` must strictly perform `Path.normalize()` and ideally `toRealPath()` before doing prefix comparisons to ensure accurate hierarchy evaluation.
