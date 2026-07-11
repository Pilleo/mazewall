---
title: "Missing Support for `O_PATH` and `O_CLOEXEC` in `Landlock` fallback"
severity: "HIGH"
status: "open"
priority: 9
dependencies: []
component: "enforcer"
effort: "small"
autonomy: "supervised"
solution_approved: false
blast_radius: "medium"
reversible: true
---

# 🔴 [Severity: MEDIUM]: Missing Support for `O_PATH` and `O_CLOEXEC` in `Landlock` fallback

**Context:**
**Hypothesis:** When `Landlock.addRule` falls back to opening a parent directory, it uses hardcoded flags that might omit `O_PATH` or `O_CLOEXEC`, causing unnecessary file descriptor leaks or rejecting valid symlinks.

The issue was highlighted in the backlog script output: "Unhandled `O_PATH` Omission on Landlock Fallback Directories". If `open` is called without `O_PATH`, it might attempt to fully open a device file or FIFO instead of just getting a file descriptor for Landlock routing, potentially causing hangs.


**Needed:**
1. Verify the `open` flags in `Landlock.kt` explicitly include `O_PATH | O_CLOEXEC`.

## Solution Options

### Option A — Refactor implementation
Implement the recommendation described in the Needed section to resolve the issue directly. Target area: ``enforcer/src/main/kotlin/io/mazewall/landlock/Landlock.kt``
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
