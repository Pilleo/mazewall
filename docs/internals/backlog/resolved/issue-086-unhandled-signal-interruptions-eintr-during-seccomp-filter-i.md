---
title: "Unhandled Signal Interruptions (`EINTR`) during `seccomp` Filter Installation"
severity: "HIGH"
status: "resolved"
priority: 9
dependencies: []
component: "seccomp"
effort: "small"
autonomy: "supervised"
solution_approved: true
blast_radius: "medium"
reversible: true
github_issue: 233
---

# 🔴 [Severity: MEDIUM]: Unhandled Signal Interruptions (`EINTR`) during `seccomp` Filter Installation

**Context:**
**Hypothesis:** If the `seccomp` downcall in `installFilter` is interrupted by an asynchronous POSIX signal (e.g., a JVM profiling signal or timer tick), it may fail with `EINTR`. The current code does not retry the syscall on `EINTR` and immediately throws an `IllegalStateException`, aborting the installation.

The `PureJavaBpfEngine.installFilter` method calls `LinuxNative.raw.syscall(NativeConstants.SECCOMP_SET_MODE_FILTER, ...)`. The kernel can interrupt almost any blocking or slow system call with `EINTR`. If `seccomp` returns `EINTR`, `r3.returnValue` will not be `0`, and the code falls back to `prctl`, which might also fail or behave unexpectedly. The method lacks a robust `while (errno == EINTR)` retry loop.


**Needed:**
1. Wrap the `seccomp` and `prctl` filter installation downcalls in a retry loop that specifically handles `EINTR`.

## Solution Options

### Option A — Refactor implementation
Implement the recommendation described in the Needed section to resolve the issue directly. Target area: ``io.mazewall.seccomp.PureJavaBpfEngine``
**Pros:** Resolves the root cause of the issue.
**Cons:** Requires careful implementation and testing.
**Risk:** MEDIUM
**Effort:** small

---
**Chosen:** Option A (Fully resolved and verified in the codebase)

**Acceptance Criteria:**
- [x] Tests verify the fix works as expected.
- [x] Issue is fully resolved in the codebase.

**Implementation Hints:**
- Ensure you read existing tests and implementation carefully before modifying code.
