---
title: "TOCTOU in Path Normalization under Multi-Threaded I/O"
severity: "HIGH"
status: "open"
priority: 9
dependencies: []
component: "unknown"
effort: "small"
autonomy: "supervised"
solution_approved: false
blast_radius: "medium"
reversible: true
---

# 🔴 [Severity: MEDIUM]: TOCTOU in Path Normalization under Multi-Threaded I/O

**Context:**
**Hypothesis:** A profiled application operates on a directory symlink that is constantly being updated by a sibling thread or background process (e.g. `/app/current -> /app/v1` switching to `/app/v2`). If the Iterative Profiler records the resolved target (`/app/v1/file`), but by the time the `SbobParser` generates the Landlock policy the symlink points to `/app/v2`, the generated policy will hardcode `/app/v1`, denying access to the application in production.

Landlock's absolute path resolution binds strictly to the inode at `addRule` time. Dynamic symlinks or active directory swaps (like Capistrano deployments) break statical Landlock profiling.


**Needed:**
1. Document the incompatibility of Landlock rules with atomic directory symlink swapping, and advise users to profile and restrict the parent umbrella directory (`/app/`) rather than the dynamic target.

## Solution Options

### Option A — Refactor implementation
Implement the recommendation described in the Needed section to resolve the issue directly. Target area: ``io.mazewall.SbobParser``
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
