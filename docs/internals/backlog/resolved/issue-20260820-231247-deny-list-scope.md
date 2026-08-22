---
title: "Downgrade read-only deny lists to thread-local scope"
severity: "LOW"
status: "resolved"
priority: medium
component: "enforcer"
dependencies: []
target_modules:
  - ":enforcer"
target_files:
  - "enforcer/src/main/kotlin/io/mazewall/PolicyLists.kt"
effort: "medium"
autonomy: "autonomous"
---

# Downgrade Read-Only Deny Lists to Thread-Local Scope

**Source:** Codex PR review comment (P2)
**Created:** 2026-08-20

## Problem

When `Policy.denyList { readOnly("/path") }` is used, `allowFsRead` returns a `Builder<ThreadLocalOnly>`, but this method discards that narrowed return value and the spec later returns the original builder as `Policy<ProcessWideSafe, ...>`. The resulting Landlock policy therefore passes the compile-time requirement of `installOnProcess` even though the scope hierarchy explicitly classifies filesystem rules as thread-local.

## Impact

- Thread-local filesystem rules in process-wide policy
- Compile-time type safety bypassed
- Potential for incorrect Landlock scope

## Solution

Retain the narrowed builder type or expose this operation through a thread-local policy factory.

## Related Files

- `enforcer/src/main/kotlin/io/mazewall/PolicyLists.kt` - Line 72
