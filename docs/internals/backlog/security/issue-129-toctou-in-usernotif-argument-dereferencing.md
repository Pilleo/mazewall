---
title: "TOCTOU in `USER_NOTIF` Argument Dereferencing"
severity: "HIGH"
status: "open"
priority: 9
dependencies: []
component: "profiler"
effort: "small"
autonomy: "supervised"
solution_approved: false
blast_radius: "medium"
reversible: true
---

# 🔴 [Severity: MEDIUM]: TOCTOU in `USER_NOTIF` Argument Dereferencing

**Context:**
**Hypothesis:** When the profiler daemon receives a `SECCOMP_NOTIF` event, it reads memory from the target process using `process_vm_readv`. A malicious or concurrent thread in the target process could modify the memory arguments (e.g., a file path string) *after* the profiler reads it but *before* the kernel executes the syscall.

This is a classic Time-of-Check to Time-of-Use (TOCTOU) vulnerability inherent in `ptrace` or `USER_NOTIF` architectures where arguments are passed by reference (pointers). The profiler might log or allow an action based on `path_A`, but the kernel might actually execute the syscall on `path_B`.


**Needed:**
1. Document this inherent limitation of `USER_NOTIF` profiling. Mention that `Landlock` is the preferred mechanism for robust, race-free filesystem restriction since it evaluates paths in the kernel space safely.

## Solution Options

### Option A — Refactor implementation
Implement the recommendation described in the Needed section to resolve the issue directly. Target area: ``profiler/src/main/kotlin/io/mazewall/profiler/strace/StraceProfiler.kt``
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
