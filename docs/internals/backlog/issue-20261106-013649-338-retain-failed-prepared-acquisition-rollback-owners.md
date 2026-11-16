---
title: "Retain failed prepared acquisition rollback owners for explicit retry"
severity: "HIGH"
status: "open"
priority: high
dependencies: []
component: "core"
target_modules: ["enforcer"]
target_files:
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/api/execution/ManagedRuntime.kt"
  - "enforcer/src/main/kotlin/io/mazewall/landlock/PreparedLandlock.kt"
  - "enforcer/src/test/kotlin/io/mazewall/execution/PreparedCloseOwnershipTest.kt"
  - "enforcer/src/test/kotlin/io/mazewall/ArchitectureTest.kt"
target_symbols:
  - "PreparedLandlock"
open_questions: false
---

# 🟡 [Severity: HIGH]: Retain failed prepared acquisition rollback owners for explicit retry

**Context:**
Approved D2 close-disposition correction verifies retry of published owners; source analysis separately finds prepare rollback can fail before close dispatch while the locally acquired ruleset has no caller-visible owner. ManagedRuntime receives PreparedLandlock only on successful return. No runtime reproduction or new recovery protocol yet.

**Needed:**
1. Design and test explicit acquisition ownership transfer before rollback can fail, preserving exact fatal identity and keeping retained genuine tokens reachable without registry or integer reconstruction.

## Candidate verification

The operator explicitly separated and deferred acquisition rollback to this follow-up. The gap remains source analysis only: no runtime reproduction of the acquisition path and no recovery implementation. Four published-owner close controls passed in `review-owner-connected-green-static-003`; they do not qualify acquisition rollback.

Candidate gates in `b4-d2-implementation-001`: connected enforcer 268/268, platform 474/474 and profiler 32/32, with no skips or failures; static004 ratchet reported zero added warnings and no baseline growth. Rootful007 passed 8/8 and successor helper-regression-rootful001 passed 18/18; both postflight input manifests matched (1,829 checks each). Negative cases with injected effects are controlled tests, not proof of naturally occurring faults. Whole-B4 build002 was pending in the ledger; this issue remains open for controller settlement.

---
<!-- id: issue-20261106-013649-338-retain-failed-prepared-acquisition-rollback-owners  file: issue-20261106-013649-338-retain-failed-prepared-acquisition-rollback-owners.md -->

## Accepted bounded implementation and final-build evidence

The controller accepted the four-file issue 338 implementation **ACCEPT_WITH_EXPLICIT_LIMITS**: actual returned resource/shell retention, original-manager bounded cleanup, typed origin/provenance, one-attempt disposition and reporting. This does not complete original acquisition, native or Live qualification, and issue 338 remains **open**. The four implementation paths are `enforcer/src/main/kotlin/io/mazewall/enforcer/api/execution/ManagedRuntime.kt`, `enforcer/src/main/kotlin/io/mazewall/landlock/PreparedLandlock.kt`, `enforcer/src/test/kotlin/io/mazewall/execution/PreparedCloseOwnershipTest.kt`, and `enforcer/src/test/kotlin/io/mazewall/ArchitectureTest.kt`.

The preceding Candidate verification text is retained as the earlier pre-implementation snapshot; this accepted implementation and full-build evidence supersede it where it says no implementation existed.

The accepted full gate recorded 19 fresh outer Test producers, 690 suites, 4,536 cases, 45 skips, 4,491 executed cases, zero failures/errors, and `BUILD SUCCESSFUL in 34m 29s`. The final enforcer host gate recorded 1,380 cases, two skips and zero failures; the owning suite covered 15 cases without skips/failures. This evidence does not upgrade skipped cases or the native, acquisition, or Live gates.

The bounded fix retains actual returned resources and the real typed ruleset before fallible later preparation, preserves original-manager cleanup and typed origin/provenance, and distinguishes one-attempt `NotAttempted` from dispatched `Unknown`. Explicit limits remain: pre-return acquisition gaps inside `PreparedResourceSet.capture`/`PreparedPath.open`, VM/allocation failure limits, and Unknown disposition are not closed; no integer-reconstructed owner or unsafe uncertain-close retry is authorized. Issue 869's 18 daemon EBADF diagnostics and issue 752's three profiler shutdown ENOSYS occurrences remain unresolved. No native/Live readiness or activation is established.

