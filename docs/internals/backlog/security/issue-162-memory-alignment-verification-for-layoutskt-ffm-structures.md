---
title: "Memory Alignment verification for `Layouts.kt` FFM Structures"
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

# 🔴 [Severity: LOW]: Memory Alignment verification for `Layouts.kt` FFM Structures

**Context:**
**Hypothesis:** `Layouts.kt` manually specifies C struct memory layouts using `java.lang.foreign.MemoryLayout`. Does it perfectly match the Linux C ABI on x86_64?

We wrote a C program and a Java program to verify `sizeof` and `offsetof` for `msghdr`, `cmsghdr`, `seccomp_data`, `seccomp_notif`, `seccomp_notif_resp`, and `seccomp_notif_addfd`. The sizes and offsets in Java exactly matched the sizes and offsets in C. No issues found in standard FFM layout alignment for x86_64.


**Needed:**
1. Continue to verify cross-compilation/aarch64 alignments if applicable, but x86_64 layouts are verified correct.

## Solution Options

### Option A — Refactor implementation
Implement the recommendation described in the Needed section to resolve the issue directly. Target area: ``enforcer/src/main/kotlin/io/mazewall/ffi/Layouts.kt``
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
