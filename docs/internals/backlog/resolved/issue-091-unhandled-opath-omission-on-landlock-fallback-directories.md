---
title: "Unhandled `O_PATH` Omission on Landlock Fallback Directories"
severity: "HIGH"
status: "resolved"
priority: 9
dependencies: []
component: "unknown"
effort: "small"
autonomy: "supervised"
solution_approved: false
blast_radius: "medium"
reversible: true
github_issue: 227
---

# 🔴 [Severity: MEDIUM]: Unhandled `O_PATH` Omission on Landlock Fallback Directories

**Context:**
**Hypothesis:** When `Landlock.addRule` falls back to opening a parent directory using `handleInitialOpenFailure`, it invokes `LinuxNative.getFileSystem().open(arena.allocateFrom(parentPath), flags)`. However, `flags` is `NativeConstants.O_PATH or NativeConstants.O_CLOEXEC or NativeConstants.O_NOFOLLOW`. If the parent directory is actually a symlink to another directory, `O_NOFOLLOW` will cause `open` to fail with `ELOOP`, rejecting the fallback completely and preventing Landlock from applying the rule.

`Landlock.addRule` passes `O_NOFOLLOW` to prevent symlink traversal for the specific file rule. However, when falling back to a parent directory (e.g. `File(resolvedPath).parent`), the parent path might be an implicitly resolved system symlink (e.g. `/var/run` -> `/run`). If the fallback uses `O_NOFOLLOW`, the parent open fails, and the user's intended sandbox rule is entirely dropped.


**Needed:**
1. When performing the directory fallback in `handleInitialOpenFailure`, remove the `O_NOFOLLOW` flag to allow the kernel to traverse to the real parent directory.

## Solution Options

### Option A — Refactor implementation
Implement the recommendation described in the Needed section to resolve the issue directly. Target area: ``io.mazewall.landlock.Landlock``
**Pros:** Resolves the root cause of the issue.
**Cons:** Requires careful implementation and testing.
**Risk:** MEDIUM
**Effort:** small

---
**Chosen:** *(not yet approved — requires human decision)*

**Acceptance Criteria:**
- [ ] Tests verify the fix works as expected.
- [ ] Issue is fully resolved in the codebase.

**Implementation Hints:**
- Ensure you read existing tests and implementation carefully before modifying code.
