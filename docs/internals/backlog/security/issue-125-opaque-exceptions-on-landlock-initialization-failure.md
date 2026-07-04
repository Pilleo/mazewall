---
title: "🔴 [Severity: DX-FRICTION]: Opaque Exceptions on Landlock Initialization Failure"
severity: "HIGH"
status: "open"
---

# 🔴 [Severity: DX-FRICTION]: Opaque Exceptions on Landlock Initialization Failure

*   **Dimension:** Developer Experience (DX) & API Ergonomics
*   **Target Area:** `enforcer/src/main/kotlin/io/mazewall/landlock/Landlock.kt`
*   **Failure Hypothesis:** If Landlock fails to initialize due to a missing kernel capability or an older ABI version, the exception message might be opaque (e.g., just returning an `errno`), confusing developers about whether the system is supported.
*   **Context & Proof:** If `landlock_create_ruleset` returns `ENOSYS` or `EOPNOTSUPP`, a generic `IllegalStateException` or `RuntimeException` without context about Kernel requirements hurts the DX.
*   **Cascading Risk Potential:** DX Friction. Developers might abandon the library if they cannot quickly diagnose environment issues.
*   **Recommendation:** Wrap Landlock native call failures in a specific `UnsupportedKernelFeatureException` with clear guidance on required Linux kernel versions.
