---
title: "Report Spring command failures once without redundant catch"
severity: "LOW"
status: "open"
priority: high
dependencies: []
component: "integrations"
target_modules: []
target_files:
  - "demos/spring-report-api/src/main/java/demo/report/ReportApplication.java"
target_symbols: []
open_questions: false
---

# 🟡 [Severity: LOW]: Report Spring command failures once without redundant catch

**Context:**
PR562 review finding4: an invalid owned stdin command prints the same original exception twice because main prints and rethrows. Preserve automatic context/bootstrap/resource cleanup and original exception propagation.

**Needed:**
1. Add finished-JAR invalid-command regression RED requiring one diagnostic and actual retirement; remove redundant catch; run native/presentation suites.

## Outcome

Removed the redundant print-and-rethrow catch; normal propagation and automatic
resource/context close remain. The finished-JAR regression observes readiness,
invalid-command refusal, nonzero exit, actual retirement and one diagnostic.
Exception text is not used for application outcome classification.

[Current qualification](issue-20261029-112338-058-qualify-copied-pitch-consumers-measurements-and-ho.md) includes the native regression and presentation suite.
Open pending managed landing; failures were not swallowed or downgraded.

---
<!-- id: issue-20261030-144908-526-report-spring-command-failures-once-without-redund  file: issue-20261030-144908-526-report-spring-command-failures-once-without-redund.md -->
