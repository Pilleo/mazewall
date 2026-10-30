---
title: "Enforce the Spring evaluation listener before refresh"
severity: "HIGH"
status: "open"
priority: high
dependencies: []
component: "integrations"
target_modules: []
target_files:
  - "demos/spring-report-api/src/main/java/demo/report/ReportApplication.java"
target_symbols:
  - "requireEvaluationListener"
open_questions: false
---

# 🟡 [Severity: HIGH]: Enforce the Spring evaluation listener before refresh

**Context:**
Approved inline follow-up Task1. Spring defaults can be overridden by system/environment configuration, violating exact127.0.0.1/CLI-port evaluation binding. Reject primary/management listener drift before registration/refresh without disabling normal Boot processing. No dependencies, policy widening, worktrees or plan files.

**Needed:**
1. Write safe finished-JAR override regressions RED, host effective-environment matrix, minimal pre-refresh guard, exact copied-report counts and docs; verify10host/8native (including management-zero review regression) then final source-bound/full gates.

## Execution contract

Approved inline review-follow-up plan; base `b7d22e326b52ee1cc645e68d40033a312aec915e`.
Current checkout/native execution, no worktrees or separate plan/journal files.
- [x] Safe native override regressions RED->GREEN.
- [x] Five host environment tests, pre-refresh guard, docs and exact-report counts.
- [x] Complete Spring host/native suites.
- [x] One final review and source/full gates.

Pre-flight: no shared implementation interfaces. Tasks1/3 update separate Spring/
Koog entries in the same parent acceptance file; preserve both edits. Task2 preserves
its current Python function signatures. No dependencies or native policy changes.

Task1 complete:3 actual finished-JAR regressions observed RED at successful startup/
REPORT_READY despite safe conflicting overrides, then GREEN with effective-value
validation before singleton registration/refresh. Tests never request a public bind:
the real address override is127.0.0.2; public/alternate address values are checked in
hermetic host environments. Primary and management configuration conflicts reject;
unrelated Boot processing remains active.

Verification: `test nativeTest` against the approved local597f483649f5 SDK executes
10host/7native cases with zero failures/errors/skips and strict configuration-cache
storage. Logs: `/tmp/opencode/review-followup-listener-{red,green}.log`.

## Independent review/fix pass

Final: Ruling: explicit management port0 is not a shared ephemeral main port in
Boot4.1.1; its actual ManagementPortType bytecode returns DIFFERENT. Correct the
plan's equal-port assumption: only absent or matching nonzero management ports are
admitted. Cost if wrong: operators must unset management port0 to use the single
ephemeral CLI listener, rather than accidentally opening a second listener.

The host and real finished-JAR management-zero tests both observed RED, including
two actual loopback-only Tomcat listeners before the fix. The safe regression sets
management.server.address=127.0.0.1 even on the unfixed version. GREEN now executes
10host/8native tests with zero skips/errors/failures, without resetting any timeout.
Logs: `/tmp/opencode/review-followup-management-zero-{red,native-red,green}.log`.

Final: Ruling: Boot's existing rejection of redundant management-address settings
on shared fixed ports remains upstream validation, not new supported configuration.
Cost if wrong: startup still fails closed; clear the redundant setting rather than
silently widening listeners or disabling normal Boot processing.

## Final qualification

Code producer `73ec49e83e4bc8bd5d95d99995215e1d7f50a834` passes exact independent
published-only copied acceptance in17m49s:10host/8native Spring cases, no skips,
strict cache reuse, byte-identical packaged SDK closure and fresh HTTP measurements.
`build -PincludeSpringIntegration=true` passes in5m26s (253tasks). One independent
review/fix pass completed; no second review was used. The important management-zero
finding is RED->GREEN, and existing authorization/rollback/readiness/owned-shutdown
controls remain selected.

Final logs: `/tmp/opencode/review-followup-{full-build,exact-acceptance}.log`.
Ordinary source/publication/raw receipts:
`/tmp/mazewall-consumer-bootstrap-2549915888906672369/checkout/optional-integration-evidence/`.
Fresh source/binary kits are in `build/pitch-evaluation-73ec49e83e4b/`, copied without
replacing any installation or old kit. Keep open pending separately approved landing;
remote-release/human/presentation-machine gates remain open.

---
<!-- id: issue-20261030-075254-853-enforce-the-spring-evaluation-listener-before-refr  file: issue-20261030-075254-853-enforce-the-spring-evaluation-listener-before-refr.md -->
