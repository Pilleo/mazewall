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
1. Write safe finished-JAR override regressions RED, host effective-environment matrix, minimal pre-refresh guard, exact copied-report counts and docs; verify10host/7native then final source-bound/full gates.

## Execution contract

Approved inline review-follow-up plan; base `b7d22e326b52ee1cc645e68d40033a312aec915e`.
Current checkout/native execution, no worktrees or separate plan/journal files.
- [x] Safe native override regressions RED->GREEN.
- [x] Five host environment tests, pre-refresh guard, docs and exact-report counts.
- [x] Complete Spring host/native suites.
- [ ] One final review and source/full gates.

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

---
<!-- id: issue-20261030-075254-853-enforce-the-spring-evaluation-listener-before-refr  file: issue-20261030-075254-853-enforce-the-spring-evaluation-listener-before-refr.md -->
