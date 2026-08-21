---
title: "Report already-active Landlock in repeat-install receipts"
severity: "LOW"
status: "open"
priority: medium
component: "enforcer"
dependencies: []
target_modules:
  - ":enforcer"
target_files:
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/api/ContainedExecutors.kt"
effort: "small"
autonomy: "autonomous"
---

# Report Already-Active Landlock in Repeat-Install Receipts

**Source:** Codex PR review comment (P2)
**Created:** 2026-08-20

## Problem

When a caller installs the same Landlock policy again, `applyLandlockIfNecessary()` returns `UNCHANGED` and leaves `landlockSuccessfullyApplied` false, so the successful receipt reports `landlockApplied=false` even though the registry and kernel still have the irreversible rules active. This makes the new diagnostic/attestation field unreliable for idempotent installations.

## Impact

- Misleading diagnostic information
- Cannot distinguish "no Landlock needed" from "Landlock already active"
- Unreliable attestation

## Solution

Distinguish an already-active ruleset from a policy that requires no Landlock. Initialize the receipt flag from the recorded state or set it to true when Landlock is already in force.

## Related Files

- `enforcer/src/main/kotlin/io/mazewall/enforcer/api/ContainedExecutors.kt` - Line 251
