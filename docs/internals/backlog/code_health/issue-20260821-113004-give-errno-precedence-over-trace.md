---
title: "Give ERRNO precedence over TRACE during intersection"
severity: "MEDIUM"
status: "open"
priority: medium
dependencies: []
component: "enforcer"
target_modules:
  - ":enforcer"
target_files:
  - "enforcer/src/main/kotlin/io/mazewall/PolicyDefinition.kt"
effort: "small"
autonomy: "autonomous"
related_pr: 512
related_thread: 3825290323
---

# 🟡 [Severity: MEDIUM]: Give ERRNO precedence over TRACE during intersection

**Context:** When one policy assigns `ACT_TRACE` and another assigns `ACT_ERRNO` to the same syscall, both actions have priority 4, so this `maxBy` tie selects whichever policy appears first. Consequently `restrictFurtherWith(tracePolicy, denyPolicy)` can retain `ACT_TRACE`; under a ptracer that action may permit or rewrite the syscall, making the advertised restriction weaker.

**Problem:**
- ACT_TRACE and ACT_ERRNO both have priority 4
- maxBy tie selects first policy
- restrictFurtherWith can retain ACT_TRACE
- TRACE may permit/rewrite syscall

**Impact:**
- Restriction may be weaker than intended
- ACT_TRACE can bypass ACT_ERRNO

**Needed:**
1. Give ERRNO precedence over TRACE in tie-breaking
2. Ensure restrictFurtherWith produces strictest result
3. Handle action type precedence correctly

**Codex PR Comment:** https://github.com/Pilleo/mazewall/pull/512#discussion_r3825290323
