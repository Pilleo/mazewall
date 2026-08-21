---
title: "Preserve explicit open denials during restrictive policy composition"
severity: "HIGH"
status: "resolved"
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

**Review (2026-08-21):** WRONG vs current tree: combine() only upgrades OPEN* to ALLOW when current.priority <= ALLOW. Explicit block() is kept. Test `Landlock composition keeps explicit OPEN denials`.

**Review (2026-08-21):** WRONG vs current tree — do not re-implement. `combine()` only sets OPEN* to ALLOW when `current.priority <= ACT_ALLOW.priority`. Explicit `block(OPEN)` (ERRNO, priority 4) is kept. Test: `Landlock composition keeps explicit OPEN denials`.

**Historical context (stale):** An older combine() upgraded OPEN* to ALLOW whenever Landlock was present. That is no longer the code.

**Do not:**
- Remove the Landlock OPEN* ALLOW upgrade entirely without checking `priority` (JVM/Landlock still needs OPEN allowed when the **effective** action was ALLOW so Landlock can see the path).
- Rank ERRNO vs ALLOW by `nativeCode`.

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
