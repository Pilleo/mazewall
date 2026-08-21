---
title: "Report already-active Landlock in repeat-install receipts"
severity: "MEDIUM"
status: "open"
priority: medium
dependencies: []
component: "enforcer"
target_modules:
  - ":enforcer"
target_files:
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/api/ContainedExecutors.kt"
effort: "small"
autonomy: "autonomous"
related_pr: 512
related_thread: 3823789313
---

# 🟡 [Severity: MEDIUM]: Report already-active Landlock in repeat-install receipts

**Context:** When a caller installs the same Landlock policy again, `applyLandlockIfNecessary()` returns `UNCHANGED` here and leaves `landlockSuccessfullyApplied` false, so the successful receipt reports `landlockApplied=false` even though the registry and kernel still have the irreversible rules active. This makes the new diagnostic/attestation field unreliable.

**Problem:**
- applyLandlockIfNecessary returns UNCHANGED
- landlockSuccessfullyApplied remains false
- Receipt reports landlockApplied=false
- But Landlock rules are actually active

**Impact:**
- landlockApplied field unreliable
- Diagnostic/attestation incorrect

**Needed:**
1. Track whether Landlock rules are actually active
2. Report landlockApplied based on registry/kernel state
3. Make attestation reliable

**Codex PR Comment:** https://github.com/Pilleo/mazewall/pull/512#discussion_r3823789313
