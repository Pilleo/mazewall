---
title: "Keep measured Spring candidate stdout separate from diagnostics"
severity: "HIGH"
status: "open"
priority: high
dependencies: []
component: "integrations"
target_modules: []
target_files:
  - "demos/spring-report-api/src/test/java/demo/report/ReportHttpTest.java"
target_symbols: []
open_questions: false
---

# 🟡 [Severity: HIGH]: Keep measured Spring candidate stdout separate from diagnostics

**Context:**
PR562 review finding2 and CI41056914481: JAVA_TOOL_OPTIONS launcher stderr contaminates JSON in both native fixture preparation and Python measurement helper. Eight native cases fail before real work. Preserve the inherited environment and all process-group retirement/error behavior.

**Needed:**
1. RED regressions with actual child stderr/JAVA_TOOL_OPTIONS; isolate stdout and retain stderr in both helper paths; run real Spring native/presentation suites and Python tests, then copied acceptance.

## Execution

- [x] Real Java launcher and Python child stream regressions RED→GREEN.
- [x] Both affected helpers fixed; process-group cleanup and combined2MiB log bound retained.
- [x] CI-style inherited-option check and final source/full gates.

The native test appends a harmless property to existing JAVA_TOOL_OPTIONS only for
its owned child; no inherited option or credential variable is removed/filtered.
Measurement stdout goes to candidate JSON, stderr to measure.stderr.log. Python
owned() captures separate files, returns stdout, forwards successful diagnostics
to stderr, and retains both streams in failed-command reports. Existing ownership,
inspection and retirement errors remain visible; no process fallback or replay.
Spring11host/11native/1presentation and13Python tests pass, including retained
failed-command stderr and the unchanged combined2MiB log bound. RED logs:
`/tmp/opencode/pitch-fix-{spring,measure}-red.log`; corresponding green logs retained.

Final producer `b741304b7b3974e811445304d6f6878c3928c99e` passes full build and the
complete committed-source acceptance with inherited JAVA_TOOL_OPTIONS plus the
harmless preference option that reproduces CI. Both candidate-producing helper
paths, all11Spring native cases and3actual Spring measurement forks pass without
filtering environment or lowering gates. Shared final evidence/kit paths are in
issue-20261030-144907-661. Open pending orchestrator-managed landing.

---
<!-- id: issue-20261030-144907-939-keep-measured-spring-candidate-stdout-separate-fro  file: issue-20261030-144907-939-keep-measured-spring-candidate-stdout-separate-fro.md -->
