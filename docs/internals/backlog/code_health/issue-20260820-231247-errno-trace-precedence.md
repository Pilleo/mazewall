---
title: "Give ERRNO precedence over TRACE during intersection"
severity: "LOW"
status: "open"
priority: medium
component: "enforcer"
dependencies: []
target_modules:
  - ":enforcer"
target_files:
  - "enforcer/src/main/kotlin/io/mazewall/PolicyDefinition.kt"
effort: "small"
autonomy: "autonomous"
---

# Give ERRNO Precedence Over TRACE During Intersection

**Source:** Codex PR review comment (P2)
**Created:** 2026-08-20

## Problem

When one policy assigns `ACT_TRACE` and another assigns `ACT_ERRNO` to the same syscall, both actions have priority 4, so this `maxBy` tie selects whichever policy appears first. Consequently `restrictFurtherWith(tracePolicy, denyPolicy)` can retain `ACT_TRACE`; under a ptracer that action may permit or rewrite the syscall, making the advertised restrictive intersection more permissive than the errno-denying input, while reversing the arguments produces a different result.

## Impact

- Inconsistent policy composition results
- TRACE actions can override ERRNO actions
- Security policy weaker than expected

## Solution

Give these actions distinct restrictive precedence or explicitly resolve ties toward `ACT_ERRNO`.

## Related Files

- `enforcer/src/main/kotlin/io/mazewall/PolicyDefinition.kt` - Line 84