Evidence: [accepted controller decision](../../../.superpowers/sdd/2026-11-08-tier-e-remaining-work/task-7-338-full-build-final/controller-decision.md), [implementation report](../../../.superpowers/sdd/2026-11-08-tier-e-remaining-work/task-7-338/report.md), [four-file final code review diff](../../../.superpowers/sdd/2026-11-08-tier-e-remaining-work/task-7-338/final-61-review.diff), [independent full-build review](../../../.superpowers/sdd/2026-11-08-tier-e-remaining-work/task-7-338-full-build-final/independent-review.md), [full-build report](../../../.superpowers/sdd/2026-11-08-tier-e-remaining-work/task-7-338-full-build-final/report.md), [raw outer counts](../../../.superpowers/sdd/2026-11-08-tier-e-remaining-work/task-7-338-full-build-final/outer-counts.tsv), and [derived corrected outer counts](../../../.superpowers/sdd/2026-11-08-tier-e-remaining-work/task-7-338-full-build-final/outer-counts-corrected.tsv).


## Manager-owned pre-return child and full-build gate

The controller accepted the bounded four-file manager-owned capture child as **ACCEPT_WITH_EXPLICIT_LIMITS_AND_DOCUMENTATION_DRIFT_EXCEPTION**, per the [pre-return controller decision](../../../.superpowers/sdd/2026-11-08-tier-e-remaining-work/task-7-338-pre-return-full-build-fixed1/controller-decision.md). The raw full-build gate remains **FAILED**: Gradle/container exited 0, while the host runner/wait exited 99 on source-manifest drift. The run recorded 19 outer producers, 690 suites, 4,554 cases, 45 skips, 4,509 non-skipped cases and zero failures/errors; Gradle reported 33m20s. Do not describe this as a green full gate.

The documentation-drift exception is limited to exactly two authorized Markdown paths, with modes unchanged at 0664: README changed from `ba795127defff415b289913aee15c3e0e0c9688eb7f7411ead9ee45cd90b3ab9` to `dee741ee52ebb8b28d5e5afea165552b51ae7814f107afd9bcc9e3c89461a32d`; new issue 051 has SHA-256 `c8bb52b57669b771b8e5651ff2a17b0b83888893f0e370a68608f778cbc20d6f`. Post-write links, scoped diff, ADK backlog and offline `validateBacklogIndex` checks passed. No executable drift was found.

Remaining limits are unchanged: direct resolver/strict-path acquisition, actual arena-close injection, VM/allocation failures and Unknown disposition remain open. Prewarm issue 051 remains unresolved. Cleanup diagnostics retain unknown retirement dispositions; none is classified as harmless. Issue 338 remains open and unarchived.


### Conditional full-build checkpoint — 2026-11-12

The [current full-build report](../../../.superpowers/sdd/2026-11-08-tier-e-remaining-work/task2-current-interface-preparation/issue338-full-gate-detailed-report002.md) records a successful full build with 4,672 cases, 45 skips and no failures or errors; unit instruction coverage was 80,145/97,726 (82.009905%) and combined coverage was 80,572/97,726 (82.446841%). The owning FreshJvm task used the approved 15,000 ms configuration; full-run supervisor propagation was not sampled. The ordinary five-second default, issue 051 startup cause and default reliability remain unqualified. Three shutdown socket exceptions and three suppressed directory exceptions leave issue 752 resource retirement Unknown. Issues 051, 338 and 569 remain open: pre-typed raw/token ownership, VM and arena gaps, canonical dispatcher and custody limits remain; B5 helper capture requires its next preimplementation review. This conditional checkpoint does not close B5 or the wider roadmap. Earlier pending statements describe their historical checkpoints and are superseded only as to this full-build result.


### B4 domain-owned retained support capture — 2026-11-12

The controller accepted the actual current B4 domain-owned support transaction as **ACCEPT_SCOPED_WITH_SEPARATE_RECOVERED_POSTFLIGHT**. The [tracked evidence note](../evidence/tier-e-retained-helper-capture-20261112.md) records the Gradle exit-0 result and the separately recovered postflight. The original host wrapper exit 95 remains failed because its late collector found the child-coverage directory absent; recovery does not relabel it as passed. The full run recorded 4,687 invocations, 45 skipped test invocations, zero failures/errors and coverage of 80,842/98,481 unit and 81,269/98,481 combined. The 15-second setting applied only to the owning FreshJvm task; default reliability remains unqualified. Issue 752 retirement remains Unknown.

This bounded domain route preattaches genuine typed support slots, counts already-authorized opens through preparation quiescence, and retains Unknown close failures without redispatch. It does not qualify bare component capture, raw-token/arena/VM acquisition, Live, or broader B5 custody and handoff. The existing **Needed** item remains unchecked, and issue 338 remains open. Earlier B4 D1/D2 acceptance statuses are unchanged.


### Subsequent B5 carrier handoff checkpoint — 2026-11-13

The [portable B5 evidence](../evidence/tier-e-b5-carrier-helper-capture-20261113.md) and [issue 842 checkpoint](issue-20261108-141254-842-b5-d4-bind-captured-engine-confined-helper-authori.md) record the later bounded carrier capture/handoff acceptance; this does not requalify earlier B4 runs. Raw/token, arena, VM, and bare-component frontiers remain open.
