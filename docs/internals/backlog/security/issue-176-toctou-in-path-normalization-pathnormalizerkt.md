---
title: "TOCTOU in Path Normalization `PathNormalizer.kt`"
severity: "HIGH"
status: "open"
priority: 9
dependencies: []
component: "enforcer"
effort: "small"
---

# 🔴 [Severity: MEDIUM]: TOCTOU in Path Normalization `PathNormalizer.kt`

*   **Target Area:** `enforcer/src/main/kotlin/io/mazewall/sbob/PathNormalizer.kt`
*   **Hypothesis:** Can an attacker rename directory to bypass path normalizer?
*   **Context & Proof:** `PathNormalizer` does static analysis. Does the system ensure paths aren't modified post-normalization?
*   **Recommendation:** Verify path resolution constraints are verified against Landlock or Seccomp hooks safely.
