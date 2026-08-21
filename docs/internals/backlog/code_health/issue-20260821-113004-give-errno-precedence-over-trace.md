---
title: "Give ERRNO precedence over TRACE during intersection"
severity: "MEDIUM"
status: "resolved"
priority: medium
dependencies: []
component: "enforcer"
target_modules:
  - ":enforcer"
target_files:
  - "enforcer/src/main/kotlin/io/mazewall/PolicyDefinition.kt"
  - "enforcer/src/test/kotlin/io/mazewall/PolicyTest.kt"
effort: "small"
autonomy: "autonomous"
related_pr: 512
related_thread: 3825290323
---

# 🟡 [Severity: MEDIUM]: Give ERRNO precedence over TRACE during intersection

**Context:** `ACT_ERRNO` and `ACT_TRACE` both have `priority = 4`. `maxBy { priority }` therefore keeps whichever policy appears first. `restrictFurtherWith(tracePolicy, denyPolicy)` can retain TRACE; a ptracer may then allow or rewrite a syscall the other policy denied.

**Public deny API:** `Policy.builder().block(syscall)` stores `SeccompAction.ACT_ERRNO` — the **companion object**, not an `ACT_ERRNO(errno)` data-class instance.

**Do not:**
- Use `action is SeccompAction.ACT_ERRNO` alone. That is **false** for the companion (`companion object : SeccompAction`).
- Rank by `nativeCode`. Those bits are not ordered by restrictiveness. `maxWith(compareBy({ priority }, { nativeCode }))` makes TRACE win because this tree's TRACE code is larger than ERRNO.
- Fix only `syscallActionNumbers` and leave `combine()` / `combinedDefaultAction` on `maxBy { priority }`. Both paths must use the same order.

**Do:**
- Treat an action as ERRNO when `action is SeccompAction.ACT_ERRNO || action === SeccompAction.ACT_ERRNO` (instances including custom errno, plus the companion).
- Comparator: higher `priority` first, then ERRNO outranks TRACE (and any other same-priority action).
- Apply that comparator in `syscallActionNumbers`, `combine` syscall intersection, and `combinedDefaultAction`.

**Tests (required, both argument orders):**
1. `Policy.builder().block(Syscall.OPEN)` intersected with `addAction(ACT_TRACE(1), OPEN)` keeps ERRNO in `combine` and `restrictFurtherWith`.
2. Two policies whose `defaultAction` is `ACT_ERRNO(EACCES)` vs `ACT_TRACE(1)` keep the ERRNO default.

**Codex PR Comment:** https://github.com/Pilleo/mazewall/pull/512#discussion_r3825290323
