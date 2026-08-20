---
title: "Preserve explicit open denials during restrictive policy composition"
severity: "HIGH"
status: "open"
priority: high
dependencies: []
component: "enforcer"
target_modules:
  - ":enforcer"
target_files:
  - "enforcer/src/main/kotlin/io/mazewall/Policy.kt"
  - "enforcer/src/main/kotlin/io/mazewall/PolicyDefinition.kt"
effort: "medium"
autonomy: "autonomous"
related_pr: 512
related_thread: PRRC_kwDOScnnEM6H6V
---

# 🔴 [Severity: HIGH]: Preserve explicit open denials during restrictive composition

**Context:** When composing policies with `restrictFurtherWith()`, if one input explicitly blocks `OPEN`/`OPENAT`/`OPENAT2` and another enables Landlock for `/tmp`, the current implementation delegates to `PolicyDefinition.combine()` which unconditionally replaces all three effective actions with `ACT_ALLOW` when Landlock is present (lines 112-118). The result permits opens under `/tmp`, making `restrictFurtherWith()` more permissive than the first input despite its documented contract.

**Problem:**
- `combine()` doesn't preserve explicit open denials when Landlock is present
- Landlock presence causes OPEN syscalls to be set to ACT_ALLOW
- Restrictive composition becomes more permissive, violating the contract

**Impact:**
- Security: explicit denials can be overridden by Landlock presence
- Composition violates "strictest wins" principle

**Needed:**
1. Preserve explicit denials for OPEN/OPENAT/OPENAT2 even when Landlock is present
2. Update `PolicyDefinition.combine()` to not override denials with ACT_ALLOW
3. Consider: should Landlock presence only affect path-based restrictions, not syscall-level denials?

**Codex PR Comment:** https://github.com/Pilleo/mazewall/pull/512#discussion_r3819861566
