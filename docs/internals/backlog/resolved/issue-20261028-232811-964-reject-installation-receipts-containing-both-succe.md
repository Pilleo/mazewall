---
title: "Reject installation receipts containing both success and failure"
severity: "MEDIUM"
status: "resolved"
priority: high
dependencies: []
component: "core"
target_modules:
  - ":enforcer"
target_files:
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/api/InstallationReceipt.kt"
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/api/InstallationProgress.kt"
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/api/ContainedExecutors.kt"
  - "enforcer/src/test/kotlin/io/mazewall/InstallationReceiptTest.kt"
  - "enforcer/src/test/kotlin/io/mazewall/RequiredInstallationReceiptTest.kt"
target_symbols:
  - "InstallationReceipt"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Reject installation receipts containing both success and failure

**Context:**
Host-only reproduction: InstallationReceipt(installed=true,failure=sentinel).requireInstalled returns the receipt rather than throwing the recorded failure. Its constructor defaults to success and permits independent contradictory fields. Current production producers appeared consistent; no live installer failure was demonstrated.

**Needed:**
1. Reject recorded failure before accepting success and constrain receipt outcome construction without conflating partial irreversible progress with overall success. Preserve original failure identity, cleanup ownership, fallback behavior and inherited-stage semantics. Add contradictory-state and partial-installation regressions; review all ContainedExecutors producers and Java callers, with explicit approval for breaking public API changes.

**Design-review follow-up (2026-11-03, finding 5):**
- `InstallationReceipt.kt:20-31,49-60` still permits `installed=true` with a non-null failure and accepts that combination in `requireInstalled`.
- Begin with failure-first rejection. Represent overall outcome with controlled `Installed`, `Failed(cause, progress)` and explicit `Bypassed(reason, progress)` alternatives, retaining public compatibility projections where required.
- Partial irreversible progress stays independent of overall success: failed seccomp after successful Landlock must retain its applied restrictions and original cause. Keep supervisor-registration ownership through rejection/close and preserve CET/current-thread proof requirements.

---
## Repository-review revalidation — 2026-11-04

[LIF-04](../../reviews/2026-11-04-repository-review-findings.md#lif-04) reproduces acceptance
of installed=true with a sentinel failure. Inspected ordinary producers are consistent; no
live installer bypass is claimed. Add contradiction/exact-failure, inherited coverage,
partial irreversible progress and registration-cleanup controls to this existing owner.

---
## Resolution Evidence (2026-11-21)

- Refactored `InstallationReceipt` (`enforcer/src/main/kotlin/io/mazewall/enforcer/api/InstallationReceipt.kt`) around a closed internal `Outcome` hierarchy (`Installed`, `Failed`, `Bypassed`) backed by a `private` primary constructor, while preserving full public binary ABI compatibility (`enforcer/api/enforcer.api` unchanged) via the public compatibility constructor and derived `installed`, `progress`, and `failure` projections.
- Added controlled internal companion factories (`installed`, `failed`, `bypassed`, and contradiction-rejecting `withSupervisorRegistration`) so `installed = true` cannot coexist with a recorded failure; updated `requireInstalled()` to reject `Outcome.Failed`/`Outcome.Bypassed` first with exact failure identity before Landlock/CET checks, and made `close()` detach `supervisorRegistration` before closing.
- Routed all 5 `InstallationReceipt` producer sites in `enforcer/src/main/kotlin/io/mazewall/enforcer/api/ContainedExecutors.kt` through `InstallationReceipt.installed` / `.failed` / `.bypassed`, replaced `LandlockStep` with a sealed hierarchy carrying explicit bypass reasons, removed mutable `InstallationAttempt.failure`, and added `scopeLandlockApplied` in `InstallationProgress.kt`.
- Added Kotlin and Java regressions in `enforcer/src/test/kotlin/io/mazewall/InstallationReceiptTest.kt`, `enforcer/src/test/kotlin/io/mazewall/RequiredInstallationReceiptTest.kt`, and `enforcer/src/test/java/io/mazewall/MazewallJavaApiTest.java` covering contradictory constructor/supervisor rejection, exact failure identity across partial Landlock+Seccomp failure and narrower Landlock bypass, inherited Landlock without new progress, supervisor lease cleanup across `requireInstalled()` rejection, and throwing-registration `close()`.
- Verified sequentially:
  - `./gradlew :enforcer:ktlintCheck :enforcer:detektMain :detektWarningRatchet :enforcer:checkKotlinAbi :verifySupportedApiSurface :verifyPackageTopology --no-daemon --console=plain` -> `BUILD SUCCESSFUL` (`56` tasks, exit `0`).
  - `./gradlew :enforcer:test :portals:worker:test -Pmazewall.test.probeParallelism=1 --no-daemon --console=plain` -> `BUILD SUCCESSFUL` (`:enforcer:test` `1681` tests, `0` failed, `2` skipped; `:portals:worker:test` `49` tests, `0` failed, `0` skipped; `-Pmazewall.test.probeParallelism=1` avoids an unrelated pre-existing `ForeignFdGuard` vs `@Execution(ExecutionMode.CONCURRENT)` child-probe parallel FD race in `:enforcer:test`).

- Full completion gate verified in commits `52e81d2e1` and `7e62bacf7` (`./gradlew build -PmazewallKernelLane=BASELINE` and rootful Docker `CONFINED` kernel lane passed).
---
<!-- id: issue-20261028-232811-964-reject-installation-receipts-containing-both-succe  file: issue-20261028-232811-964-reject-installation-receipts-containing-both-succe.md -->
