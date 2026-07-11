---
title: "Inconsistent Architecture Test for `java.lang.foreign`"
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

# 🔴 [Severity: LOW]: Inconsistent Architecture Test for `java.lang.foreign`

**Context:**
**Hypothesis:** The ArchUnit tests ban `java.lang.foreign.MemorySegment.reinterpret`, `Arena.ofAuto`, and `MemorySegment.get`, but they do not generally ban the import and usage of `java.lang.foreign` outside of `io.mazewall.ffi`.

`grep -rn "java.lang.foreign" enforcer/src/main/kotlin/io/mazewall/ | grep -v "/ffi/"` returns many hits. The `ArchitectureTest.kt` lacks a strict package boundary check for the `java.lang.foreign` package.


**Needed:**
1. Add an ArchUnit test: `noClasses().that().resideOutsideOfPackage("io.mazewall.ffi..").should().dependOnClassesThat().resideInAPackage("java.lang.foreign..")` to enforce the constraint defined in `architectural_map.md`.

## Solution Options

### Option A — Refactor implementation
Implement the recommendation described in the Needed section to resolve the issue directly. Target area: ``enforcer/src/test/kotlin/io/mazewall/ArchitectureTest.kt``
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
