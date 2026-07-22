---
title: "Incomplete FFM Architecture Isolation"
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
github_issue: 161
---

# 🔴 [Severity: LOW]: Incomplete FFM Architecture Isolation

**Context:**
**Hypothesis:** FFM (`java.lang.foreign`) MemorySegments and Arenas are bleeding outside the `io.mazewall.ffi` boundary.

`JVMValidationListener.start` and `runValidationReactor` in `SupervisorInstaller.kt` directly use `Arena.ofShared()` and manipulate memory allocation logic for the `SupervisorResponseSegment`. According to `docs/internals/architectural_map.md#7-core-architectural-paradigms--patterns`, all raw memory/FFM manipulation must be isolated to `io.mazewall.ffi`.


**Needed:**
1. Move the raw `Arena` and `SupervisorResponseSegment` lifecycle management into a dedicated class inside the `io.mazewall.ffi` package, exposing a safe, higher-level interface to the `enforcer.supervisor` package.

## Solution Options

### Option A — Refactor implementation
Implement the recommendation described in the Needed section to resolve the issue directly. Target area: ``enforcer/src/main/kotlin/io/mazewall/enforcer/supervisor/SupervisorInstaller.kt``
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
