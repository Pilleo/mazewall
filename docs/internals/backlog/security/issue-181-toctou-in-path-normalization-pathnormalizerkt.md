---
title: "TOCTOU in Path Normalization `PathNormalizer.kt`"
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

# 🔴 [Severity: MEDIUM]: TOCTOU in Path Normalization `PathNormalizer.kt`

**Context:**
**Hypothesis:** `PathNormalizer.normalizeAndPrune` uses `Path.normalize()` which is purely syntactic and does not resolve symlinks dynamically at the kernel level.

The method states "resolves all paths... and prunes redundant subpaths". However, it only uses `normalize()` (which removes `..` and `.`), not `toRealPath()` or `toAbsolutePath()`. If a path `/opt/app/../etc/passwd` is specified, `normalize()` resolves it to `/etc/passwd`. But if `/opt/app` was a symlink to `/var/app` and `/var` was allowed, the string-based parsing is ignorant of the actual kernel VFS topology. This can create vulnerabilities where an attacker constructs paths that look harmless syntactically but point to sensitive locations via symlinks. The `Landlock.applyUserRules` then registers these string paths into the kernel.


**Needed:**
1. `PathNormalizer` should strictly document that it performs static analysis, and if real security guarantees are needed, it should invoke `Path.toRealPath()` to resolve symlinks before pruning, or rely entirely on kernel-level Landlock rules (which handle symlink resolution dynamically, though Landlock `openat` flags do not follow symlinks by default unless `O_NOFOLLOW` is omitted).

## Solution Options

### Option A — Refactor implementation
Implement the recommendation described in the Needed section to resolve the issue directly. Target area: ``enforcer/src/main/kotlin/io/mazewall/sbob/PathNormalizer.kt``
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
