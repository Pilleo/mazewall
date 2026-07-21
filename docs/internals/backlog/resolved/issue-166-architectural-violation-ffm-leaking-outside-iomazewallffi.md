---
title: "Architectural Violation - FFM Leaking Outside `io.mazewall.ffi`"
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
github_issue: 149
---

# 🔴 [Severity: MEDIUM]: Architectural Violation - FFM Leaking Outside `io.mazewall.ffi`

**Context:**
**Hypothesis:** `docs/internals/architectural_map.md` strictly dictates that "all raw memory/FFM/Unsafe manipulations isolated to `io.mazewall.ffi`".

`grep -rn "java.lang.foreign" enforcer/src/main/kotlin/io/mazewall/ | grep -v "/ffi/"` reveals extensive usage of `java.lang.foreign.MemorySegment`, `Arena`, and `ValueLayout` in high-level classes like `Landlock.kt`, `SupervisorSessionHandler.kt`, and `LinuxNative.kt`. This completely violates the ArchUnit architectural constraint.


**Needed:**
1. Move all direct FFM memory allocations (`Arena.ofConfined().use { ... }`) and native struct manipulations into dedicated wrapper classes inside `io.mazewall.ffi`. The outer layers (`enforcer`, `landlock`, etc.) should only interact with safe Kotlin types (ByteArrays, Strings, domain objects).

## Solution Options

### Option A — Refactor implementation
Implement the recommendation described in the Needed section to resolve the issue directly. Target area: `Multiple modules, e.g. `LinuxNative.kt`, `Landlock.kt`, `SupervisorSessionHandler.kt`, `SupervisorDaemonManager.kt`, `SeccompInstallationState.kt`, etc.`
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
