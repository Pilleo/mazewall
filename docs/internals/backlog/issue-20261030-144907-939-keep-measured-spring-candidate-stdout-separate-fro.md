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

## Outcome

Candidate JSON uses stdout only; launcher diagnostics remain on stderr and in
failed-command evidence. Actual Java-launcher regressions preserve inherited
JAVA_TOOL_OPTIONS and verify valid candidates, original failures and retirement.
[Current qualification](issue-20261029-112338-058-qualify-copied-pitch-consumers-measurements-and-ho.md) includes Spring11host/11native/1presentation.

The separate Python pitch helper and its tests were subsequently removed by
[operator direction](issue-20261030-225329-595-replace-pitch-only-measurements-with-optional-glob.md), not retained as another benchmark path.
Open pending managed landing; no environment filtering or relaxed gates.

---
<!-- id: issue-20261030-144907-939-keep-measured-spring-candidate-stdout-separate-fro  file: issue-20261030-144907-939-keep-measured-spring-candidate-stdout-separate-fro.md -->
