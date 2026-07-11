---
title: "SbobParser Production Crashes due to Syntactic Subpath Pruning of Unresolved/Symlinked Paths"
severity: "HIGH"
status: "open"
priority: 9
dependencies: []
component: "unknown"
effort: "medium"
autonomy: "supervised"
solution_approved: false
blast_radius: "medium"
reversible: true
---

# 🔴 [Severity: HIGH]: SbobParser Production Crashes due to Syntactic Subpath Pruning of Unresolved/Symlinked Paths

**Context:**
**Hypothesis:** SbobParser's subpath pruning operates purely syntactically without resolving symlinks. If a staging environment contains a symlinked directory and a real nested directory, pruning will discard the nested path. When the parsed policy is applied, the symlink is rejected, and because the nested path was pruned, the entire tree is left blocked, causing production application crashes.

In `SbobParser.kt`, `pruneSubpaths` syntactically normalizes and sorts path strings. If a profiled workload accessed both `/var/log` (a symlink) and `/var/log/app` (a real directory), the SBoB JSON lists both. `pruneSubpaths` prunes `/var/log/app` because it syntactically starts with `/var/log`. In production, when `Landlock.addRule` is invoked for `/var/log`, `O_NOFOLLOW` triggers a symlink rejection `ELOOP`, so the rule is skipped and no filesystem rule is added. Since `/var/log/app` was pruned, no rule is added for `/var/log/app` either. The application is completely blocked from accessing `/var/log/app` and crashes.


**Needed:**
1. Implement a fix based on the issue description.

## Solution Options

### Option A — Refactor implementation
Implement the recommendation described in the Needed section to resolve the issue directly. Target area: `Unknown`
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
