---
title: "Distinguish delta filter fragments from effective unsafe prctl authority in diagnostics"
severity: "LOW"
status: "open"
priority: low
dependencies: []
component: "enforcer"
target_modules:
  - ":enforcer"
target_files:
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/internal/seccomp/BpfFilter.kt"
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/engine/FilterInstallationPlanner.kt"
needs_kernel: true
core_lock: false
effort: "small"
autonomy: "supervised"
open_questions: false
has_side_effects: true
---

# 🟢 [Severity: LOW]: Distinguish delta filter fragments from effective unsafe prctl authority in diagnostics

**Context:**
Real thread-backed Koog qualification emits the existing SEVERE unsafe-prctl/TOCTOU warning even though the new fixture never opts into unsafe prctl and its inherited process baseline remains restrictive. Independent review traced this to `BpfFilter.kt:43-56` diagnosing the compiled fragment's flag while `FilterInstallationPlanner.kt:80-82,105-115` intentionally omits redundant ancestor protection in a delta filter. `ContainerState.kt:91-99` preserves cumulative permissions by restrictive AND; stacking does not undo the ancestor denial.

This is a diagnostic/evidence issue, not evidence of a policy bypass. The implementation is deliberately separate from the Koog adapter feature: no core/filter/planner code is changed there. Future work must own the affected core scope and preserve the existing unsafe-policy warning when effective authority is genuinely unsafe.

**Needed:**
1. Distinguish a delta fragment's permissive compilation flag from effective cumulative unsafe authority in diagnostic wording/events. Use the existing authoritative installation/planning state; do not invent attestation from an untrusted flag.
2. Cover a genuine unsafe standalone policy, inherited restrictive baseline plus delta, and genuinely unsafe cumulative authority with meaningful diagnostic assertions. Preserve real unsafe-policy warnings rather than disabling them globally.
3. Preserve BPF semantics, argument restrictions, ancestor intersection, fail-closed behavior and installation order. Run focused owning enforcer tests and any required native qualification; a diagnostic correction is not authorization to widen policy.

## Investigation
- Evidence: `.superpowers/sdd/2026-10-18-koog-thread-enforcement/task-2-evidence/green-thread-native.stderr.log:1-2` and `task-3-evidence/native-thread-info.stderr.log:1-2` retain the warning during successful actual native runs.
- Independent review: that workspace's `task-2-review.md:28,37` explains the checked planner/state/compiler path; this source is unchanged from merged master. The new adapter contains no native installer or unsafe-prctl option.
- This issue records inherited diagnostic debt only; it does not reinterpret arbitrary permission errors as authenticated kernel denials.

## Side effects
- Diagnostic-only follow-up: preserve real unsafe-policy warnings and cumulative kernel restrictions; no implementation is part of the Koog thread feature.

---

**Verification:** `./gradlew :tools:orchestrator:checkBacklog` plus the `verify_cheap` commands above (if any).

<!-- id: issue-20261018-125543  file: issue-20261018-125543-distinguish-delta-filter-fragments-from-effective-unsafe-prc.md -->
