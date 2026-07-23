---
title: "Uncaught Native Exceptions Escaping BPF Installation"
severity: "HIGH"
status: "resolved"
priority: 9
dependencies: []
component: "enforcer"
effort: "small"
autonomy: "supervised"
solution_approved: false
blast_radius: "medium"
reversible: true
github_issue: 179
---

# 🔴 [Severity: MEDIUM]: Uncaught Native Exceptions Escaping BPF Installation

**Context:**
**Hypothesis:** The `installInternal` method catches `Throwable`, but `nativeScope` or underlying FFM calls might throw non-standard errors (e.g., `LinkageError` if a native symbol is suddenly unresolved on an unsupported glibc version) that should perhaps not be caught indiscriminately, or should be wrapped in a more specific containment failure exception.

Catching generic `Throwable` masks potentially critical JVM errors like `OutOfMemoryError` or `StackOverflowError`, wrapping them in `SeccompInstallationState.Failed`. While preventing a raw crash during installation is good, continuing application execution after an OOM might be dangerous if the application assumes the security boundary is up.


**Needed:**
1. Refine the catch block to specifically handle expected exceptions (e.g., `IllegalStateException`, `UnsupportedOperationException`, `IOException`) and let fatal errors (`Error`) propagate, or at least log them as FATAL before updating the state.

## Solution Options

### Option A — Refactor implementation
Implement the recommendation described in the Needed section to resolve the issue directly. Target area: ``enforcer/src/main/kotlin/io/mazewall/seccomp/PureJavaBpfEngine.kt``
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
