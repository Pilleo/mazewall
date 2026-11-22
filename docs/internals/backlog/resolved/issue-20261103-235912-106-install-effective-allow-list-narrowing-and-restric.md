---
title: "Install effective allow-list narrowing and restrictive default escalation"
severity: "HIGH"
status: "resolved"
priority: high
dependencies: ["issue-20261103-235912-401-use-canonical-action-ordering-throughout-installed"]
component: "core"
target_modules: [":enforcer"]
target_files:
   - "enforcer/src/main/kotlin/io/mazewall/enforcer/engine/FilterInstallationPlanner.kt"
   - "enforcer/src/main/kotlin/io/mazewall/enforcer/state/ContainerState.kt"
   - "enforcer/src/main/kotlin/io/mazewall/enforcer/state/ContainmentStateRegistry.kt"
   - "enforcer/src/main/kotlin/io/mazewall/enforcer/state/ContainmentRegistryEffect.kt"
   - "enforcer/src/test/kotlin/io/mazewall/enforcer/FilterInstallationPlannerTest.kt"
   - "enforcer/src/test/kotlin/io/mazewall/enforcer/ContainmentStateRegistryTest.kt"
target_symbols:
  - "calculateNewFilter"
open_questions: false
---

# 🟡 [Severity: HIGH]: Install effective allow-list narrowing and restrictive default escalation

