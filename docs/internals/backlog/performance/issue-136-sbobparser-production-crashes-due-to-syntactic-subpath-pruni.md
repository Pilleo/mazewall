---
title: "`SbobParser` Production Crashes due to Syntactic Subpath Pruning of Unresolved/Symlinked Paths"
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

# 🔴 [Severity: HIGH]: `SbobParser` Production Crashes due to Syntactic Subpath Pruning of Unresolved/Symlinked Paths

**Context:**
**Hypothesis:** The `SbobParser` aggregates file paths. If a path contains unresolved components (`.`, `..`) or symlinks, simple string-prefix matching can incorrectly classify one path as a subset of another.

Consider `allowedFsReadPaths`. If the profiler observed access to `/opt/app/../etc/passwd` and `/opt/app`, string prefix logic might prune `/opt/app/../etc/passwd` because it starts with `/opt/app`. The resulting policy would only allow `/opt/app`, and when the application tries to access the `passwd` file, it will be denied.


**Needed:**
1. `SbobParser` must strictly perform `Path.normalize()` and ideally `toRealPath()` before doing prefix comparisons to ensure accurate hierarchy evaluation.

## Solution Options

### Option A — Refactor implementation
Implement the recommendation described in the Needed section to resolve the issue directly. Target area: ``profiler/src/main/kotlin/io/mazewall/profiler/compiler/SbobParser.kt``
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
