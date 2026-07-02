---
title: "Uncaught exceptions in `ContainedExecutorWrapper.kt` during filter installation"
severity: "HIGH"
status: "open"
---

# 🔴 [Severity: MEDIUM]: Uncaught exceptions in `ContainedExecutorWrapper.kt` during filter installation

*   **Target Area:** `enforcer/src/main/kotlin/io/mazewall/enforcer/internal/ContainedExecutorWrapper.kt`
*   **Hypothesis:** If installing a policy fails, does it clean up ThreadLocals?
*   **Context & Proof:** Wrapping tasks needs robust try-finally for thread local registries.
*   **Recommendation:** Verify that executor wrappers properly handle seccomp installation failures and clean state.
