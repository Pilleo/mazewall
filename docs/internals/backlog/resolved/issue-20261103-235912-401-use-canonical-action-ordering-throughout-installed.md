---
title: "Use canonical action ordering throughout installed policy planning and state"
severity: "HIGH"
status: "resolved"
priority: high
dependencies: ["issue-20261103-235911-795-preserve-linux-user-notif-precedence-over-trace-in"]
component: "core"
target_modules: [":enforcer"]
target_files:
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/state/ContainerState.kt"
target_symbols:
  - "withNewSeccompPolicy"
open_questions: false
---

# 🟡 [Severity: HIGH]: Use canonical action ordering throughout installed policy planning and state

**Context:**
Repository-wide review, 2026-11-04: [BPF-03](../../reviews/2026-11-04-repository-review-findings.md#bpf-03). The source review and reproductions below are not release qualification or implementation approval. Historical resolved issues remain archived; this is their newly scoped residual where applicable.

**P1 · confirmed source defect, host-reproduced · high confidence · core stacked policy enforcement; permitting impact requires an applicable ptracer. NEW residual of known ordering corrections.**

**Trigger:** First plan/publish a default-ALLOW policy with GETPPID→TRACE(7); then request GETPPID→ERRNO(13) with the same flags. The second plan installs nothing. TRACE and ERRNO both have coarse priority 4, despite documented total ranks 40 and 41.

**Evidence:** `FilterInstallationPlanner.kt:50-63,73-84`, `ContainerState.kt:78-82`, and `ContainmentStateRegistry.kt:100-108` still compare `.priority`; `platform/.../SeccompAction.kt:8-11,21-34,78-91` explicitly says intersection must use `restrictivenessRank`/`stricter`, not priority alone. Composition was corrected; planning/state merge was not.

**Effect/correction:** A later errno-denying request can leave TRACE active; an attached permitted ptracer may authorize/rewrite it. Without a tracer, TRACE commonly returns ENOSYS, so a successful syscall bypass was **not** demonstrated. Use the canonical action order at every cumulative/planning comparison and preserve the documented payload tie contract. Do not rank by native return bits.

**Regression/mapping:** Table-test explicit/default TRACE→ERRNO in planner, cumulative publication and thread/process merge, both composition orders, and unchanged controls. Reuse/expand resolved `issue-20260821-113004-give-errno-precedence-over-trace.md`, `issue-20260821-134539-errno-trace-tiebreaker-misses-companion.md` and resolved stacking issue 105; no matching active planner correction found. Coordinate with BPF-01 rather than independently patching symptoms.

**Needed:**
1. Implement only the bounded correction described above, first reproducing its trigger with the stated negative and positive controls. Preserve all ownership, failure and compatibility invariants; run owning focused checks and the project completion gate before closure. Keep native, hosted and published-artifact qualification separate from source-only or isolated evidence.

## Resolution Evidence (2026-11-21)
- Replaced `.priority` comparisons with `.restrictivenessRank()` across `FilterInstallationPlanner.kt`, `ContainerState.kt`, and `ContainmentStateRegistry.kt`, guarded `ContainerState.withNewSeccompPolicy` against weakening existing explicit syscall actions, and removed the remaining `.priority` comparison in `PolicyDefinition.kt`.
- Resolved the canonical `ACT_NOTIFY` over `ACT_TRACE` ordering dependency in `issue-20261103-235911-795`.
- Added table-driven escalation/non-escalation and bidirectional merge tests in `FilterInstallationPlannerTest.kt`, `ContainerStateTest.kt`, and `ContainmentStateRegistryTest.kt`; verified `./gradlew :enforcer:test` (`1664` tests, `2` pre-existing `LandlockFailureModeTest` assumption-skipped, `0` failed) and `:enforcer:ktlintCheck :enforcer:checkKotlinAbi`.
- Full completion gate verified in commits `52e81d2e1` and `7e62bacf7` (`./gradlew build -PmazewallKernelLane=BASELINE` and rootful Docker `CONFINED` kernel lane passed).

---
<!-- id: issue-20261103-235912-401-use-canonical-action-ordering-throughout-installed  file: issue-20261103-235912-401-use-canonical-action-ordering-throughout-installed.md -->
