---
title: "Preserve static syscall denials when adding scoping notifications"
severity: "HIGH"
status: "open"
priority: high
dependencies: []
component: "enforcer"
target_modules:
  - "enforcer"
target_files:
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/api/ContainedExecutors.kt"
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/internal/seccomp/BpfFilter.kt"
  - "enforcer/src/test/kotlin/io/mazewall/seccomp/BpfFilterTest.kt"
  - "enforcer/src/test/kotlin/io/mazewall/enforcer/ContainedExecutorsStateTest.kt"
target_symbols:
  - "augmentForScoping"
  - "BpfFilter"
open_questions: false
---

# 🟡 [Severity: HIGH]: Preserve static syscall denials when adding scoping notifications

**Context:**
B5 planning and independent review found that handler-key notification augmentation unconditionally replaces explicit/default denials; supervised-exec spawn augmentation also upgrades absent entries without checking the default action. Current source observations require focused semantic regressions; no executed exploit is claimed.

**Needed:**
1. Establish compiling actual policy/BPF regressions for explicit and default denied syscall routes before correcting the shared augmentation predicates; preserve argument-sensitive JVM floor and legacy allowed-route behavior.
2. Resolve the original effective action before adding notifications: scoping may narrow an allowed route, but must not replace an explicit or default denial. Preserve already supervised routes and the argument-sensitive `clone(CLONE_THREAD)` allowance without granting process-spawn authority.
3. Cover handler-selected explicit/default-denied routes and supervised-exec spawn routes under both default-allow and default-deny policies, including explicit spawn denials and already-notified routes. Assert the actual resulting actions/BPF behavior rather than a copied decision function.
4. Run focused enforcer regressions, the enforcer suite and the ordinary completion build. Keep the separate live-stack B5 installation work behind its existing readiness/qualification gates.

## Source evidence and scope

- [ContainedExecutors.kt](../../../enforcer/src/main/kotlin/io/mazewall/enforcer/api/ContainedExecutors.kt):307–313 assigns `ACT_NOTIFY` to every handler-selected syscall without checking its prior explicit/default action.
- [BpfFilter.kt](../../../enforcer/src/main/kotlin/io/mazewall/enforcer/internal/seccomp/BpfFilter.kt):151–157 upgrades an absent spawn entry to `ACT_NOTIFY` when exec is supervised, even if the policy default denies that entry. Explicit non-ALLOW entries are already preserved by that branch.
- [PolicyDefinition.kt](../../../enforcer/src/main/kotlin/io/mazewall/enforcer/api/PolicyDefinition.kt):48–51 exposes the existing effective-action rule, `syscallActions[syscall] ?: defaultAction`; an absent map entry is not inherently permission.
- The [live-stack design](../designs/enforcer/live-stack-scoping.md) requires static policy and live authorization to intersect. The proposed B5 Live branch must respect that rule independently; its future implementation does not repair these existing augmentation paths by itself.

Discovery and independent source confirmation are retained in
`.superpowers/sdd/2026-11-03-tier-e-improvement-roadmap/b5-owned-preparation-plan-002-review.md`
and the controlling ledger. This intake establishes source-level overwrite paths,
not a demonstrated end-to-end bypass: downstream supervisor decisions still need
connected regression coverage. No product repair or runtime experiment has been
performed for this issue. Preserve the JVM floor, linear BPF design, fail-closed
error handling and existing public API.

---
<!-- id: issue-20261105-055732-509-preserve-static-syscall-denials-when-adding-scopin  file: issue-20261105-055732-509-preserve-static-syscall-denials-when-adding-scopin.md -->

## 509a accepted bounded result

The controller accepted the bounded 509a definite-static-denial correction and its approved test fixtures as **ACCEPT_WITH_EXPLICIT_LIMITS**. This is not acceptance of all issue 509: issue 509 remains **open** for 509b LOG/TRACE composition and architecture/filter-plan timing. The successor full build recorded 19 outer producers, 690 suites, 4,541 cases, 45 skips, 4,496 executed, zero failures/errors, and `BUILD SUCCESSFUL in 33m 31s`. All four failures from the retained first full attempt passed after the narrowly approved fixture corrections; preserve that first failure and its evidence without reclassification.

Static focused work supplements this full-gate evidence; cached static tasks are not fresh analysis. No native Gradle build task was observed. Twenty daemon EBADF diagnostics (issue 869) and three profiler shutdown ENOSYS occurrences (issue 752) remain unresolved. The separate host-cmdline receipt is absent. This does not qualify native/Live activation or close broader Tier E obligations.

Accepted evidence: [controller decision](../../../.superpowers/sdd/2026-11-08-tier-e-remaining-work/task-7-509-full-build-fixed1/controller-decision.md), [successor full-build report](../../../.superpowers/sdd/2026-11-08-tier-e-remaining-work/task-7-509-full-build-fixed1/report.md), [independent terminal audit](../../../.superpowers/sdd/2026-11-08-tier-e-remaining-work/task-7-509-full-build-fixed1/independent-review.md), [general evidence checksum manifest](../../../.superpowers/sdd/2026-11-08-tier-e-remaining-work/task-7-509-full-build-fixed1/evidence.sha256), and [XML checksum manifest](../../../.superpowers/sdd/2026-11-08-tier-e-remaining-work/task-7-509-full-build-fixed1/xml-evidence.sha256). The original failed full packet and the wrong Gradle-home incident remain retained.

