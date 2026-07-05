---
title: "Unhandled `O_PATH` Omission on Landlock Fallback Directories"
severity: "HIGH"
status: "open"
priority: 9
dependencies: []
component: "unknown"
effort: "small"
---

# 🔴 [Severity: MEDIUM]: Unhandled `O_PATH` Omission on Landlock Fallback Directories

*   **Dimension:** Security Privileges
*   **Target Area:** `io.mazewall.landlock.Landlock`
*   **Failure Hypothesis:** When `Landlock.addRule` falls back to opening a parent directory using `handleInitialOpenFailure`, it invokes `LinuxNative.getFileSystem().open(arena.allocateFrom(parentPath), flags)`. However, `flags` is `NativeConstants.O_PATH or NativeConstants.O_CLOEXEC or NativeConstants.O_NOFOLLOW`. If the parent directory is actually a symlink to another directory, `O_NOFOLLOW` will cause `open` to fail with `ELOOP`, rejecting the fallback completely and preventing Landlock from applying the rule.
*   **Context & Proof:** `Landlock.addRule` passes `O_NOFOLLOW` to prevent symlink traversal for the specific file rule. However, when falling back to a parent directory (e.g. `File(resolvedPath).parent`), the parent path might be an implicitly resolved system symlink (e.g. `/var/run` -> `/run`). If the fallback uses `O_NOFOLLOW`, the parent open fails, and the user's intended sandbox rule is entirely dropped.
*   **Cascading Risk Potential:** Medium feature failure. Can silently drop valid path rules if system paths involve intermediate directory symlinks.
*   **Recommendation:** When performing the directory fallback in `handleInitialOpenFailure`, remove the `O_NOFOLLOW` flag to allow the kernel to traverse to the real parent directory.
