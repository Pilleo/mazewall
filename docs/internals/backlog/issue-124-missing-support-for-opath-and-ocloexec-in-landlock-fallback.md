---
title: "Missing Support for `O_PATH` and `O_CLOEXEC` in `Landlock` fallback"
severity: "HIGH"
status: "open"
---

# 🔴 [Severity: MEDIUM]: Missing Support for `O_PATH` and `O_CLOEXEC` in `Landlock` fallback

*   **Dimension:** Micro-Implementation & FFM ABI Rigor
*   **Target Area:** `enforcer/src/main/kotlin/io/mazewall/landlock/Landlock.kt`
*   **Failure Hypothesis:** When `Landlock.addRule` falls back to opening a parent directory, it uses hardcoded flags that might omit `O_PATH` or `O_CLOEXEC`, causing unnecessary file descriptor leaks or rejecting valid symlinks.
*   **Context & Proof:** The issue was highlighted in the backlog script output: "Unhandled `O_PATH` Omission on Landlock Fallback Directories". If `open` is called without `O_PATH`, it might attempt to fully open a device file or FIFO instead of just getting a file descriptor for Landlock routing, potentially causing hangs.
*   **Cascading Risk Potential:** Medium. File descriptor leaks or blocked application initialization.
*   **Recommendation:** Verify the `open` flags in `Landlock.kt` explicitly include `O_PATH | O_CLOEXEC`.