## Selected-handler refusal accepted with explicit limits

The controller accepted this bounded selected Legacy LOG/TRACE refusal child as **ACCEPT_WITH_EXPLICIT_LIMITS**, including reviewed original-policy identity and immutable-dispatch handoff. Supported installations refuse noncomposable selected LOG/TRACE before installation effects; unsupported-platform fallback keeps its existing precedence. Permanent supervisor reuse retains original reference identity and requires the same captured handler keys and references. This does not accept automatic-spawn composition or all of issue 509. Issue 509 remains **open** for that spawn-composition work and the architecture/filter-delta questions.

The full build completed in 33m50s with 19 outer producers, 690 suites, 4,545 cases, 45 skips, 4,500 non-skipped cases and zero failures/errors. All four earlier supervisor regressions passed. Enforcer static tasks in that run were cached; focused execution and its prehash limitation are recorded separately. No native Gradle task was observed. This does not qualify native/Live behavior or skipped suites. Prior failed runs and the recorded missing-receipt/overwritten-host-XML limitations remain historical evidence.

Cleanup observations remain unqualified: 20 daemon `NativeCloseFailure` EBADF lines, a separate eventfd completion-wakeup EBADF at retained log line 12727, and three profiler shutdown occurrences all have `UNKNOWN` retirement disposition in the [derived literal-match table](../../../.superpowers/sdd/2026-11-08-tier-e-remaining-work/task-7-509-selected-handler-full-build-fixed2/diagnostic-occurrences-derived.tsv). The [addendum hash manifest](../../../.superpowers/sdd/2026-11-08-tier-e-remaining-work/task-7-509-selected-handler-full-build-fixed2/diagnostic-occurrences-addendum.sha256) binds that table and its report correction. No occurrence is classified as harmless or combined with another event class.

Evidence: [controller decision](../../../.superpowers/sdd/2026-11-08-tier-e-remaining-work/task-7-509-selected-handler-full-build-fixed2/controller-decision.md), [terminal report](../../../.superpowers/sdd/2026-11-08-tier-e-remaining-work/task-7-509-selected-handler-full-build-fixed2/report.md), [independent terminal audit](../../../.superpowers/sdd/2026-11-08-tier-e-remaining-work/task-7-509-selected-handler-full-build-fixed2/independent-review.md), [retained raw build log](../../../.superpowers/sdd/2026-11-08-tier-e-remaining-work/task-7-509-selected-handler-full-build-fixed2/build.log), and [evidence checksum manifest](../../../.superpowers/sdd/2026-11-08-tier-e-remaining-work/task-7-509-selected-handler-full-build-fixed2/evidence.sha256). The earlier failed attempts and their original evidence remain unchanged. No archive follows from this bounded acceptance.

Controller ruling (spawn composition child): enforce a conservative pre-effect refusal when supervised exec would combine with an inherited LOG/TRACE spawn action, including the documented valid-case refusals for nonexistent architecture routes, no-filter/ALLOW-default deltas, and exec notification omitted by a delta. Preserve profiling behavior and late partial-install handling. This issue remains open pending implementation, independent review and the full gate; this ruling does not establish runtime or native qualification.

## Automatic-spawn composition accepted with explicit limits

The controller accepted the four-file conservative pre-effect refusal and actual-architecture compiler guard as **ACCEPT_WITH_EXPLICIT_LIMITS**. The same immutable augmented action map is checked and supplied to planning; late actual-architecture compiler refusal keeps ordinary partial-install handling. Profiling, JVM floor, explicit spawn actions and ordinary defaults retain their existing behavior. This is not an exact planned-filter or captured-architecture handoff.

Three conservative compatibility costs are explicitly accepted, without claiming exact qualification: a valid AARCH64 request may be refused when only unsupported fork/vfork is missing; a request may yield an ALLOW-default delta or no filter; and an explicit exec notification may be absent from the eventual delta. Issue 509 remains **open** for broader intersection and exact architecture/filter-timing follow-up.

The full build completed in 33m41s with 19 producers, 690 suites, 4,550 cases, 45 skips, 4,505 non-skipped, and zero failures/errors. Static tasks were cached in the full gate; focused execution and its prehash limitation remain recorded separately. No native Gradle task was observed. This does not qualify native/Live behavior or skipped suites. All prior failures and evidence remain preserved.

Literal diagnostics remain separate with causality and individual retirement unestablished: 20 socket `NativeCloseFailure` EBADF lines, one eventfd completion-wakeup EBADF, five non-EBADF `NativeCloseFailure` lines, three profiler shutdown occurrences, and one Landlock ENOSYS warning. These are not merged into shared causes or described as harmless. Issues 752/869, broader Tier E, original 20+20/72, native and Live qualification remain open.

Evidence: [spawn-composition controller decision](../../../.superpowers/sdd/2026-11-08-tier-e-remaining-work/task-7-509-spawn-composition-full-build/controller-decision.md), [full-build report](../../../.superpowers/sdd/2026-11-08-tier-e-remaining-work/task-7-509-spawn-composition-full-build/report.md), and [independent terminal audit](../../../.superpowers/sdd/2026-11-08-tier-e-remaining-work/task-7-509-spawn-composition-full-build/independent-review.md). No archive follows from this bounded acceptance.