**Context:**
Repository-wide review, 2026-11-04: [BPF-01](../../reviews/2026-11-04-repository-review-findings.md#bpf-01). The source review and reproductions below are not release qualification or implementation approval. Historical resolved issues remain archived; this is their newly scoped residual where applicable.

**P1 · confirmed correctness/security defect, host-reproduced · high confidence · core explicit process/thread installation. NEW residual of the resolved stacking work.**

**Trigger:** Install `ProcessPolicyBuilder.allowList(HOTSPOT_JIT).allow(GETPPID).build()`, then the same allow-list without GETPPID. Derive state through the production planner and publication function, not an invented test state. The second plan says `needsNewFilter=false`, although the first program returns ALLOW and the requested program returns ERRNO for GETPPID. The same omission affects LOG-default→ERRNO-default with unchanged explicit ALLOW membership, and stronger restrictive defaults with unchanged ALLOW sets.

**Evidence:** `enforcer/src/main/kotlin/io/mazewall/enforcer/engine/FilterInstallationPlanner.kt:58-76,84-103` compares current actions through `state.syscallActions[sys] ?: state.defaultAction`, but `state/ContainerState.kt:68-95` retains only `newBlocks`, losing initial explicit ALLOW exceptions while separately remembering them in `allowedSyscalls`. For a same-default narrowing, `:73` compares ERRNO with the same ERRNO and adds no block. `:86-97` also treats an unchanged ALLOW set as proof that a stronger default is redundant. `api/ContainedExecutors.kt:377-388` then returns a default-success receipt without native installation or self-verification.

**Effect:** A permission explicitly removed by a subsequent policy can remain allowed while `requireInstalled()` succeeds. LOG→ERRNO is particularly clear: LOG still permits the syscall, so this is not merely a changed error code.

**Correction:** Represent cumulative default/exception actions faithfully and compare effective old/new actions, including non-ALLOW but permissive LOG/conditional TRACE, before deduplicating. Preserve explicit ALLOW exceptions under restrictive defaults and the safety floor. When equivalence cannot be proved, install the needed full policy; do not infer equivalence from ALLOW-set containment alone. Preserve identical-policy dedup/depth behavior.

**Regression:** Use actual `calculateNewFilter`→`withNewSeccompPolicy` evolution for same-default whitelist removal, LOG→ERRNO, ERRNO→KILL with identical explicit ALLOW sets, plus identical/superset controls and process/thread merge variants. Compare effective compiled actions independently. Then qualify a benign acquired syscall in a fresh JVM with distinguishable denial evidence.

**Mapping/dependencies:** Related resolved `issue-105-nested-seccomp-stacking-security-containment-bypass-on-alrea.md` and the whitelist note appended to resolved `issue-107-seccomp-filter-bypass-via-pkeymprotect.md`; no matching active correction found. Current `FilterInstallationPlannerTest.kt:123-143` uses `defaultAction=ALLOW` with a hand-populated allowed set, missing the real restrictive-default publication path. Fix before further optimizer/performance claims; coordinate action-ordering correction BPF-03.

**Needed:**
1. Implement only the bounded correction described above, first reproducing its trigger with the stated negative and positive controls. Preserve all ownership, failure and compatibility invariants; run owning focused checks and the project completion gate before closure. Keep native, hosted and published-artifact qualification separate from source-only or isolated evidence.

## Effective-action design follow-up — 2026-11-05

[Recursive review A-01](../../reviews/2026-11-05-state-complexity-review.md)
revalidates the existing owner and adds an explicit-versus-opposite-default
acceptance case. `ContainmentStateRegistry.kt:100–108` merges explicit entries
without comparing them with the other state's default. For a restrictive process
default and a thread LOG exception, that projection can retain LOG where the
stacked action is restrictive. This is source analysis, not a newly executed
kernel schedule; supported installation ordering remains part of qualification.

- Keep one authoritative effective default/exception projection for planner
  satisfaction, registry merge and union-aware self-verification. Derive ALLOW
  membership rather than independently updating it.
- Intersect each explicit syscall with the opposite state's effective action,
  including its default—not only another explicit map entry. Add both-direction
  restrictive-default versus LOG/ALLOW-exception cases, and compare generated
  planner/publication histories against independently intersected compiled verdicts.
- An internal `AlreadySatisfied | Install(fragment, nextProjection)` result can
  bind the fragment and next projection without a generic machine framework.
  Keep native effects outside this pure decision and precompute publication before
  irreversible installation; publish only at the actual applied boundary.
- Preserve argument-inspection/JVM-floor rules, payload tie behavior, unknown
  syscall defaults, quarantine, filter depth and existing supported APIs. A
  syscall-only projection cannot certify argument-level equivalence.
- Keep `ContainerState` an immutable snapshot, not another lifecycle hierarchy;
  its [resolved snapshot decision](issue-20260821-150006-container-state-is-snapshot-not-lifecycle.md)
  remains valid. Existing canonical-ordering dependency is unchanged.

## Resolution Evidence (2026-11-21)
- Kept `ContainerState` as an immutable snapshot with authoritative `(defaultAction, syscallActions)` projection (`effectiveAction`, derived `allowedSyscalls`, and `withAppliedFilter`) while intersecting explicit entries against the opposite state's effective action/default in `ContainmentStateRegistry.mergeStates` and publishing only the applied fragment in `ContainmentRegistryEffect.SeccompInstalled`.
- Added closed `FilterInstallationPlanner.PlanDecision` (`AlreadySatisfied` | `Install(fragment, nextProjection, newBlocks, newDefaultAction)`) using `SeccompAction.stricter`, installing narrowed allow-list blocks and stricter default escalations (`ACT_LOG -> ACT_ERRNO`, `ACT_ERRNO -> ACT_KILL_*`, explicit `ACT_LOG` exception removal) while preserving first-policy payload tie-breaking (`ACT_ERRNO(EACCES)` <-> `ACT_ERRNO(EPERM)`, `ACT_TRACE(1)` <-> `ACT_TRACE(2)` remain `AlreadySatisfied`), argument-inspection rules, and JVM-floor protections.
- Wired `ProcessFilterInstaller` and `ContainedExecutors` through `FilterInstallationPlanner.plan` and `ContainmentRegistryEffect.SeccompInstalled`, eagerly initialized `BpfFilter` comparators (`bySyscallNr`, `byKernelReturnCode`) to prevent lazy comparator classloading after `openat`-denying filters, aligned union-aware `SelfVerificationPlan` with unconditional `clone3 -> ENOSYS`, `BpfFilter.getJvmCriticalNrs(arch)` allow floor, stacked `UnsafePrctlInspector`/`MmapExecInspector` protections, and valid `nr >= 0` checks, and warmed the union self-verification path in `InstallSelfVerifier.warmup()`.
- Added regression tests across `FilterInstallationPlannerTest.kt` (AMD64/AArch64 compiled-BPF equivalence, thread-vs-process merge, and payload tie preservation), `ContainmentStateRegistryTest.kt` (bidirectional opposite-default merge), `ContainmentRegistryEffectTest.kt`, `SelfVerificationPlanTest.kt` (AMD64/AArch64 union oracle invariants), and live kernel `@NeedsFreshJvm` tests in `AllowListTest.kt`.
- Verified:
  - `./gradlew :enforcer:ktlintCheck :lockdown:ktlintCheck :enforcer:checkKotlinAbi :lockdown:checkKotlinAbi --console=plain` (`BUILD SUCCESSFUL`).
  - `./gradlew :enforcer:integrationTestFreshJvm --tests "io.mazewall.seccomp.AllowListTest" --console=plain` (`4` tests, `0` skipped, `0` failures).
  - `./gradlew :enforcer:test :lockdown:test :lockdown:integrationTestFreshJvm --console=plain` (`:enforcer:test`: `1670` tests, `2` pre-existing `LandlockFailureModeTest` assumption-skipped, `0` failures; `:lockdown:test`: `17` tests, `0` failures; `:lockdown:integrationTestFreshJvm`: `2` tests, `0` failures).
  - `./gradlew :lockdownConsumerCheck --console=plain` (`Executed: 1`, `Skipped: 0`, `Failed: 0`).
- Full completion gate verified in commits `52e81d2e1` and `7e62bacf7` (`./gradlew build -PmazewallKernelLane=BASELINE` and rootful Docker `CONFINED` kernel lane passed).

---
<!-- id: issue-20261103-235912-106-install-effective-allow-list-narrowing-and-restric  file: issue-20261103-235912-106-install-effective-allow-list-narrowing-and-restric.md -->
