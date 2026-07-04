---
title: "Uncaught exceptions in `ContainedExecutorWrapper.kt` during filter installation"
severity: "HIGH"
status: "open"
---

# 🔴 [Severity: MEDIUM]: Uncaught exceptions in `ContainedExecutorWrapper.kt` during filter installation

*   **Target Area:** `enforcer/src/main/kotlin/io/mazewall/enforcer/internal/ContainedExecutorWrapper.kt`
*   **Hypothesis:** If `ContainedExecutors.installOnCurrentThread(policy, scopingPolicy)` fails midway (e.g., Landlock throws an exception before Seccomp is installed, or the supervisor connection fails), the `ThreadStateRegistry` might be left in a partially updated or corrupted state because `installOnCurrentThread` updates `ThreadStateRegistry.state` incrementally as it installs filters, but doesn't roll back on failure.
*   **Context & Proof:** `ContainedExecutors.installInternal` calls `applyLandlockIfNecessary` which updates `ThreadStateRegistry.state`. If the subsequent `installSeccompFilter` fails, the thread state registry will reflect Landlock applied, but the seccomp filter might not be, or worse, the `SupervisorSession` might not be created properly. While `ContainedExecutorWrapper` uses `.use {}` to close the `AutoCloseable` return value of `installOnCurrentThread`, it doesn't clean up the `ThreadStateRegistry.state` if the *installation itself* throws an exception.
*   **Recommendation:** `ContainedExecutors.installInternal` should probably take a snapshot of the current state, and use a `try-catch` to restore the original `ThreadStateRegistry.state` (and potentially Landlock/Seccomp state, though those are harder to revert) if the installation throws an error, or the Wrapper should handle it.
