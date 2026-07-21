---
title: "Missing ArchUnit test for FFM architecture boundary violations"
severity: "HIGH"
status: "open"
priority: 10
dependencies: ["issue-209", "issue-210", "issue-211", "issue-212"]
component: "enforcer"
effort: "huge"
autonomy: "supervised"
solution_approved: false
blast_radius: "medium"
reversible: true
github_issue: 128
---

# 🔴 [Severity: HIGH]: Missing ArchUnit test for FFM architecture boundary violations

**Context:**
**Hypothesis:** `docs/internals/architectural_map.md` states "ArchUnit Isolation: all raw memory/FFM/Unsafe manipulations isolated to `io.mazewall.ffi`."

As noted in "Architectural Violation - FFM Leaking Outside `io.mazewall.ffi`", there is extensive usage of `java.lang.foreign` outside of the FFM boundary packages. Currently, `ArchitectureTest.kt` does not have an overarching rule checking `noClasses().that().resideOutsideOfPackage("io.mazewall.ffi..").should().dependOnClassesThat().resideInAPackage("java.lang.foreign..")`. Such a test should be added, but it would currently fail.


**Needed:**
1. Implement the ArchUnit test and incrementally refactor `Landlock.kt`, `SupervisorSessionHandler.kt`, `LinuxNative.kt`, etc., so they rely entirely on `io.mazewall.ffi` safe types.

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
