---
title: "Apply Landlock for empty restrictive intersections to deny all paths"
severity: "HIGH"
status: "resolved"
priority: high
dependencies: []
component: "enforcer"
target_modules:
  - ":enforcer"
target_files:
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/api/ContainedExecutors.kt"
  - "enforcer/src/main/kotlin/io/mazewall/PolicyDefinition.kt"
effort: "medium"
autonomy: "autonomous"
related_pr: 512
related_thread: PRRC_kwDOScnnEM6H6P
---

# 🔴 [Severity: HIGH]: Apply Landlock for empty restrictive intersections

**Review (2026-08-21):** ALREADY FIXED: needsLandlock is policy.enforceLandlock (empty paths still apply); PolicyTest covers disjoint intersection + enforceLandlock.

**Context:** When policy composition produces `enforceLandlock=true` with empty read and write sets (such as intersecting disjoint filesystem grants), the installer returns `UNCHANGED`, so no Landlock ruleset is installed. Meanwhile, `PolicyDefinition.combine()` permits the open syscalls (lines 112-118). The result allows unrestricted filesystem access instead of denying every path.

**Problem:**
- `needsLandlock(policy)` only checks `policy.enforceLandlock`
- `applyLandlockIfNecessary` checks `isDifferent` which may be false when paths are empty
- `combine()` sets OPEN/OPENAT/OPENAT2 to ACT_ALLOW when enforceLandlock is true
- With empty paths, Landlock ruleset handles all categories but has no rules = default deny
- But installer doesn't apply Landlock, allowing unrestricted access

**Impact:**
- Security: filesystem access not restricted when it should be
- Empty path intersection bypasses Landlock enforcement

**Needed:**
1. Apply Landlock when `policy.enforceLandlock` is true regardless of path emptiness
2. Ensure `needsLandlock` or installer logic doesn't skip Landlock for empty paths
3. Consider: should empty path intersection with enforceLandlock=true deny all access?

**Codex PR Comment:** https://github.com/Pilleo/mazewall/pull/512#discussion_r3819861561
