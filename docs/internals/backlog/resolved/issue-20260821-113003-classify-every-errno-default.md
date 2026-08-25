---
title: "Classify every errno default as an allow list"
severity: "MEDIUM"
status: "resolved"
priority: medium
dependencies: []
component: "enforcer"
target_modules:
  - ":enforcer"
target_files:
  - "enforcer/src/main/kotlin/io/mazewall/Policy.kt"
effort: "small"
autonomy: "autonomous"
related_pr: 512
related_thread: 3819751071
---

# 🟡 [Severity: MEDIUM]: Classify every errno default as an allow list

**Review (2026-08-21):** ALREADY FIXED: Policy.mode uses `defaultAction is ACT_ERRNO || defaultAction == ACT_ERRNO`, which covers custom errno instances.

**Context:** This equality check recognizes only the `ACT_ERRNO` companion, whose equality is limited to the default `EPERM` value. A policy built with `defaultAction(SeccompAction.ACT_ERRNO(EACCES))`, or any other custom errno, denies every unspecified syscall but is reported as `DENY_LIST`; `InstallationAssessment` repeats the same check.

**Problem:**
- Equality check only recognizes ACT_ERRNO(EPERM)
- Custom errno values not recognized
- Policy with ACT_ERRNO(EACCES) reported as DENY_LIST
- InstallationAssessment has same issue

**Impact:**
- Incorrect classification of policies
- DENY_LIST reported for custom errno defaults

**Needed:**
1. Test action type rather than equality with EPERM singleton
2. Fix InstallationAssessment classification
3. Handle all ACT_ERRNO variants

**Codex PR Comment:** https://github.com/Pilleo/mazewall/pull/512#discussion_r3819751071
