---
title: "Unhandled Signal Interruptions (`EINTR`) during `seccomp` Filter Installation"
severity: "HIGH"
status: "resolved"
priority: 9
dependencies: []
component: "enforcer"
effort: "small"
autonomy: "supervised"
solution_approved: true
blast_radius: "medium"
reversible: true
github_issue: 173
---

# 🔴 [Severity: MEDIUM]: Unhandled Signal Interruptions (`EINTR`) during `seccomp` Filter Installation

**Context:**
**Hypothesis:** The `seccomp` syscall itself might return `EINTR` if a signal is delivered to the thread precisely during the kernel's filter installation phase.

`LinuxNative.raw.syscall` is used to invoke `seccomp`. While filter installation is generally fast, if `EINTR` occurs, the installation fails. The current code does not retry the `seccomp` syscall on `EINTR`.


**Needed:**
1. Wrap the `seccomp` syscall (and `prctl` fallback) in an explicit `while (errno == EINTR)` retry loop, as is standard practice for robust Linux system programming.

## Solution Options

### Option A — Refactor implementation
Implement the recommendation described in the Needed section to resolve the issue directly. Target area: ``enforcer/src/main/kotlin/io/mazewall/seccomp/PureJavaBpfEngine.kt``
**Pros:** Resolves the root cause of the issue.
**Cons:** Requires careful implementation and testing.
**Risk:** MEDIUM
**Effort:** small

---
**Chosen:** Option A (Resolved via PR #174)

**Acceptance Criteria:**
- [x] Tests verify the fix works as expected.
- [x] Issue is fully resolved in the codebase.

**Implementation Hints:**
- Ensure you read existing tests and implementation carefully before modifying code.
