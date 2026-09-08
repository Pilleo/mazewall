---
title: "ERRNO/TRACE intersection tie-breaker misses ACT_ERRNO companion and defaultAction"
severity: "MEDIUM"
status: "resolved"
priority: high
dependencies: []
component: "enforcer"
target_modules:
  - ":enforcer"
target_files:
  - "enforcer/src/main/kotlin/io/mazewall/PolicyDefinition.kt"
  - "enforcer/src/test/kotlin/io/mazewall/PolicyTest.kt"
effort: "small"
autonomy: "autonomous"
paperclip_issue_id: 1f4cb647-755f-4d6b-8f85-bb9bd2d4d41b
paperclip_identifier: MAZ-564
---

# 🟡 [Severity: MEDIUM]: ERRNO/TRACE intersection tie-breaker misses ACT_ERRNO companion and defaultAction

**Review (2026-08-21):** DUPLICATE of issue-20260821-113004-give-errno-precedence-over-trace (fixed with INTERSECTION_ORDER).

**Context:** Follow-on to `issue-20260821-113004-give-errno-precedence-over-trace`. Ranking by `is ACT_ERRNO` misses `Policy.block()`'s companion. Ranking by `nativeCode` with `maxWith` prefers TRACE.

**Fix:** Shared `INTERSECTION_ORDER` in `PolicyDefinition`: higher `priority`, then companion-or-instance ERRNO over TRACE. Used by `syscallActionNumbers`, `combine` syscalls, and `combinedDefaultAction`. Tests cover `block()` vs `ACT_TRACE` and errno vs TRACE defaults, both orders.
