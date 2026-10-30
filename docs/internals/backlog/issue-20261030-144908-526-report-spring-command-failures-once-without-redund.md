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

## Execution

- [x] Finished-JAR invalid-command test observed2diagnostics RED, then1 GREEN.
- [x] Redundant catch removed; normal exception propagation and resource close preserved.
- [ ] Final copied/full gates.

The actual server must become ready, refuse the invalid stdin command, return
nonzero, report actual worker retirement and emit its original diagnostic once.
No exception text is parsed for application outcomes; the known test-only message
counts duplicate rendering only. Spring11host/11native/1presentation cases pass;
logs `/tmp/opencode/pitch-fix-spring-{red,green}.log`.

---
<!-- id: issue-20261030-144908-526-report-spring-command-failures-once-without-redund  file: issue-20261030-144908-526-report-spring-command-failures-once-without-redund.md -->
