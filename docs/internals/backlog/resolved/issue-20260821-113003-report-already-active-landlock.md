---
title: "Report already-active Landlock in repeat-install receipts"
severity: "MEDIUM"
status: "resolved"
priority: medium
dependencies: []
component: "enforcer"
target_modules:
  - ":enforcer"
target_files:
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/api/ContainedExecutors.kt"
  - "enforcer/src/main/kotlin/io/mazewall/InstallationReceipt.kt"
effort: "small"
autonomy: "autonomous"
related_pr: 512
related_thread: 3823789313
---

# 🟡 [Severity: MEDIUM]: Report already-active Landlock in repeat-install receipts

**Review (2026-08-21):** Still present as a **receipt/attestation** bug, not a missing Landlock apply. Empty-path intersection apply is a different (already fixed) issue.

**Current tree:** `applyLandlockIfNecessary` returns `UNCHANGED` when a ruleset is already in the registry and paths match. `landlockSuccessfullyApplied` stays `false`. `installSeccompFilter(..., landlockApplied=false)` then builds a success receipt with `landlockApplied=false` even though `ContainmentStateRegistry.*.landlockPolicy != null` and the kernel ruleset is irreversible. The **failure** path already computes `landlockInForce` from the registry; the **success / UNCHANGED** path does not.

**Do not:**
- Re-apply Landlock on every install to flip the flag (Landlock cannot be undone; nested apply is a different API).
- Set `landlockApplied=true` whenever `enforceLandlock` is true even if tryApply was skipped and no ruleset exists.
- Treat this as “empty intersection doesn’t install Landlock” (`000011` is already fixed).

**Do:**
1. On `LandlockStep.UNCHANGED`, set the receipt from registry/kernel state: `landlockApplied = state.landlockPolicy != null` (same idea as `landlockInForce` on the failure path).
2. Keep `APPLIED → true`, `BYPASSED → installed=false` as today.

**Tests:** Install a Landlock policy twice on a mock/registry-preloaded thread state. Second receipt: `installed` as today, `landlockApplied=true`. Policy with no Landlock: `landlockApplied=false`.

**Codex:** https://github.com/Pilleo/mazewall/pull/512#discussion_r3823789313
